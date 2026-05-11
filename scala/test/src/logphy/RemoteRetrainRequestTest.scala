package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator.simulators.vcs
import svsim.CommonCompilationSettings
import svsim.vcs.Backend.CompilationSettings
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import org.scalatest.funspec.AnyFunSpec

class RemoteRetrainRequestTest extends AnyFunSpec with ChiselSim {
  implicit private val simBackend: chisel3.simulator.HasSimulator =
    vcs(CommonCompilationSettings.default, CompilationSettings())

  private val OpcodeMsgWithoutData = 0x12
  private val MsgCodeRdiReq = 0x01
  private val SubcodeRetrain = 0x0B

  private def sbMsg(
      opcode: Int,
      msgCode: Int,
      msgSubcode: Int,
      msgInfo: Int = 0
  ): BigInt = {
    var header = BigInt(0)
    header |= BigInt(opcode & 0x1f)
    header |= BigInt(msgCode & 0xff) << 14
    header |= BigInt(0x18) << 22 // remote + logPhy layer + reserved
    header |= BigInt(0x2) << 29 // PHY source
    header |= BigInt(msgSubcode & 0xff) << 32
    header |= BigInt(msgInfo & 0xffff) << 40
    header |= BigInt(0x6) << 56 // remote PHY destination
    header
  }

  private def sbRdiReqRetrain(): BigInt =
    sbMsg(OpcodeMsgWithoutData, MsgCodeRdiReq, SubcodeRetrain)

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
    dut.io.ltsmState.poke(LTState.sACTIVE)
    stepWithClkAck(dut, 4)
  }

  describe("Remote retrain request propagation") {
    it("propagates remote retrain request to local retrain without premature return to active") {
      simulate(new RdiControllerLoopbackHarness(new SidebandParams())) { dut =>
        bringupToActive(dut)

        val retrainReq = sbRdiReqRetrain()
        dut.io.rxInjectBits.poke(retrainReq.U)
        dut.io.rxInjectValid.poke(true.B)

        var cycles = 0
        while (!dut.io.rxInjectReady.peekBoolean() && cycles < 40) {
          stepWithClkAck(dut)
          cycles += 1
        }
        assert(dut.io.rxInjectReady.peekBoolean(), "Remote retrain request was not accepted by RDI sideband lane")
        stepWithClkAck(dut, 1)
        dut.io.rxInjectValid.poke(false.B)
        dut.io.rxInjectBits.poke(0.U)

        waitForState(
          dut,
          RDIState.retrain,
          120,
          "Remote retrain request did not propagate to local RDI Retrain state"
        )

        var remainedNonActive = true
        cycles = 0
        while (cycles < 60) {
          if (dut.io.plStateSts.peek().litValue == RDIState.active.litValue) {
            remainedNonActive = false
          }
          stepWithClkAck(dut)
          cycles += 1
        }
        assert(remainedNonActive, "RDI returned to Active prematurely after remote retrain request")
      }
    }
  }
}
