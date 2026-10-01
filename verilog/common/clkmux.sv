`timescale 1ps/100fs

module clkmux #(
    parameter real DELAY = 10.0   // input to output delay (ps)
)(
    input  logic clk_en,
    input  logic clk_enb,
    input  logic EN,
    input  logic ENB,
    output logic clk_out
);

    always_comb begin
        if (!$isunknown({EN, ENB}) && EN === ENB)
            $error("clkmux: EN and ENB must be complementary (EN=%b ENB=%b)", EN, ENB);
    end

    assign #(DELAY) clk_out = (EN & clk_en) | (ENB & clk_enb);

endmodule
