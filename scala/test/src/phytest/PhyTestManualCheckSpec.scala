package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy

/** Scoring a manual pattern against this die's own TX pattern SRAM. */
class PhyTestManualCheckSpec extends AnyFunSpec with ChiselSim {
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  val numLanes = 4
  val numTestLanes = PhyTest.numTestLanes(numLanes)
  val validLane = PhyTest.validLane(numLanes)
  val trackLane = PhyTest.trackLane(numLanes)
  val loopbackLane = PhyTest.loopbackLane(numLanes)
  // `PhyTest`'s default `bufferDepthPerLane`.
  val depth = 32
  val period = 5
  val wordMask = (BigInt(1) << Phy.SerdesRatio) - 1
  val validWord = BigInt("0f0f0f0f", 16)

  /** The pattern: a word per lane per row, with the framing waveform on valid.
    * A mainband run reads the loopback lane as zero, so its slot is too.
    */
  def row(r: Int): Seq[BigInt] = (0 until numTestLanes).map { lane =>
    if (lane == validLane) validWord
    else if (lane == loopbackLane) BigInt(0)
    else (BigInt(r + 1) * BigInt("9e3779b9", 16) + 7 * lane) & wordMask
  }

  /** Writes `period` rows of `rowOf` into the TX pattern SRAM and sets the
    * period.
    */
  def load(c: PhyTest, rowOf: Int => Seq[BigInt] = row): Unit = {
    for (r <- 0 until period; group <- 0 until (numTestLanes + 3) / 4) {
      val words = (0 until 4).map { i =>
        val lane = 4 * group + i
        if (lane < numTestLanes) rowOf(r)(lane) else BigInt(0)
      }
      c.io.regs.txDataLaneGroup.poke(group.U)
      c.io.regs.txDataOffset.poke(r.U)
      c.io.regs.txDataChunkIn.bits.poke(
        words.zipWithIndex.map { case (w, i) => w << (32 * i) }.sum.U
      )
      c.io.regs.txDataChunkIn.valid.poke(true.B)
      c.clock.step()
    }
    c.io.regs.txDataChunkIn.valid.poke(false.B)
    c.io.regs.txManualRepeatPeriod.poke(period.U)
  }

  def setup(c: PhyTest, rowOf: Int => Seq[BigInt] = row): Unit = {
    c.io.regs.testTarget.poke(TestTarget.mainband)
    c.io.regs.mainbandMode.poke(BandMode.manual)
    c.io.regs.rxValidLaneSel.poke(Phy.defaultValidLaneSel(numLanes).U)
    c.io.regs.rxDataMode.poke(DataMode.infinite)
    c.io.regs.rxManualCheck.poke(true.B)
    c.io.rx.valid.poke(false.B)
    load(c, rowOf)
    c.io.regs.rxFsmRst.poke(true.B)
    c.clock.step()
    c.io.regs.rxFsmRst.poke(false.B)
    c.clock.step(2)
  }

  /** Sends packets `from until to` of the pattern, with `flip(packet, lane)`
    * XORed onto each of the data, valid, and track lanes, and `moved` copying
    * valid onto that data lane as well.
    */
  def drive(
      c: PhyTest,
      from: Int,
      to: Int,
      flip: (Int, Int) => BigInt = (_, _) => 0,
      moved: Option[Int] = None
  ): Unit = {
    for (p <- from until to) {
      def word(lane: Int): BigInt = row(p % period)(lane) ^ flip(p, lane)
      for (lane <- 0 until numLanes) {
        val w = if (moved.contains(lane)) word(validLane) else word(lane)
        c.io.rx.bits.data(lane).poke(w.U)
      }
      c.io.rx.bits.valid.poke(word(validLane).U)
      c.io.rx.bits.track.poke(word(trackLane).U)
      c.io.rx.valid.poke(true.B)
      c.clock.step()
    }
    c.io.rx.valid.poke(false.B)
  }

  /** The counts and the packet count sit behind a couple of registers, and
    * the manual check's a cycle more.
    */
  def settle(c: PhyTest): Unit =
    c.clock.step(PhyTestRegLoopback.outputDelay + 1)

  def errors(c: PhyTest): Seq[BigInt] =
    c.io.regs.rxBitErrors.map(_.peek().litValue)

  def packetsReceived(c: PhyTest): BigInt =
    c.io.regs.rxPacketsReceived.peek().litValue

  describe("PhyTest manual pattern check") {
    it("should count each lane's errors against its slot of the pattern") {
      val n = 23
      // Packet, lane, and the bits flipped.
      val flips = Map(
        (3, 0) -> BigInt(1),
        (7, 2) -> BigInt("e0", 16),
        (12, validLane) -> (BigInt(1) << 31),
        (20, trackLane) -> BigInt("30000", 16)
      )
      simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
        setup(c)
        drive(c, 0, n, (p, l) => flips.getOrElse((p, l), BigInt(0)))
        settle(c)
        assert(packetsReceived(c) == n)
        val expected = (0 until numTestLanes).map { lane =>
          flips.collect { case ((_, l), bits) if l == lane => bits.bitCount }.sum
        }
        assert(errors(c) == expected.map(BigInt(_)))
        for (counts <- Seq(c.io.regs.rxBitErrorsEarly, c.io.regs.rxBitErrorsLate)) {
          assert(counts.map(_.peek().litValue).forall(_ == 0))
        }
      }
    }

    it("should check a lane valid was moved onto against its own slot") {
      val moved = 1
      simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
        // The partner's pattern, with valid's waveform where valid now goes.
        setup(c, r => row(r).updated(moved, validWord))
        c.io.regs.rxValidLaneSel.poke(moved.U)
        drive(c, 0, 12, moved = Some(moved))
        settle(c)
        assert(packetsReceived(c) == 12)
        assert(errors(c).forall(_ == 0))
      }
    }

    it("should keep checking while this die's TX runs an LFSR pattern") {
      simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
        setup(c)
        c.io.tx.ready.poke(true.B)
        c.io.regs.txTestMode.poke(TxTestMode.lfsr)
        c.io.regs.txDataMode.poke(DataMode.infinite)
        c.io.regs.txExecute.poke(true.B)
        c.clock.step()
        c.io.regs.txExecute.poke(false.B)
        drive(c, 0, 12, (p, l) => if (p == 9 && l == 3) 1 else 0)
        settle(c)
        assert(
          c.io.regs.txTestState.peek().litValue == TxTestState.run.litValue
        )
        assert(errors(c) == Seq(0, 0, 0, 1, 0, 0, 0).map(BigInt(_)))
      }
    }

    it("should pause the counts on the packets the packet count covers") {
      simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
        setup(c)
        // One error in every packet, so the two counts should always agree.
        val everyPacket: (Int, Int) => BigInt =
          (_, l) => if (l == 0) 1 else 0
        drive(c, 0, 10, everyPacket)
        c.io.regs.rxPauseCounters.poke(true.B)
        drive(c, 10, 20, everyPacket)
        val paused = packetsReceived(c)
        assert(paused > 0 && paused < 20)
        assert(errors(c).head == paused)
        c.io.regs.rxPauseCounters.poke(false.B)
        settle(c)
        assert(packetsReceived(c) == 20)
        assert(errors(c).head == 20)
      }
    }

    for (post <- Seq(0, 3)) {
      it(s"should trigger the capture on the packet in error, keeping $post after") {
        val (trigger, lane, bit) = (40, 2, BigInt(1) << 9)
        simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
          setup(c)
          c.io.regs.rxCaptureMode.poke(CaptureMode.error)
          c.io.regs.rxCapturePostTrigger.poke(post.U)
          c.io.regs.rxTriggerLaneMask.poke(((1 << numTestLanes) - 1).U)
          drive(c, 0, trigger + 20, (p, l) => if (p == trigger && l == lane) bit else 0)
          settle(c)
          assert(c.io.regs.rxCaptureTriggered.peek().litToBoolean)
          assert(
            c.io.regs.rxCaptureTriggerPacket.peek().litValue == trigger
          )
          def captured(offset: Int): BigInt = {
            c.io.regs.rxDataLane.poke(lane.U)
            c.io.regs.rxDataOffset.poke(offset.U)
            c.clock.step()
            c.io.regs.rxDataChunk.peek().litValue
          }
          // The packets up to `post` after the trigger, and the rest left
          // from the lap before.
          for (p <- trigger - depth + post + 1 to trigger + post) {
            withClue(s"packet $p: ") {
              val sent = row(p % period)(lane) ^ (if (p == trigger) bit else 0)
              assert(captured(p % depth) == sent)
            }
          }
        }
      }
    }
  }
}
