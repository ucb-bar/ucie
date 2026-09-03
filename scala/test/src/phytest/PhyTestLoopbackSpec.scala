package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import edu.berkeley.cs.uciedigital.phy.macros.SbDriver
import edu.berkeley.cs.uciedigital.phy.macros.TxLaneCtlIO
import chisel3.util._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy

/** Runs the tester's loopback pair -- a TX lane wired straight into an RX lane
  * -- off a clock derived from the main one, the way
  * [[PhyTestDebugLaneHarness]] does for the TX data debug lane.
  */
class PhyTestLoopbackHarness(numLanes: Int = 2, bufferDepthPerLane: Int = 10)
    extends Module {
  val io = IO(new Bundle {
    // Active low hold on the lane serdes, driven onto `txRst`/`rxRst`.
    val serdesRstb = Input(Bool())
    val fsmRst = Input(Bool())
    val execute = Input(Bool())
    val repeatPeriod = Input(UInt((bufferDepthPerLane - 5 + 1).W))
    val writeChunk = Input(Bool())
    val chunkIn = Input(UInt(128.W))
    val txDataLaneGroup = Input(UInt(log2Ceil((numLanes + 2) / 4 + 1).W))
    val txDataOffset = Input(UInt((bufferDepthPerLane - 5).W))
    val rxDataLane = Input(UInt(log2Ceil(numLanes + 3).W))
    val rxDataOffset = Input(UInt((bufferDepthPerLane - 5).W))
    val rxDataChunk = Output(UInt(32.W))
    // The loopback pair's backup handoff phase, one bit per direction.
    val txSampleNegedge = Input(Bool())
    val rxSampleNegedge = Input(Bool())
    val rxPacketsReceived = Output(UInt(64.W))
    val txPacketsSent = Output(UInt(64.W))
    val state = Output(TxTestState())
  })

  val dut = Module(new PhyTest(bufferDepthPerLane, numLanes)(true))
  dut.io.regs := DontCare

  val laneClk = RegInit(false.B)
  laneClk := !laneClk
  dut.io.debug.txClk := laneClk.asClock
  dut.io.debug.rxClk := false.B.asClock
  // The tile no longer brings out its own divided clock, so the harness
  // supplies the one the tester hands words over on. Like the PHY's global TX
  // divider it is held by the same reset as the lane serdes, so the two come up
  // in a fixed relative phase. The tile takes a word every 16 `txClk` periods,
  // and `laneClk` is half the main clock, so that is every 16 main cycles.
  val laneDivClk = withReset(!io.serdesRstb) {
    val ctr = RegInit(0.U(4.W))
    val div = RegInit(false.B)
    ctr := ctr + 1.U
    when(ctr === 15.U) { div := !div }
    div
  }
  dut.io.debug.txDivClk := laneDivClk.asClock
  dut.io.debug.sbTxClk := false.B.asClock
  dut.io.debug.rxData := DontCare

  // The mainband FSMs have to be live for the loopback target to run; the
  // sideband tester stays in reset.
  dut.io.regs.mainbandMode := BandMode.manual
  dut.io.regs.sidebandMode := BandMode.tl
  dut.io.regs.testTarget := TestTarget.loopback
  dut.io.regs.txValidLaneSel := Phy.defaultValidLaneSel(numLanes).U
  dut.io.regs.rxValidLaneSel := Phy.defaultValidLaneSel(numLanes).U
  dut.io.regs.txTestMode := TxTestMode.manual
  dut.io.regs.txDataMode := DataMode.infinite
  dut.io.regs.rxDataMode := DataMode.infinite
  dut.io.regs.txManualRepeatPeriod := io.repeatPeriod
  dut.io.regs.txPacketsToSend := 0.U
  dut.io.regs.rxPacketsToReceive := 0.U
  dut.io.regs.txRst := io.fsmRst || !io.serdesRstb
  dut.io.regs.rxRst := io.fsmRst || !io.serdesRstb
  dut.io.regs.txExecute := io.execute
  dut.io.regs.rxPauseCounters := false.B
  dut.io.regs.txDataChunkIn.valid := io.writeChunk
  dut.io.regs.txDataChunkIn.bits := io.chunkIn
  dut.io.regs.txDataLaneGroup := io.txDataLaneGroup
  dut.io.regs.txDataOffset := io.txDataOffset
  dut.io.regs.rxDataLane := io.rxDataLane
  dut.io.regs.rxDataOffset := io.rxDataOffset
  dut.io.regs.txDebugFsmRst := true.B
  dut.io.regs.txDebugExecute := false.B
  dut.io.regs.sb.txSend := false.B
  dut.io.regs.sb.rxPop := false.B
  dut.io.regs.sb.rxRst := false.B

  // Tree-cancelling shufflers and an enabled driver on the loopback
  // transmitter, so a word comes back in the order it went out.
  dut.io.regs.loopbackTxctl.tile := TxLaneCtlIO.full
  for (i <- 0 until Phy.SerdesRatio) {
    // Both tiles go through a tree, the RX one the mirror image of the TX one.
    // Undoing it at each end puts the word on the wire in plain bit order and
    // reads it back unchanged. This is what the register file's reset values
    // do too.
    dut.io.regs.loopbackTxctl.shuffler(i) := Phy.treeBitOrder(i).U
    dut.io.regs.loopbackRxctl.shuffler(i) := Phy.treeBitOrder(i).U
  }
  dut.io.regs.loopbackTxctl.sample_negedge := io.txSampleNegedge
  dut.io.regs.loopbackRxctl.sample_negedge := io.rxSampleNegedge
  dut.io.regs.loopbackRxctl.afeBypassEn := false.B
  dut.io.regs.loopbackRxctl.afeOpCycles := 16.U
  dut.io.regs.loopbackRxctl.afeOverlapCycles := 2.U
  // The debug lane stays off so it cannot disturb the loopback pair.
  dut.io.regs.txctl.tile := TxLaneCtlIO.off

  dut.io.tx.ready := true.B
  dut.io.rx.valid := false.B
  dut.io.rx.bits := DontCare
  // Loop back through real bump drivers, so the 2:1 is exercised too.
  dut.io.sb.rxClk := SbDriver
    .bump(
      dut.io.sb.txClk.clk,
      dut.io.sb.txClk.d0,
      dut.io.sb.txClk.d1,
      includeDefaultModels = true
    )
    .asClock
  dut.io.sb.rxData :=
    SbDriver.bump(
      dut.io.sb.txData.clk,
      dut.io.sb.txData.d0,
      dut.io.sb.txData.d1,
      includeDefaultModels = true
    )

  io.rxDataChunk := dut.io.regs.rxDataChunk
  io.rxPacketsReceived := dut.io.regs.rxPacketsReceived
  io.txPacketsSent := dut.io.regs.txPacketsSent
  io.state := dut.io.regs.txTestState
}

class PhyTestLoopbackSpec extends AnyFunSpec with ChiselSim {
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  val numLanes = 2
  val loopbackLane = numLanes + 2
  val divCycle = 2 * 16

  // Distinct words so that a captured run can only line up with the pattern one
  // way. Bit 0 of the first word is set, so the RX -- which starts capturing at
  // the first one it sees, and sees zeros until the pattern starts -- lands
  // exactly on a word boundary rather than part way into one.
  val words = Seq(
    BigInt("cafe0001", 16),
    BigInt("deadbeef", 16),
    BigInt("12345679", 16),
    BigInt("a5a5a5a5", 16)
  )

  describe("PhyTest loopback lane") {
    // The pattern comes back whichever handoff phase each end is on: an end on
    // the backup phase delays its word by a divided cycle, and the RX starts
    // capturing on the first word it sees that is not zero either way.
    def checkLoopback(txNeg: Boolean, rxNeg: Boolean): Unit =
      simulate(new PhyTestLoopbackHarness(numLanes)) { c =>
        c.io.txSampleNegedge.poke(txNeg.B)
        c.io.rxSampleNegedge.poke(rxNeg.B)
        c.io.serdesRstb.poke(false.B)
        c.io.fsmRst.poke(false.B)
        c.io.execute.poke(false.B)
        c.io.writeChunk.poke(false.B)
        c.io.chunkIn.poke(0.U)
        c.io.txDataLaneGroup.poke(0.U)
        c.io.txDataOffset.poke(0.U)
        c.io.rxDataLane.poke(0.U)
        c.io.rxDataOffset.poke(0.U)
        c.io.repeatPeriod.poke(words.length.U)
        c.clock.step(4)
        c.io.serdesRstb.poke(true.B)
        c.clock.step(4)

        // The loopback lane sits in the last group, at the lane's own slot
        // within it.
        c.io.txDataLaneGroup.poke((loopbackLane >> 2).U)
        for ((word, offset) <- words.zipWithIndex) {
          c.io.txDataOffset.poke(offset.U)
          c.io.chunkIn.poke((word << (32 * (loopbackLane % 4))).U)
          c.io.writeChunk.poke(true.B)
          c.clock.step()
          c.io.writeChunk.poke(false.B)
          c.clock.step()
        }

        c.io.fsmRst.poke(true.B)
        c.clock.step()
        c.io.fsmRst.poke(false.B)
        c.clock.step(4)

        c.io.execute.poke(true.B)
        c.clock.step()
        c.io.execute.poke(false.B)

        // Both async queues have to carry their far sides out of reset before
        // anything moves, and each lane word takes a full divided cycle.
        c.clock.step(64 * divCycle)

        assert(
          c.io.state.peek() == TxTestState.run,
          "an infinite run should still be going"
        )
        val sent = c.io.txPacketsSent.peek().litValue
        assert(
          sent >= words.length,
          s"the TX FSM should have enqueued onto the loopback lane, got $sent"
        )
        val received = c.io.rxPacketsReceived.peek().litValue
        assert(
          received >= words.length,
          s"loopback RX should have captured whole packets, got $received"
        )

        // Read the capture SRAM back on the loopback lane.
        c.io.rxDataLane.poke(loopbackLane.U)
        val captured = (0 until words.length).map { offset =>
          c.io.rxDataOffset.poke(offset.U)
          c.clock.step(2)
          c.io.rxDataChunk.peek().litValue
        }

        // Bit 0 of the first word is set, so capture starts on that word and
        // the readback has to match the pattern offset for offset.
        assert(
          captured == words,
          s"captured ${captured.map(_.toString(16))} does not match " +
            s"${words.map(_.toString(16))}"
        )
      }

    it("should carry the manual pattern from the TX lane to the RX lane") {
      checkLoopback(txNeg = false, rxNeg = false)
    }

    it("should carry it with the TX lane on the backup handoff phase") {
      checkLoopback(txNeg = true, rxNeg = false)
    }

    it("should carry it with the RX lane on the backup handoff phase") {
      checkLoopback(txNeg = false, rxNeg = true)
    }

    it("should carry it with both ends on the backup handoff phase") {
      checkLoopback(txNeg = true, rxNeg = true)
    }
  }
}
