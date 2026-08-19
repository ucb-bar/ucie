module clocking_tile(
  input [63:0] PhaseSel,
  input [2:0] FreqSel,
  input Vin,
  output Vout
);
  // The programmable delay is not modeled, so Vout tracks Vin directly and
  // Dctrl is unused. The cell is non-inverting.
  assign Vout = Vin;
endmodule
