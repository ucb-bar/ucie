// Behavioral model of a pad output driver.
//
// The impedance codes only shape the analog waveform, so `pu_ctl`, `pd_ctlb`,
// `en`, and `en_b` have no effect here; see `verilog/tx.vams` for the model
// that resolves them.
module pad_driver (
   input din,
   output dout,
   input en,
   input en_b,
   input [39:0] pu_ctl,
   input [39:0] pd_ctlb
);
  assign dout = din;
endmodule
