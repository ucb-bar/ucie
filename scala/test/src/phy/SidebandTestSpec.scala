package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

// Loops the sideband tester's TX bumps back to its own RX bumps, the way
// `UcieBumpsIO.loopback` does, so every packet sent has to come back bit exact.
class SidebandTestLoopback extends Module {
  val io = IO(new SidebandTestRegsIO)

  val dut = Module(new SidebandTest)
  dut.io.regs <> io
  dut.io.sb.rxClk := dut.io.sb.txClk
  dut.io.sb.rxData := dut.io.sb.txData
}

class SidebandTestSpec extends AnyFunSpec with ChiselSim {
  // A packet takes `PacketBits` gated cycles, plus the crossing back into the
  // digital domain.
  val drainCycles = SidebandTest.PacketBits + 40

  def idle(c: SidebandTestLoopback): Unit = {
    c.io.txPacket.poke(0.U)
    c.io.txSend.poke(false.B)
    c.io.rxPop.poke(false.B)
    c.io.rxRst.poke(false.B)
  }

  def pulse(c: SidebandTestLoopback, sig: Bool): Unit = {
    sig.poke(true.B)
    c.clock.step()
    sig.poke(false.B)
    c.clock.step()
  }

  def send(c: SidebandTestLoopback, packet: BigInt): Unit = {
    c.io.txPacket.poke(packet.U)
    pulse(c, c.io.txSend)
    c.clock.step(drainCycles)
  }

  def pop(c: SidebandTestLoopback): BigInt = {
    val got = c.io.rxPacket.peek().litValue
    pulse(c, c.io.rxPop)
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
        pulse(c, c.io.rxRst)

        packets.foreach { p =>
          send(c, p)
          withClue(s"packet 0x${p.toString(16)}: ") {
            c.io.rxValid.expect(true.B)
            assert(pop(c) == p)
          }
        }
        c.io.rxValid.expect(false.B)
        c.io.rxOverflow.expect(false.B)
      }
    }

    it("should queue packets that are not popped right away") {
      simulate(new SidebandTestLoopback) { c =>
        val packets = Seq(BigInt(1), BigInt(2), BigInt(3))
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.rxRst)

        packets.foreach(send(c, _))
        packets.foreach { p =>
          c.io.rxValid.expect(true.B)
          assert(pop(c) == p)
        }
        c.io.rxValid.expect(false.B)
        c.io.rxOverflow.expect(false.B)
      }
    }

    it("should hold off a send while busy and report busy") {
      simulate(new SidebandTestLoopback) { c =>
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.rxRst)

        c.io.txBusy.expect(false.B)
        c.io.txPacket.poke(BigInt("a5a5a5a5a5a5a5a5", 16).U)
        pulse(c, c.io.txSend)
        c.io.txBusy.expect(true.B)

        // A send request mid-packet must be ignored, not corrupt the packet.
        c.io.txPacket.poke(BigInt("deadbeefdeadbeef", 16).U)
        pulse(c, c.io.txSend)
        c.clock.step(drainCycles)

        c.io.txBusy.expect(false.B)
        c.io.rxValid.expect(true.B)
        assert(pop(c) == BigInt("a5a5a5a5a5a5a5a5", 16))
        c.io.rxValid.expect(false.B)
      }
    }

    it("should flag an overflow when packets are never popped") {
      simulate(new SidebandTestLoopback) { c =>
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.rxRst)

        for (i <- 0 until SidebandTest.RxQueueDepth + 2) {
          send(c, BigInt(i + 1))
        }
        c.io.rxOverflow.expect(true.B)

        // Reset clears the backlog and the flag.
        pulse(c, c.io.rxRst)
        c.clock.step(8)
        c.io.rxOverflow.expect(false.B)
        c.io.rxValid.expect(false.B)
      }
    }
  }
}
