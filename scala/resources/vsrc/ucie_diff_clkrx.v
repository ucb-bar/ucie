module ucie_diff_clkrx(
  input vip, vin,
  output vop, von
);
  assign vop = vip;
  assign von = vin;
endmodule
