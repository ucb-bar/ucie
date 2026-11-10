package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils

/** The self-synchronizing check, and the TX error injection that proves the
  * checkers count.
  */
class PhyTestSelfSyncSpec extends AnyFunSpec with ChiselSim {
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  import PhyTestRegLoopback._

  val numLanes = 4
  val scoredLanes = 0 to PhyTest.trackLane(numLanes)
  val patternLanes = scoredLanes.filter(_ != PhyTest.validLane(numLanes))
  val packets = 128

  /** Runs `packets` packets, optionally injecting one error on `injectLane`
    * halfway through, and returns the counts at `framing`.
    */
  def run(
      c: PhyTestRegLoopback,
      framing: Int = PhyTest.NominalFraming,
      injectLane: Option[Int] = None
  ): Seq[BigInt] = {
    strobe(c, c.io.regs.txExecute)
    c.clock.step(packets / 2)
    for (lane <- injectLane) {
      c.io.regs.txInjectLane.poke(lane.U)
      strobe(c, c.io.regs.txInjectError)
    }
    c.clock.step(packets / 2 + outputDelay)
    errors(c, framing)
  }

  describe("PhyTest error injection") {
    it("should count exactly the one flipped bit, on the lane it was sent on") {
      val lane = 2
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        val counts = run(c, injectLane = Some(lane))
        for (l <- scoredLanes) {
          withClue(s"lane $l: ") {
            assert(counts(l) == (if (l == lane) 1 else 0))
          }
        }
      }
    }

    it("should frame one UI late when the first valid bit is dropped") {
      // Valid's bit 0 is the first one the RX sees, so flipping it in the
      // first packet aligns the RX a UI late: the case `rxBitErrorsEarly`
      // exists for.
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.txInjectLane.poke(PhyTest.validLane(numLanes).U)
        strobe(c, c.io.regs.txInjectError)
        val early = run(c, framing = PhyTest.EarlyFraming)
        val nominal = errors(c, PhyTest.NominalFraming)
        for (l <- scoredLanes) {
          withClue(s"lane $l: ") { assert(early(l) == 0) }
        }
        for (l <- patternLanes) {
          withClue(s"lane $l: ") { assert(nominal(l) > 0) }
        }
      }
    }
  }

  describe("PhyTest self-synchronizing check") {
    it("should score every pattern lane clean without the TX's seeds") {
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        // The RX's own LFSRs disagree with the TX on every lane, so the
        // seeded check reads about half the bits wrong.
        setupLfsr(c, rxSeed = lane => seed(lane + 7))
        c.io.regs.rxSelfSync.poke(true.B)
        val counts = run(c)
        for (l <- scoredLanes) {
          withClue(s"lane $l: ") { assert(counts(l) == 0) }
        }
        // The seeded check is still there, on the early and late counts.
        val early = errors(c, PhyTest.EarlyFraming)
        assert(patternLanes.forall(early(_) > 0))
      }
    }

    it("should read clean with the pattern a UI out from the valid edge") {
      simulate(new PhyTestRegLoopback(numLanes, dataDelay = true)) { c =>
        setupLfsr(c)
        c.io.regs.rxSelfSync.poke(true.B)
        val counts = run(c)
        for (l <- patternLanes) {
          withClue(s"lane $l: ") {
            // The first packet carries one bit from before the pattern,
            // which the first scored packet still reaches back to through
            // the largest tap.
            assert(counts(l) <= 1)
          }
        }
      }
    }

    it("should count a single bit error once per term of the recurrence") {
      val lane = 1
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.rxSelfSync.poke(true.B)
        val counts = run(c, injectLane = Some(lane))
        for (l <- patternLanes) {
          withClue(s"lane $l: ") {
            assert(
              counts(l) == (if (l == lane) 1 + PhyTest.LfsrTaps.size else 0)
            )
          }
        }
      }
    }
  }
}
