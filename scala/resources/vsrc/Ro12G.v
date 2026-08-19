`timescale 1ps/100fs

module Ro12G #(parameter real CLK_PERIOD_PS=83.0)(
  output Vout
);
  // Free-running ring oscillator: no enable, runs whenever powered.
  reg osc = 1'b0;
  always #(CLK_PERIOD_PS/2.0) osc = ~osc;
  assign Vout = osc;
endmodule
