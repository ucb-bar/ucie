// Behavioral model of the UCIe clocking tile.
//
// Sources every clock the PHY runs on, from three PLLs and two bypass pins:
//
//   off chip   RefClk          100 MHz, what the PLLs lock to
//              BypassClk       the analog bypass clock, up to 12 GHz
//              DigBypassClk    the digital bypass clock, 800 MHz
//   on chip    pll1            8 GHz    ) each individually enabled, so an
//              pll2            12 GHz   ) unselected one can be powered down
//              pll3            16 GHz   )
//
// and derives from them:
//
//   main clock   one of the three PLLs or the analog bypass pin
//   TXCLK        the main clock divided by 1, 2, 4 or 8
//   TXCLKQ       the same division, phase shifted by a whole number of main
//                clock half cycles, then through the global delay line
//   DigitalClk   the digital bypass pin, or the main clock divided by
//                1, 5, 10, 15 or 20 -- the ratios that land 800 MHz from a
//                4, 8, 12 or 16 GHz main clock. The divider stops whenever
//                the bypass pin is selected, so there is nothing to disable
//                by hand.
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
// 16, all inside the 64 ps the global delay line covers. Without the shifter
// the delay line alone falls short of a UI below 16 GT/s.
//
// At division 1 the counter has only two positions, 0 and 180 degrees. There
// is no quadrature to be had from a divider that is not dividing, but 180 is
// enough to walk an eye at the top rate.
module clocking_tile(
  // Global delay line on TXCLKQ, thermometer coded, 1 ps a tap.
  input [63:0] PhaseSel,
  // 0 pll1 (8 GHz), 1 pll2 (12 GHz), 2 pll3 (16 GHz), 3 analog bypass pin.
  input [1:0] MainClkSel,
  input Pll1En,
  input Pll2En,
  input Pll3En,
  // 0 /1, 1 /2, 2 /4, 3 /8.
  input [1:0] TxClkDiv,
  // TXCLKQ's lead over TXCLK, in main clock half cycles, 0 to 2*div-1.
  input [3:0] TxClkPhase,
  // 0 /1, 1 /5, 2 /10, 3 /15, 4 /20.
  input [2:0] DigClkDiv,
  // High takes the digital clock from the bypass pin instead of the divider.
  input DigClkBypassEn,
  // Active high enable for the TX clock outputs. DigitalClk is never gated.
  input ClkGateEn,
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
  localparam real PHASE_TAP_PS = 1.0;

  // ---- Three PLLs, each locking a half period to the reference ----
  real ref_last = -1.0;
  real ref_period = 10000.0;
  always @(posedge RefClk) begin
    if (ref_last >= 0.0) ref_period = $realtime - ref_last;
    ref_last = $realtime;
  end

  reg pll1 = 1'b0, pll2 = 1'b0, pll3 = 1'b0;
  always begin
    if (!Pll1En) @(Pll1En);
    else #(ref_period / 160.0) pll1 = ~pll1;   // x80
  end
  always begin
    if (!Pll2En) @(Pll2En);
    else #(ref_period / 240.0) pll2 = ~pll2;   // x120
  end
  always begin
    if (!Pll3En) @(Pll3En);
    else #(ref_period / 320.0) pll3 = ~pll3;   // x160
  end

  // ---- Main clock ----
  wire main_clk = (MainClkSel == 2'd0) ? pll1 :
                  (MainClkSel == 2'd1) ? pll2 :
                  (MainClkSel == 2'd2) ? pll3 : BypassClk;

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

  reg q_delayed = 1'b0;
  always @(tx_q_raw) q_delayed <= #(phase_delay) tx_q_raw;

  // ---- Digital clock divider ----
  wire [5:0] dig_div = (DigClkDiv == 3'd0) ? 6'd1 :
                       (DigClkDiv == 3'd1) ? 6'd5 :
                       (DigClkDiv == 3'd2) ? 6'd10 :
                       (DigClkDiv == 3'd3) ? 6'd15 : 6'd20;

  // The divider stops on its own when the digital clock is not coming from
  // it: nothing selects it, so nothing has to be told to switch it off.
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

  // ---- Outputs ----
  // Latch the enable on each clock's low phase so that changing ClkGateEn
  // mid-cycle cannot chop a pulse short. A runt here would reach the lane
  // dividers as a real edge and defeat the point of gating.
  reg txClkEn;
  reg txClkQEn;
  always @(*) begin
    if (!tx_i_raw) txClkEn = ClkGateEn;
  end
  always @(*) begin
    if (!q_delayed) txClkQEn = ClkGateEn;
  end

  assign TxClk = tx_i_raw & txClkEn;
  assign TxClkQ = q_delayed & txClkQEn;
endmodule
