package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.simutils.VerilatorCoverage
import org.scalatest.funspec.AnyFunSpec
import scala.collection.mutable.ArrayBuffer

class SidebandInterfaceSerdesTest extends AnyFunSpec with ChiselSim with VerilatorCoverage {
  val msgW = 128
  val ncWidths = Seq(8, 16, 32)

  val printDebugs = false
  def printDebug(msg: String): Unit = if (printDebugs) println(s"[SidebandInterfaceSerdesTest] $msg")

  def mask(w: Int): BigInt = (BigInt(1) << w) - 1
  def beat(msg: BigInt, i: Int, ncW: Int): BigInt = (msg >> (i * ncW)) & mask(ncW)

  val msgA = BigInt("0123456789abcdef0123456789abcdef", 16)
  val msgB = BigInt("fedcba9876543210fedcba9876543210", 16)

  for (ncW <- ncWidths) {
    val n = msgW / ncW

    describe(s"SidebandInterfaceSerializer (ncWidth=$ncW)") {
      it("emits every beat LSB-first then goes idle") {
        simulate(new SidebandInterfaceSerializer(msgW, ncW)) { c =>
          c.io.in.bits.poke(msgA.U(msgW.W))
          c.io.in.valid.poke(true.B)
          c.io.in.ready.expect(true.B)
          c.clock.step()
          c.io.in.valid.poke(false.B)

          var got = BigInt(0)
          for (i <- 0 until n) {
            c.io.out.valid.expect(true.B)
            got |= c.io.out.bits.peek().litValue << (i * ncW)
            c.clock.step()
          }
          c.io.out.valid.expect(false.B)
          assert(got == msgA)
        }
      }

      it("serializes two messages back-to-back with no idle gap") {
        simulate(new SidebandInterfaceSerializer(msgW, ncW)) { c =>
          c.io.in.bits.poke(msgA.U(msgW.W))
          c.io.in.valid.poke(true.B)
          c.io.in.ready.expect(true.B)
          c.clock.step()

          c.io.in.bits.poke(msgB.U(msgW.W)) // present next while first drains
          var g1 = BigInt(0)
          for (i <- 0 until n) {
            c.io.out.valid.expect(true.B)
            g1 |= c.io.out.bits.peek().litValue << (i * ncW)
            c.io.in.ready.expect((i == n - 1).B)
            c.clock.step()
          }
          c.io.in.valid.poke(false.B)

          var g2 = BigInt(0)
          for (i <- 0 until n) {
            c.io.out.valid.expect(true.B)
            g2 |= c.io.out.bits.peek().litValue << (i * ncW)
            c.clock.step()
          }
          c.io.out.valid.expect(false.B)
          assert(g1 == msgA && g2 == msgB)
        }
      }
    }

    describe(s"SidebandInterfaceDeserializer (ncWidth=$ncW)") {
      it("assembles beats into one message with a single valid pulse") {
        simulate(new SidebandInterfaceDeserializer(msgW, ncW)) { c =>
          for (i <- 0 until n) {
            c.io.in.valid.poke(true.B)
            c.io.in.bits.poke(beat(msgA, i, ncW).U(ncW.W))
            c.io.out.valid.expect(false.B)
            c.clock.step()
          }
          c.io.in.valid.poke(false.B)
          c.io.out.valid.expect(true.B)
          c.io.out.bits.expect(msgA.U(msgW.W))
          c.clock.step()
          c.io.out.valid.expect(false.B)
        }
      }

      it("aborts a partial message on a gap and recovers") {
        simulate(new SidebandInterfaceDeserializer(msgW, ncW)) { c =>
          for (i <- 0 until n - 1) {
            c.io.in.valid.poke(true.B)
            c.io.in.bits.poke(beat(msgA, i, ncW).U(ncW.W))
            c.clock.step()
          }
          c.io.in.valid.poke(false.B)
          c.clock.step()
          c.io.out.valid.expect(false.B) // partial message discarded

          for (i <- 0 until n) {
            c.io.in.valid.poke(true.B)
            c.io.in.bits.poke(beat(msgB, i, ncW).U(ncW.W))
            c.clock.step()
          }
          c.io.in.valid.poke(false.B)
          c.io.out.valid.expect(true.B)
          c.io.out.bits.expect(msgB.U(msgW.W))
        }
      }
    }
  }

  describe("SidebandInterfaceSerializer reset") {
    it("clears in-flight serialization on reset") {
      simulate(new SidebandInterfaceSerializer(msgW, 32)) { c =>
        c.io.in.bits.poke(msgA.U(msgW.W))
        c.io.in.valid.poke(true.B)
        c.clock.step()
        c.io.in.valid.poke(false.B)
        c.io.out.valid.expect(true.B)

        c.reset.poke(true.B)
        c.clock.step()
        c.reset.poke(false.B)
        c.io.out.valid.expect(false.B)
      }
    }
  }

  describe("SidebandInterfaceDeserializer reset and streaming") {
    it("clears partial assembly on reset") {
      simulate(new SidebandInterfaceDeserializer(msgW, 32)) { c =>
        for (i <- 0 until 2) {
          c.io.in.valid.poke(true.B)
          c.io.in.bits.poke(beat(msgA, i, 32).U(32.W))
          c.clock.step()
        }
        c.io.in.valid.poke(false.B)

        c.reset.poke(true.B)
        c.clock.step()
        c.reset.poke(false.B)
        c.io.out.valid.expect(false.B)

        for (i <- 0 until 4) {
          c.io.in.valid.poke(true.B)
          c.io.in.bits.poke(beat(msgB, i, 32).U(32.W))
          c.clock.step()
        }
        c.io.in.valid.poke(false.B)
        c.io.out.valid.expect(true.B)
        c.io.out.bits.expect(msgB.U(msgW.W))
      }
    }

    it("assembles back-to-back messages with no gap") {
      simulate(new SidebandInterfaceDeserializer(msgW, 32)) { c =>
        val pulses = ArrayBuffer[BigInt]()
        for (m <- Seq(msgA, msgB)) {
          for (i <- 0 until 4) {
            c.io.in.valid.poke(true.B)
            c.io.in.bits.poke(beat(m, i, 32).U(32.W))
            if (c.io.out.valid.peek().litToBoolean) {
              pulses += c.io.out.bits.peek().litValue
            }
            c.clock.step()
          }
        }
        c.io.in.valid.poke(false.B)
        if (c.io.out.valid.peek().litToBoolean) {
          pulses += c.io.out.bits.peek().litValue
        }
        assert(pulses.toSeq == Seq(msgA, msgB))
      }
    }
  }
}
