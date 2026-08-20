package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.phy.Phy

// One received packet: `Phy.SerdesRatio` bits per data lane, plus the valid and
// track lanes. The loopback lane is not wired up yet and always reads zero.
case class RxPacket(data: Seq[BigInt], valid: BigInt, track: BigInt) {
  // Lane order the RX buffers in, which is the order the signature folds them.
  def laneWords: Seq[BigInt] = data ++ Seq(valid, track, BigInt(0))
}

// Feeds packets straight into the RX port, so the signature can be checked
// against `PhyTest.signatureNext` without involving the TX FSM at all.
class PhyTestSignatureSpec extends AnyFunSpec with ChiselSim {
  val numLanes = 4
  val wordMask = (BigInt(1) << Phy.SerdesRatio) - 1
  // Recording latches onto the lowest set bit of the first valid word it sees, so
  // a valid word with bit 0 set puts the RX exactly on a packet boundary and
  // every following `io.rx.bits` is one whole packet.
  val alignedValid = wordMask
  val trackPattern = BigInt("55555555", 16)
  // `rxSignature` and `rxPacketsReceived` are two registers behind the RX
  // datapath, plus a cycle of slack.
  val outputDelay = 3

  def packet(seed: Int): RxPacket = RxPacket(
    data = (0 until numLanes).map(lane =>
      (BigInt(seed) * BigInt("9e3779b9", 16) + BigInt(lane)) & wordMask
    ),
    valid = alignedValid,
    track = trackPattern
  )

  // Every lane carries the same word, the way a manual pattern written with one
  // value per offset does.
  def uniformPacket(seed: Int, stuck: Set[Int] = Set.empty): RxPacket = {
    val word = (BigInt(seed) * BigInt("9e3779b9", 16)) & wordMask
    RxPacket(
      data =
        (0 until numLanes).map(lane => if (stuck(lane)) BigInt(0) else word),
      valid = alignedValid,
      track = trackPattern
    )
  }

  // The signature the RTL should hold after folding `packets` in order.
  def expected(packets: Seq[RxPacket]): BigInt =
    packets.foldLeft(BigInt(0))((sig, p) =>
      PhyTest.signatureNext(sig, p.laneWords)
    )

  def setup(c: PhyTest): Unit = {
    c.io.regs.testTarget.poke(TestTarget.mainband)
    c.io.regs.rxDataMode.poke(DataMode.infinite)
    c.io.regs.rxFsmRst.poke(false.B)
    c.io.regs.rxPauseCounters.poke(false.B)
    c.io.regs.txFsmRst.poke(false.B)
    c.io.regs.txExecute.poke(false.B)
    c.io.regs.txDataChunkIn.valid.poke(false.B)
    c.io.rx.valid.poke(false.B)
    c.clock.step()
  }

  // Clears the signature and the RX alignment so the next run starts fresh.
  def resetRx(c: PhyTest): Unit = {
    c.io.regs.rxFsmRst.poke(true.B)
    c.clock.step()
    c.io.regs.rxFsmRst.poke(false.B)
    c.clock.step(outputDelay)
  }

  // Drives one packet per cycle. The RX accepts unconditionally, so each cycle
  // with `rx.valid` high is exactly one packet folded into the signature.
  def drive(c: PhyTest, packets: Seq[RxPacket]): Unit = {
    for (p <- packets) {
      assert(p.data.length == numLanes)
      for (lane <- 0 until numLanes) {
        c.io.rx.bits.data(lane).poke(p.data(lane).U)
      }
      c.io.rx.bits.valid.poke(p.valid.U)
      c.io.rx.bits.track.poke(p.track.U)
      c.io.rx.valid.poke(true.B)
      c.clock.step()
    }
    c.io.rx.valid.poke(false.B)
  }

  def signature(c: PhyTest): BigInt = c.io.regs.rxSignature.peek().litValue

  def packetsReceived(c: PhyTest): BigInt =
    c.io.regs.rxPacketsReceived.peek().litValue

  // Resets the RX, runs `packets` through it, and returns the signature.
  def run(c: PhyTest, packets: Seq[RxPacket]): BigInt = {
    resetRx(c)
    assert(signature(c) == 0, "signature should clear on rxFsmRst")
    assert(packetsReceived(c) == 0, "packet count should clear on rxFsmRst")
    drive(c, packets)
    c.clock.step(outputDelay)
    signature(c)
  }

  describe("PhyTest RX signature") {
    it("should match the software model packet by packet") {
      val packets = (1 to 20).map(packet)
      simulate(new PhyTest(numLanes = numLanes)) { c =>
        setup(c)
        resetRx(c)
        // Check after every packet, so a divergence points at the packet that
        // caused it rather than just at the end of the run.
        for (n <- 1 to packets.length) {
          drive(c, Seq(packets(n - 1)))
          c.clock.step(outputDelay)
          withClue(s"after $n packet(s): ") {
            assert(signature(c) == expected(packets.take(n)))
            assert(packetsReceived(c) == n)
          }
        }
      }
    }

    it("should keep folding past the end of the capture SRAM") {
      // The capture SRAM only holds `2^(bufferDepthPerLane - 5)` packets; the
      // signature has to cover the packets that arrive after it fills up.
      val capacity = 1 << (10 - 5)
      val packets = (1 to capacity + 8).map(packet)
      simulate(new PhyTest(numLanes = numLanes)) { c =>
        setup(c)
        assert(run(c, packets) == expected(packets))
        assert(packetsReceived(c) == packets.length)
      }
    }

    it("should hold the signature while the counters are paused") {
      val packets = (1 to 6).map(packet)
      val duringPause = (100 to 105).map(packet)
      simulate(new PhyTest(numLanes = numLanes)) { c =>
        setup(c)
        val paused = run(c, packets)
        val pausedCount = packetsReceived(c)
        assert(paused == expected(packets))
        assert(pausedCount == packets.length)

        c.io.regs.rxPauseCounters.poke(true.B)
        c.clock.step()
        drive(c, duringPause)
        c.clock.step(outputDelay)
        // Both outputs have to freeze, or a signature cannot be matched up with
        // the number of packets it covers.
        assert(signature(c) == paused)
        assert(packetsReceived(c) == pausedCount)

        // Unpausing has to expose everything received during the pause.
        c.io.regs.rxPauseCounters.poke(false.B)
        c.clock.step(outputDelay)
        assert(signature(c) == expected(packets ++ duringPause))
        assert(packetsReceived(c) == packets.length + duringPause.length)
      }
    }

    it("should catch a single bit flip on any lane") {
      val packets = (1 to 8).map(packet)
      // Flip one bit of one packet on each lane in turn, including valid and
      // track, and check the signature moves every time.
      val flipped = for (lane <- 0 until numLanes + 2) yield {
        val bit = (lane * 7) % Phy.SerdesRatio
        val mutated = packets.zipWithIndex.map { case (p, i) =>
          if (i != 3) p
          else if (lane < numLanes)
            p.copy(data =
              p.data.updated(lane, p.data(lane) ^ (BigInt(1) << bit))
            )
          else if (lane == numLanes)
            p.copy(valid = p.valid ^ (BigInt(1) << bit))
          else p.copy(track = p.track ^ (BigInt(1) << bit))
        }
        (s"lane $lane bit $bit", mutated)
      }

      simulate(new PhyTest(numLanes = numLanes)) { c =>
        setup(c)
        val golden = run(c, packets)
        assert(golden == expected(packets))
        for ((clue, mutated) <- flipped) {
          withClue(s"$clue: ") {
            assert(run(c, mutated) != golden)
          }
        }
      }
    }

    it("should not cancel identical failures on two lanes") {
      // A plain XOR fold would cancel a pair of identical lane failures when
      // every lane carries the same word; the per-lane rotation is what stops
      // that.
      val uniform = (1 to 8).map(seed => uniformPacket(seed))
      val stuckSets = Seq(Set(0), Set(0, 1), Set(0, 1, 2), Set(1, 2))

      simulate(new PhyTest(numLanes = numLanes)) { c =>
        setup(c)
        val golden = run(c, uniform)
        assert(golden == expected(uniform))
        for (stuck <- stuckSets) {
          // The named lanes stick at zero for one packet in the middle.
          val mutated = uniform.zipWithIndex.map { case (p, i) =>
            if (i != 2) p else uniformPacket(i + 1, stuck)
          }
          withClue(s"lanes ${stuck.toSeq.sorted.mkString(",")} stuck at 0: ") {
            assert(run(c, mutated) != golden)
          }
        }
      }
    }
  }
}
