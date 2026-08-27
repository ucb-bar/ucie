package edu.berkeley.cs.uciedigital.phytest

import chisel3._
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
    val divResetb = Input(Bool())
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
  dut.io.debug.txDivClk := false.B.asClock
  dut.io.debug.sbTxClk := false.B.asClock
  dut.io.debug.rxData := DontCare

  // The mainband FSMs have to be live for the loopback target to run; the
  // sideband tester stays in reset.
  dut.io.regs.mainbandMode := BandMode.manual
  dut.io.regs.sidebandMode := BandMode.tl
  dut.io.regs.testTarget := TestTarget.loopback
  dut.io.regs.txValidLaneSel := Phy.defaultValidLaneSel(numLanes).U
  dut.io.regs.rxValidLaneSel := Phy.defaultValidLaneSel(numLanes).U
  dut.io.regs.divResetb := io.divResetb.asAsyncReset
  dut.io.regs.txTestMode := TxTestMode.manual
  dut.io.regs.txDataMode := DataMode.infinite
  dut.io.regs.rxDataMode := DataMode.infinite
  dut.io.regs.txManualRepeatPeriod := io.repeatPeriod
  dut.io.regs.txPacketsToSend := 0.U
  dut.io.regs.rxPacketsToReceive := 0.U
  dut.io.regs.txRst := io.fsmRst
  dut.io.regs.rxRst := io.fsmRst
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

  // Identity shufflers and released DLLs on both loopback lanes, so a word
  // comes back in the order it went out.
  dut.io.regs.loopbackTxctl.dll_reset := false.B
  for (i <- 0 until Phy.SerdesRatio) {
    // The TX tile serializes through a tree; undoing that here puts the word on
    // the wire in plain bit order, so the sequential RX tile reads it back
    // unchanged. This is what the register file's reset value does too.
    dut.io.regs.loopbackTxctl.shuffler(i) := Phy.treeBitOrder(i).U
    dut.io.regs.loopbackRxctl.shuffler(i) := i.U
  }
  dut.io.regs.loopbackRxctl.afeBypassEn := false.B
  dut.io.regs.loopbackRxctl.afeOpCycles := 16.U
  dut.io.regs.loopbackRxctl.afeOverlapCycles := 2.U
  dut.io.regs.txctl.dll_reset := true.B

  dut.io.tx.ready := true.B
  dut.io.rx.valid := false.B
  dut.io.rx.bits := DontCare
  dut.io.sb.rxClk := dut.io.sb.txClk
  dut.io.sb.rxData := dut.io.sb.txData

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
    it("should carry the manual pattern from the TX lane to the RX lane") {
      simulate(new PhyTestLoopbackHarness(numLanes)) { c =>
        c.io.divResetb.poke(false.B)
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
        c.io.divResetb.poke(true.B)
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
    }
  }
}
