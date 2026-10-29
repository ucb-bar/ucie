/*
  The global PHY registers (sec 9.5.3.22 to 9.5.3.25, 9.5.3.36, 9.5.3.37).
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util.Cat
import freechips.rocketchip.regmapper.{
  RegField,
  RegFieldAccessType,
  RegFieldDesc,
  RegWriteFn
}

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

  // sec 9.5.3.39 uses 1134h to 1140h in 4B steps for multiple modules.
  val ClockTrackValidSbRepair = 0x1134
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
  val n = params.numModules
  val pc = params.phyCapability
  val isStandard = params.packageType == UciePackageType.Standard
  def b(x: Boolean): Int = if (x) 1 else 0

  // ===========================================================================
  // PHY Capability
  // ===========================================================================
  // RWS against sec 9.5.3.22's RO, so firmware can describe its own part.
  val termLinkCap =
    f.RWS(
      1,
      b(pc.terminatedLink),
      "terminated_link",
      "PHY Capability Terminated Link"
    )
  val txEqSupport =
    f.RWS(1, b(pc.txEqSupport), "txeq_support", "PHY Capability TXEQ supported")
  val txVswing = f.RWS(
    5,
    pc.txVswingCode,
    "tx_vswing",
    "PHY Capability Supported Tx Vswing encoding"
  )
  val tarrSupport =
    f.RWS(1, b(pc.tarrSupport), "tarr_support", "PHY Capability TARR support")
  val rxClockModeCap = f.RWS(
    2,
    pc.rxClockModeSupport,
    "rx_clock_mode",
    "PHY Capability Rx Clock Mode support <=32 GT/s"
  )
  val rxClockPhaseCap = f.RWS(
    2,
    pc.rxClockPhaseSupport,
    "rx_clock_phase",
    "PHY Capability Rx Clock Phase support <=32 GT/s"
  )

  val phyCapabilityFields: Seq[RegField] = Seq(
    f.RsvdP(3, "phy_cap_rsvd_2_0"),
    termLinkCap.field,
    txEqSupport.field,
    txVswing.field,
    f.RsvdP(1, "phy_cap_rsvd_10"),
    rxClockModeCap.field,
    rxClockPhaseCap.field,
    f.RWS(
      1,
      b(isStandard),
      "package_type",
      "PHY Capability Package type (1b Standard, 0b Advanced)"
    ).field,
    f.RWS(
      1,
      b(pc.tcmSupport),
      "tcm_support",
      "PHY Capability Tightly Coupled Mode support"
    ).field,
    tarrSupport.field,
    f.RsvdP(14, "phy_cap_rsvd_31_18")
  )

  // ===========================================================================
  // PHY Control
  // ===========================================================================
  val rxTermCtrl = f.RW(
    1,
    b(pc.terminatedLink),
    "rx_term_ctrl",
    "PHY Control Rx Terminated Control"
  )
  val txEqEn = f.RW(1, 0, "tx_eq_en", "PHY Control Tx Eq Enable")

  // sec 9.5.3.23 bit 5 resets to 0 if the Rx supports Strobe ([12:11] 00b).
  val rxClkModeSel = f.RW(
    1,
    b(pc.rxClockModeSupport != 0x0),
    "rx_clk_mode_sel",
    "PHY Control Rx Clock Mode Select"
  )
  val rxClkPhaseSel =
    f.RW(1, 0, "rx_clk_phase_sel", "PHY Control Rx Clock Phase Select")
  val forceX8Applicable = isStandard && params.maxLinkWidth == 16
  val forceX8 =
    if (forceX8Applicable)
      Some(
        f.RW(
          1,
          0,
          "force_x8",
          "PHY Control Force x8 Width Mode in a UCIe-S x16 Module (debug)"
        )
      )
    else None

  // sec 9.5.3.23 limits the Force fields to > 32 GT/s, so nothing reads them.
  val forceIqEn = f.RW(
    1,
    0,
    "force_iq_en",
    "PHY Control Force I/Q Correction Enable (>32 GT/s only)"
  )
  val forceIqParam =
    f.RW(6, 0, "force_iq_param", "PHY Control Force I/Q Correction Parameter")
  val forceTxEqPreset = f.RW(
    1,
    0,
    "force_txeq_preset",
    "PHY Control Force Tx EQ Preset (>32 GT/s only)"
  )
  val forceTxEqPresetSetting =
    f.RW(
      4,
      0,
      "force_txeq_preset_setting",
      "PHY Control Force Tx EQ Preset Setting"
    )
  val tarrEn =
    f.RW(1, 0, "tarr_en", "PHY Control TARR enabled for negotiation")

  phyOut.phyControl.rxTerminationControl := rxTermCtrl.reg

  // Software can set bit 4 on a part with no Tx equalizer, so gate it.
  phyOut.phyControl.txEqEnable := txEqSupport.reg.asBool && txEqEn.reg.asBool

  // Bits 5 and 6 go on the wire, so ask only for a mode the capability allows.
  phyOut.phyControl.rxClockModeSelect :=
    rxClkModeSel.reg.asBool || (rxClockModeCap.reg =/= 0.U)
  phyOut.phyControl.rxClockPhaseSelect := rxClkPhaseSel.reg.asBool &&
    (rxClockPhaseCap.reg === 1.U || rxClockPhaseCap.reg === 2.U)
  phyOut.phyControl.forceX8Width := forceX8.map(_.reg).getOrElse(0.U)
  phyOut.phyControl.forceIqEnable := forceIqEn.reg
  phyOut.phyControl.forceIqParam := forceIqParam.reg
  phyOut.phyControl.forceTxEqPreset := forceTxEqPreset.reg
  phyOut.phyControl.forceTxEqPresetSetting := forceTxEqPresetSetting.reg
  phyOut.phyControl.tarrEnable := tarrSupport.reg.asBool && tarrEn.reg.asBool

  // The only writable capability field that reaches the wire (sec 4.5.3.3.1).
  phyOut.txVswingCode := txVswing.reg

  val phyControlFields: Seq[RegField] = Seq(
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

  // ===========================================================================
  // PHY Status
  // ===========================================================================
  val st = phyIn.phyStatus
  val phyStatusFields: Seq[RegField] = Seq(
    f.RsvdP(3, "phy_status_rsvd_2_0"),
    f.RO(
      1,
      st.rxTerminationStatus.asUInt,
      "rx_term_status",
      "PHY Status Rx Termination Status"
    ),
    f.RO(1, st.txEqStatus.asUInt, "tx_eq_status", "PHY Status Tx Eq Status"),
    f.RO(
      1,
      st.clockModeStatus.asUInt,
      "clock_mode_status",
      "PHY Status Clock Mode Status"
    ),
    f.RO(
      1,
      st.clockPhaseStatus.asUInt,
      "clock_phase_status",
      "PHY Status Clock Phase Status"
    ),
    f.RO(
      1,
      st.laneReversal.asUInt,
      "lane_reversal",
      "PHY Status Lane Reversal within Module"
    ),
    f.RO(
      6,
      st.iqCorrectionParam,
      "iq_correction_param",
      "PHY Status I/Q Correction Parameter"
    ),
    f.RO(
      4,
      st.eqPresetSetting,
      "eq_preset_setting",
      "PHY Status EQ Preset Setting"
    ),
    f.RO(1, st.tarrStatus.asUInt, "tarr_status", "PHY Status TARR operational"),
    f.RsvdP(13, "phy_status_rsvd_31_19")
  )

  // ===========================================================================
  // PHY Initialization and Debug
  // ===========================================================================
  // With no Test and Compliance block, only 000b to 010b pause (sec 9.5.3.25).
  val initControl = f.RW(
    3,
    0,
    "init_control",
    "Initialization control: 000b Active, 001b pause after MBINIT.PARAM " +
      "step 2, 010b pause after MBTRAIN.VALVREF step 1. 011b and 100b need " +
      "the Test and Compliance register block and decode as 000b here."
  )

  // [5] is RW, because two paused dies have no timeouts and need a way out.
  val resumeTraining = f.RWautoClear(
    hwDone = phyIn.resumeTrainingDone,
    ignoreWhen = !phyIn.trainingPaused,
    name = "resume_training",
    description = "A 0b-to-1b transition resumes training from the paused " +
      "state. Ignored while training is not paused, since the bit would " +
      "otherwise latch with nothing to clear it and release the next pause."
  )
  phyOut.phyInitControl := initControl.reg
  phyOut.resumeTrainingPending := resumeTraining.pending

  val phyInitDebugFields: Seq[RegField] = Seq(
    initControl.field,
    f.RsvdP(2, "phy_init_rsvd_4_3"),
    resumeTraining.field,
    f.RsvdP(26, "phy_init_rsvd_31_6")
  )

  // ===========================================================================
  // Runtime Link Test
  // ===========================================================================
  // One register with per-module fields, so Modules 1 to 3 read RsvdP (m < n).
  def applyRepair(m: Int, bit: Int): RegField =
    if (m < n) {
      val fld = f.RW(
        1,
        0,
        s"apply_module${m}_lane_repair",
        s"Runtime Link Test Control Apply Module $m Lane Repair"
      )
      phyOut.applyLaneRepair(m) := fld.reg
      fld.field
    } else f.RsvdP(1, s"apply_module${m}_lane_repair_rsvd")

  def repairId(m: Int, hi: Int, lo: Int): RegField =
    if (m < n) {
      val fld = f.RW(
        7,
        0,
        s"module${m}_lane_repair_id",
        s"Runtime Link Test Control Module $m Lane repair id"
      )
      phyOut.laneRepairId(m) := fld.reg
      fld.field
    } else f.RsvdP(7, s"module${m}_lane_repair_id_rsvd")

  // Bit 7 acts at the next retrain (sec 9.5.3.36), so software clears it.
  val injectStuckAt = f.RW(
    1,
    0,
    "inject_stuck_at",
    "Runtime Link Test Control Inject Stuck-at fault"
  )
  phyOut.injectStuckAt := injectStuckAt.reg

  val startAuto = f.RWautoClear(
    hwDone = phyIn.linkTestBusy,
    ignoreWhen = phyIn.linkTestBusy,
    name = "runtime_link_test_start",
    description = "Runtime Link Test Control Start (HW clears when Busy sets)"
  )
  phyOut.linkTestStart := startAuto.fire

  val (m3RepairLo, m3RepairHi) =
    if (n >= 4)
      splitRwField(
        3,
        4,
        "module3_lane_repair_id",
        "Runtime Link Test Control Module 3 Lane repair id"
      )
    else
      (
        f.RsvdP(3, "module3_lane_repair_id_rsvd_lo"),
        f.RsvdP(4, "module3_lane_repair_id_rsvd_hi")
      )

  val rltControlWord0: Seq[RegField] = Seq(
    f.RsvdP(2, "rlt_ctrl_1_0"),
    applyRepair(0, 2),
    applyRepair(1, 3),
    applyRepair(2, 4),
    applyRepair(3, 5),
    startAuto.field,
    injectStuckAt.field,
    repairId(0, 14, 8),
    repairId(1, 21, 15),
    repairId(2, 28, 22),
    m3RepairLo
  )
  val rltControlWord1: Seq[RegField] = Seq(
    m3RepairHi,
    f.RsvdP(28, "rlt_ctrl_rsvd_63_36")
  )

  val rltStatusFields: Seq[RegField] = Seq(
    f.RO(
      1,
      phyIn.linkTestBusy.asUInt,
      "runtime_link_test_busy",
      "Runtime Link Test Status Busy"
    ),
    f.RsvdZ(31, "rlt_status_rsvd_31_1")
  )

  // ===========================================================================
  // Register map
  // ===========================================================================
  def splitRwField(
      loWidth: Int,
      hiWidth: Int,
      name: String,
      description: String
  ): (RegField, RegField) = {
    val total = loWidth + hiWidth
    val reg = withReset(f.nonStickyReset)(RegInit(0.U(total.W)))
    phyOut.laneRepairId(3) := reg
    val loValid = WireDefault(false.B)
    val hiValid = WireDefault(false.B)
    val loData = WireDefault(0.U(loWidth.W))
    val hiData = WireDefault(0.U(hiWidth.W))
    val loWrite = RegWriteFn { (valid, data) =>
      loValid := valid
      loData := data(loWidth - 1, 0)
      true.B
    }
    val hiWrite = RegWriteFn { (valid, data) =>
      hiValid := valid
      hiData := data(hiWidth - 1, 0)
      true.B
    }
    when(loValid || hiValid) {
      reg := Cat(
        Mux(hiValid, hiData, reg(total - 1, loWidth)),
        Mux(loValid, loData, reg(loWidth - 1, 0))
      )
    }
    val loDesc = RegFieldDesc(
      s"${name}_lo",
      s"$description (low bits)",
      access = RegFieldAccessType.RW,
      reset = Some(0)
    )
    val hiDesc = RegFieldDesc(
      s"${name}_hi",
      s"$description (high bits)",
      access = RegFieldAccessType.RW,
      reset = Some(0)
    )
    (
      RegField(loWidth, reg(loWidth - 1, 0), loWrite, Some(loDesc)),
      RegField(hiWidth, reg(total - 1, loWidth), hiWrite, Some(hiDesc))
    )
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
      at(NullCapability) -> Seq(
        f.HWInit(32, 0, "null_ext_cap", "NULL Extended Capability DWORD")
      )
    )
  }
}
