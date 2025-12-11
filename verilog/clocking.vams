module clkdiv #(
    parameter integer STAGES = 4,
    parameter real T_CLKQ = 30.0, // Clock-to-q time in ps.
    parameter real T_SETUP = 20.0,   // Setup time in ps
    parameter real T_HOLD  = 20.0   // Hold time in ps
)(
    input logic clkin,
    output logic [STAGES:0] clkout,
    input logic rstb
);
    pos_dff #(
        .T_CLKQ(T_CLKQ),
        .T_SETUP(T_SETUP),
        .T_HOLD(T_HOLD)
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
                .T_CLKQ(T_CLKQ),
                .T_SETUP(T_SETUP),
                .T_HOLD(T_HOLD)
            ) ff (
                .clk(clkout[i]),
                .rstb(rstb),
                .d(~clkout[i+1]),
                .q(clkout[i+1])
            );
        end
    endgenerate
endmodule
