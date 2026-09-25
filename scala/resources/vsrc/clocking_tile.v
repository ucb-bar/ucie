// Behavioral model of the UCIe clocking tile.
//
// Sources every clock the PHY runs on:
//
//   - a PLL that multiplies a 100 MHz reference up to 8 GHz. It replaces what
//     was an 8 GHz ring oscillator, so the tile now needs a reference pin;
//   - ring oscillators at 12 GHz and 16 GHz, for the rates above 16 GT/s;
//   - a mux that picks one of those, dividing where a rate below 8 GHz is
//     wanted;
//   - a quadrature generator, which for those divided rates comes free from
//     the divide by two and otherwise is the in-phase clock shifted by a
//     quarter period;
//   - a delay line on the quadrature clock alone, for trimming its phase
//     against the in-phase output.
//
// `DigitalClk` is always the PLL divided by ten, so the digital domain stays
// at 800 MHz whatever rate the lanes run at.
//
// `FreqSel` follows `SpeedMode`. Only the rates up to 32 GT/s are built:
//
//   0  speed4    2 GHz   PLL / 4, quadrature by division
//   1  speed8    4 GHz   PLL / 2, quadrature by division
//   2  speed12   6 GHz   RO12 / 2, quadrature by division
//   3  speed16   8 GHz   PLL, quadrature by delay line
//   4  speed24  12 GHz   RO12, quadrature by delay line
//   5  speed32  16 GHz   RO16, quadrature by delay line
//   6, 7        unsupported, the TX clocks stay low
//
// The two bypass pins are selected independently. `DigBypassEn` forwards
// `DigBypassClk` as the digital clock, `TxBypassEn` forwards `BypassClk` to
// the lanes; either domain can run from its pad while the other runs from the
// tile, which is what bring-up wants. Both default to bypass, and neither is
// clocked by anything the tile produces -- they are held in a register block
// on the chip's own digital clock, because a select cannot live in a domain
// that only exists once the select has already been made.
module clocking_tile(
  input [63:0] PhaseSel,
  input [2:0] FreqSel,
  input ClkGateEn,
  input DigBypassEn,
  input TxBypassEn,
  input RefClk,
  input DigBypassClk,
  input BypassClk,
  output DigitalClk,
  output TxClkQ,
  output TxClk
);
  // Nominal half periods in ps, used until the reference has been measured.
  localparam real HALF_8G  = 62.5;
  localparam real HALF_12G = 41.66667;
  localparam real HALF_16G = 31.25;
  // 100 MHz in, 8 GHz out.
  localparam real PLL_MULT = 80.0;
  // One tap of the quadrature delay line. 64 taps of it is 64 ps, a little
  // over one UI at 16 GT/s, which is the range a phase trim needs.
  localparam real PHASE_TAP_PS = 1.0;

  // ---- PLL: lock a half period to the reference ----
  real pll_half = HALF_8G;
  real ref_last = -1.0;
  always @(posedge RefClk) begin
    if (ref_last >= 0.0) pll_half = ($realtime - ref_last) / (2.0 * PLL_MULT);
    ref_last = $realtime;
  end

  // Each oscillator stands still while the tile is bypassed. They would
  // otherwise toggle every few tens of picoseconds for the whole of a run
  // that never looks at them, which costs a simulator with timing enabled a
  // great deal for nothing.
  reg pll_clk = 1'b0;
  always begin
    if (TxBypassEn) @(TxBypassEn);
    else #(pll_half) pll_clk = ~pll_clk;
  end

  // ---- Ring oscillators, free running ----
  reg ro12 = 1'b0;
  always begin
    if (TxBypassEn) @(TxBypassEn);
    else #(HALF_12G) ro12 = ~ro12;
  end
  reg ro16 = 1'b0;
  always begin
    if (TxBypassEn) @(TxBypassEn);
    else #(HALF_16G) ro16 = ~ro16;
  end

  // ---- Digital clock: the PLL divided by ten, always ----
  integer dig_cnt = 0;
  reg dig_clk = 1'b0;
  always @(posedge pll_clk) begin
    if (dig_cnt == 4) begin
      dig_cnt = 0;
      dig_clk = ~dig_clk;
    end else begin
      dig_cnt = dig_cnt + 1;
    end
  end

  // ---- Divide by two, taking the quadrature phase with it ----
  // A toggle on each edge of the source gives two clocks a quarter period
  // apart at the divided rate, which is where the sub 8 GHz quadrature comes
  // from rather than from any delay.
  reg pll_d2_i = 1'b0, pll_d2_q = 1'b0;
  always @(posedge pll_clk) pll_d2_i = ~pll_d2_i;
  always @(negedge pll_clk) pll_d2_q = pll_d2_i;

  reg pll_d4_i = 1'b0, pll_d4_q = 1'b0;
  always @(posedge pll_d2_i) pll_d4_i = ~pll_d4_i;
  always @(negedge pll_d2_i) pll_d4_q = pll_d4_i;

  reg ro12_d2_i = 1'b0, ro12_d2_q = 1'b0;
  always @(posedge ro12) ro12_d2_i = ~ro12_d2_i;
  always @(negedge ro12) ro12_d2_q = ro12_d2_i;

  // ---- Clock mux ----
  reg sel_i;
  reg sel_q_div;
  reg sel_divided;
  always @(*) begin
    case (FreqSel)
      3'd0: begin sel_i = pll_d4_i;  sel_q_div = pll_d4_q;  sel_divided = 1'b1; end
      3'd1: begin sel_i = pll_d2_i;  sel_q_div = pll_d2_q;  sel_divided = 1'b1; end
      3'd2: begin sel_i = ro12_d2_i; sel_q_div = ro12_d2_q; sel_divided = 1'b1; end
      3'd3: begin sel_i = pll_clk;   sel_q_div = 1'b0;      sel_divided = 1'b0; end
      3'd4: begin sel_i = ro12;      sel_q_div = 1'b0;      sel_divided = 1'b0; end
      3'd5: begin sel_i = ro16;      sel_q_div = 1'b0;      sel_divided = 1'b0; end
      default: begin sel_i = 1'b0; sel_q_div = 1'b0; sel_divided = 1'b0; end
    endcase
  end

  // At and above 8 GHz there is no faster clock to divide, so the quadrature
  // copy is the in-phase one shifted by a quarter of its own period.
  real quarter_period;
  always @(*) begin
    case (FreqSel)
      3'd3: quarter_period = HALF_8G / 2.0;
      3'd4: quarter_period = HALF_12G / 2.0;
      3'd5: quarter_period = HALF_16G / 2.0;
      default: quarter_period = 0.0;
    endcase
  end

  reg sel_q_shift = 1'b0;
  always @(sel_i) sel_q_shift <= #(quarter_period) sel_i;

  // The bypass mux picks where the clocks come from; the quadrature delay
  // line sits after it, so the phase trim applies either way. A bypassed tile
  // still has to be trimmable -- that is how a testbench trains a link it is
  // driving from its own clock.
  wire int_q = sel_divided ? sel_q_div : sel_q_shift;
  wire q_raw = TxBypassEn ? ~BypassClk : int_q;

  // ---- Quadrature delay line ----
  // Thermometer coded like every other delay code in the PHY, so the trim
  // follows the number of taps enabled rather than the value of the bus.
  integer phase_taps;
  integer pi;
  always @(*) begin
    phase_taps = 0;
    for (pi = 0; pi < 64; pi = pi + 1) phase_taps = phase_taps + PhaseSel[pi];
  end
  real phase_delay;
  always @(*) phase_delay = phase_taps * PHASE_TAP_PS;

  reg q_delayed = 1'b0;
  always @(q_raw) q_delayed <= #(phase_delay) q_raw;

  // ---- Outputs ----
  // DigitalClk is never gated; the digital domain has to keep running to
  // service the RX AFEs and the register file that drives the gate.
  assign DigitalClk = DigBypassEn ? DigBypassClk : dig_clk;

  wire tx_i = TxBypassEn ? BypassClk : sel_i;
  wire tx_q = q_delayed;

  // Latch the enable on each clock's low phase so that changing ClkGateEn
  // mid-cycle cannot chop a pulse short. A runt here would reach the lane
  // dividers as a real edge and defeat the point of gating.
  reg txClkEn;
  reg txClkQEn;
  always @(*) begin
    if (!tx_i) txClkEn = ClkGateEn;
  end
  always @(*) begin
    if (!tx_q) txClkQEn = ClkGateEn;
  end

  assign TxClk = tx_i & txClkEn;
  assign TxClkQ = tx_q & txClkQEn;
endmodule
