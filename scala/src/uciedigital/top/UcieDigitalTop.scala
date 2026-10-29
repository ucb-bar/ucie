/*
  The top level of the UCIe digital stack.
 */
package edu.berkeley.cs.uciedigital.top

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util.{Decoupled, Valid}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.prci.{ClockSourceNode, ClockSourceParameters}
import edu.berkeley.cs.uciedigital.d2dadapter.D2DAdapter
import edu.berkeley.cs.uciedigital.common.MainbandClkRstIO
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.protocollayer._
import edu.berkeley.cs.uciedigital.regs._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams

class UcieDigitalTopChipIO(protocolParams: ProtocolTopParams) extends Bundle {
  val mainbandTx = Flipped(
    Decoupled(new ProtocolRawBeat(protocolParams.fdi.nBytes))
  )
  val mainbandRx = Valid(new ProtocolRawBeat(protocolParams.fdi.nBytes))
  val clkRst = Output(new MainbandClkRstIO)

  val rxOverflow = Input(Bool())
}

class UcieDigitalTopPhyIO(
    afeParams: AfeParams,
    sbParams: SidebandParams,
    separateDivClocks: Boolean = false
) extends Bundle {
  val mainbandLink = new MainbandLaneIO(afeParams)
  val sidebandLink = new SidebandPhyLinkIO(sbParams.sbLinkWidth)
  val phy = new PhyBridgeAnalogIO(afeParams)
  val clkRst =
    if (separateDivClocks) Some(new MainbandClkRstIO) else None
}

class UcieDigitalTopIO(params: UcieDigitalTopParams) extends Bundle {
  val chipFacingIo = new UcieDigitalTopChipIO(params.protocol)
  val phyFacingIo =
    new UcieDigitalTopPhyIO(
      params.logPhy.afe,
      params.logPhy.sideband,
      params.logPhy.separateDivClocks
    )
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
  val validatedParams = params.validate()
  require(
    validatedParams.regs.includeRegNode ||
      !validatedParams.regs.includeInterruptNode,
    "includeInterruptNode without includeRegNode builds a register map that no bus can reach"
  )
  override lazy val desiredName = "UcieDigitalTop"

  // With no node, an outer TLRegisterNode takes the map and sizes it from this.
  val ucieRegAllocation = validatedParams.regs.allocation
  val regs: Option[UcieRegTop] =
    if (
      validatedParams.regs.includeRegNode || validatedParams.regs.includeInterruptNode
    )
      Some(LazyModule(new UcieRegTop(validatedParams.regs)))
    else None
  val regNode = regs.flatMap(_.node)
  val intNode = regs.flatMap(_.intNode)
  val regClockSource = regs.map { r =>
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

    // =========================================================================
    // Modules
    // =========================================================================
    val separateDivClocks = validatedParams.logPhy.separateDivClocks
    val protocolLayer = Module(
      new ProtocolLayer(
        fdiParams = validatedParams.protocol.fdi,
        sbParams = validatedParams.adapter.sideband,
        separateDivClocks = separateDivClocks
      )
    )
    val d2dAdapter = Module(
      new D2DAdapter(
        fdiParams = validatedParams.adapter.fdi,
        rdiParams = validatedParams.adapter.rdi,
        sbParams = validatedParams.adapter.sideband,
        regLocators = validatedParams.regs.allocation.locators.map {
          case (_, base, size) => (base, size)
        },
        separateDivClocks = separateDivClocks
      )
    )
    val logicalPhy = Module(
      new LogicalPhy(
        afeParams = validatedParams.logPhy.afe,
        sbParams = validatedParams.logPhy.sideband,
        rdiParams = validatedParams.logPhy.rdi,
        retryW = validatedParams.logPhy.retryW,
        desTimeoutCycles = validatedParams.logPhy.desTimeoutCycles,
        queueDepths = validatedParams.logPhy.queueDepths,
        separateDivClocks = separateDivClocks
      )
    )

    // =========================================================================
    // Clocks and resets
    // =========================================================================
    // The chip's mainband queue ends take these too. With no PHY port (an
    // undriven Input Clock fails elaboration), tie both domains to the control
    // clock, the single-lclk RDI of sec 10.1.2.
    val clkRst = io.chipFacingIo.clkRst
    io.phyFacingIo.clkRst match {
      case Some(phyClkRst) =>
        clkRst := phyClkRst
      case None =>
        clkRst.txDivClk := clock
        clkRst.txDivRst := reset.asBool
        clkRst.rxDivClk := clock
        clkRst.rxDivRst := reset.asBool
    }
    logicalPhy.io.clkRst.txDivClk := clkRst.txDivClk
    logicalPhy.io.clkRst.txDivRst := clkRst.txDivRst
    logicalPhy.io.clkRst.rxDivClk := clkRst.rxDivClk
    logicalPhy.io.clkRst.rxDivRst := clkRst.rxDivRst
    protocolLayer.io.clkRst := clkRst
    d2dAdapter.io.clkRst := clkRst

    // =========================================================================
    // Connections
    // =========================================================================
    protocolLayer.io.fdi <> d2dAdapter.io.fdi
    d2dAdapter.io.rdi <> logicalPhy.io.rdi

    protocolLayer.io.mainbandTx <> io.chipFacingIo.mainbandTx
    io.chipFacingIo.mainbandRx <> protocolLayer.io.mainbandRx

    io.phyFacingIo.mainbandLink <> logicalPhy.io.analog.mainband
    io.phyFacingIo.sidebandLink <> logicalPhy.io.analog.sidebandLink
    io.phyFacingIo.phy <> logicalPhy.io.analog.phy

    // =========================================================================
    // Register bridge
    // =========================================================================
    val regBridge = Module(
      new UcieRegBridge(
        params = validatedParams.regs,
        afeParams = validatedParams.logPhy.afe,
        retryW = validatedParams.logPhy.retryW
      )
    )
    regBridge.io.ctrl <> io.ctrl

    // sec 10.2.4 gives no receive buffer, so the chip reports its own overflow.
    // A level, because a one-rxDivClk pulse can fall between two control edges.
    regBridge.io.chip.rxOverflow := io.chipFacingIo.rxOverflow
    regBridge.io.phyCtrl <> logicalPhy.io.ctrl
    regBridge.io.phyStatus <> logicalPhy.io.status
    regBridge.io.protoCtrl <> protocolLayer.io.ctrl
    regBridge.io.protoStatus <> protocolLayer.io.status
    regBridge.io.adapter <> d2dAdapter.io.regs

    // The register block and the splice port are mutually exclusive.
    regs match {
      case Some(r) => UcieRegBridge.attach(r.module.io, regBridge.io.regs)
      case None =>
        io.regBlockIo.foreach(UcieRegBridge.attach(_, regBridge.io.regs))
    }
    regs.foreach { r =>
      r.module.io.linkEventIrq.foreach(dontTouch(_))
      r.module.io.linkErrorIrq.foreach(dontTouch(_))
    }
  }
}
