package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import chisel3.experimental.noPrefix
import chisel3.experimental.BundleLiterals._

class RxLaneCtlIO extends Bundle {
  val zen = Bool()
  val zctl = UInt(5.W)
  val afe = new RxAfeIO
  val vref_sel = UInt(7.W)
}

class RxDataLaneIO extends Bundle {
  val din = Input(Bool())
  val dout = Output(Bits(32.W))
  val divclk = Output(Bool())
  val clk = Input(Clock())
  val resetb = Input(Bool())
  val ctl = Input(new RxLaneCtlIO)
}

class RxDataLane(sim: Boolean = false) extends RawModule {
  val io = IO(new RxDataLaneIO)

  val verilogBlackBox = Module(new VerilogRxDataLane(sim))
  verilogBlackBox.io.din := io.din

  io.dout := Cat(
    verilogBlackBox.io.dout_31,
    verilogBlackBox.io.dout_30,
    verilogBlackBox.io.dout_29,
    verilogBlackBox.io.dout_28,
    verilogBlackBox.io.dout_27,
    verilogBlackBox.io.dout_26,
    verilogBlackBox.io.dout_25,
    verilogBlackBox.io.dout_24,
    verilogBlackBox.io.dout_23,
    verilogBlackBox.io.dout_22,
    verilogBlackBox.io.dout_21,
    verilogBlackBox.io.dout_20,
    verilogBlackBox.io.dout_19,
    verilogBlackBox.io.dout_18,
    verilogBlackBox.io.dout_17,
    verilogBlackBox.io.dout_16,
    verilogBlackBox.io.dout_15,
    verilogBlackBox.io.dout_14,
    verilogBlackBox.io.dout_13,
    verilogBlackBox.io.dout_12,
    verilogBlackBox.io.dout_11,
    verilogBlackBox.io.dout_10,
    verilogBlackBox.io.dout_9,
    verilogBlackBox.io.dout_8,
    verilogBlackBox.io.dout_7,
    verilogBlackBox.io.dout_6,
    verilogBlackBox.io.dout_5,
    verilogBlackBox.io.dout_4,
    verilogBlackBox.io.dout_3,
    verilogBlackBox.io.dout_2,
    verilogBlackBox.io.dout_1,
    verilogBlackBox.io.dout_0
  ).asTypeOf(io.dout)
  io.divclk := verilogBlackBox.io.divclk

  verilogBlackBox.io.clk := io.clk
  verilogBlackBox.io.rstb := io.resetb

  verilogBlackBox.io.zen := io.ctl.zen
  val zctlTherm = Wire(UInt(32.W))
  zctlTherm := (1.U << io.ctl.zctl) - 1.U
  verilogBlackBox.io.zctl_0 := zctlTherm(0)
  verilogBlackBox.io.zctl_1 := zctlTherm(1)
  verilogBlackBox.io.zctl_2 := zctlTherm(2)
  verilogBlackBox.io.zctl_3 := zctlTherm(3)
  verilogBlackBox.io.zctl_4 := zctlTherm(4)
  verilogBlackBox.io.zctl_5 := zctlTherm(5)
  verilogBlackBox.io.zctl_6 := zctlTherm(6)
  verilogBlackBox.io.zctl_7 := zctlTherm(7)
  verilogBlackBox.io.zctl_8 := zctlTherm(8)
  verilogBlackBox.io.zctl_9 := zctlTherm(9)
  verilogBlackBox.io.zctl_10 := zctlTherm(10)
  verilogBlackBox.io.zctl_11 := zctlTherm(11)
  verilogBlackBox.io.zctl_12 := zctlTherm(12)
  verilogBlackBox.io.zctl_13 := zctlTherm(13)
  verilogBlackBox.io.zctl_14 := zctlTherm(14)
  verilogBlackBox.io.zctl_15 := zctlTherm(15)
  verilogBlackBox.io.zctl_16 := zctlTherm(16)
  verilogBlackBox.io.zctl_17 := zctlTherm(17)
  verilogBlackBox.io.zctl_18 := zctlTherm(18)
  verilogBlackBox.io.zctl_19 := zctlTherm(19)

  verilogBlackBox.io.a_en := io.ctl.afe.aEn
  verilogBlackBox.io.a_pc := io.ctl.afe.aPc
  verilogBlackBox.io.b_en := io.ctl.afe.bEn
  verilogBlackBox.io.b_pc := io.ctl.afe.bPc
  verilogBlackBox.io.sel_a := io.ctl.afe.selA

  verilogBlackBox.io.vref_sel_0 := io.ctl.vref_sel(0)
  verilogBlackBox.io.vref_sel_1 := io.ctl.vref_sel(1)
  verilogBlackBox.io.vref_sel_2 := io.ctl.vref_sel(2)
  verilogBlackBox.io.vref_sel_3 := io.ctl.vref_sel(3)
  verilogBlackBox.io.vref_sel_4 := io.ctl.vref_sel(4)
  verilogBlackBox.io.vref_sel_5 := io.ctl.vref_sel(5)
  verilogBlackBox.io.vref_sel_6 := io.ctl.vref_sel(6)
}

class VerilogRxDataLaneIO extends Bundle {
  val din = Input(Bool())
  val dout_0 = Output(Bool())
  val dout_1 = Output(Bool())
  val dout_2 = Output(Bool())
  val dout_3 = Output(Bool())
  val dout_4 = Output(Bool())
  val dout_5 = Output(Bool())
  val dout_6 = Output(Bool())
  val dout_7 = Output(Bool())
  val dout_8 = Output(Bool())
  val dout_9 = Output(Bool())
  val dout_10 = Output(Bool())
  val dout_11 = Output(Bool())
  val dout_12 = Output(Bool())
  val dout_13 = Output(Bool())
  val dout_14 = Output(Bool())
  val dout_15 = Output(Bool())
  val dout_16 = Output(Bool())
  val dout_17 = Output(Bool())
  val dout_18 = Output(Bool())
  val dout_19 = Output(Bool())
  val dout_20 = Output(Bool())
  val dout_21 = Output(Bool())
  val dout_22 = Output(Bool())
  val dout_23 = Output(Bool())
  val dout_24 = Output(Bool())
  val dout_25 = Output(Bool())
  val dout_26 = Output(Bool())
  val dout_27 = Output(Bool())
  val dout_28 = Output(Bool())
  val dout_29 = Output(Bool())
  val dout_30 = Output(Bool())
  val dout_31 = Output(Bool())
  val divclk = Output(Bool())
  val clk = Input(Clock())
  val rstb = Input(Bool())
  val zen = Input(Bool())
  val zctl_0 = Input(Bool())
  val zctl_1 = Input(Bool())
  val zctl_2 = Input(Bool())
  val zctl_3 = Input(Bool())
  val zctl_4 = Input(Bool())
  val zctl_5 = Input(Bool())
  val zctl_6 = Input(Bool())
  val zctl_7 = Input(Bool())
  val zctl_8 = Input(Bool())
  val zctl_9 = Input(Bool())
  val zctl_10 = Input(Bool())
  val zctl_11 = Input(Bool())
  val zctl_12 = Input(Bool())
  val zctl_13 = Input(Bool())
  val zctl_14 = Input(Bool())
  val zctl_15 = Input(Bool())
  val zctl_16 = Input(Bool())
  val zctl_17 = Input(Bool())
  val zctl_18 = Input(Bool())
  val zctl_19 = Input(Bool())
  val a_en = Input(Bool())
  val a_pc = Input(Bool())
  val b_en = Input(Bool())
  val b_pc = Input(Bool())
  val sel_a = Input(Bool())
  val vref_sel_0 = Input(Bool())
  val vref_sel_1 = Input(Bool())
  val vref_sel_2 = Input(Bool())
  val vref_sel_3 = Input(Bool())
  val vref_sel_4 = Input(Bool())
  val vref_sel_5 = Input(Bool())
  val vref_sel_6 = Input(Bool())
}

class VerilogRxDataLane(sim: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new VerilogRxDataLaneIO)

  override val desiredName = "rx_data_lane"

  if (sim) {
    setInline(
      "rx_data_lane.v",
      """
module rx_data_lane (
   inout vdd,
   inout vss,
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
  reg rstbSync;
  always @(negedge rstb) begin
    rstbSync <= rstb;
  end
  always @(posedge clk) begin
    rstbSync <= rstb;
  end
  reg [2:0] ctr;
  reg divClock;
  reg [31:0] shiftReg;
  reg [31:0] outputReg;
  always @(posedge clk) begin
    if (rstbSync) begin
      ctr <= ctr + 1'b1;
      shiftReg <= (shiftReg << 1'b1) | din;
      if (ctr == 3'b0) begin
        divClock <= ~divClock;
      end
    end else begin
      divClock <= 1'b0;
      ctr <= 3'b1;
      shiftReg <= 32'b0;
    end
  end
  always @(negedge clk) begin
    shiftReg <= (shiftReg << 1'b1) | din;
  end
  always @(posedge divClock) begin
    outputReg <= shiftReg;
  end
  assign dout_0  = outputReg[31];
  assign dout_1  = outputReg[30];
  assign dout_2  = outputReg[29];
  assign dout_3  = outputReg[28];
  assign dout_4  = outputReg[27];
  assign dout_5  = outputReg[26];
  assign dout_6  = outputReg[25];
  assign dout_7  = outputReg[24];
  assign dout_8  = outputReg[23];
  assign dout_9  = outputReg[22];
  assign dout_10 = outputReg[21];
  assign dout_11 = outputReg[20];
  assign dout_12 = outputReg[19];
  assign dout_13 = outputReg[18];
  assign dout_14 = outputReg[17];
  assign dout_15 = outputReg[16];
  assign dout_16 = outputReg[15];
  assign dout_17 = outputReg[14];
  assign dout_18 = outputReg[13];
  assign dout_19 = outputReg[12];
  assign dout_20 = outputReg[11];
  assign dout_21 = outputReg[10];
  assign dout_22 = outputReg[9];
  assign dout_23 = outputReg[8];
  assign dout_24 = outputReg[7];
  assign dout_25 = outputReg[6];
  assign dout_26 = outputReg[5];
  assign dout_27 = outputReg[4];
  assign dout_28 = outputReg[3];
  assign dout_29 = outputReg[2];
  assign dout_30 = outputReg[1];
  assign dout_31 = outputReg[0];
  assign divclk = divClock;
endmodule
      """
    )
  }
}

class RxClkLaneIO extends Bundle {
  val clkin = Input(Bool())
  val clkout = Output(Bool())
  val ctl = Input(new RxLaneCtlIO)
}

class RxClkLane(sim: Boolean = false) extends RawModule {
  val io = IO(new RxClkLaneIO)

  val verilogBlackBox = Module(new VerilogRxClkLane(sim))
  verilogBlackBox.io.clkin := io.clkin
  io.clkout := verilogBlackBox.io.clkout

  verilogBlackBox.io.zen := io.ctl.zen
  val zctlTherm = Wire(UInt(32.W))
  zctlTherm := (1.U << io.ctl.zctl) - 1.U
  verilogBlackBox.io.zctl_0 := zctlTherm(0)
  verilogBlackBox.io.zctl_1 := zctlTherm(1)
  verilogBlackBox.io.zctl_2 := zctlTherm(2)
  verilogBlackBox.io.zctl_3 := zctlTherm(3)
  verilogBlackBox.io.zctl_4 := zctlTherm(4)
  verilogBlackBox.io.zctl_5 := zctlTherm(5)
  verilogBlackBox.io.zctl_6 := zctlTherm(6)
  verilogBlackBox.io.zctl_7 := zctlTherm(7)
  verilogBlackBox.io.zctl_8 := zctlTherm(8)
  verilogBlackBox.io.zctl_9 := zctlTherm(9)
  verilogBlackBox.io.zctl_10 := zctlTherm(10)
  verilogBlackBox.io.zctl_11 := zctlTherm(11)
  verilogBlackBox.io.zctl_12 := zctlTherm(12)
  verilogBlackBox.io.zctl_13 := zctlTherm(13)
  verilogBlackBox.io.zctl_14 := zctlTherm(14)
  verilogBlackBox.io.zctl_15 := zctlTherm(15)
  verilogBlackBox.io.zctl_16 := zctlTherm(16)
  verilogBlackBox.io.zctl_17 := zctlTherm(17)
  verilogBlackBox.io.zctl_18 := zctlTherm(18)
  verilogBlackBox.io.zctl_19 := zctlTherm(19)

  verilogBlackBox.io.a_en := io.ctl.afe.aEn
  verilogBlackBox.io.a_pc := io.ctl.afe.aPc
  verilogBlackBox.io.b_en := io.ctl.afe.bEn
  verilogBlackBox.io.b_pc := io.ctl.afe.bPc
  verilogBlackBox.io.sel_a := io.ctl.afe.selA

  verilogBlackBox.io.vref_sel_0 := io.ctl.vref_sel(0)
  verilogBlackBox.io.vref_sel_1 := io.ctl.vref_sel(1)
  verilogBlackBox.io.vref_sel_2 := io.ctl.vref_sel(2)
  verilogBlackBox.io.vref_sel_3 := io.ctl.vref_sel(3)
  verilogBlackBox.io.vref_sel_4 := io.ctl.vref_sel(4)
  verilogBlackBox.io.vref_sel_5 := io.ctl.vref_sel(5)
  verilogBlackBox.io.vref_sel_6 := io.ctl.vref_sel(6)
}

class VerilogRxClkLaneIO extends Bundle {
  val clkin = Input(Bool())
  val clkout = Output(Bool())
  val zen = Input(Bool())
  val zctl_0 = Input(Bool())
  val zctl_1 = Input(Bool())
  val zctl_2 = Input(Bool())
  val zctl_3 = Input(Bool())
  val zctl_4 = Input(Bool())
  val zctl_5 = Input(Bool())
  val zctl_6 = Input(Bool())
  val zctl_7 = Input(Bool())
  val zctl_8 = Input(Bool())
  val zctl_9 = Input(Bool())
  val zctl_10 = Input(Bool())
  val zctl_11 = Input(Bool())
  val zctl_12 = Input(Bool())
  val zctl_13 = Input(Bool())
  val zctl_14 = Input(Bool())
  val zctl_15 = Input(Bool())
  val zctl_16 = Input(Bool())
  val zctl_17 = Input(Bool())
  val zctl_18 = Input(Bool())
  val zctl_19 = Input(Bool())
  val a_en = Input(Bool())
  val a_pc = Input(Bool())
  val b_en = Input(Bool())
  val b_pc = Input(Bool())
  val sel_a = Input(Bool())
  val vref_sel_0 = Input(Bool())
  val vref_sel_1 = Input(Bool())
  val vref_sel_2 = Input(Bool())
  val vref_sel_3 = Input(Bool())
  val vref_sel_4 = Input(Bool())
  val vref_sel_5 = Input(Bool())
  val vref_sel_6 = Input(Bool())
}

class VerilogRxClkLane(sim: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new VerilogRxClkLaneIO)

  override val desiredName = "rx_clock_lane"

  if (sim) {
    setInline(
      "rx_clock_lane.v",
      """
module rx_clock_lane (
   inout vdd,
   inout vss,
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
  assign clkout = clkin;
endmodule
      """
    );
  }
}

object RxAfeCtlState extends ChiselEnum {
  val sA, sAbInit, sAbSel, sB, sBaInit, sBaSel = Value
}

class RxAfeIO extends Bundle {
  val aEn = Bool()
  val aPc = Bool()
  val bEn = Bool()
  val bPc = Bool()
  val selA = Bool()
}

class RxAfeCtl extends Module {
  val io = IO(new Bundle {
    val bypass = Input(Bool())
    val afeBypass = Input(new RxAfeIO)
    val opCycles = Input(UInt(16.W))
    val overlapCycles = Input(UInt(16.W))
    val afe = Output(new RxAfeIO)
  })

  val state = RegInit(RxAfeCtlState.sA)
  val ctr = RegInit(0.U(17.W))
  val ctrinc = Wire(UInt(17.W))
  ctrinc := ctr + 1.U

  io.afe := (new RxAfeIO).Lit(
    _.aEn -> false.B,
    _.aPc -> true.B,
    _.bEn -> false.B,
    _.bPc -> true.B,
    _.selA -> true.B
  )
  when(reset.asBool) {
    io.afe := (new RxAfeIO).Lit(
      _.aEn -> false.B,
      _.aPc -> true.B,
      _.bEn -> false.B,
      _.bPc -> true.B,
      _.selA -> true.B
    )
  }.otherwise {
    when(io.bypass) {
      io.afe := io.afeBypass
    }.otherwise {
      switch(state) {
        is(RxAfeCtlState.sA) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> false.B,
            _.bPc -> true.B,
            _.selA -> true.B
          )
        }
        is(RxAfeCtlState.sAbInit) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> true.B
          )
        }
        is(RxAfeCtlState.sAbSel) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> false.B
          )
        }
        is(RxAfeCtlState.sB) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> false.B,
            _.aPc -> true.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> false.B
          )
        }
        is(RxAfeCtlState.sBaInit) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> false.B
          )
        }
        is(RxAfeCtlState.sBaSel) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> true.B
          )
        }
      }
    }
  }
  ctr := ctrinc
  switch(state) {
    is(RxAfeCtlState.sA) {
      when(ctrinc === io.opCycles) {
        state := RxAfeCtlState.sAbInit
        ctr := 0.U
      }
    }
    is(RxAfeCtlState.sAbInit) {
      when(ctrinc === io.overlapCycles) {
        state := RxAfeCtlState.sAbSel
        ctr := 0.U
      }
    }
    is(RxAfeCtlState.sAbSel) {
      state := RxAfeCtlState.sB
      ctr := 0.U
    }
    is(RxAfeCtlState.sB) {
      when(ctrinc === io.opCycles) {
        state := RxAfeCtlState.sBaInit
        ctr := 0.U
      }
    }
    is(RxAfeCtlState.sBaInit) {
      when(ctrinc === io.overlapCycles) {
        state := RxAfeCtlState.sBaSel
        ctr := 0.U
      }
    }
    is(RxAfeCtlState.sBaSel) {
      state := RxAfeCtlState.sA
      ctr := 0.U
    }
  }
}
