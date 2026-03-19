module ucie_clk_div4(
   input clk, resetb,
   output reg clkout_0, clkout_1, clkout_2, clkout_3
);
   always @(negedge resetb) begin
     clkout_0 <= 1'b0;
     clkout_1 <= 1'b0;
     clkout_2 <= 1'b0;
     clkout_3 <= 1'b0;
   end
   always @(posedge clk) begin
     if (resetb) begin
     	clkout_0 <= ~clkout_0;
     end
   end
   always @(posedge clkout_0) begin
     clkout_1 <= ~clkout_1;
   end
   always @(posedge clkout_1) begin
     clkout_2 <= ~clkout_2;
   end
   always @(posedge clkout_2) begin
     clkout_3 <= ~clkout_3;
   end
endmodule
