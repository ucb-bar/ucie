package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

// Loops the sideband tester's TX bumps back to its own RX bumps, the way
// `UcieBumpsIO.loopback` does, so every packet sent has to come back bit exact.
class SidebandTestLoopback extends Module {
  val io = IO(new Bundle {
    val regs = new SidebandTestRegsIO
    val en = Input(Bool())
  })

  val dut = Module(new SidebandTest)
  dut.io.regs <> io.regs
  dut.io.en := io.en
  dut.io.sb.rxClk := dut.io.sb.txClk
  dut.io.sb.rxData := dut.io.sb.txData
}

class SidebandTestSpec extends AnyFunSpec with ChiselSim {
  // A packet takes `PacketBits` gated cycles, plus the crossing back into the
  // digital domain.
  val drainCycles = SidebandTest.PacketBits + 40

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

  def send(c: SidebandTestLoopback, packet: BigInt): Unit = {
    c.io.regs.txPacket.poke(packet.U)
    pulse(c, c.io.regs.txSend)
    c.clock.step(drainCycles)
  }

  def pop(c: SidebandTestLoopback): BigInt = {
    val got = c.io.regs.rxPacket.peek().litValue
    pulse(c, c.io.regs.rxPop)
    got
  }

  describe("sideband tester") {
    it("should loop back a packet bit exactly") {
      simulate(new SidebandTestLoopback) { c =>
        // All ones, all zeros, and a pattern whose LSB and MSB are both 0, so
        // nothing about the packet itself can be acting as framing.
        val packets = Seq(
          BigInt("ffffffffffffffff", 16),
          BigInt(0),
          BigInt("7edcba9876543210", 16)
        )
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.regs.rxRst)

        packets.foreach { p =>
          send(c, p)
          withClue(s"packet 0x${p.toString(16)}: ") {
            c.io.regs.rxValid.expect(true.B)
            assert(pop(c) == p)
          }
        }
        c.io.regs.rxValid.expect(false.B)
        c.io.regs.rxOverflow.expect(false.B)
      }
    }

    it("should queue packets that are not popped right away") {
      simulate(new SidebandTestLoopback) { c =>
        val packets = Seq(BigInt(1), BigInt(2), BigInt(3))
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.regs.rxRst)

        packets.foreach(send(c, _))
        packets.foreach { p =>
          c.io.regs.rxValid.expect(true.B)
          assert(pop(c) == p)
        }
        c.io.regs.rxValid.expect(false.B)
        c.io.regs.rxOverflow.expect(false.B)
      }
    }

    it("should hold off a send while busy and report busy") {
      simulate(new SidebandTestLoopback) { c =>
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.regs.rxRst)

        c.io.regs.txBusy.expect(false.B)
        c.io.regs.txPacket.poke(BigInt("a5a5a5a5a5a5a5a5", 16).U)
        pulse(c, c.io.regs.txSend)
        c.io.regs.txBusy.expect(true.B)

        // A send request mid-packet must be ignored, not corrupt the packet.
        c.io.regs.txPacket.poke(BigInt("deadbeefdeadbeef", 16).U)
        pulse(c, c.io.regs.txSend)
        c.clock.step(drainCycles)

        c.io.regs.txBusy.expect(false.B)
        c.io.regs.rxValid.expect(true.B)
        assert(pop(c) == BigInt("a5a5a5a5a5a5a5a5", 16))
        c.io.regs.rxValid.expect(false.B)
      }
    }

    it("should stay off the bumps while disabled") {
      simulate(new SidebandTestLoopback) { c =>
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.regs.rxRst)

        // With `en` low the tester neither transmits nor assembles whatever the
        // block that owns the sideband is putting on the wire.
        c.io.en.poke(false.B)
        c.io.regs.txPacket.poke(BigInt("ffffffffffffffff", 16).U)
        pulse(c, c.io.regs.txSend)
        c.clock.step(drainCycles)
        c.io.regs.rxValid.expect(false.B)
        c.io.regs.rxOverflow.expect(false.B)

        // Re-enabling brings it back, aligned to the next packet.
        c.io.en.poke(true.B)
        c.clock.step(4)
        send(c, BigInt("0123456789abcdef", 16))
        c.io.regs.rxValid.expect(true.B)
        assert(pop(c) == BigInt("0123456789abcdef", 16))
      }
    }

    it("should flag an overflow when packets are never popped") {
      simulate(new SidebandTestLoopback) { c =>
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.regs.rxRst)

        for (i <- 0 until SidebandTest.RxQueueDepth + 2) {
          send(c, BigInt(i + 1))
        }
        c.io.regs.rxOverflow.expect(true.B)

        // Reset clears the backlog and the flag.
        pulse(c, c.io.regs.rxRst)
        c.clock.step(8)
        c.io.regs.rxOverflow.expect(false.B)
        c.io.regs.rxValid.expect(false.B)
      }
    }
  }
}
