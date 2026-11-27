package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._

import edu.berkeley.cs.uciedigital.phy.{SbIO, SidebandSerial}

object SidebandTest {
  // Bits per packet: the 64-bit chunk the sideband spec sends, and so what
  // `SidebandLinkSerializer` puts on the wire.
  val PacketBits = 64

  // Received packets waiting to be read over MMIO. Also the depth of the
  // crossing from the received forwarded clock to the digital domain.
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
  // High from a send until its packet has been shifted out, and while the link
  // is still coming out of reset.
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
  // `rxOverflow`. Use it to realign after a partner reset mid-packet leaves
  // the receiver an odd number of bits out of step.
  val rxRst = Input(Bool())
}

class SidebandTestIO extends Bundle {
  val regs = new SidebandTestRegsIO

  /** Low while another block owns the sideband bumps. Holds the tester's link
    * in reset, so it does not drive the bumps or turn that block's traffic into
    * bogus packets.
    */
  val en = Input(Bool())

  /** The 800 MHz sideband TX clock the serializer runs on, and its reset. */
  val txClock = Input(Clock())
  val txReset = Input(Bool())

  // PHY INTERFACE
  // ====================
  val sb = Flipped(new SbIO)
}

/** Sideband PHY tester.
  *
  * Sends and receives single `SidebandTest.PacketBits`-bit packets, staged
  * through MMIO, over a [[SidebandSerial]] link. The TileLink-over-sideband
  * path in `UcieTL` uses the same link, so both put the same waveform on the
  * wire.
  */
class SidebandTest extends Module {
  import SidebandTest._

  val io = IO(new SidebandTestIO)

  val link = Module(new SidebandSerial(PacketBits, RxQueueDepth))
  io.sb <> link.io.sb

  // The transmitter leaves busy by itself once a packet is shifted out, so
  // only `en` going low needs to reset it. Both resets come from flops, as
  // the link needs.
  link.io.txRst := RegNext(!io.en, true.B)
  link.io.txClock := io.txClock
  link.io.txReset := io.txReset
  // One packet at a time: a send while the last packet is still queued or on
  // the wire is dropped.
  link.io.tx.valid := io.regs.txSend && io.en && link.io.txIdle
  link.io.tx.bits := io.regs.txPacket
  // Not ready only after reset, while the TX queue's two clock domains finish
  // their reset handshake. A send then is also dropped.
  io.regs.txBusy := !link.io.txIdle || !link.io.tx.ready

  link.io.rxRst := RegNext(io.regs.rxRst || !io.en, true.B)
  link.io.rx.ready := io.regs.rxPop
  io.regs.rxPacket := link.io.rx.bits
  io.regs.rxValid := link.io.rx.valid
  io.regs.rxOverflow := link.io.rxOverflow
}
