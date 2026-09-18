module clocking_tile(
  input [63:0] PhaseSel,
  input [2:0] FreqSel,
  input DigBypassClk,
  input BypassClk,
  output DigitalClk,
  output TxClkQ,
  output TxClk
);
  // Behavioral stub: neither the phase code (PhaseSel) nor the frequency
  // setting (FreqSel) is modeled, so both controls are unused and the tile
  // just forwards its bypass inputs.
  assign DigitalClk = DigBypassClk;

  assign TxClk = BypassClk;

  // TxClkQ is the quadrature (90 degree) phase of TxClk, and the forwarded
  // clock lanes are driven from it, so it becomes the far end's sampling clock.
  // Its phase matters more than it looks: the lane serdes are DDR, so one clock
  // period is two UI. 90 degrees lands the receiver's sampling edge in the
  // middle of a UI; 180 degrees is a whole UI, which slips every deserialized
  // word by one bit.
  //
  // A quarter-cycle shift cannot be expressed combinationally, so this measures
  // the incoming clock and re-emits it a quarter period late. `#` delays are
  // not synthesizable, which is fine here -- this file is a simulation stub,
  // loaded only under `includeDefaultModels`.
  real prevPosedge;
  real period;
  reg  seenPosedge;
  reg  txClkQReg;

  initial begin
    prevPosedge = 0.0;
    period      = 0.0;
    seenPosedge = 1'b0;
    txClkQReg   = 1'b0;
  end

  // Measured across a full cycle, so the shift does not depend on duty cycle.
  // `period` stays 0 until the second posedge, leaving TxClkQ unshifted for the
  // first cycle out of reset.
  always @(posedge BypassClk) begin
    if (seenPosedge) period = $realtime - prevPosedge;
    prevPosedge = $realtime;
    seenPosedge = 1'b1;
  end

  // Re-emit both edges a quarter period late. Nonblocking with an
  // intra-assignment delay, so this never suspends and cannot miss an edge.
  always @(BypassClk) begin
    txClkQReg <= #(period / 4.0) BypassClk;
  end

  assign TxClkQ = txClkQReg;
endmodule
