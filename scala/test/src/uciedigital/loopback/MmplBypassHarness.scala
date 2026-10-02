package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.sideband._

// A bypass-capable multi-module die whose Modules each face a different
// single-module die: the spec 4.7.2 Figure 4-49(a) configuration, with the two
// Links going to two remote dies rather than one.
//
// Die 0 is a MultiModulePhy built with MmplParams.bypassable. Module m of die 0
// is cross-wired at the analog boundary to partner m, a bare LogicalPhy that
// is a die of its own. Each pairing is a Link, numbered by m, with an Adapter
// at either end; IO indexed [link][side] puts die 0's Module at side 0 and its
// partner at side 1.
//
// The Adapters are the testbench, as in MmplLoopbackHarness: they ask for what
// the test pokes and ack the clock and stall handshakes. Die 0's aggregate RDI
// has a quiet Adapter of its own, which a bypassed die must leave idle.
//
// Only die 0's Modules take a software trigger. A partner wakes on the sideband
// clock pattern of the Module it faces, so each Link's training starts from
// that Link alone.
//
// The analog macro is not modelled: pllLock and clocksUngatedAndStable are tied
// high.
//
// @param dataPath
//   exposes each Link's RDI data ports, at both ends.
class MmplBypassHarness(
    val params: MmplParams = MmplParams(numModules = 2, bypassable = true),
    val sbParams: SidebandParams = new SidebandParams(),
    val dataPath: Boolean = false,
    val timeoutCyclesOverride: Option[Int] = None
) extends Module {
  require(params.bypassable, "the harness exists to bypass the MMPL")

  private val n = params.numModules
  private val moduleRdiParams = params.moduleRdiParams(32)
  private val wordBits = moduleRdiParams.nBytes * 8

  private def perLink[T <: Data](t: => T) = Vec(n, Vec(2, t))

  val io = IO(new Bundle {
    // Die 0's mode request and the mode in effect.
    val bypass = Input(Bool())
    val bypassed = Output(Bool())
    // Die 0's per-Module training triggers.
    val swStartLinkTraining = Input(Vec(n, Bool()))

    val lpStateReq = Input(perLink(RDIStateReq()))
    val ltState = Output(perLink(LTState()))
    val ltsmState = Output(perLink(LTSMState()))
    // The Module ID each end learned from the other in MBINIT.PARAM.
    val remoteModuleId = Output(perLink(UInt(2.W)))
    val negotiatedParamsValid = Output(perLink(Bool()))
    val plStateSts = Output(perLink(RDIState()))
    val plInbandPres = Output(perLink(Bool()))
    val plTrainError = Output(perLink(Bool()))
    val plLnkCfg = Output(perLink(LinkWidth()))
    val plTrdy = Output(perLink(Bool()))
    val plValid = Output(perLink(Bool()))
    val sbFaultSeen = Output(perLink(Bool()))

    // Die 0's aggregate RDI, which carries no Link while bypassed.
    val aggPlStateSts = Output(RDIState())
    val aggPlInbandPres = Output(Bool())
    val aggPlTrdy = Output(Bool())
    val aggPlValid = Output(Bool())

    val lpData = Option.when(dataPath)(Input(perLink(UInt(wordBits.W))))
    val lpValid = Option.when(dataPath)(Input(perLink(Bool())))
    val lpIrdy = Option.when(dataPath)(Input(perLink(Bool())))
    val plData = Option.when(dataPath)(Output(perLink(UInt(wordBits.W))))
  })

  val die0 = Module(
    new MultiModulePhy(
      params = params,
      sbParams = sbParams,
      rdiParams = params.rdiParams(32),
      timeoutCyclesOverride = timeoutCyclesOverride
    )
  )
  val partners = Seq.tabulate(n) { m =>
    val phy = Module(
      new LogicalPhy(
        afeParams = params.afe,
        sbParams = sbParams,
        rdiParams = moduleRdiParams,
        timeoutCyclesOverride = timeoutCyclesOverride
      )
    )
    phy.suggestName(s"partner_$m")
    phy.io.mmplCtrl.tieOffSingleModule()
    phy
  }

  die0.io.mmplCtrl.bypass.get := io.bypass
  die0.io.mmplCtrl.moduleConnected.foreach(_ := true.B)
  io.bypassed := die0.io.mmplStatus.bypassed

  MmplLoopbackHarness.quietAdapter(die0.io.rdi)
  io.aggPlStateSts := die0.io.rdi.plStateSts
  io.aggPlInbandPres := die0.io.rdi.plInbandPres
  io.aggPlTrdy := die0.io.rdi.plTrdy
  io.aggPlValid := die0.io.rdi.plValid

  // Both ends advertise the same parameters, so PARAM interoperates. Die 0's
  // Module ID is driven by MultiModulePhy; a partner is M0 of its Link.
  private def driveCtrl(ctrl: LogicalPhyCtrlIO, trigger: Bool): Unit = {
    ctrl.pwrGood := true.B
    ctrl.swStartLinkTraining := trigger
    ctrl.retryTrainingAmt := 0.U
    ctrl.maxErrorThresholdPerLane := 0.U
    ctrl.changeInRuntimeLinkCtrlRegsDetected := false.B
    ctrl.runtimeLinkCtrlBusyBit := false.B
    ctrl.runtimeRequestForRepair := false.B
    ctrl.swRetrainRequest := false.B
    ctrl.linkOpParamOverride := false.B
    ctrl.clockPhaseSelect := 0.U

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
  }

  private def crossWire(
      here: LogicalPhyAnalogIO,
      there: LogicalPhyAnalogIO
  ): Unit = {
    here.sidebandLink.in.bits := there.sidebandLink.out.bits
    here.sidebandLink.in.fwClock := there.sidebandLink.out.fwClock
    here.mainband.rx.bits := there.mainband.tx.bits
    here.mainband.rx.valid := there.mainband.tx.valid
    here.mainband.tx.ready := there.mainband.rx.ready
    here.status.pllLock := true.B
    here.status.clocksUngatedAndStable := true.B
  }

  for (m <- 0 until n) {
    crossWire(die0.io.analog(m), partners(m).io.analog)
    crossWire(partners(m).io.analog, die0.io.analog(m))

    driveCtrl(die0.io.ctrl(m), io.swStartLinkTraining(m))
    driveCtrl(partners(m).io.ctrl, false.B)

    // Link m's two ends: die 0's Module and the partner it faces.
    val ends = Seq(
      (die0.io.moduleRdi.get(m), die0.io.status(m)),
      (partners(m).io.rdi, partners(m).io.status)
    )
    for (((rdi, status), side) <- ends.zipWithIndex) {
      MmplLoopbackHarness.quietAdapter(rdi)
      rdi.lpStateReq := io.lpStateReq(m)(side)
      io.lpData.foreach(d => rdi.lpData := d(m)(side))
      io.lpValid.foreach(v => rdi.lpValid := v(m)(side))
      io.lpIrdy.foreach(v => rdi.lpIrdy := v(m)(side))

      io.ltState(m)(side) := status.ltState
      io.ltsmState(m)(side) := status.currentState
      io.remoteModuleId(m)(side) :=
        status.negotiatedPhyParamSettings.bits.moduleId
      io.negotiatedParamsValid(m)(side) :=
        status.negotiatedPhyParamSettings.valid
      io.plStateSts(m)(side) := rdi.plStateSts
      io.plInbandPres(m)(side) := rdi.plInbandPres
      io.plTrainError(m)(side) := rdi.plTrainError
      io.plLnkCfg(m)(side) := rdi.plLnkCfg
      io.plTrdy(m)(side) := rdi.plTrdy
      io.plValid(m)(side) := rdi.plValid
      io.plData.foreach(_(m)(side) := rdi.plData)

      val sb = status.sideband
      io.sbFaultSeen(m)(side) := sb.sbParityErrSeen ||
        sb.sbRxPriorityQueuesFullSeen || sb.sbDeserializerTimedoutSeen ||
        sb.sbInvalidRouteUpperSeen || sb.sbInvalidRouteCurrSeen ||
        sb.sbInvalidRouteLowerSeen || sb.sbUnhandledCurrentLayerMsgSeen
    }
  }
}
