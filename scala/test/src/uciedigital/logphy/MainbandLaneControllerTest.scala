package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import org.scalatest.funspec.AnyFunSpec

/** The Lane controller's beat counters, which spread one RDI word over several
  * beats once a Module runs degraded.
  *
  * At x8 a 64-byte word takes two beats. A Module that leaves ACTIVE part-way
  * through one must not carry the stale beat into the next Active period, or
  * every later word -- behind the MMPL, every later aggregate word -- comes out
  * shifted by it.
  */
class MainbandLaneControllerTest extends AnyFunSpec with ChiselSim {
  private val afe = AfeParams()
  private val rdi = RdiParams(64, 32)
  private val lanes0to7 = "b001".U(3.W)
  private val bytesPerLane = afe.mbSerializerRatio / 8
  private val activeLanes = 8
  private val bytesPerBeat = activeLanes * bytesPerLane

  // Byte i of word `tag`: distinct per word and per byte.
  private def word(tag: Int): BigInt =
    (0 until rdi.nBytes).foldLeft(BigInt(0)) { (acc, i) =>
      acc | (BigInt((tag << 6) | i) << (i * 8))
    }
  private def byteOf(w: BigInt, i: Int): BigInt = (w >> (i * 8)) & 0xff

  // Lane `l`, slot `s` of beat `b` carries byte b*bytesPerBeat + l + s*8.
  private def laneWord(w: BigInt, beat: Int, lane: Int): BigInt =
    (0 until bytesPerLane).foldLeft(BigInt(0)) { (acc, s) =>
      acc | (byteOf(w, beat * bytesPerBeat + lane + s * activeLanes) << (s * 8))
    }

  // Valid framing: 1111_0000 every 8 UI.
  private val validFrame =
    (0 until afe.mbSerializerRatio).foldLeft(BigInt(0)) { (acc, i) =>
      if (i % 8 < 4) acc | (BigInt(1) << i) else acc
    }

  private def init(c: MainbandLaneController): Unit = {
    c.io.ctrl.localTxFunctionalLanes.poke(lanes0to7)
    c.io.ctrl.localRxFunctionalLanes.poke(lanes0to7)
    c.io.ctrl.interpretBy8Lane.poke(false.B)
    c.io.ctrl.clearFramingError.poke(false.B)
    c.io.ctrl.clearBeats.poke(false.B)
    c.io.rdi.tx.lpValid.poke(false.B)
    c.io.rdi.tx.lpIrdy.poke(false.B)
    c.io.rdi.tx.lpData.poke(0.U)
    c.io.mbLanes.tx.ready.poke(true.B)
    c.io.mbLanes.rx.valid.poke(false.B)
  }

  private def presentRx(c: MainbandLaneController, w: BigInt, beat: Int) = {
    c.io.mbLanes.rx.valid.poke(true.B)
    c.io.mbLanes.rx.bits.valid.poke(validFrame.U)
    for (lane <- 0 until afe.mbLanes) {
      val data = if (lane < activeLanes) laneWord(w, beat, lane) else BigInt(0)
      c.io.mbLanes.rx.bits.data(lane).poke(data.U)
    }
  }

  describe("MainbandLaneController beat counters") {
    it("drops a half-sent transmit word when it leaves ACTIVE") {
      simulate(new MainbandLaneController(afe, rdi)) { c =>
        init(c)
        c.io.rdi.tx.lpData.poke(word(1).U)
        c.io.rdi.tx.lpValid.poke(true.B)
        c.io.rdi.tx.lpIrdy.poke(true.B)
        c.clock.step()
        c.io.rdi.tx.lpValid.poke(false.B)
        c.io.rdi.tx.lpIrdy.poke(false.B)
        c.io.ctrl.txIdle.expect(false.B, "beat 1 of word 1 is still to go")

        // The LTSM leaves ACTIVE before beat 1 goes out.
        c.io.mbLanes.tx.ready.poke(false.B)
        c.io.ctrl.clearBeats.poke(true.B)
        c.clock.step()
        c.io.ctrl.clearBeats.poke(false.B)
        c.io.mbLanes.tx.ready.poke(true.B)
        c.io.ctrl.txIdle.expect(true.B)
        c.io.rdi.tx.plTrdy.expect(true.B, "ready for a fresh word")

        c.io.rdi.tx.lpData.poke(word(2).U)
        c.io.rdi.tx.lpValid.poke(true.B)
        c.io.rdi.tx.lpIrdy.poke(true.B)
        c.io.mbLanes.tx.valid.expect(true.B)
        c.io.mbLanes.tx.bits
          .data(0)
          .expect(laneWord(word(2), 0, 0).U, "beat 0 of the new word")
      }
    }

    it("drops a half-received word when it leaves ACTIVE") {
      simulate(new MainbandLaneController(afe, rdi)) { c =>
        init(c)
        presentRx(c, word(1), 0)
        c.io.rdi.rx.plValid.expect(false.B, "half of word 1")
        c.clock.step()
        c.io.mbLanes.rx.valid.poke(false.B)

        c.io.ctrl.clearBeats.poke(true.B)
        c.clock.step()
        c.io.ctrl.clearBeats.poke(false.B)

        presentRx(c, word(2), 0)
        c.io.rdi.rx.plValid.expect(false.B, "half of word 2, not word 1 done")
        c.clock.step()
        presentRx(c, word(2), 1)
        c.io.rdi.rx.plValid.expect(true.B, "word 2 is complete")
        c.io.rdi.rx.plData.expect(word(2).U(512.W))
      }
    }
  }
}
