package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.layer.{Layer, LayerConfig, block}
import chisel3.layers.Verification
import chisel3.util._

// ============================================================================================
// Bundles
// ============================================================================================
class LogicalPhySidebandStatusIO extends Bundle {
  val sbParityErrSeen = Output(Bool())
  val sbRxPriorityQueuesFullSeen = Output(Bool())
  val sbDeserializerTimedoutSeen = Output(Bool())
  val sbInvalidRouteUpperSeen = Output(Bool())
  val sbInvalidRouteCurrSeen = Output(Bool())
  val sbInvalidRouteLowerSeen = Output(Bool())
  val sbUnhandledCurrentLayerMsgSeen = Output(Bool())
  val sbFirstFaultValid = Output(Bool())
  val sbFirstFaultOpcode = Output(UInt(5.W))
  val sbFirstFaultHeader = Output(UInt(64.W))
}

class LogicalPhyCtrlIO(retryW: Int, afeParams: AfeParams) extends Bundle {
  val pwrGood = Input(Bool())
  val retryTrainingAmt = Input(UInt(retryW.W))
  val localPhyParamSettings = Flipped(Valid(new PHYParamExchangeIO()))
  val linkTrainingParameters = new LinkOperationParameters()
  val swStartLinkTraining = Input(Bool())
  val swRetrainRequest = Input(Bool())
  val linkOpParamOverride = Input(Bool())
  val clockPhaseSelect = Input(UInt(afeParams.clockPhaseSelBitWidth.W))
  val maxErrorThresholdPerLane = Input(UInt(16.W))
  val changeInRuntimeLinkCtrlRegsDetected = Input(Bool())
  val runtimeLinkCtrlBusyBit = Input(Bool())
  val runtimeRequestForRepair = Input(Bool())
}

class LogicalPhyStatusIO extends Bundle {
  val ltState = Output(LTState())
  val currentState = Output(LTSMState())
  val trainingTimedout = Output(Bool())
  val trainingEpisode = Output(new TrainingEpisode())
  val fatalTrainingError = Output(Bool())
  val negotiatedPhyParamSettings = Valid(new PHYParamExchangeIO())
  val linkWidth = Output(LinkWidth())
  val freqSel = Output(SpeedMode())
  val doLaneReversal = Output(Bool())
  val widthDegraded = Output(Bool())
  val txLaneMask = Output(UInt(16.W))
  val rxLaneMask = Output(UInt(16.W))
  val remoteRequestingTrainError = Output(Bool())
  // Functional-Lane codes, which the MMPL byte map needs to know how many Lanes
  // carry data in each direction.
  val localTxFunctionalLanes = Output(UInt(3.W))
  val remoteTxFunctionalLanes = Output(UInt(3.W))
  // What this Module sent and received in MBTRAIN.LINKSPEED (spec 4.7.1).
  val linkSpeedReport = Valid(new MmplLinkSpeedReport())
  val linkSpeedRespMismatch = Output(Bool())
  // Retrain encoding this Module alone derived, for the MMPL to make common
  // across the Link (spec 4.5.3.7).
  val retrainEncoding = Output(UInt(3.W))
  val sideband = new LogicalPhySidebandStatusIO()
}

class LogicalPhyAnalogIO(afeParams: AfeParams, sbParams: SidebandParams)
    extends Bundle {
  val mainband = new MainbandLaneIO(afeParams)
  val sidebandLink = new SidebandPhyLinkIO(sbParams.sbLinkWidth)
  val status = Input(new PhyStatusFromPhyIO())
  val ctrl = Output(new PhyControlToPhyIO(afeParams))
}

// ============================================================================================
// Module
// ============================================================================================
class LogicalPhy(
    afeParams: AfeParams = new AfeParams(),
    sbParams: SidebandParams = new SidebandParams(),
    rdiParams: RdiParams = RdiParams(64, 32),
    retryW: Int = 10,
    desTimeoutCycles: Int = 512,
    queueDepths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths(),
    // Simulation-only shortening of the link training residency timeouts.
    timeoutCyclesOverride: Option[Int] = None,
    // A single-module Link owns its RDI state machine. On a multi-module Link
    // there is one state machine for the whole Link (spec 3.5), so the MMPL
    // hosts it and this Module exposes io.mmplRdiHost instead. A Module that
    // can also run as a Link of its own, with the MMPL bypassed, has both.
    rdiStateMachine: RdiStateMachineHome.Value = RdiStateMachineHome.Local,
    // Simulation-only extra latency, in cycles, on the sideband receive path,
    // for tests of Modules whose sideband links differ in latency. Zero adds
    // nothing.
    sidebandRxDelayCycles: Int = 0
) extends Module {
  require(
    sidebandRxDelayCycles >= 0,
    s"sidebandRxDelayCycles must not be negative, got $sidebandRxDelayCycles"
  )
  private val hasLocalRdi = rdiStateMachine != RdiStateMachineHome.Hosted
  private val hasRdiHost = rdiStateMachine != RdiStateMachineHome.Local

  // Current integration target is Standard Package operation in Streaming RAW mode only.
  val io = IO(new Bundle {
    val rdi = new Rdi(rdiParams)
    val ctrl = new LogicalPhyCtrlIO(retryW, afeParams)
    val status = new LogicalPhyStatusIO()
    val analog = new LogicalPhyAnalogIO(afeParams, sbParams)
    // Directives from the Multi-module PHY Logic above this Module (spec 4.7).
    // Call tieOffSingleModule() when this Module is the whole Link.
    val mmplCtrl = Flipped(new MmplModuleCtrlIO())
    // Present only when the RDI state machine can live above this Module.
    val mmplRdiHost =
      Option.when(hasRdiHost)(new LogicalPhyRdiHostIO(sbParams))
  })

  /* Which machine is in charge. A Selectable Module follows the MMPL, which
     hosts the machine for a multi-module Link and hands it back once the
     Module has been bypassed to a Link of its own (spec 4.7.2). */
  val rdiHosted: Bool = rdiStateMachine match {
    case RdiStateMachineHome.Local      => false.B
    case RdiStateMachineHome.Hosted     => true.B
    case RdiStateMachineHome.Selectable => io.mmplCtrl.rdiHosted
  }

  val ltsm = Module(
    new LinkTrainingSM(sbParams, afeParams, retryW, timeoutCyclesOverride)
  )
  /* Held in reset while the hosted machine is in charge, so it neither sends
     nor claims sideband messages for a Link it is not running, and starts from
     Reset when it is handed the Module back. The MMPL only changes hands while
     the whole group is in RESET (MultiModulePhy). */
  val rdiController = Option.when(hasLocalRdi) {
    val localRdiReset =
      if (hasRdiHost) reset.asBool || rdiHosted else reset
    withReset(localRdiReset) {
      Module(
        new RDIController(
          sbParams,
          LinkTrainingSM.linkErrorResidencyCycles(timeoutCyclesOverride)
        )
      )
    }
  }

  /** A signal of the RDI state machine in charge of this Module: its own, or
    * the one hosted above it.
    */
  private def fromRdiInCharge[T <: Data](
      local: RDIController => T,
      hosted: LogicalPhyRdiHostIO => T
  ): T = (rdiController, io.mmplRdiHost) match {
    case (Some(ctrl), None)       => local(ctrl)
    case (None, Some(host))       => hosted(host)
    case (Some(ctrl), Some(host)) => Mux(rdiHosted, hosted(host), local(ctrl))
    case (None, None) =>
      throw new IllegalStateException("a Module needs an RDI state machine")
  }
  val mainbandLaneController = Module(
    new MainbandLaneController(afeParams, rdiParams)
  )
  val patternReader = Module(new PatternReader(afeParams))
  val patternWriter = Module(new PatternWriter(afeParams))
  val phyControlTranslator = Module(new PhyControlSignalTranslator(afeParams))
  val phyLaneTrainer = Module(new PhyLaneTrainer(afeParams))
  val scrambler = Module(new UcieLFSR(afeParams))
  val descrambler = Module(new UcieLFSR(afeParams))
  val logPhySidebandChannel =
    withReset(reset.asBool || ltsm.io.sbCtrlIo.sbReset) {
      Module(
        new LogPhySidebandChannel(
          sbMsgWidth = sbParams.sbNodeMsgWidth,
          sbLinkWidth = sbParams.sbLinkWidth,
          rdiNcWidth = rdiParams.ncWidth,
          numCredits = sbParams.maxCrd,
          desTimeoutCycles = desTimeoutCycles,
          queueDepths = queueDepths
        )
      )
    }

  // ============================================================================================
  // Control/status wiring
  // ============================================================================================
  ltsm.io.pwrGood := io.ctrl.pwrGood
  ltsm.io.retryTrainingAmt := io.ctrl.retryTrainingAmt
  ltsm.io.localPhyParamSettings <> io.ctrl.localPhyParamSettings
  ltsm.io.linkTrainingParameters <> io.ctrl.linkTrainingParameters
  ltsm.io.swStartLinkTraining := io.ctrl.swStartLinkTraining
  ltsm.io.swRetrainRequest := io.ctrl.swRetrainRequest
  ltsm.io.linkOpParamOverride := io.ctrl.linkOpParamOverride
  ltsm.io.maxErrorThresholdPerLane := io.ctrl.maxErrorThresholdPerLane
  ltsm.io.changeInRuntimeLinkCtrlRegsDetected := io.ctrl.changeInRuntimeLinkCtrlRegsDetected
  ltsm.io.runtimeLinkCtrlBusyBit := io.ctrl.runtimeLinkCtrlBusyBit
  ltsm.io.runtimeRequestForRepair := io.ctrl.runtimeRequestForRepair
  ltsm.io.mmplCtrl <> io.mmplCtrl

  io.status.ltState := ltsm.io.ltState
  io.status.currentState := ltsm.io.currentState
  io.status.trainingTimedout := ltsm.io.trainingTimedout
  io.status.trainingEpisode := ltsm.io.trainingEpisode
  io.status.fatalTrainingError := ltsm.io.fatalTrainingError
  io.status.negotiatedPhyParamSettings := ltsm.io.negotiatedPhyParamSettings
  io.status.freqSel := ltsm.io.phyCtrlIo.freqSel
  io.status.doLaneReversal := ltsm.io.doLaneReversal
  io.status.widthDegraded := ltsm.io.widthDegraded
  io.status.txLaneMask := ltsm.io.txLaneMask
  io.status.rxLaneMask := ltsm.io.rxLaneMask
  io.status.remoteRequestingTrainError := ltsm.io.remoteRequestingTrainError
  io.status.localTxFunctionalLanes := ltsm.io.localTxFunctionalLanes
  io.status.remoteTxFunctionalLanes := ltsm.io.remoteTxFunctionalLanes
  io.status.linkSpeedReport := ltsm.io.linkSpeedReport
  io.status.linkSpeedRespMismatch := ltsm.io.linkSpeedRespMismatch
  io.status.retrainEncoding := ltsm.io.retrainEncoding

  phyLaneTrainer.io.phyTrainIo <> ltsm.io.phyTrainIo

  // ============================================================================================
  // RDI control and sideband cfg path
  // ============================================================================================
  val phyInRecenter =
    (ltsm.io.ltState === LTState.sSBINIT) ||
      (ltsm.io.ltState === LTState.sMBINIT) ||
      (ltsm.io.ltState === LTState.sMBTRAIN) ||
      (ltsm.io.ltState === LTState.sPHYRETRAIN)

  // What escalates to RDI LinkError is the LTSM's to decide: a timeout in
  // initial training retries instead (see LinkTrainingSM).
  val internalLinkError = ltsm.io.forceRdiLinkError
  val cfgSidebandActive = logPhySidebandChannel.io.rdi.activity
  val clocksUngatedAndStable =
    phyControlTranslator.io.toDigital.clocksUngatedAndStable

  /* The sideband carries the Link's own messages (spec 4.7.1.1: not "in
     RESET or SBINIT") only once this training has got through SBINIT: a
     TRAINERROR that SBINIT timed out into has no sideband to speak of. */
  val sidebandUp = RegInit(false.B)
  when(
    (ltsm.io.ltState === LTState.sRESET) || (ltsm.io.ltState === LTState.sSBINIT)
  ) {
    sidebandUp := false.B
  }.elsewhen(ltsm.io.ltState === LTState.sMBINIT) {
    sidebandUp := true.B
  }

  rdiController.foreach { ctrl =>
    ctrl.io.rdi.lpStateReq := io.rdi.lpStateReq
    ctrl.io.rdi.lpWakeReq := io.rdi.lpWakeReq
    ctrl.io.rdi.lpClkAck := io.rdi.lpClkAck
    ctrl.io.rdi.lpStallAck := io.rdi.lpStallAck
    ctrl.io.rdi.lpLinkError := io.rdi.lpLinkError
    ctrl.io.ltsmState := ltsm.io.ltState
    ctrl.io.sbLinkDown := !sidebandUp
    ctrl.io.doRdiBringup := ltsm.io.rdi.doRdiBringup
    ctrl.io.internalLinkError := internalLinkError
    ctrl.io.internalRetrainReq := ltsm.io.swRetrainPending
    ctrl.io.txDrained := mainbandLaneController.io.ctrl.txIdle
    ctrl.io.plPhyInRecenter := phyInRecenter
    ctrl.io.cfgSidebandActive := cfgSidebandActive
    ctrl.io.clocksUngatedAndStable := clocksUngatedAndStable
  }

  io.mmplRdiHost.foreach { host =>
    host.ltsmState := ltsm.io.ltState
    host.doRdiBringup := ltsm.io.rdi.doRdiBringup
    host.internalLinkError := internalLinkError
    host.swRetrainRequest := ltsm.io.swRetrainPending
    host.txIdle := mainbandLaneController.io.ctrl.txIdle
    host.plPhyInRecenter := phyInRecenter
    host.cfgSidebandActive := cfgSidebandActive
    host.clocksUngatedAndStable := clocksUngatedAndStable
  }

  // The RDI state, wherever the machine that drives it lives.
  val rdiStateSts = fromRdiInCharge(_.io.rdi.plStateSts, _.plStateSts)

  ltsm.io.rdi.doingRdiBringUp :=
    fromRdiInCharge(_.io.doingRdiBringup, _.doingRdiBringup)

  ltsm.io.rdi.plStateSts := rdiStateSts
  ltsm.io.rdi.lpStateReq := io.rdi.lpStateReq

  logPhySidebandChannel.io.rdi.in.valid := io.rdi.lpCfgVld
  logPhySidebandChannel.io.rdi.in.bits := io.rdi.lpCfg
  logPhySidebandChannel.io.rdi.txCreditReturn := io.rdi.lpCfgCrd

  io.rdi.plCfg := logPhySidebandChannel.io.rdi.out.bits
  io.rdi.plCfgVld := logPhySidebandChannel.io.rdi.out.valid
  io.rdi.plCfgCrd := logPhySidebandChannel.io.rdi.rxCreditReturn

  // ============================================================================================
  // Sideband packet arbitration
  // ============================================================================================
  val sidebandRxQueue = Module(
    new Queue(UInt(sbParams.sbNodeMsgWidth.W), 1, pipe = true, flow = false)
  )
  // Under the sideband channel's own reset, so a delayed packet dies with it.
  private val sidebandRxIn =
    withReset(reset.asBool || ltsm.io.sbCtrlIo.sbReset) {
      (0 until sidebandRxDelayCycles).foldLeft(
        logPhySidebandChannel.io.layer.out: DecoupledIO[UInt]
      )((stage, _) => Queue(stage, 1, pipe = true))
    }
  sidebandRxQueue.io.enq <> sidebandRxIn

  val sidebandRxReadyLtsm = WireDefault(false.B)
  val sidebandRxReadyRdi = WireDefault(false.B)
  val sidebandRxUnhandled = WireDefault(false.B)

  ltsm.io.sbLaneIo.rx.valid := sidebandRxQueue.io.deq.valid
  ltsm.io.sbLaneIo.rx.bits.data := sidebandRxQueue.io.deq.bits
  sidebandRxReadyLtsm := ltsm.io.sbLaneIo.rx.ready

  // Only the machine in charge is shown the Link's messages.
  rdiController.foreach { ctrl =>
    ctrl.io.sbLaneIo.rx.valid := sidebandRxQueue.io.deq.valid && !rdiHosted
    ctrl.io.sbLaneIo.rx.bits.data := sidebandRxQueue.io.deq.bits
  }

  // A hosted state machine above this Module can ask for a packet to stay put
  // instead of being retired as unclaimed (see LogicalPhyRdiHostIO.rxHold).
  val sidebandRxHeldAbove = WireDefault(false.B)

  io.mmplRdiHost.foreach { host =>
    /* Only offer upward what this Module's own LTSM did not claim, so a hosted
       state machine merging several Modules is never shown a message that
       belongs to one of their link training state machines. That includes a
       {PHYRETRAIN.retrain start req} the LTSM is holding for later: the hosted
       machine arbitrates the Modules by fixed priority and never claims it, so
       offering it would win every grant and hold the Module carrying
       {LinkMgmt.RDI.Rsp.Retrain} back for good -- and without that response
       the RDI never enters Retrain and the LTSM never takes the request. */
    host.sbLaneIo.rx.valid := sidebandRxQueue.io.deq.valid &&
      !sidebandRxReadyLtsm && !ltsm.io.holdSidebandRx && rdiHosted
    host.sbLaneIo.rx.bits.data := sidebandRxQueue.io.deq.bits
    sidebandRxHeldAbove := host.rxHold && rdiHosted
  }

  sidebandRxReadyRdi :=
    fromRdiInCharge(_.io.sbLaneIo.rx.ready, _.sbLaneIo.rx.ready)

  // The LTSM can also ask for a packet to stay put: a {PHYRETRAIN.retrain
  // start req} that arrived before it reached PHYRETRAIN (LinkTrainingSM's
  // holdSidebandRx). Retiring it would cost the partner its handshake.
  sidebandRxUnhandled := sidebandRxQueue.io.deq.valid && !sidebandRxReadyLtsm &&
    !sidebandRxReadyRdi && !sidebandRxHeldAbove && !ltsm.io.holdSidebandRx

  val sbParityErrSeen = RegInit(false.B)
  val sbRxPriorityQueuesFullSeen = RegInit(false.B)
  val sbDeserializerTimedoutSeen = RegInit(false.B)
  val sbInvalidRouteUpperSeen = RegInit(false.B)
  val sbInvalidRouteCurrSeen = RegInit(false.B)
  val sbInvalidRouteLowerSeen = RegInit(false.B)
  val sbUnhandledCurrentLayerMsgSeen = RegInit(false.B)
  val sbFirstFaultValid = RegInit(false.B)
  val sbFirstFaultOpcode = RegInit(0.U(5.W))
  val sbFirstFaultHeader = RegInit(0.U(64.W))

  when(logPhySidebandChannel.io.layer.status.sbParityErr) {
    sbParityErrSeen := true.B
  }
  when(logPhySidebandChannel.io.layer.status.rxPriorityQueuesFull) {
    sbRxPriorityQueuesFullSeen := true.B
  }
  when(logPhySidebandChannel.io.layer.status.desTimedout) {
    sbDeserializerTimedoutSeen := true.B
  }
  when(logPhySidebandChannel.io.layer.status.invalidRouteUpper) {
    sbInvalidRouteUpperSeen := true.B
  }
  when(logPhySidebandChannel.io.layer.status.invalidRouteCurr) {
    sbInvalidRouteCurrSeen := true.B
  }
  when(logPhySidebandChannel.io.layer.status.invalidRouteLower) {
    sbInvalidRouteLowerSeen := true.B
  }
  when(sidebandRxUnhandled) {
    sbUnhandledCurrentLayerMsgSeen := true.B
  }

  block(Verification) {
    block(Verification.Assert) {
      assert(
        PopCount(Seq(sidebandRxReadyLtsm, sidebandRxReadyRdi)) <= 1.U,
        "FATAL: Multiple LogicalPhy sideband consumers asserted RX ready in the same cycle"
      )
      // Only a Selectable Module has both machines to choose between.
      if (rdiStateMachine != RdiStateMachineHome.Selectable) {
        assert(
          io.mmplCtrl.rdiHosted === hasRdiHost.B,
          "FATAL: LogicalPhy was told its RDI state machine lives where it was not built"
        )
      }
    }
    block(Verification.Cover) {
      cover(sidebandRxUnhandled)
    }
  }

  sidebandRxQueue.io.deq.ready := sidebandRxReadyLtsm || sidebandRxReadyRdi || sidebandRxUnhandled

  val sidebandTxArbiter = Module(
    new RRArbiter(UInt(sbParams.sbNodeMsgWidth.W), 2)
  )
  sidebandTxArbiter.io.in(0).valid := ltsm.io.sbLaneIo.tx.valid
  sidebandTxArbiter.io.in(0).bits := ltsm.io.sbLaneIo.tx.bits.data
  ltsm.io.sbLaneIo.tx.ready := sidebandTxArbiter.io.in(0).ready

  sidebandTxArbiter.io.in(1).valid :=
    fromRdiInCharge(_.io.sbLaneIo.tx.valid, _.sbLaneIo.tx.valid)
  sidebandTxArbiter.io.in(1).bits :=
    fromRdiInCharge(_.io.sbLaneIo.tx.bits.data, _.sbLaneIo.tx.bits.data)
  rdiController.foreach(
    _.io.sbLaneIo.tx.ready := sidebandTxArbiter.io.in(1).ready && !rdiHosted
  )
  io.mmplRdiHost.foreach(
    _.sbLaneIo.tx.ready := sidebandTxArbiter.io.in(1).ready && rdiHosted
  )

  val sidebandTxQueue = Module(
    new Queue(UInt(sbParams.sbNodeMsgWidth.W), 1, pipe = true, flow = false)
  )
  sidebandTxQueue.io.enq <> sidebandTxArbiter.io.out
  logPhySidebandChannel.io.layer.in <> sidebandTxQueue.io.deq

  val firstFaultSeen = WireDefault(
    sidebandRxUnhandled ||
      logPhySidebandChannel.io.layer.status.invalidRouteCurr ||
      logPhySidebandChannel.io.layer.status.invalidRouteUpper ||
      logPhySidebandChannel.io.layer.status.invalidRouteLower ||
      logPhySidebandChannel.io.layer.status.sbParityErr ||
      logPhySidebandChannel.io.layer.status.rxPriorityQueuesFull ||
      logPhySidebandChannel.io.layer.status.desTimedout
  )
  val firstFaultPacket = WireDefault(0.U(sbParams.sbNodeMsgWidth.W))
  when(sidebandRxUnhandled) {
    firstFaultPacket := sidebandRxQueue.io.deq.bits
  }.elsewhen(logPhySidebandChannel.io.layer.status.invalidRouteCurr) {
    firstFaultPacket := sidebandTxQueue.io.deq.bits
  }

  when(!sbFirstFaultValid && firstFaultSeen) {
    sbFirstFaultValid := true.B
    sbFirstFaultOpcode := firstFaultPacket(4, 0)
    sbFirstFaultHeader := firstFaultPacket(63, 0)
  }

  logPhySidebandChannel.io.link.ctrl.txMode := ltsm.io.sbCtrlIo.rxTxMode
  logPhySidebandChannel.io.link.ctrl.rxMode := ltsm.io.sbCtrlIo.rxTxMode
  logPhySidebandChannel.io.link.ctrl.freezeAcceptingPackets := ltsm.io.sbCtrlIo.freezeAcceptingPackets
  ltsm.io.sbCtrlIo.allPacketsSent := logPhySidebandChannel.io.link.ctrl.allPacketsSent

  io.analog.sidebandLink.out.bits := logPhySidebandChannel.io.link.out.bits
  io.analog.sidebandLink.out.fwClock := logPhySidebandChannel.io.link.out.fwClock
  logPhySidebandChannel.io.link.in.bits := io.analog.sidebandLink.in.bits
  logPhySidebandChannel.io.link.in.fwClock := io.analog.sidebandLink.in.fwClock

  io.status.sideband.sbParityErrSeen := sbParityErrSeen
  io.status.sideband.sbRxPriorityQueuesFullSeen := sbRxPriorityQueuesFullSeen
  io.status.sideband.sbDeserializerTimedoutSeen := sbDeserializerTimedoutSeen
  io.status.sideband.sbInvalidRouteUpperSeen := sbInvalidRouteUpperSeen
  io.status.sideband.sbInvalidRouteCurrSeen := sbInvalidRouteCurrSeen
  io.status.sideband.sbInvalidRouteLowerSeen := sbInvalidRouteLowerSeen
  io.status.sideband.sbUnhandledCurrentLayerMsgSeen := sbUnhandledCurrentLayerMsgSeen
  io.status.sideband.sbFirstFaultValid := sbFirstFaultValid
  io.status.sideband.sbFirstFaultOpcode := sbFirstFaultOpcode
  io.status.sideband.sbFirstFaultHeader := sbFirstFaultHeader

  // ============================================================================================
  // PHY control translation
  // ============================================================================================
  phyControlTranslator.io.fromDigital.mbCtrlIo.txDataEn := ltsm.io.mbCtrlIo.txDataEn
  phyControlTranslator.io.fromDigital.mbCtrlIo.txClkEn := ltsm.io.mbCtrlIo.txClkEn
  phyControlTranslator.io.fromDigital.mbCtrlIo.txValidEn := ltsm.io.mbCtrlIo.txValidEn
  phyControlTranslator.io.fromDigital.mbCtrlIo.txTrackEn := ltsm.io.mbCtrlIo.txTrackEn
  phyControlTranslator.io.fromDigital.mbCtrlIo.rxDataEn := ltsm.io.mbCtrlIo.rxDataEn
  phyControlTranslator.io.fromDigital.mbCtrlIo.rxClkEn := ltsm.io.mbCtrlIo.rxClkEn
  phyControlTranslator.io.fromDigital.mbCtrlIo.rxValidEn := ltsm.io.mbCtrlIo.rxValidEn
  phyControlTranslator.io.fromDigital.mbCtrlIo.rxTrackEn := ltsm.io.mbCtrlIo.rxTrackEn
  phyControlTranslator.io.fromDigital.sbCtrlIo.txDataEn := ltsm.io.sbCtrlIo.txEn
  phyControlTranslator.io.fromDigital.sbCtrlIo.txClkEn := ltsm.io.sbCtrlIo.txEn
  phyControlTranslator.io.fromDigital.sbCtrlIo.rxDataEn := ltsm.io.sbCtrlIo.rxEn
  phyControlTranslator.io.fromDigital.sbCtrlIo.rxClkEn := ltsm.io.sbCtrlIo.rxEn
  phyControlTranslator.io.fromDigital.freqSel := ltsm.io.phyCtrlIo.freqSel
  phyControlTranslator.io.fromDigital.clockPhaseSelect := io.ctrl.clockPhaseSelect
  phyControlTranslator.io.fromDigital.doElectricalIdleTx := ltsm.io.phyCtrlIo.doElectricalIdleTx
  phyControlTranslator.io.fromDigital.doElectricalIdleRx := ltsm.io.phyCtrlIo.doElectricalIdleRx
  phyControlTranslator.io.fromPhy := io.analog.status
  io.analog.ctrl := phyControlTranslator.io.toPhy

  ltsm.io.phyCtrlIo.pllLock := phyControlTranslator.io.toDigital.pllLock

  // TODO: Route this to a future digital power-management / clock-control block.
  rdiController.foreach(ctrl => dontTouch(ctrl.io.ungateClocks))

  // ============================================================================================
  // Scramblers/Descrambler
  // ============================================================================================
  patternWriter.io.txLfsrCtrl.pattern := scrambler.io.lfsrOutput
  patternReader.io.rxLfsrCtrl.pattern := descrambler.io.lfsrOutput

  val isActive = ltsm.io.ltState === LTState.sACTIVE
  val txTrainingLfsrActive = patternWriter.io.txLfsrCtrl.valid
  val txRuntimeIncrement =
    io.analog.mainband.tx.valid && io.analog.mainband.tx.ready && isActive
  val scramblerIncrement = Mux(
    txTrainingLfsrActive,
    patternWriter.io.txLfsrCtrl.increment,
    txRuntimeIncrement
  )
  val scramblerReset =
    ltsm.io.scramblerReset || patternWriter.io.txLfsrCtrl.resetLfsr

  scrambler.io.increment := VecInit(
    Seq.fill(afeParams.mbLanes)(scramblerIncrement)
  )
  scrambler.io.resetLfsr := VecInit(Seq.fill(afeParams.mbLanes)(scramblerReset))

  val rxRuntimeIncrement =
    io.analog.mainband.rx.valid && io.analog.mainband.rx.ready && isActive
  val descramblerIncrement = Mux(
    isActive,
    rxRuntimeIncrement,
    patternReader.io.rxLfsrCtrl.increment
  )
  val descramblerReset =
    ltsm.io.scramblerReset || patternReader.io.rxLfsrCtrl.resetLfsr

  descrambler.io.increment := VecInit(
    Seq.fill(afeParams.mbLanes)(descramblerIncrement)
  )
  descrambler.io.resetLfsr := VecInit(
    Seq.fill(afeParams.mbLanes)(descramblerReset)
  )

  // ============================================================================================
  // Pattern engine and runtime mainband path
  // ============================================================================================
  patternWriter.io.interfaceIo <> ltsm.io.patternWriterIo
  patternReader.io.interfaceIo <> ltsm.io.patternReaderIo

  val rawRxLaneBits = Wire(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  rawRxLaneBits := Mux(
    io.analog.mainband.rx.valid,
    io.analog.mainband.rx.bits,
    0.U.asTypeOf(chiselTypeOf(io.analog.mainband.rx.bits))
  )
  patternReader.io.mbRxLaneIo := rawRxLaneBits
  patternReader.io.mbRxValid := io.analog.mainband.rx.valid

  val canAcceptLpIrdy = rdiStateSts =/= RDIState.reset

  // Spec 4.5.3.3.5: "UCIe-S x8" puts a x16 Module into x8 mode, which changes
  // what the "all functional" Lane code means for every block that decodes it.
  val negotiatedBy8 =
    ltsm.io.negotiatedPhyParamSettings.valid &&
      ltsm.io.negotiatedPhyParamSettings.bits.ucieSx8.asBool

  mainbandLaneController.io.rdi.tx.lpIrdy := io.rdi.lpIrdy && isActive && canAcceptLpIrdy
  // Only what the RDI accepted in Active goes out: a stall drained in LinkError
  // (RDIController.stallDrain) is dropped, not transmitted.
  mainbandLaneController.io.rdi.tx.lpValid := io.rdi.lpValid && isActive &&
    (rdiStateSts === RDIState.active)
  mainbandLaneController.io.rdi.tx.lpData := io.rdi.lpData
  mainbandLaneController.io.ctrl.localTxFunctionalLanes := ltsm.io.localTxFunctionalLanes
  mainbandLaneController.io.ctrl.localRxFunctionalLanes := ltsm.io.remoteTxFunctionalLanes
  // Spec 4.5.3.3.5: in x8 mode the "all functional" code covers Lanes 0 to 7.
  mainbandLaneController.io.ctrl.interpretBy8Lane := negotiatedBy8
  // A framing error lasts until retrain takes the LTSM out of ACTIVE, so the
  // next Active period starts clean.
  mainbandLaneController.io.ctrl.clearFramingError := !isActive
  mainbandLaneController.io.ctrl.clearBeats := !isActive
  mainbandLaneController.io.mbLanes.tx.ready := io.analog.mainband.tx.ready && isActive

  val activeTxLaneMask = PatternLaneMap.decodeLaneMap(
    ltsm.io.localTxFunctionalLanes,
    afeParams.mbLanes
  )
  val activeRxLaneMask = PatternLaneMap.decodeLaneMap(
    ltsm.io.remoteTxFunctionalLanes,
    afeParams.mbLanes
  )

  val scrambledTxBits = Wire(
    chiselTypeOf(mainbandLaneController.io.mbLanes.tx.bits)
  )
  scrambledTxBits := mainbandLaneController.io.mbLanes.tx.bits
  for (lane <- 0 until afeParams.mbLanes) {
    scrambledTxBits.data(lane) := Mux(
      activeTxLaneMask(lane),
      mainbandLaneController.io.mbLanes.tx.bits.data(lane) ^ scrambler.io
        .lfsrOutput(lane),
      0.U
    )
  }

  val txLaneReversalEnabled = ltsm.io.doLaneReversal

  val descrambledRxBits = Wire(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  descrambledRxBits := io.analog.mainband.rx.bits
  for (lane <- 0 until afeParams.mbLanes) {
    descrambledRxBits.data(lane) := Mux(
      activeRxLaneMask(lane),
      io.analog.mainband.rx.bits.data(lane) ^ descrambler.io.lfsrOutput(lane),
      0.U
    )
  }
  mainbandLaneController.io.mbLanes.rx.valid := io.analog.mainband.rx.valid && isActive
  mainbandLaneController.io.mbLanes.rx.bits := descrambledRxBits
  io.analog.mainband.rx.ready := Mux(
    isActive,
    mainbandLaneController.io.mbLanes.rx.ready,
    true.B
  )

  rdiController.foreach(
    _.io.validFramingError := mainbandLaneController.io.ctrl.validFramingError
  )
  io.mmplRdiHost.foreach(
    _.validFramingError := mainbandLaneController.io.ctrl.validFramingError
  )

  // In Streaming RAW mode, framing corruption in ACTIVE triggers pl_error and
  // the data path is stalled until retrain completes and LTSM returns to ACTIVE.
  val plErrorPulse =
    isActive && mainbandLaneController.io.ctrl.validFramingError
  val suppressPlValidAfterError = RegInit(false.B)

  /* pl_valid is gated on ACTIVE anyway, so the flag only has to last while the
     LTSM is still in it. Clearing it on the way back in instead took effect a
     cycle after ACTIVE was re-entered, and a beat landing in that first cycle
     was dropped: behind the MMPL, one Module short a slice offsets every later
     aggregate word by it. */
  when(!isActive) {
    suppressPlValidAfterError := false.B
  }.elsewhen(plErrorPulse) {
    suppressPlValidAfterError := true.B
  }

  // Clk Calibrate pattern for training, constant pattern so put here
  val fwClkPPattern = "b01010101".U(8.W)
  val fwClkNPattern = "b10101010".U(8.W)
  val fwClkPBits = Wire(UInt(afeParams.mbSerializerRatio.W))
  val fwClkNBits = Wire(UInt(afeParams.mbSerializerRatio.W))
  fwClkPBits := VecInit(
    Seq.tabulate(afeParams.mbSerializerRatio)(i => fwClkPPattern(i % 8))
  ).asUInt
  fwClkNBits := VecInit(
    Seq.tabulate(afeParams.mbSerializerRatio)(i => fwClkNPattern(i % 8))
  ).asUInt

  val rxClkCalOverride =
    ltsm.io.rxClkCalSendFwClkPattern && ltsm.io.rxClkCalSendTrkPattern
  val patternWriterSelectedForTx =
    ((ltsm.io.ltState === LTState.sMBINIT) || (ltsm.io.ltState === LTState.sMBTRAIN)) &&
      !rxClkCalOverride &&
      !isActive
  patternWriter.io.mbTxLaneIo.ready := patternWriterSelectedForTx && io.analog.mainband.tx.ready

  val rxClkCalTxBits = Wire(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  rxClkCalTxBits.data.foreach(_ := 0.U)
  rxClkCalTxBits.valid := 0.U
  rxClkCalTxBits.clkP := Mux(ltsm.io.rxClkCalSendFwClkPattern, fwClkPBits, 0.U)
  rxClkCalTxBits.clkN := Mux(ltsm.io.rxClkCalSendFwClkPattern, fwClkNBits, 0.U)
  rxClkCalTxBits.trk := Mux(ltsm.io.rxClkCalSendTrkPattern, fwClkPBits, 0.U)

  // Lane reversal
  val selectedTxBits = Wire(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  selectedTxBits := 0.U.asTypeOf(chiselTypeOf(selectedTxBits))
  val reversedSelectedTxBits = Wire(chiselTypeOf(selectedTxBits))
  reversedSelectedTxBits := selectedTxBits
  for (lane <- 0 until afeParams.mbLanes) {
    reversedSelectedTxBits.data(lane) := selectedTxBits.data(
      (afeParams.mbLanes - 1) - lane
    )
  }

  io.analog.mainband.tx.bits := 0.U.asTypeOf(
    new MainbandLanes(
      afeParams.mbLanes,
      afeParams.mbSerializerRatio
    )
  )
  io.analog.mainband.tx.valid := false.B

  when(isActive) {
    selectedTxBits := scrambledTxBits
    io.analog.mainband.tx.valid := mainbandLaneController.io.mbLanes.tx.valid
  }.elsewhen(rxClkCalOverride) {
    selectedTxBits := rxClkCalTxBits
    io.analog.mainband.tx.valid := true.B
  }.elsewhen(patternWriterSelectedForTx) {
    selectedTxBits := patternWriter.io.mbTxLaneIo.bits
    io.analog.mainband.tx.valid := patternWriter.io.mbTxLaneIo.valid
  }

  io.analog.mainband.tx.bits := Mux(
    txLaneReversalEnabled,
    reversedSelectedTxBits,
    selectedTxBits
  )

  block(Verification) {
    block(Verification.Assert) {
      when(rxClkCalOverride) {
        assert(
          io.analog.mainband.tx.ready,
          "FATAL: LogicalPhy training TX path assumes the analog PHY is ready"
        )
      }
    }
  }

  // ============================================================================================
  // RDI outputs
  // ============================================================================================
  val linkWidth = Wire(LinkWidth())
  linkWidth := LinkWidth.x16
  switch(ltsm.io.localTxFunctionalLanes) {
    is("b001".U) { linkWidth := LinkWidth.x8 }
    is("b010".U) { linkWidth := LinkWidth.x8 }
    is("b100".U) { linkWidth := LinkWidth.x4 }
    is("b101".U) { linkWidth := LinkWidth.x4 }
    is("b011".U) {
      linkWidth := Mux(negotiatedBy8, LinkWidth.x8, LinkWidth.x16)
    }
  }
  io.status.linkWidth := linkWidth

  // TODO: Allow plTrdy during the LinkError pl_stallreq/lp_stallack handshake
  // after checking the exact condition in the RDI handshake and signals section.
  // A hosted machine drains a stall at the MMPL, above this Module.
  io.rdi.plTrdy := Mux(
    rdiStateSts === RDIState.active,
    mainbandLaneController.io.rdi.tx.plTrdy,
    fromRdiInCharge(_.io.stallDrain, _ => false.B)
  )
  io.rdi.plValid := Mux(
    isActive && !suppressPlValidAfterError,
    mainbandLaneController.io.rdi.rx.plValid,
    false.B
  )
  io.rdi.plData := Mux(isActive, mainbandLaneController.io.rdi.rx.plData, 0.U)
  io.rdi.plStateSts := rdiStateSts
  // With the state machine hosted above, these belong to the hosted one and the
  // block above this Module drives the Adapter with them; there is nothing
  // meaningful for a single Module to say.
  io.rdi.plInbandPres := fromRdiInCharge(_.io.rdi.plInbandPres, _ => false.B)
  io.rdi.plStallReq := fromRdiInCharge(_.io.rdi.plStallReq, _ => false.B)
  io.rdi.plClkReq := fromRdiInCharge(_.io.rdi.plClkReq, _ => false.B)
  io.rdi.plWakeAck := fromRdiInCharge(_.io.rdi.plWakeAck, _ => false.B)
  io.rdi.plSpeedmode := ltsm.io.phyCtrlIo.freqSel
  io.rdi.plLnkCfg := linkWidth
  io.rdi.plNfError := false.B
  io.rdi.plTrainError := ltsm.io.fatalTrainingError
  io.rdi.plPhyInRecenter := phyInRecenter
  io.rdi.plError := plErrorPulse
  io.rdi.plCError := false.B
  io.rdi.plMaxSpeedmode := false.B
}
