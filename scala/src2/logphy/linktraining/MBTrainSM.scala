package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/* 
  Description: Contains the MBTrain state machine top, requester, and responder module.
  The top module instantiates both the requester and responder.

  NOTE: 
    * There are remaining TODOs in this file.  
    * Multi-module (MMPL) isn't currently implemented
*/

// ============================================================================
// Bundles & Objects
// ============================================================================
class VrefCtrlIO(numMbLanes: Int) extends Bundle {
  val min = Input(UInt(5.W))
  val max = Input(UInt(5.W))
  val stepSize = Input(UInt(5.W))
  val validVrefSel = Valid(UInt(5.W))
  val clkNVrefSel = Valid(UInt(5.W))  // TODO: Not sure if we need this
  val clkPVrefSel = Valid(UInt(5.W))  // TODO: Not sure if we need this
  val dataVrefSel = Vec(numMbLanes, Valid(UInt(5.W)))
}

/*
  LinkOpSequenceIO
  Description: As per the spec, link operations in some substates of MBTrain can be 
  done an implementation specific amount of times.  
  These IOs will get input from MMIO registers. Software will be able to control
  the number of times each test is performed, and the ordering.

  IOs:
  * opSequence: bitmap of the ordering of the test sequence. Tests will execute at the LSB
      - 1 == Point Test
      - 0 == Eye Width Sweep
  * numOps: total number of bits to consider from the sequence.

  Note: Verifying number of operations (numOperations) is appropriate for the order of
  sequences (opSequence) is left to the software. Hardware doesn't need to check,
  because, if the number of operations is too large, link training will timeout.
  Also, software should prevent changes to the registers once training has started.

  Ex: 
  numOps = 5
  opSequence = 0000....0010_1000
    State machine will execute 3 eye width sweeps, 1 pt test, then an eye width sweep and stop.
*/
class LinkOpSequenceIO(numLinkOps: Int) extends Bundle {
  val opSequence = Input(UInt(numLinkOps.W))
  val numOps = Input(UInt((log2Ceil(numLinkOps) + 1).W))  
}

object MBTrainState extends ChiselEnum {
  val sVALVREF, sDATAVREF, sSPEEDIDLE, sTXSELFCAL, sRXCLKCAL, sVALTRAINCENTER,
  sVALTRAINVREF, sDATATRAINCENTER1, sDATATRAINVREF, sRXDESKEW, sDATATRAINCENTER2,
  sLINKSPEED, sREPAIR, sTOPHYRETRAIN, sTOLINKINIT = Value
}

// Used by MBTrain.L1 and MBTrain.PHYRETRAIN to jump to appropriate state
object MBTrainGoToState extends ChiselEnum {
  val goToSPEEDIDLE, goToTXSELFCAL, goToREPAIR = Value
}
object MBTrainSubstate extends ChiselEnum {
  val s0, s1, s2, s3, s4, s5, s6, s7, s8, s9, s10 = Value
}

// ============================================================================
// Modules
// ============================================================================
class MBTrainSM(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle{
    // IN
    val goToState = Flipped(Valid(MBTrainGoToState()))
    val negotiatedMaxDataRate = Input(SpeedMode())    
    val pllLock = Input(Bool()) 
    val mbTrainTxSelfCalDone = Input(Bool())
    val mbTrainRxClkCalDone = Input(Bool())
    val phyInRetrain = Input(Bool())
    val interpretBy8Lane = Input(Bool())
    val maxErrorThresholdPerLane = Input(UInt(16.W))
    val changeInRuntimeLinkCtrlRegs = Input(Bool())
    val currLocalTxFunctionalLanes = Input(UInt(3.W))
    val currRemoteTxFunctionalLanes = Input(UInt(3.W))

    // OUT
    val currentState = Output(MBTrainState())
    val mbLaneCtrlIo = new MainbandLaneCtrlIO(afeParams)
    val freqSel = Valid(SpeedMode())
    val mbTrainTxSelfCalStart = Output(Bool())
    val mbTrainRxClkCalStart = Output(Bool())
    val doElectricalIdleTx = Output(Bool())
    val clearPhyInRetrainFlag = Output(Bool())
    val txWidthChanged = Output(Bool())
    val newLocalFunctionalLanes = Output(UInt(3.W))
    val rxClkCalSendFwClkPattern = Output(Bool())
    val rxClkCalSendTrkPattern = Output(Bool())
    val newRemoteFunctionalLanes = Output(UInt(3.W))
    val rxWidthChanged = Output(Bool())
    val doElectricalIdleRx = Output(Bool())

    // Bundles with IN & OUT IOs
    val fsmCtrl = new SubFsmControlIO()
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
    val txPtTestReqIntfIo = new TxInitPtTestRequesterInterfaceIO(afeParams)
    val txEyeSweepReqIntfIo = new TxInitEyeWidthSweepRequesterInterfaceIO(afeParams)
    val rxPtTestReqIntfIo = new RxInitPtTestRequesterInterfaceIO(afeParams)
    val rxEyeSweepReqIntfIo = new RxInitEyeWidthSweepRequesterInterfaceIO(afeParams)
    val txPtTestRespIntfIo = new TxInitPtTestResponderInterfaceIO()
    val txEyeSweepRespIntfIo = new TxInitEyeWidthSweepResponderInterfaceIO()
    val rxPtTestRespIntfIo = new RxInitPtTestResponderInterfaceIO()
    val rxEyeSweepRespIntfIo = new RxInitEyeWidthSweepResponderInterfaceIO()
  })

  val requester = Module(new MBTrainRequester(afeParams, sbParams))
  val responder = Module(new MBTrainResponder(afeParams, sbParams))

  // Responder IN
  requester.io.start := io.fsmCtrl.start
  requester.io.responderRdy := responder.io.responderRdy
  requester.io.goToState := io.goToState
  requester.io.maxErrorThresholdPerLane := io.maxErrorThresholdPerLane
  requester.io.negotiatedMaxDataRate := io.negotiatedMaxDataRate
  requester.io.pllLock := io.pllLock
  requester.io.mbTrainTxSelfCalDone := io.mbTrainTxSelfCalDone
  requester.io.mbTrainRxClkCalDone := io.mbTrainRxClkCalDone
  requester.io.interpretBy8Lane := io.interpretBy8Lane
  requester.io.phyInRetrain := io.phyInRetrain
  requester.io.changeInRuntimeLinkCtrlRegs := io.changeInRuntimeLinkCtrlRegs
  requester.io.currLocalTxFunctionalLanes := io.currLocalTxFunctionalLanes
  requester.io.remoteExitingToPhyretrain := responder.io.remoteExitingToPhyretrain
  requester.io.remoteExitingToSpeedDegrade := responder.io.remoteExitingToSpeedDegrade
  requester.io.remoteExitingToRepair := responder.io.remoteExitingToRepair
  requester.io.remoteErrorInLinkspeed := responder.io.remoteErrorInLinkspeed
  requester.io.sbLaneIo <> io.requesterSbLaneIo
  requester.io.txPtTestReqIntfIo <> io.txPtTestReqIntfIo
  requester.io.txEyeSweepReqIntfIo <> io.txEyeSweepReqIntfIo
  requester.io.rxPtTestReqIntfIo <> io.rxPtTestReqIntfIo
  requester.io.rxEyeSweepReqIntfIo <> io.rxEyeSweepReqIntfIo
  requester.io.vrefCtrlIo := DontCare       // TODO: Fix once training sequencing is decided
  requester.io.valVrefLinkOpSeq := DontCare // TODO: Fix once training sequencing is decided

  // Requester IN
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

  // OUT
  // TODO: Add SVA to make sure currentState of responder and requester are the same
  io.fsmCtrl.done := requester.io.done && responder.io.done
  io.fsmCtrl.error := requester.io.error | responder.io.error
  io.fsmCtrl.substateTransitioning := requester.io.transitioningState
  io.currentState := requester.io.currentState
  io.mbLaneCtrlIo := requester.io.mbLaneCtrlIo
  io.freqSel := requester.io.freqSel
  io.mbTrainTxSelfCalStart := requester.io.mbTrainTxSelfCalStart
  io.mbTrainRxClkCalStart := requester.io.mbTrainRxClkCalStart
  io.doElectricalIdleTx := requester.io.doElectricalIdleTx
  io.clearPhyInRetrainFlag := requester.io.clearPhyInRetrainFlag
  io.txWidthChanged := requester.io.txWidthChanged
  io.newLocalFunctionalLanes := requester.io.newLocalFunctionalLanes
  io.rxClkCalSendFwClkPattern := responder.io.rxClkCalSendFwClkPattern
  io.rxClkCalSendTrkPattern := responder.io.rxClkCalSendTrkPattern
  io.newRemoteFunctionalLanes := responder.io.newRemoteFunctionalLanes
  io.rxWidthChanged := responder.io.rxWidthChanged
  io.doElectricalIdleRx := responder.io.doElectricalIdleRx
}

class MBTrainRequester(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle{
    // IN
    val start = Input(Bool())
    val responderRdy = Input(Bool())
    val goToState = Flipped(Valid(MBTrainGoToState()))
    val maxErrorThresholdPerLane = Input(UInt(16.W))
    val negotiatedMaxDataRate = Input(SpeedMode())
    val pllLock = Input(Bool())
    val mbTrainTxSelfCalDone = Input(Bool())
    val mbTrainRxClkCalDone = Input(Bool())
    val interpretBy8Lane = Input(Bool())
    val phyInRetrain = Input(Bool())
    val changeInRuntimeLinkCtrlRegs = Input(Bool())
    val currLocalTxFunctionalLanes = Input(UInt(3.W))
    val remoteExitingToPhyretrain = Input(Bool())
    val remoteExitingToSpeedDegrade = Input(Bool())
    val remoteExitingToRepair = Input(Bool()) 
    val remoteErrorInLinkspeed = Input(Bool())
    
    // OUT
    val done = Output(Bool())
    val error = Output(Bool())
    val requesterRdy = Output(Bool())
    val currentState = Output(MBTrainState())
    val transitioningState = Output(Bool())    
    val freqSel = Valid(SpeedMode())  // Valid goes high when to update speed setting
    val mbTrainTxSelfCalStart = Output(Bool())
    val mbTrainRxClkCalStart = Output(Bool())
    val doElectricalIdleTx = Output(Bool())
    val completedLinkspeedStep1And2 = Output(Bool())
    val initiatingError = Output(Bool())
    val initiatingExitToPhyretrain = Output(Bool())
    val clearPhyInRetrainFlag = Output(Bool())
    val initiatingSpeedDegrade = Output(Bool())
    val initiatingWidthDegrade = Output(Bool())
    val initiatingDone = Output(Bool())
    val txWidthChanged = Output(Bool())
    val newLocalFunctionalLanes = Output(UInt(3.W))
    val mbLaneCtrlIo = new MainbandLaneCtrlIO(afeParams)

    // Bundles with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val txPtTestReqIntfIo = new TxInitPtTestRequesterInterfaceIO(afeParams)
    val txEyeSweepReqIntfIo = new TxInitEyeWidthSweepRequesterInterfaceIO(afeParams)
    val rxPtTestReqIntfIo = new RxInitPtTestRequesterInterfaceIO(afeParams)
    val rxEyeSweepReqIntfIo = new RxInitEyeWidthSweepRequesterInterfaceIO(afeParams)    
    
    // TODO: Probably a better way to do dynamic training
    val vrefCtrlIo = new VrefCtrlIO(afeParams.mbLanes)
    val valVrefLinkOpSeq = new LinkOpSequenceIO(afeParams.numLinkOps)
  })

  // Helper Modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // FSM state register
  val currentState = RegInit(MBTrainState.sVALVREF)
  val nextState = WireInit(currentState)  
  currentState := nextState
  io.currentState := currentState
  io.transitioningState := currentState =/= nextState

  // Substate register
  val substateReg = RegInit(MBTrainSubstate.s0)
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

  // Error detection status bits
  val errorDetectedReg = RegInit(false.B)
  val errorDetectedWire = WireInit(false.B)
  val errorDetected = Wire(Bool())

  errorDetected := errorDetectedReg || errorDetectedWire
  when(errorDetectedWire) {
    errorDetectedReg := true.B
  }
  io.error := errorDetectedReg 

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

  // Link operation defaults
  // NOTE: Currently link ops doesn't use many of these parameters,
  // since pattern counts are fixed for normal link training operation. 
  // Setting them according to spec for the data bits when sending message to Remote die.
  // TX Pt test
  io.txPtTestReqIntfIo.start := false.B
  io.txPtTestReqIntfIo.patternType := PatternSelect.VALTRAIN
  io.txPtTestReqIntfIo.linkTrainingParameters.clockPhase := 0.U        // center clock pi   
  io.txPtTestReqIntfIo.linkTrainingParameters.dataPattern := 0.U       // LFSR
  io.txPtTestReqIntfIo.linkTrainingParameters.validPattern := 0.U      // valtrain
  io.txPtTestReqIntfIo.linkTrainingParameters.patternMode := 0.U       // continuous
  io.txPtTestReqIntfIo.linkTrainingParameters.iterationCount := 1.U    // num of bursts
  io.txPtTestReqIntfIo.linkTrainingParameters.idleCount := 0.U         // UI to wait
  io.txPtTestReqIntfIo.linkTrainingParameters.burstCount := 4096.U     // UI to send per burst
  io.txPtTestReqIntfIo.linkTrainingParameters.maxErrorThreshold := io.maxErrorThresholdPerLane 
  io.txPtTestReqIntfIo.linkTrainingParameters.comparisonMode := 0.U    // per lane

  // TX Eye Width Sweep
  io.txEyeSweepReqIntfIo.start := false.B
  io.txEyeSweepReqIntfIo.patternType := PatternSelect.VALTRAIN
  io.txEyeSweepReqIntfIo.linkTrainingParameters.clockPhase := 0.U        // center clock pi   
  io.txEyeSweepReqIntfIo.linkTrainingParameters.dataPattern := 1.U       // per lane id
  io.txEyeSweepReqIntfIo.linkTrainingParameters.validPattern := 0.U      // valtrain
  io.txEyeSweepReqIntfIo.linkTrainingParameters.patternMode := 0.U       // continuous
  io.txEyeSweepReqIntfIo.linkTrainingParameters.iterationCount := 1.U    // num of bursts
  io.txEyeSweepReqIntfIo.linkTrainingParameters.idleCount := 0.U         // UI to wait
  io.txEyeSweepReqIntfIo.linkTrainingParameters.burstCount := 2048.U     // UI to send per burst
  io.txEyeSweepReqIntfIo.linkTrainingParameters.maxErrorThreshold := io.maxErrorThresholdPerLane
  io.txEyeSweepReqIntfIo.linkTrainingParameters.comparisonMode := 0.U    // per lane

  // RX Pt Test
  io.rxPtTestReqIntfIo.start := false.B
  io.rxPtTestReqIntfIo.patternType := PatternSelect.VALTRAIN
  io.rxPtTestReqIntfIo.linkTrainingParameters.clockPhase := 0.U        // center clock pi   
  io.rxPtTestReqIntfIo.linkTrainingParameters.dataPattern := 1.U       // per lane id
  io.rxPtTestReqIntfIo.linkTrainingParameters.validPattern := 0.U      // valtrain
  io.rxPtTestReqIntfIo.linkTrainingParameters.patternMode := 0.U       // continuous
  io.rxPtTestReqIntfIo.linkTrainingParameters.iterationCount := 1.U    // num of bursts
  io.rxPtTestReqIntfIo.linkTrainingParameters.idleCount := 0.U         // UI to wait
  io.rxPtTestReqIntfIo.linkTrainingParameters.burstCount := 2048.U     // UI to send per burst
  io.rxPtTestReqIntfIo.linkTrainingParameters.maxErrorThreshold := io.maxErrorThresholdPerLane
  io.rxPtTestReqIntfIo.linkTrainingParameters.comparisonMode := 0.U    // per lane

  // RX Eye Width Sweep
  io.rxEyeSweepReqIntfIo.start := false.B
  io.rxEyeSweepReqIntfIo.patternType := PatternSelect.VALTRAIN
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.clockPhase := 0.U        // center clock pi   
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.dataPattern := 1.U       // per lane id
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.validPattern := 0.U      // valtrain
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.patternMode := 0.U       // continuous
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.iterationCount := 1.U    // num of bursts
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.idleCount := 0.U         // UI to wait
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.burstCount := 2048.U     // UI to send per burst
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.maxErrorThreshold := io.maxErrorThresholdPerLane 
  io.rxEyeSweepReqIntfIo.linkTrainingParameters.comparisonMode := 0.U    // per lane



  // WIP ==========================================================================================
  // TODO: Fix the reference voltage selection once training logic is determined  
  // Reference voltage registers/wires  
  // VrefCtrlIo Defaults
  io.vrefCtrlIo.validVrefSel.valid := false.B
  io.vrefCtrlIo.validVrefSel.bits := io.vrefCtrlIo.min
  io.vrefCtrlIo.clkNVrefSel.valid := false.B
  io.vrefCtrlIo.clkNVrefSel.bits := io.vrefCtrlIo.min
  io.vrefCtrlIo.clkPVrefSel.valid := false.B
  io.vrefCtrlIo.clkPVrefSel.bits := io.vrefCtrlIo.min
  for(i <- 0 until afeParams.mbLanes) {
    io.vrefCtrlIo.dataVrefSel(i).valid := false.B
    io.vrefCtrlIo.dataVrefSel(i).bits := io.vrefCtrlIo.min
  }

  // Reference voltage registers
  // Note: These registers will hold the latest best result from training
  val vrefValid = RegInit(0.U(5.W))
  val vrefClkN = RegInit(0.U(5.W))
  val vrefClkP = RegInit(0.U(5.W))
  val vrefData = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(5.W))))

  // Vref incrementation logic
  val currVrefSel = RegInit(0.U(5.W))   //  Will be used a working select
  // TODO: Add some control logic to increment but stay within the max.
  //       from FSM we just trigger an enable on the counter and valid of the vref
  //       Need to see if this is the best way

  // Training operation registers/wires
  val numLinkOpsMax = RegInit(0.U((log2Ceil(afeParams.numLinkOps) + 1).W))
  val numLinkOpsCounter = RegInit(0.U((log2Ceil(afeParams.numLinkOps) + 1).W))
  val opSequence = RegInit(0.U(afeParams.numLinkOps.W))
  val seqIncrement = WireInit(false.B) // Increments on the next cycle
  val seqDone = Wire(Bool())

  val linkOpsResults = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))
  val linkOpsResultsValid = RegInit(false.B)


  seqDone := numLinkOpsCounter === numLinkOpsMax
  when(seqIncrement) { // Trigger increment when an link operation is done
    numLinkOpsCounter := numLinkOpsCounter + 1.U
    opSequence := opSequence >> 1
  }

  // TODO: Need to also add logic for the clock phase
  // WIP ==========================================================================================

  // MBTrain.SPEEDIDLE registers/wires
  val fromDataVref = RegInit(false.B)
  val fromLinkspeed = RegInit(false.B)
  val currFreqSel = RegInit(SpeedMode.speed4)
  val speedChanged = RegInit(false.B)
  val isCurrFreqGreaterThan32 = Wire(Bool())
  val prevState = RegInit(0.U(2.W))   // 1.U == L1; 2.U == PHYRETRAIN

  isCurrFreqGreaterThan32 := (currFreqSel === SpeedMode.speed48) || 
                             (currFreqSel === SpeedMode.speed64)

  // MBTrain.TXSELFCAL registers/wires
  val mbTrainTxSelfCalDoneReg = RegInit(false.B) 
  when(io.mbTrainTxSelfCalDone) {
    mbTrainTxSelfCalDoneReg := true.B
  }
  io.mbTrainTxSelfCalStart := false.B

  when(io.freqSel.valid) {
    // TODO: This adds a cycle of latency for the speed change to happen. Not sure if needed
    // depends on how the pllLock signal reacts to the speed change.
    speedChanged := true.B
    currFreqSel := io.freqSel.bits
  }

  io.freqSel.valid := false.B
  io.freqSel.bits := SpeedMode.speed4

  // MBTrain.RXCLKCAL registers/wires
  val mbTrainRxClkCalDoneReg = RegInit(false.B)
  when(io.mbTrainRxClkCalDone) {
    mbTrainRxClkCalDoneReg := true.B
  }
  io.mbTrainRxClkCalStart := false.B

  // MBTrain.LINKSPEED registers/wires
  val clearRuntimeLinkCtrlBusyBit = WireInit(false.B)
  val clearPhyInRetrainFlag = WireInit(false.B)
  val initiatingExitToPhyretrainFlag = RegInit(false.B)
  val completedLinkspeedStep1And2Flag = RegInit(false.B)
  val initiatingErrorFlag = RegInit(false.B)
  val initiatingSpeedDegradeFlag = RegInit(false.B)
  val initiatingWidthDegradeFlag = RegInit(false.B)
  val initiatingDoneFlag = RegInit(false.B)

  // Note: Detection and assessment for lane repair is done in LINKSPEED. 
  // REPAIR state just sends the message with appropriate functional lane code
  val currLocalTxFunctionalLanes = Wire(UInt(3.W))  // Result found in MBInit/MBTrain.REPAIR
  val newTxFunctionalLanes = Wire(UInt(3.W))        // Result from pt test in MBTrain.LINKSPEED
  val laneHasErrors = Wire(Bool())
  val faultInLowerLanes = Wire(Bool())
  val faultInUpperLanes = Wire(Bool())
  val isWidthDegradePossible = Wire(Bool())
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
  
  currLocalTxFunctionalLanes := io.currLocalTxFunctionalLanes

  // Output of faultInLowerLanes and faultInUpperLanes is trusted when linkOpsResultsValid is HIGH
  when(io.interpretBy8Lane) {
    faultInLowerLanes := Cat(linkOpsResults(0),
                             linkOpsResults(1),
                             linkOpsResults(2),
                             linkOpsResults(3)).orR
    faultInUpperLanes := Cat(linkOpsResults(4),
                             linkOpsResults(5),
                             linkOpsResults(6),
                             linkOpsResults(7)).orR
  }.otherwise {
    // Get top (afeParams.mbLanes / 2) lanes
    faultInLowerLanes := linkOpsResults.take(afeParams.mbLanes / 2).map(_.asBool).reduce(_ || _)
    // Get bottom (afeParams.mbLanes / 2) lanes                                                    
    faultInUpperLanes := linkOpsResults.drop(afeParams.mbLanes / 2).map(_.asBool).reduce(_ || _)
  }
  
  isLanes0To15 := currLocalTxFunctionalLanes === "b011".U
  isLanes0To7 := isLanes0To15 && io.interpretBy8Lane
  isLowerLanes := (currLocalTxFunctionalLanes === "b001".U) ||
                  (currLocalTxFunctionalLanes === "b100".U)
  isUpperLanes := (currLocalTxFunctionalLanes === "b010".U) || 
                  (currLocalTxFunctionalLanes === "b101".U)
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

  newTxFunctionalLanes := "b011".U // Default code all lanes are functional
  newTxFunctionalLanes := Mux1H(Seq(
    laneRepairDegradeCondSel(0) -> "b101".U, // Logical lanes 4-7 are functional
    laneRepairDegradeCondSel(1) -> "b100".U, // Logical lanes 0-3 are functional
    laneRepairDegradeCondSel(2) -> "b010".U, // Logical lanes 8-15 are functional
    laneRepairDegradeCondSel(3) -> "b001".U, // Logical lanes 0-7 are functional
    laneRepairDegradeCondSel(4) -> "b000".U, // No functional lanes (No degrade possible)
  ))

  // There are no lane errors if the lane functionality test in sLINKSPEED matches the functional
  // lanes determined in from the REPAIR tests
  laneHasErrors := linkOpsResultsValid && (newTxFunctionalLanes =/= currLocalTxFunctionalLanes)
  isWidthDegradePossible :=  (newTxFunctionalLanes =/= "b000".U)

  io.initiatingError := initiatingErrorFlag
  io.clearPhyInRetrainFlag := clearPhyInRetrainFlag
  io.initiatingExitToPhyretrain := initiatingExitToPhyretrainFlag
  io.completedLinkspeedStep1And2 := completedLinkspeedStep1And2Flag
  io.initiatingSpeedDegrade := initiatingSpeedDegradeFlag
  io.initiatingWidthDegrade := initiatingWidthDegradeFlag
  io.initiatingDone := initiatingDoneFlag
  io.doElectricalIdleTx := false.B 
 
  // MBTrain.REPAIR registers/wires
  // Only set high once the width by MBTrain.REPAIR is determined, or in-progress with repair
  val applyDegrade = WireInit(false.B) 
  io.txWidthChanged := laneHasErrors && applyDegrade
  io.newLocalFunctionalLanes := newTxFunctionalLanes
       
  // IOs
  io.done := false.B

  // State Machine
  switch(currentState) {
    is(MBTrainState.sVALVREF) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      // TODO: Set forwarded clock phase at the center of the data UI on MB TX

      switch(substateReg) {
        is(MBTrainSubstate.s0) { // START
          when(io.start) {
            // Reset appropriate state registers at the start of a new training cycle
            errorDetectedReg := false.B
            fromDataVref := false.B
            fromLinkspeed := false.B
            speedChanged := false.B
            mbTrainTxSelfCalDoneReg := false.B
            mbTrainRxClkCalDoneReg := false.B
            prevState := 0.U
            currFreqSel := SpeedMode.speed4
            vrefValid := io.vrefCtrlIo.min
            vrefClkN := io.vrefCtrlIo.min
            vrefClkP := io.vrefCtrlIo.min
            vrefData.foreach(_ := io.vrefCtrlIo.min)

            sbMsgExchanger.io.req.valid := true.B        
            sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALVREF_START_REQ, 
                                                      "PHY", "PHY", true)
            sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                                 
            sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALVREF_START_RESP

            when(sbMsgExchanger.io.done) {
              nextSubstate := MBTrainSubstate.s1
            }
          }
        }
        is(MBTrainSubstate.s1) {  // START RX-INITIATED LINK OPERATION
          // TODO: Figure out a better way to do operations
          // - Question: How to sequence the training?
          //    * Note: Know a center clock phase here, so just run pt test at each vref
          //            And the passing window and center the vref 
          //    * Fixed sequence
          //    * Read some memory that gets initializied prior to training by SW
          // - Start a pt test or a eye sweep
          // - Remember to assign appropriate values for the link ops modules
          nextSubstate := MBTrainSubstate.s2
        }
        is(MBTrainSubstate.s2) {  // WAIT FOR LINK OP RESULT
          // TODO:
          // Wait for result and register, do any calculation, either go to s3 and move on,
          // or go to s1 to start a new link operation with new settings
          // Reminder to trigger an error if no satifactory param is found
          nextSubstate := MBTrainSubstate.s3
        }
        is(MBTrainSubstate.s3) {  // END
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALVREF_END_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALVREF_END_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATAVREF
            nextSubstate := MBTrainSubstate.s0            
          }
        }
      }
    }
    is(MBTrainState.sDATAVREF) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      fromDataVref := true.B  // Used in sSPEEDIDLE

      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATAVREF_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATAVREF_START_RESP

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) {  // START RX-INITIATED LINK OPERATION
          // TODO: Figure out a better way to do operations
          // - Start a pt test or a eye sweep
          // - Remember to assign appropriate values for the link ops modules
          nextSubstate := MBTrainSubstate.s2
        }
        is(MBTrainSubstate.s2) {  // WAIT FOR LINK OP RESULT
          // TODO:
          // Wait for result and register, do any calculation, either go to s3 and move on,
          // or go to s1 to start a new link operation with new settings
          // Reminder to trigger an error if no satifactory param is found
          nextSubstate := MBTrainSubstate.s3
        }
        is(MBTrainSubstate.s3) {  // END
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATAVREF_END_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                                 
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATAVREF_END_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sSPEEDIDLE
            nextSubstate := MBTrainSubstate.s0            
          }
        }
      }
    }
    is(MBTrainState.sSPEEDIDLE) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable
      
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // Set speed
          when(fromDataVref) {
            io.freqSel.bits := io.negotiatedMaxDataRate
            io.freqSel.valid := true.B
          }.elsewhen(prevState === 1.U) { // from L1
            io.freqSel.bits := currFreqSel
            io.freqSel.valid := true.B
          }.elsewhen(((prevState === 2.U) || fromLinkspeed) && (currFreqSel =/= SpeedMode.speed4)) { 
            // from PHYRETRAIN or MBTrain.Linkspeed
            io.freqSel.bits := (currFreqSel.asUInt - 1.U).asTypeOf(SpeedMode())
            io.freqSel.valid := true.B
          }.otherwise {
            errorDetectedWire := true.B
          }

          when(!errorDetected && speedChanged) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) { 
          // TODO: Currently assuming pllLock will go LOW within a cycle of the a speed change.
          when(io.pllLock) {
            nextSubstate := MBTrainSubstate.s2
          }
        }
        is(MBTrainSubstate.s2) {  // DONE
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_SPEEDIDLE_DONE_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_SPEEDIDLE_DONE_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sTXSELFCAL
            nextSubstate := MBTrainSubstate.s0

            // Reset prev state registers
            prevState := 0.U
            fromDataVref := false.B
            fromLinkspeed := false.B
            speedChanged := false.B
          }
        }
      }
    } 
    is(MBTrainState.sTXSELFCAL) {
      // MB transmitters remain tristated and MB receivers are permitted to be disabled (default)    

      // TODO: This can just run the existing link operations, or create MBTrainTxSelfCal module
      //       which is left for PHY designers to implement
      //       Can be used to fine tune with SW
      io.mbTrainTxSelfCalStart := true.B
      sbMsgExchanger.io.req.valid := mbTrainTxSelfCalDoneReg
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_TXSELFCAL_DONE_REQ, "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_TXSELFCAL_DONE_RESP    

      requesterRdy := sbMsgExchanger.io.done
      when(io.requesterRdy && io.responderRdy) {
        nextState := MBTrainState.sRXCLKCAL
        mbTrainTxSelfCalDoneReg := false.B  // Reset for possible next iteration                 
      }  
    }
    is(MBTrainState.sRXCLKCAL) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxTrackEn := true.B
      // Data and valid receivers are permitted to be disabled

      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_RXCLKCAL_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXCLKCAL_START_RESP

          when(sbMsgExchanger.io.done) {
            when(isCurrFreqGreaterThan32) {
              nextSubstate := MBTrainSubstate.s1
            }.otherwise {
              nextSubstate := MBTrainSubstate.s3
            }            
          }
        }
        is(MBTrainSubstate.s1) {  // CALIBRATION FOR > 32 GT/S
          // TODO: Missing implementation for > 32 GT/s
          //       MBTrainSubstate.s1 and .s2 reserved for > 32 GT/s.
          //       Change done state if more substates are required.
          errorDetectedWire := true.B
        }
        is(MBTrainSubstate.s3) {  // DONE
          // TODO: This can just run the existing link operations, or create MBTrainRxClkCal module
          //       which is left for PHY designers to implement
          //       Can be used to fine tune with SW
          io.mbTrainRxClkCalStart := true.B
          sbMsgExchanger.io.req.valid := mbTrainRxClkCalDoneReg        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_RXCLKCAL_DONE_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXCLKCAL_DONE_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {            
            nextState := MBTrainState.sVALTRAINCENTER
            nextSubstate := MBTrainSubstate.s0
            mbTrainRxClkCalDoneReg := false.B
          }
        }
      }
    }
    is(MBTrainState.sVALTRAINCENTER) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALTRAINCENTER_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                                 
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINCENTER_START_RESP

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) {  // START TX-INITIATED LINK OPERATION
          // TODO: Figure out a better way to do operations
          // Either start a rx-initated pt test or eye width sweep
          // USE VALTRAIN PATTERN
          nextSubstate := MBTrainSubstate.s2
        }
        is(MBTrainSubstate.s2) {  // WAIT FOR LINK OP RESULT
          // TODO:
          // Wait for result and register, do any calculation, either go to s3 and move on,
          // or go to s1 to start a new link operation with new settings
          // Reminder to trigger an error if no satifactory param is found
          nextSubstate := MBTrainSubstate.s3
        }
        is(MBTrainSubstate.s3) {  // DONE
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALTRAINCENTER_DONE_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                                 
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINCENTER_DONE_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sVALTRAINVREF
            nextSubstate := MBTrainSubstate.s0            
          }
        }
      }
    }
    is(MBTrainState.sVALTRAINVREF) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALTRAINVREF_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINVREF_START_RESP

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) {  // START TX-INITIATED LINK OPERATION
          // TODO: Figure out a better way to do operations
          // Either start a rx-initated pt test or eye width sweep
          // USE VALTRAIN PATTERN
          // TODO: This is an optional state for local die. Have an IO that is connected
          //       to MMIO registers that indicate whether to do this training or not.
          //       If not, then send jump to END and send done req.     
          nextSubstate := MBTrainSubstate.s2
        }
        is(MBTrainSubstate.s2) {  // WAIT FOR LINK OP RESULT
          // TODO:
          // Wait for result and register, do any calculation, either go to s3 and move on,
          // or go to s1 to start a new link operation with new settings
          // Reminder to trigger an error if no satifactory param is found
          nextSubstate := MBTrainSubstate.s3
        }
        is(MBTrainSubstate.s3) {  // DONE
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALTRAINVREF_DONE_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINVREF_DONE_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINCENTER1
            nextSubstate := MBTrainSubstate.s0            
          }
        }
      }
    }
    is(MBTrainState.sDATATRAINCENTER1) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINCENTER1_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER1_START_RESP

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) {  // START TX-INITIATED LINK OPERATION
          // TODO: Figure out a better way to do operations
          // Either start a rx-initated pt test or eye width sweep
          // USE LFSR PATTERN        
          nextSubstate := MBTrainSubstate.s2
        }
        is(MBTrainSubstate.s2) {  // WAIT FOR LINK OP RESULT
          // TODO:
          // Wait for result and register, do any calculation, either go to s3 and move on,
          // or go to s1 to start a new link operation with new settings
          // Reminder once it moves on to END apply the results
          // Reminder to trigger an error if no satifactory param is found
          nextSubstate := MBTrainSubstate.s3
        }
        is(MBTrainSubstate.s3) {  // END
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINCENTER1_END_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER1_END_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINVREF
            nextSubstate := MBTrainSubstate.s0            
          }
        }
      }
    }
    is(MBTrainState.sDATATRAINVREF) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      // NOTE FROM SPEC:
      // It is possible that the eye opening in this step is insufficient (test fails) and a per-bit
      // deskew may be needed on the Receiver. Thus, the UCIe Module must exit to
      // MBTRAIN.RXDESKEW.

      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINVREF_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINVREF_START_RESP

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) {  // START TX-INITIATED LINK OPERATION
          // TODO: Figure out a better way to do operations
          // Either start a rx-initated pt test or eye width sweep
          // USE LFSR PATTERN
          // TODO: This is an optional state for local die. Have an IO that is connected
          //       to MMIO registers that indicate whether to do this training or not.
          //       If not, then send jump to END and send done req.
          nextSubstate := MBTrainSubstate.s2
        }
        is(MBTrainSubstate.s2) {  // WAIT FOR LINK OP RESULT
          // TODO:
          // Wait for result and register, do any calculation, either go to s3 and move on,
          // or go to s1 to start a new link operation with new settings
          // Reminder once it moves on to END apply the results
          // Reminder to trigger an error if no satifactory param is found
          nextSubstate := MBTrainSubstate.s3
        }
        is(MBTrainSubstate.s3) {  // END
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINVREF_END_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINVREF_END_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sRXDESKEW
            nextSubstate := MBTrainSubstate.s0            
          }
        }
      }      
    }
    is(MBTrainState.sRXDESKEW) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxTrackEn := true.B
      // Data and valid receivers are permitted to be disabled

      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_RXDESKEW_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXDESKEW_START_RESP

          when(sbMsgExchanger.io.done) {
            when(isCurrFreqGreaterThan32) {
              nextSubstate := MBTrainSubstate.s1
            }.otherwise {
              nextSubstate := MBTrainSubstate.s3
            }
            
          }
        }
        is(MBTrainSubstate.s1) {  // CALIBRATION FOR > 32 GT/S
          // TODO: Missing implementation for > 32 GT/s
          //       MBTrainSubstate.s1 and .s2 reserved for > 32 GT/s.
          //       Change done state if more substates are required.
          errorDetectedWire := true.B
        }
        is(MBTrainSubstate.s3) {  // START RX-INITIATED LINK OPERATION
          // TODO: Per lane deskew for RX is optional for local die. Have an IO that is connected
          //       to MMIO registers that indicate whether to do this training or not.
          //       If not, then send jump to END and send done req.        
          nextSubstate := MBTrainSubstate.s4
        }
        is(MBTrainSubstate.s4) {  // WAIT FOR LINK OP RESULT
          // Can either move on or repeat tests. Don't forget to apply the results
          nextSubstate := MBTrainSubstate.s5
        }         
        is(MBTrainSubstate.s5) {  // DONE
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_RXDESKEW_END_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXDESKEW_END_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINCENTER2
            nextSubstate := MBTrainSubstate.s0
          }
        }
      }
    }
    is(MBTrainState.sDATATRAINCENTER2) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINCENTER2_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER2_START_RESP

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }          
        }
        is(MBTrainSubstate.s1) {  // START TX-INITIATED LINK OPERATION
          // TODO: Figure out a better way to do operations
          // Either start a rx-initated pt test or eye width sweep
          // USE LFSR PATTERN
          // TODO: This is an optional state for local die. Have an IO that is connected
          //       to MMIO registers that indicate whether to do this training or not.
          //       If not, then send jump to END and send done req.
          // TODO: Can go END from s0, if no perlane deskew performed
          nextSubstate := MBTrainSubstate.s2
        }
        is(MBTrainSubstate.s2) {  // WAIT FOR LINK OP RESULT
          // TODO:
          // Wait for result and register, do any calculation, either go to s3 and move on,
          // or go to s1 to start a new link operation with new settings
          // Reminder once it moves on to END apply the results
          // Reminder to trigger an error if no satifactory param is found
          nextSubstate := MBTrainSubstate.s3
        }
        is(MBTrainSubstate.s3) {  // END
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINCENTER2_END_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER2_END_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sLINKSPEED
            nextSubstate := MBTrainSubstate.s0            
          }
        }
      } 
    }
    is(MBTrainState.sLINKSPEED) {
      io.mbLaneCtrlIo.txDataTriState.foreach(x => x := false.B)
      io.mbLaneCtrlIo.txClkTriState := false.B
      io.mbLaneCtrlIo.txValidTriState := false.B
      io.mbLaneCtrlIo.txTrackTriState := false.B
      io.mbLaneCtrlIo.rxDataEn.foreach(x => x := true.B)
      io.mbLaneCtrlIo.rxClkEn := true.B
      io.mbLaneCtrlIo.rxValidEn := true.B
      // Track receiver is allowed to be disable

      fromLinkspeed := true.B   // Used in sSPEEDIDLE

      switch(substateReg) {
        is(MBTrainSubstate.s0) {
          completedLinkspeedStep1And2Flag := false.B
          initiatingExitToPhyretrainFlag := false.B
          initiatingSpeedDegradeFlag := false.B
          initiatingWidthDegradeFlag := false.B
          initiatingDoneFlag := false.B
          linkOpsResultsValid := false.B

          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_START_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_START_RESP

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          } 
        }
        is(MBTrainSubstate.s1) { // Start TX D2C PT TEST, and wait for result
          io.txPtTestReqIntfIo.start := true.B
          io.txPtTestReqIntfIo.patternType := PatternSelect.LFSR
          // linkTrainingParameters stay as defaults 

          when(io.txPtTestReqIntfIo.ptTestResults.valid) {
            linkOpsResults := io.txPtTestReqIntfIo.ptTestResults.bits
            linkOpsResultsValid := true.B            
          }
       
          when(io.txPtTestReqIntfIo.done) {
            completedLinkspeedStep1And2Flag := true.B // Goes HIGH when the state transitions
          }

          when(io.txPtTestReqIntfIo.done && laneHasErrors) {
            nextSubstate := MBTrainSubstate.s2
          }.elsewhen(io.txPtTestReqIntfIo.done && !laneHasErrors) {
            nextSubstate := MBTrainSubstate.s5
          }                             
        }
        is(MBTrainSubstate.s2) {  // SINGLE MODULE - ERRORS ENCOUNTERED (Exchange ERROR msg)
          io.doElectricalIdleTx := true.B

          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_ERROR_REQ, 
                                                    "PHY", "PHY", true)
          initiatingErrorFlag := sbMsgExchanger.io.msgSent

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                            
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_ERROR_RESP

          when(sbMsgExchanger.io.msgReceived) {   // Remote has error in lanes
            clearPhyInRetrainFlag := true.B

            when(isWidthDegradePossible) {
              nextSubstate := MBTrainSubstate.s3  // Do repair
            }.elsewhen(!isWidthDegradePossible) {
              nextSubstate := MBTrainSubstate.s4  // Do speed degrade
            } 
          }.elsewhen(io.remoteExitingToPhyretrain) {      
            nextSubstate := MBTrainSubstate.s7    // To an intermediate sync state
          }
        }
        is(MBTrainSubstate.s3) {  // EXIT TO REPAIR    
          io.doElectricalIdleTx := true.B
                        
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_REQ, 
                                                    "PHY", "PHY", true)
          initiatingWidthDegradeFlag := sbMsgExchanger.io.msgSent

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                            
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_RESP

          // NOTE: Speed degrade takes priority if both msgReceived and remoteExitingToSpeedDegrade
          // are high, in the off chance. However, as per the spec, local shouldn't receive a 
          // response to the {exit to repair req} when remote is going to speed degrade, because
          // you can't have both errors and no errors found.
          when(io.remoteExitingToSpeedDegrade) {
            nextSubstate := MBTrainSubstate.s9    // To an intermediate sync state            
          }.elsewhen(sbMsgExchanger.io.msgReceived) {
            requesterRdy := true.B
            when(io.requesterRdy && io.responderRdy) {
              nextSubstate := MBTrainSubstate.s0
              nextState := MBTrainState.sREPAIR 
            }            
          }         
        }
        is(MBTrainSubstate.s4) {  // EXIT TO SPEED DEGRADE   
          io.doElectricalIdleTx := true.B

          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_REQ, 
                                                    "PHY", "PHY", true)
          initiatingSpeedDegradeFlag := sbMsgExchanger.io.msgSent

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                            
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_RESP

          requesterRdy := sbMsgExchanger.io.done
          when(io.responderRdy && io.requesterRdy) {
            // State transition with Responder into SPEEDIDLE
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sSPEEDIDLE 
          }          
        }
        is(MBTrainSubstate.s5) {  // SINGLE-/MULTI-MODULE - NO ERRORS
          // TODO: Set clock phase on transmitters to sample data eye at optmimal pt
          //       should be set already at this point...todo is here to make sure before sign-off

          // Note: Multi-module isn't currently implemented. So doing single-module logic only
          // at the moment
          when(io.phyInRetrain) {
            when(io.changeInRuntimeLinkCtrlRegs) {
              sbMsgExchanger.io.req.valid := true.B        
              sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_REQ, 
                                                        "PHY", "PHY", true)
              initiatingExitToPhyretrainFlag := sbMsgExchanger.io.msgSent 

              sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                            
              sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_RESP
              
              requesterRdy := sbMsgExchanger.io.done
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
        is(MBTrainSubstate.s6) {  // DONE
          sbMsgExchanger.io.req.valid := true.B        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_DONE_REQ, 
                                                    "PHY", "PHY", true)
          initiatingDoneFlag := sbMsgExchanger.io.msgSent

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                            
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_DONE_RESP

          requesterRdy := sbMsgExchanger.io.done && !io.remoteErrorInLinkspeed

          // Priority is given to going into the various repair states that Remote requests.
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
        is(MBTrainSubstate.s7) {        
        // Intermediate synchronization state for when io.remoteExitingToPhyretrain is HIGH
        // Only transition when Responder has sent the resp to exit to phyretrain req
        requesterRdy := true.B
        when(io.responderRdy && io.requesterRdy) {
          nextSubstate := MBTrainSubstate.s0
          nextState := MBTrainState.sTOPHYRETRAIN
        }
      }
      is(MBTrainSubstate.s8) {
        io.doElectricalIdleTx := true.B
        // Intermediate synchronization state for when io.remoteExitingToRepair is HIGH
        // Only transition when Responder has sent the resp to exit to repair req
        requesterRdy := true.B
        when(io.responderRdy && io.requesterRdy) {
          nextSubstate := MBTrainSubstate.s0
          nextState := MBTrainState.sREPAIR
        }
      }
      is(MBTrainSubstate.s9) {
        io.doElectricalIdleTx := true.B
        // Intermediate synchronization state for when io.remoteExitingToSpeedDegrade is HIGH
        // Only transition when Responder has sent the resp to exit to speed degrade req
        requesterRdy := true.B
        when(io.responderRdy && io.requesterRdy) {
          nextSubstate := MBTrainSubstate.s0
          nextState := MBTrainState.sSPEEDIDLE
        }
       }  
      }          
    }
    is(MBTrainState.sREPAIR) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // INIT
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_REPAIR_INIT_REQ, 
                                                    "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_INIT_RESP
          
          when(sbMsgExchanger.io.done) {          
            nextSubstate := MBTrainSubstate.s1
          } 
        }
        is(MBTrainSubstate.s1) { // APPLY DEGRADE          
          sbMsgExchanger.io.req.valid := true.B 
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_REPAIR_APPLY_DEGRADE_REQ, 
                                                    "PHY", "PHY", true, 
                                                    msgInfo = Cat(0.U(12.W), 
                                                                  newTxFunctionalLanes))
          applyDegrade := true.B          

          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                                    
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_APPLY_DEGRADE_RESP

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s2
          }
        }
        is(MBTrainSubstate.s2) {  // END
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_REPAIR_END_RESP, 
                                                    "PHY", "PHY", true)
          requesterRdy := sbMsgExchanger.io.done
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
}

class MBTrainResponder(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle{
    // IN
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

    // OUT
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

    // Bundles with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val txPtTestRespIntfIo = new TxInitPtTestResponderInterfaceIO()
    val txEyeSweepRespIntfIo = new TxInitEyeWidthSweepResponderInterfaceIO()
    val rxPtTestRespIntfIo = new RxInitPtTestResponderInterfaceIO()
    val rxEyeSweepRespIntfIo = new RxInitEyeWidthSweepResponderInterfaceIO()
  })

  // Helper Modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // FSM state register
  val currentState = RegInit(MBTrainState.sVALVREF)
  val nextState = WireInit(currentState)  
  currentState := nextState
  io.currentState := currentState

  // Substate register
  val substateReg = RegInit(MBTrainSubstate.s0)
  val nextSubstate = WireInit(substateReg)
  substateReg := nextSubstate 

  // Responder ready logic -- used by requester
  val responderRdyStatusReg = RegInit(false.B)
  val responderRdy = WireInit(false.B)
  when((currentState =/= nextState) || (substateReg =/= nextSubstate)) {
    responderRdyStatusReg := false.B 
  }  
  when(responderRdy) {
    responderRdyStatusReg := true.B
  }
  io.responderRdy := responderRdyStatusReg || responderRdy

  // Error detection status bits
  val errorDetectedReg = RegInit(false.B)
  val errorDetectedWire = WireInit(false.B)
  val errorDetected = Wire(Bool())

  errorDetected := errorDetectedReg || errorDetectedWire
  when(errorDetectedWire) {
    errorDetectedReg := true.B
  }
  io.error := errorDetectedReg
  
  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState) || (substateReg =/= nextSubstate)
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  when(currentState === MBTrainState.sLINKSPEED) { // need to wait on mutiple messages in sLINKSPEED
    io.sbLaneIo.rx.ready := false.B    
  }.otherwise {
    io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready 
  }

  // Link operation IOs
  io.txPtTestRespIntfIo.start := false.B
  io.txPtTestRespIntfIo.patternType := PatternSelect.LFSR

  io.txEyeSweepRespIntfIo.start := false.B
  io.txEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

  io.rxPtTestRespIntfIo.start := false.B
  io.rxPtTestRespIntfIo.patternType := PatternSelect.LFSR

  io.rxEyeSweepRespIntfIo.start := false.B
  io.rxEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

  // MBTrain.RXCLKCAL registers/wires
  val rxClkCalSendFwClkPattern = RegInit(false.B)
  val rxClkCalSendTrkPattern = RegInit(false.B)

  io.rxClkCalSendFwClkPattern := rxClkCalSendFwClkPattern
  io.rxClkCalSendTrkPattern := rxClkCalSendTrkPattern

  // MBTrain.LINKSPEED registers/wires
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

  // MBTrain.REPAIR registers/wires
  val currRemoteFunctionalLanesWire = Wire(UInt(3.W))
  val incRemoteFuncLanesWire = Wire(UInt(3.W))
  val widthChanged = WireInit(false.B)

  currRemoteFunctionalLanesWire := io.currRemoteTxFunctionalLanes
  incRemoteFuncLanesWire := sbMsgExchanger.io.resp.bits(42,40)
  io.newRemoteFunctionalLanes := incRemoteFuncLanesWire
  io.rxWidthChanged := widthChanged

  // IOs
  io.done := false.B

  switch(currentState) {
    is(MBTrainState.sVALVREF) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          when(io.start) {
            rxClkCalSendFwClkPattern := false.B
            rxClkCalSendTrkPattern := false.B

            sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
            sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALVREF_START_REQ

            sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
            sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALVREF_START_RESP, 
                                                      "PHY", "PHY", true)

            when(sbMsgExchanger.io.done) {
              nextSubstate := MBTrainSubstate.s1
            }   
          }       
        }
        is(MBTrainSubstate.s1) {  // END
          // "Wake up" both pt test and sweep circuits. The circuit will start depending
          // on appropriate message receieved.
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxPtTestRespIntfIo.patternType := PatternSelect.VALTRAIN          
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.VALTRAIN

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALVREF_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALVREF_END_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATAVREF
            nextSubstate := MBTrainSubstate.s0            
          }
        }      
      }
    }
    is(MBTrainState.sDATAVREF) {     
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATAVREF_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATAVREF_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }          
        }
        is(MBTrainSubstate.s1) {  // END
          // "Wake up" both pt test, and sweep circuits. The circuit will start depending
          // on appropriate message receieved.
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxPtTestRespIntfIo.patternType := PatternSelect.LFSR          
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATAVREF_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATAVREF_END_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sSPEEDIDLE
            nextSubstate := MBTrainSubstate.s0            
          }
        }      
      } 
    }
    is(MBTrainState.sSPEEDIDLE) {    
      // The correct width if a width degrade has happen will already be set by time  
      // Local die is in this state.      
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_DONE_REQ

      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_DONE_RESP, 
                                                "PHY", "PHY", true)
      responderRdy := sbMsgExchanger.io.done
      when(io.requesterRdy && io.responderRdy) {
        nextState := MBTrainState.sTXSELFCAL           
      }
    }
    is(MBTrainState.sTXSELFCAL) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_TXSELFCAL_DONE_REQ

      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_TXSELFCAL_DONE_RESP, 
                                                "PHY", "PHY", true)
      responderRdy := sbMsgExchanger.io.done
      when(io.requesterRdy && io.responderRdy) {
        nextState := MBTrainState.sRXCLKCAL           
      }    
    }
    is(MBTrainState.sRXCLKCAL) {     
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXCLKCAL_START_REQ

          when(sbMsgExchanger.io.msgReceived) {
            rxClkCalSendFwClkPattern := true.B
            rxClkCalSendTrkPattern := true.B
          }

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_RXCLKCAL_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }          
        }
        is(MBTrainSubstate.s1) {  // END          
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXCLKCAL_DONE_REQ

          when(sbMsgExchanger.io.msgReceived) {
            rxClkCalSendFwClkPattern := false.B
            rxClkCalSendTrkPattern := false.B
          }  

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_RXCLKCAL_DONE_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sVALTRAINCENTER
            nextSubstate := MBTrainSubstate.s0            
          }
        }      
      }  
    }
    is(MBTrainState.sVALTRAINCENTER) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINCENTER_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALTRAINCENTER_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }          
        }
        is(MBTrainSubstate.s1) {  // END
          // "Wake up" both pt test, and sweep circuits. The circuit will start depending
          // on appropriate message receieved.
          io.txPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.txEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.txPtTestRespIntfIo.patternType := PatternSelect.VALTRAIN          
          io.txEyeSweepRespIntfIo.patternType := PatternSelect.VALTRAIN

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINCENTER_DONE_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALTRAINCENTER_DONE_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sVALTRAINVREF
            nextSubstate := MBTrainSubstate.s0            
          }
        }      
      }  
    }
    is(MBTrainState.sVALTRAINVREF) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINVREF_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALTRAINVREF_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }          
        }
        is(MBTrainSubstate.s1) {  // DONE
          // "Wake up" both pt test, and sweep circuits. The circuit will start depending
          // on appropriate message receieved.
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxPtTestRespIntfIo.patternType := PatternSelect.VALTRAIN          
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.VALTRAIN

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_VALTRAINVREF_DONE_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_VALTRAINVREF_DONE_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINCENTER1
            nextSubstate := MBTrainSubstate.s0            
          }
        }      
      }   
    }
    is(MBTrainState.sDATATRAINCENTER1) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER1_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINCENTER1_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }          
        }
        is(MBTrainSubstate.s1) {  // END
          // "Wake up" both pt test, and sweep circuits. The circuit will start depending
          // on appropriate message receieved.
          io.txPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.txEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.txPtTestRespIntfIo.patternType := PatternSelect.LFSR          
          io.txEyeSweepRespIntfIo.patternType := PatternSelect.LFSR

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER1_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINCENTER1_END_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINVREF
            nextSubstate := MBTrainSubstate.s0            
          }
        }      
      }        
    }
    is(MBTrainState.sDATATRAINVREF) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINVREF_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINVREF_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }          
        }
        is(MBTrainSubstate.s1) {  // END
          // "Wake up" both pt test, and sweep circuits. The circuit will start depending
          // on appropriate message receieved.
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxPtTestRespIntfIo.patternType := PatternSelect.VALTRAIN          
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.VALTRAIN

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINVREF_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINVREF_END_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sRXDESKEW
            nextSubstate := MBTrainSubstate.s0            
          }
        }      
      }    
    }
    is(MBTrainState.sRXDESKEW) {   
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXDESKEW_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_RXDESKEW_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }          
        }
        is(MBTrainSubstate.s1) {  // END
          // "Wake up" both pt test, and sweep circuits. The circuit will start depending
          // on appropriate message receieved.
          io.rxPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.rxPtTestRespIntfIo.patternType := PatternSelect.VALTRAIN          
          io.rxEyeSweepRespIntfIo.patternType := PatternSelect.VALTRAIN

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_RXDESKEW_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_RXDESKEW_END_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sDATATRAINCENTER2
            nextSubstate := MBTrainSubstate.s0            
          }
        }      
      }    
    }
    is(MBTrainState.sDATATRAINCENTER2) {
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER2_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINCENTER2_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }          
        }
        is(MBTrainSubstate.s1) {  // END
          // "Wake up" both pt test, and sweep circuits. The circuit will start depending
          // on appropriate message receieved.
          io.txPtTestRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.txEyeSweepRespIntfIo.start := !sbMsgExchanger.io.msgReceived 
          io.txPtTestRespIntfIo.patternType := PatternSelect.VALTRAIN          
          io.txEyeSweepRespIntfIo.patternType := PatternSelect.VALTRAIN

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                  
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_DATATRAINCENTER2_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived        
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_DATATRAINCENTER2_END_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextState := MBTrainState.sLINKSPEED
            nextSubstate := MBTrainSubstate.s0            
          }
        }      
      }     
    }
    is(MBTrainState.sLINKSPEED) {   
      switch(substateReg) {
        is(MBTrainSubstate.s0) {  // START
          ptTestDoneInLinkSpeed := false.B
          remoteErrorInLinkspeedFlag := false.B
          remoteExitingToRepairFlag := false.B
          remoteExitingToSpeedDegradeFlag := false.B
          remoteExitingToPhyretrainFlag := false.B

          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_LINKSPEED_START_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_START_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
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
            when(SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.MBTRAIN_LINKSPEED_ERROR_REQ)) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBTrainSubstate.s2
            }.elsewhen(SBMsgCompare(io.sbLaneIo.rx.bits.data, 
                                    SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_REQ)) {
              io.sbLaneIo.rx.ready := true.B 
              nextSubstate := MBTrainSubstate.s6
            }.elsewhen(SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.MBTRAIN_LINKSPEED_DONE_REQ)) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBTrainSubstate.s7 
            }
          }   
        }
        is(MBTrainSubstate.s2) {  // Received {MBTRAIN.LINKSPEED error req}
          remoteErrorInLinkspeedFlag := true.B

          when(localCompletedSteps1And2 && localNotInitiatingPhyRetrain) {
            io.doElectricalIdleRx := true.B
            sbMsgExchanger.io.req.valid := true.B
            sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_ERROR_RESP, 
                                                      "PHY", "PHY", true)                                                        
          }

          when(io.localInitiatingExitToPhyRetrain) {
            nextSubstate := MBTrainSubstate.s8
          }.elsewhen(sbMsgExchanger.io.msgSent) {
            nextSubstate := MBTrainSubstate.s3
          }
        } 
        is(MBTrainSubstate.s3) {  // wait for either {exit to repair} or {exit to speed degrade}
          io.doElectricalIdleRx := true.B          
          when(io.sbLaneIo.rx.valid) {
            when(SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_REQ)) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBTrainSubstate.s4  
            }.elsewhen(SBMsgCompare(io.sbLaneIo.rx.bits.data, 
                                    SBM.MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_REQ)) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := MBTrainSubstate.s5
            }
          }
        }
        is(MBTrainSubstate.s4) {  // Received {MBTRAIN.LINKSPEED exit to repair req}
          io.doElectricalIdleRx := true.B

          when(localNotInitiatingSpeedDegrade) {
            sbMsgExchanger.io.req.valid := true.B
            sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_EXIT_TO_REPAIR_RESP, 
                                                      "PHY", "PHY", true) 
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
        is(MBTrainSubstate.s5) {  // Received {MBTRAIN.LINKSPEED exit to speed degrade req}
          io.doElectricalIdleRx := true.B
          
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_EXIT_TO_SPEED_DEGRADE_RESP, 
                                                    "PHY", "PHY", true) 

          remoteExitingToSpeedDegradeFlag := sbMsgExchanger.io.msgSent

          responderRdy := remoteExitingToSpeedDegradeFlag
          when(io.responderRdy && io.requesterRdy) {  
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sSPEEDIDLE 
          }
        }
        is(MBTrainSubstate.s6) {  // Received {MBTRAIN.LINKSPEED exit to PHY retrain req}
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_RESP, 
                                                    "PHY", "PHY", true) 

          remoteExitingToPhyretrainFlag := sbMsgExchanger.io.msgSent

          responderRdy := remoteExitingToPhyretrainFlag
          when(io.requesterRdy && io.responderRdy) {  
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sTOPHYRETRAIN 
          }        
        }
        is(MBTrainSubstate.s7) {  // Received {MBTRAIN.LINKSPEED done req}
          // Only send response to done if Local (Requester) is intiating a done as well
          sbMsgExchanger.io.req.valid := io.localInitiatingDone
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_LINKSPEED_DONE_RESP,
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.msgSent && !io.localInitiatingError     

          // If localInitiatingError is HIGH that means link cannot move onto LINKINIT
          // Priority is given to going into various repair states requested by Local die.
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
        is(MBTrainSubstate.s8) {
          // Intermediate synchronization state for when io.localInitiatingExitToPhyRetrain is HIGH
          // Only transition when resp to exit to phyretrain req is received by Requester
          responderRdy := true.B
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sTOPHYRETRAIN
          }
        }
        is(MBTrainSubstate.s9) {
          io.doElectricalIdleRx := true.B
          // Intermediate synchronization state for when io.localInitiatingWidthDegrade is HIGH
          // Only transition when resp to exit to repair req is received by Requester
          responderRdy := true.B
          when(io.responderRdy && io.requesterRdy) {
            nextSubstate := MBTrainSubstate.s0
            nextState := MBTrainState.sREPAIR
          }
        }
        is(MBTrainSubstate.s10) {
          io.doElectricalIdleRx := true.B
          // Intermediate synchronization state for when io.localInitiatingSpeedDegrade is HIGH
          // Only transition when resp to exit to speed degrade req is received by Requester
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
        is(MBTrainSubstate.s0) {  // INIT
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_INIT_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_REPAIR_INIT_RESP, 
                                                    "PHY", "PHY", true)

          when(sbMsgExchanger.io.done) {
            nextSubstate := MBTrainSubstate.s1
          }
        }
        is(MBTrainSubstate.s1) {  // APPLY DEGRADE
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                    
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_APPLY_DEGRADE_REQ

          when(sbMsgExchanger.io.resp.valid) {
            errorDetectedWire := incRemoteFuncLanesWire === "b000".U
            widthChanged := incRemoteFuncLanesWire =/= currRemoteFunctionalLanesWire
          }

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived && !errorDetected
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_REPAIR_APPLY_DEGRADE_RESP, 
                                                    "PHY", "PHY", true)

          responderRdy := sbMsgExchanger.io.done
          when(io.requesterRdy && io.responderRdy) {
            nextSubstate := MBTrainSubstate.s2
          }                                                    
        }
        is(MBTrainSubstate.s2) {  // END
          sbMsgExchanger.io.rxRefBitPattern.valid := true.B
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.MBTRAIN_REPAIR_END_REQ

          sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.MBTRAIN_REPAIR_END_RESP, 
                                                    "PHY", "PHY", true)
          responderRdy := sbMsgExchanger.io.done
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

object MainMBTrainSM extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBTrainSM(new AfeParams(), new SidebandParams()),
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

object MainMBTrainRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBTrainRequester(new AfeParams(), new SidebandParams()),
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

object MainMBTrainResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBTrainResponder(new AfeParams(), new SidebandParams()),
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
