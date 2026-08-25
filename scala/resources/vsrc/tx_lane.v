// Behavioral model of the UCIe TX tile.
//
// Models the 16:1 double data rate serializer and the enable state of the
// output driver. `Dctrl`, `ENP_EQ`, and `ENN_EQ` only shape the analog
// waveform, so they have no effect here; see `verilog/tx.vams` for the model
// that does resolve them.
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
  reg [2:0] ctr;
  reg divClock;
  reg [31:0] shiftReg;
  always @(posedge RST_async) begin
    divClock <= 1'b0;
    ctr <= 3'b1;
    shiftReg <= 32'b0;
  end
  always @(posedge CK) begin
    if (!RST_async) begin
      ctr <= ctr + 1'b1;
      shiftReg <= shiftReg >> 1'b1;
      if (ctr == 3'b0) begin
        if (~divClock) begin
          shiftReg <= DataIN;
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
  // this model reports as 0.
  wire driver_off = (&ENP) & ~(|ENN);
  assign D2D_TX = driver_off ? 1'b0 : shiftReg[0];

endmodule
