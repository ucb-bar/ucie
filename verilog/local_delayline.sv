`timescale 1ps/100fs

module local_delayline #(
    parameter integer CTRL_BITWIDTH = 32,      // thermometer code width
    parameter real    DELAY_OFS     = 30.0,    // delay at x=0 (ps)
    parameter real    DELAY_STEP    = 0.16875  // 5.4ps tuning range / 32
)(
    input  logic [CTRL_BITWIDTH-1:0] dl_ctrl,
    input  logic clk_in,
    output logic clk_out
);

    assign #(DELAY_OFS + $countones(dl_ctrl) * DELAY_STEP) clk_out = clk_in;

endmodule
