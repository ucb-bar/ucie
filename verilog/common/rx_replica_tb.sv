`timescale 1ps/100fs

// The RX data lane's timing against its clock: what the clock tree replica is
// for. The clock lane and a data lane are handed the same edge on their bumps,
// the clock lane's recovered clock reaches the data lane through an arm of the
// clock distribution network, and the bench times the edge's arrival at the
// data lane's deserializer, on the clock and on the data.
//
// With the replica in the data path the two arrive equally late, but for the
// lane's own delay line, which sits on the data behind the replica: the data
// lands `DCDL_DELAY_OFS` after the clock with no taps on, give or take the
// mismatch between the arm and the replica, and each tap moves the data and
// only the data by `DCDL_DELAY_STEP`. A data path with no replica arrives a
// whole distribution delay early, and a delay line on the clock moves the
// clock.
//
// Edges come once every handover period of the front ends, in the middle of a
// quiet stretch of it, so no edge reaches a front end while it is changing
// halves.
module rx_replica_tb;

    // Handover period of the front ends, and where in it each edge lands.
    localparam real CYCLE = 2800;
    localparam real RISE_AT = 700;
    localparam real FALL_AT = 2100;
    // Handover periods to average a code over, and to let one settle first.
    localparam int CYCLES = 8;
    localparam int SETTLE_CYCLES = 2;
    // Delay line codes to time, in taps.
    localparam int TAPS[5] = '{0, 8, 16, 24, 32};

    // How far the data may land from `DCDL_DELAY_OFS` behind the clock: the
    // arm and the replica are two independent draws of `CLK_DIST_DELAY_SIGMA`,
    // so this is several of their difference's sigma.
    localparam real SKEW_TOL = 3.0;
    // How far a code's effect may stray from its taps' worth.
    localparam real STEP_TOL = 0.5;

    wire vdd = 1'b1;
    wire vss = 1'b0;

    // One edge onto both bumps. Each bump is its own net, so each gets its own
    // connect module, and the two front ends see the same edge.
    reg drive = 1'b0;
    wire clk_bump, data_bump;
    assign clk_bump = drive;
    assign data_bump = drive;

    rxclk_tile_intf rxclk_intf();
    rxclk_tile rxclk(.intf(rxclk_intf), .clkin(clk_bump));

    wire [15:0] rxclk_dist;
    clocking_distribution_model #(
        .propagation_delay_mu(`CLK_DIST_DELAY_MU),
        .propagation_delay_sigma(`CLK_DIST_DELAY_SIGMA)
    ) rxclk_dist_inst(
        .clk_in(rxclk_intf.clkout),
        .clk_out(rxclk_dist)
    );

    rxdata_tile_intf rxdata_intf();
    rxdata_tile rxdata(.intf(rxdata_intf), .din(data_bump));
    assign rxdata_intf.clk = rxclk_dist[0];

    assign rxclk_intf.vdd = vdd;
    assign rxclk_intf.vss = vss;
    assign rxdata_intf.vdd = vdd;
    assign rxdata_intf.vss = vss;

    // AFE HANDOVER, as in `training_tb`: the two halves of each front end take
    // turns. Its events fall in the first 400 ps and at 1400 to 1800 ps of each
    // `CYCLE`, clear of `RISE_AT` and `FALL_AT`.
    reg a_en, a_pc, b_en, b_pc, sel_a;
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

    always_comb begin
        rxclk_intf.a_en = a_en;
        rxclk_intf.a_pc = a_pc;
        rxclk_intf.b_en = b_en;
        rxclk_intf.b_pc = b_pc;
        rxclk_intf.sel_a = sel_a;
        rxdata_intf.a_en = a_en;
        rxdata_intf.a_pc = a_pc;
        rxdata_intf.b_en = b_en;
        rxdata_intf.b_pc = b_pc;
        rxdata_intf.sel_a = sel_a;
    end

    // The edges, on the handover's period. `cycle` fires at the start of each
    // period, when nothing is in flight.
    event cycle;
    initial begin
        #1000;
        forever begin
            -> cycle;
            #(RISE_AT) drive = 1'b1;
            #(FALL_AT - RISE_AT) drive = 1'b0;
            #(CYCLE - FALL_AT);
        end
    end

    // When each level was last put on the bumps, indexed by the level. Every
    // edge is through the lane long before the next one is driven, so an
    // arrival is timed from the last drive of its own level.
    realtime driven[2];
    always @(drive) driven[drive] = $realtime;

    bit timing = 1'b0;
    real clk_sum, data_sum;
    int clk_edges, data_edges;
    always @(rxdata.desclk[0]) begin
        if (timing) begin
            clk_sum += $realtime - driven[rxdata.desclk[0]];
            clk_edges++;
        end
    end
    always @(rxdata.dout_delayed) begin
        if (timing) begin
            data_sum += $realtime - driven[rxdata.dout_delayed];
            data_edges++;
        end
    end

    // Thermometer code with `n` taps of the lane's delay line on.
    function automatic logic [2**`SERDES_STAGES-1:0] taps(input int n);
        taps = {2**`SERDES_STAGES{1'b1}} >> (2**`SERDES_STAGES - n);
    endfunction

    // Average arrival of an edge at the deserializer with `n` taps on, on the
    // clock and on the data.
    task automatic time_code(input int n, output real clk_at, output real data_at);
        rxdata_intf.Dctrl = (n == 0) ? '0 : taps(n);
        repeat (SETTLE_CYCLES) @(cycle);
        clk_sum = 0;
        data_sum = 0;
        clk_edges = 0;
        data_edges = 0;
        timing = 1'b1;
        repeat (CYCLES) @(cycle);
        timing = 1'b0;
        // Two edges a period on each, or something besides the driven edge
        // reached the deserializer.
        if (clk_edges != 2 * CYCLES || data_edges != 2 * CYCLES)
            $error("%0d taps: %0d clock and %0d data edges in %0d periods, expected %0d each",
                   n, clk_edges, data_edges, CYCLES, 2 * CYCLES);
        clk_at = clk_sum / clk_edges;
        data_at = data_sum / data_edges;
    endtask

    real clk_at[$size(TAPS)], data_at[$size(TAPS)];
    real skew, moved;

    initial begin
        rxclk_intf.clk_gate_en = 1'b1;
        // Termination off, so the bumps swing rail to rail, and both lanes
        // sliced at the middle of that.
        rxclk_intf.zen = 1'b0;
        rxclk_intf.zctl = 0;
        rxclk_intf.vref_sel = 2 ** (`RDAC_SEL_BITS - 1);
        rxdata_intf.zen = 1'b0;
        rxdata_intf.zctl = 0;
        rxdata_intf.vref_sel = 2 ** (`RDAC_SEL_BITS - 1);
        rxdata_intf.Dctrl = '0;
        rxdata_intf.rstb = 1'b0;
        #2000;
        rxdata_intf.rstb = 1'b1;

        foreach (TAPS[i]) begin
            time_code(TAPS[i], clk_at[i], data_at[i]);
            $display("%2d taps: clock at %0.2f ps, data at %0.2f ps, data %0.2f ps behind",
                     TAPS[i], clk_at[i], data_at[i], data_at[i] - clk_at[i]);
        end

        skew = data_at[0] - clk_at[0];
        if (skew < `DCDL_DELAY_OFS - SKEW_TOL || skew > `DCDL_DELAY_OFS + SKEW_TOL)
            $error("With no taps the data lands %0.2f ps behind the clock, expected %0.2f +- %0.2f",
                   skew, `DCDL_DELAY_OFS, SKEW_TOL);
        foreach (TAPS[i]) begin
            moved = clk_at[i] - clk_at[0];
            if (moved < -STEP_TOL || moved > STEP_TOL)
                $error("%0d taps moved the clock by %0.2f ps; the delay line is on the data",
                       TAPS[i], moved);
            moved = data_at[i] - data_at[0];
            if (moved < TAPS[i] * `DCDL_DELAY_STEP - STEP_TOL
                    || moved > TAPS[i] * `DCDL_DELAY_STEP + STEP_TOL)
                $error("%0d taps moved the data by %0.2f ps, expected %0.2f",
                       TAPS[i], moved, TAPS[i] * `DCDL_DELAY_STEP);
        end
        $display("RX replica bench complete.");
        $finish;
    end
endmodule
