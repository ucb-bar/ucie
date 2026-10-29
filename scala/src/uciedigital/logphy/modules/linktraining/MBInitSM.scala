/*
  The MBINIT state machine of the Logical PHY (sec 4.5.3.3).
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.common.SpeedMode
import chisel3._
import chisel3.util._

class MbInitParamsIO extends Bundle {
  val localPhySettings = Flipped(Valid(new PHYParamExchangeIO()))
  val remotePhySettings = Valid(new PHYParamExchangeIO())
  val interoperableParamsFound = Input(Bool())
}

object MBInitState extends ChiselEnum {
  val sPARAM, sCAL, sREPAIRCLK, sREPAIRVAL, sREVERSALMB, sREPAIRMB, sTOMBTRAIN =
    Value
}
object MBInitSubstate extends ChiselEnum {
  val s0, s1, s2, s3, s4 = Value
}

class MBInitSM(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val pauseAfterParam = Input(Bool())
    val pausedAtParam = Output(Bool())
    val pauseSidebandResume = Output(Bool())
    val mbInitCalDone = Input(Bool())
    val localPhySettings = Flipped(Valid(new PHYParamExchangeIO()))
    val initWidthCode = Input(UInt(3.W))

    val currentState = Output(MBInitState())
    val mbLaneCtrlIo = new MainbandLaneCtrlIO(afeParams)
    val mbInitCalStart = Output(Bool())
    val applyLaneReversal = Output(Bool())
    val localFunctionalLanes = Output(UInt(3.W))
    val txWidthChanged = Output(Bool())
    val remoteFunctionalLanes = Output(UInt(3.W))
    val rxWidthChanged = Output(Bool())
    val widthApply = Output(new MainbandWidthApply)
    val interoperableParamsNotFound =
      Output(Bool())
    val negotiatedPhySettings = Valid(new PHYParamExchangeIO())
    val remoteAdvertisedClockPhase = Output(UInt(1.W))
    val usingPatternWriter = Output(Bool())
    val usingPatternReader = Output(Bool())

    val fsmCtrl = new SubFsmControlIO()
    val patternWriterIo = Flipped(new PatternWriterIO)
    val patternReaderIo = Flipped(new PatternReaderClientIO(afeParams.mbLanes))
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
    val txPtTestReqInterfaceIo = new TxInitPtTestRequesterInterfaceIO(afeParams)
    val txPtTestRespInterfaceIo = new TxInitPtTestResponderInterfaceIO()
  })

  // TODO: SVA that both halves change state together, with settings valid.
  val requester = Module(new MBInitRequester(afeParams, sbParams))
  val responder = Module(new MBInitResponder(afeParams, sbParams))

  // ===========================================================================
  // Parameter exchange
  // ===========================================================================
  val remoteModuleId = RegInit(0.U(2.W))
  val remoteVoltageSwing = RegInit(0.U(5.W))
  val remoteMaxDataRate = RegInit(0.U(4.W))
  val remoteClockMode = RegInit(0.U(1.W))
  val remoteClockPhase = RegInit(0.U(1.W))
  val remoteUcieSx8 = RegInit(0.U(1.W))
  val remoteSbFeatExt = RegInit(0.U(1.W))
  val remoteTxAdjRuntime = RegInit(0.U(1.W))
  val remoteSettingsValid = RegInit(false.B)

  when(responder.io.phyParams.remotePhySettings.valid) {
    remoteModuleId := responder.io.phyParams.remotePhySettings.bits.moduleId
    remoteVoltageSwing := responder.io.phyParams.remotePhySettings.bits.voltageSwing
    remoteMaxDataRate := responder.io.phyParams.remotePhySettings.bits.maxDataRate
    remoteClockMode := responder.io.phyParams.remotePhySettings.bits.clockMode
    remoteClockPhase := responder.io.phyParams.remotePhySettings.bits.clockPhase
    remoteUcieSx8 := responder.io.phyParams.remotePhySettings.bits.ucieSx8
    remoteSbFeatExt := responder.io.phyParams.remotePhySettings.bits.sbFeatExt
    remoteTxAdjRuntime := responder.io.phyParams.remotePhySettings.bits.txAdjRuntime
    remoteSettingsValid := true.B
  }

  val localNegotiatedTxAdjRuntime = RegInit(0.U(1.W))
  val localNegotiatedSbFeatExt = RegInit(0.U(1.W))
  val localNegotiatedClockPhase = RegInit(0.U(1.W))
  val localNegotiatedClockMode = RegInit(0.U(1.W))
  val localNegotiatedMaxDataRate = RegInit(0.U(4.W))
  val localNegotiatedParamsValid = RegInit(false.B)

  // The codes are in rate order, so sec 4.5.3.3.1's common rate is the min.
  val negotiatedMaxRate = Mux(
    remoteMaxDataRate < io.localPhySettings.bits.maxDataRate,
    remoteMaxDataRate,
    io.localPhySettings.bits.maxDataRate
  )

  // Clock Phase applies only at 24 and 32 GT/s (sec 4.5.3.3.1).
  val quarterRateAllowed =
    (negotiatedMaxRate === SpeedMode.speed24.asUInt) ||
      (negotiatedMaxRate === SpeedMode.speed32.asUInt)

  when(remoteSettingsValid) {
    localNegotiatedTxAdjRuntime := remoteTxAdjRuntime & io.localPhySettings.bits.txAdjRuntime
    localNegotiatedSbFeatExt := remoteSbFeatExt & io.localPhySettings.bits.sbFeatExt

    // The resp reflects the partner's requests, and Clock Phase needs the rate.
    localNegotiatedClockPhase := remoteClockPhase & quarterRateAllowed.asUInt
    localNegotiatedClockMode := remoteClockMode
    localNegotiatedMaxDataRate := negotiatedMaxRate
    localNegotiatedParamsValid := true.B
  }

  // The partner's resp can come before its req, so hold it in registers.
  val remoteNegotiatedTxAdjRuntime = RegInit(0.U(1.W))
  val remoteNegotiatedSbFeatExt = RegInit(0.U(1.W))
  val remoteNegotiatedClockPhase = RegInit(0.U(1.W))
  val remoteNegotiatedClockMode = RegInit(0.U(1.W))
  val remoteNegotiatedMaxDataRate = RegInit(0.U(4.W))
  val remoteNegotiatedParamsValid = RegInit(false.B)

  when(requester.io.phyParams.remotePhySettings.valid) {
    remoteNegotiatedTxAdjRuntime := requester.io.phyParams.remotePhySettings.bits.txAdjRuntime
    remoteNegotiatedSbFeatExt := requester.io.phyParams.remotePhySettings.bits.sbFeatExt
    remoteNegotiatedClockPhase := requester.io.phyParams.remotePhySettings.bits.clockPhase
    remoteNegotiatedClockMode := requester.io.phyParams.remotePhySettings.bits.clockMode
    remoteNegotiatedMaxDataRate := requester.io.phyParams.remotePhySettings.bits.maxDataRate
    remoteNegotiatedParamsValid := true.B
  }
  val interoperableParamsComparison = Wire(Bool())
  val interoperableParamsFound = Wire(Bool())
  val interoperableParamsErrorFlag = Wire(Bool())

  // The partner's resp reflects our req, so compare Clock Phase and Mode to it.
  interoperableParamsComparison := (localNegotiatedTxAdjRuntime === remoteNegotiatedTxAdjRuntime) &&
    (localNegotiatedSbFeatExt === remoteNegotiatedSbFeatExt) &&
    (remoteNegotiatedClockPhase ===
      (io.localPhySettings.bits.clockPhase & quarterRateAllowed.asUInt)) &&
    (remoteNegotiatedClockMode === io.localPhySettings.bits.clockMode) &&
    (localNegotiatedMaxDataRate === remoteNegotiatedMaxDataRate)

  interoperableParamsFound := interoperableParamsComparison &&
    remoteNegotiatedParamsValid && localNegotiatedParamsValid
  interoperableParamsErrorFlag := !interoperableParamsComparison &&
    remoteNegotiatedParamsValid && localNegotiatedParamsValid

  val interpretBy8Lane = Wire(Bool())
  interpretBy8Lane := (io.localPhySettings.bits.ucieSx8 & io.localPhySettings.valid) |
    (remoteUcieSx8 & remoteSettingsValid)

  // ===========================================================================
  // Requester and responder
  // ===========================================================================
  requester.io.start := io.fsmCtrl.start
  requester.io.mbInitCalDone := io.mbInitCalDone
  requester.io.responderRdy := responder.io.responderRdy
  requester.io.pauseAfterParam := io.pauseAfterParam
  responder.io.pauseAfterParam := io.pauseAfterParam
  io.pausedAtParam :=
    requester.io.pausedAtParam || responder.io.pausedAtParam
  io.pauseSidebandResume :=
    requester.io.pauseSidebandResume || responder.io.pauseSidebandResume
  requester.io.interpretBy8Lane := interpretBy8Lane
  requester.io.initWidthCode := io.initWidthCode
  requester.io.rxWidthChanged := responder.io.rxWidthChanged
  requester.io.phyParams.localPhySettings := io.localPhySettings
  requester.io.phyParams.interoperableParamsFound := interoperableParamsFound

  responder.io.start := io.fsmCtrl.start
  responder.io.requesterRdy := requester.io.requesterRdy
  responder.io.interpretBy8Lane := interpretBy8Lane
  responder.io.txWidthChanged := requester.io.txWidthChanged
  responder.io.localTxFunctionalLanes := requester.io.localFunctionalLanes
  responder.io.localTxCodeValid := requester.io.localTxCodeValid
  responder.io.phyParams.localPhySettings.valid := localNegotiatedParamsValid

  // The resp reserves these three fields (sec 4.5.3.3.1).
  responder.io.phyParams.localPhySettings.bits.voltageSwing := 0.U
  responder.io.phyParams.localPhySettings.bits.ucieSx8 := 0.U
  responder.io.phyParams.localPhySettings.bits.moduleId := 0.U
  responder.io.phyParams.localPhySettings.bits.txAdjRuntime := localNegotiatedTxAdjRuntime
  responder.io.phyParams.localPhySettings.bits.sbFeatExt := localNegotiatedSbFeatExt
  responder.io.phyParams.localPhySettings.bits.clockPhase := localNegotiatedClockPhase
  responder.io.phyParams.localPhySettings.bits.clockMode := localNegotiatedClockMode
  responder.io.phyParams.localPhySettings.bits.maxDataRate := localNegotiatedMaxDataRate
  responder.io.phyParams.interoperableParamsFound := interoperableParamsFound

  // ===========================================================================
  // Outputs
  // ===========================================================================
  io.fsmCtrl.done := requester.io.done && responder.io.done
  io.fsmCtrl.substateTransitioning := requester.io.transitioningState
  io.fsmCtrl.error := requester.io.error | responder.io.error | interoperableParamsErrorFlag

  // Only the SBFE resp can Stall (Table 7-11), and SFES is advertised as 0.
  io.fsmCtrl.stallReceived := false.B
  io.currentState := requester.io.currentState
  io.mbLaneCtrlIo := requester.io.mbLaneCtrlIo
  io.mbInitCalStart := requester.io.mbInitCalStart
  io.applyLaneReversal := requester.io.applyLaneReversal
  io.localFunctionalLanes := requester.io.localFunctionalLanes
  io.txWidthChanged := requester.io.txWidthChanged
  io.remoteFunctionalLanes := responder.io.remoteFunctionalLanes
  io.rxWidthChanged := responder.io.rxWidthChanged
  io.widthApply := responder.io.widthApply
  io.requesterSbLaneIo <> requester.io.sbLaneIo
  io.responderSbLaneIo <> responder.io.sbLaneIo
  io.patternWriterIo <> requester.io.patternWriterIo
  io.patternReaderIo <> responder.io.patternReaderIo
  io.txPtTestReqInterfaceIo <> requester.io.txPtTestReqInterfaceIo
  io.txPtTestRespInterfaceIo <> responder.io.txPtTestRespInterfaceIo
  io.interoperableParamsNotFound := interoperableParamsErrorFlag
  io.usingPatternWriter := requester.io.usingPatternWriter
  io.usingPatternReader := responder.io.usingPatternReader

  // clockMode is the mode that the local Tx uses, as the partner requested.
  io.negotiatedPhySettings.valid := interoperableParamsFound
  io.negotiatedPhySettings.bits.voltageSwing := remoteVoltageSwing
  io.negotiatedPhySettings.bits.maxDataRate := localNegotiatedMaxDataRate
  io.negotiatedPhySettings.bits.clockMode := localNegotiatedClockMode
  io.negotiatedPhySettings.bits.clockPhase := localNegotiatedClockPhase
  io.negotiatedPhySettings.bits.ucieSx8 := interpretBy8Lane
  io.negotiatedPhySettings.bits.sbFeatExt := remoteSbFeatExt
  io.negotiatedPhySettings.bits.txAdjRuntime := localNegotiatedTxAdjRuntime
  io.negotiatedPhySettings.bits.moduleId := remoteModuleId
  io.remoteAdvertisedClockPhase := remoteClockPhase
}

class MBInitRequester(afeParams: AfeParams, sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val mbInitCalDone = Input(Bool())
    val responderRdy = Input(Bool())
    val initWidthCode = Input(UInt(3.W))
    val interpretBy8Lane = Input(Bool())
    val rxWidthChanged = Input(Bool())
    val pauseAfterParam = Input(Bool())

    val pausedAtParam = Output(Bool())
    val pauseSidebandResume = Output(Bool())
    val done = Output(Bool())
    val transitioningState = Output(Bool())
    val currentState = Output(MBInitState())
    val mbLaneCtrlIo = new MainbandLaneCtrlIO(afeParams)
    val mbInitCalStart = Output(Bool())
    val requesterRdy = Output(Bool())
    val applyLaneReversal = Output(Bool())
    val localFunctionalLanes = Output(UInt(3.W))
    val localTxCodeValid = Output(Bool())
    val txWidthChanged = Output(Bool())
    val error = Output(Bool())
    val usingPatternWriter = Output(Bool())

    val sbLaneIo = new SidebandLaneIO(sbParams)
    val phyParams = new MbInitParamsIO()
    val patternWriterIo = Flipped(new PatternWriterIO)
    val txPtTestReqInterfaceIo = new TxInitPtTestRequesterInterfaceIO(afeParams)
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // ===========================================================================
  // State registers
  // ===========================================================================
  val currentState = RegInit(MBInitState.sPARAM)
  val nextState = WireInit(currentState)
  currentState := nextState
  io.currentState := currentState

  val substateReg = RegInit(MBInitSubstate.s0)
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

  io.mbInitCalStart := currentState === MBInitState.sCAL

  val errorDetectedReg = RegInit(false.B)
  val errorDetectedWire = WireInit(false.B)
  val errorDetected = Wire(Bool())

  errorDetected := errorDetectedReg || errorDetectedWire
  when(errorDetectedWire) {
    errorDetectedReg := true.B
  }

  io.error := errorDetectedReg
  io.transitioningState := currentState =/= nextState
  io.done := false.B

  // ===========================================================================
  // Defaults
  // ===========================================================================
  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := (currentState =/= nextState) || (substateReg =/= nextSubstate)
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.mbLaneCtrlIo.txDataEn.foreach(_ := false.B)
  io.mbLaneCtrlIo.txClkEn := false.B
  io.mbLaneCtrlIo.txValidEn := false.B
  io.mbLaneCtrlIo.txTrackEn := false.B
  io.mbLaneCtrlIo.txClkHeldLow := false.B
  io.mbLaneCtrlIo.txTrackPattern := false.B
  io.mbLaneCtrlIo.txTrackLevel := false.B
  io.mbLaneCtrlIo.rxDataEn.foreach(_ := false.B)
  io.mbLaneCtrlIo.rxClkEn := false.B
  io.mbLaneCtrlIo.rxValidEn := false.B
  io.mbLaneCtrlIo.rxTrackEn := false.B

  io.patternWriterIo.req.valid := false.B
  io.patternWriterIo.req.bits.patternType := PatternSelect.VALTRAIN
  io.usingPatternWriter := false.B

  io.txPtTestReqInterfaceIo.start := false.B
  io.txPtTestReqInterfaceIo.patternType := PatternSelect.PERLANEID

  // Table 7-11 codes for Center, Per Lane ID, VALTRAIN, continuous, per Lane.
  io.txPtTestReqInterfaceIo.linkTrainingParameters.clockPhase := 0.U
  io.txPtTestReqInterfaceIo.linkTrainingParameters.dataPattern := 1.U
  io.txPtTestReqInterfaceIo.linkTrainingParameters.validPattern := 0.U
  io.txPtTestReqInterfaceIo.linkTrainingParameters.patternMode := 0.U
  io.txPtTestReqInterfaceIo.linkTrainingParameters.iterationCount := 1.U
  io.txPtTestReqInterfaceIo.linkTrainingParameters.idleCount := 0.U
  io.txPtTestReqInterfaceIo.linkTrainingParameters.burstCount := 2048.U
  io.txPtTestReqInterfaceIo.linkTrainingParameters.maxErrorThreshold := 0.U
  io.txPtTestReqInterfaceIo.linkTrainingParameters.comparisonMode := 0.U

  // ===========================================================================
  // Message fields
  // ===========================================================================
  val mbInitParamReqDataBits = Wire(UInt(64.W))
  mbInitParamReqDataBits := Cat(
    0.U(48.W), // Reserved
    io.phyParams.localPhySettings.bits.txAdjRuntime,
    io.phyParams.localPhySettings.bits.sbFeatExt,
    io.phyParams.localPhySettings.bits.ucieSx8,
    io.phyParams.localPhySettings.bits.moduleId,
    io.phyParams.localPhySettings.bits.clockPhase,
    io.phyParams.localPhySettings.bits.clockMode,
    io.phyParams.localPhySettings.bits.voltageSwing,
    io.phyParams.localPhySettings.bits.maxDataRate
  )

  io.phyParams.remotePhySettings.valid := false.B
  io.phyParams.remotePhySettings.bits.txAdjRuntime := sbMsgExchanger.io.resp
    .bits(79)
  io.phyParams.remotePhySettings.bits.sbFeatExt := sbMsgExchanger.io.resp.bits(
    78
  )
  io.phyParams.remotePhySettings.bits.ucieSx8 := 0.U
  io.phyParams.remotePhySettings.bits.moduleId := 0.U
  io.phyParams.remotePhySettings.bits.clockPhase := sbMsgExchanger.io.resp.bits(
    74
  )
  io.phyParams.remotePhySettings.bits.clockMode := sbMsgExchanger.io.resp.bits(
    73
  )
  io.phyParams.remotePhySettings.bits.voltageSwing := 0.U
  io.phyParams.remotePhySettings.bits.maxDataRate := sbMsgExchanger.io.resp
    .bits(67, 64)

  // Bits 40 to 42 are MsgInfo[2:0], and all three must pass (sec 4.5.3.3.3).
  val repairClkSuccess = Wire(Bool())
  repairClkSuccess := sbMsgExchanger.io.resp.bits(40) &&
    sbMsgExchanger.io.resp.bits(41) &&
    sbMsgExchanger.io.resp.bits(42)
  val repairValSuccess = Wire(Bool())
  repairValSuccess := sbMsgExchanger.io.resp.bits(40)
  val reversalMbSuccess = Wire(Bool())

  val perLaneResults = SBMsgField.data(sbMsgExchanger.io.resp.bits)

  // sec 4.5.3.3.5 step 6 reverses at exactly 50%, so pass needs above N/2.
  val reversalLanes = afeParams.mbLanes

  // Note: PopCount is an adder tree, so this can be a long path.
  when(io.interpretBy8Lane) {
    // A x16 module run as UCIe-S x8 votes on Lanes 7:0 only (sec 4.5.3.3.5).
    reversalMbSuccess := PopCount(perLaneResults(7, 0)) > 4.U
  }.otherwise {
    reversalMbSuccess :=
      PopCount(perLaneResults(reversalLanes - 1, 0)) > (reversalLanes / 2).U
  }

  // ===========================================================================
  // Calibration, reversal and width
  // ===========================================================================
  // Software completes the sec 4.5.3.3.2 calibration, and the done req waits.
  val mbInitCalDoneReg = RegInit(false.B)
  when(io.mbInitCalDone) { mbInitCalDoneReg := true.B }

  val applyReversalMbTxReg = RegInit(false.B)
  io.applyLaneReversal := applyReversalMbTxReg

  // TODO: This register and MainbandWidth both hold the Tx code. Make them one.
  val localTxFunctionalLanesReg = RegInit(
    "b011".U(3.W) // all Lanes functional (Table 4-9)
  )
  val faultInLowerLanes = RegInit(false.B)
  val faultInUpperLanes = RegInit(false.B)
  val localFuncLanesWire = Wire(UInt(3.W))
  val widthChange = Wire(Bool())

  // Every caller sees the results, so start gates which one captures them.
  when(
    io.txPtTestReqInterfaceIo.start && io.txPtTestReqInterfaceIo.ptTestResults.valid
  ) {
    val (lowerFault, upperFault) = PatternLaneMap.faultHalves(
      localTxFunctionalLanesReg,
      io.txPtTestReqInterfaceIo.ptTestResults.bits.map(_.asBool),
      afeParams.mbLanes,
      io.interpretBy8Lane
    )
    faultInLowerLanes := lowerFault
    faultInUpperLanes := upperFault
  }

  val degradeTargetWire = PatternLaneMap.degradeTarget(
    localTxFunctionalLanesReg,
    faultInLowerLanes,
    faultInUpperLanes,
    io.interpretBy8Lane
  )

  localFuncLanesWire := localTxFunctionalLanesReg
  widthChange := localTxFunctionalLanesReg =/= localFuncLanesWire

  // TODO: SVA that widthChange is high only in sREPAIRMB s2.
  io.txWidthChanged := widthChange
  io.localFunctionalLanes := localTxFunctionalLanesReg
  io.localTxCodeValid := currentState === MBInitState.sREPAIRMB &&
    substateReg === MBInitSubstate.s2

  // ===========================================================================
  // Pause
  // ===========================================================================
  // sec 9.5.3.25 code 001b pauses after PARAM step 2, so hold at its exit.
  val paramExitReady = Wire(Bool())
  val paramPauseHeld = RegInit(false.B)
  paramExitReady := (io.requesterRdy && io.responderRdy) || paramPauseHeld

  // Latch the pause, because a hold on the rdy pair alone can strand one half.
  when(currentState =/= nextState) {
    paramPauseHeld := false.B
  }.elsewhen(paramExitReady && io.pauseAfterParam) {
    paramPauseHeld := true.B
  }

  // The latch gives the LTSM a level to gate the resume and the timeout on.
  io.pausedAtParam := paramPauseHeld

  // {MBINIT.CAL Done req} is the sec 9.5.3.25 resume message for MBINIT.
  io.pauseSidebandResume := paramPauseHeld && io.sbLaneIo.rx.valid &&
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.MBINIT_CAL_DONE_REQ)

  // ===========================================================================
  // State machine
  // ===========================================================================
  switch(currentState) {
    is(MBInitState.sPARAM) {
      // PARAM and CAL tri-state the mainband (sec 4.5.3.3.1, sec 4.5.3.3.2).
      when(io.start) {
        errorDetectedReg := false.B
        applyReversalMbTxReg := false.B

        // Start at the sec 9.5.1.5 target, or step 3 overwrites MainbandWidth.
        localTxFunctionalLanesReg := io.initWidthCode
        faultInLowerLanes := false.B
        faultInUpperLanes := false.B
        mbInitCalDoneReg := false.B

        sbMsgExchanger.io.req.valid := io.phyParams.localPhySettings.valid
        sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
          SBM.MBINIT_PARAM_CONFIGURATION_REQ,
          data = mbInitParamReqDataBits
        )
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_PARAM_CONFIGURATION_RESP

        io.phyParams.remotePhySettings.valid := sbMsgExchanger.io.resp.valid

        requesterRdy := sbMsgExchanger.io.exchDone && io.phyParams.interoperableParamsFound

        when(paramExitReady && !io.pauseAfterParam) {
          nextState := MBInitState.sCAL
        }
      }
    }
    is(MBInitState.sCAL) {
      sbMsgExchanger.io.req.valid := mbInitCalDoneReg
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(SBM.MBINIT_CAL_DONE_REQ)
      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_CAL_DONE_RESP

      requesterRdy := sbMsgExchanger.io.exchDone

      when(io.requesterRdy && io.responderRdy) {
        nextState := MBInitState.sREPAIRCLK
      }
    }
    is(MBInitState.sREPAIRCLK) {
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B

      // sec 4.5.3.3.3 step 1 also sends the clock repair pattern on TTRK_L.
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txTrackPattern := true.B
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      io.mbLaneCtrlIo.rxTrackEn := true.B

      io.usingPatternWriter := true.B

      switch(substateReg) {
        is(MBInitSubstate.s0) { // INIT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRCLK_INIT_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_INIT_RESP

          io.patternWriterIo.req.valid := sbMsgExchanger.io.exchDone && io.patternWriterIo.req.ready
          io.patternWriterIo.req.bits.patternType := PatternSelect.CLKREPAIR

          when(io.patternWriterIo.resp.complete) {
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) { // RESULT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRCLK_RESULT_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_RESULT_RESP

          errorDetectedWire := sbMsgExchanger.io.resp.valid && !repairClkSuccess

          when(
            (sbMsgExchanger.io.resp.valid && repairClkSuccess) && !errorDetected
          ) {
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) { // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRCLK_DONE_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_DONE_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sREPAIRVAL
            nextSubstate := MBInitSubstate.s0
          }
        }
      }
    }
    is(MBInitState.sREPAIRVAL) {
      // These states give no clock phase value, so PhyBridge holds a default.
      io.mbLaneCtrlIo.txDataEn.foreach(x =>
        x := true.B // sec 4.5.3.3.4 holds the Data Lanes low
      )
      io.mbLaneCtrlIo.txClkEn := true.B

      // sec 4.5.3.3.4 holds the Clock Transmitters low outside the pattern.
      io.mbLaneCtrlIo.txClkHeldLow := true.B

      // sec 4.5.3.3.4 says nothing of Track, so drive it low like REVERSALMB.
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      io.usingPatternWriter := true.B

      switch(substateReg) {
        is(MBInitSubstate.s0) { // INIT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRVAL_INIT_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_INIT_RESP

          // sec 4.5.3.3.4 tri-states the Valid Transmitter outside VALTRAIN.
          io.mbLaneCtrlIo.txValidEn := true.B

          io.patternWriterIo.req.valid := sbMsgExchanger.io.exchDone && io.patternWriterIo.req.ready
          io.patternWriterIo.req.bits.patternType := PatternSelect.VALTRAIN

          when(io.patternWriterIo.resp.complete) {
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) { // RESULT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRVAL_RESULT_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_RESULT_RESP

          errorDetectedWire := sbMsgExchanger.io.resp.valid && !repairValSuccess

          when(
            sbMsgExchanger.io.resp.valid && repairValSuccess && !errorDetected
          ) {
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) { // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRVAL_DONE_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_DONE_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sREVERSALMB
            nextSubstate := MBInitSubstate.s0
          }
        }
      }
    }
    is(MBInitState.sREVERSALMB) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkHeldLow := true.B
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      io.usingPatternWriter := true.B

      switch(substateReg) {
        is(MBInitSubstate.s0) { // INIT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REVERSALMB_INIT_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_INIT_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) { // CLEAR ERROR
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REVERSALMB_CLEAR_ERROR_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_CLEAR_ERROR_RESP

          io.patternWriterIo.req.valid := sbMsgExchanger.io.exchDone && io.patternWriterIo.req.ready
          io.patternWriterIo.req.bits.patternType := PatternSelect.PERLANEID

          when(io.patternWriterIo.resp.complete) {
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) { // RESULT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REVERSALMB_RESULT_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_RESULT_RESP

          // sec 4.5.3.3.5 step 10 fails the state if reversal does not help.
          errorDetectedWire := sbMsgExchanger.io.resp.valid &&
            !reversalMbSuccess &&
            applyReversalMbTxReg

          when(
            sbMsgExchanger.io.resp.valid && reversalMbSuccess && !errorDetected
          ) {
            nextSubstate := MBInitSubstate.s3
          }.elsewhen(
            sbMsgExchanger.io.resp.valid && !reversalMbSuccess &&
              !applyReversalMbTxReg && !errorDetected
          ) {
            nextSubstate := MBInitSubstate.s1
            applyReversalMbTxReg := true.B
          }
        }
        is(MBInitSubstate.s3) { // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REVERSALMB_DONE_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_DONE_RESP

          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sREPAIRMB
            nextSubstate := MBInitSubstate.s0
          }
        }
      }
    }
    is(MBInitState.sREPAIRMB) {
      io.mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.txClkHeldLow := true.B
      io.mbLaneCtrlIo.txClkEn := true.B
      io.mbLaneCtrlIo.txTrackEn := true.B
      io.mbLaneCtrlIo.txValidEn := true.B
      io.mbLaneCtrlIo.rxDataEn.foreach(_ := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      switch(substateReg) {
        is(MBInitSubstate.s0) { // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRMB_START_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRMB_START_RESP

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) { // TX INIT D2C POINT TEST
          io.txPtTestReqInterfaceIo.start := true.B

          when(io.txPtTestReqInterfaceIo.done) {
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) { // APPLY DEGRADE
          localFuncLanesWire := degradeTargetWire

          // localFunctionalLanes must always show the current code.
          when(widthChange) {
            io.localFunctionalLanes := localFuncLanesWire
          }

          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRMB_APPLY_DEGRADE_REQ,
            msgInfo = Cat(0.U(13.W), localFuncLanesWire)
          )

          // A 000b req still goes out, and no resp is awaited (sec 4.5.3.3.6).
          val degradeNotPossible = degradeTargetWire === "b000".U
          when(!degradeNotPossible) {
            sbMsgExchanger.io.rxRefBitPattern.valid := true.B
            sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRMB_APPLY_DEGRADE_RESP
          }

          when(sbMsgExchanger.io.msgSent && degradeNotPossible) {
            errorDetectedWire := true.B
          }
          requesterRdy := sbMsgExchanger.io.exchDone & !errorDetected

          // Commit once the partner has changed width and sent its own req.
          when(io.requesterRdy && io.responderRdy) {
            localTxFunctionalLanesReg := localFuncLanesWire
            when(widthChange || io.rxWidthChanged) {
              nextSubstate := MBInitSubstate.s1
            }
            when(!(widthChange || io.rxWidthChanged)) {
              nextSubstate := MBInitSubstate.s3
            }
          }
        }
        is(MBInitSubstate.s3) { // END
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRMB_END_REQ
          )
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRMB_END_RESP
          requesterRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sTOMBTRAIN
          }
        }
      }
    }
    is(MBInitState.sTOMBTRAIN) {
      io.done := true.B
    }
  }
}

class MBInitResponder(afeParams: AfeParams, sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val pauseAfterParam = Input(Bool())
    val pausedAtParam = Output(Bool())
    val pauseSidebandResume = Output(Bool())
    val start = Input(Bool())
    val requesterRdy = Input(Bool())
    val interpretBy8Lane = Input(Bool())
    val txWidthChanged = Input(Bool())
    val localTxFunctionalLanes = Input(UInt(3.W))
    val localTxCodeValid = Input(Bool())

    val done = Output(Bool())
    val error = Output(Bool())
    val currentState = Output(MBInitState())
    val responderRdy = Output(Bool())
    val rxWidthChanged = Output(Bool())
    val widthApply = Output(new MainbandWidthApply)
    val remoteFunctionalLanes = Output(UInt(3.W))
    val usingPatternReader = Output(Bool())

    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternReaderIo = Flipped(new PatternReaderClientIO(afeParams.mbLanes))
    val txPtTestRespInterfaceIo = new TxInitPtTestResponderInterfaceIO()
    val phyParams = new MbInitParamsIO
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // ===========================================================================
  // State registers
  // ===========================================================================
  // Set when REVERSALMB s0 or s3 takes the clear error req for s1.
  val gotLFSRClearReq = RegInit(false.B)

  // The init resp waits only for the arming, because rxDivClk starts later.
  val readerArmed = RegInit(false.B)
  val reversalSecondPass = RegInit(false.B)

  val currentState = RegInit(MBInitState.sPARAM)
  val nextState = WireInit(currentState)
  currentState := nextState
  io.currentState := currentState

  val substateReg = RegInit(MBInitSubstate.s0)
  val nextSubstate = WireInit(substateReg)
  substateReg := nextSubstate

  when(currentState =/= nextState || substateReg =/= nextSubstate) {
    readerArmed := false.B
  }

  val responderRdyStatusReg = RegInit(false.B)
  val responderRdy = WireInit(false.B)
  when((currentState =/= nextState) || (substateReg =/= nextSubstate)) {
    responderRdyStatusReg := false.B
  }.elsewhen(responderRdy) {
    responderRdyStatusReg := true.B
  }
  io.responderRdy := responderRdyStatusReg || responderRdy

  val errorDetectedReg = RegInit(false.B)
  val errorDetectedWire = WireInit(false.B)
  val errorDetected = Wire(Bool())

  errorDetected := errorDetectedReg || errorDetectedWire
  when(errorDetectedWire) {
    errorDetectedReg := true.B
  }

  io.error := errorDetectedReg
  io.done := false.B

  // ===========================================================================
  // Defaults
  // ===========================================================================
  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := (currentState =/= nextState) || (substateReg =/= nextSubstate)
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  // s3 waits for one of two messages, so it drives rx.ready itself.
  when(
    (currentState === MBInitState.sREVERSALMB) && (substateReg === MBInitSubstate.s3)
  ) {
    io.sbLaneIo.rx.ready := false.B
  }.otherwise {
    io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready
  }

  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits.patternType := PatternSelect.PERLANEID
  io.patternReaderIo.req.bits.comparisonMode := ComparisonMode.PERLANE
  io.patternReaderIo.req.bits.errorThreshold := 0.U

  // MBINIT passes a Lane on 16 consecutive iterations (sec 4.5.3.3.3).
  io.patternReaderIo.req.bits.doConsecutiveCount := true.B
  io.patternReaderIo.done := false.B
  io.patternReaderIo.resp.ready := false.B
  io.usingPatternReader := false.B

  io.txPtTestRespInterfaceIo.start := false.B
  io.txPtTestRespInterfaceIo.patternType := PatternSelect.PERLANEID

  // ===========================================================================
  // Message fields
  // ===========================================================================
  io.phyParams.remotePhySettings.valid := false.B
  io.phyParams.remotePhySettings.bits.txAdjRuntime := sbMsgExchanger.io.resp
    .bits(79)
  io.phyParams.remotePhySettings.bits.sbFeatExt := sbMsgExchanger.io.resp.bits(
    78
  )
  io.phyParams.remotePhySettings.bits.ucieSx8 := sbMsgExchanger.io.resp.bits(77)
  io.phyParams.remotePhySettings.bits.moduleId := sbMsgExchanger.io.resp
    .bits(76, 75)
  io.phyParams.remotePhySettings.bits.clockPhase := sbMsgExchanger.io.resp.bits(
    74
  )
  io.phyParams.remotePhySettings.bits.clockMode := sbMsgExchanger.io.resp.bits(
    73
  )
  io.phyParams.remotePhySettings.bits.voltageSwing := sbMsgExchanger.io.resp
    .bits(72, 68)
  io.phyParams.remotePhySettings.bits.maxDataRate := sbMsgExchanger.io.resp
    .bits(67, 64)

  // Table 7-9 MsgInfo[3] is RRDCK_L, which Standard Package lacks, so pass.
  val mbRepairClkResult = Wire(UInt(16.W))
  mbRepairClkResult := Cat(
    0.U(12.W),
    1.U(1.W),
    io.patternReaderIo.resp.bits.perLaneStatusBits(2),
    io.patternReaderIo.resp.bits.perLaneStatusBits(1),
    io.patternReaderIo.resp.bits.perLaneStatusBits(0)
  )

  // Table 7-9 MsgInfo bit 1 is RRDVLD_L, which reports pass as RRDCK_L does.
  val mbRepairValResult = Wire(UInt(16.W))
  mbRepairValResult := Cat(
    0.U(14.W),
    1.U(1.W),
    io.patternReaderIo.resp.bits.perLaneStatusBits(0)
  )

  val mbReversalMbResult = Wire(UInt(64.W))
  mbReversalMbResult := Cat(
    0.U((64 - afeParams.mbLanes).W),
    io.patternReaderIo.resp.bits.perLaneStatusBits.asUInt
  )

  val mbInitParamRespDataBits = Wire(UInt(64.W))

  // Here localPhySettings holds the resp values that MBInitSM resolves.
  mbInitParamRespDataBits := Cat(
    0.U(48.W), // Reserved
    io.phyParams.localPhySettings.bits.txAdjRuntime,
    io.phyParams.localPhySettings.bits.sbFeatExt,
    0.U(3.W),
    io.phyParams.localPhySettings.bits.clockPhase,
    io.phyParams.localPhySettings.bits.clockMode,
    0.U(5.W),
    io.phyParams.localPhySettings.bits.maxDataRate
  )

  // ===========================================================================
  // Width degrade
  // ===========================================================================
  // currRemoteTxFunctionalLanes is the Receiver code that is in use.
  val currRemoteTxFunctionalLanes = RegInit(
    "b011".U(3.W) // all Lanes functional (Table 4-9)
  )
  val newRemoteTxFunctionalLanes = RegInit("b011".U(3.W))
  val prevRemoteTxFunctionalLanes = RegInit("b011".U(3.W))
  val incRemoteFuncLanesWire = Wire(UInt(3.W))
  val widthChange = WireInit(false.B)

  incRemoteFuncLanesWire := sbMsgExchanger.io.resp.bits(42, 40)

  // The only width change of MBINIT (sec 4.5.3.3.6 step 3).
  val widthApplyValid = WireInit(false.B)
  io.widthApply.valid := widthApplyValid
  io.widthApply.txCode := io.localTxFunctionalLanes
  val rxCode = PatternLaneMap.receiverLaneMap(
    newRemoteTxFunctionalLanes,
    io.localTxFunctionalLanes
  )
  io.widthApply.rxCode := rxCode

  // Both partners repeat step 2 if either width changes (sec 4.5.3.3.6 step 3).
  widthChange := currRemoteTxFunctionalLanes =/= rxCode ||
    prevRemoteTxFunctionalLanes =/= newRemoteTxFunctionalLanes
  io.remoteFunctionalLanes := rxCode
  io.rxWidthChanged := widthChange

  // ===========================================================================
  // Pause
  // ===========================================================================
  // The same pause latch and resume decode as in the requester.
  val paramExitReady = Wire(Bool())
  val paramPauseHeld = RegInit(false.B)
  paramExitReady := (io.requesterRdy && io.responderRdy) || paramPauseHeld
  when(currentState =/= nextState) {
    paramPauseHeld := false.B
  }.elsewhen(paramExitReady && io.pauseAfterParam) {
    paramPauseHeld := true.B
  }
  io.pausedAtParam := paramPauseHeld
  io.pauseSidebandResume := paramPauseHeld && io.sbLaneIo.rx.valid &&
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.MBINIT_CAL_DONE_REQ)

  // ===========================================================================
  // State machine
  // ===========================================================================
  switch(currentState) {
    is(MBInitState.sPARAM) {
      when(io.start) {
        currRemoteTxFunctionalLanes := "b011".U
        newRemoteTxFunctionalLanes := "b011".U
        prevRemoteTxFunctionalLanes := "b011".U
        errorDetectedReg := false.B
        gotLFSRClearReq := false.B

        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_PARAM_CONFIGURATION_REQ

        io.phyParams.remotePhySettings.valid := sbMsgExchanger.io.resp.valid

        sbMsgExchanger.io.req.valid := io.phyParams.localPhySettings.valid
        sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
          SBM.MBINIT_PARAM_CONFIGURATION_RESP,
          data = mbInitParamRespDataBits
        )

        responderRdy := sbMsgExchanger.io.exchDone && io.phyParams.interoperableParamsFound

        when(paramExitReady && !io.pauseAfterParam) {
          nextState := MBInitState.sCAL
        }
      }
    }
    is(MBInitState.sCAL) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_CAL_DONE_REQ

      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(SBM.MBINIT_CAL_DONE_RESP)

      responderRdy := sbMsgExchanger.io.exchDone
      when(io.requesterRdy && io.responderRdy) {
        nextState := MBInitState.sREPAIRCLK
      }
    }
    is(MBInitState.sREPAIRCLK) {
      io.usingPatternReader := true.B
      switch(substateReg) {
        is(MBInitSubstate.s0) { // INIT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_INIT_REQ

          io.patternReaderIo.req.bits.patternType := PatternSelect.CLKREPAIR
          io.patternReaderIo.req.valid :=
            sbMsgExchanger.io.msgReceived && !readerArmed
          when(io.patternReaderIo.req.fire) {
            readerArmed := true.B
          }

          sbMsgExchanger.io.req.valid := readerArmed
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRCLK_INIT_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) { // RESULT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_RESULT_REQ

          io.patternReaderIo.done := sbMsgExchanger.io.msgReceived

          sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRCLK_RESULT_RESP,
            msgInfo = mbRepairClkResult
          )

          when(sbMsgExchanger.io.exchDone) {
            io.patternReaderIo.resp.ready := true.B
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) { // DONE
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_DONE_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRCLK_DONE_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sREPAIRVAL
            nextSubstate := MBInitSubstate.s0
          }
        }
      }
    }
    is(MBInitState.sREPAIRVAL) {
      io.usingPatternReader := true.B
      switch(substateReg) {
        is(MBInitSubstate.s0) { // INIT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_INIT_REQ

          io.patternReaderIo.req.bits.patternType := PatternSelect.VALTRAIN
          io.patternReaderIo.req.valid :=
            sbMsgExchanger.io.msgReceived && !readerArmed
          when(io.patternReaderIo.req.fire) {
            readerArmed := true.B
          }

          sbMsgExchanger.io.req.valid := readerArmed
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRVAL_INIT_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) { // RESULT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_RESULT_REQ

          io.patternReaderIo.done := sbMsgExchanger.io.msgReceived

          sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRVAL_RESULT_RESP,
            msgInfo = mbRepairValResult
          )
          when(sbMsgExchanger.io.exchDone) {
            io.patternReaderIo.resp.ready := true.B
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) { // DONE
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_DONE_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRVAL_DONE_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sREVERSALMB
            nextSubstate := MBInitSubstate.s0
          }
        }
      }
    }
    is(MBInitState.sREVERSALMB) {
      io.usingPatternReader := true.B
      switch(substateReg) {
        is(MBInitSubstate.s0) { // INIT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_INIT_REQ

          io.patternReaderIo.req.bits.patternType := PatternSelect.PERLANEID
          io.patternReaderIo.req.valid :=
            sbMsgExchanger.io.msgReceived && !readerArmed
          when(io.patternReaderIo.req.fire) {
            readerArmed := true.B
          }

          sbMsgExchanger.io.req.valid := readerArmed
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REVERSALMB_INIT_RESP
          )

          // Take an early clear error req here, or the queue drops it.
          when(
            io.sbLaneIo.rx.valid && SBMsgCompare(
              io.sbLaneIo.rx.bits.data,
              SBM.MBINIT_REVERSALMB_CLEAR_ERROR_REQ
            )
          ) {
            io.sbLaneIo.rx.ready := true.B
            gotLFSRClearReq := true.B
          }

          when(sbMsgExchanger.io.exchDone) {
            reversalSecondPass := false.B
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) { // CLEAR ERROR
          when(!gotLFSRClearReq) {
            sbMsgExchanger.io.rxRefBitPattern.valid := true.B
            sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_CLEAR_ERROR_REQ
          }

          val clearReqSeen = sbMsgExchanger.io.msgReceived || gotLFSRClearReq

          // Re-arm first, because pass 2 starts the partner on this resp.
          io.patternReaderIo.req.bits.patternType := PatternSelect.PERLANEID
          io.patternReaderIo.req.valid :=
            reversalSecondPass && clearReqSeen && !readerArmed
          when(io.patternReaderIo.req.fire) {
            readerArmed := true.B
          }

          sbMsgExchanger.io.req.valid :=
            clearReqSeen && (!reversalSecondPass || readerArmed)
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REVERSALMB_CLEAR_ERROR_RESP
          )

          // If s0 or s3 took the req, msgReceived stays low, so end on msgSent.
          when(sbMsgExchanger.io.msgSent) {
            gotLFSRClearReq := false.B
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) { // RESULT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_RESULT_REQ

          io.patternReaderIo.done := sbMsgExchanger.io.msgReceived

          sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REVERSALMB_RESULT_RESP,
            data = mbReversalMbResult
          )

          when(sbMsgExchanger.io.exchDone) {
            io.patternReaderIo.resp.ready := true.B
            nextSubstate := MBInitSubstate.s3
          }
        }
        is(MBInitSubstate.s3) { // WAIT FOR CLEAR ERROR OR DONE
          when(io.sbLaneIo.rx.valid) {
            when(
              SBMsgCompare(
                io.sbLaneIo.rx.bits.data,
                SBM.MBINIT_REVERSALMB_CLEAR_ERROR_REQ
              )
            ) {
              io.sbLaneIo.rx.ready := true.B
              gotLFSRClearReq := true.B
              reversalSecondPass := true.B
              nextSubstate := MBInitSubstate.s1
            }.elsewhen(
              SBMsgCompare(
                io.sbLaneIo.rx.bits.data,
                SBM.MBINIT_REVERSALMB_DONE_REQ
              )
            ) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBInitSubstate.s4
            }
          }
        }
        is(MBInitSubstate.s4) { // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REVERSALMB_DONE_RESP
          )

          // Leave on the rdy pair, or the requester loses the rdy it needs.
          responderRdy := sbMsgExchanger.io.msgSent
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sREPAIRMB
            nextSubstate := MBInitSubstate.s0
          }
        }
      }
    }
    is(MBInitState.sREPAIRMB) {
      switch(substateReg) {
        is(MBInitSubstate.s0) {
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRMB_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRMB_START_RESP
          )

          when(sbMsgExchanger.io.exchDone) {
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) { // TX INIT D2C POINT TEST
          io.txPtTestRespInterfaceIo.start := true.B

          when(io.txPtTestRespInterfaceIo.done === true.B) {
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) { // APPLY DEGRADE
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRMB_APPLY_DEGRADE_REQ

          when(sbMsgExchanger.io.resp.valid) {
            errorDetectedWire := incRemoteFuncLanesWire === "b000".U
            newRemoteTxFunctionalLanes := incRemoteFuncLanesWire
          }

          // Set both widths before this resp (sec 4.5.3.3.6 step 3).
          // The Tx width is known only after the local step 2.
          val applyWidth = sbMsgExchanger.io.msgReceived &&
            io.localTxCodeValid && !errorDetected
          widthApplyValid := applyWidth
          sbMsgExchanger.io.req.valid := applyWidth
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRMB_APPLY_DEGRADE_RESP
          )

          responderRdy := sbMsgExchanger.io.exchDone

          when(io.requesterRdy && io.responderRdy) {
            currRemoteTxFunctionalLanes := rxCode
            prevRemoteTxFunctionalLanes := newRemoteTxFunctionalLanes
            when(widthChange || io.txWidthChanged) {
              nextSubstate := MBInitSubstate.s1
            }
            when(!(widthChange || io.txWidthChanged)) {
              nextSubstate := MBInitSubstate.s3
            }
          }
        }
        is(MBInitSubstate.s3) {
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRMB_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.MBINIT_REPAIRMB_END_RESP
          )
          responderRdy := sbMsgExchanger.io.exchDone
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sTOMBTRAIN
          }
        }
      }
    }
    is(MBInitState.sTOMBTRAIN) {
      io.done := true.B
    }
  }
}
