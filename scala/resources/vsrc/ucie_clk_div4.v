// Divide-by-16 ripple cascade: four toggle stages, each clocked by the one
// before it, so `clkout_3` is the input divided by 16.
//
// Every stage takes `resetb` asynchronously. The divider has to clear with no
// clock present: a TX delay code is changed by gating the clock off, asserting
// this reset, releasing it, and only then letting the clock run again, so that
// every divider in the PHY restarts on the same first edge afterwards. A reset
// that needed an edge of its own would never be applied inside that window,
// and the stages would come back holding whatever phase they had.
//
// All four stages leave reset at zero and so rise together on the first input
// edge; `Phy` depends on that to place `clkout_3` half a word period from the
// edge the lane serdes loads on.
module ucie_clk_div4(
   input clk, resetb,
   output reg clkout_0, clkout_1, clkout_2, clkout_3
);
   always @(posedge clk or negedge resetb) begin
     if (!resetb) clkout_0 <= 1'b0;
     else         clkout_0 <= ~clkout_0;
   end
   always @(posedge clkout_0 or negedge resetb) begin
     if (!resetb) clkout_1 <= 1'b0;
     else         clkout_1 <= ~clkout_1;
   end
   always @(posedge clkout_1 or negedge resetb) begin
     if (!resetb) clkout_2 <= 1'b0;
     else         clkout_2 <= ~clkout_2;
   end
   always @(posedge clkout_2 or negedge resetb) begin
     if (!resetb) clkout_3 <= 1'b0;
     else         clkout_3 <= ~clkout_3;
   end
endmodule
