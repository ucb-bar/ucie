// Behavioral model of the 12 GHz PLL, `12gpll_top` in the IP library, under
// the name the RTL instantiates it by: Verilog cannot use `12gpll_top`
// unescaped.
//
// Multiplies the ref clock by 120 whenever its RDAC, `Dbias`, is nonzero:
// zero powers it off. `reset` holds only the digital loop, so lock comes
// `PLL_LOCK_PS` after the PLL is both running and out of reset, and drops
// the instant either stops. The loop's settings are otherwise ignored, and
// the codes it reports read zero. `clk_out` and `lock` are not on the cell
// yet.
module pll12g_top(
  input clk_ref,
  input reset,
  input auto_en,
  input [15:0] kp,
  input [15:0] ki,
  input [7:0] code_init,
  input [19:0] code_byp,
  input [8:0] Dbias,
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

  // Running: the RDAC on. An X on it counts as off.
  wire running = (|Dbias) === 1'b1;

  reg clk = 1'b0;
  always begin
    if (!running) @(running);
    else #(ref_period / 240.0) clk = ~clk;   // x120
  end

  // Lock `PLL_LOCK_PS` after the PLL is running with its loop out of reset,
  // and dropped the instant either stops. Losing lock while running is not
  // modelled; what this is for is the wake-up wait, which is the one an apply
  // has to hold the clock gate across. The blocking delay also holds off a
  // re-trigger, so a PLL turned off mid wake-up resolves to whatever it reads
  // by then.
  wire settling = running && (reset === 1'b0);
  reg locked = 1'b0;
  always @(posedge settling) begin
    #(PLL_LOCK_PS) locked = settling;
  end
  always @(negedge settling) locked = 1'b0;

  assign clk_out = clk;
  assign lock = locked;
  assign code_full = 20'd0;
  assign code_int = 8'd0;
endmodule
