package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy

/** What starts the RX, what it runs under, and what the capture SRAM keeps. */
class PhyTestCaptureSpec extends AnyFunSpec with ChiselSim {
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  val numLanes = 4
  val wordMask = (BigInt(1) << Phy.SerdesRatio) - 1

  def word(n: Int, lane: Int): BigInt =
    (BigInt(n + 1) * BigInt("9e3779b9", 16) + lane) & wordMask

  /** Drives `n` packets straight into the RX port, with `valid` on the valid
    * lane of every one.
    */
  def drive(c: PhyTest, n: Int, valid: BigInt): Unit = {
    for (p <- 0 until n) {
      for (lane <- 0 until numLanes) {
        c.io.rx.bits.data(lane).poke(word(p, lane).U)
      }
      c.io.rx.bits.valid.poke(valid.U)
      c.io.rx.bits.track.poke(0.U)
      c.io.rx.valid.poke(true.B)
      c.clock.step()
    }
    c.io.rx.valid.poke(false.B)
    c.clock.step(PhyTestRegLoopback.outputDelay)
  }

  def setup(c: PhyTest, mainbandMode: BandMode.Type): Unit = {
    c.io.regs.testTarget.poke(TestTarget.mainband)
    c.io.regs.mainbandMode.poke(mainbandMode)
    c.io.regs.rxValidLaneSel.poke(Phy.defaultValidLaneSel(numLanes).U)
    c.io.regs.rxDataMode.poke(DataMode.infinite)
    c.io.rx.valid.poke(false.B)
    c.io.regs.rxFsmRst.poke(true.B)
    c.clock.step()
    c.io.regs.rxFsmRst.poke(false.B)
    c.clock.step(2)
  }

  def packetsReceived(c: PhyTest): BigInt =
    c.io.regs.rxPacketsReceived.peek().litValue

  describe("PhyTest RX immediate start") {
    it("should record from the first word with nothing on valid") {
      val n = 5
      simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
        setup(c, BandMode.manual)
        // The original start never fires without a one on valid.
        drive(c, n, valid = 0)
        assert(packetsReceived(c) == 0)

        c.io.regs.rxStartMode.poke(RxStartMode.immediate)
        drive(c, n, valid = 0)
        assert(packetsReceived(c) == n)
        for (p <- 0 until n; lane <- 0 until numLanes) {
          c.io.regs.rxDataLane.poke(lane.U)
          c.io.regs.rxDataOffset.poke(p.U)
          c.clock.step()
          withClue(s"packet $p lane $lane: ") {
            assert(c.io.regs.rxDataChunk.peek().litValue == word(p, lane))
          }
        }
      }
    }
  }

  describe("PhyTest RX monitor mode") {
    it("should keep the RX running while TileLink drives the mainband") {
      val n = 6
      simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
        setup(c, BandMode.tl)
        drive(c, n, valid = wordMask)
        assert(packetsReceived(c) == 0, "held, as originally")

        c.io.regs.rxMonitor.poke(true.B)
        drive(c, n, valid = wordMask)
        assert(packetsReceived(c) == n)
        assert(
          c.io.regs.txTestState.peek().litValue == TxTestState.idle.litValue,
          "the TX stays held"
        )
      }
    }
  }

  describe("PhyTest error-triggered capture") {
    it("should keep a window around the first error and then stop") {
      import PhyTestRegLoopback._
      val depth = sramPackets
      val post = 4
      val (lane, twin) = (1, 0)
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        // Two lanes carrying the same stream, so one shows what the other
        // should have received.
        def sameSeed(l: Int): BigInt = seed(if (l == lane) twin else l)
        setupLfsr(c, txSeed = sameSeed, rxSeed = sameSeed)
        c.io.regs.rxCaptureMode.poke(CaptureMode.error)
        c.io.regs.rxCapturePostTrigger.poke(post.U)
        c.io.regs.rxTriggerLaneMask.poke(
          ((1 << PhyTest.numTestLanes(numLanes)) - 1).U
        )
        strobe(c, c.io.regs.txExecute)
        c.clock.step(2 * depth)
        assert(!c.io.regs.rxCaptureTriggered.peek().litToBoolean)

        c.io.regs.txInjectLane.poke(lane.U)
        strobe(c, c.io.regs.txInjectError)
        // Long enough to overwrite every entry, were the capture still open.
        c.clock.step(2 * depth)
        assert(c.io.regs.rxCaptureTriggered.peek().litToBoolean)
        val trigger = c.io.regs.rxCaptureTriggerPacket.peek().litValue.toInt
        assert(trigger >= 2 * depth)

        val twinWords = (0 until depth).map(captured(c, twin, _))
        val laneWords = (0 until depth).map(captured(c, lane, _))
        for (o <- 0 until depth) {
          withClue(s"offset $o: ") {
            val expected = if (o == trigger % depth) BigInt(1) else BigInt(0)
            assert((twinWords(o) ^ laneWords(o)) == expected)
          }
        }
        // Unrolled from the oldest entry, the SRAM holds one unbroken run of
        // the pattern ending `post` packets after the trigger, which pins
        // where the writes stopped.
        val oldest = (trigger + post + 1) % depth
        val unrolled = (0 until depth).map(i => twinWords((oldest + i) % depth))
        assert(followsLfsr(unrolled))
      }
    }
  }

  describe("PhyTest capture start") {
    import PhyTestRegLoopback._
    val depth = sramPackets
    val allLanes = ((1 << PhyTest.numTestLanes(numLanes)) - 1).U

    it("should record from packet rxCaptureStart on") {
      // Past the end of what the SRAM would otherwise keep.
      val start = 3 * depth + 5
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.rxCaptureStart.poke(start.U)
        strobe(c, c.io.regs.txExecute)
        // The RX frames on the TX's packet boundaries, so the `n`th data
        // packet sent is packet `n` received.
        val sent = (0 until start + 2 * depth)
          .map { _ =>
            val s = (
              c.io.tx.valid.peek().litValue,
              (0 until numLanes).map(c.io.tx.data(_).peek().litValue)
            )
            c.clock.step()
            s
          }
          .collect { case (valid, data) if valid != 0 => data }
        c.clock.step(outputDelay)
        for (o <- 0 until depth; lane <- 0 until numLanes) {
          withClue(s"entry $o lane $lane: ") {
            assert(captured(c, lane, o) == sent(start + o)(lane))
          }
        }
      }
    }

    it("should not trigger on an error before rxCaptureStart") {
      val start = 4 * depth
      val post = 4
      val (lane, twin) = (1, 0)
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        def sameSeed(l: Int): BigInt = seed(if (l == lane) twin else l)
        setupLfsr(c, txSeed = sameSeed, rxSeed = sameSeed)
        c.io.regs.rxCaptureMode.poke(CaptureMode.error)
        c.io.regs.rxCaptureStart.poke(start.U)
        c.io.regs.rxCapturePostTrigger.poke(post.U)
        c.io.regs.rxTriggerLaneMask.poke(allLanes)
        c.io.regs.txInjectLane.poke(lane.U)
        strobe(c, c.io.regs.txExecute)
        c.clock.step(depth)

        strobe(c, c.io.regs.txInjectError)
        c.clock.step(depth)
        assert(c.io.regs.rxPacketsReceived.peek().litValue < start)
        assert(errors(c, PhyTest.NominalFraming)(lane) == 1, "it did land")
        assert(!c.io.regs.rxCaptureTriggered.peek().litToBoolean)

        // Far enough past the start to have written every entry.
        c.clock.step(start)
        strobe(c, c.io.regs.txInjectError)
        c.clock.step(2 * depth)
        assert(c.io.regs.rxCaptureTriggered.peek().litToBoolean)
        val trigger = c.io.regs.rxCaptureTriggerPacket.peek().litValue.toInt
        assert(trigger >= start + depth)
        for (o <- 0 until depth) {
          withClue(s"entry $o: ") {
            val expected =
              if (o == (trigger - start) % depth) BigInt(1) else BigInt(0)
            assert((captured(c, twin, o) ^ captured(c, lane, o)) == expected)
          }
        }
      }
    }
  }
}
