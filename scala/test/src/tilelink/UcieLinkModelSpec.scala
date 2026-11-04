package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** `UcieTokenBucket` under a sender that always has a frame: frames must go
  * out at the configured rate, and no faster over any window.
  */
class UcieLinkModelSpec extends AnyFunSpec with ChiselSim {
  implicit val simulator: HasSimulator = HasSimulator.simulators.vcs(
    svsim.CommonCompilationSettings(),
    svsim.vcs.Backend.CompilationSettings(waitForLicenseIfUnavailable = true)
  )

  /** Returns, for `cycles` cycles of a sender that is always ready to send,
    * whether a frame went out each cycle.
    */
  def sends(framesPerCycle: Double, cycles: Int): Seq[Boolean] = {
    var fired = Seq.empty[Boolean]
    simulate(new UcieTokenBucket(framesPerCycle)) { c =>
      c.reset.poke(true.B)
      c.clock.step(2)
      c.reset.poke(false.B)
      fired = (0 until cycles).map { _ =>
        val ready = c.io.ready.peek().litToBoolean
        c.io.fire.poke(ready.B)
        c.clock.step()
        ready
      }
    }
    fired
  }

  def checkRate(framesPerCycle: Double): Unit = {
    val cycles = 3000
    val fired = sends(framesPerCycle, cycles)
    val expected = framesPerCycle * cycles
    assert(
      math.abs(fired.count(identity) - expected) <= 1,
      s"${fired.count(identity)} frames in $cycles cycles, expected $expected"
    )
    // A full bucket allows one frame at once, so no window may hold more than
    // its share plus one.
    for (window <- Seq(2, 7, 50); start <- 0 to cycles - window) {
      val n = fired.slice(start, start + window).count(identity)
      assert(
        n <= math.ceil(framesPerCycle * window) + 1,
        s"$n frames in the $window cycles from $start"
      )
    }
  }

  describe("UcieTokenBucket") {
    it("sends every other cycle at 0.5 frames per cycle") {
      val fired = sends(0.5, 100)
      assert(fired.grouped(2).forall(_ == Seq(true, false)), fired)
    }
    it("holds 0.8 frames per cycle") { checkRate(0.8) }
    it("holds 1/3 frame per cycle") { checkRate(1.0 / 3) }
  }

  describe("UcieLinkModelParams") {
    it("gives 0.5 frames per cycle at 16 GT/s and 1 GHz") {
      assert(UcieLinkModelParams().framesPerCycle == 0.5)
    }
    it("is limited by the sideband clock in controller mode") {
      val p = UcieLinkModelParams(laneRateGTs = 25.6, fSbusMHz = 1000)
      assert(p.framesPerCycle == 0.8)
      val mb = p.copy(mode = UcieLinkMode.MainbandTL, laneRateGTs = 32)
      assert(mb.framesPerCycle == 1.0)
    }
    it("rejects lane rates the controller can't take") {
      assertThrows[IllegalArgumentException](
        UcieLinkModelParams(laneRateGTs = 32)
      )
    }
  }
}
