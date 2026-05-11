package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator.simulators.vcs
import chisel3.util._
import svsim.CommonCompilationSettings
import svsim.vcs.Backend.CompilationSettings
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import org.scalatest.funspec.AnyFunSpec

class RdiControllerLoopbackHarness(sbParams: SidebandParams = new SidebandParams()) extends Module {
  val io = IO(new Bundle {
    val lpStateReq = Input(RDIStateReq())
    val lpWakeReq = Input(Bool())
    val lpClkAck = Input(Bool())
    val lpStallAck = Input(Bool())

    val ltsmState = Input(LTState())
    val doRdiBringup = Input(Bool())
    val trainingTimeout = Input(Bool())
    val validFramingError = Input(Bool())
    val cfgSidebandActive = Input(Bool())
    val plPhyInRecenter = Input(Bool())
    val clocksUngatedAndStable = Input(Bool())

    val plStateSts = Output(RDIState())
    val plInbandPres = Output(Bool())
    val plClkReq = Output(Bool())
    val plStallReq = Output(Bool())
    val doingRdiBringup = Output(Bool())
  })

  val dut = Module(new RDIController(sbParams))
  val rxQueue = Module(new Queue(UInt(sbParams.sbNodeMsgWidth.W), sbParams.sbLinkAsyncQueueDepth))

  dut.io.rdi.lpStateReq := io.lpStateReq
  dut.io.rdi.lpWakeReq := io.lpWakeReq
  dut.io.rdi.lpClkAck := io.lpClkAck
  dut.io.rdi.lpStallAck := io.lpStallAck

  dut.io.ltsmState := io.ltsmState
  dut.io.doRdiBringup := io.doRdiBringup
  dut.io.trainingTimeout := io.trainingTimeout
  dut.io.validFramingError := io.validFramingError
  dut.io.cfgSidebandActive := io.cfgSidebandActive
  dut.io.plPhyInRecenter := io.plPhyInRecenter
  dut.io.clocksUngatedAndStable := io.clocksUngatedAndStable

  // Loop back sideband tx to rx so requester/responder handshakes can complete.
  rxQueue.io.enq.valid := dut.io.sbLaneIo.tx.valid
  rxQueue.io.enq.bits := dut.io.sbLaneIo.tx.bits.data
  dut.io.sbLaneIo.tx.ready := rxQueue.io.enq.ready

  dut.io.sbLaneIo.rx.valid := rxQueue.io.deq.valid
  dut.io.sbLaneIo.rx.bits.data := rxQueue.io.deq.bits
  rxQueue.io.deq.ready := dut.io.sbLaneIo.rx.ready

  io.plStateSts := dut.io.rdi.plStateSts
  io.plInbandPres := dut.io.rdi.plInbandPres
  io.plClkReq := dut.io.rdi.plClkReq
  io.plStallReq := dut.io.rdi.plStallReq
  io.doingRdiBringup := dut.io.doingRdiBringup
}

class LinkInitReleaseTest extends AnyFunSpec with ChiselSim {
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

  describe("RDI Active release after LINKINIT") {
    it("does not release RDI Active before LINKINIT and releases after LINKINIT begins") {
      simulate(new RdiControllerLoopbackHarness()) { dut =>
        initHarness(dut)
        stepWithClkAck(dut, 6)

        // Visit pre-LINKINIT training states and require no early Active release.
        val preLinkInitStates = Seq(LTState.sRESET, LTState.sSBINIT, LTState.sMBINIT, LTState.sMBTRAIN)
        preLinkInitStates.foreach { st =>
          dut.io.ltsmState.poke(st)
          stepWithClkAck(dut, 8)
          dut.io.plStateSts.expect(RDIState.reset)
        }

        // Satisfy clock-handshake requirements before reset-exit transition.
        preRequestClocks(dut)

        // Enter LINKINIT; controller should force RDI bring-up and complete to Active.
        dut.io.ltsmState.poke(LTState.sLINKINIT)

        var sawBringup = false
        var sawActive = false
        var cycles = 0
        while ((!sawActive || !sawBringup) && cycles < 120) {
          sawBringup ||= dut.io.doingRdiBringup.peekBoolean()
          sawActive ||= dut.io.plStateSts.peek().litValue == RDIState.active.litValue
          stepWithClkAck(dut)
          cycles += 1
        }

        assert(sawBringup, "RDI bring-up did not start after entering LINKINIT")
        assert(sawActive, "RDI state was not released to Active after LINKINIT")
      }
    }
  }
}
