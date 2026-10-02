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

    // Transport delay, not inertial.
    //
    // A continuous assignment with a delay (`assign #d out = in`) is inertial:
    // an input pulse shorter than the delay is swallowed instead of passed on.
    // This line is meant to span more than a UI -- `CTRL_BITWIDTH` taps of
    // `DELAY_STEP` is about 70 ps against a 62.5 ps half period at 16 GT/s --
    // so with an inertial delay it stops passing the clock at all somewhere
    // around three quarters of its own range, which is the part of the range
    // the thing exists for. A delay line passes everything through shifted,
    // which is what a non-blocking assignment per input event gives.
    //
    // The cost of transport delay is that a large DECREASE in the code lets an
    // edge already in flight be overtaken. That is real behaviour for a delay
    // line and not a modelling artefact; walk the code rather than jumping it,
    // or change it with the clock stopped.
    always @(clk_in)
        clk_out <= #(DELAY_OFS + $countones(dl_ctrl) * DELAY_STEP) clk_in;

endmodule
