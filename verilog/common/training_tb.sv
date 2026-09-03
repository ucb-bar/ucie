`timescale 1ps/100fs

// One mainband lane and the forwarded clock that samples it, swept over the two
// codes a receiver is trained with: where in the UI it samples, and what level
// it slices against.
//
// This is the bench the abstraction levels exist for. Both codes are real pins
// on the tiles -- `Dctrl` on the transmitting tile's delay line, `vref_sel` on
// the receiving tile's reference ladder -- and both are reachable from software
// over MMIO, as `txctl_<lane>_tile` and `rxctl_<lane>_vrefSel`. What each level
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
// sampling axis it comes out about one UI wide, because nothing at the eye
// level closes it: a sample taken part way up an edge still resolves to the old
// bit or the new one, so there is no band of codes that fails outright. Jitter
// and ISI are what narrow that in reality, and both are `models/circuit`'s
// business. In the reference axis the eye is the swing itself, and both of its
// edges are real.
module training_tb;

    import ucie_serdes_order::shuffle;

    localparam int WORD = 2**`SERDES_STAGES;
    // A UI in ps. `MIN_PERIOD` is the period of the high speed clock and a lane
    // is double data rate, so a UI is half of it.
    localparam real UI = `MIN_PERIOD / 2.0;

    // Delay line taps to sweep. Each is `DCDL_DELAY_STEP` ps, so this covers
    // just under two UI: enough to watch the sampling point leave one UI and
    // settle into the next.
    localparam int DELAY_TAPS = 13;
    // Reference codes to sweep, as `VREF_POINTS` steps of `VREF_STEP` up the
    // ladder. The ladder spans the whole supply and the receiver only ever sees
    // `RX_V_HIGH` of it, so the swing is in the bottom half and the sweep runs
    // past the top of it to find that edge.
    localparam int VREF_POINTS = 16;
    localparam int VREF_STEP = 16;
    // Reference code to hold the lane at while the delay line is swept:
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
    localparam int MIN_EYE_TAPS = 3;
    localparam int MIN_EYE_VREF_CODES = 4;

    wire vdd = 1'b1;
    wire vss = 1'b0;

    reg ck = 1'b0;
    always #(`MIN_PERIOD / 2.0) ck = ~ck;

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

    assign txclk_intf.CK = ck;
    assign txdata_intf.CK = ck;

    // The data lane samples on the clock the clock lane recovered, which is
    // what the clock distribution network hands it in the real PHY.
    assign rxdata_intf.clk = rxclk_intf.clkout;

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

    // Thermometer code with `n` of the `TX_DCDL_TAPS` taps enabled, which is
    // how the tile reads its delay line control.
    function automatic logic [`TX_DCDL_TAPS-1:0] taps(input int n);
        taps = ((1 << n) - 1);
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
    task automatic measure(input int tap, input int vref, output int rot);
        logic [WORD-1:0] first;
        logic [WORD-1:0] second;
        txdata_intf.Dctrl = taps(tap);
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

    int delay_score[DELAY_TAPS];
    int vref_score[VREF_POINTS];
    int delay_start, delay_len, vref_start, vref_len;
    int trained_tap, trained_vref;
    int rot;
    string row;

    initial begin
        rxdata_intf.rstb = 1'b0;
        txclk_intf.RST_async = 1'b1;
        txdata_intf.RST_async = 1'b1;

        // Every driver segment on, equalizer branch off. `ENP`/`ENP_EQ` are
        // active low.
        txclk_intf.ENP = 0;
        txclk_intf.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
        txclk_intf.ENP_EQ = {`TX_DRIVER_EQ_SEGMENTS{1'b1}};
        txclk_intf.ENN_EQ = 0;
        txdata_intf.ENP = 0;
        txdata_intf.ENN = {`TX_DRIVER_SEGMENTS{1'b1}};
        txdata_intf.ENP_EQ = {`TX_DRIVER_EQ_SEGMENTS{1'b1}};
        txdata_intf.ENN_EQ = 0;

        // The clock lane is the reference the data lane is swept against, so it
        // keeps the shortest delay the line can be set to.
        txclk_intf.Dctrl = taps(0);
        txdata_intf.Dctrl = taps(0);

        txclk_intf.DataIN = shuffle(CLK_PATTERN);
        txdata_intf.DataIN = shuffle(PATTERN);

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

        // SWEEP 1: where in the UI the lane is sampled.
        $display("");
        $display("Sampling point sweep at vref_sel = %0d (%0d ps per tap, %0.1f ps per UI)",
                 VREF_CENTER, `DCDL_DELAY_STEP, UI);
        for (int t = 0; t < DELAY_TAPS; t++) begin
            measure(t, VREF_CENTER, rot);
            delay_score[t] = rot;
            $display("  tap %2d (%3d ps): %s",
                     t, t * `DCDL_DELAY_STEP,
                     rot < 0 ? "no clean word" : $sformatf("word boundary %0d", rot));
        end
        longest_run(delay_score, DELAY_TAPS, delay_start, delay_len);
        trained_tap = delay_start + delay_len / 2;
        row = "";
        for (int t = 0; t < DELAY_TAPS; t++) begin
            row = {row, (t >= delay_start && t < delay_start + delay_len)
                        ? "#" : "."};
        end
        $display("  eye: %s  (%0d taps, %0d ps, %0.2f UI, centered on tap %0d)",
                 row, delay_len, delay_len * `DCDL_DELAY_STEP,
                 delay_len * `DCDL_DELAY_STEP / UI, trained_tap);

        // SWEEP 2: what level the lane is sliced against, at the sampling point
        // the first sweep found.
        $display("");
        $display("Reference sweep at tap %0d", trained_tap);
        for (int i = 0; i < VREF_POINTS; i++) begin
            measure(trained_tap, i * VREF_STEP, rot);
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
        $display("Trained: tap %0d, vref_sel %0d", trained_tap, trained_vref);
        measure(trained_tap, trained_vref, rot);
        if (rot < 0)
            $error("Error: the lane does not receive cleanly at the trained codes");

        // An eye has to be bounded on both axes and wide enough to sit in. A
        // sweep where every code scores the same has measured no eye at all:
        // either the delay line is not moving the sampling point, or the slicer
        // is not comparing against its reference. A model that did either would
        // let training pass without training anything.
        if (delay_len < MIN_EYE_TAPS)
            $error("Error: sampling point eye is %0d taps, expected at least %0d",
                   delay_len, MIN_EYE_TAPS);
        if (delay_len == DELAY_TAPS)
            $error("Error: the sampling point never slips a UI over %0d ps of delay, so the delay line is not moving it",
                   DELAY_TAPS * `DCDL_DELAY_STEP);
        if (vref_len < MIN_EYE_VREF_CODES)
            $error("Error: reference eye is %0d codes, expected at least %0d",
                   vref_len, MIN_EYE_VREF_CODES);
        if (vref_len == VREF_POINTS)
            $error("Error: every reference code receives, so the slicer is not comparing against it");

        $display("Training complete.");
        $finish;
    end

endmodule
