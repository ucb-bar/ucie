package edu.berkeley.cs.uciedigital.phy.macros.clocking

import chisel3._
import chisel3.util._

class ClkRxIO extends Bundle {
  val Vin = Input(Clock())
  val Vout = Output(Clock())
}

// Self-biased inverter input stage followed by a restoring inverter, so the
// receiver is non-inverting. Port and module names match the analog IP top
// cell; the VDD/VSS pins are connected by the physical flow.
class ClkRx(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkRxIO)

  override val desiredName = "clock_receiver"

  if (includeDefaultModels) {
    addResource("/vsrc/clock_receiver.v")
  }
}

class ClkDistNetworkIO(numLanes: Int = 16) extends Bundle {
  // In-phase TX clock, distributed to the data, valid, and track lanes.
  val txClk = Input(Clock())
  // Quadrature TX clock, distributed to the two forwarded-clock lanes so that
  // the transmitted clock lands in the center of the data eye.
  val txClkQ = Input(Clock())

  val txClkDivClk = Output(Clock())
  val rxClkDivClk = Output(Clock())

  val rxClk = Input(Clock())
  // Every lane clock is single-ended; each TX tile makes its own complement.
  val txLaneClk = Output(Vec(numLanes + 4, Clock()))
  val rxLaneClk = Output(Vec(numLanes + 2, Clock()))

  // MBINIT.REPAIRCLK sampling clock, fanned out to just the three lanes that
  // stage measures, in `ClkDistNetwork.repairLanes` order. Its own small net
  // rather than an entry on the RX tree: the trained RX datapath's loading and
  // skew should not move for a clock that runs during one training stage.
  val repairClk = Input(Clock())
  val repairLaneClk = Output(Vec(ClkDistNetwork.repairLanes, Clock()))
  val repairClkDivClk = Output(Clock())
}

object ClkDistNetwork {

  /** Lanes the repair net reaches, and the order every per-lane repair vector
    * in the PHY and the register map uses: 0 clkP, 1 clkN, 2 track. Same order
    * as the three status bits MBINIT.REPAIRCLK reports over the sideband.
    */
  val repairLanes = 3
  val repairClkP = 0
  val repairClkN = 1
  val repairTrack = 2
}

class ClkDistNetwork(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new ClkDistNetworkIO)

  val verilogBlackBox = Module(new VerilogClkDistNetwork)
  verilogBlackBox.io.txClk := io.txClk
  verilogBlackBox.io.txClkQ := io.txClkQ
  io.txClkDivClk := verilogBlackBox.io.txClkDivClk
  io.rxClkDivClk := verilogBlackBox.io.rxClkDivClk
  verilogBlackBox.io.rxClk := io.rxClk
  io.txLaneClk := verilogBlackBox.io.txLaneClk.asTypeOf(io.txLaneClk)
  io.rxLaneClk := verilogBlackBox.io.rxLaneClk.asTypeOf(io.rxLaneClk)
  verilogBlackBox.io.repairClk := io.repairClk
  io.repairLaneClk := verilogBlackBox.io.repairLaneClk.asTypeOf(
    io.repairLaneClk
  )
  io.repairClkDivClk := verilogBlackBox.io.repairClkDivClk
}

class VerilogClkDistNetwork(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val txClk = Input(Clock())
    val txClkQ = Input(Clock())

    val txClkDivClk = Output(Clock())
    val rxClkDivClk = Output(Clock())

    val rxClk = Input(Clock())
    val txLaneClk = Output(UInt(20.W))
    val rxLaneClk = Output(UInt(18.W))

    val repairClk = Input(Clock())
    val repairLaneClk = Output(UInt(ClkDistNetwork.repairLanes.W))
    val repairClkDivClk = Output(Clock())
  })

  override val desiredName = "ucie_clk_dist_network"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_clk_dist_network.sv")
  }
}
