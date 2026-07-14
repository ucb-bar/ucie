// Per-module PHY registers of the register block PHY half.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util.Cat
import freechips.rocketchip.regmapper.{RegField, RegFieldAccessType, RegFieldDesc, RegWriteFn}

class LogPhyRegsPerModule(
    f: RegFieldTypes,
    params: UcieRegParams,
    phyIn: PhyToRegs,
    phyOut: RegsToPhy
) {
  import PhyOffsets._
  private val n = params.numModules

  private def laneMapHalf(m: Int, hwHalf: UInt, name: String, description: String): RegField = {
    val reg = withReset(f.nonStickyReset)(RegInit(0.U(32.W)))
    when(phyIn.currentLaneMap(m).valid) { reg := hwHalf }
    val writeFn = RegWriteFn { (valid, data) =>
      when(valid && !phyIn.currentLaneMap(m).valid) { reg := data }
      true.B
    }
    val d = RegFieldDesc(name, description, access = RegFieldAccessType.RW, reset = Some(0), volatile = true)
    RegField(32, reg, writeFn, Some(d))
  }

  private def moduleEntries(base: BigInt, m: Int): Seq[RegField.Map] = {
    def at(off: Int, stride: Int): Int = (base + off + stride * m).toInt

    val ts1Data = f.RW(3, 0, s"ts1_data_pattern_m$m", "Training Setup 1 Data pattern")
    val ts1Valid = f.RW(3, 0, s"ts1_valid_pattern_m$m", "Training Setup 1 Valid pattern")
    val ts1ClkPhase = f.RW(4, 0, s"ts1_clock_phase_m$m", "Training Setup 1 Clock Phase control")
    val ts1Mode = f.RW(1, 0, s"ts1_training_mode_m$m", "Training Setup 1 Training mode (0 continuous, 1 burst)")
    val ts1Burst = f.RW(16, 0x4, s"ts1_burst_count_m$m", "Training Setup 1 Burst Count")
    phyOut.ts1(m) := Cat(0.U(5.W), ts1Burst.reg, ts1Mode.reg, ts1ClkPhase.reg, ts1Valid.reg, ts1Data.reg)
    val ts1Fields = Seq(
      ts1Data.field, ts1Valid.field, ts1ClkPhase.field, ts1Mode.field, ts1Burst.field,
      f.RsvdP(5, s"ts1_rsvd_31_27_m$m")
    )

    val ts2Idle = f.RW(16, 0x4, s"ts2_idle_count_m$m", "Training Setup 2 Idle count")
    val ts2Iters = f.RW(16, 0x4, s"ts2_iterations_m$m", "Training Setup 2 Iterations")
    phyOut.ts2(m) := Cat(ts2Iters.reg, ts2Idle.reg)
    val ts2Fields = Seq(ts2Idle.field, ts2Iters.field)

    val ts3Lo = f.RW(32, 0, s"ts3_lane_mask_lo_m$m", "Training Setup 3 Lane mask")
    val ts3Hi = f.RW(32, 0, s"ts3_lane_mask_hi_m$m", "Training Setup 3 Lane mask")
    phyOut.ts3(m) := Cat(ts3Hi.reg, ts3Lo.reg)

    val ts4Repair = f.RW(4, 0, s"ts4_repair_lane_mask_m$m", "Training Setup 4 Repair Lane mask")
    val ts4PerLane = f.RW(12, 0, s"ts4_max_err_per_lane_m$m", "Training Setup 4 Max error threshold per Lane")
    val ts4Aggr = f.RW(16, 0, s"ts4_max_err_aggregate_m$m", "Training Setup 4 Max error threshold aggregate")
    phyOut.ts4(m) := Cat(ts4Aggr.reg, ts4PerLane.reg, ts4Repair.reg)
    val ts4Fields = Seq(ts4Repair.field, ts4PerLane.field, ts4Aggr.field)

    val hw = phyIn.currentLaneMap(m).bits
    val clmLo = laneMapHalf(m, hw(31, 0), s"current_lane_map_lo_m$m", "Current Lane Map")
    val clmHi = laneMapHalf(m, hw(63, 32), s"current_lane_map_hi_m$m", "Current Lane Map")

    val el0StateN = f.ROS(8, 0, s"err_log0_state_n_m$m", "Error Log 0 State N")
    val el0LaneRev = f.ROS(1, 0, s"err_log0_lane_reversal_m$m", "Error Log 0 Lane Reversal")
    val el0WidthDeg = f.ROS(1, 0, s"err_log0_width_degrade_m$m", "Error Log 0 Width Degrade (Standard package)")
    val el0StateNm1 = f.ROS(8, 0, s"err_log0_state_nm1_m$m", "Error Log 0 State N-1")
    val el0StateNm2 = f.ROS(8, 0, s"err_log0_state_nm2_m$m", "Error Log 0 State N-2")
    val el1StateNm3 = f.ROS(8, 0, s"err_log1_state_nm3_m$m", "Error Log 1 State N-3")
    val el1Flags = f.RW1CS(4, phyIn.errLog1Set(m).asUInt, s"err_log1_flags_m$m",
      "Error Log 1 State Timeout / Sideband Timeout / Remote LinkError / Internal Error")

    val elog = phyIn.errorLog(m)
    when(elog.valid) {
      el0StateN.reg := elog.bits.stateN
      el0LaneRev.reg := elog.bits.laneReversal
      el0WidthDeg.reg := elog.bits.widthDegrade
      el0StateNm1.reg := elog.bits.stateNm1
      el0StateNm2.reg := elog.bits.stateNm2
      el1StateNm3.reg := elog.bits.stateNm3
    }

    val errorLog0Fields = Seq(
      el0StateN.field,
      el0LaneRev.field,
      el0WidthDeg.field,
      f.RsvdZ(6, s"err_log0_rsvd_15_10_m$m"),
      el0StateNm1.field,
      el0StateNm2.field
    )
    val errorLog1Fields = Seq(
      el1StateNm3.field,
      el1Flags.field,
      f.RsvdZ(20, s"err_log1_rsvd_31_12_m$m")
    )

    Seq(
      at(TrainingSetup1, 4) -> ts1Fields,
      at(TrainingSetup2, 4) -> ts2Fields,
      at(TrainingSetup3, 8) -> Seq(ts3Lo.field),
      (at(TrainingSetup3, 8) + 4) -> Seq(ts3Hi.field),
      at(TrainingSetup4, 4) -> ts4Fields,
      at(CurrentLaneMap, 8) -> Seq(clmLo),
      (at(CurrentLaneMap, 8) + 4) -> Seq(clmHi),
      at(ErrorLog0, 4) -> errorLog0Fields,
      at(ErrorLog1, 4) -> errorLog1Fields
    )
  }

  def entries(base: BigInt): Seq[RegField.Map] =
    (0 until n).flatMap(m => moduleEntries(base, m))
}
