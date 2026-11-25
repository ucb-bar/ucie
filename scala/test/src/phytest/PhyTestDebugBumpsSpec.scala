package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import edu.berkeley.cs.uciedigital.phy.macros.SbDriver
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy
import edu.berkeley.cs.uciedigital.phy.macros.clocking.ClkMux

/** Drives the PHY's observation taps and their selects, and brings the bumps
  * back out for a test to watch. Taps are `Bool`s in `PhyTest.DebugMuxInputs`
  * order and nothing in the path is clocked, so a tap's level shows up on its
  * bump combinationally.
  */
class PhyTestDebugBumpsHarness(
    numLanes: Int = 4,
    hasTxDebugLane: Boolean = true
) extends Module {
  val io = IO(new Bundle {
    val taps = Input(Vec(PhyTest.DebugMuxInputs.length, Bool()))
    val debugMuxSel =
      Input(Vec(PhyTest.NumDebugDrivers, UInt(ClkMux.selWidth.W)))
    val bumps = new DebugBumpsIO(hasTxDebugLane)
  })

  val dut = Module(
    new PhyTest(numLanes = numLanes, hasTxDebugLane = hasTxDebugLane)(true)
  )
  dut.io.regs := DontCare
  // In the full block the divider resets come from the clock register
  // block; the harness reset stands in for it here.
  dut.io.txDividerRstb := (!reset.asBool).asAsyncReset
  dut.io.rxDividerRstb := (!reset.asBool).asAsyncReset

  // Both bands under TileLink, so the mainband and sideband testers stay in
  // reset and only the observation path is live.
  dut.io.regs.mainbandMode := BandMode.tl
  dut.io.regs.sidebandMode := BandMode.tl
  dut.io.regs.testTarget := TestTarget.mainband
  dut.io.regs.txDatapathRst := false.B
  dut.io.regs.rxDatapathRst := false.B
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
  dut.io.regs.debugMuxSel := io.debugMuxSel

  dut.io.debug.testTxLaneClk.foreach(_ := false.B.asClock)
  for ((name, i) <- PhyTest.DebugMuxInputs.zipWithIndex) {
    dut.io.debug.elements(name) match {
      case c: Clock => c := io.taps(i).asClock
      case b: Bool  => b := io.taps(i)
      case other    => throw new IllegalArgumentException(s"$name is $other")
    }
  }
  // The sideband tester is in reset, so its serializer's clock is the main
  // one. This is not the `sbTxClk` tap above, which only feeds the muxes.
  dut.io.sbTxClock := clock
  dut.io.sbTxReset := reset.asBool

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

  io.bumps := dut.io.bumps
}

class PhyTestDebugBumpsSpec extends AnyFunSpec with ChiselSim {
  // The tester instantiates analog macros, whose behavioral models Verilator
  // lints at.
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  val numLanes = 4
  val inputs = PhyTest.DebugMuxInputs

  def bump(c: PhyTestDebugBumpsHarness, mux: Int): Boolean =
    Seq(c.io.bumps.mux0, c.io.bumps.mux1)(mux).peek().litToBoolean

  /** Drives tap `tap` to `level` and every other tap to its complement. */
  def drive(c: PhyTestDebugBumpsHarness, tap: Int, level: Boolean): Unit =
    for (i <- inputs.indices) {
      c.io.taps(i).poke((if (i == tap) level else !level).B)
    }

  def setup(c: PhyTestDebugBumpsHarness): Unit = {
    for (i <- inputs.indices) c.io.taps(i).poke(false.B)
    for (m <- 0 until PhyTest.NumDebugDrivers) c.io.debugMuxSel(m).poke(0.U)
    c.clock.step()
  }

  describe("PhyTest observation bumps") {
    it("should put every selectable input on both bumps, not inverted") {
      simulate(new PhyTestDebugBumpsHarness(numLanes)) { c =>
        setup(c)
        // The selected tap set opposite to every other one, so a wrong select
        // and an inversion both show up as the wrong level.
        for (
          mux <- 0 until PhyTest.NumDebugDrivers;
          (name, sel) <- inputs.zipWithIndex;
          level <- Seq(true, false)
        ) {
          c.io.debugMuxSel(mux).poke(sel.U)
          drive(c, sel, level)
          c.clock.step()
          withClue(s"mux$mux sel $sel ($name) at $level: ") {
            assert(bump(c, mux) == level)
          }
        }
      }
    }

    it("should select independently on the two bumps") {
      simulate(new PhyTestDebugBumpsHarness(numLanes)) { c =>
        setup(c)
        val (a, b) =
          (inputs.indexOf("refClk"), inputs.indexOf("rxDataLsb"))
        c.io.debugMuxSel(0).poke(a.U)
        c.io.debugMuxSel(1).poke(b.U)
        for (level <- Seq(true, false)) {
          drive(c, a, level)
          c.clock.step()
          withClue(s"${inputs(a)} at $level, ${inputs(b)} at ${!level}: ") {
            assert(bump(c, 0) == level)
            assert(bump(c, 1) == !level)
          }
        }
      }
    }

    it("should read 0 on the tied-off inputs") {
      simulate(new PhyTestDebugBumpsHarness(numLanes)) { c =>
        setup(c)
        for (i <- inputs.indices) c.io.taps(i).poke(true.B)
        for (
          mux <- 0 until PhyTest.NumDebugDrivers;
          sel <- inputs.length until ClkMux.numInputs
        ) {
          c.io.debugMuxSel(mux).poke(sel.U)
          c.clock.step()
          assert(!bump(c, mux), s"mux$mux sel $sel")
        }
      }
    }

    it("should have no txData bump without the TX data debug lane") {
      simulate(
        new PhyTestDebugBumpsHarness(numLanes, hasTxDebugLane = false)
      ) { c =>
        assert(c.io.bumps.txData.isEmpty)
        setup(c)
        val sel = inputs.indexOf("txDataLsb")
        c.io.debugMuxSel(1).poke(sel.U)
        for (level <- Seq(true, false)) {
          drive(c, sel, level)
          c.clock.step()
          assert(bump(c, 1) == level, s"txDataLsb at $level")
        }
      }
    }
  }
}
