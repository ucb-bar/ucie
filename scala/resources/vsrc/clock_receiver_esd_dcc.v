module clock_receiver_esd_dcc(
  input Vin,
  output Vout
);
  // Pad ESD clamp, self-biased input stage, duty-cycle correction (an even
  // number of inverters) and a CKND24 driver: inverting.
  assign Vout = ~Vin;
endmodule
