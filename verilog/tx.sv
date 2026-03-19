`timescale 1ps/100fs

module tx_lane (
  input dll_reset,
  input dll_resetb,
  input ser_resetb,
  input clkp,
  input clkn,
  input din_0,
  input din_1,
  input din_2,
  input din_3,
  input din_4,
  input din_5,
  input din_6,
  input din_7,
  input din_8,
  input din_9,
  input din_10,
  input din_11,
  input din_12,
  input din_13,
  input din_14,
  input din_15,
  input din_16,
  input din_17,
  input din_18,
  input din_19,
  input din_20,
  input din_21,
  input din_22,
  input din_23,
  input din_24,
  input din_25,
  input din_26,
  input din_27,
  input din_28,
  input din_29,
  input din_30,
  input din_31,
  output dout,
  output divclk,
  input pu_ctl_0,
  input pu_ctl_1,
  input pu_ctl_2,
  input pu_ctl_3,
  input pu_ctl_4,
  input pu_ctl_5,
  input pu_ctl_6,
  input pu_ctl_7,
  input pu_ctl_8,
  input pu_ctl_9,
  input pu_ctl_10,
  input pu_ctl_11,
  input pu_ctl_12,
  input pu_ctl_13,
  input pu_ctl_14,
  input pu_ctl_15,
  input pu_ctl_16,
  input pu_ctl_17,
  input pu_ctl_18,
  input pu_ctl_19,
  input pu_ctl_20,
  input pu_ctl_21,
  input pu_ctl_22,
  input pu_ctl_23,
  input pu_ctl_24,
  input pu_ctl_25,
  input pu_ctl_26,
  input pu_ctl_27,
  input pu_ctl_28,
  input pu_ctl_29,
  input pu_ctl_30,
  input pu_ctl_31,
  input pu_ctl_32,
  input pu_ctl_33,
  input pu_ctl_34,
  input pu_ctl_35,
  input pu_ctl_36,
  input pu_ctl_37,
  input pu_ctl_38,
  input pu_ctl_39,
  input pd_ctlb_0,
  input pd_ctlb_1,
  input pd_ctlb_2,
  input pd_ctlb_3,
  input pd_ctlb_4,
  input pd_ctlb_5,
  input pd_ctlb_6,
  input pd_ctlb_7,
  input pd_ctlb_8,
  input pd_ctlb_9,
  input pd_ctlb_10,
  input pd_ctlb_11,
  input pd_ctlb_12,
  input pd_ctlb_13,
  input pd_ctlb_14,
  input pd_ctlb_15,
  input pd_ctlb_16,
  input pd_ctlb_17,
  input pd_ctlb_18,
  input pd_ctlb_19,
  input pd_ctlb_20,
  input pd_ctlb_21,
  input pd_ctlb_22,
  input pd_ctlb_23,
  input pd_ctlb_24,
  input pd_ctlb_25,
  input pd_ctlb_26,
  input pd_ctlb_27,
  input pd_ctlb_28,
  input pd_ctlb_29,
  input pd_ctlb_30,
  input pd_ctlb_31,
  input pd_ctlb_32,
  input pd_ctlb_33,
  input pd_ctlb_34,
  input pd_ctlb_35,
  input pd_ctlb_36,
  input pd_ctlb_37,
  input pd_ctlb_38,
  input pd_ctlb_39,
  input driver_en,
  input driver_en_b,
  input dll_en,
  input ocl,
  input delay_0,
  input delay_1,
  input delay_2,
  input delay_3,
  input delay_4,
  input delayb_0,
  input delayb_1,
  input delayb_2,
  input delayb_3,
  input delayb_4,
  input mux_en_0,
  input mux_en_1,
  input mux_en_2,
  input mux_en_3,
  input mux_en_4,
  input mux_en_5,
  input mux_en_6,
  input mux_en_7,
  input mux_enb_0,
  input mux_enb_1,
  input mux_enb_2,
  input mux_enb_3,
  input mux_enb_4,
  input mux_enb_5,
  input mux_enb_6,
  input mux_enb_7,
  input band_ctrl_0,
  input band_ctrl_1,
  input band_ctrlb_0,
  input band_ctrlb_1,
  input mix_en_0,
  input mix_en_1,
  input mix_en_2,
  input mix_en_3,
  input mix_en_4,
  input mix_en_5,
  input mix_en_6,
  input mix_en_7,
  input mix_en_8,
  input mix_en_9,
  input mix_en_10,
  input mix_en_11,
  input mix_en_12,
  input mix_en_13,
  input mix_en_14,
  input mix_en_15,
  input mix_enb_0,
  input mix_enb_1,
  input mix_enb_2,
  input mix_enb_3,
  input mix_enb_4,
  input mix_enb_5,
  input mix_enb_6,
  input mix_enb_7,
  input mix_enb_8,
  input mix_enb_9,
  input mix_enb_10,
  input mix_enb_11,
  input mix_enb_12,
  input mix_enb_13,
  input mix_enb_14,
  input mix_enb_15,
  input nen_out_0,
  input nen_out_1,
  input nen_out_2,
  input nen_out_3,
  input nen_out_4,
  input nen_outb_0,
  input nen_outb_1,
  input nen_outb_2,
  input nen_outb_3,
  input nen_outb_4,
  input pen_out_0,
  input pen_out_1,
  input pen_out_2,
  input pen_out_3,
  input pen_out_4,
  input pen_outb_0,
  input pen_outb_1,
  input pen_outb_2,
  input pen_outb_3,
  input pen_outb_4,
  output dll_code_0,
  output dll_code_1,
  output dll_code_2,
  output dll_code_3,
  output dll_code_4
);
  wire [31:0] din;
  assign din = {
    din_31,
    din_30, 
    din_29,
    din_28,
    din_27,
    din_26, 
    din_25,
    din_24,
    din_23,
    din_22, 
    din_21,
    din_20,
    din_19,
    din_18, 
    din_17,
    din_16,
    din_15,
    din_14, 
    din_13,
    din_12,
    din_11,
    din_10, 
    din_9,
    din_8,
    din_7,
    din_6, 
    din_5,
    din_4,
    din_3,
    din_2, 
    din_1,
    din_0
  };
  wire [39:0] pu_ctl = {
    pu_ctl_0,
    pu_ctl_1,
    pu_ctl_2,
    pu_ctl_3,
    pu_ctl_4,
    pu_ctl_5,
    pu_ctl_6,
    pu_ctl_7,
    pu_ctl_8,
    pu_ctl_9,
    pu_ctl_10,
    pu_ctl_11,
    pu_ctl_12,
    pu_ctl_13,
    pu_ctl_14,
    pu_ctl_15,
    pu_ctl_16,
    pu_ctl_17,
    pu_ctl_18,
    pu_ctl_19,
    pu_ctl_20,
    pu_ctl_21,
    pu_ctl_22,
    pu_ctl_23,
    pu_ctl_24,
    pu_ctl_25,
    pu_ctl_26,
    pu_ctl_27,
    pu_ctl_28,
    pu_ctl_29,
    pu_ctl_30,
    pu_ctl_31,
    pu_ctl_32,
    pu_ctl_33,
    pu_ctl_34,
    pu_ctl_35,
    pu_ctl_36,
    pu_ctl_37,
    pu_ctl_38,
    pu_ctl_39
  };
  wire [39:0] pd_ctlb = {
    pd_ctlb_0,
    pd_ctlb_1,
    pd_ctlb_2,
    pd_ctlb_3,
    pd_ctlb_4,
    pd_ctlb_5,
    pd_ctlb_6,
    pd_ctlb_7,
    pd_ctlb_8,
    pd_ctlb_9,
    pd_ctlb_10,
    pd_ctlb_11,
    pd_ctlb_12,
    pd_ctlb_13,
    pd_ctlb_14,
    pd_ctlb_15,
    pd_ctlb_16,
    pd_ctlb_17,
    pd_ctlb_18,
    pd_ctlb_19,
    pd_ctlb_20,
    pd_ctlb_21,
    pd_ctlb_22,
    pd_ctlb_23,
    pd_ctlb_24,
    pd_ctlb_25,
    pd_ctlb_26,
    pd_ctlb_27,
    pd_ctlb_28,
    pd_ctlb_29,
    pd_ctlb_30,
    pd_ctlb_31,
    pd_ctlb_32,
    pd_ctlb_33,
    pd_ctlb_34,
    pd_ctlb_35,
    pd_ctlb_36,
    pd_ctlb_37,
    pd_ctlb_38,
    pd_ctlb_39
  };

  txdata_tile_intf intf();
  assign intf.din = din;
  assign intf.clkp = clkp;
  assign intf.clkn = clkn;
  assign intf.rstb = ser_resetb;
  assign intf.pu_ctl = pu_ctl;
  assign intf.pd_ctlb = pd_ctlb;
  assign intf.driver_en = driver_en;
  assign intf.driver_enb = driver_en_b;
  assign intf.dl_ctrl = 7'b0;
  assign intf.vdd = 1'b1;
  assign intf.vss = 1'b0;
  assign dout = intf.dout;
  txdata_tile tile(
    .intf(intf)
  );

endmodule

module tx_driver (
   input din,
   output dout,
   input en,
   input en_b,
   input pu_ctl_0,
   input pu_ctl_1,
   input pu_ctl_2,
   input pu_ctl_3,
   input pu_ctl_4,
   input pu_ctl_5,
   input pu_ctl_6,
   input pu_ctl_7,
   input pu_ctl_8,
   input pu_ctl_9,
   input pu_ctl_10,
   input pu_ctl_11,
   input pu_ctl_12,
   input pu_ctl_13,
   input pu_ctl_14,
   input pu_ctl_15,
   input pu_ctl_16,
   input pu_ctl_17,
   input pu_ctl_18,
   input pu_ctl_19,
   input pu_ctl_20,
   input pu_ctl_21,
   input pu_ctl_22,
   input pu_ctl_23,
   input pu_ctl_24,
   input pu_ctl_25,
   input pu_ctl_26,
   input pu_ctl_27,
   input pu_ctl_28,
   input pu_ctl_29,
   input pu_ctl_30,
   input pu_ctl_31,
   input pu_ctl_32,
   input pu_ctl_33,
   input pu_ctl_34,
   input pu_ctl_35,
   input pu_ctl_36,
   input pu_ctl_37,
   input pu_ctl_38,
   input pu_ctl_39,
   input pd_ctlb_0,
   input pd_ctlb_1,
   input pd_ctlb_2,
   input pd_ctlb_3,
   input pd_ctlb_4,
   input pd_ctlb_5,
   input pd_ctlb_6,
   input pd_ctlb_7,
   input pd_ctlb_8,
   input pd_ctlb_9,
   input pd_ctlb_10,
   input pd_ctlb_11,
   input pd_ctlb_12,
   input pd_ctlb_13,
   input pd_ctlb_14,
   input pd_ctlb_15,
   input pd_ctlb_16,
   input pd_ctlb_17,
   input pd_ctlb_18,
   input pd_ctlb_19,
   input pd_ctlb_20,
   input pd_ctlb_21,
   input pd_ctlb_22,
   input pd_ctlb_23,
   input pd_ctlb_24,
   input pd_ctlb_25,
   input pd_ctlb_26,
   input pd_ctlb_27,
   input pd_ctlb_28,
   input pd_ctlb_29,
   input pd_ctlb_30,
   input pd_ctlb_31,
   input pd_ctlb_32,
   input pd_ctlb_33,
   input pd_ctlb_34,
   input pd_ctlb_35,
   input pd_ctlb_36,
   input pd_ctlb_37,
   input pd_ctlb_38,
   input pd_ctlb_39
);
  wire [39:0] pu_ctl = {
    pu_ctl_0,
    pu_ctl_1,
    pu_ctl_2,
    pu_ctl_3,
    pu_ctl_4,
    pu_ctl_5,
    pu_ctl_6,
    pu_ctl_7,
    pu_ctl_8,
    pu_ctl_9,
    pu_ctl_10,
    pu_ctl_11,
    pu_ctl_12,
    pu_ctl_13,
    pu_ctl_14,
    pu_ctl_15,
    pu_ctl_16,
    pu_ctl_17,
    pu_ctl_18,
    pu_ctl_19,
    pu_ctl_20,
    pu_ctl_21,
    pu_ctl_22,
    pu_ctl_23,
    pu_ctl_24,
    pu_ctl_25,
    pu_ctl_26,
    pu_ctl_27,
    pu_ctl_28,
    pu_ctl_29,
    pu_ctl_30,
    pu_ctl_31,
    pu_ctl_32,
    pu_ctl_33,
    pu_ctl_34,
    pu_ctl_35,
    pu_ctl_36,
    pu_ctl_37,
    pu_ctl_38,
    pu_ctl_39
  };
  wire [39:0] pd_ctlb = {
    pd_ctlb_0,
    pd_ctlb_1,
    pd_ctlb_2,
    pd_ctlb_3,
    pd_ctlb_4,
    pd_ctlb_5,
    pd_ctlb_6,
    pd_ctlb_7,
    pd_ctlb_8,
    pd_ctlb_9,
    pd_ctlb_10,
    pd_ctlb_11,
    pd_ctlb_12,
    pd_ctlb_13,
    pd_ctlb_14,
    pd_ctlb_15,
    pd_ctlb_16,
    pd_ctlb_17,
    pd_ctlb_18,
    pd_ctlb_19,
    pd_ctlb_20,
    pd_ctlb_21,
    pd_ctlb_22,
    pd_ctlb_23,
    pd_ctlb_24,
    pd_ctlb_25,
    pd_ctlb_26,
    pd_ctlb_27,
    pd_ctlb_28,
    pd_ctlb_29,
    pd_ctlb_30,
    pd_ctlb_31,
    pd_ctlb_32,
    pd_ctlb_33,
    pd_ctlb_34,
    pd_ctlb_35,
    pd_ctlb_36,
    pd_ctlb_37,
    pd_ctlb_38,
    pd_ctlb_39
  };
  driver drv (
      .din(din),
      .pu_ctl(pu_ctl),
      .pd_ctlb(pd_ctlb),
      .en(en),
      .enb(en_b),
      .dout(dout),
      .vdd(1'b1),
      .vss(1'b0)
  );
endmodule

interface txdata_tile_intf;
    logic [2**`SERDES_STAGES-1:0] din;
    logic clkp, clkn;
    logic rstb;
    wire dout;
    logic [`DRIVER_CTL_BITS-1:0] pu_ctl, pd_ctlb;
    logic driver_en, driver_enb;
    logic [`DCDL_CTRL_BITWIDTH-1:0] dl_ctrl;
    wire vdd, vss;
endinterface

module txdata_tile (
    txdata_tile_intf intf
);

    logic clkin;
    dcdl_simple dl(.clk_in(intf.clkp), .dl_ctrl(intf.dl_ctrl), .clk_out(clkin));

    // TODO: ensure serializer samples async queue correctly
    // for different delay line codes.
    logic [`SERDES_STAGES-1:0] serclk;
    assign serclk[0] = clkin;
    generate
        if (`SERDES_STAGES > 1) begin
            clkdiv clkdiv (
                .clkin(clkin),
                .clkout(serclk[`SERDES_STAGES-1:1]),
                .rstb(intf.rstb)
            );
        end
    endgenerate
    wire serdout;
    tree_ser ser(
        .din(intf.din),
        .clk(serclk),
        .dout(serdout)
    );

    driver drv (
        .din(serdout),
        .pu_ctl(intf.pu_ctl),
        .pd_ctlb(intf.pd_ctlb),
        .en(intf.driver_en),
        .enb(intf.driver_enb),
        .dout(intf.dout),
        .vdd(intf.vdd),
        .vss(intf.vss)
    );

endmodule

interface txdriver_tile_intf;
    logic din;
    logic [`DRIVER_CTL_BITS-1:0] pu_ctl, pd_ctlb;
    logic en, enb;
    wire dout;
    wire vdd, vss;
endinterface

module txdriver_tile (
    txdriver_tile_intf intf
);
    driver drv (
        .din(intf.din),
        .pu_ctl(intf.pu_ctl),
        .pd_ctlb(intf.pd_ctlb),
        .en(intf.en),
        .enb(intf.enb),
        .dout(intf.dout),
        .vdd(intf.vdd),
        .vss(intf.vss)
    );

endmodule

module dcdl_simple(
    input logic clk_in,
    input logic [`DCDL_CTRL_BITWIDTH-1:0] dl_ctrl,
    output logic clk_out
);

    assign #(dl_ctrl * `DCDL_DELAY_STEP + `DCDL_DELAY_OFS) clk_out = clk_in;
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


module ser_tb;

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

module ser21_tb;
    ser_tb #(.STAGES(1)) inner ();
endmodule
