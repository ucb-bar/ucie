/*
  The sideband node on the physical link (sec 4.1.5).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.SkidBuffer

class SidebandLinkNode(
    sbMsgWidth: Int,
    sbLinkWidth: Int,
    desTimeoutCycles: Int,
    queueDepths: SidebandPriorityQueueDepths
) extends Module {
  require(
    queueDepths.regAccessCompletion >= 32,
    "Register completion storage must cover the scoped outstanding set"
  )

  val io = IO(new Bundle {
    val txIn = Flipped(Decoupled(UInt(sbMsgWidth.W)))
    val flushRegAccess = Input(Bool())
    val clearParityContainment = Input(Bool())

    val rxOut = Decoupled(new SbLinkRxWord(sbMsgWidth))

    val txOut = new SidebandTxPins(sbLinkWidth)
    val rxIn = new SidebandRxPins(sbLinkWidth)

    val err = new Bundle {
      val sbParityErr = Output(Bool())
      val sbParityErrEvent = Output(Bool())
      val rxPriorityQueuesFull = Output(Bool())
      val desTimedout = Output(Bool())
    }

    val ctrl = new Bundle {
      val txMode = Input(SBRxTxMode())
      val rxMode = Input(SBRxTxMode())
      val freezeAcceptingPackets = Input(Bool())
      val allPacketsSent = Output(Bool())
    }
  })

  // ===========================================================================
  // Transmit path
  // ===========================================================================
  val serializer = Module(new SidebandLinkSerializer(sbLinkWidth, sbMsgWidth))

  // Shortens the combinational path of the serializer ready signal.
  val skidBuffer = Module(new SkidBuffer(sbMsgWidth + 1))

  io.ctrl.allPacketsSent := io.ctrl.freezeAcceptingPackets &&
    !skidBuffer.io.out.valid &&
    serializer.io.in.ready

  skidBuffer.io.in.valid := io.txIn.valid && !io.ctrl.freezeAcceptingPackets
  skidBuffer.io.in.bits := Cat(
    io.ctrl.txMode === SBRxTxMode.PACKET,
    io.txIn.bits
  )
  io.txIn.ready := skidBuffer.io.in.ready && !io.ctrl.freezeAcceptingPackets

  val txPacket = skidBuffer.io.out.bits(sbMsgWidth - 1, 0)
  val txWasPacketMode = skidBuffer.io.out.bits(sbMsgWidth)
  val txOpcode = txPacket(4, 0)
  val txIsWoData =
    SBMsgOpcode.OpsWithoutData.map(_.asUInt === txOpcode).reduce(_ || _)
  val txIsRegAccess = SBM.isRegAccessRequest(txOpcode) ||
    SBM.isRegAccessComplete(txOpcode)
  val flushTxPacket = io.flushRegAccess && txWasPacketMode && txIsRegAccess
  skidBuffer.io.out.ready := flushTxPacket || serializer.io.in.ready
  val txAccept = serializer.io.in.fire
  serializer.io.ctrl.txMode := Mux(
    txWasPacketMode,
    SBRxTxMode.PACKET,
    SBRxTxMode.RAW
  )

  val newBits = SidebandParity.set(txPacket)

  serializer.io.in.valid := skidBuffer.io.out.valid && !flushTxPacket
  serializer.io.in.bits := newBits

  io.txOut <> serializer.io.out

  // ===========================================================================
  // Receive path
  // ===========================================================================
  val deserializer = Module(
    new SidebandLinkDeserializer(sbLinkWidth, sbMsgWidth, desTimeoutCycles)
  )
  val priorityQueue = Module(
    new SidebandPriorityQueue(sbMsgWidth, queueDepths)
  )

  // The link node has no requester/completer split, so one input fans to both.
  priorityQueue.io.flushRegAccessCompletion := io.flushRegAccess
  priorityQueue.io.flushRegAccessRequest := io.flushRegAccess

  deserializer.io.ctrl.rxMode := io.ctrl.rxMode
  deserializer.io.flushRegAccess := io.flushRegAccess
  deserializer.io.clearParityContainment := io.clearParityContainment
  deserializer.io.in <> io.rxIn

  io.err.desTimedout := deserializer.io.ctrl.desTimedout

  val parityErrReg = RegInit(false.B)
  val opcode = deserializer.io.out.bits(4, 0)
  val isPacket = deserializer.io.outPacketMode

  val parityErrors = SidebandParity.check(deserializer.io.out.bits)
  val cpError = parityErrors.cp
  val dpError = parityErrors.dp

  val parityError = (cpError || dpError) && isPacket
  val completedParityErrorEvent = deserializer.io.out.valid &&
    !deserializer.io.outInvalid && parityError
  val parityErrorEvent = completedParityErrorEvent ||
    deserializer.io.headerCpErrEvent
  val rxOpcode = io.rxOut.bits.data(4, 0)
  val rxIsWoData =
    SBMsgOpcode.OpsWithoutData.map(_.asUInt === rxOpcode).reduce(_ || _)

  val gatedDeserializerValid = Wire(Bool())
  gatedDeserializerValid := deserializer.io.out.valid && isPacket &&
    !parityError && !deserializer.io.outInvalid

  when(parityErrorEvent) {
    parityErrReg := true.B
  }

  // RAW words come only in RESET and SBINIT (sec 4.5.3.1, sec 4.5.3.2), and
  // the consumer takes each one at once, so they need no queue.
  val rawValid = RegInit(false.B)
  val rawData = Reg(UInt(64.W))

  priorityQueue.io.enq.bits := deserializer.io.out.bits
  priorityQueue.io.enq.valid := gatedDeserializerValid

  // A full register stops the deserializer, as a full queue does, so a RAW
  // word is neither lost nor replaced.
  deserializer.io.out.ready := deserializer.io.outInvalid ||
    completedParityErrorEvent ||
    Mux(isPacket, priorityQueue.io.enq.ready, !rawValid)

  when(deserializer.io.out.fire && !isPacket) {
    rawValid := true.B
    rawData := deserializer.io.out.bits(63, 0)
  }.elsewhen(io.rxOut.ready) {
    rawValid := false.B
  }

  val queuedOpcode = priorityQueue.io.deq.bits(4, 0)
  val queuedRegAccess = SBM.isRegAccessRequest(queuedOpcode) ||
    SBM.isRegAccessComplete(queuedOpcode)
  val dropQueuedRegPacket = io.flushRegAccess && queuedRegAccess

  // A RAW word goes first. Its consumer takes it at once, and packets go to a
  // different consumer, so the order does not matter.
  io.rxOut.valid := rawValid ||
    (priorityQueue.io.deq.valid && !dropQueuedRegPacket)
  io.rxOut.bits.data := Mux(rawValid, rawData, priorityQueue.io.deq.bits)
  io.rxOut.bits.isRaw := rawValid
  priorityQueue.io.deq.ready := dropQueuedRegPacket ||
    (io.rxOut.ready && !rawValid)

  val queueFullDrop = priorityQueue.io.enq.valid && !priorityQueue.io.enq.ready
  io.err.rxPriorityQueuesFull := queueFullDrop
  io.err.sbParityErr := parityErrReg
  io.err.sbParityErrEvent := parityErrorEvent

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(io.err.sbParityErr) |=> Sequence.BoolSequence(
          io.err.sbParityErr
        ),
        label = Some("LinkNodeParityErrorIsSticky")
      )
      AssertProperty(
        Sequence.BoolSequence(flushTxPacket) |->
          Sequence.BoolSequence(!serializer.io.in.valid),
        label = Some("LinkNodeNeverStartsFlushedRegisterPacket")
      )
      AssertProperty(
        Sequence.BoolSequence(dropQueuedRegPacket) |->
          Sequence.BoolSequence(!io.rxOut.valid || io.rxOut.bits.isRaw),
        label = Some("LinkNodeNeverDeliversFlushedRegisterPacket")
      )
      AssertProperty(
        Sequence.BoolSequence(queueFullDrop) |-> Sequence.BoolSequence(
          !SBM.isRegAccessComplete(opcode)
        ),
        label = Some("LinkNodeNeverDropsRegisterCompletion")
      )
      AssertProperty(
        Sequence.BoolSequence(
          completedParityErrorEvent
        ) |-> Sequence.BoolSequence(
          deserializer.io.out.ready && !priorityQueue.io.enq.valid
        ),
        label = Some("LinkNodeConsumesParityErrorPacket")
      )
    }
    block(Verification.Cover) {
      val sawFreezeBusy = RegInit(false.B)

      when(io.ctrl.freezeAcceptingPackets && !serializer.io.in.ready) {
        sawFreezeBusy := true.B
      }.elsewhen(io.ctrl.allPacketsSent) {
        sawFreezeBusy := false.B
      }

      cover(
        io.ctrl.freezeAcceptingPackets && io.txIn.valid && !io.txIn.ready,
        "LinkNodeFreezeBlocksTxAccept"
      )
      cover(io.ctrl.allPacketsSent, "LinkNodeAllPacketsSent")
      cover(
        io.ctrl.allPacketsSent && sawFreezeBusy,
        "LinkNodeFreezeDrainsToAllPacketsSent"
      )
      cover(
        txAccept && (!txWasPacketMode || txIsWoData),
        "LinkNodeTx64bPacketAccepted"
      )
      cover(
        txAccept && txWasPacketMode && !txIsWoData,
        "LinkNodeTx128bPacketAccepted"
      )
      cover(
        io.rxOut.fire && (io.rxOut.bits.isRaw || rxIsWoData),
        "LinkNodeRx64bPacketEmitted"
      )
      cover(
        io.rxOut.fire && !io.rxOut.bits.isRaw && !rxIsWoData,
        "LinkNodeRx128bPacketEmitted"
      )

      // RAW words are 96 UI apart (sec 4.1.5) and the consumer is always
      // ready for them, so a RAW word must never wait for the register.
      cover(
        deserializer.io.out.valid && !isPacket && rawValid,
        "LinkNodeRawWordWaitsForRegister"
      )
      cover(
        deserializer.io.headerCpErrEvent,
        "LinkNodeCpParityErrorDrop"
      )
      cover(deserializer.io.out.valid && dpError, "LinkNodeDpParityErrorDrop")
      cover(io.err.desTimedout, "LinkNodeDeserializerTimeout")
      cover(io.err.rxPriorityQueuesFull, "LinkNodeRxPriorityQueueFull")
    }
  }
}
