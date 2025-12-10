package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._

import chisel3._
import chisel3.util._

// Currently using ChiselTest because of ChiselSim issues in Chisel 6
// Ideally want to move to ChiselSim when switched to Chisel 7
import chiseltest._
import firrtl2.options.TargetDirAnnotation

// ChiselSim for Chisel 6
// import chisel3.simulator.EphemeralSimulator._    

// ChiselSim, for Chisel 7.0+
// import chisel3.simulator.scalatest.ChiselSim     
// import chisel3.simulator.stimulus.{RunUntilFinished, RunUntilSuccess, ResetProcedure}

import org.scalatest.funspec.AnyFunSpec


class SBInitSMTest extends AnyFunSpec with ChiselScalatestTester{  

  describe("SBInitSM Instantiation Test") {
    it("SBInitSM Instantiated") {
      test(new SBInitSM(new SidebandParams(), 8000000)) 
      { c =>
        c.clock.step()
        println("[TEST] Success")
      }
    }
  }   


  class SBInitSMTestHarnessLoopback(sbParams: SidebandParams) extends Module {
    val io = IO(new Bundle {
      val fsmCtrl = new SubFsmControlIO()
      val rxTxMode = Output(SBRxTxMode())
    })

    // add tx arbitration
    class TXArbiter(sbParams: SidebandParams) extends Module {
      val io = IO(new Bundle {
        val txOut = Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth))
        val reqTx = Flipped(Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth)))
        val respTx = Flipped(Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth)))
      })
      io.txOut.bits.data := 0.U
      io.txOut.valid := false.B

      io.reqTx.ready := false.B
      io.respTx.ready := false.B

      // give priority to the requester
      // possibly switch to round robin?
      when(io.reqTx.valid) {
        io.txOut <> io.reqTx     
      }.elsewhen(!io.reqTx.valid && io.respTx.valid) {
        io.txOut <> io.respTx
      }
    }

    val txArb = Module(new TXArbiter(sbParams))

    // queue to mimic some internal queuing (async fifo at sb serdes) on rx 
    // used to see if queue will fill and we need to flush or not
    // tx queue doesn't matter since no backpressure from partner
    // rx queue has backpressure from our digital
    val rxQueue = Module(new Queue(Bits(sbParams.sbNodeMsgWidth.W), 
                                   sbParams.sbLinkAsyncQueueDepth))

    // dut
    val dut = Module(new SBInitSM(sbParams, 8000000))

    val sbLaneIo = Wire(new SidebandLaneIO(sbParams))

    // connections
    // lanes

    sbLaneIo.rx.valid := sbLaneIo.tx.valid
    sbLaneIo.tx.ready := sbLaneIo.rx.ready
    sbLaneIo.rx.bits.data := sbLaneIo.tx.bits.data


    sbLaneIo.tx.valid := txArb.io.txOut.valid
    sbLaneIo.tx.bits.data := txArb.io.txOut.bits.data
    txArb.io.txOut.ready := sbLaneIo.tx.ready

    txArb.io.reqTx <> dut.io.requesterSbLaneIo.tx
    txArb.io.respTx <> dut.io.responderSbLaneIo.tx

    rxQueue.io.enq.valid := sbLaneIo.rx.valid
    sbLaneIo.rx.ready := rxQueue.io.enq.ready
    rxQueue.io.enq.bits := sbLaneIo.rx.bits.data

    dut.io.requesterSbLaneIo.rx.bits.data := rxQueue.io.deq.bits
    dut.io.requesterSbLaneIo.rx.valid := rxQueue.io.deq.valid

    dut.io.responderSbLaneIo.rx.bits.data := rxQueue.io.deq.bits
    dut.io.responderSbLaneIo.rx.valid := rxQueue.io.deq.valid

    rxQueue.io.deq.ready := dut.io.requesterSbLaneIo.rx.ready ^ dut.io.responderSbLaneIo.rx.ready

    io.fsmCtrl <> dut.io.fsmCtrl
    io.rxTxMode := dut.io.sbRxTxMode
  }

  val sbParams = new SidebandParams()

  describe("SBInitSM Loopback Test") {
    it("SBInitSM loopback configuration works") {
      test(new SBInitSMTestHarnessLoopback(sbParams))
      .withAnnotations(Seq(WriteVcdAnnotation))
      .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/"))) 
      { c =>

        // initialize
        c.io.fsmCtrl.start.poke(false.B)
        c.clock.step()
        c.io.fsmCtrl.start.poke(true.B)
        while(c.io.fsmCtrl.done.peek().litToBoolean == false) {
          c.clock.step()
        }
        println("[TEST] Success")
      }
    }
  }  


  class SBInitSMTestHarness(sbParams: SidebandParams) extends Module {
    val io = IO(new Bundle {
      val fsmCtrl = new SubFsmControlIO()
      val rxTxMode = Output(SBRxTxMode())
      val sbLaneIo = new SidebandLaneIO(sbParams)
    })

    // add tx arbitration
    class TXArbiter(sbParams: SidebandParams) extends Module {
      val io = IO(new Bundle {
        val txOut = Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth))
        val reqTx = Flipped(Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth)))
        val respTx = Flipped(Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth)))
      })
      io.txOut.bits.data := 0.U
      io.txOut.valid := false.B

      io.reqTx.ready := false.B
      io.respTx.ready := false.B

      // give priority to the requester
      // possibly switch to round robin?
      when(io.reqTx.valid) {
        io.txOut <> io.reqTx     
      }.elsewhen(!io.reqTx.valid && io.respTx.valid) {
        io.txOut <> io.respTx
      }
    }

    val txArb = Module(new TXArbiter(sbParams))

    // queue to mimic some internal queuing (async fifo at sb serdes) on rx 
    // used to see if queue will fill and we need to flush or not
    // tx queue doesn't matter since no backpressure from partner
    // rx queue has backpressure from our digital
    val rxQueue = Module(new Queue(Bits(sbParams.sbNodeMsgWidth.W), 
                                   sbParams.sbLinkAsyncQueueDepth))

    // dut
    val dut = Module(new SBInitSM(sbParams, 8000000))

    // connections
    // lanes
    io.sbLaneIo.tx <> txArb.io.txOut
    txArb.io.reqTx <> dut.io.requesterSbLaneIo.tx
    txArb.io.respTx <> dut.io.responderSbLaneIo.tx

    rxQueue.io.enq.valid := io.sbLaneIo.rx.valid
    io.sbLaneIo.rx.ready := rxQueue.io.enq.ready
    rxQueue.io.enq.bits := io.sbLaneIo.rx.bits.data

    dut.io.requesterSbLaneIo.rx.bits.data := rxQueue.io.deq.bits
    dut.io.requesterSbLaneIo.rx.valid := rxQueue.io.deq.valid

    dut.io.responderSbLaneIo.rx.bits.data := rxQueue.io.deq.bits
    dut.io.responderSbLaneIo.rx.valid := rxQueue.io.deq.valid

    rxQueue.io.deq.ready := dut.io.requesterSbLaneIo.rx.ready ^ dut.io.responderSbLaneIo.rx.ready

    io.fsmCtrl <> dut.io.fsmCtrl
    io.rxTxMode := dut.io.sbRxTxMode
  }


  // Verification plan:
  // 1. Top level loopback, see if it works [DONE]

  // TODO:
  // 2. Top level, do a test where partner (software version) sends starts SBINIT, and we start later 
  // 3. Done for the requester and responder only goes high when we received done req and resp

  // 1. Verify responder and requester individually and their signals (maybe?)
    // 2. Verify the requester transistions after 4 detections
    // 3. Verify that responder only starts after out of reset is done
    // 4. Verify that out of reset is continuously sent
}