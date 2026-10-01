`timescale 1ps/100fs

module s2d #(
    parameter real DELAY = 14.0   // input to output delay (ps)
)(
    input  logic clk_in,
    output logic clk_outp,
    output logic clk_outn
);

    assign #(DELAY) clk_outp =  clk_in;
    assign #(DELAY) clk_outn = ~clk_in;

endmodule
