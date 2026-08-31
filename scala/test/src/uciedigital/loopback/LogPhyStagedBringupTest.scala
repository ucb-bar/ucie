package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import org.scalatest.funspec.AnyFunSpec

/** Walks two cross-wired LogicalPhy instances up the link training state
  * machine, one state per test, from RESET to ACTIVE and then across the
  * mainband.
  *
  * The tests follow the LTSM in order:
  *
  * RESET -> SBINIT -> MBINIT -> MBTRAIN -> LINKINIT -> ACTIVE -> data
  *
  * Every test is a cold start that climbs to the state it names, so the tests
  * are independent and the first failing test names the first state the link
  * cannot reach.
  *
  * Only die 0 takes the software trigger. Die 1 has to wake on the sideband
  * clock pattern die 0 transmits, which is the arrival order two chiplets
  * actually see and which a testbench that pulses both dies together would
  * hide.
  */
class LogPhyStagedBringupTest extends AnyFunSpec with ChiselSim {

  // The LTSM asserts on substates the link passes through on its way up.
  private val firtoolOpts = Array(
    "--disable-layers=Verification,Verification.Assert,Verification.Assume,Verification.Cover"
  )

  // The LTSM holds RESET for half of its 6.4M-cycle substate timeout.
  private val resetWait = 3200000

  // Cycle budgets per milestone, sized to the sideband exchanges each one runs.
  private val sbinitEntryCycles = 4096
  private val sidebandCycles = 400000
  private val mbInitCycles = 800000
  private val mbTrainCycles = 1500000
  private val rdiFlagCycles = 4096

  private val rdiWordBits = 512
  private val burstLength = 4

  // The forward bring-up path. A die that derails into TRAINERROR, PHYRETRAIN
  // or L1_L2 is not on this list, so it never satisfies a milestone.
  private val forwardPath = Seq(
    LTState.sRESET,
    LTState.sSBINIT,
    LTState.sMBINIT,
    LTState.sMBTRAIN,
    LTState.sLINKINIT,
    LTState.sACTIVE
  )

  // ---------------------------------------------------------------------------
  // Observing and stepping
  // ---------------------------------------------------------------------------

  /** Both dies, as "die0=MBINIT/MBINIT_REPAIRCLK, die1=TRAINERROR/TRAINERROR".
    */
  private def states(h: LogPhyLoopbackHarness): String =
    (0 until 2)
      .map { die =>
        s"die$die=${h.io.ltState(die).peek()}/${h.io.ltsmState(die).peek()}"
      }
      .mkString(", ")

  private def bothDies(check: Int => Boolean): Boolean = check(0) && check(1)

  /** True once both dies are at `target` or past it on the forward path. */
  private def reached(
      h: LogPhyLoopbackHarness,
      target: LTState.Type
  ): Boolean = {
    val goal = forwardPath.indexOf(target)
    bothDies { die =>
      val at = h.io.ltState(die).peek().litValue
      forwardPath.indexWhere(_.litValue == at) >= goal
    }
  }

  /** True once a die has left the forward path, which it cannot come back from
    * on its own. A training error drains back to RESET, so a test that kept
    * stepping would report RESET and hide where the link actually broke.
    */
  private def derailed(h: LogPhyLoopbackHarness): Boolean =
    (0 until 2).exists { die =>
      val at = h.io.ltState(die).peek().litValue
      !forwardPath.exists(_.litValue == at)
    }

  /** Step until `done` holds, then fail naming the milestone if it never did.
    */
  private def stepUntil(
      h: LogPhyLoopbackHarness,
      limit: Int,
      milestone: String
  )(
      done: => Boolean
  ): Unit = {
    var left = limit
    while (left > 0 && !done && !derailed(h)) {
      h.clock.step(1)
      left -= 1
    }
    assert(done, s"$milestone was not reached: ${states(h)}")
  }

  private def climbTo(
      h: LogPhyLoopbackHarness,
      target: LTState.Type,
      limit: Int
  ): Unit =
    stepUntil(h, limit, s"$target")(reached(h, target))

  /** Sit out the hardware reset wait, then trigger die 0 alone. */
  private def coldStart(h: LogPhyLoopbackHarness): Unit = {
    for (die <- 0 until 2) {
      h.io.lpStateReq(die).poke(RDIStateReq.nop)
      h.io.swStartLinkTraining(die).poke(false.B)
      h.io.pwrGood(die).poke(true.B)
    }
    h.clock.step(resetWait + 128)
    for (die <- 0 until 2) {
      h.io.ltState(die).expect(LTState.sRESET, "no training without a trigger")
    }
    h.io.swStartLinkTraining(0).poke(true.B)
    h.clock.step(4)
    h.io.swStartLinkTraining(0).poke(false.B)
  }

  /** Climb all the way to a live link, which every ACTIVE-level test needs. */
  private def bringUpToActive(h: LogPhyLoopbackHarness): Unit = {
    coldStart(h)
    climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
    climbTo(h, LTState.sMBINIT, sidebandCycles)
    climbTo(h, LTState.sMBTRAIN, mbInitCycles)
    climbTo(h, LTState.sLINKINIT, mbTrainCycles)
    climbTo(h, LTState.sACTIVE, sidebandCycles)
  }

  // ---------------------------------------------------------------------------
  // Mainband data
  // ---------------------------------------------------------------------------

  /** A word tagged by die and sequence number, so a swapped, stale or
    * lane-permuted word each fail differently.
    */
  private def payload(die: Int, seq: Int): BigInt =
    (0 until rdiWordBits / 16).foldLeft(BigInt(0)) { (acc, i) =>
      val half =
        ((die + 1) << 12) | ((seq + 1) << 8) | ((i * 7 + die * 3 + seq) & 0xff)
      acc | (BigInt(half & 0xffff) << (i * 16))
    }

  /** Send one word and check the peer receives it byte for byte. */
  private def sendWord(
      h: LogPhyLoopbackHarness,
      from: Int,
      word: BigInt
  ): Unit = {
    val to = 1 - from
    h.io.lpData.get(from).poke(word.U(rdiWordBits.W))
    h.io.lpValid.get(from).poke(true.B)
    h.io.lpIrdy.get(from).poke(true.B)

    stepUntil(h, rdiFlagCycles, s"die $from ready to send")(
      h.io.plTrdy(from).peekBoolean()
    )
    h.io.plValid(to).expect(true.B, s"die $to missed the word from die $from")
    h.io.plData.get(to).expect(word.U, s"die $from to die $to corrupted a word")

    h.clock.step(1)
    h.io.lpValid.get(from).poke(false.B)
    h.io.lpIrdy.get(from).poke(false.B)
  }

  /** Both dies send on the same cycles, so the two scrambler and descrambler
    * pairs have to advance together in both directions at once.
    */
  private def sendBothWays(
      h: LogPhyLoopbackHarness,
      words: Seq[BigInt]
  ): Unit = {
    for (die <- 0 until 2) {
      h.io.lpData.get(die).poke(words(die).U(rdiWordBits.W))
      h.io.lpValid.get(die).poke(true.B)
      h.io.lpIrdy.get(die).poke(true.B)
    }

    stepUntil(h, rdiFlagCycles, "both dies ready to send")(
      bothDies(h.io.plTrdy(_).peekBoolean())
    )
    for (from <- 0 until 2) {
      val to = 1 - from
      h.io.plValid(to).expect(true.B, s"die $to missed the word from die $from")
      h.io.plData
        .get(to)
        .expect(words(from).U, s"die $from to die $to corrupted a word")
    }

    h.clock.step(1)
    for (die <- 0 until 2) {
      h.io.lpValid.get(die).poke(false.B)
      h.io.lpIrdy.get(die).poke(false.B)
    }
  }

  // ---------------------------------------------------------------------------
  // The ladder
  // ---------------------------------------------------------------------------

  describe("LogicalPhy link training") {

    // Die 0 alone is triggered. Die 1 has to count two clock patterns off the
    // sideband to wake, which is the arrival order two chiplets actually see.
    it("Stage 1: reaches SBINIT") {
      simulate(new LogPhyLoopbackHarness(), firtoolOpts = firtoolOpts) { h =>
        coldStart(h)
        climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
        for (die <- 0 until 2) {
          h.io.ltsmState(die).expect(LTSMState.sSBINIT)
          h.io.plPhyInRecenter(die).expect(true.B)
          h.io.plStateSts(die).expect(RDIState.reset)
          h.io.trainingTimedout(die).expect(false.B)
        }
      }
    }

    it("Stage 2: completes SBINIT") {
      simulate(new LogPhyLoopbackHarness(), firtoolOpts = firtoolOpts) { h =>
        coldStart(h)
        climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
        climbTo(h, LTState.sMBINIT, sidebandCycles)
        for (die <- 0 until 2) {
          h.io.sbFaultSeen(die).expect(false.B, "sideband fault during SBINIT")
          h.io.trainingTimedout(die).expect(false.B)
          h.io.plTrainError(die).expect(false.B)
        }
      }
    }

    // Both dies advertise the same all-zero parameters, so this only proves the
    // exchange runs. Real interoperability needs dies that differ.
    it("Stage 3: negotiates PHY parameters") {
      simulate(new LogPhyLoopbackHarness(), firtoolOpts = firtoolOpts) { h =>
        coldStart(h)
        climbTo(h, LTState.sMBINIT, sidebandCycles)
        stepUntil(h, sidebandCycles, "PARAM exchange")(
          bothDies(h.io.negotiatedParamsValid(_).peekBoolean())
        )
        for (die <- 0 until 2) {
          h.io.plTrainError(die).expect(false.B)
          h.io.plSpeedmode(die).expect(SpeedMode.speed4)
        }
      }
    }

    it("Stage 4: completes MBINIT") {
      simulate(new LogPhyLoopbackHarness(), firtoolOpts = firtoolOpts) { h =>
        coldStart(h)
        climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
        climbTo(h, LTState.sMBINIT, sidebandCycles)
        climbTo(h, LTState.sMBTRAIN, mbInitCycles)
        for (die <- 0 until 2) {
          h.io.trainingTimedout(die).expect(false.B)
          h.io.plTrainError(die).expect(false.B)
          h.io.sbFaultSeen(die).expect(false.B, "sideband fault during MBINIT")
        }
      }
    }

    // The vref and centering substates complete immediately: PhyLaneTrainer has
    // no calibration hardware to drive, so it holds req.complete high. Revisit
    // once the analog knobs are wired.
    it("Stage 5: completes MBTRAIN") {
      simulate(new LogPhyLoopbackHarness(), firtoolOpts = firtoolOpts) { h =>
        coldStart(h)
        climbTo(h, LTState.sMBINIT, sidebandCycles)
        climbTo(h, LTState.sMBTRAIN, mbInitCycles)
        climbTo(h, LTState.sLINKINIT, mbTrainCycles)
        for (die <- 0 until 2) {
          h.io.plSpeedmode(die).expect(SpeedMode.speed4)
          h.io.trainingTimedout(die).expect(false.B)
          h.io.plTrainError(die).expect(false.B)
        }
      }
    }

    it("Stage 6: raises inband presence") {
      simulate(new LogPhyLoopbackHarness(), firtoolOpts = firtoolOpts) { h =>
        coldStart(h)
        climbTo(h, LTState.sMBTRAIN, mbInitCycles)
        climbTo(h, LTState.sLINKINIT, mbTrainCycles)
        stepUntil(h, rdiFlagCycles, "inband presence")(
          bothDies(h.io.plInbandPres(_).peekBoolean())
        )
        for (die <- 0 until 2) h.io.plTrainError(die).expect(false.B)
      }
    }

    it("Stage 7: brings the RDI to active") {
      simulate(new LogPhyLoopbackHarness(), firtoolOpts = firtoolOpts) { h =>
        coldStart(h)
        climbTo(h, LTState.sMBTRAIN, mbInitCycles)
        climbTo(h, LTState.sLINKINIT, mbTrainCycles)
        stepUntil(h, sidebandCycles, "RDI active")(
          bothDies(die =>
            h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
          )
        )
        for (die <- 0 until 2) {
          h.io.plStateSts(die).expect(RDIState.active)
          h.io.plInbandPres(die).expect(true.B)
          h.io.plTrainError(die).expect(false.B)
        }
      }
    }

    // Touching ACTIVE for one cycle is not the same as holding the link up.
    it("Stage 8: holds ACTIVE") {
      simulate(new LogPhyLoopbackHarness(), firtoolOpts = firtoolOpts) { h =>
        bringUpToActive(h)
        for (die <- 0 until 2) {
          h.io.ltsmState(die).expect(LTSMState.sACTIVE)
          h.io.plStateSts(die).expect(RDIState.active)
          h.io.plInbandPres(die).expect(true.B)
          h.io.plSpeedmode(die).expect(SpeedMode.speed4)
          h.io.plPhyInRecenter(die).expect(false.B, "recentering must be done")
          h.io
            .sbFaultSeen(die)
            .expect(false.B, "sideband fault during training")
          h.io.trainingTimedout(die).expect(false.B)
        }

        h.clock.step(20000)
        for (die <- 0 until 2) {
          h.io.ltsmState(die).expect(LTSMState.sACTIVE, "the link did not hold")
          h.io.plStateSts(die).expect(RDIState.active)
          h.io.plTrainError(die).expect(false.B)
        }
      }
    }

    // Bursts matter: each scrambler steps once per accepted word, so a lockstep
    // error corrupts the second word rather than the first.
    it("Stage 9: carries mainband data") {
      simulate(
        new LogPhyLoopbackHarness(dataPath = true),
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)

        sendWord(h, 0, payload(0, 0))
        sendWord(h, 1, payload(1, 0))
        for (seq <- 1 until burstLength) sendWord(h, 0, payload(0, seq))
        for (seq <- 1 until burstLength) sendWord(h, 1, payload(1, seq))
        for (seq <- 0 until burstLength) {
          sendBothWays(h, Seq(payload(0, seq), payload(1, seq)))
        }

        for (die <- 0 until 2) {
          h.io.ltsmState(die).expect(LTSMState.sACTIVE, "the link did not hold")
          h.io.plStateSts(die).expect(RDIState.active)
          h.io.plTrainError(die).expect(false.B)
          h.io.sbFaultSeen(die).expect(false.B)
        }
      }
    }
  }
}
