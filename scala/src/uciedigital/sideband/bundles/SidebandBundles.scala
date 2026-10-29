/*
  Bundles shared by the sideband layers, including the link pins (sec 4.1.5).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._

// Only the deserializer uses the sideband clock, so these lanes carry none.
class SidebandLanes(sbMsgWidth: Int) extends Bundle {
  val data = Bits(sbMsgWidth.W)
}

class SidebandLaneIO(sbParams: SidebandParams) extends Bundle {
  val tx = Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth))
  val rx = Flipped(Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth)))
}

// Half rate bits for the sideband bump drivers, which do the 2:1. `d0` goes
// out while `clk` is high and `d1` while it is low.
class SidebandTxPins(sbLinkWidth: Int) extends Bundle {
  val clk = Output(Clock())
  val d0 = Output(UInt(sbLinkWidth.W))
  val d1 = Output(UInt(sbLinkWidth.W))
  val fwClockD0 = Output(UInt(1.W))
  val fwClockD1 = Output(UInt(1.W))
}

class SidebandRxPins(sbLinkWidth: Int) extends Bundle {
  val bits = Input(UInt(sbLinkWidth.W))
  val fwClock = Input(UInt(1.W))
}

class SidebandPhyLinkIO(sbLinkWidth: Int) extends Bundle {
  val in = new SidebandRxPins(sbLinkWidth)
  val out = new SidebandTxPins(sbLinkWidth)
}
