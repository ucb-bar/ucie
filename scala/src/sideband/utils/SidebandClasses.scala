package edu.berkeley.cs.uciedigital.sideband

case class SidebandParams(
  // val NC_width: Int = 32, // This is merged into the FDI Params
  val sbNodeMsgWidth: Int = 128, // Internal SB msg widths in individual layers
  
  val maxCrd: Int = 32,

  val sbLinkAsyncQueueDepth: Int = 8,

  val sbLinkWidth: Int = 1
)

// Used to size the queues in the SidebandPriorityQueue
case class SidebandPriorityQueueDepths (
  messageRequestOrResponse: Int = 32,
  regAccessCompletion: Int = 32,
  regAccessRequest: Int = 32,
  other: Int = 32
)