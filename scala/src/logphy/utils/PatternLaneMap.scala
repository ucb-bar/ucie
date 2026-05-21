package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

object PatternLaneMap {
  def decodeLaneMap(code: UInt, nLanes: Int): UInt = {
    require(nLanes <= 16, "PatternLaneMap only supports up to 16 lanes")

    val fullMask = MuxLookup(code, 0.U(16.W))(Seq(
      "b000".U -> "h0000".U(16.W),
      "b001".U -> "h00FF".U(16.W),
      "b010".U -> "hFF00".U(16.W),
      "b011".U -> "hFFFF".U(16.W),
      "b100".U -> "h000F".U(16.W),
      "b101".U -> "h00F0".U(16.W)
    ))

    fullMask(nLanes - 1, 0)
  }

  def activeLaneIndex(code: UInt, physicalLane: Int, nLanes: Int): UInt = {
    val idxWidth = log2Ceil(math.max(2, nLanes))

    MuxLookup(code, 0.U(idxWidth.W))(Seq(
      "b001".U -> physicalLane.U(idxWidth.W),
      "b010".U -> math.max(0, physicalLane - 8).U(idxWidth.W),
      "b011".U -> physicalLane.U(idxWidth.W),
      "b100".U -> physicalLane.U(idxWidth.W),
      "b101".U -> math.max(0, physicalLane - 4).U(idxWidth.W)
    ))
  }
}
