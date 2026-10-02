package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.util.MixedVec
import chisel3.util.experimental.BoringUtils
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.sideband._
import scala.language.reflectiveCalls

// Two MultiModulePhy instances cross-wired at the analog boundary, so every
// training exchange and every mainband byte travels the real digital path.
//
// The cross-wire runs through a Module ID permutation: die 0's Module at index
// m faces die 1's Module at index modulePairing(m). Because each Module
// advertises its index as its Module ID during MBINIT.PARAM, a non-identity
// pairing is exactly the spec Figure 4-44 case where the remote Link partner's
// Module ID differs, and it is what forces the MMPL to rank its transmit bytes
// by the remote ID rather than the local one.
//
// The analog macro is not modelled: pllLock and clocksUngatedAndStable are tied
// high, as in LogPhyLoopbackHarness (pllLock unless moduleFaultInjection holds
// it low).
//
// @param modulePairing
//   must be an involution, so that wiring both dies with the same rule agrees.
// @param dataPath
//   exposes the aggregate RDI data ports. Leaving them tied off lets the
//   simulator drop the byte swizzle, which is worth a lot of cycles on the
//   tests that only climb the training ladder.
// @param laneErrorInjection
//   adds a per-Module switch that corrupts Lane 0 of that Module's receive data
//   while it sits in MBTRAIN.LINKSPEED. That is the only way to make a Module
//   report errors on a loopback where every Lane is perfect, and it is what
//   drives the MMPL resolution down its degrade and disable arcs. The corruption
//   is gated on the substate inside the harness so it cannot derail an earlier
//   one.
class MmplLoopbackHarness(
    val params: MmplParams = MmplParams(numModules = 2),
    val sbParams: SidebandParams = new SidebandParams(),
    val modulePairing: Seq[Int] = Seq(1, 0),
    val dataPath: Boolean = false,
    val laneErrorInjection: Boolean = false,
    // Like laneErrorInjection, but inside MBINIT.REPAIRMB: a Lane fault in one
    // direction of one Module, which is where a Standard Package Module first
    // width degrades (spec 4.5.3.3.6).
    val repairMbLaneErrorInjection: Boolean = false,
    // Inverts a Module's received Valid Lane while it is ACTIVE: a Valid
    // framing error, which the Physical Layer answers with a retrain of its
    // own (spec 4.5.3.7.2).
    val validErrorInjection: Boolean = false,
    // Per-Module faults that stop a Module training at all: its receive
    // sideband cut, or its PLL never locking so it never leaves RESET. Spec
    // 4.7.1 has the MMPL degrade the Link around a Module that fails to train.
    val moduleFaultInjection: Boolean = false,
    // Exposes lp_linkerror per die (spec 10.3.3.7); tied low otherwise.
    val linkErrorInjection: Boolean = false,
    // Training retries per episode (LogicalPhyCtrlIO.retryTrainingAmt).
    val retryTrainingAmt: Int = 0,
    val timeoutCyclesOverride: Option[Int] = None,
    // Extra sideband receive latency per die and Module, in cycles. Modules of
    // one Link are free to have sideband links of different latency, and the
    // two die then resolve MBTRAIN.LINKSPEED at different times.
    val sidebandRxDelayCycles: Seq[Seq[Int]] = Seq(),
    // Adds a per-Module switch that back-pressures that Module's mainband
    // transmitter while it sits in MBTRAIN.LINKSPEED, so its pattern generator
    // makes no progress and its Step 2 point test stays open for as long as
    // the switch is held. Gated on the substate like laneErrorInjection.
    val mainbandStallInjection: Boolean = false,
    // Counts, per die and Module, the {MBTRAIN.LINKSPEED exit to phy retrain
    // req} and {... resp} packets that Module put on its own sideband, tapped
    // where they leave its LogicalPhy.
    val exitToPhyRetrainProbe: Boolean = false,
    // Builds this die as SpecLiteralMultiModulePhy: its Modules neither follow
    // the MMPL's PHY retrain directive nor take the partner's {PHYRETRAIN.retrain
    // start req} as a cue while in MBTRAIN.LINKSPEED, as a die that follows
    // spec 4.5.3.4.12 literally on the side that sent the exit req.
    val specLiteralSenderDie: Option[Int] = None
) extends Module {
  private val n = params.numModules
  private val rdiParams = params.rdiParams(32)
  private val rdiWordBits = rdiParams.nBytes * 8

  require(
    modulePairing.length == n && modulePairing.sorted == (0 until n),
    s"modulePairing must be a permutation of 0 until $n, got $modulePairing"
  )
  require(
    (0 until n).forall(m => modulePairing(modulePairing(m)) == m),
    s"modulePairing must be an involution so both dies wire the same, got $modulePairing"
  )

  val io = IO(new Bundle {
    val lpStateReq = Input(Vec(2, RDIStateReq()))
    val swStartLinkTraining = Input(Vec(2, Bool()))
    val pwrGood = Input(Vec(2, Bool()))
    // Runtime Link Test Control register change, per die and Module, which in
    // MBTRAIN.LINKSPEED with PHY_IN_RETRAIN set is the trigger for a PHY
    // retrain from LINKSPEED (spec 4.5.3.4.12 Step 4a).
    val changeInRuntimeLinkCtrlRegs = Input(Vec(2, Vec(n, Bool())))

    // Per Module, because a multi-module Link can have Modules in different
    // states before the MMPL resolves them.
    val ltState = Output(Vec(2, Vec(n, LTState())))
    val ltsmState = Output(Vec(2, Vec(n, LTSMState())))
    val trainingTimedout = Output(Vec(2, Vec(n, Bool())))
    val negotiatedParamsValid = Output(Vec(2, Vec(n, Bool())))
    val remoteModuleId = Output(Vec(2, Vec(n, UInt(2.W))))
    val moduleEnable = Output(Vec(2, Vec(n, Bool())))
    // Spec 4.7.1: every Module of a multi-module Link must run at the same
    // width, so a width degrade has to reach the Modules that found no errors
    // of their own as well.
    val moduleLinkWidth = Output(Vec(2, Vec(n, LinkWidth())))
    // Table 4-9 Lane maps a Module transmits and receives on.
    val moduleTxLanes = Output(Vec(2, Vec(n, UInt(3.W))))
    val moduleRxLanes = Output(Vec(2, Vec(n, UInt(3.W))))
    // What the MMPL last directed the Link to do, for tests that need to see
    // the resolution rather than only its effect.
    val mmplResolution = Output(Vec(2, MmplResolution()))

    // Aggregate RDI, one per die.
    val plStateSts = Output(Vec(2, RDIState()))
    val plInbandPres = Output(Vec(2, Bool()))
    val plTrainError = Output(Vec(2, Bool()))
    val plLnkCfg = Output(Vec(2, LinkWidth()))
    val plSpeedmode = Output(Vec(2, SpeedMode()))
    val plTrdy = Output(Vec(2, Bool()))
    val plValid = Output(Vec(2, Bool()))
    val sbFaultSeen = Output(Vec(2, Bool()))
    // Which Module latched a fault, and the header of the first packet that
    // caused one, so a test can name the message rather than just the die.
    val sbUnhandledSeen = Output(Vec(2, Vec(n, Bool())))
    val sbFirstFaultHeader = Output(Vec(2, Vec(n, UInt(64.W))))

    val injectLaneError =
      Option.when(laneErrorInjection)(Input(Vec(2, Vec(n, Bool()))))
    val injectRepairMbLaneError =
      Option.when(repairMbLaneErrorInjection)(Input(Vec(2, Vec(n, Bool()))))
    val injectValidError =
      Option.when(validErrorInjection)(Input(Vec(2, Vec(n, Bool()))))
    val cutSideband =
      Option.when(moduleFaultInjection)(Input(Vec(2, Vec(n, Bool()))))
    val holdPllUnlocked =
      Option.when(moduleFaultInjection)(Input(Vec(2, Vec(n, Bool()))))
    val lpLinkError = Option.when(linkErrorInjection)(Input(Vec(2, Bool())))
    val stallMainbandTx =
      Option.when(mainbandStallInjection)(Input(Vec(2, Vec(n, Bool()))))
    val plError = Output(Vec(2, Bool()))
    val exitToPhyRetrainReqSent = Option.when(exitToPhyRetrainProbe)(
      Output(Vec(2, Vec(n, UInt(4.W))))
    )
    val exitToPhyRetrainRespSent = Option.when(exitToPhyRetrainProbe)(
      Output(Vec(2, Vec(n, UInt(4.W))))
    )

    val lpData = Option.when(dataPath)(Input(Vec(2, UInt(rdiWordBits.W))))
    val lpValid = Option.when(dataPath)(Input(Vec(2, Bool())))
    val lpIrdy = Option.when(dataPath)(Input(Vec(2, Bool())))
    val plData = Option.when(dataPath)(Output(Vec(2, UInt(rdiWordBits.W))))
  })

  require(
    specLiteralSenderDie.forall(d => (d == 0 || d == 1) && n > 1),
    s"specLiteralSenderDie must name die 0 or 1 of a multi-module Link, got $specLiteralSenderDie"
  )

  // Each die's IO and its Modules, whichever class builds it.
  private type DieIo = Bundle {
    val rdi: Rdi
    val ctrl: Vec[LogicalPhyCtrlIO]
    val status: Vec[LogicalPhyStatusIO]
    val analog: Vec[LogicalPhyAnalogIO]
    val mmplCtrl: MultiModulePhyCtrlIO
    val mmplStatus: MultiModulePhyStatusIO
  }
  private val dies: Seq[(DieIo, Seq[LogicalPhy])] = Seq.tabulate(2) { i =>
    val rxDelay = sidebandRxDelayCycles.lift(i).getOrElse(Seq())
    if (specLiteralSenderDie.contains(i)) {
      val die = Module(
        new SpecLiteralMultiModulePhy(
          params,
          sbParams,
          rdiParams,
          timeoutCyclesOverride,
          rxDelay
        )
      )
      (die.io: DieIo, die.modules)
    } else {
      val die = Module(
        new MultiModulePhy(
          params = params,
          sbParams = sbParams,
          rdiParams = rdiParams,
          timeoutCyclesOverride = timeoutCyclesOverride,
          sidebandRxDelayCycles = rxDelay
        )
      )
      // A bypass-capable die stays one multi-module Link here, so the
      // Modules' own RDIs carry nothing.
      die.io.mmplCtrl.bypass.foreach(_ := false.B)
      die.io.moduleRdi.foreach(_.foreach(MmplLoopbackHarness.quietAdapter))
      (die.io: DieIo, die.modules)
    }
  }
  private val dieIo = dies.map(_._1)

  for (i <- 0 until 2) {
    val dut = dieIo(i)
    val peer = dieIo(1 - i)

    // Every Module of this harness faces a Module on the other die.
    dut.mmplCtrl.moduleConnected.foreach(_ := true.B)

    for (m <- 0 until n) {
      // Module m of this die faces Module modulePairing(m) of the other.
      val peerModule = peer.analog(modulePairing(m))
      val here = dut.analog(m)

      here.sidebandLink.in.bits := peerModule.sidebandLink.out.bits
      here.sidebandLink.in.fwClock := peerModule.sidebandLink.out.fwClock

      here.mainband.rx.bits := peerModule.mainband.tx.bits
      here.mainband.rx.valid := peerModule.mainband.tx.valid
      here.mainband.tx.ready := peerModule.mainband.rx.ready

      io.injectLaneError.foreach { inject =>
        // Only inside MBTRAIN.LINKSPEED, so the Module still gets through every
        // earlier substate and fails the Step 2 point test where the spec has
        // the MMPL resolution happen.
        val corrupt = inject(i)(m) &&
          (dut.status(m).currentState === LTSMState.sMBTRAIN_LINKSPEED)
        when(corrupt) {
          here.mainband.rx.bits.data(0) := ~peerModule.mainband.tx.bits.data(0)
        }
      }
      io.injectRepairMbLaneError.foreach { inject =>
        when(
          inject(i)(m) &&
            (dut.status(m).currentState === LTSMState.sMBINIT_REPAIRMB)
        ) {
          here.mainband.rx.bits.data(0) := ~peerModule.mainband.tx.bits.data(0)
        }
      }
      io.injectValidError.foreach { inject =>
        when(inject(i)(m) && (dut.status(m).ltState === LTState.sACTIVE)) {
          here.mainband.rx.bits.valid := ~peerModule.mainband.tx.bits.valid
        }
      }
      io.stallMainbandTx.foreach { stall =>
        // This Module's transmitter, and the peer Module's view of it, stall
        // together: no beat is offered on either side while it is held.
        val peerStatus = dieIo(1 - i).status(modulePairing(m))
        when(
          stall(i)(m) &&
            (dut.status(m).currentState === LTSMState.sMBTRAIN_LINKSPEED)
        ) {
          here.mainband.tx.ready := false.B
        }
        when(
          stall(1 - i)(modulePairing(m)) &&
            (peerStatus.currentState === LTSMState.sMBTRAIN_LINKSPEED)
        ) {
          here.mainband.rx.valid := false.B
        }
      }

      io.cutSideband.foreach { cut =>
        when(cut(i)(m)) {
          here.sidebandLink.in.bits := 0.U
          here.sidebandLink.in.fwClock := 0.U
        }
      }

      here.status.pllLock := !io.holdPllUnlocked.map(_(i)(m)).getOrElse(false.B)
      here.status.clocksUngatedAndStable := true.B

      val ctrl = dut.ctrl(m)
      ctrl.pwrGood := io.pwrGood(i)
      ctrl.swStartLinkTraining := io.swStartLinkTraining(i)
      ctrl.retryTrainingAmt := retryTrainingAmt.U
      ctrl.maxErrorThresholdPerLane := 0.U
      ctrl.changeInRuntimeLinkCtrlRegsDetected :=
        io.changeInRuntimeLinkCtrlRegs(i)(m)
      ctrl.runtimeLinkCtrlBusyBit := false.B
      ctrl.runtimeRequestForRepair := false.B
      ctrl.swRetrainRequest := false.B
      ctrl.linkOpParamOverride := false.B
      ctrl.clockPhaseSelect := 0.U

      // Both dies advertise the same parameters, so PARAM interoperates. The
      // Module ID is driven by MultiModulePhy, not here.
      ctrl.localPhyParamSettings.valid := true.B
      ctrl.localPhyParamSettings.bits.voltageSwing := 0.U
      ctrl.localPhyParamSettings.bits.maxDataRate := 0.U
      ctrl.localPhyParamSettings.bits.clockMode := 0.U
      ctrl.localPhyParamSettings.bits.clockPhase := 0.U
      ctrl.localPhyParamSettings.bits.ucieSx8 := 0.U
      ctrl.localPhyParamSettings.bits.sbFeatExt := 0.U
      ctrl.localPhyParamSettings.bits.txAdjRuntime := 0.U
      ctrl.localPhyParamSettings.bits.moduleId := 0.U

      ctrl.linkTrainingParameters.clockPhase := 0.U
      ctrl.linkTrainingParameters.dataPattern := 0.U
      ctrl.linkTrainingParameters.validPattern := 0.U
      ctrl.linkTrainingParameters.patternMode := 0.U
      ctrl.linkTrainingParameters.iterationCount := 0.U
      ctrl.linkTrainingParameters.idleCount := 0.U
      ctrl.linkTrainingParameters.burstCount := 0.U
      ctrl.linkTrainingParameters.maxErrorThreshold := 0.U
      ctrl.linkTrainingParameters.comparisonMode := 0.U

      io.ltState(i)(m) := dut.status(m).ltState
      io.ltsmState(i)(m) := dut.status(m).currentState
      io.trainingTimedout(i)(m) := dut.status(m).trainingTimedout
      io.negotiatedParamsValid(i)(m) :=
        dut.status(m).negotiatedPhyParamSettings.valid
      io.remoteModuleId(i)(m) :=
        dut.status(m).negotiatedPhyParamSettings.bits.moduleId
      io.moduleEnable(i)(m) := dut.mmplStatus.moduleEnable(m)
    }

    dut.rdi.lclk := false.B
    dut.rdi.lpStateReq := io.lpStateReq(i)
    dut.rdi.lpClkAck := dut.rdi.plClkReq
    dut.rdi.lpStallAck := dut.rdi.plStallReq
    dut.rdi.lpLinkError := io.lpLinkError.map(_(i)).getOrElse(false.B)
    dut.rdi.lpWakeReq := false.B
    dut.rdi.lpCfg := 0.U
    dut.rdi.lpCfgVld := false.B
    dut.rdi.lpCfgCrd := false.B
    dut.rdi.lpData := io.lpData.map(_(i)).getOrElse(0.U)
    dut.rdi.lpValid := io.lpValid.map(_(i)).getOrElse(false.B)
    dut.rdi.lpIrdy := io.lpIrdy.map(_(i)).getOrElse(false.B)

    for (m <- 0 until n) {
      io.moduleLinkWidth(i)(m) := dut.status(m).linkWidth
      io.moduleTxLanes(i)(m) := dut.status(m).localTxFunctionalLanes
      io.moduleRxLanes(i)(m) := dut.status(m).remoteTxFunctionalLanes
    }

    io.mmplResolution(i) := dut.mmplStatus.linkResolution

    io.plStateSts(i) := dut.rdi.plStateSts
    io.plInbandPres(i) := dut.rdi.plInbandPres
    io.plTrainError(i) := dut.rdi.plTrainError
    io.plLnkCfg(i) := dut.rdi.plLnkCfg
    io.plSpeedmode(i) := dut.rdi.plSpeedmode
    io.plTrdy(i) := dut.rdi.plTrdy
    io.plValid(i) := dut.rdi.plValid
    io.plError(i) := dut.rdi.plError
    io.plData.foreach(_(i) := dut.rdi.plData)

    for (m <- 0 until n) {
      io.sbUnhandledSeen(i)(m) :=
        dut.status(m).sideband.sbUnhandledCurrentLayerMsgSeen
      io.sbFirstFaultHeader(i)(m) := dut.status(m).sideband.sbFirstFaultHeader
    }
    io.sbFaultSeen(i) := (0 until n)
      .map { m =>
        val sb = dut.status(m).sideband
        sb.sbParityErrSeen || sb.sbRxPriorityQueuesFullSeen ||
        sb.sbDeserializerTimedoutSeen || sb.sbInvalidRouteUpperSeen ||
        sb.sbInvalidRouteCurrSeen || sb.sbInvalidRouteLowerSeen ||
        sb.sbUnhandledCurrentLayerMsgSeen
      }
      .reduce(_ || _)

    // Tapped where a packet leaves the Module's LogicalPhy for its sideband
    // channel, so what is counted is what went on that Module's wire.
    for (m <- 0 until n if exitToPhyRetrainProbe) {
      val txq = dies(i)._2(m).sidebandTxQueue.io.deq
      val fire = BoringUtils.tapAndRead(txq.valid) &&
        BoringUtils.tapAndRead(txq.ready)
      val bits = BoringUtils.tapAndRead(txq.bits)
      def counter(msg: MixedVec[UInt]): UInt = {
        val count = RegInit(0.U(4.W))
        when(fire && SBMsgCompare(bits, msg) && !count.andR) {
          count := count + 1.U
        }
        count
      }
      io.exitToPhyRetrainReqSent.get(i)(m) :=
        counter(SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_REQ)
      io.exitToPhyRetrainRespSent.get(i)(m) :=
        counter(SBM.MBTRAIN_LINKSPEED_EXIT_TO_PHY_RETRAIN_RESP)
    }
  }
}

object MmplLoopbackHarness {

  /** An Adapter that asks for nothing and sends nothing, but acks the clock and
    * stall handshakes so the Physical Layer never waits on it. Drive any
    * signal again afterwards to override it.
    */
  def quietAdapter(rdi: Rdi): Unit = {
    rdi.lclk := false.B
    rdi.lpStateReq := RDIStateReq.nop
    rdi.lpClkAck := rdi.plClkReq
    rdi.lpStallAck := rdi.plStallReq
    rdi.lpLinkError := false.B
    rdi.lpWakeReq := false.B
    rdi.lpCfg := 0.U
    rdi.lpCfgVld := false.B
    rdi.lpCfgCrd := false.B
    rdi.lpData := 0.U
    rdi.lpValid := false.B
    rdi.lpIrdy := false.B
  }
}

/* Test-only stand-in for a partner die that follows spec 4.5.3.4.12 to the
   letter on the side that SENT {MBTRAIN.LINKSPEED exit to phy retrain req}.
   Step 5 makes the request received on any Module a directive for every
   Module of the RECEIVING die; nothing in the spec tells the sending die's
   other Modules, which learn of the exit only from the {exit to PHY retrain
   resp} their own partners send them (Step 4a: "Once this sideband message is
   received, the UCIe Module must exit to PHY retrain"). This RTL has two relays
   such a die does not: the MMPL's phyRetrain directive, and treating the
   partner's {PHYRETRAIN.retrain start req} as the partner having left. Both are
   removed here, so these Modules can leave only on a received exit req or
   resp. Everything else is MultiModulePhy unchanged. */
class SpecLiteralLogicalPhy(
    afeParams: AfeParams,
    sbParams: SidebandParams,
    rdiParams: RdiParams,
    timeoutCyclesOverride: Option[Int],
    sidebandRxDelayCycles: Int
) extends LogicalPhy(
      afeParams = afeParams,
      sbParams = sbParams,
      rdiParams = rdiParams,
      timeoutCyclesOverride = timeoutCyclesOverride,
      rdiStateMachine = RdiStateMachineHome.Hosted,
      sidebandRxDelayCycles = sidebandRxDelayCycles
    ) {
  // Kept at the head of the queue, as LinkTrainingSM's holdSidebandRx does,
  // until this Module has reached PHYRETRAIN, where it is taken as usual.
  private val hideRetrainStart =
    (ltsm.io.currentState === LTSMState.sMBTRAIN_LINKSPEED) &&
      sidebandRxQueue.io.deq.valid &&
      SBMsgCompare(sidebandRxQueue.io.deq.bits, SBM.PHYRETRAIN_RETRAIN_START_REQ)
  // Last connect wins over LogicalPhy's own.
  ltsm.io.sbLaneIo.rx.valid := sidebandRxQueue.io.deq.valid && !hideRetrainStart
  io.mmplRdiHost.foreach { host =>
    host.sbLaneIo.rx.valid := sidebandRxQueue.io.deq.valid &&
      !sidebandRxReadyLtsm && !ltsm.io.holdSidebandRx && !hideRetrainStart
  }
  sidebandRxUnhandled := sidebandRxQueue.io.deq.valid && !sidebandRxReadyLtsm &&
    !sidebandRxReadyRdi && !sidebandRxHeldAbove && !ltsm.io.holdSidebandRx &&
    !hideRetrainStart
}

/** MultiModulePhy built from SpecLiteralLogicalPhy, with the MMPL's phyRetrain
  * directive withheld from every Module (see SpecLiteralLogicalPhy). The
  * wiring is MultiModulePhy's.
  */
class SpecLiteralMultiModulePhy(
    params: MmplParams,
    sbParams: SidebandParams,
    rdiParams: RdiParams,
    timeoutCyclesOverride: Option[Int],
    sidebandRxDelayCycles: Seq[Int]
) extends Module {
  private val n = params.numModules
  private val afeParams = params.afe
  require(params.isMultiModule, "only a multi-module Link has siblings")
  require(rdiParams.nBytes == n * params.bytesPerModule)
  require(sbParams.maxCrd % n == 0 && sbParams.maxCrd / n >= 1)
  private val moduleSbParams = sbParams.copy(maxCrd = sbParams.maxCrd / n)

  val io = IO(new Bundle {
    val rdi = new Rdi(rdiParams)
    val ctrl = Vec(n, new LogicalPhyCtrlIO(10, afeParams))
    val status = Vec(n, new LogicalPhyStatusIO())
    val analog = Vec(n, new LogicalPhyAnalogIO(afeParams, sbParams))
    val mmplCtrl = new MultiModulePhyCtrlIO(n)
    val mmplStatus = new MultiModulePhyStatusIO(n)
  })

  val mmpl = Module(
    new Mmpl(
      params,
      rdiParams,
      sbParams,
      LinkTrainingSM.linkErrorResidencyCycles(timeoutCyclesOverride)
    )
  )
  val modules: Seq[LogicalPhy] = Seq.tabulate(n) { m =>
    val phy = Module(
      new SpecLiteralLogicalPhy(
        afeParams,
        moduleSbParams,
        params.moduleRdiParams(rdiParams.ncWidth),
        timeoutCyclesOverride,
        sidebandRxDelayCycles.lift(m).getOrElse(0)
      )
    )
    phy.suggestName(s"module_$m")
    phy
  }

  io.rdi <> mmpl.io.rdi
  for (m <- 0 until n) {
    val phy = modules(m)
    phy.io.ctrl <> io.ctrl(m)
    phy.io.ctrl.localPhyParamSettings.bits.moduleId := m.U(2.W)
    io.analog(m) <> phy.io.analog
    mmpl.io.modules(m).rdi <> phy.io.rdi
    mmpl.io.modules(m).status := phy.io.status
    phy.io.mmplCtrl <> mmpl.io.modules(m).ctrl
    // Last connect wins: every other directive still reaches the Module.
    phy.io.mmplCtrl.resolution.valid := mmpl.io.modules(m).ctrl.resolution.valid &&
      (mmpl.io.modules(m).ctrl.resolution.bits =/= MmplResolution.phyRetrain)
    mmpl.io.modules(m).rdiHost.foreach(_ <> phy.io.mmplRdiHost.get)
    io.status(m) := phy.io.status
  }
  mmpl.io.moduleConnected := io.mmplCtrl.moduleConnected
  io.mmplStatus.moduleEnable := mmpl.io.status.moduleEnable
  io.mmplStatus.linkResolution := mmpl.io.status.linkResolution
  io.mmplStatus.resolutionApplied := mmpl.io.status.resolutionApplied
  io.mmplStatus.bypassed := false.B
}
