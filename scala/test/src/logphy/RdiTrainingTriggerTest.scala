package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator.simulators.vcs
import svsim.CommonCompilationSettings
import svsim.vcs.Backend.CompilationSettings
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import org.scalatest.funspec.AnyFunSpec

class RdiTrainingTriggerTest extends AnyFunSpec with ChiselSim {
  implicit private val simBackend: chisel3.simulator.HasSimulator =
    vcs(CommonCompilationSettings.default, CompilationSettings())

  private def initController(dut: RDIController): Unit = {
    dut.io.rdi.lpStateReq.poke(RDIStateReq.nop)
    dut.io.rdi.lpWakeReq.poke(false.B)
    dut.io.rdi.lpClkAck.poke(false.B)
    dut.io.rdi.lpStallAck.poke(false.B)

    dut.io.ltsmState.poke(LTState.sRESET)
    dut.io.doRdiBringup.poke(false.B)
    dut.io.trainingTimeout.poke(false.B)
    dut.io.validFramingError.poke(false.B)
    dut.io.cfgSidebandActive.poke(false.B)
    dut.io.plPhyInRecenter.poke(false.B)
    dut.io.clocksUngatedAndStable.poke(true.B)

    dut.io.sbLaneIo.rx.valid.poke(false.B)
    dut.io.sbLaneIo.rx.bits.data.poke(0.U)
    dut.io.sbLaneIo.tx.ready.poke(true.B)
  }

  // Model the lower-layer clock-ack behavior with a one-cycle delay.
  private def stepWithClkAck(dut: RDIController, cycles: Int = 1): Unit = {
    var i = 0
    while (i < cycles) {
      val req = dut.io.rdi.plClkReq.peekBoolean()
      dut.io.rdi.lpClkAck.poke(req.B)
      dut.clock.step()
      i += 1
    }
  }

  describe("RDI-triggered bring-up start behavior") {
    it("does not start bring-up early, and starts bring-up after NOP-to-ACTIVE trigger") {
      simulate(new RDIController(new SidebandParams())) { dut =>
        initController(dut)
        stepWithClkAck(dut, 4)

        // No early bring-up while request remains NOP in RESET.
        var cycles = 0
        while (cycles < 20) {
          dut.io.doingRdiBringup.expect(false.B)
          dut.io.rdi.plStateSts.expect(RDIState.reset)
          stepWithClkAck(dut)
          cycles += 1
        }

        // Trigger bring-up through RDI request transition NOP -> ACTIVE.
        dut.io.rdi.lpStateReq.poke(RDIStateReq.active)

        cycles = 0
        var sawBringup = false
        while (!sawBringup && cycles < 30) {
          sawBringup = dut.io.doingRdiBringup.peekBoolean()
          stepWithClkAck(dut)
          cycles += 1
        }
        assert(sawBringup, "RDI bring-up did not start after NOP-to-ACTIVE request transition")
      }
    }

    it("can also start bring-up when doRdiBringup is asserted in RESET") {
      simulate(new RDIController(new SidebandParams())) { dut =>
        initController(dut)
        stepWithClkAck(dut, 2)
        dut.io.doRdiBringup.poke(true.B)

        var cycles = 0
        var sawBringup = false
        while (!sawBringup && cycles < 20) {
          sawBringup = dut.io.doingRdiBringup.peekBoolean()
          stepWithClkAck(dut)
          cycles += 1
        }
        assert(sawBringup, "RDI bring-up did not start when doRdiBringup was asserted")
      }
    }
  }
}
