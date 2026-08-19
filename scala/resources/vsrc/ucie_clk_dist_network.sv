module ucie_clk_dist_network(
    input txClk,
    input txClkQ,

    output txClkDivClk,
    output rxClkDivClk,

    input rxClk,
    output [19:0] txLaneClk,
    output [17:0] rxLaneClk
);
    // Lane map for numLanes = 16: 0..15 data, 16 valid, 17 and 18 the two
    // forwarded-clock lanes, 19 track. The clock lanes run off the quadrature
    // phase so the transmitted clock is centered in the data eye; everything
    // else runs off the in-phase clock.
    localparam integer TXCLKP_LANE = 17;
    localparam integer TXCLKN_LANE = 18;

    assign txClkDivClk = txClk;
    generate
        for (genvar i = 0; i < 20; i++) begin
            assign txLaneClk[i] =
                (i == TXCLKP_LANE || i == TXCLKN_LANE) ? txClkQ : txClk;
        end
    endgenerate

    assign rxClkDivClk = rxClk;
    generate
        for (genvar i = 0; i < 18; i++) begin
            assign rxLaneClk[i] = rxClk;
        end
    endgenerate
endmodule
