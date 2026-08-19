package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._

import edu.berkeley.cs.uciedigital.utils.Ser21
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}

object SidebandTest {
  // Bits per packet. Matches the 64-bit chunk the sideband spec transmits, and
  // therefore what `SidebandLinkSerializer` puts on the wire.
  val PacketBits = 64

  // Received packets waiting to be read out over MMIO. Also the depth of the
  // crossing from the received forwarded clock into the digital domain.
  val RxQueueDepth = 4
}

/** Control registers for the sideband PHY tester. */
class SidebandTestRegsIO extends Bundle {
  // TX CONTROL
  // =====================
  // Packet to transmit. Sent LSB first.
  val txPacket = Input(UInt(SidebandTest.PacketBits.W))
  // Starts transmitting `txPacket`. Ignored while `txBusy` is high.
  val txSend = Input(Bool())
  // High while a packet is being shifted out.
  val txBusy = Output(Bool())

  // RX CONTROL
  // =====================
  // Oldest received packet, valid when `rxValid` is high.
  val rxPacket = Output(UInt(SidebandTest.PacketBits.W))
  // High when `rxPacket` holds a packet that has not been popped yet.
  val rxValid = Output(Bool())
  // Drops `rxPacket` and advances to the next received packet.
  val rxPop = Input(Bool())
  // Sticky: a packet arrived with no room to store it and was dropped.
  val rxOverflow = Output(Bool())
  // Resets the receiver: clears the bit counter, the stored packets, and
  // `rxOverflow`. Realigns the receiver if a partner reset mid-packet left it
  // an odd number of bits out of step.
  val rxRst = Input(Bool())
}

class SidebandTestIO extends Bundle {
  val regs = new SidebandTestRegsIO

  // PHY INTERFACE
  // ====================
  val sb = Flipped(new SbIO)
}

/** Sideband PHY tester.
  *
  * Sends and receives single `PacketBits`-bit packets staged through MMIO. No
  * in-band framing is needed: the forwarded clock is gated off between packets,
  * so the receiver only sees exactly the `PacketBits` edges the transmitter
  * drives, and its bit counter cannot drift while the link is idle.
  *
  * Clocking matches
  * [[edu.berkeley.cs.uciedigital.sideband.SidebandLinkSerializer]] so that the
  * tester and the real sideband are interchangeable on the wire: the forwarded
  * clock is a gated copy of the digital clock produced through a 2:1
  * serializer, data is held for the full period, and the receiver samples on
  * the falling edge, in the middle of the eye.
  */
class SidebandTest extends Module {
  import SidebandTest._

  val io = IO(new SidebandTestIO)

  private val bitIdxBits = log2Ceil(PacketBits)

  // TX
  // ====================
  val txShift = Reg(UInt(PacketBits.W))
  val txBitIdx = RegInit(0.U(bitIdxBits.W))
  val txBusy = RegInit(false.B)

  val txClkEn = Wire(Bool())
  val txDataBit = Wire(Bool())
  txClkEn := false.B
  txDataBit := false.B

  when(!txBusy) {
    // The load cycle leaves the clock gated, so the first gated-on cycle
    // carries bit 0.
    when(io.regs.txSend) {
      txShift := io.regs.txPacket
      txBitIdx := 0.U
      txBusy := true.B
    }
  }.otherwise {
    txClkEn := true.B
    txDataBit := txShift(0)
    txShift := txShift >> 1
    txBitIdx := txBitIdx + 1.U
    when(txBitIdx === (PacketBits - 1).U) {
      txBusy := false.B
    }
  }

  io.regs.txBusy := txBusy

  // The forwarded clock is a gated copy of the digital clock and data is held
  // across the whole period, so the falling edge lands mid-bit at the receiver.
  val txsbd = Module(new Ser21)
  txsbd.io.clk := clock
  txsbd.io.d0 := txDataBit
  txsbd.io.d1 := txDataBit
  val txsbc = Module(new Ser21)
  txsbc.io.clk := clock
  txsbc.io.d0 := txClkEn
  txsbc.io.d1 := 0.U
  io.sb.txData := txsbd.io.out.asBool
  io.sb.txClk := txsbc.io.out.asBool.asClock

  // RX
  // ====================
  val rxReset = io.regs.rxRst || reset.asBool
  val negFwClock = (!io.sb.rxClk.asBool).asClock

  val rxQueue = Module(
    new AsyncQueue(UInt(PacketBits.W), AsyncQueueParams(depth = RxQueueDepth))
  )
  rxQueue.io.enq_clock := negFwClock
  rxQueue.io.enq_reset := rxReset
  rxQueue.io.deq_clock := clock
  rxQueue.io.deq_reset := rxReset

  // Assemble the serial bits in the forwarded clock domain and hand each
  // complete packet to the digital domain.
  val rxOverflow = withClockAndReset(negFwClock, rxReset.asAsyncReset) {
    val bitIdx = RegInit(0.U(bitIdxBits.W))
    val acc = RegInit(0.U(PacketBits.W))
    val overflow = RegInit(false.B)

    val completePacket = acc.bitSet(bitIdx, io.sb.rxData)
    val packetDone = bitIdx === (PacketBits - 1).U

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
  io.regs.rxOverflow := RegNext(RegNext(rxOverflow, false.B), false.B)

  rxQueue.io.deq.ready := io.regs.rxPop
  io.regs.rxPacket := rxQueue.io.deq.bits
  io.regs.rxValid := rxQueue.io.deq.valid
}
