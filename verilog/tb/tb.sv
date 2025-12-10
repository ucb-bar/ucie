`timescale 1ns/1ps

module tb_tree_serializer;

    // ------------------------------------------------------------
    // Parameters
    // ------------------------------------------------------------
    localparam K = 3;
    localparam M = (1 << K);

    // ------------------------------------------------------------
    // DUT signals
    // ------------------------------------------------------------
    reg                 clk;
    reg                 rst;
    reg  [M-1:0]        din;
    wire                dout;
    wire [K-1:0]        idx;

    // ------------------------------------------------------------
    // Instantiate DUT
    // ------------------------------------------------------------
    tree_serializer #(.K(K)) dut (
        .clk(clk),
        .rst(rst),
        .din(din),
        .idx(idx),
        .dout(dout)
    );

    // ------------------------------------------------------------
    // Clock Generation (10 ns period)
    // ------------------------------------------------------------
    initial begin
        clk = 0;
        forever #5 clk = ~clk;
    end

    // ------------------------------------------------------------
    // Reset Sequence
    // ------------------------------------------------------------
    initial begin
        rst = 1;
        #(20);
        rst = 0;
    end

    // ------------------------------------------------------------
    // Stimulus
    // ------------------------------------------------------------
    integer i;

    initial begin
        $fsdbDumpfile("tree_serializer.fsdb");
        $fsdbDumpvars(0, tb_tree_serializer);

        // Wait for reset deassertion
        @(negedge rst);

        // Apply a static pattern first
        din = 8'b1101_0011;
        repeat (10) @(posedge clk);

        // Apply random data patterns
        for (i = 0; i < 40; i = i + 1) begin
            din = $random;
            @(posedge clk);
        end

        // Finish simulation
        $display("Simulation complete.");
        $finish;
    end

    // ------------------------------------------------------------
    // Self-checking: Should see dout == din[idx]
    // ------------------------------------------------------------
    always @(posedge clk) begin
        if (!rst) begin
            if (dout !== din[idx]) begin
                $display("[%0t] ERROR: dout=%0b expected %0b (din[%0d])",
                         $time, dout, din[idx], idx);
            end else begin
                $display("[%0t] OK: dout=%0b = din[%0d]",
                         $time, dout, idx);
            end
        end
    end

endmodule
