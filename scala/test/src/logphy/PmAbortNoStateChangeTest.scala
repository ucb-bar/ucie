package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator.simulators.vcs
import svsim.CommonCompilationSettings
import svsim.vcs.Backend.CompilationSettings
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import org.scalatest.funspec.AnyFunSpec

class PmAbortNoStateChangeTest extends AnyFunSpec with ChiselSim {
  implicit private val simBackend: chisel3.simulator.HasSimulator =
    vcs(CommonCompilationSettings.default, CompilationSettings())

  private val OpcodeMsgWithoutData = 0x12
  private val MsgCodeRdiReq = 0x01
  private val SubcodeL1 = 0x04
  private val SubcodeL2 = 0x08

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

  private def sbRdiReqL1(): BigInt = sbMsg(OpcodeMsgWithoutData, MsgCodeRdiReq, SubcodeL1)
  private def sbRdiReqL2(): BigInt = sbMsg(OpcodeMsgWithoutData, MsgCodeRdiReq, SubcodeL2)

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

  private def injectRemotePmReqAndCheckNoPmEntry(dut: RdiControllerLoopbackHarness, req: BigInt, label: String): Unit = {
    dut.io.rxInjectBits.poke(req.U)
    dut.io.rxInjectValid.poke(true.B)

    var cycles = 0
    while (!dut.io.rxInjectReady.peekBoolean() && cycles < 40) {
      stepWithClkAck(dut)
      cycles += 1
    }
    assert(dut.io.rxInjectReady.peekBoolean(), s"$label request was not accepted by RDI sideband lane")
    stepWithClkAck(dut, 1)
    dut.io.rxInjectValid.poke(false.B)
    dut.io.rxInjectBits.poke(0.U)

    var sawPmAbortState = false
    cycles = 0
    while (cycles < 120) {
      val st = dut.io.plStateSts.peek().litValue
      assert(st != RDIState.l1.litValue, s"$label caused illegal transition to RDI L1")
      assert(st != RDIState.l2.litValue, s"$label caused illegal transition to RDI L2")
      if (st == RDIState.activePmNak.litValue) {
        sawPmAbortState = true
      }
      stepWithClkAck(dut)
      cycles += 1
    }

    assert(sawPmAbortState, s"$label did not exercise PM abort path (activePmNak not observed)")
    waitForState(dut, RDIState.active, 120, s"$label did not return to Active after PM abort")
  }

  describe("Unsupported PM request handling") {
    it("aborts remote L1/L2 requests and never transitions into PM states") {
      simulate(new RdiControllerLoopbackHarness(new SidebandParams())) { dut =>
        bringupToActive(dut)

        injectRemotePmReqAndCheckNoPmEntry(dut, sbRdiReqL1(), "Remote REQ_L1")
        injectRemotePmReqAndCheckNoPmEntry(dut, sbRdiReqL2(), "Remote REQ_L2")
      }
    }
  }
}
