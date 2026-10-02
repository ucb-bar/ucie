`timescale 1ps/100fs

// One mainband lane and the forwarded clock that samples it, swept over the
// codes a receiver is trained with: where in the UI it samples, what level it
// slices against, and what the per-lane trim can pull.
//
// This is the bench the abstraction levels exist for. Every code is a real pin
// -- the global delay line on the forwarded clock, `Dctrl` on a transmitting
// tile, `vref_sel` on the receiving tile's reference ladder -- and all of them
// are reachable from software over MMIO, as `clkPhaseSel`, `txctl_<lane>_tile`
// and `rxctl_<lane>_vrefSel`. What each level owes this bench is that a wrong
// code fails and a right one does not, which is what makes training something a
// test can observe. A purely digital model of the same tiles passes at every
// code, which is why `scala/resources/vsrc` is not enough to train against.
//
// WHICH LINE MOVES THE SAMPLING POINT. The two delay lines are not
// interchangeable and the bench sweeps each for what it is for. The global line
// sits on the quadrature clock that the forwarded-clock lanes transmit on, and
// covers `GLOBAL_DL_CTRL_BITWIDTH` taps of `GLOBAL_DL_DELAY_STEP` -- about
// 70 ps, which is more than a UI, so it is what walks the sampling point across
// the eye and out the other side. A tile's own `Dctrl` is a per-lane trim
// covering `TX_DCDL_TAPS` steps of `DCDL_DELAY_STEP`, about 5 ps: enough to
// pull one lane back through the spread the clock tree gives it and no further.
// Sweeping the trim and expecting to cross a UI measures nothing, which is what
// this bench did before the delay models were real.
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

    // Global delay line taps to sweep, each `GLOBAL_DL_DELAY_STEP` ps. The
    // whole line is a little over a UI, so a sweep of it has to leave one UI
    // and settle into the next -- which is what makes the word boundary slip
    // somewhere in the middle, and what the assertions below key on.
    localparam int GLOBAL_TAPS = `GLOBAL_DL_CTRL_BITWIDTH;
    localparam real GLOBAL_SPAN_PS = GLOBAL_TAPS * `GLOBAL_DL_DELAY_STEP;
    // Global taps that make up one UI.
    localparam real TAPS_PER_UI = UI / `GLOBAL_DL_DELAY_STEP;

    // The per-lane trim, in the units the global sweep measures in. Sweeping
    // the trim at full scale should move the eye by this many global taps --
    // the one quantitative statement that says the trim is wired and is moving
    // the sampling point the way the delay models say it does.
    localparam real TRIM_SPAN_PS = `TX_DCDL_TAPS * `DCDL_DELAY_STEP;
    localparam real TRIM_IN_GLOBAL_TAPS = TRIM_SPAN_PS / `GLOBAL_DL_DELAY_STEP;
    // Taps either side of the eye edge the trim sweep looks over. Comfortably
    // more than the trim can move it, so the edge cannot leave the window.
    localparam int TRIM_WINDOW = 12;
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
    //
    // A third of a UI, in global taps. Nothing at the eye level closes the eye
    // but the driver's slew, so the real figure is most of a UI; a third leaves
    // room for the circuit level to narrow it with jitter and ISI without this
    // becoming a tuning exercise.
    localparam int MIN_EYE_TAPS = int'(TAPS_PER_UI / 3.0);
    localparam int MIN_EYE_VREF_CODES = 4;
    // The most the per-lane trim is allowed to move the sampling point, in
    // global taps: a quarter of a UI.
    //
    // Deliberately a bound on the KIND of thing the trim is rather than a
    // check against `TRIM_IN_GLOBAL_TAPS`. That figure comes from
    // `DCDL_DELAY_STEP`, which is the eye level's abstraction of the line; the
    // circuit level is a transistor model of the same cell and measures about
    // twice as much. Both are trims and both are correct at their own level,
    // so pinning the picoseconds would just be asserting which level is
    // running. What has to hold at every level is that the trim moves the
    // sampling point, moves it later, and is nowhere near able to cross a UI
    // -- that last being the whole distinction from the global line.
    localparam real MAX_TRIM_TAPS = TAPS_PER_UI / 4.0;

    // A measurement that cannot complete is a hang, not a failure, and this
    // bench waits on a recovered clock that a broken model may never produce.
    // Comfortably past the time the sweeps take, so it only ever fires on a
    // bench that has stopped making progress.
    // Each measured point costs a settle plus two reads, and there are a few
    // hundred of them across three sweeps. Sized well clear of that: it is
    // here to turn a hang into a failure, not to police the runtime.
    localparam realtime TIMEOUT_PS = 50000000;
    initial begin
        #(TIMEOUT_PS);
        $error("Error: the bench did not finish within %0t -- the lane stopped producing words, so a sweep is waiting on a clock that never came",
               TIMEOUT_PS);
        $finish;
    end

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

    // The global delay line, on the clock the forwarded-clock lane transmits
    // on. That is where it sits in the PHY: `ClkDistNetwork` hands TXCLKQ --
    // the copy that has been through this line -- to the two forwarded-clock
    // lanes, and plain TXCLK to everything that carries data. Delaying the
    // clock a lane is sampled against is the same thing as moving the sampling
    // point through the data eye, which is what makes this the coarse knob.
    //
    // Its fixed offset is ~10 ns, far more than a word. That is harmless here:
    // the data lane repeats one pattern forever, so a constant skew only
    // changes which rotation the receiver reports, and a rotation is what this
    // bench scores against rather than an absolute position.
    logic [`GLOBAL_DL_CTRL_BITWIDTH-1:0] global_dl_ctrl = '0;
    wire ckq;
    // `DELAY_OFS` overridden to nothing. The offset is common to every code in
    // the sweep, so it changes which rotation the receiver reports and nothing
    // else -- but `global_delayline` models its delay with a continuous
    // assignment, which is INERTIAL: an input pulse shorter than the delay is
    // swallowed rather than passed on. At the constant's value of
    // `GLOBAL_DL_DELAY_OFS` ps against a 62.5 ps half period, every edge is
    // swallowed and the line outputs nothing at all.
    //
    // That is worth knowing beyond this bench: the cell is not instantiated
    // anywhere in `verilog/` yet, so nothing has exercised it, and whatever
    // wires it up will have to deal with the same thing. Its step is fine; the
    // offset is not.
    global_delayline #(
        .DELAY_OFS(0.0)
    ) global_dl (
        .dl_ctrl(global_dl_ctrl),
        .clk_in(ck),
        .clk_out(ckq)
    );

    assign txclk_intf.CK = ckq;
    assign txdata_intf.CK = ck;

    // The clock lane's gate, held open for the whole bench. Undriven it is X,
    // and the gate ANDs it into the recovered clock -- so `clkout` alternates
    // between 0 and X rather than 0 and 1. That still looks like a posedge to
    // the lane's divider, so `divclk` runs and nothing hangs, but every latch
    // in the deserializer captures X and the lane reads `xxxxxxxx` at every
    // sampling point and every reference.
    assign rxclk_intf.clk_gate_en = 1'b1;

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
    // Thermometer codes. Written as a masked all-ones rather than `(1<<n)-1`
    // so that the top code, where `n` equals the width, is all taps on rather
    // than a shift off the end of the word.
    function automatic logic [`TX_DCDL_TAPS-1:0] taps(input int n);
        taps = ~({`TX_DCDL_TAPS{1'b1}} << n);
    endfunction

    function automatic logic [`GLOBAL_DL_CTRL_BITWIDTH-1:0] gtaps(input int n);
        gtaps = ~({`GLOBAL_DL_CTRL_BITWIDTH{1'b1}} << n);
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

    // Programs a code triple, lets it settle, and reads the lane twice a word
    // apart. `rot` is the word boundary the receiver came back with, or -1 if
    // the two reads disagreed or neither is this pattern.
    //
    // `gtap` is the coarse knob, on the forwarded clock; `trim` is the data
    // lane's own delay line. They move the sampling point in opposite
    // directions: the coarse line delays the clock, so the sample lands later
    // in the bit, while the trim delays the data, so the same clock edge lands
    // earlier in it.
    task automatic measure(
        input int gtap,
        input int trim,
        input int vref,
        output int rot
    );
        logic [WORD-1:0] first;
        logic [WORD-1:0] second;
        global_dl_ctrl = gtaps(gtap);
        txdata_intf.Dctrl = taps(trim);
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

    int delay_score[GLOBAL_TAPS];
    int trim_score[GLOBAL_TAPS];
    int trim_lo, trim_hi;
    int edge_untrimmed, edge_trimmed, trim_shift, eye_end;
    bit edge_is_trailing;
    int first_rot;
    bit boundary_slipped;
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

        // SWEEP 1: where in the UI the lane is sampled, on the coarse knob.
        $display("");
        $display("Sampling point sweep at vref_sel = %0d (%0.3f ps per tap, %0.1f ps per UI, %0.1f taps per UI)",
                 VREF_CENTER, `GLOBAL_DL_DELAY_STEP, UI, TAPS_PER_UI);
        for (int t = 0; t < GLOBAL_TAPS; t++) begin
            measure(t, 0, VREF_CENTER, rot);
            delay_score[t] = rot;
        end
        longest_run(delay_score, GLOBAL_TAPS, delay_start, delay_len);
        trained_tap = delay_start + delay_len / 2;
        row = "";
        for (int t = 0; t < GLOBAL_TAPS; t++) begin
            row = {row, (t >= delay_start && t < delay_start + delay_len)
                        ? "#" : "."};
        end
        $display("  %s", row);
        $display("  eye: %0d taps, %0.1f ps, %0.2f UI, centered on tap %0d; sweep spans %0.2f UI",
                 delay_len, delay_len * `GLOBAL_DL_DELAY_STEP,
                 delay_len * `GLOBAL_DL_DELAY_STEP / UI, trained_tap,
                 GLOBAL_SPAN_PS / UI);

        // Does the word boundary ever move? The sweep is longer than a UI, so
        // somewhere in it the sampling point has to leave one bit and land in
        // the next, and the rotation the receiver reports has to change. A
        // sweep that reports one boundary throughout has not moved the sampling
        // point at all, whatever eye it thinks it found.
        boundary_slipped = 1'b0;
        first_rot = -1;
        for (int t = 0; t < GLOBAL_TAPS; t++) begin
            if (delay_score[t] >= 0) begin
                if (first_rot < 0) first_rot = delay_score[t];
                else if (delay_score[t] != first_rot) boundary_slipped = 1'b1;
            end
        end

        // SWEEP 2: what level the lane is sliced against, at the sampling point
        // the first sweep found.
        $display("");
        $display("Reference sweep at tap %0d", trained_tap);
        for (int i = 0; i < VREF_POINTS; i++) begin
            measure(trained_tap, 0, i * VREF_STEP, rot);
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

        // SWEEP 3: what the per-lane trim is worth.
        //
        // The trim cannot cross a UI and is not meant to, so asking whether it
        // finds an eye measures nothing. What it is for is pulling one lane a
        // few ps against the others, and the way to see a few ps is to watch
        // where an edge of the eye lands on the coarse knob. Re-walking a
        // window around that edge with the trim at full scale should move it by
        // `TRIM_IN_GLOBAL_TAPS`, towards higher coarse codes: delaying the data
        // needs more clock delay to sample the same place in the bit.
        //
        // The edge measured is whichever one the coarse sweep left room around.
        // An eye that begins at tap 0 has no leading edge to find -- it starts
        // off the end of the sweep -- so the trailing edge is used instead, and
        // the other way round for an eye that runs to the last tap.
        eye_end = delay_start + delay_len;
        if (eye_end + TRIM_WINDOW < GLOBAL_TAPS && eye_end - TRIM_WINDOW >= 0) begin
            edge_is_trailing = 1'b1;
            trim_lo = eye_end - TRIM_WINDOW;
            trim_hi = eye_end + TRIM_WINDOW;
        end else begin
            edge_is_trailing = 1'b0;
            trim_lo = (delay_start - TRIM_WINDOW < 0) ? 0 : delay_start - TRIM_WINDOW;
            trim_hi = (delay_start + TRIM_WINDOW >= GLOBAL_TAPS)
                      ? GLOBAL_TAPS - 1 : delay_start + TRIM_WINDOW;
        end
        $display("");
        $display("Per-lane trim: %0d taps of %0.5f ps = %0.2f ps = %0.1f global taps",
                 `TX_DCDL_TAPS, `DCDL_DELAY_STEP, TRIM_SPAN_PS,
                 TRIM_IN_GLOBAL_TAPS);
        $display("  measured at the %s edge of the eye, taps %0d..%0d",
                 edge_is_trailing ? "trailing" : "leading", trim_lo, trim_hi);
        for (int t = trim_lo; t <= trim_hi; t++) begin
            measure(t, `TX_DCDL_TAPS, VREF_CENTER, rot);
            trim_score[t] = rot;
        end
        // Where the eye's edge sits in each sweep.
        //
        // The edge is found as the tap where the rotation stops being whatever
        // that sweep started the window with, NOT by matching the untrimmed
        // rotation. The trim delays the transmitting tile's own clock, so it
        // moves that lane's word boundary along with its data and the receiver
        // reports a different rotation either side of the trim -- 2 and 3
        // without it, 4 and 5 with it, at the geometry this bench runs at.
        // The rotation LABEL is not the invariant; where the rotation CHANGES
        // is, because that is the sampling point crossing a bit boundary.
        edge_untrimmed = -1;
        edge_trimmed = -1;
        for (int t = trim_lo; t <= trim_hi; t++) begin
            if (edge_untrimmed < 0 && delay_score[t] != delay_score[trim_lo])
                edge_untrimmed = t;
            if (edge_trimmed < 0 && trim_score[t] != trim_score[trim_lo])
                edge_trimmed = t;
        end
        row = "";
        for (int t = trim_lo; t <= trim_hi; t++)
            row = {row, (delay_score[t] == delay_score[trim_lo]) ? "#" : "."};
        $display("  no trim:   %s  (edge at tap %0d, rotation %0d)",
                 row, edge_untrimmed, delay_score[trim_lo]);
        row = "";
        for (int t = trim_lo; t <= trim_hi; t++)
            row = {row, (trim_score[t] == trim_score[trim_lo]) ? "#" : "."};
        $display("  full trim: %s  (edge at tap %0d, rotation %0d)",
                 row, edge_trimmed, trim_score[trim_lo]);
        trim_shift = edge_trimmed - edge_untrimmed;
        $display("  trim moved the eye edge by %0d global taps (%0.2f ps, %0.3f UI); the eye level's constants predict %0.1f",
                 trim_shift, trim_shift * `GLOBAL_DL_DELAY_STEP,
                 trim_shift * `GLOBAL_DL_DELAY_STEP / UI, TRIM_IN_GLOBAL_TAPS);

        // The link at the codes the sweeps picked.
        $display("");
        $display("Trained: tap %0d, vref_sel %0d", trained_tap, trained_vref);
        measure(trained_tap, 0, trained_vref, rot);
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
        if (!boundary_slipped)
            $error("Error: the word boundary never moved over %0.2f UI of coarse delay, so the global line is not moving the sampling point",
                   GLOBAL_SPAN_PS / UI);
        if (delay_len >= int'(TAPS_PER_UI))
            $error("Error: the eye is %0d taps, which is a whole UI of %0.1f or more -- nothing is closing it",
                   delay_len, TAPS_PER_UI);
        if (vref_len < MIN_EYE_VREF_CODES)
            $error("Error: reference eye is %0d codes, expected at least %0d",
                   vref_len, MIN_EYE_VREF_CODES);
        if (vref_len == VREF_POINTS)
            $error("Error: every reference code receives, so the slicer is not comparing against it");
        // The trim is a few ps against a 62.5 ps UI, so it is only visible as a
        // shift of the eye's edge. Zero means it is not wired through to the
        // tile at all; the wrong sign means it is moving the sampling point the
        // opposite way from what the delay models say.
        if (edge_untrimmed < 0 || edge_trimmed < 0)
            $error("Error: the eye edge is not inside the trim window, so the trim could not be measured");
        else if (trim_shift <= 0)
            $error("Error: full trim moved the eye edge by %0d global taps -- the per-lane trim is not pulling the lane later, so it is either not wired through to the tile or is delaying the wrong thing",
                   trim_shift);
        else if (trim_shift > MAX_TRIM_TAPS)
            $error("Error: full trim moved the eye edge by %0d global taps, more than the %0.0f that is a quarter UI -- this is a per-lane trim and should not be able to walk the sampling point like the global line can",
                   trim_shift, MAX_TRIM_TAPS);

        $display("Training complete.");
        $finish;
    end

endmodule
