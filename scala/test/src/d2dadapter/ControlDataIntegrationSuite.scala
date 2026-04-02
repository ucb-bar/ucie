package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

/**
  * Test-only forward-path control/data integration harness.
  *
  * This preserves the real LinkManagementController and D2DMainbandModule logic
  * while avoiding full-system sideband/stall-handler environment complexity.
  */
final class ControlDataIntegrationHarness(
  val fdiParams: FdiParams,
  val rdiParams: RdiParams,
  val sbParams: SidebandParams
) extends Module {
  val io = IO(new Bundle {
    // Forward raw traffic ingress (protocol -> adapter)
    val fdi_lp_irdy = Input(Bool())
    val fdi_lp_valid = Input(Bool())
    val fdi_lp_data = Input(Bits((8 * fdiParams.width).W))
    val fdi_lp_stream = Input(new ProtoStream())
    val fdi_pl_trdy = Output(Bool())

    // Forward raw traffic egress (adapter -> physical)
    // Note: this boundary has data/valid/irdy/trdy but no stream metadata field.
    val rdi_lp_irdy = Output(Bool())
    val rdi_lp_valid = Output(Bool())
    val rdi_lp_data = Output(Bits((8 * rdiParams.width).W))
    val rdi_pl_trdy = Input(Bool())

    // Reverse path is kept controllable/observable for harness completeness,
    // but is not deeply verified by this forward-path integration suite.
    val rdi_pl_valid = Input(Bool())
    val rdi_pl_data = Input(Bits((8 * rdiParams.width).W))
    val fdi_pl_valid = Output(Bool())
    val fdi_pl_data = Output(Bits((8 * fdiParams.width).W))

    // Control/FSM inputs
    val fdi_lp_state_req = Input(PhyStateReq())
    val fdi_lp_linkerror = Input(Bool())
    val fdi_lp_rx_active_sts = Input(Bool())
    val fdi_lp_stallack = Input(Bool())
    val rdi_pl_state_sts = Input(PhyState())
    val rdi_pl_inband_pres = Input(Bool())
    val sb_rcv = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
    val sb_rdy = Input(Bool())

    // Control/FSM observability
    val link_state = Output(PhyState())
    val fdi_pl_rx_active_req = Output(Bool())
    val fdi_pl_inband_pres = Output(Bool())
    val rdi_lp_state_req = Output(PhyStateReq())
    val linkmgmt_stallreq = Output(Bool())
    val fdi_pl_stallreq = Output(Bool())
    val linkmgmt_stalldone = Output(Bool())
    val mainband_stalldone = Output(Bool())
    val sb_snd = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
  })

  val linkManager = Module(new LinkManagementController(fdiParams, rdiParams, sbParams))
  val fdiStallHandler = Module(new FDIStallHandler())
  val mainband = Module(new D2DMainbandModule(fdiParams, rdiParams, sbParams))

  // Link management I/O
  linkManager.io.fdi_lp_state_req := io.fdi_lp_state_req
  linkManager.io.fdi_lp_linkerror := io.fdi_lp_linkerror
  linkManager.io.fdi_lp_rx_active_sts := io.fdi_lp_rx_active_sts
  linkManager.io.rdi_pl_state_sts := io.rdi_pl_state_sts
  linkManager.io.rdi_pl_inband_pres := io.rdi_pl_inband_pres
  linkManager.io.sb_rcv := io.sb_rcv
  linkManager.io.sb_rdy := io.sb_rdy

  // Test default knobs
  linkManager.io.cycles_1us := 100.U
  linkManager.io.parity_tx_sw_en := false.B
  linkManager.io.parity_rx_sw_en := false.B

  // Match top-level stall handshake on protocol side.
  fdiStallHandler.io.linkmgmt_stallreq := linkManager.io.linkmgmt_stallreq
  fdiStallHandler.io.fdi_lp_stallack := io.fdi_lp_stallack
  linkManager.io.linkmgmt_stalldone := fdiStallHandler.io.linkmgmt_stalldone

  // Keep physical-side stall trigger idle in this minimal integration harness.
  mainband.io.mainband_stallreq := false.B

  // Datapath traffic wiring
  mainband.io.fdi_lp_irdy := io.fdi_lp_irdy
  mainband.io.fdi_lp_valid := io.fdi_lp_valid
  mainband.io.fdi_lp_data := io.fdi_lp_data
  mainband.io.fdi_lp_stream := io.fdi_lp_stream
  io.fdi_pl_trdy := mainband.io.fdi_pl_trdy

  io.rdi_lp_irdy := mainband.io.rdi_lp_irdy
  io.rdi_lp_valid := mainband.io.rdi_lp_valid
  io.rdi_lp_data := mainband.io.rdi_lp_data
  mainband.io.rdi_pl_trdy := io.rdi_pl_trdy

  mainband.io.rdi_pl_valid := io.rdi_pl_valid
  mainband.io.rdi_pl_data := io.rdi_pl_data
  io.fdi_pl_valid := mainband.io.fdi_pl_valid
  io.fdi_pl_data := mainband.io.fdi_pl_data

  // Control -> datapath state coupling (as in top adapter)
  mainband.io.d2d_state := linkManager.io.fdi_pl_state_sts

  // Disable parity perturbation for deterministic transport checks
  mainband.io.parity_insert := false.B
  mainband.io.parity_data := 0.U
  mainband.io.parity_check := false.B

  // Expose useful control observability
  io.link_state := linkManager.io.fdi_pl_state_sts
  io.fdi_pl_rx_active_req := linkManager.io.fdi_pl_rx_active_req
  io.fdi_pl_inband_pres := linkManager.io.fdi_pl_inband_pres
  io.rdi_lp_state_req := linkManager.io.rdi_lp_state_req
  io.linkmgmt_stallreq := linkManager.io.linkmgmt_stallreq
  io.fdi_pl_stallreq := fdiStallHandler.io.fdi_pl_stallreq
  io.linkmgmt_stalldone := fdiStallHandler.io.linkmgmt_stalldone
  io.mainband_stalldone := mainband.io.mainband_stalldone
  io.sb_snd := linkManager.io.sb_snd
}

final class ControlFdiIngressDriver(
  dut: ControlDataIntegrationHarness,
  beats: Seq[RawBeat],
  injectedSourceHoldoff: () => Boolean = () => false,
  gapCyclesBeforeBeat: Int => Int = _ => 0
) {
  private var beatIdx: Int = 0
  private var activeBeat: Option[RawBeat] = None
  private var preSendGapRemaining: Int = 0

  var acceptedCount: Long = 0L

  def isDone: Boolean = beatIdx >= beats.length && activeBeat.isEmpty
  def pendingBeat: Option[RawBeat] = activeBeat
  def pendingBeatIndex: Option[Int] = activeBeat.map(_ => beatIdx)

  private def loadNextIfNeeded(): Unit = {
    if (activeBeat.isEmpty && beatIdx < beats.length) {
      activeBeat = Some(beats(beatIdx))
      preSendGapRemaining = math.max(0, gapCyclesBeforeBeat(beatIdx))
    }
  }

  /** Drives forward ingress; holds beat stable until accepted. */
  def driveOneCycle(): Unit = {
    loadNextIfNeeded()

    activeBeat.foreach { beat =>
      dut.io.fdi_lp_data.poke(beat.data.U)
      RawStreamSignalCodec.pokeStreamFromId(dut.io.fdi_lp_stream, beat.streamId)
    }

    val gatedByScheduledGap = preSendGapRemaining > 0
    if (gatedByScheduledGap) preSendGapRemaining -= 1

    if (injectedSourceHoldoff() || gatedByScheduledGap) {
      dut.io.fdi_lp_valid.poke(false.B)
      dut.io.fdi_lp_irdy.poke(false.B)
      return
    }

    activeBeat match {
      case Some(_) =>
        dut.io.fdi_lp_valid.poke(true.B)
        dut.io.fdi_lp_irdy.poke(true.B)
      case None =>
        dut.io.fdi_lp_valid.poke(false.B)
        dut.io.fdi_lp_irdy.poke(false.B)
    }
  }

  def onAccepted(): Unit = {
    if (activeBeat.nonEmpty) {
      acceptedCount += 1
      beatIdx += 1
      activeBeat = None
    }
  }
}

final class ControlIngressTracker(dut: ControlDataIntegrationHarness) {
  private var nextSeq: Long = 0L
  var acceptedCount: Long = 0L

  /**
    * Forward ingress acceptance at fdi_lp_* boundary:
    * accepted = fdi_lp_valid && fdi_lp_irdy && fdi_pl_trdy
    */
  def observeForNextEdge(edgeCycle: Long): Option[AcceptedBeat] = {
    val accepted = dut.io.fdi_lp_valid.peek().litToBoolean &&
      dut.io.fdi_lp_irdy.peek().litToBoolean &&
      dut.io.fdi_pl_trdy.peek().litToBoolean

    if (accepted) {
      Some(AcceptedBeat(
        seq = nextSeq,
        data = dut.io.fdi_lp_data.peek().litValue,
        streamId = RawStreamSignalCodec.peekStreamId(dut.io.fdi_lp_stream),
        cycleAccepted = edgeCycle
      ))
    } else None
  }

  def commitAfterEdge(
    obs: Option[AcceptedBeat],
    expectedQ: mutable.Queue[AcceptedBeat],
    enqueueExpected: Boolean
  ): Boolean = {
    obs.foreach { beat =>
      if (enqueueExpected) expectedQ.enqueue(beat)
      nextSeq += 1
      acceptedCount += 1
    }
    obs.nonEmpty
  }
}

final class ControlEgressMonitor(
  dut: ControlDataIntegrationHarness,
  onObserved: AcceptedBeat => Unit,
  egressStreamId: () => Int = () => RawStreamIds.UnknownStreamId
) {
  private var nextSeq: Long = 0L
  var observedCount: Long = 0L

  /**
    * Forward egress transfer at rdi_lp_* boundary:
    * transferred = rdi_lp_valid && rdi_lp_irdy && rdi_pl_trdy
    */
  def observeForNextEdge(edgeCycle: Long): Option[AcceptedBeat] = {
    val transferred = dut.io.rdi_lp_valid.peek().litToBoolean &&
      dut.io.rdi_lp_irdy.peek().litToBoolean &&
      dut.io.rdi_pl_trdy.peek().litToBoolean

    if (transferred) {
      Some(AcceptedBeat(
        seq = nextSeq,
        data = dut.io.rdi_lp_data.peek().litValue,
        streamId = egressStreamId(),
        cycleAccepted = edgeCycle
      ))
    } else None
  }

  def commitAfterEdge(obs: Option[AcceptedBeat]): Unit = {
    obs.foreach { beat =>
      onObserved(beat)
      nextSeq += 1
      observedCount += 1
    }
  }
}

final class ForwardSourceStabilityChecker(
  dut: ControlDataIntegrationHarness,
  driver: ControlFdiIngressDriver
) {
  private var prevOutstanding: Option[(Int, BigInt, Int)] = None

  /**
    * Invariant checked each cycle:
    * while the same pending beat remains unaccepted, driven fdi_lp_data and
    * fdi_lp_stream stay stable.
    */
  def check(cycle: Long, boundaryCrossed: Boolean = false, boundaryName: String = "none"): Unit = {
    val linkState = dut.io.link_state.peek().litValue
    (driver.pendingBeatIndex, driver.pendingBeat) match {
      case (Some(idx), Some(beat)) =>
        val currData = dut.io.fdi_lp_data.peek().litValue
        val currStream = RawStreamSignalCodec.peekStreamId(dut.io.fdi_lp_stream)
        assert(
          currData == beat.data,
          s"Cycle $cycle state=0x${linkState.toString(16)} idx=$idx boundary=$boundaryName crossed=$boundaryCrossed: " +
            s"source data mismatch driven=0x${currData.toString(16)} pending=0x${beat.data.toString(16)}"
        ) // SPEC-DERIVED
        assert(
          currStream == beat.streamId,
          s"Cycle $cycle state=0x${linkState.toString(16)} idx=$idx boundary=$boundaryName crossed=$boundaryCrossed: " +
            s"source stream mismatch driven=0x${currStream.toHexString} pending=0x${beat.streamId.toHexString}"
        ) // SPEC-DERIVED

        prevOutstanding match {
          case Some((prevIdx, prevData, prevStream)) if prevIdx == idx =>
            assert(
              currData == prevData,
              s"Cycle $cycle state=0x${linkState.toString(16)} idx=$idx boundary=$boundaryName crossed=$boundaryCrossed: " +
                s"source data changed while unaccepted (prev=0x${prevData.toString(16)} now=0x${currData.toString(16)})"
            ) // SPEC-DERIVED
            assert(
              currStream == prevStream,
              s"Cycle $cycle state=0x${linkState.toString(16)} idx=$idx boundary=$boundaryName crossed=$boundaryCrossed: " +
                s"source stream changed while unaccepted (prev=0x${prevStream.toHexString} now=0x${currStream.toHexString})"
            ) // SPEC-DERIVED
          case _ =>
        }
        prevOutstanding = Some((idx, currData, currStream))

      case _ =>
        prevOutstanding = None
    }
  }
}

/**
  * Forward-path control/data integration tests.
  *
  * Scope: verify interaction between control-state disruptions and forward
  * traffic transport (`fdi_lp_*` -> mainband -> `rdi_lp_*`), while keeping
  * reverse path controllable/observable but not deeply validated here.
  */
class ControlDataIntegrationSuite extends AnyFlatSpec with ChiselScalatestTester {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams

  private def linkStateHex(dut: ControlDataIntegrationHarness): String =
    s"0x${dut.io.link_state.peek().litValue.toString(16)}"

  private def initDut(dut: ControlDataIntegrationHarness): Unit = {
    // Integration tests can run long while queues drain around disruptions.
    dut.clock.setTimeout(0)

    // Forward path defaults
    dut.io.fdi_lp_valid.poke(false.B)
    dut.io.fdi_lp_irdy.poke(false.B)
    dut.io.fdi_lp_data.poke(0.U)
    RawStreamSignalCodec.pokeStreamFromId(dut.io.fdi_lp_stream, RawStreamIds.Stack0Streaming)
    dut.io.rdi_pl_trdy.poke(true.B)

    // Reverse path kept idle
    dut.io.rdi_pl_valid.poke(false.B)
    dut.io.rdi_pl_data.poke(0.U)

    // Control defaults
    dut.io.fdi_lp_state_req.poke(PhyStateReq.nop)
    dut.io.fdi_lp_linkerror.poke(false.B)
    dut.io.fdi_lp_rx_active_sts.poke(false.B)
    dut.io.fdi_lp_stallack.poke(false.B)
    dut.io.rdi_pl_state_sts.poke(PhyState.reset)
    dut.io.rdi_pl_inband_pres.poke(false.B)
    dut.io.sb_rcv.poke(SideBandMessage.NOP)
    dut.io.sb_rdy.poke(false.B)

    dut.clock.step(2)
  }

  private def waitUntil(
    dut: ControlDataIntegrationHarness,
    maxCycles: Int,
    reason: String
  )(cond: => Boolean): Unit = {
    var waited = 0
    while (waited < maxCycles && !cond) {
      dut.clock.step(1)
      waited += 1
    }
    assert(
      cond,
      s"Timeout waiting for: $reason after $maxCycles cycles. " +
        s"state=${dut.io.link_state.peek().litValue} " +
        s"sb_snd=0x${dut.io.sb_snd.peek().litValue.toString(16)}"
    ) // UNKNOWN: needs spec/RTL audit
  }

  private def pulseSbReady(dut: ControlDataIntegrationHarness): Unit = {
    dut.io.sb_rdy.poke(true.B)
    dut.clock.step(1)
    dut.io.sb_rdy.poke(false.B)
  }

  private def pulseSbReceive(dut: ControlDataIntegrationHarness, msg: UInt): Unit = {
    dut.io.sb_rcv.poke(msg)
    dut.clock.step(1)
    dut.io.sb_rcv.poke(SideBandMessage.NOP)
  }

  private def driveToActive(dut: ControlDataIntegrationHarness): Unit = {
    dut.io.rdi_pl_inband_pres.poke(true.B)
    waitUntil(dut, maxCycles = 40, reason = "RDI request ACTIVE during bring-up") {
      dut.io.rdi_lp_state_req.peek().litValue == PhyStateReq.active.litValue
    }

    dut.io.rdi_pl_state_sts.poke(PhyState.active)

    waitUntil(dut, maxCycles = 40, reason = "ADV_CAP sideband send") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.ADV_CAP.litValue
    }
    pulseSbReceive(dut, SideBandMessage.ADV_CAP)
    pulseSbReady(dut)

    waitUntil(dut, maxCycles = 40, reason = "FDI inband present high") {
      dut.io.fdi_pl_inband_pres.peek().litToBoolean
    }

    dut.io.fdi_lp_state_req.poke(PhyStateReq.nop)
    dut.clock.step(1)
    dut.io.fdi_lp_state_req.poke(PhyStateReq.active)
    dut.clock.step(1)

    waitUntil(dut, maxCycles = 40, reason = "REQ_ACTIVE sideband send") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.REQ_ACTIVE.litValue
    }
    pulseSbReady(dut)
    pulseSbReceive(dut, SideBandMessage.REQ_ACTIVE)

    waitUntil(dut, maxCycles = 40, reason = "fdi_pl_rx_active_req high") {
      dut.io.fdi_pl_rx_active_req.peek().litToBoolean
    }

    pulseSbReceive(dut, SideBandMessage.RSP_ACTIVE)
    dut.io.fdi_lp_rx_active_sts.poke(true.B)

    waitUntil(dut, maxCycles = 40, reason = "RSP_ACTIVE sideband send") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.RSP_ACTIVE.litValue
    }
    pulseSbReady(dut)

    waitUntil(dut, maxCycles = 40, reason = "Link state ACTIVE") {
      dut.io.link_state.peek().litValue == PhyState.active.litValue
    }
  }

  private def recoverFromLinkErrorToActive(dut: ControlDataIntegrationHarness): Unit = {
    // Minimal LINKERROR exit conditions in this RTL:
    // 1) keep RDI reporting LINKERROR long enough for the LINKERROR state logic
    // 2) ensure rx_deactive (lp_rx_active_sts low, while pl_rx_active_req is low in LINKERROR)
    dut.io.fdi_lp_rx_active_sts.poke(false.B)
    dut.io.rdi_pl_state_sts.poke(PhyState.linkError)

    waitUntil(dut, maxCycles = 40, reason = "LINKERROR -> RESET recovery") {
      dut.io.link_state.peek().litValue == PhyState.reset.litValue
    }

    // Do not hold LINKERROR status once RESET is reached, otherwise FSM can re-enter LINKERROR.
    dut.io.rdi_pl_state_sts.poke(PhyState.reset)
    dut.io.rdi_pl_inband_pres.poke(false.B)
    dut.clock.step(1)

    // Re-run normal reset->active bring-up.
    driveToActive(dut)
  }

  private def runForwardTrafficToCompletion(
    dut: ControlDataIntegrationHarness,
    beats: Seq[RawBeat],
    maxCycles: Int = 6000,
    egressReadyFn: Long => Boolean = _ => true,
    injectedSourceHoldoffFn: Long => Boolean = _ => false,
    gapCyclesBeforeBeat: Int => Int = _ => 0,
    onObserved: AcceptedBeat => Unit = _ => ()
  ): Unit = {
    val expectedQ = mutable.Queue.empty[AcceptedBeat]
    // Stream-ID preservation cannot be checked here: rdi_lp_* has no stream field.
    val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = false)
    var cycleRef = 0L
    var maxExpectedQueueDepth = 0
    def updateMaxExpectedQueueDepth(): Unit = {
      maxExpectedQueueDepth = math.max(maxExpectedQueueDepth, expectedQ.size)
    }

    val driver = new ControlFdiIngressDriver(
      dut = dut,
      beats = beats,
      injectedSourceHoldoff = () => injectedSourceHoldoffFn(cycleRef),
      gapCyclesBeforeBeat = gapCyclesBeforeBeat
    )
    val ingressTracker = new ControlIngressTracker(dut)
    val egressMonitor = new ControlEgressMonitor(
      dut = dut,
      onObserved = beat => {
        scoreboard.onObserved(beat)
        onObserved(beat)
      },
      // No observable stream metadata exists on forward egress boundary rdi_lp_*.
      egressStreamId = () => RawStreamIds.UnknownStreamId
    )
    val sourceStabilityChecker = new ForwardSourceStabilityChecker(dut, driver)

    while (cycleRef < maxCycles && (!driver.isDone || expectedQ.nonEmpty)) {
      dut.io.rdi_pl_trdy.poke(egressReadyFn(cycleRef).B)
      driver.driveOneCycle()
      sourceStabilityChecker.check(cycleRef)

      val ingressObs = ingressTracker.observeForNextEdge(cycleRef)
      val egressObs = egressMonitor.observeForNextEdge(cycleRef)
      dut.clock.step(1)

      val accepted = ingressTracker.commitAfterEdge(ingressObs, expectedQ, enqueueExpected = true)
      if (accepted) driver.onAccepted()
      updateMaxExpectedQueueDepth()
      egressMonitor.commitAfterEdge(egressObs)

      cycleRef += 1
    }

    assert(cycleRef < maxCycles, s"Timeout at $maxCycles cycles") // UNKNOWN: needs spec/RTL audit

    var drain = 0
    while (drain < 16 && expectedQ.nonEmpty) {
      dut.io.rdi_pl_trdy.poke(true.B)
      driver.driveOneCycle()
      sourceStabilityChecker.check(cycleRef)

      val ingressObs = ingressTracker.observeForNextEdge(cycleRef)
      val egressObs = egressMonitor.observeForNextEdge(cycleRef)
      dut.clock.step(1)

      val accepted = ingressTracker.commitAfterEdge(ingressObs, expectedQ, enqueueExpected = true)
      if (accepted) driver.onAccepted()
      updateMaxExpectedQueueDepth()
      egressMonitor.commitAfterEdge(egressObs)

      cycleRef += 1
      drain += 1
    }

    scoreboard.finishAndAssert(
      acceptedInputCount = ingressTracker.acceptedCount,
      maxExpectedQueueDepth = Some(maxExpectedQueueDepth)
    ) // SPEC-DERIVED
  }

  behavior of "ControlDataIntegrationSuite"

  it should "active traffic + retrain request: assert stall, stop acceptance after boundary edge, and enter RETRAIN" in {
    test(new ControlDataIntegrationHarness(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)

      val beats = (0 until 256).map { i =>
        RawBeat(
          data = BigInt("5000000000000000", 16) + BigInt(i),
          streamId = if ((i & 1) == 0) RawStreamIds.Stack0Streaming else RawStreamIds.Stack1Streaming
        )
      }

      val expectedQ = mutable.Queue.empty[AcceptedBeat]
      // Stream-ID cannot be observed on rdi_lp_* forward egress in this RTL.
      val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = false)
      var protocolHoldoff = false
      val driver = new ControlFdiIngressDriver(
        dut = dut,
        beats = beats,
        injectedSourceHoldoff = () => protocolHoldoff
      )
      val ingressTracker = new ControlIngressTracker(dut)
      val egressMonitor = new ControlEgressMonitor(
        dut,
        scoreboard.onObserved,
        egressStreamId = () => RawStreamIds.UnknownStreamId
      )
      val sourceStabilityChecker = new ForwardSourceStabilityChecker(dut, driver)

      var cycle = 0L
      val triggerCycle = 40L
      var retrainRequested = false
      var stallReqSeen = false
      var stallBoundaryCycle: Option[Long] = None
      var retrainSeen = false
      var acceptedAfterBoundary = 0L
      var acceptedOnBoundaryEdge = 0L
      var firstAcceptedAfterBoundaryDetail: Option[String] = None
      var fdiPlStallReqSeen = false
      var stallAckPulseRemaining = 0

      while (cycle < 1200 && (!retrainSeen || cycle < triggerCycle + 120 || expectedQ.nonEmpty)) {
        if (cycle == triggerCycle) {
          dut.io.rdi_pl_state_sts.poke(PhyState.retrain)
          dut.io.fdi_lp_rx_active_sts.poke(false.B)
          retrainRequested = true
        }

        val fdiPlStallReqNow = dut.io.fdi_pl_stallreq.peek().litToBoolean
        val stallReqNow = dut.io.linkmgmt_stallreq.peek().litToBoolean
        val stallDoneNow = dut.io.linkmgmt_stalldone.peek().litToBoolean

        // Minimal partner model for this test:
        // when fdi_pl_stallreq is first observed, stop sourcing and emit a
        // one-cycle stallack pulse. This is one valid environment timing model,
        // not an exhaustive proof over all legal stallack timings.
        if (fdiPlStallReqNow && !fdiPlStallReqSeen) {
          fdiPlStallReqSeen = true
          protocolHoldoff = true
          stallAckPulseRemaining = 1
        }
        dut.io.fdi_lp_stallack.poke((stallAckPulseRemaining > 0).B)

        if (stallReqNow) stallReqSeen = true
        if (stallReqNow && stallDoneNow && stallBoundaryCycle.isEmpty) {
          stallBoundaryCycle = Some(cycle)
        }

        dut.io.rdi_pl_trdy.poke(true.B)
        driver.driveOneCycle()
        sourceStabilityChecker.check(
          cycle = cycle,
          boundaryCrossed = stallBoundaryCycle.exists(cycle >= _),
          boundaryName = "retrain_stall_boundary"
        )

        val ingressObs = ingressTracker.observeForNextEdge(cycle)
        val egressObs = egressMonitor.observeForNextEdge(cycle)
        dut.clock.step(1)

        val accepted = ingressTracker.commitAfterEdge(ingressObs, expectedQ, enqueueExpected = true)
        if (accepted) {
          driver.onAccepted()
          val beat = ingressObs.get
          if (stallBoundaryCycle.contains(cycle)) {
            // Boundary and acceptance sampled on the same edge are not strictly ordered.
            acceptedOnBoundaryEdge += 1
          } else if (stallBoundaryCycle.exists(cycle > _)) {
            acceptedAfterBoundary += 1
            if (firstAcceptedAfterBoundaryDetail.isEmpty) {
              firstAcceptedAfterBoundaryDetail = Some(
                s"cycle=$cycle state=${linkStateHex(dut)} seq=${beat.seq} " +
                  s"data=0x${beat.data.toString(16)} boundaryCycle=${stallBoundaryCycle.get}"
              )
            }
          }
        }
        egressMonitor.commitAfterEdge(egressObs)
        if (stallAckPulseRemaining > 0) stallAckPulseRemaining -= 1

        if (dut.io.link_state.peek().litValue == PhyState.retrain.litValue) {
          retrainSeen = true
        }
        cycle += 1
      }

      assert(retrainRequested, "Retrain trigger was not issued") // RTL-DERIVED
      assert(fdiPlStallReqSeen, "fdi_pl_stallreq was never asserted") // SPEC-DERIVED
      assert(stallReqSeen, "linkmgmt_stallreq was never asserted") // SPEC-DERIVED
      assert(stallBoundaryCycle.nonEmpty, "stall boundary (linkmgmt_stallreq && linkmgmt_stalldone) was never observed") // SPEC-DERIVED
      assert(retrainSeen, "FSM did not transition to RETRAIN") // SPEC-DERIVED
      assert(
        acceptedAfterBoundary == 0L,
        s"Observed $acceptedAfterBoundary accepted beats after enforced retrain stop boundary; " +
          s"boundaryCycle=${stallBoundaryCycle.get} acceptedOnBoundaryEdge=$acceptedOnBoundaryEdge " +
          s"firstViolation=${firstAcceptedAfterBoundaryDetail.getOrElse("none")}"
      ) // UNKNOWN: needs spec/RTL audit
      assert(scoreboard.extraOutputCount == 0L, s"Observed ${scoreboard.extraOutputCount} extra output beats") // SPEC-DERIVED
      assert(scoreboard.mismatchCount == 0L, s"Observed ${scoreboard.mismatchCount} output mismatches") // SPEC-DERIVED
    }
  }

  it should "active traffic + linkerror: enter LINKERROR, stop acceptance after boundary edge, and emit no extra outputs" in {
    test(new ControlDataIntegrationHarness(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)

      val beats = (0 until 256).map { i =>
        RawBeat(
          data = BigInt("6000000000000000", 16) + BigInt(i),
          streamId = if ((i & 1) == 0) RawStreamIds.Stack0Streaming else RawStreamIds.Stack1Streaming
        )
      }

      val expectedQ = mutable.Queue.empty[AcceptedBeat]
      // Stream-ID cannot be observed on rdi_lp_* forward egress in this RTL.
      val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = false)
      var protocolHoldoff = false
      val driver = new ControlFdiIngressDriver(
        dut = dut,
        beats = beats,
        injectedSourceHoldoff = () => protocolHoldoff
      )
      val ingressTracker = new ControlIngressTracker(dut)
      val egressMonitor = new ControlEgressMonitor(
        dut,
        scoreboard.onObserved,
        egressStreamId = () => RawStreamIds.UnknownStreamId
      )
      val sourceStabilityChecker = new ForwardSourceStabilityChecker(dut, driver)

      var cycle = 0L
      val triggerCycle = 40L
      var linkErrorRequested = false
      var linkErrorSeen = false
      var stopBoundaryCycle: Option[Long] = None
      var acceptedAfterBoundary = 0L
      var acceptedOnBoundaryEdge = 0L
      var firstAcceptedAfterBoundaryDetail: Option[String] = None

      while (cycle < 1200 && (!linkErrorSeen || cycle < triggerCycle + 120 || expectedQ.nonEmpty)) {
        if (cycle == triggerCycle) {
          dut.io.rdi_pl_state_sts.poke(PhyState.linkError)
          dut.io.fdi_lp_rx_active_sts.poke(false.B)
          linkErrorRequested = true
        }

        // No protocol stall handshake expected in pure LINKERROR path.
        dut.io.fdi_lp_stallack.poke(false.B)
        // Keep physical egress ready to drain any in-flight accepted beats.
        dut.io.rdi_pl_trdy.poke(true.B)

        val inLinkError = dut.io.link_state.peek().litValue == PhyState.linkError.litValue
        if (inLinkError) linkErrorSeen = true
        // Protocol model used in this test: once LINKERROR is visible, stop
        // sourcing new traffic from the next decision point.
        if (inLinkError && stopBoundaryCycle.isEmpty) {
          protocolHoldoff = true
          stopBoundaryCycle = Some(cycle)
        }

        driver.driveOneCycle()
        sourceStabilityChecker.check(
          cycle = cycle,
          boundaryCrossed = stopBoundaryCycle.exists(cycle >= _),
          boundaryName = "linkerror_stop_boundary"
        )

        val ingressObs = ingressTracker.observeForNextEdge(cycle)
        val egressObs = egressMonitor.observeForNextEdge(cycle)
        dut.clock.step(1)

        val accepted = ingressTracker.commitAfterEdge(ingressObs, expectedQ, enqueueExpected = true)
        if (accepted) {
          driver.onAccepted()
          val beat = ingressObs.get
          if (stopBoundaryCycle.contains(cycle)) {
            // Boundary and acceptance sampled on the same edge are not strictly ordered.
            acceptedOnBoundaryEdge += 1
          } else if (stopBoundaryCycle.exists(cycle > _)) {
            acceptedAfterBoundary += 1
            if (firstAcceptedAfterBoundaryDetail.isEmpty) {
              firstAcceptedAfterBoundaryDetail = Some(
                s"cycle=$cycle state=${linkStateHex(dut)} seq=${beat.seq} " +
                  s"data=0x${beat.data.toString(16)} boundaryCycle=${stopBoundaryCycle.get}"
              )
            }
          }
        }
        egressMonitor.commitAfterEdge(egressObs)

        cycle += 1
      }

      assert(linkErrorRequested, "LinkError trigger was not issued") // RTL-DERIVED
      assert(linkErrorSeen, "FSM did not enter LINKERROR") // SPEC-DERIVED
      assert(stopBoundaryCycle.nonEmpty, "Did not observe stop boundary after LINKERROR entry") // UNKNOWN: needs spec/RTL audit
      assert(
        acceptedAfterBoundary == 0L,
        s"Observed $acceptedAfterBoundary accepted beats after enforced LINKERROR stop boundary; " +
          s"boundaryCycle=${stopBoundaryCycle.get} acceptedOnBoundaryEdge=$acceptedOnBoundaryEdge " +
          s"firstViolation=${firstAcceptedAfterBoundaryDetail.getOrElse("none")}"
      ) // UNKNOWN: needs spec/RTL audit
      assert(scoreboard.extraOutputCount == 0L, s"Observed ${scoreboard.extraOutputCount} extra output beats") // SPEC-DERIVED
      assert(scoreboard.mismatchCount == 0L, s"Observed ${scoreboard.mismatchCount} output mismatches") // SPEC-DERIVED
    }
  }

  it should "recovery to ACTIVE + resumed traffic: restart cleanly without stale-beat leakage" in {
    test(new ControlDataIntegrationHarness(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)

      // Phase A: baseline active traffic before disruption.
      val preBeats = (0 until 24).map(i => RawBeat(BigInt("7000000000000000", 16) + BigInt(i), RawStreamIds.Stack0Streaming))
      runForwardTrafficToCompletion(dut, preBeats, maxCycles = 3000)

      // Enter LINKERROR and then recover to RESET->ACTIVE.
      dut.io.rdi_pl_state_sts.poke(PhyState.linkError)
      dut.io.fdi_lp_rx_active_sts.poke(false.B)
      waitUntil(dut, maxCycles = 40, reason = "FSM enters LINKERROR") {
        dut.io.link_state.peek().litValue == PhyState.linkError.litValue
      }
      recoverFromLinkErrorToActive(dut)
      dut.io.link_state.expect(PhyState.active) // SPEC-DERIVED

      // Before resuming ingress, ensure no stale output appears.
      val leakProbeExpectedQ = mutable.Queue.empty[AcceptedBeat]
      val leakProbeScoreboard = new Scoreboard(leakProbeExpectedQ, checkStreamId = false)
      val leakProbeMonitor = new ControlEgressMonitor(dut, leakProbeScoreboard.onObserved)
      for (cycle <- 0 until 8) {
        dut.io.fdi_lp_valid.poke(false.B)
        dut.io.fdi_lp_irdy.poke(false.B)
        dut.io.rdi_pl_trdy.poke(true.B)
        val obs = leakProbeMonitor.observeForNextEdge(cycle.toLong)
        dut.clock.step(1)
        leakProbeMonitor.commitAfterEdge(obs)
      }
      assert(
        leakProbeScoreboard.extraOutputCount == 0L,
        s"Observed ${leakProbeScoreboard.extraOutputCount} stale output beats before traffic resumed"
      ) // SPEC-DERIVED

      // Phase B: resumed traffic must be clean.
      val postBeats = (0 until 32).map(i => RawBeat(BigInt("7100000000000000", 16) + BigInt(i), RawStreamIds.Stack1Streaming))
      runForwardTrafficToCompletion(dut, postBeats, maxCycles = 4000)
    }
  }

  it should "buffered-beat boundary: marker beat is emitted at most once across disruption and recovery" in {
    test(new ControlDataIntegrationHarness(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)

      val marker = BigInt("DEADBEEF00000001", 16)
      val preBeats = Seq(
        RawBeat(marker, RawStreamIds.Stack0Streaming),
        RawBeat(BigInt("DEADBEEF00000002", 16), RawStreamIds.Stack0Streaming),
        RawBeat(BigInt("DEADBEEF00000003", 16), RawStreamIds.Stack0Streaming)
      )

      val expectedQ = mutable.Queue.empty[AcceptedBeat]
      val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = false)
      val driver = new ControlFdiIngressDriver(dut, preBeats)
      val ingressTracker = new ControlIngressTracker(dut)
      val sourceStabilityChecker = new ForwardSourceStabilityChecker(dut, driver)

      var markerObsCount = 0
      val egressMonitor = new ControlEgressMonitor(
        dut,
        onObserved = beat => {
          scoreboard.onObserved(beat)
          if (beat.data == marker) markerObsCount += 1
        }
      )

      var cycle = 0L
      var markerAccepted = false
      var disruptionIssued = false
      var linkErrorSeen = false

      while (cycle < 600 && (!linkErrorSeen || cycle < 120)) {
        dut.io.rdi_pl_trdy.poke(true.B)
        driver.driveOneCycle()
        sourceStabilityChecker.check(cycle, boundaryCrossed = disruptionIssued, boundaryName = "marker_disruption")

        val ingressObs = ingressTracker.observeForNextEdge(cycle)
        val egressObs = egressMonitor.observeForNextEdge(cycle)
        dut.clock.step(1)

        val accepted = ingressTracker.commitAfterEdge(ingressObs, expectedQ, enqueueExpected = true)
        if (accepted) {
          val acceptedData = ingressObs.get.data
          driver.onAccepted()
          if (acceptedData == marker && !disruptionIssued) {
            markerAccepted = true
            // Disrupt right after marker acceptance to hit the boundary condition.
            dut.io.rdi_pl_state_sts.poke(PhyState.linkError)
            dut.io.fdi_lp_rx_active_sts.poke(false.B)
            disruptionIssued = true
          }
        }
        egressMonitor.commitAfterEdge(egressObs)

        if (dut.io.link_state.peek().litValue == PhyState.linkError.litValue) {
          linkErrorSeen = true
        }
        cycle += 1
      }

      assert(markerAccepted, "Marker beat was never accepted before disruption") // RTL-DERIVED
      assert(disruptionIssued, "Boundary disruption was not issued") // RTL-DERIVED
      assert(linkErrorSeen, "FSM did not enter LINKERROR after boundary disruption") // SPEC-DERIVED
      assert(scoreboard.extraOutputCount == 0L, s"Observed ${scoreboard.extraOutputCount} extra output beats before recovery") // SPEC-DERIVED
      assert(scoreboard.mismatchCount == 0L, s"Observed ${scoreboard.mismatchCount} output mismatches before recovery") // SPEC-DERIVED

      // Recover and resume with non-marker traffic.
      recoverFromLinkErrorToActive(dut)
      dut.io.link_state.expect(PhyState.active) // SPEC-DERIVED

      val postBeats = (0 until 24).map(i => RawBeat(BigInt("7200000000000000", 16) + BigInt(i), RawStreamIds.Stack1Streaming))
      runForwardTrafficToCompletion(
        dut = dut,
        beats = postBeats,
        maxCycles = 4000,
        onObserved = beat => if (beat.data == marker) markerObsCount += 1
      )

      // Boundary policy in this test: marker may be dropped or delivered once, but never duplicated.
      assert(
        markerObsCount <= 1,
        s"Marker beat observed $markerObsCount times across disruption/recovery (expected at most once)"
      ) // UNKNOWN: needs spec/RTL audit
    }
  }
}
