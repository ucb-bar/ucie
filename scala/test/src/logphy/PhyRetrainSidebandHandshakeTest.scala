package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._
import org.scalatest.funspec.AnyFunSpec

class PhyRetrainLoopbackHarness(sbParams: SidebandParams = new SidebandParams()) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val requesterEncoding = Input(UInt(3.W))
    val responderEncoding = Input(UInt(3.W))
    val waitForRemoteRequest = Input(Bool())

    val done = Output(Bool())
    val requesterRemoteEncoding = Output(UInt(3.W))
    val requesterRemoteValid = Output(Bool())
    val responderRemoteEncoding = Output(UInt(3.W))
    val responderRemoteValid = Output(Bool())
  })

  val dut = Module(new PhyRetrainSidebandHandshake(sbParams))
  val rxQueue = Module(new Queue(UInt(sbParams.sbNodeMsgWidth.W), sbParams.sbLinkAsyncQueueDepth))

  dut.io.startPhyRetrainMsgExch := io.start
  dut.io.requesterLocalRetrainEncoding.valid := io.start
  dut.io.requesterLocalRetrainEncoding.bits := io.requesterEncoding
  dut.io.waitForRemoteRequest := io.waitForRemoteRequest
  dut.io.responderLocalRetrainEncoding.valid := io.waitForRemoteRequest
  dut.io.responderLocalRetrainEncoding.bits := io.responderEncoding

  val txClients = Seq(dut.io.requesterSbLaneIo.tx, dut.io.responderSbLaneIo.tx)
  val chosen = PriorityEncoderOH(VecInit(txClients.map(_.valid)))
  val anyTxValid = txClients.map(_.valid).reduce(_ || _)

  rxQueue.io.enq.valid := anyTxValid
  rxQueue.io.enq.bits := Mux1H(chosen, txClients.map(_.bits.data))
  txClients.zipWithIndex.foreach { case (client, idx) =>
    client.ready := rxQueue.io.enq.ready && chosen(idx)
  }

  dut.io.requesterSbLaneIo.rx.valid := rxQueue.io.deq.valid
  dut.io.requesterSbLaneIo.rx.bits.data := rxQueue.io.deq.bits
  dut.io.responderSbLaneIo.rx.valid := rxQueue.io.deq.valid
  dut.io.responderSbLaneIo.rx.bits.data := rxQueue.io.deq.bits
  rxQueue.io.deq.ready := dut.io.requesterSbLaneIo.rx.ready || dut.io.responderSbLaneIo.rx.ready

  io.done := dut.io.done
  io.requesterRemoteEncoding := dut.io.requesterRemoteRetrainEncoding.bits
  io.requesterRemoteValid := dut.io.requesterRemoteRetrainEncoding.valid
  io.responderRemoteEncoding := dut.io.responderRemoteRetrainEncoding.bits
  io.responderRemoteValid := dut.io.responderRemoteRetrainEncoding.valid
}

class PhyRetrainSidebandHandshakeTest extends AnyFunSpec with ChiselSim {
  describe("PhyRetrainSidebandHandshake") {
    it("exchanges retrain encodings through requester and responder loopback") {
      simulate(new PhyRetrainLoopbackHarness()) { dut =>
        dut.io.start.poke(false.B)
        dut.io.waitForRemoteRequest.poke(false.B)
        dut.io.requesterEncoding.poke(1.U)
        dut.io.responderEncoding.poke(5.U)
        dut.clock.step(2)

        dut.io.start.poke(true.B)
        dut.io.waitForRemoteRequest.poke(true.B)

        var sawRequesterRemote = false
        var sawResponderRemote = false
        var cycles = 0
        while ((!dut.io.done.peekBoolean() || !sawRequesterRemote || !sawResponderRemote) && cycles < 2000) {
          sawRequesterRemote ||= dut.io.requesterRemoteValid.peekBoolean()
          sawResponderRemote ||= dut.io.responderRemoteValid.peekBoolean()
          dut.clock.step()
          cycles += 1
        }

        dut.io.done.expect(true.B)
        assert(sawRequesterRemote, "Requester never captured remote retrain encoding")
        assert(sawResponderRemote, "Responder never captured remote retrain encoding")
      }
    }
  }
}
