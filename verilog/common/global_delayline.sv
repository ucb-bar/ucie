`timescale 1ps/100fs

// Delay line on TXCLKQ, shared by every lane. `dl_ctrl` is thermometer coded.
module global_delayline #(
    parameter integer CTRL_BITWIDTH = `GLOBAL_DL_CTRL_BITWIDTH,
    parameter real    DELAY_OFS     = `GLOBAL_DL_DELAY_OFS,
    parameter real    DELAY_STEP    = `GLOBAL_DL_DELAY_STEP
)(
    input  logic [CTRL_BITWIDTH-1:0] dl_ctrl,
    input  logic clk_in,
    output logic clk_out
);

    assign #(DELAY_OFS + $countones(dl_ctrl) * DELAY_STEP) clk_out = clk_in;

endmodule
