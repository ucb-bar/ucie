/*
  Description:
    Bundles that are used within the LogPHY layer.
 */
package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.util._

class SidebandCtrlIO extends Bundle {
  val txEn = Output(Bool())
  val rxEn = Output(Bool())
  val rxTxMode = Output(SBRxTxMode())
  val sbReset = Output(Bool())
  val freezeAcceptingPackets = Output(Bool())
  val allPacketsSent = Input(
    Bool()
  ) // Used in TrainError to make all packets are sent
}

class MainbandLaneCtrlIO(afeParams: AfeParams) extends Bundle {
  val txDataEn = Output(Vec(afeParams.mbLanes, Bool()))
  val txClkEn = Output(Bool())
  val txValidEn = Output(Bool())
  val txTrackEn = Output(Bool())
  val rxDataEn = Output(Vec(afeParams.mbLanes, Bool()))
  val rxClkEn = Output(Bool())
  val rxValidEn = Output(Bool())
  val rxTrackEn = Output(Bool())
}
class PhyCtrlIO extends Bundle {
  val pllLock = Input(Bool())
  val freqSel = Output(SpeedMode())
  val doElectricalIdleRx = Output(Bool())
  val doElectricalIdleTx = Output(Bool())
}

class SidebandLanes(sbMsgWidth: Int) extends Bundle {
  /*
    As of UCIe 3.0, for internal logPHY IOs, the sideband clock is only used
    in the deserializer, so it isn't included in internal routing.
   */
  val data = Bits(sbMsgWidth.W)
}

class SidebandLaneIO(sbParams: SidebandParams) extends Bundle {
  val tx = Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth))
  val rx = Flipped(Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth)))
}

class MainbandLanes(mbNumLanes: Int, mbSerializerRatio: Int) extends Bundle {
  val data = Vec(mbNumLanes, Bits(mbSerializerRatio.W))
  val valid = Bits(mbSerializerRatio.W)
  val clkP = Bits(mbSerializerRatio.W)
  val clkN = Bits(mbSerializerRatio.W)
  val trk = Bits(mbSerializerRatio.W)
}

class MainbandLaneIO(afeParams: AfeParams) extends Bundle {
  val tx = Decoupled(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  val rx = Flipped(
    Decoupled(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
  )
}

// The two directions are not symmetric: `in` is sampled off the bump after the
// PHY's receiver, while `out` is the half rate pair feeding the sideband bump
// drivers, which do the 2:1 serialization themselves.
class SidebandPhyLinkIO(sbLinkWidth: Int) extends Bundle {
  val in = new Bundle {
    val bits = Input(UInt(sbLinkWidth.W))
    val fwClock = Input(UInt(1.W))
  }
  val out = new Bundle {
    val clk = Output(Clock())
    val d0 = Output(UInt(sbLinkWidth.W))
    val d1 = Output(UInt(sbLinkWidth.W))
    val fwClockD0 = Output(UInt(1.W))
    val fwClockD1 = Output(UInt(1.W))
  }
}

class PhyStatusFromPhyIO extends Bundle {
  val pllLock = Bool()
  val clocksUngatedAndStable = Bool()
}

class PhyControlToPhyIO(afeParams: AfeParams) extends Bundle {
  val mbTxDataEn = Vec(afeParams.mbLanes, Bool())
  val mbTxClkEn = Bool()
  val mbTxValidEn = Bool()
  val mbTxTrackEn = Bool()
  val mbRxDataEn = Vec(afeParams.mbLanes, Bool())
  val mbRxClkEn = Bool()
  val mbRxValidEn = Bool()
  val mbRxTrackEn = Bool()

  val sbTxDataEn = Bool()
  val sbTxClkEn = Bool()
  val sbRxDataEn = Bool()
  val sbRxClkEn = Bool()

  val freqSel = SpeedMode()
  val clockPhaseSelect = UInt(afeParams.clockPhaseSelBitWidth.W)
  val doElectricalIdleTx = Bool()
  val doElectricalIdleRx = Bool()
}

class SubFsmControlIO extends Bundle {
  val start = Input(Bool())
  val substateTransitioning = Output(Bool())
  val error = Output(Bool())
  val done = Output(Bool())
}

class PHYParamExchangeIO extends Bundle {
  val voltageSwing = Output(UInt(5.W))
  val maxDataRate = Output(UInt(4.W))
  val clockMode = Output(UInt(1.W))
  val clockPhase = Output(UInt(1.W))
  val ucieSx8 = Output(UInt(1.W))
  val sbFeatExt = Output(UInt(1.W))
  val txAdjRuntime = Output(UInt(1.W))
  val moduleId = Output(UInt(2.W))
}

/*
  What one Module reports to the MMPL after Step 2 of MBTRAIN.LINKSPEED
  (spec 4.5.3.4.12 Step 5c). The MMPL resolves across every operational Module
  and directs each of them to the same next state.

  A Module that degraded earlier -- for example in MBINIT.REPAIRMB -- also
  counts as requesting a width degrade (spec 4.7.1.2.1), but that is not
  reported here. The test is whether the Module is narrower than "the rest of
  the operational modules", which no single Module can see, so the MMPL derives
  it from every Module's width and feeds it to the resolver directly.
 */
class MmplLinkSpeedReport extends Bundle {
  val sentDone = Bool()
  val sentRepair = Bool()
  val sentSpeedDegrade = Bool()
  val sentError = Bool()
  val sentPhyRetrain = Bool()

  val recvdDone = Bool()
  val recvdRepair = Bool()
  val recvdSpeedDegrade = Bool()
  val recvdError = Bool()
  val recvdPhyRetrain = Bool()

  /** This Module wants a width degrade, whichever direction reported it. */
  def widthDegradeRequested: Bool = sentRepair || recvdRepair

  /** This Module wants a speed degrade, whichever direction reported it. */
  def speedDegradeRequested: Bool = sentSpeedDegrade || recvdSpeedDegrade

  /** Either side is taking this Module to PHYRETRAIN, which spec 4.5.3.4.12
    * Step 5 makes a directive for every Module of the Link.
    */
  def phyRetrainRequested: Bool = sentPhyRetrain || recvdPhyRetrain
}

/*
  MMPL to Module direction. `multiModule` selects the LINKSPEED path that waits
  for a resolution instead of resolving locally, so tying it low leaves the
  single-module behaviour untouched.
 */
class MmplModuleCtrlIO extends Bundle {
  val multiModule = Output(Bool())
  val resolution = Valid(MmplResolution())
  // Spec 4.5.3.7: every Module of a multi-module Link must enter PHYRETRAIN
  // with the same retrain encoding, because they must stay at one width and
  // speed.
  val commonRetrainEncoding = Valid(UInt(3.W))
  /* This Module is not part of the operational Link: a LINKSPEED resolution
     disabled it (spec 4.5.3.4.12 Step 5d, "TRAINERROR and eventually RESET")
     or it has no remote Module Partner (Table 5-28's NC). It holds in RESET
     rather than retraining on its own, until the whole Link retrains from
     RESET and the MMPL restores it. */
  val moduleDisabled = Output(Bool())

  /** Drives the directives for a Link with no MMPL above it. */
  def tieOffSingleModule(): Unit = {
    multiModule := false.B
    resolution.valid := false.B
    resolution.bits := MmplResolution.none
    commonRetrainEncoding.valid := false.B
    commonRetrainEncoding.bits := 0.U
    moduleDisabled := false.B
  }
}

/*
  A Module that does not own an RDI state machine exposes this so the block above
  it can host one on its behalf.

  Spec 3.5: "The Adapter data path and RDI data width can be extended for
  multi-module configurations; however, there is a single RDI state machine for
  this configuration. The Multi-module PHY Logic creates the abstraction and
  coordinates between the RDI state and individual modules." The hosted machine
  needs each Module's view of training, and each Module needs the resulting RDI
  state back; {LinkMgmt.RDI.*} is a Table 7-8 message, so spec 4.7.1.1 puts it on
  one Module's sideband rather than on every Module's.
 */
class LogicalPhyRdiHostIO(sbParams: SidebandParams) extends Bundle {
  // What this Module contributes to the hosted state machine.
  val ltsmState = Output(LTState())
  val doRdiBringup = Output(Bool())
  val trainingTimeout = Output(Bool())
  val validFramingError = Output(Bool())
  val cfgSidebandActive = Output(Bool())
  val plPhyInRecenter = Output(Bool())
  val clocksUngatedAndStable = Output(Bool())

  // What the hosted state machine tells this Module.
  val plStateSts = Input(RDIState())
  val doingRdiBringup = Input(Bool())

  // The hosted machine's own sideband messages, carried on this Module's link.
  // Flipped because the bundle is written from the state machine's point of
  // view: this Module accepts what it transmits and offers what it received.
  val sbLaneIo = Flipped(new SidebandLaneIO(sbParams))

  /* `sbLaneIo.rx.ready` is a claim decode, not flow control: a LogicalPhy
     discards anything no consumer claims in the cycle it is offered. One hosted
     state machine serves several Modules, so it can only look at one of them at
     a time -- and a Module that loses that arbitration has not been rejected,
     it has not been looked at yet. `rxHold` says so, and keeps the packet at
     the head of that Module's receive queue until its turn. */
  val rxHold = Input(Bool())
}

/*
  One Module as seen by the MMPL: the Module's RDI slice, its status, the MMPL's
  directives back to it, and -- on a multi-module Link -- the hooks the single
  hosted RDI state machine needs.
 */
class MmplModulePort(
    params: MmplParams,
    ncWidth: Int,
    sbParams: SidebandParams
) extends Bundle {
  val rdi = Flipped(new Rdi(params.moduleRdiParams(ncWidth)))
  val status = Flipped(new LogicalPhyStatusIO())
  val ctrl = new MmplModuleCtrlIO()
  val rdiHost =
    Option.when(params.isMultiModule)(
      Flipped(new LogicalPhyRdiHostIO(sbParams))
    )
}
