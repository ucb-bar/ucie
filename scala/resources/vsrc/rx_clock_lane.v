module rx_clock_lane (
   input clkin,
   output clkout,
   input clk_gate_en,
   input zen,
   input zctl_0,
   input zctl_1,
   input zctl_2,
   input zctl_3,
   input zctl_4,
   input zctl_5,
   input zctl_6,
   input zctl_7,
   input zctl_8,
   input zctl_9,
   input zctl_10,
   input zctl_11,
   input zctl_12,
   input zctl_13,
   input zctl_14,
   input zctl_15,
   input zctl_16,
   input zctl_17,
   input zctl_18,
   input zctl_19,
   input a_en,
   input a_pc,
   input b_en,
   input b_pc,
   input sel_a,
   input vref_sel_0,
   input vref_sel_1,
   input vref_sel_2,
   input vref_sel_3,
   input vref_sel_4,
   input vref_sel_5,
   input vref_sel_6
);
  // Active-high enable for the recovered clock leaving this lane. Low stops
  // the clock reaching the distribution tree, and so none of the data lanes
  // are clocked. Latched on the low phase so toggling it leaves no runt.
  wire clkout_raw = clkin;
  reg gate_en_latched;
  always @(*) begin
    if (!clkout_raw) gate_en_latched = clk_gate_en;
  end
  assign clkout = clkout_raw & gate_en_latched;
endmodule
