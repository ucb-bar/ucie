package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

/**
  * Stall / retrain coordination integration tests.
  *
  * Uses the existing control+data integration harness and traffic infrastructure
  * to verify forward-path stall/retrain coordination under live traffic.
  *
  * Scope:
  * - forward ingress/egress transport behavior under retrain-driven stall control
  * - top-level observable control-state and stall-handshake behavior
  * - no full bidirectional protocol/retrain-exit specification proof
  */
class RetrainStallCoordinationSuite extends AnyFlatSpec with ChiselSim {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams

  private final class ForwardTrafficEnv(
    val driver: ControlFdiIngressDriver,
    val ingressTracker: ControlIngressTracker,
    val egressMonitor: ControlEgressMonitor,
    val sourceStabilityChecker: ForwardSourceStabilityChecker,
    val expectedQ: mutable.Queue[AcceptedBeat],
    val scoreboard: Scoreboard,
    var maxExpectedQueueDepth: Int
  )

  private def initDut(dut: ControlDataIntegrationHarness): Unit = {
    // Forward path defaults
    dut.io.fdi_lp_valid.poke(false.B)
    dut.io.fdi_lp_irdy.poke(false.B)
    dut.io.fdi_lp_data.poke(0.U)
    RawStreamSignalCodec.pokeStreamFromId(dut.io.fdi_lp_stream, RawStreamIds.Stack0Streaming)
    dut.io.rdi_pl_trdy.poke(true.B)

    // Reverse path idle
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

  private def snapshot(dut: ControlDataIntegrationHarness): String =
    s"state=${dut.io.link_state.peek().litValue} " +
      s"rdi_lp_state_req=${dut.io.rdi_lp_state_req.peek().litValue} " +
      s"stallreq=${dut.io.linkmgmt_stallreq.peek().litValue} " +
      s"stalldone=${dut.io.linkmgmt_stalldone.peek().litValue} " +
      s"fdi_pl_stallreq=${dut.io.fdi_pl_stallreq.peek().litValue}"

  private def pendingBeatSnapshot(env: ForwardTrafficEnv): String = {
    val idxStr = env.driver.pendingBeatIndex.map(_.toString).getOrElse("none")
    val dataStr = env.driver.pendingBeat.map(b => s"0x${b.data.toString(16)}").getOrElse("none")
    val streamStr = env.driver.pendingBeat.map(b => s"0x${b.streamId.toHexString}").getOrElse("none")
    s"pending_idx=$idxStr pending_data=$dataStr pending_stream=$streamStr"
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
        snapshot(dut)
    ) // UNKNOWN: needs spec/RTL audit
  }

  /**
    * One-cycle sideband pulses are valid for this RTL model because sb_rcv/sb_rdy
    * are sampled each cycle and consumed through sticky progress flags/state.
    */
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

  private def bringLinkToActive(dut: ControlDataIntegrationHarness): Unit = {
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

    waitUntil(dut, maxCycles = 40, reason = "FDI inband presence high") {
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

  private def recoverFromRetrainViaLinkErrorToActive(dut: ControlDataIntegrationHarness): Unit = {
    // Minimal restart path used in this suite:
    // force retrain -> LINKERROR, allow LINKERROR -> RESET under rx_deactive,
    // then perform legal reset -> active bring-up.
    dut.io.fdi_lp_stallack.poke(false.B)
    dut.io.fdi_lp_rx_active_sts.poke(false.B)
    dut.io.rdi_pl_state_sts.poke(PhyState.linkError)
    waitUntil(dut, maxCycles = 40, reason = "enter LINKERROR during retrain-exit recovery") {
      dut.io.link_state.peek().litValue == PhyState.linkError.litValue
    }

    // In this RTL, while in LINKERROR and rx_deactive is true, keeping
    // rdi_pl_state_sts==LINKERROR is sufficient to satisfy transition to RESET.
    waitUntil(dut, maxCycles = 40, reason = "LINKERROR -> RESET during retrain-exit recovery") {
      dut.io.link_state.peek().litValue == PhyState.reset.litValue
    }

    // Avoid re-entering LINKERROR once RESET is reached.
    dut.io.rdi_pl_state_sts.poke(PhyState.reset)
    dut.io.rdi_pl_inband_pres.poke(false.B)
    dut.clock.step(1)

    bringLinkToActive(dut)
  }

  private def newForwardTrafficEnv(
    dut: ControlDataIntegrationHarness,
    beats: Seq[RawBeat],
    injectedSourceHoldoff: () => Boolean = () => false,
    gapCyclesBeforeBeat: Int => Int = _ => 0
  ): ForwardTrafficEnv = {
    val expectedQ = mutable.Queue.empty[AcceptedBeat]
    val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = false)
    val driver = new ControlFdiIngressDriver(
      dut = dut,
      beats = beats,
      injectedSourceHoldoff = injectedSourceHoldoff,
      gapCyclesBeforeBeat = gapCyclesBeforeBeat
    )
    val ingressTracker = new ControlIngressTracker(dut)
    val egressMonitor = new ControlEgressMonitor(dut, scoreboard.onObserved)
    val sourceStabilityChecker = new ForwardSourceStabilityChecker(dut, driver)
    new ForwardTrafficEnv(
      driver = driver,
      ingressTracker = ingressTracker,
      egressMonitor = egressMonitor,
      sourceStabilityChecker = sourceStabilityChecker,
      expectedQ = expectedQ,
      scoreboard = scoreboard,
      maxExpectedQueueDepth = 0
    )
  }

  private def stepForwardTraffic(
    dut: ControlDataIntegrationHarness,
    env: ForwardTrafficEnv,
    cycle: Long,
    egressReady: Boolean = true,
    boundaryCrossed: Boolean = false,
    boundaryName: String = "none"
  ): Option[AcceptedBeat] = {
    dut.io.rdi_pl_trdy.poke(egressReady.B)
    env.driver.driveOneCycle()
    env.sourceStabilityChecker.check(
      cycle = cycle,
      boundaryCrossed = boundaryCrossed,
      boundaryName = boundaryName
    )

    val ingressObs = env.ingressTracker.observeForNextEdge(cycle)
    val egressObs = env.egressMonitor.observeForNextEdge(cycle)
    dut.clock.step(1)

    val accepted = env.ingressTracker.commitAfterEdge(ingressObs, env.expectedQ, enqueueExpected = true)
    if (accepted) env.driver.onAccepted()
    env.maxExpectedQueueDepth = math.max(env.maxExpectedQueueDepth, env.expectedQ.size)
    env.egressMonitor.commitAfterEdge(egressObs)
    if (accepted) ingressObs else None
  }

  private def drainForwardTraffic(
    dut: ControlDataIntegrationHarness,
    env: ForwardTrafficEnv,
    startCycle: Long,
    maxDrainCycles: Int = 128
  ): Long = {
    var cycle = startCycle
    var drained = 0
    while (drained < maxDrainCycles && env.expectedQ.nonEmpty) {
      stepForwardTraffic(dut, env, cycle, egressReady = true)
      cycle += 1
      drained += 1
    }
    cycle
  }

  private def finishForwardTraffic(env: ForwardTrafficEnv): Unit = {
    // Policy under retrain/stall disruption:
    // - no new ingress acceptances after the enforced stall boundary
    // - already accepted ingress beats are allowed to drain to egress
    // Scoreboard checks ordering, payload integrity, and no missing/extra beats.
    env.scoreboard.finishAndAssert(
      acceptedInputCount = env.ingressTracker.acceptedCount,
      maxExpectedQueueDepth = Some(env.maxExpectedQueueDepth)
    )
  }

  behavior of "RetrainStallCoordinationSuite"

  it should "assert stall requests under live traffic when retrain is requested" in {
    simulate(new ControlDataIntegrationHarness(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      bringLinkToActive(dut)

      val beats = (0 until 128).map(i => RawBeat(BigInt("8100000000000000", 16) + BigInt(i), RawStreamIds.Stack0Streaming))
      var sourceHoldoff = false
      val env = newForwardTrafficEnv(dut, beats, injectedSourceHoldoff = () => sourceHoldoff)

      var cycle = 0L
      val triggerCycle = 24L
      var retrainRequested = false
      var stallReqSeen = false
      var protocolStallReqSeen = false

      while (cycle < 1400 && (!env.driver.isDone || env.expectedQ.nonEmpty || !stallReqSeen || cycle < triggerCycle + 80)) {
        if (cycle == triggerCycle) {
          dut.io.rdi_pl_state_sts.poke(PhyState.retrain)
          dut.io.fdi_lp_rx_active_sts.poke(false.B)
          retrainRequested = true
        }

        // Modeled partner behavior for this test: never acknowledge stall, so
        // stalldone should remain low and the controller should stay in ACTIVE.
        dut.io.fdi_lp_stallack.poke(false.B)
        if (dut.io.linkmgmt_stallreq.peek().litToBoolean) stallReqSeen = true
        if (dut.io.fdi_pl_stallreq.peek().litToBoolean) {
          protocolStallReqSeen = true
          sourceHoldoff = true
        }

        stepForwardTraffic(dut, env, cycle, egressReady = true)
        if (cycle >= triggerCycle + 4 && protocolStallReqSeen) {
          dut.io.link_state.expect(PhyState.active) // SPEC-DERIVED
          dut.io.linkmgmt_stalldone.expect(false.B) // SPEC-DERIVED
        }
        cycle += 1
      }

      cycle = drainForwardTraffic(dut, env, cycle)
      assert(retrainRequested, "Retrain request was not issued") // RTL-DERIVED
      assert(stallReqSeen, "linkmgmt_stallreq was never asserted") // SPEC-DERIVED
      assert(protocolStallReqSeen, "fdi_pl_stallreq was never asserted") // SPEC-DERIVED
      finishForwardTraffic(env)
    }
  }

  it should "remain ACTIVE while retrain is requested but stalldone stays low" in {
    simulate(new ControlDataIntegrationHarness(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      bringLinkToActive(dut)

      val beats = (0 until 96).map(i => RawBeat(BigInt("8200000000000000", 16) + BigInt(i), RawStreamIds.Stack1Streaming))
      val env = newForwardTrafficEnv(dut, beats)

      var cycle = 0L
      val triggerCycle = 20L
      var retrainRequested = false
      var stallReqSeen = false
      var protocolStallReqSeen = false

      while (cycle < 900 && (!env.driver.isDone || env.expectedQ.nonEmpty || cycle < triggerCycle + 120)) {
        if (cycle == triggerCycle) {
          dut.io.rdi_pl_state_sts.poke(PhyState.retrain)
          dut.io.fdi_lp_rx_active_sts.poke(false.B)
          retrainRequested = true
        }
        // Modeled partner behavior: withhold stallack forever in this test.
        dut.io.fdi_lp_stallack.poke(false.B)

        if (dut.io.linkmgmt_stallreq.peek().litToBoolean) stallReqSeen = true
        if (dut.io.fdi_pl_stallreq.peek().litToBoolean) protocolStallReqSeen = true

        stepForwardTraffic(dut, env, cycle, egressReady = true)
        if (cycle >= triggerCycle + 4) {
          dut.io.link_state.expect(PhyState.active) // SPEC-DERIVED
          dut.io.linkmgmt_stalldone.expect(false.B) // SPEC-DERIVED
        }
        cycle += 1
      }

      assert(retrainRequested, "Retrain request was not issued") // RTL-DERIVED
      assert(stallReqSeen, "linkmgmt_stallreq was never asserted") // SPEC-DERIVED
      assert(protocolStallReqSeen, "fdi_pl_stallreq was never asserted") // SPEC-DERIVED
      dut.io.link_state.expect(PhyState.active) // SPEC-DERIVED
      finishForwardTraffic(env)
    }
  }

  it should "enter RETRAIN only after delayed stallack/stalldone handshake" in {
    simulate(new ControlDataIntegrationHarness(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      bringLinkToActive(dut)

      val beats = (0 until 160).map(i => RawBeat(BigInt("8300000000000000", 16) + BigInt(i), RawStreamIds.Stack0Streaming))
      var sourceHoldoff = false
      val env = newForwardTrafficEnv(dut, beats, injectedSourceHoldoff = () => sourceHoldoff)

      var cycle = 0L
      val triggerCycle = 24L
      val ackDelayCycles = 12L
      var retrainRequested = false
      var protocolStallReqSeen = false
      var stallBoundaryCycle: Option[Long] = None
      var retrainSeen = false
      var acceptedAfterBoundary = 0L
      var acceptedOnBoundaryEdge = 0L
      var firstAcceptedAfterBoundaryDetail: Option[String] = None
      var firstStallReqCycle: Long = -1L
      var ackPulseIssued = false
      var ackHighRemaining = 0

      while (cycle < 2200 && (!retrainSeen || env.expectedQ.nonEmpty || cycle < triggerCycle + ackDelayCycles + 100)) {
        if (cycle == triggerCycle) {
          dut.io.rdi_pl_state_sts.poke(PhyState.retrain)
          dut.io.fdi_lp_rx_active_sts.poke(false.B)
          retrainRequested = true
        }

        val fdiPlStallReqNow = dut.io.fdi_pl_stallreq.peek().litToBoolean
        if (fdiPlStallReqNow && !protocolStallReqSeen) {
          protocolStallReqSeen = true
          firstStallReqCycle = cycle
          sourceHoldoff = true
        }
        // Modeled partner behavior: after observing fdi_pl_stallreq, reply with a
        // delayed one-cycle stallack pulse. This is a valid environment model for
        // exercising the handshake, not an exhaustive proof over all legal timings.
        if (protocolStallReqSeen && !ackPulseIssued && ackHighRemaining == 0 && cycle - firstStallReqCycle >= ackDelayCycles) {
          ackHighRemaining = 1
          ackPulseIssued = true
        }
        dut.io.fdi_lp_stallack.poke((ackHighRemaining > 0).B)

        val stallReqNow = dut.io.linkmgmt_stallreq.peek().litToBoolean
        val stallDoneNow = dut.io.linkmgmt_stalldone.peek().litToBoolean
        if (stallReqNow && stallDoneNow && stallBoundaryCycle.isEmpty) {
          stallBoundaryCycle = Some(cycle)
        }

        val acceptedBeat = stepForwardTraffic(
          dut = dut,
          env = env,
          cycle = cycle,
          egressReady = true,
          boundaryCrossed = stallBoundaryCycle.exists(cycle >= _),
          boundaryName = "retrain_stall_boundary"
        )
        if (ackHighRemaining > 0) ackHighRemaining -= 1

        acceptedBeat.foreach { beat =>
          if (stallBoundaryCycle.contains(cycle)) {
            // Boundary and acceptance sampled on the same edge are not strictly ordered.
            acceptedOnBoundaryEdge += 1
          } else if (stallBoundaryCycle.exists(cycle > _)) {
            acceptedAfterBoundary += 1
            if (firstAcceptedAfterBoundaryDetail.isEmpty) {
              firstAcceptedAfterBoundaryDetail = Some(
                s"cycle=$cycle seq=${beat.seq} data=0x${beat.data.toString(16)} " +
                  s"${snapshot(dut)} ${pendingBeatSnapshot(env)}"
              )
            }
          }
        }

        if (dut.io.link_state.peek().litValue == PhyState.retrain.litValue) {
          retrainSeen = true
        }
        cycle += 1
      }

      cycle = drainForwardTraffic(dut, env, cycle)
      assert(retrainRequested, "Retrain request was not issued") // RTL-DERIVED
      assert(protocolStallReqSeen, "fdi_pl_stallreq was never observed") // SPEC-DERIVED
      assert(stallBoundaryCycle.nonEmpty, "Stall boundary (stallreq && stalldone) was never observed") // SPEC-DERIVED
      assert(retrainSeen, "FSM never transitioned to RETRAIN") // SPEC-DERIVED
      assert(
        acceptedAfterBoundary == 0L,
        s"Observed $acceptedAfterBoundary accepted beats after enforced stall boundary; " +
          s"boundaryCycle=${stallBoundaryCycle.get} acceptedOnBoundaryEdge=$acceptedOnBoundaryEdge " +
          s"firstViolation=${firstAcceptedAfterBoundaryDetail.getOrElse("none")}"
      ) // UNKNOWN: needs spec/RTL audit
      finishForwardTraffic(env)
    }
  }

  it should "remain consistent under repeated retrain trigger activity" in {
    simulate(new ControlDataIntegrationHarness(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      bringLinkToActive(dut)

      val beats = (0 until 120).map(i => RawBeat(BigInt("8400000000000000", 16) + BigInt(i), RawStreamIds.Stack1Streaming))
      var sourceHoldoff = false
      val env = newForwardTrafficEnv(dut, beats, injectedSourceHoldoff = () => sourceHoldoff)

      var cycle = 0L
      val triggerCycle = 18L
      var firstStallReqSeen = false
      var firstStallReqCycle = -1L
      var ackPulseIssued = false
      var ackHighRemaining = 0
      var retrainSeen = false
      var inconsistentStateSeen = false
      var firstInconsistentDetail: Option[String] = None
      var stallBoundaryCycle: Option[Long] = None
      var acceptedAfterBoundary = 0L
      var acceptedOnBoundaryEdge = 0L
      var firstAcceptedAfterBoundaryDetail: Option[String] = None

      while (cycle < 1800 && (!retrainSeen || env.expectedQ.nonEmpty || cycle < triggerCycle + 160)) {
        // First, hold retrain request steady until retrain entry.
        // After entry, inject repeated trigger activity.
        if (!retrainSeen) {
          if (cycle >= triggerCycle) dut.io.rdi_pl_state_sts.poke(PhyState.retrain)
        } else if (cycle < triggerCycle + 120) {
          if ((cycle % 4) == 0 || (cycle % 4) == 1) dut.io.rdi_pl_state_sts.poke(PhyState.retrain)
          else dut.io.rdi_pl_state_sts.poke(PhyState.active)
        }
        dut.io.fdi_lp_rx_active_sts.poke(false.B)

        val fdiPlStallReqNow = dut.io.fdi_pl_stallreq.peek().litToBoolean
        if (fdiPlStallReqNow && !firstStallReqSeen) {
          firstStallReqSeen = true
          firstStallReqCycle = cycle
          sourceHoldoff = true
        }
        // Modeled partner behavior: one delayed stallack pulse after observing
        // fdi_pl_stallreq. This is a valid minimal handshake model.
        if (firstStallReqSeen && !ackPulseIssued && ackHighRemaining == 0 && cycle - firstStallReqCycle >= 6L) {
          ackHighRemaining = 1
          ackPulseIssued = true
        }
        dut.io.fdi_lp_stallack.poke((ackHighRemaining > 0).B)

        val stallReqNow = dut.io.linkmgmt_stallreq.peek().litToBoolean
        val stallDoneNow = dut.io.linkmgmt_stalldone.peek().litToBoolean
        if (stallReqNow && stallDoneNow && stallBoundaryCycle.isEmpty) {
          stallBoundaryCycle = Some(cycle)
        }

        val acceptedBeat = stepForwardTraffic(
          dut = dut,
          env = env,
          cycle = cycle,
          egressReady = true,
          boundaryCrossed = stallBoundaryCycle.exists(cycle >= _),
          boundaryName = "repeated_retrain_triggers_boundary"
        )
        if (ackHighRemaining > 0) ackHighRemaining -= 1

        acceptedBeat.foreach { beat =>
          if (stallBoundaryCycle.contains(cycle)) {
            acceptedOnBoundaryEdge += 1
          } else if (stallBoundaryCycle.exists(cycle > _)) {
            acceptedAfterBoundary += 1
            if (firstAcceptedAfterBoundaryDetail.isEmpty) {
              firstAcceptedAfterBoundaryDetail = Some(
                s"cycle=$cycle seq=${beat.seq} data=0x${beat.data.toString(16)} " +
                  s"${snapshot(dut)} ${pendingBeatSnapshot(env)}"
              )
            }
          }
        }

        val st = dut.io.link_state.peek().litValue
        val isActive = st == PhyState.active.litValue
        val isRetrain = st == PhyState.retrain.litValue
        // Under this constrained test stimulus (no linkerror and no sideband
        // triggers for linkreset/disabled), only ACTIVE pre-entry and RETRAIN
        // post-entry are legal top-level states.
        if (!isActive && !isRetrain) {
          inconsistentStateSeen = true
          if (firstInconsistentDetail.isEmpty) {
            firstInconsistentDetail = Some(
              s"cycle=$cycle illegal_state=$st ${snapshot(dut)} ${pendingBeatSnapshot(env)}"
            )
          }
        }
        if (isRetrain) retrainSeen = true
        if (retrainSeen && !isRetrain) {
          inconsistentStateSeen = true
          if (firstInconsistentDetail.isEmpty) {
            firstInconsistentDetail = Some(
              s"cycle=$cycle observed state exit after retrain entry: state=$st ${snapshot(dut)} ${pendingBeatSnapshot(env)}"
            )
          }
        }

        cycle += 1
      }

      cycle = drainForwardTraffic(dut, env, cycle)
      assert(firstStallReqSeen, "No stall request observed under repeated retrain triggers") // SPEC-DERIVED
      assert(stallBoundaryCycle.nonEmpty, "No stall boundary observed under repeated retrain triggers") // SPEC-DERIVED
      assert(retrainSeen, "FSM never entered RETRAIN") // SPEC-DERIVED
      assert(
        !inconsistentStateSeen,
        s"FSM entered an inconsistent state under repeated retrain triggers. " +
          s"firstInconsistent=${firstInconsistentDetail.getOrElse("none")}"
      ) // RTL-DERIVED
      assert(
        acceptedAfterBoundary == 0L,
        s"Observed $acceptedAfterBoundary accepted beats after enforced stall boundary " +
          s"(acceptedOnBoundaryEdge=$acceptedOnBoundaryEdge). " +
          s"firstViolation=${firstAcceptedAfterBoundaryDetail.getOrElse("none")}"
      ) // UNKNOWN: needs spec/RTL audit
      dut.io.link_state.expect(PhyState.retrain) // RTL-DERIVED
      finishForwardTraffic(env)
    }
  }

  it should "recover from retrain-exit path and resume clean traffic" in {
    simulate(new ControlDataIntegrationHarness(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      bringLinkToActive(dut)

      // Phase A: traffic with retrain entry.
      val preBeats = (0 until 80).map(i => RawBeat(BigInt("8500000000000000", 16) + BigInt(i), RawStreamIds.Stack0Streaming))
      var sourceHoldoffPre = false
      val preEnv = newForwardTrafficEnv(dut, preBeats, injectedSourceHoldoff = () => sourceHoldoffPre)

      var cycle = 0L
      var retrainSeen = false
      var stallReqSeen = false
      var firstStallReqCycle = -1L
      var ackPulseIssued = false
      var ackHighRemaining = 0
      var stallBoundaryCycle: Option[Long] = None
      var acceptedAfterBoundary = 0L
      var acceptedOnBoundaryEdge = 0L
      var firstAcceptedAfterBoundaryDetail: Option[String] = None
      val triggerCycle = 20L

      while (cycle < 1800 && (!retrainSeen || preEnv.expectedQ.nonEmpty || cycle < triggerCycle + 120)) {
        if (cycle == triggerCycle) {
          dut.io.rdi_pl_state_sts.poke(PhyState.retrain)
          dut.io.fdi_lp_rx_active_sts.poke(false.B)
        }

        val fdiPlStallReqNow = dut.io.fdi_pl_stallreq.peek().litToBoolean
        if (fdiPlStallReqNow && !stallReqSeen) {
          stallReqSeen = true
          firstStallReqCycle = cycle
          sourceHoldoffPre = true
        }
        // Modeled partner behavior: one delayed stallack pulse after observing
        // fdi_pl_stallreq. This is a valid minimal handshake model.
        if (stallReqSeen && !ackPulseIssued && ackHighRemaining == 0 && cycle - firstStallReqCycle >= 5L) {
          ackHighRemaining = 1
          ackPulseIssued = true
        }
        dut.io.fdi_lp_stallack.poke((ackHighRemaining > 0).B)

        val stallReqNow = dut.io.linkmgmt_stallreq.peek().litToBoolean
        val stallDoneNow = dut.io.linkmgmt_stalldone.peek().litToBoolean
        if (stallReqNow && stallDoneNow && stallBoundaryCycle.isEmpty) {
          stallBoundaryCycle = Some(cycle)
        }

        val acceptedBeat = stepForwardTraffic(
          dut = dut,
          env = preEnv,
          cycle = cycle,
          egressReady = true,
          boundaryCrossed = stallBoundaryCycle.exists(cycle >= _),
          boundaryName = "pre_retrain_boundary"
        )
        if (ackHighRemaining > 0) ackHighRemaining -= 1

        acceptedBeat.foreach { beat =>
          if (stallBoundaryCycle.contains(cycle)) {
            acceptedOnBoundaryEdge += 1
          } else if (stallBoundaryCycle.exists(cycle > _)) {
            acceptedAfterBoundary += 1
            if (firstAcceptedAfterBoundaryDetail.isEmpty) {
              firstAcceptedAfterBoundaryDetail = Some(
                s"cycle=$cycle seq=${beat.seq} data=0x${beat.data.toString(16)} " +
                  s"${snapshot(dut)} ${pendingBeatSnapshot(preEnv)}"
              )
            }
          }
        }

        if (dut.io.link_state.peek().litValue == PhyState.retrain.litValue) retrainSeen = true
        cycle += 1
      }
      cycle = drainForwardTraffic(dut, preEnv, cycle)
      assert(stallReqSeen, "No stall request observed in pre-retrain phase") // SPEC-DERIVED
      assert(stallBoundaryCycle.nonEmpty, "No stall boundary observed in pre-retrain phase") // SPEC-DERIVED
      assert(retrainSeen, "FSM did not reach RETRAIN in pre-retrain phase") // SPEC-DERIVED
      assert(
        acceptedAfterBoundary == 0L,
        s"Observed $acceptedAfterBoundary accepted beats after enforced pre-retrain stall boundary " +
          s"(acceptedOnBoundaryEdge=$acceptedOnBoundaryEdge). " +
          s"firstViolation=${firstAcceptedAfterBoundaryDetail.getOrElse("none")}"
      ) // UNKNOWN: needs spec/RTL audit
      finishForwardTraffic(preEnv)

      // Recover through the minimal retrain-exit path modeled by this suite.
      recoverFromRetrainViaLinkErrorToActive(dut)
      dut.io.link_state.expect(PhyState.active) // SPEC-DERIVED

      // Ensure no stale pre-retrain beat leaks before new ingress traffic.
      var staleTransfers = 0
      for (i <- 0 until 8) {
        dut.io.fdi_lp_valid.poke(false.B)
        dut.io.fdi_lp_irdy.poke(false.B)
        dut.io.fdi_lp_stallack.poke(false.B)
        val transferred = dut.io.rdi_lp_valid.peek().litToBoolean &&
          dut.io.rdi_lp_irdy.peek().litToBoolean &&
          dut.io.rdi_pl_trdy.peek().litToBoolean
        if (transferred) staleTransfers += 1
        dut.clock.step(1)
      }
      assert(staleTransfers == 0, s"Observed $staleTransfers stale transfers after retrain exit restart") // SPEC-DERIVED

      // Phase B: resumed traffic should behave normally.
      val postBeats = (0 until 48).map(i => RawBeat(BigInt("8510000000000000", 16) + BigInt(i), RawStreamIds.Stack1Streaming))
      val postEnv = newForwardTrafficEnv(dut, postBeats)

      var postCycle = cycle + 8
      while (postCycle < cycle + 2000 && (!postEnv.driver.isDone || postEnv.expectedQ.nonEmpty)) {
        dut.io.fdi_lp_stallack.poke(false.B)
        stepForwardTraffic(dut, postEnv, postCycle, egressReady = true)
        postCycle += 1
      }
      postCycle = drainForwardTraffic(dut, postEnv, postCycle)
      finishForwardTraffic(postEnv)
    }
  }
}
