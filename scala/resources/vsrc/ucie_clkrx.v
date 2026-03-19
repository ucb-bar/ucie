module ucie_clkrx(
  input vip, vin,
  output vop, von
);
  assign vop = vip;
  assign von = vin;
endmodule
