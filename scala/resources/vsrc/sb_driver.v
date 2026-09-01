// Behavioral model of a sideband bump driver: a 2:1 serializer feeding a pad
// driver. `d0` is sent while `clk` is high and `d1` while it is low.
//
// The driver's impedance control only shapes the analog waveform, so `pu_ctl`,
// `pd_ctlb`, `en`, and `en_b` have no effect here; see `verilog/tx.sv` for the
// model that resolves them.
module sb_driver (
  input  wire clk,
  input  wire d0,
  input  wire d1,
  input  wire [39:0] pu_ctl,
  input  wire [39:0] pd_ctlb,
  input  wire en,
  input  wire en_b,
  output wire out
);

  reg d0_hold = 1'b0;
  reg d1_int = 1'b0;
  reg d1_hold = 1'b0;

  /* verilator lint_off LATCH */
  always @(*) begin
    if (!clk) begin
      d0_hold = d0;
      d1_int = d1;
    end
  end

  always @(*) begin
    if (clk) begin
      d1_hold = d1_int;
    end
  end
  /* verilator lint_on LATCH */

  assign out = clk ? d0_hold : d1_hold;
endmodule
