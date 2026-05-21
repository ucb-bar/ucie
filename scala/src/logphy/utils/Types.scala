package edu.berkeley.cs.uciedigital.logphy

import chisel3._

class LinkOperationParameters extends Bundle {
  /*
    Clock Phase control at Transmitter
    h0: Clock PI Center
    h1: Left Edge
    h2: Right Edge
  */
  val clockPhase = Input(UInt(4.W))

  /*    
    Data Pattern (for Data Lanes)
    h0: LFSR
    h1: Per Lane ID    
  */
  val dataPattern = Input(UInt(3.W))

  /*
    Valid Pattern (for Valid Lanes)
    h0: Functional pattern (aka VALTRAIN)
  */
  val validPattern = Input(UInt(3.W))

  /*
    Pattern Mode
    0: Continuous Mode
      Continuous Mode: Uses Burst count to indicate the number
      of UI of transmission. Idle Count = 0, Iteration Count = 1

    1: Burst Mode
      Burst Mode: Uses Burst Count/Idle Count/Iteration Count
  */
  val patternMode = Input(UInt(1.W))

  /*
    See spec ver 3.0 page 127 in implementation notes for details
    Note: This isn't currently used with current implementation of PatternWriter 
    and PatternReader as per spec link operations send a fixed pattern.
  */
  val iterationCount = Input(UInt(16.W))
  val idleCount = Input(UInt(16.W))
  val burstCount = Input(UInt(16.W))

  /*
    Maximum comparison error threshold  
  */
  val maxErrorThreshold = Input(UInt(16.W))

  /*
    Comparison Mode 
    0: Per Lane
    1: Aggregate
  */
  val comparisonMode = Input(UInt(1.W))    
}

object ComparisonMode extends ChiselEnum {
  val PERLANE, AGGREGATE = Value
}

object MBRxTxMode extends ChiselEnum {
  // Either send/receive RAW or process with valid framing
  val RAW, VALID_FRAME = Value
}

object MsgSource extends ChiselEnum {
  val PATTERN_GENERATOR, SB_MSG = Value
}

object LTState extends ChiselEnum {
  val sRESET, sSBINIT, sMBINIT, sMBTRAIN, sLINKINIT, sACTIVE, sPHYRETRAIN, sTRAINERROR, sL1_L2
  = Value
}

object LTSMState extends ChiselEnum {
  val sRESET                    = Value("h00".U)
  val sSBINIT                   = Value("h01".U)
  val sMBINIT_PARAM             = Value("h02".U)
  val sMBINIT_CAL               = Value("h03".U)
  val sMBINIT_REPAIRCLK         = Value("h04".U)
  val sMBINIT_REPAIRVAL         = Value("h05".U)
  val sMBINIT_REVERSALMB        = Value("h06".U)
  val sMBINIT_REPAIRMB          = Value("h07".U)
  val sMBTRAIN_VALVREF          = Value("h08".U)
  val sMBTRAIN_DATAVREF         = Value("h09".U)
  val sMBTRAIN_SPEEDIDLE        = Value("h0A".U)
  val sMBTRAIN_TXSELFCAL        = Value("h0B".U)
  val sMBTRAIN_RXCLKCAL         = Value("h0C".U)
  val sMBTRAIN_VALTRAINCENTER   = Value("h0D".U)
  val sMBTRAIN_VALTRAINVREF     = Value("h0E".U)
  val sMBTRAIN_DATATRAINCENTER1 = Value("h0F".U)
  val sMBTRAIN_DATATRAINVREF    = Value("h10".U)
  val sMBTRAIN_RXDESKEW         = Value("h11".U)
  val sMBTRAIN_DATATRAINCENTER2 = Value("h12".U)
  val sMBTRAIN_LINKSPEED        = Value("h13".U)
  val sMBTRAIN_REPAIR           = Value("h14".U)
  val sPHYRETRAIN               = Value("h15".U)
  val sLINKINIT                 = Value("h16".U)
  val sACTIVE                   = Value("h17".U)
  val sTRAINERROR               = Value("h18".U)
  val sL1_L2                    = Value("h19".U)
}

object RetrainEncoding {
  val TXSELFCAL = "b001".U(3.W)
  val SPEEDIDLE = "b010".U(3.W)
  val REPAIR    = "b100".U(3.W)
}

case class AfeParams(
  // sbSerializerRatio: Int = 1,
  // sbWidth: Int = 1,
  mbSerializerRatio: Int = 32,
  mbLanes: Int = 16,

  clockPhaseSelBitWidth: Int = 5,
)
