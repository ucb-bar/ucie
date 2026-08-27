package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.utils.ReferenceLFSR
import org.scalatest.funspec.AnyFunSpec

import scala.util.Random

class UcieLFSRTest extends AnyFunSpec with ChiselSim {
  val serializerRatio = 32
  val lanes = 16
  val deterministicSteps = 8
  val randomSteps = 32
  val randomSeed = 0x5eedL

  val lfsrWidth = 23
  val polynomial = BigInt(0x210125)
  val laneSeeds = Seq(
    BigInt(0x1dbfbc),
    BigInt(0x0607bb),
    BigInt(0x1ec760),
    BigInt(0x18c0db),
    BigInt(0x010f12),
    BigInt(0x19cfc9),
    BigInt(0x0277ce),
    BigInt(0x1bb807)
  )

  private def params =
    AfeParams(mbSerializerRatio = serializerRatio, mbLanes = lanes)

  private def laneReferenceModels(): Seq[ReferenceLFSR] =
    Seq.tabulate(lanes) { lane =>
      new ReferenceLFSR(
        laneSeeds(lane % laneSeeds.length),
        polynomial,
        lfsrWidth
      )
    }

  private def clearControls(dut: UcieLFSR): Unit = {
    for (lane <- 0 until lanes) {
      dut.io.increment(lane).poke(false.B)
      dut.io.resetLfsr(lane).poke(false.B)
    }
  }

  private def expectOutputs(
      dut: UcieLFSR,
      refs: Seq[ReferenceLFSR],
      context: String
  ): Unit = {
    for (lane <- 0 until lanes) {
      val expected = refs(lane).peekOutputWord(serializerRatio)
      val actual = dut.io.lfsrOutput(lane).peek().litValue
      assert(
        actual == expected,
        s"$context lane $lane output mismatch: expected 0x${expected
            .toString(16)}, got 0x${actual.toString(16)}"
      )
    }
  }

  describe("UcieLFSR") {
    it("verifies initial output uses the UCIe lane seed order modulo eight") {
      simulate(new UcieLFSR(params)) { dut =>
        clearControls(dut)

        expectOutputs(dut, laneReferenceModels(), "initial")
      }
    }

    it("matches the reference model when all lanes increment") {
      simulate(new UcieLFSR(params)) { dut =>
        val refs = laneReferenceModels()
        clearControls(dut)

        for (step <- 0 until deterministicSteps) {
          expectOutputs(dut, refs, s"deterministic step $step before increment")

          for (lane <- 0 until lanes) {
            dut.io.increment(lane).poke(true.B)
            dut.io.resetLfsr(lane).poke(false.B)
          }
          dut.clock.step()

          refs.foreach(_.advanceState(serializerRatio))
          clearControls(dut)
          expectOutputs(dut, refs, s"deterministic step $step after increment")
        }
      }
    }

    it(
      "matches the reference model with randomized per-lane reset and increment"
    ) {
      simulate(new UcieLFSR(params)) { dut =>
        val refs = laneReferenceModels()
        val random = new Random(randomSeed)
        clearControls(dut)

        for (step <- 0 until randomSteps) {
          expectOutputs(dut, refs, s"random step $step before control update")

          val controls = Seq.tabulate(lanes) { lane =>
            val doReset = random.nextBoolean()
            val doIncrement = random.nextBoolean()

            dut.io.resetLfsr(lane).poke(doReset.B)
            dut.io.increment(lane).poke(doIncrement.B)

            (doReset, doIncrement)
          }

          dut.clock.step()

          for (((doReset, doIncrement), ref) <- controls.zip(refs)) {
            if (doReset) {
              ref.reset()
            } else if (doIncrement) {
              ref.advanceState(serializerRatio)
            }
          }

          clearControls(dut)
          expectOutputs(dut, refs, s"random step $step after control update")
        }
      }
    }
  }
}
