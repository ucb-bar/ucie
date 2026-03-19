module ucie_rst_sync(
  input clk,
  input rstbAsync,
  output rstbSync
);
  reg [2:0] ff;
  always @(negedge rstbAsync) begin
    ff <= 3'd0;
  end
  always @(posedge clk) begin
    ff[0] <= rstbAsync;
    ff[1] <= ff[0];
    ff[2] <= ff[1];
  end
  assign rstbSync = ff[2];
endmodule
