// Behavioral model of the 8 GHz PLL, `8gpll_top` in the IP library, under the
// name the RTL instantiates it by: Verilog cannot use `8gpll_top` unescaped.
//
// Multiplies the ref clock by 80 whenever `reset` is low -- `reset` is the
// PLL's enable, inverted -- and reports lock `PLL_LOCK_PS` after it falls.
// The digital loop is not modelled: its settings are ignored and the codes it
// reports read zero. `clk_out` and `lock` are not on the cell yet.
module pll8g_top(
  input clk_ref,
  input reset,
  input auto_en,
  input [15:0] kp,
  input [15:0] ki,
  input [7:0] code_init,
  input [19:0] code_byp,
  input [8:0] Dbias,
  input [5:0] Dcoarse,
  input [5:0] Dcoarseb,
  output [19:0] code_full,
  output [7:0] code_int,
  output clk_out,
  output lock
);
  // Every delay here is in picoseconds, well below the 100 ps precision some
  // harnesses compile with by default, so the module sets its own unit and
  // precision. Declared here rather than with `timescale, which would carry
  // over into every file compiled after this one.
  timeunit 1ps;
  timeprecision 1fs;

  // How long the PLL takes to settle once out of reset. Short next to a real
  // one so that a rate sweep does not dominate a run, long enough that an
  // apply that ungates without waiting is visibly wrong.
  parameter real PLL_LOCK_PS = 1000000.0;   // 1 us

  // Locks a half period to the ref clock.
  real ref_last = -1.0;
  real ref_period = 10000.0;
  always @(posedge clk_ref) begin
    if (ref_last >= 0.0) ref_period = $realtime - ref_last;
    ref_last = $realtime;
  end

  reg clk = 1'b0;
  always begin
    if (reset !== 1'b0) @(reset);
    else #(ref_period / 160.0) clk = ~clk;   // x80
  end

  // Lock `PLL_LOCK_PS` after leaving reset, and dropped the instant reset
  // returns. Losing lock while running is not modelled; what this is for is
  // the wake-up wait, which is the one an apply has to hold the clock gate
  // across. The blocking delay also holds off a re-trigger, so a reset that
  // returns mid wake-up resolves to whatever `reset` reads by then.
  reg locked = 1'b0;
  always @(negedge reset) begin
    #(PLL_LOCK_PS) locked = (reset === 1'b0);
  end
  always @(posedge reset) locked = 1'b0;

  assign clk_out = clk;
  assign lock = locked;
  assign code_full = 20'd0;
  assign code_int = 8'd0;
endmodule
