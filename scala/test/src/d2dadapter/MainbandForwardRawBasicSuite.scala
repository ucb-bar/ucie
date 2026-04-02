package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable
import scala.util.Random

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

class MainbandForwardRawBasicSuite extends AnyFlatSpec with ChiselSim {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams

  private def initDut(dut: D2DMainbandModule): Unit = {
    dut.io.fdi_lp_valid.poke(false.B)
    dut.io.fdi_lp_irdy.poke(false.B)
    dut.io.fdi_lp_data.poke(0.U)
    RawStreamSignalCodec.pokeStreamFromId(dut.io.fdi_lp_stream, RawStreamIds.Stack0Streaming)

    // Keep reverse direction idle for this phase-1 raw transport test.
    dut.io.rdi_pl_valid.poke(false.B)
    dut.io.rdi_pl_data.poke(0.U)

    // Egress backpressure control for monitor side.
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
    checkStreamId: Boolean,
    maxCycles: Int = 500,
    egressReadyFn: Long => Boolean = _ => true,
    injectedSourceHoldoffFn: Long => Boolean = _ => false
  ): Unit = {
    val expectedQ = mutable.Queue.empty[AcceptedBeat]
    val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = checkStreamId)
    var cycleRef: Long = 0L

    val streamCheckMode =
      if (checkStreamId) "enabled"
      else "disabled"

    val driver = new RawStreamDriver(
      dut = dut,
      beats = beats,
      injectedSourceHoldoff = () => injectedSourceHoldoffFn(cycleRef)
    )
    val ingressTracker = new IngressAcceptanceTracker(dut, expectedQ)
    val egressMonitor = new EgressMonitor(
      dut,
      onObserved = scoreboard.onObserved,
      // D2DMainbandModule protocol->d2d->phy transport path is fdi_lp_* ingress to rdi_lp_* egress.
      // This egress boundary carries data/valid/irdy/trdy but no stream metadata, so stream check stays off.
      egressStreamId = () => RawStreamIds.UnknownStreamId
    )

    var prevOutstanding: Option[(Int, BigInt, Int)] = None
    def checkSourceStability(cycle: Long): Unit = {
      (driver.pendingBeatIndex, driver.pendingBeat) match {
        case (Some(idx), Some(beat)) =>
          val currData = dut.io.fdi_lp_data.peek().litValue
          val currStream = RawStreamSignalCodec.peekStreamId(dut.io.fdi_lp_stream)

          // Ensure current drive matches the currently pending beat contract.
          assert(
            currData == beat.data,
            s"Cycle $cycle: driven fdi_lp_data (0x${currData.toString(16)}) != pending beat data (0x${beat.data.toString(16)})"
          ) // SPEC-DERIVED
          assert(
            currStream == beat.streamId,
            s"Cycle $cycle: driven fdi_lp_stream (0x${currStream.toHexString}) != pending beat stream (0x${beat.streamId.toHexString})"
          ) // SPEC-DERIVED

          // If the same beat remains outstanding across cycles, source fields must stay stable.
          prevOutstanding match {
            case Some((prevIdx, prevData, prevStream)) if prevIdx == idx =>
              assert(
                currData == prevData,
                s"Cycle $cycle: source data changed while beat idx=$idx remained unaccepted (0x${prevData.toString(16)} -> 0x${currData.toString(16)})"
              ) // SPEC-DERIVED
              assert(
                currStream == prevStream,
                s"Cycle $cycle: source stream changed while beat idx=$idx remained unaccepted (0x${prevStream.toHexString} -> 0x${currStream.toHexString})"
              ) // SPEC-DERIVED
            case _ => // New pending beat (or first cycle for this beat), nothing to compare yet.
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
        // This is an injected testbench holdoff, not DUT-reported stallack.
        // It verifies the driver can gate fdi_lp_valid/fdi_lp_irdy under a hold condition.
        dut.io.fdi_lp_valid.expect(false.B) // RTL-DERIVED
        dut.io.fdi_lp_irdy.expect(false.B) // RTL-DERIVED
      }

      // Edge-timing convention:
      // 1) drive current-cycle inputs
      // 2) observe handshakes that will be sampled on this edge
      // 3) step clock (edge happens)
      // 4) commit edge observations and update source state
      val ingressObs = ingressTracker.observeForNextEdge(cycleRef)
      val egressObs = egressMonitor.observeForNextEdge(cycleRef)
      dut.clock.step(1)

      val accepted = ingressTracker.commitAfterEdge(ingressObs)
      egressMonitor.commitAfterEdge(egressObs)
      if (accepted) driver.onAccepted()
      cycleRef += 1
    }

    assert(cycleRef < maxCycles, s"Timeout at $maxCycles cycles (driverDone=${driver.isDone}, expectedQ=${expectedQ.size})") // UNKNOWN: needs spec/RTL audit

    // Small drain window for any in-flight beat.
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
      egressMonitor.commitAfterEdge(egressObs)
      if (accepted) driver.onAccepted()
      cycleRef += 1
      drain += 1
    }

    scoreboard.finishAndAssert(acceptedInputCount = ingressTracker.acceptedCount) // SPEC-DERIVED
  }

  behavior of "MainbandForwardRawBasicSuite"

  it should "forward 4 deterministic beats without corruption" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val beats = Seq(
        RawBeat(data = BigInt("0000000000000011", 16), streamId = RawStreamIds.Stack0Streaming),
        RawBeat(data = BigInt("0000000000000022", 16), streamId = RawStreamIds.Stack0Streaming),
        RawBeat(data = BigInt("00000000000000A5", 16), streamId = RawStreamIds.Stack1Streaming),
        RawBeat(data = BigInt("000000000000005A", 16), streamId = RawStreamIds.Stack1Streaming)
      )

      // Stream-preservation checking is intentionally disabled here because rdi_lp_* has no stream field.
      runForwardScenario(dut, beats, checkStreamId = false)
    }
  }

  it should "hold source beat stable under egress backpressure until accepted" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val beats = (0 until 12).map { i =>
        val sid = if ((i & 1) == 0) RawStreamIds.Stack0Streaming else RawStreamIds.Stack1Streaming
        RawBeat(data = BigInt(0x100 + i), streamId = sid)
      }

      val readyPattern: Long => Boolean = cycle => (cycle % 3) != 0
      runForwardScenario(dut, beats, checkStreamId = false, egressReadyFn = readyPattern)
    }
  }

  it should "preserve transport under bursty ready and injected source holdoff" in {
    simulate(new D2DMainbandModule(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      val rng = new Random(123)
      val beats = (0 until 20).map { i =>
        val sid = if ((i & 1) == 0) RawStreamIds.Stack0Streaming else RawStreamIds.Stack1Streaming
        val payload = BigInt(64, rng)
        RawBeat(data = payload, streamId = sid)
      }

      val readyPattern: Long => Boolean = cycle => ((cycle / 2) % 2) == 0
      val injectedHoldoffPattern: Long => Boolean = cycle => cycle >= 6 && cycle <= 8

      runForwardScenario(
        dut = dut,
        beats = beats,
        checkStreamId = false,
        egressReadyFn = readyPattern,
        injectedSourceHoldoffFn = injectedHoldoffPattern
      )
    }
  }
}
