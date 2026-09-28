package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._
import chisel3.experimental.noPrefix


object RxDataLane {

  /** Taps on the RX lane's local delay line, thermometer coded. Same count as
    * the TX tile's so the two trims are programmed the same way.
    */
  val DelayTaps = 32

  /** Bits the deserializer hands over at once. */
  val SerdesRatio = 32

  /** Termination control pins on the macro. */
  val TerminationBits = 20

  /** Reference ladder select pins on the macro. */
  val VrefBits = 7

  /** Drives the pins both RX tiles have: termination, the sampling front end,
    * and the reference ladder. Only the delay line is the data tile's alone.
    *
    * The macro splits every bus into one pin per bit. Chisel names a `Vec`
    * element `<field>_<index>`, which is that naming exactly -- so each bus is
    * declared once as a `Vec` rather than enumerated pin by pin, and nothing
    * has to reassemble it on the way back out.
    */
  private[macros] def connectFrontEnd(
      zen: Bool,
      zctl: Vec[Bool],
      aEn: Bool,
      aPc: Bool,
      bEn: Bool,
      bPc: Bool,
      selA: Bool,
      vrefSel: Vec[Bool],
      ctl: RxLaneCtlIO
  ): Unit = {
    zen := ctl.zen
    // The macro wants a thermometer code across its termination pins; the
    // register holds how many to turn on.
    zctl := VecInit(
      (((1.U << ctl.zctl) - 1.U)(TerminationBits - 1, 0)).asBools
    )
    aEn := ctl.afe.aEn
    aPc := ctl.afe.aPc
    bEn := ctl.afe.bEn
    bPc := ctl.afe.bPc
    selA := ctl.afe.selA
    vrefSel := VecInit(ctl.vref_sel.asBools)
  }
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

/** One UCIe RX data tile: termination, the reference ladder, the sampling
  * front end, a local delay line on the sampling clock, and the 1:32
  * deserializer.
  *
  * This is the macro itself rather than a wrapper around it. Its buses are
  * `Vec`s because the macro splits them into one pin per bit, and a `Vec`
  * emits exactly that naming.
  */
class RxDataLane(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val din = Input(Bool())
    val dout = Output(Vec(RxDataLane.SerdesRatio, Bool()))
    val divclk = Output(Bool())
    val clk = Input(Clock())
    val rstb = Input(AsyncReset())
    val Dctrl = Input(Vec(RxDataLane.DelayTaps, Bool()))
    val zen = Input(Bool())
    val zctl = Input(Vec(RxDataLane.TerminationBits, Bool()))
    val a_en = Input(Bool())
    val a_pc = Input(Bool())
    val b_en = Input(Bool())
    val b_pc = Input(Bool())
    val sel_a = Input(Bool())
    val vref_sel = Input(Vec(RxDataLane.VrefBits, Bool()))
  })

  override val desiredName = "rx_data_lane"

  if (includeDefaultModels) {
    addResource("/vsrc/rx_data_lane.v")
  }

  /** Drives this lane's control pins from its control bundle. */
  def connectCtl(ctl: RxLaneCtlIO): Unit = {
    io.Dctrl := VecInit(ctl.Dctrl.asBools)
    RxDataLane.connectFrontEnd(
      io.zen,
      io.zctl,
      io.a_en,
      io.a_pc,
      io.b_en,
      io.b_pc,
      io.sel_a,
      io.vref_sel,
      ctl
    )
  }
}

/** One UCIe RX clock tile: the same front end, recovering a forwarded clock
  * rather than a word, with a gate on the clock it hands out.
  */
class RxClkLane(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clkin = Input(Clock())
    val clkout = Output(Clock())
    val clk_gate_en = Input(Bool())
    val zen = Input(Bool())
    val zctl = Input(Vec(RxDataLane.TerminationBits, Bool()))
    val a_en = Input(Bool())
    val a_pc = Input(Bool())
    val b_en = Input(Bool())
    val b_pc = Input(Bool())
    val sel_a = Input(Bool())
    val vref_sel = Input(Vec(RxDataLane.VrefBits, Bool()))
  })

  override val desiredName = "rx_clock_lane"

  if (includeDefaultModels) {
    addResource("/vsrc/rx_clock_lane.v")
  }

  /** Drives this lane's control pins. A clock tile has no delay line of its
    * own, so the bundle's `Dctrl` goes nowhere; everything else is the data
    * tile's.
    */
  def connectCtl(ctl: RxLaneCtlIO): Unit =
    RxDataLane.connectFrontEnd(
      io.zen,
      io.zctl,
      io.a_en,
      io.a_pc,
      io.b_en,
      io.b_pc,
      io.sel_a,
      io.vref_sel,
      ctl
    )
}
