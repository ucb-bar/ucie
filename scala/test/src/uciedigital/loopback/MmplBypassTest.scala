package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import org.scalatest.funspec.AnyFunSpec
import scala.collection.mutable

/** A bypass-capable two-module group run both ways, spec 4.7.2 Figure 4-49.
  *
  * Bypassed, each Module is a Link of its own to a different single-module die
  * (MmplBypassHarness): configuration (a), with the MMPL out of the way. The
  * stages check that each Link trains on its own trigger, reaches Active at one
  * Module's width, carries its own data, and comes up, runs and retrains
  * without disturbing the other. With the bypass clear, the same hardware is
  * one multi-module Link to a matching die (configuration (b)).
  *
  * Timeouts are shortened for simulation, as in MmplStagedBringupTest.
  */
class MmplBypassTest extends AnyFunSpec with ChiselSim {

  // The LTSM asserts on substates the link passes through on its way up.
  private val firtoolOpts = Array(
    "--disable-layers=Verification,Verification.Assert,Verification.Assume,Verification.Cover"
  )

  private val trainingTimeout = 262144
  private val resetWait = trainingTimeout / 2

  // Cycle budgets per milestone, as in MmplStagedBringupTest.
  private val sbinitEntryCycles = 8192
  private val sidebandCycles = 400000
  private val mbInitCycles = 800000
  private val mbTrainCycles = 1500000
  private val rdiFlagCycles = 8192

  private val params = MmplParams(numModules = 2, bypassable = true)
  private val links = 0 until params.numModules
  private val sides = 0 until 2
  private val sideName = Seq("die 0", "partner")

  private type H = MmplBypassHarness

  private def harness(dataPath: Boolean) = new MmplBypassHarness(
    params = params,
    dataPath = dataPath,
    timeoutCyclesOverride = Some(trainingTimeout)
  )

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
  private def ltIs(h: H, link: Int, side: Int, state: LTState.Type): Boolean =
    h.io.ltState(link)(side).peek().litValue == state.litValue

  private def rdiIs(h: H, link: Int, side: Int, state: RDIState.Type): Boolean =
    h.io.plStateSts(link)(side).peek().litValue == state.litValue

  private def atLeast(h: H, link: Int, side: Int, target: LTState.Type) = {
    val at = h.io.ltState(link)(side).peek().litValue
    forwardPath.indexWhere(_.litValue == at) >= forwardPath.indexOf(target)
  }

  private def states(h: H): String =
    links
      .map { l =>
        val ends = sides
          .map { s =>
            s"${sideName(s)}=${h.io.ltState(l)(s).peek()}/" +
              s"${h.io.ltsmState(l)(s).peek()}/${h.io.plStateSts(l)(s).peek()}"
          }
          .mkString(" ")
        s"link$l[$ends]"
      }
      .mkString(", ") + s", aggregate=${h.io.aggPlStateSts.peek()}"

  /** Steps until `done`, checking `invariant` every cycle on the way. A Link
    * that drops into TRAINERROR fails at once rather than at the budget.
    */
  private def stepUntil(
      h: H,
      limit: Int,
      milestone: String,
      invariant: => Unit = ()
  )(done: => Boolean): Unit = {
    var left = limit
    while (left > 0 && !done) {
      invariant
      for (l <- links; s <- sides) {
        assert(
          !ltIs(h, l, s, LTState.sTRAINERROR),
          s"link $l ${sideName(s)} went to TRAINERROR on the way to " +
            s"$milestone: ${states(h)}"
        )
      }
      h.clock.step(1)
      left -= 1
    }
    assert(done, s"$milestone was not reached: ${states(h)}")
  }

  /** Bypass, sit out the reset wait, then trigger `trigger` on die 0. */
  private def coldStart(h: H, trigger: Seq[Int]): Unit = {
    h.io.bypass.poke(true.B)
    for (l <- links) {
      h.io.swStartLinkTraining(l).poke(false.B)
      for (s <- sides) h.io.lpStateReq(l)(s).poke(RDIStateReq.nop)
    }
    h.clock.step(resetWait + 128)
    // Every Module is in RESET with its RDI in Reset, so the bit took.
    h.io.bypassed.expect(true.B, "the bypass was not taken in RESET")
    for (l <- links; s <- sides) {
      h.io
        .ltState(l)(s)
        .expect(LTState.sRESET, s"link $l ${sideName(s)} trained untriggered")
    }
    startTraining(h, trigger)
  }

  private def startTraining(h: H, trigger: Seq[Int]): Unit = {
    for (l <- trigger) h.io.swStartLinkTraining(l).poke(true.B)
    h.clock.step(4)
    for (l <- trigger) h.io.swStartLinkTraining(l).poke(false.B)
  }

  private val ladder = Seq(
    LTState.sSBINIT -> sbinitEntryCycles,
    LTState.sMBINIT -> sidebandCycles,
    LTState.sMBTRAIN -> mbInitCycles,
    LTState.sLINKINIT -> mbTrainCycles
  )

  /** Climbs `ls` at both ends to LINKINIT, then asks for Active as each end's
    * Adapter would on pl_inband_pres (spec 10.1.6 Step 2).
    */
  private def train(h: H, ls: Seq[Int], invariant: => Unit = ()): Unit = {
    for ((target, budget) <- ladder) {
      stepUntil(h, budget, s"links $ls at $target", invariant)(
        ls.forall(l => sides.forall(atLeast(h, l, _, target)))
      )
    }
    activate(h, ls, invariant)
  }

  private def activate(h: H, ls: Seq[Int], invariant: => Unit = ()): Unit = {
    for (l <- ls; s <- sides) h.io.lpStateReq(l)(s).poke(RDIStateReq.active)
    stepUntil(h, sidebandCycles, s"links $ls Active", invariant)(
      ls.forall(l =>
        sides.forall(s =>
          ltIs(h, l, s, LTState.sACTIVE) && rdiIs(h, l, s, RDIState.active)
        )
      )
    )
  }

  /** Link `link` stays up at both ends: the check that something done to the
    * other Link does not reach this one.
    */
  private def holdsActive(h: H, link: Int): Unit =
    for (s <- sides) {
      assert(
        ltIs(h, link, s, LTState.sACTIVE) && rdiIs(h, link, s, RDIState.active),
        s"link $link ${sideName(s)} left Active: ${states(h)}"
      )
    }

  /** A bypassed die's aggregate RDI is no Link at all. */
  private def aggregateIdle(h: H): Unit = {
    h.io.aggPlStateSts.expect(RDIState.reset, "aggregate RDI left Reset")
    h.io.aggPlInbandPres.expect(false.B, "aggregate RDI inband present")
    h.io.aggPlValid.expect(false.B, "aggregate RDI delivered a word")
    h.io.aggPlTrdy.expect(false.B, "aggregate RDI took a word")
  }

  // ---------------------------------------------------------------------------
  // Mainband data
  // ---------------------------------------------------------------------------
  private val wordBits = params.moduleRdiParams(32).nBytes * 8

  /** A word tagged by Link, end, sequence number and byte position, so a word
    * delivered on the wrong Link, to the wrong end, stale, or permuted each
    * fails differently.
    */
  private def payload(link: Int, side: Int, seq: Int): BigInt =
    (0 until wordBits / 16).foldLeft(BigInt(0)) { (acc, i) =>
      val half = ((link * 2 + side + 1) << 12) | ((seq + 1) << 8) |
        ((i * 7 + link * 5 + side * 3 + seq) & 0xff)
      acc | (BigInt(half & 0xffff) << (i * 16))
    }

  /** Sends one tagged word from each end of every Link in `ls` at once, and
    * checks each arrives once, intact, at the other end of its own Link. Each
    * end's Adapter holds its word until that end's pl_trdy, so the Links run on
    * their own timing.
    */
  private def exchange(h: H, ls: Seq[Int], seq: Int): Unit = {
    val ends = for (l <- ls; s <- sides) yield (l, s)
    val accepted = mutable.Set[(Int, Int)]()
    val received = mutable.Map[(Int, Int), BigInt]()

    for ((l, s) <- ends) {
      h.io.lpData.get(l)(s).poke(payload(l, s, seq).U(wordBits.W))
      h.io.lpValid.get(l)(s).poke(true.B)
      h.io.lpIrdy.get(l)(s).poke(true.B)
    }

    def observe(): Unit = {
      for ((l, s) <- ends if h.io.plValid(l)(s).peekBoolean()) {
        assert(
          !received.contains((l, s)),
          s"link $l ${sideName(s)} received a second word"
        )
        received((l, s)) = h.io.plData.get(l)(s).peek().litValue
      }
      h.io.aggPlValid.expect(false.B, "aggregate RDI delivered a word")
    }

    var left = rdiFlagCycles
    while (left > 0 && (accepted.size < ends.size || received.size < ends.size)) {
      observe()
      val takenNow = ends.filter(e =>
        !accepted(e) && h.io.plTrdy(e._1)(e._2).peekBoolean()
      )
      h.clock.step(1)
      for ((l, s) <- takenNow) {
        accepted += ((l, s))
        h.io.lpValid.get(l)(s).poke(false.B)
        h.io.lpIrdy.get(l)(s).poke(false.B)
      }
      left -= 1
    }

    // Nothing more may follow.
    for (_ <- 0 until 64) {
      observe()
      h.clock.step(1)
    }

    for ((l, s) <- ends) {
      assert(accepted((l, s)), s"link $l ${sideName(s)} never took its word")
      val to = (l, 1 - s)
      assert(
        received.contains(to),
        s"link $l ${sideName(1 - s)} never received the word: ${states(h)}"
      )
      assert(
        received(to) == payload(l, s, seq),
        f"link $l ${sideName(s)} to ${sideName(1 - s)} corrupted a word: " +
          f"sent ${payload(l, s, seq)}%x, received ${received(to)}%x"
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Bypassed: two Links to two dies
  // ---------------------------------------------------------------------------
  describe("Bypassed MMPL: each Module is a Link to a die of its own") {
    it("Stage 1: each Module trains to Active at one Module's width") {
      simulate(harness(dataPath = false), firtoolOpts = firtoolOpts) { h =>
        coldStart(h, links)
        train(h, links)

        for (l <- links; s <- sides) {
          val end = s"link $l ${sideName(s)}"
          // Spec 5.7.3.4: a Module that is a Link of its own is M0 of it, so
          // each end learned Module ID 0 from the other -- die 0's Module 1 as
          // much as its Module 0.
          h.io.remoteModuleId(l)(s).expect(0.U, s"$end remote Module ID")
          h.io.plInbandPres(l)(s).expect(true.B, s"$end inband present")
          h.io.plTrainError(l)(s).expect(false.B, s"$end training error")
          // One Module's width, not the summed x32 of the multi-module Link.
          h.io.plLnkCfg(l)(s).expect(LinkWidth.x16, s"$end pl_lnk_cfg")
          h.io.sbFaultSeen(l)(s).expect(false.B, s"$end sideband fault")
        }
        aggregateIdle(h)

        // Asking for the MMPL back while the Links are up changes nothing
        // until the whole group is down again.
        h.io.bypass.poke(false.B)
        for (_ <- 0 until 2048) {
          links.foreach(holdsActive(h, _))
          h.clock.step(1)
        }
        h.io.bypassed.expect(true.B, "the bypass was dropped with Links up")
        aggregateIdle(h)
      }
    }

    it("Stage 2: each Link carries its own data, at once and both ways") {
      simulate(harness(dataPath = true), firtoolOpts = firtoolOpts) { h =>
        coldStart(h, links)
        train(h, links)
        for (seq <- 0 until 4) exchange(h, links, seq)
        for (l <- links; s <- sides) {
          h.io
            .sbFaultSeen(l)(s)
            .expect(false.B, s"link $l ${sideName(s)} sideband fault")
        }
      }
    }

    it("Stage 3: one Link comes up and runs while the other is in RESET") {
      simulate(harness(dataPath = true), firtoolOpts = firtoolOpts) { h =>
        // Only Module 0 is triggered. Module 1 and its partner must not be
        // pulled along, as a sibling of a multi-module Link would be.
        def link1Untouched(): Unit =
          for (s <- sides) {
            assert(
              ltIs(h, 1, s, LTState.sRESET),
              s"link 1 ${sideName(s)} left RESET untriggered: ${states(h)}"
            )
          }

        coldStart(h, Seq(0))
        train(h, Seq(0), link1Untouched())
        for (seq <- 0 until 2) exchange(h, Seq(0), seq)
        link1Untouched()

        // Now Module 1: Link 0 stays up and keeps moving data while Link 1
        // trains beside it.
        startTraining(h, Seq(1))
        stepUntil(h, mbInitCycles, "link 1 in MBTRAIN", holdsActive(h, 0)) {
          sides.forall(atLeast(h, 1, _, LTState.sMBTRAIN))
        }
        exchange(h, Seq(0), 2)
        train(h, Seq(1), holdsActive(h, 0))
        exchange(h, links, 3)
        aggregateIdle(h)
      }
    }

    it("Stage 4: one Link retrains while the other stays Active") {
      simulate(harness(dataPath = true), firtoolOpts = firtoolOpts) { h =>
        coldStart(h, links)
        train(h, links)

        // Die 0's Adapter on Link 1 asks for a retrain; the partner's makes no
        // request while it runs, as spec 10.3.3.4 has it wait for the PHY.
        h.io.lpStateReq(1)(1).poke(RDIStateReq.nop)
        h.io.lpStateReq(1)(0).poke(RDIStateReq.retrain)
        stepUntil(
          h,
          sidebandCycles,
          "link 1 in Retrain and PHYRETRAIN",
          holdsActive(h, 0)
        ) {
          sides.forall(s =>
            rdiIs(h, 1, s, RDIState.retrain) &&
              ltIs(h, 1, s, LTState.sPHYRETRAIN)
          )
        }
        h.io.lpStateReq(1)(0).poke(RDIStateReq.nop)
        // Link 0 is still a working Link while Link 1 is down.
        exchange(h, Seq(0), 0)

        // Back through MBTRAIN to LINKINIT, and Retrain to Active on NOP to
        // Active (spec 10.3.3.4).
        stepUntil(h, mbTrainCycles, "link 1 back in LINKINIT", holdsActive(h, 0)) {
          sides.forall(ltIs(h, 1, _, LTState.sLINKINIT))
        }
        activate(h, Seq(1), holdsActive(h, 0))
        for (seq <- 1 until 3) exchange(h, links, seq)
        aggregateIdle(h)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Bypass clear: one Link of two Modules
  // ---------------------------------------------------------------------------
  describe("MMPL in use on bypass-capable Modules") {
    it("Stage 5: the two Modules are one Link at the summed width") {
      /* The same hardware as the stages above, facing a matching bypass-capable
         die with the bypass clear on both: each Module's RDI state machine of
         its own is held in reset, and the MMPL's hosted one brings the Link up
         over the aggregate RDI. M0 faces M1, the spec Figure 4-44 case, so a
         word only crosses intact if the byte map ranks by the remote Module ID
         -- which the Modules must still advertise as their index. */
      type L = MmplLoopbackHarness
      val modules = 0 until params.numModules
      val aggWordBits = params.rdiParams(32).nBytes * 8

      def everyModuleAt(h: L, target: LTState.Type): Boolean =
        (0 until 2).forall(die =>
          modules.forall { m =>
            val at = h.io.ltState(die)(m).peek().litValue
            forwardPath.indexWhere(_.litValue == at) >=
              forwardPath.indexOf(target)
          }
        )
      def climb(h: L, limit: Int, milestone: String)(done: => Boolean) = {
        var left = limit
        while (left > 0 && !done) {
          h.clock.step(1)
          left -= 1
        }
        val at = (0 until 2)
          .map(die => modules.map(m => h.io.ltState(die)(m).peek()))
        assert(done, s"$milestone was not reached: $at")
      }

      simulate(
        new MmplLoopbackHarness(
          params = params,
          modulePairing = Seq(1, 0),
          dataPath = true,
          timeoutCyclesOverride = Some(trainingTimeout)
        ),
        firtoolOpts = firtoolOpts
      ) { h =>
        for (die <- 0 until 2) {
          h.io.lpStateReq(die).poke(RDIStateReq.nop)
          h.io.swStartLinkTraining(die).poke(false.B)
          h.io.pwrGood(die).poke(true.B)
          for (m <- modules) h.io.changeInRuntimeLinkCtrlRegs(die)(m).poke(false.B)
          h.io.lpValid.get(die).poke(false.B)
          h.io.lpIrdy.get(die).poke(false.B)
        }
        h.clock.step(resetWait + 128)
        h.io.swStartLinkTraining(0).poke(true.B)
        h.clock.step(4)
        h.io.swStartLinkTraining(0).poke(false.B)

        for ((target, budget) <- ladder) {
          climb(h, budget, s"$target")(everyModuleAt(h, target))
        }
        for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
        climb(h, sidebandCycles, "aggregate RDI Active") {
          everyModuleAt(h, LTState.sACTIVE) &&
          (0 until 2).forall(die =>
            h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
          )
        }

        for (die <- 0 until 2; m <- modules) {
          h.io
            .remoteModuleId(die)(m)
            .expect((1 - m).U, s"die $die module $m remote Module ID")
          h.io.moduleEnable(die)(m).expect(true.B, s"die $die module $m enabled")
        }
        for (die <- 0 until 2) {
          h.io.plInbandPres(die).expect(true.B, s"die $die inband present")
          h.io.plLnkCfg(die).expect(LinkWidth.x32, s"die $die pl_lnk_cfg")
          h.io.sbFaultSeen(die).expect(false.B, s"die $die sideband fault")
        }

        // One tagged aggregate word each way.
        val words = (0 until 2).map { die =>
          (0 until aggWordBits / 16).foldLeft(BigInt(0)) { (acc, i) =>
            val half = ((die + 1) << 12) | ((i * 7 + die * 3) & 0xfff)
            acc | (BigInt(half & 0xffff) << (i * 16))
          }
        }
        for (die <- 0 until 2) {
          h.io.lpData.get(die).poke(words(die).U(aggWordBits.W))
          h.io.lpValid.get(die).poke(true.B)
          h.io.lpIrdy.get(die).poke(true.B)
        }
        climb(h, rdiFlagCycles, "both dies ready to send") {
          (0 until 2).forall(h.io.plTrdy(_).peekBoolean())
        }
        for (from <- 0 until 2) {
          val to = 1 - from
          h.io.plValid(to).expect(true.B, s"die $to missed the word")
          h.io
            .plData
            .get(to)
            .expect(words(from).U(aggWordBits.W), s"die $from to $to corrupted")
        }
      }
    }
  }
}
