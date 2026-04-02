package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

final case class ReversePathScenarioStats(
  totalCycles: Long,
  ingressSampledCount: Long,
  ingressForwardedCount: Long,
  ingressParityFilteredCount: Long,
  egressObservedCount: Long,
  maxExpectedQueueDepth: Int
)

final class RdiIngressDriver(
  dut: D2DMainbandModule,
  beats: Seq[RawBeat],
  injectedSourceHoldoff: () => Boolean = () => false,
  gapCyclesBeforeBeat: Int => Int = _ => 0
) extends chisel3.simulator.PeekPokeAPI {
  private var beatIdx: Int = 0
  private var activeBeat: Option[RawBeat] = None
  private var preSendGapRemaining: Int = 0

  var sampledCount: Long = 0L

  def isDone: Boolean = beatIdx >= beats.length && activeBeat.isEmpty
  def pendingBeat: Option[RawBeat] = activeBeat
  def pendingBeatIndex: Option[Int] = activeBeat.map(_ => beatIdx)

  private def loadNextIfNeeded(): Unit = {
    if (activeBeat.isEmpty && beatIdx < beats.length) {
      activeBeat = Some(beats(beatIdx))
      preSendGapRemaining = math.max(0, gapCyclesBeforeBeat(beatIdx))
    }
  }

  /** Drives RDI->D2D reverse ingress. This boundary is valid-only (no ready). */
  def driveOneCycle(): Unit = {
    loadNextIfNeeded()

    activeBeat.foreach { beat =>
      dut.io.rdi_pl_data.poke(beat.data.U)
    }

    val gatedByScheduledGap = preSendGapRemaining > 0
    if (gatedByScheduledGap) {
      preSendGapRemaining -= 1
    }

    if (injectedSourceHoldoff() || gatedByScheduledGap) {
      dut.io.rdi_pl_valid.poke(false.B)
      return
    }

    activeBeat match {
      case Some(_) => dut.io.rdi_pl_valid.poke(true.B)
      case None    => dut.io.rdi_pl_valid.poke(false.B)
    }
  }

  /** Advance to the next beat after the current beat is sampled on ingress. */
  def onSampledIngressBeat(): Unit = {
    if (activeBeat.nonEmpty) {
      sampledCount += 1
      beatIdx += 1
      activeBeat = None
    }
  }
}

final class RdiIngressAcceptanceTracker(
  dut: D2DMainbandModule,
  expectedQ: mutable.Queue[AcceptedBeat],
  expectedForwardedStreamId: Int
) extends chisel3.simulator.PeekPokeAPI {
  final case class IngressEdgeObservation(
    sampledAtIngress: Boolean,
    parityFiltered: Boolean,
    forwardedBeat: Option[AcceptedBeat]
  )

  private var nextForwardedSeq: Long = 0L
  var sampledCount: Long = 0L
  var forwardedCount: Long = 0L
  var parityFilteredCount: Long = 0L

  /**
    * Reverse ingress is valid-only (`rdi_pl_valid` + `rdi_pl_data`), so sampling
    * occurs whenever `rdi_pl_valid` is high at the edge.
    *
    * In this RTL, `parity_check` marks beats that should NOT be forwarded to
    * `fdi_pl_*` (`data_buff_rcv_fill_reg` only sets when `!parity_check`), so:
    * - sampled at ingress: `rdi_pl_valid`
    * - forwarded payload event: `rdi_pl_valid && !parity_check`
    */
  def observeForNextEdge(edgeCycle: Long): IngressEdgeObservation = {
    val sampledAtIngress = dut.io.rdi_pl_valid.peek().litToBoolean // SPEC-DERIVED
    val parityCheck = dut.io.parity_check.peek().litToBoolean
    val parityFiltered = sampledAtIngress && parityCheck
    val forwarded = sampledAtIngress && !parityCheck // UNKNOWN: needs spec/RTL audit

    val forwardedBeat =
      if (forwarded) {
        Some(AcceptedBeat(
        seq = nextForwardedSeq,
        data = dut.io.rdi_pl_data.peek().litValue,
        // Reverse ingress has no stream field. D2DMainbandModule drives a fixed
        // stream value on fdi_pl_stream for forwarded beats.
        streamId = expectedForwardedStreamId,
        cycleAccepted = edgeCycle
      ))
      } else None

    IngressEdgeObservation(
      sampledAtIngress = sampledAtIngress,
      parityFiltered = parityFiltered,
      forwardedBeat = forwardedBeat
    )
  }

  /**
    * Commit sampled/forwarded events after stepping the edge.
    * Returns true when a beat was sampled at ingress and the driver should advance.
    */
  def commitAfterEdge(obs: IngressEdgeObservation): Boolean = {
    if (obs.sampledAtIngress) sampledCount += 1
    if (obs.parityFiltered) parityFilteredCount += 1

    obs.forwardedBeat.foreach { beat =>
      expectedQ.enqueue(beat)
      nextForwardedSeq += 1
      forwardedCount += 1
    }

    obs.sampledAtIngress
  }
}

final class FdiEgressMonitor(
  dut: D2DMainbandModule,
  onObserved: AcceptedBeat => Unit
) extends chisel3.simulator.PeekPokeAPI {
  private var nextSeq: Long = 0L
  var observedCount: Long = 0L

  /**
    * Reverse-path egress transfer model:
    * transferred = fdi_pl_valid.
    *
    * `fdi_pl_*` is valid-only toward protocol (no ready), so a beat is observable
    * whenever `fdi_pl_valid` is high at the edge.
    */
  def observeForNextEdge(edgeCycle: Long): Option[AcceptedBeat] = {
    val transferred = dut.io.fdi_pl_valid.peek().litToBoolean // SPEC-DERIVED

    val beatOpt =
      if (transferred) {
        Some(AcceptedBeat(
          seq = nextSeq,
          data = dut.io.fdi_pl_data.peek().litValue,
          streamId = RawStreamSignalCodec.peekStreamId(dut.io.fdi_pl_stream),
          cycleAccepted = edgeCycle
        ))
      } else None

    beatOpt
  }

  def commitAfterEdge(obs: Option[AcceptedBeat]): Unit = {
    obs.foreach { beat =>
      onObserved(beat)
      nextSeq += 1
      observedCount += 1
    }
  }
}

class MainbandReverseRawStressSuite extends AnyFlatSpec with ChiselSim {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams
  private val reverseEgressStreamId = RawStreamIds.Stack0Streaming

  private def initDut(dut: D2DMainbandModule): Unit = {
    // Long stress tests use their own bounded while loops.
    // Keep forward path idle for this reverse-direction suite.
    dut.io.fdi_lp_valid.poke(false.B)
    dut.io.fdi_lp_irdy.poke(false.B)
    dut.io.fdi_lp_data.poke(0.U)
    RawStreamSignalCodec.pokeStreamFromId(dut.io.fdi_lp_stream, RawStreamIds.Stack0Streaming)

    dut.io.rdi_pl_valid.poke(false.B)
    dut.io.rdi_pl_data.poke(0.U)
    dut.io.rdi_pl_trdy.poke(true.B) // not used by reverse path

    dut.io.d2d_state.poke(PhyState.active)
    dut.io.mainband_stallreq.poke(false.B)
    dut.io.parity_insert.poke(false.B)
    dut.io.parity_data.poke(0.U)
    dut.io.parity_check.poke(false.B)

    dut.clock.step(2)
  }

  private def runReverseScenario(
    dut: D2DMainbandModule,
    beats: Seq[RawBeat],
    maxCycles: Int = 4000,
    injectedSourceHoldoffFn: Long => Boolean = _ => false,
    gapCyclesBeforeBeat: Int => Int = _ => 0,
    parityCheckFn: Long => Boolean = _ => false
  ): ReversePathScenarioStats = {
    val expectedQ = mutable.Queue.empty[AcceptedBeat]
    val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = true)
    var cycleRef = 0L
    var maxExpectedQueueDepth = 0
    def updateMaxExpectedQueueDepth(): Unit = {
      maxExpectedQueueDepth = math.max(maxExpectedQueueDepth, expectedQ.size)
    }

    // Reverse path boundary being verified here is:
    // rdi_pl_* (physical -> adapter ingress) -> D2DMainbandModule -> fdi_pl_* (adapter -> protocol egress).
    //
    // There is no backpressure signal on rdi_pl_* or fdi_pl_* in this RTL shape.
    // Any testbench "holdoff" therefore models source throttling only.
    val driver = new RdiIngressDriver(
      dut = dut,
      beats = beats,
      injectedSourceHoldoff = () => injectedSourceHoldoffFn(cycleRef),
      gapCyclesBeforeBeat = gapCyclesBeforeBeat
    )
    val ingressTracker = new RdiIngressAcceptanceTracker(
      dut = dut,
      expectedQ = expectedQ,
      expectedForwardedStreamId = reverseEgressStreamId
    )
    val egressMonitor = new FdiEgressMonitor(
      dut = dut,
      onObserved = scoreboard.onObserved
    )

    var prevOutstanding: Option[(Int, BigInt)] = None
    def checkSourceStability(cycle: Long): Unit = {
      (driver.pendingBeatIndex, driver.pendingBeat) match {
        case (Some(idx), Some(beat)) =>
          val currData = dut.io.rdi_pl_data.peek().litValue
          assert(
            currData == beat.data,
            s"Cycle $cycle idx=$idx: rdi_pl_data mismatch, driven=0x${currData.toString(16)} pending=0x${beat.data.toString(16)}"
          ) // SPEC-DERIVED

          prevOutstanding match {
            case Some((prevIdx, prevData)) if prevIdx == idx =>
              assert(
                currData == prevData,
                s"Cycle $cycle idx=$idx: rdi_pl_data changed while beat remained unaccepted (prev=0x${prevData.toString(16)} now=0x${currData.toString(16)})"
              ) // SPEC-DERIVED
            case _ =>
          }
          prevOutstanding = Some((idx, currData))

        case _ =>
          prevOutstanding = None
      }
    }

    while (cycleRef < maxCycles && (!driver.isDone || expectedQ.nonEmpty)) {
      dut.io.parity_check.poke(parityCheckFn(cycleRef).B)
      driver.driveOneCycle()
      checkSourceStability(cycleRef)

      if (injectedSourceHoldoffFn(cycleRef)) {
        dut.io.rdi_pl_valid.expect(false.B) // RTL-DERIVED
      }

      // Edge contract:
      // 1) drive current-cycle inputs
      // 2) observe transfers for the upcoming edge
      // 3) step clock (edge occurs)
      // 4) commit observations and update source state
      val ingressObs = ingressTracker.observeForNextEdge(cycleRef)
      val egressObs = egressMonitor.observeForNextEdge(cycleRef)
      dut.clock.step(1)
      val sampled = ingressTracker.commitAfterEdge(ingressObs)
      updateMaxExpectedQueueDepth()
      egressMonitor.commitAfterEdge(egressObs)
      if (sampled) driver.onSampledIngressBeat()

      cycleRef += 1
    }

    assert(cycleRef < maxCycles, s"Timeout at $maxCycles cycles") // UNKNOWN: needs spec/RTL audit

    // Drain any final in-flight output beat.
    var drain = 0
    while (drain < 16 && expectedQ.nonEmpty) {
      dut.io.parity_check.poke(parityCheckFn(cycleRef).B)
      driver.driveOneCycle()
      checkSourceStability(cycleRef)

      val ingressObs = ingressTracker.observeForNextEdge(cycleRef)
      val egressObs = egressMonitor.observeForNextEdge(cycleRef)
      dut.clock.step(1)
      val sampled = ingressTracker.commitAfterEdge(ingressObs)
      updateMaxExpectedQueueDepth()
      egressMonitor.commitAfterEdge(egressObs)
      if (sampled) driver.onSampledIngressBeat()

      cycleRef += 1
      drain += 1
    }

    scoreboard.finishAndAssert(
      acceptedInputCount = ingressTracker.forwardedCount,
      maxExpectedQueueDepth = Some(maxExpectedQueueDepth)
    ) // UNKNOWN: needs spec/RTL audit

    assert(
      ingressTracker.forwardedCount + ingressTracker.parityFilteredCount == ingressTracker.sampledCount,
      s"Ingress event accounting mismatch: sampled=${ingressTracker.sampledCount} " +
        s"forwarded=${ingressTracker.forwardedCount} filtered=${ingressTracker.parityFilteredCount}"
    ) // UNKNOWN: needs spec/RTL audit

    println(
      s"[TEST][RX] cycles=$cycleRef sampled=${ingressTracker.sampledCount} " +
        s"forwarded=${ingressTracker.forwardedCount} filtered=${ingressTracker.parityFilteredCount} " +
        s"egressObserved=${egressMonitor.observedCount} maxExpectedQ=$maxExpectedQueueDepth"
    )

    ReversePathScenarioStats(
      totalCycles = cycleRef,
      ingressSampledCount = ingressTracker.sampledCount,
      ingressForwardedCount = ingressTracker.forwardedCount,
      ingressParityFilteredCount = ingressTracker.parityFilteredCount,
      egressObservedCount = egressMonitor.observedCount,
      maxExpectedQueueDepth = maxExpectedQueueDepth
    )
  }

  behavior of "MainbandReverseRawStressSuite"

  // Bug class targeted: long-run ordering/corruption/drop/duplication under deterministic source throttling.
  it should "sustain long reverse traffic under injected source holdoff" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val beatCount = 256
      val base = BigInt("4000000000000000", 16)
      val beats = (0 until beatCount).map { i =>
        // Reverse ingress has no stream field; this streamId is test-vector metadata only.
        RawBeat(data = base + BigInt(i), streamId = RawStreamIds.Stack0Streaming)
      }

      val sourceHoldoffPattern: Long => Boolean = cycle => (cycle % 11) >= 7
      val stats = runReverseScenario(
        dut = dut,
        beats = beats,
        maxCycles = 12000,
        injectedSourceHoldoffFn = sourceHoldoffPattern
      )
      assert(stats.ingressForwardedCount == beatCount.toLong, s"Expected $beatCount forwarded beats, got ${stats.ingressForwardedCount}") // UNKNOWN: needs spec/RTL audit
      assert(stats.ingressParityFilteredCount == 0L, s"Unexpected parity-filtered beats: ${stats.ingressParityFilteredCount}") // UNKNOWN: needs spec/RTL audit
    }
  }

  // Bug class targeted: repeated payload runs expose off-by-one, accidental duplicate, or dropped-beat issues.
  it should "avoid duplicate/drop errors across repeated reverse payload runs" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val pA = BigInt("AAAAAAAAAAAAAAAA", 16)
      val pB = BigInt("BBBBBBBBBBBBBBBB", 16)
      val pC = BigInt("CCCCCCCCCCCCCCCC", 16)

      // AAAA BBBB CCCC BBBB AAAA
      val payloads = Seq.fill(4)(pA) ++ Seq.fill(4)(pB) ++ Seq.fill(4)(pC) ++ Seq.fill(4)(pB) ++ Seq.fill(4)(pA)
      val beats = payloads.map(p => RawBeat(data = p, streamId = RawStreamIds.Stack0Streaming))

      val stats = runReverseScenario(
        dut = dut,
        beats = beats,
        maxCycles = 5000
      )
      assert(stats.ingressForwardedCount == beats.length.toLong, s"Expected ${beats.length} forwarded beats, got ${stats.ingressForwardedCount}") // UNKNOWN: needs spec/RTL audit
      assert(stats.ingressParityFilteredCount == 0L, s"Unexpected parity-filtered beats: ${stats.ingressParityFilteredCount}") // UNKNOWN: needs spec/RTL audit
    }
  }

  // Bug class targeted: idle spacing between ingress bursts catches buffering/order bugs around bubble insertion.
  it should "preserve reverse transport across intentional ingress gaps" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val beats = (0 until 12).map { i =>
        RawBeat(
          data = BigInt("ABC0000000000000", 16) + BigInt(i),
          streamId = RawStreamIds.Stack0Streaming
        )
      }

      // beat0, gap2 cycles, beat1+beat2, gap1 cycle, then remaining beats.
      val gapMap = Map(
        1 -> 2,
        3 -> 1
      )
      val gapFn: Int => Int = beatIdx => gapMap.getOrElse(beatIdx, 0)

      val stats = runReverseScenario(
        dut = dut,
        beats = beats,
        maxCycles = 6000,
        gapCyclesBeforeBeat = gapFn
      )
      assert(stats.ingressForwardedCount == beats.length.toLong, s"Expected ${beats.length} forwarded beats, got ${stats.ingressForwardedCount}") // UNKNOWN: needs spec/RTL audit
      assert(stats.ingressParityFilteredCount == 0L, s"Unexpected parity-filtered beats: ${stats.ingressParityFilteredCount}") // UNKNOWN: needs spec/RTL audit
    }
  }

  // Bug class targeted: parity-marked reverse ingress beats must be filtered from protocol egress.
  it should "filter parity-marked reverse ingress beats and keep egress accounting consistent" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val beatCount = 64
      val base = BigInt("D000000000000000", 16)
      val beats = (0 until beatCount).map { i =>
        RawBeat(data = base + BigInt(i), streamId = RawStreamIds.Stack0Streaming)
      }

      val parityCheckPattern: Long => Boolean = cycle => (cycle % 9) == 4
      val stats = runReverseScenario(
        dut = dut,
        beats = beats,
        maxCycles = 5000,
        parityCheckFn = parityCheckPattern
      )

      assert(stats.ingressSampledCount == beatCount.toLong, s"Expected $beatCount sampled ingress beats, got ${stats.ingressSampledCount}") // UNKNOWN: needs spec/RTL audit
      assert(
        stats.ingressParityFilteredCount > 0L,
        s"Expected parity filtering to occur, got filtered=${stats.ingressParityFilteredCount}"
      ) // UNKNOWN: needs spec/RTL audit
      assert(
        stats.ingressForwardedCount + stats.ingressParityFilteredCount == stats.ingressSampledCount,
        s"Forwarded+filtered must equal sampled: fwd=${stats.ingressForwardedCount} " +
          s"filtered=${stats.ingressParityFilteredCount} sampled=${stats.ingressSampledCount}"
      ) // UNKNOWN: needs spec/RTL audit
    }
  }
}
