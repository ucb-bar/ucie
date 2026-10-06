package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.phy.Phy

object RxDataLane {

  /** Taps on the RX lane's local delay line, thermometer coded. Same count as
    * the TX tile's so the two trims are programmed the same way.
    */
  val DelayTaps = 32

  /** Termination control pins on the macro. */
  val TerminationBits = 20

  /** Reference ladder select pins on the macro. */
  val VrefBits = 7

  /** Thermometer code for `count` of [[DelayTaps]] taps, as the macro's pins
    * want it: the register holds how many to turn on, the pins want one bit
    * each. The TX side does the same expansion in Scala
    * ([[edu.berkeley.cs.uciedigital.phy.macros.TxLane.thermometer]]) because
    * its codes are literals; these come from a register, so it happens here.
    */
  def thermometer(count: UInt): UInt =
    ((1.U << count(log2Ceil(DelayTaps + 1) - 1, 0)) - 1.U)(DelayTaps - 1, 0)

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

/** The MBINIT.REPAIRCLK sampling tap a measured lane carries.
  *
  * A second 1:32 deserializer behind the same analog front end, clocked by the
  * gated repair clock rather than by the RX lane clock. Two things make it
  * necessary rather than convenient. A forwarded-clock lane cannot be sampled
  * by the clock it is recovering -- that is circular, and if this is the lane
  * that is broken there is no recovered clock at all. And REPAIRCLK runs before
  * any sampling phase has been trained, so one sample per UI lands wherever it
  * lands; the repair clock is a faster division of the same main clock, so the
  * tap oversamples and the measurement stops depending on phase.
  *
  * Only the forwarded-clock lanes carry one. They have no data path at all, so
  * there is nothing else to measure them with. Track is measured through the
  * deserializer it already has, by switching that lane's sampling clock onto
  * the repair clock for the window -- see `docs/repairclk-track-mux.md`.
  */
class RxRepairTapIO extends Bundle {
  val clk = Input(Clock())
  val rstb = Input(AsyncReset())

  /** This tap's own delay trim, thermometer coded. Separate from the lane's
    * `Dctrl` because the tap and the trained datapath sample the same bump on
    * different clocks, so they have nothing to share.
    */
  val Dctrl = Input(Vec(RxDataLane.DelayTaps, Bool()))
  val dout = Output(Vec(Phy.SerdesRatio, Bool()))
  val divclk = Output(Bool())
}

object RxRepairTapIO {

  /** Drives a tap's pins from a control bundle, and ties off a lane that has no
    * tap so the caller does not have to care which it has.
    */
  def connect(
      tap: RxRepairTapIO,
      clk: Clock,
      rstb: AsyncReset,
      dl: UInt
  ): Unit = {
    tap.clk := clk
    tap.rstb := rstb
    tap.Dctrl := VecInit(dl.asBools)
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

/** One UCIe RX data tile: termination, the reference ladder, the sampling front
  * end, a local delay line on the sampling clock, and the 1:32 deserializer.
  *
  * This is the macro itself rather than a wrapper around it. Its buses are
  * `Vec`s because the macro splits them into one pin per bit, and a `Vec` emits
  * exactly that naming.
  */
class RxDataLane(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val din = Input(Bool())
    // The deserializer hands over exactly what the serializer sent, so this
    // is the link's ratio rather than a number of its own.
    val dout = Output(Vec(Phy.SerdesRatio, Bool()))
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
    addResource("/vsrc/ucie_des32.v")
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

    /** Always present: the tap is the only way a forwarded-clock bump can be
      * measured at all.
      */
    val repair = new RxRepairTapIO
  })

  override val desiredName = "rx_clock_lane"

  if (includeDefaultModels) {
    addResource("/vsrc/rx_clock_lane.v")
    addResource("/vsrc/ucie_des32.v")
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
