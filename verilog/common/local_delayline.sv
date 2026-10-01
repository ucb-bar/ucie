`timescale 1ps/100fs

// Per-lane trim on a tile's high speed clock. `dl_ctrl` is thermometer coded,
// so the delay follows the number of taps enabled rather than the value of the
// bus.
module local_delayline #(
    parameter integer CTRL_BITWIDTH = 32,
    parameter real    DELAY_OFS     = `DCDL_DELAY_OFS,
    parameter real    DELAY_STEP    = `DCDL_DELAY_STEP
)(
    input  logic [CTRL_BITWIDTH-1:0] dl_ctrl,
    input  logic clk_in,
    output logic clk_out
);

    assign #(DELAY_OFS + $countones(dl_ctrl) * DELAY_STEP) clk_out = clk_in;

endmodule
