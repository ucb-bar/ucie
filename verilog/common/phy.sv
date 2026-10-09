interface phy_intf;
    sb_driver_tile_intf sb_txdata(), sb_txclk();
    txdata_tile_intf txdata[`LANES]();
    txdata_tile_intf txclkp(), txclkn(), txval(), txtrk();

    sbrx_tile_intf sb_rxdata(), sb_rxclk();
    rxdata_tile_intf rxdata[`LANES]();
    rxdata_tile_intf rxval(), rxtrk();
    rxclk_tile_intf rxclkp(), rxclkn();
    // The global delay line on the forwarded clock, thermometer coded: what
    // `clkPhaseSel` drives in the PHY, and what training moves to put the
    // clock the far end samples with in the middle of the data eye.
    logic [`GLOBAL_DL_CTRL_BITWIDTH-1:0] clk_phase_sel;
    logic pll_reset;
    wire pll_clk_out;
    // The PLL model's lock status. Only the PLL drives it, so it is left
    // undriven while the model is commented out below.
    wire pll_Dctrl_value;
endinterface

// The bumps are pins rather than members of `phy_intf`, because a
// SystemVerilog interface cannot hold an electrical net and a bump routed
// through one arrives at the far end through connect modules rather than as a
// shared analog node. Everything a receiver is trained against -- the level the
// driver and the far termination divide down to, and the edge between them --
// lives on that node, so it has to survive the trip. See `verilog/README.md`.
module phy(
    phy_intf intf,
    output [`LANES-1:0] txdata_bump,
    output txclkp_bump, txclkn_bump, txval_bump, txtrk_bump,
    output sb_txdata_bump, sb_txclk_bump,
    input [`LANES-1:0] rxdata_bump,
    input rxclkp_bump, rxclkn_bump, rxval_bump, rxtrk_bump
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

sb_driver_tile sb_txdata_drv(.intf(intf.sb_txdata), .out(sb_txdata_bump));
sb_driver_tile sb_txclk_drv(.intf(intf.sb_txclk), .out(sb_txclk_bump));
wire [`LANES-1:0] txclk_sed;

clocking_distribution_model #(
    .propagation_delay_mu(`CLK_DIST_DELAY_MU),
    .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
)   clk_dist_inst_tx(
    .clk_in(intf.pll_clk_out),
    .clk_out(txclk_sed)
);

// The global delay line, on the forwarded clock only, as it sits on TXCLKQ in
// the PHY. It is the training knob for where in the UI the far end samples: a
// little over a UI in `GLOBAL_DL_CTRL_BITWIDTH` taps, where a lane's own delay
// line only trims it by a few ps.
wire clk_q;
global_delayline global_dl(
    .dl_ctrl(intf.clk_phase_sel),
    .clk_in(intf.pll_clk_out),
    .clk_out(clk_q)
);

// A fixed delay after it, standing in for the forwarded clock's arm of the
// clock tree. Its offset was meant to cancel the distribution on both sides and
// centre the clock in the eye, which it does not quite do (see "Where the
// sampling point lands" in `verilog/README.md`); centring is the global line's
// job, so this line's gain and control stay at zero.
wire deskewed_clk_tx;
dcdl #(
    .delay_gain(0),
    .delay_offset(2 * `CLK_DIST_DELAY_MU + `CLK_PERIOD/4)
) dcdl_inst(
    .clk_in(clk_q),
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
        txdata_tile txdata_tile(.intf(intf.txdata[i]), .D2D_TX(txdata_bump[i]));
    end
endgenerate
txdata_tile txclkp_tile(.intf(intf.txclkp), .D2D_TX(txclkp_bump));
txdata_tile txclkn_tile(.intf(intf.txclkn), .D2D_TX(txclkn_bump));
txdata_tile txval_tile(.intf(intf.txval), .D2D_TX(txval_bump));
txdata_tile txtrk_tile(.intf(intf.txtrk), .D2D_TX(txtrk_bump));

// The recovered clock, past the clock lane's gate, fans out to the data lanes.
wire [`LANES-1:0] rxclk_dist_sed;
wire rxclk_gated = intf.rxclkp.clkout;
clocking_distribution_model #(
    .propagation_delay_mu(`CLK_DIST_DELAY_MU),
    .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
)   clk_dist_inst_rx(
    .clk_in(rxclk_gated),
    .clk_out(rxclk_dist_sed)
);

generate
    for(i = 0; i < `LANES; i++) begin
        // perform clock distribution on RX too
        assign intf.rxdata[i].clk = rxclk_dist_sed[i];
        rxdata_tile rxdata_tile(.intf(intf.rxdata[i]), .din(rxdata_bump[i]));
    end
endgenerate
rxclk_tile rxclkp_tile(.intf(intf.rxclkp), .clkin(rxclkp_bump));
rxclk_tile rxclkn_tile(.intf(intf.rxclkn), .clkin(rxclkn_bump));
rxdata_tile rxval_tile(.intf(intf.rxval), .din(rxval_bump));
rxdata_tile rxtrk_tile(.intf(intf.rxtrk), .din(rxtrk_bump));

endmodule

module phy_tb;
    // Every lane in the digital PHY has a bit shuffler between the digital word
    // and its tile -- in front of the serializer on TX, behind the deserializer
    // on RX -- and both reset to the permutation that cancels the tile's serdes
    // tree. See `Shuffler` and `Phy.treeBitOrder` in `phy/Phy.scala`.
    //
    // This bench wires the tiles up directly, with no digital PHY in between,
    // so it stands in for those shufflers itself with `shuffle` out of
    // `ucie_serdes_order`. Everything below is written in the order it should
    // appear ON THE WIRE and passed through `shuffle`, and `dout` is brought
    // back through `shuffle` before it is checked.
    //
    // It matters most for the forwarded clock. A TX tile sends `DataIN[bitrev(t)]`
    // in UI `t`, and `bitrev` swaps the fastest varying index bit for the
    // slowest, so 0101..01 fed in raw leaves the tile as a single
    // `2**SERDES_STAGES` UI square wave rather than one edge per UI -- and the
    // receiver recovers a clock a whole word period long.

    import ucie_serdes_order::shuffle;

    // The patterns, in wire order. One edge per UI is the forwarded half-rate
    // clock and the fastest thing a data lane can send; the valid lane sends
    // four UI low then four UI high.
    localparam logic [2**`SERDES_STAGES-1:0] ALT_HIGH_FIRST =
        {2**(`SERDES_STAGES-1){2'b01}};
    localparam logic [2**`SERDES_STAGES-1:0] ALT_LOW_FIRST =
        {2**(`SERDES_STAGES-1){2'b10}};
    localparam logic [2**`SERDES_STAGES-1:0] VALID_PATTERN =
        {2**(`SERDES_STAGES-3){8'hf0}};

    wire vdd = 1, vss = 0;
    reg reset = 1;

    // The global delay line's code, as a thermometer code. Nothing in this
    // loopback centres the forwarded clock in the data eye: `phy`'s fixed
    // delay offsets it by two clock distribution delays and a quarter period,
    // and two distribution delays are not a whole number of UI, so where the
    // sampling edge lands is left over from delays that were never meant to
    // add up. It lands inside the eye for some models and on the edge for
    // others, so the bench sweeps this code over a UI and settles in the middle
    // of the longest run of codes lane 0 receives at. One code serves every
    // lane here, since a loopback puts them all through the same delays;
    // `verilog/common/training_tb.sv` is where the eye either side of it gets
    // measured.
    reg [`GLOBAL_DL_CTRL_BITWIDTH-1:0] phase_sel = 0;
    // Codes swept, as `PHASE_POINTS` steps of `PHASE_STEP` taps. A tap is
    // `GLOBAL_DL_DELAY_STEP` ps, so this covers a UI -- `MIN_PERIOD`/2 = 62.5 ps
    // at 16 GT/s -- and the edge of the eye has to be somewhere along it.
    localparam int PHASE_POINTS = 16;
    localparam int PHASE_STEP = 4;
    // Time for a code to settle and the receiver to refill, in ps: six word
    // periods, as in `training_tb`.
    localparam int PHASE_SETTLE = 6 * 2**`SERDES_STAGES * `MIN_PERIOD / 2;
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

    // Each bump is one net with a transmitter on one end and a receiver on the
    // other, rather than two nets joined by an assignment, so that the level
    // the receiver slices is the one the driver and the far termination
    // actually divide down to.
    wire [`LANES-1:0] data_bump;
    wire clkp_bump, clkn_bump, val_bump, trk_bump;
    wire sb_data_bump, sb_clk_bump;

    phy phy(
        .intf(intf),
        .txdata_bump(data_bump),
        .txclkp_bump(clkp_bump),
        .txclkn_bump(clkn_bump),
        .txval_bump(val_bump),
        .txtrk_bump(trk_bump),
        .sb_txdata_bump(sb_data_bump),
        .sb_txclk_bump(sb_clk_bump),
        .rxdata_bump(data_bump),
        .rxclkp_bump(clkp_bump),
        .rxclkn_bump(clkn_bump),
        .rxval_bump(val_bump),
        .rxtrk_bump(trk_bump)
    );

    assign intf.pll_reset = reset;
    assign intf.pll_clk_out = pll_clkp_out;
    assign intf.clk_phase_sel = phase_sel;
    assign intf.sb_txdata.vdd = vdd;
    assign intf.sb_txdata.vss = vss;
    // The sideband drivers do their own 2:1; this testbench does not exercise
    // the sideband, so the half rate bits are tied off.
    assign intf.sb_txdata.clk = pll_clkp_out;
    assign intf.sb_txdata.d0 = 1'b0;
    assign intf.sb_txdata.d1 = 1'b0;
    assign intf.sb_txdata.pu_ctl = 0;
    assign intf.sb_txdata.pd_ctlb = {`PAD_DRIVER_SEGMENTS{1'b1}};
    assign intf.sb_txdata.en = 1;
    assign intf.sb_txdata.enb = 0;

    assign intf.sb_txclk.vdd = vdd;
    assign intf.sb_txclk.vss = vss;
    // The sideband drivers do their own 2:1; this testbench does not exercise
    // the sideband, so the half rate bits are tied off.
    assign intf.sb_txclk.clk = pll_clkp_out;
    assign intf.sb_txclk.d0 = 1'b0;
    assign intf.sb_txclk.d1 = 1'b0;
    assign intf.sb_txclk.pu_ctl = 0;
    assign intf.sb_txclk.pd_ctlb = {`PAD_DRIVER_SEGMENTS{1'b1}};
    assign intf.sb_txclk.en = 1;
    assign intf.sb_txclk.enb = 0;

    genvar i;
    generate
        for (i = 0; i < `LANES; i++) begin
            assign intf.txdata[i].vddq = vdd;
            assign intf.txdata[i].vdd = vdd;
            assign intf.txdata[i].vss = vss;
            assign intf.txdata[i].DataIN = shuffle(ALT_HIGH_FIRST);
            assign intf.txdata[i].RST_async = reset;
            // Every main driver segment on and the equalizer branch off (every
            // enable is active high), no added delay on the tile clock.
            assign intf.txdata[i].ENP = {`TX_DRIVER_SEGMENTS{1'b1}};
            assign intf.txdata[i].ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
            assign intf.txdata[i].ENP_EQ = 0;
            assign intf.txdata[i].ENN_EQ = 0;
            assign intf.txdata[i].Dctrl = 0;

            assign intf.rxdata[i].vdd = vdd;
            assign intf.rxdata[i].vss = vss;
            assign intf.rxdata[i].rstb = ~reset;
            assign intf.rxdata[i].Dctrl = '0;
            assign intf.rxdata[i].zen = 1;
            assign intf.rxdata[i].zctl = 0;
            assign intf.rxdata[i].a_pc = a_pc;
            assign intf.rxdata[i].a_en = a_en;
            assign intf.rxdata[i].b_pc = b_pc;
            assign intf.rxdata[i].b_en = b_en;
            assign intf.rxdata[i].sel_a = sel_a;
            assign intf.rxdata[i].vref_sel = 80;
        end
    endgenerate

    assign intf.txclkp.vddq = vdd;
    assign intf.txclkp.vdd = vdd;
    assign intf.txclkp.vss = vss;
    assign intf.txclkp.DataIN = shuffle(ALT_HIGH_FIRST);
    assign intf.txclkp.RST_async = reset;
    // Every main driver segment on and the equalizer branch off (every enable
    // is active high), no added delay on the tile clock.
    assign intf.txclkp.ENP = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txclkp.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txclkp.ENP_EQ = 0;
    assign intf.txclkp.ENN_EQ = 0;
    assign intf.txclkp.Dctrl = 0;

    assign intf.txclkn.vddq = vdd;
    assign intf.txclkn.vdd = vdd;
    assign intf.txclkn.vss = vss;
    assign intf.txclkn.DataIN = shuffle(ALT_LOW_FIRST);
    assign intf.txclkn.RST_async = reset;
    // Every main driver segment on and the equalizer branch off (every enable
    // is active high), no added delay on the tile clock.
    assign intf.txclkn.ENP = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txclkn.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txclkn.ENP_EQ = 0;
    assign intf.txclkn.ENN_EQ = 0;
    assign intf.txclkn.Dctrl = 0;

    assign intf.txval.vddq = vdd;
    assign intf.txval.vdd = vdd;
    assign intf.txval.vss = vss;
    assign intf.txval.DataIN = shuffle(VALID_PATTERN);
    assign intf.txval.RST_async = reset;
    // Every main driver segment on and the equalizer branch off (every enable
    // is active high), no added delay on the tile clock.
    assign intf.txval.ENP = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txval.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txval.ENP_EQ = 0;
    assign intf.txval.ENN_EQ = 0;
    assign intf.txval.Dctrl = 0;

    assign intf.txtrk.vddq = vdd;
    assign intf.txtrk.vdd = vdd;
    assign intf.txtrk.vss = vss;
    assign intf.txtrk.DataIN = shuffle(ALT_HIGH_FIRST);
    assign intf.txtrk.RST_async = reset;
    // Every main driver segment on and the equalizer branch off (every enable
    // is active high), no added delay on the tile clock.
    assign intf.txtrk.ENP = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txtrk.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
    assign intf.txtrk.ENP_EQ = 0;
    assign intf.txtrk.ENN_EQ = 0;
    assign intf.txtrk.Dctrl = 0;

    // Both recovered clocks' gates open, as `rxClkGateEn` resets to. Shut, the
    // clock lane hands the distribution, and so every data lane, no clock.
    assign intf.rxclkp.clk_gate_en = 1;
    assign intf.rxclkn.clk_gate_en = 1;

    assign intf.rxclkp.vdd = vdd;
    assign intf.rxclkp.vss = vss;
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

    // Raw tile output, and the same word past the lane's shuffler -- which is
    // what the digital PHY would hand on, in wire order, bit 0 first received.
    wire [2**`SERDES_STAGES-1:0] dout[`LANES-1:0];
    wire [2**`SERDES_STAGES-1:0] dout_shuffled[`LANES-1:0];
    generate
    for(i = 0; i < `LANES; i++) begin
        assign dout[i] = intf.rxdata[i].dout;
        assign dout_shuffled[i] = shuffle(dout[i]);
    end
    endgenerate

    // Nothing here aligns the two ends -- each divider chain comes out of reset
    // where it comes out -- so the receiver may be a UI off and see the
    // alternating stream either way up.
    wire [2**`SERDES_STAGES-1:0] expected_a = ALT_HIGH_FIRST;
    wire [2**`SERDES_STAGES-1:0] expected_b = ALT_LOW_FIRST;
    // Whether a lane came back with the alternating stream, either way up.
    function automatic bit lane_ok(input logic [2**`SERDES_STAGES-1:0] word);
        lane_ok = (word === expected_a) || (word === expected_b);
    endfunction

    // Thermometer code with `n` of the global delay line's taps enabled.
    function automatic logic [`GLOBAL_DL_CTRL_BITWIDTH-1:0] phase_taps(input int n);
        phase_taps = {`GLOBAL_DL_CTRL_BITWIDTH{1'b1}} >> (`GLOBAL_DL_CTRL_BITWIDTH - n);
    endfunction

    bit phase_ok[PHASE_POINTS];
    int run_start, run_len, best_start, best_len, phase;
    string row;

    initial begin
        // Reset only has to reach the dividers, which take it asynchronously.
        // Nothing analog waits on it: the reference ladders and terminations
        // are resistive and sit at their levels from the first time step, at
        // either level. After it, a few word periods for the transmitters to
        // start and the receivers to fill; every code the sweep below tries
        // gets its own settle besides.
        #20000;
        reset = 0;

        #20000;

        // Sweep the global line and settle in the middle of the longest run of
        // codes lane 0 receives at. Every lane shares the code, so one lane is
        // enough to find it and the check below is what says the rest agree.
        // The sweep only ever moves the line later, so no edge in flight on it
        // is overtaken by a later one; the jump back to the chosen code can
        // leave a runt, which the settle after it rides out.
        best_start = 0;
        best_len = 0;
        run_len = 0;
        for (int p = 0; p < PHASE_POINTS; p++) begin
            phase_sel = phase_taps(p * PHASE_STEP);
            #PHASE_SETTLE;
            phase_ok[p] = lane_ok(dout_shuffled[0]);
            if (!phase_ok[p]) begin
                run_len = 0;
            end else begin
                if (run_len == 0) run_start = p;
                run_len++;
                if (run_len > best_len) begin
                    best_start = run_start;
                    best_len = run_len;
                end
            end
        end
        row = "";
        for (int p = 0; p < PHASE_POINTS; p++) row = {row, phase_ok[p] ? "#" : "."};
        phase = (best_start + best_len / 2) * PHASE_STEP;
        phase_sel = phase_taps(phase);
        #PHASE_SETTLE;
        $display("Forwarded clock phase sweep: %s (%0.2f ps per point)",
                 row, PHASE_STEP * `GLOBAL_DL_DELAY_STEP);
        $display("Forwarded clock phase settled at %0d taps (%0.1f ps)",
                 phase, phase * `GLOBAL_DL_DELAY_STEP);
        if (best_len == 0)
            $error("Incorrect RX data output: lane 0 receives at no forwarded clock phase");

        for (integer i = 0; i < `LANES; i++) begin
            $display("Lane %d dout = %x (tile), %x (shuffled)",
                     i, dout[i], dout_shuffled[i]);
            if (!lane_ok(dout_shuffled[i]))
                $error("Incorrect RX data output: expected %x or %x, got %x",
                       expected_a, expected_b, dout_shuffled[i]);
        end

        $finish;
    end
endmodule
