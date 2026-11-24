module clock_receiver_esd_drv_lowspeed(
  input Vin,
  output Vout
);
  // Pad ESD clamp, self-biased input stage and a CKND24 driver: inverting.
  assign Vout = ~Vin;
endmodule
