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
// bbpll pll(
//     .reset(intf.pll_reset),
//     .clk_out(intf.pll_clk_out),
//     .Dctrl_value(intf.pll_Dctrl_value)
// );

txdriver_tile sb_txdata_drv(.intf(intf.sb_txdata));
txdriver_tile sb_txclk_drv(.intf(intf.sb_txclk));
genvar i;
generate
    for(i = 0; i < `LANES; i++) begin
        txdata_tile txdata_tile(.intf(intf.txdata[i]));
    end
endgenerate
txdata_tile txclkp_tile(.intf(intf.txclkp));
txdata_tile txclkn_tile(.intf(intf.txclkn));
txdata_tile txval_tile(.intf(intf.txval));
txdata_tile txtrk_tile(.intf(intf.txtrk));

generate
    for(i = 0; i < `LANES; i++) begin
        rxdata_tile rxdata_tile(.intf(intf.rxdata[i]));
    end
endgenerate
rxclk_tile rxclkp_tile(.intf(intf.rxclkp));
rxclk_tile rxclkn_tile(.intf(intf.rxclkn));
rxdata_tile rxval_tile(.intf(intf.rxval));
rxdata_tile rxtrk_tile(.intf(intf.rxtrk));

endmodule

module tb_phy;
    wire vdd = 1, vss = 0;
    reg reset = 1;

    phy_intf intf();

    phy phy(
        .intf(intf)
    );

    assign intf.pll_reset = reset;
    assign intf.pll_Dctrl_value = 1;

    assign intf.sb_txdata.vdd = vdd;
    assign intf.sb_txdata.vss = vss;
    assign intf.sb_txclk.vdd = vdd;
    assign intf.sb_txclk.vss = vss;
    genvar i;
    generate
        for (i = 0; i < `LANES; i++) begin
            assign intf.txdata[i].vdd = vdd;
            assign intf.txdata[i].vss = vss;
        end
    endgenerate
    assign intf.txclkp.vdd = vdd;
    assign intf.txclkp.vss = vss;
    assign intf.txclkn.vdd = vdd;
    assign intf.txclkn.vss = vss;
    assign intf.txval.vdd = vdd;
    assign intf.txval.vss = vss;
    assign intf.txtrk.vdd = vdd;
    assign intf.txtrk.vss = vss;

    generate
        for (i = 0; i < `LANES; i++) begin
            assign intf.rxdata[i].vdd = vdd;
            assign intf.rxdata[i].vss = vss;
        end
    endgenerate
    assign intf.rxclkp.vdd = vdd;
    assign intf.rxclkp.vss = vss;
    assign intf.rxclkn.vdd = vdd;
    assign intf.rxclkn.vss = vss;
    assign intf.rxval.vdd = vdd;
    assign intf.rxval.vss = vss;
    assign intf.rxtrk.vdd = vdd;
    assign intf.rxtrk.vss = vss;

    initial begin
        #200000
        reset = 0;
    end
endmodule
