package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

object PadDriver {

  /** Segments in the driver's pull-up and pull-down stacks. `pu_ctl` and
    * `pd_ctl` are counts of enabled segments, thermometer expanded onto this
    * many pins.
    */
  val DriverSegments = 40
}

/** Impedance control for a [[PadDriver]], which is a separate cell from the
  * driver inside a [[TxLane]] and keeps its own control encoding.
  */
class PadDriverCtlIO extends Bundle {
  val pu_ctl = UInt(6.W)
  val pd_ctl = UInt(6.W)
  val en = Bool()
  val en_b = Bool()
}

object PadDriverCtlIO {

  /** Every segment off and the driver disabled. */
  def off: PadDriverCtlIO = (new PadDriverCtlIO).Lit(
    _.pu_ctl -> 0.U,
    _.pd_ctl -> 0.U,
    _.en -> false.B,
    _.en_b -> true.B
  )

  /** Every segment on, which is what bring-up drives. */
  def full: PadDriverCtlIO = (new PadDriverCtlIO).Lit(
    _.pu_ctl -> 63.U,
    _.pd_ctl -> 63.U,
    _.en -> true.B,
    _.en_b -> false.B
  )
}

class PadDriverIO extends Bundle {
  val din = Input(Bool())
  val dout = Output(Bool())
  val ctl = Input(new PadDriverCtlIO)
}

/** A pad output driver, nominally 50 ohms, used wherever a signal leaves the
  * die outside the mainband TX tiles: the sideband bumps and the debug clocks.
  */
class PadDriver(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new PadDriverIO)

  val verilogBlackBox = Module(new VerilogPadDriver)
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

class VerilogPadDriver(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
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
  })

  override val desiredName = "pad_driver"

  if (includeDefaultModels) {
    addResource("/vsrc/pad_driver.v")
  }
}
