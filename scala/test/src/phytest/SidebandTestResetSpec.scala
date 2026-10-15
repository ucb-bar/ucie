package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

/** Resetting the sideband tester's receiver.
  *
  * `SidebandTestRegsIO.rxRst` is documented as "Resets the receiver: clears the
  * bit counter, the stored packets, and `rxOverflow`", and `SidebandTest`
  * drives the same reset from `!io.en`, so handing the bumps to another block
  * and taking them back is supposed to leave the receiver empty.
  */
class SidebandTestResetSpec extends AnyFunSpec with ChiselSim {
  val packet = SidebandTest.PacketBits

  // A packet takes `packet` gated cycles, plus the crossing into the digital
  // domain.
  val drainCycles = packet + 40

  def idle(c: SidebandTestLoopback): Unit = {
    c.io.en.poke(true.B)
    c.io.regs.txPacket.poke(0.U)
    c.io.regs.txSend.poke(false.B)
    c.io.regs.rxPop.poke(false.B)
    c.io.regs.rxRst.poke(false.B)
  }

  def pulse(c: SidebandTestLoopback, sig: Bool): Unit = {
    sig.poke(true.B)
    c.clock.step()
    sig.poke(false.B)
    c.clock.step()
  }

  def send(c: SidebandTestLoopback, value: BigInt): Unit = {
    c.io.regs.txPacket.poke(value.U)
    pulse(c, c.io.regs.txSend)
    c.clock.step(drainCycles)
  }

  describe("sideband tester receiver reset") {
    it("should drop an unread packet on rxRst") {
      simulate(new SidebandTestLoopback) { c =>
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.regs.rxRst)

        // A packet arrives and is deliberately left unread.
        send(c, BigInt("0123456789abcdef", 16))
        c.io.regs.rxValid.expect(true.B)

        // The link is quiet now, so the forwarded clock is gated off. Reset the
        // receiver: the stored packet is supposed to go away.
        pulse(c, c.io.regs.rxRst)
        c.clock.step(8)
        c.io.regs.rxValid.expect(
          false.B,
          "rxRst left a previously received packet in the receiver"
        )
      }
    }

    it("should drop an unread packet when the sideband is handed away") {
      simulate(new SidebandTestLoopback) { c =>
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.regs.rxRst)

        send(c, BigInt("0123456789abcdef", 16))
        c.io.regs.rxValid.expect(true.B)

        // TileLink takes the sideband and gives it back. `SidebandTest` holds
        // the link in reset the whole time it is not enabled.
        c.io.en.poke(false.B)
        c.clock.step(8)
        c.io.en.poke(true.B)
        c.clock.step(8)
        c.io.regs.rxValid.expect(
          false.B,
          "a packet from before the mode switch is still in the receiver"
        )
      }
    }

    it("should receive the next packet correctly after an idle rxRst") {
      simulate(new SidebandTestLoopback) { c =>
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.regs.rxRst)

        send(c, BigInt("0123456789abcdef", 16))
        c.io.regs.rxValid.expect(true.B)

        // Reset with the link quiet, then send a fresh packet. Whatever the
        // reset does to the packet already stored, the next one has to arrive
        // intact.
        pulse(c, c.io.regs.rxRst)
        c.clock.step(8)
        val wanted = BigInt("fedcba9876543210", 16)
        send(c, wanted)

        c.io.regs.rxValid
          .expect(true.B, "the packet sent after rxRst never arrived")
        val got = c.io.regs.rxPacket.peek().litValue
        assert(
          got == wanted,
          f"receiver handed back 0x$got%x, expected 0x$wanted%x"
        )
      }
    }
  }
}
