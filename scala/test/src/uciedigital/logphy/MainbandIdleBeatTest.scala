package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import org.scalatest.funspec.AnyFunSpec

// The forwarded clock only reaches the partner while beats keep flowing to the
// PHY, so the mainband TX path hands the PHY a beat every cycle and marks the
// idle ones by holding the valid lane at zero. These check both ends of that:
// TX keeps clocking while idle, and RX treats a zero valid lane as idle rather
// than as data or as a framing error.
class MainbandIdleBeatTest extends AnyFunSpec with ChiselSim {
  val afe = AfeParams()
  val rdi = RdiParams(64, 32)
  val validFrame = BigInt("0F0F0F0F", 16)

  describe("MainbandLaneController RX") {
    it("ignores clock-only idle beats and flags bogus framing") {
      simulate(new MainbandLaneController(afe, rdi)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.io.ctrl.localTxFunctionalLanes.poke("b011".U)
        dut.io.ctrl.localRxFunctionalLanes.poke("b011".U)
        dut.io.rdi.tx.lpIrdy.poke(false.B)
        dut.io.rdi.tx.lpValid.poke(false.B)
        dut.io.mbLanes.tx.ready.poke(true.B)

        // Idle beat: clock only, valid lane at zero.
        dut.io.mbLanes.rx.valid.poke(true.B)
        dut.io.mbLanes.rx.bits.valid.poke(0.U)
        dut.io.mbLanes.rx.bits.clkP.poke("h55555555".U)
        dut.io.mbLanes.rx.bits.clkN.poke("haaaaaaaa".U)
        for (lane <- 0 until afe.mbLanes) {
          dut.io.mbLanes.rx.bits.data(lane).poke("hdeadbeef".U)
        }
        for (_ <- 0 until 4) {
          dut.io.rdi.rx.plValid.expect(false.B)
          dut.io.ctrl.validFramingError.expect(false.B)
          dut.clock.step(1)
        }

        // Data beat: lane 0 carries bytes 0/16/32/48 of the RDI word.
        dut.io.mbLanes.rx.bits.valid.poke(validFrame.U)
        for (lane <- 0 until afe.mbLanes) {
          dut.io.mbLanes.rx.bits.data(lane).poke(0.U)
        }
        dut.io.mbLanes.rx.bits.data(0).poke("h03020100".U)
        dut.io.rdi.rx.plValid.expect(true.B)
        dut.io.ctrl.validFramingError.expect(false.B)
        val expected = (BigInt(1) << (16 * 8)) | (BigInt(2) << (32 * 8)) |
          (BigInt(3) << (48 * 8))
        dut.io.rdi.rx.plData.expect(expected.U)
        dut.clock.step(1)

        // Back to idle: no new word, still no error.
        dut.io.mbLanes.rx.bits.valid.poke(0.U)
        dut.io.rdi.rx.plValid.expect(false.B)
        dut.io.ctrl.validFramingError.expect(false.B)
        dut.clock.step(1)

        // Bogus valid lane is still a framing error.
        dut.io.mbLanes.rx.bits.valid.poke("hffffffff".U)
        dut.io.ctrl.validFramingError.expect(true.B)
      }
    }
  }

  describe("LogicalPhy TX") {
    it("keeps handing the PHY clocked beats while idle") {
      simulate(new LogicalPhy(afeParams = afe, rdiParams = rdi)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(4)
        dut.reset.poke(false.B)
        dut.io.analog.mainband.tx.ready.poke(true.B)
        dut.io.analog.mainband.rx.valid.poke(false.B)
        for (_ <- 0 until 8) {
          dut.io.analog.mainband.tx.valid.expect(true.B)
          dut.io.analog.mainband.tx.bits.clkP.expect("h55555555".U)
          dut.io.analog.mainband.tx.bits.clkN.expect("haaaaaaaa".U)
          dut.io.analog.mainband.tx.bits.valid.expect(0.U)
          dut.clock.step(1)
        }
      }
    }
  }
}
