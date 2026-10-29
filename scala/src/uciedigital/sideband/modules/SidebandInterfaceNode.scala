/*
  The sideband node on RDI or FDI, with the credit flow control of sec 7.1.3.1.
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import chisel3.util._

class SidebandInterfaceNode(
    sbMsgWidth: Int,
    ncWidth: Int,
    numCredits: Int,
    queueDepths: SidebandPriorityQueueDepths
) extends Module {
  require(
    queueDepths.regAccessCompletion >= 32,
    "Register completion storage must cover the scoped outstanding set"
  )

  val io = IO(new Bundle {
    val txIn = Flipped(Decoupled(UInt(sbMsgWidth.W)))

    val flushRequesterRegAccess = Input(Bool())
    val flushCompleterRegAccess = Input(Bool())

    val rxOut = Decoupled(UInt(sbMsgWidth.W))

    val txOut = Valid(UInt(ncWidth.W))

    val txHold = Input(Bool())
    val txPending = Output(Bool())

    val txPacketCommit = Valid(UInt(sbMsgWidth.W))
    val txClassReady = Output(Vec(4, Bool()))

    val txCreditReturn = Input(Bool())
    val txCreditAvailable = Output(Bool())

    val rxIn = Flipped(Valid(UInt(ncWidth.W)))
    val rxCreditReturn = Output(Bool())

    val sbParityErr = Output(Bool())
    val sbParityErrEvent = Output(Bool())
    val rxPriorityQueuesFull = Output(Bool())
  })

  // ===========================================================================
  // Transmit path
  // ===========================================================================
  val serializer = Module(new SidebandInterfaceSerializer(sbMsgWidth, ncWidth))
  val txCreditCounter = RegInit(numCredits.U((log2Ceil(numCredits) + 1).W))
  val isEnoughCredits = txCreditCounter =/= 0.U
  io.txCreditAvailable := isEnoughCredits

  val txDepths = Seq(
    queueDepths.messageRequestOrResponse,
    queueDepths.regAccessCompletion,
    queueDepths.regAccessRequest,
    queueDepths.other
  )
  val txQueues = txDepths.zipWithIndex.map { case (depth, index) =>
    Module(
      new Queue(
        UInt(sbMsgWidth.W),
        depth,
        hasFlush = index == 1 || index == 2
      )
    )
  }

  // Each flush covers one role in both directions. The requester owns this
  // side's requests and the completions they get back. The completer owns the
  // partner's requests and the completions this side sends.
  txQueues.zipWithIndex.foreach { case (q, index) =>
    q.io.flush.foreach(
      _ := (if (index == 1) io.flushCompleterRegAccess
            else io.flushRequesterRegAccess)
    )
  }

  val txEnqueue = Module(new EnqueueArbiter(sbMsgWidth, txQueues.length))
  txEnqueue.io.in <> io.txIn
  for (i <- txQueues.indices) {
    txQueues(i).io.enq <> txEnqueue.io.out(i)
    io.txClassReady(i) := txQueues(i).io.enq.ready
  }

  val txDequeue = Module(new Arbiter(UInt(sbMsgWidth.W), txQueues.length))
  val txClassEligible = Seq(
    isEnoughCredits,
    true.B,
    isEnoughCredits,
    isEnoughCredits
  )
  for (i <- txQueues.indices) {
    val flushingClass =
      if (i == 1) io.flushCompleterRegAccess
      else if (i == 2) io.flushRequesterRegAccess
      else false.B
    txDequeue.io.in(i).valid := txQueues(i).io.deq.valid &&
      txClassEligible(i) && !flushingClass
    txDequeue.io.in(i).bits := txQueues(i).io.deq.bits
    txQueues(i).io.deq.ready := txDequeue.io.in(i).ready &&
      txClassEligible(i) && !flushingClass
  }

  val txPacket = txDequeue.io.out.bits
  val isRegAccessComplete = SBM.isRegAccessComplete(txPacket(4, 0))

  val newBits = SidebandParity.set(txPacket)

  // txHold stops only the start of a transfer, so a packet in flight finishes.
  // It holds credit returns too (Table 10-1, Table 10-3).
  serializer.io.in.valid := txDequeue.io.out.valid && !io.txHold
  serializer.io.in.bits := newBits
  txDequeue.io.out.ready := serializer.io.in.ready && !io.txHold
  io.txOut <> serializer.io.out
  io.txPacketCommit.valid := serializer.io.in.fire
  io.txPacketCommit.bits := txPacket

  val consumeCredit = Wire(Bool())
  consumeCredit := serializer.io.in.fire && !isRegAccessComplete
  val creditEmpty = txCreditCounter === 0.U
  val blockedForCredit = creditEmpty && Seq(0, 2, 3)
    .map(txQueues(_).io.deq.valid)
    .reduce(_ || _)
  val completionBypassCredit =
    serializer.io.in.fire && isRegAccessComplete && creditEmpty

  when(consumeCredit && !io.txCreditReturn) {
    txCreditCounter := txCreditCounter - 1.U
  }.elsewhen(!consumeCredit && io.txCreditReturn) {
    txCreditCounter := txCreditCounter + 1.U
  }

  // ===========================================================================
  // Receive path
  // ===========================================================================
  val deserializer = Module(
    new SidebandInterfaceDeserializer(sbMsgWidth, ncWidth)
  )
  deserializer.io.flushRegAccessCompletion := io.flushRequesterRegAccess
  deserializer.io.flushRegAccessRequest := io.flushCompleterRegAccess
  val priorityQueue = Module(new SidebandPriorityQueue(sbMsgWidth, queueDepths))
  priorityQueue.io.flushRegAccessCompletion := io.flushRequesterRegAccess
  priorityQueue.io.flushRegAccessRequest := io.flushCompleterRegAccess

  io.rxIn <> deserializer.io.in

  val parityErrReg = RegInit(false.B)
  val opcode = deserializer.io.out.bits(4, 0)

  val parityErrors = SidebandParity.check(deserializer.io.out.bits)
  val cpError = parityErrors.cp
  val dpError = parityErrors.dp

  val parityError = cpError || dpError
  val parityErrorEvent = deserializer.io.out.valid &&
    !deserializer.io.outInvalid && parityError

  val gatedDeserializerValid = Wire(Bool())
  gatedDeserializerValid := deserializer.io.out.valid && !parityError &&
    !deserializer.io.outInvalid
  val incomingRegRequest = SBM.isRegAccessRequest(opcode)

  when(parityErrorEvent) {
    parityErrReg := true.B
  }

  priorityQueue.io.enq.bits := deserializer.io.out.bits
  priorityQueue.io.enq.valid := gatedDeserializerValid

  val queuedOpcode = priorityQueue.io.deq.bits(4, 0)
  val queuedRegRequest = SBM.isRegAccessRequest(queuedOpcode)
  val queuedRegCompletion = SBM.isRegAccessComplete(queuedOpcode)
  val dropQueuedRegPacket =
    (queuedRegRequest && io.flushCompleterRegAccess) ||
      (queuedRegCompletion && io.flushRequesterRegAccess)
  io.rxOut.valid := priorityQueue.io.deq.valid && !dropQueuedRegPacket
  io.rxOut.bits := priorityQueue.io.deq.bits
  priorityQueue.io.deq.ready := dropQueuedRegPacket || io.rxOut.ready

  // ===========================================================================
  // Receive credits
  // ===========================================================================
  val requestCountWidth = math.max(
    1,
    log2Ceil(queueDepths.regAccessRequest + 1)
  )
  val creditCountWidth = math.max(1, log2Ceil(numCredits + 1))
  val queuedRegRequestCount = RegInit(0.U(requestCountWidth.W))
  val rxCreditsPending = RegInit(0.U(creditCountWidth.W))
  val requestEnqueued = priorityQueue.io.enq.fire && incomingRegRequest
  val requestDelivered = io.rxOut.fire && queuedRegRequest

  when(io.flushCompleterRegAccess) {
    queuedRegRequestCount := 0.U
  }.elsewhen(requestEnqueued =/= requestDelivered) {
    queuedRegRequestCount := Mux(
      requestEnqueued,
      queuedRegRequestCount + 1.U,
      queuedRegRequestCount - 1.U
    )
  }

  val flushedQueuedRequests = Mux(
    io.flushCompleterRegAccess,
    queuedRegRequestCount,
    0.U
  )
  val droppedIncomingRequest = deserializer.io.invalidatedRequest
  val ordinaryCreditReturn = io.rxOut.fire && !queuedRegCompletion

  // A parity error can corrupt the opcode, and no protected bit gives the
  // credit class. Only an exact completion counts as uncredited.
  val parityCreditReturn = parityErrorEvent &&
    !SBM.isRegAccessComplete(opcode)
  val queueFullDrop = priorityQueue.io.enq.valid &&
    !priorityQueue.io.enq.ready
  val queueFullCreditReturn = queueFullDrop &&
    !SBM.isRegAccessComplete(opcode)
  val newCreditsOwed = flushedQueuedRequests +&
    droppedIncomingRequest.asUInt +& ordinaryCreditReturn.asUInt +&
    parityCreditReturn.asUInt +& queueFullCreditReturn.asUInt
  val totalCreditsOwed = rxCreditsPending +& newCreditsOwed
  io.rxCreditReturn := totalCreditsOwed =/= 0.U && !io.txHold
  rxCreditsPending := Mux(
    io.rxCreditReturn,
    totalCreditsOwed - 1.U,
    totalCreditsOwed
  )

  // The clock request reads this, because txOut.valid rises only with the
  // first beat. A credit still owed keeps it up (Table 10-1, Table 10-3).
  io.txPending := txDequeue.io.out.valid || serializer.io.out.valid ||
    (rxCreditsPending =/= 0.U)

  io.rxPriorityQueuesFull := queueFullDrop
  io.sbParityErr := parityErrReg
  io.sbParityErrEvent := parityErrorEvent

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(consumeCredit) |-> Sequence.BoolSequence(
          txCreditCounter =/= 0.U
        ),
        label = Some("NodeNeverConsumesCreditWhenEmpty")
      )
      AssertProperty(
        Sequence.BoolSequence(txCreditCounter <= numCredits.U),
        label = Some("NodeCreditCounterNeverExceedsMax")
      )
      AssertProperty(
        Sequence.BoolSequence(
          (io.flushCompleterRegAccess && txQueues(1).io.deq.valid) ||
            (io.flushRequesterRegAccess && txQueues(2).io.deq.valid)
        ) |-> Sequence.BoolSequence(
          !serializer.io.in.valid || !(
            SBM.isRegAccessComplete(txPacket(4, 0)) ||
              SBM.isRegAccessRequest(txPacket(4, 0))
          )
        ),
        label = Some("NodeNeverCommitsFlushedRegisterPacket")
      )
      AssertProperty(
        Sequence.BoolSequence(
          queuedRegRequestCount <= queueDepths.regAccessRequest.U
        ),
        label = Some("NodeTracksQueuedRegisterRequests")
      )
      AssertProperty(
        Sequence.BoolSequence(totalCreditsOwed <= numCredits.U),
        label = Some("NodeNeverOwesMoreCreditsThanAdvertised")
      )
      AssertProperty(
        Sequence.BoolSequence(parityErrorEvent) |-> Sequence.BoolSequence(
          !priorityQueue.io.enq.valid
        ),
        label = Some("NodeDropsParityErrorPacket")
      )
      AssertProperty(
        Sequence.BoolSequence(queueFullDrop) |-> Sequence.BoolSequence(
          !SBM.isRegAccessComplete(opcode)
        ),
        label = Some("NodeNeverDropsRegisterCompletion")
      )
      AssertProperty(
        Sequence.BoolSequence(dropQueuedRegPacket) |->
          Sequence.BoolSequence(!io.rxOut.valid),
        label = Some("NodeNeverDeliversFlushedRegisterPacket")
      )
    }
    block(Verification.Cover) {
      val sawCreditEmpty = RegInit(false.B)
      val sawCreditReturnAfterEmpty = RegInit(false.B)

      when(creditEmpty) {
        sawCreditEmpty := true.B
      }
      when(sawCreditEmpty && io.txCreditReturn) {
        sawCreditReturnAfterEmpty := true.B
      }.elsewhen(consumeCredit) {
        sawCreditReturnAfterEmpty := false.B
      }

      cover(creditEmpty, "InterfaceNodeCreditCounterEmpty")
      cover(consumeCredit, "InterfaceNodeCreditConsumed")
      cover(io.txCreditReturn, "InterfaceNodeTxCreditReturned")
      cover(blockedForCredit, "InterfaceNodeCreditConsumingPacketBlockedAtZero")
      cover(completionBypassCredit, "InterfaceNodeCompletionBypassesZeroCredit")
      cover(
        (io.flushCompleterRegAccess && txQueues(1).io.deq.valid) ||
          (io.flushRequesterRegAccess && txQueues(2).io.deq.valid),
        "InterfaceNodeTxRegisterPacketFlushed"
      )
      cover(
        deserializer.io.out.valid && cpError,
        "InterfaceNodeCpParityErrorDrop"
      )
      cover(
        deserializer.io.out.valid && dpError,
        "InterfaceNodeDpParityErrorDrop"
      )
      cover(io.rxCreditReturn, "InterfaceNodeRxCreditReturned")
      cover(queueFullCreditReturn, "InterfaceNodeQueueFullCreditReturned")
      cover(io.rxPriorityQueuesFull, "InterfaceNodeRxPriorityQueueFull")
      cover(
        consumeCredit && sawCreditReturnAfterEmpty,
        "InterfaceNodeCreditReturnAllowsLaterConsume"
      )
    }
  }
}
