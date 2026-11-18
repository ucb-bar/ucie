package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

class UiDelayHarness extends Module {
  val io = IO(new Bundle {
    val word = Input(UInt(Phy.SerdesRatio.W))
    val delay = Input(UInt(Phy.uiDelayWidth.W))
    val out = Output(UInt(Phy.SerdesRatio.W))
  })
  io.out := Phy.delayUi(io.word, io.delay)
}

class UiDelaySpec extends AnyFunSpec with ChiselSim {
  val n = Phy.SerdesRatio

  describe("Phy.delayUi") {
    it("should hold every UI of a stream back by the delay, across words") {
      val rng = new scala.util.Random(7)
      val words = Seq.fill(64)(BigInt(n, rng))
      // The stream bit by bit, bit 0 of each word first, after the zeros the
      // history resets to.
      val stream = Seq.fill(2 * n)(0) ++ words.flatMap(w =>
        (0 until n).map(b => ((w >> b) & 1).toInt)
      )
      // Past the top, the top.
      val delays = (0 to Phy.MaxUiDelay) ++ Seq(
        Phy.MaxUiDelay + 1,
        (1 << Phy.uiDelayWidth) - 1
      )
      simulate(new UiDelayHarness) { c =>
        for (delay <- delays) {
          val held = delay.min(Phy.MaxUiDelay)
          c.reset.poke(true.B)
          c.clock.step()
          c.reset.poke(false.B)
          c.io.delay.poke(delay.U)
          for ((w, i) <- words.zipWithIndex) {
            c.io.word.poke(w.U)
            val expected = (0 until n)
              .map(b => BigInt(stream(2 * n + i * n + b - held)) << b)
              .sum
            withClue(s"delay $delay, word $i: ") {
              c.io.out.expect(expected.U)
            }
            c.clock.step()
          }
        }
      }
    }
  }
}
