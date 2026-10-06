package edu.berkeley.cs.uciedigital.utils

import chisel3._
import chisel3.util._
import freechips.rocketchip.util.{
  AsyncQueue,
  AsyncQueueParams,
  AsyncResetSynchronizerShiftReg,
  ResetCatchAndSync
}

/** Hands packets from a producer to a sideband serializer on the sideband TX
  * clock, and tells the producer when everything it handed over has been sent.
  *
  * The serializers run on the 800 MHz sideband TX clock, while the packets come
  * from the producer's own clock. The packets cross through an async queue. The
  * queue alone cannot say a packet is still being shifted out, so completion
  * crosses the other way: the serializer pulses `sent` as it finishes each
  * packet, and the count of those comes back Gray coded, to be compared with
  * the count of packets the producer enqueued.
  *
  * Both sides reset together. This module's reset (the producer's) and
  * `txReset` are combined, and each side is released against its own clock, so
  * a reset from either domain empties the queue and zeroes both counts.
  * `txResetSync` is that reset on `txClock`, for the serializer.
  *
  * @param depth
  *   packets the queue holds
  */
class SidebandTxQueue[T <: Data](gen: T, depth: Int = 2) extends Module {
  val io = IO(new Bundle {

    /** Packets in, on this module's clock. */
    val enq = Flipped(Decoupled(gen))

    /** Every packet accepted on `enq` has been sent. On this module's clock. */
    val idle = Output(Bool())

    /** The sideband TX clock. */
    val txClock = Input(Clock())

    /** The serializer's reset, from any domain. */
    val txReset = Input(Bool())

    /** The combined reset, released against `txClock`, for the serializer. */
    val txResetSync = Output(Bool())

    /** Packets out, on `txClock`. */
    val deq = Decoupled(gen)

    /** Pulsed on `txClock` as the serializer finishes a packet. */
    val sent = Input(Bool())
  })

  private val anyReset = reset.asBool || io.txReset
  private val enqReset = ResetCatchAndSync(clock, anyReset)
  private val deqReset = ResetCatchAndSync(io.txClock, anyReset)
  io.txResetSync := deqReset

  val queue = Module(new AsyncQueue(gen, AsyncQueueParams(depth = depth)))
  queue.io.enq_clock := clock
  queue.io.enq_reset := enqReset
  queue.io.deq_clock := io.txClock
  queue.io.deq_reset := deqReset
  queue.io.enq <> io.enq
  io.deq <> queue.io.deq

  // Packets can be in the queue, in the serializer, and one more accepted by a
  // serializer that loads its next packet before the last one's trailing gap
  // is over: the counts need room for all of them and one more to tell full
  // from empty.
  private val countBits = log2Ceil(depth + 3) + 1
  private def toGray(x: UInt): UInt = x ^ (x >> 1)

  private val enqCount = withReset(enqReset.asAsyncReset) {
    RegInit(0.U(countBits.W))
  }
  when(io.enq.fire) { enqCount := enqCount + 1.U }

  private val sentGray =
    withClockAndReset(io.txClock, deqReset.asAsyncReset) {
      val sentCount = RegInit(0.U(countBits.W))
      when(io.sent) { sentCount := sentCount + 1.U }
      // Registered, so the synchronizer only ever samples a flop.
      val gray = RegInit(0.U(countBits.W))
      gray := toGray(sentCount)
      gray
    }
  private val sentGraySync = withReset(enqReset) {
    AsyncResetSynchronizerShiftReg(sentGray, 3)
  }

  io.idle := toGray(enqCount) === sentGraySync
}
