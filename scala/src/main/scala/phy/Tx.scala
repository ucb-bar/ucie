package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._

class DriverControlIO extends Bundle {
  val pu_ctl = UInt(6.W)
  val pd_ctl = UInt(6.W)
  val en = Bool()
  val en_b = Bool()
}

// DLL + PI + DCC control IO.
class TxSkewControlIO extends Bundle {
  val dll_en = Bool()
  val ocl = Bool()
  val delay = Bits(5.W)
  val mux_en = Bits(8.W)
  val band_ctrl = Bits(2.W)
  val mix_en = UInt(5.W)
  val nen_out = UInt(5.W)
  val pen_out = UInt(5.W)
}

class TxLaneCtlIO extends Bundle {
  val driver = new DriverControlIO
  val skew = new TxSkewControlIO
}

class TxLaneIO extends Bundle {
  val dll_reset = Input(Bool())
  val dll_resetb = Input(Bool())
  val ser_resetb = Input(Bool())
  val clkp = Input(Bool())
  val clkn = Input(Bool())
  val din = Input(Bits(32.W))
  val dout = Output(Bool())
  val divclk = Output(Bool())
  val dll_code = Output(UInt(5.W))
  val ctl = Input(new TxLaneCtlIO)
}

class TxLane(sim: Boolean = false) extends RawModule {
  val io = IO(new TxLaneIO)

  val verilogBlackBox = Module(new VerilogTxLane(sim))
  verilogBlackBox.io.dll_reset := io.dll_reset
  verilogBlackBox.io.dll_resetb := io.dll_resetb
  verilogBlackBox.io.ser_resetb := io.ser_resetb
  verilogBlackBox.io.clkp := io.clkp
  verilogBlackBox.io.clkn := io.clkn

  verilogBlackBox.io.din_0 := io.din(0)
  verilogBlackBox.io.din_1 := io.din(1)
  verilogBlackBox.io.din_2 := io.din(2)
  verilogBlackBox.io.din_3 := io.din(3)
  verilogBlackBox.io.din_4 := io.din(4)
  verilogBlackBox.io.din_5 := io.din(5)
  verilogBlackBox.io.din_6 := io.din(6)
  verilogBlackBox.io.din_7 := io.din(7)
  verilogBlackBox.io.din_8 := io.din(8)
  verilogBlackBox.io.din_9 := io.din(9)
  verilogBlackBox.io.din_10 := io.din(10)
  verilogBlackBox.io.din_11 := io.din(11)
  verilogBlackBox.io.din_12 := io.din(12)
  verilogBlackBox.io.din_13 := io.din(13)
  verilogBlackBox.io.din_14 := io.din(14)
  verilogBlackBox.io.din_15 := io.din(15)
  verilogBlackBox.io.din_16 := io.din(16)
  verilogBlackBox.io.din_17 := io.din(17)
  verilogBlackBox.io.din_18 := io.din(18)
  verilogBlackBox.io.din_19 := io.din(19)
  verilogBlackBox.io.din_20 := io.din(20)
  verilogBlackBox.io.din_21 := io.din(21)
  verilogBlackBox.io.din_22 := io.din(22)
  verilogBlackBox.io.din_23 := io.din(23)
  verilogBlackBox.io.din_24 := io.din(24)
  verilogBlackBox.io.din_25 := io.din(25)
  verilogBlackBox.io.din_26 := io.din(26)
  verilogBlackBox.io.din_27 := io.din(27)
  verilogBlackBox.io.din_28 := io.din(28)
  verilogBlackBox.io.din_29 := io.din(29)
  verilogBlackBox.io.din_30 := io.din(30)
  verilogBlackBox.io.din_31 := io.din(31)

  io.dout := verilogBlackBox.io.dout
  io.divclk := verilogBlackBox.io.divclk

  val puCtlTherm = Wire(UInt(64.W))
  puCtlTherm := (1.U << io.ctl.driver.pu_ctl) - 1.U
  val pdCtlbTherm = Wire(UInt(64.W))
  pdCtlbTherm := ~((1.U << io.ctl.driver.pd_ctl) - 1.U)
  verilogBlackBox.io.pu_ctl_0 := puCtlTherm(0)
  verilogBlackBox.io.pu_ctl_1 := puCtlTherm(1)
  verilogBlackBox.io.pu_ctl_2 := puCtlTherm(2)
  verilogBlackBox.io.pu_ctl_3 := puCtlTherm(3)
  verilogBlackBox.io.pu_ctl_4 := puCtlTherm(4)
  verilogBlackBox.io.pu_ctl_5 := puCtlTherm(5)
  verilogBlackBox.io.pu_ctl_6 := puCtlTherm(6)
  verilogBlackBox.io.pu_ctl_7 := puCtlTherm(7)
  verilogBlackBox.io.pu_ctl_8 := puCtlTherm(8)
  verilogBlackBox.io.pu_ctl_9 := puCtlTherm(9)
  verilogBlackBox.io.pu_ctl_10 := puCtlTherm(10)
  verilogBlackBox.io.pu_ctl_11 := puCtlTherm(11)
  verilogBlackBox.io.pu_ctl_12 := puCtlTherm(12)
  verilogBlackBox.io.pu_ctl_13 := puCtlTherm(13)
  verilogBlackBox.io.pu_ctl_14 := puCtlTherm(14)
  verilogBlackBox.io.pu_ctl_15 := puCtlTherm(15)
  verilogBlackBox.io.pu_ctl_16 := puCtlTherm(16)
  verilogBlackBox.io.pu_ctl_17 := puCtlTherm(17)
  verilogBlackBox.io.pu_ctl_18 := puCtlTherm(18)
  verilogBlackBox.io.pu_ctl_19 := puCtlTherm(19)
  verilogBlackBox.io.pu_ctl_20 := puCtlTherm(20)
  verilogBlackBox.io.pu_ctl_21 := puCtlTherm(21)
  verilogBlackBox.io.pu_ctl_22 := puCtlTherm(22)
  verilogBlackBox.io.pu_ctl_23 := puCtlTherm(23)
  verilogBlackBox.io.pu_ctl_24 := puCtlTherm(24)
  verilogBlackBox.io.pu_ctl_25 := puCtlTherm(25)
  verilogBlackBox.io.pu_ctl_26 := puCtlTherm(26)
  verilogBlackBox.io.pu_ctl_27 := puCtlTherm(27)
  verilogBlackBox.io.pu_ctl_28 := puCtlTherm(28)
  verilogBlackBox.io.pu_ctl_29 := puCtlTherm(29)
  verilogBlackBox.io.pu_ctl_30 := puCtlTherm(30)
  verilogBlackBox.io.pu_ctl_31 := puCtlTherm(31)
  verilogBlackBox.io.pu_ctl_32 := puCtlTherm(32)
  verilogBlackBox.io.pu_ctl_33 := puCtlTherm(33)
  verilogBlackBox.io.pu_ctl_34 := puCtlTherm(34)
  verilogBlackBox.io.pu_ctl_35 := puCtlTherm(35)
  verilogBlackBox.io.pu_ctl_36 := puCtlTherm(36)
  verilogBlackBox.io.pu_ctl_37 := puCtlTherm(37)
  verilogBlackBox.io.pu_ctl_38 := puCtlTherm(38)
  verilogBlackBox.io.pu_ctl_39 := puCtlTherm(39)
  verilogBlackBox.io.pd_ctlb_0 := pdCtlbTherm(0)
  verilogBlackBox.io.pd_ctlb_1 := pdCtlbTherm(1)
  verilogBlackBox.io.pd_ctlb_2 := pdCtlbTherm(2)
  verilogBlackBox.io.pd_ctlb_3 := pdCtlbTherm(3)
  verilogBlackBox.io.pd_ctlb_4 := pdCtlbTherm(4)
  verilogBlackBox.io.pd_ctlb_5 := pdCtlbTherm(5)
  verilogBlackBox.io.pd_ctlb_6 := pdCtlbTherm(6)
  verilogBlackBox.io.pd_ctlb_7 := pdCtlbTherm(7)
  verilogBlackBox.io.pd_ctlb_8 := pdCtlbTherm(8)
  verilogBlackBox.io.pd_ctlb_9 := pdCtlbTherm(9)
  verilogBlackBox.io.pd_ctlb_10 := pdCtlbTherm(10)
  verilogBlackBox.io.pd_ctlb_11 := pdCtlbTherm(11)
  verilogBlackBox.io.pd_ctlb_12 := pdCtlbTherm(12)
  verilogBlackBox.io.pd_ctlb_13 := pdCtlbTherm(13)
  verilogBlackBox.io.pd_ctlb_14 := pdCtlbTherm(14)
  verilogBlackBox.io.pd_ctlb_15 := pdCtlbTherm(15)
  verilogBlackBox.io.pd_ctlb_16 := pdCtlbTherm(16)
  verilogBlackBox.io.pd_ctlb_17 := pdCtlbTherm(17)
  verilogBlackBox.io.pd_ctlb_18 := pdCtlbTherm(18)
  verilogBlackBox.io.pd_ctlb_19 := pdCtlbTherm(19)
  verilogBlackBox.io.pd_ctlb_20 := pdCtlbTherm(20)
  verilogBlackBox.io.pd_ctlb_21 := pdCtlbTherm(21)
  verilogBlackBox.io.pd_ctlb_22 := pdCtlbTherm(22)
  verilogBlackBox.io.pd_ctlb_23 := pdCtlbTherm(23)
  verilogBlackBox.io.pd_ctlb_24 := pdCtlbTherm(24)
  verilogBlackBox.io.pd_ctlb_25 := pdCtlbTherm(25)
  verilogBlackBox.io.pd_ctlb_26 := pdCtlbTherm(26)
  verilogBlackBox.io.pd_ctlb_27 := pdCtlbTherm(27)
  verilogBlackBox.io.pd_ctlb_28 := pdCtlbTherm(28)
  verilogBlackBox.io.pd_ctlb_29 := pdCtlbTherm(29)
  verilogBlackBox.io.pd_ctlb_30 := pdCtlbTherm(30)
  verilogBlackBox.io.pd_ctlb_31 := pdCtlbTherm(31)
  verilogBlackBox.io.pd_ctlb_32 := pdCtlbTherm(32)
  verilogBlackBox.io.pd_ctlb_33 := pdCtlbTherm(33)
  verilogBlackBox.io.pd_ctlb_34 := pdCtlbTherm(34)
  verilogBlackBox.io.pd_ctlb_35 := pdCtlbTherm(35)
  verilogBlackBox.io.pd_ctlb_36 := pdCtlbTherm(36)
  verilogBlackBox.io.pd_ctlb_37 := pdCtlbTherm(37)
  verilogBlackBox.io.pd_ctlb_38 := pdCtlbTherm(38)
  verilogBlackBox.io.pd_ctlb_39 := pdCtlbTherm(39)

  verilogBlackBox.io.driver_en := io.ctl.driver.en
  verilogBlackBox.io.driver_en_b := io.ctl.driver.en_b

  verilogBlackBox.io.dll_en := io.ctl.skew.dll_en
  verilogBlackBox.io.ocl := io.ctl.skew.ocl
  verilogBlackBox.io.delay_0 := io.ctl.skew.delay(0)
  verilogBlackBox.io.delay_1 := io.ctl.skew.delay(1)
  verilogBlackBox.io.delay_2 := io.ctl.skew.delay(2)
  verilogBlackBox.io.delay_3 := io.ctl.skew.delay(3)
  verilogBlackBox.io.delay_4 := io.ctl.skew.delay(4)
  verilogBlackBox.io.delayb_0 := !io.ctl.skew.delay(0)
  verilogBlackBox.io.delayb_1 := !io.ctl.skew.delay(1)
  verilogBlackBox.io.delayb_2 := !io.ctl.skew.delay(2)
  verilogBlackBox.io.delayb_3 := !io.ctl.skew.delay(3)
  verilogBlackBox.io.delayb_4 := !io.ctl.skew.delay(4)

  verilogBlackBox.io.mux_en_0 := io.ctl.skew.mux_en(0)
  verilogBlackBox.io.mux_en_1 := io.ctl.skew.mux_en(1)
  verilogBlackBox.io.mux_en_2 := io.ctl.skew.mux_en(2)
  verilogBlackBox.io.mux_en_3 := io.ctl.skew.mux_en(3)
  verilogBlackBox.io.mux_en_4 := io.ctl.skew.mux_en(4)
  verilogBlackBox.io.mux_en_5 := io.ctl.skew.mux_en(5)
  verilogBlackBox.io.mux_en_6 := io.ctl.skew.mux_en(6)
  verilogBlackBox.io.mux_en_7 := io.ctl.skew.mux_en(7)
  verilogBlackBox.io.mux_enb_0 := !io.ctl.skew.mux_en(0)
  verilogBlackBox.io.mux_enb_1 := !io.ctl.skew.mux_en(1)
  verilogBlackBox.io.mux_enb_2 := !io.ctl.skew.mux_en(2)
  verilogBlackBox.io.mux_enb_3 := !io.ctl.skew.mux_en(3)
  verilogBlackBox.io.mux_enb_4 := !io.ctl.skew.mux_en(4)
  verilogBlackBox.io.mux_enb_5 := !io.ctl.skew.mux_en(5)
  verilogBlackBox.io.mux_enb_6 := !io.ctl.skew.mux_en(6)
  verilogBlackBox.io.mux_enb_7 := !io.ctl.skew.mux_en(7)

  verilogBlackBox.io.band_ctrl_0 := io.ctl.skew.band_ctrl(0)
  verilogBlackBox.io.band_ctrl_1 := io.ctl.skew.band_ctrl(1)
  verilogBlackBox.io.band_ctrlb_0 := !io.ctl.skew.band_ctrl(0)
  verilogBlackBox.io.band_ctrlb_1 := !io.ctl.skew.band_ctrl(1)

  val mixEnTherm = Wire(UInt(32.W))
  mixEnTherm := (1.U << io.ctl.skew.mix_en) - 1.U
  val mixEnbTherm = Wire(UInt(32.W))
  mixEnbTherm := ~mixEnTherm
  verilogBlackBox.io.mix_en_0 := mixEnTherm(0)
  verilogBlackBox.io.mix_en_1 := mixEnTherm(1)
  verilogBlackBox.io.mix_en_2 := mixEnTherm(2)
  verilogBlackBox.io.mix_en_3 := mixEnTherm(3)
  verilogBlackBox.io.mix_en_4 := mixEnTherm(4)
  verilogBlackBox.io.mix_en_5 := mixEnTherm(5)
  verilogBlackBox.io.mix_en_6 := mixEnTherm(6)
  verilogBlackBox.io.mix_en_7 := mixEnTherm(7)
  verilogBlackBox.io.mix_en_8 := mixEnTherm(8)
  verilogBlackBox.io.mix_en_9 := mixEnTherm(9)
  verilogBlackBox.io.mix_en_10 := mixEnTherm(10)
  verilogBlackBox.io.mix_en_11 := mixEnTherm(11)
  verilogBlackBox.io.mix_en_12 := mixEnTherm(12)
  verilogBlackBox.io.mix_en_13 := mixEnTherm(13)
  verilogBlackBox.io.mix_en_14 := mixEnTherm(14)
  verilogBlackBox.io.mix_en_15 := mixEnTherm(15)

  verilogBlackBox.io.mix_enb_0 := mixEnbTherm(0)
  verilogBlackBox.io.mix_enb_1 := mixEnbTherm(1)
  verilogBlackBox.io.mix_enb_2 := mixEnbTherm(2)
  verilogBlackBox.io.mix_enb_3 := mixEnbTherm(3)
  verilogBlackBox.io.mix_enb_4 := mixEnbTherm(4)
  verilogBlackBox.io.mix_enb_5 := mixEnbTherm(5)
  verilogBlackBox.io.mix_enb_6 := mixEnbTherm(6)
  verilogBlackBox.io.mix_enb_7 := mixEnbTherm(7)
  verilogBlackBox.io.mix_enb_8 := mixEnbTherm(8)
  verilogBlackBox.io.mix_enb_9 := mixEnbTherm(9)
  verilogBlackBox.io.mix_enb_10 := mixEnbTherm(10)
  verilogBlackBox.io.mix_enb_11 := mixEnbTherm(11)
  verilogBlackBox.io.mix_enb_12 := mixEnbTherm(12)
  verilogBlackBox.io.mix_enb_13 := mixEnbTherm(13)
  verilogBlackBox.io.mix_enb_14 := mixEnbTherm(14)
  verilogBlackBox.io.mix_enb_15 := mixEnbTherm(15)

  verilogBlackBox.io.nen_out_0 := io.ctl.skew.nen_out(0)
  verilogBlackBox.io.nen_out_1 := io.ctl.skew.nen_out(1)
  verilogBlackBox.io.nen_out_2 := io.ctl.skew.nen_out(2)
  verilogBlackBox.io.nen_out_3 := io.ctl.skew.nen_out(3)
  verilogBlackBox.io.nen_out_4 := io.ctl.skew.nen_out(4)
  verilogBlackBox.io.nen_outb_0 := !io.ctl.skew.nen_out(0)
  verilogBlackBox.io.nen_outb_1 := !io.ctl.skew.nen_out(1)
  verilogBlackBox.io.nen_outb_2 := !io.ctl.skew.nen_out(2)
  verilogBlackBox.io.nen_outb_3 := !io.ctl.skew.nen_out(3)
  verilogBlackBox.io.nen_outb_4 := !io.ctl.skew.nen_out(4)

  verilogBlackBox.io.pen_out_0 := io.ctl.skew.pen_out(0)
  verilogBlackBox.io.pen_out_1 := io.ctl.skew.pen_out(1)
  verilogBlackBox.io.pen_out_2 := io.ctl.skew.pen_out(2)
  verilogBlackBox.io.pen_out_3 := io.ctl.skew.pen_out(3)
  verilogBlackBox.io.pen_out_4 := io.ctl.skew.pen_out(4)
  verilogBlackBox.io.pen_outb_0 := !io.ctl.skew.pen_out(0)
  verilogBlackBox.io.pen_outb_1 := !io.ctl.skew.pen_out(1)
  verilogBlackBox.io.pen_outb_2 := !io.ctl.skew.pen_out(2)
  verilogBlackBox.io.pen_outb_3 := !io.ctl.skew.pen_out(3)
  verilogBlackBox.io.pen_outb_4 := !io.ctl.skew.pen_out(4)

  io.dll_code := Cat(
    verilogBlackBox.io.dll_code_4,
    verilogBlackBox.io.dll_code_3,
    verilogBlackBox.io.dll_code_2,
    verilogBlackBox.io.dll_code_1,
    verilogBlackBox.io.dll_code_0
  )
}

class VerilogTxLane(sim: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new VerilogTxLaneIO)

  override val desiredName = "tx_lane"

  if (sim) {
    setInline(
      "tx_lane.v",
      """
module tx_lane (
  inout vdd,
  inout vss,
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
  wire rstbAsync = ser_resetb;
  reg rstbSync;
  always @(negedge rstbAsync) begin
    rstbSync <= rstbAsync;
  end
  always @(posedge clkp) begin
    rstbSync <= rstbAsync;
  end
  reg [2:0] ctr;
  reg divClock;
  reg [31:0] shiftReg;
  always @(posedge clkp) begin
    if (rstbSync) begin
      ctr <= ctr + 1'b1;
      shiftReg <= shiftReg >> 1'b1;
      if (ctr == 3'b0) begin
        if (~divClock) begin
          shiftReg <= {
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
        end        
        divClock <= ~divClock;
      end
    end else begin
      divClock <= 1'b0;
      ctr <= 3'b1;
      shiftReg <= 32'b0;
    end
  end
  always @(posedge clkn) begin
    shiftReg <= shiftReg >> 1'b1;
  end
  assign divclk = divClock;
  assign dout = (dll_reset || !dll_resetb) ? 1'b0 : shiftReg[0];
  assign dll_code_0 = 1'b0;
  assign dll_code_1 = 1'b0;
  assign dll_code_2 = 1'b0;
  assign dll_code_3 = 1'b0;
  assign dll_code_4 = 1'b0;

endmodule
      """.stripMargin
    )
  }
}

class VerilogTxLaneIO extends Bundle {
  val dll_reset = Input(Bool())
  val dll_resetb = Input(Bool())
  val ser_resetb = Input(Bool())
  val clkp = Input(Bool())
  val clkn = Input(Bool())
  val din_0 = Input(Bool())
  val din_1 = Input(Bool())
  val din_2 = Input(Bool())
  val din_3 = Input(Bool())
  val din_4 = Input(Bool())
  val din_5 = Input(Bool())
  val din_6 = Input(Bool())
  val din_7 = Input(Bool())
  val din_8 = Input(Bool())
  val din_9 = Input(Bool())
  val din_10 = Input(Bool())
  val din_11 = Input(Bool())
  val din_12 = Input(Bool())
  val din_13 = Input(Bool())
  val din_14 = Input(Bool())
  val din_15 = Input(Bool())
  val din_16 = Input(Bool())
  val din_17 = Input(Bool())
  val din_18 = Input(Bool())
  val din_19 = Input(Bool())
  val din_20 = Input(Bool())
  val din_21 = Input(Bool())
  val din_22 = Input(Bool())
  val din_23 = Input(Bool())
  val din_24 = Input(Bool())
  val din_25 = Input(Bool())
  val din_26 = Input(Bool())
  val din_27 = Input(Bool())
  val din_28 = Input(Bool())
  val din_29 = Input(Bool())
  val din_30 = Input(Bool())
  val din_31 = Input(Bool())
  val dout = Output(Bool())
  val divclk = Output(Bool())
  val pu_ctl_0 = Input(Bool())
  val pu_ctl_1 = Input(Bool())
  val pu_ctl_2 = Input(Bool())
  val pu_ctl_3 = Input(Bool())
  val pu_ctl_4 = Input(Bool())
  val pu_ctl_5 = Input(Bool())
  val pu_ctl_6 = Input(Bool())
  val pu_ctl_7 = Input(Bool())
  val pu_ctl_8 = Input(Bool())
  val pu_ctl_9 = Input(Bool())
  val pu_ctl_10 = Input(Bool())
  val pu_ctl_11 = Input(Bool())
  val pu_ctl_12 = Input(Bool())
  val pu_ctl_13 = Input(Bool())
  val pu_ctl_14 = Input(Bool())
  val pu_ctl_15 = Input(Bool())
  val pu_ctl_16 = Input(Bool())
  val pu_ctl_17 = Input(Bool())
  val pu_ctl_18 = Input(Bool())
  val pu_ctl_19 = Input(Bool())
  val pu_ctl_20 = Input(Bool())
  val pu_ctl_21 = Input(Bool())
  val pu_ctl_22 = Input(Bool())
  val pu_ctl_23 = Input(Bool())
  val pu_ctl_24 = Input(Bool())
  val pu_ctl_25 = Input(Bool())
  val pu_ctl_26 = Input(Bool())
  val pu_ctl_27 = Input(Bool())
  val pu_ctl_28 = Input(Bool())
  val pu_ctl_29 = Input(Bool())
  val pu_ctl_30 = Input(Bool())
  val pu_ctl_31 = Input(Bool())
  val pu_ctl_32 = Input(Bool())
  val pu_ctl_33 = Input(Bool())
  val pu_ctl_34 = Input(Bool())
  val pu_ctl_35 = Input(Bool())
  val pu_ctl_36 = Input(Bool())
  val pu_ctl_37 = Input(Bool())
  val pu_ctl_38 = Input(Bool())
  val pu_ctl_39 = Input(Bool())
  val pd_ctlb_0 = Input(Bool())
  val pd_ctlb_1 = Input(Bool())
  val pd_ctlb_2 = Input(Bool())
  val pd_ctlb_3 = Input(Bool())
  val pd_ctlb_4 = Input(Bool())
  val pd_ctlb_5 = Input(Bool())
  val pd_ctlb_6 = Input(Bool())
  val pd_ctlb_7 = Input(Bool())
  val pd_ctlb_8 = Input(Bool())
  val pd_ctlb_9 = Input(Bool())
  val pd_ctlb_10 = Input(Bool())
  val pd_ctlb_11 = Input(Bool())
  val pd_ctlb_12 = Input(Bool())
  val pd_ctlb_13 = Input(Bool())
  val pd_ctlb_14 = Input(Bool())
  val pd_ctlb_15 = Input(Bool())
  val pd_ctlb_16 = Input(Bool())
  val pd_ctlb_17 = Input(Bool())
  val pd_ctlb_18 = Input(Bool())
  val pd_ctlb_19 = Input(Bool())
  val pd_ctlb_20 = Input(Bool())
  val pd_ctlb_21 = Input(Bool())
  val pd_ctlb_22 = Input(Bool())
  val pd_ctlb_23 = Input(Bool())
  val pd_ctlb_24 = Input(Bool())
  val pd_ctlb_25 = Input(Bool())
  val pd_ctlb_26 = Input(Bool())
  val pd_ctlb_27 = Input(Bool())
  val pd_ctlb_28 = Input(Bool())
  val pd_ctlb_29 = Input(Bool())
  val pd_ctlb_30 = Input(Bool())
  val pd_ctlb_31 = Input(Bool())
  val pd_ctlb_32 = Input(Bool())
  val pd_ctlb_33 = Input(Bool())
  val pd_ctlb_34 = Input(Bool())
  val pd_ctlb_35 = Input(Bool())
  val pd_ctlb_36 = Input(Bool())
  val pd_ctlb_37 = Input(Bool())
  val pd_ctlb_38 = Input(Bool())
  val pd_ctlb_39 = Input(Bool())
  val driver_en = Input(Bool())
  val driver_en_b = Input(Bool())
  val dll_en = Input(Bool())
  val ocl = Input(Bool())
  val delay_0 = Input(Bool())
  val delay_1 = Input(Bool())
  val delay_2 = Input(Bool())
  val delay_3 = Input(Bool())
  val delay_4 = Input(Bool())
  val delayb_0 = Input(Bool())
  val delayb_1 = Input(Bool())
  val delayb_2 = Input(Bool())
  val delayb_3 = Input(Bool())
  val delayb_4 = Input(Bool())
  val mux_en_0 = Input(Bool())
  val mux_en_1 = Input(Bool())
  val mux_en_2 = Input(Bool())
  val mux_en_3 = Input(Bool())
  val mux_en_4 = Input(Bool())
  val mux_en_5 = Input(Bool())
  val mux_en_6 = Input(Bool())
  val mux_en_7 = Input(Bool())
  val mux_enb_0 = Input(Bool())
  val mux_enb_1 = Input(Bool())
  val mux_enb_2 = Input(Bool())
  val mux_enb_3 = Input(Bool())
  val mux_enb_4 = Input(Bool())
  val mux_enb_5 = Input(Bool())
  val mux_enb_6 = Input(Bool())
  val mux_enb_7 = Input(Bool())
  val band_ctrl_0 = Input(Bool())
  val band_ctrl_1 = Input(Bool())
  val band_ctrlb_0 = Input(Bool())
  val band_ctrlb_1 = Input(Bool())
  val mix_en_0 = Input(Bool())
  val mix_en_1 = Input(Bool())
  val mix_en_2 = Input(Bool())
  val mix_en_3 = Input(Bool())
  val mix_en_4 = Input(Bool())
  val mix_en_5 = Input(Bool())
  val mix_en_6 = Input(Bool())
  val mix_en_7 = Input(Bool())
  val mix_en_8 = Input(Bool())
  val mix_en_9 = Input(Bool())
  val mix_en_10 = Input(Bool())
  val mix_en_11 = Input(Bool())
  val mix_en_12 = Input(Bool())
  val mix_en_13 = Input(Bool())
  val mix_en_14 = Input(Bool())
  val mix_en_15 = Input(Bool())
  val mix_enb_0 = Input(Bool())
  val mix_enb_1 = Input(Bool())
  val mix_enb_2 = Input(Bool())
  val mix_enb_3 = Input(Bool())
  val mix_enb_4 = Input(Bool())
  val mix_enb_5 = Input(Bool())
  val mix_enb_6 = Input(Bool())
  val mix_enb_7 = Input(Bool())
  val mix_enb_8 = Input(Bool())
  val mix_enb_9 = Input(Bool())
  val mix_enb_10 = Input(Bool())
  val mix_enb_11 = Input(Bool())
  val mix_enb_12 = Input(Bool())
  val mix_enb_13 = Input(Bool())
  val mix_enb_14 = Input(Bool())
  val mix_enb_15 = Input(Bool())
  val nen_out_0 = Input(Bool())
  val nen_out_1 = Input(Bool())
  val nen_out_2 = Input(Bool())
  val nen_out_3 = Input(Bool())
  val nen_out_4 = Input(Bool())
  val nen_outb_0 = Input(Bool())
  val nen_outb_1 = Input(Bool())
  val nen_outb_2 = Input(Bool())
  val nen_outb_3 = Input(Bool())
  val nen_outb_4 = Input(Bool())
  val pen_out_0 = Input(Bool())
  val pen_out_1 = Input(Bool())
  val pen_out_2 = Input(Bool())
  val pen_out_3 = Input(Bool())
  val pen_out_4 = Input(Bool())
  val pen_outb_0 = Input(Bool())
  val pen_outb_1 = Input(Bool())
  val pen_outb_2 = Input(Bool())
  val pen_outb_3 = Input(Bool())
  val pen_outb_4 = Input(Bool())
  val dll_code_0 = Output(Bool())
  val dll_code_1 = Output(Bool())
  val dll_code_2 = Output(Bool())
  val dll_code_3 = Output(Bool())
  val dll_code_4 = Output(Bool())
}

class TxDriverIO extends Bundle {
  val din = Input(Bool())
  val dout = Output(Bool())
  val ctl = Input(new DriverControlIO)
}

class TxDriver(sim: Boolean = false) extends RawModule {
  val io = IO(new TxDriverIO)

  val verilogBlackBox = Module(new VerilogTxDriver(sim))
  verilogBlackBox.io.din := io.din
  io.dout := verilogBlackBox.io.dout
  val puCtlTherm = Wire(UInt(64.W))
  puCtlTherm := (1.U << io.ctl.pu_ctl) - 1.U
  val pdCtlbTherm = Wire(UInt(64.W))
  pdCtlbTherm := ~((1.U << io.ctl.pd_ctl) - 1.U)
  verilogBlackBox.io.pu_ctl_0 := puCtlTherm(0)
  verilogBlackBox.io.pu_ctl_1 := puCtlTherm(1)
  verilogBlackBox.io.pu_ctl_2 := puCtlTherm(2)
  verilogBlackBox.io.pu_ctl_3 := puCtlTherm(3)
  verilogBlackBox.io.pu_ctl_4 := puCtlTherm(4)
  verilogBlackBox.io.pu_ctl_5 := puCtlTherm(5)
  verilogBlackBox.io.pu_ctl_6 := puCtlTherm(6)
  verilogBlackBox.io.pu_ctl_7 := puCtlTherm(7)
  verilogBlackBox.io.pu_ctl_8 := puCtlTherm(8)
  verilogBlackBox.io.pu_ctl_9 := puCtlTherm(9)
  verilogBlackBox.io.pu_ctl_10 := puCtlTherm(10)
  verilogBlackBox.io.pu_ctl_11 := puCtlTherm(11)
  verilogBlackBox.io.pu_ctl_12 := puCtlTherm(12)
  verilogBlackBox.io.pu_ctl_13 := puCtlTherm(13)
  verilogBlackBox.io.pu_ctl_14 := puCtlTherm(14)
  verilogBlackBox.io.pu_ctl_15 := puCtlTherm(15)
  verilogBlackBox.io.pu_ctl_16 := puCtlTherm(16)
  verilogBlackBox.io.pu_ctl_17 := puCtlTherm(17)
  verilogBlackBox.io.pu_ctl_18 := puCtlTherm(18)
  verilogBlackBox.io.pu_ctl_19 := puCtlTherm(19)
  verilogBlackBox.io.pu_ctl_20 := puCtlTherm(20)
  verilogBlackBox.io.pu_ctl_21 := puCtlTherm(21)
  verilogBlackBox.io.pu_ctl_22 := puCtlTherm(22)
  verilogBlackBox.io.pu_ctl_23 := puCtlTherm(23)
  verilogBlackBox.io.pu_ctl_24 := puCtlTherm(24)
  verilogBlackBox.io.pu_ctl_25 := puCtlTherm(25)
  verilogBlackBox.io.pu_ctl_26 := puCtlTherm(26)
  verilogBlackBox.io.pu_ctl_27 := puCtlTherm(27)
  verilogBlackBox.io.pu_ctl_28 := puCtlTherm(28)
  verilogBlackBox.io.pu_ctl_29 := puCtlTherm(29)
  verilogBlackBox.io.pu_ctl_30 := puCtlTherm(30)
  verilogBlackBox.io.pu_ctl_31 := puCtlTherm(31)
  verilogBlackBox.io.pu_ctl_32 := puCtlTherm(32)
  verilogBlackBox.io.pu_ctl_33 := puCtlTherm(33)
  verilogBlackBox.io.pu_ctl_34 := puCtlTherm(34)
  verilogBlackBox.io.pu_ctl_35 := puCtlTherm(35)
  verilogBlackBox.io.pu_ctl_36 := puCtlTherm(36)
  verilogBlackBox.io.pu_ctl_37 := puCtlTherm(37)
  verilogBlackBox.io.pu_ctl_38 := puCtlTherm(38)
  verilogBlackBox.io.pu_ctl_39 := puCtlTherm(39)
  verilogBlackBox.io.pd_ctlb_0 := pdCtlbTherm(0)
  verilogBlackBox.io.pd_ctlb_1 := pdCtlbTherm(1)
  verilogBlackBox.io.pd_ctlb_2 := pdCtlbTherm(2)
  verilogBlackBox.io.pd_ctlb_3 := pdCtlbTherm(3)
  verilogBlackBox.io.pd_ctlb_4 := pdCtlbTherm(4)
  verilogBlackBox.io.pd_ctlb_5 := pdCtlbTherm(5)
  verilogBlackBox.io.pd_ctlb_6 := pdCtlbTherm(6)
  verilogBlackBox.io.pd_ctlb_7 := pdCtlbTherm(7)
  verilogBlackBox.io.pd_ctlb_8 := pdCtlbTherm(8)
  verilogBlackBox.io.pd_ctlb_9 := pdCtlbTherm(9)
  verilogBlackBox.io.pd_ctlb_10 := pdCtlbTherm(10)
  verilogBlackBox.io.pd_ctlb_11 := pdCtlbTherm(11)
  verilogBlackBox.io.pd_ctlb_12 := pdCtlbTherm(12)
  verilogBlackBox.io.pd_ctlb_13 := pdCtlbTherm(13)
  verilogBlackBox.io.pd_ctlb_14 := pdCtlbTherm(14)
  verilogBlackBox.io.pd_ctlb_15 := pdCtlbTherm(15)
  verilogBlackBox.io.pd_ctlb_16 := pdCtlbTherm(16)
  verilogBlackBox.io.pd_ctlb_17 := pdCtlbTherm(17)
  verilogBlackBox.io.pd_ctlb_18 := pdCtlbTherm(18)
  verilogBlackBox.io.pd_ctlb_19 := pdCtlbTherm(19)
  verilogBlackBox.io.pd_ctlb_20 := pdCtlbTherm(20)
  verilogBlackBox.io.pd_ctlb_21 := pdCtlbTherm(21)
  verilogBlackBox.io.pd_ctlb_22 := pdCtlbTherm(22)
  verilogBlackBox.io.pd_ctlb_23 := pdCtlbTherm(23)
  verilogBlackBox.io.pd_ctlb_24 := pdCtlbTherm(24)
  verilogBlackBox.io.pd_ctlb_25 := pdCtlbTherm(25)
  verilogBlackBox.io.pd_ctlb_26 := pdCtlbTherm(26)
  verilogBlackBox.io.pd_ctlb_27 := pdCtlbTherm(27)
  verilogBlackBox.io.pd_ctlb_28 := pdCtlbTherm(28)
  verilogBlackBox.io.pd_ctlb_29 := pdCtlbTherm(29)
  verilogBlackBox.io.pd_ctlb_30 := pdCtlbTherm(30)
  verilogBlackBox.io.pd_ctlb_31 := pdCtlbTherm(31)
  verilogBlackBox.io.pd_ctlb_32 := pdCtlbTherm(32)
  verilogBlackBox.io.pd_ctlb_33 := pdCtlbTherm(33)
  verilogBlackBox.io.pd_ctlb_34 := pdCtlbTherm(34)
  verilogBlackBox.io.pd_ctlb_35 := pdCtlbTherm(35)
  verilogBlackBox.io.pd_ctlb_36 := pdCtlbTherm(36)
  verilogBlackBox.io.pd_ctlb_37 := pdCtlbTherm(37)
  verilogBlackBox.io.pd_ctlb_38 := pdCtlbTherm(38)
  verilogBlackBox.io.pd_ctlb_39 := pdCtlbTherm(39)
  verilogBlackBox.io.en := io.ctl.en
  verilogBlackBox.io.en_b := io.ctl.en_b
}

class VerilogTxDriverIO extends Bundle {
  val din = Input(Bool())
  val dout = Output(Bool())
  val pu_ctl_0 = Input(Bool())
  val pu_ctl_1 = Input(Bool())
  val pu_ctl_2 = Input(Bool())
  val pu_ctl_3 = Input(Bool())
  val pu_ctl_4 = Input(Bool())
  val pu_ctl_5 = Input(Bool())
  val pu_ctl_6 = Input(Bool())
  val pu_ctl_7 = Input(Bool())
  val pu_ctl_8 = Input(Bool())
  val pu_ctl_9 = Input(Bool())
  val pu_ctl_10 = Input(Bool())
  val pu_ctl_11 = Input(Bool())
  val pu_ctl_12 = Input(Bool())
  val pu_ctl_13 = Input(Bool())
  val pu_ctl_14 = Input(Bool())
  val pu_ctl_15 = Input(Bool())
  val pu_ctl_16 = Input(Bool())
  val pu_ctl_17 = Input(Bool())
  val pu_ctl_18 = Input(Bool())
  val pu_ctl_19 = Input(Bool())
  val pu_ctl_20 = Input(Bool())
  val pu_ctl_21 = Input(Bool())
  val pu_ctl_22 = Input(Bool())
  val pu_ctl_23 = Input(Bool())
  val pu_ctl_24 = Input(Bool())
  val pu_ctl_25 = Input(Bool())
  val pu_ctl_26 = Input(Bool())
  val pu_ctl_27 = Input(Bool())
  val pu_ctl_28 = Input(Bool())
  val pu_ctl_29 = Input(Bool())
  val pu_ctl_30 = Input(Bool())
  val pu_ctl_31 = Input(Bool())
  val pu_ctl_32 = Input(Bool())
  val pu_ctl_33 = Input(Bool())
  val pu_ctl_34 = Input(Bool())
  val pu_ctl_35 = Input(Bool())
  val pu_ctl_36 = Input(Bool())
  val pu_ctl_37 = Input(Bool())
  val pu_ctl_38 = Input(Bool())
  val pu_ctl_39 = Input(Bool())
  val pd_ctlb_0 = Input(Bool())
  val pd_ctlb_1 = Input(Bool())
  val pd_ctlb_2 = Input(Bool())
  val pd_ctlb_3 = Input(Bool())
  val pd_ctlb_4 = Input(Bool())
  val pd_ctlb_5 = Input(Bool())
  val pd_ctlb_6 = Input(Bool())
  val pd_ctlb_7 = Input(Bool())
  val pd_ctlb_8 = Input(Bool())
  val pd_ctlb_9 = Input(Bool())
  val pd_ctlb_10 = Input(Bool())
  val pd_ctlb_11 = Input(Bool())
  val pd_ctlb_12 = Input(Bool())
  val pd_ctlb_13 = Input(Bool())
  val pd_ctlb_14 = Input(Bool())
  val pd_ctlb_15 = Input(Bool())
  val pd_ctlb_16 = Input(Bool())
  val pd_ctlb_17 = Input(Bool())
  val pd_ctlb_18 = Input(Bool())
  val pd_ctlb_19 = Input(Bool())
  val pd_ctlb_20 = Input(Bool())
  val pd_ctlb_21 = Input(Bool())
  val pd_ctlb_22 = Input(Bool())
  val pd_ctlb_23 = Input(Bool())
  val pd_ctlb_24 = Input(Bool())
  val pd_ctlb_25 = Input(Bool())
  val pd_ctlb_26 = Input(Bool())
  val pd_ctlb_27 = Input(Bool())
  val pd_ctlb_28 = Input(Bool())
  val pd_ctlb_29 = Input(Bool())
  val pd_ctlb_30 = Input(Bool())
  val pd_ctlb_31 = Input(Bool())
  val pd_ctlb_32 = Input(Bool())
  val pd_ctlb_33 = Input(Bool())
  val pd_ctlb_34 = Input(Bool())
  val pd_ctlb_35 = Input(Bool())
  val pd_ctlb_36 = Input(Bool())
  val pd_ctlb_37 = Input(Bool())
  val pd_ctlb_38 = Input(Bool())
  val pd_ctlb_39 = Input(Bool())
  val en = Input(Bool())
  val en_b = Input(Bool())
}

class VerilogTxDriver(sim: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new VerilogTxDriverIO)

  override val desiredName = "tx_driver"

  if (sim) {
    setInline(
      "tx_driver.v",
      """
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
   input pd_ctlb_39,
   inout vdd,
   inout vss
);
  assign dout = din;
endmodule
      """
    )
  }
}

class UciePllCtlIO extends Bundle {
  val dref_low = UInt(7.W)
  val dref_high = UInt(7.W)
  val dcoarse = UInt(8.W)
  val d_kp = UInt(16.W)
  val d_ki = UInt(16.W)
  val d_clol = Bool()
  val d_ol_fcw = UInt(8.W)
  val d_accumulator_reset = UInt(32.W)
  val vco_reset = Bool()
  val digital_reset = Bool()
}

class UciePllDebugOutIO extends Bundle {
  val d_fcw_debug = Output(UInt(8.W))
  val d_sar_debug = Output(UInt(8.W))
}

class UciePllIO extends Bundle {
  val vclk_ref = Input(Bool())
  val vclk_refb = Input(Bool())
  val dref_low = Input(UInt(7.W))
  val dref_high = Input(UInt(7.W))
  val vrdac_ref = Input(Bool())
  val dcoarse = Input(UInt(8.W))
  val dvco_reset = Input(Bool())
  val dvco_resetn = Input(Bool())
  val vp_out = Output(Bool())
  val vn_out = Output(Bool())
  val d_fcw_debug = Output(UInt(8.W))
  val d_sar_debug = Output(UInt(8.W))
  val d_digital_reset = Input(Bool())
  val d_kp = Input(UInt(16.W))
  val d_ki = Input(UInt(16.W))
  val d_clol = Input(Bool())
  val d_ol_fcw = Input(UInt(8.W))
  val d_accumulator_reset = Input(UInt(32.W))
}

// Pins marked as "leave floating" should be outputs.
class VerilogUciePllIO extends Bundle {
  val vclk_ref = Input(Bool())
  val vclk_refb = Input(Bool())
  val dref_low_0 = Input(Bool())
  val dref_low_1 = Input(Bool())
  val dref_low_2 = Input(Bool())
  val dref_low_3 = Input(Bool())
  val dref_low_4 = Input(Bool())
  val dref_low_5 = Input(Bool())
  val dref_low_6 = Input(Bool())
  val dref_high_0 = Input(Bool())
  val dref_high_1 = Input(Bool())
  val dref_high_2 = Input(Bool())
  val dref_high_3 = Input(Bool())
  val dref_high_4 = Input(Bool())
  val dref_high_5 = Input(Bool())
  val dref_high_6 = Input(Bool())
  val vrdac_ref = Input(Bool())
  val dcoarse_0 = Input(Bool())
  val dcoarse_1 = Input(Bool())
  val dcoarse_2 = Input(Bool())
  val dcoarse_3 = Input(Bool())
  val dcoarse_4 = Input(Bool())
  val dcoarse_5 = Input(Bool())
  val dcoarse_6 = Input(Bool())
  val dcoarse_7 = Input(Bool())
  val dvco_reset = Input(Bool())
  val dvco_resetn = Input(Bool())
  val vp_out = Output(Bool())
  val vn_out = Output(Bool())
  val vsar_ref_low = Output(Bool())
  val vsar_ref_high = Output(Bool())
  val vdig_clk = Output(Bool())
  val dfine_0 = Output(Bool())
  val dfine_1 = Output(Bool())
  val dfine_2 = Output(Bool())
  val dfine_3 = Output(Bool())
  val dfine_4 = Output(Bool())
  val dfine_5 = Output(Bool())
  val dfine_6 = Output(Bool())
  val dfine_7 = Output(Bool())
  val d_fcw_debug_0 = Output(Bool())
  val d_fcw_debug_1 = Output(Bool())
  val d_fcw_debug_2 = Output(Bool())
  val d_fcw_debug_3 = Output(Bool())
  val d_fcw_debug_4 = Output(Bool())
  val d_fcw_debug_5 = Output(Bool())
  val d_fcw_debug_6 = Output(Bool())
  val d_fcw_debug_7 = Output(Bool())
  val d_sar_debug_0 = Output(Bool())
  val d_sar_debug_1 = Output(Bool())
  val d_sar_debug_2 = Output(Bool())
  val d_sar_debug_3 = Output(Bool())
  val d_sar_debug_4 = Output(Bool())
  val d_sar_debug_5 = Output(Bool())
  val d_sar_debug_6 = Output(Bool())
  val d_sar_debug_7 = Output(Bool())
  val d_digital_reset = Input(Bool())
  val d_kp_0 = Input(Bool())
  val d_kp_1 = Input(Bool())
  val d_kp_2 = Input(Bool())
  val d_kp_3 = Input(Bool())
  val d_kp_4 = Input(Bool())
  val d_kp_5 = Input(Bool())
  val d_kp_6 = Input(Bool())
  val d_kp_7 = Input(Bool())
  val d_kp_8 = Input(Bool())
  val d_kp_9 = Input(Bool())
  val d_kp_10 = Input(Bool())
  val d_kp_11 = Input(Bool())
  val d_kp_12 = Input(Bool())
  val d_kp_13 = Input(Bool())
  val d_kp_14 = Input(Bool())
  val d_kp_15 = Input(Bool())
  val d_ki_0 = Input(Bool())
  val d_ki_1 = Input(Bool())
  val d_ki_2 = Input(Bool())
  val d_ki_3 = Input(Bool())
  val d_ki_4 = Input(Bool())
  val d_ki_5 = Input(Bool())
  val d_ki_6 = Input(Bool())
  val d_ki_7 = Input(Bool())
  val d_ki_8 = Input(Bool())
  val d_ki_9 = Input(Bool())
  val d_ki_10 = Input(Bool())
  val d_ki_11 = Input(Bool())
  val d_ki_12 = Input(Bool())
  val d_ki_13 = Input(Bool())
  val d_ki_14 = Input(Bool())
  val d_ki_15 = Input(Bool())
  val d_clol = Input(Bool())
  val d_ol_fcw_0 = Input(Bool())
  val d_ol_fcw_1 = Input(Bool())
  val d_ol_fcw_2 = Input(Bool())
  val d_ol_fcw_3 = Input(Bool())
  val d_ol_fcw_4 = Input(Bool())
  val d_ol_fcw_5 = Input(Bool())
  val d_ol_fcw_6 = Input(Bool())
  val d_ol_fcw_7 = Input(Bool())
  val d_accumulator_reset_0 = Input(Bool())
  val d_accumulator_reset_1 = Input(Bool())
  val d_accumulator_reset_2 = Input(Bool())
  val d_accumulator_reset_3 = Input(Bool())
  val d_accumulator_reset_4 = Input(Bool())
  val d_accumulator_reset_5 = Input(Bool())
  val d_accumulator_reset_6 = Input(Bool())
  val d_accumulator_reset_7 = Input(Bool())
  val d_accumulator_reset_8 = Input(Bool())
  val d_accumulator_reset_9 = Input(Bool())
  val d_accumulator_reset_10 = Input(Bool())
  val d_accumulator_reset_11 = Input(Bool())
  val d_accumulator_reset_12 = Input(Bool())
  val d_accumulator_reset_13 = Input(Bool())
  val d_accumulator_reset_14 = Input(Bool())
  val d_accumulator_reset_15 = Input(Bool())
  val d_accumulator_reset_16 = Input(Bool())
  val d_accumulator_reset_17 = Input(Bool())
  val d_accumulator_reset_18 = Input(Bool())
  val d_accumulator_reset_19 = Input(Bool())
  val d_accumulator_reset_20 = Input(Bool())
  val d_accumulator_reset_21 = Input(Bool())
  val d_accumulator_reset_22 = Input(Bool())
  val d_accumulator_reset_23 = Input(Bool())
  val d_accumulator_reset_24 = Input(Bool())
  val d_accumulator_reset_25 = Input(Bool())
  val d_accumulator_reset_26 = Input(Bool())
  val d_accumulator_reset_27 = Input(Bool())
  val d_accumulator_reset_28 = Input(Bool())
  val d_accumulator_reset_29 = Input(Bool())
  val d_accumulator_reset_30 = Input(Bool())
  val d_accumulator_reset_31 = Input(Bool())
}

class UciePll(sim: Boolean = false) extends RawModule {
  val io = IO(new UciePllIO)

  val verilogBlackBox = Module(new VerilogUciePll(sim))
  verilogBlackBox.io.vclk_ref := io.vclk_ref
  verilogBlackBox.io.vclk_refb := io.vclk_refb
  verilogBlackBox.io.dref_low_0 := io.dref_low(0)
  verilogBlackBox.io.dref_low_1 := io.dref_low(1)
  verilogBlackBox.io.dref_low_2 := io.dref_low(2)
  verilogBlackBox.io.dref_low_3 := io.dref_low(3)
  verilogBlackBox.io.dref_low_4 := io.dref_low(4)
  verilogBlackBox.io.dref_low_5 := io.dref_low(5)
  verilogBlackBox.io.dref_low_6 := io.dref_low(6)
  verilogBlackBox.io.dref_high_0 := io.dref_high(0)
  verilogBlackBox.io.dref_high_1 := io.dref_high(1)
  verilogBlackBox.io.dref_high_2 := io.dref_high(2)
  verilogBlackBox.io.dref_high_3 := io.dref_high(3)
  verilogBlackBox.io.dref_high_4 := io.dref_high(4)
  verilogBlackBox.io.dref_high_5 := io.dref_high(5)
  verilogBlackBox.io.dref_high_6 := io.dref_high(6)
  verilogBlackBox.io.vrdac_ref := io.vrdac_ref
  verilogBlackBox.io.dcoarse_0 := io.dcoarse(0)
  verilogBlackBox.io.dcoarse_1 := io.dcoarse(1)
  verilogBlackBox.io.dcoarse_2 := io.dcoarse(2)
  verilogBlackBox.io.dcoarse_3 := io.dcoarse(3)
  verilogBlackBox.io.dcoarse_4 := io.dcoarse(4)
  verilogBlackBox.io.dcoarse_5 := io.dcoarse(5)
  verilogBlackBox.io.dcoarse_6 := io.dcoarse(6)
  verilogBlackBox.io.dcoarse_7 := io.dcoarse(7)
  verilogBlackBox.io.dvco_reset := io.dvco_reset
  verilogBlackBox.io.dvco_resetn := io.dvco_resetn
  io.vp_out := verilogBlackBox.io.vp_out
  io.vn_out := verilogBlackBox.io.vn_out
  io.d_fcw_debug := Cat(
    verilogBlackBox.io.d_fcw_debug_7,
    verilogBlackBox.io.d_fcw_debug_6,
    verilogBlackBox.io.d_fcw_debug_5,
    verilogBlackBox.io.d_fcw_debug_4,
    verilogBlackBox.io.d_fcw_debug_3,
    verilogBlackBox.io.d_fcw_debug_2,
    verilogBlackBox.io.d_fcw_debug_1,
    verilogBlackBox.io.d_fcw_debug_0
  )
  io.d_sar_debug := Cat(
    verilogBlackBox.io.d_sar_debug_7,
    verilogBlackBox.io.d_sar_debug_6,
    verilogBlackBox.io.d_sar_debug_5,
    verilogBlackBox.io.d_sar_debug_4,
    verilogBlackBox.io.d_sar_debug_3,
    verilogBlackBox.io.d_sar_debug_2,
    verilogBlackBox.io.d_sar_debug_1,
    verilogBlackBox.io.d_sar_debug_0
  )
  verilogBlackBox.io.d_digital_reset := io.d_digital_reset
  verilogBlackBox.io.d_kp_0 := io.d_kp(0)
  verilogBlackBox.io.d_kp_1 := io.d_kp(1)
  verilogBlackBox.io.d_kp_2 := io.d_kp(2)
  verilogBlackBox.io.d_kp_3 := io.d_kp(3)
  verilogBlackBox.io.d_kp_4 := io.d_kp(4)
  verilogBlackBox.io.d_kp_5 := io.d_kp(5)
  verilogBlackBox.io.d_kp_6 := io.d_kp(6)
  verilogBlackBox.io.d_kp_7 := io.d_kp(7)
  verilogBlackBox.io.d_kp_8 := io.d_kp(8)
  verilogBlackBox.io.d_kp_9 := io.d_kp(9)
  verilogBlackBox.io.d_kp_10 := io.d_kp(10)
  verilogBlackBox.io.d_kp_11 := io.d_kp(11)
  verilogBlackBox.io.d_kp_12 := io.d_kp(12)
  verilogBlackBox.io.d_kp_13 := io.d_kp(13)
  verilogBlackBox.io.d_kp_14 := io.d_kp(14)
  verilogBlackBox.io.d_kp_15 := io.d_kp(15)
  verilogBlackBox.io.d_ki_0 := io.d_ki(0)
  verilogBlackBox.io.d_ki_1 := io.d_ki(1)
  verilogBlackBox.io.d_ki_2 := io.d_ki(2)
  verilogBlackBox.io.d_ki_3 := io.d_ki(3)
  verilogBlackBox.io.d_ki_4 := io.d_ki(4)
  verilogBlackBox.io.d_ki_5 := io.d_ki(5)
  verilogBlackBox.io.d_ki_6 := io.d_ki(6)
  verilogBlackBox.io.d_ki_7 := io.d_ki(7)
  verilogBlackBox.io.d_ki_8 := io.d_ki(8)
  verilogBlackBox.io.d_ki_9 := io.d_ki(9)
  verilogBlackBox.io.d_ki_10 := io.d_ki(10)
  verilogBlackBox.io.d_ki_11 := io.d_ki(11)
  verilogBlackBox.io.d_ki_12 := io.d_ki(12)
  verilogBlackBox.io.d_ki_13 := io.d_ki(13)
  verilogBlackBox.io.d_ki_14 := io.d_ki(14)
  verilogBlackBox.io.d_ki_15 := io.d_ki(15)
  verilogBlackBox.io.d_clol := io.d_clol
  verilogBlackBox.io.d_ol_fcw_0 := io.d_ol_fcw(0)
  verilogBlackBox.io.d_ol_fcw_1 := io.d_ol_fcw(1)
  verilogBlackBox.io.d_ol_fcw_2 := io.d_ol_fcw(2)
  verilogBlackBox.io.d_ol_fcw_3 := io.d_ol_fcw(3)
  verilogBlackBox.io.d_ol_fcw_4 := io.d_ol_fcw(4)
  verilogBlackBox.io.d_ol_fcw_5 := io.d_ol_fcw(5)
  verilogBlackBox.io.d_ol_fcw_6 := io.d_ol_fcw(6)
  verilogBlackBox.io.d_ol_fcw_7 := io.d_ol_fcw(7)
  verilogBlackBox.io.d_accumulator_reset_0 := io.d_accumulator_reset(0)
  verilogBlackBox.io.d_accumulator_reset_1 := io.d_accumulator_reset(1)
  verilogBlackBox.io.d_accumulator_reset_2 := io.d_accumulator_reset(2)
  verilogBlackBox.io.d_accumulator_reset_3 := io.d_accumulator_reset(3)
  verilogBlackBox.io.d_accumulator_reset_4 := io.d_accumulator_reset(4)
  verilogBlackBox.io.d_accumulator_reset_5 := io.d_accumulator_reset(5)
  verilogBlackBox.io.d_accumulator_reset_6 := io.d_accumulator_reset(6)
  verilogBlackBox.io.d_accumulator_reset_7 := io.d_accumulator_reset(7)
  verilogBlackBox.io.d_accumulator_reset_8 := io.d_accumulator_reset(8)
  verilogBlackBox.io.d_accumulator_reset_9 := io.d_accumulator_reset(9)
  verilogBlackBox.io.d_accumulator_reset_10 := io.d_accumulator_reset(10)
  verilogBlackBox.io.d_accumulator_reset_11 := io.d_accumulator_reset(11)
  verilogBlackBox.io.d_accumulator_reset_12 := io.d_accumulator_reset(12)
  verilogBlackBox.io.d_accumulator_reset_13 := io.d_accumulator_reset(13)
  verilogBlackBox.io.d_accumulator_reset_14 := io.d_accumulator_reset(14)
  verilogBlackBox.io.d_accumulator_reset_15 := io.d_accumulator_reset(15)
  verilogBlackBox.io.d_accumulator_reset_16 := io.d_accumulator_reset(16)
  verilogBlackBox.io.d_accumulator_reset_17 := io.d_accumulator_reset(17)
  verilogBlackBox.io.d_accumulator_reset_18 := io.d_accumulator_reset(18)
  verilogBlackBox.io.d_accumulator_reset_19 := io.d_accumulator_reset(19)
  verilogBlackBox.io.d_accumulator_reset_20 := io.d_accumulator_reset(20)
  verilogBlackBox.io.d_accumulator_reset_21 := io.d_accumulator_reset(21)
  verilogBlackBox.io.d_accumulator_reset_22 := io.d_accumulator_reset(22)
  verilogBlackBox.io.d_accumulator_reset_23 := io.d_accumulator_reset(23)
  verilogBlackBox.io.d_accumulator_reset_24 := io.d_accumulator_reset(24)
  verilogBlackBox.io.d_accumulator_reset_25 := io.d_accumulator_reset(25)
  verilogBlackBox.io.d_accumulator_reset_26 := io.d_accumulator_reset(26)
  verilogBlackBox.io.d_accumulator_reset_27 := io.d_accumulator_reset(27)
  verilogBlackBox.io.d_accumulator_reset_28 := io.d_accumulator_reset(28)
  verilogBlackBox.io.d_accumulator_reset_29 := io.d_accumulator_reset(29)
  verilogBlackBox.io.d_accumulator_reset_30 := io.d_accumulator_reset(30)
  verilogBlackBox.io.d_accumulator_reset_31 := io.d_accumulator_reset(31)
}

class VerilogUciePll(sim: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new VerilogUciePllIO)

  override val desiredName = "ucie_pll"
  if (sim) {
    setInline(
      "ucie_pll.v",
      """
module ucie_pll (
   inout vdd,
   inout vdd_dig,
   inout vss,
   input vclk_ref,
   input vclk_refb,
   input dref_low_0,
   input dref_low_1,
   input dref_low_2,
   input dref_low_3,
   input dref_low_4,
   input dref_low_5,
   input dref_low_6,
   input dref_high_0,
   input dref_high_1,
   input dref_high_2,
   input dref_high_3,
   input dref_high_4,
   input dref_high_5,
   input dref_high_6,
   input vrdac_ref,
   input dcoarse_0,
   input dcoarse_1,
   input dcoarse_2,
   input dcoarse_3,
   input dcoarse_4,
   input dcoarse_5,
   input dcoarse_6,
   input dcoarse_7,
   input dvco_reset,
   input dvco_resetn,
   output vp_out,
   output vn_out,
   output vsar_ref_low,
   output vsar_ref_high,
   output vdig_clk,
   output dfine_0,
   output dfine_1,
   output dfine_2,
   output dfine_3,
   output dfine_4,
   output dfine_5,
   output dfine_6,
   output dfine_7,
   output d_fcw_debug_0,
   output d_fcw_debug_1,
   output d_fcw_debug_2,
   output d_fcw_debug_3,
   output d_fcw_debug_4,
   output d_fcw_debug_5,
   output d_fcw_debug_6,
   output d_fcw_debug_7,
   output d_sar_debug_0,
   output d_sar_debug_1,
   output d_sar_debug_2,
   output d_sar_debug_3,
   output d_sar_debug_4,
   output d_sar_debug_5,
   output d_sar_debug_6,
   output d_sar_debug_7,
   input d_digital_reset,
   input d_kp_0,
   input d_kp_1,
   input d_kp_2,
   input d_kp_3,
   input d_kp_4,
   input d_kp_5,
   input d_kp_6,
   input d_kp_7,
   input d_kp_8,
   input d_kp_9,
   input d_kp_10,
   input d_kp_11,
   input d_kp_12,
   input d_kp_13,
   input d_kp_14,
   input d_kp_15,
   input d_ki_0,
   input d_ki_1,
   input d_ki_2,
   input d_ki_3,
   input d_ki_4,
   input d_ki_5,
   input d_ki_6,
   input d_ki_7,
   input d_ki_8,
   input d_ki_9,
   input d_ki_10,
   input d_ki_11,
   input d_ki_12,
   input d_ki_13,
   input d_ki_14,
   input d_ki_15,
   input d_clol,
   input d_ol_fcw_0,
   input d_ol_fcw_1,
   input d_ol_fcw_2,
   input d_ol_fcw_3,
   input d_ol_fcw_4,
   input d_ol_fcw_5,
   input d_ol_fcw_6,
   input d_ol_fcw_7,
   input d_accumulator_reset_0,
   input d_accumulator_reset_1,
   input d_accumulator_reset_2,
   input d_accumulator_reset_3,
   input d_accumulator_reset_4,
   input d_accumulator_reset_5,
   input d_accumulator_reset_6,
   input d_accumulator_reset_7,
   input d_accumulator_reset_8,
   input d_accumulator_reset_9,
   input d_accumulator_reset_10,
   input d_accumulator_reset_11,
   input d_accumulator_reset_12,
   input d_accumulator_reset_13,
   input d_accumulator_reset_14,
   input d_accumulator_reset_15,
   input d_accumulator_reset_16,
   input d_accumulator_reset_17,
   input d_accumulator_reset_18,
   input d_accumulator_reset_19,
   input d_accumulator_reset_20,
   input d_accumulator_reset_21,
   input d_accumulator_reset_22,
   input d_accumulator_reset_23,
   input d_accumulator_reset_24,
   input d_accumulator_reset_25,
   input d_accumulator_reset_26,
   input d_accumulator_reset_27,
   input d_accumulator_reset_28,
   input d_accumulator_reset_29,
   input d_accumulator_reset_30,
   input d_accumulator_reset_31
);
  wire reset = dvco_reset || !dvco_resetn || d_digital_reset;
  assign vp_out = reset ? 1'b0 : vclk_ref;
  assign vn_out = reset ? 1'b0 : vclk_refb;
  assign d_fcw_debug_0 = 1'b0;
  assign d_fcw_debug_1 = 1'b0;
  assign d_fcw_debug_2 = 1'b0;
  assign d_fcw_debug_3 = 1'b0;
  assign d_fcw_debug_4 = 1'b0;
  assign d_fcw_debug_5 = 1'b0;
  assign d_fcw_debug_6 = 1'b0;
  assign d_fcw_debug_7 = 1'b0;
  assign d_sar_debug_0 = 1'b0;
  assign d_sar_debug_1 = 1'b0;
  assign d_sar_debug_2 = 1'b0;
  assign d_sar_debug_3 = 1'b0;
  assign d_sar_debug_4 = 1'b0;
  assign d_sar_debug_5 = 1'b0;
  assign d_sar_debug_6 = 1'b0;
  assign d_sar_debug_7 = 1'b0;
endmodule
      """
    )
  }
}
