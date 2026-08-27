package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.util.experimental.BoringUtils
import edu.berkeley.cs.uciedigital.d2dadapter.{D2DAdapter, LinkInitState}
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.protocol._
import edu.berkeley.cs.uciedigital.sideband._

/** Bit positions in the packed observation word.
  *
  * One wide UInt rather than one port per signal: registering many scopes makes
  * the generated Verilator model fault at time zero on this toolchain.
  */
object DieFlag {
  // RDI, between the adapter and the PHY.
  val rdiInbandPres = 0
  val rdiLpReqActive = 1
  val rdiStallReq = 2
  val rdiStallAck = 3
  val rdiPlTrdy = 4
  val rdiPlValid = 5
  val rdiPlWakeAck = 6
  val phyParamsVld = 7
  val phyRecenter = 8
  val phyTrainError = 9
  val phyTimedout = 10
  val rdiPlError = 11

  // Latched sideband faults, one bit each.
  val sbParityErr = 12
  val sbRxQueuesFull = 13
  val sbDeserTimedout = 14
  val sbBadRouteUpper = 15
  val sbBadRouteCurr = 16
  val sbBadRouteLower = 17
  val sbUnhandledMsg = 18

  // FDI, between the protocol layer and the adapter.
  val fdiInbandPres = 19
  val fdiRxActiveReq = 20
  val fdiRxActiveSts = 21
  val fdiProtocolVld = 22
  val fdiStallReq = 23
  val fdiStallAck = 24
  val fdiLpReqActive = 25
  val fdiPlTrdy = 26
  val fdiPlValid = 27
  val fdiLpValid = 28

  // Protocol layer and the chip-facing interface.
  val negotiatedProto = 29
  val protoStalled = 30
  val rxOverflow = 31
  val chipTxReady = 32
  val chipRxValid = 33

  // AdapterSM link-init probes, zero when exposeAdapterProbes is false.
  val advCapSent = 34
  val advCapRcvd = 35
  val actReqSent = 36
  val actReqRcvd = 37
  val actRspSent = 38
  val actRspRcvd = 39
  val transitionToActive = 40

  val width = 64

  val sbFaults: Seq[(Int, String)] = Seq(
    sbParityErr -> "parity",
    sbRxQueuesFull -> "rxQueuesFull",
    sbDeserTimedout -> "deserTimeout",
    sbBadRouteUpper -> "badRouteUpper",
    sbBadRouteCurr -> "badRouteCurr",
    sbBadRouteLower -> "badRouteLower",
    sbUnhandledMsg -> "unhandledMsg"
  )
}

/** Bit positions in the protocol-layer control word driven by the testbench. */
object ProtoCtrl {
  val active = 0
  val retrain = 1
  val linkReset = 2
  val disable = 3

  def word(
      reqActive: Boolean = false,
      reqRetrain: Boolean = false,
      reqLinkReset: Boolean = false,
      reqDisable: Boolean = false
  ): BigInt =
    (if (reqActive) BigInt(1) << active else BigInt(0)) |
      (if (reqRetrain) BigInt(1) << retrain else BigInt(0)) |
      (if (reqLinkReset) BigInt(1) << linkReset else BigInt(0)) |
      (if (reqDisable) BigInt(1) << disable else BigInt(0))
}

/** Two full {ProtocolLayer, D2DAdapter, LogicalPhy} stacks cross-wired at the
  * analog boundary. The adapter is real, so the RDI handshakes the LogPhy
  * harness stubbed out are driven by hardware here.
  *
  * The analog macro is not modelled: pllLock and clocksUngatedAndStable are
  * tied high.
  *
  * @param exposeDataPath
  *   exposes the chip-facing data ports. Leaving them tied off lets the
  *   simulator drop the beat packing, which every test pays for across the
  *   reset wait, so only the data tests turn it on.
  * @param exposeAdapterProbes
  *   taps AdapterSM registers through BoringUtils. The taps register extra
  *   scopes in the Verilator model, so this is an escape hatch: turn it off and
  *   read the same registers from a waveform.
  */
class UcieDigitalLoopbackHarness(
    val afeParams: AfeParams = new AfeParams(),
    val sbParams: SidebandParams = new SidebandParams(),
    val rdiParams: RdiParams = RdiParams(64, 32),
    val fdiParams: FdiParams = FdiParams(64, 32),
    val protocolParams: ProtocolLayerParams = new ProtocolLayerParams(),
    val exposeDataPath: Boolean = false,
    val exposeAdapterProbes: Boolean = true
) extends Module {
  val beatBits = fdiParams.nBytes * 8

  val io = IO(new Bundle {
    val swStartLinkTraining = Input(Vec(2, Bool()))
    val pwrGood = Input(Vec(2, Bool()))
    val protoCtrl = Input(Vec(2, UInt(4.W)))

    val ltState = Output(Vec(2, LTState()))
    val ltsmState = Output(Vec(2, LTSMState()))
    val rdiState = Output(Vec(2, RDIState()))
    val fdiState = Output(Vec(2, FDIState()))
    val adapterLinkInit = Output(Vec(2, LinkInitState()))
    val flags = Output(Vec(2, UInt(DieFlag.width.W)))

    val txValid = Option.when(exposeDataPath)(Input(Vec(2, Bool())))
    val txData = Option.when(exposeDataPath)(Input(Vec(2, UInt(beatBits.W))))
    val rxReady = Option.when(exposeDataPath)(Input(Vec(2, Bool())))
    val rxData = Option.when(exposeDataPath)(Output(Vec(2, UInt(beatBits.W))))
  })

  val phys = Seq.fill(2)(
    Module(
      new LogicalPhy(
        afeParams = afeParams,
        sbParams = sbParams,
        rdiParams = rdiParams
      )
    )
  )
  val adapters =
    Seq.fill(2)(Module(new D2DAdapter(fdiParams, rdiParams, sbParams)))
  val protocols = Seq.fill(2)(
    Module(new ProtocolLayer(protocolParams, fdiParams, sbParams))
  )

  for (i <- 0 until 2) {
    val phy = phys(i).io
    val peer = phys(1 - i).io
    val adapter = adapters(i).io
    val proto = protocols(i).io

    phy.analog.sidebandLink.in.bits := peer.analog.sidebandLink.out.bits
    phy.analog.sidebandLink.in.fwClock := peer.analog.sidebandLink.out.fwClock

    phy.analog.mainband.rx.bits := peer.analog.mainband.tx.bits
    phy.analog.mainband.rx.valid := peer.analog.mainband.tx.valid
    phy.analog.mainband.tx.ready := peer.analog.mainband.rx.ready

    phy.analog.status.pllLock := true.B
    phy.analog.status.clocksUngatedAndStable := true.B

    // The whole stack in two lines, as UcieDigitalTop wires it. Driving any FDI
    // signal here as well would win by last connect and make the protocol layer
    // a decoration.
    proto.fdi <> adapter.fdi
    adapter.rdi <> phy.rdi

    // Error reporting is enabled by these, so tying them low would gate FDI
    // error reporting off rather than leave it at its default.
    adapter.regs.corrProtoReport := true.B
    adapter.regs.nonFatalProtoReport := true.B
    adapter.regs.fatalProtoReport := true.B

    // Identical parameters on both dies so PARAM interoperates, and no retries
    // because a retry costs another full timeout.
    phy.ctrl.pwrGood := io.pwrGood(i)
    phy.ctrl.swStartLinkTraining := io.swStartLinkTraining(i)
    phy.ctrl.retryTrainingAmt := 0.U
    phy.ctrl.maxErrorThresholdPerLane := 0.U
    phy.ctrl.changeInRuntimeLinkCtrlRegsDetected := false.B
    phy.ctrl.runtimeLinkCtrlBusyBit := false.B
    phy.ctrl.runtimeRequestForRepair := false.B
    phy.ctrl.swRetrainRequest := false.B
    phy.ctrl.linkOpParamOverride := false.B
    phy.ctrl.clockPhaseSelect := 0.U

    phy.ctrl.localPhyParamSettings.valid := true.B
    phy.ctrl.localPhyParamSettings.bits.voltageSwing := 0.U
    phy.ctrl.localPhyParamSettings.bits.maxDataRate := 0.U
    phy.ctrl.localPhyParamSettings.bits.clockMode := 0.U
    phy.ctrl.localPhyParamSettings.bits.clockPhase := 0.U
    phy.ctrl.localPhyParamSettings.bits.ucieSx8 := 0.U
    phy.ctrl.localPhyParamSettings.bits.sbFeatExt := 0.U
    phy.ctrl.localPhyParamSettings.bits.txAdjRuntime := 0.U
    phy.ctrl.localPhyParamSettings.bits.moduleId := 0.U

    phy.ctrl.linkTrainingParameters.clockPhase := 0.U
    phy.ctrl.linkTrainingParameters.dataPattern := 0.U
    phy.ctrl.linkTrainingParameters.validPattern := 0.U
    phy.ctrl.linkTrainingParameters.patternMode := 0.U
    phy.ctrl.linkTrainingParameters.iterationCount := 0.U
    phy.ctrl.linkTrainingParameters.idleCount := 0.U
    phy.ctrl.linkTrainingParameters.burstCount := 0.U
    phy.ctrl.linkTrainingParameters.maxErrorThreshold := 0.U
    phy.ctrl.linkTrainingParameters.comparisonMode := 0.U

    // The protocol layer decides when to present Active, because the adapter
    // edge-detects nop to active and only inside its bring-up window. A level
    // held from reset consumes the edge before that window opens.
    proto.ctrl.requestActive := io.protoCtrl(i)(ProtoCtrl.active)
    proto.ctrl.requestRetrain := io.protoCtrl(i)(ProtoCtrl.retrain)
    proto.ctrl.requestLinkReset := io.protoCtrl(i)(ProtoCtrl.linkReset)
    proto.ctrl.requestDisable := io.protoCtrl(i)(ProtoCtrl.disable)

    proto.mainbandTx.valid := io.txValid.map(_(i)).getOrElse(false.B)
    proto.mainbandTx.bits.data := io.txData.map(_(i)).getOrElse(0.U)
    proto.mainbandRx.ready := io.rxReady.map(_(i)).getOrElse(false.B)
    io.rxData.foreach(_(i) := proto.mainbandRx.bits.data)

    io.ltState(i) := phy.status.ltState
    io.ltsmState(i) := phy.status.currentState
    io.rdiState(i) := phy.rdi.plStateSts
    io.fdiState(i) := adapter.fdi.plStateSts

    val f = Wire(Vec(DieFlag.width, Bool()))
    f.foreach(_ := false.B)

    f(DieFlag.rdiInbandPres) := phy.rdi.plInbandPres
    f(DieFlag.rdiLpReqActive) := adapter.rdi.lpStateReq === RDIStateReq.active
    f(DieFlag.rdiStallReq) := phy.rdi.plStallReq
    f(DieFlag.rdiStallAck) := adapter.rdi.lpStallAck
    f(DieFlag.rdiPlTrdy) := phy.rdi.plTrdy
    f(DieFlag.rdiPlValid) := phy.rdi.plValid
    f(DieFlag.rdiPlWakeAck) := phy.rdi.plWakeAck
    f(DieFlag.phyParamsVld) := phy.status.negotiatedPhyParamSettings.valid
    f(DieFlag.phyRecenter) := phy.rdi.plPhyInRecenter
    f(DieFlag.phyTrainError) := phy.rdi.plTrainError
    f(DieFlag.phyTimedout) := phy.status.trainingTimedout
    f(DieFlag.rdiPlError) := phy.rdi.plError

    f(DieFlag.sbParityErr) := phy.status.sideband.sbParityErrSeen
    f(DieFlag.sbRxQueuesFull) := phy.status.sideband.sbRxPriorityQueuesFullSeen
    f(DieFlag.sbDeserTimedout) := phy.status.sideband.sbDeserializerTimedoutSeen
    f(DieFlag.sbBadRouteUpper) := phy.status.sideband.sbInvalidRouteUpperSeen
    f(DieFlag.sbBadRouteCurr) := phy.status.sideband.sbInvalidRouteCurrSeen
    f(DieFlag.sbBadRouteLower) := phy.status.sideband.sbInvalidRouteLowerSeen
    f(
      DieFlag.sbUnhandledMsg
    ) := phy.status.sideband.sbUnhandledCurrentLayerMsgSeen

    f(DieFlag.fdiInbandPres) := adapter.fdi.plInbandPres
    f(DieFlag.fdiRxActiveReq) := adapter.fdi.plRxActiveReq
    f(DieFlag.fdiRxActiveSts) := proto.fdi.lpRxActiveSts
    f(DieFlag.fdiProtocolVld) := adapter.fdi.plProtocolVld
    f(DieFlag.fdiStallReq) := adapter.fdi.plStallReq
    f(DieFlag.fdiStallAck) := proto.fdi.lpStallAck
    f(DieFlag.fdiLpReqActive) := proto.fdi.lpStateReq === FDIStateReq.active
    f(DieFlag.fdiPlTrdy) := adapter.fdi.plTrdy
    f(DieFlag.fdiPlValid) := adapter.fdi.plValid
    f(DieFlag.fdiLpValid) := proto.fdi.lpValid

    f(DieFlag.negotiatedProto) := proto.status.negotiatedProtocolValid
    f(DieFlag.protoStalled) := proto.status.stalled
    f(DieFlag.rxOverflow) := proto.status.rxOverflow
    f(DieFlag.chipTxReady) := proto.mainbandTx.ready
    f(DieFlag.chipRxValid) := proto.mainbandRx.valid

    // advCapSent and advCapRcvd are a live view of PARAM_EXCH, not a record
    // that it happened: the adapter clears both on leaving that sub-state.
    if (exposeAdapterProbes) {
      val sm = adapters(i).linkManager
      io.adapterLinkInit(i) := BoringUtils.tapAndRead(sm.linkInitStateReg)
      f(DieFlag.advCapSent) := BoringUtils.tapAndRead(sm.paramExchSbMsgSntFlag)
      f(DieFlag.advCapRcvd) := BoringUtils.tapAndRead(sm.paramExchSbMsgRcvFlag)
      f(DieFlag.actReqSent) := BoringUtils.tapAndRead(sm.activeSbMsgExtReqReg)
      f(DieFlag.actReqRcvd) := BoringUtils.tapAndRead(sm.activeSbMsgReqRcvFlag)
      f(DieFlag.actRspSent) := BoringUtils.tapAndRead(sm.activeSbMsgExtRspReg)
      f(DieFlag.actRspRcvd) := BoringUtils.tapAndRead(sm.activeSbMsgRspRcvFlag)
      f(DieFlag.transitionToActive) :=
        BoringUtils.tapAndRead(sm.transitionToActiveReg)
    } else {
      io.adapterLinkInit(i) := LinkInitState.INIT_START
    }

    io.flags(i) := f.asUInt
  }
}
