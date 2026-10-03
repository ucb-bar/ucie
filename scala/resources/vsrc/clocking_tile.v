// Behavioral model of the UCIe clocking tile.
//
// Sources every clock the PHY runs on, from three PLLs and two bypass pins:
//
//   off chip   RefClk          100 MHz, what the PLLs lock to
//              BypassClk       the analog bypass clock, up to 12 GHz
//              DigBypassClk    the digital bypass clock, 800 MHz
//   on chip    pll8            8 GHz    ) named for their output; each is
//              pll12           12 GHz   ) individually enabled, so an
//              pll16           16 GHz   ) unselected one can be powered down
//
// and derives from them:
//
//   main clock   one of the three PLLs or the analog bypass pin
//   TXCLK        the main clock divided by 1, 2, 4 or 8
//   TXCLKQ       the same division, phase shifted by a whole number of main
//                clock half cycles, then through the global delay line
//   DigitalClk   the digital bypass pin, or the main clock divided by
//                1, 2, 4, 8 or 15 -- the ratios that land a little over 1 GHz
//                from an 8 or 16 GHz main clock
//   SbClk        the sideband bypass pin, or the main clock divided by
//                1, 5, 10, 15 or 20, which is where 800 MHz comes from
//
// A divider is held at zero while its bypass pin is selected, so deselecting
// one starts a fresh period rather than landing mid-count.
//
// The divider and the phase shifter are one circuit. A counter over main clock
// half cycles, modulo twice the division ratio, gives every phase the divider
// can reach: `TXCLK` is the first half of that count and `TXCLKQ` the same
// window moved along by `TxClkPhase`. In silicon that is cascaded pairs of
// flip flops with the unused stages disabled; the counter is the behavioural
// equivalent and reaches exactly the same positions.
//
// Those positions are half a main clock period apart, which is what lets a
// sweep tile a whole UI at any rate: 62.5 ps at 8 GHz, 41.7 at 12, 31.25 at
// 16, all inside the range the global delay line covers. Without the shifter
// the delay line alone falls short of a UI below 16 GT/s.
//
// At division 1 the counter has only two positions, 0 and 180 degrees. There
// is no quadrature to be had from a divider that is not dividing, but 180 is
// enough to walk an eye at the top rate.
module clocking_tile(
  // Global delay line on TXCLKQ, thermometer coded.
  input [63:0] PhaseSel,
  // 0 pll8, 1 pll12, 2 pll16, 3 the analog bypass pin.
  input [1:0] MainClkSel,
  input Pll8En,
  input Pll12En,
  input Pll16En,
  // 0 /1, 1 /2, 2 /4, 3 /8.
  input [1:0] TxClkDiv,
  // TXCLKQ's lead over TXCLK, in main clock half cycles, 0 to 2*div-1.
  input [3:0] TxClkPhase,
  // 0 /1, 1 /2, 2 /4, 3 /8, 4 /15. The ratios that land a little over 1 GHz
  // from an 8 or 16 GHz main clock.
  input [2:0] DigClkDiv,
  // High takes the digital clock from the bypass pin instead of the divider.
  input DigClkBypassEn,
  // 0 /1, 1 /5, 2 /10, 3 /15, 4 /20. The sideband runs at 800 MHz, which is a
  // different ratio off the same main clock, so it gets its own divider.
  input [2:0] SbClkDiv,
  // High takes the sideband clock from its own bypass pin.
  input SbClkBypassEn,
  // Active high enable for the TX clock outputs. DigitalClk is never gated.
  input ClkGateEn,
  input RefClk,
  input DigBypassClk,
  input SbBypassClk,
  input BypassClk,
  // High once a PLL has settled after being enabled. Low while it is off, and
  // low from the moment it is enabled until it has had its wake-up time.
  output Pll8Lock,
  output Pll12Lock,
  output Pll16Lock,
  output DigitalClk,
  output SbClk,
  output TxClkQ,
  output TxClk
);
  // Nominal half periods in ps, used until the reference has been measured.
  localparam real HALF_8G  = 62.5;
  localparam real HALF_12G = 41.66667;
  localparam real HALF_16G = 31.25;
  // Delays below are written in ps and applied through a `1ps` time literal,
  // so they hold under whatever timescale the model is compiled with (ucie's
  // tests use 1ps, iris uses 1ns). A bare `#(x)` would read them in the
  // compile unit's time unit instead.
  localparam real PHASE_TAP_PS = 1.086;   // matches `global_delayline`
  // How long a PLL takes to settle once enabled. Short next to a real one so
  // that a rate sweep does not dominate a run, long enough that an apply that
  // ungates without waiting is visibly wrong.
  parameter real PLL_LOCK_PS = 1000000.0;   // 1 us

  // ---- Three PLLs, each locking a half period to the reference ----
  real ref_last = -1.0;
  real ref_period = 10000.0 * 1ps;
  always @(posedge RefClk) begin
    if (ref_last >= 0.0) ref_period = $realtime - ref_last;
    ref_last = $realtime;
  end

  reg pll8 = 1'b0, pll12 = 1'b0, pll16 = 1'b0;
  always begin
    if (!Pll8En) @(Pll8En);
    else #(ref_period / 160.0) pll8 = ~pll8;   // x80
  end
  always begin
    if (!Pll12En) @(Pll12En);
    else #(ref_period / 240.0) pll12 = ~pll12;   // x120
  end
  always begin
    if (!Pll16En) @(Pll16En);
    else #(ref_period / 320.0) pll16 = ~pll16;   // x160
  end

  // ---- Lock ----
  // A PLL reports lock `PLL_LOCK_PS` after being enabled and drops it the
  // instant it is switched off. Losing lock while running is not modelled;
  // what this is for is the wake-up wait, which is the one an apply has to
  // hold the clock gate across.
  //
  // The blocking delay also holds off a re-trigger, so an enable that is
  // withdrawn mid wake-up resolves to whatever `PllNEn` reads by then, which
  // is zero.
  reg pll8Locked = 1'b0;
  always @(posedge Pll8En) begin
    #(PLL_LOCK_PS * 1ps) pll8Locked = Pll8En;
  end
  always @(negedge Pll8En) pll8Locked = 1'b0;

  reg pll12Locked = 1'b0;
  always @(posedge Pll12En) begin
    #(PLL_LOCK_PS * 1ps) pll12Locked = Pll12En;
  end
  always @(negedge Pll12En) pll12Locked = 1'b0;

  reg pll16Locked = 1'b0;
  always @(posedge Pll16En) begin
    #(PLL_LOCK_PS * 1ps) pll16Locked = Pll16En;
  end
  always @(negedge Pll16En) pll16Locked = 1'b0;

  assign Pll8Lock = pll8Locked;
  assign Pll12Lock = pll12Locked;
  assign Pll16Lock = pll16Locked;

  // ---- Main clock ----
  wire main_clk = (MainClkSel == 2'd0) ? pll8 :
                  (MainClkSel == 2'd1) ? pll12 :
                  (MainClkSel == 2'd2) ? pll16 : BypassClk;

  // ---- Main clock divider and phase shifter ----
  // Continuous assignments rather than an `always @(*)`: these have to be
  // settled before the first main clock edge, and a procedural block racing
  // that edge leaves the span unknown. The counter then latches X and stays
  // there, because X plus one is X -- so the guard below catches it too.
  wire [5:0] tx_span = (TxClkDiv == 2'd0) ? 6'd2 :
                       (TxClkDiv == 2'd1) ? 6'd4 :
                       (TxClkDiv == 2'd2) ? 6'd8 : 6'd16;
  wire [4:0] tx_div = tx_span[5:1];           // span is 2 * div half cycles

  reg [5:0] half_cnt = 6'd0;
  always @(main_clk) begin
    if ((^half_cnt === 1'bx) || (half_cnt + 6'd1 >= tx_span)) half_cnt <= 6'd0;
    else half_cnt <= half_cnt + 6'd1;
  end

  wire [5:0] q_cnt = (half_cnt + {2'b0, TxClkPhase}) % tx_span;
  wire tx_i_raw = half_cnt < {1'b0, tx_div};
  wire tx_q_raw = q_cnt < {1'b0, tx_div};

  // ---- Global delay line, on the quadrature output only ----
  integer phase_taps;
  integer pi;
  always @(*) begin
    phase_taps = 0;
    for (pi = 0; pi < 64; pi = pi + 1) phase_taps = phase_taps + PhaseSel[pi];
  end
  real phase_delay;
  always @(*) phase_delay = phase_taps * PHASE_TAP_PS;

  // The quadrature clock is gated before the delay line, on its undelayed low
  // phase, so it carries exactly the edges TxClk's gate lets through, only
  // later. Gating after the line latched off `q_delayed`, which settles a
  // step after `tx_q_raw`: a ClkGateEn change on a clock edge could then open
  // the Q gate an edge before the I gate, and the clock lanes' serializers
  // counted one edge more than the data lanes', shifting their framing.
  reg txClkQEn;
  always @(*) begin
    if (!tx_q_raw) txClkQEn = ClkGateEn;
  end
  wire tx_q_gated = tx_q_raw & txClkQEn;

  reg q_delayed = 1'b0;
  always @(tx_q_gated) q_delayed <= #(phase_delay * 1ps) tx_q_gated;

  // ---- Digital clock divider ----
  wire [5:0] dig_div = (DigClkDiv == 3'd0) ? 6'd1 :
                       (DigClkDiv == 3'd1) ? 6'd2 :
                       (DigClkDiv == 3'd2) ? 6'd4 :
                       (DigClkDiv == 3'd3) ? 6'd8 : 6'd15;

  // Held at zero while the bypass pin is selected, so switching back to the
  // divider starts a fresh period.
  reg [5:0] dig_cnt = 6'd0;
  always @(posedge main_clk) begin
    if (DigClkBypassEn || (^dig_cnt === 1'bx) ||
        (dig_cnt + 6'd1 >= dig_div)) dig_cnt <= 6'd0;
    else dig_cnt <= dig_cnt + 6'd1;
  end
  // An odd ratio cannot give an even mark to space, which costs a behavioural
  // model nothing: what the digital domain needs is the edge rate.
  wire dig_divided = dig_cnt < ((dig_div + 6'd1) >> 1);

  assign DigitalClk = DigClkBypassEn ? DigBypassClk : dig_divided;

  // ---- Sideband clock divider ----
  // Its own divider off the same main clock: the sideband runs at a rate the
  // digital domain does not.
  wire [5:0] sb_div = (SbClkDiv == 3'd0) ? 6'd1 :
                      (SbClkDiv == 3'd1) ? 6'd5 :
                      (SbClkDiv == 3'd2) ? 6'd10 :
                      (SbClkDiv == 3'd3) ? 6'd15 : 6'd20;

  reg [5:0] sb_cnt = 6'd0;
  always @(posedge main_clk) begin
    if (SbClkBypassEn || (^sb_cnt === 1'bx) ||
        (sb_cnt + 6'd1 >= sb_div)) sb_cnt <= 6'd0;
    else sb_cnt <= sb_cnt + 6'd1;
  end
  wire sb_divided = sb_cnt < ((sb_div + 6'd1) >> 1);

  assign SbClk = SbClkBypassEn ? SbBypassClk : sb_divided;

  // ---- Outputs ----
  // Latch the enable on each clock's low phase so that changing ClkGateEn
  // mid-cycle cannot chop a pulse short. A runt here would reach the lane
  // dividers as a real edge and defeat the point of gating. TxClkQ's gate
  // sits ahead of the global delay line; see `txClkQEn`.
  reg txClkEn;
  always @(*) begin
    if (!tx_i_raw) txClkEn = ClkGateEn;
  end

  assign TxClk = tx_i_raw & txClkEn;
  assign TxClkQ = q_delayed;
endmodule
