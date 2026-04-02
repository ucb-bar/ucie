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
import freechips.rocketchip.diplomacy.AddressSet

abstract class TestDriver extends ExtModule {
  val digitalClock = IO(Output(Clock()))
  val ucieBypassClock = IO(Output(Clock()))
  val ucieDigitalBypassClock = IO(Output(Clock()))
  val reset = IO(Output(Reset()))
  val tltReg = IO(Flipped(new TLTesterIO(TestHarness.tltParams)))
  val tltMb = IO(Flipped(new TLTesterIO(TestHarness.tltParams)))

  val codegen = new Codegen(new SystemVerilogFormatter)

  def setStimulus(name: String, body: String) = setInline(
    s"${name}.sv",
    s"""
`timescale 1ps/100fs

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

interface tltBus (
  output reg [63:0] req_bits_addr,
  output reg [63:0] req_bits_data,
  output reg req_bits_is_write,
  output reg req_valid,
  input req_ready,
  input [63:0] resp_bits_data,
  input resp_valid,
  output reg resp_ready
);
endinterface

module TLTDriver(
  input clock,
  tltBus intf
);
  task op(input [63:0] addr, input [63:0] data, input is_write, input string ctx);
    begin
      intf.resp_ready = 1'b1;
      intf.req_valid = 1'b1;
      intf.req_bits_addr = addr;
      intf.req_bits_data = data;
      intf.req_bits_is_write = is_write;
      for (int i = 0; i < 1000; i++) begin
        @(posedge clock)
        if (intf.req_ready) break;
      end
      assert(intf.req_ready) else $$fatal(1, "Timeout waiting for TLT request to be ready: %s", ctx);
      fork
        @(negedge clock) intf.req_valid = 1'b0;
        fork
          if (!intf.resp_valid) @(posedge intf.resp_valid);
          repeat(1000) @(posedge clock);
        join_any
      join
      assert(intf.resp_valid) else $$fatal(1, "Timeout waiting for TLT response to be valid: %s", ctx);
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
      result = intf.resp_bits_data;
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
  initial begin
    intf.req_bits_addr = 64'b0;
    intf.req_bits_data = 64'b0;
    intf.req_bits_is_write = 64'b0;
    intf.req_valid = 1'b0;
    intf.resp_ready = 1'b0;
  end
endmodule

module ${name}(
  output reg digitalClock,
  output reg ucieBypassClock,
  output reg ucieDigitalBypassClock,
  output reg reset,

  output reg [63:0] tltReg_req_bits_addr,
  output reg [63:0] tltReg_req_bits_data,
  output reg tltReg_req_bits_is_write,
  output reg tltReg_req_valid,
  input tltReg_req_ready,
  input [63:0] tltReg_resp_bits_data,
  input tltReg_resp_valid,
  output reg tltReg_resp_ready,

  output reg [63:0] tltMb_req_bits_addr,
  output reg [63:0] tltMb_req_bits_data,
  output reg tltMb_req_bits_is_write,
  output reg tltMb_req_valid,
  input tltMb_req_ready,
  input [63:0] tltMb_resp_bits_data,
  input tltMb_resp_valid,
  output reg tltMb_resp_ready
);
  tltBus tltReg(
    .req_bits_addr(tltReg_req_bits_addr),
    .req_bits_data(tltReg_req_bits_data),
    .req_bits_is_write(tltReg_req_bits_is_write),
    .req_valid(tltReg_req_valid),
    .req_ready(tltReg_req_ready),
    .resp_bits_data(tltReg_resp_bits_data),
    .resp_valid(tltReg_resp_valid),
    .resp_ready(tltReg_resp_ready)
  );
  tltBus tltMb(
    .req_bits_addr(tltMb_req_bits_addr),
    .req_bits_data(tltMb_req_bits_data),
    .req_bits_is_write(tltMb_req_bits_is_write),
    .req_valid(tltMb_req_valid),
    .req_ready(tltMb_req_ready),
    .resp_bits_data(tltMb_resp_bits_data),
    .resp_valid(tltMb_resp_valid),
    .resp_ready(tltMb_resp_ready)
  );
  TLTDriver regDrv (.clock(digitalClock), .intf(tltReg));
  TLTDriver mbDrv(.clock(digitalClock), .intf(tltMb));
  `define WRITE(drv, addr, data) drv.write(addr, data, $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__))
  `define READ(drv, addr, result) drv.read(addr, result, $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__))
  `define EXPECT(drv, addr, data) drv.expect_data(addr, data, $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__))
  `define WRITE_MSG(drv, addr, data, msg) drv.write(addr, data, $$sformatf("%s (%s:%0d)", msg, basename(`__FILE__), `__LINE__))
  `define READ_MSG(drv, addr, result, msg) drv.read(addr, result, $$sformatf("%s (%s:%0d)", msg, basename(`__FILE__), `__LINE__))
  `define EXPECT_MSG(drv, addr, data, msg) drv.expect_data(addr, data, $$sformatf("%s (%s:%0d)", msg, basename(`__FILE__), `__LINE__))
  `define WRITE_UCIE(drv, addr, data) drv.write_ucie(addr, data, $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__))
  `define READ_UCIE(drv, addr, result) drv.read_ucie(addr, result, $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__))
  `define EXPECT_UCIE(drv, addr, data) drv.expect_ucie(addr, data, $$sformatf("%s:%0d", basename(`__FILE__), `__LINE__))
  `define WRITE_UCIE_MSG(drv, addr, data, msg) drv.write_ucie(addr, data, $$sformatf("%s (%s:%0d)", msg, basename(`__FILE__), `__LINE__))
  `define READ_UCIE_MSG(drv, addr, result, msg) drv.read_ucie(addr, result, $$sformatf("%s (%s:%0d)", msg, basename(`__FILE__), `__LINE__))
  `define EXPECT_UCIE_MSG(drv, addr, data, msg) drv.expect_ucie(addr, data, $$sformatf("%s (%s:%0d)", msg, basename(`__FILE__), `__LINE__))
${Codegen.indent(codegen.formatFns())}
  initial digitalClock = 1'b0;
  initial ucieBypassClock = 1'b0;
  initial ucieDigitalBypassClock = 1'b0;
  always #1000 digitalClock = ~digitalClock;
  always #62.5 ucieBypassClock = ~ucieBypassClock;
  always #625 ucieDigitalBypassClock = ~ucieDigitalBypassClock;

  initial begin
    repeat(100000) @(negedge digitalClock);
    $$fatal(1, "Timeout");
  end

  initial begin
    $$dumpfile("trace.vcd");
    $$dumpvars(0);
    reset = 1'b1;
    repeat(5) @(negedge digitalClock);
    reset = 1'b0;
    repeat(5) @(negedge digitalClock);
${Codegen.indent(body, n = 2)}
    $$display("TEST PASSED");
    $$finish;
  end
endmodule
          """.trim
  )
}

class SimTop[T <: TestDriver](
    driver: => T
)(implicit
    p: Parameters,
    includeDefaultModels: Boolean = true
) extends RawModule {
  val drv = Module(driver)

  withClockAndReset(drv.digitalClock, drv.reset) {
    val harness = Module(
      LazyModule(
        new TestHarness
      ).module
    )
    harness.io.ucieBypassClock := drv.ucieBypassClock
    harness.io.ucieDigitalBypassClock := drv.ucieDigitalBypassClock
    harness.io.reg <> drv.tltReg
    harness.io.mb <> drv.tltMb
  }
}

object TestHarness {
  val tltParams = TLTesterParams(addrWidth = 64, dataWidth = 64)
  val beatBytes = 8
}

class TestHarness(implicit p: Parameters, includeDefaultModels: Boolean = true)
    extends LazyModule {

  val clockNode = ClockSourceNode(Seq(ClockSourceParameters()))
  val tltReg = LazyModule(
    new TLTester(TestHarness.tltParams, TestHarness.beatBytes)
  )
  val tltMb = LazyModule(
    new TLTester(TestHarness.tltParams, TestHarness.beatBytes)
  )
  val tlRam =
    LazyModule(
      new TLRAM(
        AddressSet(0x0, 0xffffL),
        beatBytes = TestHarness.beatBytes,
        cacheable = false
      )
    )
  val ucieTL = LazyModule(
    new UcieTL(
      UcieTLParams(includeDefaultModels = includeDefaultModels),
      TestHarness.beatBytes
    )
  )

  ucieTL.digitalClockNode := clockNode
  ucieTL.regNode := tltReg.node
  tlRam.node := ucieTL.clientNode
  ucieTL.managerNode := tltMb.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val ucieBypassClock = Input(Clock())
      val ucieDigitalBypassClock = Input(Clock())
      val reg = new TLTesterIO(TestHarness.tltParams)
      val mb = new TLTesterIO(TestHarness.tltParams)
    })

    clockNode.out(0)._1.clock := clock
    clockNode.out(0)._1.reset := reset

    io.reg <> tltReg.module.io
    io.mb <> tltMb.module.io

    // Loopback
    ucieTL.module.io.phy.rxData := ucieTL.module.io.phy.txData
    ucieTL.module.io.phy.rxValid := ucieTL.module.io.phy.txValid
    ucieTL.module.io.phy.rxTrack := ucieTL.module.io.phy.txTrack
    ucieTL.module.io.phy.rxClkP := ucieTL.module.io.phy.txClkP
    ucieTL.module.io.phy.rxClkN := ucieTL.module.io.phy.txClkN
    ucieTL.module.io.phy.sbRxClk := ucieTL.module.io.phy.sbTxClk
    ucieTL.module.io.phy.sbRxData := ucieTL.module.io.phy.sbTxData
    ucieTL.module.io.phy.refClkP := DontCare
    ucieTL.module.io.phy.refClkN := DontCare
    ucieTL.module.io.phy.bypassClkP := io.ucieBypassClock
    ucieTL.module.io.phy.bypassClkN := (!io.ucieBypassClock.asBool).asClock
    ucieTL.module.io.phy.digitalBypassClk := io.ucieDigitalBypassClock
    ucieTL.module.io.phy.pllRdacVref := 0.U
  }
}

class MmioSimpleTestDriver extends TestDriver {
  setStimulus(
    "MmioSimpleTestDriver",
    """
`EXPECT_UCIE(regDrv, `TEST_TARGET, 64'h0);
`WRITE_UCIE(regDrv, `TX_DATA_CHUNK_IN0, 64'hdeadbeef);
`EXPECT_UCIE(regDrv, `TX_DATA_CHUNK_IN0, 64'hdeadbeef);
          """.trim
  )
}

class ManualSimpleTestDriver extends TestDriver {
  setStimulus(
    "ManualSimpleTestDriver",
    """
manual_simple();
          """.trim
  )
}

class TlSimpleTestDriver extends TestDriver {
  setStimulus(
    "TlSimpleTestDriver",
    """
tl_simple();
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
        c.io.reg.expect(c.clock, "h4000".U, 0.U)
        c.io.reg.write(c.clock, "h4100".U, "hdeadbeef".U)
        c.io.reg.expect(c.clock, "h4100".U, "hdeadbeef".U)
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
        new SimTop(new ManualSimpleTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_manual_test_using_Verilator"
      )
    }

    it("should support simple TL test using Verilator") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new TlSimpleTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_TL_test_using_Verilator"
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
        new SimTop(new ManualSimpleTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_manual_test_using_Xcelium"
      )
    }

    it(
      "should support simple manual test using Xcelium with PHY analog models"
    ) {
      implicit val p = Parameters.empty
      implicit val includeDefaultModels = false
      Utils.simulate(
        new SimTop(new ManualSimpleTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_manual_test_using_Xcelium_with_PHY_analog_models",
        includeVamsModels = true
      )
    }
  }
}
