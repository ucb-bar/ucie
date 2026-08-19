module ucie_rst_sync (
  input  clk,
  input  rstbAsync,
  output rstbSync
);
  reg [2:0] ff;

  always @(posedge clk or negedge rstbAsync) begin
    if (!rstbAsync)
      ff <= 3'b000;
    else
      ff <= {ff[1:0], 1'b1};
  end

  assign rstbSync = ff[2];
endmodule
