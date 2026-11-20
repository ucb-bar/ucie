package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

object TxLane {

  /** Bits of parallel data (`DataIN`) the tile consumes per divided clock
    * cycle. The serializer is 16:1 and double data rate, so the tile emits two
    * bits per `CK` period.
    */
  val SerdesRatio = 32

  /** Segments in the output driver (`ENP`/`ENN`). */
  val DriverSegments = 9

  /** Segments in the capacitive-peaking equalizer branch (`ENP_EQ`/`ENN_EQ`).
    */
  val EqSegments = 4

  /** Taps in the delay line on the high speed clock (`Dctrl`). */
  val DelayTaps = 32

  /** Thermometer code with the low `count` of `segments` segments enabled.
    * Every control rail on the tile but `ENP_EQ` is active high, so this is the
    * code for all of them; `ENP_EQ` takes [[thermometerLow]].
    */
  def thermometer(count: Int, segments: Int): BigInt = {
    require(
      count >= 0 && count <= segments,
      s"count $count out of range for $segments segments"
    )
    (BigInt(1) << count) - 1
  }

  /** Active low thermometer code: the low `count` of `segments` segments
    * enabled by a zero, every other bit one. The code for `ENP_EQ`.
    */
  def thermometerLow(count: Int, segments: Int): BigInt =
    thermometer(count, segments) ^ thermometer(segments, segments)
}

/** Control pins of a [[TxLane]], driven straight through to the tile.
  *
  * These are the codes as the pins carry them: thermometer coded, and active
  * high, a one enabling its segment or tap, on every rail but `ENP_EQ`. That
  * one gates the equalizer's PMOS headers directly, so it is active low: a zero
  * enables its segment. Nothing between the register and the pin inverts a
  * rail, and every bit is its own, so any pattern the tile accepts is
  * reachable, including ones no binary code could express, and the pull-up and
  * pull-down stacks need not match.
  *
  * There is no separate driver enable. The driver is high impedance when every
  * segment of `ENP`, `ENN`, `ENP_EQ` and `ENN_EQ` is off: `ENP`, `ENN` and
  * `ENN_EQ` all zeros, and `ENP_EQ` all ones.
  *
  * Use [[TxLane.thermometer]] to build a code from a segment count, or
  * [[TxLane.thermometerLow]] for `ENP_EQ`.
  */
class TxLaneCtlIO extends Bundle {

  /** Which edge of the tile's clock synchronizes `RST_async` into the reset of
    * its clock dividers: the select of the tile's `MUX_for_FF_Reset`, which
    * clocks the two-flop synchronizer from one phase of the duty-cycle
    * corrected clock or its complement. Either makes a clean reset; this picks
    * the half cycle the dividers come out of it on.
    *
    * First in the bundle, so it is the packed word's top bit and every rail
    * below keeps the position it had before the tile had this pin.
    */
  val Edge_Sel = Bool()

  /** Pull-up driver impedance. A one turns its segment on. */
  val ENP = UInt(TxLane.DriverSegments.W)

  /** Pull-down driver impedance. A one turns its segment on. */
  val ENN = UInt(TxLane.DriverSegments.W)

  /** Pull-up impedance of the equalizer branch. Active low: a zero turns its
    * segment on.
    */
  val ENP_EQ = UInt(TxLane.EqSegments.W)

  /** Pull-down impedance of the equalizer branch. A one turns its segment on.
    */
  val ENN_EQ = UInt(TxLane.EqSegments.W)

  /** Delay taps on the high speed clock. A one enables its tap. */
  val Dctrl = UInt(TxLane.DelayTaps.W)
}

object TxLaneCtlIO {

  /** Every driver segment off, so the driver is high impedance, and no added
    * clock delay. What a lane resets to, so that it stays quiet until software
    * brings it up.
    */
  def off: TxLaneCtlIO = codes(driver = 0, eq = 0, delay = 0)

  /** Every main driver segment on, equalizer branch off, no added clock delay.
    */
  def full: TxLaneCtlIO =
    codes(driver = TxLane.DriverSegments, eq = 0, delay = 0)

  /** Control with `driver` segments of each main stack, `eq` of each equalizer
    * stack, and `delay` clock taps enabled.
    */
  def codes(driver: Int, eq: Int, delay: Int): TxLaneCtlIO =
    raw(
      enp = TxLane.thermometer(driver, TxLane.DriverSegments),
      enn = TxLane.thermometer(driver, TxLane.DriverSegments),
      enpEq = TxLane.thermometerLow(eq, TxLane.EqSegments),
      ennEq = TxLane.thermometer(eq, TxLane.EqSegments),
      dctrl = TxLane.thermometer(delay, TxLane.DelayTaps)
    )

  /** Control with each rail given as its pins carry it, for what [[codes]] does
    * not build: unequal stacks, or segments other than the low ones. `edgeSel`
    * is `Edge_Sel`, low unless given.
    */
  def raw(
      enp: BigInt,
      enn: BigInt,
      enpEq: BigInt,
      ennEq: BigInt,
      dctrl: BigInt,
      edgeSel: Boolean = false
  ): TxLaneCtlIO =
    (new TxLaneCtlIO).Lit(
      _.Edge_Sel -> edgeSel.B,
      _.ENP -> enp.U(TxLane.DriverSegments.W),
      _.ENN -> enn.U(TxLane.DriverSegments.W),
      _.ENP_EQ -> enpEq.U(TxLane.EqSegments.W),
      _.ENN_EQ -> ennEq.U(TxLane.EqSegments.W),
      _.Dctrl -> dctrl.U(TxLane.DelayTaps.W)
    )
}

class TxLaneIO extends Bundle {

  /** Asynchronous reset for the tile's clock dividers (`RST_async`). High holds
    * the divided clocks at zero; low lets them run.
    */
  val rst = Input(AsyncReset())

  /** High speed clock (`CK`). The tile makes its own complement internally. */
  val clk = Input(Clock())
  val din = Input(Bits(TxLane.SerdesRatio.W))

  /** Tile output to the die-to-die channel (`D2D_TX`). */
  val dout = Output(Bool())
  val ctl = Input(new TxLaneCtlIO)
}

/** One UCIe TX tile: a 16:1 double data rate serializer, a delay line on the
  * high speed clock, and the N-over-N output driver with its capacitive peaking
  * equalizer branch.
  *
  * Control reaches the tile's pins unchanged, so what the register file holds
  * is what the driver sees.
  */
class TxLane(implicit includeDefaultModels: Boolean = false) extends RawModule {
  val io = IO(new TxLaneIO)

  val verilogBlackBox = Module(new VerilogTxLane)
  verilogBlackBox.io.RST_async := io.rst
  verilogBlackBox.io.CK := io.clk
  verilogBlackBox.io.DataIN := io.din
  io.dout := verilogBlackBox.io.D2D_TX

  verilogBlackBox.io.ENP := io.ctl.ENP
  verilogBlackBox.io.ENN := io.ctl.ENN
  verilogBlackBox.io.ENP_EQ := io.ctl.ENP_EQ
  verilogBlackBox.io.ENN_EQ := io.ctl.ENN_EQ
  verilogBlackBox.io.Dctrl := io.ctl.Dctrl
  verilogBlackBox.io.Edge_Sel := io.ctl.Edge_Sel
}

/** The delivered TX tile, `TX`. Port names match its pins verbatim.
  *
  * The tile's netlist has more pins than these: VDDQ (output driver), VDD
  * (pre-driver and digital logic) and VSS, which the physical flow connects,
  * and some thirty internal nodes brought out as outputs -- the divided clocks,
  * the duty-cycle corrector's taps, the serializer's intermediate stages, and
  * `rst`, the divider reset the tile synchronizes from `RST_async`. Nothing
  * outside the tile reads those, so they are left off here and unconnected.
  * `rst` in particular is the tile's own output, not a second reset input.
  */
class VerilogTxLane(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val DataIN = Input(Bits(TxLane.SerdesRatio.W))
    val CK = Input(Clock())
    val Dctrl = Input(Bits(TxLane.DelayTaps.W))
    val ENP = Input(Bits(TxLane.DriverSegments.W))
    val ENN = Input(Bits(TxLane.DriverSegments.W))
    val ENP_EQ = Input(Bits(TxLane.EqSegments.W))
    val ENN_EQ = Input(Bits(TxLane.EqSegments.W))
    val RST_async = Input(AsyncReset())
    val Edge_Sel = Input(Bool())
    val D2D_TX = Output(Bool())
  })

  override val desiredName = "TX"

  if (includeDefaultModels) {
    addResource("/vsrc/TX.v")
  }
}
