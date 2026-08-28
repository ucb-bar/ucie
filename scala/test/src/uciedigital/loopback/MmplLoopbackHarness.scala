package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.sideband._

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
// high, as in LogPhyLoopbackHarness.
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
    val timeoutCyclesOverride: Option[Int] = None
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

    // Per Module, because a multi-module Link can have Modules in different
    // states before the MMPL resolves them.
    val ltState = Output(Vec(2, Vec(n, LTState())))
    val ltsmState = Output(Vec(2, Vec(n, LTSMState())))
    val trainingTimedout = Output(Vec(2, Vec(n, Bool())))
    val negotiatedParamsValid = Output(Vec(2, Vec(n, Bool())))
    val remoteModuleId = Output(Vec(2, Vec(n, UInt(2.W))))
    val moduleEnable = Output(Vec(2, Vec(n, Bool())))

    // Aggregate RDI, one per die.
    val plStateSts = Output(Vec(2, RDIState()))
    val plInbandPres = Output(Vec(2, Bool()))
    val plTrainError = Output(Vec(2, Bool()))
    val plLnkCfg = Output(Vec(2, LinkWidth()))
    val plSpeedmode = Output(Vec(2, SpeedMode()))
    val plTrdy = Output(Vec(2, Bool()))
    val plValid = Output(Vec(2, Bool()))
    val sbFaultSeen = Output(Vec(2, Bool()))

    val injectLaneError =
      Option.when(laneErrorInjection)(Input(Vec(2, Vec(n, Bool()))))

    val lpData = Option.when(dataPath)(Input(Vec(2, UInt(rdiWordBits.W))))
    val lpValid = Option.when(dataPath)(Input(Vec(2, Bool())))
    val lpIrdy = Option.when(dataPath)(Input(Vec(2, Bool())))
    val plData = Option.when(dataPath)(Output(Vec(2, UInt(rdiWordBits.W))))
  })

  val duts = Seq.fill(2)(
    Module(
      new MultiModulePhy(
        params = params,
        sbParams = sbParams,
        rdiParams = rdiParams,
        timeoutCyclesOverride = timeoutCyclesOverride
      )
    )
  )

  for (i <- 0 until 2) {
    val dut = duts(i).io
    val peer = duts(1 - i).io

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

      here.status.pllLock := true.B
      here.status.clocksUngatedAndStable := true.B

      val ctrl = dut.ctrl(m)
      ctrl.pwrGood := io.pwrGood(i)
      ctrl.swStartLinkTraining := io.swStartLinkTraining(i)
      ctrl.retryTrainingAmt := 0.U
      ctrl.maxErrorThresholdPerLane := 0.U
      ctrl.changeInRuntimeLinkCtrlRegsDetected := false.B
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
    dut.rdi.lpLinkError := false.B
    dut.rdi.lpWakeReq := false.B
    dut.rdi.lpCfg := 0.U
    dut.rdi.lpCfgVld := false.B
    dut.rdi.lpCfgCrd := false.B
    dut.rdi.lpData := io.lpData.map(_(i)).getOrElse(0.U)
    dut.rdi.lpValid := io.lpValid.map(_(i)).getOrElse(false.B)
    dut.rdi.lpIrdy := io.lpIrdy.map(_(i)).getOrElse(false.B)

    io.plStateSts(i) := dut.rdi.plStateSts
    io.plInbandPres(i) := dut.rdi.plInbandPres
    io.plTrainError(i) := dut.rdi.plTrainError
    io.plLnkCfg(i) := dut.rdi.plLnkCfg
    io.plSpeedmode(i) := dut.rdi.plSpeedmode
    io.plTrdy(i) := dut.rdi.plTrdy
    io.plValid(i) := dut.rdi.plValid
    io.plData.foreach(_(i) := dut.rdi.plData)

    io.sbFaultSeen(i) := (0 until n)
      .map { m =>
        val sb = dut.status(m).sideband
        sb.sbParityErrSeen || sb.sbRxPriorityQueuesFullSeen ||
        sb.sbDeserializerTimedoutSeen || sb.sbInvalidRouteUpperSeen ||
        sb.sbInvalidRouteCurrSeen || sb.sbInvalidRouteLowerSeen ||
        sb.sbUnhandledCurrentLayerMsgSeen
      }
      .reduce(_ || _)
  }
}
