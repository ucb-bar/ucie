/*
  Description:
    Link Training State Machine for the Logical PHY.
    Currently scoped to Standard Package operation in Streaming RAW mode.
*/
package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.layer.{Layer, LayerConfig, block}
import chisel3.layers.Verification
import chisel3.util._

class LinkTrainingSM(sbParams: SidebandParams, afeParams: AfeParams, retryW: Int) extends Module {
  val io = IO(new Bundle {
    // ========================================================================
    // IN
    // ========================================================================
    val pwrGood = Input(Bool())                     // TODO: DVSEC, custom
    val retryTrainingAmt = Input(UInt(retryW.W))    // TODO: DVSEC, custom
    val localPhyParamSettings = Flipped(Valid(new PHYParamExchangeIO()))
    val linkTrainingParameters = new LinkOperationParameters()
    val swStartLinkTraining = Input(Bool())         // TODO: DVSEC
    val maxErrorThresholdPerLane = Input(UInt(16.W)) 
    // val trainingBypass    = Input(Bool())      TODO: Need to add bypassing
    // val selectStateBypass = Input(LTState())   TODO: Need to add bypassing
    val changeInRuntimeLinkCtrlRegsDetected = Input(Bool())   // TODO: Detected by DVSEC in LogPHY, DVSEC regs not fully implemented
    val runtimeLinkCtrlBusyBit = Input(Bool())                // TODO: Implement DVSEC
    val runtimeRequestForRepair = Input(Bool())               // TODO: Implemnt DVSEC

    // ========================================================================
    // OUT
    // ========================================================================
    val ltState = Output(LTState())
    val currentState     = Output(LTSMState())  // Out to logphytop    
    val trainingTimedout = Output(Bool())
    val fatalTrainingError = Output(Bool())
    val forceRdiLinkError = Output(Bool())
    val doLaneReversal   = Output(Bool())
    val localTxFunctionalLanes = Output(UInt(3.W))
    val remoteTxFunctionalLanes = Output(UInt(3.W))
    val negotiatedPhyParamSettings = Valid(new PHYParamExchangeIO())
    val rxClkCalSendFwClkPattern = Output(Bool())
    val rxClkCalSendTrkPattern = Output(Bool())
    val scramblerReset = Output(Bool())         // Same reset for scrambler and descrambler
    
    // ========================================================================
    // RDI IO
    // ========================================================================
    val rdi = new Bundle {
      // val lpStateReq = Input(RDIStateReq())
      val plStateSts = Input(RDIState())
      val lpStateReq = Input(RDIStateReq())
      val doRdiBringup = Output(Bool())
      val doingRdiBringUp = Input(Bool())
    }

    // ========================================================================
    // CONTROL BUNDLES
    // ========================================================================
    val sbCtrlIo  = new SidebandCtrlIO()
    val mbCtrlIo  = new MainbandLaneCtrlIO(afeParams)
    val phyCtrlIo = new PhyCtrlIO()
    
    // ========================================================================
    // PHY TRAINING IO
    // ========================================================================
    val phyTrainIo = Flipped(new PhyTrainIO(afeParams))

    // ========================================================================
    // LANE IO BUNDLES (Mixed Decoupled TX/RX)
    // ========================================================================
    val sbLaneIo  = new SidebandLaneIO(sbParams)

    // ========================================================================
    // SHARED PATTERN RESOURCES
    // ========================================================================
    val patternWriterIo = Flipped(new PatternWriterIO())
    val patternReaderIo = Flipped(new PatternReaderIO(afeParams.mbLanes))
  })

  // ==============================================================================================
  // Variables
  // ==============================================================================================
  val mbSerializerRatio = afeParams.mbSerializerRatio
  val timeoutMs = 0.008
  val operatingFreq = 800000000     // TODO: Put this into an object
  val retryAmtW = retryW            // TODO: Need to put retryW into an object

  // ==============================================================================================
  // FSM state register
  // ==============================================================================================
  val currentState = RegInit(LTState.sRESET)
  val nextState = WireInit(currentState)
  currentState := nextState

  // Full detailed states, used for debugging
  val currentStateSpecific = WireDefault(LTSMState.sRESET)
  io.currentState := currentStateSpecific
  io.ltState := currentState
  
  // ==============================================================================================
  // Timeout Logic
  // ==============================================================================================
  // If operating frequency is 800 MHz and timeout at 8ms, timeout cycles is 6,400,000
  // log2ceil(6,400,000) == 23
  val timeoutCycles = (operatingFreq * timeoutMs).toInt
  val timeoutWidth = log2Ceil(timeoutCycles)
  val timeoutCounter = RegInit(0.U(timeoutWidth.W))
  val timeoutMaxCycles = timeoutCycles.U
  val timeoutCntEn = Wire(Bool())         // disable next cycle
  val timeoutCntReset = Wire(Bool())      // reset next cycle
  val trainingTimedout = RegInit(false.B) // sticky the training timed out flag since counter reuse 
  val resetMinWait = RegInit(false.B)  
  val resetMinWaitMaxCycles = (timeoutCycles / 2).U   // 4ms
  val substateTransitioning = Wire(Bool())
  val trainErrorHandshakeTimedout = WireInit(false.B)

  // TrainError signals used for counter reuse
  val triggerTrainErrorReq = WireInit(false.B)  // Goes HIGH when TrainError requester sends REQ
  val waitTrainErrorResp = RegInit(false.B)     // Stays HIGH while waiting for Remote to respond

  // Reuse timeout counter for reset wait counting
  timeoutCntEn := ((currentState =/= LTState.sRESET) &&
                  (currentState =/= LTState.sACTIVE) &&
                  (currentState =/= LTState.sL1_L2) &&
                  (currentState =/= LTState.sTRAINERROR) && 
                  (timeoutCounter =/= timeoutMaxCycles)) ||
                  (currentState === LTState.sRESET && !resetMinWait) ||
                  (waitTrainErrorResp && (timeoutCounter =/= timeoutMaxCycles))

  // Spec defined substates have a 8ms residency timeout
  substateTransitioning := false.B
  timeoutCntReset := (nextState =/= currentState) || substateTransitioning || triggerTrainErrorReq
  
  trainErrorHandshakeTimedout := waitTrainErrorResp && (timeoutCounter === timeoutMaxCycles)

  when(timeoutCounter === timeoutMaxCycles) {
    trainingTimedout := true.B
  } .elsewhen(currentState === LTState.sRESET && nextState =/= LTState.sRESET) {
    trainingTimedout := false.B
  }

  // Timeout counter logic  
  when(timeoutCntReset) {
    timeoutCounter := 0.U
  }.otherwise {
    when(timeoutCntEn){
      timeoutCounter := timeoutCounter + 1.U               
    }
  }

  // Wait a minimum of 4ms upon entering RESET  -- reuses timeout counter
  when((timeoutCounter === resetMinWaitMaxCycles) && (currentState === LTState.sRESET)) {
    resetMinWait := true.B        
  }.elsewhen((currentState =/= LTState.sRESET) && (nextState === LTState.sRESET)) {
    resetMinWait := false.B
  }

  // ==============================================================================================
  // Training Retrigger Logic
  // ==============================================================================================
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

  // ==============================================================================================
  // Top-Level Defaults and Registers
  // ==============================================================================================
  // Defaults to PACKET, overridden by sRESET and sSBINIT
  val sbRxTxMode = WireDefault(SBRxTxMode.PACKET)

  io.trainingTimedout := trainingTimedout
  io.fatalTrainingError := fatalTrainingError
  io.forceRdiLinkError := forceRdiLinkError

  io.sbCtrlIo.txEn := true.B
  io.sbCtrlIo.rxEn := true.B
  io.sbCtrlIo.rxTxMode := sbRxTxMode
  io.sbCtrlIo.sbReset := (currentState === LTState.sRESET) && !resetMinWait

  // SB Link Serdes freezes accepting packets when transitioned TrainError
  io.sbCtrlIo.freezeAcceptingPackets := false.B

  
  // Frequency register
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

  // ==============================================================================================
  // Mainband Trigger Training Logic
  // ==============================================================================================
  // Remote triggered training -- SBINIT pattern detection from remote is done in LTState.sRESET
  val sbInitPatternCounter = RegInit(0.U(2.W))
  val remoteTriggerTraining = Wire(Bool())
  val sbInitClkPattern = BigInt("5555555555555555", 16).U(64.W) // 0b0101_0101_..._0101
  remoteTriggerTraining := sbInitPatternCounter === 2.U

  // RDI triggered training
  val rdiTriggerTrainingWire = Wire(Bool())
  val rdiTriggerTraining = RegInit(false.B)  
  val prevRdiStateReq = RegInit(RDIStateReq.nop)
  prevRdiStateReq := io.rdi.lpStateReq
  rdiTriggerTrainingWire := (io.rdi.plStateSts === RDIState.reset) && 
                            (prevRdiStateReq === RDIStateReq.nop) && 
                            (io.rdi.lpStateReq === RDIStateReq.active)

  //  Adapter triggers Link Training on the RDI (RDI status is Reset and there is a NOP to Active
  //  transition on the state request)
  when(rdiTriggerTraining) {
    rdiTriggerTraining := true.B
  }.elsewhen(rdiTriggerTrainingWire) {
    rdiTriggerTraining := true.B
  }.elsewhen(nextState =/= LTState.sRESET && currentState === LTState.sRESET) {
    rdiTriggerTraining := false.B
  }

  // SW triggered training
  // TODO: Need to implement DVSEC registers and add the correct link up/down logic for this
  val swTriggerTraining = Wire(Bool())
  swTriggerTraining := io.swStartLinkTraining

  val triggerTraining = Wire(Bool())
  triggerTraining := swTriggerTraining || rdiTriggerTraining || remoteTriggerTraining
  val freshTrainingTrigger = Wire(Bool())
  freshTrainingTrigger := triggerTraining && !prevTrigger
  prevTrigger := triggerTraining

  // ==============================================================================================
  // Phy Parameters
  // ==============================================================================================
  // Local parameters
  val localModuleId = RegInit(0.U(2.W))
  val localVoltageSwing = RegInit(0.U(5.W))
  val localMaxDataRate = RegInit(0.U(4.W))
  val localClockMode = RegInit(0.U(1.W))
  val localClockPhase = RegInit(0.U(1.W))
  val localUcieSx8 = RegInit(0.U(1.W))
  val localSbFeatExt = RegInit(0.U(1.W))
  val localTxAdjRuntime = RegInit(0.U(1.W))
  val localSettingsValid = RegInit(false.B)

  // Negotiated parameters
  val remoteModuleId = RegInit(0.U(2.W))            // remote module ID
  val negotiatedVoltageSwing = RegInit(0.U(5.W))
  val negotiatedMaxDataRate = RegInit(0.U(4.W))
  val negotiatedClockMode = RegInit(0.U(1.W))
  val negotiatedClockPhase = RegInit(0.U(1.W))
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
  io.negotiatedPhyParamSettings.bits.ucieSx8 := negotiatedUcieSx8
  io.negotiatedPhyParamSettings.bits.sbFeatExt := negotiatedSbFeatExt
  io.negotiatedPhyParamSettings.bits.txAdjRuntime := negotiatedTxAdjRuntime
  io.negotiatedPhyParamSettings.valid := negotiatedSettingsValid

  // ==============================================================================================
  // Sub FSM Modules
  // ==============================================================================================
  val ltsmInReset = Wire(Bool())
  ltsmInReset := false.B  

  // TODO: Need to add this reset to the submodules
  val subFsmModuleReset = (reset.asBool || ltsmInReset).asAsyncReset 

  // ==============================================================================================
  // SBInit 
  // ==============================================================================================
  val sbInitSM = withReset(subFsmModuleReset) { Module(new SBInitSM(sbParams, timeoutCycles)) }

  // Defaults
  sbInitSM.io.fsmCtrl.start := false.B

  sbInitSM.io.requesterSbLaneIo.rx.valid := false.B
  sbInitSM.io.requesterSbLaneIo.rx.bits := 0.U.asTypeOf(chiselTypeOf(sbInitSM.io.requesterSbLaneIo.rx.bits))
  sbInitSM.io.requesterSbLaneIo.tx.ready := false.B

  sbInitSM.io.responderSbLaneIo.rx.valid := false.B
  sbInitSM.io.responderSbLaneIo.rx.bits := 0.U.asTypeOf(chiselTypeOf(sbInitSM.io.responderSbLaneIo.rx.bits))
  sbInitSM.io.responderSbLaneIo.tx.ready := false.B

  // ==============================================================================================
  // MBInit
  // ==============================================================================================
  val mbInitSM = withReset(subFsmModuleReset) { Module(new MBInitSM(afeParams, sbParams)) }

  // Defaults
  mbInitSM.io.fsmCtrl.start := false.B  

  mbInitSM.io.mbInitCalDone := io.phyTrainIo.mbInit.selfCalDone  
  io.phyTrainIo.mbInit.selfCalStart := mbInitSM.io.mbInitCalStart

  mbInitSM.io.localPhySettings.bits.moduleId := localModuleId
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
    negotiatedUcieSx8 := mbInitSM.io.negotiatedPhySettings.bits.ucieSx8
    negotiatedSbFeatExt := mbInitSM.io.negotiatedPhySettings.bits.sbFeatExt
    negotiatedTxAdjRuntime := mbInitSM.io.negotiatedPhySettings.bits.txAdjRuntime
    negotiatedSettingsValid := true.B
  }

  when(currentState === LTState.sRESET) {
    negotiatedSettingsValid := false.B
  }

  // Sideband defaults
  mbInitSM.io.requesterSbLaneIo.rx.valid := false.B
  mbInitSM.io.requesterSbLaneIo.rx.bits := 
    0.U.asTypeOf(chiselTypeOf(mbInitSM.io.requesterSbLaneIo.rx.bits))
  mbInitSM.io.requesterSbLaneIo.tx.ready := false.B

  mbInitSM.io.responderSbLaneIo.rx.valid := false.B
  mbInitSM.io.responderSbLaneIo.rx.bits := 
    0.U.asTypeOf(chiselTypeOf(mbInitSM.io.responderSbLaneIo.rx.bits))
  mbInitSM.io.responderSbLaneIo.tx.ready := false.B

  // Test defaults
  mbInitSM.io.txPtTestReqInterfaceIo.done := false.B
  mbInitSM.io.txPtTestReqInterfaceIo.ptTestResults.valid := false.B
  mbInitSM.io.txPtTestReqInterfaceIo.ptTestResults.bits := 
    0.U.asTypeOf(chiselTypeOf(mbInitSM.io.txPtTestReqInterfaceIo.ptTestResults.bits))

  mbInitSM.io.txPtTestRespInterfaceIo.done := false.B
  // ==============================================================================================
  // MBTrain
  // ==============================================================================================
  val mbTrainSM = withReset(subFsmModuleReset) { Module(new MBTrainSM(afeParams, sbParams)) }

  // The parameters are known from outside (DVSEC, or elaboration) see where they come from
  mbTrainSM.io.negotiatedMaxDataRate := negotiatedMaxDataRate(2,0).asTypeOf(SpeedMode())
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

  // Sideband defaults
  mbTrainSM.io.requesterSbLaneIo.rx.valid := false.B
  mbTrainSM.io.requesterSbLaneIo.rx.bits := 
    0.U.asTypeOf(chiselTypeOf(mbTrainSM.io.requesterSbLaneIo.rx.bits))
  mbTrainSM.io.requesterSbLaneIo.tx.ready := false.B

  mbTrainSM.io.responderSbLaneIo.rx.valid := false.B
  mbTrainSM.io.responderSbLaneIo.rx.bits := 
    0.U.asTypeOf(chiselTypeOf(mbTrainSM.io.responderSbLaneIo.rx.bits))
  mbTrainSM.io.responderSbLaneIo.tx.ready := false.B

  // TX Point Test Defaults
  mbTrainSM.io.txPtTestReqIntfIo.done := false.B
  mbTrainSM.io.txPtTestReqIntfIo.ptTestResults.valid := false.B
  mbTrainSM.io.txPtTestReqIntfIo.ptTestResults.bits := 
    0.U.asTypeOf(chiselTypeOf(mbTrainSM.io.txPtTestReqIntfIo.ptTestResults.bits))

  mbTrainSM.io.txPtTestRespIntfIo.done := false.B

  // TX Eye Sweep Defaults
  mbTrainSM.io.txEyeSweepReqIntfIo.done := false.B
  mbTrainSM.io.txEyeSweepReqIntfIo.eyeSweepTestResults.valid := false.B
  mbTrainSM.io.txEyeSweepReqIntfIo.eyeSweepTestResults.bits := 
    0.U.asTypeOf(chiselTypeOf(mbTrainSM.io.txEyeSweepReqIntfIo.eyeSweepTestResults.bits))

  mbTrainSM.io.txEyeSweepRespIntfIo.done := false.B

  // RX Point Test Defaults
  mbTrainSM.io.rxPtTestReqIntfIo.done := false.B
  mbTrainSM.io.rxPtTestReqIntfIo.ptTestResults.valid := false.B
  mbTrainSM.io.rxPtTestReqIntfIo.ptTestResults.bits := 
    0.U.asTypeOf(chiselTypeOf(mbTrainSM.io.rxPtTestReqIntfIo.ptTestResults.bits))

  mbTrainSM.io.rxPtTestRespIntfIo.done := false.B

  // RX Eye Sweep Defaults
  mbTrainSM.io.rxEyeSweepReqIntfIo.done := false.B
  mbTrainSM.io.rxEyeSweepReqIntfIo.eyeSweepTestResults.valid := false.B
  mbTrainSM.io.rxEyeSweepReqIntfIo.eyeSweepTestResults.bits := 
    0.U.asTypeOf(chiselTypeOf(mbTrainSM.io.rxEyeSweepReqIntfIo.eyeSweepTestResults.bits))

  mbTrainSM.io.rxEyeSweepRespIntfIo.done := false.B
  mbTrainSM.io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.valid := false.B
  mbTrainSM.io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.bits := 
    0.U.asTypeOf(chiselTypeOf(mbTrainSM.io.rxEyeSweepRespIntfIo.remoteEyeSweepTestResults.bits))

  mbTrainSM.io.trainingCtrl <> io.phyTrainIo.mbTrain

  // Basic Training Operations
  // ==============================================================================================
  // TX-initiated D2C Point Test
  // ==============================================================================================
  // Interface Wires
  val txPtReqIntf = Wire(new TxInitPtTestRequesterInterfaceIO(afeParams))
  val txPtRespIntf = Wire(new TxInitPtTestResponderInterfaceIO())

  // Defaults
  txPtReqIntf.start := false.B
  txPtReqIntf.patternType := DontCare
  txPtReqIntf.linkTrainingParameters := DontCare

  txPtRespIntf.start := false.B
  txPtRespIntf.patternType := DontCare

  // Module
  val txPtTestRequester = Module(new TxD2CPointTestRequester(afeParams, sbParams))
  val txPtTestResponder = Module(new TxD2CPointTestResponder(afeParams, sbParams))

  // Requester <-> Interface Connections
  txPtTestRequester.io.start := txPtReqIntf.start
  txPtTestRequester.io.patternType := txPtReqIntf.patternType
  txPtTestRequester.io.linkTrainingParameters := txPtReqIntf.linkTrainingParameters

  txPtReqIntf.done := txPtTestRequester.io.done
  txPtReqIntf.ptTestResults := txPtTestRequester.io.txInitPtTestResults
  
  txPtTestRequester.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits    // Broadcast sideband RX lane
  txPtTestRequester.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  // Responder <-> Interface Connections
  txPtTestResponder.io.start := txPtRespIntf.start
  txPtTestResponder.io.patternType := txPtRespIntf.patternType

  txPtRespIntf.done := txPtTestResponder.io.done

  txPtTestResponder.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits    // Broadcast sideband RX lane
  txPtTestResponder.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  // ==============================================================================================
  // TX-initiated D2C Eye Width Sweep
  // ==============================================================================================
  // Interface Wires
  val txEwReqIntf = Wire(new TxInitEyeWidthSweepRequesterInterfaceIO(afeParams))
  val txEwRespIntf = Wire(new TxInitEyeWidthSweepResponderInterfaceIO())

  // Defaults
  txEwReqIntf.start := false.B
  txEwReqIntf.patternType := DontCare
  txEwReqIntf.linkTrainingParameters := DontCare

  txEwRespIntf.start := false.B
  txEwRespIntf.patternType := DontCare

  // Module
  val txEwSweepRequester = Module(new TxD2CEyeWidthSweepRequester(afeParams, sbParams))
  val txEwSweepResponder = Module(new TxD2CEyeWidthSweepResponder(afeParams, sbParams))

  // Requester <-> Interface Connections
  txEwSweepRequester.io.start := txEwReqIntf.start
  txEwSweepRequester.io.patternType := txEwReqIntf.patternType
  txEwSweepRequester.io.linkTrainingParameters := txEwReqIntf.linkTrainingParameters

  txEwReqIntf.done := txEwSweepRequester.io.done
  txEwReqIntf.eyeSweepTestResults := txEwSweepRequester.io.txInitEyeWidthSweepResults

  txEwSweepRequester.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits  // Broadcast sideband RX lane
  txEwSweepRequester.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  // Responder <-> Interface Connections
  txEwSweepResponder.io.start := txEwRespIntf.start
  txEwSweepResponder.io.patternType := txEwRespIntf.patternType

  txEwRespIntf.done := txEwSweepResponder.io.done

  txEwSweepResponder.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits  // Broadcast sideband RX lane
  txEwSweepResponder.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  // ==============================================================================================
  // RX-initiated D2C Point Test
  // ==============================================================================================
  // Interface Wires
  val rxPtReqIntf = Wire(new RxInitPtTestRequesterInterfaceIO(afeParams))
  val rxPtRespIntf = Wire(new RxInitPtTestResponderInterfaceIO())

  // Defaults
  rxPtReqIntf.start := false.B
  rxPtReqIntf.patternType := DontCare
  rxPtReqIntf.linkTrainingParameters := DontCare

  rxPtRespIntf.start := false.B
  rxPtRespIntf.patternType := DontCare

  // Modules
  val rxPtTestRequester = Module(new RxD2CPointTestRequester(afeParams, sbParams))
  val rxPtTestResponder = Module(new RxD2CPointTestResponder(afeParams, sbParams))

  // Requester <-> Interface Connections
  rxPtTestRequester.io.start := rxPtReqIntf.start
  rxPtTestRequester.io.patternType := rxPtReqIntf.patternType
  rxPtTestRequester.io.linkTrainingParameters := rxPtReqIntf.linkTrainingParameters

  rxPtReqIntf.done := rxPtTestRequester.io.done
  rxPtReqIntf.ptTestResults := rxPtTestRequester.io.rxInitPtTestLocalResults

  rxPtTestRequester.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits  // Broadcast sideband RX lane
  rxPtTestRequester.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  // Responder <-> Interface Connections
  rxPtTestResponder.io.start := rxPtRespIntf.start
  rxPtTestResponder.io.patternType := rxPtRespIntf.patternType

  rxPtRespIntf.done := rxPtTestResponder.io.done
  
  rxPtTestResponder.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits    // Broadcast sideband RX lane
  rxPtTestResponder.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  // TODO: rxPtTestResponder.io.clockPhaseSelect is available here if needed by top-level PHY clocking

  // ==============================================================================================
  // RX-initiated D2C Eye Width Sweep
  // ==============================================================================================
  // Interface Wires
  val rxEwReqIntf = Wire(new RxInitEyeWidthSweepRequesterInterfaceIO(afeParams))
  val rxEwRespIntf = Wire(new RxInitEyeWidthSweepResponderInterfaceIO(afeParams))

  // Defaults
  rxEwReqIntf.start := false.B
  rxEwReqIntf.patternType := DontCare
  rxEwReqIntf.linkTrainingParameters := DontCare

  rxEwRespIntf.start := false.B
  rxEwRespIntf.patternType := DontCare

  // Modules
  val rxEwSweepRequester = Module(new RxD2CEyeWidthSweepRequester(afeParams, sbParams))
  val rxEwSweepResponder = Module(new RxD2CEyeWidthSweepResponder(afeParams, sbParams))

  // Requester <-> Interface Connections
  rxEwSweepRequester.io.start := rxEwReqIntf.start
  rxEwSweepRequester.io.patternType := rxEwReqIntf.patternType
  rxEwSweepRequester.io.linkTrainingParameters := rxEwReqIntf.linkTrainingParameters

  rxEwReqIntf.done := rxEwSweepRequester.io.done
  rxEwReqIntf.eyeSweepTestResults := rxEwSweepRequester.io.rxInitEyeWidthSweepResults

  rxEwSweepRequester.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits   // Broadcast sideband RX lane
  rxEwSweepRequester.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  // Responder <-> Interface Connections
  rxEwSweepResponder.io.start := rxEwRespIntf.start
  rxEwSweepResponder.io.patternType := rxEwRespIntf.patternType

  rxEwRespIntf.done := rxEwSweepResponder.io.done
  rxEwRespIntf.remoteEyeSweepTestResults := rxEwSweepResponder.io.rxEyeWidthSweepRemoteResults

  rxEwSweepResponder.io.sbLaneIo.rx.bits := io.sbLaneIo.rx.bits   // Broadcast sideband RX lane
  rxEwSweepResponder.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid

  
  // ==============================================================================================
  // PHY Lane Trainer Connections (TX & RX)
  // ==============================================================================================
  // Broadcast step / doneStepping. Note for RX Eye Sweep, Responder steps the PHY.
  txEwSweepRequester.io.phyTrainIo.step := io.phyTrainIo.eyeSweepCtrl.step 
  txEwSweepRequester.io.phyTrainIo.doneStepping := io.phyTrainIo.eyeSweepCtrl.doneStepping
  
  rxEwSweepResponder.io.phyTrainIo.step := io.phyTrainIo.eyeSweepCtrl.step
  rxEwSweepResponder.io.phyTrainIo.doneStepping := io.phyTrainIo.eyeSweepCtrl.doneStepping

  val fsmTxPtReqRunning = txPtReqIntf.start 
  val fsmTxEwReqRunning = txEwReqIntf.start 
  val fsmRxPtReqRunning = rxPtReqIntf.start 
  val fsmRxEwReqRunning = rxEwReqIntf.start 

  val fsmTxPtRespRunning = txPtTestResponder.io.usingPatternReader
  val fsmTxEwRespRunning = txEwSweepResponder.io.usingPatternReader
  val fsmRxPtRespRunning = rxPtTestResponder.io.usingPatternWriter
  val fsmRxEwRespRunning = rxEwSweepResponder.io.usingPatternWriter

  io.phyTrainIo.eyeSweepCtrl.waitingForCommand :=  
    (fsmTxEwReqRunning && txEwSweepRequester.io.phyTrainIo.waitingForCommand) ||
    (fsmRxEwReqRunning && rxEwSweepResponder.io.phyTrainIo.waitingForCommand)

  io.phyTrainIo.localStatus.doingTxPointTest := fsmTxPtReqRunning
  io.phyTrainIo.localStatus.doingTxEyeWidthSweep := fsmTxEwReqRunning
  io.phyTrainIo.localStatus.doingRxPointTest := fsmRxPtReqRunning
  io.phyTrainIo.localStatus.doingRxEyeWidthSweep := fsmRxEwReqRunning

  io.phyTrainIo.remoteStatus.doingTxPointTest := fsmTxPtRespRunning
  io.phyTrainIo.remoteStatus.doingTxEyeWidthSweep := fsmTxEwRespRunning
  io.phyTrainIo.remoteStatus.doingRxPointTest := fsmRxPtRespRunning
  io.phyTrainIo.remoteStatus.doingRxEyeWidthSweep := fsmRxEwRespRunning

  io.phyTrainIo.ltsmState := currentStateSpecific

  // ==============================================================================================
  // Lane Repair Registers
  // ==============================================================================================
  val localTxFunctionalLanes = RegInit("b011".U(3.W))   // Which local TX data lanes to disable
  val remoteTxFunctionalLanes = RegInit("b011".U(3.W))  // Which local RX data lanes to disable

  // ==============================================================================================
  // Pattern Writer/Reader Arbitration
  // ==============================================================================================
  // Setup
  val patternWriterClients = Seq(
    (mbInitSM.io.usingPatternWriter,           mbInitSM.io.patternWriterIo),
    (txPtTestRequester.io.usingPatternWriter,  txPtTestRequester.io.patternWriterIo),
    (txEwSweepRequester.io.usingPatternWriter, txEwSweepRequester.io.patternWriterIo),
    (rxPtTestResponder.io.usingPatternWriter,  rxPtTestResponder.io.patternWriterIo),
    (rxEwSweepResponder.io.usingPatternWriter, rxEwSweepResponder.io.patternWriterIo)
  )

  val patternReaderClients = Seq(
    (mbInitSM.io.usingPatternReader,           mbInitSM.io.patternReaderIo),
    (txPtTestResponder.io.usingPatternReader,  txPtTestResponder.io.patternReaderIo),
    (txEwSweepResponder.io.usingPatternReader, txEwSweepResponder.io.patternReaderIo),
    (rxPtTestRequester.io.usingPatternReader,  rxPtTestRequester.io.patternReaderIo),
    (rxEwSweepRequester.io.usingPatternReader, rxEwSweepRequester.io.patternReaderIo)
  )
  block(Verification){
    block(Verification.Assert) {
      // Hardware only exists in the verification layer
      val activeWriterClients = PopCount(patternWriterClients.map { case (isUsing, _) => isUsing })
      assert(activeWriterClients <= 1.U, 
            "FATAL: Multiple clients are trying to use the Pattern Writer at the same time")

      val activeReaderClients = PopCount(patternReaderClients.map { case (isUsing, _) => isUsing })
      assert(activeReaderClients <= 1.U, 
            "FATAL: Multiple clients are trying to use the Pattern Reader at the same time")
    }
  }

  // Pattern Writer Arbitration
  // Default assignments
  io.patternWriterIo.req.valid := false.B
  io.patternWriterIo.req.bits := DontCare
  io.patternWriterIo.functionalLanes := localTxFunctionalLanes

  patternWriterClients.foreach { case (isUsing, clientIo) =>
    clientIo.resp := io.patternWriterIo.resp
    clientIo.req.ready := false.B

    // If this specific client is using the writer, route its req to the top level
    when(isUsing) {
      io.patternWriterIo.req.valid := clientIo.req.valid
      io.patternWriterIo.req.bits := clientIo.req.bits
      clientIo.req.ready := io.patternWriterIo.req.ready
    }
  }

  // Pattern Reader Arbitration
  // Default assignments
  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits := DontCare
  io.patternReaderIo.functionalLanes := remoteTxFunctionalLanes

  patternReaderClients.foreach { case (isUsing, clientIo) =>
    clientIo.resp := io.patternReaderIo.resp
    clientIo.req.ready := false.B

    when(isUsing) {
      io.patternReaderIo.req.valid := clientIo.req.valid
      io.patternReaderIo.req.bits := clientIo.req.bits
      clientIo.req.ready := io.patternReaderIo.req.ready
    }
  }

  // ==============================================================================================
  // Lane Repair Logic
  // ==============================================================================================
  // These wires get muxed in MBInit and MBTrain
  val updateLocalTxFuncLanes = WireInit(false.B)
  val updateRemoteTxFuncLanes = WireInit(false.B)
  val newLocalTxFuncLanes = WireInit("b011".U(3.W))
  val newLocalRxFuncLanes = WireInit("b011".U(3.W))


  val outOfSpareTxLanes = Wire(Bool())
  outOfSpareTxLanes := Mux(negotiatedUcieSx8.asBool,
    // If negotiated as x8: Out of spares if already degraded to x4 (100, 101) or dead (000)
    localTxFunctionalLanes === "b100".U(3.W) || 
    localTxFunctionalLanes === "b101".U(3.W) || 
    localTxFunctionalLanes === "b000".U(3.W),

    // If negotiated as x16: Out of spares if already degraded to x8 (001, 010) or dead (000)
    localTxFunctionalLanes === "b001".U(3.W) || 
    localTxFunctionalLanes === "b010".U(3.W) || 
    localTxFunctionalLanes === "b000".U(3.W)
  )

  val outOfSpareRxLanes = Wire(Bool())
  outOfSpareRxLanes := Mux(negotiatedUcieSx8.asBool,
    // If negotiated as x8: Out of spares if already degraded to x4 (100, 101) or dead (000)
    remoteTxFunctionalLanes === "b100".U(3.W) || 
    remoteTxFunctionalLanes === "b101".U(3.W) || 
    remoteTxFunctionalLanes === "b000".U(3.W),

    // If negotiated as x16: Out of spares if already degraded to x8 (001, 010) or dead (000)
    remoteTxFunctionalLanes === "b001".U(3.W) || 
    remoteTxFunctionalLanes === "b010".U(3.W) || 
    remoteTxFunctionalLanes === "b000".U(3.W)
  )


  // Update the lane repair results
  when(updateLocalTxFuncLanes) {
    localTxFunctionalLanes := newLocalTxFuncLanes    
  }
  when(updateRemoteTxFuncLanes) {
    remoteTxFunctionalLanes := newLocalRxFuncLanes    
  }
  when(currentState === LTState.sRESET) {
    localTxFunctionalLanes := "b011".U(3.W)     // default
    remoteTxFunctionalLanes := "b011".U(3.W)
  }
  io.localTxFunctionalLanes := localTxFunctionalLanes
  io.remoteTxFunctionalLanes := remoteTxFunctionalLanes

  def decodeLaneMap(code: UInt): UInt = {
    MuxLookup(code, "hFFFF".U(16.W))(Seq(
      "b000".U -> "h0000".U(16.W), // Degrade not possible (No functional lanes)
      "b001".U -> "h00FF".U(16.W), // Lanes 0 to 7
      "b010".U -> "hFF00".U(16.W), // Lanes 8 to 15
      "b011".U -> "hFFFF".U(16.W), // Lanes 0 to 15 (All lanes functional)
      "b100".U -> "h000F".U(16.W), // Lanes 0 to 3
      "b101".U -> "h00F0".U(16.W)  // Lanes 4 to 7
    ))
  }

  val txLaneMask = decodeLaneMap(localTxFunctionalLanes)
  val rxLaneMask = decodeLaneMap(remoteTxFunctionalLanes)
  
  val mbLaneCtrlIo = Wire(new MainbandLaneCtrlIO(afeParams))
  
  mbLaneCtrlIo.txDataEn.foreach(_ := false.B)
  mbLaneCtrlIo.txClkEn := false.B
  mbLaneCtrlIo.txValidEn := false.B
  mbLaneCtrlIo.txTrackEn := false.B
  mbLaneCtrlIo.rxDataEn.foreach(_ := false.B)
  mbLaneCtrlIo.rxClkEn := false.B
  mbLaneCtrlIo.rxValidEn := false.B
  mbLaneCtrlIo.rxTrackEn := false.B
  
  // Each state has default settings for the `enable` of the lanes but once lane
  // repair is applied mask the `enable` signals based on the lane repair results
  for (i <- 0 until afeParams.mbLanes) {
    io.mbCtrlIo.txDataEn(i) := mbLaneCtrlIo.txDataEn(i) && txLaneMask(i)
    io.mbCtrlIo.rxDataEn(i) := mbLaneCtrlIo.rxDataEn(i) && rxLaneMask(i)
  }
  
  io.mbCtrlIo.txClkEn := mbLaneCtrlIo.txClkEn
  io.mbCtrlIo.txValidEn := mbLaneCtrlIo.txValidEn
  io.mbCtrlIo.txTrackEn := mbLaneCtrlIo.txTrackEn
  
  io.mbCtrlIo.rxClkEn := mbLaneCtrlIo.rxClkEn
  io.mbCtrlIo.rxValidEn := mbLaneCtrlIo.rxValidEn
  io.mbCtrlIo.rxTrackEn := mbLaneCtrlIo.rxTrackEn

  // Relevant module connections:
  // MBTrain
  mbTrainSM.io.currLocalTxFunctionalLanes := localTxFunctionalLanes
  mbTrainSM.io.currRemoteTxFunctionalLanes := remoteTxFunctionalLanes

  // ==============================================================================================
  // Lane Reversal Logic
  // ==============================================================================================
  val doLaneReversal = RegInit(false.B)

  // Lane reversal is detected in MBInit
  when(mbInitSM.io.applyLaneReversal) {
    doLaneReversal := true.B
  }.elsewhen(currentState === LTState.sRESET) {
    doLaneReversal := false.B
  }

  io.doLaneReversal := doLaneReversal

  // ==============================================================================================
  // Phy Retrain Logic
  // ==============================================================================================
  val retrainSbModule = withReset(ltsmInReset) { Module(new PhyRetrainSidebandHandshake(sbParams)) }

  retrainSbModule.io.startPhyRetrainMsgExch := false.B
  retrainSbModule.io.waitForRemoteRequest := false.B

  // Sideband defaults
  retrainSbModule.io.requesterSbLaneIo.rx.valid := false.B
  retrainSbModule.io.requesterSbLaneIo.rx.bits := 
    0.U.asTypeOf(chiselTypeOf(retrainSbModule.io.requesterSbLaneIo.rx.bits))
  retrainSbModule.io.requesterSbLaneIo.tx.ready := false.B

  retrainSbModule.io.responderSbLaneIo.rx.valid := false.B
  retrainSbModule.io.responderSbLaneIo.rx.bits := 
    0.U.asTypeOf(chiselTypeOf(retrainSbModule.io.responderSbLaneIo.rx.bits))
  retrainSbModule.io.responderSbLaneIo.tx.ready := false.B

  val phyInRetrain = RegInit(false.B)
  val phyRetrainFromLinkspeed = RegInit(false.B)

  when(currentState === LTState.sRESET || mbTrainSM.io.clearPhyInRetrainFlag) {
    phyInRetrain := false.B
  }

  // MBTrain IOs     
  mbTrainSM.io.phyInRetrain := phyInRetrain

  // Table 4-10/11: Local Encoding Generation  
  val busyBit = WireInit(io.runtimeLinkCtrlBusyBit.asUInt)
  val repairNeeded = WireInit(io.runtimeRequestForRepair) 
  val repairResourcesAvailable = WireInit(outOfSpareTxLanes && outOfSpareRxLanes) 

  val localEncoding = WireInit(RetrainEncoding.TXSELFCAL) // Default for Busy = 0b

  when(busyBit === 1.U) {
    when(repairNeeded) {
      when(repairResourcesAvailable) {
        localEncoding := RetrainEncoding.REPAIR
      } .otherwise {
        localEncoding := RetrainEncoding.SPEEDIDLE
      }
    } .otherwise { // No repair needed
      localEncoding := RetrainEncoding.TXSELFCAL
    }
  }

  // Remote's Initial Retrain Encoding Bundle
  val remoteReqEncoding = retrainSbModule.io.responderRemoteRetrainEncoding
  
  val resolutionDone = RegInit(false.B)
  val resolvedEncoding = RegInit(0.U(3.W))

  // Table 4-12: (Local Die, Remote Die) -> Resolved Exit State
  val resolutionTable = Seq(
    (RetrainEncoding.TXSELFCAL, RetrainEncoding.TXSELFCAL) -> RetrainEncoding.TXSELFCAL,
    (RetrainEncoding.TXSELFCAL, RetrainEncoding.REPAIR)    -> RetrainEncoding.REPAIR,
    (RetrainEncoding.TXSELFCAL, RetrainEncoding.SPEEDIDLE) -> RetrainEncoding.SPEEDIDLE,
    
    (RetrainEncoding.REPAIR,    RetrainEncoding.TXSELFCAL) -> RetrainEncoding.REPAIR,
    (RetrainEncoding.REPAIR,    RetrainEncoding.REPAIR)    -> RetrainEncoding.REPAIR,
    (RetrainEncoding.REPAIR,    RetrainEncoding.SPEEDIDLE) -> RetrainEncoding.SPEEDIDLE,
    
    (RetrainEncoding.SPEEDIDLE, RetrainEncoding.TXSELFCAL) -> RetrainEncoding.SPEEDIDLE,
    (RetrainEncoding.SPEEDIDLE, RetrainEncoding.REPAIR)    -> RetrainEncoding.SPEEDIDLE,
    (RetrainEncoding.SPEEDIDLE, RetrainEncoding.SPEEDIDLE) -> RetrainEncoding.SPEEDIDLE
  )

  // Wait for Remote to send its encoding
  when(remoteReqEncoding.valid) {
    resolutionDone := true.B 
    
    when(remoteReqEncoding.bits === localEncoding) {
      // No conflict: Encodings match
      resolvedEncoding := localEncoding
    } .otherwise {
      // Conflict: Resolve using the Table 4-12
      resolvedEncoding := localEncoding // Fallback latch
      
      for (((loc, rem), res) <- resolutionTable) {
        when(localEncoding === loc && remoteReqEncoding.bits === rem) {
          resolvedEncoding := res
        }
      }
    }
  }

  // Responder sends resolved encoding in RESP message
  retrainSbModule.io.responderLocalRetrainEncoding.valid := resolutionDone
  retrainSbModule.io.responderLocalRetrainEncoding.bits := resolvedEncoding

  // Send the Table 4-10 result in send the REQ message
  retrainSbModule.io.requesterLocalRetrainEncoding.valid := (currentState === LTState.sPHYRETRAIN)
  retrainSbModule.io.requesterLocalRetrainEncoding.bits  := localEncoding
  
  mbTrainSM.io.goToState.valid := false.B
  mbTrainSM.io.goToState.bits := MBTrainGoToState.goToSPEEDIDLE // Default

  switch(resolvedEncoding) {
    is(RetrainEncoding.TXSELFCAL) { mbTrainSM.io.goToState.bits := MBTrainGoToState.goToTXSELFCAL }
    is(RetrainEncoding.REPAIR)    { mbTrainSM.io.goToState.bits := MBTrainGoToState.goToREPAIR }
    is(RetrainEncoding.SPEEDIDLE) { mbTrainSM.io.goToState.bits := MBTrainGoToState.goToSPEEDIDLE }
  }

  when(mbTrainSM.io.goToState.valid) {
    switch(mbTrainSM.io.goToState.bits) {
      is(MBTrainGoToState.goToREPAIR) {
        localTxFunctionalLanes := "b011".U(3.W) 
        remoteTxFunctionalLanes := "b011".U(3.W)
      }
      is(MBTrainGoToState.goToSPEEDIDLE) {
        localTxFunctionalLanes := "b011".U(3.W) 
        remoteTxFunctionalLanes := "b011".U(3.W)
      }
    }    
  }

  // ==============================================================================================
  // Error Detection Classification Logic
  // ==============================================================================================
  // TODO: Have error handling logic from the modules here
  // TODO: need to use signal from trainerror responder and likely do a reset on the modules and transition to trainerror
  // TODO: when coming into reset, we reset any state register in the LTSM
  // TODO: note we may have to let sb and mb messages through if there are pending
  // TODO: Need to make sure to classify the errors appropriately, correctable, uncorrectable fatel, uncorrectable non-fatel
  val errorDetected = Wire(Bool())  

  errorDetected := mbInitSM.io.fsmCtrl.error || mbTrainSM.io.fsmCtrl.error

  // mbInitSM.io.interoperableParamsNotFound   // TODO: (OUT) used to escalate an error (mbInit.io.fsmCtrl.error also goes high)

  val localError = Wire(Bool())
  localError := trainingTimedout || errorDetected

  // ==============================================================================================
  // TrainError Logic
  // ==============================================================================================
  val trainErrorRequester = withReset(ltsmInReset) { Module(new TrainErrorRequester(sbParams)) }
  val trainErrorResponder = withReset(ltsmInReset) { Module(new TrainErrorResponder(sbParams)) }

  // Requester Inputs
  when(localError && !waitTrainErrorResp && (currentState =/= LTState.sTRAINERROR)) {
    triggerTrainErrorReq := true.B
  }
  when(triggerTrainErrorReq) {    // Flags when the TrainError RESP is received
    waitTrainErrorResp := true.B
  } .elsewhen(trainErrorRequester.io.done || 
              currentState === LTState.sTRAINERROR || 
              currentState === LTState.sRESET) {
    waitTrainErrorResp := false.B
  }
  trainErrorRequester.io.sendReq := waitTrainErrorResp

  // Responder Inputs
  trainErrorResponder.io.wakeUp :=  (currentState =/= LTState.sRESET) && 
                                    (currentState =/= LTState.sTRAINERROR)
  trainErrorResponder.io.sendResp :=  (currentState === LTState.sTRAINERROR) && 
                                      trainErrorResponder.io.remoteRequestingTrainError &&
                                      !trainErrorResponder.io.done

  val transitionToTrainError = Wire(Bool())
  transitionToTrainError := (waitTrainErrorResp && 
                            (trainErrorRequester.io.done || trainErrorHandshakeTimedout)) || 
                            trainErrorResponder.io.remoteRequestingTrainError
  
  val responderFinished = !trainErrorResponder.io.remoteRequestingTrainError || 
                          trainErrorResponder.io.done

  val transitionToReset = Wire(Bool())
  transitionToReset := io.sbCtrlIo.allPacketsSent

  val succeededThisCycle = Wire(Bool())
  succeededThisCycle := (currentState === LTState.sLINKINIT) && (nextState === LTState.sACTIVE)

  retryCounterEn := (currentState === LTState.sTRAINERROR) &&
                    (nextState === LTState.sRESET) &&
                    trainingEpisodeActive

  autoRetrain := (currentState === LTState.sRESET) &&
                 trainingEpisodeActive &&
                 (trainingRetryCounter <= retryAmtMax)

  when(freshTrainingTrigger && (currentState === LTState.sRESET)) {
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
  }.elsewhen(retryCounterEn) {
    trainingRetryCounter := trainingRetryCounter + 1.U
    when(trainingRetryCounter === retryAmtMax) {
      trainingEpisodeActive := false.B
      when(currentEpisodeIsLocal) {
        fatalTrainingError := true.B
        forceRdiLinkError := true.B
      }
      currentEpisodeIsLocal := false.B
    }
  }

  when(fatalTrainingError && (io.rdi.plStateSts === RDIState.linkError)) {
    fatalTrainingSawLinkError := true.B
    forceRdiLinkError := false.B
  }

  when(forceRdiLinkError && (io.rdi.plStateSts === RDIState.linkError)) {
    forceRdiLinkError := false.B
  }

  when(fatalTrainingError &&
      fatalTrainingSawLinkError &&
      (io.rdi.plStateSts === RDIState.reset)) {
    fatalTrainingError := false.B
    fatalTrainingSawLinkError := false.B
  }
  
  // ==============================================================================================
  // Sideband Arbitration
  // ==============================================================================================
  // Setup
  val activeReqSbLane = Wire(new SidebandLaneIO(sbParams))
  val activeRespSbLane = Wire(new SidebandLaneIO(sbParams))

  activeReqSbLane.tx.valid := false.B
  activeReqSbLane.tx.bits := 0.U.asTypeOf(chiselTypeOf(activeReqSbLane.tx.bits))
  activeReqSbLane.rx.ready := false.B

  activeRespSbLane.tx.valid := false.B
  activeRespSbLane.tx.bits := 0.U.asTypeOf(chiselTypeOf(activeRespSbLane.tx.bits))
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
    rxEwSweepResponder.io.sbLaneIo
  )
  block(Verification) {
    block(Verification.Assert) {
      // Assert that at most one module claims to consume an RX message
      val activeReaderClients = PopCount(sidebandClients.map(_.rx.ready))
      assert(activeReaderClients <= 1.U, 
            "FATAL: Multiple modules asserting RX ready for a Sideband message at the same time")
    }
  }

  // RX Arbitration
  sidebandClients.foreach { client =>
    client.rx.valid := io.sbLaneIo.rx.valid
    client.rx.bits  := io.sbLaneIo.rx.bits
  }

  io.sbLaneIo.rx.ready := sidebandClients.map(_.rx.ready).reduce(_ || _)

  // TX Arbitration
  val txArbiter = Module(new RRArbiter(chiselTypeOf(io.sbLaneIo.tx.bits), sidebandClients.length))

  sidebandClients.zipWithIndex.foreach { case (client, index) =>
    txArbiter.io.in(index) <> client.tx
  }

  io.sbLaneIo.tx <> txArbiter.io.out
 
  // ==============================================================================================
  // State Machine
  // ==============================================================================================
  switch(currentState) {
    is(LTState.sRESET) {            
      /*  
      Default signals:
        Data, Valid, Clock TX are tri-stated (en == 0)
        Data, Valid, Clock RX are disabled (en == 0)
        Sideband TX is enabled (en == 1)
        Sideband RX is enabled (en == 1)                
        Set Mainband Clock Speed to lowest (4 GT/s)
      */    
      currentStateSpecific := LTSMState.sRESET
      ltsmInReset := true.B             // This asserts reset on submodules until out of reset
      sbRxTxMode := SBRxTxMode.RAW      
      io.sbLaneIo.rx.ready := true.B
      when(io.sbLaneIo.rx.valid) {
        when(io.sbLaneIo.rx.bits.data(63,0) === sbInitClkPattern) {
          when(sbInitPatternCounter =/= 2.U) {
            sbInitPatternCounter := sbInitPatternCounter + 1.U
          }
        }.otherwise { 
          when(sbInitPatternCounter === 1.U) { // pattern not consecutively seen, so reset counter
            sbInitPatternCounter := 0.U
          }
        }
      }

      when(io.pwrGood && io.phyCtrlIo.pllLock && resetMinWait &&
          (freshTrainingTrigger || autoRetrain)) {
        nextState := LTState.sSBINIT
        sbInitPatternCounter := 0.U
      }.otherwise {
        nextState := LTState.sRESET
      }
    }
    is(LTState.sSBINIT) {        
      // SBInit doesn't use mainband ctrl IO, so defaults are kept
      currentStateSpecific := LTSMState.sSBINIT
      sbInitSM.io.fsmCtrl.start := !trainingTimedout && !sbInitSM.io.fsmCtrl.done
      sbRxTxMode := sbInitSM.io.sbRxTxMode      
      activeReqSbLane  <> sbInitSM.io.requesterSbLaneIo
      activeRespSbLane <> sbInitSM.io.responderSbLaneIo

      when(transitionToTrainError) {
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

      updateLocalTxFuncLanes := mbInitSM.io.txWidthChanged
      updateRemoteTxFuncLanes := mbInitSM.io.remoteFunctionalLanes
      newLocalTxFuncLanes := mbInitSM.io.localFunctionalLanes         
      newLocalRxFuncLanes := mbInitSM.io.rxWidthChanged
  
      substateTransitioning := mbInitSM.io.fsmCtrl.substateTransitioning

      // Extract MBInit specific substate
      switch(mbInitSM.io.currentState) {
        is(MBInitState.sPARAM)      { currentStateSpecific := LTSMState.sMBINIT_PARAM }
        is(MBInitState.sCAL)        { currentStateSpecific := LTSMState.sMBINIT_CAL }
        is(MBInitState.sREPAIRCLK)  { currentStateSpecific := LTSMState.sMBINIT_REPAIRCLK }
        is(MBInitState.sREPAIRVAL)  { currentStateSpecific := LTSMState.sMBINIT_REPAIRVAL }
        is(MBInitState.sREVERSALMB) { currentStateSpecific := LTSMState.sMBINIT_REVERSALMB }
        is(MBInitState.sREPAIRMB)   { currentStateSpecific := LTSMState.sMBINIT_REPAIRMB }
        // Ignore TOMBTRAIN transition state: hold the last value to prevent the output from 
        // glitching to a default value
        is(MBInitState.sTOMBTRAIN)  { currentStateSpecific := LTSMState.sMBINIT_REPAIRMB }
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

      // Extract MBTrain specific substate
      switch(mbTrainSM.io.currentState) {
        is(MBTrainState.sVALVREF)          { currentStateSpecific := LTSMState.sMBTRAIN_VALVREF }
        is(MBTrainState.sDATAVREF)         { currentStateSpecific := LTSMState.sMBTRAIN_DATAVREF }
        is(MBTrainState.sSPEEDIDLE)        { currentStateSpecific := LTSMState.sMBTRAIN_SPEEDIDLE }
        is(MBTrainState.sTXSELFCAL)        { currentStateSpecific := LTSMState.sMBTRAIN_TXSELFCAL }
        is(MBTrainState.sRXCLKCAL)         { currentStateSpecific := LTSMState.sMBTRAIN_RXCLKCAL } 
        is(MBTrainState.sVALTRAINCENTER)   { currentStateSpecific := LTSMState.sMBTRAIN_VALTRAINCENTER }
        is(MBTrainState.sVALTRAINVREF)     { currentStateSpecific := LTSMState.sMBTRAIN_VALTRAINVREF }
        is(MBTrainState.sDATATRAINCENTER1) { currentStateSpecific := LTSMState.sMBTRAIN_DATATRAINCENTER1 }
        is(MBTrainState.sDATATRAINVREF)    { currentStateSpecific := LTSMState.sMBTRAIN_DATATRAINVREF }
        is(MBTrainState.sRXDESKEW)         { currentStateSpecific := LTSMState.sMBTRAIN_RXDESKEW }
        is(MBTrainState.sDATATRAINCENTER2) { currentStateSpecific := LTSMState.sMBTRAIN_DATATRAINCENTER2 }
        is(MBTrainState.sLINKSPEED)        { currentStateSpecific := LTSMState.sMBTRAIN_LINKSPEED }
        is(MBTrainState.sREPAIR)           { currentStateSpecific := LTSMState.sMBTRAIN_REPAIR }        
        // Ignore transition states: hold the last value to prevent the output from 
        // glitching to a default value
        is(MBTrainState.sTOPHYRETRAIN)     { currentStateSpecific := LTSMState.sMBTRAIN_LINKSPEED }
        is(MBTrainState.sTOLINKINIT)       { currentStateSpecific := LTSMState.sMBTRAIN_LINKSPEED }
      }
 
      when(transitionToTrainError) {
        nextState := LTState.sTRAINERROR
      }.elsewhen(mbTrainSM.io.currentState === MBTrainState.sTOPHYRETRAIN) {
        nextState := LTState.sPHYRETRAIN
        phyRetrainFromLinkspeed := true.B
      }.elsewhen(mbTrainSM.io.currentState === MBTrainState.sTOLINKINIT) {
        nextState := LTState.sLINKINIT
        io.scramblerReset := true.B    // Reset scrambler upon entering LinkInit
      }
    }
    is(LTState.sLINKINIT) {
      // Mainband Track, Data, and Valid Transmitters are held low (defaults)

      currentStateSpecific := LTSMState.sLINKINIT
      io.rdi.doRdiBringup := true.B

      when(transitionToTrainError) {
        nextState := LTState.sTRAINERROR
      }.elsewhen(io.rdi.plStateSts === RDIState.active) {   // Indicates RDI SM went to Active
        nextState := LTState.sACTIVE
      }
    } 
    is(LTState.sACTIVE) {
      currentStateSpecific := LTSMState.sACTIVE
      mbLaneCtrlIo.txDataEn.foreach(_ := true.B)
      mbLaneCtrlIo.txClkEn := true.B
      mbLaneCtrlIo.txValidEn := true.B
      mbLaneCtrlIo.txTrackEn := true.B
      mbLaneCtrlIo.rxDataEn.foreach(_ := true.B)
      mbLaneCtrlIo.rxClkEn := true.B
      mbLaneCtrlIo.rxValidEn := true.B
      mbLaneCtrlIo.rxTrackEn := true.B

      when(transitionToTrainError) {
        nextState := LTState.sTRAINERROR
      }.elsewhen(io.rdi.plStateSts === RDIState.retrain) {
        nextState := LTState.sPHYRETRAIN
      }
      // }.elsewhen() {
      //   nextState := LTState.sL1_L2
      // }
    }    
    is(LTState.sPHYRETRAIN) {
      // Mainband Track, Data, and Valid Transmitters are held low (defaults)
      currentStateSpecific := LTSMState.sPHYRETRAIN
      phyInRetrain := true.B
      
      activeReqSbLane <> retrainSbModule.io.requesterSbLaneIo
      activeRespSbLane <> retrainSbModule.io.responderSbLaneIo

      retrainSbModule.io.startPhyRetrainMsgExch := true.B
      retrainSbModule.io.waitForRemoteRequest := true.B
      
      when(retrainSbModule.io.done) { 
        resolutionDone := false.B       
        phyRetrainFromLinkspeed := false.B
        mbTrainSM.io.goToState.valid := true.B  // Logic for which state to jump to is done above
        nextState := LTState.sMBTRAIN
      }.elsewhen(transitionToTrainError) {
        resolutionDone := false.B
        phyRetrainFromLinkspeed := false.B        
        nextState := LTState.sTRAINERROR
      }
    } 
    is(LTState.sTRAINERROR) {
      /*  
      Default signals:
        Data, Valid, Clock TX are tri-stated (en == 0)
        Data, Valid, Clock RX are disabled (en == 0)
        Sideband TX is enabled (en == 1)
        Sideband RX is enabled (en == 1)                
      */

      currentStateSpecific := LTSMState.sTRAINERROR
      io.sbCtrlIo.freezeAcceptingPackets := responderFinished
      
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
}


