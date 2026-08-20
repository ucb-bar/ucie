package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._

import edu.berkeley.cs.uciedigital.phy.{SbIO, SidebandSerial}

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

  /** Low while another block owns the sideband bumps. Holds the tester's link
    * in reset so it neither drives the bumps nor assembles that block's traffic
    * into nonsense packets.
    */
  val en = Input(Bool())

  // PHY INTERFACE
  // ====================
  val sb = Flipped(new SbIO)
}

/** Sideband PHY tester.
  *
  * Stages single `SidebandTest.PacketBits`-bit packets through MMIO over a
  * [[SidebandSerial]] link, which is also what the TileLink-over-sideband path
  * in `UcieTL` uses, so both put the same waveform on the wire.
  */
class SidebandTest extends Module {
  import SidebandTest._

  val io = IO(new SidebandTestIO)

  val link = Module(new SidebandSerial(PacketBits, RxQueueDepth))
  io.sb <> link.io.sb

  // The transmitter drops out of busy on its own once a packet is shifted out,
  // so `en` going low is the only thing that has to reset it.
  link.io.txRst := !io.en
  link.io.tx.valid := io.regs.txSend && io.en
  link.io.tx.bits := io.regs.txPacket
  io.regs.txBusy := !link.io.tx.ready

  link.io.rxRst := io.regs.rxRst || !io.en
  link.io.rx.ready := io.regs.rxPop
  io.regs.rxPacket := link.io.rx.bits
  io.regs.rxValid := link.io.rx.valid
  io.regs.rxOverflow := link.io.rxOverflow
}
