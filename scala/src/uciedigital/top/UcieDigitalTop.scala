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
  val ctrl = new UcieRegBridgeCtrlIO(params.logPhy.retryW)
}

class UcieDigitalTop(
    params: UcieDigitalTopParams = UcieDigitalTopParams.default()
)(implicit p: Parameters)
    extends LazyModule {
  private val validatedParams = params.validate()
  require(
    validatedParams.regs.includeRegNode ||
      !validatedParams.regs.includeInterruptNode,
    "includeInterruptNode without includeRegNode builds a register map that no bus can reach"
  )
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

    val regBridge = Module(
      new UcieRegBridge(
        params = validatedParams.regs,
        afeParams = validatedParams.logPhy.afe,
        retryW = validatedParams.logPhy.retryW
      )
    )
    regBridge.io.ctrl <> io.ctrl
    regBridge.io.phyCtrl <> logicalPhy.io.ctrl
    regBridge.io.phyStatus <> logicalPhy.io.status
    regBridge.io.protoCtrl <> protocolLayer.io.ctrl
    regBridge.io.protoStatus <> protocolLayer.io.status
    regBridge.io.adapter <> d2dAdapter.io.regs

    // The internal register block and the external splice port are mutually exclusive.
    regs match {
      case Some(r) => UcieRegBridge.attach(r.module.io, regBridge.io.regs)
      case None =>
        io.regBlockIo.foreach(UcieRegBridge.attach(_, regBridge.io.regs))
    }
    regs.foreach { r =>
      r.module.io.linkEventIrq.foreach(dontTouch(_))
      r.module.io.linkErrorIrq.foreach(dontTouch(_))
    }

    // TODO: PHY macro control/status is owned by the analog register file, not the UCIe block.
    logicalPhy.io.analog.status := DontCare
    dontTouch(logicalPhy.io.analog.ctrl)
  }
}
