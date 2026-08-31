module clkmux(
  input Vin_0,
  input Vin_1,
  input Vin_2,
  input Vin_3,
  input Vin_4,
  input Vin_5,
  input Vin_6,
  input Vin_7,
  input Vin_8,
  input Vin_9,
  input Vin_10,
  input Vin_11,
  input Vin_12,
  input Vin_13,
  input Vin_14,
  input Vin_15,
  input sel_0,
  input sel_1,
  input sel_2,
  input sel_3,
  input sel_4,
  input sel_5,
  input sel_6,
  input sel_7,
  input sel_8,
  input sel_9,
  input sel_10,
  input sel_11,
  input sel_12,
  input sel_13,
  input sel_14,
  input sel_15,
  input selb_0,
  input selb_1,
  input selb_2,
  input selb_3,
  input selb_4,
  input selb_5,
  input selb_6,
  input selb_7,
  input selb_8,
  input selb_9,
  input selb_10,
  input selb_11,
  input selb_12,
  input selb_13,
  input selb_14,
  input selb_15,
  output Vout
);
  // Every input hangs a complementary pass gate off one shared node, so sel
  // has to be one-hot and selb its exact complement. Anything else either
  // floats the node (no gate on) or shorts two clocks together.
  wire [15:0] vin = {Vin_15, Vin_14, Vin_13, Vin_12, Vin_11, Vin_10, Vin_9, Vin_8, Vin_7, Vin_6, Vin_5, Vin_4, Vin_3, Vin_2, Vin_1, Vin_0};
  wire [15:0] sel = {sel_15, sel_14, sel_13, sel_12, sel_11, sel_10, sel_9, sel_8, sel_7, sel_6, sel_5, sel_4, sel_3, sel_2, sel_1, sel_0};
  wire [15:0] selb = {selb_15, selb_14, selb_13, selb_12, selb_11, selb_10, selb_9, selb_8, selb_7, selb_6, selb_5, selb_4, selb_3, selb_2, selb_1, selb_0};

  reg selected;
  integer i;
  always @(*) begin
    if (sel != ~selb || $onehot(sel) !== 1'b1) begin
      selected = 1'bx;
    end else begin
      selected = 1'b0;
      for (i = 0; i < 16; i = i + 1) begin
        if (sel[i]) selected = vin[i];
      end
    end
  end

  // Shared output inverter.
  assign Vout = ~selected;
endmodule
