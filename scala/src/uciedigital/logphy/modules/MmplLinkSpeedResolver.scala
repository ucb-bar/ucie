/*
  Description:
    MBTRAIN.LINKSPEED resolution for a multi-module Link (spec 4.7.1,
    Figure 4-47 for Advanced Package and Figure 4-48 for Standard Package).

  Every Module trains independently through Step 2 of MBTRAIN.LINKSPEED and then
  reports the message it sent and the message it received. This block collects
  those reports, decides one next state for the whole Link, and names the Modules
  that must be disabled to reach it. Both die reach the same answer because both
  resolve from the same (sent, received) pairs.

  NOTE:
 * The Common Minimum Link Width cancels out of the Standard Package bandwidth
   comparison: AggBW(M, CLS-1) > AggBW(M/2, CLS) is
   CommonMinWidth * M * (CLS-1) > CommonMinWidth * (M/2) * CLS, which reduces to
   2 * (CLS-1) > CLS. So the width is not an input here.
 * HMLS is the current Link speed and CMLS the next lower one, so HMLS/2 > CMLS
   holds only at 4 GT/s. That is the base case the spec calls out: a Module that
   passed MBINIT but failed 4 GT/s is disabled rather than taking the Link to
   TRAINERROR.
 * Disable granularity follows spec 5.7.3.4.1. Rule 1 fixes the surviving count:
   on a four-Module Link one or two failures leave two Modules and three leave
   one; on a two-Module Link one failure leaves one. Rule 2 adds the only
   sacrificial disable there is -- when exactly one Module of a four-Module Link
   failed, the other Module of its half along the Die Edge goes with it. Table
   5-29 confirms there is no such padding for two or three failures, so a pair
   that straddles the halves (say {M1, M3}) is a legal surviving set and the
   byte map already handles it.
 * "Exactly one Module failed" is a property of the whole resolution, not of
   one trip round the flow chart. Modules leave in stages -- the ones that
   failed before reporting, then each disable arc of the chart -- so rule 2 is
   applied only once the chart has stopped disabling: a Link that started with
   four Modules and has settled on three loses the other Module of the missing
   one's half, and the chart is re-run on the pair that is left, since the
   partner may itself have been the Module asking for something. Applying rule
   2 whenever a single stage removes a single Module instead spends the
   padding before the second failure is seen, and ends one Module short of
   Table 5-29 -- or with no Link at all.
 * The speed-degrade disable arc of both flow charts returns through connector
   1, into the "any enabled Module reporting errors?" decision with the reduced
   Module set. The survivors of that disable are not necessarily clean -- a
   Module reporting width degrade survives it -- so the decision is instantiated
   once per Module, the most trips a resolution can take, and the passes are
   chained. The width disable arc goes straight to LINKINIT in the charts; its
   survivors are always clean, so sending it through the next pass lands in
   the same place.
 * A Module that failed to train before reporting (io.failed) simply is not in
   the set the first pass sees. It counts towards rule 2 like any other
   Module that left.
 * "Narrower than the rest" (spec 4.7.1.2.1) is judged on both directions of a
   Module, which is what keeps it the same predicate on both die.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

class MmplLinkSpeedResolver(params: MmplParams) extends Module {
  private val n = params.numModules
  private val laneCountW = log2Ceil(params.lanesPerModule + 1)

  val io = IO(new Bundle {
    // IN
    val reports = Flipped(Vec(n, Valid(new MmplLinkSpeedReport())))
    val enable = Input(Vec(n, Bool()))
    val currentSpeed = Input(SpeedMode())
    /* Active Lanes each Module currently drives. Spec 4.7.1.2.1: a Module
       already narrower than the rest of the operational Modules counts as
       requesting a width degrade even though it exchanged
       {MBTRAIN.LINKSPEED done req}.
       The counts come in rather than the derived "narrower" bit, because the
       predicate is relative to "the rest of the operational modules" and each
       pass of the flow chart re-enters connector 1 with a *reduced* Module set.
       A bit computed once against the initial set would still flag the
       survivors of a disable as narrow after the wide Modules that made them
       narrow have gone, sending an already-uniform Link to REPAIR for nothing. */
    val activeLanes = Input(Vec(n, UInt(laneCountW.W)))
    /* Active Lanes each Module currently receives on, i.e. what its remote
       Module Partner's Transmitter drives. A Module's two directions can
       leave MBINIT.REPAIRMB at different widths, and each die only sees its
       own Transmitter as "TX": judged on TX alone, die A would call a pair
       narrow that die B calls full width, direct a different response, and
       the Step 5d mismatch would take the whole Link to TRAINERROR. Testing
       both directions is the same predicate from either die, so both still
       reach the same resolution (spec 4.7.1.2), and it also keeps a Module
       whose RX alone is narrow from joining an Active Link at mixed widths. */
    val activeRxLanes = Input(Vec(n, UInt(laneCountW.W)))
    /* Enabled Modules that dropped out of training before reporting, e.g. to
       TRAINERROR in MBINIT. Spec 4.7.1: "if any module failed to train, the
       MMPL must ensure that the multi-module configuration degrades to the
       next permitted configuration". Such a Module counts as reported, and is
       disabled ahead of the flow chart. */
    val failed = Input(Vec(n, Bool()))

    // OUT
    val resolved = Output(Bool())
    val linkResolution = Output(MmplResolution())
    val moduleResolution = Output(Vec(n, MmplResolution()))
    val nextEnable = Output(Vec(n, Bool()))
  })

  // ==========================================================================
  // Speed ladder
  // ==========================================================================
  private val speedLadder: Seq[(SpeedMode.Type, Int)] = Seq(
    SpeedMode.speed4 -> 4,
    SpeedMode.speed8 -> 8,
    SpeedMode.speed12 -> 12,
    SpeedMode.speed16 -> 16,
    SpeedMode.speed24 -> 24,
    SpeedMode.speed32 -> 32,
    SpeedMode.speed48 -> 48,
    SpeedMode.speed64 -> 64
  )

  /** Next lower allowed Link speed in GT/s, or 0 when already at 4 GT/s. */
  private def nextLowerGTs(idx: Int): Int =
    if (idx == 0) 0 else speedLadder(idx - 1)._2

  // 2 * (CLS-1) > CLS, the reduced Standard Package bandwidth comparison.
  private val speedDegradeWinsTable = speedLadder.zipWithIndex.map {
    case ((mode, gts), idx) => mode -> (2 * nextLowerGTs(idx) > gts)
  }

  // HMLS/2 > CMLS.
  private val noLowerSpeedTable = speedLadder.zipWithIndex.map {
    case ((mode, gts), idx) => mode -> (gts / 2 > nextLowerGTs(idx))
  }

  // The conditions are mutually exclusive, so a flat set of `when`s is a lookup.
  // `switch` cannot be used here because its macro needs literal `is` blocks.
  private val speedDegradeWins = WireDefault(false.B)
  private val noLowerSpeed = WireDefault(false.B)
  speedDegradeWinsTable.zip(noLowerSpeedTable).foreach {
    case ((mode, degradeWins), (_, noLower)) =>
      when(io.currentSpeed === mode) {
        speedDegradeWins := degradeWins.B
        noLowerSpeed := noLower.B
      }
  }

  private val atLowestSpeed = io.currentSpeed === SpeedMode.speed4

  // ==========================================================================
  // What the Modules reported
  // ==========================================================================
  private val reported = (0 until n).map(m => io.reports(m).valid)

  /** Widest Module of a given operational set, spec 4.7.1.2.1's "the rest of
    * the operational modules". Recomputed per pass, because connector 1 re-runs
    * the decision with whatever survived the last one.
    */
  private def widestOf(lanes: Vec[UInt], active: Seq[Bool]): UInt =
    (0 until n)
      .map(m => Mux(active(m), lanes(m), 0.U))
      .reduce((a, b) => Mux(a > b, a, b))

  // Narrower than the rest in either direction. Each die's RX is the other's
  // TX, so the two dies evaluate the same pair of widths.
  private def narrowerOf(m: Int, active: Seq[Bool]): Bool =
    (io.activeLanes(m) < widestOf(io.activeLanes, active)) ||
      (io.activeRxLanes(m) < widestOf(io.activeRxLanes, active))

  private def widthOf(m: Int, active: Seq[Bool]): Bool =
    reported(m) &&
      (io.reports(m).bits.widthDegradeRequested || narrowerOf(m, active))
  private def speedOf(m: Int): Bool =
    reported(m) && io.reports(m).bits.speedDegradeRequested

  // Spec 4.5.3.4.12 Step 5: a PHY retrain request on ANY Module of the Link
  // abandons the resolution and takes every Module to PHYRETRAIN.
  private def phyRetrainOf(m: Int): Bool =
    reported(m) &&
      (io.reports(m).bits.sentPhyRetrain || io.reports(m).bits.recvdPhyRetrain)

  private val anyPhyRetrain =
    (0 until n).map(m => io.enable(m) && phyRetrainOf(m)).reduce(_ || _)

  // ==========================================================================
  // Rule 2's same-half partner (spec 5.7.3.4.1)
  // ==========================================================================
  private val numHalves = if (n == 1) 1 else 2
  private val modulesPerHalf = n / numHalves

  private def halfMembers(m: Int): Range = {
    val half = m / modulesPerHalf
    (half * modulesPerHalf) until ((half + 1) * modulesPerHalf)
  }

  /** The other Modules of `m`'s half along the Die Edge. */
  private def matesOf(m: Int): Seq[Int] = halfMembers(m).filter(_ != m)

  /* Rule 2 is scoped to a four-Module Link, i.e. one this resolution found
     with all four Modules enabled (a Module that failed before reporting
     still counts: it is one of the pairs that failed). A Link an earlier resolution already
     degraded, or one with a Table 5-28 NC Module, has fewer enabled and never
     pads. */
  private val startedFull: Bool =
    (n == 4).B && io.enable.asUInt.andR

  // ==========================================================================
  // One pass of the flow chart
  // ==========================================================================
  // Standard Package splits the width-degrade case on whether a majority
  // reported; Advanced Package repairs whenever repair was requested.
  private val useWidthMajorityRule =
    params.packageType == MmplPackageType.Standard

  /** One trip round Figure 4-47 / Figure 4-48 on a Module set.
    *
    * A pass either hands a smaller set on (`continuing`) -- a disable arc of
    * the chart (`chartDisabling`, disabling `doomed`), or rule 2 on a settled
    * three-Module set (`halfRule`, disabling `partner`) -- or settles, in which
    * case `resolution` is the Link's outcome and `nextEnable` the set it
    * applies to.
    */
  private case class Pass(
      resolution: MmplResolution.Type,
      nextEnable: Vec[Bool],
      continuing: Bool,
      chartDisabling: Bool,
      doomed: Vec[Bool],
      halfRule: Bool,
      partner: Vec[Bool]
  )

  /** The decision diamonds of Figure 4-47 / Figure 4-48 for one Module set. */
  private def decide(active: Seq[Bool]): Pass = {
    val widthRequested = (0 until n).map(m => active(m) && widthOf(m, active))
    val speedRequested = (0 until n).map(m => active(m) && speedOf(m))
    val failing =
      (0 until n).map(m => widthRequested(m) || speedRequested(m))

    val numActive = PopCount(active)
    val numWidthRequested = PopCount(widthRequested)
    val anyActive = active.reduce(_ || _)
    val anySpeedRequested = speedRequested.reduce(_ || _)
    val anyWidthRequested = widthRequested.reduce(_ || _)
    val anyFailing = failing.reduce(_ || _)

    // "More than half number of modules report errors" (Figure 4-48).
    val widthMajority = (numWidthRequested << 1).asUInt > numActive

    val resolution = WireDefault(MmplResolution.none)
    val wantDisable = WireDefault(false.B)
    // Both flow charts name the disable set explicitly, and it is not simply
    // "everything that failed": the width arc disables the Modules reporting
    // width degrade, the speed arc the Modules reporting speed degrade.
    val doomed = WireInit(VecInit(Seq.fill(n)(false.B)))

    when(!anyFailing) {
      // No enabled Module is reporting errors: every Module goes to LINKINIT.
      resolution := MmplResolution.done
    }.elsewhen(!anySpeedRequested && anyWidthRequested) {
      if (useWidthMajorityRule) {
        when(widthMajority) {
          when(atLowestSpeed) {
            // Already at the bottom of the ladder, so width degrade is all
            // there is.
            resolution := MmplResolution.repair
          }.elsewhen(speedDegradeWins) {
            resolution := MmplResolution.speedDegrade
          }.otherwise {
            resolution := MmplResolution.repair
          }
        }.otherwise {
          // At most half reported, so disable them and drop a Module count.
          wantDisable := true.B
          doomed := VecInit(widthRequested)
        }
      } else {
        resolution := MmplResolution.repair
      }
    }.elsewhen(noLowerSpeed) {
      // A Module wants a speed degrade but there is no lower speed to move to,
      // so the Modules asking for one are the ones that leave.
      wantDisable := true.B
      doomed := VecInit(speedRequested)
    }.otherwise {
      resolution := MmplResolution.speedDegrade
    }

    val remaining = VecInit((0 until n).map(m => active(m) && !doomed(m)))

    // Disabling only happens if something is left to carry the Link; otherwise
    // the chart's "any modules with an operational configuration?" answers No.
    // A set the failures before reporting had already emptied answers No too.
    val chartDisabling = wantDisable && remaining.reduce(_ || _)
    val noSurvivor = !anyActive || (wantDisable && !chartDisabling)

    /* Rule 2, decided on the whole resolution: the chart has stopped
       disabling, the Link started with four Modules, and exactly one has
       gone -- whether it failed before reporting or an earlier pass disabled
       it. The other Module of the missing one's half goes too. Only a settled
       pass may do this: while the chart is still disabling, more Modules may
       be about to leave, and rule 2 does not pad two or three failures. */
    val halfRule = startedFull && !wantDisable && numActive === 3.U
    val partner = VecInit((0 until n).map { m =>
      halfRule && active(m) &&
      matesOf(m).map(k => !active(k)).foldLeft(false.B)(_ || _)
    })

    val continuing = chartDisabling || halfRule
    val nextEnable = VecInit((0 until n).map { m =>
      Mux(
        noSurvivor,
        false.B,
        Mux(chartDisabling, remaining(m), active(m) && !partner(m))
      )
    })

    // A pass that hands a smaller set on leaves the resolution to a later
    // pass -- the chart's connector 1, or the re-run after rule 2 -- so the
    // value here is only ever read when this pass is the one that settled.
    Pass(
      Mux(noSurvivor, MmplResolution.trainError, resolution),
      nextEnable,
      continuing,
      chartDisabling,
      doomed,
      halfRule,
      partner
    )
  }

  // ==========================================================================
  // Chained passes: the flow chart's connector 1, iterated to a fixed point
  // ==========================================================================
  /* A pass that does not settle removes at least one Module and leaves at
     least one, so starting from at most n Modules there can be at most n - 1
     such passes before one settles. A settled pass hands on the set it was
     given (or nothing, on TRAINERROR), and the pass after it is the same
     function of the same set, so every later pass repeats the answer: the last
     pass is the fixed point. */
  private val numPasses = n

  // The failures before reporting are not in the set the flow chart sees.
  private val reachable =
    (0 until n).map(m => io.enable(m) && !io.failed(m))

  private val passes = {
    val built = scala.collection.mutable.ArrayBuffer.empty[Pass]
    var active: Seq[Bool] = reachable
    for (_ <- 0 until numPasses) {
      val p = decide(active)
      built += p
      active = (0 until n).map(p.nextEnable(_))
    }
    built.toSeq
  }

  private val resolution = WireDefault(passes.last.resolution)
  private val nextEnable = WireInit(passes.last.nextEnable)

  // A PHY retrain request outranks every other outcome and does not wait for
  // the rest of the Link to finish reporting.
  when(anyPhyRetrain) {
    resolution := MmplResolution.phyRetrain
    nextEnable := VecInit((0 until n).map(io.enable(_)))
  }

  private val active = (0 until n).map(io.enable(_))
  private val reportedOrIdle =
    (0 until n).map(m => !active(m) || reported(m) || io.failed(m))
  private val anyActive = active.reduce(_ || _)

  io.resolved :=
    anyActive && (anyPhyRetrain || reportedOrIdle.reduce(_ && _))
  io.linkResolution := resolution
  io.nextEnable := nextEnable
  for (m <- 0 until n) {
    /* A Module an earlier resolution already dropped is not part of the Link
       and has no expected response (spec 4.5.3.4.12 Step 5d directs the Modules
       operational in the Link), so it is named nothing rather than handed the
       Link's outcome. */
    io.moduleResolution(m) := Mux(
      !active(m),
      MmplResolution.none,
      Mux(
        !nextEnable(m) && resolution =/= MmplResolution.trainError,
        MmplResolution.disableModule,
        resolution
      )
    )
  }

  // ==========================================================================
  // Assertions
  // ==========================================================================
  block(Verification) {
    // Why each Module left, accumulated over the chain.
    val chartDisabled = (0 until n).map { m =>
      passes.map(p => p.chartDisabling && p.doomed(m)).reduce(_ || _)
    }
    val rulePartner = (0 until n).map { m =>
      passes.map(_.partner(m)).reduce(_ || _)
    }
    // Resolved to a surviving set, i.e. neither PHYRETRAIN (which keeps the
    // set as it is) nor TRAINERROR (which empties it).
    val degraded = io.resolved && !anyPhyRetrain &&
      resolution =/= MmplResolution.trainError

    block(Verification.Assert) {
      // Spec 5.7.3.4.1 rule 1: a degraded Link is one or two Modules. That rule
      // governs a *degraded* Link, so it does not reach the chart's other exit:
      // "any modules with an operational configuration? No" leaves none, which
      // is TRAINERROR rather than an illegal Module count.
      val shrinking = io.resolved && PopCount(nextEnable) < PopCount(active)
      assert(
        !shrinking || resolution === MmplResolution.trainError ||
          PopCount(nextEnable) === 1.U ||
          PopCount(nextEnable) === 2.U,
        "FATAL: MMPL resolved to a Module count that is not a permitted configuration"
      )
      // The unrolled chain must have reached its fixed point; the pass count
      // above is a proof obligation, not a guess.
      assert(
        !io.resolved || !passes.last.continuing,
        "FATAL: MMPL resolution did not settle within the unrolled passes"
      )
      // Rule 1 stated exactly: a Module leaves because it failed before
      // reporting, because a disable arc of the chart named it, or as rule 2's
      // single same-half partner -- never for any other reason.
      assert(
        !degraded ||
          (0 until n)
            .map(m =>
              !active(m) || nextEnable(m) || io.failed(m) ||
                chartDisabled(m) || rulePartner(m)
            )
            .reduce(_ && _),
        "FATAL: MMPL disabled a Module that spec 5.7.3.4.1 requires to survive"
      )
      /* Rule 2: at most one sacrificial disable, only on a Link that started
         with four Modules, and only of a Module whose half had already lost
         its other Module for cause. */
      assert(
        !io.resolved || PopCount(rulePartner) <= 1.U,
        "FATAL: MMPL applied the same-half degrade rule more than once"
      )
      assert(
        !io.resolved ||
          (0 until n)
            .map(m =>
              !rulePartner(m) || (startedFull &&
                matesOf(m)
                  .map(k => io.failed(k) || chartDisabled(k))
                  .foldLeft(false.B)(_ || _))
            )
            .reduce(_ && _),
        "FATAL: MMPL disabled a same-half partner whose half had not failed"
      )
      // A disable must never grow the operational set.
      assert(
        !io.resolved ||
          (0 until n)
            .map(m => !nextEnable(m) || active(m))
            .reduce(_ && _),
        "FATAL: MMPL resolution enabled a Module that was not operational"
      )
      /* Spec 4.5.3.4.12 Step 5: PHY retrain is a whole-Link directive. Checked
         on what the Modules are actually handed, not on `resolution` -- the
         `when` above assigns that literally, so testing it there is a tautology
         the Mux below is under no obligation to honour. */
      assert(
        !io.resolved || !anyPhyRetrain ||
          ((0 until n)
            .map(m =>
              !active(m) || io.moduleResolution(m) === MmplResolution.phyRetrain
            )
            .reduce(_ && _) &&
            (0 until n).map(m => nextEnable(m) === active(m)).reduce(_ && _)),
        "FATAL: MMPL resolved away from PHYRETRAIN while a Module reported one"
      )
    }
    block(Verification.Cover) {
      cover(io.resolved && resolution === MmplResolution.repair)
      cover(io.resolved && resolution === MmplResolution.speedDegrade)
      cover(io.resolved && resolution === MmplResolution.phyRetrain)
      cover(io.resolved && PopCount(nextEnable) < PopCount(active))
      cover(io.resolved && resolution === MmplResolution.trainError)
      // The connector-1 loop actually iterating.
      cover(io.resolved && passes.head.chartDisabling)
      // Rule 2 spent on a Module that failed before reporting.
      cover(degraded && rulePartner.reduce(_ || _) && io.failed.asUInt.orR)
      // A failure before reporting followed by a disable arc of the chart:
      // the two-failure case that must not be padded.
      cover(
        degraded && io.failed.asUInt.orR &&
          chartDisabled.reduce(_ || _) && !rulePartner.reduce(_ || _)
      )
      // The chart re-run after rule 2 disabling something further.
      cover(
        degraded &&
          passes
            .zip(passes.drop(1))
            .map { case (a, b) => a.halfRule && b.chartDisabling }
            .foldLeft(false.B)(_ || _)
      )
    }
  }
}
