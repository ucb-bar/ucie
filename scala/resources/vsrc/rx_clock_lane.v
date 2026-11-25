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
  // are clocked.
  //
  // The enable is asynchronous to this clock. As in the TX gate
  // (`global_delayline_gate`), three flops on the clock synchronize it, and
  // a clock gate cell (a latch open while the clock is low, then an AND)
  // driven by the third flop gates the clock. A change takes effect on the
  // fourth rising edge after it, and only while the clock runs. Every path
  // fits in a whole period. A shut gate holds the clock low.
  wire clkout_raw = clkin;
  reg [2:0] en_sync = 3'b0;
  always @(posedge clkout_raw) en_sync <= {en_sync[1:0], clk_gate_en};
  reg gate_en_latched = 1'b0;
  always @(*) begin
    if (!clkout_raw) gate_en_latched = en_sync[2];
  end
  assign clkout = clkout_raw & gate_en_latched;
endmodule
