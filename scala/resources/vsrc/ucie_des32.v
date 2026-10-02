// Behavioral 1:32 deserializer: the sampling back end a receive tile puts
// behind its analog front end.
//
// Factored out because three paths need exactly the same one and they have to
// stay identical bit for bit. A data lane's normal path takes its clock from
// the RX lane clock network; the MBINIT.REPAIRCLK taps on the forwarded-clock
// and track lanes take theirs from the gated repair clock instead. Nothing
// else differs, which is the point: a repair tap deserializes the wire the
// same way the trained datapath will, so the per-lane shuffler default lines
// both of them up.
//
// `Dctrl` is the sampling clock's delay trim. Like the TX tile's it only
// shapes analog timing, so this model accepts and ignores it; see
// `verilog/common/rx.sv` for the model that resolves it.
module ucie_des32 (
   input din,
   input clk,
   input rstb,
   output [31:0] dout,
   output divclk
);
  // The tile needs the clock running for WAKE_CYCLES cycles after reset before
  // its analog front end settles; until then it captures nothing.
  //
  // A clock that stops and restarts would in reality have to wake again. That
  // is not modelled here -- the VAMS models are where behaviour at that level
  // belongs. It matters more for a repair tap than for a data lane, since the
  // repair clock is gated off between REPAIRCLK windows by design.
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
  assign dout[0]  = outputReg[31];
  assign dout[1]  = outputReg[15];
  assign dout[2]  = outputReg[23];
  assign dout[3]  = outputReg[7];
  assign dout[4]  = outputReg[27];
  assign dout[5]  = outputReg[11];
  assign dout[6]  = outputReg[19];
  assign dout[7]  = outputReg[3];
  assign dout[8]  = outputReg[29];
  assign dout[9]  = outputReg[13];
  assign dout[10] = outputReg[21];
  assign dout[11] = outputReg[5];
  assign dout[12] = outputReg[25];
  assign dout[13] = outputReg[9];
  assign dout[14] = outputReg[17];
  assign dout[15] = outputReg[1];
  assign dout[16] = outputReg[30];
  assign dout[17] = outputReg[14];
  assign dout[18] = outputReg[22];
  assign dout[19] = outputReg[6];
  assign dout[20] = outputReg[26];
  assign dout[21] = outputReg[10];
  assign dout[22] = outputReg[18];
  assign dout[23] = outputReg[2];
  assign dout[24] = outputReg[28];
  assign dout[25] = outputReg[12];
  assign dout[26] = outputReg[20];
  assign dout[27] = outputReg[4];
  assign dout[28] = outputReg[24];
  assign dout[29] = outputReg[8];
  assign dout[30] = outputReg[16];
  assign dout[31] = outputReg[0];
  assign divclk = divClock;
endmodule
