module clkdiv #(
    parameter integer STAGES = 4,
    parameter real T_CLKQ = 5.0 // Clock-to-q time in ps.
)(
    input logic clkin,
    output logic [STAGES:0] clkout,
    input logic rstb
);
    pos_dff #(
        .T_CLKQ(T_CLKQ)
    ) ff (
        .clk(clkin),
        .rstb(rstb),
        .d(~clkout[0]),
        .q(clkout[0])
    );
    genvar i;
    generate
        for (i = 0; i < STAGES - 1; i++) begin
            pos_dff #(
                .T_CLKQ(T_CLKQ)
            ) ff (
                .clk(clkout[i]),
                .rstb(rstb),
                .d(~clkout[i+1]),
                .q(clkout[i+1])
            );
        end
    endgenerate
endmodule
