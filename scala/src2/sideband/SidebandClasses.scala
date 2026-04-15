package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._

case class SidebandParams(
  // val NC_width: Int = 32, // This is merged into the FDI Params
  val sbNodeMsgWidth: Int =
    128, // Internal SB msg widths in individual layers
  val maxCrd: Int = 32,

  val sbLinkAsyncQueueDepth: Int = 8,

  val sbLinkWidth: Int = 1
)
