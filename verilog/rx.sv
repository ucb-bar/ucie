interface rxdata_tile_intf;
    wire din;
    logic clk;
    logic rstb;
    logic [2**`SERDES_STAGES-1:0] dout;
    logic zen;
    logic [`TERMINATION_CTL_BITS-1:0] zctl;
    logic a_en, a_pc, b_en, b_pc, sel_a;
    logic [`RDAC_SEL_BITS-1:0] vref_sel;
    wire vdd, vss;
endinterface

module rxdata_tile(
    rxdata_tile_intf intf
);

wire vref;
wire dout_afe;

termination term(
    .vin(intf.din),
    .en(intf.zen),
    .zctl(intf.zctl),
    .vss(intf.vss)
);

rdac rdac(
    .out(vref),
    .sel(intf.vref_sel),
    .vdd(intf.vdd),
    .vss(intf.vss)
);

rx_afe afe(
    .vref(vref),
    .din(intf.din),
    .a_en(intf.a_en),
    .a_pc(intf.a_pc),
    .b_en(intf.b_en),
    .b_pc(intf.b_pc),
    .sel_a(intf.sel_a),
    .dout(dout_afe),
    .vdd(intf.vdd),
    .vss(intf.vss)
);

logic [`SERDES_STAGES-1:0] desclk;
assign desclk[0] = intf.clk;
generate
    if (`SERDES_STAGES > 1) begin
        clkdiv clkdiv (
            .clkin(intf.clk),
            .clkout(desclk[`SERDES_STAGES-1:1]),
            .rstb(intf.rstb)
        );
    end
endgenerate

tree_des des(
    .din(dout_afe),
    .clk(desclk),
    .dout(intf.dout)
);

endmodule

interface rxclk_tile_intf;
    wire clkin;
    logic clkout;
    logic zen;
    logic [`TERMINATION_CTL_BITS-1:0] zctl;
    logic a_en, a_pc, b_en, b_pc, sel_a;
    logic [`RDAC_SEL_BITS-1:0] vref_sel;
    wire vdd, vss;
endinterface

module rxclk_tile(
    rxclk_tile_intf intf
);

wire vref;

termination term(
    .vin(intf.clkin),
    .en(intf.zen),
    .zctl(intf.zctl),
    .vss(intf.vss)
);

rdac rdac(
    .out(vref),
    .sel(intf.vref_sel),
    .vdd(intf.vdd),
    .vss(intf.vss)
);

rx_afe afe(
    .vref(vref),
    .din(intf.clkin),
    .a_en(intf.a_en),
    .a_pc(intf.a_pc),
    .b_en(intf.b_en),
    .b_pc(intf.b_pc),
    .sel_a(intf.sel_a),
    .dout(intf.clkout),
    .vdd(intf.vdd),
    .vss(intf.vss)
);

endmodule

module des12 (
    input logic din,
    input logic clk,
    output logic [1:0] dout
);
    wire din_delayed;
    logic d0_int;

    assign #(`DES_IN_DELAY) din_delayed = din;

    neg_latch d0_l0 (
        .clkb(clk),
        .d(din_delayed),
        .q(d0_int)
    );

    pos_latch d0_l1 (
        .clk(clk),
        .d(d0_int),
        .q(dout[0])
    );

    pos_latch d1_l0 (
        .clk(clk),
        .d(din_delayed),
        .q(dout[1])
    );

endmodule

interface sbrx_tile_intf;
    wire din;
endinterface

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
    localparam DIN_DELAY = `T_HOLD_DEFAULT; // delay after fast clock edge that din changes

    logic clk;
    logic [STAGES-1:0] desclk;
    logic rstb;
    logic [2**STAGES-1:0] dout;
    logic din;

    assign desclk[0] = clk;

    generate
        if (STAGES > 1) begin
            clkdiv #(
                .STAGES(STAGES - 1)
            ) clkdiv (
                .clkin(clk),
                .clkout(desclk[STAGES-1:1]),
                .rstb(rstb)
            );
        end
    endgenerate


    tree_des #(
        .STAGES(STAGES)
    ) dut (
        .clk(desclk),
        .din(din),
        .dout(dout)
    );

    // Clock generation
    initial clk = 0;
    always #(`MIN_PERIOD/2) clk = ~clk;

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
