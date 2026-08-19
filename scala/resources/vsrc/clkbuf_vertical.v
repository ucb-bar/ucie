module clkbuf_vertical(
  input Vin,
  output Vout
);
  // Single inverting stage; horizontal and vertical differ only in layout.
  assign Vout = ~Vin;
endmodule
