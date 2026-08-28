package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import org.scalatest.funspec.AnyFunSpec

/** MBTRAIN.LINKSPEED resolution for a multi-module Link, spec 4.7.1.
  *
  * The report alphabet below is every (sent, received) pair a Module can arrive
  * at after Step 2, so the combinations can be enumerated exhaustively. Besides
  * comparing against a reference model, the suite checks the spec-level
  * properties the model cannot vouch for on its own: the permitted Module
  * counts of spec 5.7.3.4.1, that a resolution never enables a Module that was
  * down, and the two-die consistency that spec 4.7.1.2 relies on.
  */
class MmplLinkSpeedResolverTest extends AnyFunSpec with ChiselSim {

  // ==========================================================================
  // Report alphabet
  // ==========================================================================
  private case class Report(
      sentDone: Boolean = false,
      sentRepair: Boolean = false,
      sentSpeedDegrade: Boolean = false,
      recvdDone: Boolean = false,
      recvdRepair: Boolean = false,
      recvdSpeedDegrade: Boolean = false,
      priorWidthDegrade: Boolean = false
  ) {
    def errored: Boolean =
      sentRepair || sentSpeedDegrade || recvdRepair || recvdSpeedDegrade

    /** Sent and received with the two directions exchanged, which is what the
      * remote die's corresponding Module observes.
      */
    def mirrored: Report = Report(
      sentDone = recvdDone,
      sentRepair = recvdRepair,
      sentSpeedDegrade = recvdSpeedDegrade,
      recvdDone = sentDone,
      recvdRepair = sentRepair,
      recvdSpeedDegrade = sentSpeedDegrade,
      priorWidthDegrade = priorWidthDegrade
    )
  }

  private val clean = Report(sentDone = true, recvdDone = true)
  private val cleanButDegraded =
    Report(sentDone = true, recvdDone = true, priorWidthDegrade = true)
  private val sentRepairReq = Report(sentRepair = true, recvdDone = true)
  private val recvdRepairReq = Report(sentDone = true, recvdRepair = true)
  private val sentSpeedReq = Report(sentSpeedDegrade = true, recvdDone = true)
  private val recvdSpeedReq = Report(sentDone = true, recvdSpeedDegrade = true)

  private val alphabet: Seq[(String, Report)] = Seq(
    "done" -> clean,
    "done+priorDegrade" -> cleanButDegraded,
    "sent repair" -> sentRepairReq,
    "recvd repair" -> recvdRepairReq,
    "sent speedDegrade" -> sentSpeedReq,
    "recvd speedDegrade" -> recvdSpeedReq
  )

  private val speeds: Seq[(SpeedMode.Type, Int)] = Seq(
    SpeedMode.speed4 -> 4,
    SpeedMode.speed8 -> 8,
    SpeedMode.speed12 -> 12,
    SpeedMode.speed16 -> 16,
    SpeedMode.speed24 -> 24,
    SpeedMode.speed32 -> 32,
    SpeedMode.speed48 -> 48,
    SpeedMode.speed64 -> 64
  )

  // ==========================================================================
  // Reference model of Figure 4-48 (Standard Package)
  // ==========================================================================
  private case class Outcome(link: String, nextEnable: Seq[Boolean])

  private def model(
      reports: Seq[Report],
      enable: Seq[Boolean],
      speedGTs: Int,
      numModules: Int,
      standard: Boolean
  ): Outcome = {
    val ladder = speeds.map(_._2)
    val idx = ladder.indexOf(speedGTs)
    val nextLower = if (idx == 0) 0 else ladder(idx - 1)
    val speedDegradeWins = 2 * nextLower > speedGTs
    val noLowerSpeed = speedGTs / 2 > nextLower

    val widthReq = (0 until numModules).map { m =>
      enable(m) && (reports(m).sentRepair || reports(m).recvdRepair ||
        reports(m).priorWidthDegrade)
    }
    val speedReq = (0 until numModules).map { m =>
      enable(m) &&
      (reports(m).sentSpeedDegrade || reports(m).recvdSpeedDegrade)
    }
    val failing = (0 until numModules).map(m => widthReq(m) || speedReq(m))

    val numActive = enable.count(identity)
    val numWidth = widthReq.count(identity)
    val numHalves = if (numModules == 1) 1 else 2
    val perHalf = numModules / numHalves

    def halfMembers(h: Int) = (h * perHalf) until ((h + 1) * perHalf)
    val cleanHalf =
      (0 until numHalves).find(h =>
        halfMembers(h).forall(m => !failing(m) && enable(m))
      )
    val cleanModule = (0 until numModules).find(m => enable(m) && !failing(m))

    val survivors: Option[Seq[Boolean]] = cleanHalf
      .map(h => (0 until numModules).map(halfMembers(h).contains))
      .orElse(cleanModule.map(m => (0 until numModules).map(_ == m)))

    def disableOutcome: Outcome = survivors match {
      case Some(mask) => Outcome("done", mask)
      case None       => Outcome("trainError", enable)
    }

    if (!failing.exists(identity)) Outcome("done", enable)
    else if (!speedReq.exists(identity) && widthReq.exists(identity)) {
      if (!standard) Outcome("repair", enable)
      else if (2 * numWidth > numActive) {
        if (speedGTs == 4) Outcome("repair", enable)
        else if (speedDegradeWins) Outcome("speedDegrade", enable)
        else Outcome("repair", enable)
      } else disableOutcome
    } else if (noLowerSpeed) disableOutcome
    else Outcome("speedDegrade", enable)
  }

  // ==========================================================================
  // Driving and reading the DUT
  // ==========================================================================
  private def resolutionName(v: BigInt): String =
    Seq(
      "none" -> MmplResolution.none,
      "done" -> MmplResolution.done,
      "repair" -> MmplResolution.repair,
      "speedDegrade" -> MmplResolution.speedDegrade,
      "disableModule" -> MmplResolution.disableModule,
      "trainError" -> MmplResolution.trainError
    ).collectFirst { case (name, e) if e.litValue == v => name }
      .getOrElse(s"unknown($v)")

  private def driveReport(
      port: chisel3.util.Valid[MmplLinkSpeedReport],
      report: Report,
      valid: Boolean
  ): Unit = {
    port.valid.poke(valid.B)
    port.bits.sentDone.poke(report.sentDone.B)
    port.bits.sentRepair.poke(report.sentRepair.B)
    port.bits.sentSpeedDegrade.poke(report.sentSpeedDegrade.B)
    // An error handshake precedes any degrade request in the real flow; it
    // carries no information the resolution uses.
    port.bits.sentError.poke(report.errored.B)
    port.bits.sentPhyRetrain.poke(false.B)
    port.bits.recvdDone.poke(report.recvdDone.B)
    port.bits.recvdRepair.poke(report.recvdRepair.B)
    port.bits.recvdSpeedDegrade.poke(report.recvdSpeedDegrade.B)
    port.bits.recvdError.poke(report.errored.B)
    port.bits.recvdPhyRetrain.poke(false.B)
    port.bits.priorWidthDegrade.poke(report.priorWidthDegrade.B)
  }

  private def apply(
      c: MmplLinkSpeedResolver,
      reports: Seq[Report],
      enable: Seq[Boolean],
      speed: SpeedMode.Type,
      numModules: Int
  ): Outcome = {
    c.io.currentSpeed.poke(speed)
    for (m <- 0 until numModules) {
      c.io.enable(m).poke(enable(m).B)
      driveReport(c.io.reports(m), reports(m), enable(m))
    }
    assert(
      c.io.resolved.peek().litToBoolean,
      "resolver did not report resolved"
    )
    Outcome(
      resolutionName(c.io.linkResolution.peek().litValue),
      (0 until numModules).map(m => c.io.nextEnable(m).peek().litToBoolean)
    )
  }

  /** Spec-level checks that hold whatever the reference model says. */
  private def checkProperties(
      c: MmplLinkSpeedResolver,
      out: Outcome,
      enable: Seq[Boolean],
      numModules: Int,
      context: String
  ): Unit = {
    val perHalf = if (numModules == 1) 1 else numModules / 2

    assert(
      Seq("done", "repair", "speedDegrade", "trainError").contains(out.link),
      s"$context: link resolution ${out.link} is not a permitted outcome"
    )
    // A resolution may only ever shrink the operational set.
    for (m <- 0 until numModules) {
      assert(
        !out.nextEnable(m) || enable(m),
        s"$context: Module $m was enabled by the resolution"
      )
    }
    // Spec 5.7.3.4.1 rule 1: a degraded Link is one or two Modules, never three.
    val survivors = out.nextEnable.count(identity)
    if (survivors != enable.count(identity)) {
      assert(
        survivors == perHalf || survivors == 1,
        s"$context: degraded to $survivors Modules, which is not permitted"
      )
    }
    // Repair and speed degrade keep every Module.
    if (out.link == "repair" || out.link == "speedDegrade") {
      assert(
        out.nextEnable == enable,
        s"$context: ${out.link} disabled a Module"
      )
    }
    // Per-Module directives must agree with nextEnable.
    for (m <- 0 until numModules) {
      val perModule = resolutionName(c.io.moduleResolution(m).peek().litValue)
      if (enable(m) && !out.nextEnable(m)) {
        assert(
          perModule == "disableModule",
          s"$context: Module $m is dropped but was told $perModule"
        )
      } else if (enable(m)) {
        assert(
          perModule == out.link,
          s"$context: Module $m was told $perModule, Link resolved ${out.link}"
        )
      }
    }
  }

  // ==========================================================================
  // Exhaustive enumeration
  // ==========================================================================
  private def combinations(numModules: Int): Seq[Seq[(String, Report)]] =
    Seq.fill(numModules)(alphabet).foldLeft(Seq(Seq.empty[(String, Report)])) {
      (acc, syms) => acc.flatMap(prefix => syms.map(prefix :+ _))
    }

  describe("MmplLinkSpeedResolver exhaustively, two modules") {
    val numModules = 2
    val params = MmplParams(numModules = numModules)

    it("matches the reference model at every Link speed") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val enable = Seq.fill(numModules)(true)
        for {
          (speed, gts) <- speeds
          combo <- combinations(numModules)
        } {
          val reports = combo.map(_._2)
          val context =
            s"${gts}GT/s [${combo.map(_._1).mkString(" | ")}]"
          val out = apply(c, reports, enable, speed, numModules)
          val expected =
            model(reports, enable, gts, numModules, standard = true)
          assert(
            out == expected,
            s"$context: got $out, model says $expected"
          )
          checkProperties(c, out, enable, numModules, context)
        }
      }
    }

    it(
      "reaches the same resolution on both die when the reports are mirrored"
    ) {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val enable = Seq.fill(numModules)(true)
        for {
          (speed, gts) <- speeds
          combo <- combinations(numModules)
        } {
          val reports = combo.map(_._2)
          val local = apply(c, reports, enable, speed, numModules)
          val remote =
            apply(c, reports.map(_.mirrored), enable, speed, numModules)
          assert(
            local == remote,
            s"${gts}GT/s [${combo.map(_._1).mkString(" | ")}]: " +
              s"local resolved $local, remote resolved $remote"
          )
        }
      }
    }
  }

  describe("MmplLinkSpeedResolver exhaustively, four modules") {
    val numModules = 4
    val params = MmplParams(numModules = numModules)
    // 4 GT/s exercises the base case, 8 GT/s the width-over-speed comparison,
    // and 16 GT/s the speed degrade.
    val sampledSpeeds = speeds.filter { case (_, gts) =>
      Seq(4, 8, 16).contains(gts)
    }

    it("matches the reference model and stays mirror consistent") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val enable = Seq.fill(numModules)(true)
        for {
          (speed, gts) <- sampledSpeeds
          combo <- combinations(numModules)
        } {
          val reports = combo.map(_._2)
          val context = s"${gts}GT/s [${combo.map(_._1).mkString(" | ")}]"
          val out = apply(c, reports, enable, speed, numModules)
          assert(
            out == model(reports, enable, gts, numModules, standard = true),
            s"$context: got $out"
          )
          checkProperties(c, out, enable, numModules, context)

          val mirrored =
            apply(c, reports.map(_.mirrored), enable, speed, numModules)
          assert(mirrored == out, s"$context: mirror gave $mirrored, not $out")
        }
      }
    }

    it("resolves from an already degraded two-module configuration") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        // M0 and M1 were disabled by an earlier resolution.
        val enable = Seq(false, false, true, true)
        for {
          (speed, gts) <- sampledSpeeds
          combo <- combinations(numModules)
        } {
          val reports = combo.map(_._2)
          val context =
            s"${gts}GT/s degraded [${combo.map(_._1).mkString(" | ")}]"
          val out = apply(c, reports, enable, speed, numModules)
          assert(
            out == model(reports, enable, gts, numModules, standard = true),
            s"$context: got $out"
          )
          checkProperties(c, out, enable, numModules, context)
        }
      }
    }
  }

  // ==========================================================================
  // The worked examples of spec 4.7.1.2
  // ==========================================================================
  describe("MmplLinkSpeedResolver on the spec 4.7.1.2 examples") {
    val params = MmplParams(numModules = 4)
    val allEnabled = Seq.fill(4)(true)

    it(
      "Example 1: three of four report width degrade at 8 GT/s, width degrade"
    ) {
      // Table 4-13. BW(4 Links at 4 GT/s) is not greater than
      // BW(2 Links at 8 GT/s), so the Link moves to MBTRAIN.REPAIR.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val reports =
          Seq(clean, sentRepairReq, recvdRepairReq, sentRepairReq)
        val out = apply(c, reports, allEnabled, SpeedMode.speed8, 4)
        assert(out.link == "repair", s"expected repair, got ${out.link}")
        assert(out.nextEnable == allEnabled, "no Module should be disabled")
      }
    }

    it("Example 2: one module received a speed degrade at 16 GT/s") {
      // Table 4-14. CMLS is 12 GT/s and HMLS 16 GT/s, so HMLS/2 > CMLS is
      // false and every Module degrades speed through MBTRAIN.SPEEDIDLE.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val reports = Seq(sentRepairReq, clean, clean, recvdSpeedReq)
        val out = apply(c, reports, allEnabled, SpeedMode.speed16, 4)
        assert(
          out.link == "speedDegrade",
          s"expected speedDegrade, got ${out.link}"
        )
        assert(out.nextEnable == allEnabled, "no Module should be disabled")
      }
    }

    it(
      "Example 3: one of four reports width degrade at 16 GT/s, module disable"
    ) {
      // Table 4-15. Fewer than half report, so the Link drops to two Modules.
      // Module 1 failed, so its half {M0, M1} is disabled and {M2, M3} carries
      // on to LINKINIT.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val reports = Seq(clean, sentRepairReq, clean, clean)
        val out = apply(c, reports, allEnabled, SpeedMode.speed16, 4)
        assert(out.link == "done", s"expected done, got ${out.link}")
        assert(
          out.nextEnable == Seq(false, false, true, true),
          s"expected M2 and M3 to survive, got ${out.nextEnable}"
        )
        assert(
          resolutionName(c.io.moduleResolution(0).peek().litValue) ==
            "disableModule",
          "Module 0 should have been told to disable"
        )
        assert(
          resolutionName(c.io.moduleResolution(1).peek().litValue) ==
            "disableModule",
          "Module 1 should have been told to disable"
        )
        assert(
          resolutionName(c.io.moduleResolution(2).peek().litValue) == "done",
          "Module 2 should have been told done"
        )
      }
    }

    it("Treats a prior width degrade as a width degrade request") {
      // Spec 4.7.1.2.1: a Module that degraded in MBINIT.REPAIRMB may still
      // exchange {done req}, but the MMPL must not let the Link run with
      // Modules at different widths.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val reports =
          Seq(clean, cleanButDegraded, cleanButDegraded, cleanButDegraded)
        val out = apply(c, reports, allEnabled, SpeedMode.speed8, 4)
        assert(
          out.link == "repair",
          s"expected the Link to width degrade, got ${out.link}"
        )
      }
    }

    it("Disables rather than TRAINERROR when 4 GT/s cannot degrade further") {
      // Spec 4.7.1: the HMLS/2 > CMLS Yes arc exists so Modules that failed at
      // 4 GT/s are disabled and the rest stay operational.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val reports = Seq(clean, clean, sentSpeedReq, clean)
        val out = apply(c, reports, allEnabled, SpeedMode.speed4, 4)
        assert(out.link == "done", s"expected done, got ${out.link}")
        assert(
          out.nextEnable == Seq(true, true, false, false),
          s"expected M0 and M1 to survive, got ${out.nextEnable}"
        )
      }
    }

    it("Reports TRAINERROR when no Module has an operational configuration") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val reports = Seq.fill(4)(sentSpeedReq)
        val out = apply(c, reports, allEnabled, SpeedMode.speed4, 4)
        assert(
          out.link == "trainError",
          s"expected trainError, got ${out.link}"
        )
      }
    }
  }

  describe("MmplLinkSpeedResolver with one module") {
    it("Leaves the single-module flow alone") {
      simulate(new MmplLinkSpeedResolver(MmplParams(numModules = 1))) { c =>
        val out = apply(c, Seq(clean), Seq(true), SpeedMode.speed8, 1)
        assert(out.link == "done", s"expected done, got ${out.link}")

        // A lone Module is its own majority, so a repairable error resolves to
        // MBTRAIN.REPAIR exactly as the single-module flow already does.
        val repair =
          apply(c, Seq(sentRepairReq), Seq(true), SpeedMode.speed8, 1)
        assert(repair.link == "repair", s"expected repair, got ${repair.link}")
        assert(repair.nextEnable == Seq(true), "the Module must stay enabled")

        // A speed degrade at 12 GT/s and above still beats a width degrade.
        val speed =
          apply(c, Seq(sentRepairReq), Seq(true), SpeedMode.speed16, 1)
        assert(
          speed.link == "speedDegrade",
          s"expected speedDegrade, got ${speed.link}"
        )
      }
    }

    it("Reports TRAINERROR when the only Module cannot degrade further") {
      simulate(new MmplLinkSpeedResolver(MmplParams(numModules = 1))) { c =>
        val out = apply(c, Seq(sentSpeedReq), Seq(true), SpeedMode.speed4, 1)
        assert(
          out.link == "trainError",
          s"nothing is left operational, got ${out.link}"
        )
      }
    }
  }
}
