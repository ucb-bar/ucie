package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.simutils.VerilatorCoverage
import org.scalatest.funspec.AnyFunSpec

class SidebandPriorityQueueTest
    extends AnyFunSpec
    with ChiselSim
    with VerilatorCoverage {
  val msgW = 128

  val reqResp = SBMsgOpcode.MessageWithoutData.litValue
  val accComplete = SBMsgOpcode.CompletionWithoutData.litValue
  val accRequest = SBMsgOpcode.MemoryRead_32b.litValue
  val other = SBMsgOpcode.ManagementPortMsgWithoutData.litValue

  // Every defined opcode paired with the priority rank its class should dequeue at.
  val classification: Seq[(BigInt, Int)] = {
    import SBMsgOpcode._
    Seq(MessageWithoutData, MessageWith64bData).map(_.litValue -> 0) ++
      Seq(CompletionWithoutData, CompletionWith32bData, CompletionWith64bData)
        .map(_.litValue -> 1) ++
      Seq(
        MemoryRead_32b,
        MemoryWrite_32b,
        DMSRegisterRead_32b,
        DMSRegisterWrite_32b,
        ConfigurationRead_32b,
        ConfigurationWrite_32b,
        MemoryRead_64b,
        MemoryWrite_64b,
        DMSRegisterRead_64b,
        DMSRegisterWrite_64b,
        ConfigurationRead_64b,
        ConfigurationWrite_64b
      )
        .map(_.litValue -> 2) ++
      Seq(
        ManagementPortMsgWithoutData,
        ManagementPortMsgWithData,
        BackToBackPriorityPacket,
        SinglePriorityPacket
      ).map(_.litValue -> 3)
  }

  def mkMsg(opcode: BigInt, tag: BigInt): BigInt = (tag << 64) | opcode

  val printDebugs = false
  def printDebug(msg: String): Unit =
    if (printDebugs) println(s"[SidebandPriorityQueueTest] $msg")

  // Enqueue while holding dequeue stalled so messages accumulate across classes.
  def enq(c: SidebandPriorityQueue, opcode: BigInt, tag: BigInt): Unit = {
    c.io.deq.ready.poke(false.B)
    c.io.enq.bits.poke(mkMsg(opcode, tag).U(msgW.W))
    c.io.enq.valid.poke(true.B)
    c.io.enq.ready.expect(true.B)
    printDebug(f"enq opcode=0x$opcode%02x tag=$tag")
    c.clock.step()
    c.io.enq.valid.poke(false.B)
  }

  def deqTag(c: SidebandPriorityQueue): BigInt = {
    c.io.deq.ready.poke(true.B)
    c.io.deq.valid.expect(true.B)
    val t = c.io.deq.bits.peek().litValue >> 64
    printDebug(f"deq tag=$t")
    c.clock.step()
    t
  }

  describe("SidebandPriorityQueue") {
    it("dequeues classes in strict priority order") {
      simulate(new SidebandPriorityQueue(msgW, SidebandPriorityQueueDepths())) {
        c =>
          enq(c, other, 4)
          enq(c, accRequest, 3)
          enq(c, accComplete, 2)
          enq(c, reqResp, 1)
          assert(Seq.fill(4)(deqTag(c)) == Seq(1, 2, 3, 4))
      }
    }

    it("dequeues a reqResp message ahead of a waiting completion") {
      simulate(new SidebandPriorityQueue(msgW, SidebandPriorityQueueDepths())) {
        c =>
          enq(c, accComplete, 7)
          enq(c, reqResp, 8)
          assert(deqTag(c) == 8)
          assert(deqTag(c) == 7)
      }
    }

    it("preserves FIFO order within a class") {
      simulate(new SidebandPriorityQueue(msgW, SidebandPriorityQueueDepths())) {
        c =>
          enq(c, reqResp, 1)
          enq(c, reqResp, 2)
          enq(c, reqResp, 3)
          assert(Seq.fill(3)(deqTag(c)) == Seq(1, 2, 3))
      }
    }

    it("classifies every opcode into the correct priority class") {
      simulate(new SidebandPriorityQueue(msgW, SidebandPriorityQueueDepths())) {
        c =>
          for ((op, rank) <- classification) {
            // tag 9 dequeues at the index of the class it was sorted into.
            enq(c, op, 9)
            enq(c, reqResp, 0)
            enq(c, accComplete, 1)
            enq(c, accRequest, 2)
            enq(c, other, 3)
            val expected =
              Seq[BigInt](0, 1, 2, 3).patch(rank, Seq(BigInt(9)), 0)
            assert(
              Seq.fill(5)(deqTag(c)) == expected,
              s"opcode 0x${op.toString(16)} misclassified"
            )
          }
      }
    }

    it("deasserts deq.valid when empty") {
      simulate(new SidebandPriorityQueue(msgW, SidebandPriorityQueueDepths())) {
        c =>
          c.io.deq.ready.poke(true.B)
          c.io.deq.valid.expect(false.B)
          c.clock.step()
          c.io.deq.valid.expect(false.B)
      }
    }

    it("streams under concurrent enqueue and dequeue without loss or reorder") {
      simulate(new SidebandPriorityQueue(msgW, SidebandPriorityQueueDepths())) {
        c =>
          c.io.deq.ready.poke(true.B)
          val got = scala.collection.mutable.ArrayBuffer[BigInt]()
          val n = 8
          for (i <- 1 to n) {
            c.io.enq.bits.poke(mkMsg(reqResp, i).U(msgW.W))
            c.io.enq.valid.poke(true.B)
            c.io.enq.ready.expect(true.B)
            if (c.io.deq.valid.peek().litToBoolean)
              got += (c.io.deq.bits.peek().litValue >> 64)
            c.clock.step()
          }
          c.io.enq.valid.poke(false.B)
          while (c.io.deq.valid.peek().litToBoolean) {
            got += (c.io.deq.bits.peek().litValue >> 64)
            c.clock.step()
          }
          printDebug(s"streamed tags=${got.toSeq}")
          assert(got.toSeq == (1 to n).map(BigInt(_)))
      }
    }

    it("holds the output and loses nothing when dequeue stalls mid-stream") {
      simulate(new SidebandPriorityQueue(msgW, SidebandPriorityQueueDepths())) {
        c =>
          enq(c, reqResp, 1)
          enq(c, reqResp, 2)
          enq(c, reqResp, 3)
          assert(deqTag(c) == 1)

          c.io.deq.ready.poke(false.B)
          c.io.deq.valid.expect(true.B)
          val held = c.io.deq.bits.peek().litValue >> 64
          c.clock.step()
          c.io.deq.valid.expect(true.B)
          assert(
            (c.io.deq.bits.peek().litValue >> 64) == held,
            "held output changed under backpressure"
          )

          assert(deqTag(c) == 2)
          assert(deqTag(c) == 3)
      }
    }

    it("flushes all queued data on reset and leaves no stale messages") {
      simulate(new SidebandPriorityQueue(msgW, SidebandPriorityQueueDepths())) {
        c =>
          enq(c, reqResp, 1)
          enq(c, accComplete, 2)
          enq(c, other, 3)
          c.io.deq.ready.poke(false.B)
          c.io.deq.valid.expect(true.B)

          c.reset.poke(true.B)
          c.clock.step()
          c.reset.poke(false.B)

          c.io.deq.ready.poke(true.B)
          c.io.deq.valid.expect(false.B) // nothing stale survives the reset
          c.clock.step()
          c.io.deq.valid.expect(false.B)

          enq(c, reqResp, 9)
          assert(deqTag(c) == 9)
      }
    }

    it(
      "does not block a high-priority enqueue behind a full low-priority queue"
    ) {
      val depths = SidebandPriorityQueueDepths(
        messageRequestOrResponse = 4,
        regAccessCompletion = 4,
        regAccessRequest = 4,
        other = 1
      )
      simulate(new SidebandPriorityQueue(msgW, depths)) { c =>
        enq(c, other, 10) // fills the depth-1 "other" queue

        c.io.deq.ready.poke(false.B)
        c.io.enq.bits.poke(mkMsg(other, 11).U(msgW.W))
        c.io.enq.valid.poke(true.B)
        c.io.enq.ready.expect(false.B) // another "other" is blocked

        c.io.enq.bits.poke(mkMsg(reqResp, 12).U(msgW.W))
        c.io.enq.ready.expect(true.B) // reqResp still accepted
        c.clock.step()
        c.io.enq.valid.poke(false.B)

        assert(deqTag(c) == 12)
        assert(deqTag(c) == 10)
      }
    }
  }
}
