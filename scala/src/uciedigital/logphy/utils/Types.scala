package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
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
  val sRESET, sSBINIT, sMBINIT, sMBTRAIN, sLINKINIT, sACTIVE, sPHYRETRAIN,
      sTRAINERROR, sL1_L2 = Value
}

object LTSMState extends ChiselEnum {
  val sRESET = Value("h00".U)
  val sSBINIT = Value("h01".U)
  val sMBINIT_PARAM = Value("h02".U)
  val sMBINIT_CAL = Value("h03".U)
  val sMBINIT_REPAIRCLK = Value("h04".U)
  val sMBINIT_REPAIRVAL = Value("h05".U)
  val sMBINIT_REVERSALMB = Value("h06".U)
  val sMBINIT_REPAIRMB = Value("h07".U)
  val sMBTRAIN_VALVREF = Value("h08".U)
  val sMBTRAIN_DATAVREF = Value("h09".U)
  val sMBTRAIN_SPEEDIDLE = Value("h0A".U)
  val sMBTRAIN_TXSELFCAL = Value("h0B".U)
  val sMBTRAIN_RXCLKCAL = Value("h0C".U)
  val sMBTRAIN_VALTRAINCENTER = Value("h0D".U)
  val sMBTRAIN_VALTRAINVREF = Value("h0E".U)
  val sMBTRAIN_DATATRAINCENTER1 = Value("h0F".U)
  val sMBTRAIN_DATATRAINVREF = Value("h10".U)
  val sMBTRAIN_RXDESKEW = Value("h11".U)
  val sMBTRAIN_DATATRAINCENTER2 = Value("h12".U)
  val sMBTRAIN_LINKSPEED = Value("h13".U)
  val sMBTRAIN_REPAIR = Value("h14".U)
  val sPHYRETRAIN = Value("h15".U)
  val sLINKINIT = Value("h16".U)
  val sACTIVE = Value("h17".U)
  val sTRAINERROR = Value("h18".U)
  val sL1_L2 = Value("h19".U)
}

object RetrainEncoding {
  val TXSELFCAL = "b001".U(3.W)
  val SPEEDIDLE = "b010".U(3.W)
  val REPAIR = "b100".U(3.W)
}

case class AfeParams(
    // sbSerializerRatio: Int = 1,
    // sbWidth: Int = 1,
    mbSerializerRatio: Int = 32,
    mbLanes: Int = 16,
    clockPhaseSelBitWidth: Int = 5
)

/*
  Package type a multi-module Link is built from. Mirrors regs.UciePackageType,
  kept local so the LogPHY does not depend on the register block.
 */
object MmplPackageType extends Enumeration {
  val Standard, Advanced = Value
}

/*
  Elaboration parameters for the Multi-module PHY Logic (spec 4.7). One MMPL
  aggregates `numModules` UCIe Modules into a single logical Link that presents
  one RDI to one Die-to-Die Adapter.

  The RDI is `numModules` times the bytes one Module carries per mainband beat,
  so each Module keeps a full-width slice and its MainbandLaneController needs
  no change. On width degrade that controller spends more beats per slice, which
  throttles pl_trdy on its own.
 */
case class MmplParams(
    numModules: Int = 1,
    afe: AfeParams = new AfeParams(),
    packageType: MmplPackageType.Value = MmplPackageType.Standard,
    /* Mainband beats of skew the receive gather absorbs between Modules.
       Spec 4.7.1.2 notes Modules of one Link can be staggered, and pl_valid has
       no backpressure (spec 10.1.4), so a Module running ahead has nowhere to
       go but this queue. */
    rxAlignDepth: Int = 8,
    /* Whole sideband cfg packets the transmit path stages before handing one to
       a Module. Spec 7.1.4 needs the phases of a packet on consecutive cycles,
       so a packet is only started once it is fully resident; this also rides
       out the window where no Module is eligible to transmit yet. */
    cfgTxDepth: Int = 4
) {
  require(
    Set(1, 2, 4).contains(numModules),
    s"MMPL supports one-, two-, and four-module Links (spec 4.7), got $numModules"
  )
  require(
    rxAlignDepth >= 2,
    s"MMPL receive alignment needs room for at least two beats, got $rxAlignDepth"
  )
  require(
    cfgTxDepth >= 1,
    s"MMPL sideband cfg transmit needs room for at least one packet, got $cfgTxDepth"
  )
  require(
    afe.mbSerializerRatio % 8 == 0,
    s"MMPL requires a serializer ratio that is a multiple of 8, got ${afe.mbSerializerRatio}"
  )
  // MainbandLaneController.activeLanesForCode reports 16 active Lanes for the
  // "all functional" code regardless of mbLanes, so an x8 Module would disagree
  // with the MMPL byte map. Standard Package x16 is the integration target.
  require(
    numModules == 1 || afe.mbLanes == 16,
    s"Multi-module MMPL currently targets Standard Package x16 Modules, got x${afe.mbLanes}"
  )

  /** Lanes one Module owns at full width. */
  def lanesPerModule: Int = afe.mbLanes

  /** 8-UI chunks inside one mainband beat. */
  def chunksPerBeat: Int = afe.mbSerializerRatio / 8

  /** Bytes one Module carries per mainband beat: one byte per Lane per 8 UI. */
  def bytesPerModule: Int = afe.mbLanes * chunksPerBeat

  /** RDI presented by one Module to the MMPL. */
  def moduleRdiParams(ncWidth: Int = 32): RdiParams =
    RdiParams(bytesPerModule, ncWidth)

  /** Aggregate RDI the MMPL presents to the Adapter. */
  def rdiParams(ncWidth: Int = 32): RdiParams =
    RdiParams(numModules * bytesPerModule, ncWidth)

  def isMultiModule: Boolean = numModules > 1
}

/*
  Resolution the MMPL hands every Module in MBTRAIN.LINKSPEED (spec 4.7.1).
  Each Module then sends, and expects to receive, the matching response:

    done          -> {MBTRAIN.LINKSPEED done resp}, next state LINKINIT
    repair        -> {MBTRAIN.LINKSPEED exit to repair resp}, next state REPAIR
    speedDegrade  -> {MBTRAIN.LINKSPEED exit to speed degrade resp}, SPEEDIDLE
    disableModule -> {MBTRAIN.LINKSPEED multi-module disable module resp},
                     next state TRAINERROR and eventually RESET
    phyRetrain    -> next state PHYRETRAIN, with no directed response of its
                     own: spec 4.5.3.4.12 Step 5 says a
                     {MBTRAIN.LINKSPEED exit to phy retrain req} received on any
                     Module abandons every outstanding message on all of them
    trainError    -> no operational configuration remains
 */
object MmplResolution extends ChiselEnum {
  val none, done, repair, speedDegrade, disableModule, phyRetrain,
      trainError = Value
}
