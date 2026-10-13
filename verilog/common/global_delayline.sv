`timescale 1ps/100fs

// Delay line on TXCLKQ, shared by every lane. `dl_ctrl` is thermometer coded.
//
// A transport delay rather than a continuous assignment's: the line is far
// longer than a clock period -- `GLOBAL_DL_DELAY_OFS` alone is 80 of them -- and
// a delayed `assign` is inertial, so it would swallow every pulse shorter than
// the delay and pass no clock at all. The same goes for `clocking_tile.v`'s
// line in `scala/resources/vsrc`, which is written the same way for the same
// reason.
module global_delayline #(
    parameter integer CTRL_BITWIDTH = `GLOBAL_DL_CTRL_BITWIDTH,
    parameter real    DELAY_OFS     = `GLOBAL_DL_DELAY_OFS,
    parameter real    DELAY_STEP    = `GLOBAL_DL_DELAY_STEP
)(
    input  logic [CTRL_BITWIDTH-1:0] dl_ctrl,
    input  logic clk_in,
    output logic clk_out
);

    always @(clk_in)
        clk_out <= #(DELAY_OFS + $countones(dl_ctrl) * DELAY_STEP) clk_in;

endmodule
