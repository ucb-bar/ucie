package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._

class ClkRxIO extends Bundle {
  val vi = Input(Clock())
  val vo = Output(Clock())
}

class ClkRx(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkRxIO)

  override val desiredName = "ucie_clkrx"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_clkrx.v")
  }
}

class ClkDistNetworkIO(numLanes: Int = 16) extends Bundle {
  val bypassClk = Input(Clock())

  val clkMux = Flipped(new ClkMuxClockIO)

  val txClkDivClk = Output(Clock())
  val rxClkDivClk = Output(Clock())

  val rxClk = Input(Clock())
  val txLaneClk = Output(Vec(numLanes + 4, Clock()))
  val rxLaneClk = Output(Vec(numLanes + 2, Clock()))
}

class ClkDistNetwork(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new ClkDistNetworkIO)

  val verilogBlackBox = Module(new VerilogClkDistNetwork)
  verilogBlackBox.io.bypassClk := io.bypassClk
  io.clkMux <> verilogBlackBox.io.clkMux
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
    val bypassClk = Input(Clock())

    val clkMux = Flipped(new ClkMuxClockIO)

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
