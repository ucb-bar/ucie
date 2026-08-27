module ser21 (
  input  wire clk,
  input  wire d0,
  input  wire d1,
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
