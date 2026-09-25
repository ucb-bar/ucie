package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._
import chisel3.experimental.noPrefix

object RxDataLane {

  /** Taps on the RX lane's local delay line, thermometer coded. Same count as
    * the TX tile's so the two trims are programmed the same way.
    */
  val DelayTaps = 32
}

class RxAfeIO extends Bundle {
  val aEn = Bool()
  val aPc = Bool()
  val bEn = Bool()
  val bPc = Bool()
  val selA = Bool()
}

class RxLaneCtlIO extends Bundle {

  /** Delay taps on the sampling clock, thermometer coded as on the TX tile. A
    * one enables its tap. This is the lane's local trim; it moves where in the
    * UI this lane samples without touching any other lane.
    */
  val Dctrl = UInt(RxDataLane.DelayTaps.W)
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
  val resetb = Input(AsyncReset())
  val ctl = Input(new RxLaneCtlIO)
}

class RxDataLane(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new RxDataLaneIO)

  val verilogBlackBox = Module(new VerilogRxDataLane)
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

  verilogBlackBox.io.Dctrl_0 := io.ctl.Dctrl(0)
  verilogBlackBox.io.Dctrl_1 := io.ctl.Dctrl(1)
  verilogBlackBox.io.Dctrl_2 := io.ctl.Dctrl(2)
  verilogBlackBox.io.Dctrl_3 := io.ctl.Dctrl(3)
  verilogBlackBox.io.Dctrl_4 := io.ctl.Dctrl(4)
  verilogBlackBox.io.Dctrl_5 := io.ctl.Dctrl(5)
  verilogBlackBox.io.Dctrl_6 := io.ctl.Dctrl(6)
  verilogBlackBox.io.Dctrl_7 := io.ctl.Dctrl(7)
  verilogBlackBox.io.Dctrl_8 := io.ctl.Dctrl(8)
  verilogBlackBox.io.Dctrl_9 := io.ctl.Dctrl(9)
  verilogBlackBox.io.Dctrl_10 := io.ctl.Dctrl(10)
  verilogBlackBox.io.Dctrl_11 := io.ctl.Dctrl(11)
  verilogBlackBox.io.Dctrl_12 := io.ctl.Dctrl(12)
  verilogBlackBox.io.Dctrl_13 := io.ctl.Dctrl(13)
  verilogBlackBox.io.Dctrl_14 := io.ctl.Dctrl(14)
  verilogBlackBox.io.Dctrl_15 := io.ctl.Dctrl(15)
  verilogBlackBox.io.Dctrl_16 := io.ctl.Dctrl(16)
  verilogBlackBox.io.Dctrl_17 := io.ctl.Dctrl(17)
  verilogBlackBox.io.Dctrl_18 := io.ctl.Dctrl(18)
  verilogBlackBox.io.Dctrl_19 := io.ctl.Dctrl(19)
  verilogBlackBox.io.Dctrl_20 := io.ctl.Dctrl(20)
  verilogBlackBox.io.Dctrl_21 := io.ctl.Dctrl(21)
  verilogBlackBox.io.Dctrl_22 := io.ctl.Dctrl(22)
  verilogBlackBox.io.Dctrl_23 := io.ctl.Dctrl(23)
  verilogBlackBox.io.Dctrl_24 := io.ctl.Dctrl(24)
  verilogBlackBox.io.Dctrl_25 := io.ctl.Dctrl(25)
  verilogBlackBox.io.Dctrl_26 := io.ctl.Dctrl(26)
  verilogBlackBox.io.Dctrl_27 := io.ctl.Dctrl(27)
  verilogBlackBox.io.Dctrl_28 := io.ctl.Dctrl(28)
  verilogBlackBox.io.Dctrl_29 := io.ctl.Dctrl(29)
  verilogBlackBox.io.Dctrl_30 := io.ctl.Dctrl(30)
  verilogBlackBox.io.Dctrl_31 := io.ctl.Dctrl(31)
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

class VerilogRxDataLane(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val din = Input(Bool())
    val Dctrl_0 = Input(Bool())
    val Dctrl_1 = Input(Bool())
    val Dctrl_2 = Input(Bool())
    val Dctrl_3 = Input(Bool())
    val Dctrl_4 = Input(Bool())
    val Dctrl_5 = Input(Bool())
    val Dctrl_6 = Input(Bool())
    val Dctrl_7 = Input(Bool())
    val Dctrl_8 = Input(Bool())
    val Dctrl_9 = Input(Bool())
    val Dctrl_10 = Input(Bool())
    val Dctrl_11 = Input(Bool())
    val Dctrl_12 = Input(Bool())
    val Dctrl_13 = Input(Bool())
    val Dctrl_14 = Input(Bool())
    val Dctrl_15 = Input(Bool())
    val Dctrl_16 = Input(Bool())
    val Dctrl_17 = Input(Bool())
    val Dctrl_18 = Input(Bool())
    val Dctrl_19 = Input(Bool())
    val Dctrl_20 = Input(Bool())
    val Dctrl_21 = Input(Bool())
    val Dctrl_22 = Input(Bool())
    val Dctrl_23 = Input(Bool())
    val Dctrl_24 = Input(Bool())
    val Dctrl_25 = Input(Bool())
    val Dctrl_26 = Input(Bool())
    val Dctrl_27 = Input(Bool())
    val Dctrl_28 = Input(Bool())
    val Dctrl_29 = Input(Bool())
    val Dctrl_30 = Input(Bool())
    val Dctrl_31 = Input(Bool())
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
    val rstb = Input(AsyncReset())
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
  })

  override val desiredName = "rx_data_lane"

  if (includeDefaultModels) {
    addResource("/vsrc/rx_data_lane.v")
  }
}

class RxClkLaneIO extends Bundle {
  val clkin = Input(Clock())
  val clkout = Output(Clock())
  val ctl = Input(new RxLaneCtlIO)
}

class RxClkLane(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new RxClkLaneIO)

  val verilogBlackBox = Module(new VerilogRxClkLane)
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

class VerilogRxClkLane(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clkin = Input(Clock())
    val clkout = Output(Clock())
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
  })

  override val desiredName = "rx_clock_lane"

  if (includeDefaultModels) {
    addResource("/vsrc/rx_clock_lane.v")
  }
}
