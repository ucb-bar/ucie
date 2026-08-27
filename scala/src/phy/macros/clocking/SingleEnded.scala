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
  })

  override val desiredName = "ucie_clk_dist_network"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_clk_dist_network.sv")
  }
}
