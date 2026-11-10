package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils

/** The two sticky queue flags, on their own. */
class QueueFlags extends Module {
  val io = IO(new Bundle {
    val enqReady = Input(Bool())
    val deqValid = Input(Bool())
    val feeding = Input(Bool())
    val overflow = Output(Bool())
    val underflow = Output(Bool())
  })
  io.overflow := PhyTest.overflowFlag(io.enqReady)
  io.underflow := PhyTest.underflowFlag(io.deqValid, io.feeding)
}

/** Counters and flags PhyTest keeps whatever mode it is in. */
class PhyTestStatusSpec extends AnyFunSpec with ChiselSim {
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  val numLanes = 4

  describe("PhyTest track counters") {
    it("should count track's ones and zeros whatever owns the mainband") {
      simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
        // TileLink owns the mainband, so the RX FSM is held throughout.
        c.io.regs.mainbandMode.poke(BandMode.tl)
        c.io.regs.trackCntRst.poke(true.B)
        c.clock.step()
        c.io.regs.trackCntRst.poke(false.B)

        def send(n: Int, track: BigInt): Unit = {
          c.io.rx.bits.track.poke(track.U)
          c.io.rx.valid.poke(true.B)
          c.clock.step(n)
          c.io.rx.valid.poke(false.B)
          c.clock.step()
        }
        def counts: (BigInt, BigInt) = (
          c.io.regs.trackOnes.peek().litValue,
          c.io.regs.trackZeros.peek().litValue
        )

        send(10, BigInt("000000ff", 16))
        assert(counts == (BigInt(80), BigInt(240)))
        assert(c.io.regs.rxPacketsReceived.peek().litValue == 0)

        c.io.regs.trackCntHold.poke(true.B)
        send(10, BigInt("ffffffff", 16))
        assert(counts == (BigInt(80), BigInt(240)), "held")
        c.io.regs.trackCntHold.poke(false.B)
        send(1, BigInt("ffffffff", 16))
        assert(counts == (BigInt(112), BigInt(240)))

        c.io.regs.trackCntRst.poke(true.B)
        c.clock.step()
        c.io.regs.trackCntRst.poke(false.B)
        assert(counts == (BigInt(0), BigInt(0)))
      }
    }
  }

  describe("PhyTest queue flags") {
    it("should flag an overflow only once the queue has been ready") {
      simulate(new QueueFlags) { c =>
        c.io.enqReady.poke(false.B)
        c.clock.step(4)
        assert(!c.io.overflow.peek().litToBoolean, "still handshaking")
        c.io.enqReady.poke(true.B)
        c.clock.step(4)
        assert(!c.io.overflow.peek().litToBoolean)
        c.io.enqReady.poke(false.B)
        c.clock.step()
        c.io.enqReady.poke(true.B)
        c.clock.step()
        assert(c.io.overflow.peek().litToBoolean, "sticky")
      }
    }

    it("should flag an underflow only while fed since the reset") {
      simulate(new QueueFlags) { c =>
        c.io.feeding.poke(true.B)
        c.io.deqValid.poke(false.B)
        c.clock.step(4)
        assert(!c.io.underflow.peek().litToBoolean, "nothing arrived yet")
        c.io.deqValid.poke(true.B)
        c.clock.step(4)
        c.io.deqValid.poke(false.B)
        c.clock.step()
        c.io.deqValid.poke(true.B)
        c.clock.step()
        assert(c.io.underflow.peek().litToBoolean, "sticky")
      }
      simulate(new QueueFlags) { c =>
        // Fed by someone else for a while: the queue may have run dry for
        // reasons of its own, so the flag stays disarmed until a reset.
        c.io.feeding.poke(false.B)
        c.io.deqValid.poke(true.B)
        c.clock.step(2)
        c.io.feeding.poke(true.B)
        c.clock.step(2)
        c.io.deqValid.poke(false.B)
        c.clock.step(2)
        assert(!c.io.underflow.peek().litToBoolean)
      }
    }
  }
}
