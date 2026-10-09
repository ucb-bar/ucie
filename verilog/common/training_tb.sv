`timescale 1ps/100fs

// One mainband lane and the forwarded clock that samples it, swept over the two
// codes a receiver is trained with: where in the UI it samples, and what level
// it slices against.
//
// This is the bench the abstraction levels exist for. Both codes are real pins
// -- `PhaseSel` on the clocking tile's global delay line, which delays the
// quadrature clock the forwarded clock lanes run on, and `vref_sel` on the
// receiving tile's reference ladder -- and both are reachable from software
// over MMIO, as `clkPhaseSel` and `rxctl_<lane>_vrefSel`. What each level
// owes this bench is that a wrong code fails and a right one does not, which is
// what makes training something a test can observe. A purely digital model of
// the same tiles passes at every code, which is why `scala/resources/vsrc` is
// not enough to train against.
//
// HOW A CODE IS SCORED. The lane repeats one 32 bit pattern, so a receiver that
// is sampling cleanly comes back with the same word every word period, and that
// word is `PATTERN` rotated by however far its word boundary sits from the
// transmitter's. Two things can go wrong and both are caught:
//
//   - The lane does not resolve the pattern at all. A `vref` outside the swing
//     the driver and the far termination divide down to slices every UI the
//     same way, so the word comes back constant rather than as any rotation of
//     the pattern; a sampling point that never settles comes back different on
//     two consecutive reads.
//   - The sampling point slips into the next UI. Every bit is still clean, but
//     the whole stream has shifted by one, so the rotation changes. Against a
//     fixed expected pattern -- which is what the receiver has in a real link,
//     and what `rxBitErrors` scores against over MMIO -- a slip is an error on
//     roughly half the bits, so it counts as a failure here too.
//
// The run of codes that share one rotation is therefore the eye. In the
// sampling axis little closes it at the eye level -- only a band a few ps wide
// around the boundary between two UI fails -- and jitter and ISI, which narrow
// it in reality, are `models/circuit`'s business. The global line only covers a
// little over a UI, though, so the sweep starts wherever the sampling point
// happens to sit and usually cuts one end of the eye off; the width it reports
// is then a lower bound. In the reference axis the eye is the swing itself,
// and both of its edges are real.
module training_tb;

    import ucie_serdes_order::shuffle;

    localparam int WORD = 2**`SERDES_STAGES;
    // A UI in ps. `MIN_PERIOD` is the period of the high speed clock and a lane
    // is double data rate, so a UI is half of it.
    localparam real UI = `MIN_PERIOD / 2.0;

    // Global delay line codes to sweep, as `PHASE_POINTS` steps of
    // `PHASE_STEP` taps. A tap is `GLOBAL_DL_DELAY_STEP` ps, so this covers a
    // little over a UI -- enough that the sampling point has to leave one UI
    // and settle into the next somewhere along it. The tile's own delay line
    // cannot do this: its whole range is `TX_DCDL_TAPS` taps of
    // `DCDL_DELAY_STEP`, a few ps, because it only trims a lane against the
    // spread of the clock tree.
    localparam int PHASE_POINTS = 32;
    localparam int PHASE_STEP = 2;
    // Reference codes to sweep, as `VREF_POINTS` steps of `VREF_STEP` up the
    // ladder. The ladder spans the whole supply and the receiver only ever sees
    // `RX_V_HIGH` of it, so the swing is in the bottom half and the sweep runs
    // past the top of it to find that edge.
    localparam int VREF_POINTS = 16;
    localparam int VREF_STEP = 16;
    // Reference code to hold the lane at while the sampling point is swept:
    // `RX_V_HIGH`/2, the middle of the swing the nominal driver and termination
    // impedances divide down to. The weakest termination code the sweep uses
    // puts the real swing a little above that, so this sits just below its
    // middle -- which is the point, since a code that only works dead centre
    // would say nothing about the eye around it.
    localparam int VREF_CENTER = (2 ** `RDAC_SEL_BITS) * `RX_VTF / 2;

    // Word periods to let a code settle before the word is read.
    localparam int SETTLE_WORDS = 6;

    // The pattern the data lane repeats, in wire order. It has to be free of
    // rotational symmetry, since the rotation the receiver comes back with is
    // what says where its word boundary sits.
    localparam logic [WORD-1:0] PATTERN = 32'h5aa3_c96e;
    // The forwarded clock: one edge per UI, which is also the fastest thing a
    // lane can send.
    localparam logic [WORD-1:0] CLK_PATTERN = {WORD/2{2'b01}};

    // How wide a pass band has to be for the sweep to count as having found an
    // eye rather than a lucky code.
    localparam int MIN_EYE_PHASE_POINTS = 3;
    localparam int MIN_EYE_VREF_CODES = 4;

    wire vdd = 1'b1;
    wire vss = 1'b0;

    reg ck = 1'b0;
    always #(`MIN_PERIOD / 2.0) ck = ~ck;

    // The global delay line, between the high speed clock and the forwarded
    // clock lane. In the PHY it sits on TXCLKQ, which only the forwarded clock
    // lanes run on, so it moves the clock the far end samples with rather than
    // the data being sampled.
    logic [`GLOBAL_DL_CTRL_BITWIDTH-1:0] phase_sel;
    wire ck_q;
    global_delayline global_dl(
        .dl_ctrl(phase_sel),
        .clk_in(ck),
        .clk_out(ck_q)
    );

    // TILES
    // The forwarded clock lane and one data lane, transmitted off the same high
    // speed clock and received against the clock the far end recovers.
    // Each bump is one net with the transmitting tile on one end and the
    // receiving tile on the other, so the driver, the channel and the far
    // termination are a single analog node -- which is where the eye's height
    // comes from.
    wire clk_bump;
    wire data_bump;

    txdata_tile_intf txclk_intf();
    txdata_tile txclk_tile(.intf(txclk_intf), .D2D_TX(clk_bump));

    txdata_tile_intf txdata_intf();
    txdata_tile txdata_tile(.intf(txdata_intf), .D2D_TX(data_bump));

    rxclk_tile_intf rxclk_intf();
    rxclk_tile rxclk_tile(.intf(rxclk_intf), .clkin(clk_bump));

    rxdata_tile_intf rxdata_intf();
    rxdata_tile rxdata_tile(.intf(rxdata_intf), .din(data_bump));

    assign txclk_intf.vddq = vdd;
    assign txclk_intf.vdd = vdd;
    assign txclk_intf.vss = vss;
    assign txdata_intf.vddq = vdd;
    assign txdata_intf.vdd = vdd;
    assign txdata_intf.vss = vss;
    assign rxclk_intf.vdd = vdd;
    assign rxclk_intf.vss = vss;
    assign rxdata_intf.vdd = vdd;
    assign rxdata_intf.vss = vss;

    assign txclk_intf.CK = ck_q;
    assign txdata_intf.CK = ck;

    // The data lane samples on the clock the clock lane recovered, which is
    // what the clock distribution network hands it in the real PHY.
    assign rxdata_intf.clk = rxclk_intf.clkout;
    // No local RX trim in this bench; the sweep here moves the forwarded clock.
    assign rxdata_intf.Dctrl = '0;

    // AFE HANDOVER
    // The two halves of each receiving front end take turns, which is the
    // sequence `RxAfeCtl` drives in the digital PHY. Both levels need it: at the
    // circuit level a half that is left evaluating leaks its sampling capacitor
    // away, and at the eye level a lane that is never handed a live half holds
    // its last decision forever.
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

    // Thermometer code with `n` of the global delay line's taps enabled, which
    // is how the line reads its control.
    function automatic logic [`GLOBAL_DL_CTRL_BITWIDTH-1:0] phase_taps(input int n);
        phase_taps = {`GLOBAL_DL_CTRL_BITWIDTH{1'b1}} >> (`GLOBAL_DL_CTRL_BITWIDTH - n);
    endfunction

    function automatic logic [WORD-1:0] rotl(
        input logic [WORD-1:0] p,
        input int k
    );
        for (int j = 0; j < WORD; j++) rotl[j] = p[(j + k) % WORD];
    endfunction

    // How far the receiver's word boundary sits from the transmitter's, or -1
    // if what came back is not this pattern at all.
    function automatic int rotation_of(input logic [WORD-1:0] w);
        rotation_of = -1;
        for (int k = 0; k < WORD; k++)
            if (rotation_of < 0 && w === rotl(PATTERN, k)) rotation_of = k;
    endfunction

    // Programs a code pair, lets it settle, and reads the lane twice a word
    // apart. `rot` is the word boundary the receiver came back with, or -1 if
    // the two reads disagreed or neither is this pattern.
    task automatic measure(input int phase, input int vref, output int rot);
        logic [WORD-1:0] first;
        logic [WORD-1:0] second;
        phase_sel = phase_taps(phase);
        rxdata_intf.vref_sel = vref[`RDAC_SEL_BITS-1:0];
        repeat (SETTLE_WORDS) @(posedge rxdata_intf.divclk);
        @(negedge rxdata_intf.divclk);
        first = shuffle(rxdata_intf.dout);
        @(negedge rxdata_intf.divclk);
        second = shuffle(rxdata_intf.dout);
        rot = (first === second) ? rotation_of(first) : -1;
    endtask

    // Longest run of equal, non-failing scores, as a start index and a length.
    task automatic longest_run(
        input int score[],
        input int n,
        output int start,
        output int len
    );
        int run_start;
        int run_len;
        start = 0;
        len = 0;
        run_start = 0;
        run_len = 0;
        for (int i = 0; i < n; i++) begin
            if (score[i] >= 0 && run_len > 0 && score[i] == score[run_start]) begin
                run_len++;
            end else if (score[i] >= 0) begin
                run_start = i;
                run_len = 1;
            end else begin
                run_len = 0;
            end
            if (run_len > len) begin
                len = run_len;
                start = run_start;
            end
        end
    endtask

    int phase_score[PHASE_POINTS];
    int vref_score[VREF_POINTS];
    int phase_start, phase_len, vref_start, vref_len;
    int trained_phase, trained_vref;
    int rot;
    string row;

    initial begin
        rxdata_intf.rstb = 1'b0;
        txclk_intf.RST_async = 1'b1;
        txdata_intf.RST_async = 1'b1;

        // Every driver segment on, equalizer branch off. Every enable is active
        // high.
        txclk_intf.ENP = {`TX_DRIVER_SEGMENTS{1'b1}};
        txclk_intf.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
        txclk_intf.ENP_EQ = 0;
        txclk_intf.ENN_EQ = 0;
        txdata_intf.ENP = {`TX_DRIVER_SEGMENTS{1'b1}};
        txdata_intf.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
        txdata_intf.ENP_EQ = 0;
        txdata_intf.ENN_EQ = 0;

        // Neither lane's own delay line is trimmed: with one data lane there is
        // no spread between lanes for it to take out.
        txclk_intf.Dctrl = '0;
        txdata_intf.Dctrl = '0;
        phase_sel = phase_taps(0);

        txclk_intf.DataIN = shuffle(CLK_PATTERN);
        txdata_intf.DataIN = shuffle(PATTERN);

        // The recovered clock's gate open, as `rxClkGateEn` resets to. Shut, the
        // clock lane hands the data lane no clock at all.
        rxclk_intf.clk_gate_en = 1'b1;

        // Termination on at its weakest code, and the clock lane sliced at the
        // middle of its swing. Only the data lane's reference is swept.
        rxclk_intf.zen = 1'b1;
        rxclk_intf.zctl = 0;
        rxclk_intf.vref_sel = VREF_CENTER[`RDAC_SEL_BITS-1:0];
        rxdata_intf.zen = 1'b1;
        rxdata_intf.zctl = 0;
        rxdata_intf.vref_sel = VREF_CENTER[`RDAC_SEL_BITS-1:0];

        #50000;
        txclk_intf.RST_async = 1'b0;
        txdata_intf.RST_async = 1'b0;
        rxdata_intf.rstb = 1'b1;
        #20000;

        // SWEEP 1: where in the UI the lane is sampled. Only ever moved later
        // until the sweep is done, so no edge in flight on the line is
        // overtaken by a later one and the clock lane never sees a runt.
        $display("");
        $display("Sampling point sweep at vref_sel = %0d (%0.3f ps per tap, %0.1f ps per UI)",
                 VREF_CENTER, `GLOBAL_DL_DELAY_STEP, UI);
        for (int p = 0; p < PHASE_POINTS; p++) begin
            measure(p * PHASE_STEP, VREF_CENTER, rot);
            phase_score[p] = rot;
            $display("  phase %2d (%5.1f ps): %s",
                     p * PHASE_STEP, p * PHASE_STEP * `GLOBAL_DL_DELAY_STEP,
                     rot < 0 ? "no clean word" : $sformatf("word boundary %0d", rot));
        end
        longest_run(phase_score, PHASE_POINTS, phase_start, phase_len);
        trained_phase = (phase_start + phase_len / 2) * PHASE_STEP;
        row = "";
        for (int p = 0; p < PHASE_POINTS; p++) begin
            row = {row, (p >= phase_start && p < phase_start + phase_len)
                        ? "#" : "."};
        end
        $display("  eye: %s  (%s%0d points, %0.1f ps, %0.2f UI, centered on phase %0d)",
                 row,
                 (phase_start == 0 || phase_start + phase_len == PHASE_POINTS)
                     ? "cut off by the sweep, at least " : "",
                 phase_len, phase_len * PHASE_STEP * `GLOBAL_DL_DELAY_STEP,
                 phase_len * PHASE_STEP * `GLOBAL_DL_DELAY_STEP / UI, trained_phase);

        // SWEEP 2: what level the lane is sliced against, at the sampling point
        // the first sweep found.
        $display("");
        $display("Reference sweep at phase %0d", trained_phase);
        for (int i = 0; i < VREF_POINTS; i++) begin
            measure(trained_phase, i * VREF_STEP, rot);
            vref_score[i] = rot;
            $display("  vref_sel %3d (%0.3f V): %s",
                     i * VREF_STEP,
                     `VDD * (i * VREF_STEP) / (2.0 ** `RDAC_SEL_BITS),
                     rot < 0 ? "no clean word" : $sformatf("word boundary %0d", rot));
        end
        longest_run(vref_score, VREF_POINTS, vref_start, vref_len);
        trained_vref = (vref_start + vref_len / 2) * VREF_STEP;
        row = "";
        for (int i = 0; i < VREF_POINTS; i++) begin
            row = {row, (i >= vref_start && i < vref_start + vref_len)
                        ? "#" : "."};
        end
        $display("  eye: %s  (%0d codes, %0.3f V tall)",
                 row, vref_len,
                 vref_len * VREF_STEP * `VDD / (2.0 ** `RDAC_SEL_BITS));

        // The link at the codes the two sweeps picked.
        $display("");
        $display("Trained: phase %0d, vref_sel %0d", trained_phase, trained_vref);
        measure(trained_phase, trained_vref, rot);
        if (rot < 0)
            $error("Error: the lane does not receive cleanly at the trained codes");

        // An eye has to be bounded on both axes and wide enough to sit in. A
        // sweep where every code scores the same has measured no eye at all:
        // either the delay line is not moving the sampling point, or the slicer
        // is not comparing against its reference. A model that did either would
        // let training pass without training anything.
        if (phase_len < MIN_EYE_PHASE_POINTS)
            $error("Error: sampling point eye is %0d points, expected at least %0d",
                   phase_len, MIN_EYE_PHASE_POINTS);
        if (phase_len == PHASE_POINTS)
            $error("Error: the sampling point never slips a UI over %0.1f ps of delay, so the delay line is not moving it",
                   (PHASE_POINTS - 1) * PHASE_STEP * `GLOBAL_DL_DELAY_STEP);
        if (vref_len < MIN_EYE_VREF_CODES)
            $error("Error: reference eye is %0d codes, expected at least %0d",
                   vref_len, MIN_EYE_VREF_CODES);
        if (vref_len == VREF_POINTS)
            $error("Error: every reference code receives, so the slicer is not comparing against it");

        $display("Training complete.");
        $finish;
    end

endmodule
