package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.d2dadapter.LinkInitState
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import org.scalatest.funspec.AnyFunSpec
import scala.collection.mutable.ArrayBuffer

/** Walks two full {ProtocolLayer, D2DAdapter, LogicalPhy} stacks from reset to
  * protocol data crossing the link, one stage per test.
  *
  * PHY training is one stage here rather than nine: LogPhyStagedBringupTest
  * already walks the LTSM state by state, so a training failure is located
  * there. What this testbench adds is everything above the RDI, driven by a
  * real adapter instead of the stub the LogPhy harness uses.
  *
  * Every test is a cold start, so the stages are independent and the first
  * failing test names the first thing the stack cannot do.
  */
class UcieDigitalStagedBringupTest extends AnyFunSpec with ChiselSim {
  private type H = UcieDigitalLoopbackHarness

  // The LTSM asserts on substates the link passes through on its way up.
  private val firtoolOpts = Array[String]()

  private val resetWait = 3200000
  private val trainCycles = 3000000
  private val handshakeCycles = 200000
  private val flagCycles = 4096
  private val dataCycles = 200000
  private val burstLength = 4

  // ---------------------------------------------------------------------------
  // Observing and stepping
  // ---------------------------------------------------------------------------

  private def flag(h: H, die: Int, bit: Int): Boolean =
    ((h.io.flags(die).peek().litValue >> bit) & 1) == 1

  private def bothDies(check: Int => Boolean): Boolean = check(0) && check(1)

  private def states(h: H): String =
    (0 until 2)
      .map { die =>
        s"die$die=${h.io.ltState(die).peek()}/${h.io
            .fdiState(die)
            .peek()}/${h.io.adapterLinkInit(die).peek()}"
      }
      .mkString(", ")

  private def stepUntil(h: H, limit: Int, milestone: String)(
      done: => Boolean
  ): Unit = {
    var left = limit
    while (left > 0 && !done) {
      h.clock.step(1)
      left -= 1
    }
    assert(done, s"$milestone was not reached: ${states(h)}")
  }

  private def coldStart(h: H): Unit = {
    for (die <- 0 until 2) {
      h.io.swStartLinkTraining(die).poke(false.B)
      h.io.pwrGood(die).poke(true.B)
      h.io.protoCtrl(die).poke(ProtoCtrl.word().U)
    }
    h.clock.step(resetWait + 128)
    h.io.swStartLinkTraining(0).poke(true.B)
    h.clock.step(4)
    h.io.swStartLinkTraining(0).poke(false.B)
  }

  /** Reset, train the PHY, then ask both protocol layers for Active. */
  private def bringUpToActive(h: H): Unit = {
    coldStart(h)
    stepUntil(h, trainCycles, "RDI active")(
      bothDies(_ =>
        (0 until 2).forall { die =>
          h.io.rdiState(die).peek().litValue == RDIState.active.litValue
        }
      )
    )
    for (die <- 0 until 2) {
      h.io.protoCtrl(die).poke(ProtoCtrl.word(reqActive = true).U)
    }
    stepUntil(h, handshakeCycles, "FDI active")(
      bothDies(die =>
        h.io.fdiState(die).peek().litValue == FDIState.active.litValue
      )
    )
  }

  // ---------------------------------------------------------------------------
  // Chip-facing data
  // ---------------------------------------------------------------------------

  /** A beat tagged by die and sequence number, so a swapped, stale or
    * lane-permuted beat each fail differently.
    */
  private def payload(bits: Int, die: Int, seq: Int): BigInt =
    (0 until bits / 16).foldLeft(BigInt(0)) { (acc, i) =>
      val half =
        ((die + 1) << 12) | ((seq + 1) << 8) | ((i * 7 + die * 3 + seq) & 0xff)
      acc | (BigInt(half & 0xffff) << (i * 16))
    }

  /** Drive each die's beats out of its chip-facing TX while draining both RX
    * sides, then keep draining through a quiet tail. Returns what each die
    * received, in arrival order.
    */
  private def exchange(h: H, words: Seq[Seq[BigInt]]): Seq[Seq[BigInt]] = {
    val pending = words.map(_.to(ArrayBuffer))
    val got = Seq.fill(2)(ArrayBuffer.empty[BigInt])
    for (die <- 0 until 2) h.io.rxReady.get(die).poke(true.B)

    var quiet = 0
    var left = dataCycles
    while ((pending.exists(_.nonEmpty) || quiet < 64) && left > 0) {
      for (die <- 0 until 2) {
        h.io.txValid.get(die).poke(pending(die).nonEmpty.B)
        if (pending(die).nonEmpty) {
          h.io.txData.get(die).poke(pending(die).head.U(h.beatBits.W))
        }
      }
      // Sample the handshakes completing on this edge, before stepping.
      val accepted = (0 until 2).map { die =>
        pending(die).nonEmpty && flag(h, die, DieFlag.chipTxReady)
      }
      val delivered = (0 until 2).map { die =>
        Option.when(flag(h, die, DieFlag.chipRxValid))(
          h.io.rxData.get(die).peek().litValue
        )
      }

      h.clock.step(1)
      left -= 1

      for (die <- 0 until 2) {
        if (accepted(die)) pending(die).remove(0)
        delivered(die).foreach(got(die) += _)
      }
      if (pending.forall(_.isEmpty)) quiet += 1
    }

    for (die <- 0 until 2) {
      h.io.txValid.get(die).poke(false.B)
      h.io.rxReady.get(die).poke(false.B)
    }
    assert(
      pending.forall(_.isEmpty),
      s"the chip-facing TX never accepted every beat: ${states(h)}"
    )
    got.map(_.toSeq)
  }

  private def checkDelivery(
      from: Int,
      sent: Seq[BigInt],
      received: Seq[BigInt]
  ): Unit = {
    val to = 1 - from
    assert(
      received == sent,
      s"die $from to die $to: expected ${sent.size} beats, got ${received.size}" +
        (if (received.size == sent.size) ", in the wrong order or corrupted"
         else "")
    )
  }

  // ---------------------------------------------------------------------------
  // The ladder
  // ---------------------------------------------------------------------------

  describe("UCIe digital stack bring-up") {

    it("Stage 1: acknowledges the RDI wake request") {
      simulate(new UcieDigitalLoopbackHarness(), firtoolOpts = firtoolOpts) {
        h =>
          for (die <- 0 until 2) {
            h.io.swStartLinkTraining(die).poke(false.B)
            h.io.pwrGood(die).poke(true.B)
            h.io.protoCtrl(die).poke(ProtoCtrl.word().U)
          }
          // The adapter holds lp_wake_req from reset, so the PHY has to answer
          // without waiting out the reset minimum.
          stepUntil(h, flagCycles, "RDI wake ack")(
            bothDies(flag(h, _, DieFlag.rdiPlWakeAck))
          )
          for (die <- 0 until 2) {
            h.io
              .ltsmState(die)
              .expect(LTSMState.sRESET, "training has not begun")
            h.io.rdiState(die).expect(RDIState.reset)
            assert(!flag(h, die, DieFlag.rdiInbandPres))
          }
      }
    }

    // Collapses the nine LogPhy stages into one. A real adapter drives the RDI
    // here, so the clock and stall handshakes and the cfg credits are hardware
    // rather than testbench pokes.
    it("Stage 2: trains to ACTIVE with a real adapter") {
      simulate(new UcieDigitalLoopbackHarness(), firtoolOpts = firtoolOpts) {
        h =>
          coldStart(h)
          stepUntil(h, trainCycles, "RDI active")(
            bothDies(die =>
              h.io.rdiState(die).peek().litValue == RDIState.active.litValue
            )
          )
          // The RDI goes active while the LTSM is still in LINKINIT, so ACTIVE
          // is a separate wait rather than something to assert right here.
          stepUntil(h, handshakeCycles, "LTSM ACTIVE")(
            bothDies(die =>
              h.io.ltState(die).peek().litValue == LTState.sACTIVE.litValue
            )
          )
          for (die <- 0 until 2) {
            h.io.ltsmState(die).expect(LTSMState.sACTIVE)
            assert(!flag(h, die, DieFlag.phyTrainError))
            assert(!flag(h, die, DieFlag.phyTimedout))
            assert(
              !flag(h, die, DieFlag.phyRecenter),
              "recentering must be done"
            )
            assert(
              !flag(h, die, DieFlag.rdiStallReq),
              "no stall may be pending"
            )
            assert(flag(h, die, DieFlag.rdiInbandPres))
          }
      }
    }

    // The advertised capability payload is hardcoded, so nothing is really
    // negotiated yet. This checks the exchange completes and presence follows.
    it("Stage 3: exchanges ADV_CAP") {
      simulate(new UcieDigitalLoopbackHarness(), firtoolOpts = firtoolOpts) {
        h =>
          coldStart(h)
          stepUntil(h, trainCycles, "FDI inband presence")(
            bothDies(flag(h, _, DieFlag.fdiInbandPres))
          )
          for (die <- 0 until 2) {
            assert(
              h.io.adapterLinkInit(die).peek().litValue !=
                LinkInitState.INIT_START.litValue,
              s"die $die never left the initial link-init state"
            )
          }
      }
    }

    it("Stage 4: latches the negotiated protocol") {
      simulate(new UcieDigitalLoopbackHarness(), firtoolOpts = firtoolOpts) {
        h =>
          coldStart(h)
          stepUntil(h, trainCycles, "negotiated protocol")(
            bothDies(flag(h, _, DieFlag.negotiatedProto))
          )
          for (die <- 0 until 2) {
            assert(flag(h, die, DieFlag.fdiProtocolVld))
          }
      }
    }

    it("Stage 5: requests FDI Active") {
      simulate(new UcieDigitalLoopbackHarness(), firtoolOpts = firtoolOpts) {
        h =>
          coldStart(h)
          stepUntil(h, trainCycles, "RDI active")(
            bothDies(die =>
              h.io.rdiState(die).peek().litValue == RDIState.active.litValue
            )
          )
          for (die <- 0 until 2) {
            h.io.protoCtrl(die).poke(ProtoCtrl.word(reqActive = true).U)
          }
          stepUntil(h, handshakeCycles, "REQ_ACTIVE crossing")(
            bothDies(flag(h, _, DieFlag.actReqRcvd))
          )
          for (die <- 0 until 2) {
            assert(
              flag(h, die, DieFlag.fdiLpReqActive),
              "the FDI request must be held"
            )
            assert(flag(h, die, DieFlag.actReqSent))
          }
          stepUntil(h, handshakeCycles, "adapter transition to active")(
            bothDies(flag(h, _, DieFlag.transitionToActive))
          )
      }
    }

    it("Stage 6: reports the receiver alive") {
      simulate(new UcieDigitalLoopbackHarness(), firtoolOpts = firtoolOpts) {
        h =>
          bringUpToActive(h)
          for (die <- 0 until 2) {
            assert(flag(h, die, DieFlag.fdiRxActiveReq))
            assert(flag(h, die, DieFlag.fdiRxActiveSts))
            assert(!flag(h, die, DieFlag.rxOverflow))
          }
      }
    }

    it("Stage 7: reaches FDI active") {
      simulate(new UcieDigitalLoopbackHarness(), firtoolOpts = firtoolOpts) {
        h =>
          bringUpToActive(h)
          for (die <- 0 until 2) {
            h.io.fdiState(die).expect(FDIState.active)
            h.io.rdiState(die).expect(RDIState.active)
            assert(flag(h, die, DieFlag.fdiInbandPres))
            assert(flag(h, die, DieFlag.fdiProtocolVld))
            assert(flag(h, die, DieFlag.rdiLpReqActive))
            // The protocol layer drops back to nop once the FDI leaves reset,
            // which re-arms the edge the adapter needs for the next bring-up.
            assert(
              !flag(h, die, DieFlag.fdiLpReqActive),
              s"die $die still holds the FDI Active request after reaching active"
            )
            assert(!flag(h, die, DieFlag.protoStalled))
            assert(!flag(h, die, DieFlag.rxOverflow))
          }
      }
    }

    it("Stage 8: opens the chip interface") {
      simulate(
        new UcieDigitalLoopbackHarness(exposeDataPath = true),
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        stepUntil(h, flagCycles, "chip TX ready")(
          bothDies(flag(h, _, DieFlag.chipTxReady))
        )
        // Touching the state for one cycle is not the same as holding the link.
        h.clock.step(20000)
        for (die <- 0 until 2) {
          h.io.fdiState(die).expect(FDIState.active, "the link did not hold")
          assert(!flag(h, die, DieFlag.phyTrainError))
          assert(!flag(h, die, DieFlag.fdiStallReq), "no stall may be pending")
          assert(!flag(h, die, DieFlag.fdiPlValid), "no beat may be presented")
        }
      }
    }

    it("Stage 9: carries one protocol beat each way") {
      simulate(
        new UcieDigitalLoopbackHarness(exposeDataPath = true),
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        val sent = Seq(
          Seq(payload(h.beatBits, 0, 0)),
          Seq(payload(h.beatBits, 1, 0))
        )
        val got = exchange(h, sent)
        checkDelivery(from = 0, sent = sent(0), received = got(1))
        checkDelivery(from = 1, sent = sent(1), received = got(0))
        for (die <- 0 until 2) {
          assert(
            !flag(h, die, DieFlag.fdiPlValid),
            s"die $die still holds FDI pl_valid after the beat was delivered"
          )
        }
      }
    }

    it("Stage 10: carries simultaneous bursts") {
      simulate(
        new UcieDigitalLoopbackHarness(exposeDataPath = true),
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        val sent = Seq(
          (0 until burstLength).map(payload(h.beatBits, 0, _)),
          (0 until burstLength).map(payload(h.beatBits, 1, _))
        )
        val got = exchange(h, sent)
        checkDelivery(from = 0, sent = sent(0), received = got(1))
        checkDelivery(from = 1, sent = sent(1), received = got(0))
        for (die <- 0 until 2) {
          h.io.fdiState(die).expect(FDIState.active, "the link did not hold")
          assert(!flag(h, die, DieFlag.rxOverflow))
          assert(!flag(h, die, DieFlag.phyTrainError))
        }
      }
    }

    it("Stage 11: carries adapter sideband traffic") {
      simulate(new UcieDigitalLoopbackHarness(), firtoolOpts = firtoolOpts) {
        h =>
          bringUpToActive(h)
          for (die <- 0 until 2; (bit, name) <- DieFlag.sbFaults) {
            assert(
              !flag(h, die, bit),
              s"die $die latched a sideband $name fault during link init"
            )
          }
      }
    }
  }
}
