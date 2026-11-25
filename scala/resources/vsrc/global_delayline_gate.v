// Behavioral model of `global_delayline_gate`: the global delay line on the TX
// quadrature clock (TxClkQ) and the TX clock gate after it. The name is a
// placeholder for the macro. `Dctrl`, `Vin` and `Vout` are the delay line's
// pins. `enable`, `edgeSel`, `VinData` (TxClk) and `VoutData` belong to the
// gate.
//
// The line delays `Vin` by a fixed offset plus one tap for each set bit of
// `Dctrl`.
//
// `enable` is asynchronous to both TX clocks. Three flops on TxClk synchronize
// it, and three more delay it to match the TxClkQ side. Each clock goes
// through a clock gate cell (a latch open while the clock is low, then an AND)
// driven by a flop on the same clock. Every path has a whole period, or ends
// in a flop meant to take a clock crossing. No path has to fit in half a
// period, because at 16 GHz a standard-cell latch may not.
//
// Three flops on the delayed TxClkQ take the decision from one of two taps,
// chosen by `edgeSel`. The lag is how far TxClkQ rises after TxClk.
//   0: TxClk flop 4. Each TxClkQ pulse follows the TxClk pulse before it.
//   1: TxClk flop 3 through a latch open while TxClk is low, half a period
//      earlier. For a lag under half a period this acts like 0. Above that,
//      each TxClkQ pulse follows the TxClk pulse after it.
// Each tap has a race: the lag at which its change meets a TxClkQ edge. That
// is near 0 for tap 0 and near half a period for tap 1, plus cell delays. At
// the race, a gate change can take effect on either of two TxClkQ pulses, but
// no pulse is cut short. Software sets `edgeSel` to 1 when the lag is within a
// quarter period of tap 0's race. While the gate stays open or shut, both taps
// agree, so changing `edgeSel` has no effect. This model has no delays, so the
// races are exactly on TxClk's edges.
module global_delayline_gate(
  input [63:0] Dctrl,
  input Vin,
  output Vout,
  input enable,
  input edgeSel,
  input VinData,
  output VoutData
);
  // A tap is under 2 ps, finer than some harnesses' default precision. Set
  // here rather than with `timescale, which would apply to later files too.
  timeunit 1ps;
  timeprecision 1fs;

  localparam real TAP_PS = 1.57;    // one tap
  localparam real OFS_PS = 826.0;   // the line with every tap off

  // ---- Global delay line ----
  integer taps;
  integer i;
  always @(*) begin
    taps = 0;
    for (i = 0; i < 64; i = i + 1) taps = taps + Dctrl[i];
  end
  real delay;
  always @(*) delay = taps * TAP_PS + OFS_PS;

  reg q_delayed = 1'b0;
  always @(Vin) q_delayed <= #(delay) Vin;

  // ---- TX clock gate ----
  // Bits 0-2 synchronize; bits 3-5 match the TxClkQ side's delay.
  reg [5:0] en_sync = 6'b0;
  always @(posedge VinData) en_sync <= {en_sync[4:0], enable};

  // TxClk's clock gate cell.
  reg en_data = 1'b0;
  always @(*) begin
    if (!VinData) en_data = en_sync[5];
  end
  assign VoutData = VinData & en_data;

  // TxClkQ's tap: flop 4, or flop 3 through a latch open while TxClk is low.
  reg en_fall = 1'b0;
  always @(*) begin
    if (!VinData) en_fall = en_sync[2];
  end
  wire en_tap = edgeSel ? en_fall : en_sync[3];

  reg [2:0] q_sync = 3'b0;
  always @(posedge q_delayed) q_sync <= {q_sync[1:0], en_tap};

  // TxClkQ's clock gate cell.
  reg en_q = 1'b0;
  always @(*) begin
    if (!q_delayed) en_q = q_sync[2];
  end
  assign Vout = q_delayed & en_q;
endmodule
