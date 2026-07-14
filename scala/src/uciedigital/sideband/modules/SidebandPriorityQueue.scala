/*
  Description:
    SidebandPriorityQueue is a helper module used in the SidebandInterfaceNode.
    It separates sideband messages into the following queues:
      1. MessageRequestOrResponse
      2. RegAccessCompletion
      3. RegAccessRequests
      3. Other

    More queues can be added, if required.

    1. MessageRequestOrResponse queues messages with and without data this because training
    messages are of this form. They must not be blocked behind other messages and need to 
    prioritized for forward progress.

    2. RegAccessCompletion these messages must be unconditionally sinked according to the spec

    3. ReqAccessRequest has a separate credit system and provision 4 credits for them

    4. Other catch all queue.    
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import circt.stage.ChiselStage
import chisel3.util._

class SidebandPriorityQueue(sbMsgWidth: Int, depths: SidebandPriorityQueueDepths) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(UInt(sbMsgWidth.W)))
    val deq = Decoupled(UInt(sbMsgWidth.W))    
  })

  // Put depths in a sequence, ordered from highest priority to lowest
  // To change priority, can just change depth ordering here. Make to 
  // also change order in the EnqueueArbiter module below.
  val priorityDepths = Seq(
    depths.messageRequestOrResponse,
    depths.regAccessCompletion,
    depths.regAccessRequest,
    depths.other
  )

  // queue(0) has highest priority, queue(n) has lowest priority
  val queues = priorityDepths.map(depth => Module(new Queue(UInt(sbMsgWidth.W), depth)))

  // Enqueue
  val enqArbiter = Module(new EnqueueArbiter(sbMsgWidth, queues.length))
  enqArbiter.io.in <> io.enq

  for (i <- queues.indices) {
    queues(i).io.enq <> enqArbiter.io.out(i)
  }

  // Dequeue
  val deqArbiter = Module(new Arbiter(UInt(sbMsgWidth.W), queues.length))

  for(i <- queues.indices) {
    deqArbiter.io.in(i) <> queues(i).io.deq
  }

  io.deq <> deqArbiter.io.out

  block(Verification) {
    block(Verification.Cover) {
      val names = Seq("ReqResp", "Completion", "RegAccessRequest", "Other")
      for(i <- queues.indices) {
        cover(queues(i).io.enq.fire, s"PriorityQueue${names(i)}Enqueue")
        cover(queues(i).io.deq.fire, s"PriorityQueue${names(i)}Dequeue")
        cover(queues(i).io.enq.valid && !queues(i).io.enq.ready, s"PriorityQueue${names(i)}Backpressure")
      }
      cover(queues(0).io.deq.valid && queues.drop(1).map(_.io.deq.valid).reduce(_ || _) &&
        io.deq.fire && deqArbiter.io.chosen === 0.U, "PriorityQueueHighPriorityWinsContention")
    }
  }
}

// ============================================================================

// Helper module to select correct queue
// NOTE: If changing priority order, make sure the enqueue arbiter 
// matches the ordering of the queues
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

  io.in.ready := (isReqRespMessage && io.out(0).ready)  ||
                 (isAccComplete && io.out(1).ready)     ||
                 (isAccRequest && io.out(2).ready)      ||
                 (isOther && io.out(3).ready)

  // =======================================================================
  // Assertions
  // =======================================================================  
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(io.in.valid) |->
          Sequence.BoolSequence(PopCount(VecInit(io.out.map(_.valid)).asUInt) === 1.U),
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

object MainSBPriorityQueue extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandPriorityQueue(128, SidebandPriorityQueueDepths()),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Array(
      "-O=debug",
      "--lowering-options=disallowLocalVariables",
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket",  
    ),
  )
}
