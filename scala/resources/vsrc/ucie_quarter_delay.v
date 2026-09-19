// Re-emits `din` a quarter of `clk`'s period later.
//
// Simulation-only shim used on the forwarded-clock bumps. The transmitted clock
// has to land in the middle of the data eye, which is a quarter-period shift.
// Taking that shift from a shifted lane clock instead would make the clock
// lanes' wake counters and word framing advance on different edges from the
// data lanes, and since the receiver has no clock at all until the clkP lane
// starts driving, any difference in when the two start becomes a capture
// offset. Shifting the bump output instead leaves every TX lane clocked
// identically, so they wake on the same edge and only the waveform moves.
//
// `#` delays are not synthesizable; this file is only loaded under
// `includeDefaultModels`.
module ucie_quarter_delay (
  input  clk,
  input  din,
  output dout
);
  real prevPosedge;
  real period;
  reg  seenPosedge;
  reg  doutReg;

  initial begin
    prevPosedge = 0.0;
    period      = 0.0;
    seenPosedge = 1'b0;
    doutReg     = 1'b0;
  end

  // Measured across a full cycle, so the shift does not depend on duty cycle.
  // `period` stays 0 until the second posedge, leaving `dout` unshifted through
  // the first cycle out of reset -- before any lane has woken.
  always @(posedge clk) begin
    if (seenPosedge) period = $realtime - prevPosedge;
    prevPosedge = $realtime;
    seenPosedge = 1'b1;
  end

  // Nonblocking with an intra-assignment delay, so this never suspends and
  // cannot miss a transition.
  always @(din) begin
    doutReg <= #(period / 4.0) din;
  end

  assign dout = doutReg;
endmodule
