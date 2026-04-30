package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.d2dadapter._
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import org.scalatest.funspec.AnyFunSpec

class ClockRequesterLoopbackHarness extends Module {
  val io = IO(new Bundle {
    val startHandshake = Input(Bool())
    val releaseReq = Input(Bool())
    val doneHandshake = Output(Bool())
    val inIdle = Output(Bool())
    val plClkReq = Output(Bool())
    val lpClkAck = Output(Bool())
  })

  val requester = Module(new RDIClockHandshakeRequester())
  val lpClkAckReg = RegInit(false.B)

  requester.io.ctrl.startHandshake := io.startHandshake
  requester.io.ctrl.releaseReq := io.releaseReq
  lpClkAckReg := requester.io.rdi.plClkReq
  requester.io.rdi.lpClkAck := lpClkAckReg

  io.doneHandshake := requester.io.ctrl.doneHandshake
  io.inIdle := requester.io.ctrl.inIdle
  io.plClkReq := requester.io.rdi.plClkReq
  io.lpClkAck := lpClkAckReg
}

class RdiHandshakeIntegrationTest extends AnyFunSpec with ChiselSim {
  private def initAdapter(dut: D2DAdapter): Unit = {
    dut.io.fdi.lpIrdy.poke(false.B)
    dut.io.fdi.lpValid.poke(false.B)
    dut.io.fdi.lpData.poke(0.U)
    dut.io.fdi.lpStateReq.poke(FDIStateReq.nop)
    dut.io.fdi.lpLinkError.poke(false.B)
    dut.io.fdi.lpRxActiveSts.poke(false.B)
    dut.io.fdi.lpStallAck.poke(false.B)
    dut.io.fdi.lpClkAck.poke(false.B)
    dut.io.fdi.lpWakeReq.poke(true.B)
    dut.io.fdi.lpCfg.poke(0.U)
    dut.io.fdi.lpCfgVld.poke(false.B)
    dut.io.fdi.plCfgCrd.poke(false.B)

    dut.io.rdi.plTrdy.poke(false.B)
    dut.io.rdi.plValid.poke(false.B)
    dut.io.rdi.plData.poke(0.U)
    dut.io.rdi.plStateSts.poke(RDIState.reset)
    dut.io.rdi.plInbandPres.poke(false.B)
    dut.io.rdi.plError.poke(false.B)
    dut.io.rdi.plCError.poke(false.B)
    dut.io.rdi.plNfError.poke(false.B)
    dut.io.rdi.plTrainError.poke(false.B)
    dut.io.rdi.plPhyInRecenter.poke(false.B)
    dut.io.rdi.plStallReq.poke(false.B)
    dut.io.rdi.plSpeedmode.poke(SpeedMode.speed16)
    dut.io.rdi.plMaxSpeedmode.poke(false.B)
    dut.io.rdi.plLnkCfg.poke(LinkWidth.x16)
    dut.io.rdi.plClkReq.poke(false.B)
    dut.io.rdi.plCfg.poke(0.U)
    dut.io.rdi.plCfgVld.poke(false.B)
    dut.io.rdi.plCfgCrd.poke(false.B)
    dut.io.rdi.plWakeAck.poke(false.B)
  }

  describe("Adapter and logphy no-gating handshakes") {
    it("stages RDI lpClkAck in the adapter instead of tying it high") {
      simulate(new D2DAdapter(FdiParams(64, 32), RdiParams(64, 32), new SidebandParams())) { dut =>
        initAdapter(dut)
        dut.clock.step()
        dut.io.rdi.lpClkAck.expect(false.B)

        dut.io.rdi.plClkReq.poke(true.B)
        dut.clock.step()
        dut.io.rdi.lpClkAck.expect(true.B)

        dut.io.rdi.plClkReq.poke(false.B)
        dut.clock.step(2)
        dut.io.rdi.lpClkAck.expect(false.B)
      }
    }

    it("allows the logphy clock requester to complete both phases against a staged responder") {
      simulate(new ClockRequesterLoopbackHarness()) { dut =>
        dut.io.startHandshake.poke(false.B)
        dut.io.releaseReq.poke(false.B)
        dut.clock.step()

        dut.io.startHandshake.poke(true.B)
        dut.io.plClkReq.expect(false.B)
        dut.io.lpClkAck.expect(false.B)

        while(!dut.io.plClkReq.peekBoolean()) {
          dut.clock.step()
        }
        dut.io.lpClkAck.expect(false.B)

        while(!dut.io.lpClkAck.peekBoolean()) {
          dut.io.plClkReq.expect(true.B)
          dut.clock.step()
        }
        dut.io.plClkReq.expect(true.B)

        dut.io.releaseReq.poke(true.B)
        while(dut.io.plClkReq.peekBoolean()) {
          dut.io.lpClkAck.expect(true.B)
          dut.clock.step()
        }
        dut.io.lpClkAck.expect(true.B)

        while(dut.io.lpClkAck.peekBoolean()) {
          dut.clock.step()
        }
        while(!dut.io.inIdle.peekBoolean()) {
          dut.io.plClkReq.expect(false.B)
          dut.io.lpClkAck.expect(false.B)
          dut.clock.step()
        }
        dut.io.inIdle.expect(true.B)
      }
    }

    it("returns plWakeAck when the wake responder sees always-ready clocks") {
      simulate(new RDIWakeHandshakeResponder()) { dut =>
        dut.io.ctrl.clocksUngatedAndStable.poke(true.B)
        dut.io.rdi.lpWakeReq.poke(false.B)
        dut.clock.step()
        dut.io.rdi.plWakeAck.expect(false.B)

        dut.io.rdi.lpWakeReq.poke(true.B)
        dut.clock.step()
        dut.io.rdi.plWakeAck.expect(false.B)
        dut.clock.step()
        dut.io.rdi.plWakeAck.expect(true.B)

        dut.io.rdi.lpWakeReq.poke(false.B)
        dut.clock.step()
        dut.io.rdi.plWakeAck.expect(true.B)
        dut.clock.step()
        dut.io.rdi.plWakeAck.expect(false.B)
      }
    }
  }
}
