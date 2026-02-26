package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import org.scalatest.funspec.AnyFunSpec
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.prci._
import edu.berkeley.cs.uciedigital.tilelink._
import edu.berkeley.cs.chippy.TLTesterParams
import edu.berkeley.cs.chippy.TLTester
import edu.berkeley.cs.chippy.TLTesterIO
import edu.berkeley.cs.chippy.TLTesterReq
import edu.berkeley.cs.chippy.TLTesterResp
import chisel3.simulator.ChiselSim
import chisel3.simulator.HasSimulator.simulators.verilator
import svsim.verilator.Backend.CompilationSettings
import _root_.circt.stage.ChiselStage
import chisel3.simulator.stimulus.RunUntilFinished
import edu.berkeley.cs.uciedigital.Utils

trait TestDriverInterface {
  def clock: Clock
  def reset: Reset
  def tlt: TLTesterIO
}
class TestDriver extends ExtModule with TestDriverInterface {
  val clock = IO(Output(Clock()))
  val reset = IO(Output(Reset()))
  val tlt = IO(Flipped(new TLTesterIO(TestHarness.tltParams)))
  setInline(
    "TestDriver.sv",
    """module TestDriver(
            |    output reg clock,
            |    output reset,
            |    output [63:0] tlt_req_bits_addr,
            |    output [63:0] tlt_req_bits_data,
            |    output tlt_req_bits_is_write,
            |    output tlt_req_valid,
            |    input tlt_req_ready,
            |    input [63:0] tlt_resp_bits_data,
            |    input tlt_resp_valid,
            |    output tlt_resp_ready
            |);
            | `timescale 1ns/1ps
            | initial clock = 1'b0;
            | always #1 clock = ~clock;
            | initial begin
            |   repeat(5) @(posedge clock);
            |   $display("TEST PASSED");
            |   $finish;
            | end
            |endmodule
          """.stripMargin
  )
}

class SimTop(implicit
    p: Parameters
) extends RawModule {
  val drv = Module(new TestDriver)

  withClockAndReset(drv.clock, drv.reset) {
    val harness = Module(LazyModule(new TestHarness).module)
    harness.io <> drv.tlt
  }
}

object TestHarness {
  val tltParams = TLTesterParams(addrWidth = 64, dataWidth = 64)
  val ucieParams = UcieTLParams(sim = true)
  val beatBytes = 8
}

class TestHarness(implicit p: Parameters) extends LazyModule {
  val tlt = LazyModule(
    new TLTester(TestHarness.tltParams, TestHarness.beatBytes)
  )

  val ucieTL = LazyModule(
    new UcieTL(TestHarness.ucieParams, TestHarness.beatBytes)
  )
  val clockSourceNode_digital = ClockSourceNode(
    Seq(ClockSourceParameters())
  )

  ucieTL.clockNode := clockSourceNode_digital
  ucieTL.node := tlt.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new TLTesterIO(TestHarness.tltParams))

    io <> tlt.module.io

    // Loopback
    ucieTL.module.io.phy.rxData := ucieTL.module.io.phy.txData
    ucieTL.module.io.phy.rxValid := ucieTL.module.io.phy.txValid
    ucieTL.module.io.phy.rxTrack := ucieTL.module.io.phy.txTrack
    ucieTL.module.io.phy.rxClkP := ucieTL.module.io.phy.txClkP
    ucieTL.module.io.phy.rxClkN := ucieTL.module.io.phy.txClkN
    ucieTL.module.io.phy.sbRxClk := ucieTL.module.io.phy.sbTxClk
    ucieTL.module.io.phy.sbRxData := ucieTL.module.io.phy.sbTxData
    ucieTL.module.io.phy.refClkP := clock
    ucieTL.module.io.phy.refClkN := (!clock.asBool).asClock
    ucieTL.module.io.phy.bypassClkP := clock
    ucieTL.module.io.phy.bypassClkN := (!clock.asBool).asClock
    ucieTL.module.io.phy.pllRdacVref := 0.U

    clockSourceNode_digital.out(0)._1.clock := clock
    clockSourceNode_digital.out(0)._1.reset := reset
  }
}

class TestHarnessSpec extends AnyFunSpec with ChiselSim {
  describe("TestHarness") {
    it("should generate valid SystemVerilog") {
      implicit val p = Parameters.empty
      ChiselStage.emitSystemVerilogFile(
        LazyModule(new TestHarness()).module,
        args = Array(
          "--target-dir",
          (Utils.buildRoot / "TestHarness_should_generate_valid_SystemVerilog").toString
        )
      )
    }

    it("should be able to read/write MMIO registers using ChiselSim") {
      implicit val p = Parameters.empty
      implicit val simulator =
        verilator(verilatorSettings = Utils.verilatorSettings)
      val dut = new TestHarness()
      simulate(LazyModule(dut).module, additionalResetCycles = 5) { c =>
        enableWaves()
        c.io.expect(c.clock, "h4000".U, 0.U)
        c.io.write(c.clock, "h40f8".U, "hdeadbeef".U)
        c.io.expect(c.clock, "h40f8".U, "hdeadbeef".U)
        println("[TEST] Success")
      }
    }

    it("should be able to read/write MMIO registers using Verilog testbench") {
      implicit val p = Parameters.empty
      implicit val simulator =
        verilator(verilatorSettings = Utils.verilatorSettings)
      simulateRaw(new SimTop) { c =>
        RunUntilFinished
      }
    }
  }
}
