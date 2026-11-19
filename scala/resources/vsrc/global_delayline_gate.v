// Behavioral model of the global delay line on the TX quadrature clock and the
// TX clock gate after it, `global_delayline_gate`. The name is a placeholder
// for the macro: the delay line's pins -- `Dctrl`, `Vin`, `Vout` -- with the
// gate's `enable`, and the TX data clock's `VinData` and `VoutData`, added.
//
// The line delays `Vin` by one tap for each bit of `Dctrl` set.
//
// The gate is two latches. The TX data clock's takes `enable` while that clock
// is low; the TX quadrature clock's takes what the first one holds while the
// delayed quadrature clock is low. So each clock only stops or starts between
// its own pulses, and the quadrature clock follows the data clock: shut, it
// stops at its first low phase after the data clock has, and open, it starts
// at its first low phase after the data clock has.
//
// With the quadrature clock lagging the data clock by up to half a period, the
// two pass the same pulses. Lagging by more, the quadrature latch is open
// while the data latch is, and can catch it mid-change: the quadrature clock
// can then pass one pulse more or fewer than the data clock.
module global_delayline_gate(
  input [63:0] Dctrl,
  input Vin,
  output Vout,
  input enable,
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
  reg en_data = 1'b0;
  always @(*) begin
    if (!VinData) en_data = enable;
  end
  assign VoutData = VinData & en_data;

  reg en_q = 1'b0;
  always @(*) begin
    if (!q_delayed) en_q = en_data;
  end
  assign Vout = q_delayed & en_q;
endmodule
