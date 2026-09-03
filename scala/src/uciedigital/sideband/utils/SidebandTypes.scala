package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._

object SBRxTxMode extends ChiselEnum {
  val RAW, PACKET = Value
}

// [bugfix] Capture packet type here instead of passing it combinationally
class SbLinkRxWord(msgW: Int) extends Bundle {
  val data = UInt(msgW.W)
  val isRaw = Bool()
}
