`timescale 1ps/1ps

module des12 (
    input logic din,
    input logic clk,
    output logic [1:0] dout
);
    logic d0_int;

    neg_latch d0_l0 (
        .clkb(clk),
        .d(din),
        .q(d0_int)
    );

    pos_latch d0_l1 (
        .clk(clk),
        .d(d0_int),
        .q(dout[0])
    );

    pos_latch d1_l0 (
        .clk(clk),
        .d(din),
        .q(dout[1])
    );

endmodule

module tree_des #(
    parameter integer STAGES = `SERDES_STAGES
)(
    input logic din,
    input logic [STAGES-1:0] clk,
    output logic [2**STAGES-1:0] dout
);
    generate
        if (STAGES == 1) begin
            des12 ser (
                .clk(clk[0]),
                .din(din),
                .dout(dout)
            );
        end
        else begin
            logic [1:0] dout_int;
            logic [2**(STAGES-1)-1:0] dout0;
            logic [2**(STAGES-1)-1:0] dout1;

            genvar i;
            for (i = 0; i < 2**STAGES; i++) begin
                if (i % 2 == 0) begin
                    assign dout[i] = dout0[i/2];
                end
                else begin
                    assign dout[i] = dout1[i/2];
                end
            end

            tree_des #(
                .STAGES(STAGES-1)
            ) ser0 (
                .clk(clk[STAGES-1:1]),
                .din(dout_int[0]),
                .dout(dout0)
            );

            tree_des #(
                .STAGES(STAGES-1)
            ) ser1 (
                .clk(clk[STAGES-1:1]),
                .din(dout_int[1]),
                .dout(dout1)
            );

            des12 ser (
                .clk(clk[0]),
                .din(din),
                .dout(dout_int)
            );
        end
    endgenerate

endmodule


module tb_des;

    parameter STAGES = `SERDES_STAGES;
    localparam CYCLES = 16;    // number of test cycles
    localparam DIN_DELAY = `T_HOLD_DEFAULT;    // delay after fast clock edge that din changes
    localparam STAGE_DELAY = 100;    // delay of each stage

    logic clk;
    logic [STAGES-1:0] desclk;
    logic [STAGES-1:0] clk_int;
    logic rstb;
    logic [2**STAGES-1:0] dout;
    logic din;

    assign clk_int[0] = clk;

    generate
        if (STAGES > 1) begin
            clkdiv #(
                .STAGES(STAGES - 1)
            ) clkdiv (
                .clkin(clk),
                .clkout(clk_int[STAGES-1:1]),
                .rstb(rstb)
            );
        end
    endgenerate
    for (genvar i = 0; i < STAGES; i++) begin
        always @(posedge clk_int[i], negedge clk_int[i])
            desclk[i] = #(STAGE_DELAY * (STAGES - i - 1)) clk_int[i];
    end


    tree_des #(
        .STAGES(STAGES)
    ) dut (
        .clk(desclk),
        .din(din),
        .dout(dout)
    );

    // Clock generation
    initial clk = 0;
    always #500 clk = ~clk; // 500ps period

    bit [2**STAGES-1:0] expected_q[$];
    bit [2**STAGES-1:0] next_bits;

    // Test stimulus
    initial begin
        rstb = 0;
        din = 0;
        repeat (5) @(posedge clk);
        rstb = 1;
        repeat (5) @(posedge clk);

        // Apply 1 to input to find start of output.
        #(DIN_DELAY);
        din = 1;

        // Apply random inputs
        for (integer i = 0; i < CYCLES; i=i+1) begin
            next_bits = $urandom_range(0, 2**(2**STAGES) - 1);
            expected_q.push_back(next_bits);
            for (integer j = 0; j < 2**STAGES; j++ ) begin
                @(posedge clk, negedge clk);
                #(DIN_DELAY);
                din = next_bits[0];
                next_bits >>= 1;
            end
        end

    end

    bit [2**STAGES-1:0] expected;
    reg [2**STAGES-1:0] prev;
    reg [2**STAGES-1:0] next;
    reg [STAGES:0] shift;
    initial begin
        @(posedge |dout);
        @(posedge desclk[STAGES-1]);
        for (integer i = 2**STAGES - 1; i >= 0; i--) begin
            if (dout[i]) shift = i + 1;
        end
        prev = dout >> shift;
        
        for (integer i = 0; i < CYCLES; i++) begin
            @(posedge desclk[STAGES-1]);
            next = prev | (dout << (2**STAGES - shift));
            prev = dout >> shift;
            expected = expected_q.pop_front();
            $display("Expected %0b, got %0b", expected, next);
            if (expected !== next)
                $error("Mismatch at time %t: expected %0b, got %0b",
                        $time, expected, next);
        end

        $display("Simulation complete.");
        $finish;
    end

endmodule

module tb_des12;
    tb_des #(.STAGES(1)) inner ();
endmodule
