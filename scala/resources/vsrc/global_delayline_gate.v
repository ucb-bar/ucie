// Behavioral model of the global delay line on the TX quadrature clock and the
// TX clock gate after it, `global_delayline_gate`. The name is a placeholder
// for the macro: the delay line's pins -- `Dctrl`, `Vin`, `Vout` -- with the
// gate's `enable` and `edgeSel`, and the TX data clock's `VinData` and
// `VoutData`, added.
//
// The line delays `Vin` by one tap for each bit of `Dctrl` set.
//
// `enable` comes from the chip clock's register block, so it is asynchronous
// to both TX clocks. Three flops on the data clock's rising edge bring it
// over, and three more hold it back while the quadrature clock's side catches
// up. Each clock is gated by a clock gate cell (a latch open while that clock
// is low, then an AND), fed from a flop on the same clock. So every path in
// the gate has a whole period, or crosses between the clocks into flops that
// expect it to; none has to fit in half a period. At 16 GHz a standard-cell
// latch may not fit in half a period, and a gate built of half-period hops
// would then cut pulses.
//
// The quadrature clock's gate takes the data clock's decision through three
// flops on the delayed quadrature clock. They sample one of two taps of the
// data clock's chain, picked by `edgeSel`: the fourth flop (0), which changes
// as the data clock rises, or the third through a latch open while the data
// clock is low (1), which carries the same decision from half a period
// earlier. With the fourth flop, each quadrature pulse passes exactly when the
// data pulse before it did, at any lag. With the latch, that holds for a lag
// under half a period; past it, each quadrature pulse does what the data pulse
// after it does. Each tap races the quadrature flops at one lag: near zero for
// the flop, near half a period for the latch, plus the cell's delays. A
// quadrature clock rising as the tap changes may take the change on either of
// two pulses, but the flops behind it settle the choice, so neither clock is
// ever cut short. Software picks the tap whose race is farther from the lag:
// the latch within a quarter period of the flop's race. With the gate steadily
// open or shut both taps carry the same decision, so `edgeSel` moves nothing
// when it changes then. This model has no delays, so the races sit exactly on
// the data clock's edges.
module global_delayline_gate(
  input [63:0] Dctrl,
  input Vin,
  output Vout,
  input enable,
  input edgeSel,
  input VinData,
  output VoutData
);
  // A tap is about a picosecond, well below the 100 ps precision some
  // harnesses compile with by default, so the module sets its own unit and
  // precision. Declared here rather than with `timescale, which would carry
  // over into every file compiled after this one.
  timeunit 1ps;
  timeprecision 1fs;

  localparam real TAP_PS = 1.086;   // matches `global_delayline`

  // ---- Global delay line ----
  integer taps;
  integer i;
  always @(*) begin
    taps = 0;
    for (i = 0; i < 64; i = i + 1) taps = taps + Dctrl[i];
  end
  real delay;
  always @(*) delay = taps * TAP_PS;

  reg q_delayed = 1'b0;
  always @(Vin) q_delayed <= #(delay) Vin;

  // ---- TX clock gate ----
  // Bits 0 to 2 synchronize; 3 to 5 wait for the quadrature clock's flops.
  reg [5:0] en_sync = 6'b0;
  always @(posedge VinData) en_sync <= {en_sync[4:0], enable};

  // The data clock's clock gate cell.
  reg en_data = 1'b0;
  always @(*) begin
    if (!VinData) en_data = en_sync[5];
  end
  assign VoutData = VinData & en_data;

  // The quadrature clock's tap: the fourth flop, or the third through a latch
  // open while the data clock is low.
  reg en_fall = 1'b0;
  always @(*) begin
    if (!VinData) en_fall = en_sync[2];
  end
  wire en_tap = edgeSel ? en_fall : en_sync[3];

  reg [2:0] q_sync = 3'b0;
  always @(posedge q_delayed) q_sync <= {q_sync[1:0], en_tap};

  // The quadrature clock's clock gate cell.
  reg en_q = 1'b0;
  always @(*) begin
    if (!q_delayed) en_q = q_sync[2];
  end
  assign Vout = q_delayed & en_q;
endmodule
