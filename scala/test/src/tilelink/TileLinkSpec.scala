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
import edu.berkeley.cs.uciedigital.phytest.DebugBumpsIO
import edu.berkeley.cs.chippy.{
  TLTesterParams,
  TLTester,
  TLTesterIO,
  TLTesterReq,
  TLTesterResp,
  TLDriver,
  TLRequestDescriptor,
  TLBackpressureTestWidget
}
import chisel3.simulator.ChiselSim
import chisel3.simulator.HasSimulator.simulators.verilator
import svsim.verilator.Backend.CompilationSettings
import _root_.circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.{AmsLevel, Utils}
import chisel3.testing.HasTestingDirectory
import java.nio.file.Paths
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.diplomacy.AddressSet
import chisel3.stage.DesignAnnotation

abstract class TestDriver extends ExtModule {
  val digitalClock = IO(Output(Clock()))
  val ucieBypassClock = IO(Output(Clock()))
  val ucieDigitalBypassClock = IO(Output(Clock()))
  val ucieSidebandBypassClock = IO(Output(Clock()))
  // 100 MHz reference for the clocking tile's PLL. Unused while the tile is
  // bypassed, which is its reset default, but present so the generated path
  // can be exercised without rewiring the harness.
  val ucieRefClock = IO(Output(Clock()))
  val reset = IO(Output(Reset()))

  def regReqs: Seq[TLRequestDescriptor] = Seq.empty
  def mbReqs: Seq[TLRequestDescriptor] = Seq.empty
  def mbMaxInflight: Int = 1
  def stallCycles: Int = 0
}

abstract class SVTestDriver extends TestDriver {

  /** Half period of the analog bypass clock, in ps. 62.5 is 8 GHz, which with
    * no division is 16 GT/s -- the rate every test here ran at before there was
    * a reason to pick another. A driver overrides it to sit the part at a
    * different rate without touching the design's reset defaults.
    */
  def bypassHalfPeriodPs: Double = 62.5

  val tltReg = IO(Flipped(new TLTesterIO(TestHarness.tltParams)))
  val tltMb = IO(Flipped(new TLTesterIO(TestHarness.tltParams)))

  val codegen = new Codegen(new SystemVerilogFormatter)

  /** Emits this driver as a SystemVerilog module.
    *
    * `body` is the stimulus, inlined as statements into an `initial` block that
    * has already brought the design out of reset, so it cannot declare
    * subroutines. `moduleItems` is inlined at module scope next to the
    * sequences `Codegen` emits, which is where a driver that needs a task of
    * its own puts it.
    */
  def setStimulus(name: String, body: String, moduleItems: String = "") =
    setInline(
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

`define UCIE_Q1_BASE 64'h200000
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
  task op(input [63:0] addr, input [63:0] data, input is_write, input string ctx, output [63:0] resp_data);
    begin
      // Declared and cleared separately: a variable declared inside a task is
      // static, so an initializer here would run once at time zero and leave
      // the flag set from the previous call.
      bit got_resp;
      got_resp = 1'b0;
      // Drive on the falling edge, and sample only after a settling delay.
      // Driving on the rising edge races the DUT sampling it: `op` returns on
      // the posedge that carried the response, so the next call asserted
      // req_valid at that same instant and the request could be presented in
      // the very cycle that answered the previous one, reusing a source ID the
      // TileLink monitor still counts as in flight.
      @(negedge clock);
      intf.resp_ready = 1'b1;
      intf.req_valid = 1'b1;
      intf.req_bits_addr = addr;
      intf.req_bits_data = data;
      intf.req_bits_is_write = is_write;
      // Bounded well above a TileLink round trip over the sideband, which
      // shifts a whole frame out one bit per cycle in each direction.
      for (int i = 0; i < 10000; i++) begin
        #10;
        if (intf.req_ready) begin
          // Check for same-cycle resp (possible with regnode)
          if (intf.resp_valid) begin
            got_resp = 1'b1;
            resp_data = intf.resp_bits_data;
          end
          break;
        end
        @(negedge clock);
      end
      assert(intf.req_ready) else $$fatal(1, "Timeout waiting for TLT request to be ready: %s", ctx);
      // The request transfers on the coming posedge; drop req_valid after it.
      @(negedge clock);
      intf.req_valid = 1'b0;
      if (!got_resp) begin
        for (int i = 0; i < 10000; i++) begin
          #10;
          if (intf.resp_valid) begin
            got_resp = 1'b1;
            resp_data = intf.resp_bits_data;
            break;
          end
          @(negedge clock);
        end
      end
      assert(got_resp) else $$fatal(1, "Timeout waiting for TLT response to be valid: %s", ctx);
    end
  endtask
  task write(input [63:0] addr, input [63:0] data, input string ctx);
    begin
      reg [63:0] discard;
      op(addr, data, 1'b1, ctx, discard);
      // @(negedge clock);
    end
  endtask
  task read(input [63:0] addr, output [63:0] result, input string ctx);
    begin
      op(addr, 64'b0, 1'b0, ctx, result);
      // @(negedge clock);
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
  output reg ucieSidebandBypassClock,
  output reg ucieRefClock,
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
${Codegen.indent(moduleItems)}
  initial digitalClock = 1'b0;
  initial ucieBypassClock = 1'b0;
  initial ucieDigitalBypassClock = 1'b0;
  initial ucieSidebandBypassClock = 1'b0;
  initial ucieRefClock = 1'b0;
  always #1000 digitalClock = ~digitalClock;
  always #${bypassHalfPeriodPs} ucieBypassClock = ~ucieBypassClock;
  always #625 ucieDigitalBypassClock = ~ucieDigitalBypassClock;
  // 800 MHz sideband bypass.
  always #625 ucieSidebandBypassClock = ~ucieSidebandBypassClock;
  // 100 MHz: the PLL multiplies this by 80 to reach 8 GHz.
  always #5000 ucieRefClock = ~ucieRefClock;

  initial begin
    repeat(100000) @(posedge digitalClock);
    $$fatal(1, "Timeout");
  end

  initial begin
`ifdef FSDB
    begin
      string fsdbfile;
      if (!$$value$$plusargs("fsdbfile=%s", fsdbfile)) fsdbfile = "waveform.fsdb";
      $$fsdbDumpfile(fsdbfile);
      $$fsdbDumpvars(0, SimTop, "+all");
    end
`else
    $$dumpfile("trace.vcd");
    $$dumpvars(0);
`endif
    reset = 1'b1;
    repeat(5) @(posedge digitalClock);
    reset = 1'b0;
    repeat(5) @(posedge digitalClock);
${Codegen.indent(body, n = 2)}
    $$display("TEST PASSED");
    $$finish;
  end
endmodule
          """.trim
    )
}

class SimTop[T <: SVTestDriver](
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
    harness.io.ucieSidebandBypassClock := drv.ucieSidebandBypassClock
    harness.io.ucieRefClock := drv.ucieRefClock
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
      UcieTLParams(
        includeDefaultModels = includeDefaultModels,
        maxInflight = 1
      ),
      Seq(AddressSet(0x0, 0xffffL)),
      TestHarness.beatBytes,
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
      val ucieSidebandBypassClock = Input(Clock())
      val ucieRefClock = Input(Clock())
      val reg = new TLTesterIO(TestHarness.tltParams)
      val mb = new TLTesterIO(TestHarness.tltParams)
      // Brought out so the observation bumps show up in a waveform rather than
      // dangling. Nothing in the harness reads them.
      val debug = new DebugBumpsIO
    })

    for (o <- clockNode.out) {
      o._1.clock := clock
      o._1.reset := reset
    }

    io.reg <> tltReg.module.io
    io.mb <> tltMb.module.io
    io.debug := ucieTL.module.io.debug

    // Loopback
    ucieTL.module.io.phy.rxData := ucieTL.module.io.phy.txData
    ucieTL.module.io.phy.rxValid := ucieTL.module.io.phy.txValid
    ucieTL.module.io.phy.rxTrack := ucieTL.module.io.phy.txTrack
    ucieTL.module.io.phy.rxClkP := ucieTL.module.io.phy.txClkP
    ucieTL.module.io.phy.rxClkN := ucieTL.module.io.phy.txClkN
    ucieTL.module.io.phy.sbRxClk := ucieTL.module.io.phy.sbTxClk
    ucieTL.module.io.phy.sbRxData := ucieTL.module.io.phy.sbTxData
    ucieTL.module.io.phy.bypassClk := io.ucieBypassClock
    ucieTL.module.io.phy.digitalBypassClk := io.ucieDigitalBypassClock
    // The sideband runs at its own rate; these tests drive it from the same
    // 800 MHz source the digital bypass used to be.
    ucieTL.module.io.phy.sidebandBypassClk := io.ucieSidebandBypassClock
    ucieTL.module.io.phy.refClk := io.ucieRefClock
  }
}

class ScalaTestDriver extends TestDriver {
  override def desiredName = "ScalaTestDriver"
  setInline(
    "ScalaTestDriver.sv",
    s"""
`timescale 1ps/100fs

module ScalaTestDriver(
  output reg digitalClock,
  output reg ucieBypassClock,
  output reg ucieDigitalBypassClock,
  output reg ucieSidebandBypassClock,
  output reg ucieRefClock,
  output reg reset
);
  initial digitalClock = 1'b0;
  initial ucieBypassClock = 1'b0;
  initial ucieDigitalBypassClock = 1'b0;
  initial ucieSidebandBypassClock = 1'b0;
  initial ucieRefClock = 1'b0;
  always #1000 digitalClock = ~digitalClock;
  always #62.5 ucieBypassClock = ~ucieBypassClock;
  always #625 ucieDigitalBypassClock = ~ucieDigitalBypassClock;
  // 800 MHz sideband bypass.
  always #625 ucieSidebandBypassClock = ~ucieSidebandBypassClock;
  // 100 MHz: the PLL multiplies this by 80 to reach 8 GHz.
  always #5000 ucieRefClock = ~ucieRefClock;

  initial begin
    repeat(100000) @(posedge digitalClock);
    $$fatal(1, "Timeout");
  end

  initial begin
`ifdef FSDB
    begin
      string fsdbfile;
      if (!$$value$$plusargs("fsdbfile=%s", fsdbfile)) fsdbfile = "waveform.fsdb";
      $$fsdbDumpfile(fsdbfile);
      $$fsdbDumpvars(0, ScalaSimTop, "+all");
    end
`else
    $$dumpfile("trace.vcd");
    $$dumpvars(0);
`endif
    reset = 1'b1;
    repeat(5) @(posedge digitalClock);
    reset = 1'b0;
  end
endmodule
""".trim
  )
}

class ScalaTestHarness(
    regReqs: Seq[TLRequestDescriptor],
    mbReqs: Seq[TLRequestDescriptor],
    delayCycles: Int = 32,
    startupDelayCycles: Int = 8,
    mbMaxInflight: Int = 1,
    stallCycles: Int = 0
)(implicit p: Parameters, includeDefaultModels: Boolean = true)
    extends LazyModule {

  val clockNode = ClockSourceNode(Seq(ClockSourceParameters()))
  val regDriver = LazyModule(new TLDriver(regReqs))
  val mbDriver = LazyModule(new TLDriver(mbReqs, mbMaxInflight))
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
      UcieTLParams(
        includeDefaultModels = includeDefaultModels,
        maxInflight = mbMaxInflight
      ),
      Seq(AddressSet(0x0, 0xffffL)),
      TestHarness.beatBytes,
      TestHarness.beatBytes
    )
  )
  val backpressure = LazyModule(new TLBackpressureTestWidget(stallCycles))

  ucieTL.digitalClockNode := clockNode
  ucieTL.regNode := regDriver.node
  backpressure.node := ucieTL.clientNode
  tlRam.node := backpressure.node
  ucieTL.managerNode := mbDriver.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val ucieBypassClock = Input(Clock())
      val ucieDigitalBypassClock = Input(Clock())
      val ucieSidebandBypassClock = Input(Clock())
      val ucieRefClock = Input(Clock())
      val finished = Output(Bool())
    })

    for (o <- clockNode.out) {
      o._1.clock := clock
      o._1.reset := reset
    }

    // Wait a few cycles before starting regDriver, so the PHY's digital reset
    // synchronizer has time to deassert ucieRst (which clocks the regs module).
    val startupCounter = RegInit(0.U(log2Up(startupDelayCycles + 1).W))
    when(startupCounter < startupDelayCycles.U) {
      startupCounter := startupCounter + 1.U
    }
    val startupReady = startupCounter === startupDelayCycles.U

    val delayCounter = RegInit(0.U(log2Up(delayCycles + 1).W))
    when(regDriver.module.io.finished && delayCounter < delayCycles.U) {
      delayCounter := delayCounter + 1.U
    }

    regDriver.module.io.start := startupReady
    mbDriver.module.io.start := delayCounter === delayCycles.U
    io.finished := mbDriver.module.io.finished

    when(io.finished) {
      printf("TEST PASSED\n")
      chisel3.stop()
    }

    ucieTL.module.io.phy.rxData := ucieTL.module.io.phy.txData
    ucieTL.module.io.phy.rxValid := ucieTL.module.io.phy.txValid
    ucieTL.module.io.phy.rxTrack := ucieTL.module.io.phy.txTrack
    ucieTL.module.io.phy.rxClkP := ucieTL.module.io.phy.txClkP
    ucieTL.module.io.phy.rxClkN := ucieTL.module.io.phy.txClkN
    ucieTL.module.io.phy.sbRxClk := ucieTL.module.io.phy.sbTxClk
    ucieTL.module.io.phy.sbRxData := ucieTL.module.io.phy.sbTxData
    ucieTL.module.io.phy.bypassClk := io.ucieBypassClock
    ucieTL.module.io.phy.digitalBypassClk := io.ucieDigitalBypassClock
    // The sideband runs at its own rate; these tests drive it from the same
    // 800 MHz source the digital bypass used to be.
    ucieTL.module.io.phy.sidebandBypassClk := io.ucieSidebandBypassClock
    ucieTL.module.io.phy.refClk := io.ucieRefClock
  }
}

class ScalaSimTop[T <: ScalaTestDriver](
    driver: => T
)(implicit
    p: Parameters,
    includeDefaultModels: Boolean = true
) extends RawModule {
  val drv = Module(driver)

  withClockAndReset(drv.digitalClock, drv.reset) {
    val ucie_harness = Module(
      LazyModule(
        new ScalaTestHarness(
          regReqs = drv.regReqs,
          mbReqs = drv.mbReqs,
          mbMaxInflight = drv.mbMaxInflight,
          stallCycles = drv.stallCycles
        )
      ).module
    )
    ucie_harness.io.ucieBypassClock := drv.ucieBypassClock
    ucie_harness.io.ucieDigitalBypassClock := drv.ucieDigitalBypassClock
    ucie_harness.io.ucieSidebandBypassClock := drv.ucieSidebandBypassClock
    ucie_harness.io.ucieRefClock := drv.ucieRefClock
  }
}

class ScalaTlSimpleTestDriver extends ScalaTestDriver {
  override def regReqs = Codegen.tlSimpleRegReqs
  override def mbReqs = Codegen.tlSimpleMbReqs
}

class ScalaTlSidebandTestDriver extends ScalaTestDriver {
  override def regReqs = Codegen.tlSidebandRegReqs
  override def mbReqs = Codegen.tlSimpleMbReqs
}

class ScalaTlLongTestDriver extends ScalaTestDriver {
  override def regReqs = Codegen.tlSimpleRegReqs
  override def mbReqs = Codegen.tlLongMbReqs
  override def mbMaxInflight = 32
}

class ScalaTlLongSidebandTestDriver extends ScalaTlLongTestDriver {
  override def regReqs = Codegen.tlSidebandRegReqs
}

class ScalaTlLongStallTestDriver extends ScalaTlLongTestDriver {
  override def stallCycles = 1024
}

class MmioSimpleTestDriver extends SVTestDriver {
  setStimulus(
    "MmioSimpleTestDriver",
    """
`EXPECT_UCIE(regDrv, `TEST_TARGET, 64'h0);
`WRITE_UCIE(regDrv, `TX_DATA_CHUNK_IN0, 64'hdeadbeef);
`EXPECT_UCIE(regDrv, `TX_DATA_CHUNK_IN0, 64'hdeadbeef);
          """.trim
  )
}

/** A fast check on the two things a training sweep depends on and neither the
  * Verilator tests nor a single `run_lfsr` exercises: that a run's packets are
  * all counted, and that a lane still reads clean after its delay has been
  * moved across the eye and back.
  *
  * Runs in about three minutes against `models/eye`, where the full sweep takes
  * fifty, which is the difference between iterating on a change and guessing at
  * it.
  */
class ResetReproTestDriver extends SVTestDriver {
  setStimulus(
    "ResetReproTestDriver",
    """
begin : repro
  reg [63:0] sent;
  reg [63:0] got;
  reg [63:0] nom;
  reg [63:0] erl;
  reg [63:0] lat;
  integer i;
  integer fails;
  integer clean;

  fails = 0;
  clean = 0;
  setup_ucie();
  seed_lfsrs();

  // 1. Every packet sent is counted. `run_lfsr` gives up after a fixed number
  // of read backs, so a run whose burst starts late reads a partial count --
  // full packets sent, a fraction of them scored.
  for (i = 0; i < 3; i++) begin
    run_lfsr(`TRAIN_PACKETS);
    `READ_UCIE(regDrv, `TX_PACKETS_SENT, sent);
    `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, got);
    $display("REPRO run %0d: tx sent %0d, rx received %0d", i, sent, got);
    if (got != sent) begin
      $display("REPRO FAIL: run %0d counted %0d of %0d packets", i, got, sent);
      fails = fails + 1;
    end
  end

  // 2. The coarse knob moves the sampling point, and where it is approached
  // from must not matter. Sweep 1 of the training run walks the global code
  // upwards and finds code 12 clean; sweep 3 parks on code 12 coming down from
  // 32 and finds it dirty. Either the approach direction matters -- a delay
  // line shortened under a running clock loses an edge -- or something else
  // sweep 2 leaves behind does. This asks the question directly.
  set_clk_gate(0);
  set_global_delay(12);
  set_clk_gate(1);
  run_lfsr(`TRAIN_PACKETS);
  run_lfsr(`TRAIN_PACKETS);
  `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h1);
  `READ_UCIE(regDrv, `RX_BIT_ERRORS, nom);
  `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h0);
  $display("REPRO code 12 reached directly:   nominal errors %0d", nom);
  if (nom != 0) fails = fails + 1;

  // The two things sweep 2 and sweep 3 do that the check above does not:
  // leave vref at the trained code, and write every data lane's trim. Applied
  // one at a time, so whichever flips it is named rather than inferred.
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) set_rx_vref(l, 64);
  run_lfsr(`TRAIN_PACKETS);
  run_lfsr(`TRAIN_PACKETS);
  `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h1);
  `READ_UCIE(regDrv, `RX_BIT_ERRORS, nom);
  `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h0);
  $display("REPRO code 12, vref 64:           nominal errors %0d", nom);

  set_clk_gate(0);
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) set_tx_delay(l, 0);
  set_clk_gate(1);
  run_lfsr(`TRAIN_PACKETS);
  run_lfsr(`TRAIN_PACKETS);
  `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h1);
  `READ_UCIE(regDrv, `RX_BIT_ERRORS, nom);
  `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h0);
  $display("REPRO code 12, vref 64 + trim 0:  nominal errors %0d", nom);

  // Now the same code, approached downwards from 32, as sweep 3 does.
  set_clk_gate(0);
  set_global_delay(32);
  set_clk_gate(1);
  run_lfsr(`TRAIN_PACKETS);
  set_clk_gate(0);
  set_global_delay(12);
  set_clk_gate(1);
  run_lfsr(`TRAIN_PACKETS);
  run_lfsr(`TRAIN_PACKETS);
  `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h1);
  `READ_UCIE(regDrv, `RX_BIT_ERRORS, nom);
  `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h0);
  $display("REPRO code 12 reached from 32:    nominal errors %0d", nom);

  // 3. The RX clock gate actually gates, and the receiver comes back after it.
  // With the clock stopped at the clock lanes nothing downstream is clocked,
  // so a burst sent across that window is not received at all. Ungating has to
  // restore a receiver that counts every packet again -- everything on the RX
  // divided clock stopped too, the reset synchronizer and the queue's PHY side
  // among them, so coming back is the part worth checking.
  set_rx_clk_gate(0);
  run_lfsr(`TRAIN_PACKETS);
  `READ_UCIE(regDrv, `TX_PACKETS_SENT, sent);
  `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, got);
  $display("REPRO rx clock gated:   tx sent %0d, rx received %0d", sent, got);
  if (got != 0) begin
    $display("REPRO FAIL: %0d packets received with the RX clock gated", got);
    fails = fails + 1;
  end

  set_rx_clk_gate(1);
  run_lfsr(`TRAIN_PACKETS);
  run_lfsr(`TRAIN_PACKETS);
  `READ_UCIE(regDrv, `TX_PACKETS_SENT, sent);
  `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, got);
  $display("REPRO rx clock ungated: tx sent %0d, rx received %0d", sent, got);
  if (got != sent) begin
    $display("REPRO FAIL: %0d of %0d packets after ungating the RX clock",
             got, sent);
    fails = fails + 1;
  end

  // 4. Does the link carry anything at half rate? The eye sweep finds nothing
  // at /2, and the first thing to separate is a phase the sweep missed from a
  // link that is not running at all. Packets sent against packets counted says
  // which: a receiver seeing nothing is not a phase problem.
  set_rx_clk_gate(1);
  for (i = 0; i < 4; i++) begin
    set_main_clk(3, 1);
    set_tx_phase(i);
    set_clk_gate(0);
    set_global_delay(0);
    set_clk_gate(1);
    run_lfsr(`TRAIN_PACKETS);
    run_lfsr(`TRAIN_PACKETS);
    `READ_UCIE(regDrv, `TX_PACKETS_SENT, sent);
    `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h1);
    `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, got);
    `READ_UCIE(regDrv, `RX_BIT_ERRORS, nom);
    `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h0);
    $display("REPRO /2 phase %0d: tx sent %0d, rx received %0d, nominal %0d",
             i, sent, got, nom);
    // Is the count still climbing after `run_lfsr` gave up? If it is, the
    // poll window is simply too short for a burst that now takes twice as
    // long; if it is not, the receiver stopped for some other reason.
    if (i == 0) begin
      for (int w = 0; w < 40; w++) begin
        `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, got);
        if (w % 8 == 7 || got >= `TRAIN_PACKETS)
          $display("    after %0d more reads: rx received %0d", w + 1, got);
        if (got >= `TRAIN_PACKETS) break;
      end
      `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h1);
      `READ_UCIE(regDrv, `RX_BIT_ERRORS, nom);
      `READ_UCIE(regDrv, `RX_BIT_ERRORS_EARLY, erl);
      `READ_UCIE(regDrv, `RX_BIT_ERRORS_LATE, lat);
      `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h0);
      $display("    settled: nominal %0d, early %0d, late %0d", nom, erl, lat);
    end
  end
  set_main_clk(3, 0);

  if (fails != 0) $fatal(1, "%0d repro checks failed", fails);
  $display("TEST PASSED");
end
    """.trim
  )
}

/** Walks a coarse eye diagram at each of the rates the clocking tile can
  * produce, and shows why the coarse phase shifter is needed to do it.
  *
  * The global delay line covers 64 ps. That is a whole UI at 16 GT/s and less
  * than half of one at 8, so below the top rate the line alone cannot walk the
  * sampling point across an eye -- it runs out of range partway. The tile's
  * phase shifter moves TXCLKQ in main clock half cycles, 62.5 ps off an 8 GHz
  * main clock, which is inside what the line covers; the two together tile any
  * UI with no gap. This sweeps both: the shifter for the coarse position and
  * the line within it.
  *
  * A point counts as open when every data lane reads clean on at least one of
  * its three framings. Taking the best of the three is the point: at an
  * arbitrary phase the receiver may frame a UI early or late, and the lowest of
  * the counters is the lane's real error rate rather than an artifact of where
  * the capture happened to start.
  */
/** SystemVerilog shared by every driver that scores a sweep: the per-lane
  * framing result, the task that reads it out of `PhyTest`, and the run length
  * helper the eye reports use.
  *
  * One copy because these sweeps have to stay comparable -- a definition that
  * drifted in one of them would quietly make its eye mean something else.
  */
object TestDriverItems {
  val scoring: String = """
// Which framing, if any, read clean for each scored lane at the last
// measurement: 0 nominal, 1 a UI early, 2 a UI late, 3 real bit errors,
// 4 the lane received nothing.
integer lane_framing[`TRAIN_SCORE_LANES];

// Waits for a burst to finish arriving.
//
// `run_lfsr` reads the packet count a fixed number of times and scores
// whatever has landed. That bound was chosen against a burst at the top rate;
// divide the TX clock and the same burst takes proportionally longer, so the
// window expires mid-flight and every point reads short however good the
// phase is. Measured at /2: the count was still climbing six reads after
// `run_lfsr` returned.
task automatic wait_for_packets();
  reg [63:0] p, prev;
  int stalls;
  begin
    prev = 64'hffffffffffffffff;
    stalls = 0;
    // Wait while packets are still arriving, not for a fixed number of reads.
    // A burst takes proportionally longer at every division, so any constant
    // bound is right at one rate and short at the next -- at /4 a 64-read
    // bound returned with 7 of 16 packets in, and `score_lanes` marks a short
    // receive as framing 4, which reads exactly like a dead lane. Giving up
    // only once the count has genuinely stopped moving is the same condition
    // at every rate.
    for (int w = 0; w < 1024; w++) begin
      `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, p);
      if (p >= `TRAIN_PACKETS) break;
      // Only a stall *after* packets have started counts. `p` sitting at zero
      // means the burst has not reached us yet, which at a divided rate takes
      // longer than any small number of reads -- treating that as "stopped"
      // abandons the wait before the first packet ever lands.
      if (p == prev && p > 0) stalls = stalls + 1;
      else stalls = 0;
      prev = p;
      if (stalls >= 32) break;
    end
  end
endtask

// Reads every scored lane's three bit error counters under one counter pause.
//
// The three differ only in where the pattern is assumed to have started: the
// receiver frames on the valid lane's edge, so a valid bit that was itself
// mis-sampled leaves the whole capture a UI out of step and pins the nominal
// count near half the bits received. Whichever framing reads zero is the one
// that was right.
task automatic score_lanes();
  begin
    reg [63:0] packets;
    reg [63:0] nominal;
    reg [63:0] early;
    reg [63:0] late;
    reg [63:0] sent;
    `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h1);
    `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, packets);
    for (int l = 0; l < `TRAIN_SCORE_LANES; l++) begin
      `READ_UCIE(regDrv, `RX_BIT_ERRORS + l * `RX_BIT_ERRORS_WIDTH, nominal);
      `READ_UCIE(regDrv, `RX_BIT_ERRORS_EARLY + l * `RX_BIT_ERRORS_EARLY_WIDTH, early);
      `READ_UCIE(regDrv, `RX_BIT_ERRORS_LATE + l * `RX_BIT_ERRORS_LATE_WIDTH, late);
      // A re-framed capture is recognised by its error count collapsing
      // relative to the nominal one, not by reaching exactly zero. A lane that
      // has slipped a UI is usually also sampling near the edge that it slipped
      // across, so some of its bits are genuinely corrupted on top of the slip
      // and no framing scores clean: at one tap either side of the boundary the
      // right framing lands around a tenth of the nominal count, not at zero.
      // Testing for zero bins that alongside a total failure and hides the very
      // effect these counters exist to show.
      if (packets < `TRAIN_PACKETS) lane_framing[l] = 4;
      else if (nominal == 64'h0) lane_framing[l] = 0;
      else if (early * `TRAIN_SLIP_RATIO < nominal) lane_framing[l] = 1;
      else if (late * `TRAIN_SLIP_RATIO < nominal) lane_framing[l] = 2;
      else lane_framing[l] = 3;
      // A witness lane's raw counts. Classifying each framing as clean or not
      // throws away the thing worth knowing: whether a framing that is not
      // exactly zero is nonetheless far below the nominal count, which is what
      // a one UI slip resolved by re-framing looks like.
      if (l == 0) begin
        // `TX_PACKETS_SENT` alongside the RX count: a shortfall that shows up
        // on both is the transmitter not sending, one that shows up only here
        // is the receiver not counting what was sent.
        `READ_UCIE(regDrv, `TX_PACKETS_SENT, sent);
        $display("          lane 0: %0d packets (tx sent %0d), nominal %0d, early %0d, late %0d",
                 packets, sent, nominal, early, late);
      end
    end
    `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h0);
  end
endtask

// One character per scored lane -- the data lanes, then valid last, so the
// string is one longer than the counts the sweeps print. Shown only when a
// point needed something other than the nominal framing or failed outright.
// `.` nominal, `e` a UI early, `l` a UI late, `x` real errors, `-` nothing
// received.
task automatic report_framing();
  begin
    string marks;
    bit interesting;
    marks = "";
    interesting = 1'b0;
    for (int l = 0; l < `TRAIN_SCORE_LANES; l++) begin
      case (lane_framing[l])
        0: marks = {marks, "."};
        1: begin marks = {marks, "e"}; interesting = 1'b1; end
        2: begin marks = {marks, "l"}; interesting = 1'b1; end
        3: begin marks = {marks, "x"}; interesting = 1'b1; end
        default: begin marks = {marks, "-"}; interesting = 1'b1; end
      endcase
    end
    if (interesting) $display("          framing: %s", marks);
  end
endtask

// Longest run of consecutive clean codes in `score`, as a start index and a
// length. That run is the eye, and its middle is the code to train to.
task automatic longest_run(
  input integer score[],
  input integer n,
  output integer start,
  output integer len
);
  begin
    integer run_start;
    integer run_len;
    start = 0;
    len = 0;
    run_start = 0;
    run_len = 0;
    for (int i = 0; i < n; i++) begin
      if (score[i] == 1) begin
        if (run_len == 0) run_start = i;
        run_len = run_len + 1;
      end else begin
        run_len = 0;
      end
      if (run_len > len) begin
        len = run_len;
        start = run_start;
      end
    end
  end
endtask
          """.trim
}

/** Coarse eye at 4 Gb/s with the quadrature the divider makes.
  *
  * The bypass pin runs at twice the main clock, so /2 lands TXCLK at 2 GHz and
  * the lane at 4 Gb/s. Dividing by two gives the shifter a span of four main
  * clock half cycles, and each of those is 125 ps -- half a UI at this rate --
  * so the four coarse positions walk two whole UI on their own, and the global
  * delay line only has to fill in between them.
  */
class EyeLowRateDividerTestDriver extends SVTestDriver {

  /** 4 GHz: twice the 2 GHz main clock /2 produces. */
  override def bypassHalfPeriodPs: Double = 125.0

  setStimulus(
    "EyeLowRateDividerTestDriver",
    """
begin : eye_lowrate_div
  integer open_points, total_points;
  integer clean_any;
  string row;

  setup_ucie();
  seed_lfsrs();

  // 4 GHz bypass /2 -> TXCLK 2 GHz -> 4 Gb/s a lane.
  set_main_clk(3, 1);

  $display("Coarse eye at 4 Gb/s, quadrature from the /2 divider");
  $display("  4 coarse positions of 125 ps (half a UI), 8 fine of 8 ps");

  row = "";
  open_points = 0;
  total_points = 0;
  for (int c = 0; c < 4; c++) begin
    set_tx_phase(c);
    for (int fi = 0; fi < 8; fi++) begin
      set_clk_gate(0);
      set_global_delay(fi * 8);
      set_clk_gate(1);
      run_lfsr(`TRAIN_PACKETS);
      run_lfsr(`TRAIN_PACKETS);
      // The burst takes four times as long at 4 Gb/s as at
      // 16 GT/s, so `run_lfsr`'s own poll window expires before
      // the packets land. Without this wait every point scores
      // as "received nothing" and the row reads solid shut.
      wait_for_packets();
      score_lanes();
      clean_any = 0;
      for (int l = 0; l < `TRAIN_DATA_LANES; l++)
        if (lane_framing[l] <= 2) clean_any = clean_any + 1;
      row = {row, clean_any == `TRAIN_DATA_LANES ? "#" : "."};
      if (clean_any == `TRAIN_DATA_LANES) open_points = open_points + 1;
      total_points = total_points + 1;
    end
  end

  $display("  4 Gb/s, divider quadrature: %s  %0d/%0d open",
           row, open_points, total_points);

  // Two UI of travel, so an open stretch and a shut one both have to appear.
  // All open or all shut means the phase is not being resolved at all.
  assert(open_points > 0)
    else $fatal(1, "4 Gb/s /2: nothing samples cleanly, so the sweep never found the eye");
  assert(open_points < total_points)
    else $fatal(1, "4 Gb/s /2: everything samples cleanly, so the eye is not being resolved");

  $display("TEST PASSED");
end
    """.trim,
    moduleItems = TestDriverItems.scoring
  )
}

/** Coarse eye at 4 Gb/s with nothing but the global delay line.
  *
  * The bypass pin runs at the main clock, so /1 already puts the lane at 4 Gb/s
  * and the divider has no quadrature to give -- a span of two half cycles is 0
  * and 180 degrees with nothing between. That leaves the global delay line as
  * the only way to move the sampling point, and its 64 taps of 1 ps cover 64 ps
  * against a 250 ps UI.
  *
  * So this is the case expected to fall short, and the test says so rather than
  * pretending otherwise: it checks the link runs and reports how much of a UI
  * the line reaches. It is the measurement that argues for the divider based
  * shifter, which is why it is worth keeping.
  */
class EyeLowRateDelayTestDriver extends SVTestDriver {

  /** 2 GHz: the main clock itself, undivided. */
  override def bypassHalfPeriodPs: Double = 250.0

  setStimulus(
    "EyeLowRateDelayTestDriver",
    """
begin : eye_lowrate_delay
  integer open_points, total_points;
  integer clean_any;
  string row;

  setup_ucie();
  seed_lfsrs();

  // 2 GHz bypass /1 -> TXCLK 2 GHz -> 4 Gb/s a lane.
  set_main_clk(3, 0);
  // No coarse shift: the delay line is the whole of the sweep.
  set_tx_phase(0);

  $display("Coarse eye at 4 Gb/s, global delay line only, no quadrature");
  $display("  16 fine positions of 4 ps: 64 ps against a 250 ps UI, about a quarter of one");

  row = "";
  open_points = 0;
  total_points = 0;
  for (int fi = 0; fi < 16; fi++) begin
    set_clk_gate(0);
    set_global_delay(fi * 4);
    set_clk_gate(1);
    run_lfsr(`TRAIN_PACKETS);
    run_lfsr(`TRAIN_PACKETS);
    // The burst takes four times as long at 4 Gb/s as at
    // 16 GT/s, so `run_lfsr`'s own poll window expires before
    // the packets land. Without this wait every point scores
    // as "received nothing" and the row reads solid shut.
    wait_for_packets();
    score_lanes();
    clean_any = 0;
    for (int l = 0; l < `TRAIN_DATA_LANES; l++)
      if (lane_framing[l] <= 2) clean_any = clean_any + 1;
    row = {row, clean_any == `TRAIN_DATA_LANES ? "#" : "."};
    if (clean_any == `TRAIN_DATA_LANES) open_points = open_points + 1;
    total_points = total_points + 1;
  end

  $display("  4 Gb/s, delay line only: %s  %0d/%0d open", row, open_points, total_points);

  // Only that the link runs somewhere in the line's reach. Whether the row is
  // solid is the result, not a failure: 64 ps cannot walk a 250 ps UI, and a
  // solid row is that limitation showing rather than a broken sweep.
  assert(open_points > 0)
    else $fatal(1, "4 Gb/s /1: nothing samples cleanly anywhere in the delay line's 64 ps");
  if (open_points == total_points)
    $display("  (solid: 64 ps never reaches an edge at this rate, so the divider based shifter is what covers the rest of the UI)");

  $display("TEST PASSED");
end
    """.trim,
    moduleItems = TestDriverItems.scoring
  )
}

/** Why the coarse eye reads nothing at /2, asked directly.
  *
  * The eye sweep reports open points and nothing else, so a rate that scores
  * shut everywhere cannot be told apart from a rate whose packets never arrive.
  * This runs the same two rates and prints what actually came back at each
  * phase: packets counted against packets sent, and the framing each lane
  * resolved to. A link that is merely mis-sampled counts its packets and scores
  * them dirty; a link that is not running counts nothing.
  *
  * Cut down to a handful of bursts so it answers in minutes rather than the
  * twenty the full sweep takes.
  */
/** The link carries clean traffic at every clock division.
  *
  * A burst, a datapath reset release and a queue drain all take proportionally
  * longer at each division, so anything in the harness that waits a fixed
  * number of iterations is correct at the top rate and short below it. Two such
  * waits have already done this: the RX datapath settle, whose release is
  * synchronized to the divided clock, and `wait_for_packets`. Both presented
  * the same way -- a link that is actually fine reading as broken, either as a
  * short receive scored like a dead lane, or as an RX that missed the
  * idle-to-valid transition and aligned mid-pattern.
  *
  * The eye sweeps would catch that eventually, but they take twenty minutes a
  * rate and report only open-point counts, which cannot tell a mis-sampled link
  * from one that received nothing. This asks the direct question at each
  * division in a few minutes: did every packet arrive, and was it clean.
  */
class RateSweepTestDriver extends SVTestDriver {
  setStimulus(
    "RateSweepTestDriver",
    """
begin : rate_sweep
  setup_ucie();
  seed_lfsrs();

  $display("Traffic at each clock division");
  check_rate("/1", 0);
  check_rate("/2", 1);
  check_rate("/4", 2);

  $display("TEST PASSED");
end
    """.trim,
    moduleItems = TestDriverItems.scoring + """

// One division: find a sampling point where the whole burst arrives clean.
//
// The point is not pinned, because where the eye sits depends on the delay
// line geometry -- a change to a step or an offset moves it, and a test that
// asserts cleanliness at one hardcoded code reports that as a broken link.
// What has to hold at every division is that *some* point works.
task automatic check_rate(input string label, input integer div);
  reg [63:0] got, idlew;
  integer clean_lanes;
  integer clean_at;
  begin
    set_main_clk(3, div);
    set_tx_phase(0);
    clean_at = -1;

    for (int t = 0; t < 4; t++) begin
      set_clk_gate(0);
      set_global_delay(t * 16);
      set_clk_gate(1);
      run_lfsr(`TRAIN_PACKETS);
      wait_for_packets();
      run_lfsr(`TRAIN_PACKETS);
      wait_for_packets();
      score_lanes();
      `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, got);
      // Zero idle words means the RX was not presenting words when
      // transmission began -- what a settle too short for this division looks
      // like. Checked at every point, since it does not depend on the phase.
      `READ_UCIE(regDrv, `RX_IDLE_WORDS_OBSERVED, idlew);

      clean_lanes = 0;
      for (int l = 0; l < `TRAIN_DATA_LANES; l++)
        if (lane_framing[l] == 0) clean_lanes = clean_lanes + 1;
      $display("  %s tap %0d: %0d/%0d packets, %0d/%0d lanes clean, idleWords=%0d",
               label, t * 16, got[31:0], `TRAIN_PACKETS,
               clean_lanes, `TRAIN_DATA_LANES, idlew[31:0]);

      // A short receive scores as framing 4, the same code as a dead lane, so
      // the count is checked separately: a wait that gave up early would
      // otherwise read as a broken link at every point.
      assert(got >= `TRAIN_PACKETS)
        else $fatal(1, "%s: %0d of %0d packets arrived at tap %0d -- a wait sized at the top rate is short here",
                    label, got[31:0], `TRAIN_PACKETS, t * 16);

      if (clean_lanes == `TRAIN_DATA_LANES && clean_at < 0) clean_at = t * 16;
    end

    assert(clean_at >= 0)
      else $fatal(1, "%s: no sampling point in the global delay line's range carries a clean burst",
                  label);
    $display("  %s: clean at tap %0d", label, clean_at);
  end
endtask
"""
  )
}

class EyeDiagramTestDriver extends SVTestDriver {
  setStimulus(
    "EyeDiagramTestDriver",
    """
begin : eye
  integer open_points, total_points;
  integer clean_any;
  integer coarse_positions;
  string row;

  setup_ucie();
  seed_lfsrs();

  $display("Coarse eye at each rate, main clock on the analog bypass pin");

  for (int d = 0; d < `TRAIN_EYE_DIVS; d++) begin
    // Division `d` is /1, /2, /4. The shifter spans 2*div half cycles, which
    // is one period of the divided clock however far it has been divided.
    coarse_positions = 2 << d;
    set_main_clk(3, d);

    row = "";
    open_points = 0;
    total_points = 0;
    for (int c = 0; c < coarse_positions; c++) begin
      set_tx_phase(c);
      for (int fi = 0; fi < `TRAIN_EYE_FINE_POINTS; fi++) begin
        set_clk_gate(0);
        set_global_delay(fi * `TRAIN_EYE_FINE_STEP);
        set_clk_gate(1);
        run_lfsr(`TRAIN_PACKETS);
        run_lfsr(`TRAIN_PACKETS);
        wait_for_packets();
        score_lanes();
        clean_any = 0;
        for (int l = 0; l < `TRAIN_DATA_LANES; l++)
          if (lane_framing[l] <= 2) clean_any = clean_any + 1;
        row = {row, clean_any == `TRAIN_DATA_LANES ? "#" : "."};
        if (clean_any == `TRAIN_DATA_LANES) open_points = open_points + 1;
        total_points = total_points + 1;
      end
    end

    $display("  /%0d (%0d coarse x %0d fine, %0d ps a step): %s  %0d/%0d open",
             1 << d, coarse_positions, `TRAIN_EYE_FINE_POINTS,
             `TRAIN_EYE_FINE_STEP, row, open_points, total_points);

    // An eye that is open everywhere is not an eye, and one that is shut
    // everywhere means the sweep never found the link. Either way the phase
    // is not being resolved and the diagram says nothing.
    assert(open_points > 0)
      else $fatal(1, "Rate /%0d: no phase samples cleanly, so the sweep never found the eye",
                  1 << d);
    assert(open_points < total_points)
      else $fatal(1, "Rate /%0d: every phase samples cleanly, so the eye is not being resolved",
                  1 << d);
  end

  $display("TEST PASSED");
end
    """.trim,
    moduleItems = """
integer lane_framing[`TRAIN_SCORE_LANES];

// Waits for a burst to finish arriving.
//
// `run_lfsr` reads the packet count a fixed number of times and scores
// whatever has landed. That bound was chosen against a burst at the top rate;
// divide the TX clock and the same burst takes proportionally longer, so the
// window expires mid-flight and every point reads short however good the
// phase is. Measured at /2: the count was still climbing six reads after
// `run_lfsr` returned. Waiting here keeps that out of the training sweep's
// bound, which only ever runs at one rate.
task automatic wait_for_packets();
  reg [63:0] p, prev;
  int stalls;
  begin
    prev = 64'hffffffffffffffff;
    stalls = 0;
    // Wait while packets are still arriving, not for a fixed number of reads.
    // A burst takes proportionally longer at every division, so any constant
    // bound is right at one rate and short at the next -- at /4 a 64-read
    // bound returned with 7 of 16 packets in, and `score_lanes` marks a short
    // receive as framing 4, which reads exactly like a dead lane. Giving up
    // only once the count has genuinely stopped moving is the same condition
    // at every rate.
    for (int w = 0; w < 1024; w++) begin
      `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, p);
      if (p >= `TRAIN_PACKETS) break;
      // Only a stall *after* packets have started counts. `p` sitting at zero
      // means the burst has not reached us yet, which at a divided rate takes
      // longer than any small number of reads -- treating that as "stopped"
      // abandons the wait before the first packet ever lands.
      if (p == prev && p > 0) stalls = stalls + 1;
      else stalls = 0;
      prev = p;
      if (stalls >= 32) break;
    end
  end
endtask

task automatic score_lanes();
  begin
    reg [63:0] packets;
    reg [63:0] nominal;
    reg [63:0] early;
    reg [63:0] late;
    `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h1);
    `READ_UCIE(regDrv, `RX_PACKETS_RECEIVED, packets);
    for (int l = 0; l < `TRAIN_SCORE_LANES; l++) begin
      `READ_UCIE(regDrv, `RX_BIT_ERRORS + l * `RX_BIT_ERRORS_WIDTH, nominal);
      `READ_UCIE(regDrv, `RX_BIT_ERRORS_EARLY + l * `RX_BIT_ERRORS_EARLY_WIDTH, early);
      `READ_UCIE(regDrv, `RX_BIT_ERRORS_LATE + l * `RX_BIT_ERRORS_LATE_WIDTH, late);
      if (packets < `TRAIN_PACKETS) lane_framing[l] = 4;
      else if (nominal == 64'h0) lane_framing[l] = 0;
      else if (early * `TRAIN_SLIP_RATIO < nominal) lane_framing[l] = 1;
      else if (late * `TRAIN_SLIP_RATIO < nominal) lane_framing[l] = 2;
      else lane_framing[l] = 3;
    end
    `WRITE_UCIE(regDrv, `RX_PAUSE_COUNTERS, 64'h0);
  end
endtask
    """
  )
}

class ManualSimpleTestDriver extends SVTestDriver {
  setStimulus(
    "ManualSimpleTestDriver",
    """
manual_simple();
          """.trim
  )
}

class ManualLoopbackTestDriver extends SVTestDriver {
  setStimulus(
    "ManualLoopbackTestDriver",
    """
manual_loopback();
          """.trim
  )
}

class TlSimpleTestDriver extends SVTestDriver {
  setStimulus(
    "TlSimpleTestDriver",
    """
tl_simple();
          """.trim
  )
}

class SbManualTestDriver extends SVTestDriver {
  setStimulus(
    "SbManualTestDriver",
    """
sb_manual();
          """.trim
  )
}

class TlSidebandTestDriver extends SVTestDriver {
  setStimulus(
    "TlSidebandTestDriver",
    """
tl_sideband();
          """.trim
  )
}

/** Trains every mainband lane over MMIO, the way software would.
  *
  * Nothing here reaches past the register block: `setup_ucie`, `set_tx_delay`,
  * `set_rx_vref` and `run_lfsr` are the sequences `Codegen` emits, and the two
  * codes being swept -- `txctl_<lane>_tile`'s delay taps and
  * `rxctl_<lane>_vrefSel` -- are pins on the analog tiles. So this measures
  * exactly what a driver on real silicon would measure, through exactly the
  * registers it would use.
  *
  * Three sweeps. The first two are the two axes of an eye, run across every
  * data lane at once: the delay line moves where in the UI the far receiver
  * samples a lane, the reference ladder moves where between the rails it
  * slices, and each lane is scored from its own counters so one pass trains the
  * whole set.
  *
  * FRAMING is why valid is not swept with them. The receiver frames every
  * lane's comparison on the edge it sees on valid, so moving valid together
  * with the lanes it frames keeps the two in step and measures nothing -- and
  * when the pair lands badly the receiver never aligns at all, which reads as
  * every lane receiving nothing rather than as a code being wrong. Held still,
  * valid gives the framing something to be wrong about: a data lane that slips
  * into the next UI has slipped relative to valid, and `PhyTest` scores the
  * same capture framed a UI either side, so reading all three counters
  * separates "this lane's data is wrong" from "this lane slipped a UI". Only
  * the nominal counter defines the eye, since treating a slipped UI as inside
  * it would merge two adjacent UI into one apparent opening; the other two are
  * diagnosis, and the sweep reports where they mattered. The third sweep is the
  * other half of that experiment -- valid alone, past data lanes held still --
  * and doubles as how valid gets trained, since valid cannot be scored against
  * a framing it is itself producing.
  *
  * Both sweeps have to find a bounded run -- the test fails if every code
  * works, because a model that trains without a wrong answer is not modelling
  * the thing being trained. That is what `models/eye` exists to provide and
  * what the behavioral models in `scala/resources/vsrc` cannot; see
  * `verilog/README.md`.
  *
  * The pattern and the scoring come from `PhyTest`, which `setup_ucie` selects
  * as the controller, because that is where the per-lane bit error counters
  * are. The link training state machine does not yet program either code:
  * `PhyLaneTrainer` reports every calibration state complete without running
  * one, so moving this sweep behind the LTSM is a matter of giving that module
  * the registers to drive.
  */
class TrainMainbandTestDriver extends SVTestDriver {
  setStimulus(
    "TrainMainbandTestDriver",
    """
begin : train
  integer global_score[`TRAIN_GLOBAL_CODES];
  integer local_score[`TRAIN_DATA_LANES][`TRAIN_LOCAL_CODES];
  integer vref_score[`TRAIN_DATA_LANES][`TRAIN_VREF_CODES];
  integer trained_local[`TRAIN_DATA_LANES];
  integer trained_vref[`TRAIN_DATA_LANES];
  integer trained_global, edge_global;
  integer run_start, run_len;
  integer clean_nom, clean_any;
  integer global_slipped, vref_slipped;
  integer bounded_global, bounded_vrefs, distinct_local;
  string row;

  // `integer` comes up X, and X plus one stays X.
  bounded_global = 0;
  bounded_vrefs = 0;
  distinct_local = 0;
  global_slipped = 0;
  vref_slipped = 0;

  setup_ucie();
  seed_lfsrs();

  $display("Training %0d data lanes over MMIO, framing on valid (lane %0d)",
           `TRAIN_DATA_LANES, `TRAIN_VALID_LANE);

  // SWEEP 1: where in the UI the far side samples, for every lane at once.
  //
  // The global line delays the quadrature clock, and the quadrature clock is
  // what the forwarded clock lanes carry, so this walks the sampling point
  // across the data rather than walking the data past a fixed sampling point.
  // Its range is a little over a UI, so the eye should open and close exactly
  // once: a run of clean codes with dirty ones either side. Codes outside that
  // run land near an edge, where a lane that has slipped into the next UI is
  // what `rxBitErrorsEarly` and `rxBitErrorsLate` are there to resolve.
  for (int g = 0; g < `TRAIN_GLOBAL_CODES; g++) begin
    set_clk_gate(0);
    set_global_delay(g * `TRAIN_GLOBAL_STEP);
    set_clk_gate(1);
    run_lfsr(`TRAIN_PACKETS);
    run_lfsr(`TRAIN_PACKETS);
    score_lanes();
    clean_nom = 0;
    clean_any = 0;
    for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
      if (lane_framing[l] == 0) clean_nom = clean_nom + 1;
      if (lane_framing[l] <= 2) clean_any = clean_any + 1;
    end
    global_score[g] = (clean_nom == `TRAIN_DATA_LANES) ? 1 : 0;
    if (clean_any > clean_nom) global_slipped = global_slipped + 1;
    $display("  global %3d: %0d/%0d clean, %0d/%0d if a slipped UI is allowed%s",
             g * `TRAIN_GLOBAL_STEP, clean_nom, `TRAIN_DATA_LANES,
             clean_any, `TRAIN_DATA_LANES,
             clean_any > clean_nom ? "   <- slip resolved by early/late" : "");
    report_framing();
  end

  longest_run(global_score, `TRAIN_GLOBAL_CODES, run_start, run_len);
  trained_global = (run_start + run_len / 2) * `TRAIN_GLOBAL_STEP;
  if (run_len > 0 && run_len < `TRAIN_GLOBAL_CODES) bounded_global = 1;
  // One end of the run, where a lane is marginal rather than comfortable.
  // That is where a per-lane trim decides whether it reads clean, so it is
  // where sweep 3 parks.
  edge_global = run_start * `TRAIN_GLOBAL_STEP;
  row = "";
  for (int g = 0; g < `TRAIN_GLOBAL_CODES; g++)
    row = {row, global_score[g] == 1 ? "#" : "."};
  $display("  global eye: %s  (%0d codes, code %0d)",
           row, run_len, trained_global);

  // SWEEP 2: each lane's own trim, taken at the edge of the global eye.
  //
  // A lane's line spans 5 ps, far less than the eye, so at the middle of the
  // eye every code reads clean and the sweep says nothing. At the edge it is
  // the trim that decides, and lanes whose arm of the clock tree is long or
  // short pick different codes -- which is the whole point of having the trim.
  // With the skew model off every arm is identical and every lane lands on the
  // same code, which is correct for that model rather than a failure.
  //
  // This runs before the reference is trained, on the reference sweep 1 was
  // clean with. The eye closes in both directions at once, so at its edge the
  // vertical margin is as thin as the horizontal one and a single step of the
  // ladder is enough to read dirty -- which says nothing about the trim.
  set_clk_gate(0);
  set_global_delay(edge_global);
  set_clk_gate(1);

  for (int c = 0; c < `TRAIN_LOCAL_CODES; c++) begin
    set_clk_gate(0);
    for (int l = 0; l < `TRAIN_DATA_LANES; l++)
      set_tx_delay(l, c * `TRAIN_LOCAL_STEP);
    set_clk_gate(1);
    run_lfsr(`TRAIN_PACKETS);
    run_lfsr(`TRAIN_PACKETS);
    score_lanes();
    clean_nom = 0;
    for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
      local_score[l][c] = (lane_framing[l] == 0) ? 1 : 0;
      if (lane_framing[l] == 0) clean_nom = clean_nom + 1;
    end
    $display("  local %3d: %0d/%0d clean",
             c * `TRAIN_LOCAL_STEP, clean_nom, `TRAIN_DATA_LANES);
    report_framing();
  end

  for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
    longest_run(local_score[l], `TRAIN_LOCAL_CODES, run_start, run_len);
    trained_local[l] = (run_start + run_len / 2) * `TRAIN_LOCAL_STEP;
    if (l > 0 && trained_local[l] != trained_local[0])
      distinct_local = distinct_local + 1;
    row = "";
    for (int c = 0; c < `TRAIN_LOCAL_CODES; c++)
      row = {row, local_score[l][c] == 1 ? "#" : "."};
    $display("  lane %2d trim: %s  (%0d codes, code %0d)",
             l, row, run_len, trained_local[l]);
  end

  set_clk_gate(0);
  set_global_delay(trained_global);
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) set_tx_delay(l, trained_local[l]);
  set_clk_gate(1);

  // SWEEP 3: what level each data lane slices against, at the sampling point
  // and the trims the first two sweeps settled on.
  for (int i = 0; i < `TRAIN_VREF_CODES; i++) begin
    for (int l = 0; l < `TRAIN_DATA_LANES; l++)
      set_rx_vref(l, i * `TRAIN_VREF_STEP);
    run_lfsr(`TRAIN_PACKETS);
    run_lfsr(`TRAIN_PACKETS);
    score_lanes();
    clean_nom = 0;
    clean_any = 0;
    for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
      vref_score[l][i] = (lane_framing[l] == 0) ? 1 : 0;
      if (lane_framing[l] == 0) clean_nom = clean_nom + 1;
      if (lane_framing[l] <= 2) clean_any = clean_any + 1;
    end
    if (clean_any > clean_nom) vref_slipped = vref_slipped + 1;
    $display("  vref_sel %3d: %0d/%0d clean, %0d/%0d if a slipped UI is allowed%s",
             i * `TRAIN_VREF_STEP, clean_nom, `TRAIN_DATA_LANES,
             clean_any, `TRAIN_DATA_LANES,
             clean_any > clean_nom ? "   <- slip resolved by early/late" : "");
    report_framing();
  end

  for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
    longest_run(vref_score[l], `TRAIN_VREF_CODES, run_start, run_len);
    trained_vref[l] = (run_start + run_len / 2) * `TRAIN_VREF_STEP;
    if (run_len > 0 && run_len < `TRAIN_VREF_CODES)
      bounded_vrefs = bounded_vrefs + 1;
    row = "";
    for (int i = 0; i < `TRAIN_VREF_CODES; i++)
      row = {row, vref_score[l][i] == 1 ? "#" : "."};
    $display("  lane %2d vref: %s  (%0d codes, code %0d)",
             l, row, run_len, trained_vref[l]);
  end

  for (int l = 0; l < `TRAIN_DATA_LANES; l++) set_rx_vref(l, trained_vref[l]);

  // The link at the codes every lane picked for itself.
  $display("Trained:");
  set_clk_gate(0);
  set_global_delay(trained_global);
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) set_tx_delay(l, trained_local[l]);
  set_clk_gate(1);
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
    set_rx_vref(l, trained_vref[l]);
    $display("  lane %2d: trim %0d, vref_sel %0d",
             l, trained_local[l], trained_vref[l]);
  end
  $display("  global : code %0d", trained_global);
  $display("  lanes on a trim of their own: %0d", distinct_local);

  run_lfsr(`TRAIN_PACKETS);
  run_lfsr(`TRAIN_PACKETS);
  score_lanes();
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
    assert(lane_framing[l] == 0)
      else $fatal(1, "Lane %0d is not clean at its trained codes (framing code %0d)",
                  l, lane_framing[l]);
  end

  // A sweep that never finds a wrong answer is not measuring anything. The
  // global line covers just over a UI, so its eye has to be bounded; the
  // reference ladder has to run out at one end or both.
  assert(bounded_global == 1)
    else $fatal(1, "The global delay eye is not bounded: every code works, so the sampling point is not being resolved");
  assert(bounded_vrefs > 0)
    else $fatal(1, "No lane has a bounded reference eye: every code works, so the slicer is not comparing against it");

  $display("TEST PASSED");
end
          """.trim,
    moduleItems = TestDriverItems.scoring
  )
}

class TlLongTestDriver extends SVTestDriver {
  setStimulus(
    "TlLongTestDriver",
    """
tl_long();
          """.trim
  )
}

/** Register accesses through a harness's tester, with the harness clock and the
  * PHY clocks the register blocks run on all running.
  *
  * The tester, and the bus side of the register crossings, run on the harness
  * clock. The PhyTest and controller blocks run on the PhyTest and sideband
  * clocks, which the clocking tile takes from `digitalBypassClk` and
  * `sidebandBypassClk` out of reset, so an access to either needs its clock
  * too. Every step advances them all, eight PHY edges to five harness edges as
  * 800 and 500 MHz would. The clock register block is on the harness clock
  * alone, and stepping the others does it no harm.
  *
  * `TLTesterIO.op` steps one clock and leaves `req.valid` asserted, so this
  * drives the tester itself: each access raises `req.valid` for exactly one
  * accepted beat and then waits for its one response.
  */
class MultiClockRegAccess(
    io: TLTesterIO,
    harnessClock: Clock,
    phyClocks: Clock*
) {
  import chisel3.simulator.PeekPokeAPI._

  private var steps = 0

  def step(cycles: Int = 1): Unit = for (_ <- 0 until cycles) {
    harnessClock.step()
    phyClocks.foreach(_.step())
    if (steps % 5 < 3) phyClocks.foreach(_.step())
    steps += 1
  }

  // A response can come back in the cycle its request is accepted -- the clock
  // register block answers combinationally -- so both handshakes are sampled
  // before every step, and each completes on the edge that step makes.
  private def op(
      addr: BigInt,
      data: BigInt,
      write: Boolean,
      timeout: Int
  ): BigInt = {
    io.resp.ready.poke(true.B)
    io.req.bits.addr.poke(addr.U)
    io.req.bits.data.poke(data.U)
    io.req.bits.is_write.poke(write.B)
    io.req.valid.poke(true.B)
    var accepted = false
    var response: Option[BigInt] = None
    var n = 0
    while (response.isEmpty) {
      val acceptNow = !accepted && io.req.ready.peek().litToBoolean
      if (io.resp.valid.peek().litToBoolean) {
        response = Some(io.resp.bits.data.peek().litValue)
      }
      step()
      if (acceptNow) {
        accepted = true
        io.req.valid.poke(false.B)
      }
      n += 1
      assert(n < timeout, f"request to 0x$addr%x never completed")
    }
    response.get
  }

  def write(addr: BigInt, data: BigInt, timeout: Int = 1000): Unit =
    op(addr, data, true, timeout)

  def read(addr: BigInt, timeout: Int = 1000): BigInt =
    op(addr, 0, false, timeout)
}

class TileLinkSpec extends AnyFunSpec with ChiselSim {
  describe("UcieTL") {
    it("should generate valid SystemVerilog") {
      implicit val p: Parameters =
        new freechips.rocketchip.subsystem.WithoutTLMonitors
      ChiselStage.emitSystemVerilogFile(
        LazyModule(
          new RTLHarness(
            new UcieTL(UcieTLParams(), Seq(AddressSet(0x0, 0xffffL)), 32, 32)
          )
        ).module,
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
      // LazyModule.scope is pushed by the LazyModule constructor and only
      // popped by LazyModule.apply, so apply it here rather than inside
      // simulate's by-name argument. If simulate throws first (e.g. no
      // verilator on PATH), an un-applied TestHarness leaks into the global
      // scope and every later elaboration in this JVM fails with
      // "<x>.module was constructed before LazyModule() was run on TestHarness".
      val dut = LazyModule(new TestHarness())
      simulate(dut.module) { c =>
        enableWaves()
        // The tester and the bus side of the register crossings run on the
        // harness clock, the PhyTest and controller register blocks on the
        // PhyTest and sideband clocks, which the clocking tile takes from the
        // digital and sideband bypass clocks. An access needs its block's.
        val regs = new MultiClockRegAccess(
          c.io.reg,
          c.clock,
          c.io.ucieDigitalBypassClock,
          c.io.ucieSidebandBypassClock
        )
        // ChiselSim's own reset sequence only steps the harness clock, so hold
        // reset across edges of all of them to bring the PHY domains, and their
        // ends of the crossings, out of reset with their registers initialized.
        c.reset.poke(true.B)
        regs.step(5)
        c.reset.poke(false.B)
        regs.step(5)
        // Addresses come from the register map by name, the way the
        // SystemVerilog drivers use the generated constants. Hardcoded offsets
        // silently retarget this test at a different register whenever the map
        // grows.
        def addr(name: String): BigInt =
          BigInt(0x200000L) +
            (Codegen.regAddrMap(name) - Codegen.ucieParams.address)
        assert(regs.read(addr("testTarget")) == 0, "testTarget reset value")
        regs.write(addr("txDataChunkIn0"), BigInt("deadbeef", 16))
        assert(
          regs.read(addr("txDataChunkIn0")) == BigInt("deadbeef", 16),
          "txDataChunkIn0 readback"
        )
        // The controller's block, across its own crossing.
        regs.write(addr("ucieRetryTrainingAmt"), 5)
        assert(
          regs.read(addr("ucieRetryTrainingAmt")) == 5,
          "ucieRetryTrainingAmt readback"
        )
        println("[TEST] Success")
      }
    }

    // What `ucieRst` is for, shown by what it does and does not reach.
    //
    // At chip reset the clocking is whatever the reset defaults describe,
    // which need not be what the part is wired for, so the rest of the block
    // comes up on a clock it may have no business running on. The way out is
    // to configure the clocking first and then restart everything else -- and
    // that only works because the clocking registers sit in a domain the
    // restart does not touch. Without that split, asserting the reset would
    // wipe the configuration it was asserted to make use of, and there would
    // be no way to bring the block up on a clock the defaults do not describe.
    it(
      "should reset the UCIe block without losing the clocking configuration"
    ) {
      implicit val p = Parameters.empty
      implicit val simulator =
        verilator(verilatorSettings = Utils.verilatorSettings)
      implicit val testingDirectory = new HasTestingDirectory {
        override def getDirectory =
          (Utils.buildRoot / "UcieTL_should_reset_ucie_keeping_clock_cfg").toNIO
      }
      val dut = LazyModule(new TestHarness())
      simulate(dut.module) { c =>
        // Two domains: the PhyTest registers run on the digital bypass clock,
        // the clocking registers on the link's digital clock, which here is
        // the harness clock. Both run throughout.
        val regs = new MultiClockRegAccess(
          c.io.reg,
          c.clock,
          c.io.ucieDigitalBypassClock
        )
        c.reset.poke(true.B)
        regs.step(5)
        c.reset.poke(false.B)
        regs.step(5)

        def addr(name: String): BigInt =
          BigInt(0x200000L) +
            (Codegen.regAddrMap(name) - Codegen.ucieParams.address)
        def wr(name: String, v: BigInt): Unit = regs.write(addr(name), v)
        def rd(name: String): BigInt = regs.read(addr(name))

        // One register either side of the boundary, each holding a value that
        // is not its reset value.
        wr("txDataChunkIn0", BigInt("deadbeef", 16))
        wr("txClkPhase", 3)
        assert(
          rd("txDataChunkIn0") == BigInt("deadbeef", 16),
          "the block register did not take its value before the reset"
        )
        assert(
          rd("txClkPhase") == 3,
          "the clocking register did not take its value before the reset"
        )

        // Assert and release. Both writes go to the clocking block, which is
        // the point: the register that commands the reset has to live where
        // the reset cannot reach it, or nothing could release it.
        wr("ucieRst", 1)
        regs.step(5)
        wr("ucieRst", 0)
        regs.step(20)

        // The block restarted: its register file is back at reset values.
        val blockReg = rd("txDataChunkIn0")
        assert(
          blockReg == 0,
          s"ucieRst left the block's register file alone (txDataChunkIn0 = 0x${blockReg
              .toString(16)}), " +
            "so it is not reaching the block it is supposed to restart"
        )
        // The clocking configuration did not, so the block comes back up on
        // the clocking software set rather than on the reset defaults.
        val clkReg = rd("txClkPhase")
        assert(
          clkReg == 3,
          s"ucieRst cleared the clocking configuration (txClkPhase 3 -> $clkReg), " +
            "so configuring the clocks and then restarting the block is impossible"
        )
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

    it("should support loopback lane test using Verilator") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new ManualLoopbackTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_support_loopback_lane_test_using_Verilator"
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

    it("should support manual sideband test using Xcelium") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new SbManualTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_support_manual_sideband_test_using_Xcelium"
      )
    }

    it("should support simple TL sideband test using Verilator") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new TlSidebandTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_TL_sideband_test_using_Verilator"
      )
    }

    it("should support simple Scala TL test using Verilator") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlSimpleTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_Scala_TL_test_using_Verilator"
      )
    }

    it("should support simple Scala TL test using VCS") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlSimpleTestDriver),
        Utils.writeVcsSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_Scala_TL_test_using_VCS"
      )
    }

    it("should support simple Scala TL test using Xcelium") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlSimpleTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_Scala_TL_test_using_Xcelium"
      )
    }

    it("should support simple Scala TL sideband test using Verilator") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlSidebandTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_Scala_TL_sideband_test_using_Verilator"
      )
    }

    it("should support simple Scala TL sideband test using Xcelium") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlSidebandTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_Scala_TL_sideband_test_using_Xcelium"
      )
    }

    it("should support long Scala TL sideband test using Xcelium") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlLongSidebandTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_support_long_Scala_TL_sideband_test_using_Xcelium"
      )
    }

    it("should support long Scala TL test using Verilator") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlLongTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_support_long_Scala_TL_test_using_Verilator"
      )
    }

    it("should support long Scala TL test using VCS") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlLongTestDriver),
        Utils.writeVcsSimScript,
        Utils.buildRoot / "UcieTL_should_support_long_Scala_TL_test_using_VCS"
      )
    }

    it("should support long Scala TL test using Xcelium") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlLongTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_support_long_Scala_TL_test_using_Xcelium"
      )
    }

    it(
      "should support long Scala TL test with RAM-side backpressure stall using Verilator"
    ) {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlLongStallTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_should_support_long_Scala_TL_test_with_stall_using_Verilator"
      )
    }

    it(
      "should support long Scala TL test with RAM-side backpressure stall using VCS"
    ) {
      implicit val p = Parameters.empty
      Utils.simulate(
        new ScalaSimTop(new ScalaTlLongStallTestDriver),
        Utils.writeVcsSimScript,
        Utils.buildRoot / "UcieTL_should_support_long_Scala_TL_test_with_stall_using_VCS"
      )
    }

    it("should be able to read/write MMIO registers using VCS") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new MmioSimpleTestDriver),
        Utils.writeVcsSimScript,
        Utils.buildRoot / "UcieTL_should_be_able_to_read_write_MMIO_registers_using_VCS"
      )
    }

    it("should support simple manual test using VCS") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new SimTop(new ManualSimpleTestDriver),
        Utils.writeVcsSimScript,
        Utils.buildRoot / "UcieTL_should_support_simple_manual_test_using_VCS"
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
      "should repro the reset packet shortfall using Xcelium with PHY analog models"
    ) {
      implicit val p = Parameters.empty
      implicit val includeDefaultModels = false
      Utils.simulate(
        new SimTop(new ResetReproTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_repro_the_reset_packet_shortfall",
        amsLevel = Some(AmsLevel.Eye)
      )
    }

    it(
      "should measure a coarse eye at each rate using Xcelium with PHY analog models"
    ) {
      implicit val p = Parameters.empty
      implicit val includeDefaultModels = false
      Utils.simulate(
        new SimTop(new EyeDiagramTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_measure_a_coarse_eye_at_each_rate",
        amsLevel = Some(AmsLevel.Eye)
      )
    }

    it(
      "should carry clean traffic at every clock division using Xcelium with PHY analog models"
    ) {
      implicit val p = Parameters.empty
      implicit val includeDefaultModels = false
      Utils.simulate(
        new SimTop(new RateSweepTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_carry_traffic_at_each_division",
        amsLevel = Some(AmsLevel.Eye)
      )
    }

    it(
      "should measure a 4 Gbps eye with divider quadrature using Xcelium with PHY analog models"
    ) {
      implicit val p = Parameters.empty
      implicit val includeDefaultModels = false
      Utils.simulate(
        new SimTop(new EyeLowRateDividerTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_measure_a_4Gbps_eye_divider_quadrature",
        amsLevel = Some(AmsLevel.Eye)
      )
    }

    it(
      "should measure a 4 Gbps eye with the delay line only using Xcelium with PHY analog models"
    ) {
      implicit val p = Parameters.empty
      implicit val includeDefaultModels = false
      Utils.simulate(
        new SimTop(new EyeLowRateDelayTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_measure_a_4Gbps_eye_delay_line_only",
        amsLevel = Some(AmsLevel.Eye)
      )
    }

    it(
      "should train the mainband over MMIO using Xcelium with PHY analog models"
    ) {
      implicit val p = Parameters.empty
      implicit val includeDefaultModels = false
      Utils.simulate(
        new SimTop(new TrainMainbandTestDriver),
        Utils.writeXrunSimScript,
        Utils.buildRoot / "UcieTL_should_train_the_mainband_over_MMIO_using_Xcelium_with_PHY_analog_models",
        amsLevel = Some(AmsLevel.Eye)
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
        amsLevel = Some(AmsLevel.Eye)
      )
    }
  }
}
