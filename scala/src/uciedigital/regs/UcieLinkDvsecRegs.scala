// UCIe Link DVSEC registers.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util._
import freechips.rocketchip.regmapper.RegField

object DvsecOffsets {
  val ExtCapHeader = 0x00
  val DvsecHeader1 = 0x04
  val DvsecHeader2 = 0x08
  val LinkCapability = 0x0c
  val LinkControl = 0x10
  val LinkStatus = 0x14
  val Notification = 0x18
  val RegisterLocator0 = 0x1c

  def mailboxBase(numLocators: Int): Int = RegisterLocator0 + 8 * numLocators
  val MailboxSpan = 0x18

  def lengthBytes(numLocators: Int, hasMailbox: Boolean): Int =
    mailboxBase(numLocators) + (if (hasMailbox) MailboxSpan else 0)
}

class UcieLinkDvsecRegs(
    f: RegFieldTypes,
    linkIn: LinkToRegs,
    linkOut: RegsToLink,
    mbSb: MailboxToSideband,
    params: UcieRegParams
) {
  import DvsecOffsets._
  private val alloc = params.allocation
  private def b(x: Boolean): Int = if (x) 1 else 0
  private def ctrlWidthEncoding(w: Int): Int = log2Ceil(w) - 2

  private val extCapHeaderFields = Seq(
    f.HWInit(16, 0x0023, "pcie_ext_cap_id", "PCIe Ext Cap Header Capability ID (DVSEC)"),
    f.HWInit(4, 0x1, "pcie_ext_cap_rev", "PCIe Ext Cap Header Revision"),
    f.HWInit(12, 0x0, "pcie_ext_next_cap", "PCIe Ext Cap Header Next Capability Offset (no MSI/next cap)")
  )

  private val dvsecLength = DvsecOffsets.lengthBytes(alloc.numLocators, params.hasSbMailbox)
  private val dvsecHeader1Fields = Seq(
    f.HWInit(16, 0xd2de, "dvsec_vendor_id", "DVSEC Vendor ID (UCIe, spec-fixed)"),
    f.HWInit(4, 0x0, "dvsec_revision", "DVSEC Revision"),
    f.HWInit(12, dvsecLength, "dvsec_length", "DVSEC Length")
  )

  private val header2AndCapDescFields = Seq(
    f.HWInit(16, 0x0, "dvsec_id", "DVSEC ID"),
    f.HWInit(3, alloc.capabilityDescriptorLocatorCode, "cap_desc_num_locators",
      "Capability Descriptor Number of Register Locators"),
    f.HWInit(1, b(params.hasSbMailbox), "cap_desc_sb_mailbox",
      "Capability Descriptor Sideband mailbox present"),
    f.RsvdP(4, "cap_desc_num_dsps"),
    f.RsvdP(8, "cap_desc_rsvd_15_8")
  )

  private val advancedPkg = params.packageType == UciePackageType.Advanced
  private val apmw = advancedPkg && params.maxLinkWidth == 32
  private val spmw = !advancedPkg && params.maxLinkWidth == 8
  private val linkCapFields = Seq(
    f.HWInit(1, b(params.rawFormatCapable), "raw_format", "Link Capability Raw Format"),
    f.HWInit(3, UcieRegParams.widthEncoding(params.maxLinkWidth), "max_link_width", "Link Capability Max Link Width"),
    f.HWInit(4, UcieRegParams.speedEncoding(params.maxLinkSpeedGTs), "max_link_speed", "Link Capability Max Link Speeds"),
    f.RsvdP(1, "linkcap_retimer"),
    f.HWInit(1, 0, "multi_protocol_cap", "Link Capability Multi-protocol capable"),
    f.HWInit(1, b(advancedPkg), "advanced_packaging", "Link Capability Advanced Packaging"),
    f.HWInit(5, 0, "streaming_flit_caps", "Link Capability Streaming flit-format caps (Raw only)"),
    f.HWInit(1, 0, "enhanced_multi_proto_cap", "Link Capability Enhanced Multi-protocol"),
    f.HWInit(2, 0, "pcie_flit_caps", "Link Capability PCIe flit-format caps"),
    f.HWInit(1, 0, "runtime_link_test_parity_cap", "Link Capability Runtime Link Testing Parity"),
    f.HWInit(1, b(apmw), "apmw", "Link Capability APMW (Advanced Package Module Width)"),
    f.RsvdP(1, "x32_in_x64"),
    f.HWInit(1, b(spmw), "spmw", "Link Capability SPMW (Standard Package Module Width)"),
    f.HWInit(3, 0, "sb_feature_caps", "Link Capability PMO/PSPT/L2SPD"),
    f.RsvdP(6, "linkcap_rsvd_31_26")
  )

  private val rawFormatEnable = f.RW(1, 0, "raw_format_enable", "Link Control Raw Format Enable")
  private val multiProtoEn = f.RW(1, 0, "multi_protocol_enable", "Link Control Multi-protocol enable")
  private val targetWidth = f.RW(4, ctrlWidthEncoding(params.maxLinkWidth), "target_link_width",
    "Link Control Target Link Width")
  private val targetSpeed = f.RW(4, UcieRegParams.speedEncoding(params.maxLinkSpeedGTs), "target_link_speed",
    "Link Control Target Link Speed")
  private val startTrain = f.RWautoClear(linkIn.trainingDone, linkIn.linkTraining, "start_link_training",
    "Link Control Start UCIe Link training (auto-clear)")
  private val retrain = f.RWautoClear(linkIn.retrainDone, !linkIn.linkUp, "retrain_link",
    "Link Control Retrain UCIe Link (auto-clear)")
  private val linkControlFields = Seq(
    rawFormatEnable.field,
    multiProtoEn.field,
    targetWidth.field,
    targetSpeed.field,
    startTrain.field,
    retrain.field,
    f.HWInit(1, 0, "link_ctrl_unused", "Link Control Unused (implemented RO 0)"),
    f.RW(5, 0, "flit_format_enables", "Link Control Streaming flit-format enables").field,
    f.RW(1, 0, "enhanced_multi_proto_enable", "Link Control Enhanced Multi-protocol enable").field,
    f.RW(2, 0, "pcie_flit_enables", "Link Control PCIe flit-format enables").field,
    f.RW(3, 0, "sb_feature_enables", "Link Control PMO/PSPT/L2SPD enables").field,
    f.RsvdP(8, "link_ctrl_rsvd_31_24")
  )
  linkOut.rawFormatEnable := rawFormatEnable.reg.asBool
  linkOut.targetWidth := targetWidth.reg
  linkOut.targetSpeed := targetSpeed.reg
  linkOut.startTraining := startTrain.fire
  linkOut.retrain := retrain.fire

  private val statusChanged = f.RW1C(1, linkIn.statusChanged.asUInt, "link_status_changed", "Link Status changed")
  private val bwChanged = f.RW1C(1, linkIn.bwChanged.asUInt, "hw_auton_bw_changed", "Link Status HW autonomous BW changed")
  private val corrErr = f.RW1CS(1, linkIn.corrErr.asUInt, "detected_corr_err", "Link Status Detected correctable error")
  private val uncorrNonFatal = f.RW1CS(1, linkIn.uncorrNonFatal.asUInt, "detected_uncorr_nonfatal", "Link Status Detected uncorrectable non-fatal error")
  private val uncorrFatal = f.RW1CS(1, linkIn.uncorrFatal.asUInt, "detected_uncorr_fatal", "Link Status Detected uncorrectable fatal error")
  private val linkStatusFields = Seq(
    f.RO(1, linkIn.rawFormatEnabled.asUInt, "raw_format_enabled", "Link Status Raw Format Enabled"),
    f.RO(1, 0.U, "multi_protocol_enabled", "Link Status Multi-protocol enabled"),
    f.RO(1, 0.U, "enhanced_multi_proto_enabled", "Link Status Enhanced Multi-protocol enabled"),
    f.RO(1, linkIn.x32AdvPkgEnabled.asUInt, "x32_adv_pkg_enabled", "Link Status x32 Advanced Package Module enabled"),
    f.RsvdZ(3, "link_status_rsvd_6_4"),
    f.RO(4, linkIn.linkWidthEnabled, "link_width_enabled", "Link Status Link Width enabled"),
    f.RO(4, linkIn.linkSpeedEnabled, "link_speed_enabled", "Link Status Link Speed enabled"),
    f.RO(1, linkIn.linkUp.asUInt, "link_status", "Link Status (1 = up)"),
    f.RO(1, linkIn.linkTraining.asUInt, "link_training", "Link Status Link Training/Retraining"),
    statusChanged.field,
    bwChanged.field,
    corrErr.field,
    uncorrNonFatal.field,
    uncorrFatal.field,
    f.RO(4, linkIn.flitFormat, "flit_format_status", "Link Status Flit Format Status"),
    f.HWInit(3, 0, "sb_feature_status", "Link Status PMO/PSPT/L2SPD status"),
    f.RsvdZ(3, "link_status_rsvd_31_29")
  )

  private val statusChangedIntEn = f.RW(1, 0, "link_status_changed_int_en", "Link Event Notif Link Status changed interrupt enable")
  private val bwChangedIntEn = f.RW(1, 0, "bw_changed_int_en", "Link Event Notif HW autonomous BW changed interrupt enable")
  private val corrProtoEn = f.RW(1, 0, "corr_proto_report_en", "Error Notif Correctable protocol-layer reporting enable")
  private val corrIntEn = f.RW(1, 0, "corr_int_en", "Error Notif Correctable interrupt enable")
  private val nonFatalProtoEn = f.RW(1, 0, "nonfatal_proto_report_en", "Error Notif Uncorr non-fatal protocol-layer reporting enable")
  private val nonFatalIntEn = f.RW(1, 0, "nonfatal_int_en", "Error Notif Uncorr non-fatal interrupt enable")
  private val fatalProtoEn = f.RW(1, 0, "fatal_proto_report_en", "Error Notif Uncorr fatal protocol-layer reporting enable")
  private val fatalIntEn = f.RW(1, 0, "fatal_int_en", "Error Notif Uncorr fatal interrupt enable")
  private val notificationFields = Seq(
    statusChangedIntEn.field,
    bwChangedIntEn.field,
    f.RsvdP(9, "link_event_rsvd_10_2"),
    f.HWInit(5, 0, "link_event_int_number", "Link Event Notif Interrupt number (no MSI)"),
    corrProtoEn.field,
    corrIntEn.field,
    nonFatalProtoEn.field,
    nonFatalIntEn.field,
    fatalProtoEn.field,
    fatalIntEn.field,
    f.RsvdP(5, "error_notif_rsvd_10_6"),
    f.HWInit(5, 0, "error_int_number", "Error Notif Interrupt number (no MSI)")
  )
  linkOut.corrProtoReport := corrProtoEn.reg.asBool
  linkOut.nonFatalProtoReport := nonFatalProtoEn.reg.asBool
  linkOut.fatalProtoReport := fatalProtoEn.reg.asBool

  val linkEventIrq: Bool =
    (statusChanged.reg.asBool && statusChangedIntEn.reg.asBool) ||
      (bwChanged.reg.asBool && bwChangedIntEn.reg.asBool)
  val linkErrorIrq: Bool =
    (corrErr.reg.asBool && corrIntEn.reg.asBool) ||
      (uncorrNonFatal.reg.asBool && nonFatalIntEn.reg.asBool) ||
      (uncorrFatal.reg.asBool && fatalIntEn.reg.asBool)

  private val locators: Seq[(Int, BigInt)] =
    Seq((0x0, alloc.d2dPhyBase)) ++
      alloc.phyVendorBase.map(off => (0x3, off)).toSeq ++
      alloc.d2dVendorBase.map(off => (0x2, off)).toSeq
  private def locatorLow(blockId: Int, off: BigInt, idx: Int): Seq[RegField] = Seq(
    f.RsvdP(3, s"locator${idx}_bir"),
    f.HWInit(4, blockId, s"locator${idx}_block_id", s"Register Locator $idx Block Identifier"),
    f.RsvdP(5, s"locator${idx}_rsvd_11_7"),
    f.HWInit(20, off >> 12, s"locator${idx}_block_offset", s"Register Locator $idx Block Offset")
  )

  private val mailbox: Option[SidebandMailbox] =
    if (params.hasSbMailbox) Some(new SidebandMailbox(f, mbSb, alloc.numLocators)) else None
  if (mailbox.isEmpty) {
    mbSb.req.valid := false.B
    mbSb.req.bits := 0.U.asTypeOf(new MailboxSbReq)
    mbSb.resp.ready := false.B
  }
  val mailboxHeaderLog1: Valid[UInt] = {
    val w = Wire(Valid(UInt(64.W)))
    mailbox match {
      case Some(m) => w := m.headerLog1
      case None =>
        w.valid := false.B
        w.bits := 0.U
    }
    w
  }

  def entries(base: BigInt): Seq[RegField.Map] = {
    def at(off: Int): Int = (base + off).toInt
    val fixed = Seq(
      at(ExtCapHeader) -> extCapHeaderFields,
      at(DvsecHeader1) -> dvsecHeader1Fields,
      at(DvsecHeader2) -> header2AndCapDescFields,
      at(LinkCapability) -> linkCapFields,
      at(LinkControl) -> linkControlFields,
      at(LinkStatus) -> linkStatusFields,
      at(Notification) -> notificationFields
    )
    val locatorEntries = locators.zipWithIndex.flatMap { case ((blockId, off), idx) =>
      Seq(
        at(RegisterLocator0 + 8 * idx) -> locatorLow(blockId, off, idx),
        at(RegisterLocator0 + 8 * idx + 4) -> Seq(f.HWInit(32, 0, s"locator${idx}_block_offset_hi", s"Register Locator $idx High"))
      )
    }
    fixed ++ locatorEntries ++ mailbox.map(_.entries(base)).getOrElse(Nil)
  }
}
