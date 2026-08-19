module s2d(
  input Vin,
  output Voutp,
  output Voutn
);
  assign Voutp = Vin;
  assign Voutn = ~Vin;
endmodule
