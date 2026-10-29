/*
  The sideband priority queues. Each class has its own queue, so completions
  and request or response messages never wait behind register access requests
  (sec 7.1.3.1).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import chisel3.util._

class SidebandPriorityQueue(
    sbMsgWidth: Int,
    depths: SidebandPriorityQueueDepths
) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(UInt(sbMsgWidth.W)))
    val deq = Decoupled(UInt(sbMsgWidth.W))
    val flushRegAccessCompletion = Input(Bool())
    val flushRegAccessRequest = Input(Bool())
  })

  // Note: keep this priority order the same as in the EnqueueArbiter below.
  val priorityDepths = Seq(
    depths.messageRequestOrResponse,
    depths.regAccessCompletion,
    depths.regAccessRequest,
    depths.other
  )

  val queues = priorityDepths.zipWithIndex.map { case (depth, index) =>
    Module(
      new Queue(
        UInt(sbMsgWidth.W),
        depth,
        hasFlush = index == 1 || index == 2
      )
    )
  }

  queues.zipWithIndex.foreach { case (q, index) =>
    q.io.flush.foreach(
      _ := (if (index == 1) io.flushRegAccessCompletion
            else io.flushRegAccessRequest)
    )
  }

  val enqArbiter = Module(new EnqueueArbiter(sbMsgWidth, queues.length))
  enqArbiter.io.in <> io.enq

  for (i <- queues.indices) {
    queues(i).io.enq <> enqArbiter.io.out(i)
  }

  val deqArbiter = Module(new Arbiter(UInt(sbMsgWidth.W), queues.length))

  for (i <- queues.indices) {
    deqArbiter.io.in(i) <> queues(i).io.deq
  }

  io.deq <> deqArbiter.io.out

  block(Verification) {
    block(Verification.Cover) {
      val names = Seq("ReqResp", "Completion", "RegAccessRequest", "Other")
      for (i <- queues.indices) {
        cover(queues(i).io.enq.fire, s"PriorityQueue${names(i)}Enqueue")
        cover(queues(i).io.deq.fire, s"PriorityQueue${names(i)}Dequeue")
        cover(
          queues(i).io.enq.valid && !queues(i).io.enq.ready,
          s"PriorityQueue${names(i)}Backpressure"
        )
      }
      cover(
        queues(0).io.deq.valid && queues
          .drop(1)
          .map(_.io.deq.valid)
          .reduce(_ || _) &&
          io.deq.fire && deqArbiter.io.chosen === 0.U,
        "PriorityQueueHighPriorityWinsContention"
      )
    }
  }
}

class EnqueueArbiter(sbMsgWidth: Int, numQueues: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(sbMsgWidth.W)))
    val out = Vec(numQueues, Decoupled(UInt(sbMsgWidth.W)))
  })

  io.out.foreach(_.bits := io.in.bits)

  val opcode = io.in.bits(4, 0)
  val isReqRespMessage = SBM.isReqRespMessage(opcode)
  val isAccComplete = SBM.isRegAccessComplete(opcode)
  val isAccRequest = SBM.isRegAccessRequest(opcode)
  val isOther = !(isReqRespMessage || isAccComplete || isAccRequest)

  io.out(0).valid := io.in.valid && isReqRespMessage
  io.out(1).valid := io.in.valid && isAccComplete
  io.out(2).valid := io.in.valid && isAccRequest
  io.out(3).valid := io.in.valid && isOther

  io.in.ready := (isReqRespMessage && io.out(0).ready) ||
    (isAccComplete && io.out(1).ready) ||
    (isAccRequest && io.out(2).ready) ||
    (isOther && io.out(3).ready)

  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(io.in.valid) |->
          Sequence.BoolSequence(
            PopCount(VecInit(io.out.map(_.valid)).asUInt) === 1.U
          ),
        label = Some("PriorityQueueClassifiesIntoExactlyOneQueue")
      )
    }
    block(Verification.Cover) {
      cover(io.in.fire && isReqRespMessage, "PriorityQueueClassifyReqResp")
      cover(io.in.fire && isAccComplete, "PriorityQueueClassifyCompletion")
      cover(io.in.fire && isAccRequest, "PriorityQueueClassifyRegAccessRequest")
      cover(io.in.fire && isOther, "PriorityQueueClassifyOther")
    }
  }
}
