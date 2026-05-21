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

class LogicalPhyCtrlIO(retryW: Int) extends Bundle {
  val pwrGood = Input(Bool())
  val retryTrainingAmt = Input(UInt(retryW.W))
  val localPhyParamSettings = Flipped(Valid(new PHYParamExchangeIO()))
  val linkTrainingParameters = new LinkOperationParameters()
  val swStartLinkTraining = Input(Bool())
  val maxErrorThresholdPerLane = Input(UInt(16.W))
  val changeInRuntimeLinkCtrlRegsDetected = Input(Bool())
  val runtimeLinkCtrlBusyBit = Input(Bool())
  val runtimeRequestForRepair = Input(Bool())
}

class LogicalPhyStatusIO extends Bundle {
  val ltState = Output(LTState())
  val currentState = Output(LTSMState())
  val trainingTimedout = Output(Bool())
  val negotiatedPhyParamSettings = Valid(new PHYParamExchangeIO())
  val sideband = new LogicalPhySidebandStatusIO()
}

class LogicalPhyAnalogIO(afeParams: AfeParams, sbParams: SidebandParams) extends Bundle {
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
  queueDepths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths()
) extends Module {
  // Current integration target is Standard Package operation in Streaming RAW mode only.
  val io = IO(new Bundle {
    val rdi = new Rdi(rdiParams)
    val ctrl = new LogicalPhyCtrlIO(retryW)
    val status = new LogicalPhyStatusIO()
    val analog = new LogicalPhyAnalogIO(afeParams, sbParams)
  })

  val ltsm = Module(new LinkTrainingSM(sbParams, afeParams, retryW))
  val rdiController = Module(new RDIController(sbParams))
  val mainbandLaneController = Module(new MainbandLaneController(afeParams, rdiParams))
  val patternReader = Module(new PatternReader(afeParams))
  val patternWriter = Module(new PatternWriter(afeParams))
  val phyControlTranslator = Module(new PhyControlSignalTranslator(afeParams))
  val phyLaneTrainer = Module(new PhyLaneTrainer(afeParams))
  val scrambler = Module(new UcieLFSR(afeParams))
  val descrambler = Module(new UcieLFSR(afeParams))
  val logPhySidebandChannel = withReset(reset.asBool || ltsm.io.sbCtrlIo.sbReset) {
    Module(new LogPhySidebandChannel(
      sbMsgWidth = sbParams.sbNodeMsgWidth,
      sbLinkWidth = sbParams.sbLinkWidth,
      rdiNcWidth = rdiParams.ncWidth,
      numCredits = sbParams.maxCrd,
      desTimeoutCycles = desTimeoutCycles,
      queueDepths = queueDepths
    ))
  }

  // ============================================================================================
  // Control/status wiring
  // ============================================================================================
  ltsm.io.pwrGood := io.ctrl.pwrGood
  ltsm.io.retryTrainingAmt := io.ctrl.retryTrainingAmt
  ltsm.io.localPhyParamSettings <> io.ctrl.localPhyParamSettings
  ltsm.io.linkTrainingParameters <> io.ctrl.linkTrainingParameters
  ltsm.io.swStartLinkTraining := io.ctrl.swStartLinkTraining
  ltsm.io.maxErrorThresholdPerLane := io.ctrl.maxErrorThresholdPerLane
  ltsm.io.changeInRuntimeLinkCtrlRegsDetected := io.ctrl.changeInRuntimeLinkCtrlRegsDetected
  ltsm.io.runtimeLinkCtrlBusyBit := io.ctrl.runtimeLinkCtrlBusyBit
  ltsm.io.runtimeRequestForRepair := io.ctrl.runtimeRequestForRepair

  io.status.ltState := ltsm.io.ltState
  io.status.currentState := ltsm.io.currentState
  io.status.trainingTimedout := ltsm.io.trainingTimedout
  io.status.negotiatedPhyParamSettings := ltsm.io.negotiatedPhyParamSettings

  phyLaneTrainer.io.phyTrainIo <> ltsm.io.phyTrainIo

  // ============================================================================================
  // RDI control and sideband cfg path
  // ============================================================================================
  val phyInRecenter =
    (ltsm.io.ltState === LTState.sSBINIT) ||
    (ltsm.io.ltState === LTState.sMBINIT) ||
    (ltsm.io.ltState === LTState.sMBTRAIN) ||
    (ltsm.io.ltState === LTState.sPHYRETRAIN)

  rdiController.io.rdi.lpStateReq := io.rdi.lpStateReq
  rdiController.io.rdi.lpWakeReq := io.rdi.lpWakeReq
  rdiController.io.rdi.lpClkAck := io.rdi.lpClkAck
  rdiController.io.rdi.lpStallAck := io.rdi.lpStallAck
  rdiController.io.ltsmState := ltsm.io.ltState
  rdiController.io.doRdiBringup := ltsm.io.rdi.doRdiBringup
  ltsm.io.rdi.doingRdiBringUp := rdiController.io.doingRdiBringup
  rdiController.io.trainingTimeout := ltsm.io.trainingTimedout || ltsm.io.forceRdiLinkError
  rdiController.io.plPhyInRecenter := phyInRecenter
  rdiController.io.cfgSidebandActive := logPhySidebandChannel.io.rdi.activity
  rdiController.io.clocksUngatedAndStable := phyControlTranslator.io.toDigital.clocksUngatedAndStable

  ltsm.io.rdi.plStateSts := rdiController.io.rdi.plStateSts
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
  val sidebandRxQueue = Module(new Queue(UInt(sbParams.sbNodeMsgWidth.W), 1, pipe = true, flow = false))
  sidebandRxQueue.io.enq <> logPhySidebandChannel.io.layer.out

  val sidebandRxReadyLtsm = WireDefault(false.B)
  val sidebandRxReadyRdi = WireDefault(false.B)
  val sidebandRxUnhandled = WireDefault(false.B)

  ltsm.io.sbLaneIo.rx.valid := sidebandRxQueue.io.deq.valid
  ltsm.io.sbLaneIo.rx.bits.data := sidebandRxQueue.io.deq.bits
  sidebandRxReadyLtsm := ltsm.io.sbLaneIo.rx.ready

  rdiController.io.sbLaneIo.rx.valid := sidebandRxQueue.io.deq.valid
  rdiController.io.sbLaneIo.rx.bits.data := sidebandRxQueue.io.deq.bits
  sidebandRxReadyRdi := rdiController.io.sbLaneIo.rx.ready

  sidebandRxUnhandled := sidebandRxQueue.io.deq.valid && !sidebandRxReadyLtsm && !sidebandRxReadyRdi

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
      assert(PopCount(Seq(sidebandRxReadyLtsm, sidebandRxReadyRdi)) <= 1.U,
        "FATAL: Multiple LogicalPhy sideband consumers asserted RX ready in the same cycle")
    }
    block(Verification.Cover) {
      cover(sidebandRxUnhandled)
    }
  }

  sidebandRxQueue.io.deq.ready := sidebandRxReadyLtsm || sidebandRxReadyRdi || sidebandRxUnhandled

  val sidebandTxArbiter = Module(new RRArbiter(UInt(sbParams.sbNodeMsgWidth.W), 2))
  sidebandTxArbiter.io.in(0).valid := ltsm.io.sbLaneIo.tx.valid
  sidebandTxArbiter.io.in(0).bits := ltsm.io.sbLaneIo.tx.bits.data
  ltsm.io.sbLaneIo.tx.ready := sidebandTxArbiter.io.in(0).ready

  sidebandTxArbiter.io.in(1).valid := rdiController.io.sbLaneIo.tx.valid
  sidebandTxArbiter.io.in(1).bits := rdiController.io.sbLaneIo.tx.bits.data
  rdiController.io.sbLaneIo.tx.ready := sidebandTxArbiter.io.in(1).ready

  val sidebandTxQueue = Module(new Queue(UInt(sbParams.sbNodeMsgWidth.W), 1, pipe = true, flow = false))
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
  phyControlTranslator.io.fromDigital.clockPhaseSelect := 0.U
  phyControlTranslator.io.fromDigital.doElectricalIdleTx := ltsm.io.phyCtrlIo.doElectricalIdleTx
  phyControlTranslator.io.fromDigital.doElectricalIdleRx := ltsm.io.phyCtrlIo.doElectricalIdleRx
  phyControlTranslator.io.fromPhy := io.analog.status
  io.analog.ctrl := phyControlTranslator.io.toPhy

  ltsm.io.phyCtrlIo.pllLock := phyControlTranslator.io.toDigital.pllLock

  // TODO: Route this to a future digital power-management / clock-control block.
  dontTouch(rdiController.io.ungateClocks)

  // ============================================================================================
  // Scramblers/Descrambler
  // ============================================================================================
  patternWriter.io.txLfsrCtrl.pattern := scrambler.io.lfsrOutput
  patternReader.io.rxLfsrCtrl.pattern := descrambler.io.lfsrOutput

  val isActive = ltsm.io.ltState === LTState.sACTIVE
  val txTrainingLfsrActive = patternWriter.io.txLfsrCtrl.valid
  val txRuntimeIncrement = io.analog.mainband.tx.valid && io.analog.mainband.tx.ready && isActive
  val scramblerIncrement = Mux(
    txTrainingLfsrActive,
    patternWriter.io.txLfsrCtrl.increment,
    txRuntimeIncrement
  )
  val scramblerReset = ltsm.io.scramblerReset || patternWriter.io.txLfsrCtrl.resetLfsr

  scrambler.io.increment := VecInit(Seq.fill(afeParams.mbLanes)(scramblerIncrement))
  scrambler.io.resetLfsr := VecInit(Seq.fill(afeParams.mbLanes)(scramblerReset))

  val rxRuntimeIncrement = io.analog.mainband.rx.valid && io.analog.mainband.rx.ready && isActive
  val descramblerIncrement = Mux(
    isActive,
    rxRuntimeIncrement,
    patternReader.io.rxLfsrCtrl.increment
  )
  val descramblerReset = ltsm.io.scramblerReset || patternReader.io.rxLfsrCtrl.resetLfsr

  descrambler.io.increment := VecInit(Seq.fill(afeParams.mbLanes)(descramblerIncrement))
  descrambler.io.resetLfsr := VecInit(Seq.fill(afeParams.mbLanes)(descramblerReset))

  // ============================================================================================
  // Pattern engine and runtime mainband path
  // ============================================================================================
  patternWriter.io.interfaceIo <> ltsm.io.patternWriterIo
  patternReader.io.interfaceIo <> ltsm.io.patternReaderIo

  val rawRxLaneBits = Wire(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
  rawRxLaneBits := Mux(
    io.analog.mainband.rx.valid,
    io.analog.mainband.rx.bits,
    0.U.asTypeOf(chiselTypeOf(io.analog.mainband.rx.bits))
  )
  patternReader.io.mbRxLaneIo := rawRxLaneBits

  mainbandLaneController.io.rdi.tx.lpIrdy := io.rdi.lpIrdy && isActive
  mainbandLaneController.io.rdi.tx.lpValid := io.rdi.lpValid && isActive
  mainbandLaneController.io.rdi.tx.lpData := io.rdi.lpData
  mainbandLaneController.io.ctrl.localTxFunctionalLanes := ltsm.io.localTxFunctionalLanes
  mainbandLaneController.io.ctrl.localRxFunctionalLanes := ltsm.io.remoteTxFunctionalLanes
  mainbandLaneController.io.mbLanes.tx.ready := io.analog.mainband.tx.ready && isActive

  val activeTxLaneMask = PatternLaneMap.decodeLaneMap(ltsm.io.localTxFunctionalLanes, afeParams.mbLanes)
  val activeRxLaneMask = PatternLaneMap.decodeLaneMap(ltsm.io.remoteTxFunctionalLanes, afeParams.mbLanes)

  val scrambledTxBits = Wire(chiselTypeOf(mainbandLaneController.io.mbLanes.tx.bits))
  scrambledTxBits := mainbandLaneController.io.mbLanes.tx.bits
  for (lane <- 0 until afeParams.mbLanes) {
    scrambledTxBits.data(lane) := Mux(
      activeTxLaneMask(lane),
      mainbandLaneController.io.mbLanes.tx.bits.data(lane) ^ scrambler.io.lfsrOutput(lane),
      0.U
    )
  }

  val txLaneReversalEnabled = ltsm.io.doLaneReversal

  val descrambledRxBits = Wire(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
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
  io.analog.mainband.rx.ready := Mux(isActive, mainbandLaneController.io.mbLanes.rx.ready, true.B)

  rdiController.io.validFramingError := mainbandLaneController.io.ctrl.validFramingError

  // In Streaming RAW mode, framing corruption in ACTIVE triggers pl_error and
  // the data path is stalled until retrain completes and LTSM returns to ACTIVE.
  val plErrorPulse = isActive && mainbandLaneController.io.ctrl.validFramingError
  val suppressPlValidAfterError = RegInit(false.B)
  val prevIsActive = RegNext(isActive, false.B)

  when(plErrorPulse) {
    suppressPlValidAfterError := true.B
  }.elsewhen(suppressPlValidAfterError && !prevIsActive && isActive) {
    suppressPlValidAfterError := false.B
  }


  // Clk Calibrate pattern for training, constant pattern so put here
  val fwClkPPattern = "b01010101".U(8.W)
  val fwClkNPattern = "b10101010".U(8.W)
  val fwClkPBits = Wire(UInt(afeParams.mbSerializerRatio.W))
  val fwClkNBits = Wire(UInt(afeParams.mbSerializerRatio.W))
  fwClkPBits := VecInit(Seq.tabulate(afeParams.mbSerializerRatio)(i => fwClkPPattern(i % 8))).asUInt
  fwClkNBits := VecInit(Seq.tabulate(afeParams.mbSerializerRatio)(i => fwClkNPattern(i % 8))).asUInt

  val rxClkCalOverride = ltsm.io.rxClkCalSendFwClkPattern && ltsm.io.rxClkCalSendTrkPattern
  val trainingPatternTxActive =
    ((ltsm.io.ltState === LTState.sMBINIT) || (ltsm.io.ltState === LTState.sMBTRAIN)) &&
    patternWriter.io.mbTxLaneIo.valid
  val rxClkCalTxBits = Wire(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
  rxClkCalTxBits.data.foreach(_ := 0.U)
  rxClkCalTxBits.valid := 0.U
  rxClkCalTxBits.clkP := Mux(ltsm.io.rxClkCalSendFwClkPattern, fwClkPBits, 0.U)
  rxClkCalTxBits.clkN := Mux(ltsm.io.rxClkCalSendFwClkPattern, fwClkNBits, 0.U)
  rxClkCalTxBits.trk := Mux(ltsm.io.rxClkCalSendTrkPattern, fwClkPBits, 0.U)

  // Lane reversal 
  val selectedTxBits = Wire(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
  selectedTxBits := 0.U.asTypeOf(chiselTypeOf(selectedTxBits))
  val reversedSelectedTxBits = Wire(chiselTypeOf(selectedTxBits))
  reversedSelectedTxBits := selectedTxBits
  for (lane <- 0 until afeParams.mbLanes) {
    reversedSelectedTxBits.data(lane) := selectedTxBits.data((afeParams.mbLanes - 1) - lane)
  }

  io.analog.mainband.tx.bits := 0.U.asTypeOf(new MainbandLanes(
    afeParams.mbLanes,
    afeParams.mbSerializerRatio
  ))
  io.analog.mainband.tx.valid := false.B

  when(isActive) {
    selectedTxBits := scrambledTxBits
    io.analog.mainband.tx.valid := mainbandLaneController.io.mbLanes.tx.valid
  }.elsewhen(rxClkCalOverride) {
    selectedTxBits := rxClkCalTxBits
    io.analog.mainband.tx.valid := true.B
  }.elsewhen((ltsm.io.ltState === LTState.sMBINIT) || (ltsm.io.ltState === LTState.sMBTRAIN)) {
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
        assert(io.analog.mainband.tx.ready,
          "FATAL: LogicalPhy training TX path assumes the analog PHY is ready")
      }
      when(trainingPatternTxActive) {
        assert(io.analog.mainband.tx.ready,
          "FATAL: PatternWriter training path assumes the analog PHY is ready")
      }
    }
  }

  // ============================================================================================
  // RDI outputs
  // ============================================================================================
  val negotiatedBy8 =
    ltsm.io.negotiatedPhyParamSettings.valid &&
    ltsm.io.negotiatedPhyParamSettings.bits.ucieSx8.asBool

  val linkWidth = Wire(LinkWidth())
  linkWidth := LinkWidth.x16
  switch(ltsm.io.localTxFunctionalLanes) {
    is("b001".U) { linkWidth := LinkWidth.x8 }
    is("b010".U) { linkWidth := LinkWidth.x8 }
    is("b100".U) { linkWidth := LinkWidth.x4 }
    is("b101".U) { linkWidth := LinkWidth.x4 }
    is("b011".U) { linkWidth := Mux(negotiatedBy8, LinkWidth.x8, LinkWidth.x16) }
  }

  io.rdi.plTrdy := Mux(isActive, mainbandLaneController.io.rdi.tx.plTrdy, false.B)
  io.rdi.plValid := Mux(
    isActive && !suppressPlValidAfterError,
    mainbandLaneController.io.rdi.rx.plValid,
    false.B
  )
  io.rdi.plData := Mux(isActive, mainbandLaneController.io.rdi.rx.plData, 0.U)
  io.rdi.plStateSts := rdiController.io.rdi.plStateSts
  io.rdi.plInbandPres := rdiController.io.rdi.plInbandPres
  io.rdi.plStallReq := rdiController.io.rdi.plStallReq
  io.rdi.plClkReq := rdiController.io.rdi.plClkReq
  io.rdi.plWakeAck := rdiController.io.rdi.plWakeAck
  io.rdi.plSpeedmode := ltsm.io.phyCtrlIo.freqSel
  io.rdi.plLnkCfg := linkWidth
  io.rdi.plNfError := false.B
  io.rdi.plTrainError := ltsm.io.fatalTrainingError
  io.rdi.plPhyInRecenter := phyInRecenter
  io.rdi.plError := plErrorPulse
  io.rdi.plCError := false.B
  io.rdi.plMaxSpeedmode := false.B
}
