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

  /** Thermometer code with the low `count` of `segments` segments enabled, on a
    * rail where a one enables a segment (`ENN`, `ENN_EQ`, `Dctrl`).
    */
  def thermometer(count: Int, segments: Int): BigInt = {
    require(
      count >= 0 && count <= segments,
      s"count $count out of range for $segments segments"
    )
    (BigInt(1) << count) - 1
  }

  /** Thermometer code with the low `count` of `segments` segments enabled, on a
    * rail where a zero enables a segment (`ENP`, `ENP_EQ`).
    */
  def thermometerB(count: Int, segments: Int): BigInt =
    ((BigInt(1) << segments) - 1) ^ thermometer(count, segments)
}

/** Control pins of a [[TxLane]], driven straight through to the tile.
  *
  * These are the codes as the pins carry them: thermometer coded, and each with
  * its own polarity. Software writes what it wants the tile to see, so any
  * pattern the tile accepts is reachable, including ones no binary code could
  * express.
  *
  * Use [[TxLane.thermometer]] and [[TxLane.thermometerB]] to build a code from
  * a segment count rather than open coding the polarity.
  */
class TxLaneCtlIO extends Bundle {

  /** Pull-up driver impedance. A one turns its segment off. */
  val ENP = UInt(TxLane.DriverSegments.W)

  /** Pull-down driver impedance. A one turns its segment on. */
  val ENN = UInt(TxLane.DriverSegments.W)

  /** Pull-up impedance of the equalizer branch. A one turns its segment off. */
  val ENP_EQ = UInt(TxLane.EqSegments.W)

  /** Pull-down impedance of the equalizer branch. A one turns its segment on.
    */
  val ENN_EQ = UInt(TxLane.EqSegments.W)

  /** Delay taps on the high speed clock. A one enables its tap. */
  val Dctrl = UInt(TxLane.DelayTaps.W)
}

object TxLaneCtlIO {

  /** Every driver segment off and no added clock delay, which is what a lane
    * resets to so that it stays quiet until software brings it up.
    */
  def off: TxLaneCtlIO = codes(driver = 0, eq = 0, delay = 0)

  /** Every main driver segment on, equalizer branch off, no added clock delay.
    */
  def full: TxLaneCtlIO =
    codes(driver = TxLane.DriverSegments, eq = 0, delay = 0)

  /** Control with `driver` main segments, `eq` equalizer segments, and `delay`
    * clock taps enabled, with each rail's polarity applied.
    */
  def codes(driver: Int, eq: Int, delay: Int): TxLaneCtlIO =
    (new TxLaneCtlIO).Lit(
      _.ENP -> TxLane.thermometerB(driver, TxLane.DriverSegments).U,
      _.ENN -> TxLane.thermometer(driver, TxLane.DriverSegments).U,
      _.ENP_EQ -> TxLane.thermometerB(eq, TxLane.EqSegments).U,
      _.ENN_EQ -> TxLane.thermometer(eq, TxLane.EqSegments).U,
      _.Dctrl -> TxLane.thermometer(delay, TxLane.DelayTaps).U
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
}

/** Port names match the tile's pins verbatim. VDDQ (output driver), VDD
  * (pre-driver and digital logic), and VSS are pins on the tile but are omitted
  * here; they are connected by the physical flow.
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
    val D2D_TX = Output(Bool())
  })

  override val desiredName = "tx_lane"

  if (includeDefaultModels) {
    addResource("/vsrc/tx_lane.v")
  }
}
