/*
  Serializer and deserializer for sideband packets over FDI and RDI. The data
  phases follow the header phases only when the opcode has data (sec 7.1.4).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import chisel3.util._
import scala.math.max

object SidebandInterfaceFraming {
  def headerOnly(opcode: UInt): Bool =
    SBMsgOpcode.OpsWithoutData.map(_.asUInt === opcode).reduce(_ || _)
}

class SidebandInterfaceSerializer(sbMsgWidth: Int, ncWidth: Int)
    extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(sbMsgWidth.W)))
    val out = Valid(UInt(ncWidth.W))
  })

  require(sbMsgWidth == 128 && Seq(8, 16, 32).contains(ncWidth))

  val numBeats = (sbMsgWidth / ncWidth)
  val maxBeats = numBeats - 1
  val headerBeats = 64 / ncWidth
  val counterWidth = max(1, log2Ceil(numBeats))

  val beatCounter = RegInit(0.U(counterWidth.W))
  val dataReg = RegInit(0.U(sbMsgWidth.W))
  val inProgress = RegInit(false.B)
  val lastBeat = RegInit(maxBeats.U(counterWidth.W))
  val isLastBeat = WireDefault(beatCounter === lastBeat)
  val loadLastBeat = Mux(
    SidebandInterfaceFraming.headerOnly(io.in.bits(4, 0)),
    (headerBeats - 1).U,
    maxBeats.U
  )

  io.out.bits := dataReg(ncWidth - 1, 0)
  io.out.valid := inProgress

  io.in.ready := !inProgress || isLastBeat

  when(inProgress) {
    when(!isLastBeat) {
      // Shift by the Int ncWidth. A UInt amount may build a barrel shifter.
      dataReg := dataReg >> ncWidth
      beatCounter := beatCounter + 1.U
    }.otherwise {
      when(io.in.valid) { // Immediately loads back-to-back valid packet
        dataReg := io.in.bits
        lastBeat := loadLastBeat
        beatCounter := 0.U
      }.otherwise {
        inProgress := false.B
        beatCounter := 0.U
      }
    }
  }

  when(!inProgress && io.in.valid) {
    dataReg := io.in.bits
    lastBeat := loadLastBeat
    inProgress := true.B
    beatCounter := 0.U
  }

  block(Verification) {
    block(Verification.Assert) {
      // Verilator allows only a boolean consequent. An accept in the last
      // `headerBeats` cycles, the shortest packet, means a beat is out now.
      val acceptedWithinLastBeats = Seq
        .iterate[Bool](io.in.fire, headerBeats + 1)(prev =>
          RegNext(prev, false.B)
        )
        .tail
        .reduce(_ || _)
      AssertProperty(
        Sequence.BoolSequence(acceptedWithinLastBeats) |->
          Sequence.BoolSequence(io.out.valid),
        label = Some("SerializerEmitsAllBeatsAfterAccept")
      )
    }
    block(Verification.Cover) {
      cover(io.in.fire, "InterfaceSerializerInputFire")
      cover(io.out.valid && isLastBeat, "InterfaceSerializerLastBeat")
      cover(
        inProgress && isLastBeat && io.in.valid && io.in.ready,
        "InterfaceSerializerBackToBackFinalBeatAccept"
      )
    }
  }
}

class SidebandInterfaceDeserializer(sbMsgWidth: Int, ncWidth: Int)
    extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Valid(UInt(ncWidth.W)))
    val flushRegAccessCompletion = Input(Bool())
    val flushRegAccessRequest = Input(Bool())
    val out = Valid(
      UInt(sbMsgWidth.W)
    )
    val outInvalid = Output(Bool())
    val invalidatedRequest = Output(Bool())
  })

  require(sbMsgWidth == 128 && Seq(8, 16, 32).contains(ncWidth))

  val numBeats = (sbMsgWidth / ncWidth)
  val maxBeats = numBeats - 1
  val headerBeats = 64 / ncWidth
  val counterWidth = max(1, log2Ceil(numBeats))

  val beatCounter = RegInit(0.U(counterWidth.W))
  val dataReg = RegInit(VecInit.fill(numBeats)(0.U(ncWidth.W)))
  val inProgress = RegInit(false.B)
  val invalidatePacket = RegInit(false.B)

  // Phase 0 carries the opcode, so the packet length is known from its first
  // beat. That beat is never the last (two header phases at least).
  val lastBeat = RegInit(maxBeats.U(counterWidth.W))

  // Note: the receiver must sink the packet in the one cycle valid is high.
  io.out.bits := dataReg.asUInt
  io.out.valid := inProgress && (beatCounter === 0.U)
  val packetOpcode = dataReg.asUInt(4, 0)
  val packetIsRegRequest = SBM.isRegAccessRequest(packetOpcode)
  val packetIsRegCompletion = SBM.isRegAccessComplete(packetOpcode)
  val packetIsRegAccess = packetIsRegRequest || packetIsRegCompletion
  val flushThisPacket = (packetIsRegRequest && io.flushRegAccessRequest) ||
    (packetIsRegCompletion && io.flushRegAccessCompletion)
  io.outInvalid := io.out.valid && packetIsRegAccess &&
    (invalidatePacket || flushThisPacket)

  val partialPacket = inProgress && beatCounter =/= 0.U
  val abortingPacket = partialPacket && !io.in.valid
  io.invalidatedRequest :=
    (io.outInvalid && packetIsRegRequest) ||
      (abortingPacket && packetIsRegRequest &&
        (invalidatePacket || io.flushRegAccessRequest))

  val firstBeat = io.in.valid && beatCounter === 0.U
  val firstBeatOpcode = io.in.bits.pad(5)(4, 0)
  val invalidateFirstBeat = firstBeat &&
    ((SBM.isRegAccessRequest(firstBeatOpcode) && io.flushRegAccessRequest) ||
      (SBM.isRegAccessComplete(firstBeatOpcode) &&
        io.flushRegAccessCompletion))
  val invalidatePartialPacket = partialPacket && io.in.valid && flushThisPacket

  when(io.out.valid || abortingPacket) {
    invalidatePacket := false.B
  }
  when(invalidateFirstBeat || invalidatePartialPacket) {
    invalidatePacket := true.B
  }

  val firstHeaderOnly =
    SidebandInterfaceFraming.headerOnly(firstBeatOpcode)

  // sec 7.1.4 sends the phases of a packet on consecutive cycles, so a gap ends
  // the packet.
  when(io.in.valid) {
    dataReg(beatCounter) := io.in.bits
    inProgress := true.B

    when(beatCounter =/= lastBeat) {
      beatCounter := beatCounter + 1.U
    }.otherwise {
      beatCounter := 0.U
    }
  }.otherwise {
    inProgress := false.B
    beatCounter := 0.U
  }

  when(firstBeat) {
    lastBeat := Mux(firstHeaderOnly, (headerBeats - 1).U, maxBeats.U)
    when(firstHeaderOnly) {
      for (i <- headerBeats until numBeats) dataReg(i) := 0.U
    }
  }

  if (numBeats > 1) {
    block(Verification) {
      block(Verification.Assert) {
        AssertProperty(
          Sequence.BoolSequence(io.out.valid) |=> Sequence.BoolSequence(
            !io.out.valid
          ),
          label = Some("DeserializerOutputIsSingleCyclePulse")
        )
        AssertProperty(
          Sequence.BoolSequence(io.outInvalid) |->
            Sequence.BoolSequence(io.out.valid && packetIsRegAccess),
          label = Some("DeserializerOnlyInvalidatesRegisterPackets")
        )
        AssertProperty(
          Sequence.BoolSequence(io.invalidatedRequest) |->
            Sequence.BoolSequence(packetIsRegRequest),
          label = Some("DeserializerOnlyReturnsInvalidatedRequestCredit")
        )
      }
      block(Verification.Cover) {
        cover(io.in.valid && !inProgress, "InterfaceDeserializerFirstBeat")
        cover(
          io.in.valid && inProgress && beatCounter === maxBeats.U,
          "InterfaceDeserializerFinalBeat"
        )
        cover(
          !io.in.valid && inProgress && beatCounter =/= 0.U,
          "InterfaceDeserializerGapAbort"
        )
        cover(io.out.valid, "InterfaceDeserializerOutputPulse")
        cover(
          io.out.valid && io.in.valid,
          "InterfaceDeserializerStreamingOutputWithNewBeat"
        )
      }
    }
  }
}
