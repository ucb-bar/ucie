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
 * Disable granularity is a half of the Module set along the Die Edge
   (spec 5.7.3.4.1 rule 2): {M0, M1} and {M2, M3} for a four-module Link, and a
   single Module for a two-module Link. A degraded Link is one or two Modules and
   never three (rule 1), so when no half survives intact the Link falls back to a
   one-module configuration.
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
  private val active = (0 until n).map(io.enable(_))
  private val reportedOrIdle =
    (0 until n).map(m => !active(m) || io.reports(m).valid)

  private val widthRequested = (0 until n).map { m =>
    active(m) && io.reports(m).valid &&
    io.reports(m).bits.widthDegradeRequested
  }
  private val speedRequested = (0 until n).map { m =>
    active(m) && io.reports(m).valid &&
    io.reports(m).bits.speedDegradeRequested
  }
  private val failing =
    (0 until n).map(m => widthRequested(m) || speedRequested(m))

  private val numActive = PopCount(active)
  private val numWidthRequested = PopCount(widthRequested)
  private val anySpeedRequested = speedRequested.reduce(_ || _)
  private val anyWidthRequested = widthRequested.reduce(_ || _)
  private val anyFailing = failing.reduce(_ || _)
  private val anyActive = active.reduce(_ || _)

  // "More than half number of modules report errors" (Figure 4-48).
  private val widthMajority = (numWidthRequested << 1).asUInt > numActive

  // ==========================================================================
  // Which Modules survive a disable
  // ==========================================================================
  private val numHalves = if (n == 1) 1 else 2
  private val modulesPerHalf = n / numHalves

  private def halfMembers(half: Int): Range =
    (half * modulesPerHalf) until ((half + 1) * modulesPerHalf)

  private def maskOf(members: Seq[Int]): Vec[Bool] =
    VecInit((0 until n).map(m => members.contains(m).B))

  private val allDisabled = maskOf(Seq.empty)

  private val halfIsClean = (0 until numHalves).map { half =>
    halfMembers(half).map(m => active(m) && !failing(m)).reduce(_ && _)
  }

  // The numerically least intact half survives; otherwise fall back to the
  // numerically least Module that is still good.
  private val halfSurvivors = PriorityMux(
    halfIsClean.zip((0 until numHalves).map(h => maskOf(halfMembers(h)))) :+
      (true.B -> allDisabled)
  )
  private val moduleIsClean = (0 until n).map(m => active(m) && !failing(m))
  private val singleSurvivor = PriorityMux(
    moduleIsClean.zip((0 until n).map(m => maskOf(Seq(m)))) :+
      (true.B -> allDisabled)
  )

  private val anyHalfClean = halfIsClean.reduce(_ || _)
  private val anyModuleClean = moduleIsClean.reduce(_ || _)
  private val disableSurvivors =
    Mux(anyHalfClean, halfSurvivors, singleSurvivor)
  private val disablePossible = anyHalfClean || anyModuleClean

  // ==========================================================================
  // Resolution
  // ==========================================================================
  // Standard Package splits the width-degrade case on whether a majority
  // reported; Advanced Package repairs whenever repair was requested.
  private val useWidthMajorityRule =
    params.packageType == MmplPackageType.Standard

  private val resolution = WireDefault(MmplResolution.none)
  private val nextEnable = WireInit(VecInit(active))
  private val disabling = WireDefault(false.B)

  when(!anyFailing) {
    // No enabled Module is reporting errors: every Module goes to LINKINIT.
    resolution := MmplResolution.done
  }.elsewhen(!anySpeedRequested && anyWidthRequested) {
    if (useWidthMajorityRule) {
      when(widthMajority) {
        when(atLowestSpeed) {
          // Already at the bottom of the ladder, so width degrade is all there
          // is.
          resolution := MmplResolution.repair
        }.elsewhen(speedDegradeWins) {
          resolution := MmplResolution.speedDegrade
        }.otherwise {
          resolution := MmplResolution.repair
        }
      }.otherwise {
        // At most half reported, so disable them and drop a Module count.
        disabling := true.B
      }
    } else {
      resolution := MmplResolution.repair
    }
  }.elsewhen(noLowerSpeed) {
    // A Module wants a speed degrade but there is no lower speed to move to.
    disabling := true.B
  }.otherwise {
    resolution := MmplResolution.speedDegrade
  }

  when(disabling) {
    when(disablePossible) {
      // The survivors carry on to LINKINIT; the rest are told to disable.
      resolution := MmplResolution.done
      nextEnable := disableSurvivors
    }.otherwise {
      resolution := MmplResolution.trainError
    }
  }

  io.resolved := anyActive && reportedOrIdle.reduce(_ && _)
  io.linkResolution := resolution
  io.nextEnable := nextEnable
  for (m <- 0 until n) {
    io.moduleResolution(m) := Mux(
      active(m) && !nextEnable(m),
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
      assert(
        !io.resolved || !disabling || !disablePossible ||
          PopCount(nextEnable) === modulesPerHalf.U ||
          PopCount(nextEnable) === 1.U,
        "FATAL: MMPL resolved to a Module count that is not a permitted configuration"
      )
      // A disable must never grow the operational set.
      assert(
        !io.resolved ||
          (0 until n)
            .map(m => !nextEnable(m) || active(m))
            .reduce(_ && _),
        "FATAL: MMPL resolution enabled a Module that was not operational"
      )
    }
    block(Verification.Cover) {
      cover(io.resolved && resolution === MmplResolution.repair)
      cover(io.resolved && resolution === MmplResolution.speedDegrade)
      cover(io.resolved && disabling && disablePossible)
      cover(io.resolved && resolution === MmplResolution.trainError)
    }
  }
}
