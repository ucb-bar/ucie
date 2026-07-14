// Global PHY registers of the register block PHY half.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util.Cat
import freechips.rocketchip.regmapper.{RegField, RegFieldAccessType, RegFieldDesc, RegWriteFn}

object PhyOffsets {
  val PhyCapability = 0x1000
  val PhyControl = 0x1004
  val PhyStatus = 0x1008
  val PhyInitDebug = 0x100c
  val TrainingSetup1 = 0x1010
  val TrainingSetup2 = 0x1020
  val TrainingSetup3 = 0x1030
  val TrainingSetup4 = 0x1050
  val CurrentLaneMap = 0x1060
  val ErrorLog0 = 0x1080
  val ErrorLog1 = 0x1090
  val RuntimeLinkTestControl = 0x1100
  val RuntimeLinkTestStatus = 0x1108
  val NullCapability = 0x1200
}

class LogPhyRegsGlobal(
    f: RegFieldTypes,
    params: UcieRegParams,
    phyIn: PhyToRegs,
    phyOut: RegsToPhy
) {
  import PhyOffsets._
  private val n = params.numModules
  private val pc = params.phyCapability
  private val isStandard = params.packageType == UciePackageType.Standard
  private def b(x: Boolean): Int = if (x) 1 else 0

  private val phyCapabilityFields: Seq[RegField] = Seq(
    f.RsvdP(3, "phy_cap_rsvd_2_0"),
    f.HWInit(1, b(pc.terminatedLink), "terminated_link", "PHY Capability Terminated Link"),
    f.HWInit(1, b(pc.txEqSupport), "txeq_support", "PHY Capability TXEQ supported"),
    f.HWInit(5, pc.txVswingCode, "tx_vswing", "PHY Capability Supported Tx Vswing encoding"),
    f.RsvdP(1, "phy_cap_rsvd_10"),
    f.HWInit(2, pc.rxClockModeSupport, "rx_clock_mode", "PHY Capability Rx Clock Mode support <=32 GT/s"),
    f.HWInit(2, pc.rxClockPhaseSupport, "rx_clock_phase", "PHY Capability Rx Clock Phase support <=32 GT/s"),
    f.HWInit(1, b(isStandard), "package_type", "PHY Capability Package type (1b Standard, 0b Advanced)"),
    f.HWInit(1, b(pc.tcmSupport), "tcm_support", "PHY Capability Tightly Coupled Mode support"),
    f.HWInit(1, b(pc.tarrSupport), "tarr_support", "PHY Capability TARR support"),
    f.RsvdP(14, "phy_cap_rsvd_31_18")
  )

  private val rxTermCtrl = f.RW(1, b(pc.terminatedLink), "rx_term_ctrl",
    "PHY Control Rx Terminated Control")
  private val txEqEn = f.RW(1, 0, "tx_eq_en", "PHY Control Tx Eq Enable")
  private val rxClkModeSel = f.RW(1, 0, "rx_clk_mode_sel", "PHY Control Rx Clock Mode Select")
  private val rxClkPhaseSel = f.RW(1, 0, "rx_clk_phase_sel", "PHY Control Rx Clock Phase Select")
  private val forceX8Applicable = isStandard && params.maxLinkWidth == 16
  private val forceX8 =
    if (forceX8Applicable)
      Some(f.RW(1, 0, "force_x8", "PHY Control Force x8 Width Mode in a UCIe-S x16 Module (debug)"))
    else None
  private val forceIqEn = f.RW(1, 0, "force_iq_en", "PHY Control Force I/Q Correction Enable (>32 GT/s only)")
  private val forceIqParam = f.RW(6, 0, "force_iq_param", "PHY Control Force I/Q Correction Parameter")
  private val forceTxEqPreset = f.RW(1, 0, "force_txeq_preset", "PHY Control Force Tx EQ Preset (>32 GT/s only)")
  private val forceTxEqPresetSetting =
    f.RW(4, 0, "force_txeq_preset_setting", "PHY Control Force Tx EQ Preset Setting")
  private val tarrEn = f.RW(1, 0, "tarr_en", "PHY Control TARR enabled for negotiation")

  phyOut.phyControl.rxTerminationControl := rxTermCtrl.reg
  phyOut.phyControl.txEqEnable := txEqEn.reg
  phyOut.phyControl.rxClockModeSelect := rxClkModeSel.reg
  phyOut.phyControl.rxClockPhaseSelect := rxClkPhaseSel.reg
  phyOut.phyControl.forceX8Width := forceX8.map(_.reg).getOrElse(0.U)
  phyOut.phyControl.forceIqEnable := forceIqEn.reg
  phyOut.phyControl.forceIqParam := forceIqParam.reg
  phyOut.phyControl.forceTxEqPreset := forceTxEqPreset.reg
  phyOut.phyControl.forceTxEqPresetSetting := forceTxEqPresetSetting.reg
  phyOut.phyControl.tarrEnable := tarrEn.reg

  private val phyControlFields: Seq[RegField] = Seq(
    f.RsvdP(3, "phy_ctrl_rsvd_2_0"),
    rxTermCtrl.field,
    txEqEn.field,
    rxClkModeSel.field,
    rxClkPhaseSel.field,
    f.RsvdP(1, "force_x32"),
    forceX8.map(_.field).getOrElse(f.RsvdP(1, "force_x8_rsvd")),
    forceIqEn.field,
    forceIqParam.field,
    forceTxEqPreset.field,
    forceTxEqPresetSetting.field,
    tarrEn.field,
    f.RsvdP(10, "phy_ctrl_rsvd_31_22")
  )

  private val st = phyIn.phyStatus
  private val phyStatusFields: Seq[RegField] = Seq(
    f.RsvdP(3, "phy_status_rsvd_2_0"),
    f.RO(1, st.rxTerminationStatus.asUInt, "rx_term_status", "PHY Status Rx Termination Status"),
    f.RO(1, st.txEqStatus.asUInt, "tx_eq_status", "PHY Status Tx Eq Status"),
    f.RO(1, st.clockModeStatus.asUInt, "clock_mode_status", "PHY Status Clock Mode Status"),
    f.RO(1, st.clockPhaseStatus.asUInt, "clock_phase_status", "PHY Status Clock Phase Status"),
    f.RO(1, st.laneReversal.asUInt, "lane_reversal", "PHY Status Lane Reversal within Module"),
    f.RO(6, st.iqCorrectionParam, "iq_correction_param", "PHY Status I/Q Correction Parameter"),
    f.RO(4, st.eqPresetSetting, "eq_preset_setting", "PHY Status EQ Preset Setting"),
    f.RO(1, st.tarrStatus.asUInt, "tarr_status", "PHY Status TARR operational"),
    f.RsvdP(13, "phy_status_rsvd_31_19")
  )

  private val phyInitDebugFields: Seq[RegField] = Seq(
    f.HWInit(3, 0, "init_control",
      "PHY Init Control; No Test and Compliance Register Block, hardwired to 0"),
    f.RsvdP(2, "phy_init_rsvd_4_3"),
    f.HWInit(1, 0, "resume_training",
      "PHY Resume Training; No Test and Compliance Register Block, hardwired to 0"),
    f.RsvdP(26, "phy_init_rsvd_31_6")
  )

  private def applyRepair(m: Int, bit: Int): RegField =
    if (m < n) {
      val fld = f.RW(1, 0, s"apply_module${m}_lane_repair",
        s"Runtime Link Test Control Apply Module $m Lane Repair")
      phyOut.applyLaneRepair(m) := fld.reg
      fld.field
    } else f.RsvdP(1, s"apply_module${m}_lane_repair_rsvd")

  private def repairId(m: Int, hi: Int, lo: Int): RegField =
    if (m < n) {
      val fld = f.RW(7, 0, s"module${m}_lane_repair_id",
        s"Runtime Link Test Control Module $m Lane repair id")
      phyOut.laneRepairId(m) := fld.reg
      fld.field
    } else f.RsvdP(7, s"module${m}_lane_repair_id_rsvd")

  private val startAuto = f.RWautoClear(
    hwDone = phyIn.linkTestBusy,
    ignoreWhen = phyIn.linkTestBusy,
    name = "runtime_link_test_start",
    description = "Runtime Link Test Control Start (HW clears when Busy sets)"
  )
  phyOut.linkTestStart := startAuto.fire

  private val (m3RepairLo, m3RepairHi) =
    if (n >= 4) splitRwField(3, 4, "module3_lane_repair_id",
      "Runtime Link Test Control Module 3 Lane repair id")
    else (f.RsvdP(3, "module3_lane_repair_id_rsvd_lo"), f.RsvdP(4, "module3_lane_repair_id_rsvd_hi"))

  private val rltControlWord0: Seq[RegField] = Seq(
    f.RsvdP(2, "rlt_ctrl_1_0"),
    applyRepair(0, 2),
    applyRepair(1, 3),
    applyRepair(2, 4),
    applyRepair(3, 5),
    startAuto.field,
    f.HWInit(1, 0, "inject_stuck_at", "Runtime Link Test Control Inject Stuck-at fault — not implemented"),
    repairId(0, 14, 8),
    repairId(1, 21, 15),
    repairId(2, 28, 22),
    m3RepairLo
  )
  private val rltControlWord1: Seq[RegField] = Seq(
    m3RepairHi,
    f.RsvdP(28, "rlt_ctrl_rsvd_63_36")
  )

  private val rltStatusFields: Seq[RegField] = Seq(
    f.RO(1, phyIn.linkTestBusy.asUInt, "runtime_link_test_busy", "Runtime Link Test Status Busy"),
    f.RsvdZ(31, "rlt_status_rsvd_31_1")
  )

  private def splitRwField(loWidth: Int, hiWidth: Int, name: String, description: String): (RegField, RegField) = {
    val total = loWidth + hiWidth
    val reg = withReset(f.nonStickyReset)(RegInit(0.U(total.W)))
    phyOut.laneRepairId(3) := reg
    val loWrite = RegWriteFn { (valid, data) =>
      when(valid) { reg := Cat(reg(total - 1, loWidth), data(loWidth - 1, 0)) }
      true.B
    }
    val hiWrite = RegWriteFn { (valid, data) =>
      when(valid) { reg := Cat(data(hiWidth - 1, 0), reg(loWidth - 1, 0)) }
      true.B
    }
    val loDesc = RegFieldDesc(s"${name}_lo", s"$description (low bits)", access = RegFieldAccessType.RW, reset = Some(0))
    val hiDesc = RegFieldDesc(s"${name}_hi", s"$description (high bits)", access = RegFieldAccessType.RW, reset = Some(0))
    (RegField(loWidth, reg(loWidth - 1, 0), loWrite, Some(loDesc)),
     RegField(hiWidth, reg(total - 1, loWidth), hiWrite, Some(hiDesc)))
  }

  def entries(base: BigInt): Seq[RegField.Map] = {
    def at(off: Int): Int = (base + off).toInt
    Seq(
      at(PhyCapability) -> phyCapabilityFields,
      at(PhyControl) -> phyControlFields,
      at(PhyStatus) -> phyStatusFields,
      at(PhyInitDebug) -> phyInitDebugFields,
      at(RuntimeLinkTestControl) -> rltControlWord0,
      at(RuntimeLinkTestControl + 4) -> rltControlWord1,
      at(RuntimeLinkTestStatus) -> rltStatusFields,
      at(NullCapability) -> Seq(f.HWInit(32, 0, "null_ext_cap", "NULL Extended Capability DWORD"))
    )
  }
}
