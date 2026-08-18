module ucie_clk_dist_network(
    input bypassClk,

    output clkMux_in0,
    output clkMux_in1,
    input clkMux_out,

    output txClkDivClk,
    output rxClkDivClk,

    input rxClk,
    output [19:0] txLaneClk,
    output [17:0] rxLaneClk
);
    assign clkMux_in1 = bypassClk;

    assign txClkDivClk = clkMux_out;
    generate
        for (genvar i = 0; i < 20; i++) begin
            assign txLaneClk[i] = clkMux_out;
        end
    endgenerate

    assign rxClkDivClk = rxClk;
    generate
        for (genvar i = 0; i < 18; i++) begin
            assign rxLaneClk[i] = rxClk;
        end
    endgenerate
endmodule
