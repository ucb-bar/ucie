package edu.berkeley.cs.uciedigital.utils

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class ParallelGaloisLFSRTest extends AnyFunSpec with ChiselSim {
  val seed = 0x1dbfbc
  val lfsrWidth = 23
  val dataWidth = 32
  val polynomial = 0x210125
  val printDebug = false

  def debug(printDebug: Boolean, message: String): Unit = {
    if (printDebug) {
      println(message)
    }
  }

  def bitString(value: BigInt, width: Int): String =
    value.toString(2).reverse.padTo(width, '0').reverse

  def expectInitialState(dut: ParallelGaloisLFSR, refLfsr: ReferenceLFSR): Unit = {
    debug(printDebug, "[TEST] State at initialization")
    debug(printDebug, "[TEST] Reference State: 0x" + refLfsr.getStateHex())
    debug(printDebug, "[TEST] DUT State:       0x" + dut.io.state.peek().litValue.toString(16).toUpperCase)

    dut.io.state.expect(refLfsr.getState().U, "DUT initial state doesn't match the reference state")
  }

  def checkOutputAndIncrement(
    dut: ParallelGaloisLFSR,
    refLfsr: ReferenceLFSR,
    step: Int
  ): Unit = {
    debug(printDebug, s"[TEST] ======== Step ${step + 1} ========")

    val expectedOutput = refLfsr.peekOutputWord(dataWidth)
    refLfsr.advanceState(dataWidth)
    val expectedState = refLfsr.getState()
    val dutOutput = dut.io.lfsrOutput.peek().litValue

    debug(printDebug, s"[TEST] Reference output bit string: ${bitString(expectedOutput, dataWidth)}")
    debug(printDebug, s"[TEST] DUT output bit string:       ${bitString(dutOutput, dataWidth)}")

    dut.io.lfsrOutput.expect(expectedOutput.U, "DUT output doesn't match the reference output")

    dut.io.increment.poke(true.B)
    dut.clock.step()
    dut.io.increment.poke(false.B)

    debug(printDebug, s"[TEST] Reference state after incrementing: 0x${expectedState.toString(16).toUpperCase}")
    debug(printDebug, s"[TEST] DUT state after incrementing:       0x${dut.io.state.peek().litValue.toString(16).toUpperCase}")

    dut.io.state.expect(expectedState.U, "DUT state doesn't match the reference state")
  }

  describe("Parallel Galois LFSR Instantiation Test") {
    it("Instantiated ParallelGaloisLFSR") {
      simulate(new ParallelGaloisLFSR(seed, lfsrWidth, dataWidth, polynomial)) { dut =>
        dut.io.state.expect(seed.U, "DUT initial state doesn't match the seed")
        dut.clock.step()
        debug(printDebug, "[TEST] Success")
      }
    }
  }

  describe("Parallel Galois LFSR Sanity Check Test") {
    it("Parallel Galois LFSR works") {
      simulate(new ParallelGaloisLFSR(seed, lfsrWidth, dataWidth, polynomial)) { dut =>
        val refLfsr = new ReferenceLFSR(seed, polynomial, lfsrWidth)
        val numIncrements = 5

        expectInitialState(dut, refLfsr)

        for (step <- 0 until numIncrements) {
          checkOutputAndIncrement(dut, refLfsr, step)
        }

        debug(printDebug, "[TEST] Success")
      }
    }
  }

  describe("Parallel Galois LFSR Intermediate Reset Test") {
    it("Parallel Galois LFSR reset signal works") {
      simulate(new ParallelGaloisLFSR(seed, lfsrWidth, dataWidth, polynomial)) { dut =>
        val refLfsr = new ReferenceLFSR(seed, polynomial, lfsrWidth)
        val numIncrements = 5
        val whenToReset = 4 // 1 based count

        expectInitialState(dut, refLfsr)

        for (step <- 0 until numIncrements) {
          if (step == (whenToReset - 1)) {
            debug(printDebug, s"[TEST] ======== Step ${step + 1} ========")
            debug(printDebug, "[TEST] RESETTING LFSR")

            dut.io.resetLfsr.poke(true.B)
            dut.clock.step()
            dut.io.resetLfsr.poke(false.B)

            refLfsr.reset()

            debug(printDebug, "[TEST] States should be the same after reset")
            debug(printDebug, s"[TEST] Reference state after reset: 0x${refLfsr.getStateHex()}")
            debug(printDebug, s"[TEST] DUT state after reset:       0x${dut.io.state.peek().litValue.toString(16).toUpperCase}")

            dut.io.state.expect(refLfsr.getState().U, "DUT state doesn't match the reference state")
          } else {
            checkOutputAndIncrement(dut, refLfsr, step)
          }
        }

        debug(printDebug, "[TEST] Success")
      }
    }
  }
}
