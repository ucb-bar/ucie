/*
  The Link Training State Machine (sec 4.5), for Standard Package Raw Format.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.rdi._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.common._
import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

class LinkTrainingSM(
    sbParams: SidebandParams,
    afeParams: AfeParams,
    retryW: Int
) extends Module {
  val io = IO(new Bundle {
    val pwrGood = Input(Bool())
    val retryTrainingAmt = Input(UInt(retryW.W))
    val localPhyParamSettings = Flipped(Valid(new PHYParamExchangeIO()))
    val linkTrainingParameters = new LinkOperationParameters()
    val swStartLinkTraining = Input(Bool())
    val phyInitControl = Input(UInt(3.W))
    val resumeTrainingPending = Input(Bool())
    val maxErrorThresholdPerLane = Input(UInt(16.W))
    val changeInRuntimeLinkCtrlRegsDetected = Input(Bool())
    val runtimeLinkCtrlBusyBit = Input(Bool())
    val runtimeRequestForRepair = Input(Bool())
    val runtimeLaneRepairId = Input(UInt(7.W))
    val injectStuckAt = Input(Bool())
    val linkOpParamOverride = Input(Bool())
    val remoteRxTerminated = Input(Bool())

    val ltState = Output(LTState())
    val currentState = Output(LTSMState())
    val trainingPaused = Output(Bool())
    val resumeTrainingDone = Output(Bool())
    val trainingTimedout = Output(Bool())
    val clearRuntimeLinkCtrlBusyBit = Output(Bool())
    val fatalTrainingError = Output(Bool())
    val forceRdiLinkError = Output(Bool())
    val doLaneReversal = Output(Bool())
    val negotiatedBy8 = Output(Bool())
    val stuckAtLaneMask = Output(UInt(afeParams.mbLanes.W))
    val currLocalTxFunctionalLanes = Input(UInt(3.W))
    val currRemoteTxFunctionalLanes = Input(UInt(3.W))
    val widthWrite = Output(new MainbandWidthWrite)
    val widthReset = Output(Bool())
    val initWidthCode = Input(UInt(3.W))
    val localTxFunctionalLanes = Output(UInt(3.W))
    val remoteTxFunctionalLanes = Output(UInt(3.W))
    val rxLaneMask = Output(UInt(16.W))
    val widthDegraded = Output(Bool())
    val linkInitWidthCode = Output(UInt(3.W))
    val negotiatedPhyParamSettings = Valid(new PHYParamExchangeIO())
    val remoteAdvertisedClockPhase = Output(UInt(1.W))
    val partnerEyeEdges = Valid(new PartnerEyeEdges)
    val rxClkCalSendFwClkPattern = Output(Bool())
    val rxClkCalSendTrkPattern = Output(Bool())
    val scramblerReset = Output(Bool())
    val recalInProgress = Output(Bool())
    val sbInitParityError = Output(Bool())

    val rdi = new Bundle {
      val plStateSts = Input(RDIState())
      val lpStateReq = Input(RDIStateReq())
      val doRdiBringup = Output(Bool())
    }

    val sbCtrlIo = new SidebandCtrlIO()
    val mbCtrlIo = new MainbandLaneCtrlIO(afeParams)
    val phyCtrlIo = new PhyCtrlIO()

    val phyTrainIo = Flipped(new PhyTrainIO(afeParams))

    val sbLaneIo = new SidebandLaneIO(sbParams)

    val patternWriterIo = Flipped(new PatternWriterIO())
    val patternReaderIo = Flipped(new PatternReaderIO(afeParams.mbLanes))
  })

  val mbSerializerRatio = afeParams.mbSerializerRatio
  val retryAmtW = retryW // TODO: Put retryW into an object.

  val currentState = RegInit(LTState.sRESET)
  val nextState = WireInit(currentState)
  currentState := nextState

  val currentStateSpecific = WireDefault(LTSMState.sRESET)
  io.currentState := currentStateSpecific
  io.ltState := currentState

  // ===========================================================================
  // Timeouts
  // ===========================================================================
  val timeoutCycles = sbParams.trainingTimeoutCycles

  // The counter reaches timeoutCycles, which log2Ceil misses at a power of two.
  val timeoutWidth = log2Ceil(timeoutCycles + 1)
  val timeoutCounter = RegInit(0.U(timeoutWidth.W))
  val timeoutMaxCycles = timeoutCycles.U
  val timeoutCntEn = Wire(Bool())
  val timeoutCntReset = Wire(Bool())
  val trainingTimedout = RegInit(
    false.B
  )
  val resetMinWait = RegInit(false.B)
  val resetMinWaitMaxCycles = sbParams.resetMinResidencyCycles.U
  val substateTransitioning = Wire(Bool())

  // Only the sub-state machine that owns the exchange restarts it on a Stall.
  val sbStallReceived = Wire(Bool())
  val trainErrorHandshakeTimedout = WireInit(false.B)

  // sec 9.5.3.25 disables the state timeouts while training is paused.
  val trainingPaused = Wire(Bool())

  val triggerTrainErrorReq = WireInit(
    false.B
  )
  val waitTrainErrorResp = RegInit(
    false.B
  )

  // This counter also times the 4 ms RESET and the TRAINERROR handshake.
  timeoutCntEn := !trainingPaused &&
    (((currentState =/= LTState.sRESET) &&
      (currentState =/= LTState.sACTIVE) &&
      (currentState =/= LTState.sL1_L2) &&
      (currentState =/= LTState.sTRAINERROR) &&
      (timeoutCounter =/= timeoutMaxCycles)) ||
      (currentState === LTState.sRESET && !resetMinWait) ||
      (waitTrainErrorResp && (timeoutCounter =/= timeoutMaxCycles)))

  // sec 4.5 times each sub-state separately, so a sub-state change restarts it.
  substateTransitioning := false.B
  sbStallReceived := false.B
  timeoutCntReset := (nextState =/= currentState) || substateTransitioning ||
    triggerTrainErrorReq || sbStallReceived

  trainErrorHandshakeTimedout := waitTrainErrorResp && (timeoutCounter === timeoutMaxCycles)

  // Latch the timeout, because the counter restarts.
  // Clear it in TRAINERROR, so RDI can leave LinkError (sec 10.3.3.7).
  when(currentState === LTState.sTRAINERROR) {
    trainingTimedout := false.B
  }.elsewhen(timeoutCounter === timeoutMaxCycles) {
    trainingTimedout := true.B
  }

  when(timeoutCntReset) {
    timeoutCounter := 0.U
  }.otherwise {
    when(timeoutCntEn) {
      timeoutCounter := timeoutCounter + 1.U
    }
  }

  val enteringReset =
    (currentState =/= LTState.sRESET) && (nextState === LTState.sRESET)

  when(
    (timeoutCounter === resetMinWaitMaxCycles) && (currentState === LTState.sRESET)
  ) {
    resetMinWait := true.B
  }.elsewhen(enteringReset) {
    resetMinWait := false.B
  }

  // ===========================================================================
  // Registers and defaults
  // ===========================================================================
  val prevTrigger = RegInit(false.B)
  val trainingRetryCounter = RegInit(0.U(retryAmtW.W))
  val autoRetrain = Wire(Bool())
  val retryCounterEn = Wire(Bool())
  val retryAmtMax = RegInit(0.U(retryAmtW.W))
  val trainingEpisodeActive = RegInit(false.B)
  val currentEpisodeIsLocal = RegInit(false.B)
  val fatalTrainingError = RegInit(false.B)
  val fatalTrainingSawLinkError = RegInit(false.B)
  val forceRdiLinkError = RegInit(false.B)

  val sbRxTxMode = WireDefault(SBRxTxMode.PACKET)

  io.trainingTimedout := trainingTimedout
  io.fatalTrainingError := fatalTrainingError
  io.forceRdiLinkError := forceRdiLinkError

  io.sbCtrlIo.txEn := true.B
  io.sbCtrlIo.rxEn := true.B
  io.sbCtrlIo.rxTxMode := sbRxTxMode

  // RESET keeps Sideband Receivers on (sec 4.5.3.1), so reset only on entry.
  io.sbCtrlIo.sbReset := RegNext(enteringReset, false.B)

  // sec 4.5.3.1 holds the Sideband Transmitters low in RESET.
  io.sbCtrlIo.freezeAcceptingPackets := currentState === LTState.sRESET

  // RESET returns to 4 GT/s, the slowest data rate (sec 4.5.3.1).
  val freqSel = RegInit(SpeedMode.speed4)
  val doFreqChange = WireInit(false.B)
  val newFreqSel = WireInit(SpeedMode.speed4)

  when(doFreqChange) {
    freqSel := newFreqSel
  }.elsewhen(currentState === LTState.sRESET) {
    freqSel := SpeedMode.speed4
  }
  io.phyCtrlIo.freqSel := freqSel
  io.scramblerReset := false.B

  io.rdi.doRdiBringup := false.B

  // ===========================================================================
  // Training triggers
  // ===========================================================================
  // Two consecutive SBINIT pattern iterations are a trigger (sec 4.5).
  val sbInitPatternCounter = RegInit(0.U(2.W))
  val remoteTriggerTraining = Wire(Bool())
  val sbInitClkPattern =
    PatternDefs.sbInitClock.U(64.W)
  remoteTriggerTraining := sbInitPatternCounter === 2.U

  val rdiTriggerTrainingWire = Wire(Bool())
  val rdiTriggerTraining = RegInit(false.B)
  val prevRdiStateReq = RegInit(RDIStateReq.nop)
  prevRdiStateReq := io.rdi.lpStateReq
  rdiTriggerTrainingWire := (io.rdi.plStateSts === RDIState.reset) &&
    (prevRdiStateReq === RDIStateReq.nop) &&
    (io.rdi.lpStateReq === RDIStateReq.active)

  // The Adapter may return to NOP after its trigger (sec 10.3.3.8), so hold it.
  when(
    (currentState =/= LTState.sRESET) && (currentState =/= LTState.sTRAINERROR)
  ) {
    rdiTriggerTraining := false.B
  }.elsewhen(rdiTriggerTrainingWire) {
    rdiTriggerTraining := true.B
  }

  // Start (sec 9.5.1.5) stays set through training, so only its edge counts.
  val swTriggerTraining = Wire(Bool())
  swTriggerTraining := io.swStartLinkTraining

  val triggerTraining = Wire(Bool())
  triggerTraining := swTriggerTraining || rdiTriggerTraining || remoteTriggerTraining
  val freshTrainingTrigger = Wire(Bool())
  freshTrainingTrigger := triggerTraining && !prevTrigger

  // The history falls only outside RESET, so a held trigger counts again there.
  prevTrigger := triggerTraining &&
    (prevTrigger || (currentState === LTState.sRESET))

  // The sec 9.5.1.5 link-up rule is for Start only, so it has its own edge.
  val prevSwTrigger = RegInit(false.B)
  prevSwTrigger := swTriggerTraining
  val freshSwStartTrigger = swTriggerTraining && !prevSwTrigger

  // A Start on an up Link means full training through Link Down (sec 9.5.1.5).
  val rdiUp = (io.rdi.plStateSts === RDIState.active) ||
    (io.rdi.plStateSts === RDIState.activePmNak) ||
    (io.rdi.plStateSts === RDIState.retrain)
  val liveStart = freshSwStartTrigger && rdiUp &&
    (currentState =/= LTState.sRESET) &&
    (currentState =/= LTState.sSBINIT) &&
    (currentState =/= LTState.sTRAINERROR)
  val liveStartPending = RegInit(false.B)
  when(
    (currentState === LTState.sRESET) ||
      (currentState === LTState.sTRAINERROR)
  ) {
    liveStartPending := false.B
  }.elsewhen(liveStart) {
    liveStartPending := true.B
  }

  // ===========================================================================
  // PHY parameters
  // ===========================================================================
  val localModuleId = RegInit(0.U(2.W))
  val localVoltageSwing = RegInit(0.U(5.W))
  val localMaxDataRate = RegInit(0.U(4.W))
  val localClockMode = RegInit(0.U(1.W))
  val localClockPhase = RegInit(0.U(1.W))
  val localUcieSx8 = RegInit(0.U(1.W))
  val localSbFeatExt = RegInit(0.U(1.W))
  val localTxAdjRuntime = RegInit(0.U(1.W))
  val localSettingsValid = RegInit(false.B)

  val remoteModuleId = RegInit(0.U(2.W))
  val negotiatedVoltageSwing = RegInit(0.U(5.W))
  val negotiatedMaxDataRate = RegInit(0.U(4.W))
  val negotiatedClockMode = RegInit(0.U(1.W))
  val negotiatedClockPhase = RegInit(0.U(1.W))
  val remoteAdvertisedClockPhase = RegInit(0.U(1.W))
  val negotiatedUcieSx8 = RegInit(0.U(1.W))
  val negotiatedSbFeatExt = RegInit(0.U(1.W))
  val negotiatedTxAdjRuntime = RegInit(0.U(1.W))
  val negotiatedSettingsValid = RegInit(false.B)

  when(io.localPhyParamSettings.valid) {
    localModuleId := io.localPhyParamSettings.bits.moduleId
    localVoltageSwing := io.localPhyParamSettings.bits.voltageSwing
    localMaxDataRate := io.localPhyParamSettings.bits.maxDataRate
    localClockMode := io.localPhyParamSettings.bits.clockMode
    localClockPhase := io.localPhyParamSettings.bits.clockPhase
    localUcieSx8 := io.localPhyParamSettings.bits.ucieSx8
    localSbFeatExt := io.localPhyParamSettings.bits.sbFeatExt
    localTxAdjRuntime := io.localPhyParamSettings.bits.txAdjRuntime
    localSettingsValid := true.B
  }

  when(currentState === LTState.sRESET) {
    localSettingsValid := false.B
  }

  io.negotiatedPhyParamSettings.bits.moduleId := remoteModuleId
  io.negotiatedPhyParamSettings.bits.voltageSwing := negotiatedVoltageSwing
  io.negotiatedPhyParamSettings.bits.maxDataRate := negotiatedMaxDataRate
  io.negotiatedPhyParamSettings.bits.clockMode := negotiatedClockMode
  io.negotiatedPhyParamSettings.bits.clockPhase := negotiatedClockPhase
  io.remoteAdvertisedClockPhase := remoteAdvertisedClockPhase
  io.negotiatedPhyParamSettings.bits.ucieSx8 := negotiatedUcieSx8

  // Narrow only after PARAM, or the MBINIT patterns lose Lanes 8 to 15.
  val negotiatedBy8 = negotiatedSettingsValid && negotiatedUcieSx8.asBool
  io.negotiatedBy8 := negotiatedBy8
  io.negotiatedPhyParamSettings.bits.sbFeatExt := negotiatedSbFeatExt
  io.negotiatedPhyParamSettings.bits.txAdjRuntime := negotiatedTxAdjRuntime
  io.negotiatedPhyParamSettings.valid := negotiatedSettingsValid

  // ===========================================================================
  // Sub-state machines
  // ===========================================================================
  // A flop from nextState adds no delay and keeps glitches off the async reset.
  val ltsmInReset = RegInit(true.B)
  ltsmInReset := nextState === LTState.sRESET

  val subFsmModuleReset = (reset.asBool || ltsmInReset).asAsyncReset

  val sbInitSM = withReset(subFsmModuleReset) {
    Module(new SBInitSM(sbParams, timeoutCycles))
  }

  sbInitSM.io.fsmCtrl.start := false.B
  io.sbInitParityError := sbInitSM.io.parityError

  sbInitSM.io.requesterSbLaneIo.rx.valid := false.B
  sbInitSM.io.requesterSbLaneIo.rx.bits := 0.U.asTypeOf(
    chiselTypeOf(sbInitSM.io.requesterSbLaneIo.rx.bits)
  )
  sbInitSM.io.requesterSbLaneIo.tx.ready := false.B

  sbInitSM.io.responderSbLaneIo.rx.valid := false.B
  sbInitSM.io.responderSbLaneIo.rx.bits := 0.U.asTypeOf(
    chiselTypeOf(sbInitSM.io.responderSbLaneIo.rx.bits)
  )
  sbInitSM.io.responderSbLaneIo.tx.ready := false.B

  val mbInitSM = withReset(subFsmModuleReset) {
    Module(new MBInitSM(afeParams, sbParams))
  }

  // sec 9.5.3.25 [2:0]. 011b and 100b need a Test and Compliance block.
  val pauseAtMbInitParam = io.phyInitControl === 1.U
  val pauseAtMbTrainValVref = io.phyInitControl === 2.U

  // One latch covers the episode, as sec 9.5.3.25 bit 5 resumes until ACTIVE.
  val trainingResumed = RegInit(false.B)
  val resumeFromRegister = io.resumeTrainingPending && trainingPaused
  io.trainingPaused := trainingPaused
  io.resumeTrainingDone := resumeFromRegister

  mbInitSM.io.fsmCtrl.start := false.B
  mbInitSM.io.pauseAfterParam := pauseAtMbInitParam && !trainingResumed

  // MBINIT.CAL is implementation specific (sec 4.5.3.3.2), so software ends it.
  mbInitSM.io.mbInitCalDone := io.phyTrainIo.mbInitCalDone
  io.phyTrainIo.mbInitCalStart := mbInitSM.io.mbInitCalStart

  mbInitSM.io.localPhySettings.bits.moduleId := localModuleId
  mbInitSM.io.initWidthCode := io.initWidthCode
  mbInitSM.io.localPhySettings.bits.voltageSwing := localVoltageSwing
  mbInitSM.io.localPhySettings.bits.maxDataRate := localMaxDataRate
  mbInitSM.io.localPhySettings.bits.clockMode := localClockMode
  mbInitSM.io.localPhySettings.bits.clockPhase := localClockPhase
  mbInitSM.io.localPhySettings.bits.ucieSx8 := localUcieSx8
  mbInitSM.io.localPhySettings.bits.sbFeatExt := localSbFeatExt
  mbInitSM.io.localPhySettings.bits.txAdjRuntime := localTxAdjRuntime
  mbInitSM.io.localPhySettings.valid := localSettingsValid

  when(mbInitSM.io.negotiatedPhySettings.valid) {
    remoteModuleId := mbInitSM.io.negotiatedPhySettings.bits.moduleId
    negotiatedVoltageSwing := mbInitSM.io.negotiatedPhySettings.bits.voltageSwing
    negotiatedMaxDataRate := mbInitSM.io.negotiatedPhySettings.bits.maxDataRate
    negotiatedClockMode := mbInitSM.io.negotiatedPhySettings.bits.clockMode
    negotiatedClockPhase := mbInitSM.io.negotiatedPhySettings.bits.clockPhase
    remoteAdvertisedClockPhase := mbInitSM.io.remoteAdvertisedClockPhase
    negotiatedUcieSx8 := mbInitSM.io.negotiatedPhySettings.bits.ucieSx8
    negotiatedSbFeatExt := mbInitSM.io.negotiatedPhySettings.bits.sbFeatExt
    negotiatedTxAdjRuntime := mbInitSM.io.negotiatedPhySettings.bits.txAdjRuntime
    negotiatedSettingsValid := true.B
  }

  when(currentState === LTState.sRESET) {
    negotiatedSettingsValid := false.B
  }

  mbInitSM.io.requesterSbLaneIo.rx.valid := false.B
  mbInitSM.io.requesterSbLaneIo.rx.bits :=
    0.U.asTypeOf(chiselTypeOf(mbInitSM.io.requesterSbLaneIo.rx.bits))
  mbInitSM.io.requesterSbLaneIo.tx.ready := false.B

  mbInitSM.io.responderSbLaneIo.rx.valid := false.B
  mbInitSM.io.responderSbLaneIo.rx.bits :=
    0.U.asTypeOf(chiselTypeOf(mbInitSM.io.responderSbLaneIo.rx.bits))
  mbInitSM.io.responderSbLaneIo.tx.ready := false.B

  mbInitSM.io.txPtTestReqInterfaceIo.done := false.B
  mbInitSM.io.txPtTestReqInterfaceIo.ptTestResults.valid := false.B
  mbInitSM.io.txPtTestReqInterfaceIo.ptTestResults.bits :=
    0.U.asTypeOf(
      chiselTypeOf(mbInitSM.io.txPtTestReqInterfaceIo.ptTestResults.bits)
    )

  mbInitSM.io.txPtTestRespInterfaceIo.done := false.B

  val mbTrainSM = withReset(subFsmModuleReset) {
    Module(new MBTrainSM(afeParams, sbParams))
  }

  mbTrainSM.io.negotiatedMaxDataRate := negotiatedMaxDataRate(2, 0).asTypeOf(
    SpeedMode()
  )
  mbTrainSM.io.pllLock := io.phyCtrlIo.pllLock
  mbTrainSM.io.interpretBy8Lane := negotiatedUcieSx8
  mbTrainSM.io.maxErrorThresholdPerLane := io.maxErrorThresholdPerLane
  mbTrainSM.io.changeInRuntimeLinkCtrlRegs := io.changeInRuntimeLinkCtrlRegsDetected

  newFreqSel := mbTrainSM.io.freqSel.bits
  doFreqChange := mbTrainSM.io.freqSel.valid

  io.rxClkCalSendFwClkPattern := mbTrainSM.io.rxClkCalSendFwClkPattern
  io.rxClkCalSendTrkPattern := mbTrainSM.io.rxClkCalSendTrkPattern

  io.phyCtrlIo.doElectricalIdleRx := mbTrainSM.io.doElectricalIdleRx
  io.phyCtrlIo.doElectricalIdleTx := mbTrainSM.io.doElectricalIdleTx

  mbTrainSM.io.fsmCtrl.start := false.B
  mbTrainSM.io.pauseAfterValVrefStart :=
    pauseAtMbTrainValVref && !trainingResumed

  trainingPaused := mbInitSM.io.pausedAtParam ||
    mbTrainSM.io.pausedAtValVrefStart
  val resumeFromPartner = mbInitSM.io.pauseSidebandResume ||
    mbTrainSM.io.pauseSidebandResume
  when(currentState === LTState.sRESET) {
    trainingResumed := false.B
  }.elsewhen(resumeFromRegister || resumeFromPartner) {
    trainingResumed := true.B
  }

  mbTrainSM.io.requesterSbLaneIo.rx.valid := false.B
  mbTrainSM.io.requesterSbLaneIo.rx.bits :=
    0.U.asTypeOf(chiselTypeOf(mbTrainSM.io.requesterSbLaneIo.rx.bits))
  mbTrainSM.io.requesterSbLaneIo.tx.ready := false.B

  mbTrainSM.io.responderSbLaneIo.rx.valid := false.B
  mbTrainSM.io.responderSbLaneIo.rx.bits :=
    0.U.asTypeOf(chiselTypeOf(mbTrainSM.io.responderSbLaneIo.rx.bits))
  mbTrainSM.io.responderSbLaneIo.tx.ready := false.B

  mbTrainSM.io.txPtTestReqIntfIo.done := false.B
  mbTrainSM.io.txPtTestReqIntfIo.ptTestResults.valid := false.B
  mbTrainSM.io.txPtTestReqIntfIo.ptTestResults.bits :=
    0.U.asTypeOf(
      chiselTypeOf(mbTrainSM.io.txPtTestReqIntfIo.ptTestResults.bits)
    )

  mbTrainSM.io.txPtTestRespIntfIo.done := false.B

  mbTrainSM.io.txEyeSweepReqIntfIo.done := false.B
  mbTrainSM.io.txEyeSweepReqIntfIo.eyeSweepTestResults.valid := false.B
  mbTrainSM.io.txEyeSweepReqIntfIo.eyeSweepTestResults.bits :=
    0.U.asTypeOf(
      chiselTypeOf(mbTrainSM.io.txEyeSweepReqIntfIo.eyeSweepTestResults.bits)
    )

  mbTrainSM.io.txEyeSweepRespIntfIo.done := false.B

  mbTrainSM.io.rxPtTestReqIntfIo.done := false.B
  mbTrainSM.io.rxPtTestReqIntfIo.ptTestResults.valid := false.B
  mbTrainSM.io.rxPtTestReqIntfIo.ptTestResults.bits :=
    0.U.asTypeOf(
      chiselTypeOf(mbTrainSM.io.rxPtTestReqIntfIo.ptTestResults.bits)
    )

  mbTrainSM.io.rxPtTestRespIntfIo.done := false.B

  mbTrainSM.io.rxEyeSweepReqIntfIo.done := false.B
  mbTrainSM.io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid := false.B
  mbTrainSM.io.rxEyeSweepReqIntfIo.eyeSweepTestResults.bits :=
    0.U.asTypeOf(
      chiselTypeOf(mbTrainSM.io.rxEyeSweepReqIntfIo.eyeSweepTestResults.bits)
    )

  mbTrainSM.io.rxEyeSweepRespIntfIo.done := false.B
  mbTrainSM.io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.valid := false.B
  mbTrainSM.io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.bits :=
    0.U.asTypeOf(
      chiselTypeOf(
        mbTrainSM.io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.bits
      )
    )

  mbTrainSM.io.trainingCtrl.action <> io.phyTrainIo.action
  io.phyTrainIo.result <> mbTrainSM.io.trainingCtrl.result
  io.phyTrainIo.remoteResult <> mbTrainSM.io.trainingCtrl.remoteResult
  io.phyTrainIo.txSelfCalStart :=
    mbTrainSM.io.currentState === MBTrainState.sTXSELFCAL
  io.phyTrainIo.rxClkCalStart := mbTrainSM.io.rxClkCalStart

  // ===========================================================================
  // Training operations
  // ===========================================================================
  val txPtReqIntf = Wire(new TxInitPtTestRequesterInterfaceIO(afeParams))
  val txPtRespIntf = Wire(new TxInitPtTestResponderInterfaceIO())

  txPtReqIntf.start := false.B
  txPtReqIntf.patternType := DontCare
  txPtReqIntf.linkTrainingParameters := DontCare

  txPtRespIntf.start := false.B
  txPtRespIntf.patternType := DontCare

  // Reset with the sub-state machines, so no engine survives a TRAINERROR.
  val txPtTestRequester = withReset(subFsmModuleReset) {
    Module(new TxD2CPointTestRequester(afeParams, sbParams))
  }
  val txPtTestResponder = withReset(subFsmModuleReset) {
    Module(new TxD2CPointTestResponder(afeParams, sbParams))
  }

  txPtTestRequester.io.start := txPtReqIntf.start
  txPtTestRequester.io.patternType := txPtReqIntf.patternType
  txPtTestRequester.io.linkTrainingParameters := txPtReqIntf.linkTrainingParameters

  txPtReqIntf.done := txPtTestRequester.io.done
  txPtReqIntf.ptTestResults := txPtTestRequester.io.txInitPtTestResults

  txPtTestRequester.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits
  txPtTestRequester.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  txPtTestResponder.io.start := txPtRespIntf.start
  txPtTestResponder.io.patternType := txPtRespIntf.patternType

  txPtRespIntf.done := txPtTestResponder.io.done

  txPtTestResponder.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits
  txPtTestResponder.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  val txEwReqIntf = Wire(new TxInitEyeWidthSweepRequesterInterfaceIO(afeParams))
  val txEwRespIntf = Wire(new TxInitEyeWidthSweepResponderInterfaceIO())

  txEwReqIntf.start := false.B
  txEwReqIntf.patternType := DontCare
  txEwReqIntf.linkTrainingParameters := DontCare

  txEwRespIntf.start := false.B
  txEwRespIntf.patternType := DontCare

  val txEwSweepRequester = withReset(subFsmModuleReset) {
    Module(new TxD2CEyeWidthSweepRequester(afeParams, sbParams))
  }
  val txEwSweepResponder = withReset(subFsmModuleReset) {
    Module(new TxD2CEyeWidthSweepResponder(afeParams, sbParams))
  }

  txEwSweepRequester.io.start := txEwReqIntf.start
  txEwSweepRequester.io.patternType := txEwReqIntf.patternType
  txEwSweepRequester.io.linkTrainingParameters := txEwReqIntf.linkTrainingParameters

  txEwReqIntf.done := txEwSweepRequester.io.done
  txEwReqIntf.eyeSweepTestResults := txEwSweepRequester.io.txInitEyeWidthSweepResults

  txEwSweepRequester.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits
  txEwSweepRequester.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  txEwSweepResponder.io.start := txEwRespIntf.start
  txEwSweepResponder.io.patternType := txEwRespIntf.patternType

  txEwRespIntf.done := txEwSweepResponder.io.done

  txEwSweepResponder.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits
  txEwSweepResponder.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  val rxPtReqIntf = Wire(new RxInitPtTestRequesterInterfaceIO(afeParams))
  val rxPtRespIntf = Wire(new RxInitPtTestResponderInterfaceIO())

  rxPtReqIntf.start := false.B
  rxPtReqIntf.patternType := DontCare
  rxPtReqIntf.linkTrainingParameters := DontCare

  rxPtRespIntf.start := false.B
  rxPtRespIntf.patternType := DontCare

  val rxPtTestRequester = withReset(subFsmModuleReset) {
    Module(new RxD2CPointTestRequester(afeParams, sbParams))
  }
  val rxPtTestResponder = withReset(subFsmModuleReset) {
    Module(new RxD2CPointTestResponder(afeParams, sbParams))
  }

  rxPtTestRequester.io.start := rxPtReqIntf.start
  rxPtTestRequester.io.patternType := rxPtReqIntf.patternType
  rxPtTestRequester.io.linkTrainingParameters := rxPtReqIntf.linkTrainingParameters

  rxPtReqIntf.done := rxPtTestRequester.io.done
  rxPtReqIntf.ptTestResults := rxPtTestRequester.io.rxInitPtTestLocalResults

  rxPtTestRequester.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits
  rxPtTestRequester.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  rxPtTestResponder.io.start := rxPtRespIntf.start
  rxPtTestResponder.io.patternType := rxPtRespIntf.patternType

  rxPtRespIntf.done := rxPtTestResponder.io.done

  rxPtTestResponder.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits
  rxPtTestResponder.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  io.phyTrainIo.semanticPhase.request.valid :=
    rxPtTestResponder.io.semanticPhase.request.valid
  io.phyTrainIo.semanticPhase.request.bits :=
    rxPtTestResponder.io.semanticPhase.request.bits
  rxPtTestResponder.io.semanticPhase.request.ready :=
    io.phyTrainIo.semanticPhase.request.ready
  rxPtTestResponder.io.semanticPhase.completion.valid :=
    io.phyTrainIo.semanticPhase.completion.valid
  rxPtTestResponder.io.semanticPhase.completion.bits :=
    io.phyTrainIo.semanticPhase.completion.bits
  io.phyTrainIo.semanticPhase.completion.ready :=
    rxPtTestResponder.io.semanticPhase.completion.ready

  val rxEwReqIntf = Wire(new RxInitEyeWidthSweepRequesterInterfaceIO(afeParams))
  val rxEwRespIntf = Wire(
    new RxInitEyeWidthSweepResponderInterfaceIO(afeParams)
  )

  rxEwReqIntf.start := false.B
  rxEwReqIntf.patternType := DontCare
  rxEwReqIntf.linkTrainingParameters := DontCare

  rxEwRespIntf.start := false.B
  rxEwRespIntf.patternType := DontCare

  val rxEwSweepRequester = withReset(subFsmModuleReset) {
    Module(new RxD2CEyeWidthSweepRequester(afeParams, sbParams))
  }
  val rxEwSweepResponder = withReset(subFsmModuleReset) {
    Module(new RxD2CEyeWidthSweepResponder(afeParams, sbParams))
  }

  rxEwSweepRequester.io.start := rxEwReqIntf.start
  rxEwSweepRequester.io.patternType := rxEwReqIntf.patternType
  rxEwSweepRequester.io.linkTrainingParameters := rxEwReqIntf.linkTrainingParameters

  rxEwReqIntf.done := rxEwSweepRequester.io.done
  rxEwReqIntf.eyeSweepTestResults := rxEwSweepRequester.io.rxInitEyeWidthSweepResults

  io.partnerEyeEdges := rxEwSweepRequester.io.partnerEyeEdges

  rxEwSweepRequester.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits
  rxEwSweepRequester.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  rxEwSweepResponder.io.start := rxEwRespIntf.start
  rxEwSweepResponder.io.patternType := rxEwRespIntf.patternType

  rxEwRespIntf.done := rxEwSweepResponder.io.done
  rxEwRespIntf.remoteEyeSweepTestResults := rxEwSweepResponder.io.rxEyeWidthSweepRemoteResults

  rxEwSweepResponder.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits
  rxEwSweepResponder.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  val decisionTargetsTx = io.phyTrainIo.eyeDecision.bits.target ===
    PhyEyeDecisionTarget.TxRequester
  txEwSweepRequester.io.decision.valid :=
    io.phyTrainIo.eyeDecision.valid && decisionTargetsTx
  txEwSweepRequester.io.decision.bits := io.phyTrainIo.eyeDecision.bits.kind
  rxEwSweepResponder.io.decision.valid :=
    io.phyTrainIo.eyeDecision.valid && !decisionTargetsTx
  rxEwSweepResponder.io.decision.bits := io.phyTrainIo.eyeDecision.bits.kind

  rxEwSweepResponder.io.localEyeEdges := io.phyTrainIo.eyeEdges

  io.phyTrainIo.eyeDecision.ready := Mux(
    decisionTargetsTx,
    txEwSweepRequester.io.decision.ready,
    rxEwSweepResponder.io.decision.ready
  )

  io.phyTrainIo.ltsmState := currentStateSpecific
  io.phyTrainIo.speedMode := freqSel

  // Saturate, because a wrap to near zero would make software finish early.
  io.phyTrainIo.residencyLeft := {
    val left = timeoutMaxCycles - timeoutCounter
    val w = LogPhyTrainerParams.residencyWidth
    if (timeoutWidth > w)
      Mux(
        left > ((BigInt(1) << w) - 1).U,
        ((BigInt(1) << w) - 1).U(w.W),
        left(w - 1, 0)
      )
    else left.pad(w)
  }

  // ===========================================================================
  // Width codes and pattern arbitration
  // ===========================================================================
  val localTxFunctionalLanes = io.currLocalTxFunctionalLanes
  val remoteTxFunctionalLanes = io.currRemoteTxFunctionalLanes

  val txDatapathCode =
    PatternLaneMap.datapathCode(localTxFunctionalLanes, negotiatedBy8)
  val rxDatapathCode =
    PatternLaneMap.datapathCode(remoteTxFunctionalLanes, negotiatedBy8)

  // REVERSALMB uses all N Lanes (sec 4.5.3.3.5), even at a smaller start width.
  val reversalMb = currentState === LTState.sMBINIT &&
    mbInitSM.io.currentState === MBInitState.sREVERSALMB
  val moduleCode = PatternLaneMap.datapathCode("b011".U(3.W), negotiatedBy8)
  val txEnCode = Mux(reversalMb, moduleCode, txDatapathCode)
  val rxEnCode = Mux(reversalMb, moduleCode, rxDatapathCode)

  val patternWriterClients = Seq(
    (mbInitSM.io.usingPatternWriter, mbInitSM.io.patternWriterIo),
    (
      txPtTestRequester.io.usingPatternWriter,
      txPtTestRequester.io.patternWriterIo
    ),
    (
      txEwSweepRequester.io.usingPatternWriter,
      txEwSweepRequester.io.patternWriterIo
    ),
    (
      rxPtTestResponder.io.usingPatternWriter,
      rxPtTestResponder.io.patternWriterIo
    ),
    (
      rxEwSweepResponder.io.usingPatternWriter,
      rxEwSweepResponder.io.patternWriterIo
    )
  )

  val patternReaderClients = Seq(
    (mbInitSM.io.usingPatternReader, mbInitSM.io.patternReaderIo),
    (
      txPtTestResponder.io.usingPatternReader,
      txPtTestResponder.io.patternReaderIo
    ),
    (
      txEwSweepResponder.io.usingPatternReader,
      txEwSweepResponder.io.patternReaderIo
    ),
    (
      rxPtTestRequester.io.usingPatternReader,
      rxPtTestRequester.io.patternReaderIo
    ),
    (
      rxEwSweepRequester.io.usingPatternReader,
      rxEwSweepRequester.io.patternReaderIo
    )
  )

  io.patternWriterIo.req.valid := false.B
  io.patternWriterIo.req.bits := DontCare

  patternWriterClients.foreach { case (isUsing, clientIo) =>
    clientIo.resp := io.patternWriterIo.resp
    clientIo.req.ready := false.B

    when(isUsing) {
      io.patternWriterIo.req.valid := clientIo.req.valid
      io.patternWriterIo.req.bits := clientIo.req.bits
      clientIo.req.ready := io.patternWriterIo.req.ready
    }
  }

  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits := DontCare
  io.patternReaderIo.done := false.B
  io.patternReaderIo.resp.ready := false.B
  io.patternReaderIo.remoteFuncLanes := rxEnCode

  patternReaderClients.foreach { case (isUsing, clientIo) =>
    clientIo.resp.valid := io.patternReaderIo.resp.valid
    clientIo.resp.bits := io.patternReaderIo.resp.bits
    clientIo.verdictFinal := io.patternReaderIo.verdictFinal
    clientIo.req.ready := false.B

    when(isUsing) {
      io.patternReaderIo.req.valid := clientIo.req.valid
      io.patternReaderIo.req.bits := clientIo.req.bits
      clientIo.req.ready := io.patternReaderIo.req.ready
      io.patternReaderIo.done := clientIo.done
      io.patternReaderIo.resp.ready := clientIo.resp.ready
    }
  }

  // ===========================================================================
  // Lane width and reversal
  // ===========================================================================
  val updateLocalTxFuncLanes = WireInit(false.B)
  val updateRemoteTxFuncLanes = WireInit(false.B)
  val newLocalTxFuncLanes = WireInit("b011".U(3.W))
  val newLocalRxFuncLanes = WireInit("b011".U(3.W))

  // sec 9.5.3.36 bits 14:8 name a logical transmit Lane.
  val swRepairLane =
    PatternLaneMap.laneHalf(
      io.runtimeLaneRepairId,
      negotiatedUcieSx8.asBool,
      afeParams.mbLanes
    )
  val swRepairLaneRelevant = swRepairLane.relevant

  // Repair resources (Table 4-10) mean a width without the Lane (sec 4.3.7).
  val swTxDegradeTarget = PatternLaneMap.degradeTarget(
    localTxFunctionalLanes,
    swRepairLane.lower,
    swRepairLane.upper,
    negotiatedUcieSx8.asBool
  )
  val swRxDegradeTarget = PatternLaneMap.degradeTarget(
    remoteTxFunctionalLanes,
    swRepairLane.lower,
    swRepairLane.upper,
    negotiatedUcieSx8.asBool
  )

  // Latch at RESET, because sec 9.5.1.5 trains with the bits set at the start.
  val linkInitWidthCode = RegInit("b011".U(3.W))
  when(currentState === LTState.sRESET) {
    linkInitWidthCode := io.initWidthCode
  }

  // MBTRAIN.REPAIR starts at full width, because sec 4.3.7 degrades from it.
  val restoreFullWidth = WireInit(false.B)
  io.widthWrite.txValid := updateLocalTxFuncLanes || restoreFullWidth

  // Full width means the start width, so Lanes Target Link Width cut stay off.
  // sec 4.3.7 does not say, so this is a reading.
  io.widthWrite.txCode :=
    Mux(restoreFullWidth, linkInitWidthCode, newLocalTxFuncLanes)
  io.widthWrite.rxValid := updateRemoteTxFuncLanes || restoreFullWidth
  io.widthWrite.rxCode :=
    Mux(restoreFullWidth, linkInitWidthCode, newLocalRxFuncLanes)
  io.widthReset := currentState === LTState.sRESET
  io.localTxFunctionalLanes := localTxFunctionalLanes
  io.remoteTxFunctionalLanes := remoteTxFunctionalLanes

  val rxLaneMask = PatternLaneMap.decodeLaneMap(rxDatapathCode, 16)

  io.rxLaneMask := rxLaneMask

  // sec 9.5.3.34 bit 9. A width that Link Control asked for is not a degrade.
  io.widthDegraded := localTxFunctionalLanes =/= linkInitWidthCode
  io.linkInitWidthCode := linkInitWidthCode

  val mbLaneCtrlIo = Wire(new MainbandLaneCtrlIO(afeParams))

  // Tri-state by default, as RESET, SBINIT and TRAINERROR require (sec 4.5.3).
  mbLaneCtrlIo.txDataEn.foreach(_ := false.B)
  mbLaneCtrlIo.txClkEn := false.B
  mbLaneCtrlIo.txValidEn := false.B
  mbLaneCtrlIo.txTrackEn := false.B
  mbLaneCtrlIo.txClkHeldLow := false.B
  mbLaneCtrlIo.txTrackPattern := false.B
  mbLaneCtrlIo.txTrackLevel := false.B
  mbLaneCtrlIo.rxDataEn.foreach(_ := false.B)
  mbLaneCtrlIo.rxClkEn := false.B
  mbLaneCtrlIo.rxValidEn := false.B
  mbLaneCtrlIo.rxTrackEn := false.B

  val doLaneReversal = RegInit(false.B)

  val txEnMask = PatternLaneMap.decodeLaneMap(txEnCode, 16)
  val rxEnMask = PatternLaneMap.decodeLaneMap(rxEnCode, 16)
  for (i <- 0 until afeParams.mbLanes) {
    // Only the Transmitter reverses (sec 4.2), so its enables are mirrored.
    def mirror(n: Int): Int = if (i < n) n - 1 - i else i
    val txEn = Mux(
      doLaneReversal,
      Mux(
        negotiatedBy8,
        txEnMask(mirror(8)),
        txEnMask(mirror(afeParams.mbLanes))
      ),
      txEnMask(i)
    )
    io.mbCtrlIo.txDataEn(i) := mbLaneCtrlIo.txDataEn(i) && txEn
    io.mbCtrlIo.rxDataEn(i) := mbLaneCtrlIo.rxDataEn(i) && rxEnMask(i)
  }

  io.mbCtrlIo.txClkEn := mbLaneCtrlIo.txClkEn
  io.mbCtrlIo.txClkHeldLow := mbLaneCtrlIo.txClkHeldLow
  io.mbCtrlIo.txValidEn := mbLaneCtrlIo.txValidEn
  io.mbCtrlIo.txTrackEn := mbLaneCtrlIo.txTrackEn
  io.mbCtrlIo.txTrackPattern := mbLaneCtrlIo.txTrackPattern
  io.mbCtrlIo.txTrackLevel := mbLaneCtrlIo.txTrackLevel

  io.mbCtrlIo.rxClkEn := mbLaneCtrlIo.rxClkEn
  io.mbCtrlIo.rxValidEn := mbLaneCtrlIo.rxValidEn
  io.mbCtrlIo.rxTrackEn := mbLaneCtrlIo.rxTrackEn

  mbTrainSM.io.currLocalTxFunctionalLanes := localTxFunctionalLanes
  mbTrainSM.io.currRemoteTxFunctionalLanes := remoteTxFunctionalLanes

  // sec 4.5.3.3.5 step 9 keeps this order, and only a domain reset clears it.
  val laneReversalKept = RegInit(false.B)
  val reversalTried = RegNext(mbInitSM.io.applyLaneReversal, false.B)

  // Test again with the other Lane order (sec 4.5.3.3.5 step 7).
  when(mbInitSM.io.applyLaneReversal && !reversalTried) {
    doLaneReversal := !doLaneReversal
  }

  // In TRAINERROR, as at step 10, go back to the order that step 9 kept last.
  when(currentState === LTState.sTRAINERROR) {
    doLaneReversal := laneReversalKept
  }.elsewhen(mbInitSM.io.currentState === MBInitState.sREPAIRMB) {
    laneReversalKept := doLaneReversal
  }

  io.doLaneReversal := doLaneReversal

  // ===========================================================================
  // PHY retrain
  // ===========================================================================
  val retrainSbModule = withReset(subFsmModuleReset) {
    Module(new PhyRetrainSidebandHandshake(sbParams))
  }

  retrainSbModule.io.startPhyRetrainMsgExch := false.B
  retrainSbModule.io.waitForRemoteRequest := false.B

  retrainSbModule.io.requesterSbLaneIo.rx.valid := false.B
  retrainSbModule.io.requesterSbLaneIo.rx.bits :=
    0.U.asTypeOf(chiselTypeOf(retrainSbModule.io.requesterSbLaneIo.rx.bits))
  retrainSbModule.io.requesterSbLaneIo.tx.ready := false.B

  retrainSbModule.io.responderSbLaneIo.rx.valid := false.B
  retrainSbModule.io.responderSbLaneIo.rx.bits :=
    0.U.asTypeOf(chiselTypeOf(retrainSbModule.io.responderSbLaneIo.rx.bits))
  retrainSbModule.io.responderSbLaneIo.tx.ready := false.B

  val phyInRetrain = RegInit(false.B)

  when(currentState === LTState.sRESET || mbTrainSM.io.clearPhyInRetrainFlag) {
    phyInRetrain := false.B
  }

  mbTrainSM.io.phyInRetrain := phyInRetrain
  io.clearRuntimeLinkCtrlBusyBit := mbTrainSM.io.clearRuntimeLinkCtrlBusyBit

  val busyBit = WireInit(io.runtimeLinkCtrlBusyBit.asUInt)
  val repairNeeded =
    WireInit(io.runtimeRequestForRepair && swRepairLaneRelevant)

  // Both directions need a target, because pl_lnk_cfg has one width.
  val repairResourcesAvailable = WireInit(
    (swTxDegradeTarget =/= "b000".U) && (swRxDegradeTarget =/= "b000".U)
  )

  mbTrainSM.io.swDegradeTarget.valid := io.runtimeLinkCtrlBusyBit &&
    repairNeeded && repairResourcesAvailable

  // sec 9.5.3.36 bit 7 waits for the next retrain, so it needs PHY_IN_RETRAIN.
  val stuckAtActive = io.injectStuckAt && io.runtimeLinkCtrlBusyBit &&
    phyInRetrain && swRepairLaneRelevant

  // Inject on Tx (sec 9.5.3.36 allows either), so LINKSPEED's results see it.
  io.stuckAtLaneMask := Mux(
    stuckAtActive,
    UIntToOH(io.runtimeLaneRepairId, afeParams.mbLanes),
    0.U(afeParams.mbLanes.W)
  )
  mbTrainSM.io.swDegradeTarget.bits := swTxDegradeTarget

  val localEncoding = RetrainResolution.localEncoding(
    busyBit === 1.U,
    repairNeeded,
    repairResourcesAvailable
  )

  val remoteReqEncoding = retrainSbModule.io.responderRemoteRetrainEncoding

  val resolutionDone = RegInit(false.B)
  val resolvedEncoding = RegInit(0.U(3.W))

  // Only in PHYRETRAIN, or a late encoding carries into the next retrain.
  when(remoteReqEncoding.valid && (currentState === LTState.sPHYRETRAIN)) {
    resolutionDone := true.B
    resolvedEncoding := RetrainResolution.resolve(
      localEncoding,
      remoteReqEncoding.bits
    )
  }

  retrainSbModule.io.responderLocalRetrainEncoding.valid := resolutionDone
  retrainSbModule.io.responderLocalRetrainEncoding.bits := resolvedEncoding

  retrainSbModule.io.requesterLocalRetrainEncoding.valid := (currentState === LTState.sPHYRETRAIN)
  retrainSbModule.io.requesterLocalRetrainEncoding.bits := localEncoding

  mbTrainSM.io.goToState.valid := false.B
  mbTrainSM.io.goToState.bits := MBTrainGoToState.goToSPEEDIDLE

  switch(resolvedEncoding) {
    is(RetrainEncoding.TXSELFCAL) {
      mbTrainSM.io.goToState.bits := MBTrainGoToState.goToTXSELFCAL
    }
    is(RetrainEncoding.REPAIR) {
      mbTrainSM.io.goToState.bits := MBTrainGoToState.goToREPAIR
    }
    is(RetrainEncoding.SPEEDIDLE) {
      mbTrainSM.io.goToState.bits := MBTrainGoToState.goToSPEEDIDLE
    }
  }

  // Only REPAIR restores, as SPEEDIDLE keeps the width (sec 4.5.3.4.3 rule 2).
  when(mbTrainSM.io.goToState.valid) {
    switch(mbTrainSM.io.goToState.bits) {
      is(MBTrainGoToState.goToREPAIR) { restoreFullWidth := true.B }
    }
  }

  // ===========================================================================
  // Errors and TRAINERROR
  // ===========================================================================
  val errorDetected = Wire(Bool())

  errorDetected := mbInitSM.io.fsmCtrl.error || mbTrainSM.io.fsmCtrl.error ||
    rxPtTestResponder.io.error

  val localError = Wire(Bool())
  localError := trainingTimedout || errorDetected

  val trainErrorRequester = withReset(subFsmModuleReset) {
    Module(new TrainErrorRequester(sbParams))
  }
  val trainErrorResponder = withReset(subFsmModuleReset) {
    Module(new TrainErrorResponder(sbParams))
  }

  // RDI LinkReset, Disabled or LinkError leads to TRAINERROR (sec 4.5.3.8).
  val linkDownRequested =
    ((io.rdi.plStateSts === RDIState.linkReset) ||
      (io.rdi.plStateSts === RDIState.disabled) ||
      (io.rdi.plStateSts === RDIState.linkError)) &&
      !trainingEpisodeActive && (currentState =/= LTState.sRESET)

  // On an up Link, RDI goes to LinkError first, so the partner hears it first.
  when(
    ((localError && !rdiUp) || linkDownRequested) &&
      !waitTrainErrorResp &&
      (currentState =/= LTState.sTRAINERROR) &&

      // sec 4.5.3.8 needs no handshake from SBINIT, where a req goes out RAW.
      (currentState =/= LTState.sSBINIT) &&

      // Not from RESET, where a request would restart the 4 ms residency count.
      (currentState =/= LTState.sRESET)
  ) {
    triggerTrainErrorReq := true.B
  }
  when(triggerTrainErrorReq) {
    waitTrainErrorResp := true.B
  }.elsewhen(
    trainErrorRequester.io.done ||
      currentState === LTState.sTRAINERROR ||
      currentState === LTState.sRESET
  ) {
    waitTrainErrorResp := false.B
  }
  trainErrorRequester.io.sendReq := waitTrainErrorResp

  trainErrorResponder.io.wakeUp := (currentState =/= LTState.sRESET) &&
    (currentState =/= LTState.sTRAINERROR)
  trainErrorResponder.io.sendResp := (currentState === LTState.sTRAINERROR) &&
    trainErrorResponder.io.remoteRequestingTrainError &&
    !trainErrorResponder.io.done

  val transitionToTrainError = Wire(Bool())
  transitionToTrainError := (waitTrainErrorResp &&
    (trainErrorRequester.io.done || trainErrorHandshakeTimedout)) ||
    trainErrorResponder.io.remoteRequestingTrainError

  val responderFinished = !trainErrorResponder.io.remoteRequestingTrainError ||
    trainErrorResponder.io.done

  // sec 4.5.3.8 holds TRAINERROR for packets in flight and for RDI LinkError.
  // forceRdiLinkError covers the cycles before RDI gets there.
  val transitionToReset = Wire(Bool())
  transitionToReset := io.sbCtrlIo.allPacketsSent &&
    (io.rdi.plStateSts =/= RDIState.linkError) &&
    !forceRdiLinkError

  val succeededThisCycle = Wire(Bool())
  succeededThisCycle := (currentState === LTState.sLINKINIT) && (nextState === LTState.sACTIVE)

  retryCounterEn := (currentState === LTState.sTRAINERROR) &&
    (nextState === LTState.sRESET) &&
    trainingEpisodeActive

  // Decide on entry, or an escalation lands as the PHY leaves (sec 4.5.3.8).
  val enteringTrainError = (currentState =/= LTState.sTRAINERROR) &&
    (nextState === LTState.sTRAINERROR)
  val episodeExhausted = enteringTrainError && trainingEpisodeActive &&
    (trainingRetryCounter === retryAmtMax)

  autoRetrain := (currentState === LTState.sRESET) &&
    trainingEpisodeActive &&
    (trainingRetryCounter <= retryAmtMax)

  // A trigger in RESET between retries belongs to the current episode.
  when(
    freshTrainingTrigger && (currentState === LTState.sRESET) &&
      !trainingEpisodeActive
  ) {
    trainingEpisodeActive := true.B
    currentEpisodeIsLocal := swTriggerTraining || rdiTriggerTraining
    trainingRetryCounter := 0.U
    retryAmtMax := io.retryTrainingAmt
    fatalTrainingError := false.B
    fatalTrainingSawLinkError := false.B
    forceRdiLinkError := false.B
  }.elsewhen(succeededThisCycle) {
    trainingEpisodeActive := false.B
    currentEpisodeIsLocal := false.B
    trainingRetryCounter := 0.U
  }.elsewhen(episodeExhausted) {
    trainingEpisodeActive := false.B
    when(currentEpisodeIsLocal) {
      fatalTrainingError := true.B
      forceRdiLinkError := true.B
    }
    currentEpisodeIsLocal := false.B
  }.elsewhen(retryCounterEn) {
    trainingRetryCounter := trainingRetryCounter + 1.U
  }

  // RDI goes to LinkError first, so its request reaches the partner.
  // A Start is not a failure, so fatalTrainingError stays low.
  when(liveStart) {
    forceRdiLinkError := true.B
  }

  // sec 4.5 and Table 10-1 escalate a failure that takes an up Link down.
  when(localError && rdiUp) {
    fatalTrainingError := true.B
    forceRdiLinkError := true.B
  }

  // A partner's TRAINERROR takes RDI to LinkError, with no fatal escalation.
  when(trainErrorResponder.io.remoteRequestingTrainError && rdiUp) {
    forceRdiLinkError := true.B
  }

  when(fatalTrainingError && (io.rdi.plStateSts === RDIState.linkError)) {
    fatalTrainingSawLinkError := true.B
    forceRdiLinkError := false.B
  }

  when(forceRdiLinkError && (io.rdi.plStateSts === RDIState.linkError)) {
    forceRdiLinkError := false.B
  }

  when(
    fatalTrainingError &&
      fatalTrainingSawLinkError &&
      (io.rdi.plStateSts === RDIState.reset)
  ) {
    fatalTrainingError := false.B
    fatalTrainingSawLinkError := false.B
  }

  // sec 4.5.3.3.1 recommends an Internal Error, LinkError and pl_trainerror.
  val interopFailPrev =
    RegNext(mbInitSM.io.interoperableParamsNotFound, false.B)

  // A retry cannot change the parameters, so escalate at once (sec 4.5).
  when(
    mbInitSM.io.interoperableParamsNotFound && !interopFailPrev &&
      currentEpisodeIsLocal
  ) {
    fatalTrainingError := true.B
    forceRdiLinkError := true.B

    // The episode ends here, so the escalation happens once.
    trainingEpisodeActive := false.B
  }

  // ===========================================================================
  // Runtime recalibration
  // ===========================================================================
  val runtimeRecal = withReset(subFsmModuleReset) {
    Module(new RuntimeRecalResponder(sbParams))
  }
  runtimeRecal.io.inActive := currentState === LTState.sACTIVE
  io.recalInProgress := runtimeRecal.io.recalInProgress

  // Outside the sub-state reset, so a retrain cannot favor one level.
  val trackParkLevel = RegInit(false.B)
  val recalWasInProgress = RegNext(runtimeRecal.io.recalInProgress, false.B)
  when(recalWasInProgress && !runtimeRecal.io.recalInProgress) {
    trackParkLevel := !trackParkLevel
  }

  // ===========================================================================
  // Sideband arbitration
  // ===========================================================================
  val activeReqSbLane = Wire(new SidebandLaneIO(sbParams))
  val activeRespSbLane = Wire(new SidebandLaneIO(sbParams))

  activeReqSbLane.tx.valid := false.B
  activeReqSbLane.tx.bits := 0.U.asTypeOf(chiselTypeOf(activeReqSbLane.tx.bits))
  activeReqSbLane.rx.ready := false.B

  activeRespSbLane.tx.valid := false.B
  activeRespSbLane.tx.bits := 0.U.asTypeOf(
    chiselTypeOf(activeRespSbLane.tx.bits)
  )
  activeRespSbLane.rx.ready := false.B

  val sidebandClients = Seq(
    activeReqSbLane,
    activeRespSbLane,
    trainErrorRequester.io.sbLaneIo,
    trainErrorResponder.io.sbLaneIo,
    txPtTestRequester.io.sbLaneIo,
    txPtTestResponder.io.sbLaneIo,
    txEwSweepRequester.io.sbLaneIo,
    txEwSweepResponder.io.sbLaneIo,
    rxPtTestRequester.io.sbLaneIo,
    rxPtTestResponder.io.sbLaneIo,
    rxEwSweepRequester.io.sbLaneIo,
    rxEwSweepResponder.io.sbLaneIo,
    runtimeRecal.io.patternSbLaneIo,
    runtimeRecal.io.adjustSbLaneIo
  )

  sidebandClients.foreach { client =>
    client.rx.valid := io.sbLaneIo.rx.valid
    client.rx.bits := io.sbLaneIo.rx.bits
  }

  io.sbLaneIo.rx.ready := sidebandClients.map(_.rx.ready).reduce(_ || _)

  val txArbiter = Module(
    new RRArbiter(chiselTypeOf(io.sbLaneIo.tx.bits), sidebandClients.length)
  )

  sidebandClients.zipWithIndex.foreach { case (client, index) =>
    txArbiter.io.in(index) <> client.tx
  }

  io.sbLaneIo.tx <> txArbiter.io.out

  // ===========================================================================
  // State machine
  // ===========================================================================
  switch(currentState) {
    is(LTState.sRESET) {
      currentStateSpecific := LTSMState.sRESET
      sbRxTxMode := SBRxTxMode.RAW
      io.sbLaneIo.rx.ready := true.B
      when(io.sbLaneIo.rx.valid) {
        when(io.sbLaneIo.rx.bits.data(63, 0) === sbInitClkPattern) {
          when(sbInitPatternCounter =/= 2.U) {
            sbInitPatternCounter := sbInitPatternCounter + 1.U
          }
        }.otherwise {
          when(sbInitPatternCounter === 1.U) {
            sbInitPatternCounter := 0.U
          }
        }
      }

      when(
        io.pwrGood && io.phyCtrlIo.pllLock && resetMinWait &&
          (freshTrainingTrigger || autoRetrain)
      ) {
        nextState := LTState.sSBINIT
        sbInitPatternCounter := 0.U
      }.otherwise {
        nextState := LTState.sRESET
      }
    }
    is(LTState.sSBINIT) {
      currentStateSpecific := LTSMState.sSBINIT
      sbInitSM.io.fsmCtrl.start := !trainingTimedout && !sbInitSM.io.fsmCtrl.done

      // SBINIT stays RAW, or a pattern in flight goes out as a 2-beat packet.
      sbRxTxMode := SBRxTxMode.RAW
      activeReqSbLane <> sbInitSM.io.requesterSbLaneIo
      activeRespSbLane <> sbInitSM.io.responderSbLaneIo

      // A local error needs no handshake here (sec 4.5.3.8).
      when(transitionToTrainError || localError) {
        nextState := LTState.sTRAINERROR
      }.elsewhen(sbInitSM.io.fsmCtrl.done) {
        nextState := LTState.sMBINIT
      }
    }
    is(LTState.sMBINIT) {
      mbInitSM.io.fsmCtrl.start := !trainingTimedout && !mbInitSM.io.fsmCtrl.done

      mbLaneCtrlIo := mbInitSM.io.mbLaneCtrlIo

      txPtReqIntf <> mbInitSM.io.txPtTestReqInterfaceIo
      txPtRespIntf <> mbInitSM.io.txPtTestRespInterfaceIo

      activeReqSbLane <> mbInitSM.io.requesterSbLaneIo
      activeRespSbLane <> mbInitSM.io.responderSbLaneIo

      updateLocalTxFuncLanes := mbInitSM.io.widthApply.valid
      updateRemoteTxFuncLanes := mbInitSM.io.widthApply.valid
      newLocalTxFuncLanes := mbInitSM.io.widthApply.txCode
      newLocalRxFuncLanes := mbInitSM.io.widthApply.rxCode

      substateTransitioning := mbInitSM.io.fsmCtrl.substateTransitioning
      sbStallReceived := mbInitSM.io.fsmCtrl.stallReceived

      switch(mbInitSM.io.currentState) {
        is(MBInitState.sPARAM) {
          currentStateSpecific := LTSMState.sMBINIT_PARAM
        }
        is(MBInitState.sCAL) { currentStateSpecific := LTSMState.sMBINIT_CAL }
        is(MBInitState.sREPAIRCLK) {
          currentStateSpecific := LTSMState.sMBINIT_REPAIRCLK
        }
        is(MBInitState.sREPAIRVAL) {
          currentStateSpecific := LTSMState.sMBINIT_REPAIRVAL
        }
        is(MBInitState.sREVERSALMB) {
          currentStateSpecific := LTSMState.sMBINIT_REVERSALMB
        }
        is(MBInitState.sREPAIRMB) {
          currentStateSpecific := LTSMState.sMBINIT_REPAIRMB
        }

        // Report the last sub-state, so the output does not glitch.
        is(MBInitState.sTOMBTRAIN) {
          currentStateSpecific := LTSMState.sMBINIT_REPAIRMB
        }
      }

      when(transitionToTrainError) {
        nextState := LTState.sTRAINERROR
      }.elsewhen(mbInitSM.io.fsmCtrl.done) {
        nextState := LTState.sMBTRAIN
      }
    }
    is(LTState.sMBTRAIN) {
      mbTrainSM.io.fsmCtrl.start := !trainingTimedout && !mbTrainSM.io.fsmCtrl.done

      mbLaneCtrlIo := mbTrainSM.io.mbLaneCtrlIo

      activeReqSbLane <> mbTrainSM.io.requesterSbLaneIo
      activeRespSbLane <> mbTrainSM.io.responderSbLaneIo

      txPtReqIntf <> mbTrainSM.io.txPtTestReqIntfIo
      txPtRespIntf <> mbTrainSM.io.txPtTestRespIntfIo
      txEwReqIntf <> mbTrainSM.io.txEyeSweepReqIntfIo
      txEwRespIntf <> mbTrainSM.io.txEyeSweepRespIntfIo
      rxPtReqIntf <> mbTrainSM.io.rxPtTestReqIntfIo
      rxPtRespIntf <> mbTrainSM.io.rxPtTestRespIntfIo
      rxEwReqIntf <> mbTrainSM.io.rxEyeSweepReqIntfIo
      rxEwRespIntf <> mbTrainSM.io.rxEyeSweepRespIntfIo

      updateLocalTxFuncLanes := mbTrainSM.io.txWidthChanged
      updateRemoteTxFuncLanes := mbTrainSM.io.rxWidthChanged
      newLocalTxFuncLanes := mbTrainSM.io.newLocalFunctionalLanes
      newLocalRxFuncLanes := mbTrainSM.io.newRemoteFunctionalLanes

      substateTransitioning := mbTrainSM.io.fsmCtrl.substateTransitioning
      sbStallReceived := mbTrainSM.io.fsmCtrl.stallReceived

      switch(mbTrainSM.io.currentState) {
        is(MBTrainState.sVALVREF) {
          currentStateSpecific := LTSMState.sMBTRAIN_VALVREF
        }
        is(MBTrainState.sDATAVREF) {
          currentStateSpecific := LTSMState.sMBTRAIN_DATAVREF
        }
        is(MBTrainState.sSPEEDIDLE) {
          currentStateSpecific := LTSMState.sMBTRAIN_SPEEDIDLE
        }
        is(MBTrainState.sTXSELFCAL) {
          currentStateSpecific := LTSMState.sMBTRAIN_TXSELFCAL
        }
        is(MBTrainState.sRXCLKCAL) {
          currentStateSpecific := LTSMState.sMBTRAIN_RXCLKCAL
        }
        is(MBTrainState.sVALTRAINCENTER) {
          currentStateSpecific := LTSMState.sMBTRAIN_VALTRAINCENTER
        }
        is(MBTrainState.sVALTRAINVREF) {
          currentStateSpecific := LTSMState.sMBTRAIN_VALTRAINVREF
        }
        is(MBTrainState.sDATATRAINCENTER1) {
          currentStateSpecific := LTSMState.sMBTRAIN_DATATRAINCENTER1
        }
        is(MBTrainState.sDATATRAINVREF) {
          currentStateSpecific := LTSMState.sMBTRAIN_DATATRAINVREF
        }
        is(MBTrainState.sRXDESKEW) {
          currentStateSpecific := LTSMState.sMBTRAIN_RXDESKEW
        }
        is(MBTrainState.sDATATRAINCENTER2) {
          currentStateSpecific := LTSMState.sMBTRAIN_DATATRAINCENTER2
        }
        is(MBTrainState.sLINKSPEED) {
          currentStateSpecific := LTSMState.sMBTRAIN_LINKSPEED
        }
        is(MBTrainState.sREPAIR) {
          currentStateSpecific := LTSMState.sMBTRAIN_REPAIR
        }
        is(MBTrainState.sTOPHYRETRAIN) {
          currentStateSpecific := LTSMState.sMBTRAIN_LINKSPEED
        }
        is(MBTrainState.sTOLINKINIT) {
          currentStateSpecific := LTSMState.sMBTRAIN_LINKSPEED
        }
      }

      when(transitionToTrainError) {
        nextState := LTState.sTRAINERROR
      }.elsewhen(mbTrainSM.io.currentState === MBTrainState.sTOPHYRETRAIN) {
        // sec 4.5.3.7.4 has no RDI handshake, so do not wait for RDI Retrain.
        nextState := LTState.sPHYRETRAIN
      }.elsewhen(mbTrainSM.io.currentState === MBTrainState.sTOLINKINIT) {
        nextState := LTState.sLINKINIT

        // sec 4.5.3.5 resets the scrambler LFSR on entry to LINKINIT.
        io.scramblerReset := true.B
      }
    }
    is(LTState.sLINKINIT) {
      // sec 4.5.3.5 holds Track, Data and Valid low, so they are driven.
      mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      mbLaneCtrlIo.txClkEn := true.B
      mbLaneCtrlIo.txValidEn := true.B
      mbLaneCtrlIo.txTrackEn := true.B
      mbLaneCtrlIo.rxClkEn := true.B

      currentStateSpecific := LTSMState.sLINKINIT
      io.rdi.doRdiBringup := true.B

      when(transitionToTrainError) {
        nextState := LTState.sTRAINERROR
      }.elsewhen(io.rdi.plStateSts === RDIState.active) {
        // Do not wait on a divided clock, which can stop.
        nextState := LTState.sACTIVE
      }
    }
    is(LTState.sACTIVE) {
      currentStateSpecific := LTSMState.sACTIVE
      mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      mbLaneCtrlIo.txClkEn := true.B
      mbLaneCtrlIo.txValidEn := true.B

      // Hi-Z for a terminated partner (sec 4.6), else drive the park level.
      mbLaneCtrlIo.txTrackEn :=
        runtimeRecal.io.recalInProgress || !io.remoteRxTerminated

      // One signal sends Track (sec 5.3) and keeps the clock ungated.
      mbLaneCtrlIo.txTrackPattern := runtimeRecal.io.recalInProgress
      mbLaneCtrlIo.txTrackLevel := trackParkLevel
      mbLaneCtrlIo.rxDataEn.foreach(_ := true.B)
      mbLaneCtrlIo.rxClkEn := true.B
      mbLaneCtrlIo.rxValidEn := true.B
      mbLaneCtrlIo.rxTrackEn := true.B

      // A Start blocks the retrain arc, as full training replaces PHYRETRAIN.
      when(transitionToTrainError) {
        nextState := LTState.sTRAINERROR
      }.elsewhen(
        (io.rdi.plStateSts === RDIState.retrain) && !liveStart &&
          !liveStartPending
      ) {
        // Follow RDI Retrain (sec 4.5.3.7.2), as the runtime link test does.
        nextState := LTState.sPHYRETRAIN
      }
    }
    is(LTState.sPHYRETRAIN) {
      // sec 4.5.3.7 holds Track, Data, Valid and Clock low, so they are driven.
      mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      mbLaneCtrlIo.txClkEn := true.B

      // No pattern runs here, so hold the clock pair low for the whole state.
      mbLaneCtrlIo.txClkHeldLow := true.B
      mbLaneCtrlIo.txValidEn := true.B
      mbLaneCtrlIo.txTrackEn := true.B
      mbLaneCtrlIo.rxClkEn := true.B
      currentStateSpecific := LTSMState.sPHYRETRAIN
      phyInRetrain := true.B

      activeReqSbLane <> retrainSbModule.io.requesterSbLaneIo
      activeRespSbLane <> retrainSbModule.io.responderSbLaneIo

      retrainSbModule.io.startPhyRetrainMsgExch := true.B
      retrainSbModule.io.waitForRemoteRequest := true.B

      when(retrainSbModule.io.done) {
        resolutionDone := false.B
        mbTrainSM.io.goToState.valid := true.B
        nextState := LTState.sMBTRAIN
      }.elsewhen(transitionToTrainError) {
        resolutionDone := false.B
        nextState := LTState.sTRAINERROR
      }
    }
    is(LTState.sTRAINERROR) {
      currentStateSpecific := LTSMState.sTRAINERROR

      // Freeze last, so LinkError messages still go out (sec 4.5.3.8).
      io.sbCtrlIo.freezeAcceptingPackets := responderFinished &&
        (io.rdi.plStateSts =/= RDIState.linkError) && !forceRdiLinkError

      when(transitionToReset) {
        nextState := LTState.sRESET
      }
    }
    is(LTState.sL1_L2) {
      currentStateSpecific := LTSMState.sL1_L2

      // TODO: Need to implement power management state logic
      nextState := LTState.sTRAINERROR
    }
  }

  // Note: Keep this after the state switch, because the last connect wins.
  when(io.linkOpParamOverride) {
    txPtReqIntf.linkTrainingParameters := io.linkTrainingParameters
    txEwReqIntf.linkTrainingParameters := io.linkTrainingParameters
    rxPtReqIntf.linkTrainingParameters := io.linkTrainingParameters
    rxEwReqIntf.linkTrainingParameters := io.linkTrainingParameters
  }

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      val activeWriterClients = PopCount(patternWriterClients.map {
        case (isUsing, _) => isUsing
      })
      assert(
        activeWriterClients <= 1.U,
        "FATAL: Multiple clients are trying to use the Pattern Writer at the same time"
      )

      val activeReaderClients = PopCount(patternReaderClients.map {
        case (isUsing, _) => isUsing
      })
      assert(
        activeReaderClients <= 1.U,
        "FATAL: Multiple clients are trying to use the Pattern Reader at the same time"
      )
    }
  }

  block(Verification) {
    block(Verification.Assert) {
      val activeReaderClients = PopCount(sidebandClients.map(_.rx.ready))
      assert(
        activeReaderClients <= 1.U,
        "FATAL: Multiple modules asserting RX ready for a Sideband message at the same time"
      )
    }
  }
}
