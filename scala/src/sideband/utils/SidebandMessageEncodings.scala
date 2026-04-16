package edu.berkeley.cs.uciedigital.sideband
import chisel3._
import chisel3.util._

/*
  Description:
    Contains sideband message types, and functions to create sideband messages and comparisons
*/

// ==============================================================================================
// Opcode Identifer (unspecified values are reserved)
// ==============================================================================================
/* Opcode Identifer (unspecified values are reserved) */
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

    /*
    --  See Section 4.1.5.2 (Spec Version: 3.0) for details on priority packets
    Snippet from spec:
        The opcode field in the priority packet can be 11111b or 11110b.
        * If the opcode is 11111b, the normal traffic packet resumes immediately 
          after the priority traffic packet without any idle time.          
        * If the opcode is 11110b, then another priority traffic packet is 
          transmitted immediately without any idle insertion.
    */
    val BackToBackPriorityPacket = Value("b11110".U(5.W))
    val SinglePriorityPacket = Value("b11111".U(5.W))

    val OpsWithoutData = Seq(
      CompletionWithoutData,
      MessageWithoutData,
      ManagementPortMsgWithoutData
    )

    val OpsThatDontUseDPField = Seq(
      ManagementPortMsgWithData,
      BackToBackPriorityPacket,
      SinglePriorityPacket,
    ) ++ OpsWithoutData
}


object SBM {
  
  // Vector ordering: {Opcode (5.W), Msgcode (8.W), Megsubcode (8.W)}

  // ==============================================================================================
  // Message Encodings for Messages without Data
  // ==============================================================================================
  def NOP_CRD = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h00".U(8.W), "h00".U(8.W))
  
  def LINKMGMT_RDI_REQ_ACTIVE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h01".U(8.W), "h01".U(8.W))
  def LINKMGMT_RDI_REQ_L1 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h01".U(8.W), "h04".U(8.W))
  def LINKMGMT_RDI_REQ_L2 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h01".U(8.W), "h08".U(8.W))
  def LINKMGMT_RDI_REQ_LINKRESET = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h01".U(8.W), "h09".U(8.W))
  def LINKMGMT_RDI_REQ_LINKERROR = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h01".U(8.W), "h0A".U(8.W))
  def LINKMGMT_RDI_REQ_RETRAIN = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h01".U(8.W), "h0B".U(8.W))
  def LINKMGMT_RDI_REQ_DISABLE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h01".U(8.W), "h0C".U(8.W))

  def LINKMGMT_RDI_RSP_ACTIVE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h02".U(8.W), "h01".U(8.W))
  def LINKMGMT_RDI_RSP_PMNAK = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h02".U(8.W), "h02".U(8.W))
  def LINKMGMT_RDI_RSP_L1 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h02".U(8.W), "h04".U(8.W))
  def LINKMGMT_RDI_RSP_L2 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h02".U(8.W), "h08".U(8.W))
  def LINKMGMT_RDI_RSP_LINKRESET = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h02".U(8.W), "h09".U(8.W))
  def LINKMGMT_RDI_RSP_LINKERROR = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h02".U(8.W), "h0A".U(8.W))
  def LINKMGMT_RDI_RSP_RETRAIN = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h02".U(8.W), "h0B".U(8.W))
  def LINKMGMT_RDI_RSP_DISABLE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h02".U(8.W), "h0C".U(8.W))

  def LINKMGMT_ADAPTER0_REQ_ACTIVE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h03".U(8.W), "h01".U(8.W))
  def LINKMGMT_ADAPTER0_REQ_L1 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h03".U(8.W), "h04".U(8.W))
  def LINKMGMT_ADAPTER0_REQ_L2 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h03".U(8.W), "h08".U(8.W))
  def LINKMGMT_ADAPTER0_REQ_LINKRESET = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h03".U(8.W), "h09".U(8.W))
  def LINKMGMT_ADAPTER0_REQ_DISABLE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h03".U(8.W), "h0C".U(8.W))

  def LINKMGMT_ADAPTER0_RSP_ACTIVE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h04".U(8.W), "h01".U(8.W))
  def LINKMGMT_ADAPTER0_RSP_PMNAK = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h04".U(8.W), "h02".U(8.W))
  def LINKMGMT_ADAPTER0_RSP_L1 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h04".U(8.W), "h04".U(8.W))
  def LINKMGMT_ADAPTER0_RSP_L2 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h04".U(8.W), "h08".U(8.W))
  def LINKMGMT_ADAPTER0_RSP_LINKRESET = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h04".U(8.W), "h09".U(8.W))
  def LINKMGMT_ADAPTER0_RSP_DISABLE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h04".U(8.W), "h0C".U(8.W))

  def LINKMGMT_ADAPTER1_REQ_ACTIVE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h05".U(8.W), "h01".U(8.W))
  def LINKMGMT_ADAPTER1_REQ_L1 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h05".U(8.W), "h04".U(8.W))
  def LINKMGMT_ADAPTER1_REQ_L2 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h05".U(8.W), "h08".U(8.W))
  def LINKMGMT_ADAPTER1_REQ_LINKRESET = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h05".U(8.W), "h09".U(8.W))
  def LINKMGMT_ADAPTER1_REQ_DISABLE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h05".U(8.W), "h0C".U(8.W))

  def LINKMGMT_ADAPTER1_RSP_ACTIVE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h06".U(8.W), "h01".U(8.W))
  def LINKMGMT_ADAPTER1_RSP_PMNAK = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h06".U(8.W), "h02".U(8.W))
  def LINKMGMT_ADAPTER1_RSP_L1 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h06".U(8.W), "h04".U(8.W))
  def LINKMGMT_ADAPTER1_RSP_L2 = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h06".U(8.W), "h08".U(8.W))
  def LINKMGMT_ADAPTER1_RSP_LINKRESET = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h06".U(8.W), "h09".U(8.W))
  def LINKMGMT_ADAPTER1_RSP_DISABLE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h06".U(8.W), "h0C".U(8.W))

  def PARTIYFEATURE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h07".U(8.W), "h00".U(8.W))
  def PARTIYFEATURE_ACK = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h08".U(8.W), "h00".U(8.W))
  def PARTIYFEATURE_NAK = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h08".U(8.W), "h01".U(8.W))

  def ERRMSG_CORRECTABLE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h09".U(8.W), "h00".U(8.W))
  def ERRMSG_NONFATAL = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h09".U(8.W), "h01".U(8.W))
  def ERRMSG_FATAL = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h09".U(8.W), "h02".U(8.W))

  // ==============================================================================================
  // Link Training State Machine related Message encodings for messages without data
  // ==============================================================================================
  def START_TX_INIT_D2C_POINT_TEST_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h01".U(8.W))
  def LFSR_CLEAR_ERROR_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h85".U(8.W), "h02".U(8.W))
  def LFSR_CLEAR_ERROR_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h02".U(8.W))
  def TX_INIT_D2C_RESULTS_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h85".U(8.W), "h03".U(8.W))

  def END_TX_INIT_D2C_POINT_TEST_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h85".U(8.W), "h04".U(8.W))
  def END_TX_INIT_D2C_POINT_TEST_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h04".U(8.W))
  def START_TX_INIT_D2C_EYE_SWEEP_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h05".U(8.W))
  def END_TX_INIT_D2C_EYE_SWEEP_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h85".U(8.W), "h06".U(8.W))

  def END_TX_INIT_D2C_EYE_SWEEP_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h06".U(8.W))
  def START_RX_INIT_D2C_POINT_TEST_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h07".U(8.W))
  def RX_INIT_D2C_TX_COUNT_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h85".U(8.W), "h08".U(8.W))
  def RX_INIT_D2C_TX_COUNT_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h08".U(8.W))

  def END_RX_INIT_D2C_POINT_TEST_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h85".U(8.W), "h09".U(8.W))
  def END_RX_INIT_D2C_POINT_TEST_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h09".U(8.W))
  def START_RX_INIT_D2C_EYE_SWEEP_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h0A".U(8.W))
  def RX_INIT_D2C_RESULTS_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h85".U(8.W), "h0B".U(8.W))

  def END_RX_INIT_D2C_EYE_SWEEP_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h85".U(8.W), "h0D".U(8.W))
  def END_RX_INIT_D2C_EYE_SWEEP_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h8A".U(8.W), "h0D".U(8.W))
  def SBINIT_OUT_OF_RESET = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h91".U(8.W), "h00".U(8.W))
  def SBINIT_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h95".U(8.W), "h01".U(8.W))

  def SBINIT_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "h9A".U(8.W), "h01".U(8.W))
  def MBINIT_CAL_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h02".U(8.W))
  def MBINIT_CAL_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h02".U(8.W))
  def MBINIT_REPAIRCLK_INIT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h03".U(8.W))

  def MBINIT_REPAIRCLK_INIT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h03".U(8.W))
  def MBINIT_REPAIRCLK_RESULT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h04".U(8.W))
  def MBINIT_REPAIRCLK_RESULT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h04".U(8.W))
  def MBINIT_REPAIRCLK_APPLY_REPAIR_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h05".U(8.W))

  def MBINIT_REPAIRCLK_APPLY_REPAIR_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h05".U(8.W))
  def MBINIT_REPAIRCLK_CHECK_REPAIR_INIT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h06".U(8.W))  
  def MBINIT_REPAIRCLK_CHECK_REPAIR_INIT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h06".U(8.W))
  def MBINIT_REPAIRCLK_CHECK_RESULT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h07".U(8.W))

  def MBINIT_REPAIRCLK_CHECK_RESULT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h07".U(8.W))  
  def MBINIT_REPAIRCLK_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h08".U(8.W))
  def MBINIT_REPAIRCLK_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h08".U(8.W))
  def MBINIT_REPAIRVAL_INIT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h09".U(8.W))  

  def MBINIT_REPAIRVAL_INIT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h09".U(8.W))
  def MBINIT_REPAIRVAL_RESULT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h0A".U(8.W))
  def MBINIT_REPAIRVAL_RESULT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h0A".U(8.W))  
  def MBINIT_REPAIRVAL_APPLY_REPAIR_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h0B".U(8.W))

  def MBINIT_REPAIRVAL_APPLY_REPAIR_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h0B".U(8.W))
  def MBINIT_REPAIRVAL_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h0C".U(8.W))  
  def MBINIT_REPAIRVAL_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h0C".U(8.W))
  def MBINIT_REVERSALMB_INIT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h0D".U(8.W))

  def MBINIT_REVERSALMB_INIT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h0D".U(8.W))
  def MBINIT_REVERSALMB_CLEAR_ERROR_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h0E".U(8.W))
  def MBINIT_REVERSALMB_CLEAR_ERROR_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h0E".U(8.W))
  def MBINIT_REVERSALMB_RESULT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h0F".U(8.W))

  def MBINIT_REVERSALMB_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h10".U(8.W))
  def MBINIT_REVERSALMB_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h10".U(8.W))
  def MBINIT_REPAIRMB_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h11".U(8.W))
  def MBINIT_REPAIRMB_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h11".U(8.W))

  def MBINIT_REPAIRMB_APPLY_REPAIR_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h12".U(8.W))
  def MBINIT_REPAIRMB_END_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h13".U(8.W))
  def MBINIT_REPAIRMB_END_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h13".U(8.W))
  def MBINIT_REPAIRMB_APPLY_DEGRADE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hA5".U(8.W), "h14".U(8.W))

  def MBINIT_REPAIRMB_APPLY_DEGRADE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hAA".U(8.W), "h14".U(8.W))
  def MBTRAIN_VALVREF_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h00".U(8.W))
  def MBTRAIN_VALVREF_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h00".U(8.W))
  def MBTRAIN_VALVREF_END_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h01".U(8.W))

  def MBTRAIN_VALVREF_END_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h01".U(8.W))
  def MBTRAIN_DATAVREF_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h02".U(8.W))
  def MBTRAIN_DATAVREF_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h02".U(8.W))
  def MBTRAIN_DATAVREF_END_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h03".U(8.W))

  def MBTRAIN_DATAVREF_END_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h03".U(8.W))
  def MBTRAIN_SPEEDIDLE_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h04".U(8.W))
  def MBTRAIN_SPEEDIDLE_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h04".U(8.W))
  def MBTRAIN_TXSELFCAL_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h05".U(8.W))

  def MBTRAIN_TXSELFCAL_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h05".U(8.W))
  def MBTRAIN_RXCLKCAL_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h06".U(8.W))
  def MBTRAIN_RXCLKCAL_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h06".U(8.W))
  def MBTRAIN_RXCLKCAL_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h07".U(8.W))

  def MBTRAIN_RXCLKCAL_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h07".U(8.W))
  def MBTRAIN_VALTRAINCENTER_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h08".U(8.W))
  def MBTRAIN_VALTRAINCENTER_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h08".U(8.W))
  def MBTRAIN_VALTRAINCENTER_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h09".U(8.W))

  def MBTRAIN_VALTRAINCENTER_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h09".U(8.W))
  def MBTRAIN_VALTRAINVREF_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h0A".U(8.W))
  def MBTRAIN_VALTRAINVREF_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h0A".U(8.W))
  def MBTRAIN_VALTRAINVREF_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h0B".U(8.W))   // TYPO: LogPHY section description has END, but chapter 7 table has DONE. Keeping as table

  def MBTRAIN_VALTRAINVREF_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h0B".U(8.W))  // TYPO: LogPHY section description has END, but chapter 7 table has DONE. Keeping as table
  def MBTRAIN_DATATRAINCENTER1_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h0C".U(8.W))
  def MBTRAIN_DATATRAINCENTER1_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h0C".U(8.W))
  def MBTRAIN_DATATRAINCENTER1_END_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h0D".U(8.W))

  def MBTRAIN_DATATRAINCENTER1_END_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h0D".U(8.W))
  def MBTRAIN_DATATRAINVREF_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h0E".U(8.W))
  def MBTRAIN_DATATRAINVREF_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h0E".U(8.W))
  def MBTRAIN_DATATRAINVREF_END_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h10".U(8.W))

  def MBTRAIN_DATATRAINVREF_END_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h10".U(8.W))
  def MBTRAIN_RXDESKEW_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h11".U(8.W))
  def MBTRAIN_RXDESKEW_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h11".U(8.W))
  def MBTRAIN_RXDESKEW_END_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h12".U(8.W))

  def MBTRAIN_RXDESKEW_END_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h12".U(8.W))
  def MBTRAIN_DATATRAINCENTER2_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h13".U(8.W))
  def MBTRAIN_DATATRAINCENTER2_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h13".U(8.W))
  def MBTRAIN_DATATRAINCENTER2_END_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h14".U(8.W))

  def MBTRAIN_DATATRAINCENTER2_END_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h14".U(8.W))
  def MBTRAIN_LINKSPEED_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h15".U(8.W))
  def MBTRAIN_LINKSPEED_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h15".U(8.W))
  def MBTRAIN_LINKSPEED_ERROR_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h16".U(8.W))

  def MBTRAIN_LINKSPEED_ERROR_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h16".U(8.W))
  def MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h17".U(8.W))
  def MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h17".U(8.W))
  def MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h18".U(8.W))

  def MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h18".U(8.W))
  def MBTRAIN_LINKSPEED_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h19".U(8.W))
  def MBTRAIN_LINKSPEED_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h19".U(8.W))
  def MBTRAIN_LINKSPEED_MULTIMODULE_DISABLE_MODULE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h1A".U(8.W))

  def MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h1F".U(8.W))
  def MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h1F".U(8.W))
  def MBTRAIN_REPAIR_INIT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h1B".U(8.W))
  def MBTRAIN_REPAIR_INIT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h1B".U(8.W))

  def MBTRAIN_REPAIR_APPLY_REPAIR_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h1C".U(8.W))
  def MBTRAIN_REPAIR_END_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h1D".U(8.W))
  def MBTRAIN_REPAIR_END_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h1D".U(8.W))
  def MBTRAIN_REPAIR_APPLY_DEGRADE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h1E".U(8.W))

  def MBTRAIN_REPAIR_APPLY_DEGRADE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h1E".U(8.W))
  def MBTRAIN_RXDESKEW_EQ_PRESENT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h1F".U(8.W))
  def MBTRAIN_RXDESKEW_EQ_PRESENT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h1F".U(8.W))
  def MBTRAIN_RXDESKEW_EXIT_TO_DATATRAINCENTER1_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h20".U(8.W))

  def MBTRAIN_RXDESKEW_EXIT_TO_DATATRAINCENTER1_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h20".U(8.W))
  def MBTRAIN_RXDESKEW_TCKN_L_SHIFT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h21".U(8.W))
  def MBTRAIN_RXDESKEW_TCKN_L_SHIFT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h21".U(8.W))
  def PHYRETRAIN_RETRAIN_START_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hC5".U(8.W), "h01".U(8.W))

  def PHYRETRAIN_RETRAIN_START_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hCA".U(8.W), "h01".U(8.W))
  def TRAINERROR_ENTRY_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hE5".U(8.W), "h00".U(8.W))
  def TRAINERROR_ENTRY_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hEA".U(8.W), "h00".U(8.W))
  def RECAL_TRACK_PATTERN_INIT_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hD5".U(8.W), "h00".U(8.W))

  def RECAL_TRACK_PATTERN_INIT_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hDA".U(8.W), "h00".U(8.W))
  def RECAL_TRACK_PATTERN_DONE_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hD5".U(8.W), "h01".U(8.W))
  def RECAL_TRACK_PATTERN_DONE_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hDA".U(8.W), "h01".U(8.W))
  def RECAL_TRACK_TX_ADJUST_REQ = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hB5".U(8.W), "h22".U(8.W))
  
  def RECAL_TRACK_TX_ADJUST_RESP = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hBA".U(8.W), "h22".U(8.W))

  // ==============================================================================================
  // Message encodings for Messages with Data
  // ==============================================================================================
  def ADVCAP_ADAPTER = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h01".U(8.W), "h00".U(8.W))
  def FINCAP_ADAPTER = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h02".U(8.W), "h00".U(8.W))
  def ADVCAP_CXL = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h01".U(8.W), "h01".U(8.W))
  def FINCAP_CXL = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h02".U(8.W), "h01".U(8.W))
  def MULTIPROT_ADVCAP_ADAPTER = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h01".U(8.W), "h02".U(8.W))
  def MULTIPROT_FINCAP_ADAPTER = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h02".U(8.W), "h02".U(8.W))

  // ==============================================================================================
  // Link Training State Machine related encodings with Data
  // ==============================================================================================
  def START_TX_INIT_D2C_POINT_TEST_REQ = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h85".U(8.W), "h01".U(8.W))
  def TX_INIT_D2C_RESULTS_RESP = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h8A".U(8.W), "h03".U(8.W))
  
  def START_TX_INIT_D2C_EYE_SWEEP_REQ = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h85".U(8.W), "h05".U(8.W))
  def START_RX_INIT_D2C_POINT_TEST_REQ = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h85".U(8.W), "h07".U(8.W))
  def START_RX_INIT_D2C_EYE_SWEEP_REQ = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h85".U(8.W), "h0A".U(8.W))

  def RX_INIT_D2C_RESULTS_RESP = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h8A".U(8.W), "h0B".U(8.W))
  def RX_INIT_D2C_SWEEP_DONE_WITH_RESULTS = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "h81".U(8.W), "h0C".U(8.W))

  def MBINIT_PARAM_CONFIGURATION_REQ = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "hA5".U(8.W), "h00".U(8.W))
  def MBINIT_PARAM_CONFIGURATION_RESP = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "hAA".U(8.W), "h00".U(8.W))
  def MBINIT_PARAM_SBFE_REQ = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "hA5".U(8.W), "h01".U(8.W))
  def MBINIT_PARAM_SBFE_RESP = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "hAA".U(8.W), "h01".U(8.W))

  def MBINIT_REVERSALMB_RESULT_RESP = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "hAA".U(8.W), "h0F".U(8.W))
  def MBINIT_REVERSALMB_APPLY_REPAIR_REQ = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "hA5".U(8.W), "h12".U(8.W))

  def MBINIT_REPAIR_APPLY_REPAIR_REQ = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "hB5".U(8.W), "h1C".U(8.W))

  // ==============================================================================================
  // Vendor Defined Messsage (Uses Msgcode "hFF")
  // ==============================================================================================
  // Unsupported vendor defined messages must be discarded by the receiver
  def VENDOR_DEFINED_MESSAGE = VecInit(SBMsgOpcode.MessageWithoutData.asUInt, "hFF".U(8.W))  
  def VENDOR_DEFINED_MESSAGE_WITH_DATA = VecInit(SBMsgOpcode.MessageWith64bData.asUInt, "hFF".U(8.W))  



  // ==============================================================================================
  // Helpers
  // ==============================================================================================
  def isRegAccessComplete(opcode: UInt) = (opcode === SBMsgOpcode.CompletionWithoutData.asUInt | 
                                           opcode === SBMsgOpcode.CompletionWith32bData.asUInt | 
                                           opcode === SBMsgOpcode.CompletionWith64bData.asUInt)

  def isReqRespMessage(opcode: UInt) = (opcode === SBMsgOpcode.MessageWithoutData.asUInt | 
                                        opcode === SBMsgOpcode.MessageWith64bData.asUInt)

  def isRegAccessRequest(opcode: UInt) = ~opcode(4)  // 
}

object SBSourceDestination {
  val Stack0ProtocolLayer = "b000".U(3.W)
  val Stack1ProtocolLayer = "b100".U(3.W)
  val D2D = "b001".U(3.W)
  val PHY = "b010".U(3.W)
}

object LayerId {
  val protocol = 0
  val d2d = 1
  val logPhy = 2
}

// ==============================================================================================
// A factory function to create a messages
// ==============================================================================================
object SBMsgCreate {
 def apply(
    base: Vec[UInt],
    src: String,
    dst: String, 
    remote: Boolean,
    msgInfo: UInt = 0.U(16.W),
    data: UInt = 0.U(64.W)
  ): UInt = {
    val srcid: UInt = src match {
      case "Stack0ProtocolLayer" => "b000".U(3.W)
      case "Stack1ProtocolLayer" => "b100".U(3.W)
      case "D2D" => "b001".U(3.W)
      case "PHY" => "b010".U(3.W)
    }
    var dstid: UInt = src match {
      case "D2D" => "b001".U(3.W) 
      case "PHY" => "b010".U(3.W)
    }
    dstid = (dstid | Cat(remote.B.asUInt, 0.U(2.W)))
    val cp = 0.U(1.W)
    val dp = 0.U(1.W)
    val msgSubcode = base(2)
    val msgCode = base(1)
    val opcode = base(0)

    val msg = Cat(dp, cp, 0.U(3.W), dstid, msgInfo, msgSubcode, 
                  srcid, 0.U(2.W), 0.U(5.W), msgCode, 0.U(9.W), opcode)

    Cat(data, msg)
  }
}

// ==============================================================================================
// A function to do comparison 
// ==============================================================================================
object SBMsgCompare {
  def apply(incMsg: UInt, base: Vec[UInt]): Bool = {
    val opcode = base(0)
    val msgCode = base(1)    
    val msgSubcode = base(2)
  
    (incMsg(4, 0) === opcode) && (incMsg(21, 14) === msgCode) && (incMsg(39, 32) === msgSubcode)
  }
}