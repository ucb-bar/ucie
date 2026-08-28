/*
  Description:
    A whole multi-module UCIe Physical Layer: one Mmpl above `numModules`
    LogicalPhy instances (spec 1.2.2 Figure 1-12 and Figure 1-13, spec 10.1
    Figure 10-1). Presents a single RDI upward and one analog boundary per
    Module, so it drops in where a LogicalPhy would sit.

  NOTE:
 * There is one RDI state machine for the whole Link (spec 3.5). At two or four
   Modules the MMPL hosts it and each LogicalPhy is built without one; at one
   Module the LogicalPhy keeps its own, so nothing about a single-module Link
   changes.
 * The local Module ID is the Module index. It is advertised in
   {MBINIT.PARAM configuration req} and the remote Module ID that comes back is
   what the MMPL transmit byte map ranks by (spec 4.7.1).
 * At numModules == 1 this is wiring only: the byte map is the identity and the
   resolver never fires, so a one-module Link behaves exactly as a bare
   LogicalPhy does.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util._

class MultiModulePhyStatusIO(numModules: Int) extends Bundle {
  val moduleEnable = Output(Vec(numModules, Bool()))
  val linkResolution = Output(MmplResolution())
  val resolutionApplied = Output(Bool())
}

class MultiModulePhyCtrlIO(numModules: Int) extends Bundle {
  /* Which Modules are wired to a remote Module Partner. Chapter 5 permits a
     Link whose two die have different Module counts (Table 5-28), and the local
     Modules that Table marks NC never train. Tie the corresponding bit low so
     the MMPL leaves that Module out of every aggregate; leave all bits high for
     a Link where every Module is connected. */
  val moduleConnected = Input(Vec(numModules, Bool()))
}

class MultiModulePhy(
    params: MmplParams = MmplParams(),
    sbParams: SidebandParams = new SidebandParams(),
    rdiParams: RdiParams = RdiParams(64, 32),
    retryW: Int = 10,
    desTimeoutCycles: Int = 512,
    queueDepths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths(),
    // Simulation-only shortening of the link training residency timeouts.
    timeoutCyclesOverride: Option[Int] = None
) extends Module {
  private val n = params.numModules
  private val afeParams = params.afe

  require(
    rdiParams.nBytes == n * params.bytesPerModule,
    s"MultiModulePhy needs an RDI of $n x ${params.bytesPerModule} bytes, " +
      s"got ${rdiParams.nBytes}"
  )

  val io = IO(new Bundle {
    val rdi = new Rdi(rdiParams)
    val ctrl = Vec(n, new LogicalPhyCtrlIO(retryW, afeParams))
    val status = Vec(n, new LogicalPhyStatusIO())
    val analog = Vec(n, new LogicalPhyAnalogIO(afeParams, sbParams))
    val mmplCtrl = new MultiModulePhyCtrlIO(n)
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

  val mmpl = Module(new Mmpl(params, rdiParams, sbParams))
  val modules = Seq.tabulate(n) { m =>
    val phy = Module(
      new LogicalPhy(
        afeParams = afeParams,
        sbParams = moduleSbParams,
        rdiParams = params.moduleRdiParams(rdiParams.ncWidth),
        retryW = retryW,
        desTimeoutCycles = desTimeoutCycles,
        queueDepths = queueDepths,
        timeoutCyclesOverride = timeoutCyclesOverride,
        // Spec 3.5: one RDI state machine for the whole Link, hosted by the
        // MMPL. A one-module Link keeps its own.
        hasRdiStateMachine = !params.isMultiModule
      )
    )
    phy.suggestName(s"module_$m")
    phy
  }

  io.rdi <> mmpl.io.rdi

  for (m <- 0 until n) {
    val phy = modules(m)

    phy.io.ctrl <> io.ctrl(m)
    // Spec 4.7: each Module of a multi-module Link owns a dedicated Module ID,
    // advertised to the remote Link partner during MBINIT.PARAM.
    phy.io.ctrl.localPhyParamSettings.bits.moduleId := m.U(2.W)

    io.analog(m) <> phy.io.analog

    mmpl.io.modules(m).rdi <> phy.io.rdi
    mmpl.io.modules(m).status := phy.io.status
    phy.io.mmplCtrl <> mmpl.io.modules(m).ctrl
    mmpl.io.modules(m).rdiHost.foreach(_ <> phy.io.mmplRdiHost.get)

    io.status(m) := phy.io.status
  }

  mmpl.io.moduleConnected := io.mmplCtrl.moduleConnected

  io.mmplStatus.moduleEnable := mmpl.io.status.moduleEnable
  io.mmplStatus.linkResolution := mmpl.io.status.linkResolution
  io.mmplStatus.resolutionApplied := mmpl.io.status.resolutionApplied
}
