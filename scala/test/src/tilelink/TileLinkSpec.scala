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
import edu.berkeley.cs.chippy.{
  TLTesterParams,
  TLTester,
  TLTesterIO,
  TLTesterReq,
  TLTesterResp
}
import chisel3.simulator.ChiselSim
import chisel3.simulator.HasSimulator.simulators.verilator
import svsim.verilator.Backend.CompilationSettings
import _root_.circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.Utils
import chisel3.testing.HasTestingDirectory
import java.nio.file.Paths
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.IdRange

abstract class TestDriver extends ExtModule {
  val clock = IO(Output(Clock()))
  val reset = IO(Output(Reset()))
  val tlt = IO(Flipped(new TLTesterIO(TestHarness.tltParams)))

  val codegen = new Codegen(new SystemVerilogFormatter)

  def setStimulus(name: String, body: String) = setInline(
    s"${name}.sv",
    s"""
`timescale 1ns/1ps

function string basename(string path);
  int idx;
  idx = path.len() - 1;
  while (idx >= 0 && path[idx] != "/" && path[idx] != "\\\\")
    idx--;

  if (idx >= 0)
    return path.substr(idx+1, path.len()-1);
  else
    return path;
endfunction

`define UCIE_Q1_BASE 64'h4000
${codegen.formatDefines()}
module ${name}(
  output reg clock,
  output reg reset,
  output reg [63:0] tlt_req_bits_addr,
  output reg [63:0] tlt_req_bits_data,
  output reg tlt_req_bits_is_write,
  output reg tlt_req_valid,
  input tlt_req_ready,
  input [63:0] tlt_resp_bits_data,
  input tlt_resp_valid,
  output reg tlt_resp_ready
);
  task op(input [63:0] addr, input [63:0] data, input is_write, input string ctx);
    begin
      tlt_resp_ready = 1'b1;
      tlt_req_valid = 1'b1;
      tlt_req_bits_addr = addr;
      tlt_req_bits_data = data;
      tlt_req_bits_is_write = is_write;
      fork
        if (!tlt_req_ready) @(posedge tlt_req_ready);
        repeat(1000) @(posedge clock);
      join_any
      assert(tlt_req_ready) else $$fatal(1, "Timeout waiting for TLT request to be ready: %s", ctx);
      fork
        @(negedge clock) tlt_req_valid = 1'b0;
        fork
          if (!tlt_resp_valid) @(posedge tlt_resp_valid);
          repeat(1000) @(posedge clock);
        join_any
      join
      assert(tlt_resp_valid) else $$fatal(1, "Timeout waiting for TLT response to be valid: %s", ctx);
      @(negedge clock);
    end
  endtask
  task write(input [63:0] addr, input [63:0] data, input string ctx);
    begin
      op(addr, data, 1'b1, ctx);
      @(negedge clock);
    end
  endtask
  task read(input [63:0] addr, output [63:0] result, input string ctx);
    begin
      op(addr, 64'b0, 1'b0, ctx);
      result = tlt_resp_bits_data;
      @(negedge clock);
    end
  endtask
  task expect_data(input [63:0] addr, input [63:0] data, input string ctx);
    begin
      reg [63:0] result;
      read(addr, result, ctx);
      assert(result === data) else begin
        $$fatal(1, "Expected 0x%X, got 0x%X: %s", data, result, ctx);
      end
    end
  endtask
  task write_ucie(input [63:0] addr, input [63:0] data, input string ctx);
    begin
      write(`UCIE_Q1_BASE + addr, data, ctx);
    end
  endtask
  task read_ucie(input [63:0] addr, output [63:0] result, input string ctx);
    begin
      read(`UCIE_Q1_BASE + addr, result, ctx);
    end
  endtask
  task expect_ucie(input [63:0] addr, input [63:0] data, input string ctx);
    begin
      expect_data(`UCIE_Q1_BASE + addr, data, ctx);
    end
  endtask
  `define FILE_LINE_CTX $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__)
  `define WRITE_UCIE(addr, data) write_ucie(addr, data, $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__))
  `define READ_UCIE(addr, result) read_ucie(addr, result, $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__))
  `define EXPECT_UCIE(addr, data) expect_ucie(addr, data, $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__))
  `define WRITE_UCIE_MSG(addr, data, msg) write_ucie(addr, data, $$sformatf("%s (%s:%0d)", msg, basename(`__FILE__), `__LINE__))
  `define READ_UCIE_MSG(addr, result, msg) read_ucie(addr, result, $$sformatf("%s (%s:%0d)", msg, basename(`__FILE__), `__LINE__))
  `define EXPECT_UCIE_MSG(addr, data, msg) expect_ucie(addr, data, $$sformatf("%s (%s:%0d)", msg, basename(`__FILE__), `__LINE__))
${Codegen.indent(codegen.formatFns())}
  initial clock = 1'b0;
  always #1 clock = ~clock;

  initial begin
    repeat(100000) @(negedge clock);
    $$fatal(1, "Timeout");
  end

  initial begin
    $$dumpfile("trace.vcd");
    $$dumpvars(0);
    reset = 1'b1;
    tlt_req_bits_addr = 64'b0;
    tlt_req_bits_data = 64'b0;
    tlt_req_bits_is_write = 64'b0;
    tlt_req_valid = 1'b0;
    tlt_resp_ready = 1'b0;
    repeat(5) @(negedge clock);
    reset = 1'b0;
    repeat(5) @(negedge clock);
${Codegen.indent(body, n = 2)}
    $$display("TEST PASSED");
    $$finish;
  end
endmodule
          """.trim
  )
}

class SimTop[T <: TestDriver](driver: => T)(implicit
    p: Parameters
) extends RawModule {
  val drv = Module(driver)

  withClockAndReset(drv.clock, drv.reset) {
    val harness = Module(LazyModule(new TestHarness).module)
    harness.io <> drv.tlt
  }
}

object TestHarness {
  val tltParams = TLTesterParams(addrWidth = 64, dataWidth = 64)
  val beatBytes = 8
}

class TestHarness(includeDefaultModels: Boolean = true)(implicit p: Parameters)
    extends LazyModule {

  val clockNode = ClockSourceNode(Seq(ClockSourceParameters()))
  val tlt = LazyModule(
    new TLTester(TestHarness.tltParams, TestHarness.beatBytes)
  )
  val ucieTL = LazyModule(
    new UcieTL(
      UcieTLParams(includeDefaultModels = includeDefaultModels),
      TestHarness.beatBytes
    )
  )

  ucieTL.digitalClockNode := clockNode
  ucieTL.regNode := tlt.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new TLTesterIO(TestHarness.tltParams))

    clockNode.out(0)._1.clock := clock
    clockNode.out(0)._1.reset := reset

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
    ucieTL.module.io.phy.digitalBypassClk := clock
    ucieTL.module.io.phy.pllRdacVref := 0.U
  }
}

class MmioSimpleTestDriver extends TestDriver {
  setStimulus(
    "MmioSimpleTestDriver",
    """
`EXPECT_UCIE(`TEST_TARGET, 64'h0);
`WRITE_UCIE(`TX_DATA_CHUNK_IN0, 64'hdeadbeef);
`EXPECT_UCIE(`TX_DATA_CHUNK_IN0, 64'hdeadbeef);
          """.trim
  )
}

class UcieTestDriver extends TestDriver {
  setStimulus(
    "UcieTestDriver",
    """
manual_simple();
          """.trim
  )
}

class TileLinkSpec extends AnyFunSpec with ChiselSim {
  describe("UcieTL") {
    it("should generate valid SystemVerilog") {
      implicit val p = Parameters.empty
      ChiselStage.emitSystemVerilogFile(
        LazyModule(new RTLHarness(new UcieTL(UcieTLParams(), 32))).module,
        args = Array(
          "--target-dir",
          (Utils.buildRoot / "UcieTL_should_generate_valid_SystemVerilog").toString
        )
      )
    }

    it("should be able to read/write MMIO registers using ChiselSim") {
      implicit val p = Parameters.empty
      implicit val simulator =
        verilator(verilatorSettings = Utils.verilatorSettings)
      implicit val testingDirectory = new HasTestingDirectory {
        override def getDirectory =
          (Utils.buildRoot / "UcieTL_should_be_able_to_read_write_MMIO_registers_using_ChiselSim").toNIO
      }
      val dut = new TestHarness()
      simulate(LazyModule(dut).module) { c =>
        enableWaves()
        // Allow reset to propagate to UCIe via reset synchronizers.
        c.clock.step(cycles = 5)
        c.io.expect(c.clock, "h4000".U, 0.U)
        c.io.write(c.clock, "h4100".U, "hdeadbeef".U)
        c.io.expect(c.clock, "h4100".U, "hdeadbeef".U)
        println("[TEST] Success")
      }
    }

    it("should be able to read/write MMIO registers using Verilator") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new MmioSimpleTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_be_able_to_read_write_MMIO_registers_using_Verilator"
      )
    }

    it("should support simple manual test using Verilator") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new UcieTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_manual_test_using_Verilator"
      )
    }

    it("should be able to read/write MMIO registers using Xcelium") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new MmioSimpleTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_be_able_to_read_write_MMIO_registers_using_Xcelium"
      )
    }

    it("should support simple manual test using Xcelium") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new UcieTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_manual_test_using_Xcelium"
      )
    }
  }
}
