// The UCIe RX data tile: termination, the reference ladder, the sampling front
// end, a local delay line on the sampling clock, and the 1:32 deserializer.
//
// `Dctrl_*` are the sampling clock's delay taps. Like the TX tile's, they only
// shape analog timing, so this behavioral model accepts and ignores them; see
// `verilog/common/rx.sv` for the model that resolves them.
module rx_data_lane (
   input din,
   output dout_0,
   output dout_1,
   output dout_2,
   output dout_3,
   output dout_4,
   output dout_5,
   output dout_6,
   output dout_7,
   output dout_8,
   output dout_9,
   output dout_10,
   output dout_11,
   output dout_12,
   output dout_13,
   output dout_14,
   output dout_15,
   output dout_16,
   output dout_17,
   output dout_18,
   output dout_19,
   output dout_20,
   output dout_21,
   output dout_22,
   output dout_23,
   output dout_24,
   output dout_25,
   output dout_26,
   output dout_27,
   output dout_28,
   output dout_29,
   output dout_30,
   output dout_31,
   output divclk,
   input clk,
   input rstb,
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
   input Dctrl_0,
   input Dctrl_1,
   input Dctrl_2,
   input Dctrl_3,
   input Dctrl_4,
   input Dctrl_5,
   input Dctrl_6,
   input Dctrl_7,
   input Dctrl_8,
   input Dctrl_9,
   input Dctrl_10,
   input Dctrl_11,
   input Dctrl_12,
   input Dctrl_13,
   input Dctrl_14,
   input Dctrl_15,
   input Dctrl_16,
   input Dctrl_17,
   input Dctrl_18,
   input Dctrl_19,
   input Dctrl_20,
   input Dctrl_21,
   input Dctrl_22,
   input Dctrl_23,
   input Dctrl_24,
   input Dctrl_25,
   input Dctrl_26,
   input Dctrl_27,
   input Dctrl_28,
   input Dctrl_29,
   input Dctrl_30,
   input Dctrl_31,
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
  wire [31:0] dout_bus;
  ucie_des32 des (
    .din(din),
    .clk(clk),
    .rstb(rstb),
    .dout(dout_bus),
    .divclk(divclk)
  );
  assign dout_0 = dout_bus[0];
  assign dout_1 = dout_bus[1];
  assign dout_2 = dout_bus[2];
  assign dout_3 = dout_bus[3];
  assign dout_4 = dout_bus[4];
  assign dout_5 = dout_bus[5];
  assign dout_6 = dout_bus[6];
  assign dout_7 = dout_bus[7];
  assign dout_8 = dout_bus[8];
  assign dout_9 = dout_bus[9];
  assign dout_10 = dout_bus[10];
  assign dout_11 = dout_bus[11];
  assign dout_12 = dout_bus[12];
  assign dout_13 = dout_bus[13];
  assign dout_14 = dout_bus[14];
  assign dout_15 = dout_bus[15];
  assign dout_16 = dout_bus[16];
  assign dout_17 = dout_bus[17];
  assign dout_18 = dout_bus[18];
  assign dout_19 = dout_bus[19];
  assign dout_20 = dout_bus[20];
  assign dout_21 = dout_bus[21];
  assign dout_22 = dout_bus[22];
  assign dout_23 = dout_bus[23];
  assign dout_24 = dout_bus[24];
  assign dout_25 = dout_bus[25];
  assign dout_26 = dout_bus[26];
  assign dout_27 = dout_bus[27];
  assign dout_28 = dout_bus[28];
  assign dout_29 = dout_bus[29];
  assign dout_30 = dout_bus[30];
  assign dout_31 = dout_bus[31];
  wire _unused_dctrl = &{1'b0, Dctrl_0, Dctrl_1, Dctrl_2, Dctrl_3, Dctrl_4, Dctrl_5, Dctrl_6, Dctrl_7, Dctrl_8, Dctrl_9, Dctrl_10, Dctrl_11, Dctrl_12, Dctrl_13, Dctrl_14, Dctrl_15, Dctrl_16, Dctrl_17, Dctrl_18, Dctrl_19, Dctrl_20, Dctrl_21, Dctrl_22, Dctrl_23, Dctrl_24, Dctrl_25, Dctrl_26, Dctrl_27, Dctrl_28, Dctrl_29, Dctrl_30, Dctrl_31};
endmodule
