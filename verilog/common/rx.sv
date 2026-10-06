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
   input Dctrl_0,
   input Dctrl_1,
   input Dctrl_2,
   input Dctrl_3,
   input Dctrl_4,
   input Dctrl_5,
   input Dctrl_6,
   input Dctrl_7,
   input Dctrl_8,
   input Dctrl_9,
   input Dctrl_10,
   input Dctrl_11,
   input Dctrl_12,
   input Dctrl_13,
   input Dctrl_14,
   input Dctrl_15,
   input Dctrl_16,
   input Dctrl_17,
   input Dctrl_18,
   input Dctrl_19,
   input Dctrl_20,
   input Dctrl_21,
   input Dctrl_22,
   input Dctrl_23,
   input Dctrl_24,
   input Dctrl_25,
   input Dctrl_26,
   input Dctrl_27,
   input Dctrl_28,
   input Dctrl_29,
   input Dctrl_30,
   input Dctrl_31,
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
  wire [31:0] Dctrl = {
    Dctrl_31,
    Dctrl_30,
    Dctrl_29,
    Dctrl_28,
    Dctrl_27,
    Dctrl_26,
    Dctrl_25,
    Dctrl_24,
    Dctrl_23,
    Dctrl_22,
    Dctrl_21,
    Dctrl_20,
    Dctrl_19,
    Dctrl_18,
    Dctrl_17,
    Dctrl_16,
    Dctrl_15,
    Dctrl_14,
    Dctrl_13,
    Dctrl_12,
    Dctrl_11,
    Dctrl_10,
    Dctrl_9,
    Dctrl_8,
    Dctrl_7,
    Dctrl_6,
    Dctrl_5,
    Dctrl_4,
    Dctrl_3,
    Dctrl_2,
    Dctrl_1,
    Dctrl_0
  };
  rxdata_tile_intf intf();
  assign intf.Dctrl = Dctrl;
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
    .intf(intf),
    .din(din)
  );
endmodule

module rx_clock_lane (
   input clkin,
   output clkout,
   input clk_gate_en,
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
   input vref_sel_6,
   input repair_clk,
   input repair_rstb,
   input repair_Dctrl_0,
   input repair_Dctrl_1,
   input repair_Dctrl_2,
   input repair_Dctrl_3,
   input repair_Dctrl_4,
   input repair_Dctrl_5,
   input repair_Dctrl_6,
   input repair_Dctrl_7,
   input repair_Dctrl_8,
   input repair_Dctrl_9,
   input repair_Dctrl_10,
   input repair_Dctrl_11,
   input repair_Dctrl_12,
   input repair_Dctrl_13,
   input repair_Dctrl_14,
   input repair_Dctrl_15,
   input repair_Dctrl_16,
   input repair_Dctrl_17,
   input repair_Dctrl_18,
   input repair_Dctrl_19,
   input repair_Dctrl_20,
   input repair_Dctrl_21,
   input repair_Dctrl_22,
   input repair_Dctrl_23,
   input repair_Dctrl_24,
   input repair_Dctrl_25,
   input repair_Dctrl_26,
   input repair_Dctrl_27,
   input repair_Dctrl_28,
   input repair_Dctrl_29,
   input repair_Dctrl_30,
   input repair_Dctrl_31,
   output repair_dout_0,
   output repair_dout_1,
   output repair_dout_2,
   output repair_dout_3,
   output repair_dout_4,
   output repair_dout_5,
   output repair_dout_6,
   output repair_dout_7,
   output repair_dout_8,
   output repair_dout_9,
   output repair_dout_10,
   output repair_dout_11,
   output repair_dout_12,
   output repair_dout_13,
   output repair_dout_14,
   output repair_dout_15,
   output repair_dout_16,
   output repair_dout_17,
   output repair_dout_18,
   output repair_dout_19,
   output repair_dout_20,
   output repair_dout_21,
   output repair_dout_22,
   output repair_dout_23,
   output repair_dout_24,
   output repair_dout_25,
   output repair_dout_26,
   output repair_dout_27,
   output repair_dout_28,
   output repair_dout_29,
   output repair_dout_30,
   output repair_dout_31,
   output repair_divclk
);
  wire [31:0] repair_Dctrl = {
    repair_Dctrl_31,
    repair_Dctrl_30,
    repair_Dctrl_29,
    repair_Dctrl_28,
    repair_Dctrl_27,
    repair_Dctrl_26,
    repair_Dctrl_25,
    repair_Dctrl_24,
    repair_Dctrl_23,
    repair_Dctrl_22,
    repair_Dctrl_21,
    repair_Dctrl_20,
    repair_Dctrl_19,
    repair_Dctrl_18,
    repair_Dctrl_17,
    repair_Dctrl_16,
    repair_Dctrl_15,
    repair_Dctrl_14,
    repair_Dctrl_13,
    repair_Dctrl_12,
    repair_Dctrl_11,
    repair_Dctrl_10,
    repair_Dctrl_9,
    repair_Dctrl_8,
    repair_Dctrl_7,
    repair_Dctrl_6,
    repair_Dctrl_5,
    repair_Dctrl_4,
    repair_Dctrl_3,
    repair_Dctrl_2,
    repair_Dctrl_1,
    repair_Dctrl_0
  };
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
  assign intf.clk_gate_en = clk_gate_en;
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
  assign intf.repair_clk = repair_clk;
  assign intf.repair_rstb = repair_rstb;
  assign intf.repair_Dctrl = repair_Dctrl;
  assign repair_divclk = intf.repair_divclk;
  assign repair_dout_0  = intf.repair_dout[0];
  assign repair_dout_1  = intf.repair_dout[1];
  assign repair_dout_2  = intf.repair_dout[2];
  assign repair_dout_3  = intf.repair_dout[3];
  assign repair_dout_4  = intf.repair_dout[4];
  assign repair_dout_5  = intf.repair_dout[5];
  assign repair_dout_6  = intf.repair_dout[6];
  assign repair_dout_7  = intf.repair_dout[7];
  assign repair_dout_8  = intf.repair_dout[8];
  assign repair_dout_9  = intf.repair_dout[9];
  assign repair_dout_10 = intf.repair_dout[10];
  assign repair_dout_11 = intf.repair_dout[11];
  assign repair_dout_12 = intf.repair_dout[12];
  assign repair_dout_13 = intf.repair_dout[13];
  assign repair_dout_14 = intf.repair_dout[14];
  assign repair_dout_15 = intf.repair_dout[15];
  assign repair_dout_16 = intf.repair_dout[16];
  assign repair_dout_17 = intf.repair_dout[17];
  assign repair_dout_18 = intf.repair_dout[18];
  assign repair_dout_19 = intf.repair_dout[19];
  assign repair_dout_20 = intf.repair_dout[20];
  assign repair_dout_21 = intf.repair_dout[21];
  assign repair_dout_22 = intf.repair_dout[22];
  assign repair_dout_23 = intf.repair_dout[23];
  assign repair_dout_24 = intf.repair_dout[24];
  assign repair_dout_25 = intf.repair_dout[25];
  assign repair_dout_26 = intf.repair_dout[26];
  assign repair_dout_27 = intf.repair_dout[27];
  assign repair_dout_28 = intf.repair_dout[28];
  assign repair_dout_29 = intf.repair_dout[29];
  assign repair_dout_30 = intf.repair_dout[30];
  assign repair_dout_31 = intf.repair_dout[31];
  rxclk_tile #(.WITH_REPAIR_TAP(1)) tile(
    .intf(intf),
    .clkin(clkin)
  );
endmodule

interface rxdata_tile_intf;
    logic clk;
    // Delay taps on the sampling clock, thermometer coded as on the TX tile.
    logic [2**`SERDES_STAGES-1:0] Dctrl;
    logic divclk;
    logic rstb;
    logic [2**`SERDES_STAGES-1:0] dout;
    logic zen;
    logic [`TERMINATION_CTL_BITS-1:0] zctl;
    logic a_en, a_pc, b_en, b_pc, sel_a;
    logic [`RDAC_SEL_BITS-1:0] vref_sel;
    wire vdd, vss;
endinterface

// The sampling back end a receive tile puts behind its analog front end: the
// local delay line on the sampling clock, the divider that clocks the tree,
// and the 1:32 deserializer itself.
//
// Factored out because the forwarded-clock lanes need one too. They have no
// data path of their own -- `rxclk_tile` is a front end feeding the clock tree
// -- so MBINIT.REPAIRCLK cannot measure them without a deserializer of their
// own, and it has to resolve bits exactly the way a data lane's does or the
// per-lane shuffler default is wrong for one of them.
//
// The track lane needs no tap: it already has this behind its own front end,
// and REPAIRCLK borrows it by switching that lane's sampling clock over for
// the duration. See `docs/repairclk-track-mux.md`.
module rx_des_backend(
    input logic din,
    input logic clk,
    input logic rstb,
    input logic [2**`SERDES_STAGES-1:0] Dctrl,
    output logic divclk,
    output logic [2**`SERDES_STAGES-1:0] dout
);

// Local delay line on the sampling clock, the RX counterpart of the TX tile's.
logic desclkin;
local_delayline dl(
    .clk_in(clk),
    .dl_ctrl(Dctrl),
    .clk_out(desclkin)
);

logic [`SERDES_STAGES-1:0] desclk;
assign desclk[0] = desclkin;
generate
    if (`SERDES_STAGES > 1) begin
        clkdiv clkdiv (
            .clkin(desclkin),
            .clkout(desclk[`SERDES_STAGES-1:1]),
            .rstb(rstb)
        );
    end
endgenerate
assign divclk = desclk[`SERDES_STAGES-1];

tree_des des(
    .din(din),
    .clk(desclk),
    .dout(dout)
);

endmodule

// As on the TX side, the bump is a pin rather than a member of
// `rxdata_tile_intf`: a SystemVerilog interface cannot hold an electrical net,
// and a bump routed through one arrives at the termination and the front end
// through connect modules rather than as a shared analog node. See
// `verilog/README.md`.
module rxdata_tile(
    rxdata_tile_intf intf,
    input din
);

wire vref;
wire dout_afe;

termination term(
    .vin(din),
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
    .din(din),
    .a_en(intf.a_en),
    .a_pc(intf.a_pc),
    .b_en(intf.b_en),
    .b_pc(intf.b_pc),
    .sel_a(intf.sel_a),
    .dout(dout_afe),
    .vdd(intf.vdd),
    .vss(intf.vss)
);

rx_des_backend main(
    .din(dout_afe),
    .clk(intf.clk),
    .rstb(intf.rstb),
    .Dctrl(intf.Dctrl),
    .divclk(intf.divclk),
    .dout(intf.dout)
);

endmodule

interface rxclk_tile_intf;
    logic clkout;
    // Active-high enable for the recovered clock leaving this lane. Low while
    // the RX is not expected to receive: the lane stops handing a clock out,
    // so the distribution tree behind it stops too and none of the data lanes
    // are clocked.
    logic clk_gate_en;
    logic zen;
    logic [`TERMINATION_CTL_BITS-1:0] zctl;
    logic a_en, a_pc, b_en, b_pc, sel_a;
    logic [`RDAC_SEL_BITS-1:0] vref_sel;
    // MBINIT.REPAIRCLK sampling tap. A clock lane always carries one: it is
    // the only way its bump can be measured at all, since a forwarded clock
    // cannot be sampled by the clock it is recovering.
    logic repair_clk;
    logic repair_rstb;
    logic [2**`SERDES_STAGES-1:0] repair_Dctrl;
    logic repair_divclk;
    logic [2**`SERDES_STAGES-1:0] repair_dout;
    wire vdd, vss;
endinterface

module rxclk_tile #(
    // Both forwarded-clock lanes carry a tap in the PHY -- it is the only way
    // their bumps can be measured at all. The parameter exists so the
    // standalone analog benches, which drive neither a repair clock nor a
    // reset, instantiate the tile without one.
    parameter bit WITH_REPAIR_TAP = 0
)(
    rxclk_tile_intf intf,
    input clkin
);

// The recovered clock, before this lane's gate.
logic clkout_raw;

// Latched on the clock's low phase, so enabling or disabling mid-cycle cannot
// hand a runt to the tree and leave a lane's divider a count out.
logic gate_en_latched;
always @(*) begin
    if (!clkout_raw) gate_en_latched = intf.clk_gate_en;
end
assign intf.clkout = clkout_raw & gate_en_latched;

wire vref;

termination term(
    .vin(clkin),
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
    .din(clkin),
    .a_en(intf.a_en),
    .a_pc(intf.a_pc),
    .b_en(intf.b_en),
    .b_pc(intf.b_pc),
    .sel_a(intf.sel_a),
    .dout(clkout_raw),
    .vdd(intf.vdd),
    .vss(intf.vss)
);

// MBINIT.REPAIRCLK tap, taken ahead of the gate. The recovered clock is not
// distributed during REPAIRCLK -- that is the point of having a separate
// sample clock -- so a tap behind the gate would measure nothing.
generate
    if (WITH_REPAIR_TAP) begin : repair
        rx_des_backend tap(
            .din(clkout_raw),
            .clk(intf.repair_clk),
            .rstb(intf.repair_rstb),
            .Dctrl(intf.repair_Dctrl),
            .divclk(intf.repair_divclk),
            .dout(intf.repair_dout)
        );
    end else begin : no_repair
        assign intf.repair_divclk = 1'b0;
        assign intf.repair_dout = '0;
    end
endgenerate

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

// The mainband deserializer: a binary tree of `des12` cells, the fastest one
// first, that turns a double data rate serial stream into a `2**STAGES` bit
// word.
//
// This is the mirror image of `tree_ser`: the tree pairs ADJACENT bus bits at
// every level, so a cell only ever drives the two neighbouring bits of its
// output bus. Each level takes the earlier of its two half rate streams into
// the low half of its bus, which at the last level leaves cell k driving
// `dout[2*k]` and `dout[2*k+1]`.
//
// That wiring undoes the tree's own bit reversal on the way in, so `dout[j]`
// carries the bit received in UI `bitrev(j)`, the reversal of the STAGES index
// bits, i.e. for 32 bits `dout[0]` is UI 0, `dout[1]` is UI 16, `dout[2]` is
// UI 8, and so on rather than `dout[t]` being UI `t`. A `tree_ser` on the far
// end reverses the same way, so the two cancel bit for bit once the word
// boundaries line up; against anything else the digital side has to undo it
// (that is what the per-lane shuffler behind the tile is for).
//
// One consequence: which `2**STAGES` UI of the stream land in one word is set
// by where this tile's divider chain came out of reset, and because the tree
// reverses bit order an offset capture is NOT a rotation of `dout`. Word
// alignment has to be fixed on the serial side, before the tree.
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

            // `dout_int[0]` is the bit this level captured first, so the
            // earlier of the two streams fills the low half of the bus.
            assign dout[2**(STAGES-1)-1:0] = dout0;
            assign dout[2**STAGES-1:2**(STAGES-1)] = dout1;

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

    // The bit `dout[j]` carries came in on UI `treeBitOrder(j)`: the reversal
    // of the STAGES index bits. See `tree_des`.
    function automatic integer treeBitOrder(integer t);
        treeBitOrder = 0;
        for (int b = 0; b < STAGES; b++) begin
            treeBitOrder |= ((t >> b) & 1) << (STAGES - 1 - b);
        end
    endfunction

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

    // The same window in time order: `dout_time[t]` is the bit that came in on
    // UI `t`. `treeBitOrder` is its own inverse, so applying it to the index
    // undoes the tree. The alignment search below slides the captured window
    // by whole UI, and that is a rotation only in time order.
    logic [2**STAGES-1:0] dout_time;
    always_comb begin
        for (int t = 0; t < 2**STAGES; t++) begin
            dout_time[t] = dout[treeBitOrder(t)];
        end
    end

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
        @(posedge |dout_time);
        @(posedge desclk[STAGES-1]);
        for (integer i = 2**STAGES - 1; i >= 0; i--) begin
            if (dout_time[i]) shift = i + 1;
        end
        prev = dout_time >> shift;

        for (integer i = 0; i < CYCLES; i++) begin
            @(posedge desclk[STAGES-1]);
            next = prev | (dout_time << (2**STAGES - shift));
            prev = dout_time >> shift;
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
