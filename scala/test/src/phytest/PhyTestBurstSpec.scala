package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy

/** Bursts, clock gating, and the track clock: the TX's packet-by-packet shape,
  * and the RX's handling of it.
  */
class PhyTestBurstSpec extends AnyFunSpec with ChiselSim {
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  import PhyTestRegLoopback._

  val numLanes = 4
  val scoredLanes = 0 to PhyTest.trackLane(numLanes)

  /** One packet as the TX put it on the wire. */
  case class Slot(data: Seq[BigInt], valid: BigInt, track: BigInt, clkp: BigInt) {
    def isData: Boolean = valid != 0
    def clockOn: Boolean = clkp != 0
  }

  def slot(c: PhyTestRegLoopback): Slot = Slot(
    data = (0 until numLanes).map(c.io.tx.data(_).peek().litValue),
    valid = c.io.tx.valid.peek().litValue,
    track = c.io.tx.track.peek().litValue,
    clkp = c.io.tx.clkp.peek().litValue
  )

  /** Starts the TX and returns the `n` packets it sends from the first one
    * after `txExecute`.
    */
  def record(c: PhyTestRegLoopback, n: Int): Seq[Slot] = {
    strobe(c, c.io.regs.txExecute)
    (0 until n).map { _ =>
      val s = slot(c)
      c.clock.step()
      s
    }
  }

  def finiteRun(c: PhyTestRegLoopback, packets: Int): Unit = {
    c.io.regs.txDataMode.poke(DataMode.finite)
    c.io.regs.txPacketsToSend.poke(packets.U)
  }

  def bursts(c: PhyTestRegLoopback, burst: Int, idle: Int): Unit = {
    c.io.regs.txBurstPackets.poke(burst.U)
    c.io.regs.txIdlePackets.poke(idle.U)
  }

  describe("PhyTest TX bursts") {
    it("should send bursts of the continuous stream separated by idle packets") {
      val packets = 18
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        finiteRun(c, packets)
        val continuous = record(c, packets + 4).filter(_.isData)

        resetFsms(c)
        bursts(c, burst = 4, idle = 3)
        val sent = record(c, packets + 4 * 3 + 4)

        // 4 on, 3 off, ..., with the last burst cut short by the count.
        val expectedShape =
          (0 until packets).grouped(4).toSeq.map(_.length).flatMap(n =>
            Seq.fill(n)(true) ++ Seq.fill(3)(false)
          )
        assert(
          sent.map(_.isData).take(expectedShape.length) == expectedShape
        )
        assert(sent.drop(expectedShape.length).forall(!_.isData))
        for (s <- sent.filter(!_.isData)) {
          assert(s.data.forall(_ == 0), "idle packets carry no pattern")
          assert(s.track == 0)
        }
        // The pattern does not advance across a gap.
        assert(continuous.length == packets)
        assert(sent.filter(_.isData) == continuous)
      }
    }

    it("should score a bursting run clean with rxBurstMode, and not without") {
      val packets = 40
      def run(burstMode: Boolean): (Seq[BigInt], BigInt) = {
        var result: (Seq[BigInt], BigInt) = (Seq.empty, 0)
        simulate(new PhyTestRegLoopback(numLanes)) { c =>
          setupLfsr(c)
          finiteRun(c, packets)
          bursts(c, burst = 5, idle = 3)
          c.io.regs.rxBurstMode.poke(burstMode.B)
          strobe(c, c.io.regs.txExecute)
          c.clock.step(packets * 2 + outputDelay)
          result = (errors(c, PhyTest.NominalFraming), packetsReceived(c))
        }
        result
      }
      val (clean, received) = run(burstMode = true)
      assert(received == packets)
      for (lane <- scoredLanes) {
        withClue(s"lane $lane: ") { assert(clean(lane) == 0) }
      }
      // The original RX takes the idle packets for data.
      val (dirty, _) = run(burstMode = false)
      assert(scoredLanes.exists(dirty(_) > 0))
    }
  }

  describe("PhyTest forwarded clock gating") {
    it("should run the clock through each burst, its preamble and postamble") {
      val (pre, post, burst, idle, packets) = (2, 3, 4, 8, 8)
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        finiteRun(c, packets)
        bursts(c, burst, idle)
        c.io.regs.txClkGate.poke(true.B)
        c.io.regs.txClkPreamble.poke(pre.U)
        c.io.regs.txClkPostamble.poke(post.U)
        assert(!slot(c).clockOn, "a gated clock is parked before the run")

        val sent = record(c, 40)
        // (data, clock) per packet.
        val gap = Seq.fill(post)((false, true)) ++
          Seq.fill(idle - pre - post)((false, false)) ++
          Seq.fill(pre)((false, true))
        val expected = Seq.fill(pre)((false, true)) ++
          Seq.fill(burst)((true, true)) ++ gap ++
          Seq.fill(burst)((true, true)) ++
          Seq.fill(post)((false, true))
        val shape = sent.map(s => (s.isData, s.clockOn))
        assert(shape.take(expected.length) == expected)
        assert(shape.drop(expected.length).forall(_ == (false, false)))
        assert(sent.filter(_.clockOn).forall(_.clkp == clkPWord))
        assert(sent.filter(!_.clockOn).forall(_.clkp == 0))
      }
    }

    it("should score clean when the RX only sees packets the clock ran for") {
      val packets = 24
      simulate(new PhyTestRegLoopback(numLanes, rxNeedsClock = true)) { c =>
        setupLfsr(c)
        finiteRun(c, packets)
        bursts(c, burst = 6, idle = 10)
        c.io.regs.txClkGate.poke(true.B)
        c.io.regs.txClkPreamble.poke(2.U)
        c.io.regs.txClkPostamble.poke(2.U)
        c.io.regs.rxBurstMode.poke(true.B)
        // Parked, so nothing more reaches the RX before the run.
        c.clock.step(2)
        val idleWords = c.io.regs.rxIdleWordsObserved.peek().litValue
        c.clock.step(8)
        assert(c.io.regs.rxIdleWordsObserved.peek().litValue == idleWords)
        strobe(c, c.io.regs.txExecute)
        c.clock.step(packets * 3 + outputDelay)
        assert(packetsReceived(c) == packets)
        val counts = errors(c, PhyTest.NominalFraming)
        for (lane <- scoredLanes) {
          withClue(s"lane $lane: ") { assert(counts(lane) == 0) }
        }
      }
    }
  }

  describe("PhyTest track clock") {
    it("should send txClkP on track whatever the FSM is doing") {
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.txTrackClk.poke(true.B)
        assert(slot(c).track == clkPWord, "idle")
        val sent = record(c, 8)
        assert(sent.forall(_.isData))
        assert(sent.forall(_.track == clkPWord), "running")
      }
    }

    it("should still give way to valid moved onto track") {
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.txTrackClk.poke(true.B)
        c.io.regs.txValidLaneSel.poke(Phy.trackValidLaneSel(numLanes).U)
        val sent = record(c, 8)
        assert(sent.forall(_.track == validWord))
      }
    }
  }
}
