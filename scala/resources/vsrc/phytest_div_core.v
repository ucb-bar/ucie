// Behavioral model of the PHY test divider, `phytest_div_core`.
//
// A source mux, not on the cell yet, picks what the dividers divide: `src_sel`
// 0 PLL8, 1 PLL12, 2 and 3 the analog bypass pin. Then, as in the cell: /6 and
// /12 off a /3 and two /2s, /15 off the /3 and a /5, and /8 off three /2s, all
// free running and none of them reset. `sel[1:0]` picks /6, /8, /15 or /12 (0
// to 3), and `sel[2]` the divided clock, high, or the bypass pin, low. The /3
// and /5 are the cell's fast dividers, flops and a NOR in a loop, high one
// cycle in three and two in five, so /15 is high 6 source cycles of the 15;
// the /2s are even, and so are /6, /8 and /12.
//
// The gate takes `phyTestClkEn` through three flops on the muxed clock's
// falling edge and ANDs the third with that clock, so it opens and shuts only
// while the clock is low, three to four periods after the enable moves -- and
// not at all while the clock is stopped. `PhyTestClk` is the gated clock
// inverted, as in the cell, so it sits high while the gate is shut.
module phytest_div_core(
  input PLLclk_8G,
  input PLLclk_12G,
  input clk_bypass_analog,
  input [1:0] src_sel,
  input clk_bypass,
  input [2:0] sel,
  input phyTestClkEn,
  output PhyTestClk
);
  wire clk_in = (src_sel == 2'd0) ? PLLclk_8G :
                (src_sel == 2'd1) ? PLLclk_12G : clk_bypass_analog;

  // /3.
  reg d3_q0 = 1'b0;
  reg clk_div3 = 1'b0;
  always @(posedge clk_in) begin
    d3_q0 <= !(d3_q0 | clk_div3);
    clk_div3 <= d3_q0;
  end
  // /6 and /12, off the /3.
  reg clk_div6 = 1'b0;
  reg clk_div12 = 1'b0;
  always @(posedge clk_div3) clk_div6 <= ~clk_div6;
  always @(posedge clk_div6) clk_div12 <= ~clk_div12;
  // /15, a /5 off the /3.
  reg d5_1 = 1'b0;
  reg d5_2 = 1'b0;
  reg clk_div15 = 1'b0;
  always @(posedge clk_div3) begin
    d5_1 <= !(clk_div15 | d5_2);
    d5_2 <= d5_1;
    clk_div15 <= d5_2;
  end
  // /8, three /2s.
  reg d8_0 = 1'b0;
  reg d8_1 = 1'b0;
  reg clk_div8 = 1'b0;
  always @(posedge clk_in) d8_0 <= ~d8_0;
  always @(posedge d8_0) d8_1 <= ~d8_1;
  always @(posedge d8_1) clk_div8 <= ~clk_div8;

  wire clk_ratio = (sel[1:0] == 2'd0) ? clk_div6 :
                   (sel[1:0] == 2'd1) ? clk_div8 :
                   (sel[1:0] == 2'd2) ? clk_div15 : clk_div12;
  wire clk_muxed = sel[2] ? clk_ratio : clk_bypass;

  reg en_1 = 1'b0;
  reg en_2 = 1'b0;
  reg en_3 = 1'b0;
  always @(negedge clk_muxed) begin
    en_1 <= phyTestClkEn;
    en_2 <= en_1;
    en_3 <= en_2;
  end
  assign PhyTestClk = !(clk_muxed & en_3);
endmodule
