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
// over; nothing else in the gate samples it.
//
// The data clock's gate is an ordinary clock gate latch: it takes the
// synchronized enable while the data clock is low, so the data clock only
// stops or starts between its pulses.
//
// The quadrature clock's gate follows the data clock's. Its latch takes the
// data clock's decision while the delayed quadrature clock is low, but only
// once the data clock's first pulse under that decision has begun: a second
// latch, open while the data clock is high, holds the decision back until
// then. Each quadrature pulse therefore passes exactly when the data pulse
// before it did, at any lag. Taken straight from the data clock's latch, which
// changes half a period earlier, a quadrature clock lagging by more than half
// a period would still be low at the change and pass it to the pulse before.
//
// The retiming latch's output changes just after the data clock rises. A
// quadrature clock rising within that delay and its own latch's setup of the
// data clock -- a lag near zero, or near a whole period -- closes its latch
// on the change. `edgeSel` high holds the retiming latch open, so the
// quadrature clock's latch takes the decision straight from the data clock's,
// which changes on the data clock's falling edge: right for any lag under half
// a period, and clear of a lag near zero, but racing one near half a period.
// Software sets it for lags under a quarter period and clears it otherwise,
// which keeps the edge taken a quarter period from the one it races. With the
// gate steadily open or shut both latches hold the same value, so `edgeSel`
// moves nothing when it changes then. This model has no delays and cannot show
// either race.
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
  reg [2:0] en_sync = 3'b0;
  always @(posedge VinData) en_sync <= {en_sync[1:0], enable};

  reg en_data = 1'b0;
  always @(*) begin
    if (!VinData) en_data = en_sync[2];
  end
  assign VoutData = VinData & en_data;

  reg en_retimed = 1'b0;
  always @(*) begin
    if (VinData || edgeSel) en_retimed = en_data;
  end

  reg en_q = 1'b0;
  always @(*) begin
    if (!q_delayed) en_q = en_retimed;
  end
  assign Vout = q_delayed & en_q;
endmodule
