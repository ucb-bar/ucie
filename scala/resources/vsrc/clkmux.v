module clkmux(
  input Vinp,
  input Vinn,
  input sel,
  input selb,
  output Vout
);
  // Complementary pass gates select one input; driving sel and selb the same
  // way either floats or shorts the internal node.
  wire selected = (sel && !selb) ? Vinp : ((!sel && selb) ? Vinn : 1'bx);
  // Shared output inverter.
  assign Vout = ~selected;
endmodule
