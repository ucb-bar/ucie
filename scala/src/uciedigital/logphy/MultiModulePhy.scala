/*
  Description:
    A whole multi-module UCIe Physical Layer: one Mmpl above `numModules`
    LogicalPhy instances (spec 1.2.2 Figure 1-12 and Figure 1-13, spec 10.1
    Figure 10-1). Presents a single RDI upward and one analog boundary per
    Module, so it drops in where a LogicalPhy would sit.

  NOTE:
 * There is one RDI state machine for the whole Link (spec 3.5). At two or four
   Modules the MMPL hosts it and each LogicalPhy is built without one (unless
   the group can be bypassed, below); at one Module the LogicalPhy keeps its
   own, so nothing about a single-module Link changes.
 * The local Module ID is the Module index. It is advertised in
   {MBINIT.PARAM configuration req} and the remote Module ID that comes back is
   what the MMPL transmit byte map ranks by (spec 4.7.1).
 * At numModules == 1 this is wiring only: the byte map is the identity and the
   resolver never fires, so a one-module Link behaves exactly as a bare
   LogicalPhy does.
 * With MmplParams.bypassable the same Modules can instead run as independent
   single-module Links, each to its own Adapter over io.moduleRdi, with the
   MMPL bypassed (spec 4.7.2, Figure 4-49(a)). Each LogicalPhy then also
   carries an RDI state machine of its own, which runs only while bypassed.
   Bypassed, a Module is M0 of its own Link and trains on its own trigger.
 * The sideband cfg credit pool is still split numModules ways when bypassed,
   so each Module uses maxCrd / numModules of the credits its own Adapter
   advertises. That is never more than the Adapter can take (spec 7.1.3.1),
   only fewer than it could.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._

class MultiModulePhyStatusIO(numModules: Int) extends Bundle {
  val moduleEnable = Output(Vec(numModules, Bool()))
  val linkResolution = Output(MmplResolution())
  val resolutionApplied = Output(Bool())
  // The MMPL is bypassed and every Module is a Link of its own. Always low on
  // a group that cannot bypass.
  val bypassed = Output(Bool())
}

class MultiModulePhyCtrlIO(numModules: Int, bypassable: Boolean = false)
    extends Bundle {
  /* Which Modules are wired to a remote Module Partner. Chapter 5 permits a
     Link whose two die have different Module counts (Table 5-28), and the local
     Modules that Table marks NC never train. Tie the corresponding bit low so
     the MMPL leaves that Module out of every aggregate; leave all bits high for
     a Link where every Module is connected. Bypassed, an unconnected Module is
     held in RESET the same way. */
  val moduleConnected = Input(Vec(numModules, Bool()))
  /* Bypass the MMPL: train every Module as a Link of its own, on io.moduleRdi,
     instead of one Link on io.rdi. Spec 4.7.2 has software pick "within which
     configuration to train the link", so the bit is a choice of how to train
     next. It takes effect while every Module is in RESET and every RDI is in
     Reset, and not before; status.bypassed says which mode is in effect. */
  val bypass = Option.when(bypassable)(Input(Bool()))
}

class MultiModulePhy(
    params: MmplParams = MmplParams(),
    sbParams: SidebandParams = new SidebandParams(),
    rdiParams: RdiParams = RdiParams(64, 32),
    retryW: Int = 10,
    desTimeoutCycles: Int = 512,
    queueDepths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths(),
    // Simulation-only shortening of the link training residency timeouts.
    timeoutCyclesOverride: Option[Int] = None,
    // Simulation-only extra sideband receive latency per Module (missing
    // entries are zero), for tests of Modules whose sideband links differ.
    sidebandRxDelayCycles: Seq[Int] = Seq()
) extends Module {
  private val n = params.numModules
  private val afeParams = params.afe
  private val moduleRdiParams = params.moduleRdiParams(rdiParams.ncWidth)

  require(
    rdiParams.nBytes == n * params.bytesPerModule,
    s"MultiModulePhy needs an RDI of $n x ${params.bytesPerModule} bytes, " +
      s"got ${rdiParams.nBytes}"
  )

  val io = IO(new Bundle {
    val rdi = new Rdi(rdiParams)
    // Each Module's own RDI, to an Adapter of its own, while the MMPL is
    // bypassed. Idle in Reset otherwise, as io.rdi is while bypassed.
    val moduleRdi =
      Option.when(params.bypassable)(Vec(n, new Rdi(moduleRdiParams)))
    val ctrl = Vec(n, new LogicalPhyCtrlIO(retryW, afeParams))
    val status = Vec(n, new LogicalPhyStatusIO())
    val analog = Vec(n, new LogicalPhyAnalogIO(afeParams, sbParams))
    val mmplCtrl = new MultiModulePhyCtrlIO(n, params.bypassable)
    val mmplStatus = new MultiModulePhyStatusIO(n)
  })

  /* Spec 7.1.3.1: "Every Transmitter/Receiver pair has an independent credit
     loop", and a multi-module Link presents one RDI, so the Adapter advertises
     one pool of maxCrd credits for all the Modules together. Each Module runs
     its own sideband node, so the pool has to be split between them -- handing
     every Module the whole pool would let the Link put numModules x maxCrd
     packets in flight against an Adapter that provisioned maxCrd. */
  require(
    sbParams.maxCrd % n == 0 && sbParams.maxCrd / n >= 1,
    s"MultiModulePhy splits the RDI sideband credit pool across $n Modules, " +
      s"so maxCrd (${sbParams.maxCrd}) must be a positive multiple of $n"
  )
  private val moduleSbParams =
    if (params.isMultiModule) sbParams.copy(maxCrd = sbParams.maxCrd / n)
    else sbParams

  // The mode in effect, which io.mmplCtrl.bypass only changes while the group
  // is down (see below).
  private val bypassed: Bool =
    if (params.bypassable) RegInit(false.B) else false.B

  /* Bypassed, the MMPL has nothing to run. Holding it in reset keeps it from
     acting on Modules that are not its Link, and the next multi-module training
     starts it from scratch, every Module restored. */
  private val mmplReset =
    if (params.bypassable) reset.asBool || bypassed else reset
  val mmpl = withReset(mmplReset) {
    Module(
      new Mmpl(
        params,
        rdiParams,
        sbParams,
        LinkTrainingSM.linkErrorResidencyCycles(timeoutCyclesOverride)
      )
    )
  }
  val modules = Seq.tabulate(n) { m =>
    val phy = Module(
      new LogicalPhy(
        afeParams = afeParams,
        sbParams = moduleSbParams,
        rdiParams = moduleRdiParams,
        retryW = retryW,
        desTimeoutCycles = desTimeoutCycles,
        queueDepths = queueDepths,
        timeoutCyclesOverride = timeoutCyclesOverride,
        // Spec 3.5: one RDI state machine for the whole Link, hosted by the
        // MMPL. A one-module Link keeps its own, and so does a Module that can
        // be bypassed to a Link of its own.
        rdiStateMachine =
          if (!params.isMultiModule) RdiStateMachineHome.Local
          else if (params.bypassable) RdiStateMachineHome.Selectable
          else RdiStateMachineHome.Hosted,
        sidebandRxDelayCycles = sidebandRxDelayCycles.lift(m).getOrElse(0)
      )
    )
    phy.suggestName(s"module_$m")
    phy
  }

  /* Spec 4.7.2 has the configuration chosen before the Link trains, so the
     mode only changes hands while nothing is up in either: every LTSM in RESET
     and every RDI state machine -- the hosted one and each Module's own -- in
     Reset. A Link in LinkError keeps its mode until it has been through it. */
  io.mmplCtrl.bypass.foreach { requested =>
    val groupDown = (mmpl.io.rdi.plStateSts === RDIState.reset) &&
      modules
        .map { phy =>
          (phy.io.status.ltState === LTState.sRESET) &&
          (phy.io.rdi.plStateSts === RDIState.reset)
        }
        .reduce(_ && _)
    when(groupDown) { bypassed := requested }
  }

  io.rdi <> mmpl.io.rdi
  if (params.bypassable) {
    // Bypassed, the aggregate is no Link at all. The MMPL is in reset, but its
    // pass-through paths would still show the Modules' traffic.
    when(bypassed) { MultiModulePhy.idleRdi(io.rdi) }
  }

  for (m <- 0 until n) {
    val phy = modules(m)

    phy.io.ctrl <> io.ctrl(m)
    /* Spec 4.7: each Module of a multi-module Link owns a dedicated Module ID,
       advertised to the remote Link partner during MBINIT.PARAM. Bypassed, the
       Module is the only one of its Link, which spec 5.7.3.4 names M0 (Table
       5-28's x1 pairings). */
    phy.io.ctrl.localPhyParamSettings.bits.moduleId := Mux(bypassed, 0.U, m.U(2.W))

    io.analog(m) <> phy.io.analog

    io.moduleRdi match {
      case Some(own) =>
        MultiModulePhy.steerModuleRdi(
          phy.io.rdi,
          mmpl.io.modules(m).rdi,
          own(m),
          bypassed
        )
      case None => mmpl.io.modules(m).rdi <> phy.io.rdi
    }
    mmpl.io.modules(m).status := phy.io.status
    phy.io.mmplCtrl <> mmpl.io.modules(m).ctrl
    mmpl.io.modules(m).rdiHost.foreach(_ <> phy.io.mmplRdiHost.get)

    // Bypassed, a Module is a Link of its own with nothing above it to direct
    // it, as LogicalPhy is on its own -- though still none without a partner.
    when(bypassed) {
      phy.io.mmplCtrl.tieOffSingleModule()
      phy.io.mmplCtrl.moduleDisabled := !io.mmplCtrl.moduleConnected(m)
    }

    io.status(m) := phy.io.status
  }

  mmpl.io.moduleConnected := io.mmplCtrl.moduleConnected

  io.mmplStatus.moduleEnable := mmpl.io.status.moduleEnable
  io.mmplStatus.linkResolution := mmpl.io.status.linkResolution
  io.mmplStatus.resolutionApplied := mmpl.io.status.resolutionApplied
  io.mmplStatus.bypassed := bypassed
}

object MultiModulePhy {

  /** Drives every Physical Layer output of `rdi` to an RDI in Reset with
    * nothing valid and nothing requested. Every RDI encoding is legal at zero.
    */
  private def idleRdi(rdi: Rdi): Unit =
    for ((_, field) <- rdi.elements if isPhyOutput(field)) {
      field := 0.U.asTypeOf(field)
    }

  /** Connects a Module's RDI to whichever upper port is in use: the MMPL's
    * port for the Module, or the Module's own RDI while bypassed. What the
    * Module drives goes to both, and its own port reads idle while it is not
    * in use; what the Module takes comes from the port in use alone.
    */
  private def steerModuleRdi(
      module: Rdi,
      mmplPort: Rdi,
      own: Rdi,
      bypassed: Bool
  ): Unit = {
    for ((name, field) <- module.elements) {
      val viaMmpl = mmplPort.elements(name)
      val viaOwn = own.elements(name)
      if (isPhyOutput(field)) {
        viaMmpl := field
        viaOwn := Mux(bypassed, field, 0.U.asTypeOf(field))
      } else {
        field := Mux(bypassed, viaOwn, viaMmpl)
      }
    }
  }

  // Rdi declares each signal from the Physical Layer's side.
  private def isPhyOutput(field: Data): Boolean =
    DataMirror.specifiedDirectionOf(field) == SpecifiedDirection.Output
}
