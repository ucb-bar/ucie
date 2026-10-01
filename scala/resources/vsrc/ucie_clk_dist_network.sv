module ucie_clk_dist_network(
    input txClk,
    input txClkQ,

    output txClkDivClk,
    output rxClkDivClk,

    input rxClk,
    output [19:0] txLaneClk,
    output [17:0] rxLaneClk
);
    // Lane map for numLanes = 16: 0..15 data, 16 valid, 17 track, 18 and 19 the
    // two forwarded-clock lanes. The clock lanes run off the quadrature phase so
    // the transmitted clock is centered in the data eye; everything else runs
    // off the in-phase clock.
    localparam integer TXCLKP_LANE = 18;
    localparam integer TXCLKN_LANE = 19;

    // Each lane's clock before distribution. The two forwarded-clock lanes run
    // off the quadrature phase, everything else off the in-phase clock.
    wire [19:0] txLaneSrc;
    generate
        for (genvar i = 0; i < 20; i++) begin
            assign txLaneSrc[i] =
                (i == TXCLKP_LANE || i == TXCLKN_LANE) ? txClkQ : txClk;
        end
    endgenerate

`ifdef UCIE_CLK_SKEW
    // Under the analog models the network is a real tree, and its arms are not
    // the same length: each lane picks up its own static delay, drawn once per
    // elaboration. That skew is what a per-lane delay trim exists to absorb,
    // so a sweep run against a skewless fanout cannot show the trim doing
    // anything -- every lane trains to the same code whatever the models
    // underneath say.
    //
    // `clocking_distribution_model` fans one clock out to sixteen, so the
    // lanes are grouped by the clock they take: the data lanes off the
    // in-phase clock, valid and track off the same but through their own arm,
    // and the two forwarded-clock lanes off the quadrature clock. Outputs
    // beyond what a group needs are left unused.
    //
    // Off by default, including under the analog models: the tree is eighty
    // analog clock nets and costs several times the run time of the fanout.
    // A sweep that is demonstrating per-lane trim turns it on; everything
    // else, digital or analog, takes the plain fanout below.
    clocking_distribution_model #(
        .propagation_delay_mu(`CLK_DIST_DELAY_MU),
        .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
    ) tx_data_dist (.clk_in(txClk), .clk_out(txLaneClk[15:0]));

    wire [15:0] tx_aux;
    clocking_distribution_model #(
        .propagation_delay_mu(`CLK_DIST_DELAY_MU),
        .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
    ) tx_aux_dist (.clk_in(txClk), .clk_out(tx_aux));
    assign txLaneClk[16] = tx_aux[0];
    assign txLaneClk[17] = tx_aux[1];

    wire [15:0] tx_q;
    clocking_distribution_model #(
        .propagation_delay_mu(`CLK_DIST_DELAY_MU),
        .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
    ) tx_q_dist (.clk_in(txClkQ), .clk_out(tx_q));
    assign txLaneClk[TXCLKP_LANE] = tx_q[0];
    assign txLaneClk[TXCLKN_LANE] = tx_q[1];

    clocking_distribution_model #(
        .propagation_delay_mu(`CLK_DIST_DELAY_MU),
        .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
    ) rx_data_dist (.clk_in(rxClk), .clk_out(rxLaneClk[15:0]));

    wire [15:0] rx_aux;
    clocking_distribution_model #(
        .propagation_delay_mu(`CLK_DIST_DELAY_MU),
        .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
    ) rx_aux_dist (.clk_in(rxClk), .clk_out(rx_aux));
    assign rxLaneClk[16] = rx_aux[0];
    assign rxLaneClk[17] = rx_aux[1];
`else
    assign txLaneClk = txLaneSrc;
    generate
        for (genvar i = 0; i < 18; i++) begin
            assign rxLaneClk[i] = rxClk;
        end
    endgenerate
`endif

    // The divider clocks take the tree's nominal delay and none of its spread,
    // so the global dividers sit in the middle of the lane spread rather than
    // at one edge of it.
    assign txClkDivClk = txClk;
    assign rxClkDivClk = rxClk;
endmodule
