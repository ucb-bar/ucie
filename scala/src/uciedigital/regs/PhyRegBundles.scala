// Bundles wiring the logical PHY to its register block.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util.Valid

class PhyStatusFields extends Bundle {
  val rxTerminationStatus = Bool()
  val txEqStatus = Bool()
  val clockModeStatus = Bool()
  val clockPhaseStatus = Bool()
  val laneReversal = Bool()
  val iqCorrectionParam = UInt(6.W)
  val eqPresetSetting = UInt(4.W)
  val tarrStatus = Bool()
}

class ErrorLogFields extends Bundle {
  val stateN = UInt(8.W)
  val laneReversal = Bool()
  val widthDegrade = Bool()
  val stateNm1 = UInt(8.W)
  val stateNm2 = UInt(8.W)
  val stateNm3 = UInt(8.W)
}

class PhyToRegs(val numModules: Int) extends Bundle {
  val phyStatus = new PhyStatusFields
  val errorLog = Vec(numModules, Valid(new ErrorLogFields))
  val errLog1Set = Vec(numModules, Vec(4, Bool()))
  val currentLaneMap = Vec(numModules, Valid(UInt(64.W)))
  val linkTestBusy = Bool()
}

class PhyControlFields extends Bundle {
  val rxTerminationControl = Bool()
  val txEqEnable = Bool()
  val rxClockModeSelect = Bool()
  val rxClockPhaseSelect = Bool()
  val forceX8Width = Bool()
  val forceIqEnable = Bool()
  val forceIqParam = UInt(6.W)
  val forceTxEqPreset = Bool()
  val forceTxEqPresetSetting = UInt(4.W)
  val tarrEnable = Bool()
}

class RegsToPhy(val numModules: Int) extends Bundle {
  val phyControl = new PhyControlFields
  val ts1 = Vec(numModules, UInt(32.W))
  val ts2 = Vec(numModules, UInt(32.W))
  val ts3 = Vec(numModules, UInt(64.W))
  val ts4 = Vec(numModules, UInt(32.W))
  val applyLaneRepair = Vec(numModules, Bool())
  val laneRepairId = Vec(numModules, UInt(7.W))
  val linkTestStart = Bool()
}
