module rx_data_lane (
   input din,
   output dout_0,
   output dout_1,
   output dout_2,
   output dout_3,
   output dout_4,
   output dout_5,
   output dout_6,
   output dout_7,
   output dout_8,
   output dout_9,
   output dout_10,
   output dout_11,
   output dout_12,
   output dout_13,
   output dout_14,
   output dout_15,
   output dout_16,
   output dout_17,
   output dout_18,
   output dout_19,
   output dout_20,
   output dout_21,
   output dout_22,
   output dout_23,
   output dout_24,
   output dout_25,
   output dout_26,
   output dout_27,
   output dout_28,
   output dout_29,
   output dout_30,
   output dout_31,
   output divclk,
   input clk,
   input rstb,
   input zen,
   input zctl_0,
   input zctl_1,
   input zctl_2,
   input zctl_3,
   input zctl_4,
   input zctl_5,
   input zctl_6,
   input zctl_7,
   input zctl_8,
   input zctl_9,
   input zctl_10,
   input zctl_11,
   input zctl_12,
   input zctl_13,
   input zctl_14,
   input zctl_15,
   input zctl_16,
   input zctl_17,
   input zctl_18,
   input zctl_19,
   input a_en,
   input a_pc,
   input b_en,
   input b_pc,
   input sel_a,
   input vref_sel_0,
   input vref_sel_1,
   input vref_sel_2,
   input vref_sel_3,
   input vref_sel_4,
   input vref_sel_5,
   input vref_sel_6
);
  wire [19:0] zctl = {
    zctl_0,
    zctl_1,
    zctl_2,
    zctl_3,
    zctl_4,
    zctl_5,
    zctl_6,
    zctl_7,
    zctl_8,
    zctl_9,
    zctl_10,
    zctl_11,
    zctl_12,
    zctl_13,
    zctl_14,
    zctl_15,
    zctl_16,
    zctl_17,
    zctl_18,
    zctl_19
  };
  wire [6:0] vref_sel = {
    vref_sel_0,
    vref_sel_1,
    vref_sel_2,
    vref_sel_3,
    vref_sel_4,
    vref_sel_5,
    vref_sel_6
  };
  rxdata_tile_intf intf();
  assign intf.din = din;
  assign intf.clk = clk;
  assign intf.rstb = rstb;
  assign intf.zen = zen;
  assign intf.zctl = zctl;
  assign intf.a_en = a_en;
  assign intf.a_pc = a_pc;
  assign intf.b_en = b_en;
  assign intf.b_pc = b_pc;
  assign intf.sel_a = sel_a;
  assign intf.vref_sel = vref_sel;
  assign intf.vdd = 1'b1;
  assign intf.vss = 1'b0;

  assign dout_0  = intf.dout[0];
  assign dout_1  = intf.dout[1];
  assign dout_2  = intf.dout[2];
  assign dout_3  = intf.dout[3];
  assign dout_4  = intf.dout[4];
  assign dout_5  = intf.dout[5];
  assign dout_6  = intf.dout[6];
  assign dout_7  = intf.dout[7];
  assign dout_8  = intf.dout[8];
  assign dout_9  = intf.dout[9];
  assign dout_10 = intf.dout[10];
  assign dout_11 = intf.dout[11];
  assign dout_12 = intf.dout[12];
  assign dout_13 = intf.dout[13];
  assign dout_14 = intf.dout[14];
  assign dout_15 = intf.dout[15];
  assign dout_16 = intf.dout[16];
  assign dout_17 = intf.dout[17];
  assign dout_18 = intf.dout[18];
  assign dout_19 = intf.dout[19];
  assign dout_20 = intf.dout[20];
  assign dout_21 = intf.dout[21];
  assign dout_22 = intf.dout[22];
  assign dout_23 = intf.dout[23];
  assign dout_24 = intf.dout[24];
  assign dout_25 = intf.dout[25];
  assign dout_26 = intf.dout[26];
  assign dout_27 = intf.dout[27];
  assign dout_28 = intf.dout[28];
  assign dout_29 = intf.dout[29];
  assign dout_30 = intf.dout[30];
  assign dout_31 = intf.dout[31];
  assign divclk = intf.divclk;
  rxdata_tile tile(
    .intf(intf)
  );
endmodule

module rx_clock_lane (
   input clkin,
   output clkout,
   input zen,
   input zctl_0,
   input zctl_1,
   input zctl_2,
   input zctl_3,
   input zctl_4,
   input zctl_5,
   input zctl_6,
   input zctl_7,
   input zctl_8,
   input zctl_9,
   input zctl_10,
   input zctl_11,
   input zctl_12,
   input zctl_13,
   input zctl_14,
   input zctl_15,
   input zctl_16,
   input zctl_17,
   input zctl_18,
   input zctl_19,
   input a_en,
   input a_pc,
   input b_en,
   input b_pc,
   input sel_a,
   input vref_sel_0,
   input vref_sel_1,
   input vref_sel_2,
   input vref_sel_3,
   input vref_sel_4,
   input vref_sel_5,
   input vref_sel_6
);
  wire [19:0] zctl = {
    zctl_0,
    zctl_1,
    zctl_2,
    zctl_3,
    zctl_4,
    zctl_5,
    zctl_6,
    zctl_7,
    zctl_8,
    zctl_9,
    zctl_10,
    zctl_11,
    zctl_12,
    zctl_13,
    zctl_14,
    zctl_15,
    zctl_16,
    zctl_17,
    zctl_18,
    zctl_19
  };
  wire [6:0] vref_sel = {
    vref_sel_0,
    vref_sel_1,
    vref_sel_2,
    vref_sel_3,
    vref_sel_4,
    vref_sel_5,
    vref_sel_6
  };
  rxclk_tile_intf intf();
  assign intf.clkin = clkin;
  assign clkout = intf.clkout;
  assign intf.zen = zen;
  assign intf.zctl = zctl;
  assign intf.a_en = a_en;
  assign intf.a_pc = a_pc;
  assign intf.b_en = b_en;
  assign intf.b_pc = b_pc;
  assign intf.sel_a = sel_a;
  assign intf.vref_sel = vref_sel;
  assign intf.vdd = 1'b1;
  assign intf.vss = 1'b0;
  rxclk_tile tile(
    .intf(intf)
  );
endmodule

interface rxdata_tile_intf;
    wire din;
    logic clk;
    logic divclk;
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
assign intf.divclk = desclk[`SERDES_STAGES-1];

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


module des_tb;

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

module des12_tb;
    des_tb #(.STAGES(1)) inner ();
endmodule
