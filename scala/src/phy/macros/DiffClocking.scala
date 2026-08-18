package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._

class DiffClkRxIO extends Bundle {
  val vip = Input(Clock())
  val vin = Input(Clock())
  val vop = Output(Clock())
  val von = Output(Clock())
}

class DiffClkRx(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new DiffClkRxIO)

  override val desiredName = "ucie_diff_clkrx"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_diff_clkrx.v")
  }
}

class DiffClkDistNetworkIO(numLanes: Int = 16) extends Bundle {
  val bypassClkP = Input(Clock())
  val bypassClkN = Input(Clock())

  val clkMuxP = Flipped(new ClkMuxClockIO)
  val clkMuxN = Flipped(new ClkMuxClockIO)

  val txClkDivClk = Output(Clock())
  val rxClkDivClk = Output(Clock())

  val rxClkP = Input(Clock())
  val rxClkN = Input(Clock())
  val txLaneClkP = Output(Vec(numLanes + 4, Clock()))
  val txLaneClkN = Output(Vec(numLanes + 4, Clock()))
  val rxLaneClk = Output(Vec(numLanes + 2, Clock()))
}

class DiffClkDistNetwork(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new DiffClkDistNetworkIO)

  val verilogBlackBox = Module(new VerilogDiffClkDistNetwork)
  verilogBlackBox.io.bypassClkP := io.bypassClkP
  verilogBlackBox.io.bypassClkN := io.bypassClkN
  io.clkMuxP <> verilogBlackBox.io.clkMuxP
  io.clkMuxN <> verilogBlackBox.io.clkMuxN
  io.txClkDivClk := verilogBlackBox.io.txClkDivClk
  io.rxClkDivClk := verilogBlackBox.io.rxClkDivClk
  verilogBlackBox.io.rxClkP := io.rxClkP
  verilogBlackBox.io.rxClkN := io.rxClkN
  io.txLaneClkP := verilogBlackBox.io.txLaneClkP.asTypeOf(io.txLaneClkP)
  io.txLaneClkN := verilogBlackBox.io.txLaneClkN.asTypeOf(io.txLaneClkN)
  io.rxLaneClk := verilogBlackBox.io.rxLaneClk.asTypeOf(io.rxLaneClk)
}

class VerilogDiffClkDistNetwork(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val bypassClkP = Input(Clock())
    val bypassClkN = Input(Clock())

    val clkMuxP = Flipped(new ClkMuxClockIO)
    val clkMuxN = Flipped(new ClkMuxClockIO)

    val txClkDivClk = Output(Clock())
    val rxClkDivClk = Output(Clock())

    val rxClkP = Input(Clock())
    val rxClkN = Input(Clock())
    val txLaneClkP = Output(UInt(20.W))
    val txLaneClkN = Output(UInt(20.W))
    val rxLaneClk = Output(UInt(18.W))
  })

  override val desiredName = "ucie_diff_clk_dist_network"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_diff_clk_dist_network.sv")
  }
}
