package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import org.scalatest.funspec.AnyFunSpec

/** MBTRAIN.LINKSPEED resolution for a multi-module Link, spec 4.7.1.
  *
  * The suite is anchored in two places the implementation cannot vouch for
  * itself:
  *
  *   - the golden vectors transcribed from spec Table 5-29 (p.247), which
  *     enumerate the surviving Module set for every one-, two- and three-
  *     failure pattern on a four-Module Standard Package Link, and
  *   - the three worked examples of spec 4.7.1.2.
  *
  * Those are literal readings of the spec, so a reference model that drifted
  * towards the RTL cannot make them pass. The exhaustive sweeps then use a
  * model for coverage of the combinations the spec does not tabulate.
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
      sentPhyRetrain: Boolean = false,
      recvdPhyRetrain: Boolean = false
  ) {
    def errored: Boolean =
      sentRepair || sentSpeedDegrade || recvdRepair || recvdSpeedDegrade

    def phyRetrain: Boolean = sentPhyRetrain || recvdPhyRetrain

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
      sentPhyRetrain = recvdPhyRetrain,
      recvdPhyRetrain = sentPhyRetrain
    )
  }

  private val clean = Report(sentDone = true, recvdDone = true)
  private val sentRepairReq = Report(sentRepair = true, recvdDone = true)
  private val recvdRepairReq = Report(sentDone = true, recvdRepair = true)
  private val sentSpeedReq = Report(sentSpeedDegrade = true, recvdDone = true)
  private val recvdSpeedReq = Report(sentDone = true, recvdSpeedDegrade = true)
  private val recvdPhyRetrainReq = Report(recvdPhyRetrain = true)

  private val alphabet: Seq[(String, Report)] = Seq(
    "done" -> clean,
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
  // Reference model
  // ==========================================================================
  private case class Outcome(link: String, nextEnable: Seq[Boolean])

  /** Spec 5.7.3.4.1: the surviving set after disabling `doomed`.
    *
    * Rule 1 keeps everything that did not fail. Rule 2 is the only sacrificial
    * disable there is, and it applies solely when exactly one Module of a full
    * four-Module Link failed -- Table 5-29 shows no `x (d)` marker in either
    * the two-fail or the three-fail columns.
    */
  private def survivorsOf(
      active: Seq[Boolean],
      doomed: Seq[Boolean],
      numModules: Int
  ): Seq[Boolean] = {
    val perHalf = if (numModules == 1) 1 else numModules / 2
    val halfRule =
      numModules == 4 && active.count(identity) == 4 &&
        doomed.count(identity) == 1
    def halfOf(m: Int) = {
      val h = m / perHalf
      (h * perHalf) until ((h + 1) * perHalf)
    }
    (0 until numModules).map { m =>
      active(m) && !doomed(m) &&
      !(halfRule && halfOf(m).exists(doomed(_)))
    }
  }

  /** One trip through the decision diamonds of Figure 4-48. */
  private def decideOnce(
      reports: Seq[Report],
      narrower: Seq[Boolean],
      active: Seq[Boolean],
      speedGTs: Int,
      numModules: Int,
      standard: Boolean
  ): (String, Seq[Boolean]) = {
    val ladder = speeds.map(_._2)
    val idx = ladder.indexOf(speedGTs)
    val nextLower = if (idx == 0) 0 else ladder(idx - 1)
    val none = Seq.fill(numModules)(false)

    val widthReq = (0 until numModules).map { m =>
      active(m) &&
      (reports(m).sentRepair || reports(m).recvdRepair || narrower(m))
    }
    val speedReq = (0 until numModules).map { m =>
      active(m) &&
      (reports(m).sentSpeedDegrade || reports(m).recvdSpeedDegrade)
    }
    val numActive = active.count(identity)
    val numWidth = widthReq.count(identity)

    if (!widthReq.exists(identity) && !speedReq.exists(identity))
      ("done", none)
    else if (!speedReq.exists(identity)) {
      if (!standard) ("repair", none)
      else if (2 * numWidth > numActive) {
        // Rule 1.b's pseudo code.
        if (speedGTs == 4) ("repair", none)
        else if (2 * nextLower > speedGTs) ("speedDegrade", none)
        else ("repair", none)
      } else ("disable", widthReq) // Rule 1.a
    } else if (speedGTs / 2 > nextLower) ("disable", speedReq)
    else ("speedDegrade", none)
  }

  /** The whole chart including the loop back through connector 1. */
  private def model(
      reports: Seq[Report],
      narrower: Seq[Boolean],
      enable: Seq[Boolean],
      speedGTs: Int,
      numModules: Int,
      standard: Boolean = true
  ): Outcome = {
    if ((0 until numModules).exists(m => enable(m) && reports(m).phyRetrain))
      return Outcome("phyRetrain", enable)

    var active = enable
    var result: Option[Outcome] = None
    var guard = 0
    while (result.isEmpty && guard < numModules + 1) {
      guard += 1
      val (link, doomed) =
        decideOnce(reports, narrower, active, speedGTs, numModules, standard)
      if (link != "disable") result = Some(Outcome(link, active))
      else {
        val survivors = survivorsOf(active, doomed, numModules)
        if (!survivors.exists(identity))
          result = Some(Outcome("trainError", Seq.fill(numModules)(false)))
        else active = survivors
      }
    }
    result.getOrElse(Outcome("trainError", Seq.fill(numModules)(false)))
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
      "phyRetrain" -> MmplResolution.phyRetrain,
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
    port.bits.sentPhyRetrain.poke(report.sentPhyRetrain.B)
    port.bits.recvdDone.poke(report.recvdDone.B)
    port.bits.recvdRepair.poke(report.recvdRepair.B)
    port.bits.recvdSpeedDegrade.poke(report.recvdSpeedDegrade.B)
    port.bits.recvdError.poke(report.errored.B)
    port.bits.recvdPhyRetrain.poke(report.recvdPhyRetrain.B)
  }

  private def apply(
      c: MmplLinkSpeedResolver,
      reports: Seq[Report],
      enable: Seq[Boolean],
      speed: SpeedMode.Type,
      numModules: Int,
      narrower: Seq[Boolean] = Seq.empty
  ): Outcome = {
    val narrow =
      if (narrower.isEmpty) Seq.fill(numModules)(false) else narrower
    c.io.currentSpeed.poke(speed)
    for (m <- 0 until numModules) {
      c.io.enable(m).poke(enable(m).B)
      c.io.narrowerThanPeers(m).poke((enable(m) && narrow(m)).B)
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
    assert(
      Seq("done", "repair", "speedDegrade", "phyRetrain", "trainError")
        .contains(out.link),
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
    if (out.link != "trainError" && survivors != enable.count(identity)) {
      assert(
        survivors == 1 || survivors == 2,
        s"$context: degraded to $survivors Modules, which is not permitted"
      )
    }
    // Repair and speed degrade do not themselves disable anything, but they can
    // be the outcome of a later trip round the chart's connector-1 loop, where
    // an earlier pass already dropped the Modules that could not be degraded.
    // So the invariant is on the shape of the surviving set, checked above, not
    // on it being untouched.
    // Per-Module directives must agree with nextEnable.
    for (m <- 0 until numModules) {
      val perModule = resolutionName(c.io.moduleResolution(m).peek().litValue)
      if (out.link == "trainError") {
        assert(
          perModule == "trainError",
          s"$context: Module $m was told $perModule, Link resolved trainError"
        )
      } else if (enable(m) && !out.nextEnable(m)) {
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
  // Spec Table 5-29 (p.247): golden survivor sets
  // ==========================================================================
  // Transcribed from the rendered table. Rows are the Module - Module Partner
  // pairs M0-M2, M1-M3, M3-M1, M2-M0; a column is one failure pattern, with
  // `x` a failed pair, `x (d)` a pair disabled to comply with the degrade
  // rules, and a check mark a pair still functional. Only the local Module
  // matters here, so each column is recorded as (failed Modules, survivors).
  //
  // The point of the two- and three-fail rows is that Table 5-29 shows no
  // `x (d)` marker anywhere in them: the Modules that did not fail all survive,
  // whether or not they sit in the same half of the Die Edge.
  //
  // The stimulus differs by column because Figure 4-48 reaches a Module disable
  // by two different arcs. One or two failures out of four is "less than or
  // equal to half", so a width degrade request takes the disable arc directly.
  // Three out of four is a majority, which takes the bandwidth comparison
  // instead and degrades the whole Link rather than disabling anything -- so
  // the three-fail column is reached through the other disable arc, where
  // Modules ask for a speed degrade at 4 GT/s and there is no lower speed.
  private val table5_29: Seq[(String, Set[Int], Set[Int])] = Seq(
    // 1-fail: rule 2 also disables the failed Module's same-half partner.
    ("1-fail M0", Set(0), Set(2, 3)),
    ("1-fail M1", Set(1), Set(2, 3)),
    ("1-fail M3", Set(3), Set(0, 1)),
    ("1-fail M2", Set(2), Set(0, 1)),
    // 2-fail: the two survivors carry on, same half or not.
    ("2-fail M0,M1", Set(0, 1), Set(2, 3)),
    ("2-fail M0,M3", Set(0, 3), Set(1, 2)),
    ("2-fail M0,M2", Set(0, 2), Set(1, 3)),
    ("2-fail M1,M3", Set(1, 3), Set(0, 2)),
    ("2-fail M1,M2", Set(1, 2), Set(0, 3)),
    ("2-fail M2,M3", Set(2, 3), Set(0, 1))
  )

  private val table5_29ThreeFail: Seq[(String, Set[Int], Set[Int])] = Seq(
    ("3-fail M0,M1,M3", Set(0, 1, 3), Set(2)),
    ("3-fail M0,M1,M2", Set(0, 1, 2), Set(3)),
    ("3-fail M0,M2,M3", Set(0, 2, 3), Set(1)),
    ("3-fail M1,M2,M3", Set(1, 2, 3), Set(0))
  )

  describe("MmplLinkSpeedResolver against spec Table 5-29") {
    val params = MmplParams(numModules = 4)
    val allEnabled = Seq.fill(4)(true)

    it("degrades to exactly the surviving Module set the table names") {
      // Width degrade requests at 16 GT/s. This is the case the previous
      // implementation got wrong: it looked for an intact {M0,M1} or {M2,M3}
      // half and fell back to a single Module when the two failures straddled
      // them, so four of the six two-fail columns lost a Module they should
      // have kept.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        table5_29.foreach { case (name, failed, expected) =>
          val reports =
            (0 until 4).map(m => if (failed(m)) sentRepairReq else clean)
          val out = apply(c, reports, allEnabled, SpeedMode.speed16, 4)
          val got = (0 until 4).filter(out.nextEnable(_)).toSet
          assert(
            out.link == "done",
            s"$name: expected the survivors to proceed, got ${out.link}"
          )
          assert(
            got == expected,
            s"$name: Table 5-29 requires survivors $expected, got $got"
          )
          checkProperties(c, out, allEnabled, 4, name)
        }
      }
    }

    it("reaches the same survivors when the failures are reported remotely") {
      // A failure seen as {exit to repair req} received rather than sent must
      // resolve identically, which is what keeps the two die in step.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        table5_29.foreach { case (name, failed, expected) =>
          val reports =
            (0 until 4).map(m => if (failed(m)) recvdRepairReq else clean)
          val out = apply(c, reports, allEnabled, SpeedMode.speed16, 4)
          val got = (0 until 4).filter(out.nextEnable(_)).toSet
          assert(
            got == expected,
            s"$name mirrored: Table 5-29 requires $expected, got $got"
          )
        }
      }
    }

    it("degrades to one Module when three of four fail") {
      // Speed degrade requests at 4 GT/s, where HMLS/2 > CMLS holds and the
      // Modules asking for one are disabled instead.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        table5_29ThreeFail.foreach { case (name, failed, expected) =>
          val reports =
            (0 until 4).map(m => if (failed(m)) sentSpeedReq else clean)
          val out = apply(c, reports, allEnabled, SpeedMode.speed4, 4)
          val got = (0 until 4).filter(out.nextEnable(_)).toSet
          assert(
            out.link == "done",
            s"$name: expected the survivor to proceed, got ${out.link}"
          )
          assert(
            got == expected,
            s"$name: Table 5-29 requires survivors $expected, got $got"
          )
          checkProperties(c, out, allEnabled, 4, name)
        }
      }
    }

    it("degrades a two-module Link to one Module on a single failure") {
      // Spec 5.7.3.4.1 rule 1.b.i. Rule 2's same-half padding is scoped to
      // four-Module Links, so the clean Module simply carries on alone.
      simulate(new MmplLinkSpeedResolver(MmplParams(numModules = 2))) { c =>
        val out =
          apply(c, Seq(sentRepairReq, clean), Seq(true, true), SpeedMode.speed16, 2)
        assert(out.link == "done", s"expected done, got ${out.link}")
        assert(
          out.nextEnable == Seq(false, true),
          s"expected M1 to survive alone, got ${out.nextEnable}"
        )
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

  // ==========================================================================
  // Regressions
  // ==========================================================================
  describe("MmplLinkSpeedResolver regressions") {
    val params = MmplParams(numModules = 4)
    val allEnabled = Seq.fill(4)(true)

    it("treats a Module narrower than its peers as requesting width degrade") {
      // Spec 4.7.1.2.1: a Module that degraded in MBINIT.REPAIRMB may still
      // exchange {done req}, but the Link cannot run at mixed widths.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val out = apply(
          c,
          Seq.fill(4)(clean),
          allEnabled,
          SpeedMode.speed16,
          4,
          narrower = Seq(false, true, false, false)
        )
        assert(
          out.nextEnable == Seq(false, false, true, true),
          s"the narrow Module's half should be disabled, got ${out.nextEnable}"
        )
      }
    }

    it("lets a Link that has already degraded together proceed to LINKINIT") {
      // The counterpart, and the reason the test above is stated relative to
      // the other Modules rather than against full width: once every Module has
      // width degraded, none of them is narrower than its peers and the next
      // pass of LINKSPEED must proceed to Step 6. Measuring against x16 instead
      // would keep resolving `repair` and the Link would never come up.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        speeds.foreach { case (speed, gts) =>
          val out = apply(
            c,
            Seq.fill(4)(clean),
            allEnabled,
            speed,
            4,
            narrower = Seq.fill(4)(false)
          )
          assert(
            out.link == "done",
            s"${gts}GT/s: a uniformly degraded Link must proceed, got ${out.link}"
          )
          assert(out.nextEnable == allEnabled, s"${gts}GT/s: no Module drops")
        }
      }
    }

    it("disables only the Modules reporting speed degrade") {
      // Figure 4-48 labels that box "Modules reporting speed degrade disabled",
      // and routes the survivors back through connector 1. Here M2 asks for a
      // speed degrade at 4 GT/s where none is possible, so M2 goes -- taking
      // its same-half partner M3 with it under rule 2 -- and the remaining
      // {M0, M1}, which had asked for a width degrade, are re-evaluated and
      // width degrade as a two-Module Link.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val reports =
          Seq(sentRepairReq, sentRepairReq, sentSpeedReq, clean)
        val out = apply(c, reports, allEnabled, SpeedMode.speed4, 4)
        assert(
          out.link == "repair",
          s"expected the survivors to width degrade, got ${out.link}"
        )
        assert(
          out.nextEnable == Seq(true, true, false, false),
          s"expected {M0, M1} to survive, got ${out.nextEnable}"
        )
      }
    }

    it("takes the whole Link to PHYRETRAIN on one Module's request") {
      // Spec 4.5.3.4.12 Step 5: an {exit to phy retrain req} received on any
      // Module of the Link is a directive for all of them, and it must not wait
      // on the Modules that are still reporting.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        c.io.currentSpeed.poke(SpeedMode.speed16)
        for (m <- 0 until 4) {
          c.io.enable(m).poke(true.B)
          c.io.narrowerThanPeers(m).poke(false.B)
        }
        // Only M2 has anything to say, and it is heading for PHYRETRAIN.
        driveReport(c.io.reports(0), clean, valid = false)
        driveReport(c.io.reports(1), clean, valid = false)
        driveReport(c.io.reports(2), recvdPhyRetrainReq, valid = true)
        driveReport(c.io.reports(3), clean, valid = false)

        assert(
          c.io.resolved.peek().litToBoolean,
          "a PHY retrain must resolve without waiting for the other Modules"
        )
        assert(
          resolutionName(c.io.linkResolution.peek().litValue) == "phyRetrain",
          "the Link must be directed to PHYRETRAIN"
        )
        for (m <- 0 until 4) {
          assert(
            c.io.nextEnable(m).peek().litToBoolean,
            s"Module $m must stay in the Link across a PHY retrain"
          )
          assert(
            resolutionName(c.io.moduleResolution(m).peek().litValue) ==
              "phyRetrain",
            s"Module $m was not directed to PHYRETRAIN"
          )
        }
      }
    }

    it("does not resolve while an operational Module has yet to report") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        c.io.currentSpeed.poke(SpeedMode.speed16)
        for (m <- 0 until 4) {
          c.io.enable(m).poke(true.B)
          c.io.narrowerThanPeers(m).poke(false.B)
          driveReport(c.io.reports(m), clean, valid = m != 3)
        }
        assert(
          !c.io.resolved.peek().litToBoolean,
          "resolved with Module 3 still silent"
        )
        driveReport(c.io.reports(3), clean, valid = true)
        assert(c.io.resolved.peek().litToBoolean, "did not resolve")
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
    val noneNarrow = Seq.fill(numModules)(false)

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
          val expected = model(reports, noneNarrow, enable, gts, numModules)
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
    val noneNarrow = Seq.fill(numModules)(false)
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
            out == model(reports, noneNarrow, enable, gts, numModules),
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
            out == model(reports, noneNarrow, enable, gts, numModules),
            s"$context: got $out"
          )
          checkProperties(c, out, enable, numModules, context)
        }
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
