package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator.simulators.vcs
import svsim.CommonCompilationSettings
import svsim.vcs.Backend.CompilationSettings
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import org.scalatest.funspec.AnyFunSpec

class TrainingFailureEscalationTest extends AnyFunSpec with ChiselSim {
  implicit private val simBackend: chisel3.simulator.HasSimulator =
    vcs(CommonCompilationSettings.default, CompilationSettings())

  private def initHarness(dut: RdiControllerLoopbackHarness): Unit = {
    dut.io.lpStateReq.poke(RDIStateReq.nop)
    dut.io.lpWakeReq.poke(false.B)
    dut.io.lpClkAck.poke(false.B)
    dut.io.lpStallAck.poke(false.B)

    dut.io.ltsmState.poke(LTState.sRESET)
    dut.io.doRdiBringup.poke(false.B)
    dut.io.trainingTimeout.poke(false.B)
    dut.io.validFramingError.poke(false.B)
    dut.io.cfgSidebandActive.poke(false.B)
    dut.io.plPhyInRecenter.poke(false.B)
    dut.io.clocksUngatedAndStable.poke(true.B)
    dut.io.rxInjectValid.poke(false.B)
    dut.io.rxInjectBits.poke(0.U)
  }

  private def stepWithClkAck(dut: RdiControllerLoopbackHarness, cycles: Int = 1): Unit = {
    var i = 0
    while (i < cycles) {
      val req = dut.io.plClkReq.peekBoolean()
      dut.io.lpClkAck.poke(req.B)
      dut.clock.step()
      i += 1
    }
  }

  private def preRequestClocks(dut: RdiControllerLoopbackHarness): Unit = {
    dut.io.plPhyInRecenter.poke(true.B)
    dut.io.lpClkAck.poke(true.B)
    var cycles = 0
    while (!dut.io.plClkReq.peekBoolean() && cycles < 40) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.plClkReq.peekBoolean(), "plClkReq did not assert during pre-request clock handshake")
  }

  private def waitForState(dut: RdiControllerLoopbackHarness, state: RDIState.Type, maxCycles: Int, err: String): Unit = {
    var cycles = 0
    var reached = false
    while (!reached && cycles < maxCycles) {
      reached = dut.io.plStateSts.peek().litValue == state.litValue
      stepWithClkAck(dut)
      cycles += 1
    }
    assert(reached, err)
  }

  private def bringupToActive(dut: RdiControllerLoopbackHarness): Unit = {
    initHarness(dut)
    stepWithClkAck(dut, 6)
    preRequestClocks(dut)
    dut.io.ltsmState.poke(LTState.sLINKINIT)
    waitForState(dut, RDIState.active, 140, "RDI did not reach Active during bring-up")
  }

  describe("Training failure escalation to adapter boundary") {
    it("escalates reset-phase training timeout to RDI LinkError instead of hanging") {
      simulate(new RdiControllerLoopbackHarness(new SidebandParams())) { dut =>
        initHarness(dut)
        stepWithClkAck(dut, 6)
        preRequestClocks(dut)

        dut.io.trainingTimeout.poke(true.B)
        waitForState(
          dut,
          RDIState.linkError,
          120,
          "Reset-phase training timeout did not escalate to RDI LinkError"
        )
      }
    }

    it("escalates active-phase training timeout to RDI LinkError instead of hanging") {
      simulate(new RdiControllerLoopbackHarness(new SidebandParams())) { dut =>
        bringupToActive(dut)
        dut.io.trainingTimeout.poke(true.B)
        waitForState(
          dut,
          RDIState.linkError,
          140,
          "Active-phase training timeout did not escalate to RDI LinkError"
        )
      }
    }
  }
}
