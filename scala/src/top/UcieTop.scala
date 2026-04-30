/*
  Description:
    UcieTop is the integration wrapper that wires together the protocol layer,
    die-to-die adapter, and logical PHY into a single top-level module.

    The main interface parameters live in UcieTopParams. In general:
    - FDI/RDI/sideband width parameters define the top-level interface shape.
    - LogicalPhyTopParams contains the primary tuning knobs for bring-up and
      training experiments, such as retry width and sideband timeout depth.
*/
package edu.berkeley.cs.uciedigital.top

import chisel3._
import chisel3.util.Decoupled
import edu.berkeley.cs.uciedigital.d2dadapter.D2DAdapter
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.protocol._

class UcieTopProtocolIO(protocolParams: ProtocolTopParams) extends Bundle {
  val ctrl = new ProtocolLayerCtrlIO()
  val status = new ProtocolLayerStatusIO()
  val mainbandTx = Flipped(Decoupled(new ProtocolRawBeat(protocolParams.fdi.nBytes)))
  val mainbandRx = Decoupled(new ProtocolRawBeat(protocolParams.fdi.nBytes))
}

class UcieTopIO(params: UcieTopParams) extends Bundle {
  val protocol = new UcieTopProtocolIO(params.protocol)
  val logPhy = new Bundle {
    val ctrl = new LogicalPhyCtrlIO(params.logPhy.retryW)
    val status = new LogicalPhyStatusIO()
  }
  val analog = new LogicalPhyAnalogIO(params.logPhy.afe, params.logPhy.sideband)
}

class UcieTop(params: UcieTopParams = UcieTopParams.default()) extends Module {
  private val validatedParams = params.validate()

  val io = IO(new UcieTopIO(validatedParams))

  val protocolLayer = Module(new ProtocolLayer(
    params = validatedParams.protocol.layer,
    fdiParams = validatedParams.protocol.fdi,
    sbParams = validatedParams.adapter.sideband
  ))
  val d2dAdapter = Module(new D2DAdapter(
    fdiParams = validatedParams.adapter.fdi,
    rdiParams = validatedParams.adapter.rdi,
    sbParams = validatedParams.adapter.sideband
  ))
  val logicalPhy = Module(new LogicalPhy(
    afeParams = validatedParams.logPhy.afe,
    sbParams = validatedParams.logPhy.sideband,
    rdiParams = validatedParams.logPhy.rdi,
    retryW = validatedParams.logPhy.retryW,
    desTimeoutCycles = validatedParams.logPhy.desTimeoutCycles,
    queueDepths = validatedParams.logPhy.queueDepths
  ))

  protocolLayer.io.fdi <> d2dAdapter.io.fdi
  d2dAdapter.io.rdi <> logicalPhy.io.rdi

  protocolLayer.io.ctrl <> io.protocol.ctrl
  io.protocol.status <> protocolLayer.io.status
  protocolLayer.io.mainbandTx <> io.protocol.mainbandTx
  io.protocol.mainbandRx <> protocolLayer.io.mainbandRx

  logicalPhy.io.ctrl <> io.logPhy.ctrl
  io.logPhy.status <> logicalPhy.io.status
  io.analog <> logicalPhy.io.analog
}
