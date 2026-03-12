package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._

class ClkRxIO extends Bundle {
  val vip = Input(Clock())
  val vin = Input(Clock())
  val vop = Output(Clock())
  val von = Output(Clock())
}

class ClkRx(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new ClkRxIO)

  override val desiredName = "clkrx_with_esd"

  if (includeDefaultModels) {
    setInline(
      "clkrx_with_esd.v",
      """module clkrx_with_esd(
      | input vip, vin,
      | output vop, von
      |);
      | assign vop = vip;
      | assign von = vin;
      |endmodule
      """.stripMargin
    )
  }
}

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

class ClkMux(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new ClkMuxIO)

  override val desiredName = "ucie_clkmux"

  if (includeDefaultModels) {
    setInline(
      "ucie_clkmux.v",
      """module ucie_clkmux(
      | input in0, in1,
      | input mux0_en_0, mux0_en_1,
      | input mux1_en_0, mux1_en_1,
      | output out, outb
      |);
      | assign out = mux0_en_0 && ~mux0_en_1 ? in0 : (~mux0_en_0 && mux0_en_1 ? in1 : 1'b0);
      |endmodule
      """.stripMargin
    )
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
    with HasBlackBoxInline {
  val io = IO(new RstSyncIO)

  override val desiredName = "ucie_rst_sync"

  if (includeDefaultModels) {
    setInline(
      "ucie_rst_sync.v",
      """module ucie_rst_sync(
      | input clk,
      | input rstbAsync,
      | output rstbSync
      |);
      | reg [2:0] ff;
      | always @(negedge rstbAsync) begin
      |   ff <= 3'd0;
      | end
      | always @(posedge clk) begin
      |   ff[0] <= rstbAsync;
      |   ff[1] <= ff[0];
      |   ff[2] <= ff[1];
      | end
      | assign rstbSync = ff[2];
      |endmodule
      """.stripMargin
    )
  }
}

class Esd(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new Bundle {
    val term = Input(Bool())
  })

  override val desiredName = "ucie_esd"

  if (includeDefaultModels) {
    setInline(
      "ucie_esd.v",
      """module ucie_esd(
      | input term
      |);
      |endmodule
      """.stripMargin
    )
  }
}

class EsdRoutable(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new Bundle {
    val term = Input(Bool())
  })

  override val desiredName = "ucie_esd_routable"

  if (includeDefaultModels) {
    setInline(
      "ucie_esd_routable.v",
      """module ucie_esd_routable(
      | input term
      |);
      |endmodule
      """.stripMargin
    )
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
    with HasBlackBoxInline {
  val io = IO(new ClkDiv4IO)

  override val desiredName = "ucie_clk_div4"

  if (includeDefaultModels) {
    setInline(
      "ucie_clk_div4.v",
      """module ucie_clk_div4(
      |  input clk, resetb,
      |  output reg clkout_0, clkout_1, clkout_2, clkout_3
      |);
      |  always @(negedge resetb) begin
      |    clkout_0 <= 1'b0;
      |    clkout_1 <= 1'b0;
      |    clkout_2 <= 1'b0;
      |    clkout_3 <= 1'b0;
      |  end
      |  always @(posedge clk) begin
      |    if (resetb) begin
      |    	clkout_0 <= ~clkout_0;
      |    end
      |  end
      |  always @(posedge clkout_0) begin
      |    clkout_1 <= ~clkout_1;
      |  end
      |  always @(posedge clkout_1) begin
      |    clkout_2 <= ~clkout_2;
      |  end
      |  always @(posedge clkout_2) begin
      |    clkout_3 <= ~clkout_3;
      |  end
      |endmodule
      """.stripMargin
    )
  }
}

class ClkGateIO extends Bundle {
  val clk = Input(Clock())
  val en = Input(Bool())
  val gated_clk = Output(Clock())
}

class ClkGate(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new ClkGateIO)

  override val desiredName = "ucie_clk_gate"

  if (includeDefaultModels) {
    setInline(
      "ucie_clk_gate.sv",
      """module ucie_clk_gate(
    input  logic clk,
    input  logic en,
    output logic gated_clk
);

logic en_latched;

always_latch begin
    if (!clk)
        en_latched = en;
end

assign gated_clk = clk & en_latched;

endmodule
      """.trim
    )
  }
}

class ClkDistNetworkIO(numLanes: Int = 16) extends Bundle {
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

class ClkDistNetwork(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new ClkDistNetworkIO)

  val verilogBlackBox = Module(new VerilogClkDistNetwork)
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

class VerilogClkDistNetwork(implicit includeDefaultModels: Boolean = false)
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

  override val desiredName = "ucie_clk_dist_network"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_clk_dist_network.v")
  }
}
