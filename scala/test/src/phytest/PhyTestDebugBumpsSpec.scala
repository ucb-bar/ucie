package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy
import edu.berkeley.cs.uciedigital.phy.macros.clocking.ClkMux

/** Drives the PHY's observation taps and the selects that pick between them,
  * and brings the resulting bumps back out so a test can watch them.
  *
  * The taps are clocks on the PHY side, so they are `Bool` here and converted
  * at the boundary; nothing in this path is clocked, so a level on a tap shows
  * up on its bump combinationally.
  */
class PhyTestDebugBumpsHarness(numLanes: Int = 4) extends Module {
  val io = IO(new Bundle {
    val txClk = Input(Bool())
    val rxClk = Input(Bool())
    val txDivClk = Input(Bool())
    val sbTxClk = Input(Bool())
    val rxData = Input(Vec(Phy.numRxDataLanes(numLanes), UInt(32.W)))
    val clkMuxSel = Input(UInt(ClkMux.selWidth.W))
    val rxDebugLane = Input(UInt(log2Ceil(numLanes + 2).W))
    val rxDebugBit = Input(UInt(log2Ceil(Phy.SerdesRatio).W))
    val bumps = new DebugBumpsIO
  })

  val dut = Module(new PhyTest(numLanes = numLanes)(true))
  dut.io.regs := DontCare

  // Both bands under TileLink, so the mainband and sideband testers stay in
  // reset and only the observation path is live.
  dut.io.regs.mainbandMode := BandMode.tl
  dut.io.regs.sidebandMode := BandMode.tl
  dut.io.regs.testTarget := TestTarget.mainband
  dut.io.regs.divResetb := false.B.asAsyncReset
  dut.io.regs.txRst := false.B
  dut.io.regs.rxRst := false.B
  dut.io.regs.txExecute := false.B
  dut.io.regs.txDataChunkIn.valid := false.B
  dut.io.regs.rxPauseCounters := false.B
  dut.io.regs.txDebugFsmRst := true.B
  dut.io.regs.txDebugExecute := false.B
  dut.io.regs.sb.txSend := false.B
  dut.io.regs.sb.rxPop := false.B
  dut.io.regs.sb.rxRst := false.B
  dut.io.regs.txValidLaneSel := Phy.defaultValidLaneSel(numLanes).U
  dut.io.regs.rxValidLaneSel := Phy.defaultValidLaneSel(numLanes).U

  // The pad drivers are enabled, as bring-up would leave them.
  for (i <- 0 until PhyTest.NumDebugDrivers) {
    dut.io.regs.driverctl(i).pu_ctl := 63.U
    dut.io.regs.driverctl(i).pd_ctl := 63.U
    dut.io.regs.driverctl(i).en := true.B
    dut.io.regs.driverctl(i).en_b := false.B
  }

  dut.io.debug.txClk := io.txClk.asClock
  dut.io.debug.rxClk := io.rxClk.asClock
  dut.io.debug.txDivClk := io.txDivClk.asClock
  dut.io.debug.sbTxClk := io.sbTxClk.asClock
  dut.io.debug.rxData := io.rxData
  dut.io.regs.clkMuxSel := io.clkMuxSel
  dut.io.regs.rxDebugLane := io.rxDebugLane
  dut.io.regs.rxDebugBit := io.rxDebugBit

  dut.io.tx.ready := true.B
  dut.io.rx.valid := false.B
  dut.io.rx.bits := DontCare
  dut.io.sb.rxClk := dut.io.sb.txClk
  dut.io.sb.rxData := dut.io.sb.txData

  io.bumps := dut.io.bumps
}

class PhyTestDebugBumpsSpec extends AnyFunSpec with ChiselSim {
  // The tester instantiates analog macros, whose behavioral models Verilator
  // lints at.
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  val numLanes = 4
  val rxLanes = Phy.numRxDataLanes(numLanes)

  // A distinct word per lane, so that selecting the wrong lane cannot happen to
  // give the right bit.
  def laneWord(lane: Int): BigInt =
    (BigInt("9e3779b9", 16) * (lane + 1)) & ((BigInt(1) << 32) - 1)

  def bitOf(word: BigInt, bit: Int): Int = ((word >> bit) & 1).toInt

  def setup(c: PhyTestDebugBumpsHarness): Unit = {
    for (lane <- 0 until rxLanes) {
      c.io.rxData(lane).poke(laneWord(lane).U)
    }
    c.io.txClk.poke(false.B)
    c.io.rxClk.poke(false.B)
    c.io.txDivClk.poke(false.B)
    c.io.sbTxClk.poke(false.B)
    c.io.clkMuxSel.poke(0.U)
    c.io.rxDebugLane.poke(0.U)
    c.io.rxDebugBit.poke(0.U)
    c.clock.step()
  }

  describe("PhyTest observation bumps") {
    it("should put the selected RX lane and bit on the rxData bump") {
      simulate(new PhyTestDebugBumpsHarness(numLanes)) { c =>
        setup(c)
        // Every lane, and enough bits per lane to catch a stuck or swapped
        // select rather than a single lucky match.
        for (lane <- 0 until rxLanes; bit <- Seq(0, 1, 7, 16, 30, 31)) {
          c.io.rxDebugLane.poke(lane.U)
          c.io.rxDebugBit.poke(bit.U)
          c.clock.step()
          withClue(s"lane $lane bit $bit of ${laneWord(lane).toString(16)}: ") {
            assert(
              c.io.bumps.rxData.peek().litValue.toInt ==
                bitOf(laneWord(lane), bit)
            )
          }
        }
      }
    }

    it("should follow a lane whose word changes under a fixed select") {
      simulate(new PhyTestDebugBumpsHarness(numLanes)) { c =>
        setup(c)
        c.io.rxDebugLane.poke(2.U)
        c.io.rxDebugBit.poke(5.U)
        for (pattern <- Seq(BigInt(0), ~BigInt(0) & ((BigInt(1) << 32) - 1))) {
          c.io.rxData(2).poke(pattern.U)
          c.clock.step()
          assert(
            c.io.bumps.rxData.peek().litValue.toInt == bitOf(pattern, 5),
            s"bump did not follow lane 2 set to ${pattern.toString(16)}"
          )
        }
      }
    }

    it("should put the selected clock on the clkMux bump, inverted") {
      simulate(new PhyTestDebugBumpsHarness(numLanes)) { c =>
        setup(c)
        // Input 0 is the sideband forwarded clock and input 1 the TX global
        // divided clock. The cell shares an output inverter, so the bump is the
        // complement of whichever input is selected -- fine for observation,
        // but worth pinning down.
        for (sb <- Seq(false, true); div <- Seq(false, true)) {
          c.io.sbTxClk.poke(sb.B)
          c.io.txDivClk.poke(div.B)

          c.io.clkMuxSel.poke(0.U)
          c.clock.step()
          withClue(s"sel 0 with sb=$sb div=$div: ") {
            assert(c.io.bumps.clkMux.peek().litToBoolean == !sb)
          }

          c.io.clkMuxSel.poke(1.U)
          c.clock.step()
          withClue(s"sel 1 with sb=$sb div=$div: ") {
            assert(c.io.bumps.clkMux.peek().litToBoolean == !div)
          }
        }
      }
    }

    it("should pass the tapped clocks straight through to their own bumps") {
      simulate(new PhyTestDebugBumpsHarness(numLanes)) { c =>
        setup(c)
        // These two have no mux in front of them: the tap drives a pad driver
        // and nothing else, so the bump tracks the tap level for level.
        for (tx <- Seq(false, true); rx <- Seq(false, true)) {
          c.io.txClk.poke(tx.B)
          c.io.rxClk.poke(rx.B)
          c.clock.step()
          withClue(s"txClk=$tx rxClk=$rx: ") {
            assert(c.io.bumps.txClk.peek().litToBoolean == tx)
            assert(c.io.bumps.rxClk.peek().litToBoolean == rx)
          }
        }
      }
    }
  }
}
