/*
  The D2D Adapter registers of the D2D/PHY block (sec 9.5.3.2 to sec 9.5.3.21).
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util.PriorityEncoder
import freechips.rocketchip.regmapper.RegField

object D2DAdapterOffsets {
  val UncorrStatus = 0x10
  val UncorrMask = 0x14
  val UncorrSeverity = 0x18
  val CorrStatus = 0x1c
  val CorrMask = 0x20
  val HeaderLog1 = 0x24
  val HeaderLog2 = 0x2c
  val ErrLinkTestingControl = 0x30
  val ParityLog0 = 0x34
  val ParityLog1 = 0x3c
  val ParityLog2 = 0x44
  val ParityLog3 = 0x4c
  val AdvAdapterCap = 0x54
  val FinAdapterCap = 0x5c
  val AdvCxlCap = 0x64
  val FinCxlCap = 0x6c
  val AdvMultiProtCap = 0x78
  val FinMultiProtCap = 0x80
  val AdvCxlCapStack1 = 0x88
  val FinCxlCapStack1 = 0x90
}

class D2DAdapterRegs(f: RegFieldTypes, io: AdapterToRegs, out: RegsToAdapter) {
  import D2DAdapterOffsets._

  // ===========================================================================
  // Error status and mask
  // ===========================================================================
  val uncorrStatus = f.RW1CS(
    6,
    io.uncorrErrSet.asUInt,
    "uncorr_err_status",
    "Uncorrectable Error Status: Timeout/RxOverflow/Internal/SB-Fatal/SB-NonFatal/InvalidParamExch"
  )

  val uncorrMask =
    f.RWS(6, 0x3f, "uncorr_err_mask", "Uncorrectable Error Mask")
  out.uncorrMask := uncorrMask.reg

  val uncorrSeverity =
    f.RWS(6, 0x2f, "uncorr_err_severity", "Uncorrectable Error Severity")
  out.uncorrSeverity := uncorrSeverity.reg

  val corrStatus = f.RW1CS(
    5,
    io.corrErrSet.asUInt,
    "corr_err_status",
    "Correctable Error Status: CRC/LSM->Retrain/CorrInternal/SB-Corr/ParityErr"
  )

  val corrMask =
    f.RWS(5, 0x1f, "corr_err_mask", "Correctable Error Mask")
  out.corrMask := corrMask.reg

  // ===========================================================================
  // Header logs
  // ===========================================================================
  val hl1Lo = f.ROS(32, 0, "header_log1_lo", "Header Log 1")
  val hl1Hi = f.ROS(32, 0, "header_log1_hi", "Header Log 1")
  when(io.headerLog1.valid) {
    hl1Lo.reg := io.headerLog1.bits(31, 0)
    hl1Hi.reg := io.headerLog1.bits(63, 32)
  }.elsewhen(io.headerLog1Rearm) {
    hl1Lo.reg := 0.U
    hl1Hi.reg := 0.U
  }

  val hl2TimeoutEnc =
    f.ROS(4, 0, "hl2_timeout_enc", "Header Log 2 Adapter Timeout encoding")
  val hl2RxOverflowEnc = f.ROS(
    3,
    0,
    "hl2_rx_overflow_enc",
    "Header Log 2 Receiver overflow encoding"
  )
  val hl2LsmResponse =
    f.ROS(3, 0, "hl2_lsm_response", "Header Log 2 Adapter LSM response type")
  val hl2LsmId =
    f.ROS(1, 0, "hl2_lsm_id", "Header Log 2 Adapter LSM id")
  val hl2FlitFormat =
    f.ROS(4, 0, "hl2_flit_format", "Header Log 2 negotiated Flit Format")
  val hl2FirstFatal =
    f.ROS(5, 0, "hl2_first_fatal", "Header Log 2 First Fatal Error Indicator")

  when(io.uncorrErrSet(0) && !uncorrStatus.reg(0)) {
    hl2TimeoutEnc.reg := io.headerLog2.timeoutEnc
    hl2LsmResponse.reg := io.headerLog2.lsmResponse
    hl2LsmId.reg := io.headerLog2.lsmId
  }
  when(io.uncorrErrSet(1) && !uncorrStatus.reg(1)) {
    hl2RxOverflowEnc.reg := io.headerLog2.rxOverflowEnc
  }
  when(io.headerLog2.paramExchSuccess) {
    hl2FlitFormat.reg := io.headerLog2.flitFormat
  }

  val ffValid = withReset(f.stickyReset)(RegInit(false.B))
  val anySet = io.uncorrErrSet.asUInt.orR
  when(!ffValid && anySet) {
    hl2FirstFatal.reg := PriorityEncoder(io.uncorrErrSet)
    ffValid := true.B
  }
  when(ffValid && !uncorrStatus.reg(hl2FirstFatal.reg(2, 0))) {
    ffValid := false.B
  }

  val hl2ParamExchSuccess =
    f.RO(
      1,
      io.headerLog2.paramExchSuccess,
      "hl2_param_exch_success",
      "Header Log 2 Parameter Exchange Successful"
    )

  // ===========================================================================
  // Error and Link Testing Control
  // ===========================================================================
  val remoteRegAccessThreshold = f.RW(
    4,
    0x4,
    "remote_reg_access_threshold",
    "Error & Link Testing Control Remote Register Access Threshold"
  )
  out.remoteRegAccessThreshold := remoteRegAccessThreshold.reg

  // RW per Table 9-34, but with Table 9-8 bit 19 at 0 they only store a value.
  val runtimeTestTxEn =
    f.RW(1, 0, "runtime_link_test_tx_en", "Runtime Link Testing Tx Enable")
  val runtimeTestRxEn =
    f.RW(1, 0, "runtime_link_test_rx_en", "Runtime Link Testing Rx Enable")
  val num64bInserts =
    f.RW(3, 0, "num_64b_inserts", "Number of 64 Byte Inserts")

  // Raw Format has no CRC to corrupt (sec 3.3.1), so these only store a value.
  val crcInjectionEnable =
    f.RW(2, 0, "crc_injection_enable", "CRC Injection Enable")
  val crcInjectionCount =
    f.RW(2, 0, "crc_injection_count", "CRC Injection Count")

  // ===========================================================================
  // Capability and parity logs
  // ===========================================================================
  def capLog64(
      payload: chisel3.util.Valid[UInt],
      name: String,
      desc: String
  ) = {
    val setLo = Mux(payload.valid, payload.bits(31, 0), 0.U)
    val setHi = Mux(payload.valid, payload.bits(63, 32), 0.U)
    (
      f.RW1C(32, setLo, s"${name}_lo", desc),
      f.RW1C(32, setHi, s"${name}_hi", desc)
    )
  }
  val (advAdapterLo, advAdapterHi) =
    capLog64(
      io.advCapAdapter,
      "adv_adapter_cap",
      "Advertised Adapter Capability Log"
    )
  val (finAdapterLo, finAdapterHi) =
    capLog64(
      io.finCapAdapter,
      "fin_adapter_cap",
      "Finalized Adapter Capability Log"
    )

  def parityLog64(offset: Int, name: String): Seq[RegField.Map] = {
    val d = "Runtime Link Testing Parity Log (feature off), RW1C tied 0"
    f.rows64(
      offset,
      f.RW1C(32, 0.U(32.W), s"${name}_lo", d).field,
      f.RW1C(32, 0.U(32.W), s"${name}_hi", d).field
    )
  }

  def rsvdLog64(offset: Int, name: String): Seq[RegField.Map] =
    f.rows64(offset, f.RsvdP(32, s"${name}_lo"), f.RsvdP(32, s"${name}_hi"))

  // ===========================================================================
  // Register map
  // ===========================================================================
  def entries(base: BigInt): Seq[RegField.Map] = {
    def at(off: Int): Int = (base + off).toInt

    val statusMaskRows: Seq[RegField.Map] = Seq(
      f.paddedRow(
        at(UncorrStatus),
        uncorrStatus.field,
        6,
        f.RsvdZ(_, "uncorr_status_rsvd")
      ),
      f.paddedRow(
        at(UncorrMask),
        uncorrMask.field,
        6,
        f.RsvdP(_, "uncorr_mask_rsvd")
      ),
      f.paddedRow(
        at(UncorrSeverity),
        uncorrSeverity.field,
        6,
        f.RsvdP(_, "uncorr_severity_rsvd")
      ),
      f.paddedRow(
        at(CorrStatus),
        corrStatus.field,
        5,
        f.RsvdZ(_, "corr_status_rsvd")
      ),
      f.paddedRow(at(CorrMask), corrMask.field, 5, f.RsvdP(_, "corr_mask_rsvd"))
    )

    val headerLogRows: Seq[RegField.Map] =
      f.rows64(at(HeaderLog1), hl1Lo.field, hl1Hi.field) ++ Seq(
        at(HeaderLog2) -> Seq(
          hl2TimeoutEnc.field,
          hl2RxOverflowEnc.field,
          hl2LsmResponse.field,
          hl2LsmId.field,
          f.RsvdZ(2, "hl2_rsvd_12_11"),
          hl2ParamExchSuccess,
          hl2FlitFormat.field,
          hl2FirstFatal.field,
          f.RsvdZ(9, "hl2_rsvd_31_23")
        )
      )

    val errLinkTestingRow: Seq[RegField.Map] = Seq(
      at(ErrLinkTestingControl) -> Seq(
        remoteRegAccessThreshold.field,
        runtimeTestTxEn.field,
        runtimeTestRxEn.field,
        num64bInserts.field,

        // Reads 0, because no {ParityFeature.Req} is sent, so no Nak returns.
        f.RW1C(
          1,
          false.B,
          "parity_feature_nak_received",
          "Parity Feature Nak received (never set: Raw Format)"
        ).field,
        f.RsvdP(3, "elt_rsvd_12_10"),
        crcInjectionEnable.field,
        crcInjectionCount.field,

        // No injection ever starts, so it reads 0.
        f.RO(1, 0.U, "crc_injection_busy", "CRC Injection Busy"),
        f.RsvdP(14, "elt_rsvd_31_18")
      )
    )

    val parityLogRows: Seq[RegField.Map] =
      parityLog64(at(ParityLog0), "parity_log0") ++
        parityLog64(at(ParityLog1), "parity_log1") ++
        parityLog64(at(ParityLog2), "parity_log2") ++
        parityLog64(at(ParityLog3), "parity_log3")

    val adapterCapRows: Seq[RegField.Map] =
      f.rows64(at(AdvAdapterCap), advAdapterLo.field, advAdapterHi.field) ++
        f.rows64(at(FinAdapterCap), finAdapterLo.field, finAdapterHi.field)

    val reservedCapRows: Seq[RegField.Map] =
      rsvdLog64(at(AdvCxlCap), "adv_cxl_cap") ++
        rsvdLog64(at(FinCxlCap), "fin_cxl_cap") ++
        rsvdLog64(at(AdvMultiProtCap), "adv_multiprot_cap") ++
        rsvdLog64(at(FinMultiProtCap), "fin_multiprot_cap") ++
        rsvdLog64(at(AdvCxlCapStack1), "adv_cxl_cap_stack1") ++
        rsvdLog64(at(FinCxlCapStack1), "fin_cxl_cap_stack1")

    statusMaskRows ++ headerLogRows ++ errLinkTestingRow ++ parityLogRows ++
      adapterCapRows ++ reservedCapRows
  }
}
