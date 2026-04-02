package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

/**
  * Phase 1/1B hardening for mainband raw-stream transport only.
  * This is not full UCIe protocol verification.
  */
class MainbandForwardRawStressSuite extends AnyFlatSpec with ChiselSim {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams

  private def initDut(dut: D2DMainbandModule): Unit = {
    dut.io.fdi_lp_valid.poke(false.B)
    dut.io.fdi_lp_irdy.poke(false.B)
    dut.io.fdi_lp_data.poke(0.U)
    RawStreamSignalCodec.pokeStreamFromId(dut.io.fdi_lp_stream, RawStreamIds.Stack0Streaming)

    dut.io.rdi_pl_valid.poke(false.B)
    dut.io.rdi_pl_data.poke(0.U)
    dut.io.rdi_pl_trdy.poke(true.B)

    dut.io.d2d_state.poke(PhyState.active)
    dut.io.mainband_stallreq.poke(false.B)
    dut.io.parity_insert.poke(false.B)
    dut.io.parity_data.poke(0.U)
    dut.io.parity_check.poke(false.B)

    dut.clock.step(2)
  }

  private def runForwardScenario(
    dut: D2DMainbandModule,
    beats: Seq[RawBeat],
    checkStreamId: Boolean = false,
    maxCycles: Int = 4000,
    egressReadyFn: Long => Boolean = _ => true,
    injectedSourceHoldoffFn: Long => Boolean = _ => false,
    gapCyclesBeforeBeat: Int => Int = _ => 0
  ): Unit = {
    val expectedQ = mutable.Queue.empty[AcceptedBeat]
    val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = checkStreamId)
    var cycleRef = 0L
    var maxExpectedQueueDepth = 0
    def updateMaxExpectedQueueDepth(): Unit = {
      maxExpectedQueueDepth = math.max(maxExpectedQueueDepth, expectedQ.size)
    }

    val streamCheckMode =
      if (checkStreamId) "enabled"
      else "disabled"

    val driver = new RawStreamDriver(
      dut = dut,
      beats = beats,
      injectedSourceHoldoff = () => injectedSourceHoldoffFn(cycleRef),
      gapCyclesBeforeBeat = gapCyclesBeforeBeat
    )

    val ingressTracker = new IngressAcceptanceTracker(dut, expectedQ)
    val egressMonitor = new EgressMonitor(
      dut = dut,
      onObserved = scoreboard.onObserved,
      // D2DMainbandModule's protocol->physical path on rdi_lp_* does not carry stream metadata.
      egressStreamId = () => RawStreamIds.UnknownStreamId
    )

    var prevOutstanding: Option[(Int, BigInt, Int)] = None
    def checkSourceStability(cycle: Long): Unit = {
      (driver.pendingBeatIndex, driver.pendingBeat) match {
        case (Some(idx), Some(beat)) =>
          val currData = dut.io.fdi_lp_data.peek().litValue
          val currStream = RawStreamSignalCodec.peekStreamId(dut.io.fdi_lp_stream)
          assert(
            currData == beat.data,
            s"Cycle $cycle idx=$idx: source data mismatch, driven=0x${currData.toString(16)} pending=0x${beat.data.toString(16)}"
          ) // SPEC-DERIVED
          assert(
            currStream == beat.streamId,
            s"Cycle $cycle idx=$idx: source stream mismatch, driven=0x${currStream.toHexString} pending=0x${beat.streamId.toHexString}"
          ) // SPEC-DERIVED

          prevOutstanding match {
            case Some((prevIdx, prevData, prevStream)) if prevIdx == idx =>
              assert(
                currData == prevData,
                s"Cycle $cycle idx=$idx: source data changed while beat remained unaccepted (prev=0x${prevData.toString(16)} now=0x${currData.toString(16)})"
              ) // SPEC-DERIVED
              assert(
                currStream == prevStream,
                s"Cycle $cycle idx=$idx: source stream changed while beat remained unaccepted (prev=0x${prevStream.toHexString} now=0x${currStream.toHexString})"
              ) // SPEC-DERIVED
            case _ =>
          }
          prevOutstanding = Some((idx, currData, currStream))
        case _ =>
          prevOutstanding = None
      }
    }

    while (cycleRef < maxCycles && (!driver.isDone || expectedQ.nonEmpty)) {
      dut.io.rdi_pl_trdy.poke(egressReadyFn(cycleRef).B)
      driver.driveOneCycle()
      checkSourceStability(cycleRef)

      if (injectedSourceHoldoffFn(cycleRef)) {
        // Injected source holdoff check (testbench behavior), not DUT stallack semantics.
        dut.io.fdi_lp_valid.expect(false.B) // RTL-DERIVED
        dut.io.fdi_lp_irdy.expect(false.B) // RTL-DERIVED
      }

      // Edge contract:
      // 1) drive current-cycle inputs
      // 2) observe transfers for the upcoming edge
      // 3) step clock (edge occurs)
      // 4) commit edge observations and update source state
      val ingressObs = ingressTracker.observeForNextEdge(cycleRef)
      val egressObs = egressMonitor.observeForNextEdge(cycleRef)
      dut.clock.step(1)
      val accepted = ingressTracker.commitAfterEdge(ingressObs)
      updateMaxExpectedQueueDepth()
      egressMonitor.commitAfterEdge(egressObs)
      if (accepted) driver.onAccepted()

      cycleRef += 1
    }

    assert(cycleRef < maxCycles, s"Timeout at $maxCycles cycles") // UNKNOWN: needs spec/RTL audit

    var drain = 0
    while (drain < 16 && expectedQ.nonEmpty) {
      dut.io.rdi_pl_trdy.poke(true.B)
      driver.driveOneCycle()
      checkSourceStability(cycleRef)

      if (injectedSourceHoldoffFn(cycleRef)) {
        dut.io.fdi_lp_valid.expect(false.B) // RTL-DERIVED
        dut.io.fdi_lp_irdy.expect(false.B) // RTL-DERIVED
      }

      val ingressObs = ingressTracker.observeForNextEdge(cycleRef)
      val egressObs = egressMonitor.observeForNextEdge(cycleRef)
      dut.clock.step(1)
      val accepted = ingressTracker.commitAfterEdge(ingressObs)
      updateMaxExpectedQueueDepth()
      egressMonitor.commitAfterEdge(egressObs)
      if (accepted) driver.onAccepted()

      cycleRef += 1
      drain += 1
    }

    scoreboard.finishAndAssert(
      acceptedInputCount = ingressTracker.acceptedCount,
      maxExpectedQueueDepth = Some(maxExpectedQueueDepth)
    ) // SPEC-DERIVED
  }

  behavior of "MainbandForwardRawStressSuite"

  // Bug class targeted: long-run ordering/corruption/drop/duplication under sustained bursty backpressure.
  it should "sustain long forward traffic with bursty egress readiness" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val beatCount = 256
      val base = BigInt("1000000000000000", 16)
      val beats = (0 until beatCount).map { i =>
        val sid = if ((i & 1) == 0) RawStreamIds.Stack0Streaming else RawStreamIds.Stack1Streaming
        RawBeat(data = base + BigInt(i), streamId = sid)
      }

      val readyPattern: Long => Boolean = cycle => (cycle % 11) < 7
      runForwardScenario(
        dut = dut,
        beats = beats,
        checkStreamId = false,
        maxCycles = 12000,
        egressReadyFn = readyPattern
      )
    }
  }

  // Bug class targeted: repeated payload runs expose off-by-one, accidental duplicate, or dropped-beat issues.
  it should "avoid duplicate/drop errors across repeated payload runs" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val p1 = BigInt("1111111111111111", 16)
      val p2 = BigInt("2222222222222222", 16)
      val p3 = BigInt("3333333333333333", 16)
      val payloads = Seq.fill(4)(p1) ++ Seq.fill(4)(p2) ++ Seq.fill(4)(p3) ++ Seq.fill(4)(p2) ++ Seq.fill(4)(p1)
      val beats = payloads.map(p => RawBeat(data = p, streamId = RawStreamIds.Stack0Streaming))

      val readyPattern: Long => Boolean = cycle => (cycle % 5) != 0
      runForwardScenario(
        dut = dut,
        beats = beats,
        checkStreamId = false,
        maxCycles = 5000,
        egressReadyFn = readyPattern
      )
    }
  }

  // Bug class targeted: source-idle insertion between bursts catches state-machine assumptions about continuous valid.
  it should "preserve order and payloads across intentional source gaps" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val beats = (0 until 12).map { i =>
        val sid = if ((i & 1) == 0) RawStreamIds.Stack0Streaming else RawStreamIds.Stack1Streaming
        RawBeat(data = BigInt("ABC0000000000000", 16) + BigInt(i), streamId = sid)
      }

      // Sequence intent:
      // beat0, gap2 cycles, beat1+beat2, gap1 cycle, then remaining beats.
      val gapMap = Map(
        1 -> 2,
        3 -> 1
      )
      val gapFn: Int => Int = beatIdx => gapMap.getOrElse(beatIdx, 0)

      runForwardScenario(
        dut = dut,
        beats = beats,
        checkStreamId = false,
        maxCycles = 6000,
        gapCyclesBeforeBeat = gapFn
      )
    }
  }

  // Stream-preservation test is intentionally skipped for this boundary:
  // rdi_lp_* has no stream field, so stream equality cannot be observed at this monitor point.
}
