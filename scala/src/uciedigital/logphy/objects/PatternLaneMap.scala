/*
  Table 4-9, the Standard Package logical Lane map.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.common.LinkWidth

object PatternLaneMap {

  case class LaneSpan(code: String, active: Int, firstLane: Int) {
    def codeBits: Int = Integer.parseInt(code.drop(1), 2)

    def mask: BigInt = ((BigInt(1) << active) - 1) << firstLane
  }

  def spans(nLanes: Int): Seq[LaneSpan] = Seq(
    LaneSpan("b000", 0, 0),
    LaneSpan("b001", 8, 0),
    LaneSpan("b010", 8, 8),
    LaneSpan("b011", nLanes, 0),
    LaneSpan("b100", 4, 0),
    LaneSpan("b101", 4, 4)
  )

  def span(code: String, nLanes: Int): LaneSpan =
    spans(nLanes)
      .find(_.code == code)
      .getOrElse(
        throw new IllegalArgumentException(
          s"$code is not a Table 4-9 encoding"
        )
      )

  // 011b means all Lanes of this module, so UCIe-S x8 reads it as 001b.
  // Note: datapath only. The wire keeps 011b (sec 4.5.3.3.6 step 3).
  def datapathCode(code: UInt, ucieSx8: Bool): UInt =
    Mux(ucieSx8 && code === "b011".U, "b001".U(3.W), code)

  def decodeLaneMap(code: UInt, nLanes: Int): UInt = {
    require(nLanes <= 16, "PatternLaneMap only supports up to 16 lanes")

    val fullMask = MuxLookup(code, 0.U(16.W))(
      spans(nLanes).map(w => w.code.U -> w.mask.U(16.W))
    )

    fullMask(nLanes - 1, 0)
  }

  def activeLaneCount(code: UInt, nLanes: Int): UInt = {
    val width = log2Ceil(nLanes + 1)
    MuxLookup(code, 0.U(width.W))(
      spans(nLanes).map(w => w.code.U -> w.active.U(width.W))
    )
  }

  // Reserved codes give x4, because claiming extra Lanes loses data.
  def linkWidth(code: UInt, ucieSx8: Bool): LinkWidth.Type = {
    val width = WireDefault(LinkWidth.x4)
    switch(code) {
      is("b001".U) { width := LinkWidth.x8 }
      is("b010".U) { width := LinkWidth.x8 }
      is("b100".U) { width := LinkWidth.x4 }
      is("b101".U) { width := LinkWidth.x4 }
      is("b011".U) {
        width := Mux(ucieSx8, LinkWidth.x8, LinkWidth.x16)
      }
    }
    width
  }

  // sec 4.5.3.3.6 step 3, a partner 011b keeps this side's Transmitter code.
  def receiverLaneMap(remoteCode: UInt, localTxCode: UInt): UInt =
    Mux(remoteCode === "b011".U, localTxCode, remoteCode)

  // An id outside the module is not relevant, so no repair (sec 9.5.3.36).
  case class LaneHalf(relevant: Bool, lower: Bool, upper: Bool)

  def laneHalf(laneId: UInt, ucieSx8: Bool, nLanes: Int): LaneHalf = {
    val lanes = Mux(ucieSx8, 8.U, nLanes.U)
    val half = Mux(ucieSx8, 4.U, (nLanes / 2).U)
    val relevant = laneId < lanes
    LaneHalf(
      relevant = relevant,
      lower = relevant && (laneId < half),
      upper = relevant && (laneId >= half)
    )
  }

  // One step only, x16 to x8 or x8 to x4 (sec 4.3.7), else 000b.
  def degradeTarget(
      code: UInt,
      faultLower: Bool,
      faultUpper: Bool,
      ucieSx8: Bool
  ): UInt = {
    // No fault keeps the code. Tri-stated Lanes were not tested (sec 4.3.7).
    val target = WireDefault(code)
    switch(code) {
      is("b011".U) {
        when(faultLower && faultUpper) {
          target := "b000".U
        }.elsewhen(faultUpper) {
          target := Mux(ucieSx8, "b100".U, "b001".U)
        }.elsewhen(faultLower) {
          target := Mux(ucieSx8, "b101".U, "b010".U)
        }
      }

      is("b001".U) { when(faultLower) { target := "b000".U } }
      is("b100".U) { when(faultLower) { target := "b000".U } }
      is("b010".U) { when(faultUpper) { target := "b000".U } }
      is("b101".U) { when(faultUpper) { target := "b000".U } }
    }
    target
  }

  // Only kept Lanes count, because sec 4.3.7 tri-states the rest.
  def faultHalves(
      code: UInt,
      perLaneResults: Seq[Bool],
      nLanes: Int,
      ucieSx8: Bool
  ): (Bool, Bool) = {
    val kept = decodeLaneMap(datapathCode(code, ucieSx8), nLanes)

    // A UCIe-S x8 Link is Lanes 0-7 (sec 5.7.3.2), so its halves are 0-3, 4-7.
    val half = Mux(ucieSx8, 4.U, (nLanes / 2).U)
    def failIn(inRange: Int => Bool): Bool =
      perLaneResults.zipWithIndex
        .map { case (pass, i) => kept(i) && inRange(i) && !pass }
        .reduce(_ || _)

    val whole = code === "b011".U
    val anyKeptFail = failIn(_ => true.B)

    val isUpperHalfCode = (code === "b010".U) || (code === "b101".U)

    val lower =
      Mux(whole, failIn(i => i.U < half), anyKeptFail && !isUpperHalfCode)
    val upper =
      Mux(whole, failIn(i => i.U >= half), anyKeptFail && isUpperHalfCode)
    (lower, upper)
  }
}
