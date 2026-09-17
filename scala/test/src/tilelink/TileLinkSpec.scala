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
  val reset = IO(Output(Reset()))

  def regReqs: Seq[TLRequestDescriptor] = Seq.empty
  def mbReqs: Seq[TLRequestDescriptor] = Seq.empty
  def mbMaxInflight: Int = 1
  def stallCycles: Int = 0
}

abstract class SVTestDriver extends TestDriver {
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
  always #1000 digitalClock = ~digitalClock;
  always #62.5 ucieBypassClock = ~ucieBypassClock;
  always #625 ucieDigitalBypassClock = ~ucieDigitalBypassClock;

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
      val reg = new TLTesterIO(TestHarness.tltParams)
      val mb = new TLTesterIO(TestHarness.tltParams)
      // Brought out so the observation bumps show up in a waveform rather than
      // dangling. Nothing in the harness reads them.
      val debug = new DebugBumpsIO
    })

    clockNode.out(0)._1.clock := clock
    clockNode.out(0)._1.reset := reset

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
  output reg reset
);
  initial digitalClock = 1'b0;
  initial ucieBypassClock = 1'b0;
  initial ucieDigitalBypassClock = 1'b0;
  always #1000 digitalClock = ~digitalClock;
  always #62.5 ucieBypassClock = ~ucieBypassClock;
  always #625 ucieDigitalBypassClock = ~ucieDigitalBypassClock;

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
      val finished = Output(Bool())
    })

    clockNode.out(0)._1.clock := clock
    clockNode.out(0)._1.reset := reset

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
  integer tap_score[`TRAIN_DATA_LANES][`TRAIN_DELAY_TAPS];
  integer vref_score[`TRAIN_DATA_LANES][`TRAIN_VREF_CODES];
  integer valid_score[`TRAIN_DELAY_TAPS];
  integer trained_tap[`TRAIN_SCORE_LANES];
  integer trained_vref[`TRAIN_DATA_LANES];
  integer run_start, run_len;
  integer clean_nom, clean_any;
  integer tap_slipped, vref_slipped, valid_slipped;
  integer valid_broken, valid_trained_tap;
  integer bounded_taps, bounded_vrefs;
  reg [63:0] dbg_tx;
  reg [63:0] dbg_rx;
  string row;

  // `integer` comes up X, and X plus one stays X.
  bounded_taps = 0;
  bounded_vrefs = 0;
  tap_slipped = 0;
  vref_slipped = 0;
  valid_slipped = 0;
  valid_broken = 0;

  setup_ucie();
  seed_lfsrs();

  $display("Training %0d data lanes over MMIO, framing on valid (lane %0d)",
           `TRAIN_DATA_LANES, `TRAIN_VALID_LANE);

  // SWEEP 1: where in the UI each data lane is sampled.
  //
  // Valid stays where `setup_ucie` left it. The receiver frames every lane's
  // comparison on the edge it sees there, so moving valid along with the lanes
  // it frames would keep the two in step and measure nothing -- and when it
  // lands badly the receiver never aligns at all, which reads as every lane
  // receiving nothing rather than as a code being wrong. Held still, it also
  // gives the framing something to be wrong ABOUT: a data lane that slips into
  // the next UI has slipped relative to valid, which is exactly the condition
  // `rxBitErrorsEarly` and `rxBitErrorsLate` claim to resolve.
  for (int t = 0; t < `TRAIN_DELAY_TAPS; t++) begin
    for (int l = 0; l < `TRAIN_DATA_LANES; l++) set_tx_delay(l, t);
    // Programming a batch of lanes leaves the mainband idle for long enough
    // that the next run does not align. It is the same effect that makes the
    // very first run after `setup_ucie` come back empty -- that is just the
    // largest batch of all, 63 writes -- and one run is enough to recover from
    // it. So each point runs twice and scores the second.
    run_lfsr(`TRAIN_PACKETS);
    run_lfsr(`TRAIN_PACKETS);
    score_lanes();
    clean_nom = 0;
    clean_any = 0;
    for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
      // The eye is where the NOMINAL framing reads clean. A lane that only
      // reads clean a UI either side has slipped, and counting that as inside
      // the eye would merge two adjacent UI into one apparent opening and pick
      // a code sitting on the boundary between them.
      tap_score[l][t] = (lane_framing[l] == 0) ? 1 : 0;
      if (lane_framing[l] == 0) clean_nom = clean_nom + 1;
      if (lane_framing[l] <= 2) clean_any = clean_any + 1;
    end
    if (clean_any > clean_nom) tap_slipped = tap_slipped + 1;
    $display("  tap %2d: %0d/%0d clean, %0d/%0d if a slipped UI is allowed%s",
             t, clean_nom, `TRAIN_DATA_LANES, clean_any, `TRAIN_DATA_LANES,
             clean_any > clean_nom ? "   <- slip resolved by early/late" : "");
    report_framing();
  end

  for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
    longest_run(tap_score[l], `TRAIN_DELAY_TAPS, run_start, run_len);
    trained_tap[l] = run_start + run_len / 2;
    if (run_len > 0 && run_len < `TRAIN_DELAY_TAPS)
      bounded_taps = bounded_taps + 1;
    row = "";
    for (int t = 0; t < `TRAIN_DELAY_TAPS; t++)
      row = {row, tap_score[l][t] == 1 ? "#" : "."};
    $display("  lane %2d eye: %s  (%0d taps, tap %0d)",
             l, row, run_len, trained_tap[l]);
  end

  // SWEEP 2: what level each data lane slices against, at the sampling point
  // its own first sweep found.
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) set_tx_delay(l, trained_tap[l]);
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
    $display("  lane %2d eye: %s  (%0d codes, vref_sel %0d)",
             l, row, run_len, trained_vref[l]);
  end

  // SWEEP 3: valid alone, with every data lane held at the codes it just
  // picked. This is the other half of the framing experiment -- sweep 1 moved
  // the framed lanes past a fixed edge, this moves the edge past fixed lanes --
  // and it is also how valid gets trained, since valid cannot be scored against
  // a framing it is itself producing. Its quality is whether the lanes it
  // frames read clean.
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
    set_tx_delay(l, trained_tap[l]);
    set_rx_vref(l, trained_vref[l]);
  end
  for (int t = 0; t < `TRAIN_DELAY_TAPS; t++) begin
    set_tx_delay(`TRAIN_VALID_LANE, t);
    run_lfsr(`TRAIN_PACKETS);
    run_lfsr(`TRAIN_PACKETS);
    score_lanes();
    clean_nom = 0;
    clean_any = 0;
    for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
      if (lane_framing[l] == 0) clean_nom = clean_nom + 1;
      if (lane_framing[l] <= 2) clean_any = clean_any + 1;
    end
    valid_score[t] = (clean_nom == `TRAIN_DATA_LANES) ? 1 : 0;
    if (clean_nom < `TRAIN_DATA_LANES) valid_broken = valid_broken + 1;
    if (clean_any > clean_nom) valid_slipped = valid_slipped + 1;
    $display("  valid tap %2d: %0d/%0d clean, %0d/%0d if a slipped UI is allowed%s",
             t, clean_nom, `TRAIN_DATA_LANES, clean_any, `TRAIN_DATA_LANES,
             clean_any > clean_nom ? "   <- slip resolved by early/late" : "");
    report_framing();
  end

  longest_run(valid_score, `TRAIN_DELAY_TAPS, run_start, run_len);
  valid_trained_tap = run_start + run_len / 2;
  trained_tap[`TRAIN_VALID_LANE] = valid_trained_tap;
  row = "";
  for (int t = 0; t < `TRAIN_DELAY_TAPS; t++)
    row = {row, valid_score[t] == 1 ? "#" : "."};
  $display("  valid  eye: %s  (%0d taps, tap %0d)",
           row, run_len, valid_trained_tap);

  // The link at the codes every lane picked for itself.
  $display("Trained:");
  set_tx_delay(`TRAIN_VALID_LANE, valid_trained_tap);
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
    set_tx_delay(l, trained_tap[l]);
    set_rx_vref(l, trained_vref[l]);
    $display("  lane %2d: tap %0d, vref_sel %0d", l, trained_tap[l], trained_vref[l]);
  end
  $display("  valid  : tap %0d", valid_trained_tap);
  run_lfsr(`TRAIN_PACKETS);
  run_lfsr(`TRAIN_PACKETS);
  score_lanes();
  for (int l = 0; l < `TRAIN_DATA_LANES; l++) begin
    assert(lane_framing[l] == 0)
      else $fatal(1, "Lane %0d is not clean at its trained codes (framing code %0d)",
                  l, lane_framing[l]);
  end

  $display("Framing: a slipped UI was resolved by early/late at %0d of %0d sampling points, %0d of %0d reference points, and %0d of %0d valid points",
           tap_slipped, `TRAIN_DELAY_TAPS, vref_slipped, `TRAIN_VREF_CODES,
           valid_slipped, `TRAIN_DELAY_TAPS);

  // Walking valid across more than a UI has to disturb the framing somewhere.
  // If it never does, valid is not the edge the receiver aligns on and nothing
  // else this sweep reports about framing means anything. Whether the early and
  // late counters then resolve those points is the open question the sweep
  // exists to answer, so that is reported rather than asserted.
  assert(valid_broken > 0)
    else $fatal(1, "Moving valid across %0d taps never disturbed the framing, so the receiver is not aligning on it",
                `TRAIN_DELAY_TAPS);

  // An eye has to be bounded on both axes, for every lane. If every code works
  // the model in front of the receiver is not resolving the thing being
  // trained, and the sweep proved nothing.
  assert(bounded_taps == `TRAIN_DATA_LANES)
    else $fatal(1, "Only %0d of %0d lanes found a bounded sampling eye, so the delay line is not moving the sampling point",
                bounded_taps, `TRAIN_DATA_LANES);
  assert(bounded_vrefs == `TRAIN_DATA_LANES)
    else $fatal(1, "Only %0d of %0d lanes found a bounded reference eye, so the slicer is not comparing against its reference",
                bounded_vrefs, `TRAIN_DATA_LANES);
end
          """.trim,
    moduleItems = """
// Which framing, if any, read clean for each scored lane at the last
// measurement: 0 nominal, 1 a UI early, 2 a UI late, 3 real bit errors,
// 4 the lane received nothing.
integer lane_framing[`TRAIN_SCORE_LANES];

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
      if (l == 0)
        $display("          lane 0: %0d packets, nominal %0d, early %0d, late %0d",
                 packets, nominal, early, late);
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
        // The clocking tile hands `digitalBypassClk` straight through as the
        // UCIe digital clock, so that port -- not the harness clock -- is what
        // the register block and its reset synchronizer run on. The register
        // TL path is combinational, so advancing this clock alone carries an
        // MMIO access.
        val ucieClk = c.io.ucieDigitalBypassClock
        // ChiselSim's own reset sequence only steps the harness clock, so hold
        // reset across a few edges of this one to bring the UCIe domain out of
        // reset with its registers initialized.
        c.reset.poke(true.B)
        ucieClk.step(cycles = 5)
        c.reset.poke(false.B)
        ucieClk.step(cycles = 5)
        // Addresses come from the register map by name, the way the
        // SystemVerilog drivers use the generated constants. Hardcoded offsets
        // silently retarget this test at a different register whenever the map
        // grows.
        def addr(name: String): BigInt =
          BigInt(0x200000L) +
            (Codegen.regAddrMap(name) - Codegen.ucieParams.address)
        c.io.reg.expect(ucieClk, addr("testTarget").U, 0.U)
        c.io.reg.write(ucieClk, addr("txDataChunkIn0").U, "hdeadbeef".U)
        c.io.reg.expect(ucieClk, addr("txDataChunkIn0").U, "hdeadbeef".U)
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
