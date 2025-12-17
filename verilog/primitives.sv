// Positive edge triggered D flip-flop
module pos_dff #(
    parameter real T_CLKQ = `T_CLKQ_DQ_DEFAULT
)(
    input logic clk,
    input logic d,
    input logic rstb,
    output logic q
);

    specify
        specparam T_SETUP = `T_SETUP_DEFAULT;
        specparam T_HOLD = `T_HOLD_DEFAULT;
        $setup(d, posedge clk, T_SETUP);
        $hold(posedge clk, d, T_HOLD);
    endspecify

    // TODO: design reset distribution/async reset?
    always_ff @(posedge clk, negedge rstb) begin
        if (!rstb)
            q <= 1'b0;
        else
            q <= #(T_CLKQ) d;
    end
endmodule


module tb_dff;

    // Signals
    logic clk;
    logic d;
    logic q;

    // Ips DUT
    pos_dff dut (
        .clk(clk),
        .d(d),
        .q(q)
    );

    real T_PERIOD = `T_SETUP_DEFAULT + `T_HOLD_DEFAULT + 10;

    // Clock generation
    initial clk = 0;
    always #(T_PERIOD/2) clk = ~clk;

    // Test stimulus
    initial begin
        d = 0;

        repeat (2) @(posedge clk);

        // --- Setup violation: change 'd' too close to clock ---
        $display("Testing setup violation at %0t", $time);
        #(T_PERIOD - `T_SETUP_DEFAULT + 1)
        d = 1;
        @(posedge clk);

        repeat (2) @(posedge clk);

        // --- Hold violation: change 'd' too soon after clock ---
        $display("Testing hold violation at %0t", $time);
        @(posedge clk);
        #(`T_HOLD_DEFAULT - 1);  // 1ps after clk edge, hold required is 2ps
        d = 0;

        repeat (2) @(posedge clk);

        // Normal operation (no violation)
        $display("Normal operation at %0t", $time);
        @(posedge clk);
        #(`T_HOLD_DEFAULT + 1) // 3ps after clk edge and 7ps before next clk edge, satisfies setup/hold
        d = 1;
        @(posedge clk);
        #(`T_CLKQ_DQ_DEFAULT + 1) // Wait for data-to-q delay.
        if (q != 1'b1) $error("Incorrect q value (expected %b, got %b)", d, q);
        @(posedge clk);
        #(`T_HOLD_DEFAULT + 1)
        d = 0;
        @(posedge clk);
        #(`T_CLKQ_DQ_DEFAULT + 1)
        if (q != 1'b0) $error("Incorrect q value (expected %b, got %b)", d, q);

        repeat (2) @(posedge clk);

        $finish;
    end

endmodule

// Positive transparent latch
module pos_latch #(
    parameter real T_CLKQ_DQ = `T_CLKQ_DQ_DEFAULT
)(
    input logic clk,
    input logic d,
    output logic q
);

    specify
        specparam T_SETUP = `T_SETUP_DEFAULT;
        specparam T_HOLD = `T_HOLD_DEFAULT;
        $setup(d, negedge clk, T_SETUP);
        $hold(negedge clk, d, T_HOLD);
    endspecify

    always_latch begin
        if (clk) begin
            q <= #(T_CLKQ_DQ) d;
        end
    end
endmodule

// Negative transparent latch
module neg_latch #(
    parameter real T_CLKQ_DQ = `T_CLKQ_DQ_DEFAULT
)(
    input logic clkb,
    input logic d,
    output logic q
);

    specify
        specparam T_SETUP = `T_SETUP_DEFAULT;
        specparam T_HOLD = `T_HOLD_DEFAULT;
        $setup(d, posedge clkb, T_SETUP);
        $hold(posedge clkb, d, T_HOLD);
    endspecify

    always_latch begin
        if (!clkb) begin
            q <= #(T_CLKQ_DQ) d;
        end
    end
endmodule


module tb_latch;

    logic clk;
    logic clkb;
    logic d;
    logic qp;
    logic qn;

    pos_latch pdut (
        .clk(clk),
        .d(d),
        .q(qp)
    );

    neg_latch ndut (
        .clkb(clkb),
        .d(d),
        .q(qn)
    );

    real T_PERIOD = `T_SETUP_DEFAULT + `T_HOLD_DEFAULT + 10;

    // Clock generation (10ps period)
    initial clk = 0;
    always #(T_PERIOD/2) clk = ~clk;
    assign clkb = ~clk;

    // Test stimulus
    initial begin
        d = 0;

        // Negedge is the sampling edge for positive transparent latches.
        repeat (2) @(negedge clk);

        // --- Setup violation: change 'd' too close to clock ---
        $display("Testing setup violation at %0t", $time);
        #(T_PERIOD - `T_SETUP_DEFAULT + 1); // 2ps before clk edge, setup required is 5ps
        d = 1;

        repeat (2) @(negedge clk);

        // --- Hold violation: change 'd' too soon after clock ---
        $display("Testing hold violation at %0t", $time);
        @(negedge clk);
        #(`T_HOLD_DEFAULT - 1);  // 1ps after clk edge, hold required is 2ps
        d = 0;

        repeat (2) @(negedge clk);

        // Normal operation (no violation)
        $display("Normal operation at %0t", $time);
        @(negedge clk);
        #(`T_HOLD_DEFAULT + 1); // 3ps after clk edge and 7ps before next clk edge, satisfies setup/hold
        d = 1;
        @(negedge clk);
        #(`T_CLKQ_DQ_DEFAULT + 1); // Output should be up after T_CLKQ_DQ delay.
        if (qp != 1'b1) $error("Incorrect q value for pos_latch (expected %b, got %b)", d, qp);
        if (qn != 1'b1) $error("Incorrect q value for neg_latch (expected %b, got %b)", d, qn);
        @(negedge clk);
        #(`T_HOLD_DEFAULT + 1);
        d = 0;
        @(negedge clk);
        #(`T_CLKQ_DQ_DEFAULT + 1);
        if (qp != 1'b0) $error("Incorrect q value for pos_latch (expected %b, got %b)", d, qp);
        if (qn != 1'b0) $error("Incorrect q value for neg_latch (expected %b, got %b)", d, qn);

        repeat (2) @(negedge clk);

        $finish;
    end

endmodule

module mux #(
    parameter real MUX_DELAY = `MUX_DELAY_DEFAULT
)(
    input logic sel_a,
    input logic a,
    input logic b,
    output logic o
);
    assign #(MUX_DELAY) o = sel_a ? a : b;
endmodule

module clkdiv #(
    parameter integer STAGES = `CLKDIV_STAGES
)(
    input logic clkin,
    output logic [STAGES-1:0] clkout,
    input logic rstb
);
    pos_dff ff (
        .clk(clkin),
        .rstb(rstb),
        .d(~clkout[0]),
        .q(clkout[0])
    );
    genvar i;
    generate
        for (i = 0; i < STAGES - 1; i++) begin
            pos_dff ff (
                .clk(clkout[i]),
                .rstb(rstb),
                .d(~clkout[i+1]),
                .q(clkout[i+1])
            );
        end
    endgenerate
endmodule
