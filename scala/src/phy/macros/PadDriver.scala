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

  /** Thermometer code with the low `count` segments enabled. Counts above
    * [[DriverSegments]] saturate rather than wrapping around.
    */
  def thermometer(count: UInt): UInt =
    VecInit((0 until DriverSegments).map(i => count > i.U)).asUInt
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
/** A pad output driver, nominally 50 ohms, used wherever a signal leaves the
  * die outside the mainband TX tiles: the sideband bumps and the debug clocks.
  */
class PadDriver(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new PadDriverIO)

  val verilogBlackBox = Module(new VerilogPadDriver)
  verilogBlackBox.io.din := io.din
  io.dout := verilogBlackBox.io.dout
  verilogBlackBox.io.pu_ctl := PadDriver.thermometer(io.ctl.pu_ctl)
  verilogBlackBox.io.pd_ctlb := ~PadDriver.thermometer(io.ctl.pd_ctl)
  verilogBlackBox.io.en := io.ctl.en
  verilogBlackBox.io.en_b := io.ctl.en_b
}

class VerilogPadDriver(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val din = Input(Bool())
    val dout = Output(Bool())

    /** Thermometer coded, active high: a one enables its pull-up segment. */
    val pu_ctl = Input(UInt(PadDriver.DriverSegments.W))

    /** Thermometer coded, active low: a zero enables its pull-down segment. */
    val pd_ctlb = Input(UInt(PadDriver.DriverSegments.W))
    val en = Input(Bool())
    val en_b = Input(Bool())
  })

  override val desiredName = "pad_driver"

  if (includeDefaultModels) {
    addResource("/vsrc/pad_driver.v")
  }
}
