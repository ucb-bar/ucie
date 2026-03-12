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
  reg [2:0] ctr;
  reg divClock;
  reg [31:0] shiftReg;
  reg [31:0] outputReg;
  always @(negedge rstb) begin
    divClock <= 1'b0;
    ctr <= 3'b1;
    shiftReg <= 32'b0;
  end
  always @(posedge clk) begin
    if (rstb) begin
      ctr <= ctr + 1'b1;
      shiftReg <= (shiftReg << 1'b1) | din;
      if (ctr == 3'b0) begin
        divClock <= ~divClock;
      end
    end
  end
  always @(negedge clk) begin
    if (rstb) begin
        shiftReg <= (shiftReg << 1'b1) | din;
    end
  end
  always @(posedge divClock) begin
    outputReg <= shiftReg;
  end
  assign dout_0  = outputReg[31];
  assign dout_1  = outputReg[30];
  assign dout_2  = outputReg[29];
  assign dout_3  = outputReg[28];
  assign dout_4  = outputReg[27];
  assign dout_5  = outputReg[26];
  assign dout_6  = outputReg[25];
  assign dout_7  = outputReg[24];
  assign dout_8  = outputReg[23];
  assign dout_9  = outputReg[22];
  assign dout_10 = outputReg[21];
  assign dout_11 = outputReg[20];
  assign dout_12 = outputReg[19];
  assign dout_13 = outputReg[18];
  assign dout_14 = outputReg[17];
  assign dout_15 = outputReg[16];
  assign dout_16 = outputReg[15];
  assign dout_17 = outputReg[14];
  assign dout_18 = outputReg[13];
  assign dout_19 = outputReg[12];
  assign dout_20 = outputReg[11];
  assign dout_21 = outputReg[10];
  assign dout_22 = outputReg[9];
  assign dout_23 = outputReg[8];
  assign dout_24 = outputReg[7];
  assign dout_25 = outputReg[6];
  assign dout_26 = outputReg[5];
  assign dout_27 = outputReg[4];
  assign dout_28 = outputReg[3];
  assign dout_29 = outputReg[2];
  assign dout_30 = outputReg[1];
  assign dout_31 = outputReg[0];
  assign divclk = divClock;
endmodule
