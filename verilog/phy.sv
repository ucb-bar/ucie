interface phy_intf;
    txdriver_tile_intf sb_txdata(), sb_txclk();
    txdata_tile_intf txdata[`LANES]();
    txdata_tile_intf txclkp(), txclkn(), txval(), txtrk();

    sbrx_tile_intf sb_rxdata(), sb_rxclk();
    rxdata_tile_intf rxdata[`LANES]();
    rxdata_tile_intf rxval(), rxtrk();
    rxclk_tile_intf rxclkp(), rxclkn();
    logic pll_reset;
    wire pll_clk_out;
    wire pll_Dctrl_value;
endinterface

module phy(
    phy_intf intf
);

// TODO: add back after PLL model simulates faster and/or 
// jitter simulation is needed
// FIXME(Di): If you use the PLL model, make sure to turn on simulation noise and set the simulation time > 15us (which is the PLL lock time).  
// NOTE(Di): It's pretty slow to lock the PLL compared to other parts of the PHY.
// bbpll pll(
//     .reset(intf.pll_reset),
//     .clk_out(intf.pll_clk_out),
//     .Dctrl_value(intf.pll_Dctrl_value)
// );

txdriver_tile sb_txdata_drv(.intf(intf.sb_txdata));
txdriver_tile sb_txclk_drv(.intf(intf.sb_txclk));
wire [`LANES-1:0] txclk_sed;

clocking_distribution_model #(
    .propagation_delay_mu(`CLK_DIST_DELAY_MU),
    .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
)   clk_dist_inst_tx(
    .clk_in(intf.pll_clk_out),
    .clk_out(txclk_sed)
);

wire deskewed_clk_tx;
dcdl #(
    .delay_gain(0),
    // FIXME(Di): set the gain for DCDL 
    .delay_offset(2 * `CLK_DIST_DELAY_MU + `CLK_PERIOD/4)
) dcdl_inst(
    .clk_in(intf.pll_clk_out),
    // TODO(Di): connect the control signal to the main LogPHY controller
    .dl_ctrl(0), 
    .clk_out(deskewed_clk_tx)
);

// The TX tiles take a single-ended clock and do their own single to
// differential conversion internally, so there is no `s2d` on this path.
assign intf.txclkp.CK = deskewed_clk_tx;
assign intf.txclkn.CK = deskewed_clk_tx;
assign intf.txval.CK = deskewed_clk_tx;
assign intf.txtrk.CK = deskewed_clk_tx;

genvar i;
generate
    for(i = 0; i < `LANES; i++) begin
        // TODO(Di): DCC?
        assign intf.txdata[i].CK = txclk_sed[i];
        txdata_tile txdata_tile(.intf(intf.txdata[i]));
    end
endgenerate
txdata_tile txclkp_tile(.intf(intf.txclkp));
txdata_tile txclkn_tile(.intf(intf.txclkn));
txdata_tile txval_tile(.intf(intf.txval));
txdata_tile txtrk_tile(.intf(intf.txtrk));

wire [`LANES-1:0] rxclk_dist_sed;
clocking_distribution_model #(
    .propagation_delay_mu(`CLK_DIST_DELAY_MU),
    .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
)   clk_dist_inst_rx(
    .clk_in(rxclkp_tile.afe.dout), // HACK(Di): explicitly call the AFE output
//    .clk_in(intf.pll_clk_out),
    .clk_out(rxclk_dist_sed)
);

generate
    for(i = 0; i < `LANES; i++) begin
        // perform clock distribution on RX too
        assign intf.rxdata[i].clk = rxclk_dist_sed[i];
        rxdata_tile rxdata_tile(.intf(intf.rxdata[i]));
    end
endgenerate
rxclk_tile rxclkp_tile(.intf(intf.rxclkp));
rxclk_tile rxclkn_tile(.intf(intf.rxclkn));
rxdata_tile rxval_tile(.intf(intf.rxval));
rxdata_tile rxtrk_tile(.intf(intf.rxtrk));

endmodule

module phy_tb;
    wire vdd = 1, vss = 0;
    reg reset = 1;
    reg pll_clkp_out;
    wire pll_clkn_out;

    reg a_en, a_pc, b_en, b_pc, sel_a, din_dig;

    initial pll_clkp_out = 0;
    always #(`MIN_PERIOD/2) pll_clkp_out = ~pll_clkp_out;
    assign pll_clkn_out = ~pll_clkp_out;

    initial begin
        a_pc = 1;
        b_pc = 1;
        a_en = 0;
        b_en = 0;
        sel_a = 1;
    end

    initial begin
        #1000;
        forever begin
            a_pc = 0;
            #100;
            a_en = 1;
            #100;
            sel_a = 1;
            #100;
            b_en = 0;
            #100;
            b_pc = 1;
            #1000;
            b_pc = 0;
            #100;
            b_en = 1;
            #100;
            sel_a = 0;
            #100;
            a_en = 0;
            #100;
            a_pc = 1;
            #1000;
        end
    end

    phy_intf intf();

    phy phy(
        .intf(intf)
    );

    assign intf.pll_reset = reset;
    assign intf.pll_Dctrl_value = 1; // FIXME(Di): pll_Dctrl_value is an output showing the internal locking status of the PLL, so don't tie it to 1.
    assign intf.pll_clk_out = pll_clkp_out;
    assign intf.sb_txdata.vdd = vdd;
    assign intf.sb_txdata.vss = vss;
    assign intf.sb_txdata.pu_ctl = 0;
    assign intf.sb_txdata.pd_ctlb = {`DRIVER_CTL_BITS{1'b1}};
    assign intf.sb_txdata.en = 1;
    assign intf.sb_txdata.enb = 0;

    assign intf.sb_txclk.vdd = vdd;
    assign intf.sb_txclk.vss = vss;
    assign intf.sb_txclk.pu_ctl = 0;
    assign intf.sb_txclk.pd_ctlb = {`DRIVER_CTL_BITS{1'b1}};
    assign intf.sb_txclk.en = 1;
    assign intf.sb_txclk.enb = 0;

    genvar i;
    generate
        for (i = 0; i < `LANES; i++) begin
            assign intf.txdata[i].vddq = vdd;
            assign intf.txdata[i].vdd = vdd;
            assign intf.txdata[i].vss = vss;
            assign intf.txdata[i].DataIN = {2**(`SERDES_STAGES-1){2'b01}};
            assign intf.txdata[i].RST_async = reset;
            // Every main driver segment on (`ENP` is active low, `ENN` active high),
            // equalizer branch off, no added delay on the tile clock.
            assign intf.txdata[i].ENP = 0;
            assign intf.txdata[i].ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
            assign intf.txdata[i].ENP_EQ = {`TX_DRIVER_EQ_SEGMENTS{1'b1}};
            assign intf.txdata[i].ENN_EQ = 0;
            assign intf.txdata[i].Dctrl = 0;

            assign intf.rxdata[i].vdd = vdd;
            assign intf.rxdata[i].vss = vss;
            assign intf.rxdata[i].rstb = ~reset;
            assign intf.rxdata[i].zen = 1;
            assign intf.rxdata[i].zctl = 0;
            assign intf.rxdata[i].a_pc = a_pc;
            assign intf.rxdata[i].a_en = a_en;
            assign intf.rxdata[i].b_pc = b_pc;
            assign intf.rxdata[i].b_en = b_en;
            assign intf.rxdata[i].sel_a = sel_a;
            assign intf.rxdata[i].vref_sel = 80;
            assign intf.rxdata[i].din = intf.txdata[i].D2D_TX;
        end
    endgenerate

    assign intf.txclkp.vddq = vdd;
    assign intf.txclkp.vdd = vdd;
    assign intf.txclkp.vss = vss;
    assign intf.txclkp.DataIN = {2**(`SERDES_STAGES-1){2'b01}};
    assign intf.txclkp.RST_async = reset;
    // Every main driver segment on (`ENP` is active low, `ENN` active high),
    // equalizer branch off, no added delay on the tile clock.
    assign intf.txclkp.ENP = 0;
    assign intf.txclkp.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txclkp.ENP_EQ = {`TX_DRIVER_EQ_SEGMENTS{1'b1}};
    assign intf.txclkp.ENN_EQ = 0;
    assign intf.txclkp.Dctrl = 0;

    assign intf.txclkn.vddq = vdd;
    assign intf.txclkn.vdd = vdd;
    assign intf.txclkn.vss = vss;
    assign intf.txclkn.DataIN = {2**(`SERDES_STAGES-1){2'b10}};
    assign intf.txclkn.RST_async = reset;
    // Every main driver segment on (`ENP` is active low, `ENN` active high),
    // equalizer branch off, no added delay on the tile clock.
    assign intf.txclkn.ENP = 0;
    assign intf.txclkn.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txclkn.ENP_EQ = {`TX_DRIVER_EQ_SEGMENTS{1'b1}};
    assign intf.txclkn.ENN_EQ = 0;
    assign intf.txclkn.Dctrl = 0;

    assign intf.txval.vddq = vdd;
    assign intf.txval.vdd = vdd;
    assign intf.txval.vss = vss;
    assign intf.txval.DataIN = {2**(`SERDES_STAGES-3){8'hf0}};
    assign intf.txval.RST_async = reset;
    // Every main driver segment on (`ENP` is active low, `ENN` active high),
    // equalizer branch off, no added delay on the tile clock.
    assign intf.txval.ENP = 0;
    assign intf.txval.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txval.ENP_EQ = {`TX_DRIVER_EQ_SEGMENTS{1'b1}};
    assign intf.txval.ENN_EQ = 0;
    assign intf.txval.Dctrl = 0;

    assign intf.txtrk.vddq = vdd;
    assign intf.txtrk.vdd = vdd;
    assign intf.txtrk.vss = vss;
    assign intf.txtrk.DataIN = {2**(`SERDES_STAGES-1){2'b01}};
    assign intf.txtrk.RST_async = reset;
    // Every main driver segment on (`ENP` is active low, `ENN` active high),
    // equalizer branch off, no added delay on the tile clock.
    assign intf.txtrk.ENP = 0;
    assign intf.txtrk.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txtrk.ENP_EQ = {`TX_DRIVER_EQ_SEGMENTS{1'b1}};
    assign intf.txtrk.ENN_EQ = 0;
    assign intf.txtrk.Dctrl = 0;

    assign intf.rxclkp.vdd = vdd;
    assign intf.rxclkp.vss = vss;
    assign intf.rxclkp.clkin = intf.txclkp.D2D_TX;
    assign intf.rxclkp.zen = 1;
    assign intf.rxclkp.zctl = 0;
    assign intf.rxclkp.a_pc = a_pc;
    assign intf.rxclkp.a_en = a_en;
    assign intf.rxclkp.b_pc = b_pc;
    assign intf.rxclkp.b_en = b_en;
    assign intf.rxclkp.sel_a = sel_a;
    assign intf.rxclkp.vref_sel = 80;

    assign intf.rxclkn.vdd = vdd;
    assign intf.rxclkn.vss = vss;
    assign intf.rxclkn.clkin = intf.txclkn.D2D_TX;
    assign intf.rxclkn.zen = 1;
    assign intf.rxclkn.zctl = 0;
    assign intf.rxclkn.a_pc = a_pc;
    assign intf.rxclkn.a_en = a_en;
    assign intf.rxclkn.b_pc = b_pc;
    assign intf.rxclkn.b_en = b_en;
    assign intf.rxclkn.sel_a = sel_a;
    assign intf.rxclkn.vref_sel = 80;

    assign intf.rxval.vdd = vdd;
    assign intf.rxval.vss = vss;
    assign intf.rxval.clk = intf.rxclkp.clkout;
    assign intf.rxval.rstb = ~reset;
    assign intf.rxval.zen = 1;
    assign intf.rxval.zctl = 0;
    assign intf.rxval.a_pc = a_pc;
    assign intf.rxval.a_en = a_en;
    assign intf.rxval.b_pc = b_pc;
    assign intf.rxval.b_en = b_en;
    assign intf.rxval.sel_a = sel_a;
    assign intf.rxval.vref_sel = 80;
    assign intf.rxval.din = intf.txval.D2D_TX;

    assign intf.rxtrk.vdd = vdd;
    assign intf.rxtrk.vss = vss;
    assign intf.rxtrk.clk = intf.rxclkp.clkout;
    assign intf.rxtrk.rstb = ~reset;
    assign intf.rxtrk.zen = 1;
    assign intf.rxtrk.zctl = 0;
    assign intf.rxtrk.a_pc = a_pc;
    assign intf.rxtrk.a_en = a_en;
    assign intf.rxtrk.b_pc = b_pc;
    assign intf.rxtrk.b_en = b_en;
    assign intf.rxtrk.sel_a = sel_a;
    assign intf.rxtrk.vref_sel = 80;
    assign intf.rxtrk.din = intf.txtrk.D2D_TX;

    wire [2**`SERDES_STAGES-1:0] dout[`LANES-1:0];
    generate
    for(i = 0; i < `LANES; i++) begin
        assign dout[i] = intf.rxdata[i].dout;
    end
    endgenerate

    wire [2**`SERDES_STAGES-1:0] expected_a = {2**(`SERDES_STAGES-1){2'b01}};
    wire [2**`SERDES_STAGES-1:0] expected_b = {2**(`SERDES_STAGES-1){2'b10}};
    initial begin
        #200000; // FIXME(Di): Do we need to wait this long for reset?
        reset = 0;
        
        #200000;
        for (integer i = 0; i < `LANES; i++) begin
            $display("Lane %d dout = %x", i, dout[i]);
            if (dout[i] !== expected_a && dout[i] !== expected_b)
                $error("Incorrect RX data output: expected %x or %x, got %x", expected_a, expected_b, dout[i]);
        end

        $finish;
    end
endmodule
