module ucie_clk_dist_network(
    input bypassClkP,
    input bypassClkN,

    output clkMuxP_in0,
    output clkMuxP_in1,
    input clkMuxP_out,
    output clkMuxN_in0,
    output clkMuxN_in1,
    input clkMuxN_out,

    output txClkDivClk,
    output rxClkDivClk,

    input rxClkP,
    input rxClkN,
    output [19:0] txLaneClkP,
    output [19:0] txLaneClkN,
    output [17:0] rxLaneClk
);
    assign clkMuxP_in1 = bypassClkP;
    assign clkMuxN_in1 = bypassClkN;

    assign txClkDivClk = clkMuxP_out;
    generate
        for (genvar i = 0; i < 20; i++) begin
            assign txLaneClkP[i] = clkMuxP_out;
            assign txLaneClkN[i] = clkMuxN_out;
        end
    endgenerate

    assign rxClkDivClk = rxClkP;
    generate
        for (genvar i = 0; i < 18; i++) begin
            assign rxLaneClk[i] = rxClkP;
        end
    endgenerate
endmodule
