package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chiseltest._
import edu.berkeley.cs.uciedigital.interfaces.{ProtoStack, ProtoStream, ProtoStreamType}

object RawStreamSignalCodec {
  private def decodeStack(streamId: Int): ProtoStack.Type = {
    val stackNibble = (streamId >> 4) & 0xF
    stackNibble match {
      case 0x0 => ProtoStack.stack0
      case 0x1 => ProtoStack.stack1
      case other =>
        throw new IllegalArgumentException(
          f"Unsupported stack nibble 0x$other%x in streamId=0x$streamId%x"
        )
    }
  }

  private def decodeProto(streamId: Int): ProtoStreamType.Type = {
    val protoNibble = streamId & 0xF
    protoNibble match {
      case 0x1 => ProtoStreamType.PCIe
      case 0x2 => ProtoStreamType.CXLI
      case 0x3 => ProtoStreamType.CXLC
      case 0x4 => ProtoStreamType.Stream
      case other =>
        throw new IllegalArgumentException(
          f"Unsupported proto nibble 0x$other%x in streamId=0x$streamId%x"
        )
    }
  }

  def pokeStreamFromId(stream: ProtoStream, streamId: Int): Unit = {
    stream.protoStack.poke(decodeStack(streamId))
    stream.protoType.poke(decodeProto(streamId))
  }

  def peekStreamId(stream: ProtoStream): Int = {
    val stack = stream.protoStack.peek().litValue.toInt & 0xF
    val proto = stream.protoType.peek().litValue.toInt & 0xF
    (stack << 4) | proto
  }
}

final class RawStreamDriver(
  dut: D2DMainbandModule,
  beats: Seq[RawBeat],
  injectedSourceHoldoff: () => Boolean = () => false,
  gapCyclesBeforeBeat: Int => Int = _ => 0
) {
  private var beatIdx: Int = 0
  private var activeBeat: Option[RawBeat] = None
  private var preSendGapRemaining: Int = 0

  var acceptedCount: Long = 0L

  def isDone: Boolean = beatIdx >= beats.length && activeBeat.isEmpty
  def pendingBeat: Option[RawBeat] = activeBeat
  def pendingBeatIndex: Option[Int] = activeBeat.map(_ => beatIdx)

  private def loadNextIfNeeded(): Unit = {
    if (activeBeat.isEmpty && beatIdx < beats.length) {
      activeBeat = Some(beats(beatIdx))
      preSendGapRemaining = math.max(0, gapCyclesBeforeBeat(beatIdx))
    }
  }

  /** Drives ingress side; holds beat stable until accepted. */
  def driveOneCycle(): Unit = {
    loadNextIfNeeded()

    activeBeat.foreach { beat =>
      dut.io.fdi_lp_data.poke(beat.data.U)
      RawStreamSignalCodec.pokeStreamFromId(dut.io.fdi_lp_stream, beat.streamId)
    }

    val gatedByScheduledGap = preSendGapRemaining > 0
    if (gatedByScheduledGap) {
      preSendGapRemaining -= 1
    }

    if (injectedSourceHoldoff() || gatedByScheduledGap) {
      // Injected holdoff used by testbench to emulate source-side gating.
      // Scheduled gap is a deterministic idle spacing mechanism between beats.
      dut.io.fdi_lp_valid.poke(false.B)
      dut.io.fdi_lp_irdy.poke(false.B)
      return
    }

    activeBeat match {
      case Some(beat) =>
        dut.io.fdi_lp_valid.poke(true.B)
        dut.io.fdi_lp_irdy.poke(true.B)
      case None =>
        dut.io.fdi_lp_valid.poke(false.B)
        dut.io.fdi_lp_irdy.poke(false.B)
    }
  }

  /** Move to next beat only after true acceptance. */
  def onAccepted(): Unit = {
    if (activeBeat.nonEmpty) {
      acceptedCount += 1
      beatIdx += 1
      activeBeat = None
    }
  }
}
