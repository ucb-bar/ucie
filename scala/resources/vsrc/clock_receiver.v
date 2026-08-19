module clock_receiver(
  input Vin,
  output Vout
);
  // Self-biased input stage plus a restoring inverter: non-inverting.
  assign Vout = Vin;
endmodule
