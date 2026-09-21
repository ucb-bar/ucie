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

  // TxClkQ is the quadrature (90 degree) phase of TxClk in the real tile, and
  // the distribution network hands it to the two forwarded-clock lanes.
  //
  // This model leaves it in phase, and the quarter-period shift is applied to
  // the clkP/clkN bump outputs instead (see `ucie_quarter_delay.v`). Shifting
  // it here would give the clock lanes' serializers a different set of clock
  // edges from the data lanes, so their wake counters and word framing would
  // advance at different times. The receiver has no clock at all until the
  // clkP lane starts driving, so any difference in when the clock and data
  // lanes start becomes a capture offset on every deserialized word. Moving
  // the shift downstream keeps every TX lane clocked identically and leaves
  // only the transmitted waveform shifted, which is the part that matters.
  assign TxClkQ = BypassClk;
endmodule
