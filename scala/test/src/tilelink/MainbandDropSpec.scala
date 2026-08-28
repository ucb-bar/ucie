package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._

import org.scalatest.funspec.AnyFunSpec
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.prci._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.AddressSet
import edu.berkeley.cs.chippy.TLDriver
import edu.berkeley.cs.uciedigital.Utils

/** Passes a TileLink link through unchanged, counting the beats that cross it
  * and optionally holding the A channel off in a square wave.
  *
  * Both ends of the counting are in this module, so nothing here reaches into
  * `UcieTL`: on the manager side it counts the A beats `UcieTL` accepts for
  * framing, and on the client side the A beats that come back out of
  * `rxABuffer` into the RAM. The mainband path is a loopback, so those two
  * counts have to converge.
  *
  * @param aStallLog2
  *   half-period of the A-channel stall, in digital cycles, as a power of two.
  *   0 leaves the channel alone.
  * @param dStallLog2
  *   same for the D channel. Different periods for the two so every combination
  *   of stalled and free happens somewhere in the run.
  */
class TLStallMonitor(aStallLog2: Int, dStallLog2: Int = 0)(implicit
    p: Parameters
) extends LazyModule {
  val node = TLAdapterNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val enable = Input(Bool())
      val aBeats = Output(UInt(32.W))
      val dBeats = Output(UInt(32.W))
      val stalling = Output(Bool())
    })

    require(node.in.size == 1, "one link only")
    val (in, _) = node.in.head
    val (out, _) = node.out.head

    val phase = RegInit(0.U(32.W))
    when(io.enable) { phase := phase + 1.U }
    val aStall =
      if (aStallLog2 == 0) false.B else io.enable && phase(aStallLog2)
    val dStall =
      if (dStallLog2 == 0) false.B else io.enable && phase(dStallLog2)

    // A and D, with the stalls spliced in.
    out.a.valid := in.a.valid && !aStall
    out.a.bits := in.a.bits
    in.a.ready := out.a.ready && !aStall

    in.d.valid := out.d.valid && !dStall
    in.d.bits := out.d.bits
    out.d.ready := in.d.ready && !dStall

    // No B/C/E traffic on this link.
    in.b.valid := false.B
    in.b.bits := DontCare
    in.c.ready := true.B
    in.e.ready := true.B
    out.b.ready := true.B
    out.c.valid := false.B
    out.c.bits := DontCare
    out.e.valid := false.B
    out.e.bits := DontCare

    val aBeats = RegInit(0.U(32.W))
    val dBeats = RegInit(0.U(32.W))
    when(in.a.fire) { aBeats := aBeats + 1.U }
    when(in.d.fire) { dBeats := dBeats + 1.U }

    io.aBeats := aBeats
    io.dBeats := dBeats
    io.stalling := aStall || dStall
  }
}

/** The mainband TileLink loopback with the RAM side stalled in a square wave
  * while traffic keeps flowing, and a watchdog on beats that never arrive.
  *
  * `TileLink.scala:1102-1151` drives `rxABuffer.io.enq.valid` from the framer
  * without ever looking at `enq.ready`, so a frame that arrives with the buffer
  * full is dropped on the floor with no error anywhere. Credit flow is the only
  * thing standing between the partner and that queue.
  */
class MbDropHarness(
    regReqs: Seq[edu.berkeley.cs.chippy.TLRequestDescriptor],
    mbReqs: Seq[edu.berkeley.cs.chippy.TLRequestDescriptor],
    delayCycles: Int = 32,
    startupDelayCycles: Int = 8,
    mbMaxInflight: Int = 1,
    aStallLog2: Int = 5,
    dStallLog2: Int = 6,
    tlBufferDepth: Int = 15,
    stuckCycles: Int = 1024
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
        maxInflight = mbMaxInflight,
        // Smaller than the driver's outstanding-request limit, so credit flow
        // -- not the driver -- is what has to keep the RX buffers from
        // overflowing. That is what TileLink.scala:1122-1124 says it does.
        tlBufferDepth = tlBufferDepth
      ),
      Seq(AddressSet(0x0, 0xffffL)),
      TestHarness.beatBytes,
      TestHarness.beatBytes
    )
  )
  // Counts what goes in, counts what comes out, and stalls the RAM side.
  // The RAM side stops taking requests, which holds rxABuffer's head; the
  // driver side stops taking responses, which holds rxDBuffer's head and makes
  // the D channel credit-limited instead of always-ready.
  val mgrMon = LazyModule(new TLStallMonitor(0, dStallLog2))
  val ramMon = LazyModule(new TLStallMonitor(aStallLog2, 0))

  ucieTL.digitalClockNode := clockNode
  ucieTL.regNode := regDriver.node
  ucieTL.managerNode := mgrMon.node
  mgrMon.node := mbDriver.node
  ramMon.node := ucieTL.clientNode
  tlRam.node := ramMon.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val ucieBypassClock = Input(Clock())
      val ucieDigitalBypassClock = Input(Clock())
      val finished = Output(Bool())
    })

    clockNode.out(0)._1.clock := clock
    clockNode.out(0)._1.reset := reset

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

    // The stall pattern starts with the mainband traffic, so the first stall
    // lands well after credits are already moving.
    mgrMon.module.io.enable := mbDriver.module.io.start
    ramMon.module.io.enable := mbDriver.module.io.start

    val aSent = mgrMon.module.io.aBeats
    val aArrived = ramMon.module.io.aBeats
    val dSent = ramMon.module.io.dBeats
    val dArrived = mgrMon.module.io.dBeats

    // Every A beat framed onto the mainband is looped straight back into this
    // die's own rxABuffer, and every D beat the RAM answers with comes back to
    // the driver. If either count stops converging while the link is not being
    // held off, a frame went missing.
    // How many A beats are framed but have not reached the RAM: a lower bound
    // on rxABuffer's occupancy, since credit-only frames take slots too.
    val pendingA = aSent - aArrived
    val peakPendingA = RegInit(0.U(32.W))
    when(pendingA > peakPendingA) { peakPendingA := pendingA }
    val stallWindows = RegInit(0.U(32.W))
    val wasStalling = RegNext(ramMon.module.io.stalling, false.B)
    when(ramMon.module.io.stalling && !wasStalling) {
      stallWindows := stallWindows + 1.U
    }

    val counts = Cat(aSent, aArrived, dSent, dArrived)
    val progress = counts =/= RegNext(counts)
    val pending = aSent =/= aArrived || dSent =/= dArrived
    val stuck = RegInit(0.U(32.W))
    val heldOff = ramMon.module.io.stalling || mgrMon.module.io.stalling
    when(pending && !progress && !heldOff) {
      stuck := stuck + 1.U
    }.otherwise {
      stuck := 0.U
    }
    assert(
      stuck < stuckCycles.U,
      "mainband TL frame dropped: %d A beats framed, %d reached the RAM; " +
        "%d D beats answered, %d reached the driver\n",
      aSent,
      aArrived,
      dSent,
      dArrived
    )

    when(io.finished) {
      printf(
        "TEST PASSED (A %d/%d, D %d/%d, peak A in flight %d over %d stalls)\n",
        aArrived,
        aSent,
        dArrived,
        dSent,
        peakPendingA,
        stallWindows
      )
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

class MbDropSimTop[T <: ScalaTestDriver](
    driver: => T,
    aStallLog2: Int = 5,
    dStallLog2: Int = 6,
    tlBufferDepth: Int = 15
)(implicit
    p: Parameters,
    includeDefaultModels: Boolean = true
) extends RawModule {
  val drv = Module(driver)

  withClockAndReset(drv.digitalClock, drv.reset) {
    val ucie_harness = Module(
      LazyModule(
        new MbDropHarness(
          regReqs = drv.regReqs,
          mbReqs = drv.mbReqs,
          mbMaxInflight = drv.mbMaxInflight,
          aStallLog2 = aStallLog2,
          dStallLog2 = dStallLog2,
          tlBufferDepth = tlBufferDepth
        )
      ).module
    )
    ucie_harness.io.ucieBypassClock := drv.ucieBypassClock
    ucie_harness.io.ucieDigitalBypassClock := drv.ucieDigitalBypassClock
  }
}

/** Long enough to put many stall windows on top of live credit traffic.
  */
class MbDropTestDriver extends ScalaTestDriver {
  override def regReqs = Codegen.tlSimpleRegReqs
  override def mbReqs = {
    val pattern: BigInt = BigInt(0x0100010001000100L)
    val writes = (0 until 128).map { i =>
      edu.berkeley.cs.chippy.TLRequestDescriptor(
        BigInt(i) * 8,
        isWrite = true,
        data = BigInt(i) * pattern
      )
    }
    val reads = (0 until 128).map { i =>
      edu.berkeley.cs.chippy.TLRequestDescriptor(
        BigInt(i) * 8,
        isWrite = false,
        data = BigInt(i) * pattern
      )
    }
    writes ++ reads
  }
  override def mbMaxInflight = 32
}

/** Mainband TileLink with backpressure that arrives while credits are already
  * in flight -- the overlap `docs/code-review-2026-08-20.md` finding 6 says no
  * existing test produces.
  *
  * Fails today. `rxABuffer` and `rxDBuffer` both overflow and the driver reads
  * back zeros from an address it wrote:
  *
  * {{{
  * TLDriver read mismatch: idx=196 src=0 addr=0x0220
  *   expected=0x4400440044004400 got=0x0000000000000000
  * }}}
  *
  * The chain: a credit-carrying frame held at a buffer head is counted once per
  * cycle it waits (`TileLink.scala:1204`, `deq.valid` where it wants
  * `deq.fire`), so `cred_gnt` is granted room the partner does not have; the
  * framer then enqueues past the end of a full `Queue`
  * (`TileLink.scala:1145-1151` never looks at `enq.ready`) and the frame is
  * gone with no error anywhere. Applying findings 3-5 as suggested makes this
  * test pass with 0 drops and all 256 requests correct.
  */
class MainbandDropSpec extends AnyFunSpec {
  describe("mainband TileLink") {
    it("should not drop frames when the RAM side stalls mid-traffic") {
      implicit val p = Parameters.empty
      Utils.simulate(
        new MbDropSimTop(new MbDropTestDriver),
        Utils.writeVerilatorSimScript,
        Utils.buildRoot / "UcieTL_mainband_drop_under_midtraffic_stall"
      )
    }
  }
}
