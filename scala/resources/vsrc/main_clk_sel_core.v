// Behavioral model of the TX clock generator, `main_clk_sel_core`.
//
// The X2 multiplier doubles PLL8 whenever PLL8 runs; it has no enable. The
// UCIe main clock is PLL12, PLL8, the X2 clock or the analog bypass pin
// (`mainClkSel` 0 to 3), with no gate after it: the TX clock gate is after the
// global delay line, in another macro, so everything here runs whenever its
// source does.
//
// The main clock feeds four dividers, a /2 and a /4 for each of the TX data
// clock and the TX quadrature clock, and `TxClkSel` and `TxClkQSel` each pick
// /2, /1 -- the main clock itself -- nothing, or /4 (0 to 3). Each /4 is a
// two-flop Johnson counter and each /2 one flop, as in the cell.
//
// The data dividers count the main clock's falling edges. Each quadrature
// divider counts the same edges with its `S2DSel` high, or the rising ones
// with it low, half a main clock period later; and its rotate select takes
// its output a whole number of main clock periods later still. So the
// quadrature clock lags the data clock by 2 * rotate + !S2DSel main clock
// half cycles. `S2DSel_B` is the select's complement, which the cell's
// transmission gates need and this model ignores.
//
// `reset` holds all four dividers, and is not on the cell yet. It releases on
// a rising edge of the main clock, so the data dividers, and a quadrature one
// on falling edges, first count half a period after it, and a quadrature
// divider on rising edges a whole period after. That is what makes the lag
// above exact rather than a cycle out either way; the cell has to release the
// same way, synchronized to the main clock, for its lag to be. With no main
// clock the dividers stay held.
module main_clk_sel_core(
  input PLLclk_8G,
  input PLLclk_12G,
  input clk_bypass,
  input [1:0] mainClkSel,
  input [1:0] TxClkSel,
  input [1:0] TxClkQSel,
  input TxClkQDiv2PhaseSel,
  input TxClkQDiv2PhaseS2DSel,
  input TxClkQDiv2PhaseS2DSel_B,
  input [1:0] TxClkQDiv4PhaseSel,
  input TxClkQDiv4PhaseS2DSel,
  input TxClkQDiv4PhaseS2DSel_B,
  input reset,
  output TxClk,
  output TxClkQ
);
  // The X2 pulse is a few picoseconds long, well below the 100 ps precision
  // some harnesses compile with by default, so the module sets its own unit
  // and precision. Declared here rather than with `timescale, which would
  // carry over into every file compiled after this one.
  timeunit 1ps;
  timeprecision 1fs;

  // ---- X2 multiplier ----
  // A pulse a quarter of a PLL8 period long on every PLL8 edge, rising and
  // falling: two cycles for each of PLL8's, at an even duty cycle. The quarter
  // period is half the last gap between PLL8 edges; a gap of a nanosecond or
  // more is PLL8 stopping, not a half period, and is not taken.
  real pll8_last = -1.0;
  real pll8_half = 62.5;
  reg x2 = 1'b0;
  always @(PLLclk_8G) begin
    if (pll8_last >= 0.0 && $realtime - pll8_last < 1000.0)
      pll8_half = $realtime - pll8_last;
    pll8_last = $realtime;
    x2 = 1'b1;
    #(pll8_half / 2.0) x2 = 1'b0;
  end

  // ---- UCIe main clock mux ----
  wire clk_main = (mainClkSel == 2'd0) ? PLLclk_12G :
                  (mainClkSel == 2'd1) ? PLLclk_8G :
                  (mainClkSel == 2'd2) ? x2 : clk_bypass;

  // ---- The dividers' reset, released on a rising main clock edge ----
  reg rst_q = 1'b1;
  always @(posedge clk_main or posedge reset) begin
    if (reset) rst_q <= 1'b1;
    else rst_q <= 1'b0;
  end

  // ---- TX data dividers, on the main clock's falling edges ----
  // The /4's Johnson counter is {Q1, Q0}: Q0 takes !Q1 and Q1 takes Q0, so Q1
  // is Q0 a main clock period later.
  reg data_d2 = 1'b0;
  reg [1:0] data_d4 = 2'b00;
  always @(negedge clk_main or posedge rst_q) begin
    if (rst_q) begin
      data_d2 <= 1'b0;
      data_d4 <= 2'b00;
    end else begin
      data_d2 <= ~data_d2;
      data_d4 <= {data_d4[0], ~data_d4[1]};
    end
  end

  // ---- TX quadrature dividers, on whichever edge `S2DSel` picks ----
  wire q2_clk = TxClkQDiv2PhaseS2DSel ? ~clk_main : clk_main;
  wire q4_clk = TxClkQDiv4PhaseS2DSel ? ~clk_main : clk_main;
  reg q_d2 = 1'b0;
  reg [1:0] q_d4 = 2'b00;
  always @(posedge q2_clk or posedge rst_q) begin
    if (rst_q) q_d2 <= 1'b0;
    else q_d2 <= ~q_d2;
  end
  always @(posedge q4_clk or posedge rst_q) begin
    if (rst_q) q_d4 <= 2'b00;
    else q_d4 <= {q_d4[0], ~q_d4[1]};
  end

  // The rotate selects: the /2's output or its complement, a period later;
  // the /4's Q0, Q1, !Q0 or !Q1, each a period after the one before.
  wire q_div2 = TxClkQDiv2PhaseSel ? ~q_d2 : q_d2;
  wire q_div4 = (TxClkQDiv4PhaseSel == 2'd0) ? q_d4[0] :
                (TxClkQDiv4PhaseSel == 2'd1) ? q_d4[1] :
                (TxClkQDiv4PhaseSel == 2'd2) ? ~q_d4[0] : ~q_d4[1];

  assign TxClk = (TxClkSel == 2'd0) ? data_d2 :
                 (TxClkSel == 2'd1) ? clk_main :
                 (TxClkSel == 2'd2) ? 1'b0 : data_d4[0];
  assign TxClkQ = (TxClkQSel == 2'd0) ? q_div2 :
                  (TxClkQSel == 2'd1) ? clk_main :
                  (TxClkQSel == 2'd2) ? 1'b0 : q_div4;
endmodule
