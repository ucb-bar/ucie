package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._

/** Which path the real link's TL frames take, which sets its frame rate. */
sealed trait UcieLinkMode
object UcieLinkMode {

  /** Through the UCIe controller (`controllerSel = ucie`): limited by both the
    * sideband clock the controller runs on and the PHY's `txDivClk`.
    */
  case object Controller extends UcieLinkMode

  /** Straight onto the mainband (`mainbandMode = tl`): limited by `txDivClk`. */
  case object MainbandTL extends UcieLinkMode
}

/** Timing of a real UCIe link, for modeling it on a `UciePacketIO`.
  *
  * The model is a frame rate, applied by `UcieLinkModel` at each sender, and a
  * constant one-way latency, which whatever carries the frames applies (a
  * delay line in RTL sim, `+d2d_latency` in FireSim).
  *
  * @param mode the path the real link's frames take
  * @param laneRateGTs the mainband lane rate; one frame is one 32-bit-per-lane
  *   word, so `txDivClk` is `laneRateGTs / 32`
  * @param fSbusMHz the frequency the dies' links run at (the system bus's)
  * @param linkLatency one-way latency in `fSbusMHz` cycles, sender's protocol
  *   to receiver's. Measured on the controller + PHY RTL at 16 GT/s with
  *   `tests/ucie-latency.c` (see `UCIE_LINK_MODEL_PLAN.md`): 83 at 1 GHz, 44
  *   at 500 MHz
  * @param sidebandMHz the sideband clock, which the controller runs on
  * @param bypass leave the link unthrottled (one frame per cycle)
  */
case class UcieLinkModelParams(
    mode: UcieLinkMode = UcieLinkMode.Controller,
    laneRateGTs: Double = 16.0,
    fSbusMHz: Double = 1000.0,
    linkLatency: Int = 83,
    sidebandMHz: Double = 800.0,
    bypass: Boolean = false
) {
  require(laneRateGTs > 0 && fSbusMHz > 0 && sidebandMHz > 0)
  require(linkLatency >= 0, s"linkLatency must be non-negative: $linkLatency")
  // Faster than this, `txDivClk` outruns the sideband clock and the controller
  // overflows `rxUcieFifo`.
  require(
    mode != UcieLinkMode.Controller || txDivClkMHz <= sidebandMHz,
    s"$laneRateGTs GT/s is too fast for the controller: txDivClk " +
      s"($txDivClkMHz MHz) must not exceed the sideband clock ($sidebandMHz MHz)"
  )

  def txDivClkMHz: Double = laneRateGTs * 1000.0 / 32

  /** Frames per second the real link can carry, in MHz. */
  def fLimitMHz: Double = mode match {
    case UcieLinkMode.Controller => math.min(sidebandMHz, txDivClkMHz)
    case UcieLinkMode.MainbandTL => txDivClkMHz
  }

  /** Frames per `fSbusMHz` cycle, at most one. */
  def framesPerCycle: Double =
    if (bypass) 1.0 else math.min(1.0, fLimitMHz / fSbusMHz)

  /** The one-way latency the carrier must add, in `fSbusMHz` cycles. */
  def latencyCycles: Int = linkLatency
}

/** Limits a sender to `framesPerCycle` frames per cycle with a token bucket:
  * each cycle adds `framesPerCycle` of a frame, each frame sent takes one, and
  * `ready` is high while the bucket holds a whole frame.
  *
  * A bucket that holds a frame nobody sends stays at one frame, so an idle link
  * cannot save up a burst. Below that it keeps filling, and what it holds past
  * one frame when it becomes ready carries over to the next, so a sender that
  * always has a frame gets exactly `framesPerCycle`.
  *
  * Rates are in fixed point with `fracBits` fraction bits, rounded to nearest.
  */
class UcieTokenBucket(framesPerCycle: Double, fracBits: Int = 16)
    extends Module {
  require(framesPerCycle > 0 && framesPerCycle <= 1)
  val io = IO(new Bundle {
    /** A frame was sent this cycle (only while `ready`). */
    val fire = Input(Bool())
    val ready = Output(Bool())
  })

  val one = BigInt(1) << fracBits
  val inc = BigInt(math.round(framesPerCycle * one.toDouble))
  require(inc > 0, s"framesPerCycle $framesPerCycle rounds to 0")

  // Below one frame plus one cycle's fill, so under two frames. Starts full, so
  // the first frame goes out right away.
  val level = RegInit(one.U((fracBits + 1).W))
  io.ready := level >= one.U
  when(io.fire) {
    level := level - one.U + inc.U
  }.elsewhen(io.ready) {
    level := one.U
  }.otherwise {
    level := level + inc.U
  }
}

object UcieLinkModel {

  /** Applies `params`' frame rate to the frames `io`'s die sends, in the
    * link's clock domain. Call it after `connect` (or the like), which leaves
    * `txReady` high. The latency is the carrier's to add
    * (`params.latencyCycles`).
    */
  def apply(io: UciePacketIO, params: UcieLinkModelParams): Unit = {
    if (params.framesPerCycle >= 1.0) {
      io.txReady := true.B
    } else {
      val bucket = withClockAndReset(io.clock, io.reset) {
        Module(new UcieTokenBucket(params.framesPerCycle))
      }
      bucket.io.fire := io.tx.valid
      io.txReady := bucket.io.ready
    }
  }

  /** Joins two dies' links as the real link would: each sender limited to
    * `params`' rate, and each frame arriving `params.latencyCycles` after it
    * was sent. For RTL sims; both links must share a clock.
    */
  def connect(a: UciePacketIO, b: UciePacketIO, params: UcieLinkModelParams)
      : Unit = {
    Seq(a, b).foreach(apply(_, params))
    withClockAndReset(a.clock, a.reset) {
      for ((from, to) <- Seq((a, b), (b, a))) {
        to.rx := ShiftRegister(
          from.tx,
          params.latencyCycles,
          0.U.asTypeOf(from.tx),
          true.B
        )
      }
    }
  }
}
