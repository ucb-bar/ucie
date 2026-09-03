`timescale 1ps/100fs

// Shim matching the `tx_lane` blackbox in `phy/macros/Tx.scala`, so a design
// emitted from Chisel can be simulated against the tile model below. The port
// widths are fixed here rather than taken from `constants.vams` because they
// have to track `TxLane` on the Chisel side.
//
// VDDQ, VDD, and VSS are pins on the tile; this shim ties them off so the
// digital-only flow does not have to route supplies.
module tx_lane (
  input [31:0] DataIN,
  input CK,
  input [31:0] Dctrl,
  input [8:0] ENP,
  input [8:0] ENN,
  input [3:0] ENP_EQ,
  input [3:0] ENN_EQ,
  input RST_async,
  output D2D_TX
);
  txdata_tile_intf intf();
  assign intf.DataIN = DataIN;
  assign intf.CK = CK;
  assign intf.Dctrl = Dctrl;
  assign intf.ENP = ENP;
  assign intf.ENN = ENN;
  assign intf.ENP_EQ = ENP_EQ;
  assign intf.ENN_EQ = ENN_EQ;
  assign intf.RST_async = RST_async;
  assign intf.vddq = 1'b1;
  assign intf.vdd = 1'b1;
  assign intf.vss = 1'b0;
  assign D2D_TX = intf.D2D_TX;
  txdata_tile tile(
    .intf(intf)
  );

endmodule

module pad_driver (
  input din,
  output dout,
  input en,
  input en_b,
  input [`PAD_DRIVER_SEGMENTS-1:0] pu_ctl,
  input [`PAD_DRIVER_SEGMENTS-1:0] pd_ctlb
);
  pad_driver_cell drv (
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
    logic [2**`SERDES_STAGES-1:0] DataIN;
    // The tile takes a single-ended high speed clock and makes its own
    // complement internally.
    logic CK;
    // Asynchronous reset for the tile's clock dividers, active high.
    logic RST_async;
    wire D2D_TX;
    logic [`TX_DRIVER_SEGMENTS-1:0] ENP, ENN;
    logic [`TX_DRIVER_EQ_SEGMENTS-1:0] ENP_EQ, ENN_EQ;
    logic [`TX_DCDL_TAPS-1:0] Dctrl;
    // VDDQ supplies the output driver, VDD the pre-driver and digital logic.
    wire vddq, vdd, vss;
endinterface

module txdata_tile (
    txdata_tile_intf intf
);

    // `Dctrl` is thermometer coded, so the delay follows the number of taps
    // enabled rather than the value of the bus.
    logic clkin;
    dcdl_simple dl(
        .clk_in(intf.CK),
        .dl_ctrl(`DCDL_CTRL_BITWIDTH'($countones(intf.Dctrl))),
        .clk_out(clkin)
    );

    // TODO: ensure serializer samples async queue correctly
    // for different delay line codes.
    logic [`SERDES_STAGES-1:0] serclk;
    assign serclk[0] = clkin;
    generate
        if (`SERDES_STAGES > 1) begin
            clkdiv clkdiv (
                .clkin(clkin),
                .clkout(serclk[`SERDES_STAGES-1:1]),
                .rstb(~intf.RST_async)
            );
        end
    endgenerate
    wire serdout;
    tree_ser ser(
        .din(intf.DataIN),
        .clk(serclk),
        .dout(serdout)
    );

    tx_tile_driver drv (
        .din(serdout),
        .ENP(intf.ENP),
        .ENN(intf.ENN),
        .ENP_EQ(intf.ENP_EQ),
        .ENN_EQ(intf.ENN_EQ),
        .dout(intf.D2D_TX),
        .vddq(intf.vddq),
        .vss(intf.vss)
    );

endmodule

// Sideband bump driver: the 2:1 serializer and the pad driver as one cell,
// matching the `sb_driver` blackbox in `phy/macros/SbDriver.scala`.
//
// VDD and VSS are pins on the cell; this shim ties them off so the
// digital-only flow does not have to route supplies.
module sb_driver (
  input clk,
  input d0,
  input d1,
  input [`PAD_DRIVER_SEGMENTS-1:0] pu_ctl,
  input [`PAD_DRIVER_SEGMENTS-1:0] pd_ctlb,
  input en,
  input en_b,
  output out
);
  wire serdout;
  ser21 ser (
      .din({d1, d0}),
      .clk(clk),
      .dout(serdout)
  );
  pad_driver_cell drv (
      .din(serdout),
      .pu_ctl(pu_ctl),
      .pd_ctlb(pd_ctlb),
      .en(en),
      .enb(en_b),
      .dout(out),
      .vdd(1'b1),
      .vss(1'b0)
  );
endmodule

interface sb_driver_tile_intf;
    // Half rate bits and the clock to serialize them on.
    logic clk, d0, d1;
    logic [`PAD_DRIVER_SEGMENTS-1:0] pu_ctl, pd_ctlb;
    logic en, enb;
    wire out;
    wire vdd, vss;
endinterface

// The tile behind `sb_driver`: a 2:1 serializer feeding a pad driver.
module sb_driver_tile (
    sb_driver_tile_intf intf
);
    wire serdout;
    ser21 ser (
        .din({intf.d1, intf.d0}),
        .clk(intf.clk),
        .dout(serdout)
    );
    pad_driver_cell drv (
        .din(serdout),
        .pu_ctl(intf.pu_ctl),
        .pd_ctlb(intf.pd_ctlb),
        .en(intf.en),
        .enb(intf.enb),
        .dout(intf.out),
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


// 2:1 double data rate serializer, shared by the mainband serializer tree and
// the sideband bump drivers.
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

// The mainband serializer: a binary tree of `ser21` cells, the fastest one
// last, that turns a `2**STAGES` bit word into a double data rate serial
// stream.
//
// The tree pairs ADJACENT bus bits at every level, so a cell only ever sees the
// two neighbouring bits of its input bus and the layout stays a regular tree.
// Each level splits its bus into contiguous halves and sends the low half
// first, which at the last level leaves cell k holding `din[2*k]` and
// `din[2*k+1]`.
//
// That wiring emits the bit-reversal permutation: the bit sent in UI `t` is
// `din[bitrev(t)]`, the reversal of the STAGES index bits, i.e. for 32 bits
//
//   D0 D16 D8 D24 D4 D20 D12 D28 D2 D18 D10 D26 D6 D22 D14 D30
//   D1 D17 D9 D25 D5 D21 D13 D29 D3 D19 D11 D27 D7 D23 D15 D31
//
// rather than D0 D1 D2 D3. `tree_des` reverses the same way, so a tree at each
// end of a link cancels once the word boundaries line up; against anything else
// the digital side has to undo it (that is what the per-lane shuffler in front
// of the tile is for).
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

            // `din_int[0]` goes out during the high phase of this level's
            // clock, so the low half of the bus is the half that goes first.
            assign din0 = din[2**(STAGES-1)-1:0];
            assign din1 = din[2**STAGES-1:2**(STAGES-1)];

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

    // The bit the tree sends in UI `t` is `din[treeBitOrder(t)]`: the reversal
    // of the STAGES index bits. See `tree_ser`.
    function automatic integer treeBitOrder(integer t);
        treeBitOrder = 0;
        for (int b = 0; b < STAGES; b++) begin
            treeBitOrder |= ((t >> b) & 1) << (STAGES - 1 - b);
        end
    endfunction

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
            for (int t = 0; t < 2**STAGES; t++) begin
                expected_q.push_back(din[treeBitOrder(t)]);
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
