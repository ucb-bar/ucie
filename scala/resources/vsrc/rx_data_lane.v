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
  // The tile needs the clock running for WAKE_CYCLES cycles after reset before
  // its analog front end settles; until then it captures nothing.
  //
  // A clock that stops and restarts would in reality have to wake again. That
  // is not modelled here -- the VAMS models are where behaviour at that level
  // belongs.
  parameter integer WAKE_CYCLES = 8;
  reg [2:0] ctr;
  reg divClock;
  reg [31:0] shiftReg;
  reg [31:0] outputReg;
  reg [7:0] wakeCtr;
  wire awake = (wakeCtr >= WAKE_CYCLES);
  always @(negedge rstb) begin
    divClock <= 1'b0;
    ctr <= 3'b0;
    shiftReg <= 32'b0;
    outputReg <= 32'b0;
    wakeCtr <= 8'b0;
  end
  always @(posedge clk) begin
    if (rstb) begin
      if (!awake) wakeCtr <= wakeCtr + 1'b1;
      ctr <= ctr + 1'b1;
      shiftReg <= (shiftReg << 1'b1) | din;
      if (ctr == 3'b0) begin
        divClock <= ~divClock;
      end
      if (ctr == 3'b0 && divClock == 1'b0 && awake) begin
        outputReg <= shiftReg;
      end
    end
  end
  always @(negedge clk) begin
    if (rstb) begin
        shiftReg <= (shiftReg << 1'b1) | din;
    end
  end
  // The tile deserializes through an adjacent-pairing binary tree
  // (deserializer_1to32), the mirror image of the TX tile's serializer, so the
  // bit that lands in dout[j] is the one received in UI bitrev5(j), not UI j:
  //
  //   UI0 UI16 UI8 UI24 UI4 UI20 UI12 UI28 UI2 UI18 UI10 UI26 UI6 UI22 UI14 UI30
  //   UI1 UI17 UI9 UI25 UI5 UI21 UI13 UI29 UI3 UI19 UI11 UI27 UI7 UI23 UI15 UI31
  //
  // Tapping the shift register in that permuted order reproduces the tree's
  // wire order while keeping this a plain shift register: the same word rate
  // (one word per divclk, 32 UI DDR), just the tree's bit order. `shiftReg[31]`
  // is the oldest bit in the window and `shiftReg[0]` the newest, so UI t is
  // `shiftReg[31-t]`.
  //
  // A TX tile on the far end reverses the same way, so the two trees cancel
  // once the word boundaries line up; against anything else the per-lane
  // shuffler behind the tile has to undo this. Note that the boundary cannot be
  // fixed after the tree: it reverses bit order, so an offset capture is a
  // scramble of dout rather than a rotation of it.
  assign dout_0  = outputReg[31];
  assign dout_1  = outputReg[15];
  assign dout_2  = outputReg[23];
  assign dout_3  = outputReg[7];
  assign dout_4  = outputReg[27];
  assign dout_5  = outputReg[11];
  assign dout_6  = outputReg[19];
  assign dout_7  = outputReg[3];
  assign dout_8  = outputReg[29];
  assign dout_9  = outputReg[13];
  assign dout_10 = outputReg[21];
  assign dout_11 = outputReg[5];
  assign dout_12 = outputReg[25];
  assign dout_13 = outputReg[9];
  assign dout_14 = outputReg[17];
  assign dout_15 = outputReg[1];
  assign dout_16 = outputReg[30];
  assign dout_17 = outputReg[14];
  assign dout_18 = outputReg[22];
  assign dout_19 = outputReg[6];
  assign dout_20 = outputReg[26];
  assign dout_21 = outputReg[10];
  assign dout_22 = outputReg[18];
  assign dout_23 = outputReg[2];
  assign dout_24 = outputReg[28];
  assign dout_25 = outputReg[12];
  assign dout_26 = outputReg[20];
  assign dout_27 = outputReg[4];
  assign dout_28 = outputReg[24];
  assign dout_29 = outputReg[8];
  assign dout_30 = outputReg[16];
  assign dout_31 = outputReg[0];
  assign divclk = divClock;
endmodule
