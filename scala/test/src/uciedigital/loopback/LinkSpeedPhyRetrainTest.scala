package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import org.scalatest.funspec.AnyFunSpec

/** PHY retrain from MBTRAIN.LINKSPEED when the two ends of a Module, or the
  * Modules of a Link, are not in step (spec 4.5.3.4.12 Steps 3 to 5).
  *
  * MmplStagedBringupTest Stage 11 takes the LINKSPEED exit with every Module
  * and both ends of it in lockstep. Here one Module's transmitter is held back
  * in LINKSPEED, so its Step 2 point test is still open when the exit arrives
  * -- on its own sideband as {MBTRAIN.LINKSPEED exit to phy retrain req}, or,
  * for a sibling, as the MMPL's PHY retrain directive. Step 3/5 has that Module
  * exit to PHYRETRAIN and abandon what is outstanding. The pattern generator
  * of a stalled Module cannot finish, so the only way the Link gets out of
  * LINKSPEED is for that point test to be abandoned; a Module that waits for
  * it instead sits out its residency timeout and the Link goes to TRAINERROR.
  */
class LinkSpeedPhyRetrainTest extends AnyFunSpec with ChiselSim {

  private val firtoolOpts = Array(
    "--disable-layers=Verification,Verification.Assert,Verification.Assume,Verification.Cover"
  )

  private val trainingTimeout = 262144
  private val resetWait = trainingTimeout / 2

  private val sbinitEntryCycles = 8192
  private val sidebandCycles = 400000
  private val mbInitCycles = 800000
  private val mbTrainCycles = 1500000
  private val rdiFlagCycles = 8192

  private val forwardPath = Seq(
    LTState.sRESET,
    LTState.sSBINIT,
    LTState.sMBINIT,
    LTState.sMBTRAIN,
    LTState.sLINKINIT,
    LTState.sACTIVE
  )

  private type H = MmplLoopbackHarness

  private def modules(h: H): Range = 0 until h.params.numModules

  private def states(h: H): String =
    (0 until 2)
      .map { die =>
        val per = modules(h)
          .map(m =>
            s"m$m=${h.io.ltState(die)(m).peek()}/${h.io.ltsmState(die)(m).peek()}"
          )
          .mkString(" ")
        s"die$die[$per]"
      }
      .mkString(", ")

  private def everyModule(h: H)(check: (Int, Int) => Boolean): Boolean =
    (0 until 2).forall(die => modules(h).forall(m => check(die, m)))

  private def in(h: H, die: Int, m: Int, state: LTState.Type): Boolean =
    h.io.ltState(die)(m).peek().litValue == state.litValue

  private def allIn(h: H, state: LTState.Type): Boolean =
    everyModule(h)((die, m) => in(h, die, m, state))

  private def rdiIn(h: H, state: RDIState.Type): Boolean =
    (0 until 2).forall(die => h.io.plStateSts(die).peek().litValue == state.litValue)

  private def reached(h: H, target: LTState.Type): Boolean = {
    val goal = forwardPath.indexOf(target)
    everyModule(h) { (die, m) =>
      val at = h.io.ltState(die)(m).peek().litValue
      forwardPath.indexWhere(_.litValue == at) >= goal
    }
  }

  /** A Module that timed out or fell into TRAINERROR is how a LINKSPEED exit
    * that waits on an abandoned exchange shows up, so stop there rather than
    * burn the rest of the budget. (RESET is only reached through TRAINERROR.)
    */
  private def failed(h: H): Option[String] = {
    val bad = for {
      die <- 0 until 2
      m <- modules(h)
      if h.io.trainingTimedout(die)(m).peekBoolean() ||
        in(h, die, m, LTState.sTRAINERROR)
    } yield s"die $die module $m"
    Option.when(bad.nonEmpty)(bad.mkString(", "))
  }

  private def stepUntil(h: H, limit: Int, milestone: String)(
      done: => Boolean
  ): Unit = {
    var left = limit
    while (left > 0 && !done && failed(h).isEmpty) {
      h.clock.step(1)
      left -= 1
    }
    assert(
      done,
      s"$milestone was not reached (failed: ${failed(h).getOrElse("none")}): ${states(h)}"
    )
  }

  private def coldStart(h: H): Unit = {
    for (die <- 0 until 2) {
      h.io.lpStateReq(die).poke(RDIStateReq.nop)
      h.io.swStartLinkTraining(die).poke(false.B)
      h.io.pwrGood(die).poke(true.B)
      for (m <- modules(h)) {
        h.io.changeInRuntimeLinkCtrlRegs(die)(m).poke(false.B)
        h.io.stallMainbandTx.get(die)(m).poke(false.B)
        h.io.injectLaneError.foreach(_(die)(m).poke(false.B))
      }
    }
    h.clock.step(resetWait + 128)
    h.io.swStartLinkTraining(0).poke(true.B)
    h.clock.step(4)
    h.io.swStartLinkTraining(0).poke(false.B)
  }

  private def climbTo(h: H, target: LTState.Type, limit: Int): Unit =
    stepUntil(h, limit, s"$target")(reached(h, target))

  /** Up to ACTIVE, then an Adapter-directed retrain from die 0, so every
    * Module comes back into MBTRAIN with PHY_IN_RETRAIN set (spec 4.5.3.7).
    */
  private def retrainThroughRdi(h: H): Unit = {
    coldStart(h)
    climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
    climbTo(h, LTState.sMBINIT, sidebandCycles)
    climbTo(h, LTState.sMBTRAIN, mbInitCycles)
    climbTo(h, LTState.sLINKINIT, mbTrainCycles)
    // As an Adapter does on pl_inband_pres (spec 10.1.6 Step 2): the RDI
    // leaves Reset only on that request (spec 10.3.3.1).
    for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
    climbTo(h, LTState.sACTIVE, sidebandCycles)
    stepUntil(h, rdiFlagCycles, "aggregate RDI Active")(rdiIn(h, RDIState.active))

    h.io.lpStateReq(1).poke(RDIStateReq.nop)
    h.io.lpStateReq(0).poke(RDIStateReq.retrain)
    stepUntil(h, sidebandCycles, "every Module in PHYRETRAIN for the RDI retrain") {
      rdiIn(h, RDIState.retrain) && allIn(h, LTState.sPHYRETRAIN)
    }
    h.io.lpStateReq(0).poke(RDIStateReq.nop)
  }

  /** The LINKSPEED exit with `stalled` Modules' point tests held open.
    *
    * `trigger` sees the Runtime Link Test Control change and takes the exit
    * (spec 4.5.3.4.12 Step 4a). `corrupted` Modules receive a bad Lane, so
    * their partners' point tests fail and take the {error req} arc instead.
    * Each switch is withdrawn once its Module has reached PHYRETRAIN, so the
    * pass after the retrain completes and the Link has to come back to ACTIVE
    * whole.
    */
  private def exitWithPointTestsOpen(
      h: H,
      trigger: (Int, Int),
      stalled: Seq[(Int, Int)],
      corrupted: Seq[(Int, Int)] = Seq()
  ): Unit = {
    retrainThroughRdi(h)

    h.io.changeInRuntimeLinkCtrlRegs(trigger._1)(trigger._2).poke(true.B)
    for ((die, m) <- stalled) h.io.stallMainbandTx.get(die)(m).poke(true.B)
    for ((die, m) <- corrupted) h.io.injectLaneError.get(die)(m).poke(true.B)
    stepUntil(h, mbTrainCycles, "every Module back in MBTRAIN") {
      allIn(h, LTState.sMBTRAIN)
    }

    // A stalled Module cannot finish Step 2, so reaching PHYRETRAIN from here
    // means its point test was abandoned.
    val stalledInLinkSpeed = Array.fill(stalled.length)(false)
    val seen = Array.fill(2, h.params.numModules)(false)
    var left = 2 * mbTrainCycles
    while (left > 0 && !seen.forall(_.forall(identity)) && failed(h).isEmpty) {
      for (die <- 0 until 2; m <- modules(h)) {
        if (in(h, die, m, LTState.sPHYRETRAIN)) seen(die)(m) = true
      }
      for (((die, m), i) <- stalled.zipWithIndex) {
        if (
          h.io.ltsmState(die)(m).peek().litValue ==
            LTSMState.sMBTRAIN_LINKSPEED.litValue
        ) stalledInLinkSpeed(i) = true
        if (seen(die)(m)) h.io.stallMainbandTx.get(die)(m).poke(false.B)
      }
      for ((die, m) <- corrupted) {
        if (seen(die)(m)) h.io.injectLaneError.get(die)(m).poke(false.B)
      }
      if (seen(trigger._1)(trigger._2)) {
        h.io.changeInRuntimeLinkCtrlRegs(trigger._1)(trigger._2).poke(false.B)
      }
      h.clock.step(1)
      left -= 1
    }
    for (die <- 0 until 2; m <- modules(h)) {
      assert(
        seen(die)(m),
        s"die $die module $m never followed the LINKSPEED exit to PHYRETRAIN " +
          s"(failed: ${failed(h).getOrElse("none")}): ${states(h)}"
      )
    }
    for (((die, m), i) <- stalled.zipWithIndex) {
      assert(
        stalledInLinkSpeed(i),
        s"die $die module $m was never held in LINKSPEED"
      )
    }

    stepUntil(h, 2 * mbTrainCycles, "every Module back in LINKINIT") {
      allIn(h, LTState.sLINKINIT)
    }
    for (die <- 0 until 2; m <- modules(h)) {
      h.io
        .moduleEnable(die)(m)
        .expect(true.B, s"die $die module $m lost to the PHY retrain")
    }
    for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
    stepUntil(h, rdiFlagCycles, "aggregate RDI Active again") {
      rdiIn(h, RDIState.active)
    }
    stepUntil(h, rdiFlagCycles, "every Module ACTIVE again") {
      allIn(h, LTState.sACTIVE)
    }
    for (die <- 0 until 2) {
      h.io.plTrainError(die).expect(false.B, s"die $die training error")
      // Nothing the abandoned exchanges left in flight may reach a Module
      // that no longer expects it.
      assert(
        !h.io.sbFaultSeen(die).peekBoolean(),
        s"die $die sideband fault: ${sidebandFaults(h)}"
      )
    }
  }

  private def harness(
      numModules: Int,
      laneErrors: Boolean = false,
      probe: Boolean = false,
      specLiteralSenderDie: Option[Int] = None
  ) =
    new MmplLoopbackHarness(
      params = MmplParams(numModules = numModules),
      modulePairing = 0 until numModules,
      laneErrorInjection = laneErrors,
      timeoutCyclesOverride = Some(trainingTimeout),
      mainbandStallInjection = true,
      exitToPhyRetrainProbe = probe,
      specLiteralSenderDie = specLiteralSenderDie
    )

  /** What each Module put on its own sideband for the one LINKSPEED exit the
    * test took (the harness counts from reset, and nothing else in these tests
    * sends either message). Spec 4.5.3.4.12: the Module that saw the register
    * change sends the req and only receives the resp (Step 4a); its partner
    * answers it; and every other Module of the Link -- a sibling the MMPL
    * moved -- "must exit to PHYRETRAIN and send an {exit to PHY retrain resp}"
    * (Step 5) on its own sideband, once, since that is what a partner which
    * was not told any other way waits for. `dies` limits the check to the die
    * whose MMPL did the directing.
    */
  private def checkExitMessages(
      h: H,
      trigger: (Int, Int),
      dies: Seq[Int] = Seq(0, 1)
  ): Unit = {
    val (tDie, tModule) = trigger
    for (die <- dies; m <- modules(h)) {
      val req = h.io.exitToPhyRetrainReqSent.get(die)(m).peek().litValue
      val resp = h.io.exitToPhyRetrainRespSent.get(die)(m).peek().litValue
      val (wantReq, wantResp, role) =
        if (die == tDie && m == tModule) (1, 0, "the Module that took the exit")
        else if (m == tModule) (0, 1, "the partner answering the exit req")
        else (0, 1, "a sibling the MMPL moved")
      assert(
        req == wantReq && resp == wantResp,
        s"die $die module $m ($role) sent $req exit req and $resp exit resp, " +
          s"expected $wantReq and $wantResp"
      )
    }
  }

  describe("One-module Link: PHY retrain from LINKSPEED") {
    it(
      "the partner abandons its Step 2 point test when the exit req arrives"
    ) {
      /* Die 0 finishes Step 2 and sends {exit to phy retrain req} while die
         1's point test is held open. Die 1 has to answer and leave without
         finishing it (Step 3: "any outstanding messages are abandoned"), and
         die 0 has to leave on the response alone, without the {done req} or
         {error req} die 1 will now never send. */
      simulate(harness(1), firtoolOpts = firtoolOpts) { h =>
        exitWithPointTestsOpen(h, trigger = (0, 0), stalled = Seq((1, 0)))
      }
    }
  }

  describe("Two-module Link: PHY retrain from LINKSPEED") {
    it(
      "the partner abandons its Step 2 point test when the exit req arrives"
    ) {
      // The one-module case on Module 0, with Module 1 in lockstep beside it.
      simulate(harness(2), firtoolOpts = firtoolOpts) { h =>
        exitWithPointTestsOpen(h, trigger = (0, 0), stalled = Seq((1, 0)))
      }
    }

    it(
      "a sibling still in its Step 2 point test follows the MMPL directive"
    ) {
      /* Die 0 Module 0 takes the exit. Module 1 on either die has no exit req
         on its own sideband and is moved by its die's MMPL (spec 4.5.3.4.12
         Step 5) -- die 1's while its point test is held open, and die 0's
         while it is still waiting for the {done req} that test would have
         been followed by. Both have to leave with it unfinished. */
      simulate(harness(2), firtoolOpts = firtoolOpts) { h =>
        exitWithPointTestsOpen(h, trigger = (0, 0), stalled = Seq((1, 1)))
      }
    }

    it(
      "a sibling in the error req exchange follows the MMPL directive"
    ) {
      /* Die 1 Module 1's point test fails (die 0 Module 1 receives a bad
         Lane), so it sends {error req} and waits for {error resp}. Die 0
         Module 1 owes that response only once its own Step 2 is complete
         (Step 5), and its point test is held open -- so neither Module of the
         pair can finish the exchange, and both have to leave it when the
         MMPL directs a PHY retrain. */
      simulate(harness(2, laneErrors = true), firtoolOpts = firtoolOpts) { h =>
        exitWithPointTestsOpen(
          h,
          trigger = (0, 0),
          stalled = Seq((0, 1)),
          corrupted = Seq((0, 1))
        )
      }
    }
  }

  describe("Four-module Link: PHY retrain from LINKSPEED") {
    it(
      "siblings still in their Step 2 point tests follow the MMPL directive"
    ) {
      // As above, with the stalled siblings on both dies.
      simulate(harness(4), firtoolOpts = firtoolOpts) { h =>
        exitWithPointTestsOpen(
          h,
          trigger = (0, 0),
          stalled = Seq((1, 1), (0, 2))
        )
      }
    }
  }

  describe("Siblings send {exit to PHY retrain resp} on their own sideband") {
    it(
      "two-module Link: the sibling on each die sends one, and takes its partner's"
    ) {
      /* Die 0 Module 0 takes the exit and die 1 Module 0 answers it. Module 1
         of each die is moved by its MMPL and sends the resp itself; the one
         it receives from its partner it never asked for, and it has to claim
         it (no unhandled sideband packet) and leave on it. */
      simulate(harness(2, probe = true), firtoolOpts = firtoolOpts) { h =>
        exitWithPointTestsOpen(h, trigger = (0, 0), stalled = Seq())
        checkExitMessages(h, trigger = (0, 0))
      }
    }

    it(
      "four-module Link: every sibling sends one, including those whose point tests are open"
    ) {
      // A sibling still in Step 2 (die 1 Module 1, die 0 Module 2) is moved
      // out of it and still owes its partner the resp; its partner receives
      // that resp while waiting for a report that will not come.
      simulate(harness(4, probe = true), firtoolOpts = firtoolOpts) { h =>
        exitWithPointTestsOpen(
          h,
          trigger = (0, 0),
          stalled = Seq((1, 1), (0, 2))
        )
        checkExitMessages(h, trigger = (0, 0))
      }
    }
  }

  describe("A partner die that does not relay the exit to its siblings") {
    /* Die 0 is SpecLiteralMultiModulePhy: its Module 0 sends the exit req,
       and its other Modules -- which spec 4.5.3.4.12 gives no way to learn of
       it but the {exit to PHY retrain resp} on their own sideband -- ignore
       their MMPL's directive and the partner's {PHYRETRAIN.retrain start req}.
       Unless die 1's siblings send that resp, they sit in LINKSPEED until the
       residency timeout and the Link goes to TRAINERROR. */
    it("two-module Link: its sibling leaves on the resp die 1's sibling sends") {
      simulate(
        harness(2, probe = true, specLiteralSenderDie = Some(0)),
        firtoolOpts = firtoolOpts
      ) { h =>
        exitWithPointTestsOpen(h, trigger = (0, 0), stalled = Seq())
        checkExitMessages(h, trigger = (0, 0), dies = Seq(1))
      }
    }

    it(
      "four-module Link: its siblings leave on the resps, one with its point test open"
    ) {
      // Die 0 Module 2 is still in its Step 2 point test when the resp lands.
      simulate(
        harness(4, probe = true, specLiteralSenderDie = Some(0)),
        firtoolOpts = firtoolOpts
      ) { h =>
        exitWithPointTestsOpen(h, trigger = (0, 0), stalled = Seq((0, 2)))
        checkExitMessages(h, trigger = (0, 0), dies = Seq(1))
      }
    }
  }

  /** Names the first faulting sideband packet on every Module that saw one. */
  private def sidebandFaults(h: H): String =
    (for {
      die <- 0 until 2
      m <- modules(h)
      if h.io.sbUnhandledSeen(die)(m).peekBoolean() ||
        h.io.sbFirstFaultHeader(die)(m).peek().litValue != 0
    } yield {
      val hdr = h.io.sbFirstFaultHeader(die)(m).peek().litValue
      val opcode = (hdr & 0x1f).toInt
      val msgCode = ((hdr >> 14) & 0xff).toInt
      val msgSubcode = ((hdr >> 32) & 0xff).toInt
      f"die $die module $m unhandled=${h.io.sbUnhandledSeen(die)(m).peekBoolean()} " +
        f"opcode=$opcode%02x msgCode=$msgCode%02x subcode=$msgSubcode%02x"
    }).mkString("; ")
}
