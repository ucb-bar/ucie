module ucie_clk_dist_network(
    output clkMuxP_in0,
    output clkMuxP_in1,
    input clkMuxP_out,
    output clkMuxN_in0,
    output clkMuxN_in1,
    input clkMuxN_out,

    output txClkDivClk,
    output rxClkDivClk,

    output [19:0] txLaneClkP,
    output [19:0] txLaneClkN,
    output [17:0] rxLaneClk
);
endmodule
