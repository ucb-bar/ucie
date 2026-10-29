/*
  Sideband opcodes and message encodings (Table 7-1, Table 7-8 to Table 7-11).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._

object SBMsgOpcode extends ChiselEnum {
  val MemoryRead_32b = Value("b00000".U(5.W))
  val MemoryWrite_32b = Value("b00001".U(5.W))
  val DMSRegisterRead_32b = Value("b00010".U(5.W))
  val DMSRegisterWrite_32b = Value("b00011".U(5.W))
  val ConfigurationRead_32b = Value("b00100".U(5.W))
  val ConfigurationWrite_32b = Value("b00101".U(5.W))
  val MemoryRead_64b = Value("b01000".U(5.W))
  val MemoryWrite_64b = Value("b01001".U(5.W))
  val DMSRegisterRead_64b = Value("b01010".U(5.W))
  val DMSRegisterWrite_64b = Value("b01011".U(5.W))
  val ConfigurationRead_64b = Value("b01100".U(5.W))
  val ConfigurationWrite_64b = Value("b01101".U(5.W))
  val CompletionWithoutData = Value("b10000".U(5.W))
  val CompletionWith32bData = Value("b10001".U(5.W))
  val MessageWithoutData = Value("b10010".U(5.W))
  val ManagementPortMsgWithoutData = Value("b10111".U(5.W))
  val ManagementPortMsgWithData = Value("b11000".U(5.W))
  val CompletionWith64bData = Value("b11001".U(5.W))
  val MessageWith64bData = Value("b11011".U(5.W))

  // Priority packets (sec 4.1.5.2).
  val BackToBackPriorityPacket = Value("b11110".U(5.W))
  val SinglePriorityPacket = Value("b11111".U(5.W))

  val OpsWithoutData = Seq(
    MemoryRead_32b,
    DMSRegisterRead_32b,
    ConfigurationRead_32b,
    MemoryRead_64b,
    DMSRegisterRead_64b,
    ConfigurationRead_64b,
    CompletionWithoutData,
    MessageWithoutData,
    ManagementPortMsgWithoutData
  )

  val OpsThatDontUseDPField = Seq(
    ManagementPortMsgWithData,
    BackToBackPriorityPacket,
    SinglePriorityPacket
  ) ++ OpsWithoutData
}

object SBM {
  val SC = "b000".U(3.W)
  val UR = "b001".U(3.W)
  val CA = "b100".U(3.W)
  val Stall = "b111".U(3.W)

  // Each message is {opcode, msgCode, msgSubcode}.

  // ===========================================================================
  // Messages without data
  // ===========================================================================
  def NOP_CRD = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h00".U(8.W),
    "h00".U(8.W)
  )

  def LINKMGMT_RDI_REQ_ACTIVE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h01".U(8.W),
    "h01".U(8.W)
  )
  def LINKMGMT_RDI_REQ_L1 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h01".U(8.W),
    "h04".U(8.W)
  )
  def LINKMGMT_RDI_REQ_L2 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h01".U(8.W),
    "h08".U(8.W)
  )
  def LINKMGMT_RDI_REQ_LINKRESET = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h01".U(8.W),
    "h09".U(8.W)
  )
  def LINKMGMT_RDI_REQ_LINKERROR = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h01".U(8.W),
    "h0A".U(8.W)
  )
  def LINKMGMT_RDI_REQ_RETRAIN = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h01".U(8.W),
    "h0B".U(8.W)
  )
  def LINKMGMT_RDI_REQ_DISABLE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h01".U(8.W),
    "h0C".U(8.W)
  )

  def LINKMGMT_RDI_RSP_ACTIVE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h02".U(8.W),
    "h01".U(8.W)
  )
  def LINKMGMT_RDI_RSP_PMNAK = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h02".U(8.W),
    "h02".U(8.W)
  )
  def LINKMGMT_RDI_RSP_L1 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h02".U(8.W),
    "h04".U(8.W)
  )
  def LINKMGMT_RDI_RSP_L2 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h02".U(8.W),
    "h08".U(8.W)
  )
  def LINKMGMT_RDI_RSP_LINKRESET = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h02".U(8.W),
    "h09".U(8.W)
  )
  def LINKMGMT_RDI_RSP_LINKERROR = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h02".U(8.W),
    "h0A".U(8.W)
  )
  def LINKMGMT_RDI_RSP_RETRAIN = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h02".U(8.W),
    "h0B".U(8.W)
  )
  def LINKMGMT_RDI_RSP_DISABLE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h02".U(8.W),
    "h0C".U(8.W)
  )

  def LINKMGMT_ADAPTER0_REQ_ACTIVE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h03".U(8.W),
    "h01".U(8.W)
  )
  def LINKMGMT_ADAPTER0_REQ_L1 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h03".U(8.W),
    "h04".U(8.W)
  )
  def LINKMGMT_ADAPTER0_REQ_L2 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h03".U(8.W),
    "h08".U(8.W)
  )
  def LINKMGMT_ADAPTER0_REQ_LINKRESET = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h03".U(8.W),
    "h09".U(8.W)
  )
  def LINKMGMT_ADAPTER0_REQ_DISABLE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h03".U(8.W),
    "h0C".U(8.W)
  )

  def LINKMGMT_ADAPTER0_RSP_ACTIVE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h04".U(8.W),
    "h01".U(8.W)
  )
  def LINKMGMT_ADAPTER0_RSP_PMNAK = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h04".U(8.W),
    "h02".U(8.W)
  )
  def LINKMGMT_ADAPTER0_RSP_L1 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h04".U(8.W),
    "h04".U(8.W)
  )
  def LINKMGMT_ADAPTER0_RSP_L2 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h04".U(8.W),
    "h08".U(8.W)
  )
  def LINKMGMT_ADAPTER0_RSP_LINKRESET = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h04".U(8.W),
    "h09".U(8.W)
  )
  def LINKMGMT_ADAPTER0_RSP_DISABLE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h04".U(8.W),
    "h0C".U(8.W)
  )

  def PARITY_FEATURE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h07".U(8.W),
    "h00".U(8.W)
  )
  def PARITY_FEATURE_ACK = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h08".U(8.W),
    "h00".U(8.W)
  )
  def PARITY_FEATURE_NAK = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h08".U(8.W),
    "h01".U(8.W)
  )

  def LINKMGMT_ADAPTER1_REQ_ACTIVE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h05".U(8.W),
    "h01".U(8.W)
  )
  def LINKMGMT_ADAPTER1_REQ_L1 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h05".U(8.W),
    "h04".U(8.W)
  )
  def LINKMGMT_ADAPTER1_REQ_L2 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h05".U(8.W),
    "h08".U(8.W)
  )
  def LINKMGMT_ADAPTER1_REQ_LINKRESET = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h05".U(8.W),
    "h09".U(8.W)
  )
  def LINKMGMT_ADAPTER1_REQ_DISABLE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h05".U(8.W),
    "h0C".U(8.W)
  )

  def LINKMGMT_ADAPTER1_RSP_ACTIVE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h06".U(8.W),
    "h01".U(8.W)
  )
  def LINKMGMT_ADAPTER1_RSP_PMNAK = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h06".U(8.W),
    "h02".U(8.W)
  )
  def LINKMGMT_ADAPTER1_RSP_L1 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h06".U(8.W),
    "h04".U(8.W)
  )
  def LINKMGMT_ADAPTER1_RSP_L2 = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h06".U(8.W),
    "h08".U(8.W)
  )
  def LINKMGMT_ADAPTER1_RSP_LINKRESET = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h06".U(8.W),
    "h09".U(8.W)
  )
  def LINKMGMT_ADAPTER1_RSP_DISABLE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h06".U(8.W),
    "h0C".U(8.W)
  )

  def ERRMSG_CORRECTABLE = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h09".U(8.W),
    "h00".U(8.W)
  )
  def ERRMSG_NONFATAL = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h09".U(8.W),
    "h01".U(8.W)
  )
  def ERRMSG_FATAL = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h09".U(8.W),
    "h02".U(8.W)
  )

  // ===========================================================================
  // Link training messages without data
  // ===========================================================================
  def START_TX_INIT_D2C_POINT_TEST_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h01".U(8.W)
  )
  def LFSR_CLEAR_ERROR_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h85".U(8.W),
    "h02".U(8.W)
  )
  def LFSR_CLEAR_ERROR_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h02".U(8.W)
  )
  def TX_INIT_D2C_RESULTS_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h85".U(8.W),
    "h03".U(8.W)
  )

  def END_TX_INIT_D2C_POINT_TEST_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h85".U(8.W),
    "h04".U(8.W)
  )
  def END_TX_INIT_D2C_POINT_TEST_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h04".U(8.W)
  )
  def START_TX_INIT_D2C_EYE_SWEEP_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h05".U(8.W)
  )
  def END_TX_INIT_D2C_EYE_SWEEP_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h85".U(8.W),
    "h06".U(8.W)
  )

  def END_TX_INIT_D2C_EYE_SWEEP_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h06".U(8.W)
  )
  def START_RX_INIT_D2C_POINT_TEST_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h07".U(8.W)
  )
  def RX_INIT_D2C_TX_COUNT_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h85".U(8.W),
    "h08".U(8.W)
  )
  def RX_INIT_D2C_TX_COUNT_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h08".U(8.W)
  )

  def END_RX_INIT_D2C_POINT_TEST_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h85".U(8.W),
    "h09".U(8.W)
  )
  def END_RX_INIT_D2C_POINT_TEST_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h09".U(8.W)
  )
  def START_RX_INIT_D2C_EYE_SWEEP_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h0A".U(8.W)
  )
  def RX_INIT_D2C_RESULTS_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h85".U(8.W),
    "h0B".U(8.W)
  )

  def END_RX_INIT_D2C_EYE_SWEEP_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h85".U(8.W),
    "h0D".U(8.W)
  )
  def END_RX_INIT_D2C_EYE_SWEEP_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h8A".U(8.W),
    "h0D".U(8.W)
  )
  def SBINIT_OUT_OF_RESET = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h91".U(8.W),
    "h00".U(8.W)
  )
  def SBINIT_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h95".U(8.W),
    "h01".U(8.W)
  )

  def SBINIT_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "h9A".U(8.W),
    "h01".U(8.W)
  )
  def MBINIT_CAL_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h02".U(8.W)
  )
  def MBINIT_CAL_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h02".U(8.W)
  )
  def MBINIT_REPAIRCLK_INIT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h03".U(8.W)
  )

  def MBINIT_REPAIRCLK_INIT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h03".U(8.W)
  )
  def MBINIT_REPAIRCLK_RESULT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h04".U(8.W)
  )
  def MBINIT_REPAIRCLK_RESULT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h04".U(8.W)
  )
  def MBINIT_REPAIRCLK_APPLY_REPAIR_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h05".U(8.W)
  )

  def MBINIT_REPAIRCLK_APPLY_REPAIR_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h05".U(8.W)
  )
  def MBINIT_REPAIRCLK_CHECK_REPAIR_INIT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h06".U(8.W)
  )
  def MBINIT_REPAIRCLK_CHECK_REPAIR_INIT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h06".U(8.W)
  )
  def MBINIT_REPAIRCLK_CHECK_RESULT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h07".U(8.W)
  )

  def MBINIT_REPAIRCLK_CHECK_RESULT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h07".U(8.W)
  )
  def MBINIT_REPAIRCLK_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h08".U(8.W)
  )
  def MBINIT_REPAIRCLK_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h08".U(8.W)
  )
  def MBINIT_REPAIRVAL_INIT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h09".U(8.W)
  )

  def MBINIT_REPAIRVAL_INIT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h09".U(8.W)
  )
  def MBINIT_REPAIRVAL_RESULT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h0A".U(8.W)
  )
  def MBINIT_REPAIRVAL_RESULT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h0A".U(8.W)
  )
  def MBINIT_REPAIRVAL_APPLY_REPAIR_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h0B".U(8.W)
  )

  def MBINIT_REPAIRVAL_APPLY_REPAIR_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h0B".U(8.W)
  )
  def MBINIT_REPAIRVAL_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h0C".U(8.W)
  )
  def MBINIT_REPAIRVAL_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h0C".U(8.W)
  )
  def MBINIT_REVERSALMB_INIT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h0D".U(8.W)
  )

  def MBINIT_REVERSALMB_INIT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h0D".U(8.W)
  )
  def MBINIT_REVERSALMB_CLEAR_ERROR_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h0E".U(8.W)
  )
  def MBINIT_REVERSALMB_CLEAR_ERROR_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h0E".U(8.W)
  )
  def MBINIT_REVERSALMB_RESULT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h0F".U(8.W)
  )

  def MBINIT_REVERSALMB_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h10".U(8.W)
  )
  def MBINIT_REVERSALMB_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h10".U(8.W)
  )
  def MBINIT_REPAIRMB_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h11".U(8.W)
  )
  def MBINIT_REPAIRMB_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h11".U(8.W)
  )

  def MBINIT_REPAIRMB_APPLY_REPAIR_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h12".U(8.W)
  )
  def MBINIT_REPAIRMB_END_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h13".U(8.W)
  )
  def MBINIT_REPAIRMB_END_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h13".U(8.W)
  )
  def MBINIT_REPAIRMB_APPLY_DEGRADE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hA5".U(8.W),
    "h14".U(8.W)
  )

  def MBINIT_REPAIRMB_APPLY_DEGRADE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hAA".U(8.W),
    "h14".U(8.W)
  )
  def MBTRAIN_VALVREF_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h00".U(8.W)
  )
  def MBTRAIN_VALVREF_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h00".U(8.W)
  )
  def MBTRAIN_VALVREF_END_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h01".U(8.W)
  )

  def MBTRAIN_VALVREF_END_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h01".U(8.W)
  )
  def MBTRAIN_DATAVREF_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h02".U(8.W)
  )
  def MBTRAIN_DATAVREF_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h02".U(8.W)
  )
  def MBTRAIN_DATAVREF_END_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h03".U(8.W)
  )

  def MBTRAIN_DATAVREF_END_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h03".U(8.W)
  )
  def MBTRAIN_SPEEDIDLE_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h04".U(8.W)
  )
  def MBTRAIN_SPEEDIDLE_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h04".U(8.W)
  )
  def MBTRAIN_TXSELFCAL_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h05".U(8.W)
  )

  def MBTRAIN_TXSELFCAL_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h05".U(8.W)
  )
  def MBTRAIN_RXCLKCAL_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h06".U(8.W)
  )
  def MBTRAIN_RXCLKCAL_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h06".U(8.W)
  )
  def MBTRAIN_RXCLKCAL_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h07".U(8.W)
  )

  def MBTRAIN_RXCLKCAL_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h07".U(8.W)
  )
  def MBTRAIN_VALTRAINCENTER_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h08".U(8.W)
  )
  def MBTRAIN_VALTRAINCENTER_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h08".U(8.W)
  )
  def MBTRAIN_VALTRAINCENTER_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h09".U(8.W)
  )

  def MBTRAIN_VALTRAINCENTER_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h09".U(8.W)
  )
  def MBTRAIN_VALTRAINVREF_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h0A".U(8.W)
  )
  def MBTRAIN_VALTRAINVREF_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h0A".U(8.W)
  )

  // Named DONE as in Table 7-9. Chapter 4 calls it END.
  def MBTRAIN_VALTRAINVREF_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h0B".U(8.W)
  )

  def MBTRAIN_VALTRAINVREF_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h0B".U(8.W)
  )
  def MBTRAIN_DATATRAINCENTER1_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h0C".U(8.W)
  )
  def MBTRAIN_DATATRAINCENTER1_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h0C".U(8.W)
  )
  def MBTRAIN_DATATRAINCENTER1_END_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h0D".U(8.W)
  )

  def MBTRAIN_DATATRAINCENTER1_END_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h0D".U(8.W)
  )
  def MBTRAIN_DATATRAINVREF_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h0E".U(8.W)
  )
  def MBTRAIN_DATATRAINVREF_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h0E".U(8.W)
  )
  def MBTRAIN_DATATRAINVREF_END_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h10".U(8.W)
  )

  def MBTRAIN_DATATRAINVREF_END_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h10".U(8.W)
  )
  def MBTRAIN_RXDESKEW_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h11".U(8.W)
  )
  def MBTRAIN_RXDESKEW_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h11".U(8.W)
  )
  def MBTRAIN_RXDESKEW_END_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h12".U(8.W)
  )

  def MBTRAIN_RXDESKEW_END_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h12".U(8.W)
  )
  def MBTRAIN_DATATRAINCENTER2_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h13".U(8.W)
  )
  def MBTRAIN_DATATRAINCENTER2_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h13".U(8.W)
  )
  def MBTRAIN_DATATRAINCENTER2_END_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h14".U(8.W)
  )

  def MBTRAIN_DATATRAINCENTER2_END_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h14".U(8.W)
  )
  def MBTRAIN_LINKSPEED_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h15".U(8.W)
  )
  def MBTRAIN_LINKSPEED_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h15".U(8.W)
  )
  def MBTRAIN_LINKSPEED_ERROR_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h16".U(8.W)
  )

  def MBTRAIN_LINKSPEED_ERROR_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h16".U(8.W)
  )
  def MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h17".U(8.W)
  )
  def MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h17".U(8.W)
  )
  def MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h18".U(8.W)
  )

  def MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h18".U(8.W)
  )
  def MBTRAIN_LINKSPEED_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h19".U(8.W)
  )
  def MBTRAIN_LINKSPEED_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h19".U(8.W)
  )
  def MBTRAIN_LINKSPEED_MULTIMODULE_DISABLE_MODULE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h1A".U(8.W)
  )

  // Shares 1Fh with the unused RXDESKEW EQ Preset, so decode it by state.
  def MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h1F".U(8.W)
  )
  def MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h1F".U(8.W)
  )
  def MBTRAIN_REPAIR_INIT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h1B".U(8.W)
  )
  def MBTRAIN_REPAIR_INIT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h1B".U(8.W)
  )

  def MBTRAIN_REPAIR_APPLY_REPAIR_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h1C".U(8.W)
  )
  def MBTRAIN_REPAIR_END_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h1D".U(8.W)
  )
  def MBTRAIN_REPAIR_END_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h1D".U(8.W)
  )
  def MBTRAIN_REPAIR_APPLY_DEGRADE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h1E".U(8.W)
  )

  def MBTRAIN_REPAIR_APPLY_DEGRADE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h1E".U(8.W)
  )

  def MBTRAIN_RXDESKEW_EQ_PRESENT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h1F".U(8.W)
  )
  def MBTRAIN_RXDESKEW_EQ_PRESENT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h1F".U(8.W)
  )
  def MBTRAIN_RXDESKEW_EXIT_TO_DATATRAINCENTER1_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h20".U(8.W)
  )

  def MBTRAIN_RXDESKEW_EXIT_TO_DATATRAINCENTER1_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h20".U(8.W)
  )
  def MBTRAIN_RXDESKEW_TCKN_L_SHIFT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h21".U(8.W)
  )
  def MBTRAIN_RXDESKEW_TCKN_L_SHIFT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h21".U(8.W)
  )
  def PHYRETRAIN_RETRAIN_START_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hC5".U(8.W),
    "h01".U(8.W)
  )

  def PHYRETRAIN_RETRAIN_START_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hCA".U(8.W),
    "h01".U(8.W)
  )
  def TRAINERROR_ENTRY_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hE5".U(8.W),
    "h00".U(8.W)
  )
  def TRAINERROR_ENTRY_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hEA".U(8.W),
    "h00".U(8.W)
  )
  def RECAL_TRACK_PATTERN_INIT_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hD5".U(8.W),
    "h00".U(8.W)
  )

  def RECAL_TRACK_PATTERN_INIT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hDA".U(8.W),
    "h00".U(8.W)
  )
  def RECAL_TRACK_PATTERN_DONE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hD5".U(8.W),
    "h01".U(8.W)
  )
  def RECAL_TRACK_PATTERN_DONE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hDA".U(8.W),
    "h01".U(8.W)
  )
  def RECAL_TRACK_TX_ADJUST_REQ = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hB5".U(8.W),
    "h22".U(8.W)
  )

  def RECAL_TRACK_TX_ADJUST_RESP = MixedVecInit(
    SBMsgOpcode.MessageWithoutData.asUInt,
    "hBA".U(8.W),
    "h22".U(8.W)
  )

  // ===========================================================================
  // Messages with data
  // ===========================================================================
  def ADVCAP_ADAPTER = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h01".U(8.W),
    "h00".U(8.W)
  )
  def FINCAP_ADAPTER = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h02".U(8.W),
    "h00".U(8.W)
  )
  def ADVCAP_CXL = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h01".U(8.W),
    "h01".U(8.W)
  )
  def FINCAP_CXL = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h02".U(8.W),
    "h01".U(8.W)
  )
  def MULTIPROT_ADVCAP_ADAPTER = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h01".U(8.W),
    "h02".U(8.W)
  )
  def MULTIPROT_FINCAP_ADAPTER = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h02".U(8.W),
    "h02".U(8.W)
  )

  // ===========================================================================
  // Link training messages with data
  // ===========================================================================
  def START_TX_INIT_D2C_POINT_TEST_REQ = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h85".U(8.W),
    "h01".U(8.W)
  )
  def TX_INIT_D2C_RESULTS_RESP = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h8A".U(8.W),
    "h03".U(8.W)
  )

  def START_TX_INIT_D2C_EYE_SWEEP_REQ = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h85".U(8.W),
    "h05".U(8.W)
  )
  def START_RX_INIT_D2C_POINT_TEST_REQ = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h85".U(8.W),
    "h07".U(8.W)
  )
  def START_RX_INIT_D2C_EYE_SWEEP_REQ = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h85".U(8.W),
    "h0A".U(8.W)
  )

  def RX_INIT_D2C_RESULTS_RESP = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h8A".U(8.W),
    "h0B".U(8.W)
  )
  def RX_INIT_D2C_SWEEP_DONE_WITH_RESULTS = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "h81".U(8.W),
    "h0C".U(8.W)
  )

  def MBINIT_PARAM_CONFIGURATION_REQ = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "hA5".U(8.W),
    "h00".U(8.W)
  )
  def MBINIT_PARAM_CONFIGURATION_RESP = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "hAA".U(8.W),
    "h00".U(8.W)
  )
  def MBINIT_PARAM_SBFE_REQ = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "hA5".U(8.W),
    "h01".U(8.W)
  )
  def MBINIT_PARAM_SBFE_RESP = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "hAA".U(8.W),
    "h01".U(8.W)
  )

  def MBINIT_REVERSALMB_RESULT_RESP = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "hAA".U(8.W),
    "h0F".U(8.W)
  )
  def MBINIT_REVERSALMB_APPLY_REPAIR_REQ = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "hA5".U(8.W),
    "h12".U(8.W)
  )

  def MBINIT_REPAIR_APPLY_REPAIR_REQ = MixedVecInit(
    SBMsgOpcode.MessageWith64bData.asUInt,
    "hB5".U(8.W),
    "h1C".U(8.W)
  )

  // ===========================================================================
  // Vendor Defined messages
  // ===========================================================================
  def VENDOR_DEFINED_MESSAGE =
    MixedVecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hFF".U(8.W))
  def VENDOR_DEFINED_MESSAGE_WITH_DATA =
    MixedVecInit(SBMsgOpcode.MessageWith64bData.asUInt, "hFF".U(8.W))

  // ===========================================================================
  // Helpers
  // ===========================================================================
  def isRegAccessComplete(opcode: UInt) =
    (opcode === SBMsgOpcode.CompletionWithoutData.asUInt |
      opcode === SBMsgOpcode.CompletionWith32bData.asUInt |
      opcode === SBMsgOpcode.CompletionWith64bData.asUInt)

  def isReqRespMessage(opcode: UInt) =
    (opcode === SBMsgOpcode.MessageWithoutData.asUInt |
      opcode === SBMsgOpcode.MessageWith64bData.asUInt)

  def isRegAccessRequest(opcode: UInt) = Seq(
    SBMsgOpcode.MemoryRead_32b,
    SBMsgOpcode.MemoryWrite_32b,
    SBMsgOpcode.DMSRegisterRead_32b,
    SBMsgOpcode.DMSRegisterWrite_32b,
    SBMsgOpcode.ConfigurationRead_32b,
    SBMsgOpcode.ConfigurationWrite_32b,
    SBMsgOpcode.MemoryRead_64b,
    SBMsgOpcode.MemoryWrite_64b,
    SBMsgOpcode.DMSRegisterRead_64b,
    SBMsgOpcode.DMSRegisterWrite_64b,
    SBMsgOpcode.ConfigurationRead_64b,
    SBMsgOpcode.ConfigurationWrite_64b
  ).map(_.asUInt === opcode).reduce(_ || _)

  def isRemoteRegAccessRequest(opcode: UInt) = Seq(
    SBMsgOpcode.MemoryRead_32b,
    SBMsgOpcode.MemoryWrite_32b,
    SBMsgOpcode.ConfigurationRead_32b,
    SBMsgOpcode.ConfigurationWrite_32b,
    SBMsgOpcode.MemoryRead_64b,
    SBMsgOpcode.MemoryWrite_64b,
    SBMsgOpcode.ConfigurationRead_64b,
    SBMsgOpcode.ConfigurationWrite_64b
  ).map(_.asUInt === opcode).reduce(_ || _)
}

// srcid and dstid encodings (Table 7-2 to Table 7-4).
object SBSourceDestination {
  val codes: Map[String, String] = Map(
    "Stack0ProtocolLayer" -> "b000",
    "Stack1ProtocolLayer" -> "b100",
    "D2D" -> "b001",
    "PHY" -> "b010"
  )

  def apply(name: String, field: String): UInt =
    codes
      .get(name)
      .map(_.U(3.W))
      .getOrElse(
        throw new IllegalArgumentException(
          s"$field '$name' is not a sec 7.1 layer; expected one of " +
            codes.keys.toSeq.sorted.mkString(", ")
        )
      )
}

object LayerId {
  val protocol = 0
  val d2d = 1
  val logPhy = 2
}

object SBMsgCreate {
  def apply(
      base: MixedVec[UInt],
      src: String,
      dst: String,
      remote: Boolean,
      msgInfo: UInt = 0.U(16.W),
      data: UInt = 0.U(64.W)
  ): UInt = {
    val srcid: UInt = SBSourceDestination(src, "srcid")
    val localDstId: UInt = SBSourceDestination(dst, "dstid")
    val dstid = localDstId | Cat(remote.B, 0.U(2.W))

    apply(base, srcid, dstid, msgInfo, data, false.B, 0.U, false.B)
  }

  def apply(
      base: MixedVec[UInt],
      srcid: UInt,
      dstid: UInt,
      msgInfo: UInt,
      data: UInt,
      cr: Bool,
      tag: UInt,
      ep: Bool
  ): UInt = {
    msgInfo.widthOption.foreach { w =>
      require(w <= 16, s"msgInfo is $w bits, wider than the 16 it is sent in")
    }
    val cp = 0.U(1.W)
    val dp = 0.U(1.W)
    val msgSubcode = base(2).pad(8)(7, 0)
    val msgCode = base(1).pad(8)(7, 0)
    val opcode = base(0).pad(5)(4, 0)

    val msg = Cat(
      dp,
      cp,
      cr,
      0.U(2.W),
      dstid.pad(3)(2, 0),
      msgInfo.pad(16)(15, 0),
      msgSubcode,
      srcid.pad(3)(2, 0),
      0.U(2.W),
      tag.pad(5)(4, 0),
      msgCode,
      0.U(8.W),
      ep,
      opcode
    )

    Cat(data.pad(64)(63, 0), msg)
  }
}

object SBMsgField {
  // Note: read SBMsgCreate packet fields only through these.
  def msgInfo(msg: UInt): UInt = msg(55, 40)
  def data(msg: UInt): UInt = msg(127, 64)
  def opcode(msg: UInt): UInt = msg(4, 0)
  def msgCode(msg: UInt): UInt = msg(21, 14)

  // msgCode FFh (Table 7-8, Table 7-10). sec 7.1.3.3 exempts it from sinking.
  def isVendorDefined(msg: UInt): Bool = msgCode(msg) === "hFF".U(8.W)
}

object SBMsgCompare {
  def apply(incMsg: UInt, base: MixedVec[UInt]): Bool = {
    val opcode = base(0)
    val msgCode = base(1)
    val msgSubcode = base(2)

    (incMsg(4, 0) === opcode) && (incMsg(21, 14) === msgCode) && (incMsg(
      39,
      32
    ) === msgSubcode)
  }
}
