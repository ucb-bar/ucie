package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/* 
  Description: Contains the MBInit state machine top, requester, and responder module.
  The top module instantiates both the requester and responder.

  NOTE: There are remaining TODOs in this file.
  1. Functionality - how to implement sCAL and how to set clock phase to center of UI
  2. Need to write SVAs (Not required for functionality)
*/

// ============================================================================
// Bundles & Objects
// ============================================================================
class MbInitPHYParamExchangeIO extends Bundle {
  val voltageSwing = Output(UInt(5.W))
  val maxDataRate = Output(UInt(4.W))
  val clockMode = Output(UInt(1.W))
  val clockPhase = Output(UInt(1.W))
  val ucieSx8 = Output(UInt(1.W))
  val sbFeatExt = Output(UInt(1.W))
  val txAdjRuntime = Output(UInt(1.W))
  val moduleId = Output(UInt(2.W))
}

class MbInitParamsIO extends Bundle {
  val localPhySettings = Flipped(Valid(new MbInitPHYParamExchangeIO()))
  val remotePhySettings = Valid(new MbInitPHYParamExchangeIO())
  val interoperableParamsFound = Input(Bool())  
}

object MBInitState extends ChiselEnum {
  val sPARAM, sCAL, sREPAIRCLK, sREPAIRVAL, sREVERSALMB, sREPAIRMB, sTOMBTRAIN = Value
}
object MBInitSubstate extends ChiselEnum {
  val s0, s1, s2, s3, s4 = Value
}

// ============================================================================
// Modules
// ============================================================================
class MBInitSM(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val mbInitCalDone = Input(Bool())
    val localPhySettings = Flipped(Valid(new MbInitPHYParamExchangeIO()))

    // OUT
    val currentState = Output(MBInitState())
    val mbLaneCtrlIo = new MainbandLaneCtrlIO(afeParams)
    val mbInitCalStart = Output(Bool())
    val applyLaneReversal = Output(Bool())
    val localFunctionalLanes = Output(UInt(3.W))
    val txWidthChanged = Output(Bool())
    val remoteFunctionalLanes = Output(UInt(3.W))   
    val rxWidthChanged = Output(Bool())
    val interoperableParamsNotFound = Output(Bool())  // Error signal in fsmCtrl will also go HIGH
    val negotiatedPhySettings = Valid(new MbInitPHYParamExchangeIO())
    val usingPatternWriter = Output(Bool())
    val usingPatternReader = Output(Bool())

    // Bundles with IN & OUT IOs
    val fsmCtrl = new SubFsmControlIO()
    val patternWriterIo = Flipped(new PatternWriterIO)
    val patternReaderIo = Flipped(new PatternReaderIO(afeParams.mbLanes))
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
    val txPtTestReqInterfaceIo = new TxInitPtTestRequesterInterfaceIO(afeParams)
    val txPtTestRespInterfaceIo = new TxInitPtTestResponderInterfaceIO() 
  })

  val requester = Module(new MBInitRequester(afeParams, sbParams))
  val responder = Module(new MBInitResponder(afeParams, sbParams))

  // TODO: SVA -- when transition state goes high then currentstates of 
  // requester and responder need to be the same

  // TODO: SVA -- assuming io.phyParams.localPhySettings.valid is always high 

  // Remote's params given from {MBINIT_PARAM_CONFIGURATION_REQ}
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

  // These registers populate local die's {MBINIT_PARAM_CONFIGURATION_RESP}
  val localNegotiatedTxAdjRuntime = RegInit(0.U(1.W))
  val localNegotiatedSbFeatExt = RegInit(0.U(1.W))
  val localNegotiatedClockPhase = RegInit(0.U(1.W))
  val localNegotiatedClockMode = RegInit(0.U(1.W))
  val localNegotiatedMaxDataRate = RegInit(0.U(4.W))
  val localNegotiatedParamsValid = RegInit(false.B)

  when(remoteSettingsValid) {
    localNegotiatedTxAdjRuntime := remoteTxAdjRuntime & io.localPhySettings.bits.txAdjRuntime
    localNegotiatedSbFeatExt := remoteSbFeatExt & io.localPhySettings.bits.sbFeatExt
    localNegotiatedClockPhase := remoteClockPhase & io.localPhySettings.bits.clockPhase
    localNegotiatedClockMode := io.localPhySettings.bits.clockMode
    localNegotiatedMaxDataRate := remoteMaxDataRate & io.localPhySettings.bits.maxDataRate            
    localNegotiatedParamsValid := true.B
  }

  // These registers are populated by the remote die's {MBINIT_PARAM_CONFIGURATION_RESP}
  // Good to register these values in a case of a scenario when remote 
  // sends a response to local's request before sending the remote's request
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
  
  // Based on the spec, clock mode bit in the response must be the same as the one in the request
  interoperableParamsComparison := (localNegotiatedTxAdjRuntime === remoteNegotiatedTxAdjRuntime) &&
                                   (localNegotiatedSbFeatExt === remoteNegotiatedSbFeatExt) &&
                                   (localNegotiatedClockPhase === remoteNegotiatedClockPhase) &&
                                   (remoteNegotiatedClockMode === remoteClockMode) &&
                                   (localNegotiatedMaxDataRate === remoteNegotiatedMaxDataRate)

  interoperableParamsFound := interoperableParamsComparison && 
                              remoteNegotiatedParamsValid && localNegotiatedParamsValid
  interoperableParamsErrorFlag := !interoperableParamsComparison && 
                                  remoteNegotiatedParamsValid && localNegotiatedParamsValid

  val interpretBy8Lane = Wire(Bool())
  interpretBy8Lane := (io.localPhySettings.bits.ucieSx8 & io.localPhySettings.valid) |
                      (remoteUcieSx8 & remoteSettingsValid)

  // Requester IN
  requester.io.start := io.fsmCtrl.start
  requester.io.mbInitCalDone := io.mbInitCalDone
  requester.io.responderRdy := responder.io.responderRdy
  requester.io.interpretBy8Lane := interpretBy8Lane
  requester.io.rxWidthChanged := responder.io.rxWidthChanged
  requester.io.phyParams.localPhySettings := io.localPhySettings
  requester.io.phyParams.interoperableParamsFound := interoperableParamsFound
  
  // Responder IN
  responder.io.start := io.fsmCtrl.start  
  responder.io.requesterRdy := requester.io.requesterRdy
  responder.io.interpretBy8Lane := interpretBy8Lane
  responder.io.txWidthChanged := requester.io.txWidthChanged 
  responder.io.phyParams.localPhySettings.valid := localNegotiatedParamsValid
  responder.io.phyParams.localPhySettings.bits.voltageSwing := 0.U  // Not used
  responder.io.phyParams.localPhySettings.bits.ucieSx8 := 0.U       // Not used
  responder.io.phyParams.localPhySettings.bits.moduleId := 0.U      // Not used
  responder.io.phyParams.localPhySettings.bits.txAdjRuntime := localNegotiatedTxAdjRuntime
  responder.io.phyParams.localPhySettings.bits.sbFeatExt := localNegotiatedSbFeatExt
  responder.io.phyParams.localPhySettings.bits.clockPhase := localNegotiatedClockPhase
  responder.io.phyParams.localPhySettings.bits.clockMode := localNegotiatedClockMode
  responder.io.phyParams.localPhySettings.bits.maxDataRate := localNegotiatedMaxDataRate
  responder.io.phyParams.interoperableParamsFound := interoperableParamsFound

  // Top level IOs
  io.fsmCtrl.done := requester.io.done && responder.io.done
  io.fsmCtrl.substateTransitioning := requester.io.transitioningState
  io.fsmCtrl.error := requester.io.error | responder.io.error | interoperableParamsErrorFlag
  io.currentState := requester.io.currentState
  io.mbLaneCtrlIo := requester.io.mbLaneCtrlIo
  io.mbInitCalStart := requester.io.mbInitCalStart
  io.applyLaneReversal := requester.io.applyLaneReversal
  io.localFunctionalLanes := requester.io.localFunctionalLanes
  io.txWidthChanged := requester.io.txWidthChanged
  io.remoteFunctionalLanes := responder.io.remoteFunctionalLanes
  io.rxWidthChanged := responder.io.rxWidthChanged
  io.requesterSbLaneIo <> requester.io.sbLaneIo
  io.responderSbLaneIo <> responder.io.sbLaneIo
  io.patternWriterIo <> requester.io.patternWriterIo
  io.patternReaderIo <> responder.io.patternReaderIo
  io.txPtTestReqInterfaceIo <> requester.io.txPtTestReqInterfaceIo
  io.txPtTestRespInterfaceIo <> responder.io.txPtTestRespInterfaceIo
  io.interoperableParamsNotFound := interoperableParamsErrorFlag
  io.usingPatternWriter := requester.io.usingPatternWriter
  io.usingPatternReader := responder.io.usingPatternReader

  // Agreed upon and requested PHY settings that are used between the modules
  // Note: clockMode is setting local TX uses for the remote RX
  io.negotiatedPhySettings.valid := interoperableParamsFound
  io.negotiatedPhySettings.bits.voltageSwing := remoteVoltageSwing
  io.negotiatedPhySettings.bits.maxDataRate := localNegotiatedMaxDataRate
  io.negotiatedPhySettings.bits.clockMode := remoteNegotiatedClockMode
  io.negotiatedPhySettings.bits.clockPhase := localNegotiatedClockPhase
  io.negotiatedPhySettings.bits.ucieSx8 := interpretBy8Lane
  io.negotiatedPhySettings.bits.sbFeatExt := remoteSbFeatExt
  io.negotiatedPhySettings.bits.txAdjRuntime := localNegotiatedTxAdjRuntime
  io.negotiatedPhySettings.bits.moduleId := remoteModuleId
}

class MBInitRequester(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val start = Input(Bool())
    val mbInitCalDone = Input(Bool())
    val responderRdy = Input(Bool())
    val interpretBy8Lane = Input(Bool())
    val rxWidthChanged = Input(Bool())

    // OUT
    val done = Output(Bool())
    val transitioningState = Output(Bool())
    val currentState = Output(MBInitState())
    val mbLaneCtrlIo = new MainbandLaneCtrlIO(afeParams)
    val mbInitCalStart = Output(Bool())
    val requesterRdy = Output(Bool())
    val applyLaneReversal = Output(Bool())
    val localFunctionalLanes = Output(UInt(3.W))
    val txWidthChanged = Output(Bool())
    val error = Output(Bool())
    val usingPatternWriter = Output(Bool())

    // Bundles with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val phyParams = new MbInitParamsIO()
    val patternWriterIo = Flipped(new PatternWriterIO)
    val txPtTestReqInterfaceIo = new TxInitPtTestRequesterInterfaceIO(afeParams)  
  })  

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // FSM state register
  val currentState = RegInit(MBInitState.sPARAM)
  val nextState = WireInit(currentState)  
  currentState := nextState
  io.currentState := currentState

  // Substate register
  val substateReg = RegInit(MBInitSubstate.s0)
  val nextSubstate = WireInit(substateReg)
  substateReg := nextSubstate  

  // Requester ready logic -- used by responder
  val requesterRdyStatusReg = RegInit(false.B)
  val requesterRdy = WireInit(false.B)
  when((currentState =/= nextState) || (substateReg =/= nextSubstate)) {
    requesterRdyStatusReg := false.B 
  }  
  when(requesterRdy) {
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

  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState) || (substateReg =/= nextSubstate)
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo
  
  // mbLaneCtrlIo Defaults
  io.mbLaneCtrlIo.txDataTriState.foreach(x => x := true.B)
  io.mbLaneCtrlIo.txClkTriState := true.B
  io.mbLaneCtrlIo.txValidTriState := true.B
  io.mbLaneCtrlIo.txTrackTriState := true.B
  io.mbLaneCtrlIo.rxDataEn.foreach(x => x := false.B)
  io.mbLaneCtrlIo.rxClkEn := false.B
  io.mbLaneCtrlIo.rxValidEn := false.B
  io.mbLaneCtrlIo.rxTrackEn := false.B

  // patternWriterIo defaults
  io.patternWriterIo.req.valid := false.B
  io.patternWriterIo.req.bits.patternType := PatternSelect.VALTRAIN

  // txPtTestReqInterfaceIo Defaults (defaults are used in MBINIT.REPAIRMB state)
  io.txPtTestReqInterfaceIo.start := false.B
  io.txPtTestReqInterfaceIo.patternType := PatternSelect.PERLANEID

  // NOTE: Currently TxD2CPointTest, PatternWriter don't use many of these parameters,
  // since pattern counts are fixed for normal link training operation. 
  // Setting them according to spec for the data bits when sending message to Remote die.
  io.txPtTestReqInterfaceIo.linkTrainingParameters.clockPhase := 0.U        // center clock pi   
  io.txPtTestReqInterfaceIo.linkTrainingParameters.dataPattern := 1.U       // per lane id
  io.txPtTestReqInterfaceIo.linkTrainingParameters.validPattern := 0.U      // valtrain
  io.txPtTestReqInterfaceIo.linkTrainingParameters.patternMode := 0.U       // continuous
  io.txPtTestReqInterfaceIo.linkTrainingParameters.iterationCount := 1.U    // num of bursts
  io.txPtTestReqInterfaceIo.linkTrainingParameters.idleCount := 0.U         // UI to wait
  io.txPtTestReqInterfaceIo.linkTrainingParameters.burstCount := 2048.U     // UI to send per burst
  io.txPtTestReqInterfaceIo.linkTrainingParameters.maxErrorThreshold := 0.U // consecutive detect.
  io.txPtTestReqInterfaceIo.linkTrainingParameters.comparisonMode := 0.U    // per lane
  
  // MBInit.PARAM Configuration message bits
  val mbInitParamReqDataBits = Wire(UInt(64.W))
  mbInitParamReqDataBits := Cat(0.U(48.W),       // Reserved
                                io.phyParams.localPhySettings.bits.txAdjRuntime,
                                io.phyParams.localPhySettings.bits.sbFeatExt,
                                io.phyParams.localPhySettings.bits.ucieSx8,
                                io.phyParams.localPhySettings.bits.moduleId,
                                io.phyParams.localPhySettings.bits.clockPhase,
                                io.phyParams.localPhySettings.bits.clockMode,
                                io.phyParams.localPhySettings.bits.voltageSwing,
                                io.phyParams.localPhySettings.bits.maxDataRate)

  // PhyParams from Remote die defaults (This is coming from Remote die's response)
  // Parameter interopertability done in top level
  io.phyParams.remotePhySettings.valid := false.B
  io.phyParams.remotePhySettings.bits.txAdjRuntime := sbMsgExchanger.io.resp.bits(79)
  io.phyParams.remotePhySettings.bits.sbFeatExt := sbMsgExchanger.io.resp.bits(78)
  io.phyParams.remotePhySettings.bits.ucieSx8 := 0.U
  io.phyParams.remotePhySettings.bits.moduleId := 0.U
  io.phyParams.remotePhySettings.bits.clockPhase := sbMsgExchanger.io.resp.bits(74)
  io.phyParams.remotePhySettings.bits.clockMode := sbMsgExchanger.io.resp.bits(73)
  io.phyParams.remotePhySettings.bits.voltageSwing := 0.U
  io.phyParams.remotePhySettings.bits.maxDataRate := sbMsgExchanger.io.resp.bits(67, 64)

  // Success indicators for pattern detection
  val repairClkSuccess = Wire(Bool())
  repairClkSuccess := sbMsgExchanger.io.resp.bits(40) && 
                      sbMsgExchanger.io.resp.bits(41) && 
                      sbMsgExchanger.io.resp.bits(42)                      
  val repairValSuccess = Wire(Bool())
  repairValSuccess := sbMsgExchanger.io.resp.bits(40)
  val reversalMbSuccess = Wire(Bool())

  // NOTE: PopCount uses an adder tree. So, might be a long path. Can add 
  // an extra cycle by registering the count, and then do the comparison
  when(io.interpretBy8Lane) {
    reversalMbSuccess := PopCount(sbMsgExchanger.io.resp.bits(70,63)) > 4.U
  }.otherwise {
    reversalMbSuccess := PopCount(sbMsgExchanger.io.resp.bits(78,63)) > 8.U
  }

  // MBINIT.Cal registers/wires
  val mbInitCalDoneReg = RegInit(false.B)
  when(io.mbInitCalDone) {
    mbInitCalDoneReg := true.B
  }

  // MBINIT.ReversalMB registers/wires
  val applyReversalMbTxReg = RegInit(false.B)
  io.applyLaneReversal := applyReversalMbTxReg
  
  // MBINIT.RepairMB registers/wires
  val localTxFunctionalLanesReg = RegInit("b011".U(3.W)) // Default code all lanes are functional
  val faultInLowerLanes = RegInit(false.B)
  val faultInUpperLanes = RegInit(false.B)
  val localFuncLanesWire = Wire(UInt(3.W))
  val widthChange = Wire(Bool())
  val isLanes0To15 = Wire(Bool())
  val isLanes0To7 = Wire(Bool())
  val isLowerLanes = Wire(Bool()) // Logical lanes 0-7 (or 0-3, if by 8 lanes)
  val isUpperLanes = Wire(Bool()) // Logical lanes 8-15 (or 4-7, if by 8 lanes)
  val onlyLowerLanesFailed = Wire(Bool())
  val onlyUpperLanesFailed = Wire(Bool())
  val allLanesFailed = Wire(Bool())
  val widthDegradeFromAllToLowerBy8 = Wire(Bool())
  val widthDegradeFromAllToUpperBy8 = Wire(Bool())
  val widthDegradeFromAllToLower = Wire(Bool())
  val widthDegradeFromAllToUpper = Wire(Bool())
  val laneRepairDegradeCondSel = Wire(UInt(5.W))

  // Using `start` in the condition acts as a gate. Can have valid and data bits fan out to
  // modules that can initiate a point test but only modules that started the point test will
  // register the values. Eliminates any arbitration logic in the top level.
  when(io.txPtTestReqInterfaceIo.start && io.txPtTestReqInterfaceIo.ptTestResults.valid) {
    when(io.interpretBy8Lane) {
      faultInLowerLanes := Cat(io.txPtTestReqInterfaceIo.ptTestResults.bits(0),
                               io.txPtTestReqInterfaceIo.ptTestResults.bits(1),
                               io.txPtTestReqInterfaceIo.ptTestResults.bits(2),
                               io.txPtTestReqInterfaceIo.ptTestResults.bits(3)).orR
      faultInUpperLanes := Cat(io.txPtTestReqInterfaceIo.ptTestResults.bits(4),
                               io.txPtTestReqInterfaceIo.ptTestResults.bits(5),
                               io.txPtTestReqInterfaceIo.ptTestResults.bits(6),
                               io.txPtTestReqInterfaceIo.ptTestResults.bits(7)).orR
    }.otherwise {
      // Get top (afeParams.mbLanes / 2) lanes
      faultInLowerLanes := io.txPtTestReqInterfaceIo.ptTestResults
                                                    .bits
                                                    .take(afeParams.mbLanes / 2)
                                                    .map(_.asBool)
                                                    .reduce(_ || _)
      // Get bottom (afeParams.mbLanes / 2) lanes                                                    
      faultInUpperLanes := io.txPtTestReqInterfaceIo.ptTestResults
                                                    .bits
                                                    .drop(afeParams.mbLanes / 2)
                                                    .map(_.asBool)
                                                    .reduce(_ || _)
    }
  }

  isLanes0To15 := localTxFunctionalLanesReg === "b011".U
  isLanes0To7 := isLanes0To15 && io.interpretBy8Lane
  isLowerLanes := (localTxFunctionalLanesReg === "b001".U) ||
                  (localTxFunctionalLanesReg === "b100".U)
  isUpperLanes := (localTxFunctionalLanesReg === "b010".U) || 
                  (localTxFunctionalLanesReg === "b101".U)
  onlyLowerLanesFailed := !faultInUpperLanes && faultInLowerLanes
  onlyUpperLanesFailed := faultInUpperLanes && !faultInLowerLanes
            
  // Plausible conditions for width degrade (laneRepairCondSel)   
  allLanesFailed := (isLowerLanes && faultInLowerLanes) || 
                    (isUpperLanes && faultInUpperLanes) ||
                    (isLanes0To15 && faultInUpperLanes && faultInLowerLanes)


  // Upper lanes failed to width degrade to lower lanes (when interpreting by 8 lanes)
  widthDegradeFromAllToLowerBy8 := isLanes0To7 && onlyUpperLanesFailed

  // Lower lanes failed so width degrade to upper lanes (when interpreting by 8 lanes)
  widthDegradeFromAllToUpperBy8 := isLanes0To7 && onlyLowerLanesFailed

  // Upper lanes failed to width degrade to lower lanes
  widthDegradeFromAllToLower := isLanes0To15 && onlyUpperLanesFailed

  // Lower lanes failed so width degrade to upper lanes
  widthDegradeFromAllToUpper := isLanes0To15 && onlyLowerLanesFailed

  // TODO: SVA for one hot (or generate onehot checker hw gated with a sim flag, or wrap in layer)
  laneRepairDegradeCondSel := Cat(allLanesFailed,
                                  widthDegradeFromAllToLower,
                                  widthDegradeFromAllToUpper,
                                  widthDegradeFromAllToLowerBy8,
                                  widthDegradeFromAllToUpperBy8)
  
  localFuncLanesWire := localTxFunctionalLanesReg
  widthChange := localTxFunctionalLanesReg =/= localFuncLanesWire

  // TODO: SVA -- if widthChange goes high then it state is sREPAIRMB and s2 (applying degrade)
  io.txWidthChanged := widthChange  // goes HIGH in sREPAIRMB if localFuncLanesWire changes
  io.localFunctionalLanes := localTxFunctionalLanesReg
  io.usingPatternWriter := false.B

  switch(currentState) {
    is(MBInitState.sPARAM) {    
      // mb transmitters remain tristated, and mb receivers are permitted to be disabled (default)
      when(io.start) {
        // Reset status registers
        errorDetectedReg := false.B
        applyReversalMbTxReg := false.B
        localTxFunctionalLanesReg := "b011".U
        faultInLowerLanes := false.B
        faultInUpperLanes := false.B
        mbInitCalDoneReg := false.B

        sbMsgExchanger.io.req.valid := io.phyParams.localPhySettings.valid        
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_PARAM_CONFIGURATION_REQ, 
                                                  "PHY", "PHY", true, 
                                                  data = mbInitParamReqDataBits)
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_PARAM_CONFIGURATION_RESP
     
        io.phyParams.remotePhySettings.valid := sbMsgExchanger.io.resp.valid

        // io.phyParams.interoperableParamsFound is HIGH when resp params are what is expected
        requesterRdy := sbMsgExchanger.io.done && io.phyParams.interoperableParamsFound

        when(io.requesterRdy && io.responderRdy) {
          nextState := MBInitState.sCAL                    
        }
      }
    }
    is(MBInitState.sCAL) {      
      // mb transmitters remain tristated, and mb receivers are permitted to be disabled (default)    

      // TODO: This can just run the existing link operations, or create MBInitCal module
      //       which is left for PHY designers to implement

      sbMsgExchanger.io.req.valid := mbInitCalDoneReg
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_CAL_DONE_REQ, "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_CAL_DONE_RESP    

      requesterRdy := sbMsgExchanger.io.done

      when(io.requesterRdy && io.responderRdy) {
        nextState := MBInitState.sREPAIRCLK                    
      }
    }
    is(MBInitState.sREPAIRCLK) {     
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      io.mbLaneCtrlIo.rxTrackEn := true.B

      io.usingPatternWriter := true.B

      switch(substateReg) {
        is(MBInitSubstate.s0) { // INIT
          sbMsgExchanger.io.req.valid := true.B 
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRCLK_INIT_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_INIT_RESP
          
          // send 128 iterations of clock repair pattern (16 cycles clock followed by 8 cycles low)
          // on TCLKN_L, TCLKP_L, TTRK_L (not scrambled)
          io.patternWriterIo.req.valid := sbMsgExchanger.io.done && io.patternWriterIo.req.ready 
          io.patternWriterIo.req.bits.patternType := PatternSelect.CLKREPAIR

          when(io.patternWriterIo.resp.complete) {
            nextSubstate := MBInitSubstate.s1
          }
        }                      
        is(MBInitSubstate.s1) { // RESULT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRCLK_RESULT_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_RESULT_RESP

          errorDetectedWire := sbMsgExchanger.io.resp.valid && !repairClkSuccess

          when((sbMsgExchanger.io.resp.valid && repairClkSuccess) && !errorDetected) {
            nextSubstate := MBInitSubstate.s2
          }          
        }
        is(MBInitSubstate.s2) { // DONE
          sbMsgExchanger.io.req.valid := true.B  
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRCLK_DONE_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_DONE_RESP
          
          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sREPAIRVAL
            nextSubstate := MBInitSubstate.s0
          }
        }        
      }      
    }
    is(MBInitState.sREPAIRVAL) {
      // TODO:     
      //  -- center the clock phase at the center of data UI; info from the calibration
      //     Need to see how sCAL will be implemented

      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B) // data lanes held low in sREPAIRVAL
      io.mbLaneCtrlIo.txClkTriState := false.B // clock lanes held low in sREPAIRVAL 
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B

      io.usingPatternWriter := true.B

      switch(substateReg) {
        is(MBInitSubstate.s0) { // INIT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRVAL_INIT_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_INIT_RESP

          // valid transmitter is enabled when sending, tri-stated otherwise
          io.mbLaneCtrlIo.txValidTriState := false.B

          io.patternWriterIo.req.valid := sbMsgExchanger.io.done && io.patternWriterIo.req.ready 
          io.patternWriterIo.req.bits.patternType := PatternSelect.VALTRAIN

          when(io.patternWriterIo.resp.complete) {
            nextSubstate := MBInitSubstate.s1
          }
        }       
        is(MBInitSubstate.s1) { // RESULT
          sbMsgExchanger.io.req.valid := true.B 
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRVAL_RESULT_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_RESULT_RESP
            
          errorDetectedWire := sbMsgExchanger.io.resp.valid && !repairValSuccess

          when(sbMsgExchanger.io.resp.valid && repairValSuccess && !errorDetected) {
            nextSubstate := MBInitSubstate.s2
          }
        } 
        is(MBInitSubstate.s2) { // DONE
          sbMsgExchanger.io.req.valid := true.B  
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRVAL_DONE_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_DONE_RESP
          
          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sREVERSALMB
            nextSubstate := MBInitSubstate.s0
          }
        }        
      }
    }
    is(MBInitState.sREVERSALMB) {
      // TODO:     
      //  -- center the clock phase at the center of data UI; info from the calibration
      //     Need to see how sCAL will be implemented
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      io.usingPatternWriter := true.B

      switch(substateReg) {
        is(MBInitSubstate.s0) { // INIT
          sbMsgExchanger.io.req.valid := true.B 
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REVERSALMB_INIT_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_INIT_RESP

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBInitSubstate.s1
          }          
        }
        is(MBInitSubstate.s1) { // CLEAR ERROR
          sbMsgExchanger.io.req.valid := true.B 
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REVERSALMB_CLEAR_ERROR_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_CLEAR_ERROR_RESP

          io.patternWriterIo.req.valid := sbMsgExchanger.io.done && io.patternWriterIo.req.ready 
          io.patternWriterIo.req.bits.patternType := PatternSelect.PERLANEID

          when(io.patternWriterIo.resp.complete) {
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) { // RESULT
          sbMsgExchanger.io.req.valid := true.B 
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REVERSALMB_RESULT_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                          
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_RESULT_RESP

          // Error is triggered when it applies a lane reversal and it test still fails
          errorDetectedWire := sbMsgExchanger.io.resp.valid && 
                               !reversalMbSuccess &&
                               applyReversalMbTxReg 
                           
          when(sbMsgExchanger.io.resp.valid && reversalMbSuccess && !errorDetected) {
            nextSubstate := MBInitSubstate.s3
          }.elsewhen(sbMsgExchanger.io.resp.valid && !reversalMbSuccess && 
                     !applyReversalMbTxReg && !errorDetected) {
            nextSubstate := MBInitSubstate.s1
            applyReversalMbTxReg := true.B
          }
        }  
        is(MBInitSubstate.s3) { // DONE
          sbMsgExchanger.io.req.valid := true.B 
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REVERSALMB_DONE_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                          
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_DONE_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBInitState.sREPAIRMB
            nextSubstate := MBInitSubstate.s0
          }
        }        
      }     
    }
    is(MBInitState.sREPAIRMB) {
      // TODO:     
      //  -- center the clock phase at the center of data UI; info from the calibration
      //     Need to see how sCAL will be implemented
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      switch(substateReg) {
        is(MBInitSubstate.s0) {  // START
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRMB_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                          
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRMB_START_RESP
          
          when(sbMsgExchanger.io.done) {          
            nextSubstate := MBInitSubstate.s1
          }  
        }
        is(MBInitSubstate.s1) { // TX INIT D2C POINT TEST
          io.txPtTestReqInterfaceIo.start := true.B // Trigger the pt test

          // Using defaults signals for tx d2c point test (see above)
          // io.txPtTestReqInterfaceIo.linkTrainingParameters.<signal>
                  
          // faultInUpperLanes, and faultInLowerLanes registers will capture the results from the
          // point test when the following condition is satifised:
          // io.txPtTestReqInterfaceIo.start && io.txPtTestReqInterfaceIo.ptTestResults.valid

          when(io.txPtTestReqInterfaceIo.done) {
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) {  // APPLY DEGRADE
          // Calculate the width degrade          
          // If no degrade needed, keeps default value (driven by localTxFunctionalLanesReg)

          localFuncLanesWire := Mux1H(Seq(
            laneRepairDegradeCondSel(0) -> "b101".U, // Logical lanes 4-7 are functional
            laneRepairDegradeCondSel(1) -> "b100".U, // Logical lanes 0-3 are functional
            laneRepairDegradeCondSel(2) -> "b010".U, // Logical lanes 8-15 are functional
            laneRepairDegradeCondSel(3) -> "b001".U, // Logical lanes 0-7 are functional
            laneRepairDegradeCondSel(4) -> "b000".U, // No functional lanes (No degrade possible)
          ))
          
          // io.localFunctionalLanes should always have an updated value no matter what
          when(widthChange) {
            io.localFunctionalLanes := localFuncLanesWire
          }

          sbMsgExchanger.io.req.valid := true.B 
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRMB_APPLY_DEGRADE_REQ, 
                                                    "PHY", "PHY", true, 
                                                    msgInfo = Cat(0.U(12.W), localFuncLanesWire))

          // No need to wait for a response when all lanes have failed. Going into TrainError
          when(!allLanesFailed) {
            sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                    
            sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRMB_APPLY_DEGRADE_RESP
          }                                                    

          // Need message to send when all lanes failed before triggering an error
          when(sbMsgExchanger.io.msgSent && allLanesFailed) { 
            errorDetectedWire := true.B
          }          
          requesterRdy := sbMsgExchanger.io.done & !errorDetected

          // Need to wait for responder to change width and send the response to Remote die
          // before moving on. (i.e. If Local die receives a response that means, Remote
          // die has adjusted their TX and RX widths. However, if Local die Responder isn't ready
          // that means Local die hasn't receieved a request, so Local die is unable to adjust 
          // RX widths)
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
        is(MBInitSubstate.s3) {  // END
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRMB_END_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRMB_END_RESP

          requesterRdy := sbMsgExchanger.io.done
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

class MBInitResponder(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val start = Input(Bool())
    val requesterRdy = Input(Bool())
    val interpretBy8Lane = Input(Bool())
    val txWidthChanged = Input(Bool())

    // OUT
    val done = Output(Bool())
    val error = Output(Bool())
    val currentState = Output(MBInitState())
    val responderRdy = Output(Bool())
    val rxWidthChanged = Output(Bool()) 
    val remoteFunctionalLanes = Output(UInt(3.W))
    val usingPatternReader = Output(Bool())

    // Bundles with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)      
    val patternReaderIo = Flipped(new PatternReaderIO(afeParams.mbLanes))
    val txPtTestRespInterfaceIo = new TxInitPtTestResponderInterfaceIO()
    val phyParams = new MbInitParamsIO
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // Internal state registers
  val gotLFSRClearReq = RegInit(false.B) // HIGH if clear error message received

  // FSM state register
  val currentState = RegInit(MBInitState.sPARAM)
  val nextState = WireInit(currentState)  
  currentState := nextState
  io.currentState := currentState

  // Substate register
  val substateReg = RegInit(MBInitSubstate.s0)
  val nextSubstate = WireInit(substateReg)
  substateReg := nextSubstate  
  
  // Responder ready logic -- used by requester
  val responderRdyStatusReg = RegInit(false.B)
  val responderRdy = WireInit(false.B)
  when(currentState =/= nextState) {
    responderRdyStatusReg := false.B 
  }  
  when(responderRdy) {
    responderRdyStatusReg := true.B
  }
  io.responderRdy := responderRdyStatusReg || responderRdy

  // IO defaults
  val errorDetectedReg = RegInit(false.B)
  val errorDetectedWire = WireInit(false.B)
  val errorDetected = Wire(Bool())

  errorDetected := errorDetectedReg || errorDetectedWire
  when(errorDetectedWire) {
    errorDetectedReg := true.B
  }

  io.error := errorDetectedReg  
  io.done := false.B

  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState) || (substateReg =/= nextSubstate)
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  // need to wait for two diff. messages sREVERSALMB
  when((currentState === MBInitState.sREVERSALMB) && (substateReg === MBInitSubstate.s3)) { 
    io.sbLaneIo.rx.ready := false.B    
  }.otherwise {
    io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready 
  }

  // patternReaderIo defaults
  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits.patternType := PatternSelect.CLKREPAIR
  io.patternReaderIo.req.bits.comparisonMode := ComparisonMode.PERLANE
  io.patternReaderIo.req.bits.errorThreshold := 0.U
  io.patternReaderIo.req.bits.doConsecutiveCount := true.B // mbinit always consecutive counts
  io.patternReaderIo.req.bits.done := false.B
  io.patternReaderIo.req.bits.clear := false.B

  // txPtTestRespInterfaceIo Defaults (defaults are used in MBINIT.RepairMB state)
  io.txPtTestRespInterfaceIo.start := false.B
  io.txPtTestRespInterfaceIo.patternType := PatternSelect.PERLANEID

  // PhyParams from Remote die defaults (This is coming from the Remote die's request)
  // Parameter interopertability done in top level; top level registers the values
  io.phyParams.remotePhySettings.valid := false.B
  io.phyParams.remotePhySettings.bits.txAdjRuntime := sbMsgExchanger.io.resp.bits(79)
  io.phyParams.remotePhySettings.bits.sbFeatExt := sbMsgExchanger.io.resp.bits(78)
  io.phyParams.remotePhySettings.bits.ucieSx8 := sbMsgExchanger.io.resp.bits(77)
  io.phyParams.remotePhySettings.bits.moduleId := sbMsgExchanger.io.resp.bits(76, 75)
  io.phyParams.remotePhySettings.bits.clockPhase := sbMsgExchanger.io.resp.bits(74)
  io.phyParams.remotePhySettings.bits.clockMode := sbMsgExchanger.io.resp.bits(73)
  io.phyParams.remotePhySettings.bits.voltageSwing := sbMsgExchanger.io.resp.bits(72, 68)
  io.phyParams.remotePhySettings.bits.maxDataRate := sbMsgExchanger.io.resp.bits(67, 64)

  // MBINIT.RepairClk wires
  val mbRepairClkResult = Wire(UInt(16.W))
  mbRepairClkResult := Cat(0.U(12.W), 
                           0.U, 
                           io.patternReaderIo.resp.bits.perLaneStatusBits(2),
                           io.patternReaderIo.resp.bits.perLaneStatusBits(1),
                           io.patternReaderIo.resp.bits.perLaneStatusBits(0))

  // MBINIT.RepairVal wires
  val mbRepairValResult = Wire(UInt(16.W))
  mbRepairValResult := Cat(0.U(14.W), 
                           0.U, 
                           io.patternReaderIo.resp.bits.perLaneStatusBits(0))

  // MBINIT.ReversalMb wires
  val  mbReversalMbResult = Wire(UInt(64.W))
  mbReversalMbResult := Cat(0.U((64 - afeParams.mbLanes).W), 
                            io.patternReaderIo.resp.bits.perLaneStatusBits.asUInt)

  // MBINIT.RepairMB registers/wires
  val currRemoteTxFunctionalLanes = RegInit("b011".U(3.W)) // Default code all lanes are functional
  val newRemoteTxFunctionalLanes = RegInit("b011".U(3.W))
  val incRemoteFuncLanesWire = Wire(UInt(3.W)) 
  val widthChange = WireInit(false.B)
  
  incRemoteFuncLanesWire := sbMsgExchanger.io.resp.bits(42,40)

  widthChange := currRemoteTxFunctionalLanes =/= newRemoteTxFunctionalLanes
  when(widthChange) {
    io.remoteFunctionalLanes := newRemoteTxFunctionalLanes
  }.otherwise {
    io.remoteFunctionalLanes := currRemoteTxFunctionalLanes
  }
  io.rxWidthChanged := widthChange

  // MBInit.PARAM Configuration message bits
  val mbInitParamRespDataBits = Wire(UInt(64.W))
  // localPhySettings in this case are the negotiated params after comparison between local
  // settings and remote's settings in {MBINIT_PARAM_CONFIGURATION_REQ}
  // Data comes from the negotiated prefixed registers from the top level (MBInit module)
  mbInitParamRespDataBits := Cat(0.U(48.W),       // Reserved
                                 io.phyParams.localPhySettings.bits.txAdjRuntime,
                                 io.phyParams.localPhySettings.bits.sbFeatExt,
                                 0.U(3.W),
                                 io.phyParams.localPhySettings.bits.clockPhase,
                                 io.phyParams.localPhySettings.bits.clockMode,
                                 0.U(5.W),
                                 io.phyParams.localPhySettings.bits.maxDataRate)

  io.usingPatternReader := false.B

  switch(currentState) {
    is(MBInitState.sPARAM) {
      when(io.start) {
        currRemoteTxFunctionalLanes := "b011".U
        newRemoteTxFunctionalLanes := "b011".U
        errorDetectedReg := false.B
        gotLFSRClearReq := false.B

        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_PARAM_CONFIGURATION_REQ

        io.phyParams.remotePhySettings.valid := sbMsgExchanger.io.resp.valid
        
        // Valid goes HIGH when interoperable params are found in the top level after receiving req
        sbMsgExchanger.io.req.valid :=  io.phyParams.localPhySettings.valid
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_PARAM_CONFIGURATION_RESP, 
                                                  "PHY", "PHY", true, 
                                                  data = mbInitParamRespDataBits)

        // Top level will trigger an error if interoperable parameters are not found
        // Message won't send unless interoperable parameters are found.

        responderRdy := sbMsgExchanger.io.done && io.phyParams.interoperableParamsFound

        when(io.requesterRdy && io.responderRdy) {
          nextState := MBInitState.sCAL                    
        }
      }
    }
    is(MBInitState.sCAL) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_CAL_DONE_REQ

      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_CAL_DONE_RESP, "PHY", "PHY", true)

      responderRdy := sbMsgExchanger.io.done
      when(io.requesterRdy && io.responderRdy) {
        nextState := MBInitState.sREPAIRCLK                    
      }
    }
    is(MBInitState.sREPAIRCLK) {
      io.usingPatternReader := true.B
      switch(substateReg) {
        is(MBInitSubstate.s0) {  // INIT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_INIT_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRCLK_INIT_RESP, 
                                                    "PHY", "PHY", true)
          
          io.patternReaderIo.req.bits.patternType := PatternSelect.CLKREPAIR

          when(sbMsgExchanger.io.done && io.patternReaderIo.req.ready) {
            io.patternReaderIo.req.valid := true.B
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) {   // RESULT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_RESULT_REQ

          io.patternReaderIo.req.bits.done := sbMsgExchanger.io.msgReceived

          sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRCLK_RESULT_RESP, 
                                                    "PHY", "PHY", true, 
                                                    msgInfo = mbRepairClkResult)

          when(sbMsgExchanger.io.done) {
            io.patternReaderIo.req.bits.clear := true.B
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) {   // DONE
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRCLK_DONE_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRCLK_DONE_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
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
        is(MBInitSubstate.s0) {  // INIT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_INIT_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRVAL_INIT_RESP, 
                                                    "PHY", "PHY", true)

          io.patternReaderIo.req.bits.patternType := PatternSelect.VALTRAIN

          when(sbMsgExchanger.io.done && io.patternReaderIo.req.ready) {
            io.patternReaderIo.req.valid := true.B
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) {   // RESULT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_RESULT_REQ

          io.patternReaderIo.req.bits.done := sbMsgExchanger.io.msgReceived

          sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRVAL_RESULT_RESP, 
                                                    "PHY", "PHY", true,
                                                    msgInfo = mbRepairValResult)
          when(sbMsgExchanger.io.done) {
            io.patternReaderIo.req.bits.clear := true.B
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) {   // DONE
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REPAIRVAL_DONE_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRVAL_DONE_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
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
        is(MBInitSubstate.s0) {  // INIT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_INIT_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REVERSALMB_INIT_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) {   // CLEAR ERROR
          when(!gotLFSRClearReq) {
            sbMsgExchanger.io.rxRefBitPattern.valid := true.B
            sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_CLEAR_ERROR_REQ
          }        

          assert(io.patternReaderIo.req.ready === true.B, "PatternReader should be ready here")
          io.patternReaderIo.req.valid := sbMsgExchanger.io.resp.valid
          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REVERSALMB_CLEAR_ERROR_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done && io.patternReaderIo.req.ready) {
            gotLFSRClearReq := false.B
            nextSubstate := MBInitSubstate.s2
          }
        }
        is(MBInitSubstate.s2) {   // RESULT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBINIT_REVERSALMB_RESULT_REQ

          // stop the pattern reader
          io.patternReaderIo.req.bits.done := sbMsgExchanger.io.msgReceived

          sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REVERSALMB_RESULT_RESP, 
                                                    "PHY", "PHY", true,
                                                    data = mbReversalMbResult)

          when(sbMsgExchanger.io.done) {
            io.patternReaderIo.req.bits.clear := true.B
            nextSubstate := MBInitSubstate.s3
          }                                                    
        }
        is(MBInitSubstate.s3) { // Intermediate state
          when(io.sbLaneIo.rx.valid) {
            when(SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.MBINIT_REVERSALMB_CLEAR_ERROR_REQ)) {
              io.sbLaneIo.rx.ready := true.B
              gotLFSRClearReq := true.B
              nextSubstate := MBInitSubstate.s1
            }.elsewhen(SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.MBINIT_REVERSALMB_DONE_REQ)) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBInitSubstate.s4
            }
          }
        }
        is(MBInitSubstate.s4) {  // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REVERSALMB_DONE_RESP,
                                                    "PHY", "PHY", true)
          when(sbMsgExchanger.io.msgSent) {
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
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRMB_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBInitSubstate.s1
          }
        }
        is(MBInitSubstate.s1) { // Start RX side of pt test
          io.txPtTestRespInterfaceIo.start := true.B  // Trigger the responder for the tx pt test

          // Waits for PERLANEID by default (see above)

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

          // Width change will be happen within a cycle. The top level register will update
          // and the lanes are tristated/disabled appropriately in the top level LTSM.
          // Substates drive tristate/disable signals according to spec. However, 
          // repair/masking will happen in the top level LTSM module -- centralizes the logic.
          // So, sending the {apply degrade resp} without an ack from LTSM 
          // that the width changes has happend is safe.
          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived && !errorDetected
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRMB_APPLY_DEGRADE_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done

          when(io.requesterRdy && io.responderRdy) {
            currRemoteTxFunctionalLanes := newRemoteTxFunctionalLanes
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
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBINIT_REPAIRMB_END_RESP, 
                                                    "PHY", "PHY", true)
          responderRdy := sbMsgExchanger.io.done
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

object MainMBInitSM extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBInitSM(new AfeParams(), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}

object MainMBInitRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBInitRequester(new AfeParams(), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}

object MainMBInitResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBInitResponder(new AfeParams(), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}