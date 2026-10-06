package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._

import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}

/** Ports of a [[SidebandSerial]] link. */
class SidebandSerialIO(packetBits: Int) extends Bundle {

  /** Packet to shift out. Crosses to `txClock` through a queue, so `ready`
    * means there is room for it, not that the link is idle; see `txIdle`.
    */
  val tx = Flipped(DecoupledIO(UInt(packetBits.W)))

  /** Every packet accepted on `tx` has been shifted out. */
  val txIdle = Output(Bool())

  /** Oldest fully received packet. */
  val rx = DecoupledIO(UInt(packetBits.W))

  /** Sticky: a packet arrived with no room to store it and was dropped. */
  val rxOverflow = Output(Bool())

  /** Resets the transmitter, dropping any packet queued or mid-flight. */
  val txRst = Input(Bool())

  /** The 800 MHz sideband TX clock the serializer runs on. */
  val txClock = Input(Clock())

  /** Reset for the serializer, from any domain: the sideband clock's own. */
  val txReset = Input(Bool())

  /** Resets the receiver: clears the bit counter, the stored packets, and
    * `rxOverflow`. Realigns the receiver if a partner reset mid-packet left it
    * an odd number of bits out of step.
    */
  val rxRst = Input(Bool())

  // PHY INTERFACE
  // ====================
  val sb = Flipped(new SbIO)
}

/** Fixed-width packet link over the sideband bumps.
  *
  * No in-band framing is needed: the forwarded clock is gated off between
  * packets, so the receiver only sees exactly the `packetBits` edges the
  * transmitter drives, and its bit counter cannot drift while the link is idle.
  * Both ends must therefore agree on `packetBits` and must come out of reset
  * before the first packet is sent.
  *
  * Clocking matches
  * [[edu.berkeley.cs.uciedigital.sideband.SidebandLinkSerializer]] so that this
  * link and the real sideband are interchangeable on the wire: the serializer
  * runs on the sideband TX clock, the forwarded clock is a gated copy of that
  * clock produced through a 2:1 serializer, data is held for the full period,
  * and the receiver samples on the falling edge, in the middle of the eye.
  * Packets cross into the TX clock through a queue, and received ones out of
  * the forwarded clock through another, so the rest of the link is on this
  * module's clock.
  *
  * @param packetBits
  *   bits per packet, sent LSB first
  * @param rxQueueDepth
  *   received packets waiting to be read out. Also the depth of the crossing
  *   from the received forwarded clock into the digital domain.
  */
class SidebandSerial(packetBits: Int, rxQueueDepth: Int) extends Module {
  require(packetBits > 1, s"packetBits must be at least 2, got $packetBits")

  val io = IO(new SidebandSerialIO(packetBits))

  private val bitIdxBits = log2Ceil(packetBits)

  // TX
  // ====================
  val txQueue = Module(new SidebandTxQueue(UInt(packetBits.W)))
  txQueue.io.enq <> io.tx
  io.txIdle := txQueue.io.idle
  txQueue.io.txClock := io.txClock
  txQueue.io.txReset := io.txReset || io.txRst

  val (txClkEn, txDataBit) =
    withClockAndReset(io.txClock, txQueue.io.txResetSync) {
      val txShift = Reg(UInt(packetBits.W))
      val txBitIdx = RegInit(0.U(bitIdxBits.W))
      val txBusy = RegInit(false.B)

      val clkEn = WireDefault(false.B)
      val dataBit = WireDefault(false.B)
      txQueue.io.deq.ready := !txBusy
      txQueue.io.sent := false.B

      when(!txBusy) {
        // The load cycle leaves the clock gated, so the first gated-on cycle
        // carries bit 0.
        when(txQueue.io.deq.valid) {
          txShift := txQueue.io.deq.bits
          txBitIdx := 0.U
          txBusy := true.B
        }
      }.otherwise {
        clkEn := true.B
        dataBit := txShift(0)
        txShift := txShift >> 1
        txBitIdx := txBitIdx + 1.U
        when(txBitIdx === (packetBits - 1).U) {
          txBusy := false.B
          txQueue.io.sent := true.B
        }
      }
      (clkEn, dataBit)
    }

  // The `SbDriver` on each bump does the 2:1. The forwarded clock is a gated
  // copy of the TX clock and data is held across the whole period, so the
  // falling edge lands mid-bit at the receiver.
  io.sb.txData.clk := io.txClock
  io.sb.txData.d0 := txDataBit
  io.sb.txData.d1 := txDataBit
  io.sb.txClk.clk := io.txClock
  io.sb.txClk.d0 := txClkEn
  io.sb.txClk.d1 := false.B

  // RX
  // ====================
  val rxReset = io.rxRst || reset.asBool
  val negFwClock = (!io.sb.rxClk.asBool).asClock

  val rxQueue = Module(
    new AsyncQueue(UInt(packetBits.W), AsyncQueueParams(depth = rxQueueDepth))
  )
  rxQueue.io.enq_clock := negFwClock
  rxQueue.io.enq_reset := rxReset
  rxQueue.io.deq_clock := clock
  rxQueue.io.deq_reset := rxReset

  // Assemble the serial bits in the forwarded clock domain and hand each
  // complete packet to the digital domain.
  val rxOverflow = withClockAndReset(negFwClock, rxReset.asAsyncReset) {
    val bitIdx = RegInit(0.U(bitIdxBits.W))
    val acc = RegInit(0.U(packetBits.W))
    val overflow = RegInit(false.B)

    val completePacket = acc.bitSet(bitIdx, io.sb.rxData)
    val packetDone = bitIdx === (packetBits - 1).U

    acc := completePacket
    bitIdx := Mux(packetDone, 0.U, bitIdx + 1.U)

    rxQueue.io.enq.valid := packetDone
    rxQueue.io.enq.bits := completePacket
    when(packetDone && !rxQueue.io.enq.ready) {
      overflow := true.B
    }

    overflow
  }

  // 2-FF sync of the sticky overflow flag into the digital domain.
  io.rxOverflow := RegNext(RegNext(rxOverflow, false.B), false.B)

  io.rx <> rxQueue.io.deq
}
