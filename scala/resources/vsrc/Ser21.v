module Ser21 (
  input  wire clk,    
  input  wire reset,  
  input  wire d0,     
  input  wire d1,     
  output wire out     
);

  reg d0_hold;
  reg d1_int;
  reg d1_hold;

  always @(*) begin
    if (reset) begin
      d0_hold = 1'b0;
    end else if (!clk) begin
      d0_hold = d0;
    end
  end

  always @(*) begin
    if (reset) begin
      d1_int = 1'b0;
    end else if (!clk) begin
      d1_int = d1;
    end
  end

  always @(*) begin
    if (reset) begin
      d1_hold = 1'b0;
    end else if (clk) begin
      d1_hold = d1_int;
    end
  end

  assign out = clk ? d1_hold : d0_hold;
endmodule