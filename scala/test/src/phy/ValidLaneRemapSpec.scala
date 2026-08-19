package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

// Loops the remapped TX lanes back onto the RX lanes, so a round trip through
// `Phy.txValidRemap` and `Phy.rxValidRemap` must reproduce the original `TxIO`
// no matter which lane is carrying valid.
class ValidLaneRemapHarness(numLanes: Int = 16) extends Module {
  val io = IO(new Bundle {
    val txSel = Input(UInt(Phy.validLaneSelWidth(numLanes).W))
    val rxSel = Input(UInt(Phy.validLaneSelWidth(numLanes).W))
    val tx = Input(new TxIO(numLanes))
    val rx = Output(new RxIO(numLanes))
  })

  val txLanes = Phy.txValidRemap(io.tx, io.txSel, numLanes)
  // The bumps carry data and valid straight across; RX has no clock lanes, so
  // its track lane pairs with TX lane `numLanes + 3`.
  val rxLanes = Wire(Vec(numLanes + 2, Bits(Phy.SerdesRatio.W)))
  for (lane <- 0 until numLanes + 1) {
    rxLanes(lane) := txLanes(lane)
  }
  rxLanes(numLanes + 1) := txLanes(numLanes + 3)
  io.rx := Phy.rxValidRemap(rxLanes, io.rxSel, numLanes)
}

class ValidLaneRemapSpec extends AnyFunSpec with ChiselSim {
  val numLanes = 16
  // Distinct per lane so a misrouted lane cannot pass by coincidence.
  def dataPattern(lane: Int): BigInt = BigInt("a0000000", 16) + lane
  val validPattern = BigInt("0000ffff", 16)
  val trackPattern = BigInt("55555555", 16)
  val clkPPattern = BigInt("55555555", 16)
  val clkNPattern = BigInt("aaaaaaaa", 16)

  // Drives one payload through the harness with both selects set to `sel` and
  // checks that everything comes back off the far side unchanged.
  def checkRoundTrip(c: ValidLaneRemapHarness, sel: Int): Unit = {
    c.io.txSel.poke(sel.U)
    c.io.rxSel.poke(sel.U)
    for (lane <- 0 until numLanes) {
      c.io.tx.data(lane).poke(dataPattern(lane).U)
    }
    c.io.tx.valid.poke(validPattern.U)
    c.io.tx.track.poke(trackPattern.U)
    c.io.tx.clkp.poke(clkPPattern.U)
    c.io.tx.clkn.poke(clkNPattern.U)
    c.clock.step()

    withClue(s"valid lane select $sel: ") {
      c.io.rx.valid.expect(validPattern.U)
      c.io.rx.track.expect(trackPattern.U)
      for (lane <- 0 until numLanes) {
        withClue(s"data lane $lane: ") {
          c.io.rx.data(lane).expect(dataPattern(lane).U)
        }
      }
    }
  }

  describe("valid lane remap") {
    it("should round trip every payload on every selectable lane") {
      simulate(new ValidLaneRemapHarness(numLanes)) { c =>
        for (sel <- 0 until Phy.validLaneSelCount(numLanes)) {
          checkRoundTrip(c, sel)
        }
      }
    }

    it("should fall back to the dedicated valid lane on out of range selects") {
      simulate(new ValidLaneRemapHarness(numLanes)) { c =>
        val outOfRange = (1 << Phy.validLaneSelWidth(numLanes)) - 1
        assert(outOfRange > Phy.trackValidLaneSel(numLanes))
        checkRoundTrip(c, outOfRange)
      }
    }
  }
}
