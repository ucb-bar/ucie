`timescale 1ps/1ps

module txdata (
    input logic [2**`SERDES_STAGES-1:0] din,
    input logic clkp, clkn,
    input logic rstb,
    output out,
    input logic [`DRIVER_CTL_BITS-1:0] pu_ctl, pd_ctlb,
    input logic driver_en, driver_enb,
    input logic [`DCDL_CTRL_BITWIDTH-1:0] dl_ctrl,
    inout vdd, vss
);

    logic clkin;
    dcdl dl(.clkin(clkp), .dl_ctrl(dl_ctrl), .clkout(clkin));

    // TODO: ensure serializer samples async queue correctly
    // for different delay line codes.
    logic [`SERDES_STAGES-1:0] serclk;
    assign serclk[0] = clkin;
    generate
        if (`SERDES_STAGES > 1) begin
            clkdiv clkdiv (
                .clkin(clkin),
                .clkout(serclk[`SERDES_STAGES-1:1]),
                .rstb(rstb)
            );
        end
    endgenerate
    wire serdout;
    tree_ser ser(
        .din(din),
        .clk(serclk),
        .dout(serdout)
    );

    driver drv (
        .din(serdout),
        .pu_ctl(pu_ctl),
        .pd_ctlb(pd_ctlb),
        .en(driver_en),
        .enb(driver_enb),
        .dout(dout),
        .vdd(vdd),
        .vss(vss)
    );

endmodule

module ser21 (
    input logic [1:0] din,
    input logic clk,
    output logic dout
);
    logic d0_hold, d1_int, d1_hold;

    neg_latch d0_l0 (
        .clkb(clk),
        .d(din[0]),
        .q(d0_hold)
    );

    neg_latch d1_l0 (
        .clkb(clk),
        .d(din[1]),
        .q(d1_int)
    );

    pos_latch d1_l1 (
        .clk(clk),
        .d(d1_int),
        .q(d1_hold)
    );

    mux mux (
        .sel_a(clk),
        .a(d0_hold),
        .b(d1_hold),
        .o(dout)
    );

endmodule

module tree_ser #(
    parameter integer STAGES = `SERDES_STAGES
)(
    input logic [2**STAGES-1:0] din,
    input logic [STAGES-1:0] clk,
    output logic dout
);
    generate
        if (STAGES == 1) begin
            ser21 ser (
                .clk(clk[0]),
                .din(din),
                .dout(dout)
            );
        end
        else begin
            logic [1:0] din_int;
            logic [2**(STAGES-1)-1:0] din0;
            logic [2**(STAGES-1)-1:0] din1;

            genvar i;
            for (i = 0; i < 2**STAGES; i++) begin
                if (i % 2 == 0) begin
                    assign din0[i/2] = din[i];
                end
                else begin
                    assign din1[i/2] = din[i];
                end
            end

            tree_ser #(
                .STAGES(STAGES-1)
            ) ser0 (
                .clk(clk[STAGES-1:1]),
                .din(din0),
                .dout(din_int[0])
            );

            tree_ser #(
                .STAGES(STAGES-1)
            ) ser1 (
                .clk(clk[STAGES-1:1]),
                .din(din1),
                .dout(din_int[1])
            );

            ser21 ser (
                .clk(clk[0]),
                .din(din_int),
                .dout(dout)
            );
        end
    endgenerate

endmodule


module tb_ser;

    parameter STAGES = `SERDES_STAGES;          // width of serializer
    parameter CYCLES = 16;    // number of test cycles

    logic clk;
    logic [STAGES-1:0] serclk;
    logic rstb;
    logic [2**STAGES-1:0] din;
    logic dout;

    assign serclk[0] = clk;

    generate
        if (STAGES > 1) begin
            clkdiv #(
                .STAGES(STAGES - 1)
            ) clkdiv (
                .clkin(clk),
                .clkout(serclk[STAGES-1:1]),
                .rstb(rstb)
            );
        end
    endgenerate

    tree_ser #(
        .STAGES(STAGES)
    ) dut (
        .clk(serclk),
        .din(din),
        .dout(dout)
    );

    // Clock generation
    initial clk = 0;
    always #(`MIN_PERIOD/2) clk = ~clk;

    bit expected_q[$];

    // Test stimulus
    initial begin
        $display("OUTPUT: clk\tdin\tdout");
        $monitor("OUTPUT: %b\t%h\t%b\t%b", clk, din, dout, rstb);

        rstb = 0;
        din = 0;
        repeat (5) @(posedge clk);
        rstb = 1;
        repeat (5) @(posedge clk);

        // Apply all ones to input to find start of output.
        @(negedge serclk[STAGES-1]);
        din = {2**STAGES{1'b1}};

        // Apply random inputs
        for (integer i = 0; i < CYCLES; i=i+1) begin
            @(negedge serclk[STAGES-1]);
            din = $urandom_range(0, 2**(2**STAGES) - 1);
            for (int b = 0; b < 2**STAGES; b++) begin
                expected_q.push_back(din[b]);   // push LSB first if that is how your design emits
            end
        end
    end

    bit expected;
    initial begin
        @(posedge dout)
        repeat (2**STAGES) @(posedge clk, negedge clk);
        
        for (integer i = 0; i < CYCLES * 2**STAGES; i++) begin
            @(posedge clk, negedge clk);
            expected = expected_q.pop_front();
            if (expected !== dout)
                $error("Mismatch at time %t: expected %0b, got %0b",
                        $time, expected, dout);
        end

        $display("Simulation complete.");
        $finish;
    end

endmodule

module tb_ser21;
    tb_ser #(.STAGES(1)) inner ();
endmodule
