// Behavioral model of the UCIe TX tile.
//
// Models the 16:1 double data rate serializer, the tile's wake-up, and the
// enable state of the output driver. `Dctrl`, `ENP_EQ`, and `ENN_EQ` only
// shape the analog waveform, so they have no effect here; see
// `verilog/tx.vams` for the model that does resolve them.
//
// VDDQ, VDD, and VSS are pins on the tile but are omitted here; they are
// connected by the physical flow.
module tx_lane (
  input [31:0] DataIN,
  input CK,
  input [31:0] Dctrl,
  input [8:0] ENP,
  input [8:0] ENN,
  input [3:0] ENP_EQ,
  input [3:0] ENN_EQ,
  input RST_async,
  output D2D_TX
);
  // The tile needs the clock running for WAKE_CYCLES cycles after reset before
  // its analog front end settles; until then it drives nothing.
  //
  // A clock that stops and restarts would in reality have to wake again. That
  // is not modelled here -- the VAMS models are where behaviour at that level
  // belongs.
  parameter integer WAKE_CYCLES = 8;
  // The tile serializes with an adjacent-pairing binary tree (ser32to1), so
  // the bit sent in UI t is DataIN[bitrev5(t)], not DataIN[t]:
  //
  //   D0 D16 D8 D24 D4 D20 D12 D28 D2 D18 D10 D26 D6 D22 D14 D30
  //   D1 D17 D9 D25 D5 D21 D13 D29 D3 D19 D11 D27 D7 D23 D15 D31
  //
  // Loading the shift register in that permuted order reproduces the tree's
  // wire order while keeping this a plain shift register: the same word rate
  // (one word per divclk, 32 UI DDR), just the tree's bit order.
  reg [2:0] ctr;
  reg divClock;
  reg [31:0] shiftReg;
  reg [7:0] wakeCtr;
  wire awake = (wakeCtr >= WAKE_CYCLES);
  always @(posedge RST_async) begin
    divClock <= 1'b0;
    ctr <= 3'b1;
    shiftReg <= 32'b0;
    wakeCtr <= 8'b0;
  end
  always @(posedge CK) begin
    if (!RST_async) begin
      if (!awake) wakeCtr <= wakeCtr + 1'b1;
      ctr <= ctr + 1'b1;
      shiftReg <= shiftReg >> 1'b1;
      if (ctr == 3'b0) begin
        if (~divClock) begin
          shiftReg <= {
            DataIN[31],
            DataIN[15],
            DataIN[23],
            DataIN[7],
            DataIN[27],
            DataIN[11],
            DataIN[19],
            DataIN[3],
            DataIN[29],
            DataIN[13],
            DataIN[21],
            DataIN[5],
            DataIN[25],
            DataIN[9],
            DataIN[17],
            DataIN[1],
            DataIN[30],
            DataIN[14],
            DataIN[22],
            DataIN[6],
            DataIN[26],
            DataIN[10],
            DataIN[18],
            DataIN[2],
            DataIN[28],
            DataIN[12],
            DataIN[20],
            DataIN[4],
            DataIN[24],
            DataIN[8],
            DataIN[16],
            DataIN[0]
          };
        end
        divClock <= ~divClock;
      end
    end
  end
  // Second half of the DDR serializer: the tile takes a single-ended clock
  // and makes its own complement internally, so the falling edge here is
  // what the second serializer phase keys off.
  always @(negedge CK) begin
    shiftReg <= shiftReg >> 1'b1;
  end

  // `ENP` is active low and `ENN` active high, so the driver is off when every
  // segment of both rails is disabled. The tile then floats its output, which
  // this model reports as 0, as it does before the tile has woken.
  wire driver_off = (&ENP) & ~(|ENN);
  assign D2D_TX = (driver_off || !awake) ? 1'b0 : shiftReg[0];

endmodule
