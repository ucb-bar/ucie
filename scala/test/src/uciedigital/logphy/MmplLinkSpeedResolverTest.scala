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
  * Table 5-29 counts failed Module-pairs, whichever way each one left -- by
  * failing before it reported or through a disable arc of Figure 4-48 -- so its
  * columns are also driven through every mix of those routes.
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

  /** One trip through the decision diamonds of Figure 4-48.
    *
    * `txLanes` and `rxLanes` are the Lanes each Module drives and receives on.
    * Spec 4.7.1.2.1's "lower ... from the rest of the operational modules" is
    * relative to whichever Modules are still in the Link on this pass, in
    * either direction, so the widest is recomputed here rather than fixed once.
    */
  private def decideOnce(
      reports: Seq[Report],
      txLanes: Seq[Int],
      rxLanes: Seq[Int],
      active: Set[Int],
      speedGTs: Int,
      standard: Boolean
  ): (String, Set[Int]) = {
    val ladder = speeds.map(_._2)
    val idx = ladder.indexOf(speedGTs)
    val nextLower = if (idx == 0) 0 else ladder(idx - 1)

    val widestTx = active.map(txLanes).max
    val widestRx = active.map(rxLanes).max
    val widthReq = active.filter { m =>
      reports(m).sentRepair || reports(m).recvdRepair ||
      txLanes(m) < widestTx || rxLanes(m) < widestRx
    }
    val speedReq = active.filter { m =>
      reports(m).sentSpeedDegrade || reports(m).recvdSpeedDegrade
    }

    if (widthReq.isEmpty && speedReq.isEmpty) ("done", Set.empty)
    else if (speedReq.isEmpty) {
      if (!standard) ("repair", Set.empty)
      else if (2 * widthReq.size > active.size) {
        // Rule 1.b's pseudo code.
        if (speedGTs == 4) ("repair", Set.empty)
        else if (2 * nextLower > speedGTs) ("speedDegrade", Set.empty)
        else ("repair", Set.empty)
      } else ("disable", widthReq) // Rule 1.a
    } else if (speedGTs / 2 > nextLower) ("disable", speedReq)
    else ("speedDegrade", Set.empty)
  }

  /** The whole chart including the loop back through connector 1, with spec
    * 5.7.3.4.1 applied to the resolution as a whole.
    *
    * The Modules that failed before reporting are simply not in the first set.
    * Each disable arc shrinks the set and goes round again. Once the chart
    * stops disabling, rule 2 is decided exactly once: a Link that started with
    * four Modules and settled on three loses the other Module of the missing
    * one's half, and the chart runs again on the pair left -- the partner may
    * have been the one asking for something. Rule 1 needs nothing further: the
    * chart never leaves three Modules any other way.
    */
  private def model(
      reports: Seq[Report],
      enable: Seq[Boolean],
      speedGTs: Int,
      numModules: Int,
      standard: Boolean = true,
      failed: Seq[Boolean] = Seq.empty,
      txLanes: Seq[Int] = Seq.empty,
      rxLanes: Seq[Int] = Seq.empty
  ): Outcome = {
    if ((0 until numModules).exists(m => enable(m) && reports(m).phyRetrain))
      return Outcome("phyRetrain", enable)

    val fail = if (failed.isEmpty) Seq.fill(numModules)(false) else failed
    val tx =
      if (txLanes.isEmpty) Seq.fill(numModules)(fullWidthLanes) else txLanes
    val rx =
      if (rxLanes.isEmpty) Seq.fill(numModules)(fullWidthLanes) else rxLanes
    val startedFull = numModules == 4 && enable.forall(identity)
    val trainError = Outcome("trainError", Seq.fill(numModules)(false))
    def asSeq(set: Set[Int]) = (0 until numModules).map(set.contains)

    // Every step either settles or strictly shrinks the set, so this ends.
    @annotation.tailrec
    def resolve(active: Set[Int]): Outcome =
      if (active.isEmpty) trainError
      else
        decideOnce(reports, tx, rx, active, speedGTs, standard) match {
          case ("disable", doomed) =>
            val left = active -- doomed
            if (left.isEmpty) trainError else resolve(left)
          case (link, _) =>
            if (startedFull && active.size == 3) {
              val missing = (0 until 4).filterNot(active).head
              resolve(active - (missing ^ 1))
            } else Outcome(link, asSeq(active))
        }

    resolve((0 until numModules).filter(m => enable(m) && !fail(m)).toSet)
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

  /** Lane count standing in for "already width degraded" vs "full width". The
    * resolver compares counts rather than taking a precomputed narrow bit, so a
    * Link whose Modules are all degraded to the same width has no narrow Module
    * -- which is the behaviour spec 4.7.1.2.1 asks for.
    */
  private val fullWidthLanes = 16
  private val degradedLanes = 8
  private def laneCountFor(narrow: Boolean): Int =
    if (narrow) degradedLanes else fullWidthLanes

  private def apply(
      c: MmplLinkSpeedResolver,
      reports: Seq[Report],
      enable: Seq[Boolean],
      speed: SpeedMode.Type,
      numModules: Int,
      narrower: Seq[Boolean] = Seq.empty,
      rxNarrower: Seq[Boolean] = Seq.empty,
      failed: Seq[Boolean] = Seq.empty,
      txLanes: Seq[Int] = Seq.empty,
      rxLanes: Seq[Int] = Seq.empty,
      staleFailedReports: Boolean = false
  ): Outcome = {
    val narrow =
      if (narrower.isEmpty) Seq.fill(numModules)(false) else narrower
    val rxNarrow =
      if (rxNarrower.isEmpty) Seq.fill(numModules)(false) else rxNarrower
    val fail = if (failed.isEmpty) Seq.fill(numModules)(false) else failed
    val tx =
      if (txLanes.isEmpty) narrow.map(laneCountFor) else txLanes
    val rx =
      if (rxLanes.isEmpty) rxNarrow.map(laneCountFor) else rxLanes
    c.io.currentSpeed.poke(speed)
    for (m <- 0 until numModules) {
      c.io.enable(m).poke(enable(m).B)
      c.io.activeLanes(m).poke(tx(m).U)
      c.io.activeRxLanes(m).poke(rx(m).U)
      c.io.failed(m).poke((enable(m) && fail(m)).B)
      /* A Module that fell out of training has normally withdrawn its report;
         `staleFailedReports` leaves whatever it last said on the port, which
         the resolver must ignore just the same. */
      driveReport(
        c.io.reports(m),
        reports(m),
        enable(m) && (!fail(m) || staleFailedReports)
      )
    }
    /* The block is combinational, but a Chisel `assert` only samples on a clock
       edge -- without a step the whole Verification layer never evaluates and
       the design's own spec checks are dead weight in every test. */
    c.clock.step()
    assert(
      c.io.resolved.peek().litToBoolean,
      "resolver did not report resolved"
    )
    Outcome(
      resolutionName(c.io.linkResolution.peek().litValue),
      (0 until numModules).map(m => c.io.nextEnable(m).peek().litToBoolean)
    )
  }

  private def moduleResolutions(
      c: MmplLinkSpeedResolver,
      numModules: Int
  ): Seq[String] =
    (0 until numModules).map(m =>
      resolutionName(c.io.moduleResolution(m).peek().litValue)
    )

  /** Everything one die's resolution is a function of. */
  private case class Stimulus(
      reports: Seq[Report],
      enable: Seq[Boolean],
      failed: Seq[Boolean],
      txLanes: Seq[Int],
      rxLanes: Seq[Int],
      speed: SpeedMode.Type,
      gts: Int
  ) {
    def numModules: Int = reports.length

    /** The same Link seen from the other die. `pairing(m)` is the remote Module
      * Partner of local Module m (spec Tables 5-27 / 5-28). The partner sent
      * what m received and vice versa, drives what m receives on, and is
      * enabled and failed together with m -- a failure is of a Module-pair.
      */
    def fromRemote(pairing: Seq[Int]): Stimulus = {
      val local = pairing.indices.map(j => pairing.indexOf(j))
      Stimulus(
        local.map(reports(_).mirrored),
        local.map(enable),
        local.map(failed),
        local.map(rxLanes),
        local.map(txLanes),
        speed,
        gts
      )
    }

    def expected: Outcome =
      model(
        reports,
        enable,
        gts,
        numModules,
        failed = failed,
        txLanes = txLanes,
        rxLanes = rxLanes
      )

    override def toString: String = {
      val mods = (0 until numModules).map { m =>
        val tag =
          if (!enable(m)) "off"
          else if (failed(m)) "failed"
          else
            alphabet
              .collectFirst {
                case (name, r) if r == reports(m) => name
              }
              .getOrElse(reports(m).toString)
        s"M$m:$tag tx${txLanes(m)} rx${rxLanes(m)}"
      }
      s"${gts}GT/s [${mods.mkString(" | ")}]"
    }
  }

  private def run(
      c: MmplLinkSpeedResolver,
      st: Stimulus,
      staleFailedReports: Boolean = false
  ): (Outcome, Seq[String]) = {
    val out = apply(
      c,
      st.reports,
      st.enable,
      st.speed,
      st.numModules,
      failed = st.failed,
      txLanes = st.txLanes,
      rxLanes = st.rxLanes,
      staleFailedReports = staleFailedReports
    )
    (out, moduleResolutions(c, st.numModules))
  }

  /** Standard Die Rotate pairing for x4 to x4 (Table 5-27): M0-M2, M1-M3. It
    * maps each half along the Die Edge onto the other die's opposite half,
    * which is what makes rule 2's "same half" the same pair on both die.
    */
  private val dieRotate4 = Seq(2, 3, 0, 1)
  private val dieRotate2 = Seq(1, 0)

  /** Every x4 to x4 pairing Table 5-27 lists: identity (Mirrored Die Rotate
    * Option 1), Standard Die Rotate, and Mirrored Die Rotate Option 2 for x4
    * Stacked (M0-M1, M2-M3), which keeps each half on its own side.
    */
  private val pairings4 =
    Seq(Seq(0, 1, 2, 3), dieRotate4, Seq(1, 0, 3, 2))

  /** Both die, fed the same Link from either end, must direct every Module-pair
    * the same way, or the Step 5d cross-check takes the Link to TRAINERROR.
    */
  private def checkDieSymmetry(
      c: MmplLinkSpeedResolver,
      st: Stimulus,
      local: (Outcome, Seq[String]),
      pairing: Seq[Int],
      context: String
  ): Unit = {
    val (out, mods) = local
    val (rOut, rMods) = run(c, st.fromRemote(pairing))
    assert(
      rOut.link == out.link,
      s"$context: local resolved ${out.link}, remote ${rOut.link}"
    )
    for (m <- 0 until st.numModules) {
      val p = pairing(m)
      assert(
        rOut.nextEnable(p) == out.nextEnable(m) && rMods(p) == mods(m),
        s"$context: local M$m ${mods(m)}/${out.nextEnable(m)} but its " +
          s"partner M$p ${rMods(p)}/${rOut.nextEnable(p)}"
      )
    }
  }

  /** Spec 5.7.3.4.1 read directly off the stimulus, independent of the model.
    *
    * A Module that failed never survives. A Module with nothing against it --
    * it did not fail, asked for nothing, and is full width in both directions
    * among the Modules that trained -- can only leave as rule 2's padding: at
    * most one of them, only on a Link that started with four Modules, and only
    * together with the other Module of its half.
    */
  private def checkDegradeRules(
      st: Stimulus,
      out: Outcome,
      context: String
  ): Unit = {
    if (out.link == "trainError" || out.link == "phyRetrain") return
    val n = st.numModules
    val trained = (0 until n).filter(m => st.enable(m) && !st.failed(m))
    val widestTx = trained.map(st.txLanes).max
    val widestRx = trained.map(st.rxLanes).max
    for (m <- 0 until n if st.failed(m) && st.enable(m)) {
      assert(!out.nextEnable(m), s"$context: failed Module $m survived")
    }
    val removedBlameless = trained.filter { m =>
      !out.nextEnable(m) && !st.reports(m).errored &&
      st.txLanes(m) == widestTx && st.rxLanes(m) == widestRx
    }
    assert(
      removedBlameless.size <= 1,
      s"$context: disabled Modules $removedBlameless that nothing was wrong with"
    )
    removedBlameless.foreach { m =>
      assert(
        n == 4 && st.enable.forall(identity) && !out.nextEnable(m ^ 1),
        s"$context: Module $m was disabled without its half failing"
      )
    }
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
      if (!enable(m)) {
        // A Module an earlier resolution already dropped is not in the Link and
        // has no expected response (spec 4.5.3.4.12 Step 5d), so it must be
        // named nothing rather than handed the Link's outcome.
        assert(
          perModule == "none",
          s"$context: Module $m is not in the Link but was told $perModule"
        )
      } else if (out.link == "trainError") {
        assert(
          perModule == "trainError",
          s"$context: Module $m was told $perModule, Link resolved trainError"
        )
      } else if (!out.nextEnable(m)) {
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
          apply(
            c,
            Seq(sentRepairReq, clean),
            Seq(true, true),
            SpeedMode.speed16,
            2
          )
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

    it(
      "stops calling a Module narrow once the wide Modules have been dropped"
    ) {
      /* Spec 4.7.1.2.1 measures "lower ... from the rest of the operational
         modules", and the flow chart's connector 1 re-enters the decision with
         the reduced Module set -- so the comparison has to be remade against
         the survivors. A narrow bit computed once against the starting set
         still flags the survivors of a disable as narrow after the very
         Modules that made them narrow have gone, sending an already-uniform
         Link to MBTRAIN.REPAIR to halve a width that needed no halving. */
      simulate(new MmplLinkSpeedResolver(MmplParams(numModules = 2))) { c =>
        // M0 is full width and cannot degrade further at 4 GT/s; M1 already
        // went to x8 in MBINIT.REPAIRMB and is otherwise clean.
        val out = apply(
          c,
          Seq(sentSpeedReq, clean),
          Seq(true, true),
          SpeedMode.speed4,
          2,
          narrower = Seq(false, true)
        )
        assert(
          out.nextEnable == Seq(false, true),
          s"expected M0 disabled and M1 to survive, got ${out.nextEnable}"
        )
        assert(
          out.link == "done",
          s"M1 is alone and uniform at x8, so the Link proceeds to LINKINIT; " +
            s"got ${out.link}"
        )
      }

      // The same on a four-Module Link, where rule 2 takes M1 down with M0 and
      // leaves the two narrow Modules as a matched pair.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val out = apply(
          c,
          Seq(sentSpeedReq, clean, clean, clean),
          allEnabled,
          SpeedMode.speed4,
          4,
          narrower = Seq(false, false, true, true)
        )
        assert(
          out.nextEnable == Seq(false, false, true, true),
          s"expected {M2, M3} to survive, got ${out.nextEnable}"
        )
        assert(
          out.link == "done",
          s"the survivors are both x8, so nothing is narrower; got ${out.link}"
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
          c.io.activeLanes(m).poke(fullWidthLanes.U)
          c.io.activeRxLanes(m).poke(fullWidthLanes.U)
          c.io.failed(m).poke(false.B)
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
          c.io.activeLanes(m).poke(fullWidthLanes.U)
          c.io.activeRxLanes(m).poke(fullWidthLanes.U)
          c.io.failed(m).poke(false.B)
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

    it("judges narrowness on both directions, the same way from both die") {
      /* A Module's two directions can leave MBINIT.REPAIRMB at different
         widths, and each die's receive width is the other die's transmit
         width. Judged on transmit alone, die A would see nothing narrow while
         die B saw its Module 0 narrow, direct a different response, and the
         Step 5d mismatch would take the Link to TRAINERROR. Identity pairing,
         so die B's Module m is die A's Module m seen from the other end. */
      val two = MmplParams(numModules = 2)
      simulate(new MmplLinkSpeedResolver(two)) { c =>
        for (
          (name, txA, rxA) <- Seq(
            (
              "one Module narrow in one direction",
              Seq(false, false),
              Seq(true, false)
            ),
            (
              "the Modules narrow in opposite directions",
              Seq(false, true),
              Seq(true, false)
            )
          );
          speed <- Seq(SpeedMode.speed8, SpeedMode.speed16)
        ) {
          val onA = apply(
            c,
            Seq(clean, clean),
            Seq(true, true),
            speed,
            2,
            narrower = txA,
            rxNarrower = rxA
          )
          val onB = apply(
            c,
            Seq(clean.mirrored, clean.mirrored),
            Seq(true, true),
            speed,
            2,
            narrower = rxA,
            rxNarrower = txA
          )
          assert(
            onA == onB,
            s"$name at $speed: die A resolved $onA, die B $onB"
          )
          assert(
            onA.link != "done" || onA.nextEnable.contains(false),
            s"$name at $speed: a narrow Module was missed: $onA"
          )
        }
        // Mixed widths on different Modules must never reach LINKINIT as-is:
        // the receive gather reads one Lane code for every Module.
        val mixed = apply(
          c,
          Seq(clean, clean),
          Seq(true, true),
          SpeedMode.speed8,
          2,
          narrower = Seq(false, true),
          rxNarrower = Seq(true, false)
        )
        assert(mixed.link == "repair", s"expected a width degrade, got $mixed")
      }
    }

    it("disables a Module that failed to train and resolves the rest") {
      /* Spec 4.7.1: "if any module failed to train, the MMPL must ensure that
         the multi-module configuration degrades to the next permitted
         configuration". Module 0 never reported; the others did. It counts as
         reported, and is disabled with the other Module of its half. */
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        c.io.currentSpeed.poke(SpeedMode.speed16)
        for (m <- 0 until 4) {
          c.io.enable(m).poke(true.B)
          c.io.activeLanes(m).poke(fullWidthLanes.U)
          c.io.activeRxLanes(m).poke(fullWidthLanes.U)
          c.io.failed(m).poke((m == 0).B)
          driveReport(c.io.reports(m), clean, valid = m != 0)
        }
        c.clock.step()
        assert(
          c.io.resolved.peek().litToBoolean,
          "the failed Module blocked it"
        )
        assert(resolutionName(c.io.linkResolution.peek().litValue) == "done")
        assert(
          (0 until 4)
            .filter(c.io.nextEnable(_).peek().litToBoolean) == Seq(2, 3),
          "expected Modules 2 and 3 to carry on"
        )
        for (m <- 0 until 2) {
          assert(
            resolutionName(c.io.moduleResolution(m).peek().litValue) ==
              "disableModule",
            s"Module $m should be disabled"
          )
        }

        // With nothing left to carry the Link, it is a training error.
        for (m <- 0 until 4) c.io.failed(m).poke(true.B)
        for (m <- 0 until 4) driveReport(c.io.reports(m), clean, valid = false)
        c.clock.step()
        assert(
          resolutionName(c.io.linkResolution.peek().litValue) == "trainError"
        )
      }
    }
  }

  // ==========================================================================
  // Rule 2 decided once, over every way a Module-pair leaves
  // ==========================================================================
  /* Spec 4.7.1: "if any module failed to train, the MMPL must ensure that the
     multi-module configuration degrades to the next permitted configuration".
     A pair can leave the Link three ways -- failing before it reports, a
     speed degrade request at 4 GT/s where there is no lower speed, or a
     minority width degrade request -- and Table 5-29 counts failed pairs, not
     the route each took. So whichever mix of routes takes a column's pairs
     out, the survivors must be the column's. Padding the first lone Module to
     leave, before the rest have been seen, gets every mixed two- and
     three-fail column wrong. */
  private def stimulus4(
      reports: Seq[Report],
      speed: SpeedMode.Type,
      gts: Int,
      failed: Set[Int] = Set.empty
  ): Stimulus = Stimulus(
    reports,
    Seq.fill(4)(true),
    (0 until 4).map(failed),
    Seq.fill(4)(fullWidthLanes),
    Seq.fill(4)(fullWidthLanes),
    speed,
    gts
  )

  private def survivorSet(out: Outcome): Set[Int] =
    out.nextEnable.indices.filter(out.nextEnable(_)).toSet

  /** Every way of picking one element from each of `choices`, in order. */
  private def cartesian[A](choices: Seq[Seq[A]]): Seq[Seq[A]] =
    choices.foldLeft(Seq(Seq.empty[A])) { (acc, options) =>
      acc.flatMap(prefix => options.map(prefix :+ _))
    }

  private val routeKinds = Seq("failed", "speed", "width")

  describe("MmplLinkSpeedResolver with Modules that failed before reporting") {
    val params = MmplParams(numModules = 4)

    /** One directed case: M`failed` fell out of training, the rest report as
      * given; `expected` is the Table 5-29 survivor set, all proceeding to
      * LINKINIT.
      */
    def expectSurvivors(
        c: MmplLinkSpeedResolver,
        st: Stimulus,
        expected: Set[Int],
        context: String,
        link: String = "done"
    ): Unit = {
      val local = run(c, st)
      val (out, mods) = local
      assert(out.link == link, s"$context: expected $link, got ${out.link}")
      assert(
        survivorSet(out) == expected,
        s"$context: Table 5-29 requires survivors $expected, got " +
          s"${survivorSet(out)}"
      )
      for (m <- 0 until 4) {
        val want = if (expected(m)) link else "disableModule"
        assert(mods(m) == want, s"$context: Module $m was told ${mods(m)}")
      }
      assert(out == st.expected, s"$context: model says ${st.expected}")
      checkProperties(c, out, st.enable, 4, context)
      checkDegradeRules(st, out, context)
      checkDieSymmetry(c, st, local, dieRotate4, context)
    }

    it("keeps {M1, M3} when M0 failed and M2 cannot degrade from 4 GT/s") {
      // Two pairs failed, M0 before reporting and M2 on the HMLS/2 > CMLS Yes
      // arc: Table 5-29's 2-fail M0,M2 column. Padding M0 on its own first
      // took M1 as well and left {M3}.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        expectSurvivors(
          c,
          stimulus4(
            Seq(clean, clean, sentSpeedReq, clean),
            SpeedMode.speed4,
            4,
            failed = Set(0)
          ),
          Set(1, 3),
          "M0 failed, M2 speed degrade at 4 GT/s"
        )
      }
    }

    it("keeps {M1, M2} when M0 failed and M3 asks for a width degrade") {
      // One of the three trained Modules asks, which is not a majority, so
      // Rule 1.a disables it: Table 5-29's 2-fail M0,M3 column.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        expectSurvivors(
          c,
          stimulus4(
            Seq(clean, clean, clean, sentRepairReq),
            SpeedMode.speed8,
            8,
            failed = Set(0)
          ),
          Set(1, 2),
          "M0 failed, M3 width degrade at 8 GT/s"
        )
      }
    }

    it("keeps the one-Module Link {M1} when M0 failed and M2, M3 cannot") {
      // Three pairs failed, rule 1.a.iii. Padding M0 first left nothing to
      // take M2 and M3 out of and ended in TRAINERROR.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        expectSurvivors(
          c,
          stimulus4(
            Seq(clean, clean, sentSpeedReq, recvdSpeedReq),
            SpeedMode.speed4,
            4,
            failed = Set(0)
          ),
          Set(1),
          "M0 failed, M2 and M3 speed degrade at 4 GT/s"
        )
      }
    }

    it("keeps {M1, M3} when M0 and M2 both failed before reporting") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        expectSurvivors(
          c,
          stimulus4(
            Seq.fill(4)(clean),
            SpeedMode.speed16,
            16,
            failed = Set(0, 2)
          ),
          Set(1, 3),
          "M0 and M2 failed"
        )
      }
    }

    it("pads a lone failure before reporting with its same-half partner") {
      // Rule 2 proper: M0 is the only pair that failed.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        expectSurvivors(
          c,
          stimulus4(Seq.fill(4)(clean), SpeedMode.speed16, 16, failed = Set(0)),
          Set(2, 3),
          "M0 failed alone"
        )
      }
    }

    it("decides rule 2 across the flow chart's own passes too") {
      /* At 4 GT/s M0 asks for a speed degrade and M2 for a width degrade. The
         speed arc disables M0 and goes round connector 1; on the three left,
         M2 is a minority and is disabled too. Two pairs failed, so Table 5-29
         keeps {M1, M3} -- padding M0 when it left alone took M1 and left
         {M3}. */
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        expectSurvivors(
          c,
          stimulus4(
            Seq(sentSpeedReq, clean, sentRepairReq, clean),
            SpeedMode.speed4,
            4
          ),
          Set(1, 3),
          "M0 speed degrade, M2 width degrade at 4 GT/s"
        )
      }
    }

    it("re-runs the flow chart once rule 2 has taken the partner") {
      /* M0 failed and M1 asks for a speed degrade at 16 GT/s. On the three
         trained Modules the chart settles on a speed degrade, but three is not
         a permitted count: rule 2 takes M1, and on {M2, M3} nothing is wrong,
         so they go to LINKINIT rather than degrading speed for a Module that
         is no longer in the Link. */
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        expectSurvivors(
          c,
          stimulus4(
            Seq(clean, sentSpeedReq, clean, clean),
            SpeedMode.speed16,
            16,
            failed = Set(0)
          ),
          Set(2, 3),
          "M0 failed, M1 speed degrade at 16 GT/s"
        )
        // And the survivors' own request still counts once they are the pair.
        expectSurvivors(
          c,
          stimulus4(
            Seq(clean, clean, recvdSpeedReq, clean),
            SpeedMode.speed16,
            16,
            failed = Set(0)
          ),
          Set(2, 3),
          "M0 failed, M2 speed degrade at 16 GT/s",
          link = "speedDegrade"
        )
      }
    }

    it("runs the chart again on the pair rule 2 leaves, down to one Module") {
      /* The longest chain four Modules allow, one Module per pass. At 4 GT/s
         M0 asks for a speed degrade, M2 and M3 left MBINIT.REPAIRMB at x8 and
         M2 also asks for a width degrade:
           1. the speed arc disables M0;
           2. on {M1, M2, M3} two of three are narrower than M1, a majority, so
              the chart settles on a width degrade -- but three Modules is not a
              permitted count, and rule 2 takes M1;
           3. on {M2, M3} both are x8, so only M2's own request is left, a
              minority, and it is disabled;
           4. M3 alone is clean and proceeds.
         So the resolver needs one pass per Module, and the answer has to be
         read from the pass that settled rather than from wherever the chain
         happens to stop. */
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val st = Stimulus(
          Seq(sentSpeedReq, clean, sentRepairReq, clean),
          Seq.fill(4)(true),
          Seq.fill(4)(false),
          Seq(fullWidthLanes, fullWidthLanes, degradedLanes, degradedLanes),
          Seq.fill(4)(fullWidthLanes),
          SpeedMode.speed4,
          4
        )
        val local = run(c, st)
        val (out, _) = local
        assert(
          out == Outcome("done", Seq(false, false, false, true)),
          s"expected M3 to carry on alone, got $out"
        )
        assert(out == st.expected, s"model says ${st.expected}")
        checkProperties(c, out, st.enable, 4, "four-pass chain")
        checkDegradeRules(st, out, "four-pass chain")
        checkDieSymmetry(c, st, local, dieRotate4, "four-pass chain")
      }
    }

    it("never pads a Link that did not start with four Modules") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        // Two-Module Links an earlier resolution left, straddling the halves or
        // not, and a lone failure before reporting on each.
        for (
          (enabled, fails) <- Seq(
            (Set(2, 3), 2),
            (Set(0, 1), 1),
            (Set(1, 3), 3),
            (Set(0, 2), 0)
          )
        ) {
          val st = Stimulus(
            Seq.fill(4)(clean),
            (0 until 4).map(enabled),
            (0 until 4).map(_ == fails),
            Seq.fill(4)(fullWidthLanes),
            Seq.fill(4)(fullWidthLanes),
            SpeedMode.speed16,
            16
          )
          val context = s"enabled $enabled, M$fails failed"
          val (out, _) = run(c, st)
          assert(out.link == "done", s"$context: got ${out.link}")
          assert(
            survivorSet(out) == enabled - fails,
            s"$context: expected ${enabled - fails}, got ${survivorSet(out)}"
          )
          checkDegradeRules(st, out, context)
        }
      }
    }

    it("ignores what a Module that failed last said") {
      // A stale report on the port of a failed Module must not steer the rest.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        for ((name, r) <- alphabet) {
          val st = stimulus4(
            Seq(r, clean, clean, clean),
            SpeedMode.speed4,
            4,
            failed = Set(0)
          )
          val (out, _) = run(c, st, staleFailedReports = true)
          assert(
            out == Outcome("done", Seq(false, false, true, true)),
            s"M0 failed with a stale '$name': got $out"
          )
        }
      }
    }

    it("reaches every Table 5-29 column by any mix of failure routes") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        for ((column, pairs, expected) <- table5_29 ++ table5_29ThreeFail) {
          val ordered = pairs.toSeq.sorted
          // Each failed pair either fails before reporting, or is disabled by
          // the chart -- on the speed arc at 4 GT/s, or the width arc at 16
          // GT/s when the requests are a minority of the Modules that trained.
          for (routes <- cartesian(ordered.map(_ => routeKinds))) {
            val route = ordered.zip(routes).toMap
            val failed = pairs.filter(route(_) == "failed")
            val bySpeed = pairs.filter(route(_) == "speed")
            val byWidth = pairs.filter(route(_) == "width")
            val trained = 4 - failed.size
            val usable =
              (bySpeed.isEmpty || byWidth.isEmpty) &&
                (byWidth.isEmpty || 2 * byWidth.size <= trained)
            if (usable) {
              val (speed, gts) =
                if (byWidth.nonEmpty) (SpeedMode.speed16, 16)
                else (SpeedMode.speed4, 4)
              val reports = (0 until 4).map { m =>
                if (bySpeed(m)) sentSpeedReq
                else if (byWidth(m)) recvdRepairReq
                else clean
              }
              expectSurvivors(
                c,
                stimulus4(reports, speed, gts, failed = failed),
                expected,
                s"$column: failed $failed, speed $bySpeed, width $byWidth"
              )
            }
          }
        }
      }
    }
  }

  // ==========================================================================
  // Exhaustive enumeration
  // ==========================================================================
  private def combinations(
      numModules: Int,
      symbols: Seq[(String, Report)] = alphabet
  ): Seq[Seq[(String, Report)]] =
    cartesian(Seq.fill(numModules)(symbols))

  /** The report alphabet plus a Module that fell out of training before it
    * reported. Its report is never looked at.
    */
  private val failedSymbol = "failed"
  private val alphabetWithFailure = alphabet :+ (failedSymbol -> clean)

  private def stimulusOf(
      combo: Seq[(String, Report)],
      enable: Seq[Boolean],
      speed: SpeedMode.Type,
      gts: Int
  ): Stimulus = Stimulus(
    combo.map(_._2),
    enable,
    combo.map(_._1 == failedSymbol),
    Seq.fill(combo.size)(fullWidthLanes),
    Seq.fill(combo.size)(fullWidthLanes),
    speed,
    gts
  )

  describe("MmplLinkSpeedResolver exhaustively, two modules") {
    val numModules = 2
    val params = MmplParams(numModules = numModules)

    it("matches the reference model at every Link speed") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val enable = Seq.fill(numModules)(true)
        for {
          (speed, gts) <- speeds
          combo <- combinations(numModules, alphabetWithFailure)
        } {
          val st = stimulusOf(combo, enable, speed, gts)
          val context = st.toString
          val (out, _) = run(c, st)
          val expected = st.expected
          assert(
            out == expected,
            s"$context: got $out, model says $expected"
          )
          checkProperties(c, out, enable, numModules, context)
          checkDegradeRules(st, out, context)
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
          combo <- combinations(numModules, alphabetWithFailure)
          pairing <- Seq(Seq(0, 1), dieRotate2)
        } {
          val st = stimulusOf(combo, enable, speed, gts)
          checkDieSymmetry(c, st, run(c, st), pairing, s"$st via $pairing")
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

    it("matches the reference model and stays consistent across the die") {
      // Every report and failure pattern, checked against the model, against
      // spec 5.7.3.4.1 read directly, and from the other die under every
      // x4 to x4 pairing of Table 5-27.
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        val enable = Seq.fill(numModules)(true)
        for {
          (speed, gts) <- sampledSpeeds
          combo <- combinations(numModules, alphabetWithFailure)
        } {
          val st = stimulusOf(combo, enable, speed, gts)
          val context = st.toString
          val local = run(c, st)
          val (out, _) = local
          assert(
            out == st.expected,
            s"$context: got $out, model says ${st.expected}"
          )
          checkProperties(c, out, enable, numModules, context)
          checkDegradeRules(st, out, context)
          for (pairing <- pairings4)
            checkDieSymmetry(c, st, local, pairing, s"$context via $pairing")
        }
      }
    }

    it("resolves from an already degraded two-module configuration") {
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        // An earlier resolution left one half, or a pair straddling them.
        for {
          enable <- Seq(
            Seq(false, false, true, true),
            Seq(false, true, true, false)
          )
          (speed, gts) <- sampledSpeeds
          combo <- combinations(numModules, alphabetWithFailure)
        } {
          val st = stimulusOf(combo, enable, speed, gts)
          val context = s"degraded ${enable.mkString(",")} $st"
          val local = run(c, st)
          val (out, _) = local
          assert(out == st.expected, s"$context: got $out")
          checkProperties(c, out, enable, numModules, context)
          checkDegradeRules(st, out, context)
          checkDieSymmetry(c, st, local, dieRotate4, context)
        }
      }
    }

    it("matches the reference model on random widths, failures and Links") {
      /* The sweeps above keep every Module at full width. Here the Lane
         counts vary per direction, so "narrower than the rest" appears and
         disappears as the set shrinks, alongside failures before reporting,
         stale reports on failed Modules and the Links an earlier resolution
         leaves. Seeded, so a failure reproduces. */
      val rng = new scala.util.Random(0x5729)
      val enables: Seq[Set[Int]] = Seq(
        Set(0, 1, 2, 3),
        Set(0, 1, 2, 3),
        Set(0, 1, 2, 3),
        Set(0, 1, 2, 3),
        Set(0, 1),
        Set(2, 3),
        Set(0, 2),
        Set(1, 3),
        Set(0, 3),
        Set(1, 2),
        Set(0),
        Set(3)
      )
      val laneChoices = Seq(16, 16, 8, 4)
      simulate(new MmplLinkSpeedResolver(params)) { c =>
        for (_ <- 0 until 4000) {
          val enabled = enables(rng.nextInt(enables.size))
          val (speed, gts) = speeds(rng.nextInt(speeds.size))
          val st = Stimulus(
            Seq.fill(numModules)(alphabet(rng.nextInt(alphabet.size))._2),
            (0 until numModules).map(enabled),
            Seq.fill(numModules)(rng.nextInt(5) == 0),
            Seq.fill(numModules)(laneChoices(rng.nextInt(laneChoices.size))),
            Seq.fill(numModules)(laneChoices(rng.nextInt(laneChoices.size))),
            speed,
            gts
          )
          val context = st.toString
          val local = run(c, st, staleFailedReports = rng.nextBoolean())
          val (out, _) = local
          assert(
            out == st.expected,
            s"$context: got $out, model says ${st.expected}"
          )
          checkProperties(c, out, st.enable, numModules, context)
          checkDegradeRules(st, out, context)
          val pairing = pairings4(rng.nextInt(pairings4.size))
          checkDieSymmetry(c, st, local, pairing, s"$context via $pairing")
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
