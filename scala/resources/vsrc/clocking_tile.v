// Behavioral model of the UCIe clocking tile.
//
// Sources every clock the PHY runs on, from two PLLs, a doubler, and three
// bypass pins:
//
//   off chip   RefClk          100 MHz, what the PLLs lock to
//              BypassClk       the analog bypass clock, any rate up to 16 GHz
//              DigBypassClk    the PhyTest bypass clock, 800 MHz
//              SbBypassClk     the sideband bypass clock, 800 MHz
//   on chip    pll8            8 GHz    ) named for their output; each is
//              pll12           12 GHz   ) individually enabled, so an
//                                       ) unselected one can be powered down
//              x2              16 GHz, pll8 doubled; enabled on its own, and
//                              silent unless pll8 is running
//
// and derives from them:
//
//   main clock   pll8, pll12, the doubler, or the analog bypass pin, through
//                the clock gate
//   TXCLK        the main clock divided by 1, 2, 4 or 8
//   TXCLKQ       the same division, phase shifted by a whole number of main
//                clock half cycles, then through the global delay line
//   PhyTestClk   the PhyTest bypass pin, or the main clock divided by
//                1, 2, 4, 8 or 15 -- the ratios that land a little over 1 GHz
//                from an 8 or 16 GHz main clock
//   SbClk        the sideband bypass pin, or the main clock divided by
//                1, 5, 10, 15 or 20, which is where 800 MHz comes from
//
// The clock gate sits straight after the main clock mux, so it stops all three
// dividers at once and they hold their count until it opens again. It is
// glitch free: its enable is latched while the main clock is low, so the gate
// only ever opens or closes between pulses, and whatever the mux does while
// the gate is shut never reaches a divider. The bypass pins come in after the
// dividers and are not gated.
//
// A divider is held at zero while its bypass pin is selected, so deselecting
// one starts a fresh period rather than landing mid-count.
//
// The TX divider and the phase shifter are one circuit. A counter over main
// clock half cycles, modulo twice the division ratio, gives every phase the
// divider can reach: `TXCLK` is the first half of that count and `TXCLKQ` the
// same window moved along by `TxClkPhase`. In silicon that is cascaded pairs
// of flip flops with the unused stages disabled; the counter is the
// behavioural equivalent and reaches exactly the same positions.
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
  // 0 pll8, 1 pll12, 2 pll8 doubled, 3 the analog bypass pin.
  input [1:0] MainClkSel,
  input Pll8En,
  input Pll12En,
  // Enables the doubler. It doubles pll8, so pll8 has to be enabled too.
  input X2En,
  // 0 /1, 1 /2, 2 /4, 3 /8.
  input [1:0] TxClkDiv,
  // TXCLKQ's lead over TXCLK, in main clock half cycles, 0 to 2*div-1.
  input [3:0] TxClkPhase,
  // 0 /1, 1 /2, 2 /4, 3 /8, 4 /15. The ratios that land a little over 1 GHz
  // from an 8 or 16 GHz main clock.
  input [2:0] PhyTestClkDiv,
  // High takes the PhyTest clock from the bypass pin instead of the divider.
  input PhyTestClkBypassEn,
  // 0 /1, 1 /5, 2 /10, 3 /15, 4 /20. The sideband runs at 800 MHz, which is a
  // different ratio off the same main clock, so it gets its own divider.
  input [2:0] SbClkDiv,
  // High takes the sideband clock from its own bypass pin.
  input SbClkBypassEn,
  // Active high enable for the main clock, and so for every divided clock.
  // A clock taken from a bypass pin keeps running.
  input ClkGateEn,
  input RefClk,
  input DigBypassClk,
  input SbBypassClk,
  input BypassClk,
  // High once a PLL has settled after being enabled. Low while it is off, and
  // low from the moment it is enabled until it has had its wake-up time. The
  // doubler has no lock of its own: it is ready once pll8 is.
  output Pll8Lock,
  output Pll12Lock,
  output PhyTestClk,
  output SbClk,
  output TxClkQ,
  output TxClk
);
  localparam real PHASE_TAP_PS = 1.086;   // matches `global_delayline`
  // How long a PLL takes to settle once enabled. Short next to a real one so
  // that a rate sweep does not dominate a run, long enough that an apply that
  // ungates without waiting is visibly wrong.
  parameter real PLL_LOCK_PS = 1000000.0;   // 1 us

  // ---- Two PLLs, each locking a half period to the reference ----
  real ref_last = -1.0;
  real ref_period = 10000.0;
  always @(posedge RefClk) begin
    if (ref_last >= 0.0) ref_period = $realtime - ref_last;
    ref_last = $realtime;
  end

  reg pll8 = 1'b0, pll12 = 1'b0;
  always begin
    if (!Pll8En) @(Pll8En);
    else #(ref_period / 160.0) pll8 = ~pll8;   // x80
  end
  always begin
    if (!Pll12En) @(Pll12En);
    else #(ref_period / 240.0) pll12 = ~pll12;   // x120
  end

  // ---- Doubler ----
  // A pulse a quarter of a pll8 period long on every pll8 edge, rising and
  // falling: two cycles for each of pll8's, at an even duty cycle. Dropping
  // the enable mid-pulse finishes the pulse rather than cutting it short, and
  // with pll8 stopped there are no edges to double.
  reg x2 = 1'b0;
  always @(pll8) begin
    if (X2En) begin
      x2 = 1'b1;
      #(ref_period / 320.0) x2 = 1'b0;
    end
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
    #(PLL_LOCK_PS) pll8Locked = Pll8En;
  end
  always @(negedge Pll8En) pll8Locked = 1'b0;

  reg pll12Locked = 1'b0;
  always @(posedge Pll12En) begin
    #(PLL_LOCK_PS) pll12Locked = Pll12En;
  end
  always @(negedge Pll12En) pll12Locked = 1'b0;

  assign Pll8Lock = pll8Locked;
  assign Pll12Lock = pll12Locked;

  // ---- Main clock mux and clock gate ----
  wire main_ungated = (MainClkSel == 2'd0) ? pll8 :
                      (MainClkSel == 2'd1) ? pll12 :
                      (MainClkSel == 2'd2) ? x2 : BypassClk;

  // The enable is latched while the main clock is low, so changing `ClkGateEn`
  // mid-pulse cannot chop that pulse short: a glitch here would reach every
  // divider as a real edge and defeat the point of gating.
  reg main_en = 1'b0;
  always @(*) begin
    if (!main_ungated) main_en = ClkGateEn;
  end
  wire main_clk = main_ungated & main_en;

  // ---- TX divider and phase shifter ----
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
  wire tx_i = half_cnt < {1'b0, tx_div};
  wire tx_q = q_cnt < {1'b0, tx_div};

  // ---- Global delay line, on the quadrature output only ----
  integer phase_taps;
  integer pi;
  always @(*) begin
    phase_taps = 0;
    for (pi = 0; pi < 64; pi = pi + 1) phase_taps = phase_taps + PhaseSel[pi];
  end
  real phase_delay;
  always @(*) phase_delay = phase_taps * PHASE_TAP_PS;

  reg q_delayed = 1'b0;
  always @(tx_q) q_delayed <= #(phase_delay) tx_q;

  // ---- PhyTest clock divider ----
  wire [5:0] phytest_div = (PhyTestClkDiv == 3'd0) ? 6'd1 :
                           (PhyTestClkDiv == 3'd1) ? 6'd2 :
                           (PhyTestClkDiv == 3'd2) ? 6'd4 :
                           (PhyTestClkDiv == 3'd3) ? 6'd8 : 6'd15;

  // Held at zero while the bypass pin is selected, so switching back to the
  // divider starts a fresh period.
  reg [5:0] phytest_cnt = 6'd0;
  always @(posedge main_clk) begin
    if (PhyTestClkBypassEn || (^phytest_cnt === 1'bx) ||
        (phytest_cnt + 6'd1 >= phytest_div)) phytest_cnt <= 6'd0;
    else phytest_cnt <= phytest_cnt + 6'd1;
  end
  // An odd ratio cannot give an even mark to space, which costs a behavioural
  // model nothing: what the PhyTest domain needs is the edge rate.
  wire phytest_divided = phytest_cnt < ((phytest_div + 6'd1) >> 1);

  assign PhyTestClk = PhyTestClkBypassEn ? DigBypassClk : phytest_divided;

  // ---- Sideband clock divider ----
  // Its own divider off the same main clock: the sideband runs at a rate the
  // PhyTest domain does not.
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
  assign TxClk = tx_i;
  assign TxClkQ = q_delayed;
endmodule
