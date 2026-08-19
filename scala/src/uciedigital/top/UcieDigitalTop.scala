/*
  Description:
    UcieDigitalTop wires together the protocol layer,
    die-to-die adapter, logical PHY, and the UCIe register block.

    The main interface parameters live in UcieDigitalTopParams. In general:
    - FDI/RDI/sideband width parameters define the top-level interface shape.
    - LogicalPhyTopParams contains the primary tuning knobs for bring-up and
      training experiments, such as retry width and sideband timeout depth.
 */
package edu.berkeley.cs.uciedigital.top

import chisel3._
import chisel3.util.Decoupled
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.prci.{ClockSourceNode, ClockSourceParameters}
import edu.berkeley.cs.uciedigital.d2dadapter.D2DAdapter
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.protocol._
import edu.berkeley.cs.uciedigital.regs._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams

class UcieDigitalTopChipIO(protocolParams: ProtocolTopParams) extends Bundle {
  val mainbandTx = Flipped(
    Decoupled(new ProtocolRawBeat(protocolParams.fdi.nBytes))
  )
  val mainbandRx = Decoupled(new ProtocolRawBeat(protocolParams.fdi.nBytes))
}

class UcieDigitalTopPhyIO(afeParams: AfeParams, sbParams: SidebandParams)
    extends Bundle {
  val mainbandLink = new MainbandLaneIO(afeParams)
  val sidebandLink = new SidebandPhyLinkIO(sbParams.sbLinkWidth)
}

class UcieDigitalTopIO(params: UcieDigitalTopParams) extends Bundle {
  val chipFacingIo = new UcieDigitalTopChipIO(params.protocol)
  val phyFacingIo =
    new UcieDigitalTopPhyIO(params.logPhy.afe, params.logPhy.sideband)
  val regBlockIo =
    if (!params.regs.includeRegNode)
      Some(Flipped(new UcieRegBlockIO(params.regs)))
    else None
}

class UcieDigitalTop(
    params: UcieDigitalTopParams = UcieDigitalTopParams.default()
)(implicit p: Parameters)
    extends LazyModule {
  private val validatedParams = params.validate()
  override lazy val desiredName = "UcieDigitalTop"

  // With no node the map is spliced into an outer TLRegisterNode; expose the allocation so it can size it.
  val ucieRegAllocation = validatedParams.regs.allocation
  val regs: Option[UcieRegTop] =
    if (
      validatedParams.regs.includeRegNode || validatedParams.regs.includeInterruptNode
    )
      Some(LazyModule(new UcieRegTop(validatedParams.regs)))
    else None
  val regNode = regs.flatMap(_.node)
  val intNode = regs.flatMap(_.intNode)
  private val regClockSource = regs.map { r =>
    val src = ClockSourceNode(Seq(ClockSourceParameters()))
    r.clockNode := src
    src
  }

  override lazy val module = new UcieDigitalTopImpl
  class UcieDigitalTopImpl extends LazyModuleImp(this) {
    val io = IO(new UcieDigitalTopIO(validatedParams))

    regClockSource.foreach { src =>
      val (regClk, _) = src.out(0)
      regClk.clock := clock
      regClk.reset := reset
    }

    val protocolLayer = Module(
      new ProtocolLayer(
        params = validatedParams.protocol.layer,
        fdiParams = validatedParams.protocol.fdi,
        sbParams = validatedParams.adapter.sideband
      )
    )
    val d2dAdapter = Module(
      new D2DAdapter(
        fdiParams = validatedParams.adapter.fdi,
        rdiParams = validatedParams.adapter.rdi,
        sbParams = validatedParams.adapter.sideband
      )
    )
    val logicalPhy = Module(
      new LogicalPhy(
        afeParams = validatedParams.logPhy.afe,
        sbParams = validatedParams.logPhy.sideband,
        rdiParams = validatedParams.logPhy.rdi,
        retryW = validatedParams.logPhy.retryW,
        desTimeoutCycles = validatedParams.logPhy.desTimeoutCycles,
        queueDepths = validatedParams.logPhy.queueDepths
      )
    )

    // Internal connection
    protocolLayer.io.fdi <> d2dAdapter.io.fdi
    d2dAdapter.io.rdi <> logicalPhy.io.rdi

    // Chip-facing connection
    protocolLayer.io.mainbandTx <> io.chipFacingIo.mainbandTx
    io.chipFacingIo.mainbandRx <> protocolLayer.io.mainbandRx

    // PHY-facing connection
    io.phyFacingIo.mainbandLink <> logicalPhy.io.analog.mainband
    io.phyFacingIo.sidebandLink <> logicalPhy.io.analog.sidebandLink

    // TODO: pending connection -- status to regs. Layer ctrl/status + PHY macro ctrl/status + the
    // register-block bundles are not yet cross-wired, working on bug fixes; tie off inputs, keep outputs (dontTouch) so nothing is pruned.
    protocolLayer.io.ctrl := DontCare
    logicalPhy.io.ctrl := DontCare
    logicalPhy.io.analog.status := DontCare // PHY->logphy status
    dontTouch(protocolLayer.io.status)
    dontTouch(logicalPhy.io.status)
    dontTouch(logicalPhy.io.analog.ctrl) // logphy->PHY control

    regs.foreach { r =>
      r.module.io.linkReset := false.B
      r.module.io.adapterToRegs := 0.U.asTypeOf(new AdapterToRegs)
      r.module.io.phyToRegs := 0.U.asTypeOf(
        new PhyToRegs(validatedParams.regs.numModules)
      )
      r.module.io.linkToRegs := 0.U.asTypeOf(new LinkToRegs)
      r.module.io.mailboxSideband.req.ready := true.B
      r.module.io.mailboxSideband.resp.valid := false.B
      r.module.io.mailboxSideband.resp.bits := 0.U.asTypeOf(new MailboxSbResp)
      r.module.io.phyToVendor.foreach(_ := 0.U.asTypeOf(new PhyToVendor))
      r.module.io.d2dToVendor.foreach(_ := DontCare)
      dontTouch(r.module.io.regsToAdapter)
      dontTouch(r.module.io.regsToPhy)
      dontTouch(r.module.io.regsToLink)
      dontTouch(r.module.io.mailboxSideband.req)
      r.module.io.vendorToPhy.foreach(dontTouch(_))
      r.module.io.linkEventIrq.foreach(dontTouch(_))
      r.module.io.linkErrorIrq.foreach(dontTouch(_))
    }

    // External reg block: drive our side so the integrator only needs a single <>.
    io.regBlockIo.foreach { rb =>
      rb.linkReset := false.B
      rb.adapterToRegs := 0.U.asTypeOf(new AdapterToRegs)
      rb.phyToRegs := 0.U.asTypeOf(
        new PhyToRegs(validatedParams.regs.numModules)
      )
      rb.linkToRegs := 0.U.asTypeOf(new LinkToRegs)
      rb.mailboxSideband.req.ready := true.B
      rb.mailboxSideband.resp.valid := false.B
      rb.mailboxSideband.resp.bits := 0.U.asTypeOf(new MailboxSbResp)
      rb.phyToVendor.foreach(_ := 0.U.asTypeOf(new PhyToVendor))
      rb.d2dToVendor.foreach(_ := DontCare)
      dontTouch(rb.regsToAdapter)
      dontTouch(rb.regsToPhy)
      dontTouch(rb.regsToLink)
      dontTouch(rb.mailboxSideband.req)
      rb.vendorToPhy.foreach(dontTouch(_))
    }
  }
}
