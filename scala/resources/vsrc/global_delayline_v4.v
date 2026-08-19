module global_delayline_v4(
  input [63:0] Dctrl,
  input Vin,
  output Vout
);
  // The programmable delay is not modeled, so Vout tracks Vin directly and
  // Dctrl is unused. The cell is non-inverting.
  assign Vout = Vin;
endmodule
