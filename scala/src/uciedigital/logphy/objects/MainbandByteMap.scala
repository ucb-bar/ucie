/*
  The RDI byte to Lane map for each Table 4-9 code, shared by Tx and Rx.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

class MainbandByteMap(nLanes: Int, serRatio: Int, nBytes: Int) {
  val bytesPerLane: Int = serRatio / 8

  case class Width(code: String, active: Int, firstLane: Int) {
    val codeBits: Int = Integer.parseInt(code.drop(1), 2)
    val bytesPerBeat: Int = active * bytesPerLane
    val beats: Int = nBytes / bytesPerBeat
    def carries(lane: Int): Boolean =
      lane >= firstLane && lane < firstLane + active

    // `active` is a power of two, so this sum is only a bit concatenation.
    def sourceByte(beat: Int, lane: Int, slot: Int): Option[Int] =
      if (!carries(lane)) None
      else Some(beat * bytesPerBeat + (lane - firstLane) + slot * active)

    // None outside the reversal span, where nothing is sent.
    def mirror(n: Int)(lane: Int): Option[Int] =
      if (lane < n) Some(n - 1 - lane) else None
  }

  // 000b is "Degrade not possible" (sec 4.5.3.3.6), so it has no map.
  val widths: Seq[Width] =
    Seq("b011", "b001", "b010", "b100", "b101")
      .map(PatternLaneMap.span(_, nLanes))
      .map(w => Width(w.code, w.active, w.firstLane))
      .filter(w => w.active <= nLanes && w.firstLane + w.active <= nLanes)

  widths.foreach { w =>
    require(
      nBytes % w.bytesPerBeat == 0,
      s"RDI nBytes must divide cleanly across ${w.active} active Lanes"
    )
  }

  val maxBeats: Int = widths.map(_.beats).max
  val beatSelW: Int = log2Ceil(maxBeats)

  // Both fields come from registers, so the index is known early.
  def key(code: String, beat: Int): UInt =
    ((BigInt(code.drop(1), 2) << beatSelW) | beat).U((3 + beatSelW).W)

  def sel(code: UInt, beatCtr: UInt): UInt =
    if (beatSelW == 0) code else Cat(code, beatCtr(beatSelW - 1, 0))

  def beatsFor(code: UInt): UInt =
    MuxLookup(code, (nBytes / (nLanes * bytesPerLane)).U)(
      widths.map(w => w.codeBits.U(3.W) -> w.beats.U)
    )
}
