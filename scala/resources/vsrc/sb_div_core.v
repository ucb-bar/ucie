// Behavioral model of the TX sideband divider, `sb_div_core`.
//
// A source mux, not on the cell yet, as `phytest_div_core`'s: `sb_src_sel` 0
// PLL8, 1 PLL12, 2 and 3 the analog bypass pin. Then, as in the cell: a /5,
// and /15 and /10 off it, a /3 and a /2, all free running and none of them
// reset. `sb_sel` picks /5, /10, the bypass pin or /15 (0 to 3). The /5 and /3
// are the cell's fast dividers, high two cycles in five and one in three, so
// /5 is high 2 source cycles of the 5 and /15 5 of the 15; /10 is even.
//
// The gate is `phytest_div_core`'s: `sbClkEn` through three flops on the muxed
// clock's falling edge, and `SbClk` the gated clock inverted, high while shut.
module sb_div_core(
  input PLLclk_8G,
  input PLLclk_12G,
  input clk_bypass_analog,
  input [1:0] sb_src_sel,
  input clk_bypass,
  input [1:0] sb_sel,
  input sbClkEn,
  output SbClk
);
  wire clk_in = (sb_src_sel == 2'd0) ? PLLclk_8G :
                (sb_src_sel == 2'd1) ? PLLclk_12G : clk_bypass_analog;

  // /5.
  reg d5_1 = 1'b0;
  reg d5_2 = 1'b0;
  reg clk_div5 = 1'b0;
  always @(posedge clk_in) begin
    d5_1 <= !(clk_div5 | d5_2);
    d5_2 <= d5_1;
    clk_div5 <= d5_2;
  end
  // /15, a /3 off the /5.
  reg d3_q0 = 1'b0;
  reg clk_div15 = 1'b0;
  always @(posedge clk_div5) begin
    d3_q0 <= !(d3_q0 | clk_div15);
    clk_div15 <= d3_q0;
  end
  // /10, a /2 off the /5.
  reg clk_div10 = 1'b0;
  always @(posedge clk_div5) clk_div10 <= ~clk_div10;

  wire clk_muxed = (sb_sel == 2'd0) ? clk_div5 :
                   (sb_sel == 2'd1) ? clk_div10 :
                   (sb_sel == 2'd2) ? clk_bypass : clk_div15;

  reg en_1 = 1'b0;
  reg en_2 = 1'b0;
  reg en_3 = 1'b0;
  always @(negedge clk_muxed) begin
    en_1 <= sbClkEn;
    en_2 <= en_1;
    en_3 <= en_2;
  end
  assign SbClk = !(clk_muxed & en_3);
endmodule
