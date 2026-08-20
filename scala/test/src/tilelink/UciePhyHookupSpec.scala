package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import org.scalatest.funspec.AnyFunSpec
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.prci._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.AddressSet
import edu.berkeley.cs.chippy.{TLDriver, TLRequestDescriptor}
import edu.berkeley.cs.uciedigital.phytest.BandMode
import edu.berkeley.cs.uciedigital.Utils

/** Register sequences for the ucieDigital-to-PHY hookup checks. */
object UciePhyHookup {
  def write(name: String, value: BigInt): TLRequestDescriptor =
    TLRequestDescriptor(Codegen.regAddrMap(name), isWrite = true, data = value)

  /** The usual PHY bring-up, but with the tester's own lane patterns zeroed
    * afterwards. PhyTest keeps enqueueing beats while it owns the mainband, so
    * zeroing its patterns is what makes the bumps go quiet -- anything still
    * moving out there has to be coming from somewhere else.
    */
  val quietPhyTest: Seq[TLRequestDescriptor] =
    Codegen.tlRegReqs(
      BandMode.manual.litValue,
      BandMode.manual.litValue
    ) ++ Seq(
      write("txClkP", 0),
      write("txClkN", 0),
      write("txTrack", 0),
      write("txValid", 0)
    )

  /** Same, then hand the bumps to ucieDigital. */
  val handToUcieDigital: Seq[TLRequestDescriptor] =
    quietPhyTest :+ write("controllerSel", ControllerSel.ucie.litValue)
}

/** Watches the mainband bumps while one controller or the other owns them.
  *
  * ucieDigital only raises `mainband.tx.valid` when it has something to send,
  * which used to mean the PHY serializer ran dry and the forwarded clock
  * stopped between beats. It now hands the PHY an idle beat -- clock and track
  * patterns, data and valid lanes at zero -- every cycle, so this measures the
  * bumps directly: with nothing to transmit the clock and track bumps must
  * still be toggling while the data and valid bumps stay quiet.
  *
  * The bumps are looped back, so the digital's own transmission is also what
  * comes back at it; `rxBeatSeen` only goes high if the forwarded clock made it
  * out and back, since the PHY's receive path is clocked off it.
  */
class UciePhyHookupHarness(
    regReqs: Seq[TLRequestDescriptor],
    ucieDigitalOwnsMainband: Boolean,
    measureCycles: Int = 512,
    minBumpEdges: Int = 256,
    settleCycles: Int = 64,
    startupDelayCycles: Int = 8
)(implicit p: Parameters, includeDefaultModels: Boolean = true)
    extends LazyModule {

  val clockNode = ClockSourceNode(Seq(ClockSourceParameters()))
  val regDriver = LazyModule(new TLDriver(regReqs))
  // The mainband TL path is unused here (ucieDigital cannot carry TL traffic
  // until link training is wired up), but the manager node still needs a
  // client bound to it. This driver is never started; it carries one request
  // only because TLDriver cannot be built with an empty list.
  val mbDriver = LazyModule(
    new TLDriver(Seq(TLRequestDescriptor(0, isWrite = false, data = 0)))
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
  ucieTL.regNode := regDriver.node
  tlRam.node := ucieTL.clientNode
  ucieTL.managerNode := mbDriver.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val ucieBypassClock = Input(Clock())
      val ucieDigitalBypassClock = Input(Clock())
    })

    clockNode.out(0)._1.clock := clock
    clockNode.out(0)._1.reset := reset

    val bumps = ucieTL.module.io.phy
    bumps.rxData := bumps.txData
    bumps.rxValid := bumps.txValid
    bumps.rxTrack := bumps.txTrack
    bumps.rxClkP := bumps.txClkP
    bumps.rxClkN := bumps.txClkN
    bumps.sbRxClk := bumps.sbTxClk
    bumps.sbRxData := bumps.sbTxData
    bumps.bypassClk := io.ucieBypassClock
    bumps.digitalBypassClk := io.ucieDigitalBypassClock

    // Same startup delay as the other Scala-driven harnesses: the PHY's reset
    // synchronizer needs a few cycles before the register block is clocked.
    val startupCounter = RegInit(0.U(log2Up(startupDelayCycles + 1).W))
    when(startupCounter < startupDelayCycles.U) {
      startupCounter := startupCounter + 1.U
    }
    regDriver.module.io.start := startupCounter === startupDelayCycles.U
    mbDriver.module.io.start := false.B

    // Measure only once the mode is programmed and the beats already queued
    // for the PHY have drained: the bring-up writes leave PhyTest's default
    // clock pattern in flight, which would otherwise show up in the counts.
    val setupDone = regDriver.module.io.finished
    val settleCounter = RegInit(0.U(log2Up(settleCycles + 1).W))
    when(setupDone && settleCounter =/= settleCycles.U) {
      settleCounter := settleCounter + 1.U
    }
    val measuring = settleCounter === settleCycles.U
    val measureCounter = RegInit(0.U(log2Up(measureCycles + 1).W))
    when(measuring && measureCounter =/= measureCycles.U) {
      measureCounter := measureCounter + 1.U
    }
    val measured = measureCounter === measureCycles.U

    // One counter per bump, each clocked by the bump it watches, so a bump that
    // never moves leaves its counter at the async-reset value. Reading them
    // from the digital domain is an unsynchronized crossing, which is fine for
    // the wide margins these are compared against.
    def bumpActivity(bump: Clock): UInt =
      withClockAndReset(bump, reset.asAsyncReset) {
        val count = RegInit(0.U(32.W))
        when(measuring) { count := count + 1.U }
        count
      }

    val clkPActivity = bumpActivity(bumps.txClkP)
    val clkNActivity = bumpActivity(bumps.txClkN)
    val trackActivity = bumpActivity(bumps.txTrack.asClock)
    val validActivity = bumpActivity(bumps.txValid.asClock)
    val dataActivity = bumpActivity(bumps.txData.reduce(_ || _).asClock)

    // What ucieDigital sees coming back around the loopback.
    val digiRx = ucieTL.ucieDigitalLazy.module.io.phyFacingIo.mainbandLink.rx
    val digiRxValid = BoringUtils.tapAndRead(digiRx.valid)
    val digiRxLaneValid = BoringUtils.tapAndRead(digiRx.bits.valid)
    val digiRxData = BoringUtils.tapAndRead(digiRx.bits.data)

    val rxBeatSeen = RegInit(false.B)
    val rxBeatNotIdle = RegInit(false.B)
    when(measuring && digiRxValid) {
      rxBeatSeen := true.B
      when(digiRxLaneValid =/= 0.U || digiRxData.asUInt =/= 0.U) {
        rxBeatNotIdle := true.B
      }
    }

    val clockBumpsRunning =
      clkPActivity > minBumpEdges.U && clkNActivity > minBumpEdges.U
    val trackBumpRunning = trackActivity > minBumpEdges.U
    val laneBumpsQuiet = validActivity === 0.U && dataActivity === 0.U
    val allBumpsQuiet =
      clkPActivity === 0.U && clkNActivity === 0.U &&
        trackActivity === 0.U && laneBumpsQuiet

    val passed = Wire(Bool())
    if (ucieDigitalOwnsMainband) {
      passed := clockBumpsRunning && trackBumpRunning && laneBumpsQuiet &&
        rxBeatSeen && !rxBeatNotIdle
    } else {
      passed := allBumpsQuiet && !rxBeatSeen
    }

    when(measuring && measureCounter === (measureCycles - 1).U) {
      printf(
        "[hookup] clkP=%d clkN=%d trk=%d vld=%d dat=%d rxSeen=%d rxNotIdle=%d\n",
        clkPActivity,
        clkNActivity,
        trackActivity,
        validActivity,
        dataActivity,
        rxBeatSeen,
        rxBeatNotIdle
      )
    }

    when(measured) {
      if (ucieDigitalOwnsMainband) {
        assert(
          clockBumpsRunning,
          "ucieDigital owns the mainband but the forwarded clock bumps are not toggling"
        )
        assert(
          trackBumpRunning,
          "ucieDigital owns the mainband but the track bump is not toggling"
        )
        assert(
          laneBumpsQuiet,
          "ucieDigital has nothing to send, so its idle beats must leave the data and valid bumps quiet"
        )
        assert(
          rxBeatSeen,
          "no mainband beat came back through the PHY, so the forwarded clock never reached the receive path"
        )
        assert(
          !rxBeatNotIdle,
          "ucieDigital received something other than the idle beats it sent around the loopback"
        )
      } else {
        assert(
          allBumpsQuiet,
          "PhyTest owns the mainband with its lane patterns zeroed, so every bump should be quiet"
        )
        assert(
          !rxBeatSeen,
          "ucieDigital does not own the mainband, so it should not be receiving beats"
        )
      }
      // Only stop on success: a $finish in the same time step as a failing
      // assertion masks the simulator's nonzero exit status.
      when(passed) {
        printf("TEST PASSED\n")
        chisel3.stop()
      }
    }
  }
}

/** Clock and reset generation for the hookup sims, matching `ScalaTestDriver`.
  */
class UciePhyHookupDriver extends TestDriver {
  override def desiredName = "UciePhyHookupDriver"
  setInline(
    "UciePhyHookupDriver.sv",
    s"""
`timescale 1ps/100fs

module UciePhyHookupDriver(
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
      $$fsdbDumpvars(0, UciePhyHookupSimTop, "+all");
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

class UciePhyHookupSimTop(
    regReqs: Seq[TLRequestDescriptor],
    ucieDigitalOwnsMainband: Boolean
)(implicit p: Parameters, includeDefaultModels: Boolean = true)
    extends RawModule {
  val drv = Module(new UciePhyHookupDriver)

  withClockAndReset(drv.digitalClock, drv.reset) {
    val harness = Module(
      LazyModule(
        new UciePhyHookupHarness(regReqs, ucieDigitalOwnsMainband)
      ).module
    )
    harness.io.ucieBypassClock := drv.ucieBypassClock
    harness.io.ucieDigitalBypassClock := drv.ucieDigitalBypassClock
  }
}

class UciePhyHookupSpec extends AnyFunSpec {
  describe("ucieDigital through the PHY") {
    it(
      "keeps the mainband forwarded clock running while it has nothing to send"
    ) {
      implicit val p = Parameters.empty
      Utils.simulate(
        new UciePhyHookupSimTop(
          UciePhyHookup.handToUcieDigital,
          ucieDigitalOwnsMainband = true
        ),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UciePhyHookup_ucie_mainband_clock_keeps_running"
      )
    }

    it(
      "leaves the mainband bumps quiet when PhyTest owns them and sends nothing"
    ) {
      implicit val p = Parameters.empty
      Utils.simulate(
        new UciePhyHookupSimTop(
          UciePhyHookup.quietPhyTest,
          ucieDigitalOwnsMainband = false
        ),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UciePhyHookup_phytest_mainband_bumps_quiet"
      )
    }
  }
}
