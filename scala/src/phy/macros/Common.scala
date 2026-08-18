package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._

class ClkMuxClockIO extends Bundle {
  val in0 = Input(Clock())
  val in1 = Input(Clock())
  val out = Output(Clock())
}

class ClkMuxIO extends Bundle {
  val in0 = Input(Clock())
  val in1 = Input(Clock())
  val mux0_en_0 = Input(Bool())
  val mux0_en_1 = Input(Bool())
  val mux1_en_0 = Input(Bool())
  val mux1_en_1 = Input(Bool())
  val out = Output(Clock())
  val outb = Output(Clock())
}

// Single-ended 2:1 clock mux cell. Differential clocking instantiates one per
// polarity; single-ended clocking instantiates one.
class ClkMux(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkMuxIO)

  override val desiredName = "ucie_clkmux"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_clkmux.v")
  }

  def connect(clocks: ClkMuxClockIO, sel1: Bool) = {
    io.in0 := clocks.in0
    io.in1 := clocks.in1
    io.mux0_en_0 := ~sel1
    io.mux0_en_1 := sel1
    io.mux1_en_0 := false.B
    io.mux1_en_1 := false.B
    clocks.out := io.out
  }
}

class RstSyncIO extends Bundle {
  val clk = Input(Clock())
  val rstbAsync = Input(Bool())
  val rstbSync = Output(Bool())
}

class RstSync(implicit includeDefaultModels: Boolean = true)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new RstSyncIO)

  override val desiredName = "ucie_rst_sync"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_rst_sync.v")
  }
}

class Esd(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val term = Input(Bool())
  })

  override val desiredName = "ucie_esd"

  if (includeDefaultModels) {
    addResource("ucie_esd.v")
  }
}

class EsdRoutable(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val term = Input(Bool())
  })

  override val desiredName = "ucie_esd_routable"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_esd_routable.v")
  }
}

class ClkDiv4IO extends Bundle {
  val clk = Input(Clock())
  val resetb = Input(AsyncReset())
  val clkout_0 = Output(Clock())
  val clkout_1 = Output(Clock())
  val clkout_2 = Output(Clock())
  val clkout_3 = Output(Clock())
}

class ClkDiv4(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkDiv4IO)

  override val desiredName = "ucie_clk_div4"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_clk_div4.v")
  }
}

class ClkGateIO extends Bundle {
  val clk = Input(Clock())
  val en = Input(Bool())
  val gated_clk = Output(Clock())
}

class ClkGate(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkGateIO)

  override val desiredName = "ucie_clk_gate"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_clk_gate.sv")
  }
}
