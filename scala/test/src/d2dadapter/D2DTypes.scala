package edu.berkeley.cs.uciedigital.d2dadapter

final case class RawBeat(data: BigInt, streamId: Int)

final case class AcceptedBeat(
  seq: Long,
  data: BigInt,
  streamId: Int,
  cycleAccepted: Long
)

object RawStreamIds {
  // UCIe Streaming Raw Format FDI stream IDs.
  val Stack0Streaming: Int = 0x04
  val Stack1Streaming: Int = 0x14

  // Used when the monitored egress boundary has no stream signal.
  val UnknownStreamId: Int = -1
}
