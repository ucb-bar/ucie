package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy

/** Per-lane pattern sources, and where the errors landed: by lane, by UI, by
  * sampling edge and by the bit that was sent.
  */
class PhyTestDiagnosticsSpec extends AnyFunSpec with ChiselSim {
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  import PhyTestRegLoopback._

  val numLanes = 4
  val scoredLanes = 0 to PhyTest.trackLane(numLanes)
  val patternLanes = scoredLanes.filter(_ != PhyTest.validLane(numLanes))
  val packets = 64
  val wordMask = (BigInt(1) << Phy.SerdesRatio) - 1

  def run(c: PhyTestRegLoopback): Unit = {
    strobe(c, c.io.regs.txExecute)
    c.clock.step(packets + outputDelay)
  }

  def classes(c: PhyTestRegLoopback): Seq[BigInt] = Seq(
    c.io.regs.rxErrRiseOne,
    c.io.regs.rxErrRiseZero,
    c.io.regs.rxErrFallOne,
    c.io.regs.rxErrFallZero
  ).map(_.peek().litValue)

  def positions(c: PhyTestRegLoopback, lane: Int): BigInt = {
    c.io.regs.rxErrPosLane.poke(lane.U)
    c.clock.step()
    c.io.regs.rxErrPosMask.peek().litValue
  }

  def errored(c: PhyTestRegLoopback): BigInt =
    c.io.regs.rxLaneErrored.peek().litValue

  describe("PhyTest per-lane pattern sources") {
    it(
      "should send a fixed word or the inverted stream where asked, clean when the RX expects it"
    ) {
      val (fixedLane, invertedLanes) = (1, Seq(3, PhyTest.trackLane(numLanes)))
      val fixedWord = BigInt("00ff00ff", 16)
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        val inverted = invertedLanes.map(BigInt(1) << _).sum
        for (
          (fixed, invert, word) <- Seq(
            (
              c.io.regs.txLaneFixed,
              c.io.regs.txLaneInvert,
              c.io.regs.txFixedWord
            ),
            (
              c.io.regs.rxLaneFixed,
              c.io.regs.rxLaneInvert,
              c.io.regs.rxFixedWord
            )
          )
        ) {
          fixed.poke((BigInt(1) << fixedLane).U)
          invert.poke(inverted.U)
          word.poke(fixedWord.U)
        }
        strobe(c, c.io.regs.txExecute)
        c.clock.step(4)
        assert(c.io.tx.data(fixedLane).peek().litValue == fixedWord)
        c.clock.step(packets + outputDelay)
        for (l <- scoredLanes) {
          withClue(s"lane $l: ") {
            assert(errors(c, PhyTest.NominalFraming)(l) == 0)
          }
        }
        // Only the target's lanes collect errors: the loopback lane, idle in
        // a mainband run, is scored against a pattern but never flagged.
        assert(errored(c) == 0)
      }
    }

    it(
      "should read every bit wrong where the TX inverts and the RX does not, half on each edge"
    ) {
      val lane = 2
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.txLaneInvert.poke((BigInt(1) << lane).U)
        c.io.regs.rxErrClassLane.poke(lane.U)
        run(c)
        val received = packetsReceived(c)
        assert(received > 0)
        val counts = errors(c, PhyTest.NominalFraming)
        for (l <- scoredLanes) {
          withClue(s"lane $l: ") {
            assert(counts(l) == (if (l == lane) 32 * received else BigInt(0)))
          }
        }
        val Seq(riseOne, riseZero, fallOne, fallZero) = classes(c)
        assert(riseOne + riseZero == 16 * received)
        assert(fallOne + fallZero == 16 * received)
        assert(errored(c) == (BigInt(1) << lane))
        assert(positions(c, lane) == wordMask)
        assert(positions(c, lane + 1) == 0)
      }
    }

    it("should count a lane the TX holds at zero as ones read as zeros only") {
      val lane = 1
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.txLaneFixed.poke((BigInt(1) << lane).U)
        c.io.regs.txFixedWord.poke(0.U)
        c.io.regs.rxErrClassLane.poke(lane.U)
        run(c)
        val Seq(riseOne, riseZero, fallOne, fallZero) = classes(c)
        assert(riseOne + fallOne == errors(c, PhyTest.NominalFraming)(lane))
        assert(riseOne > 0 && fallOne > 0)
        assert(riseZero == 0 && fallZero == 0)
      }
    }

    it(
      "should score fixed and inverted lanes against their expectation under rxSelfSync"
    ) {
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.rxSelfSync.poke(true.B)
        for (
          (fixed, invert, word) <- Seq(
            (
              c.io.regs.txLaneFixed,
              c.io.regs.txLaneInvert,
              c.io.regs.txFixedWord
            ),
            (
              c.io.regs.rxLaneFixed,
              c.io.regs.rxLaneInvert,
              c.io.regs.rxFixedWord
            )
          )
        ) {
          fixed.poke((BigInt(1) << 0).U)
          invert.poke((BigInt(1) << 2).U)
          word.poke(BigInt("ffffffff", 16).U)
        }
        run(c)
        for (l <- scoredLanes) {
          withClue(s"lane $l: ") {
            assert(errors(c, PhyTest.NominalFraming)(l) == 0)
          }
        }
      }
    }
  }

  describe("PhyTest error locations and classes") {
    it(
      "should place one injected error by lane, UI, edge and the bit that was sent"
    ) {
      val lane = 1
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.rxErrClassLane.poke(lane.U)
        // Before the run, so it lands on the first packet, which the capture
        // SRAM keeps.
        c.io.regs.txInjectLane.poke(lane.U)
        strobe(c, c.io.regs.txInjectError)
        run(c)
        assert(errors(c, PhyTest.NominalFraming)(lane) == 1)
        assert(errored(c) == (BigInt(1) << lane))
        // Bit 0 of the packet: UI 0, which the RX samples on a rising edge
        // when it frames on its own word's first UI, as it does here.
        assert(positions(c, lane) == 1)
        val sentOne = !captured(c, lane, 0).testBit(0)
        assert(
          classes(c) == Seq(
            if (sentOne) 1 else 0,
            if (sentOne) 0 else 1,
            0,
            0
          ).map(BigInt(_))
        )
      }
    }

    it(
      "should freeze the class counts under rxPauseCounters and clear them with the RX FSM"
    ) {
      val lane = 2
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.txLaneInvert.poke((BigInt(1) << lane).U)
        c.io.regs.rxErrClassLane.poke(lane.U)
        strobe(c, c.io.regs.txExecute)
        c.clock.step(packets / 2)
        c.io.regs.rxPauseCounters.poke(true.B)
        c.clock.step(outputDelay)
        val held = classes(c)
        c.clock.step(packets / 2)
        assert(classes(c) == held)
        assert(held.sum == errors(c, PhyTest.NominalFraming)(lane))
        c.io.regs.rxPauseCounters.poke(false.B)
        // Both FSMs: an RX restarted under a running TX would score it
        // against freshly reseeded LFSRs straight away.
        resetFsms(c)
        c.clock.step(outputDelay)
        assert(classes(c).forall(_ == 0))
        assert(errored(c) == 0)
      }
    }
  }
}
