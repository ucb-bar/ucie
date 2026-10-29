/*
  The MBTRAIN state machine (sec 4.5.3.4), for a single module.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.common._
import chisel3._
import chisel3.util._

object MBTrainState extends ChiselEnum {
  val sVALVREF, sDATAVREF, sSPEEDIDLE, sTXSELFCAL, sRXCLKCAL, sVALTRAINCENTER,
      sVALTRAINVREF, sDATATRAINCENTER1, sDATATRAINVREF, sRXDESKEW,
      sDATATRAINCENTER2, sLINKSPEED, sREPAIR, sTOPHYRETRAIN, sTOLINKINIT = Value
}

object MBTrainGoToState extends ChiselEnum {
  val goToSPEEDIDLE, goToTXSELFCAL, goToREPAIR = Value
}
object MBTrainSubstate extends ChiselEnum {
  val s0, s1, s2, s3, s4, s5, s6, s7, s8, s9, s10 = Value
}

class MBTrainSM(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val goToState = Flipped(Valid(MBTrainGoToState()))
    val negotiatedMaxDataRate = Input(SpeedMode())
    val pllLock = Input(Bool())
    val phyInRetrain = Input(Bool())
    val interpretBy8Lane = Input(Bool())
    val maxErrorThresholdPerLane = Input(UInt(16.W))
    val changeInRuntimeLinkCtrlRegs = Input(Bool())
    val currLocalTxFunctionalLanes = Input(UInt(3.W))
    val currRemoteTxFunctionalLanes = Input(UInt(3.W))
    val swDegradeTarget = Input(Valid(UInt(3.W)))
    val pauseAfterValVrefStart = Input(Bool())

    val pausedAtValVrefStart = Output(Bool())
    val pauseSidebandResume = Output(Bool())
    val currentState = Output(MBTrainState())
    val mbLaneCtrlIo = new MainbandLaneCtrlIO(afeParams)
    val freqSel = Valid(SpeedMode())
    val doElectricalIdleTx = Output(Bool())
    val clearPhyInRetrainFlag = Output(Bool())
    val clearRuntimeLinkCtrlBusyBit = Output(Bool())
    val txWidthChanged = Output(Bool())
    val newLocalFunctionalLanes = Output(UInt(3.W))
    val rxClkCalSendFwClkPattern = Output(Bool())
    val rxClkCalSendTrkPattern = Output(Bool())
    val rxClkCalStart = Output(Bool())
    val newRemoteFunctionalLanes = Output(UInt(3.W))
    val rxWidthChanged = Output(Bool())
    val doElectricalIdleRx = Output(Bool())

    val fsmCtrl = new SubFsmControlIO()
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
    val txPtTestReqIntfIo = new TxInitPtTestRequesterInterfaceIO(afeParams)
    val txEyeSweepReqIntfIo =
      new TxInitEyeWidthSweepRequesterInterfaceIO(afeParams)
    val rxPtTestReqIntfIo = new RxInitPtTestRequesterInterfaceIO(afeParams)
    val rxEyeSweepReqIntfIo =
      new RxInitEyeWidthSweepRequesterInterfaceIO(afeParams)
    val txPtTestRespIntfIo = new TxInitPtTestResponderInterfaceIO()
    val txEyeSweepRespIntfIo = new TxInitEyeWidthSweepResponderInterfaceIO()
    val rxPtTestRespIntfIo = new RxInitPtTestResponderInterfaceIO()
    val rxEyeSweepRespIntfIo =
      new RxInitEyeWidthSweepResponderInterfaceIO(afeParams)
    val trainingCtrl = new MbTrainBridgeIO(afeParams)
  })

  // ===========================================================================
  // Requester and responder
  // ===========================================================================
  val requester = Module(new MBTrainRequester(afeParams, sbParams))
  val responder = Module(new MBTrainResponder(afeParams, sbParams))

  requester.io.start := io.fsmCtrl.start
  requester.io.responderRdy := responder.io.responderRdy
  requester.io.pauseAfterValVrefStart := io.pauseAfterValVrefStart
  responder.io.pauseAfterValVrefStart := io.pauseAfterValVrefStart
  io.pausedAtValVrefStart :=
    requester.io.pausedAtValVrefStart || responder.io.pausedAtValVrefStart
  io.pauseSidebandResume :=
    requester.io.pauseSidebandResume || responder.io.pauseSidebandResume
  requester.io.goToState := io.goToState
  requester.io.maxErrorThresholdPerLane := io.maxErrorThresholdPerLane
  requester.io.negotiatedMaxDataRate := io.negotiatedMaxDataRate
  requester.io.pllLock := io.pllLock
  requester.io.interpretBy8Lane := io.interpretBy8Lane
  requester.io.phyInRetrain := io.phyInRetrain
  requester.io.changeInRuntimeLinkCtrlRegs := io.changeInRuntimeLinkCtrlRegs
  requester.io.currLocalTxFunctionalLanes := io.currLocalTxFunctionalLanes
  requester.io.swDegradeTarget := io.swDegradeTarget
  requester.io.remoteExitingToPhyretrain := responder.io.remoteExitingToPhyretrain
  requester.io.remoteExitingToSpeedDegrade := responder.io.remoteExitingToSpeedDegrade
  requester.io.remoteExitingToRepair := responder.io.remoteExitingToRepair
  requester.io.remoteErrorInLinkspeed := responder.io.remoteErrorInLinkspeed
  requester.io.sbLaneIo <> io.requesterSbLaneIo
  requester.io.txPtTestReqIntfIo <> io.txPtTestReqIntfIo
  requester.io.txEyeSweepReqIntfIo <> io.txEyeSweepReqIntfIo
  requester.io.rxPtTestReqIntfIo <> io.rxPtTestReqIntfIo
  requester.io.rxEyeSweepReqIntfIo <> io.rxEyeSweepReqIntfIo

  responder.io.start := io.fsmCtrl.start
  responder.io.currRemoteTxFunctionalLanes := io.currRemoteTxFunctionalLanes
  responder.io.goToState := io.goToState
  responder.io.requesterRdy := requester.io.requesterRdy
  responder.io.localInitiatingError := requester.io.initiatingError
  responder.io.localInitiatingWidthDegrade := requester.io.initiatingWidthDegrade
  responder.io.localInitiatingSpeedDegrade := requester.io.initiatingSpeedDegrade
  responder.io.localInitiatingDone := requester.io.initiatingDone
  responder.io.localInitiatingExitToPhyRetrain := requester.io.initiatingExitToPhyretrain
  responder.io.localCompletedSteps1And2 := requester.io.completedLinkspeedStep1And2
  responder.io.sbLaneIo <> io.responderSbLaneIo
  responder.io.txPtTestRespIntfIo <> io.txPtTestRespIntfIo
  responder.io.txEyeSweepRespIntfIo <> io.txEyeSweepRespIntfIo
  responder.io.rxPtTestRespIntfIo <> io.rxPtTestRespIntfIo
  responder.io.rxEyeSweepRespIntfIo <> io.rxEyeSweepRespIntfIo

  // TODO: Add an assertion that both halves have the same currentState.
  io.fsmCtrl.done := requester.io.done && responder.io.done
  io.fsmCtrl.error := requester.io.error | responder.io.error
  io.fsmCtrl.substateTransitioning := requester.io.transitioningState
  io.fsmCtrl.stallReceived := requester.io.stallReceived
  io.currentState := requester.io.currentState
  io.mbLaneCtrlIo := requester.io.mbLaneCtrlIo
  io.freqSel := requester.io.freqSel
  io.doElectricalIdleTx := requester.io.doElectricalIdleTx
  io.clearPhyInRetrainFlag := requester.io.clearPhyInRetrainFlag
  io.clearRuntimeLinkCtrlBusyBit := requester.io.clearRuntimeLinkCtrlBusyBit
  io.txWidthChanged := requester.io.txWidthChanged
  io.newLocalFunctionalLanes := requester.io.newLocalFunctionalLanes
  io.rxClkCalSendFwClkPattern := responder.io.rxClkCalSendFwClkPattern
  io.rxClkCalSendTrkPattern := responder.io.rxClkCalSendTrkPattern
  io.rxClkCalStart := requester.io.rxClkCalStart
  io.newRemoteFunctionalLanes := responder.io.newRemoteFunctionalLanes
  io.rxWidthChanged := responder.io.rxWidthChanged
  io.doElectricalIdleRx := responder.io.doElectricalIdleRx

  // ===========================================================================
  // Bridge
  // ===========================================================================
  // VALVREF, VALTRAINCENTER and VALTRAINVREF compare slot 0, so others pass.
  def toSlots(st: MBTrainState.Type, r: Vec[UInt]): PhyTrainResult = {
    val valTrain = st === MBTrainState.sVALVREF ||
      st === MBTrainState.sVALTRAINCENTER || st === MBTrainState.sVALTRAINVREF
    val out = Wire(new PhyTrainResult(afeParams))
    out.pass := VecInit(r.map(_.asBool || valTrain))
    out.validPass := !valTrain || r(0).asBool
    out
  }

  // Results have no back pressure, so the queue holds each one for the bridge.
  val resultQueue = Module(new Queue(new PhyTrainResult(afeParams), 1))
  val resultCaptured = RegInit(false.B)
  val legacyResultValid = requester.io.trainingCtrl.resp.results.valid
  resultQueue.io.enq.valid := legacyResultValid && !resultCaptured
  resultQueue.io.enq.bits := toSlots(
    requester.io.currentState,
    requester.io.trainingCtrl.resp.results.bits
  )
  when(!legacyResultValid) {
    resultCaptured := false.B
  }.elsewhen(resultQueue.io.enq.fire) {
    resultCaptured := true.B
  }
  io.trainingCtrl.result <> resultQueue.io.deq

  val peerQueue = Module(new Queue(new PhyTrainResult(afeParams), 1))
  val peerCaptured = RegInit(false.B)
  val peerResultValid = responder.io.remoteRxSweepResults.valid
  peerQueue.io.enq.valid := peerResultValid && !peerCaptured
  peerQueue.io.enq.bits := toSlots(
    responder.io.currentState,
    responder.io.remoteRxSweepResults.bits
  )
  when(!peerResultValid) {
    peerCaptured := false.B
  }.elsewhen(peerQueue.io.enq.fire) {
    peerCaptured := true.B
  }
  io.trainingCtrl.remoteResult <> peerQueue.io.deq

  val isFinish =
    io.trainingCtrl.action.bits.kind === PhyTrainActionKind.FinishState
  io.trainingCtrl.action.ready :=
    requester.io.trainingCtrl.req.readyForReq && !resultQueue.io.deq.valid
  val actionFire = io.trainingCtrl.action.fire
  requester.io.trainingCtrl.req.start := actionFire && !isFinish
  requester.io.trainingCtrl.req.complete := actionFire && isFinish
  requester.io.trainingCtrl.req.testKind := io.trainingCtrl.action.bits.testKind
  requester.io.trainingCtrl.req.clockPhase :=
    io.trainingCtrl.action.bits.clockPhase
}

class MBTrainRequester(afeParams: AfeParams, sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val responderRdy = Input(Bool())
    val goToState = Flipped(Valid(MBTrainGoToState()))
    val maxErrorThresholdPerLane = Input(UInt(16.W))
    val negotiatedMaxDataRate = Input(SpeedMode())
    val pllLock = Input(Bool())
    val interpretBy8Lane = Input(Bool())
    val phyInRetrain = Input(Bool())
    val changeInRuntimeLinkCtrlRegs = Input(Bool())
    val currLocalTxFunctionalLanes = Input(UInt(3.W))
    val swDegradeTarget = Input(Valid(UInt(3.W)))
    val remoteExitingToPhyretrain = Input(Bool())
    val remoteExitingToSpeedDegrade = Input(Bool())
    val remoteExitingToRepair = Input(Bool())
    val remoteErrorInLinkspeed = Input(Bool())
    val pauseAfterValVrefStart = Input(Bool())

    val pausedAtValVrefStart = Output(Bool())
    val pauseSidebandResume = Output(Bool())
    val done = Output(Bool())
    val error = Output(Bool())
    val requesterRdy = Output(Bool())
    val currentState = Output(MBTrainState())
    val rxClkCalStart = Output(Bool())
    val transitioningState = Output(Bool())
    val freqSel =
      Valid(SpeedMode())
    val doElectricalIdleTx = Output(Bool())
    val completedLinkspeedStep1And2 = Output(Bool())
    val initiatingError = Output(Bool())
    val initiatingExitToPhyretrain = Output(Bool())
    val clearPhyInRetrainFlag = Output(Bool())
    val clearRuntimeLinkCtrlBusyBit = Output(Bool())
    val initiatingSpeedDegrade = Output(Bool())
    val initiatingWidthDegrade = Output(Bool())
    val initiatingDone = Output(Bool())
    val stallReceived = Output(Bool())
    val txWidthChanged = Output(Bool())
    val newLocalFunctionalLanes = Output(UInt(3.W))
    val mbLaneCtrlIo = new MainbandLaneCtrlIO(afeParams)

    val sbLaneIo = new SidebandLaneIO(sbParams)
    val txPtTestReqIntfIo = new TxInitPtTestRequesterInterfaceIO(afeParams)
    val txEyeSweepReqIntfIo =
      new TxInitEyeWidthSweepRequesterInterfaceIO(afeParams)
    val rxPtTestReqIntfIo = new RxInitPtTestRequesterInterfaceIO(afeParams)
    val rxEyeSweepReqIntfIo =
      new RxInitEyeWidthSweepRequesterInterfaceIO(afeParams)
    val trainingCtrl = Flipped(new MbTrainRequesterCtrlIO(afeParams))
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // ===========================================================================
  // State, pause and error
  // ===========================================================================
  val currentState = RegInit(MBTrainState.sVALVREF)
  val nextState = WireInit(currentState)
  currentState := nextState
  io.currentState := currentState
  io.transitioningState := currentState =/= nextState

  val substateReg = RegInit(MBTrainSubstate.s0)
  val nextSubstate = WireInit(substateReg)
  substateReg := nextSubstate

  val requesterRdyStatusReg = RegInit(false.B)
  val requesterRdy = WireInit(false.B)

  // The clear wins, because a rdy in the transition cycle is the old state's.
  when((currentState =/= nextState) || (substateReg =/= nextSubstate)) {
    requesterRdyStatusReg := false.B
  }.elsewhen(requesterRdy) {
    requesterRdyStatusReg := true.B
  }
  io.requesterRdy := requesterRdyStatusReg || requesterRdy

  // Both halves hold, because each boundary needs both rdy bits.
  // The latch gives the LTSM timeout gate a stable level.
  val valVrefPauseHeld = RegInit(false.B)
  when(io.pauseAfterValVrefStart) {
    when(sbMsgExchanger.io.exchDone) { valVrefPauseHeld := true.B }
  }.otherwise {
    valVrefPauseHeld := false.B
  }
  io.pausedAtValVrefStart := valVrefPauseHeld
  io.pauseSidebandResume := valVrefPauseHeld && io.sbLaneIo.rx.valid &&
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.MBTRAIN_VALVREF_END_REQ)

  val errorDetectedReg = RegInit(false.B)
  val errorDetectedWire = WireInit(false.B)
  val errorDetected = Wire(Bool())

  errorDetected := errorDetectedReg || errorDetectedWire
  when(errorDetectedWire) {
    errorDetectedReg := true.B
  }
  io.error := errorDetectedReg

  // ===========================================================================
  // Sideband
  // ===========================================================================
  SidebandMessageExchanger.tieOff(sbMsgExchanger)

  // SBMsgCompare ignores MsgInfo, so detect the FFFFh Stall here (Table 7-9).
  val linkspeedDoneRespIsStall =
    (currentState === MBTrainState.sLINKSPEED) &&
      (substateReg === MBTrainSubstate.s6) &&
      sbMsgExchanger.io.resp.valid &&
      SBMsgField.msgInfo(sbMsgExchanger.io.resp.bits) === "hFFFF".U

  // Clear the exchanger after a Stall, which safely sends the done req again.
  val stallPending = RegInit(false.B)
  when(linkspeedDoneRespIsStall) {
    stallPending := true.B
  }.elsewhen(stallPending) {
    stallPending := false.B
  }
  io.stallReceived := linkspeedDoneRespIsStall

  sbMsgExchanger.io.clear := (currentState =/= nextState) ||
    (substateReg =/= nextSubstate) || stallPending
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // ===========================================================================
  // Defaults
  // ===========================================================================
  io.mbLaneCtrlIo.txDataEn.foreach(_ := false.B)
  io.mbLaneCtrlIo.txClkEn := false.B
  io.mbLaneCtrlIo.txValidEn := false.B
  io.mbLaneCtrlIo.txTrackEn := false.B
  io.mbLaneCtrlIo.txClkHeldLow := false.B
  io.mbLaneCtrlIo.txTrackPattern := false.B
  io.mbLaneCtrlIo.txTrackLevel := false.B
  io.mbLaneCtrlIo.rxDataEn.foreach(x => x := false.B)
  io.mbLaneCtrlIo.rxClkEn := false.B
  io.mbLaneCtrlIo.rxValidEn := false.B

  // The spec permits the Track Receiver to stay disabled.
  io.mbLaneCtrlIo.rxTrackEn := false.B

  io.txPtTestReqIntfIo.start := false.B
  io.txPtTestReqIntfIo.patternType := PatternSelect.VALTRAIN
  io.txPtTestReqIntfIo.linkTrainingParameters.clockPhase := 0.U
  io.txPtTestReqIntfIo.linkTrainingParameters.validPattern := 0.U
  io.txPtTestReqIntfIo.linkTrainingParameters.patternMode := 0.U
  io.txPtTestReqIntfIo.linkTrainingParameters.maxErrorThreshold := io.maxErrorThresholdPerLane
  io.txPtTestReqIntfIo.linkTrainingParameters.comparisonMode := 0.U

  io.txEyeSweepReqIntfIo.start := false.B
  io.txEyeSweepReqIntfIo.patternType := PatternSelect.VALTRAIN
  io.txEyeSweepReqIntfIo.linkTrainingParameters.clockPhase := 0.U
  io.txEyeSweepReqIntfIo.linkTrainingParameters.validPattern := 0.U
  io.txEyeSweepReqIntfIo.linkTrainingParameters.patternMode := 0.U
  io.txEyeSweepReqIntfIo.linkTrainingParameters.maxErrorThreshold := io.maxErrorThresholdPerLane
  io.txEyeSweepReqIntfIo.linkTrainingParameters.comparisonMode := 0.U

  io.rxPtTestReqIntfIo.start := false.B
  io.rxPtTestReqIntfIo.patternType := PatternSelect.VALTRAIN
  io.rxPtTestReqIntfIo.linkTrainingParameters.clockPhase := 0.U
  io.rxPtTestReqIntfIo.linkTrainingParameters.validPattern := 0.U
  io.rxPtTestReqIntfIo.linkTrainingParameters.patternMode := 0.U
  io.rxPtTestReqIntfIo.linkTrainingParameters.maxErrorThreshold := io.maxErrorThresholdPerLane
  io.rxPtTestReqIntfIo.linkTrainingParameters.comparisonMode := 0.U

  io.rxEyeSweepReqIntfIo.start := false.B
  io.rxEyeSweepReqIntfIo.patternType := PatternSelect.VALTRAIN
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.clockPhase := 0.U
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.validPattern := 0.U
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.patternMode := 0.U
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.maxErrorThreshold := io.maxErrorThresholdPerLane
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.comparisonMode := 0.U

  // ===========================================================================
  // Speed change
  // ===========================================================================
  val fromDataVref = RegInit(false.B)
  val fromLinkspeed = RegInit(false.B)
  val currFreqSel = RegInit(SpeedMode.speed4)
  val speedChanged = RegInit(false.B)
  val isCurrFreqGreaterThan32 = Wire(Bool())
  val prevState = RegInit(0.U(2.W)) // 1.U == L1; 2.U == PHYRETRAIN

  isCurrFreqGreaterThan32 := (currFreqSel === SpeedMode.speed48) ||
    (currFreqSel === SpeedMode.speed64)

  when(io.freqSel.valid) {
    currFreqSel := io.freqSel.bits

    // pllLock is for the old rate until the PHY takes the new one.
    when(io.pllLock && (currFreqSel === io.freqSel.bits)) {
      speedChanged := true.B
    }
  }

  io.freqSel.valid := false.B
  io.freqSel.bits := SpeedMode.speed4

  // ===========================================================================
  // Calibration
  // ===========================================================================
  val mbTrainTxSelfCalDoneReg = RegInit(false.B)
  when(
    io.trainingCtrl.req.complete && currentState === MBTrainState.sTXSELFCAL
  ) {
    mbTrainTxSelfCalDoneReg := true.B
  }

  // The partner starts its clock on the start req, so calibrate after the resp.
  io.rxClkCalStart := currentState === MBTrainState.sRXCLKCAL &&
    substateReg =/= MBTrainSubstate.s0
  val mbTrainRxClkCalDoneReg = RegInit(false.B)
  when(
    io.trainingCtrl.req.complete && currentState === MBTrainState.sRXCLKCAL
  ) {
    mbTrainRxClkCalDoneReg := true.B
  }

  // ===========================================================================
  // LINKSPEED and REPAIR
  // ===========================================================================
  val clearRuntimeLinkCtrlBusyBit = WireInit(false.B)
  val clearPhyInRetrainFlag = WireInit(false.B)
  val initiatingExitToPhyretrainFlag = RegInit(false.B)
  val completedLinkspeedStep1And2Flag = RegInit(false.B)
  val initiatingErrorFlag = RegInit(false.B)
  val initiatingSpeedDegradeFlag = RegInit(false.B)
  val initiatingWidthDegradeFlag = RegInit(false.B)
  val initiatingDoneFlag = RegInit(false.B)

  val currLocalTxFunctionalLanes = Wire(
    UInt(3.W)
  )
  val newTxFunctionalLanes = Wire(
    UInt(3.W)
  )
  val laneHasErrors = Wire(Bool())
  val faultInLowerLanes = Wire(Bool())
  val faultInUpperLanes = Wire(Bool())
  val isWidthDegradePossible = Wire(Bool())
  val linkOpsResults = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))
  val linkOpsResultsValid = RegInit(false.B)

  currLocalTxFunctionalLanes := io.currLocalTxFunctionalLanes

  val (lowerFault, upperFault) = PatternLaneMap.faultHalves(
    currLocalTxFunctionalLanes,
    linkOpsResults.map(_.asBool),
    afeParams.mbLanes,
    io.interpretBy8Lane
  )
  faultInLowerLanes := lowerFault
  faultInUpperLanes := upperFault

  newTxFunctionalLanes := PatternLaneMap.degradeTarget(
    currLocalTxFunctionalLanes,
    faultInLowerLanes,
    faultInUpperLanes,
    io.interpretBy8Lane
  )

  laneHasErrors := linkOpsResultsValid &&
    (newTxFunctionalLanes =/= currLocalTxFunctionalLanes)
  isWidthDegradePossible := (newTxFunctionalLanes =/= "b000".U)

  val degradeToApply = Mux(
    io.swDegradeTarget.valid,
    io.swDegradeTarget.bits,
    newTxFunctionalLanes
  )

  io.initiatingError := initiatingErrorFlag
  io.clearPhyInRetrainFlag := clearPhyInRetrainFlag
  io.clearRuntimeLinkCtrlBusyBit := clearRuntimeLinkCtrlBusyBit
  io.initiatingExitToPhyretrain := initiatingExitToPhyretrainFlag
  io.completedLinkspeedStep1And2 := completedLinkspeedStep1And2Flag
  io.initiatingSpeedDegrade := initiatingSpeedDegradeFlag
  io.initiatingWidthDegrade := initiatingWidthDegradeFlag
  io.initiatingDone := initiatingDoneFlag
  io.doElectricalIdleTx := false.B

  val applyDegrade = WireInit(false.B)

  // Note: Do not use laneHasErrors, because a software degrade runs no test.
  io.txWidthChanged := applyDegrade &&
    (degradeToApply =/= currLocalTxFunctionalLanes)
  io.newLocalFunctionalLanes := degradeToApply

  // ===========================================================================
  // State machine
  // ===========================================================================
  io.done := false.B

  io.trainingCtrl.req.readyForReq := false.B
  io.trainingCtrl.resp.inProgress := false.B
  io.trainingCtrl.resp.done := false.B
  io.trainingCtrl.resp.results.valid := false.B
  io.trainingCtrl.resp.results.bits := 0.U.asTypeOf(
    io.trainingCtrl.resp.results.bits
  )

  switch(currentState) {
    is(MBTrainState.sVALVREF) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B

      // sec 4.5.3.4.1 holds the clock low between patterns in every clock mode.
      io.mbLaneCtrlIo.txClkHeldLow := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          when(io.start) {
            errorDetectedReg := false.B
            fromDataVref := false.B
            fromLinkspeed := false.B
            speedChanged := false.B
            mbTrainTxSelfCalDoneReg := false.B
            mbTrainRxClkCalDoneReg := false.B
            prevState := 0.U
            currFreqSel := SpeedMode.speed4

            sbMsgExchanger.io.req.valid := true.B
            sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
              SBM.MBTRAIN_VALVREF_START_REQ
            )
            sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
            sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALVREF_START_RESP

            when(
              (sbMsgExchanger.io.exchDone || valVrefPauseHeld) &&
                !io.pauseAfterValVrefStart
            ) {
              nextSubstate := MBTrainSubstate.s1
            }
          }
        }
        is(MBTrainSubstate.s1) { // START RX-INITIATED LINK OPERATION
          io.trainingCtrl.req.readyForReq := true.B

          io.trainingCtrl.resp.inProgress := false.B
          io.trainingCtrl.resp.done := false.B

          io.rxPtTestReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.PointTest
          io.rxPtTestReqIntfIo.patternType := PatternSelect.VALTRAIN

          io.rxEyeSweepReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep
          io.rxEyeSweepReqIntfIo.patternType := PatternSelect.VALTRAIN

          when(
            io.trainingCtrl.req.start &&
              ((io.trainingCtrl.req.testKind === TrainingTestType.PointTest) ||
                (io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep))
          ) {
            nextSubstate := MBTrainSubstate.s2
          }.elsewhen(io.trainingCtrl.req.complete) {
            nextSubstate := MBTrainSubstate.s3
          }
        }
        is(MBTrainSubstate.s2) { // TEST IN PROGRESS
          io.trainingCtrl.resp.inProgress := true.B
          io.trainingCtrl.resp.results.valid := io.rxPtTestReqIntfIo.ptTestResults.valid ||
            io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid

          when(io.rxPtTestReqIntfIo.ptTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxPtTestReqIntfIo.ptTestResults.bits
          }.elsewhen(io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxEyeSweepReqIntfIo.eyeSweepTestResults.bits
          }

          when(io.rxPtTestReqIntfIo.done || io.rxEyeSweepReqIntfIo.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s3) { // END
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALVREF_END_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALVREF_END_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATAVREF
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sDATAVREF) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txClkHeldLow := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      fromDataVref := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATAVREF_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATAVREF_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // START RX-INITIATED LINK OPERATION
          io.trainingCtrl.req.readyForReq := true.B

          io.trainingCtrl.resp.inProgress := false.B
          io.trainingCtrl.resp.done := false.B

          io.rxPtTestReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.PointTest
          io.rxPtTestReqIntfIo.patternType := PatternSelect.LFSR

          io.rxEyeSweepReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep
          io.rxEyeSweepReqIntfIo.patternType := PatternSelect.LFSR

          when(
            io.trainingCtrl.req.start &&
              ((io.trainingCtrl.req.testKind === TrainingTestType.PointTest) ||
                (io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep))
          ) {
            nextSubstate := MBTrainSubstate.s2
          }.elsewhen(io.trainingCtrl.req.complete) {
            nextSubstate := MBTrainSubstate.s3
          }
        }
        is(MBTrainSubstate.s2) { // TEST IN PROGRESS
          io.trainingCtrl.resp.inProgress := true.B
          io.trainingCtrl.resp.results.valid := io.rxPtTestReqIntfIo.ptTestResults.valid ||
            io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid

          when(io.rxPtTestReqIntfIo.ptTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxPtTestReqIntfIo.ptTestResults.bits
          }.elsewhen(io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxEyeSweepReqIntfIo.eyeSweepTestResults.bits
          }

          when(io.rxPtTestReqIntfIo.done || io.rxEyeSweepReqIntfIo.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s3) { // END
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATAVREF_END_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATAVREF_END_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sSPEEDIDLE
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sSPEEDIDLE) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txClkHeldLow := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        // sec 4.5.3.4.3 rule 1 picks the rate by the state before SPEEDIDLE.
        is(MBTrainSubstate.s0) { // SET SPEED
          when(fromDataVref) {
            io.freqSel.bits := io.negotiatedMaxDataRate
            io.freqSel.valid := true.B
          }.elsewhen(prevState === 1.U) {
            io.freqSel.bits := currFreqSel
            io.freqSel.valid := true.B
          }.elsewhen(
            ((prevState === 2.U) || fromLinkspeed) && (currFreqSel =/= SpeedMode.speed4)
          ) {
            io.freqSel.bits := (currFreqSel.asUInt - 1.U).asTypeOf(SpeedMode())
            io.freqSel.valid := true.B
          }.otherwise {
            errorDetectedWire := true.B
          }

          when(!errorDetected && speedChanged) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_SPEEDIDLE_DONE_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_SPEEDIDLE_DONE_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sTXSELFCAL
            nextSubstate := MBTrainSubstate.s0

            prevState := 0.U
            fromDataVref := false.B
            fromLinkspeed := false.B
            speedChanged := false.B
          }
        }
      }
    }
    is(MBTrainState.sTXSELFCAL) {
      // sec 4.5.3.4.4 tri-states the Transmitters, so the lane defaults stay.
      io.trainingCtrl.req.readyForReq := !mbTrainTxSelfCalDoneReg
      sbMsgExchanger.io.req.valid := mbTrainTxSelfCalDoneReg
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.MBTRAIN_TXSELFCAL_DONE_REQ
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_TXSELFCAL_DONE_RESP

      requesterRdy := sbMsgExchanger.io.exchDone
      when(io.requesterRdy && io.responderRdy) {
        nextState := MBTrainState.sRXCLKCAL
        mbTrainTxSelfCalDoneReg := false.B
      }
    }
    is(MBTrainState.sRXCLKCAL) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B

      // Track carries the sec 5.5.1 pattern in RXCLKCAL (sec 4.5.3.4.5 step 1).
      io.mbLaneCtrlIo.txTrackPattern := true.B
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_RXCLKCAL_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXCLKCAL_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            when(isCurrFreqGreaterThan32) {
              nextSubstate := MBTrainSubstate.s1
            }.otherwise {
              nextSubstate := MBTrainSubstate.s3
            }
          }
        }

        // TODO: Put the > 32 GT/s I/Q correction (sec 4.5.3.4.5) in s1 and s2.
        is(MBTrainSubstate.s1) { // CALIBRATION FOR > 32 GT/S
          errorDetectedWire := true.B
        }
        is(MBTrainSubstate.s3) { // DONE
          io.trainingCtrl.req.readyForReq := !mbTrainRxClkCalDoneReg
          sbMsgExchanger.io.req.valid := mbTrainRxClkCalDoneReg
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_RXCLKCAL_DONE_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXCLKCAL_DONE_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sVALTRAINCENTER
            nextSubstate := MBTrainSubstate.s0
            mbTrainRxClkCalDoneReg := false.B
          }
        }
      }
    }
    is(MBTrainState.sVALTRAINCENTER) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALTRAINCENTER_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINCENTER_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // START TX-INITIATED LINK OPERATION
          io.trainingCtrl.req.readyForReq := true.B

          io.trainingCtrl.resp.inProgress := false.B
          io.trainingCtrl.resp.done := false.B

          io.txPtTestReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.PointTest
          io.txPtTestReqIntfIo.patternType := PatternSelect.VALTRAIN

          io.txEyeSweepReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep
          io.txEyeSweepReqIntfIo.patternType := PatternSelect.VALTRAIN

          when(
            io.trainingCtrl.req.start &&
              ((io.trainingCtrl.req.testKind === TrainingTestType.PointTest) ||
                (io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep))
          ) {
            nextSubstate := MBTrainSubstate.s2
          }.elsewhen(io.trainingCtrl.req.complete) {
            nextSubstate := MBTrainSubstate.s3
          }
        }
        is(MBTrainSubstate.s2) { // TEST IN PROGRESS
          io.trainingCtrl.resp.inProgress := true.B
          io.trainingCtrl.resp.results.valid := io.txPtTestReqIntfIo.ptTestResults.valid ||
            io.txEyeSweepReqIntfIo.eyeSweepTestResults.valid

          when(io.txPtTestReqIntfIo.ptTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.txPtTestReqIntfIo.ptTestResults.bits
          }.elsewhen(io.txEyeSweepReqIntfIo.eyeSweepTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.txEyeSweepReqIntfIo.eyeSweepTestResults.bits
          }

          when(io.txPtTestReqIntfIo.done || io.txEyeSweepReqIntfIo.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s3) { // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALTRAINCENTER_DONE_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINCENTER_DONE_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sVALTRAINVREF
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sVALTRAINVREF) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALTRAINVREF_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINVREF_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // START RX-INITIATED LINK OPERATION
          io.trainingCtrl.req.readyForReq := true.B

          io.trainingCtrl.resp.inProgress := false.B
          io.trainingCtrl.resp.done := false.B

          io.rxPtTestReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.PointTest
          io.rxPtTestReqIntfIo.patternType := PatternSelect.VALTRAIN

          io.rxEyeSweepReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep
          io.rxEyeSweepReqIntfIo.patternType := PatternSelect.VALTRAIN

          when(
            io.trainingCtrl.req.start &&
              ((io.trainingCtrl.req.testKind === TrainingTestType.PointTest) ||
                (io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep))
          ) {
            nextSubstate := MBTrainSubstate.s2
          }.elsewhen(io.trainingCtrl.req.complete) {
            nextSubstate := MBTrainSubstate.s3
          }
        }
        is(MBTrainSubstate.s2) { // TEST IN PROGRESS
          io.trainingCtrl.resp.inProgress := true.B
          io.trainingCtrl.resp.results.valid := io.rxPtTestReqIntfIo.ptTestResults.valid ||
            io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid

          when(io.rxPtTestReqIntfIo.ptTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxPtTestReqIntfIo.ptTestResults.bits
          }.elsewhen(io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxEyeSweepReqIntfIo.eyeSweepTestResults.bits
          }

          when(io.rxPtTestReqIntfIo.done || io.rxEyeSweepReqIntfIo.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s3) { // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALTRAINVREF_DONE_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINVREF_DONE_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINCENTER1
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sDATATRAINCENTER1) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINCENTER1_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER1_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // START TX-INITIATED LINK OPERATION
          io.trainingCtrl.req.readyForReq := true.B

          io.trainingCtrl.resp.inProgress := false.B
          io.trainingCtrl.resp.done := false.B

          io.txPtTestReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.PointTest
          io.txPtTestReqIntfIo.patternType := PatternSelect.LFSR

          io.txEyeSweepReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep
          io.txEyeSweepReqIntfIo.patternType := PatternSelect.LFSR

          when(
            io.trainingCtrl.req.start &&
              ((io.trainingCtrl.req.testKind === TrainingTestType.PointTest) ||
                (io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep))
          ) {
            nextSubstate := MBTrainSubstate.s2
          }.elsewhen(io.trainingCtrl.req.complete) {
            nextSubstate := MBTrainSubstate.s3
          }
        }
        is(MBTrainSubstate.s2) { // TEST IN PROGRESS
          io.trainingCtrl.resp.inProgress := true.B
          io.trainingCtrl.resp.results.valid := io.txPtTestReqIntfIo.ptTestResults.valid ||
            io.txEyeSweepReqIntfIo.eyeSweepTestResults.valid

          when(io.txPtTestReqIntfIo.ptTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.txPtTestReqIntfIo.ptTestResults.bits
          }.elsewhen(io.txEyeSweepReqIntfIo.eyeSweepTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.txEyeSweepReqIntfIo.eyeSweepTestResults.bits
          }

          when(io.txPtTestReqIntfIo.done || io.txEyeSweepReqIntfIo.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s3) { // END
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINCENTER1_END_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER1_END_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINVREF
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sDATATRAINVREF) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINVREF_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINVREF_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // START RX-INITIATED LINK OPERATION
          io.trainingCtrl.req.readyForReq := true.B

          io.trainingCtrl.resp.inProgress := false.B
          io.trainingCtrl.resp.done := false.B

          io.rxPtTestReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.PointTest
          io.rxPtTestReqIntfIo.patternType := PatternSelect.LFSR

          io.rxEyeSweepReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep
          io.rxEyeSweepReqIntfIo.patternType := PatternSelect.LFSR

          when(
            io.trainingCtrl.req.start &&
              ((io.trainingCtrl.req.testKind === TrainingTestType.PointTest) ||
                (io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep))
          ) {
            nextSubstate := MBTrainSubstate.s2
          }.elsewhen(io.trainingCtrl.req.complete) {
            nextSubstate := MBTrainSubstate.s3
          }
        }
        is(MBTrainSubstate.s2) { // TEST IN PROGRESS
          io.trainingCtrl.resp.inProgress := true.B
          io.trainingCtrl.resp.results.valid := io.rxPtTestReqIntfIo.ptTestResults.valid ||
            io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid

          when(io.rxPtTestReqIntfIo.ptTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxPtTestReqIntfIo.ptTestResults.bits
          }.elsewhen(io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxEyeSweepReqIntfIo.eyeSweepTestResults.bits
          }

          when(io.rxPtTestReqIntfIo.done || io.rxEyeSweepReqIntfIo.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s3) { // END
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINVREF_END_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINVREF_END_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sRXDESKEW
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sRXDESKEW) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_RXDESKEW_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXDESKEW_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            when(isCurrFreqGreaterThan32) {
              nextSubstate := MBTrainSubstate.s1
            }.otherwise {
              nextSubstate := MBTrainSubstate.s2
            }
          }
        }

        // TODO: Put the > 32 GT/s EQ preset request (sec 4.5.3.4.10) in s1.
        is(MBTrainSubstate.s1) { // EQ PRESET FOR > 32 GT/S
          errorDetectedWire := true.B
        }
        is(MBTrainSubstate.s2) { // START RX-INITIATED LINK OPERATION
          io.trainingCtrl.req.readyForReq := true.B

          io.trainingCtrl.resp.inProgress := false.B
          io.trainingCtrl.resp.done := false.B

          io.rxPtTestReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.PointTest
          io.rxPtTestReqIntfIo.patternType := PatternSelect.LFSR
          io.rxPtTestReqIntfIo.linkTrainingParameters.clockPhase :=
            io.trainingCtrl.req.clockPhase.asUInt

          io.rxEyeSweepReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep
          io.rxEyeSweepReqIntfIo.patternType := PatternSelect.LFSR

          when(
            io.trainingCtrl.req.start &&
              ((io.trainingCtrl.req.testKind === TrainingTestType.PointTest) ||
                (io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep))
          ) {
            nextSubstate := MBTrainSubstate.s3
          }.elsewhen(io.trainingCtrl.req.complete) {
            nextSubstate := MBTrainSubstate.s4
          }
        }
        is(MBTrainSubstate.s3) { // TEST IN PROGRESS
          io.trainingCtrl.resp.inProgress := true.B
          io.trainingCtrl.resp.results.valid := io.rxPtTestReqIntfIo.ptTestResults.valid ||
            io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid

          when(io.rxPtTestReqIntfIo.ptTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxPtTestReqIntfIo.ptTestResults.bits
          }.elsewhen(io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.rxEyeSweepReqIntfIo.eyeSweepTestResults.bits
          }

          when(io.rxPtTestReqIntfIo.done || io.rxEyeSweepReqIntfIo.done) {
            nextSubstate := MBTrainSubstate.s2
          }
        }
        is(MBTrainSubstate.s4) { // END
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_RXDESKEW_END_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXDESKEW_END_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINCENTER2
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sDATATRAINCENTER2) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINCENTER2_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER2_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // START TX-INITIATED LINK OPERATION
          io.trainingCtrl.req.readyForReq := true.B

          io.trainingCtrl.resp.inProgress := false.B
          io.trainingCtrl.resp.done := false.B

          io.txPtTestReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.PointTest
          io.txPtTestReqIntfIo.patternType := PatternSelect.LFSR

          io.txEyeSweepReqIntfIo.start := io.trainingCtrl.req.start &&
            io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep
          io.txEyeSweepReqIntfIo.patternType := PatternSelect.LFSR

          when(
            io.trainingCtrl.req.start &&
              ((io.trainingCtrl.req.testKind === TrainingTestType.PointTest) ||
                (io.trainingCtrl.req.testKind === TrainingTestType.EyeWidthSweep))
          ) {
            nextSubstate := MBTrainSubstate.s2
          }.elsewhen(io.trainingCtrl.req.complete) {
            nextSubstate := MBTrainSubstate.s3
          }
        }
        is(MBTrainSubstate.s2) { // TEST IN PROGRESS
          io.trainingCtrl.resp.inProgress := true.B
          io.trainingCtrl.resp.results.valid := io.txPtTestReqIntfIo.ptTestResults.valid ||
            io.txEyeSweepReqIntfIo.eyeSweepTestResults.valid

          when(io.txPtTestReqIntfIo.ptTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.txPtTestReqIntfIo.ptTestResults.bits
          }.elsewhen(io.txEyeSweepReqIntfIo.eyeSweepTestResults.valid) {
            io.trainingCtrl.resp.results.bits := io.txEyeSweepReqIntfIo.eyeSweepTestResults.bits
          }

          when(io.txPtTestReqIntfIo.done || io.txEyeSweepReqIntfIo.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s3) { // END
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINCENTER2_END_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER2_END_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sLINKSPEED
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sLINKSPEED) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      fromLinkspeed := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) {
          // Clear the flags, or a stale error flag holds responder rdy low.
          completedLinkspeedStep1And2Flag := false.B
          initiatingErrorFlag := false.B
          initiatingExitToPhyretrainFlag := false.B
          initiatingSpeedDegradeFlag := false.B
          initiatingWidthDegradeFlag := false.B
          initiatingDoneFlag := false.B
          linkOpsResultsValid := false.B

          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_LINKSPEED_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // TX-INITIATED POINT TEST
          io.txPtTestReqIntfIo.start := true.B
          io.txPtTestReqIntfIo.patternType := PatternSelect.LFSR

          when(io.txPtTestReqIntfIo.ptTestResults.valid) {
            linkOpsResults := io.txPtTestReqIntfIo.ptTestResults.bits
            linkOpsResultsValid := true.B
          }

          when(io.txPtTestReqIntfIo.done) {
            completedLinkspeedStep1And2Flag := true.B
          }

          when(io.txPtTestReqIntfIo.done && laneHasErrors) {
            nextSubstate := MBTrainSubstate.s2
          }.elsewhen(io.txPtTestReqIntfIo.done && !laneHasErrors) {
            nextSubstate := MBTrainSubstate.s5
          }
        }
        is(MBTrainSubstate.s2) { // ERRORS FOUND
          io.doElectricalIdleTx := true.B

          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_LINKSPEED_ERROR_REQ
          )
          initiatingErrorFlag := sbMsgExchanger.io.msgSent

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_ERROR_RESP

          when(sbMsgExchanger.io.msgReceived) {
            // sec 4.5.3.4.12 step 3 keeps Busy for the next retrain's table.
            clearPhyInRetrainFlag := true.B

            when(isWidthDegradePossible) {
              nextSubstate := MBTrainSubstate.s3 // EXIT TO REPAIR
            }.elsewhen(!isWidthDegradePossible) {
              nextSubstate := MBTrainSubstate.s4 // EXIT TO SPEED DEGRADE
            }
          }.elsewhen(io.remoteExitingToPhyretrain) {
            nextSubstate := MBTrainSubstate.s7 // SYNC STATE
          }
        }
        is(MBTrainSubstate.s3) { // EXIT TO REPAIR
          io.doElectricalIdleTx := true.B

          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_REQ
          )
          initiatingWidthDegradeFlag := sbMsgExchanger.io.msgSent

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_RESP

          // sec 4.5.3.4.12 step 3c gives the partner's speed degrade priority.
          when(io.remoteExitingToSpeedDegrade) {
            nextSubstate := MBTrainSubstate.s9 // SYNC STATE
          }.elsewhen(sbMsgExchanger.io.msgReceived) {
            requesterRdy := true.B
            when(io.requesterRdy && io.responderRdy) {
              nextSubstate := MBTrainSubstate.s0
              nextState := MBTrainState.sREPAIR
            }
          }
        }
        is(MBTrainSubstate.s4) { // EXIT TO SPEED DEGRADE
          io.doElectricalIdleTx := true.B

          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_REQ
          )
          initiatingSpeedDegradeFlag := sbMsgExchanger.io.msgSent

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sSPEEDIDLE
          }
        }

        // DATATRAINCENTER2 already set step 4's optimal clock phase.
        is(MBTrainSubstate.s5) { // NO ERRORS
          when(io.phyInRetrain) {
            when(io.changeInRuntimeLinkCtrlRegs) {
              sbMsgExchanger.io.req.valid := true.B
              sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
                SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_REQ
              )
              initiatingExitToPhyretrainFlag := sbMsgExchanger.io.msgSent

              sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
              sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_RESP

              requesterRdy := sbMsgExchanger.io.exchDone
              when(io.requesterRdy && io.responderRdy) {
                nextSubstate := MBTrainSubstate.s0
                nextState := MBTrainState.sTOPHYRETRAIN
              }
            }.otherwise {
              clearRuntimeLinkCtrlBusyBit := true.B
              clearPhyInRetrainFlag := true.B
              nextSubstate := MBTrainSubstate.s6
            }
          }.otherwise {
            nextSubstate := MBTrainSubstate.s6
          }
        }
        is(MBTrainSubstate.s6) { // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_LINKSPEED_DONE_REQ
          )
          initiatingDoneFlag := sbMsgExchanger.io.msgSent

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_DONE_RESP

          // sec 4.5.3.4.12 step 3d drops an open done req after an error req.
          requesterRdy := sbMsgExchanger.io.exchDone &&
            !io.remoteErrorInLinkspeed &&
            !stallPending && !linkspeedDoneRespIsStall

          when(io.remoteExitingToPhyretrain) {
            nextSubstate := MBTrainSubstate.s7
          }.elsewhen(io.remoteExitingToRepair) {
            nextSubstate := MBTrainSubstate.s8
          }.elsewhen(io.remoteExitingToSpeedDegrade) {
            nextSubstate := MBTrainSubstate.s9
          }.elsewhen(io.requesterRdy && io.responderRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sTOLINKINIT
          }
        }
        is(MBTrainSubstate.s7) { // SYNC STATE
          requesterRdy := true.B
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sTOPHYRETRAIN
          }
        }
        is(MBTrainSubstate.s8) { // SYNC STATE
          io.doElectricalIdleTx := true.B
          requesterRdy := true.B
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sREPAIR
          }
        }
        is(MBTrainSubstate.s9) { // SYNC STATE
          io.doElectricalIdleTx := true.B
          requesterRdy := true.B
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sSPEEDIDLE
          }
        }
      }
    }
    is(MBTrainState.sREPAIR) {
      // Driven low (sec 4.5.3.4.13). The Lane map tri-states degraded Lanes.
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txValidEn := true.B

      // A low txClkEn holds the Tx serdes in reset, through PhyBridge txResetb.
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txClkHeldLow := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // INIT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_REPAIR_INIT_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_INIT_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // APPLY DEGRADE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_REPAIR_APPLY_DEGRADE_REQ,
            msgInfo = Cat(0.U(12.W), degradeToApply)
          )
          applyDegrade := true.B

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_APPLY_DEGRADE_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s2
          }
        }
        is(MBTrainSubstate.s2) { // END
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_REPAIR_END_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_END_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sTXSELFCAL
          }
        }
      }
    }
    is(MBTrainState.sTOPHYRETRAIN) {
      nextSubstate := MBTrainSubstate.s0
      when(io.goToState.valid) {
        prevState := 2.U
        nextSubstate := MBTrainSubstate.s0
        switch(io.goToState.bits) {
          is(MBTrainGoToState.goToSPEEDIDLE) {
            nextState := MBTrainState.sSPEEDIDLE
          }
          is(MBTrainGoToState.goToTXSELFCAL) {
            nextState := MBTrainState.sTXSELFCAL
          }
          is(MBTrainGoToState.goToREPAIR) {
            nextState := MBTrainState.sREPAIR
          }
        }
      }
    }
    is(MBTrainState.sTOLINKINIT) {
      io.done := true.B
      when(io.goToState.valid) {
        prevState := 1.U
        nextSubstate := MBTrainSubstate.s0
        switch(io.goToState.bits) {
          is(MBTrainGoToState.goToSPEEDIDLE) {
            nextState := MBTrainState.sSPEEDIDLE
          }
          is(MBTrainGoToState.goToTXSELFCAL) {
            nextState := MBTrainState.sTXSELFCAL
          }
          is(MBTrainGoToState.goToREPAIR) {
            nextState := MBTrainState.sREPAIR
          }
        }
      }
    }
  }

  // ===========================================================================
  // Pattern parameters
  // ===========================================================================
  // These follow patternType, so a start req cannot name the wrong pattern.
  val reqIntfs = Seq(
    io.txPtTestReqIntfIo.patternType -> io.txPtTestReqIntfIo.linkTrainingParameters,
    io.txEyeSweepReqIntfIo.patternType -> io.txEyeSweepReqIntfIo.linkTrainingParameters,
    io.rxPtTestReqIntfIo.patternType -> io.rxPtTestReqIntfIo.linkTrainingParameters,
    io.rxEyeSweepReqIntfIo.patternType -> io.rxEyeSweepReqIntfIo.linkTrainingParameters
  )
  reqIntfs.foreach { case (patternType, params) =>
    params.dataPattern := LinkOperationParameters.dataPatternFor(patternType)
    params.burstCount := LinkOperationParameters.burstCountFor(patternType)
    params.idleCount := 0.U
    params.iterationCount := 1.U
  }
}

class MBTrainResponder(afeParams: AfeParams, sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val pauseAfterValVrefStart = Input(Bool())
    val pausedAtValVrefStart = Output(Bool())
    val pauseSidebandResume = Output(Bool())
    val start = Input(Bool())
    val currRemoteTxFunctionalLanes = Input(UInt(3.W))
    val goToState = Flipped(Valid(MBTrainGoToState()))
    val requesterRdy = Input(Bool())
    val localInitiatingError = Input(Bool())
    val localInitiatingWidthDegrade = Input(Bool())
    val localInitiatingSpeedDegrade = Input(Bool())
    val localInitiatingDone = Input(Bool())
    val localInitiatingExitToPhyRetrain = Input(Bool())
    val localCompletedSteps1And2 = Input(Bool())

    val currentState = Output(MBTrainState())
    val done = Output(Bool())
    val error = Output(Bool())
    val rxClkCalSendFwClkPattern = Output(Bool())
    val rxClkCalSendTrkPattern = Output(Bool())
    val newRemoteFunctionalLanes = Output(UInt(3.W))
    val rxWidthChanged = Output(Bool())
    val responderRdy = Output(Bool())
    val remoteErrorInLinkspeed = Output(Bool())
    val remoteExitingToRepair = Output(Bool())
    val remoteExitingToSpeedDegrade = Output(Bool())
    val remoteExitingToPhyretrain = Output(Bool())
    val doElectricalIdleRx = Output(Bool())
    val remoteRxSweepResults = Valid(Vec(afeParams.mbLanes, UInt(1.W)))

    val sbLaneIo = new SidebandLaneIO(sbParams)
    val txPtTestRespIntfIo = new TxInitPtTestResponderInterfaceIO()
    val txEyeSweepRespIntfIo = new TxInitEyeWidthSweepResponderInterfaceIO()
    val rxPtTestRespIntfIo = new RxInitPtTestResponderInterfaceIO()
    val rxEyeSweepRespIntfIo =
      new RxInitEyeWidthSweepResponderInterfaceIO(afeParams)
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // ===========================================================================
  // State, pause and error
  // ===========================================================================
  val currentState = RegInit(MBTrainState.sVALVREF)
  val nextState = WireInit(currentState)
  currentState := nextState
  io.currentState := currentState

  val substateReg = RegInit(MBTrainSubstate.s0)
  val nextSubstate = WireInit(substateReg)
  substateReg := nextSubstate

  val responderRdyStatusReg = RegInit(false.B)
  val responderRdy = WireInit(false.B)
  when((currentState =/= nextState) || (substateReg =/= nextSubstate)) {
    responderRdyStatusReg := false.B
  }.elsewhen(responderRdy) {
    responderRdyStatusReg := true.B
  }
  io.responderRdy := responderRdyStatusReg || responderRdy

  val valVrefPauseHeld = RegInit(false.B)
  when(io.pauseAfterValVrefStart) {
    when(sbMsgExchanger.io.exchDone) { valVrefPauseHeld := true.B }
  }.otherwise {
    valVrefPauseHeld := false.B
  }
  io.pausedAtValVrefStart := valVrefPauseHeld
  io.pauseSidebandResume := valVrefPauseHeld && io.sbLaneIo.rx.valid &&
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.MBTRAIN_VALVREF_END_REQ)

  val errorDetectedReg = RegInit(false.B)
  val errorDetectedWire = WireInit(false.B)
  val errorDetected = Wire(Bool())

  errorDetected := errorDetectedReg || errorDetectedWire
  when(errorDetectedWire) {
    errorDetectedReg := true.B
  }
  io.error := errorDetectedReg

  // ===========================================================================
  // Sideband and link operations
  // ===========================================================================
  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := (currentState =/= nextState) || (substateReg =/= nextSubstate)
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  // LINKSPEED overrides this default for the messages that it compares raw.
  io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready

  io.txPtTestRespIntfIo.start := false.B
  io.txPtTestRespIntfIo.patternType := PatternSelect.LFSR

  io.txEyeSweepRespIntfIo.start := false.B
  io.txEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

  io.rxPtTestRespIntfIo.start := false.B
  io.rxPtTestRespIntfIo.patternType := PatternSelect.LFSR

  io.rxEyeSweepRespIntfIo.start := false.B
  io.rxEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

  // ===========================================================================
  // RXCLKCAL, LINKSPEED and REPAIR
  // ===========================================================================
  val rxClkCalSendFwClkPattern = RegInit(false.B)
  val rxClkCalSendTrkPattern = RegInit(false.B)

  io.rxClkCalSendFwClkPattern := rxClkCalSendFwClkPattern
  io.rxClkCalSendTrkPattern := rxClkCalSendTrkPattern

  val ptTestDoneInLinkSpeed = RegInit(false.B)
  val localCompletedSteps1And2 = Wire(Bool())
  val localNotInitiatingPhyRetrain = Wire(Bool())
  val localNotInitiatingSpeedDegrade = Wire(Bool())
  val remoteErrorInLinkspeedFlag = RegInit(false.B)
  val remoteExitingToRepairFlag = RegInit(false.B)
  val remoteExitingToSpeedDegradeFlag = RegInit(false.B)
  val remoteExitingToPhyretrainFlag = RegInit(false.B)

  localNotInitiatingSpeedDegrade := (io.localInitiatingError && io.localInitiatingWidthDegrade) ||
    io.localInitiatingDone
  localCompletedSteps1And2 := io.localCompletedSteps1And2
  localNotInitiatingPhyRetrain := io.localInitiatingError ^ io.localInitiatingDone
  io.remoteErrorInLinkspeed := remoteErrorInLinkspeedFlag
  io.remoteExitingToRepair := remoteExitingToRepairFlag
  io.remoteExitingToSpeedDegrade := remoteExitingToSpeedDegradeFlag
  io.remoteExitingToPhyretrain := remoteExitingToPhyretrainFlag
  io.doElectricalIdleRx := false.B

  val currRemoteFunctionalLanesWire = Wire(UInt(3.W))
  val incRemoteFuncLanesWire = Wire(UInt(3.W))
  val widthChanged = WireInit(false.B)

  currRemoteFunctionalLanesWire := io.currRemoteTxFunctionalLanes
  incRemoteFuncLanesWire := sbMsgExchanger.io.resp.bits(42, 40)
  io.newRemoteFunctionalLanes := incRemoteFuncLanesWire
  io.rxWidthChanged := widthChanged

  // ===========================================================================
  // State machine
  // ===========================================================================
  io.done := false.B
  io.remoteRxSweepResults.valid := false.B
  io.remoteRxSweepResults.bits := 0.U.asTypeOf(io.remoteRxSweepResults.bits)

  switch(currentState) {
    is(MBTrainState.sVALVREF) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          when(io.start) {
            rxClkCalSendFwClkPattern := false.B
            rxClkCalSendTrkPattern := false.B

            sbMsgExchanger.io.rxRefBitPattern.valid := true.B
            sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALVREF_START_REQ

            sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
            sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
              SBM.MBTRAIN_VALVREF_START_RESP
            )

            when(
              (sbMsgExchanger.io.exchDone || valVrefPauseHeld) &&
                !io.pauseAfterValVrefStart
            ) {
              nextSubstate := MBTrainSubstate.s1
            }
          }
        }

        // Here start only enables the responders. Each runs on its own request.
        is(MBTrainSubstate.s1) { // END
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxPtTestRespIntfIo.patternType := PatternSelect.VALTRAIN
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.VALTRAIN

          // The partner's sweep result goes to software in the result FIFO.
          when(io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.valid) {
            io.remoteRxSweepResults.valid := true.B
            io.remoteRxSweepResults.bits := io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.bits
          }

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALVREF_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALVREF_END_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATAVREF
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sDATAVREF) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATAVREF_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATAVREF_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // END
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxPtTestRespIntfIo.patternType := PatternSelect.LFSR
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

          when(io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.valid) {
            io.remoteRxSweepResults.valid := true.B
            io.remoteRxSweepResults.bits := io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.bits
          }

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATAVREF_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATAVREF_END_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sSPEEDIDLE
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sSPEEDIDLE) {
      // The width of sec 4.5.3.4.3 rule 2 is already set when SPEEDIDLE starts.
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_SPEEDIDLE_DONE_REQ

      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.MBTRAIN_SPEEDIDLE_DONE_RESP
      )
      responderRdy := sbMsgExchanger.io.exchDone

      // No substates here, so the sticky rdy bits can be set already on entry.
      when(io.requesterRdy && io.responderRdy && sbMsgExchanger.io.exchDone) {
        nextState := MBTrainState.sTXSELFCAL
      }
    }
    is(MBTrainState.sTXSELFCAL) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_TXSELFCAL_DONE_REQ

      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.MBTRAIN_TXSELFCAL_DONE_RESP
      )
      responderRdy := sbMsgExchanger.io.exchDone
      when(io.requesterRdy && io.responderRdy) {
        nextState := MBTrainState.sRXCLKCAL
      }
    }
    is(MBTrainState.sRXCLKCAL) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXCLKCAL_START_REQ

          when(sbMsgExchanger.io.msgReceived) {
            rxClkCalSendFwClkPattern := true.B
            rxClkCalSendTrkPattern := true.B
          }

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_RXCLKCAL_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // DONE
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXCLKCAL_DONE_REQ

          when(sbMsgExchanger.io.msgReceived) {
            rxClkCalSendFwClkPattern := false.B
            rxClkCalSendTrkPattern := false.B
          }

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_RXCLKCAL_DONE_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sVALTRAINCENTER
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sVALTRAINCENTER) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINCENTER_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALTRAINCENTER_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // DONE
          io.txPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.txEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.txPtTestRespIntfIo.patternType := PatternSelect.VALTRAIN
          io.txEyeSweepRespIntfIo.patternType := PatternSelect.VALTRAIN

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINCENTER_DONE_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALTRAINCENTER_DONE_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sVALTRAINVREF
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sVALTRAINVREF) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINVREF_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALTRAINVREF_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // DONE
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxPtTestRespIntfIo.patternType := PatternSelect.VALTRAIN
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.VALTRAIN

          when(io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.valid) {
            io.remoteRxSweepResults.valid := true.B
            io.remoteRxSweepResults.bits := io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.bits
          }

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINVREF_DONE_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_VALTRAINVREF_DONE_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINCENTER1
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sDATATRAINCENTER1) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER1_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINCENTER1_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // END
          io.txPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.txEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.txPtTestRespIntfIo.patternType := PatternSelect.LFSR
          io.txEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER1_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINCENTER1_END_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINVREF
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sDATATRAINVREF) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINVREF_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINVREF_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // END
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxPtTestRespIntfIo.patternType := PatternSelect.LFSR
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

          when(io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.valid) {
            io.remoteRxSweepResults.valid := true.B
            io.remoteRxSweepResults.bits := io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.bits
          }

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINVREF_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINVREF_END_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sRXDESKEW
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sRXDESKEW) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXDESKEW_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_RXDESKEW_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // END
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.rxPtTestRespIntfIo.patternType := PatternSelect.LFSR
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

          when(io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.valid) {
            io.remoteRxSweepResults.valid := true.B
            io.remoteRxSweepResults.bits := io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.bits
          }

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXDESKEW_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_RXDESKEW_END_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINCENTER2
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sDATATRAINCENTER2) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER2_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINCENTER2_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // END
          io.txPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.txEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived
          io.txPtTestRespIntfIo.patternType := PatternSelect.LFSR
          io.txEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER2_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_DATATRAINCENTER2_END_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sLINKSPEED
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sLINKSPEED) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          ptTestDoneInLinkSpeed := false.B
          remoteErrorInLinkspeedFlag := false.B
          remoteExitingToRepairFlag := false.B
          remoteExitingToSpeedDegradeFlag := false.B
          remoteExitingToPhyretrainFlag := false.B

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_LINKSPEED_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) {
          io.txPtTestRespIntfIo.start := !ptTestDoneInLinkSpeed
          io.txPtTestRespIntfIo.patternType := PatternSelect.LFSR

          when(io.txPtTestRespIntfIo.done) {
            ptTestDoneInLinkSpeed := true.B
          }

          when(ptTestDoneInLinkSpeed && io.sbLaneIo.rx.valid) {
            when(
              SBMsgCompare(
                io.sbLaneIo.rx.bits.data,
                SBM.MBTRAIN_LINKSPEED_ERROR_REQ
              )
            ) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBTrainSubstate.s2
            }.elsewhen(
              SBMsgCompare(
                io.sbLaneIo.rx.bits.data,
                SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_REQ
              )
            ) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBTrainSubstate.s6
            }.elsewhen(
              SBMsgCompare(
                io.sbLaneIo.rx.bits.data,
                SBM.MBTRAIN_LINKSPEED_DONE_REQ
              )
            ) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBTrainSubstate.s7
            }
          }
        }
        is(MBTrainSubstate.s2) { // ERROR REQ RECEIVED
          remoteErrorInLinkspeedFlag := true.B

          when(localCompletedSteps1And2 && localNotInitiatingPhyRetrain) {
            io.doElectricalIdleRx := true.B
            sbMsgExchanger.io.req.valid := true.B
            sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
              SBM.MBTRAIN_LINKSPEED_ERROR_RESP
            )
          }

          when(io.localInitiatingExitToPhyRetrain) {
            nextSubstate := MBTrainSubstate.s8
          }.elsewhen(sbMsgExchanger.io.msgSent) {
            nextSubstate := MBTrainSubstate.s3
          }
        }
        is(MBTrainSubstate.s3) { // WAIT FOR EXIT REQ
          io.doElectricalIdleRx := true.B
          when(io.sbLaneIo.rx.valid) {
            when(
              SBMsgCompare(
                io.sbLaneIo.rx.bits.data,
                SBM.MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_REQ
              )
            ) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBTrainSubstate.s4
            }.elsewhen(
              SBMsgCompare(
                io.sbLaneIo.rx.bits.data,
                SBM.MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_REQ
              )
            ) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBTrainSubstate.s5
            }
          }
        }
        is(MBTrainSubstate.s4) { // EXIT TO REPAIR REQ RECEIVED
          io.doElectricalIdleRx := true.B

          when(localNotInitiatingSpeedDegrade) {
            sbMsgExchanger.io.req.valid := true.B
            sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
              SBM.MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_RESP
            )
          }

          remoteExitingToRepairFlag := sbMsgExchanger.io.msgSent

          responderRdy := remoteExitingToRepairFlag
          when(io.localInitiatingSpeedDegrade) {
            nextSubstate := MBTrainSubstate.s10
          }.elsewhen(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sREPAIR
          }
        }
        is(MBTrainSubstate.s5) { // EXIT TO SPEED DEGRADE REQ RECEIVED
          io.doElectricalIdleRx := true.B

          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_RESP
          )

          remoteExitingToSpeedDegradeFlag := sbMsgExchanger.io.msgSent

          responderRdy := remoteExitingToSpeedDegradeFlag
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sSPEEDIDLE
          }
        }
        is(MBTrainSubstate.s6) { // EXIT TO PHY RETRAIN REQ RECEIVED
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_RESP
          )

          remoteExitingToPhyretrainFlag := sbMsgExchanger.io.msgSent

          responderRdy := remoteExitingToPhyretrainFlag
          when(io.requesterRdy && io.responderRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sTOPHYRETRAIN
          }
        }
        is(MBTrainSubstate.s7) { // DONE REQ RECEIVED
          sbMsgExchanger.io.req.valid := io.localInitiatingDone
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_LINKSPEED_DONE_RESP
          )

          // sec 4.5.3.4.12 step 3d drops the done req after our error req.
          responderRdy := sbMsgExchanger.io.msgSent && !io.localInitiatingError

          when(io.localInitiatingExitToPhyRetrain) {
            nextSubstate := MBTrainSubstate.s8
          }.elsewhen(io.localInitiatingWidthDegrade) {
            nextSubstate := MBTrainSubstate.s9
          }.elsewhen(io.localInitiatingSpeedDegrade) {
            nextSubstate := MBTrainSubstate.s10
          }.elsewhen(io.requesterRdy && io.responderRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sTOLINKINIT
          }
        }
        is(MBTrainSubstate.s8) { // SYNC STATE
          responderRdy := true.B
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sTOPHYRETRAIN
          }
        }
        is(MBTrainSubstate.s9) { // SYNC STATE
          io.doElectricalIdleRx := true.B
          responderRdy := true.B
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sREPAIR
          }
        }
        is(MBTrainSubstate.s10) { // SYNC STATE
          io.doElectricalIdleRx := true.B
          responderRdy := true.B
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sSPEEDIDLE
          }
        }
      }
    }
    is(MBTrainState.sREPAIR) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) { // INIT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_INIT_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_REPAIR_INIT_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { // APPLY DEGRADE
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_APPLY_DEGRADE_REQ

          when(sbMsgExchanger.io.resp.valid) {
            errorDetectedWire := incRemoteFuncLanesWire === "b000".U
            widthChanged := incRemoteFuncLanesWire =/= currRemoteFunctionalLanesWire
          }

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived && !errorDetected
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_REPAIR_APPLY_DEGRADE_RESP
          )

          // A wait for both rdys here would deadlock, so advance on this one.
          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBTrainSubstate.s2
          }
        }
        is(MBTrainSubstate.s2) { // END
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBTRAIN_REPAIR_END_RESP
          )
          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sTXSELFCAL
          }
        }
      }
    }
    is(MBTrainState.sTOPHYRETRAIN) {
      when(io.goToState.valid) {
        nextSubstate := MBTrainSubstate.s0
        switch(io.goToState.bits) {
          is(MBTrainGoToState.goToSPEEDIDLE) {
            nextState := MBTrainState.sSPEEDIDLE
          }
          is(MBTrainGoToState.goToTXSELFCAL) {
            nextState := MBTrainState.sTXSELFCAL
          }
          is(MBTrainGoToState.goToREPAIR) {
            nextState := MBTrainState.sREPAIR
          }
        }
      }
    }
    is(MBTrainState.sTOLINKINIT) {
      io.done := true.B
      when(io.goToState.valid) {
        nextSubstate := MBTrainSubstate.s0
        switch(io.goToState.bits) {
          is(MBTrainGoToState.goToSPEEDIDLE) {
            nextState := MBTrainState.sSPEEDIDLE
          }
          is(MBTrainGoToState.goToTXSELFCAL) {
            nextState := MBTrainState.sTXSELFCAL
          }
          is(MBTrainGoToState.goToREPAIR) {
            nextState := MBTrainState.sREPAIR
          }
        }
      }
    }
  }
}
