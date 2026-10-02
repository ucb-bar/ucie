// The UCIe RX clock tile: the same front end as a data tile, recovering a
// forwarded clock rather than a word, with a gate on the clock it hands out --
// plus the MBINIT.REPAIRCLK sampling tap.
//
// The tap is a 1:32 deserializer hung off the same front-end output that feeds
// the clock tree, clocked by the gated repair clock. Two things make it
// necessary rather than merely convenient:
//
//   - A forwarded-clock lane cannot be sampled by the clock it is recovering.
//     That is circular, and if this is the lane that is broken there is no
//     recovered clock at all. The repair clock is locally sourced, so it works
//     either way.
//   - REPAIRCLK runs before any sampling phase has been trained, so a sample
//     taken once per UI lands at an arbitrary phase. The repair clock is a
//     faster division of the same main clock, so the tap oversamples and the
//     measurement stops depending on phase.
//
// The tap takes `clkout_raw`, ahead of the gate: the recovered clock is not
// distributed during REPAIRCLK, and the measurement has to work anyway.
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
   input vref_sel_6,
   input repair_clk,
   input repair_rstb,
   input repair_Dctrl_0,
   input repair_Dctrl_1,
   input repair_Dctrl_2,
   input repair_Dctrl_3,
   input repair_Dctrl_4,
   input repair_Dctrl_5,
   input repair_Dctrl_6,
   input repair_Dctrl_7,
   input repair_Dctrl_8,
   input repair_Dctrl_9,
   input repair_Dctrl_10,
   input repair_Dctrl_11,
   input repair_Dctrl_12,
   input repair_Dctrl_13,
   input repair_Dctrl_14,
   input repair_Dctrl_15,
   input repair_Dctrl_16,
   input repair_Dctrl_17,
   input repair_Dctrl_18,
   input repair_Dctrl_19,
   input repair_Dctrl_20,
   input repair_Dctrl_21,
   input repair_Dctrl_22,
   input repair_Dctrl_23,
   input repair_Dctrl_24,
   input repair_Dctrl_25,
   input repair_Dctrl_26,
   input repair_Dctrl_27,
   input repair_Dctrl_28,
   input repair_Dctrl_29,
   input repair_Dctrl_30,
   input repair_Dctrl_31,
   output repair_dout_0,
   output repair_dout_1,
   output repair_dout_2,
   output repair_dout_3,
   output repair_dout_4,
   output repair_dout_5,
   output repair_dout_6,
   output repair_dout_7,
   output repair_dout_8,
   output repair_dout_9,
   output repair_dout_10,
   output repair_dout_11,
   output repair_dout_12,
   output repair_dout_13,
   output repair_dout_14,
   output repair_dout_15,
   output repair_dout_16,
   output repair_dout_17,
   output repair_dout_18,
   output repair_dout_19,
   output repair_dout_20,
   output repair_dout_21,
   output repair_dout_22,
   output repair_dout_23,
   output repair_dout_24,
   output repair_dout_25,
   output repair_dout_26,
   output repair_dout_27,
   output repair_dout_28,
   output repair_dout_29,
   output repair_dout_30,
   output repair_dout_31,
   output repair_divclk
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

  wire [31:0] repair_dout_bus;
  ucie_des32 repair_des (
    .din(clkout_raw),
    .clk(repair_clk),
    .rstb(repair_rstb),
    .dout(repair_dout_bus),
    .divclk(repair_divclk)
  );
  assign repair_dout_0 = repair_dout_bus[0];
  assign repair_dout_1 = repair_dout_bus[1];
  assign repair_dout_2 = repair_dout_bus[2];
  assign repair_dout_3 = repair_dout_bus[3];
  assign repair_dout_4 = repair_dout_bus[4];
  assign repair_dout_5 = repair_dout_bus[5];
  assign repair_dout_6 = repair_dout_bus[6];
  assign repair_dout_7 = repair_dout_bus[7];
  assign repair_dout_8 = repair_dout_bus[8];
  assign repair_dout_9 = repair_dout_bus[9];
  assign repair_dout_10 = repair_dout_bus[10];
  assign repair_dout_11 = repair_dout_bus[11];
  assign repair_dout_12 = repair_dout_bus[12];
  assign repair_dout_13 = repair_dout_bus[13];
  assign repair_dout_14 = repair_dout_bus[14];
  assign repair_dout_15 = repair_dout_bus[15];
  assign repair_dout_16 = repair_dout_bus[16];
  assign repair_dout_17 = repair_dout_bus[17];
  assign repair_dout_18 = repair_dout_bus[18];
  assign repair_dout_19 = repair_dout_bus[19];
  assign repair_dout_20 = repair_dout_bus[20];
  assign repair_dout_21 = repair_dout_bus[21];
  assign repair_dout_22 = repair_dout_bus[22];
  assign repair_dout_23 = repair_dout_bus[23];
  assign repair_dout_24 = repair_dout_bus[24];
  assign repair_dout_25 = repair_dout_bus[25];
  assign repair_dout_26 = repair_dout_bus[26];
  assign repair_dout_27 = repair_dout_bus[27];
  assign repair_dout_28 = repair_dout_bus[28];
  assign repair_dout_29 = repair_dout_bus[29];
  assign repair_dout_30 = repair_dout_bus[30];
  assign repair_dout_31 = repair_dout_bus[31];
  wire _unused_repair_dctrl = &{1'b0, repair_Dctrl_0, repair_Dctrl_1, repair_Dctrl_2, repair_Dctrl_3, repair_Dctrl_4, repair_Dctrl_5, repair_Dctrl_6, repair_Dctrl_7, repair_Dctrl_8, repair_Dctrl_9, repair_Dctrl_10, repair_Dctrl_11, repair_Dctrl_12, repair_Dctrl_13, repair_Dctrl_14, repair_Dctrl_15, repair_Dctrl_16, repair_Dctrl_17, repair_Dctrl_18, repair_Dctrl_19, repair_Dctrl_20, repair_Dctrl_21, repair_Dctrl_22, repair_Dctrl_23, repair_Dctrl_24, repair_Dctrl_25, repair_Dctrl_26, repair_Dctrl_27, repair_Dctrl_28, repair_Dctrl_29, repair_Dctrl_30, repair_Dctrl_31};
endmodule
