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
 * Both flow charts route the disable arcs back through connector 1, into the
   "any enabled Module reporting errors?" decision with the reduced Module set.
   The survivors of a disable are not necessarily clean -- a Module reporting
   width degrade survives a speed-degrade disable -- so the decision is
   instantiated once per reachable Module count and the passes are chained.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

class MmplLinkSpeedResolver(params: MmplParams) extends Module {
  private val n = params.numModules

  val io = IO(new Bundle {
    // IN
    val reports = Flipped(Vec(n, Valid(new MmplLinkSpeedReport())))
    val enable = Input(Vec(n, Bool()))
    val currentSpeed = Input(SpeedMode())
    // Spec 4.7.1.2.1: a Module already narrower than the rest of the
    // operational Modules counts as requesting a width degrade even though it
    // exchanged {MBTRAIN.LINKSPEED done req}. Only the MMPL can see every
    // Module's width, so it computes this and hands it down.
    val narrowerThanPeers = Input(Vec(n, Bool()))

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

  private def widthOf(m: Int): Bool =
    reported(m) &&
      (io.reports(m).bits.widthDegradeRequested || io.narrowerThanPeers(m))
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
  // Which Modules survive a disable (spec 5.7.3.4.1)
  // ==========================================================================
  private val numHalves = if (n == 1) 1 else 2
  private val modulesPerHalf = n / numHalves

  private def halfMembers(m: Int): Range = {
    val half = m / modulesPerHalf
    (half * modulesPerHalf) until ((half + 1) * modulesPerHalf)
  }

  /** Survivors of disabling `doomed` out of `active`, and whether rule 2's
    * sacrificial disable applied.
    *
    * Rule 1 says the surviving count is simply "everything that did not fail".
    * Rule 2 adds the one exception: on a full four-Module Link a lone failure
    * also takes down the other Module of its half.
    */
  private def survivorsOf(
      active: Seq[Bool],
      doomed: Seq[Bool]
  ): (Vec[Bool], Bool) = {
    val numDoomed = PopCount(doomed)
    val numActive = PopCount(active)
    val halfRule =
      (n == 4).B && (numActive === n.U) && (numDoomed === 1.U)
    val survivors = VecInit((0 until n).map { m =>
      val halfIsDoomed = halfMembers(m).map(doomed(_)).reduce(_ || _)
      active(m) && !doomed(m) && !(halfRule && halfIsDoomed)
    })
    (survivors, halfRule)
  }

  // ==========================================================================
  // One pass of the flow chart
  // ==========================================================================
  // Standard Package splits the width-degrade case on whether a majority
  // reported; Advanced Package repairs whenever repair was requested.
  private val useWidthMajorityRule =
    params.packageType == MmplPackageType.Standard

  private case class Pass(
      resolution: MmplResolution.Type,
      nextEnable: Vec[Bool],
      disabling: Bool,
      numActive: UInt,
      numDoomed: UInt,
      halfRule: Bool
  )

  /** The decision diamonds of Figure 4-47 / Figure 4-48 for one Module set. */
  private def decide(active: Seq[Bool]): Pass = {
    val widthRequested = (0 until n).map(m => active(m) && widthOf(m))
    val speedRequested = (0 until n).map(m => active(m) && speedOf(m))
    val failing =
      (0 until n).map(m => widthRequested(m) || speedRequested(m))

    val numActive = PopCount(active)
    val numWidthRequested = PopCount(widthRequested)
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

    val (survivors, halfRule) = survivorsOf(active, doomed)
    val disablePossible = survivors.reduce(_ || _)

    // Disabling only happens if something is left to carry the Link; otherwise
    // the chart's "any modules with an operational configuration?" answers No.
    val disabling = wantDisable && disablePossible
    val noSurvivor = wantDisable && !disablePossible

    // A pass that disables hands its survivors to the next pass -- the flow
    // chart's connector 1 -- and that pass names the resolution, so the value
    // here is only ever read when this pass is the one that settled.
    val settledResolution =
      Mux(noSurvivor, MmplResolution.trainError, resolution)
    val nextEnable = VecInit((0 until n).map { m =>
      Mux(noSurvivor, false.B, Mux(disabling, survivors(m), active(m)))
    })

    Pass(
      settledResolution,
      nextEnable,
      disabling,
      numActive,
      PopCount(doomed),
      halfRule
    )
  }

  // ==========================================================================
  // Chained passes: the flow chart's connector 1
  // ==========================================================================
  // A disable can only halve the Module count, so the chart can loop at most
  // log2(n) times before reaching a single Module, plus one final decision.
  private val numPasses = log2Ceil(n) + 1

  private val passes = {
    val built = scala.collection.mutable.ArrayBuffer.empty[Pass]
    var active: Seq[Bool] = (0 until n).map(io.enable(_))
    for (_ <- 0 until numPasses) {
      val p = decide(active)
      built += p
      active = (0 until n).map(p.nextEnable(_))
    }
    built.toSeq
  }

  // The answer is the first pass that stopped disabling; if every pass disabled
  // then the last one's outcome stands. Selected field by field because a
  // PriorityMux carries Data, not a case class.
  private val passSelect = passes.map(!_.disabling) :+ true.B

  private def settle[T <: Data](field: Pass => T): T =
    PriorityMux(passSelect, passes.map(field) :+ field(passes.last))

  private val resolution = WireDefault(settle(_.resolution))
  private val nextEnable = WireInit(VecInit((0 until n).map { m =>
    settle(_.nextEnable(m))
  }))

  // A PHY retrain request outranks every other outcome and does not wait for
  // the rest of the Link to finish reporting.
  when(anyPhyRetrain) {
    resolution := MmplResolution.phyRetrain
    nextEnable := VecInit((0 until n).map(io.enable(_)))
  }

  private val active = (0 until n).map(io.enable(_))
  private val reportedOrIdle =
    (0 until n).map(m => !active(m) || reported(m))
  private val anyActive = active.reduce(_ || _)

  io.resolved :=
    anyActive && (anyPhyRetrain || reportedOrIdle.reduce(_ && _))
  io.linkResolution := resolution
  io.nextEnable := nextEnable
  for (m <- 0 until n) {
    io.moduleResolution(m) := Mux(
      active(m) && !nextEnable(m) && resolution =/= MmplResolution.trainError,
      MmplResolution.disableModule,
      resolution
    )
  }

  // ==========================================================================
  // Assertions
  // ==========================================================================
  block(Verification) {
    block(Verification.Assert) {
      // Spec 5.7.3.4.1 rule 1: a degraded Link is one or two Modules.
      val shrinking = io.resolved && PopCount(nextEnable) < PopCount(active)
      assert(
        !shrinking || PopCount(nextEnable) === 1.U ||
          PopCount(nextEnable) === 2.U,
        "FATAL: MMPL resolved to a Module count that is not a permitted configuration"
      )
      // Rule 1 stated exactly: every Module that did not fail survives, and the
      // only Module disabled beyond the failures is rule 2's same-half partner.
      // This is what a survivor set of {M1} where Table 5-29 requires {M1, M3}
      // trips, which the previous shape-only check could not.
      passes.foreach { p =>
        assert(
          !io.resolved || !p.disabling ||
            PopCount(p.nextEnable) ===
            (p.numActive - p.numDoomed - Mux(p.halfRule, 1.U, 0.U)),
          "FATAL: MMPL disabled a Module that spec 5.7.3.4.1 requires to survive"
        )
      }
      // A disable must never grow the operational set.
      assert(
        !io.resolved ||
          (0 until n)
            .map(m => !nextEnable(m) || active(m))
            .reduce(_ && _),
        "FATAL: MMPL resolution enabled a Module that was not operational"
      )
      // Spec 4.5.3.4.12 Step 5: PHY retrain is a whole-Link directive.
      assert(
        !io.resolved || !anyPhyRetrain ||
          resolution === MmplResolution.phyRetrain,
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
      cover(io.resolved && passes.head.disabling && !passes(numPasses - 1).disabling)
    }
  }
}
