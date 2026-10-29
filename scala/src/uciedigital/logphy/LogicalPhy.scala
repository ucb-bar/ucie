/*
  The Logical PHY (Chapter 4) behind RDI, for Standard Package Raw Format.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.rdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.utils.Sync3
import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

class LogicalPhySidebandStatusIO extends Bundle {
  val sbParityErrSeen = Output(Bool())
  val sbParityErrEvent = Output(Bool())
  val sbRxPriorityQueuesFullSeen = Output(Bool())
  val sbDeserializerTimedoutSeen = Output(Bool())
  val sbRdiHandshakeTimedoutSeen = Output(Bool())
  val sbInvalidRouteUpperSeen = Output(Bool())
  val sbInvalidRouteCurrSeen = Output(Bool())
  val sbInvalidRouteLowerSeen = Output(Bool())
  val sbUnhandledCurrentLayerMsgSeen = Output(Bool())
  val sbFirstFaultValid = Output(Bool())
  val sbFirstFaultOpcode = Output(UInt(5.W))
  val sbFirstFaultHeader = Output(UInt(64.W))
}

// Note: Hand-built sites need VendorTrainerCtrl.tieOff, or elaboration fails.
class LogicalPhyCtrlIO(retryW: Int, afeParams: AfeParams) extends Bundle {
  val pwrGood = Input(Bool())
  val retryTrainingAmt = Input(UInt(retryW.W))
  val localPhyParamSettings = Flipped(Valid(new PHYParamExchangeIO()))
  val linkTrainingParameters = new LinkOperationParameters()
  val swStartLinkTraining = Input(Bool())
  val remoteLinkErrorLogged = Input(Bool())
  val phyInitControl = Input(UInt(3.W))
  val resumeTrainingPending = Input(Bool())
  val trainingPaused = Output(Bool())
  val resumeTrainingDone = Output(Bool())
  val linkOpParamOverride = Input(Bool())
  val maxErrorThresholdPerLane = Input(UInt(16.W))
  val rxCompareLaneMask = Input(UInt(afeParams.mbLanes.W))
  val remoteRxTerminated = Input(Bool())
  val targetWidthCode = Input(UInt(3.W))
  val txEqEnable = Input(Bool())
  val changeInRuntimeLinkCtrlRegsDetected = Input(Bool())
  val runtimeLinkCtrlBusyBit = Input(Bool())
  val runtimeRequestForRepair = Input(Bool())
  val runtimeLaneRepairId = Input(UInt(7.W))
  val injectStuckAt = Input(Bool())
  val bridgeConfig = Input(
    new PhyBridgeConfig(afeParams.bridge, afeParams.slots)
  )
  val bridgeStatus = Output(
    new PhyBridgeStatus(afeParams.bridge, afeParams.slots)
  )
  val trainerCtrl = Input(new VendorTrainerCtrl(afeParams))
  val trainerStatus = Output(new VendorTrainerStatus(afeParams))
}

class LogicalPhyStatusIO extends Bundle {
  val ltState = Output(LTState())
  val currentState = Output(LTSMState())
  val trainingTimedout = Output(Bool())
  val clearRuntimeLinkCtrlBusyBit = Output(Bool())
  val fatalTrainingError = Output(Bool())
  val negotiatedPhyParamSettings = Valid(new PHYParamExchangeIO())
  val partnerEyeEdges = Valid(new PartnerEyeEdges)
  val linkWidth = Output(LinkWidth())
  val freqSel = Output(SpeedMode())
  val doLaneReversal = Output(Bool())
  val widthDegraded = Output(Bool())
  val rxLaneMask = Output(UInt(16.W))
  val rxLaneMapCommit = Output(Bool())
  val remoteClockPhase = Output(Bool())
  val initLinkWidth = Output(LinkWidth())
  val remoteLinkErrorRequest = Output(Bool())
  val sideband = new LogicalPhySidebandStatusIO()
}

class LogicalPhyAnalogIO(afeParams: AfeParams, sbParams: SidebandParams)
    extends Bundle {
  val mainband = new MainbandLaneIO(afeParams)
  val sidebandLink = new SidebandPhyLinkIO(sbParams.sbLinkWidth)
  val phy = new PhyBridgeAnalogIO(afeParams)
}

// separateDivClocks false means one divided clock, so every crossing is a wire.
class LogicalPhy(
    afeParams: AfeParams = new AfeParams(),
    sbParams: SidebandParams = new SidebandParams(),
    rdiParams: RdiParams = RdiParams(64, 32),
    retryW: Int = 10,
    desTimeoutCycles: Int = 512,
    queueDepths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths(),
    separateDivClocks: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val rdi = new Rdi(rdiParams)
    val ctrl = new LogicalPhyCtrlIO(retryW, afeParams)
    val status = new LogicalPhyStatusIO()
    val analog = new LogicalPhyAnalogIO(afeParams, sbParams)
    val clkRst = new MainbandClkRstIO
  })

  // ===========================================================================
  // Modules
  // ===========================================================================
  val ltsm = Module(new LinkTrainingSM(sbParams, afeParams, retryW))
  val rdiController = Module(new RDIController(sbParams))
  val phyBridge = Module(new PhyBridge(afeParams))
  val scramblerResetCrossing = Module(
    new ScramblerResetCrossing(separateDivClocks)
  )
  val logPhySidebandChannel = Module(
    new LogPhySidebandChannel(
      sbMsgWidth = sbParams.sbNodeMsgWidth,
      sbLinkWidth = sbParams.sbLinkWidth,
      rdiNcWidth = rdiParams.ncWidth,
      numCredits = sbParams.maxCrd,
      desTimeoutCycles = desTimeoutCycles,
      queueDepths = queueDepths
    )
  )

  // ===========================================================================
  // Sideband channel control
  // ===========================================================================
  val rdiStateSts = rdiController.io.rdi.plStateSts
  val previousRdiStateSts = RegNext(rdiStateSts, RDIState.reset)
  val newRdiEpoch = previousRdiStateSts === RDIState.reset &&
    rdiStateSts === RDIState.active

  // A flop, because it drives async clears in the sideband receiver.
  val clearParityContainment = RegNext(
    previousRdiStateSts === RDIState.linkError &&
      rdiStateSts === RDIState.reset,
    false.B
  )
  val flushRegAccess =
    rdiStateSts === RDIState.reset ||
      rdiStateSts === RDIState.linkReset ||
      rdiStateSts === RDIState.linkError ||
      io.rdi.lpStateReq === RDIStateReq.linkReset ||
      io.rdi.lpLinkError || ltsm.io.forceRdiLinkError || newRdiEpoch
  logPhySidebandChannel.io.linkReset := ltsm.io.sbCtrlIo.sbReset
  logPhySidebandChannel.io.flushRegAccess := flushRegAccess
  logPhySidebandChannel.io.clearParityContainment := clearParityContainment
  val sidebandParityErrorEvent =
    logPhySidebandChannel.io.layer.status.sbParityErrEvent ||
      ltsm.io.sbInitParityError

  // ===========================================================================
  // Control and status
  // ===========================================================================
  ltsm.io.pwrGood := io.ctrl.pwrGood
  ltsm.io.retryTrainingAmt := io.ctrl.retryTrainingAmt
  ltsm.io.localPhyParamSettings <> io.ctrl.localPhyParamSettings
  ltsm.io.linkTrainingParameters <> io.ctrl.linkTrainingParameters
  ltsm.io.swStartLinkTraining := io.ctrl.swStartLinkTraining
  ltsm.io.phyInitControl := io.ctrl.phyInitControl
  ltsm.io.resumeTrainingPending := io.ctrl.resumeTrainingPending
  io.ctrl.trainingPaused := ltsm.io.trainingPaused
  io.ctrl.resumeTrainingDone := ltsm.io.resumeTrainingDone
  ltsm.io.linkOpParamOverride := io.ctrl.linkOpParamOverride

  // No register carries the partner's termination, so the vendor block does.
  ltsm.io.remoteRxTerminated := io.ctrl.remoteRxTerminated
  ltsm.io.maxErrorThresholdPerLane := io.ctrl.maxErrorThresholdPerLane
  ltsm.io.changeInRuntimeLinkCtrlRegsDetected := io.ctrl.changeInRuntimeLinkCtrlRegsDetected
  ltsm.io.runtimeLinkCtrlBusyBit := io.ctrl.runtimeLinkCtrlBusyBit
  ltsm.io.runtimeRequestForRepair := io.ctrl.runtimeRequestForRepair
  ltsm.io.runtimeLaneRepairId := io.ctrl.runtimeLaneRepairId
  ltsm.io.injectStuckAt := io.ctrl.injectStuckAt

  io.status.ltState := ltsm.io.ltState
  io.status.currentState := ltsm.io.currentState
  io.status.trainingTimedout := ltsm.io.trainingTimedout
  io.status.clearRuntimeLinkCtrlBusyBit := ltsm.io.clearRuntimeLinkCtrlBusyBit
  io.status.fatalTrainingError := ltsm.io.fatalTrainingError
  io.status.negotiatedPhyParamSettings := ltsm.io.negotiatedPhyParamSettings
  io.status.partnerEyeEdges := ltsm.io.partnerEyeEdges
  io.status.freqSel := ltsm.io.phyCtrlIo.freqSel
  io.status.doLaneReversal := ltsm.io.doLaneReversal
  io.status.widthDegraded := ltsm.io.widthDegraded

  // One cycle late, so rxLaneMask holds the new map for sec 9.5.3.30.
  io.status.rxLaneMapCommit :=
    RegNext(ltsm.io.widthWrite.rxValid && !ltsm.io.widthReset, false.B)
  io.status.remoteClockPhase := ltsm.io.remoteAdvertisedClockPhase.asBool
  io.status.rxLaneMask := ltsm.io.rxLaneMask
  io.status.remoteLinkErrorRequest := rdiController.io.remoteLinkErrorRequest

  phyBridge.io.phyTrainIo <> ltsm.io.phyTrainIo
  phyBridge.io.config := io.ctrl.bridgeConfig
  io.ctrl.bridgeStatus := phyBridge.io.status
  io.analog.phy <> phyBridge.io.analog

  // ===========================================================================
  // RDI control and sideband config
  // ===========================================================================
  val phyInRecenter =
    (ltsm.io.ltState === LTState.sSBINIT) ||
      (ltsm.io.ltState === LTState.sMBINIT) ||
      (ltsm.io.ltState === LTState.sMBTRAIN) ||
      (ltsm.io.ltState === LTState.sPHYRETRAIN)

  rdiController.io.rdi.lpStateReq := io.rdi.lpStateReq
  rdiController.io.rdi.lpWakeReq := io.rdi.lpWakeReq
  rdiController.io.rdi.lpClkAck := io.rdi.lpClkAck

  // lp_stallack comes on txLclk. sec 10.3.2 waits on levels, so Sync3 is safe.
  val lpStallAckSync =
    Sync3(io.rdi.lpStallAck, false.B, !separateDivClocks)
  rdiController.io.rdi.lpStallAck := lpStallAckSync
  rdiController.io.ltsmState := ltsm.io.ltState
  rdiController.io.doRdiBringup := ltsm.io.rdi.doRdiBringup
  rdiController.io.trainingTimeout := ltsm.io.trainingTimedout
  rdiController.io.internalLinkError :=
    ltsm.io.forceRdiLinkError || sidebandParityErrorEvent
  rdiController.io.linkErrorHold := io.rdi.lpLinkError

  // sec 10.3.3.7's Reset request once software clears sec 9.5.3.35 bit 10.
  val remoteLinkErrorCleared =
    (ltsm.io.ltState === LTState.sTRAINERROR) &&
      rdiController.io.remoteOnlyLinkError && !io.ctrl.remoteLinkErrorLogged
  rdiController.io.internalReset :=
    (ltsm.io.ltState === LTState.sRESET) || remoteLinkErrorCleared
  rdiController.io.plPhyInRecenter := phyInRecenter
  rdiController.io.cfgSidebandActive := logPhySidebandChannel.io.rdi.activity

  // The wake handshake waits on the PHY clocks, so this comes from the PHY.
  rdiController.io.clocksUngatedAndStable :=
    io.analog.phy.clocksUngatedAndStable

  ltsm.io.rdi.plStateSts := rdiController.io.rdi.plStateSts
  ltsm.io.rdi.lpStateReq := io.rdi.lpStateReq

  logPhySidebandChannel.io.rdi.in.valid := io.rdi.lpCfgVld
  logPhySidebandChannel.io.rdi.in.bits := io.rdi.lpCfg
  logPhySidebandChannel.io.rdi.txCreditReturn := io.rdi.lpCfgCrd

  // pl_cfg waits for lp_clk_ack (sec 10.1.3.2 rule 9), at a packet start only.
  logPhySidebandChannel.io.rdi.txHold := !rdiController.io.cfgClockReady

  io.rdi.plCfg := logPhySidebandChannel.io.rdi.out.bits
  io.rdi.plCfgVld := logPhySidebandChannel.io.rdi.out.valid
  io.rdi.plCfgCrd := logPhySidebandChannel.io.rdi.rxCreditReturn

  // ===========================================================================
  // Sideband packet arbitration
  // ===========================================================================
  val sidebandRxQueue = Module(
    new Queue(
      new SbLinkRxWord(sbParams.sbNodeMsgWidth),
      1,
      pipe = true,
      flow = false
    )
  )
  sidebandRxQueue.io.enq <> logPhySidebandChannel.io.layer.out

  val sidebandRxReadyLtsm = WireDefault(false.B)
  val sidebandRxReadyRdi = WireDefault(false.B)
  val sidebandRxUnhandled = WireDefault(false.B)

  // In RESET only RAW words go to the LTSM, and RDI never takes a RAW word.
  val sidebandRxInReset = ltsm.io.ltState === LTState.sRESET
  val sidebandRxToLtsm =
    sidebandRxQueue.io.deq.bits.isRaw || !sidebandRxInReset

  ltsm.io.sbLaneIo.rx.valid := sidebandRxQueue.io.deq.valid && sidebandRxToLtsm
  ltsm.io.sbLaneIo.rx.bits.data := sidebandRxQueue.io.deq.bits.data
  sidebandRxReadyLtsm := ltsm.io.sbLaneIo.rx.ready && sidebandRxToLtsm

  rdiController.io.sbLaneIo.rx.valid :=
    sidebandRxQueue.io.deq.valid && !sidebandRxQueue.io.deq.bits.isRaw
  rdiController.io.sbLaneIo.rx.bits.data := sidebandRxQueue.io.deq.bits.data
  sidebandRxReadyRdi := rdiController.io.sbLaneIo.rx.ready

  sidebandRxUnhandled := sidebandRxQueue.io.deq.valid && !sidebandRxReadyLtsm && !sidebandRxReadyRdi

  // A packet left in the queue at RESET entry is dropped, and is no fault.
  val sidebandRxFault = sidebandRxUnhandled && !sidebandRxInReset

  val sbParityErrSeen = RegInit(false.B)
  val sbRxPriorityQueuesFullSeen = RegInit(false.B)
  val sbDeserializerTimedoutSeen = RegInit(false.B)

  // sec 9.5.3.35 bit 9 excludes training, so it is the sec 10.3.3.8 timeout.
  val rdiHandshakeTimedoutSeen = RegInit(false.B)
  when(rdiController.io.handshakeTimedOut) {
    rdiHandshakeTimedoutSeen := true.B
  }
  val sbInvalidRouteUpperSeen = RegInit(false.B)
  val sbInvalidRouteCurrSeen = RegInit(false.B)
  val sbInvalidRouteLowerSeen = RegInit(false.B)
  val sbUnhandledCurrentLayerMsgSeen = RegInit(false.B)
  val sbFirstFaultValid = RegInit(false.B)
  val sbFirstFaultOpcode = RegInit(0.U(5.W))
  val sbFirstFaultHeader = RegInit(0.U(64.W))

  when(
    logPhySidebandChannel.io.layer.status.sbParityErr ||
      ltsm.io.sbInitParityError
  ) {
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

  // Table 7-8 discards an unsupported Vendor Defined message, with no error.
  val rxIsVendorDefined =
    SBMsgField.isVendorDefined(sidebandRxQueue.io.deq.bits.data)
  when(sidebandRxFault && !rxIsVendorDefined) {
    sbUnhandledCurrentLayerMsgSeen := true.B
  }

  sidebandRxQueue.io.deq.ready := sidebandRxReadyLtsm || sidebandRxReadyRdi || sidebandRxUnhandled

  val sidebandTxArbiter = Module(
    new RRArbiter(UInt(sbParams.sbNodeMsgWidth.W), 2)
  )
  sidebandTxArbiter.io.in(0).valid := ltsm.io.sbLaneIo.tx.valid
  sidebandTxArbiter.io.in(0).bits := ltsm.io.sbLaneIo.tx.bits.data
  ltsm.io.sbLaneIo.tx.ready := sidebandTxArbiter.io.in(0).ready

  sidebandTxArbiter.io.in(1).valid := rdiController.io.sbLaneIo.tx.valid
  sidebandTxArbiter.io.in(1).bits := rdiController.io.sbLaneIo.tx.bits.data
  rdiController.io.sbLaneIo.tx.ready := sidebandTxArbiter.io.in(1).ready

  val sidebandTxQueue = Module(
    new Queue(UInt(sbParams.sbNodeMsgWidth.W), 1, pipe = true, flow = false)
  )

  val sbTxFrozen = ltsm.io.sbCtrlIo.freezeAcceptingPackets
  sidebandTxQueue.io.enq.valid :=
    sidebandTxArbiter.io.out.valid && !sbTxFrozen
  sidebandTxQueue.io.enq.bits := sidebandTxArbiter.io.out.bits
  sidebandTxArbiter.io.out.ready :=
    sidebandTxQueue.io.enq.ready && !sbTxFrozen
  logPhySidebandChannel.io.layer.in <> sidebandTxQueue.io.deq

  val firstFaultSeen = WireDefault(
    sidebandRxFault ||
      logPhySidebandChannel.io.layer.status.invalidRouteCurr ||
      logPhySidebandChannel.io.layer.status.invalidRouteUpper ||
      logPhySidebandChannel.io.layer.status.invalidRouteLower ||
      logPhySidebandChannel.io.layer.status.sbParityErr ||
      logPhySidebandChannel.io.layer.status.rxPriorityQueuesFull ||
      logPhySidebandChannel.io.layer.status.desTimedout
  )
  val firstFaultPacket = WireDefault(0.U(sbParams.sbNodeMsgWidth.W))
  when(sidebandRxFault) {
    firstFaultPacket := sidebandRxQueue.io.deq.bits.data
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

  io.analog.sidebandLink.out <> logPhySidebandChannel.io.link.out
  logPhySidebandChannel.io.link.in <> io.analog.sidebandLink.in

  io.status.sideband.sbParityErrSeen := sbParityErrSeen
  io.status.sideband.sbParityErrEvent := sidebandParityErrorEvent
  io.status.sideband.sbRxPriorityQueuesFullSeen := sbRxPriorityQueuesFullSeen
  io.status.sideband.sbDeserializerTimedoutSeen := sbDeserializerTimedoutSeen
  io.status.sideband.sbRdiHandshakeTimedoutSeen := rdiHandshakeTimedoutSeen
  io.status.sideband.sbInvalidRouteUpperSeen := sbInvalidRouteUpperSeen
  io.status.sideband.sbInvalidRouteCurrSeen := sbInvalidRouteCurrSeen
  io.status.sideband.sbInvalidRouteLowerSeen := sbInvalidRouteLowerSeen
  io.status.sideband.sbUnhandledCurrentLayerMsgSeen := sbUnhandledCurrentLayerMsgSeen
  io.status.sideband.sbFirstFaultValid := sbFirstFaultValid
  io.status.sideband.sbFirstFaultOpcode := sbFirstFaultOpcode
  io.status.sideband.sbFirstFaultHeader := sbFirstFaultHeader

  // ===========================================================================
  // PHY control
  // ===========================================================================
  // Only PhyBridge connects to the PHY. The LTSM decides what it forwards.
  phyBridge.io.laneCtrl := ltsm.io.mbCtrlIo
  phyBridge.io.sbTxEn := ltsm.io.sbCtrlIo.txEn
  phyBridge.io.sbRxEn := ltsm.io.sbCtrlIo.rxEn
  phyBridge.io.freqSel := ltsm.io.phyCtrlIo.freqSel
  phyBridge.io.doElectricalIdleTx := ltsm.io.phyCtrlIo.doElectricalIdleTx
  phyBridge.io.doElectricalIdleRx := ltsm.io.phyCtrlIo.doElectricalIdleRx

  // sec 9.5.3.23 bit 4 is a software setting, so the LTSM has no part in it.
  phyBridge.io.txEqEnable := io.ctrl.txEqEnable
  ltsm.io.phyCtrlIo.pllLock := phyBridge.io.pllLock

  // VendorTrainerCtrl is one way, so ready returns as jobTaken and fifoPop.
  val trainCtrl = io.ctrl.trainerCtrl
  val trainSts = io.ctrl.trainerStatus
  val bridgeTrainer = phyBridge.io.trainerCtrl

  bridgeTrainer.swJobsEn := trainCtrl.swJobsEn
  bridgeTrainer.autoFinishEn := trainCtrl.autoFinishEn
  bridgeTrainer.finishWatermark := trainCtrl.finishWatermark
  bridgeTrainer.substateEn := trainCtrl.substateEn
  bridgeTrainer.job.valid := trainCtrl.job.valid
  bridgeTrainer.job.bits := trainCtrl.job.bits
  bridgeTrainer.finish := trainCtrl.finish
  bridgeTrainer.abort := trainCtrl.abort
  bridgeTrainer.result.ready := trainCtrl.fifoPop

  trainSts.jobTaken := bridgeTrainer.job.fire
  trainSts.jobBusy := bridgeTrainer.jobBusy
  trainSts.waitingForJob := bridgeTrainer.waitingForJob
  trainSts.codeSettling := bridgeTrainer.codeSettling
  trainSts.refused := bridgeTrainer.refused
  trainSts.overflow := bridgeTrainer.overflow
  trainSts.fifoLevel := bridgeTrainer.fifoLevel
  trainSts.substate := bridgeTrainer.substate
  trainSts.residencyLeft := bridgeTrainer.residencyLeft
  trainSts.result.valid := bridgeTrainer.result.valid
  trainSts.result.bits := bridgeTrainer.result.bits

  phyBridge.io.swCodeWrite := trainCtrl.codeWrite
  phyBridge.io.eyePub := trainCtrl.eyePub
  phyBridge.io.swCalDone := trainCtrl.swCalDone
  trainSts.calPending := phyBridge.io.calPending

  // ===========================================================================
  // Mainband clock domains
  // ===========================================================================
  val isActive = ltsm.io.ltState === LTState.sACTIVE
  val mainbandActive = isActive && rdiStateSts === RDIState.active &&
    !sidebandParityErrorEvent && !ltsm.io.trainingTimedout &&
    !ltsm.io.forceRdiLinkError && !io.rdi.lpLinkError

  // sec 4.5.3.3.1 uses Strobe before RXCLKCAL and after a LINKSPEED error.
  val ltsmState = ltsm.io.currentState
  val freeRunningState =
    (ltsmState.asUInt >= LTSMState.sMBTRAIN_VALTRAINCENTER.asUInt &&
      ltsmState.asUInt <= LTSMState.sMBTRAIN_LINKSPEED.asUInt) ||
      ltsmState === LTSMState.sLINKINIT || ltsmState === LTSMState.sACTIVE

  // The partner's request sets our Tx mode, and a flop shields Sync3.
  val negotiatedContinuous = RegNext(
    ltsm.io.negotiatedPhyParamSettings.valid &&
      ltsm.io.negotiatedPhyParamSettings.bits.clockMode.asBool &&
      freeRunningState,
    false.B
  )

  // Quadrature is only for 24 and 32 GT/s (sec 9.5.3.23 bit 6).
  val operatingQuarterRate =
    ltsm.io.negotiatedPhyParamSettings.valid &&
      ltsm.io.negotiatedPhyParamSettings.bits.clockPhase.asBool &&
      ((ltsm.io.phyCtrlIo.freqSel === SpeedMode.speed24) ||
        (ltsm.io.phyCtrlIo.freqSel === SpeedMode.speed32))

  // rxDivClk has no edge in RESET or SBINIT, so these reset asynchronously.
  val txDivRst = io.clkRst.txDivRst.asAsyncReset
  val rxDivRst = io.clkRst.rxDivRst.asAsyncReset
  val mainbandTx = withClockAndReset(io.clkRst.txDivClk, txDivRst) {
    Module(new MainbandTxDomain(afeParams, rdiParams, separateDivClocks))
  }
  val mainbandRx = withClockAndReset(io.clkRst.rxDivClk, rxDivRst) {
    Module(new MainbandRxDomain(afeParams, rdiParams, separateDivClocks))
  }

  // The engines are in the divided domains, so hosts present their interfaces.
  val patternWriterCross = Module(
    new PatternWriterCrossHost(!separateDivClocks)
  )
  val patternReaderCross = Module(
    new PatternReaderCrossHost(afeParams, !separateDivClocks)
  )
  patternWriterCross.io.engine <> ltsm.io.patternWriterIo
  patternReaderCross.io.engine <> ltsm.io.patternReaderIo
  patternReaderCross.io.ltsmInReset := ltsm.io.ltState === LTState.sRESET
  mainbandTx.io.patternCross <> patternWriterCross.io.cross
  mainbandRx.io.patternCross <> patternReaderCross.io.cross

  mainbandTx.io.rdi.lpIrdy := io.rdi.lpIrdy
  mainbandTx.io.rdi.lpValid := io.rdi.lpValid
  mainbandTx.io.rdi.lpData := io.rdi.lpData

  // Levels leave from a flop, so Sync3 never sees a decode glitch.
  // One stage for all of them keeps their order.
  def divLevel[T <: Data](x: T): T =
    if (separateDivClocks) RegNext(x, 0.U.asTypeOf(x)) else x

  mainbandTx.io.ctrl.mainbandActive := divLevel(mainbandActive)
  mainbandTx.io.ctrl.stallDrain := divLevel(rdiController.io.stallDrain)
  mainbandTx.io.ctrl.isActive := divLevel(isActive)
  mainbandTx.io.ctrl.inLinkInit :=
    divLevel(ltsm.io.ltState === LTState.sLINKINIT)
  mainbandTx.io.ctrl.inPatternState := divLevel(
    (ltsm.io.ltState === LTState.sMBINIT) ||
      (ltsm.io.ltState === LTState.sMBTRAIN)
  )

  // The composer owns the pair in ACTIVE and LINKINIT, so they do not set it.
  mainbandTx.io.ctrl.txClkHeldLow := divLevel(ltsm.io.mbCtrlIo.txClkHeldLow)

  // Substates drive Pattern Mode 0, so only the override selects burst mode.
  mainbandTx.io.ctrl.patternBurst.enable := divLevel(
    io.ctrl.linkOpParamOverride &&
      io.ctrl.linkTrainingParameters.patternMode.asBool
  )
  mainbandTx.io.ctrl.patternBurst.burstCount :=
    io.ctrl.linkTrainingParameters.burstCount
  mainbandTx.io.ctrl.patternBurst.idleCount :=
    io.ctrl.linkTrainingParameters.idleCount
  mainbandTx.io.ctrl.patternBurst.iterationCount :=
    io.ctrl.linkTrainingParameters.iterationCount

  // One module holds the Table 4-9 codes, so no reader sees a stale value.
  val mainbandWidth = Module(new MainbandWidth)
  mainbandWidth.io.resetWidth := ltsm.io.widthReset
  mainbandWidth.io.initCode := io.ctrl.targetWidthCode
  mainbandWidth.io.hw := ltsm.io.widthWrite
  ltsm.io.initWidthCode := io.ctrl.targetWidthCode
  ltsm.io.currLocalTxFunctionalLanes := mainbandWidth.io.txCode
  ltsm.io.currRemoteTxFunctionalLanes := mainbandWidth.io.rxCode

  // On UCIe-S x8, 011b means eight Lanes, so only the datapath narrows it.
  mainbandTx.io.ctrl.localTxFunctionalLanes := divLevel(
    PatternLaneMap.datapathCode(
      ltsm.io.localTxFunctionalLanes,
      ltsm.io.negotiatedBy8
    )
  )
  mainbandTx.io.ctrl.laneReversal := ltsm.io.doLaneReversal
  mainbandTx.io.ctrl.negotiatedBy8 := divLevel(ltsm.io.negotiatedBy8)

  // The word select. The driver enable reaches the analog through PhyBridge.
  mainbandTx.io.ctrl.txTrackPattern :=
    divLevel(ltsm.io.mbCtrlIo.txTrackPattern)

  // An idle driven Track level, which alternates in ACTIVE (sec 4.6).
  mainbandTx.io.ctrl.txTrackLevel := divLevel(ltsm.io.mbCtrlIo.txTrackLevel)
  mainbandTx.io.ctrl.stuckAtLaneMask := divLevel(ltsm.io.stuckAtLaneMask)

  // sec 4.1.3 never gates for free running or recal, so one term covers both.
  mainbandTx.io.ctrl.continuous :=
    divLevel(negotiatedContinuous || ltsm.io.recalInProgress)
  mainbandTx.io.ctrl.quarterRate := divLevel(operatingQuarterRate)
  mainbandTx.io.ctrl.rxClkCalSendFwClkPattern :=
    ltsm.io.rxClkCalSendFwClkPattern
  mainbandTx.io.ctrl.rxClkCalSendTrkPattern := ltsm.io.rxClkCalSendTrkPattern
  mainbandTx.io.ctrl.scramblerResetLfsr :=
    scramblerResetCrossing.io.txResetLfsr

  io.analog.mainband.tx <> mainbandTx.io.analogTx

  mainbandRx.io.ctrl.mainbandActive :=
    (if (separateDivClocks) RegNext(mainbandActive, false.B)
     else mainbandActive)

  // Read the partner's 011b the same way, as eight Lanes on a x8 Link.
  mainbandRx.io.ctrl.localRxFunctionalLanes := divLevel(
    PatternLaneMap.datapathCode(
      ltsm.io.remoteTxFunctionalLanes,
      ltsm.io.negotiatedBy8
    )
  )

  mainbandRx.io.ctrl.laneMaskRx := io.ctrl.rxCompareLaneMask

  // rxDivClk can park, so hold the LINKINIT reset (sec 4.5.3.5) as a level.
  mainbandRx.io.ctrl.linkInitReset :=
    (if (separateDivClocks)
       RegNext(ltsm.io.ltState === LTState.sLINKINIT, false.B)
     else ltsm.io.scramblerReset)
  mainbandRx.io.analogRx <> io.analog.mainband.rx

  // Sticky in rxDivClk, so a level synchroniser is enough.
  val framingErrorSync =
    Sync3(mainbandRx.io.ctrl.validFramingError, false.B, !separateDivClocks)

  // Retrain on the rising edge (sec 4.5.3.7). LINKINIT re-arms it.
  val framingErrorPrev = RegNext(framingErrorSync, false.B)
  rdiController.io.internalRetrainRequest :=
    framingErrorSync && !framingErrorPrev

  scramblerResetCrossing.io.pulse := ltsm.io.scramblerReset
  scramblerResetCrossing.io.txDivClk := io.clkRst.txDivClk
  scramblerResetCrossing.io.txDivRst := io.clkRst.txDivRst

  // ===========================================================================
  // RDI outputs
  // ===========================================================================
  // One source, so pl_lnk_cfg and the datapath agree on the width.
  val negotiatedBy8 = ltsm.io.negotiatedBy8

  // The directions have separate codes, but their widths must match.
  val linkWidth =
    PatternLaneMap.linkWidth(ltsm.io.localTxFunctionalLanes, negotiatedBy8)
  val rxLinkWidth =
    PatternLaneMap.linkWidth(ltsm.io.remoteTxFunctionalLanes, negotiatedBy8)
  io.status.linkWidth := linkWidth
  io.status.initLinkWidth :=
    PatternLaneMap.linkWidth(ltsm.io.linkInitWidthCode, negotiatedBy8)

  // pl_trainerror holds until RDI goes from LinkError to Reset (Table 10-1).
  val phyFatalReg = RegInit(false.B)
  val phyFatalSawLinkErrorReg = RegInit(false.B)
  when(sidebandParityErrorEvent || rdiController.io.liveLinkTimeout) {
    phyFatalReg := true.B
    phyFatalSawLinkErrorReg := false.B
  }.elsewhen(phyFatalReg && rdiStateSts === RDIState.linkError) {
    phyFatalSawLinkErrorReg := true.B
  }.elsewhen(
    phyFatalReg && phyFatalSawLinkErrorReg &&
      rdiStateSts === RDIState.reset
  ) {
    phyFatalReg := false.B
    phyFatalSawLinkErrorReg := false.B
  }

  // Exported, so the SoC can attach its own crossing.
  io.rdi.txLclk := io.clkRst.txDivClk
  io.rdi.rxLclk := io.clkRst.rxDivClk

  // In the divided build pl_trdy trails pl_state_sts, against sec 10.1.
  // It is safe, because a stall sets lp_valid low first.
  io.rdi.plTrdy := mainbandTx.io.rdi.plTrdy

  io.rdi.plValid := mainbandRx.io.rdi.plValid
  io.rdi.plData := mainbandRx.io.rdi.plData
  io.rdi.plStateSts := rdiStateSts
  io.rdi.plInbandPres := rdiController.io.rdi.plInbandPres

  // The pin is on txLclk, so it comes from a txDivClk flop.
  io.rdi.plStallReq :=
    withClockAndReset(io.clkRst.txDivClk, txDivRst) {
      Sync3(rdiController.io.rdi.plStallReq, false.B, !separateDivClocks)
    }
  io.rdi.plClkReq := rdiController.io.rdi.plClkReq
  io.rdi.plWakeAck := rdiController.io.rdi.plWakeAck
  io.rdi.plSpeedmode := ltsm.io.phyCtrlIo.freqSel
  io.rdi.plLnkCfg := linkWidth

  // Note: A new pl_nferror source must exit clock gating before its pulse.
  io.rdi.plNfError := false.B
  io.rdi.plTrainError := ltsm.io.fatalTrainingError || phyFatalReg
  io.rdi.plPhyInRecenter := phyInRecenter
  io.rdi.plError := mainbandRx.io.rdi.plError

  // Each PHY fault retrains or goes to LinkError, so none is correctable.
  io.rdi.plCError := false.B

  // Table 10-1 changes this only in RDI Reset, and the LTSM order ensures it.
  io.rdi.plMaxSpeedmode :=
    ltsm.io.negotiatedPhyParamSettings.valid &&
      (ltsm.io.negotiatedPhyParamSettings.bits.maxDataRate >
        SpeedMode.speed32.asUInt)

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      assert(
        PopCount(Seq(sidebandRxReadyLtsm, sidebandRxReadyRdi)) <= 1.U,
        "FATAL: Multiple LogicalPhy sideband consumers asserted RX ready in the same cycle"
      )
    }
    block(Verification.Cover) {
      cover(sidebandRxUnhandled)
    }
  }

  block(Verification) {
    block(Verification.Assert) {
      when(mainbandActive) {
        assert(
          linkWidth === rxLinkWidth,
          "FATAL: TX and RX Lane maps decode to different Link widths"
        )
      }
    }
  }
}
