package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.phy.macros.SbDriver
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.tilelink.{UcieTL, UcieTLParams}

// Loops a link's TX bumps back to its own RX bumps, the way
// `UcieBumpsIO.loopback` does, so every packet sent has to come back bit exact.
class SidebandSerialLoopback(packetBits: Int, rxQueueDepth: Int)
    extends Module {
  val io = IO(new Bundle {
    val tx = Flipped(DecoupledIO(UInt(packetBits.W)))
    val txIdle = Output(Bool())
    val rx = DecoupledIO(UInt(packetBits.W))
    val rxOverflow = Output(Bool())
    val txRst = Input(Bool())
    val rxRst = Input(Bool())
  })

  val dut = Module(new SidebandSerial(packetBits, rxQueueDepth))
  dut.io.tx <> io.tx
  io.txIdle := dut.io.txIdle
  io.rx <> dut.io.rx
  io.rxOverflow := dut.io.rxOverflow
  dut.io.txRst := io.txRst
  dut.io.rxRst := io.rxRst
  // One clock for everything here, so the serializer takes it too.
  dut.io.txClock := clock
  dut.io.txReset := reset.asBool
  // Loop back through real bump drivers, so the 2:1 is exercised too.
  dut.io.sb.rxClk := SbDriver
    .bump(
      dut.io.sb.txClk.clk,
      dut.io.sb.txClk.d0,
      dut.io.sb.txClk.d1,
      includeDefaultModels = true
    )
    .asClock
  dut.io.sb.rxData := SbDriver.bump(
    dut.io.sb.txData.clk,
    dut.io.sb.txData.d0,
    dut.io.sb.txData.d1,
    includeDefaultModels = true
  )
}

class SidebandSerialSpec extends AnyFunSpec with ChiselSim {
  // The width the TileLink-over-sideband path runs at: wide, and not a power of
  // two, so an off-by-one in the bit counter shows up as a corrupted packet.
  val frameBits = UcieTL.frameBits(UcieTLParams().creditBits)
  val queueDepth = UcieTLParams().sbRxQueueDepth

  // A packet takes `frameBits` gated cycles, plus the crossing back into the
  // digital domain.
  val drainCycles = frameBits + 40

  def idle(c: SidebandSerialLoopback): Unit = {
    c.io.tx.valid.poke(false.B)
    c.io.tx.bits.poke(0.U)
    c.io.rx.ready.poke(false.B)
    c.io.txRst.poke(false.B)
    c.io.rxRst.poke(false.B)
  }

  def pulse(c: SidebandSerialLoopback, sig: Bool): Unit = {
    sig.poke(true.B)
    c.clock.step()
    sig.poke(false.B)
    c.clock.step()
  }

  // Offers a packet until the link takes it. Coming out of reset the TX queue
  // takes a few cycles to be ready, while its two clock domains finish their
  // reset handshake.
  def offer(c: SidebandSerialLoopback, packet: BigInt): Unit = {
    c.io.tx.bits.poke(packet.U)
    c.io.tx.valid.poke(true.B)
    var n = 0
    while (!c.io.tx.ready.peek().litToBoolean) {
      c.clock.step()
      n += 1
      assert(n < 100, "the link never took the packet")
    }
    c.clock.step()
    c.io.tx.valid.poke(false.B)
  }

  def send(c: SidebandSerialLoopback, packet: BigInt): Unit = {
    offer(c, packet)
    c.clock.step(drainCycles)
  }

  def pop(c: SidebandSerialLoopback): BigInt = {
    val got = c.io.rx.bits.peek().litValue
    pulse(c, c.io.rx.ready)
    got
  }

  describe("sideband serial link") {
    it("should loop back a TL-sized frame bit exactly") {
      simulate(new SidebandSerialLoopback(frameBits, queueDepth)) { c =>
        // All ones, all zeros, and a pattern whose LSB and MSB are both 0, so
        // nothing about the packet itself can be acting as framing.
        val allOnes = (BigInt(1) << frameBits) - 1
        val walking =
          (0 until frameBits).foldLeft(BigInt(0)) { (acc, i) =>
            if (i % 3 == 0 && i != 0 && i != frameBits - 1) acc.setBit(i)
            else acc
          }
        val packets = Seq(allOnes, BigInt(0), walking)

        idle(c)
        c.clock.step(4)
        pulse(c, c.io.rxRst)

        packets.foreach { p =>
          send(c, p)
          withClue(s"packet 0x${p.toString(16)}: ") {
            c.io.rx.valid.expect(true.B)
            assert(pop(c) == p)
          }
        }
        c.io.rx.valid.expect(false.B)
        c.io.rxOverflow.expect(false.B)
      }
    }

    it("should queue a frame offered while one is in flight") {
      simulate(new SidebandSerialLoopback(frameBits, queueDepth)) { c =>
        val first = BigInt(1) << (frameBits - 1)
        val second = (BigInt(1) << frameBits) - 1
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.rxRst)

        // The second frame goes into the TX queue while the first is on the
        // wire, and follows it out intact, without corrupting it.
        offer(c, first)
        c.io.txIdle.expect(false.B)
        offer(c, second)
        c.clock.step(2 * drainCycles)

        c.io.txIdle.expect(true.B)
        c.io.rx.valid.expect(true.B)
        assert(pop(c) == first)
        c.io.rx.valid.expect(true.B)
        assert(pop(c) == second)
        c.io.rx.valid.expect(false.B)
      }
    }

    it("should drop a frame mid-flight on a TX reset") {
      simulate(new SidebandSerialLoopback(frameBits, queueDepth)) { c =>
        idle(c)
        c.clock.step(4)
        pulse(c, c.io.rxRst)

        offer(c, (BigInt(1) << frameBits) - 1)
        c.clock.step(frameBits / 2)
        pulse(c, c.io.txRst)
        c.clock.step(drainCycles)

        // Nothing complete ever reached the receiver, and the transmitter is
        // free again.
        c.io.tx.ready.expect(true.B)
        c.io.rx.valid.expect(false.B)

        // The receiver saw half a frame, so it has to be realigned before the
        // next one.
        pulse(c, c.io.rxRst)
        send(c, BigInt(0x5a5a5a5aL))
        c.io.rx.valid.expect(true.B)
        assert(pop(c) == BigInt(0x5a5a5a5aL))
      }
    }
  }
}
