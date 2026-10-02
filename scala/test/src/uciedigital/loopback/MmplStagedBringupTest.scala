package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import org.scalatest.funspec.AnyFunSpec

/** Two multi-module dies brought up through the real digital path, spec 4.7.
  *
  * The ladder mirrors LogPhyStagedBringupTest, so a failure names the first
  * thing a multi-module Link cannot do. What is new here is that every stage is
  * checked on every Module, and that the dies are cross-wired through a Module
  * ID permutation: the data stages are what catch a transmit byte map that
  * ranks by the local Module ID instead of the remote one.
  *
  * The link training residency timeouts are shortened for simulation. The spec
  * value is 8 ms, which at 800 MHz is 6.4M cycles with a 3.2M cycle minimum
  * RESET wait; paying that on top of two dies of several Modules each is far
  * more than these checks need.
  */
class MmplStagedBringupTest extends AnyFunSpec with ChiselSim {

  // The LTSM asserts on substates the link passes through on its way up.
  private val firtoolOpts = Array(
    "--disable-layers=Verification,Verification.Assert,Verification.Assume,Verification.Cover"
  )

  private val trainingTimeout = 262144
  private val resetWait = trainingTimeout / 2

  // Cycle budgets per milestone, sized to the sideband exchanges each one runs.
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

  // ---------------------------------------------------------------------------
  // Observing and stepping
  // ---------------------------------------------------------------------------
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

  private def reached(h: H, target: LTState.Type): Boolean = {
    val goal = forwardPath.indexOf(target)
    everyModule(h) { (die, m) =>
      val at = h.io.ltState(die)(m).peek().litValue
      forwardPath.indexWhere(_.litValue == at) >= goal
    }
  }

  /** A Module that left the forward path cannot come back on its own, and a
    * training error drains to RESET, so keep watch for it rather than letting a
    * later check report a misleading RESET.
    */
  private def derailed(h: H): Boolean =
    !everyModule(h) { (die, m) =>
      val at = h.io.ltState(die)(m).peek().litValue
      forwardPath.exists(_.litValue == at)
    }

  private def stepUntil(h: H, limit: Int, milestone: String)(
      done: => Boolean
  ): Unit = {
    var left = limit
    while (left > 0 && !done && !derailed(h)) {
      h.clock.step(1)
      left -= 1
    }
    assert(done, s"$milestone was not reached: ${states(h)}")
  }

  private def climbTo(h: H, target: LTState.Type, limit: Int): Unit =
    stepUntil(h, limit, s"$target")(reached(h, target))

  /** Like stepUntil, but without the derail guard, for the stages where a
    * Module is meant to leave the forward path.
    */
  private def stepWhileFailing(h: H, limit: Int, milestone: String)(
      done: => Boolean
  ): Unit = {
    var left = limit
    while (left > 0 && !done) {
      h.clock.step(1)
      left -= 1
    }
    assert(done, s"$milestone was not reached: ${states(h)}")
  }

  /** Sit out the hardware reset wait, then trigger die 0 alone, as a real
    * chiplet pair arrives.
    */
  private def coldStart(h: H): Unit = {
    for (die <- 0 until 2) {
      h.io.lpStateReq(die).poke(RDIStateReq.nop)
      h.io.swStartLinkTraining(die).poke(false.B)
      h.io.pwrGood(die).poke(true.B)
      for (m <- modules(h)) {
        h.io.changeInRuntimeLinkCtrlRegs(die)(m).poke(false.B)
      }
    }
    h.clock.step(resetWait + 128)
    for (die <- 0 until 2; m <- modules(h)) {
      h.io
        .ltState(die)(m)
        .expect(LTState.sRESET, s"die $die module $m trained without a trigger")
    }
    h.io.swStartLinkTraining(0).poke(true.B)
    h.clock.step(4)
    h.io.swStartLinkTraining(0).poke(false.B)
  }

  private def bringUpToActive(h: H): Unit = {
    coldStart(h)
    climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
    climbTo(h, LTState.sMBINIT, sidebandCycles)
    climbTo(h, LTState.sMBTRAIN, mbInitCycles)
    climbTo(h, LTState.sLINKINIT, mbTrainCycles)
    requestActive(h)
    climbTo(h, LTState.sACTIVE, sidebandCycles)
  }

  /** What an Adapter does once it sees pl_inband_pres (spec 10.1.6 Step 2): ask
    * for Active. The RDI leaves Reset only on that request, the NOP having been
    * presented since cold start (spec 10.3.3.1).
    */
  private def requestActive(h: H): Unit =
    for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)

  /** Waits for `mods(die)` on each die to finish training, asks for Active as
    * an Adapter would, and waits for them to reach ACTIVE.
    */
  private def trainThenActivate(h: H, limit: Int, milestone: String)(
      mods: Int => Seq[Int]
  ): Unit = {
    def trained(die: Int, m: Int): Boolean = {
      val at = h.io.ltState(die)(m).peek().litValue
      at == LTState.sLINKINIT.litValue || at == LTState.sACTIVE.litValue
    }
    stepWhileFailing(h, limit, s"$milestone (trained)") {
      (0 until 2).forall(die => mods(die).forall(trained(die, _)))
    }
    requestActive(h)
    stepWhileFailing(h, sidebandCycles, milestone) {
      (0 until 2).forall(die =>
        mods(die).forall(m =>
          h.io.ltState(die)(m).peek().litValue == LTState.sACTIVE.litValue
        )
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Mainband data
  // ---------------------------------------------------------------------------
  /** A word tagged by die, sequence number and byte position, so a word that is
    * stale, swapped between dies, or permuted across Modules, chunks or Lanes
    * each fail differently.
    */
  private def payload(bits: Int, die: Int, seq: Int): BigInt =
    (0 until bits / 16).foldLeft(BigInt(0)) { (acc, i) =>
      val half =
        ((die + 1) << 12) | ((seq + 1) << 8) | ((i * 7 + die * 3 + seq) & 0xff)
      acc | (BigInt(half & 0xffff) << (i * 16))
    }

  private def sendBothWays(h: H, bits: Int, words: Seq[BigInt]): Unit = {
    for (die <- 0 until 2) {
      h.io.lpData.get(die).poke(words(die).U(bits.W))
      h.io.lpValid.get(die).poke(true.B)
      h.io.lpIrdy.get(die).poke(true.B)
    }

    stepUntil(h, rdiFlagCycles, "both dies ready to send")(
      (0 until 2).forall(h.io.plTrdy(_).peekBoolean())
    )
    for (from <- 0 until 2) {
      val to = 1 - from
      h.io.plValid(to).expect(true.B, s"die $to missed the word from die $from")
      h.io.plData
        .get(to)
        .expect(
          words(from).U(bits.W),
          s"die $from to die $to corrupted a word"
        )
    }

    h.clock.step(1)
    for (die <- 0 until 2) {
      h.io.lpValid.get(die).poke(false.B)
      h.io.lpIrdy.get(die).poke(false.B)
    }
  }

  /** Like sendBothWays, but tolerant of a transfer that takes several beats.
    *
    * Once Modules have been disabled the surviving Lanes cannot carry the whole
    * aggregate word in one 8-UI interval, so the receiver only presents it
    * after the last beat has been gathered (spec 4.7.1, Figure 4-46).
    */
  private def sendBothWaysMultiBeat(
      h: H,
      bits: Int,
      words: Seq[BigInt]
  ): Unit = {
    for (die <- 0 until 2) {
      h.io.lpData.get(die).poke(words(die).U(bits.W))
      h.io.lpValid.get(die).poke(true.B)
      h.io.lpIrdy.get(die).poke(true.B)
    }

    stepUntil(h, rdiFlagCycles, "both dies ready to send")(
      (0 until 2).forall(h.io.plTrdy(_).peekBoolean())
    )
    h.clock.step(1)
    for (die <- 0 until 2) {
      h.io.lpValid.get(die).poke(false.B)
      h.io.lpIrdy.get(die).poke(false.B)
    }

    // Collect whatever each die presents, however many beats it takes.
    val got = Array.fill(2)(Option.empty[BigInt])
    var left = rdiFlagCycles
    while (left > 0 && got.exists(_.isEmpty)) {
      for (die <- 0 until 2) {
        if (got(die).isEmpty && h.io.plValid(die).peekBoolean()) {
          got(die) = Some(h.io.plData.get(die).peek().litValue)
        }
      }
      h.clock.step(1)
      left -= 1
    }

    for (from <- 0 until 2) {
      val to = 1 - from
      assert(
        got(to).isDefined,
        s"die $to never received the word from die $from"
      )
      assert(
        got(to).get == words(from),
        f"die $from to die $to corrupted a word: sent ${words(from)}%x, " +
          f"received ${got(to).get}%x"
      )
    }
  }

  // ---------------------------------------------------------------------------
  // The ladder
  // ---------------------------------------------------------------------------
  private val configurations = Seq(
    // Both dies name their Modules the same way.
    ("two modules, matching Module IDs", 2, Seq(0, 1), LinkWidth.x32),
    // Table 5-27, x2: M0 faces M1. This is the spec Figure 4-44 case.
    ("two modules, swapped Module IDs", 2, Seq(1, 0), LinkWidth.x32),
    // Table 5-27, x4 unstacked: M0 faces M2 and M1 faces M3.
    ("four modules, rotated Module IDs", 4, Seq(2, 3, 0, 1), LinkWidth.x64)
  )

  for ((name, numModules, pairing, expectedWidth) <- configurations) {
    val params = MmplParams(numModules = numModules)
    val rdiWordBits = params.rdiParams(32).nBytes * 8

    def harness(dataPath: Boolean) = new MmplLoopbackHarness(
      params = params,
      modulePairing = pairing,
      dataPath = dataPath,
      timeoutCyclesOverride = Some(trainingTimeout)
    )

    describe(s"Multi-module link training: $name") {
      it("Stage 1: every Module leaves RESET for SBINIT") {
        simulate(harness(false), firtoolOpts = firtoolOpts) { h =>
          coldStart(h)
          climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
        }
      }

      it("Stage 2: every Module completes SBINIT and enters MBINIT") {
        simulate(harness(false), firtoolOpts = firtoolOpts) { h =>
          coldStart(h)
          climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
          climbTo(h, LTState.sMBINIT, sidebandCycles)
        }
      }

      it("Stage 3: MBINIT.PARAM carries the Module IDs across") {
        simulate(harness(false), firtoolOpts = firtoolOpts) { h =>
          coldStart(h)
          climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
          climbTo(h, LTState.sMBINIT, sidebandCycles)
          stepUntil(h, mbInitCycles, "MBINIT.PARAM negotiated")(
            everyModule(h)((die, m) =>
              h.io.negotiatedParamsValid(die)(m).peekBoolean()
            )
          )
          // Spec 4.7.1: the Module ID a Module learns is the one its partner
          // advertised, which the harness pairing determines.
          for (die <- 0 until 2; m <- modules(h)) {
            h.io
              .remoteModuleId(die)(m)
              .expect(
                pairing(m).U,
                s"die $die module $m should face remote Module ${pairing(m)}"
              )
          }
        }
      }

      it("Stage 4: every Module completes MBINIT and enters MBTRAIN") {
        simulate(harness(false), firtoolOpts = firtoolOpts) { h =>
          coldStart(h)
          climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
          climbTo(h, LTState.sMBINIT, sidebandCycles)
          climbTo(h, LTState.sMBTRAIN, mbInitCycles)
        }
      }

      it("Stage 5: the MMPL resolution carries every Module to LINKINIT") {
        // With no Module reporting errors the resolution is {done resp} on all
        // of them (spec 4.7.1.2), which is the multi-module MBTRAIN.LINKSPEED
        // path end to end.
        simulate(harness(false), firtoolOpts = firtoolOpts) { h =>
          coldStart(h)
          climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
          climbTo(h, LTState.sMBINIT, sidebandCycles)
          climbTo(h, LTState.sMBTRAIN, mbInitCycles)
          climbTo(h, LTState.sLINKINIT, mbTrainCycles)
          for (die <- 0 until 2; m <- modules(h)) {
            h.io
              .moduleEnable(die)(m)
              .expect(true.B, s"die $die module $m should still be operational")
          }
        }
      }

      it("Stage 6: the aggregate RDI reaches Active at the summed width") {
        simulate(harness(false), firtoolOpts = firtoolOpts) { h =>
          bringUpToActive(h)
          for (die <- 0 until 2) {
            h.io.lpStateReq(die).poke(RDIStateReq.active)
          }
          stepUntil(h, rdiFlagCycles, "aggregate RDI Active")(
            (0 until 2).forall { die =>
              h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
            }
          )
          for (die <- 0 until 2) {
            h.io.plInbandPres(die).expect(true.B, s"die $die inband present")
            h.io.plTrainError(die).expect(false.B, s"die $die training error")
            // Spec 10.1: pl_lnk_cfg is the width across all active Modules.
            h.io
              .plLnkCfg(die)
              .expect(
                expectedWidth,
                s"die $die should report the aggregate width"
              )
            h.io.sbFaultSeen(die).expect(false.B, s"die $die sideband fault")
          }
        }
      }

      it("Stage 7: a tagged word crosses byte for byte in both directions") {
        // This is the stage that fails if the transmit byte map ranks by the
        // local Module ID rather than the remote one (spec Figure 4-44).
        simulate(harness(true), firtoolOpts = firtoolOpts) { h =>
          bringUpToActive(h)
          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
          stepUntil(h, rdiFlagCycles, "aggregate RDI Active")(
            (0 until 2).forall { die =>
              h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
            }
          )

          for (seq <- 0 until 4) {
            sendBothWays(
              h,
              rdiWordBits,
              (0 until 2).map(payload(rdiWordBits, _, seq))
            )
          }
        }
      }

      it("Stage 8: a Module that fails LINKSPEED is disabled on both dies") {
        // Corrupting one Module's receive Lane inside MBTRAIN.LINKSPEED makes
        // it fail the Step 2 point test, which is the only way to reach the
        // degrade and disable arcs of spec 4.7.1 on a perfect loopback. Fewer
        // than half the Modules report, so the resolution disables the half the
        // failing Module belongs to (spec 5.7.3.4.1) and the rest carry on.
        val injected = 1
        val perHalf = if (numModules == 1) 1 else numModules / 2
        val half = (m: Int) => m / perHalf
        val degradedWidth =
          if (perHalf == 1) LinkWidth.x16 else LinkWidth.x32

        simulate(
          new MmplLoopbackHarness(
            params = params,
            modulePairing = pairing,
            dataPath = true,
            laneErrorInjection = true,
            timeoutCyclesOverride = Some(trainingTimeout)
          ),
          firtoolOpts = firtoolOpts
        ) { h =>
          h.io.injectLaneError.get(0)(injected).poke(true.B)
          coldStart(h)

          stepWhileFailing(
            h,
            mbInitCycles + mbTrainCycles,
            "the MMPL disabled a Module on both dies"
          ) {
            (0 until 2).forall(die =>
              modules(h).exists(m => !h.io.moduleEnable(die)(m).peekBoolean())
            )
          }

          val disabled = (0 until 2).map { die =>
            modules(h).filterNot(m => h.io.moduleEnable(die)(m).peekBoolean())
          }

          // Die 0 loses the half holding the Module whose Lane was corrupted.
          // Die 1 reports the same failure from the other direction, so it loses
          // the half holding that Module's partner.
          assert(
            disabled(0).toSet == modules(h)
              .filter(m => half(m) == half(injected))
              .toSet,
            s"die 0 disabled ${disabled(0)}, expected the half of Module $injected"
          )
          assert(
            disabled(1).toSet ==
              modules(h).filter(m => half(m) == half(pairing(injected))).toSet,
            s"die 1 disabled ${disabled(1)}, expected the half of Module ${pairing(injected)}"
          )
          // The Modules still standing on the two dies must be the ones wired to
          // each other, or the Link would be talking to nothing.
          assert(
            disabled(0).map(pairing).toSet == disabled(1).toSet,
            s"the dies disabled unpaired Modules: ${disabled(0)} and ${disabled(1)}"
          )

          val surviving =
            (0 until 2).map(die => modules(h).filterNot(disabled(die).contains))
          trainThenActivate(
            h,
            mbTrainCycles,
            "the surviving Modules reached ACTIVE"
          )(
            surviving(_)
          )

          for (die <- 0 until 2) {
            h.io.lpStateReq(die).poke(RDIStateReq.active)
          }
          stepWhileFailing(
            h,
            rdiFlagCycles,
            "the degraded RDI reached Active"
          ) {
            (0 until 2).forall(die =>
              h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
            )
          }
          for (die <- 0 until 2) {
            // Spec 10.1: pl_lnk_cfg is the width across the Modules still active.
            h.io
              .plLnkCfg(die)
              .expect(
                degradedWidth,
                s"die $die should report the degraded aggregate width"
              )
            h.io.plTrainError(die).expect(false.B, s"die $die training error")
          }

          // The surviving Modules cannot carry the whole aggregate word in one
          // 8-UI interval any more, so this is the multi-beat path of spec
          // 4.7.1 / Figure 4-46 carrying real data for the first time.
          for (seq <- 0 until 3) {
            sendBothWaysMultiBeat(
              h,
              rdiWordBits,
              (0 until 2).map(payload(rdiWordBits, _, seq))
            )
          }
        }
      }

      it("Stage 9: a majority width degrade reaches every Module") {
        /* Corrupting a majority of the Modules takes Figure 4-48's other arc:
           at 4 GT/s the bandwidth comparison cannot favour a speed degrade, so
           the MMPL resolves `repair` and every Module width degrades, none is
           disabled.

           What this pins down is that the directive reaches all of them. A
           Module that found no errors of its own has nothing locally to act on,
           and spec 4.7.1 does not let a multi-module Link run at mixed widths,
           so the MMPL's resolution is the only thing that can move it. Getting
           here also requires MBTRAIN.REPAIR to complete, which it could not
           before: see the note on the ignored stage below. */
        val injected =
          if (numModules > 2) (1 until numModules) else (0 until numModules)

        simulate(
          new MmplLoopbackHarness(
            params = params,
            modulePairing = pairing,
            laneErrorInjection = true,
            timeoutCyclesOverride = Some(trainingTimeout)
          ),
          firtoolOpts = firtoolOpts
        ) { h =>
          for (m <- injected) h.io.injectLaneError.get(0)(m).poke(true.B)
          coldStart(h)

          stepWhileFailing(
            h,
            mbInitCycles + 2 * mbTrainCycles,
            "the MMPL resolved a width degrade"
          ) {
            (0 until 2).forall(die =>
              h.io.mmplResolution(die).peek().litValue ==
                MmplResolution.repair.litValue
            )
          }

          // Every Module applies it, including the ones with no errors.
          stepWhileFailing(
            h,
            mbTrainCycles,
            "every Module applied the width degrade"
          ) {
            (0 until 2).forall(die =>
              (0 until numModules).forall(m =>
                h.io.moduleLinkWidth(die)(m).peek().litValue ==
                  LinkWidth.x8.litValue
              )
            )
          }

          // A width degrade keeps every Module; nothing should be disabled.
          for (die <- 0 until 2; m <- 0 until numModules) {
            assert(
              h.io.moduleEnable(die)(m).peekBoolean(),
              s"die $die module $m was disabled by a width degrade"
            )
          }

          // MBTRAIN.REPAIR has to complete for the Link to carry on training.
          for (m <- injected) h.io.injectLaneError.get(0)(m).poke(false.B)
          stepWhileFailing(
            h,
            mbTrainCycles,
            "every Module left MBTRAIN.REPAIR"
          ) {
            (0 until 2).forall(die =>
              (0 until numModules).forall(m =>
                h.io.ltsmState(die)(m).peek().litValue !=
                  LTSMState.sMBTRAIN_REPAIR.litValue
              )
            )
          }
          for (die <- 0 until 2; m <- 0 until numModules) {
            h.io
              .moduleLinkWidth(die)(m)
              .expect(
                LinkWidth.x8,
                s"die $die module $m did not hold the degraded width"
              )
          }
        }
      }

      /* The other half of Stage 9: once the Link has degraded together it must
         finish training and carry data at the new width. Getting here needed
         four defects fixed on a path no test had ever driven to completion --
         the MBTRAIN.REPAIR end handshake, the requester/responder
         synchronisation in REPAIR s1, a 15-bit msgInfo that made the
         apply-degrade packet 127 bits, and a TX self-calibration "done" that
         was tied low so MBTRAIN.TXSELFCAL only ever exited on ready bits
         leaking across a state transition. */
      it("Stage 10: the width-degraded Link reaches Active and moves data") {
        val injected =
          if (numModules > 2) (1 until numModules) else (0 until numModules)
        val halvedWidth =
          if (numModules == 2) LinkWidth.x16 else LinkWidth.x32

        simulate(
          new MmplLoopbackHarness(
            params = params,
            modulePairing = pairing,
            dataPath = true,
            laneErrorInjection = true,
            timeoutCyclesOverride = Some(trainingTimeout)
          ),
          firtoolOpts = firtoolOpts
        ) { h =>
          for (m <- injected) h.io.injectLaneError.get(0)(m).poke(true.B)
          coldStart(h)

          stepWhileFailing(
            h,
            mbInitCycles + 2 * mbTrainCycles,
            "every Module applied the width degrade"
          ) {
            (0 until 2).forall(die =>
              (0 until numModules).forall(m =>
                h.io.moduleLinkWidth(die)(m).peek().litValue ==
                  LinkWidth.x8.litValue
              )
            )
          }
          for (m <- injected) h.io.injectLaneError.get(0)(m).poke(false.B)

          trainThenActivate(
            h,
            3 * mbTrainCycles,
            "the width-degraded Link reached ACTIVE on every Module"
          )(_ => 0 until numModules)

          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
          stepWhileFailing(
            h,
            rdiFlagCycles,
            "the degraded RDI reached Active"
          ) {
            (0 until 2).forall(die =>
              h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
            )
          }
          for (die <- 0 until 2) {
            h.io
              .plLnkCfg(die)
              .expect(halvedWidth, s"die $die aggregate width after degrade")
            h.io.plTrainError(die).expect(false.B, s"die $die training error")
          }

          for (seq <- 0 until 3) {
            sendBothWaysMultiBeat(
              h,
              rdiWordBits,
              (0 until 2).map(payload(rdiWordBits, _, seq))
            )
          }
        }
      }

      it("Stage 11: a PHY retrain from LINKSPEED takes every Module round") {
        /* Spec 4.5.3.4.12 Step 4a: with PHY_IN_RETRAIN set, a change in the
           Runtime Link Test Control register makes a Module send
           {MBTRAIN.LINKSPEED exit to phy retrain req}, and Step 5 has the
           request received on any Module take every Module of the Link to
           PHYRETRAIN. Driven end to end on two dies: an Adapter-directed
           retrain first, so PHY_IN_RETRAIN is set, then the register change on
           one Module of die 0, then a clean pass back to Active with no Module
           lost. A stale {exit to phy retrain} bit surviving into the next
           LINKSPEED pass turns this into a PHYRETRAIN loop, and a sibling
           entering PHYRETRAIN after its partner's {PHYRETRAIN.retrain start
           req} has arrived deadlocks it. */
        simulate(harness(false), firtoolOpts = firtoolOpts) { h =>
          def allIn(state: LTState.Type): Boolean =
            everyModule(h) { (die, m) =>
              h.io.ltState(die)(m).peek().litValue == state.litValue
            }
          def rdiIn(state: RDIState.Type): Boolean =
            (0 until 2).forall { die =>
              h.io.plStateSts(die).peek().litValue == state.litValue
            }

          bringUpToActive(h)
          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
          stepUntil(h, rdiFlagCycles, "aggregate RDI Active")(
            rdiIn(RDIState.active)
          )

          // Die 0's Adapter asks for a retrain. Die 1's makes no request while
          // the Link retrains, as spec 10.3.3.4 has it wait for the PHY.
          h.io.lpStateReq(1).poke(RDIStateReq.nop)
          h.io.lpStateReq(0).poke(RDIStateReq.retrain)
          stepWhileFailing(
            h,
            sidebandCycles,
            "both RDIs in Retrain and every Module in PHYRETRAIN"
          ) {
            rdiIn(RDIState.retrain) && allIn(LTState.sPHYRETRAIN)
          }
          h.io.lpStateReq(0).poke(RDIStateReq.nop)

          // Die 0 Module 0 sees the register change and will take the
          // LINKSPEED exit; the MMPLs must carry every other Module along.
          h.io.changeInRuntimeLinkCtrlRegs(0)(0).poke(true.B)
          stepWhileFailing(h, mbTrainCycles, "every Module back in MBTRAIN") {
            allIn(LTState.sMBTRAIN)
          }

          // Modules enter and leave the second PHYRETRAIN staggered, so watch
          // for each one rather than for a cycle with all of them there. The
          // register change is withdrawn as soon as the Module that took the
          // exit is back in PHYRETRAIN, so the next pass completes.
          val seen = Array.fill(2, numModules)(false)
          var left = 2 * mbTrainCycles
          while (left > 0 && !seen.forall(_.forall(identity))) {
            for (die <- 0 until 2; m <- 0 until numModules) {
              if (
                h.io.ltState(die)(m).peek().litValue ==
                  LTState.sPHYRETRAIN.litValue
              ) seen(die)(m) = true
            }
            if (seen(0)(0)) h.io.changeInRuntimeLinkCtrlRegs(0)(0).poke(false.B)
            h.clock.step(1)
            left -= 1
          }
          for (die <- 0 until 2; m <- 0 until numModules) {
            assert(
              seen(die)(m),
              s"die $die module $m never followed the LINKSPEED exit to " +
                s"PHYRETRAIN: ${states(h)}"
            )
          }

          stepWhileFailing(
            h,
            2 * mbTrainCycles,
            "every Module back in LINKINIT after the PHY retrain"
          ) {
            allIn(LTState.sLINKINIT)
          }
          for (die <- 0 until 2; m <- 0 until numModules) {
            h.io
              .moduleEnable(die)(m)
              .expect(true.B, s"die $die module $m lost to a PHY retrain")
          }

          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
          stepWhileFailing(h, rdiFlagCycles, "aggregate RDI Active again") {
            rdiIn(RDIState.active)
          }
          stepWhileFailing(h, rdiFlagCycles, "every Module ACTIVE again") {
            allIn(LTState.sACTIVE)
          }
          for (die <- 0 until 2) {
            h.io.plTrainError(die).expect(false.B, s"die $die training error")
            assert(
              !h.io.sbFaultSeen(die).peekBoolean(),
              s"die $die sideband fault: ${sidebandFaults(h)}"
            )
          }
        }
      }

      it(
        "Stage 12: Modules whose sidebands differ in latency resolve on both dies"
      ) {
        /* Each die resolves MBTRAIN.LINKSPEED once its own last Module has
           reported, so when one Module's sideband is slower than its sibling's
           the two dies resolve at different times, and the early die's
           directed response can arrive on a Module whose own die has yet to
           answer. It has to be kept rather than dropped, or that Module waits
           out its residency timeout and the Link goes down. Stage 8's failure,
           with the failing Module's sideband a few cycles slower. */
        val injected = 1
        val delay = Seq.tabulate(numModules)(m => if (m == injected) 3 else 0)
        simulate(
          new MmplLoopbackHarness(
            params = params,
            modulePairing = pairing,
            laneErrorInjection = true,
            timeoutCyclesOverride = Some(trainingTimeout),
            sidebandRxDelayCycles = Seq(delay, Seq())
          ),
          firtoolOpts = firtoolOpts
        ) { h =>
          h.io.injectLaneError.get(0)(injected).poke(true.B)
          coldStart(h)

          stepWhileFailing(
            h,
            mbInitCycles + mbTrainCycles,
            "the MMPL disabled a Module on both dies"
          ) {
            (0 until 2).forall(die =>
              modules(h).exists(m => !h.io.moduleEnable(die)(m).peekBoolean())
            )
          }
          val surviving = (0 until 2).map { die =>
            modules(h).filter(m => h.io.moduleEnable(die)(m).peekBoolean())
          }
          trainThenActivate(
            h,
            mbTrainCycles,
            "the surviving Modules reached ACTIVE"
          )(
            surviving(_)
          )
          for (die <- 0 until 2; m <- surviving(die)) {
            h.io
              .trainingTimedout(die)(m)
              .expect(false.B, s"die $die module $m waited out a timeout")
          }
        }
      }

      it(
        "Stage 13: a one-sided Lane fault leaves both directions of the Module at one width"
      ) {
        /* Spec 4.5.3.3.6 Step 3: a Module whose partner found every Lane
           functional sets its Transmitter and Receiver to the Lane map its own
           point test found. Only die 1's Transmitter into die 0's Module
           `injected` sees the fault here, so die 1 degrades that Transmitter
           and die 0's Transmitter back has nothing to repair; without the rule
           the pair left MBINIT with its two directions at different widths.
           The fault is on Lane 0, so the half that survives is Lanes 8 to 15,
           whose second point test only passes if Lane IDs are compared as
           sent rather than renumbered. */
        val injected = 1
        val partner = pairing(injected)
        val upperHalf = "b010".U(3.W)
        val allLanes = "b011".U(3.W)
        val degradedWidth =
          if (numModules == 2) LinkWidth.x16 else LinkWidth.x32
        simulate(
          new MmplLoopbackHarness(
            params = params,
            modulePairing = pairing,
            dataPath = true,
            repairMbLaneErrorInjection = true,
            timeoutCyclesOverride = Some(trainingTimeout)
          ),
          firtoolOpts = firtoolOpts
        ) { h =>
          h.io.injectRepairMbLaneError.get(0)(injected).poke(true.B)
          coldStart(h)
          climbTo(h, LTState.sSBINIT, sbinitEntryCycles)
          climbTo(h, LTState.sMBINIT, sidebandCycles)
          climbTo(h, LTState.sMBTRAIN, mbInitCycles)
          h.io.injectRepairMbLaneError.get(0)(injected).poke(false.B)

          for (die <- 0 until 2; m <- modules(h)) {
            val faulted =
              (die == 0 && m == injected) || (die == 1 && m == partner)
            val expected = if (faulted) upperHalf else allLanes
            h.io
              .moduleTxLanes(die)(m)
              .expect(expected, s"die $die module $m transmit Lane map")
            h.io
              .moduleRxLanes(die)(m)
              .expect(expected, s"die $die module $m receive Lane map")
          }

          // That pair is now narrower than the rest (spec 4.7.1.2.1), so both
          // dies disable it and the others carry the Link.
          stepWhileFailing(
            h,
            mbTrainCycles,
            "the MMPL disabled a Module on both dies"
          ) {
            (0 until 2).forall(die =>
              modules(h).exists(m => !h.io.moduleEnable(die)(m).peekBoolean())
            )
          }
          h.io.moduleEnable(0)(injected).expect(false.B)
          h.io.moduleEnable(1)(partner).expect(false.B)
          val surviving = (0 until 2).map { die =>
            modules(h).filter(m => h.io.moduleEnable(die)(m).peekBoolean())
          }
          trainThenActivate(
            h,
            mbTrainCycles,
            "the surviving Modules reached ACTIVE"
          )(
            surviving(_)
          )
          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
          stepWhileFailing(
            h,
            rdiFlagCycles,
            "the degraded RDI reached Active"
          ) {
            (0 until 2).forall(die =>
              h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
            )
          }
          for (die <- 0 until 2) {
            h.io
              .plLnkCfg(die)
              .expect(degradedWidth, s"die $die aggregate width")
            h.io.plTrainError(die).expect(false.B, s"die $die training error")
          }
          for (seq <- 0 until 2) {
            sendBothWaysMultiBeat(
              h,
              rdiWordBits,
              (0 until 2).map(payload(rdiWordBits, _, seq))
            )
          }
        }
      }

      it(
        "Stage 14: a Valid framing error retrains the Link without the Adapter asking"
      ) {
        /* Spec 4.5.3.7.2: on a Valid framing error the Physical Layer asserts
           pl_error, stalls the Adapter, and sends {LinkMgmt.RDI.Req.Retrain}
           itself. Both RDIs go to Retrain while both Adapters still ask for
           Active, and spec 10.3.3.4 then keeps them there until lp_state_req
           moves NOP to Active. Receive has to work again afterwards: the
           framing error must not outlive the retrain. */
        simulate(
          new MmplLoopbackHarness(
            params = params,
            modulePairing = pairing,
            dataPath = true,
            validErrorInjection = true,
            timeoutCyclesOverride = Some(trainingTimeout)
          ),
          firtoolOpts = firtoolOpts
        ) { h =>
          def allIn(state: LTState.Type): Boolean =
            everyModule(h) { (die, m) =>
              h.io.ltState(die)(m).peek().litValue == state.litValue
            }
          def rdiIn(state: RDIState.Type): Boolean =
            (0 until 2).forall { die =>
              h.io.plStateSts(die).peek().litValue == state.litValue
            }

          bringUpToActive(h)
          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
          stepUntil(h, rdiFlagCycles, "aggregate RDI Active")(
            rdiIn(RDIState.active)
          )
          sendBothWays(
            h,
            rdiWordBits,
            (0 until 2).map(payload(rdiWordBits, _, 0))
          )

          // Die 1 streams while one of die 0's Modules has its Valid Lane
          // corrupted for a few beats.
          h.io.lpData.get(1).poke(payload(rdiWordBits, 1, 1).U(rdiWordBits.W))
          h.io.lpValid.get(1).poke(true.B)
          h.io.lpIrdy.get(1).poke(true.B)
          h.clock.step(8)
          var sawPlError = false
          h.io.injectValidError.get(0)(0).poke(true.B)
          for (_ <- 0 until 4) {
            sawPlError ||= h.io.plError(0).peekBoolean()
            h.clock.step(1)
          }
          h.io.injectValidError.get(0)(0).poke(false.B)
          for (_ <- 0 until 64) {
            sawPlError ||= h.io.plError(0).peekBoolean()
            h.clock.step(1)
          }
          h.io.lpValid.get(1).poke(false.B)
          h.io.lpIrdy.get(1).poke(false.B)
          assert(
            sawPlError,
            "die 0 never raised pl_error for the corrupted word"
          )

          stepWhileFailing(h, sidebandCycles, "both RDIs in Retrain") {
            rdiIn(RDIState.retrain)
          }
          stepWhileFailing(
            h,
            2 * mbTrainCycles,
            "every Module back in LINKINIT after the retrain"
          ) {
            allIn(LTState.sLINKINIT)
          }
          // Both Adapters have held Active throughout, which is not the NOP to
          // Active that lets a Retrain entered from Active end.
          h.clock.step(rdiFlagCycles)
          assert(
            rdiIn(RDIState.retrain),
            "an RDI left Retrain without seeing lp_state_req go NOP to Active"
          )

          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.nop)
          h.clock.step(4)
          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
          stepWhileFailing(h, rdiFlagCycles, "aggregate RDI Active again") {
            rdiIn(RDIState.active)
          }
          stepWhileFailing(h, rdiFlagCycles, "every Module ACTIVE again") {
            allIn(LTState.sACTIVE)
          }
          for (seq <- 2 until 4) {
            sendBothWays(
              h,
              rdiWordBits,
              (0 until 2).map(payload(rdiWordBits, _, seq))
            )
          }
          for (die <- 0 until 2) {
            h.io.plTrainError(die).expect(false.B, s"die $die training error")
          }
        }
      }

      /* Spec 4.7.1: "if any module failed to train, the MMPL must ensure that
         the multi-module configuration degrades to the next permitted
         configuration". Each stage below stops one Module pair training and
         checks that both dies come up on the rest. The Module that fails
         waits out its residency timeout while its siblings sit on the
         resolution in MBTRAIN.LINKSPEED; the timeout used to reach the one
         RDI state machine and take the whole Link to LinkError. */
      val faulted = 1
      // Rule 2 of spec 5.7.3.4.1 takes the failed Module's half down with it
      // on a four-Module Link, so either way half the Modules survive.
      val degradedWidth = if (numModules == 2) LinkWidth.x16 else LinkWidth.x32

      def comesUpWithoutFaultedPair(h: H): Unit = {
        val faultedOn = Seq(faulted, pairing(faulted))
        stepWhileFailing(
          h,
          3 * trainingTimeout,
          "both dies dropped the faulted Module pair"
        ) {
          (0 until 2).forall(die =>
            !h.io.moduleEnable(die)(faultedOn(die)).peekBoolean()
          )
        }
        val surviving = (0 until 2).map { die =>
          modules(h).filter(m => h.io.moduleEnable(die)(m).peekBoolean())
        }
        for (die <- 0 until 2) {
          assert(
            surviving(die).length == numModules / 2,
            s"die $die kept Modules ${surviving(die)}: ${states(h)}"
          )
        }
        stepWhileFailing(h, mbTrainCycles, "the survivors reached LINKINIT") {
          (0 until 2).forall(die =>
            surviving(die).forall(m =>
              h.io.ltState(die)(m).peek().litValue ==
                LTState.sLINKINIT.litValue
            )
          )
        }
        for (die <- 0 until 2) {
          assert(
            h.io.plStateSts(die).peek().litValue != RDIState.linkError.litValue,
            s"die $die RDI went to LinkError"
          )
          h.io.lpStateReq(die).poke(RDIStateReq.active)
        }
        stepWhileFailing(h, rdiFlagCycles, "both RDIs Active") {
          (0 until 2).forall(die =>
            h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
          )
        }
        stepWhileFailing(h, rdiFlagCycles, "the survivors reached ACTIVE") {
          (0 until 2).forall(die =>
            surviving(die).forall(m =>
              h.io.ltState(die)(m).peek().litValue == LTState.sACTIVE.litValue
            )
          )
        }
        for (die <- 0 until 2) {
          h.io.plLnkCfg(die).expect(degradedWidth, s"die $die Link width")
          h.io.plTrainError(die).expect(false.B, s"die $die training error")
        }
      }

      it(
        "Stage 15: a Module pair whose sideband is cut is dropped on both dies"
      ) {
        /* Neither Module of the pair ever hears its partner, so both time out
           in SBINIT -- which spec 4.5.3.8 exits to TRAINERROR without a
           handshake -- while their siblings wait on them in LINKSPEED. */
        simulate(
          new MmplLoopbackHarness(
            params = params,
            modulePairing = pairing,
            moduleFaultInjection = true,
            timeoutCyclesOverride = Some(trainingTimeout)
          ),
          firtoolOpts = firtoolOpts
        ) { h =>
          for (die <- 0 until 2; m <- modules(h)) {
            h.io.holdPllUnlocked.get(die)(m).poke(false.B)
            h.io.cutSideband.get(die)(m).poke(false.B)
          }
          h.io.cutSideband.get(0)(faulted).poke(true.B)
          h.io.cutSideband.get(1)(pairing(faulted)).poke(true.B)
          coldStart(h)
          comesUpWithoutFaultedPair(h)
        }
      }

      it("Stage 16: a Module that never leaves RESET is dropped on both dies") {
        /* Only one die sees this failure directly: die 0's Module never locks
           its PLL. Die 1's partner leaves RESET with its siblings and waits in
           SBINIT for a sideband that never comes, and its timeout is how die 1
           learns of it. */
        simulate(
          new MmplLoopbackHarness(
            params = params,
            modulePairing = pairing,
            moduleFaultInjection = true,
            timeoutCyclesOverride = Some(trainingTimeout)
          ),
          firtoolOpts = firtoolOpts
        ) { h =>
          for (die <- 0 until 2; m <- modules(h)) {
            h.io.holdPllUnlocked.get(die)(m).poke(false.B)
            h.io.cutSideband.get(die)(m).poke(false.B)
          }
          h.io.holdPllUnlocked.get(0)(faulted).poke(true.B)
          coldStart(h)
          comesUpWithoutFaultedPair(h)
        }
      }

      it(
        "Stage 17: lp_linkerror holds every Module down until LinkError is left"
      ) {
        /* Spec 10.3.3.7: "The lower layer enters LinkError state when directed
           by an lp_linkerror signal", and "For RDI, the entry is also
           triggered if the remote Link partner requested LinkError entry
           through the relevant sideband message". Spec 4.5.3.8: "it is
           required for Physical Layer to be in TRAINERROR as long as RDI is in
           LinkError". LinkError is left for Reset only with lp_state_req
           Active, lp_linkerror low and the minimum residency met, after which
           the Link trains again from scratch. */
        simulate(
          new MmplLoopbackHarness(
            params = params,
            modulePairing = pairing,
            linkErrorInjection = true,
            timeoutCyclesOverride = Some(trainingTimeout)
          ),
          firtoolOpts = firtoolOpts
        ) { h =>
          def rdiIn(state: RDIState.Type): Boolean =
            (0 until 2).forall(die =>
              h.io.plStateSts(die).peek().litValue == state.litValue
            )
          def allIn(state: LTState.Type): Boolean =
            everyModule(h)((die, m) =>
              h.io.ltState(die)(m).peek().litValue == state.litValue
            )

          for (die <- 0 until 2) h.io.lpLinkError.get(die).poke(false.B)
          bringUpToActive(h)

          h.io.lpLinkError.get(0).poke(true.B)
          stepWhileFailing(h, sidebandCycles, "both RDIs in LinkError") {
            rdiIn(RDIState.linkError)
          }
          stepWhileFailing(h, sidebandCycles, "every Module in TRAINERROR") {
            allIn(LTState.sTRAINERROR)
          }
          h.clock.step(trainingTimeout)
          assert(
            allIn(LTState.sTRAINERROR) && rdiIn(RDIState.linkError),
            s"the Link came up while in LinkError: ${states(h)}"
          )

          // Die 0's Adapter lets go; both still ask for Active.
          h.io.lpLinkError.get(0).poke(false.B)
          stepWhileFailing(h, 3 * trainingTimeout, "both RDIs back in Reset") {
            rdiIn(RDIState.reset)
          }
          stepWhileFailing(h, sidebandCycles, "every Module back in RESET") {
            allIn(LTState.sRESET)
          }
          for (die <- 0 until 2) {
            h.io.plTrainError(die).expect(false.B, s"die $die training error")
          }

          // Software trains the Link again, and the Adapters bring it up.
          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.nop)
          h.clock.step(resetWait + 128)
          h.io.swStartLinkTraining(0).poke(true.B)
          h.clock.step(4)
          h.io.swStartLinkTraining(0).poke(false.B)
          trainThenActivate(h, mbInitCycles + mbTrainCycles, "trained again")(
            _ => modules(h)
          )
          stepWhileFailing(h, rdiFlagCycles, "both RDIs Active again") {
            rdiIn(RDIState.active)
          }
        }
      }
    }
  }

  describe("Multi-module Link failure") {
    /* Spec 4.7.1 degrades a multi-module Link around a Module that fails to
       train while the rest train on. When the rest fail as well, nothing is
       left to degrade to: the Link has failed, and the training episode
       retries it, or escalates once no retry is left (Table 10-1
       pl_trainerror), exactly as a one-Module Link does. */
    val params = MmplParams(numModules = 2)
    val pairing = Seq(0, 1)

    def harness(retries: Int) = new MmplLoopbackHarness(
      params = params,
      modulePairing = pairing,
      moduleFaultInjection = true,
      retryTrainingAmt = retries,
      timeoutCyclesOverride = Some(trainingTimeout)
    )

    def connectAll(h: H): Unit =
      for (die <- 0 until 2; m <- modules(h)) {
        h.io.holdPllUnlocked.get(die)(m).poke(false.B)
        h.io.cutSideband.get(die)(m).poke(false.B)
      }

    def ltIs(h: H, die: Int, m: Int, state: LTState.Type): Boolean =
      h.io.ltState(die)(m).peek().litValue == state.litValue

    def die0NotInLinkError(h: H): Unit =
      assert(
        h.io.plStateSts(0).peek().litValue != RDIState.linkError.litValue &&
          !h.io.plTrainError(0).peekBoolean(),
        s"die 0 escalated: ${h.io.plStateSts(0).peek()} ${states(h)}"
      )

    def escalates(h: H, held: Seq[Int]): Unit = {
      stepWhileFailing(h, 3 * trainingTimeout, "die 0's RDI in LinkError") {
        h.io.plStateSts(0).peek().litValue == RDIState.linkError.litValue
      }
      h.io.plTrainError(0).expect(true.B, "pl_trainerror with it")
      h.clock.step(trainingTimeout / 4)
      for (m <- held) {
        h.io.ltState(0)(m).expect(LTState.sTRAINERROR, s"Module $m held")
      }
      h.io.plStateSts(0).expect(RDIState.linkError)
    }

    it("Stage 1: Modules that time out together retry the Link") {
      /* Die 0 hears nothing on either Module, so both time out in SBINIT in
         the same cycle. Neither is the one still training for the other to
         be degraded around; counting each other so latched both out, and the
         Link neither retried nor escalated. */
      simulate(harness(retries = 1), firtoolOpts = firtoolOpts) { h =>
        connectAll(h)
        for (m <- modules(h)) h.io.cutSideband.get(0)(m).poke(true.B)
        coldStart(h)

        val sawTrainError = Array.fill(params.numModules)(false)
        var left = 3 * trainingTimeout
        while (
          left > 0 &&
          !(sawTrainError.forall(identity) &&
            modules(h).forall(ltIs(h, 0, _, LTState.sRESET)))
        ) {
          for (m <- modules(h) if ltIs(h, 0, m, LTState.sTRAINERROR))
            sawTrainError(m) = true
          die0NotInLinkError(h)
          h.clock.step(1)
          left -= 1
        }
        assert(sawTrainError.forall(identity), s"no timeout: ${states(h)}")
        for (m <- modules(h)) {
          h.io.moduleEnable(0)(m).expect(true.B, s"Module $m kept")
          h.io.cutSideband.get(0)(m).poke(false.B)
        }

        left = sidebandCycles + mbInitCycles + 3 * mbTrainCycles
        while (
          left > 0 &&
          !everyModule(h)((die, m) => ltIs(h, die, m, LTState.sLINKINIT))
        ) {
          die0NotInLinkError(h)
          h.clock.step(1)
          left -= 1
        }
        trainThenActivate(h, rdiFlagCycles, "the retry reached ACTIVE")(_ =>
          modules(h)
        )
        stepWhileFailing(h, rdiFlagCycles, "both RDIs Active") {
          (0 until 2).forall(die =>
            h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
          )
        }
        for (die <- 0 until 2) {
          h.io.plLnkCfg(die).expect(LinkWidth.x32, s"die $die Link width")
          h.io.plTrainError(die).expect(false.B, s"die $die training error")
        }
      }
    }

    it("Stage 2: Modules that time out together escalate with no retry left") {
      simulate(harness(retries = 0), firtoolOpts = firtoolOpts) { h =>
        connectAll(h)
        for (m <- modules(h)) h.io.cutSideband.get(0)(m).poke(true.B)
        coldStart(h)
        escalates(h, modules(h))
      }
    }

    it("Stage 3: a Module restored for the retry fails it for the Link") {
      /* Module 1 fails first and is degraded around; then Module 0 fails too,
         so the Link has failed and retries, restoring Module 1. Module 0 is
         then kept in RESET, so the restored Module 1 is all the Link has, and
         with no retry left its failure must escalate. Restored with no
         training episode, it ended the Link in RESET without a word. (A
         restored Module leaves RESET first, its reset wait long over, so a
         fault on both fails it first and leaves the escalation to the other.) */
      simulate(harness(retries = 1), firtoolOpts = firtoolOpts) { h =>
        connectAll(h)
        h.io.cutSideband.get(0)(1).poke(true.B)
        coldStart(h)
        stepWhileFailing(
          h,
          sidebandCycles + mbInitCycles,
          "Module 0 in MBTRAIN"
        ) {
          ltIs(h, 0, 0, LTState.sMBTRAIN)
        }
        h.io.cutSideband.get(0)(0).poke(true.B)

        // Module 1 failing while Module 0 trains on is degraded around: held
        // in RESET, not retrying on its own.
        var m1Failed = false
        stepWhileFailing(h, 3 * trainingTimeout, "Module 0 failed") {
          if (ltIs(h, 0, 1, LTState.sTRAINERROR)) m1Failed = true
          ltIs(h, 0, 0, LTState.sTRAINERROR)
        }
        assert(
          m1Failed && ltIs(h, 0, 1, LTState.sRESET),
          s"Module 1 was not degraded around: ${states(h)}"
        )
        die0NotInLinkError(h)

        h.io.holdPllUnlocked.get(0)(0).poke(true.B)
        stepWhileFailing(h, sidebandCycles, "Module 1 restored for the retry") {
          die0NotInLinkError(h)
          ltIs(h, 0, 1, LTState.sSBINIT)
        }
        escalates(h, Seq(1))
        h.io.ltState(0)(0).expect(LTState.sRESET)
      }
    }
  }

  describe("One-module link training") {
    // At one Module a MultiModulePhy is a bare LogicalPhy: the MMPL resolution
    // never fires and MBTRAIN.LINKSPEED is the Module's own.
    val params = MmplParams(numModules = 1)
    val rdiWordBits = params.rdiParams(32).nBytes * 8

    def rdiNeverInLinkError(h: H): Unit =
      for (die <- 0 until 2) {
        assert(
          h.io.plStateSts(die).peek().litValue != RDIState.linkError.litValue,
          s"die $die RDI went to LinkError: ${states(h)}"
        )
        h.io.plTrainError(die).expect(false.B, s"die $die training error")
      }

    it("Stage 2: a training that times out retries, the RDI staying in Reset") {
      /* A TRAINERROR is not an error escalation by itself (spec 4.5.3.3.1.2:
         one that "does not escalate to RDI transitioning to LinkError"), and
         4.5.3.8 recommends leaving it "as soon as possible" when there is
         none. Die 0 hears nothing from its partner at first, times out in
         SBINIT, and retries; the second attempt gets through. The RDI stays in
         Reset throughout -- escalating on the timeout would have held the PHY
         in TRAINERROR for as long as LinkError lasted, and no retry could have
         run. */
      simulate(
        new MmplLoopbackHarness(
          params = params,
          modulePairing = Seq(0),
          moduleFaultInjection = true,
          retryTrainingAmt = 1,
          timeoutCyclesOverride = Some(trainingTimeout)
        ),
        firtoolOpts = firtoolOpts
      ) { h =>
        for (die <- 0 until 2) {
          h.io.holdPllUnlocked.get(die)(0).poke(false.B)
          h.io.cutSideband.get(die)(0).poke(false.B)
        }
        h.io.cutSideband.get(0)(0).poke(true.B)
        coldStart(h)

        var sawTrainError = false
        var left = 3 * trainingTimeout
        while (
          left > 0 &&
          !(sawTrainError &&
            h.io.ltState(0)(0).peek().litValue == LTState.sRESET.litValue)
        ) {
          if (
            h.io.ltState(0)(0).peek().litValue == LTState.sTRAINERROR.litValue
          )
            sawTrainError = true
          rdiNeverInLinkError(h)
          h.clock.step(1)
          left -= 1
        }
        assert(sawTrainError, s"die 0 never timed out: ${states(h)}")
        h.io.cutSideband.get(0)(0).poke(false.B)

        left = mbInitCycles + 3 * mbTrainCycles
        while (
          left > 0 &&
          !(0 until 2).forall(die =>
            h.io.ltState(die)(0).peek().litValue == LTState.sLINKINIT.litValue
          )
        ) {
          rdiNeverInLinkError(h)
          h.clock.step(1)
          left -= 1
        }
        trainThenActivate(h, rdiFlagCycles, "the retry reached ACTIVE")(_ =>
          Seq(0)
        )
        rdiNeverInLinkError(h)
      }
    }

    it("Stage 3: the last retry failing escalates, and TRAINERROR holds") {
      /* With no retries left, the training die 0 started has failed for good:
         pl_trainerror, "a fatal error from the Physical Layer", which "must
         transition pl_state_sts to LinkError" and "remains asserted until RDI
         exits the LinkError state to Reset". Spec 4.5.3.8 keeps the Physical
         Layer in TRAINERROR for as long as that lasts, and 10.3.3.7 lets the
         RDI go to Reset once the Adapter asks, after the 16 ms residency. */
      simulate(
        new MmplLoopbackHarness(
          params = params,
          modulePairing = Seq(0),
          moduleFaultInjection = true,
          timeoutCyclesOverride = Some(trainingTimeout)
        ),
        firtoolOpts = firtoolOpts
      ) { h =>
        for (die <- 0 until 2) {
          h.io.holdPllUnlocked.get(die)(0).poke(false.B)
          h.io.cutSideband.get(die)(0).poke(false.B)
        }
        h.io.cutSideband.get(0)(0).poke(true.B)
        coldStart(h)

        stepWhileFailing(h, 2 * trainingTimeout, "die 0's RDI in LinkError") {
          h.io.plStateSts(0).peek().litValue == RDIState.linkError.litValue
        }
        h.io.plTrainError(0).expect(true.B, "pl_trainerror with it")
        h.clock.step(trainingTimeout)
        h.io.ltState(0)(0).expect(LTState.sTRAINERROR, "held in TRAINERROR")
        h.io.plStateSts(0).expect(RDIState.linkError)
        h.io.plTrainError(0).expect(true.B)

        // The Adapter asks to leave LinkError.
        h.io.lpStateReq(0).poke(RDIStateReq.active)
        stepWhileFailing(h, 3 * trainingTimeout, "die 0's RDI back in Reset") {
          h.io.plStateSts(0).peek().litValue == RDIState.reset.litValue
        }
        stepWhileFailing(h, sidebandCycles, "die 0 back in RESET") {
          h.io.ltState(0)(0).peek().litValue == LTState.sRESET.litValue
        }
        h.io.plTrainError(0).expect(false.B, "until LinkError is left")
        h.clock.step(trainingTimeout)
        h.io.ltState(0)(0).expect(LTState.sRESET, "no retry is left to run")
      }
    }

    it(
      "Stage 1: a one-sided LINKSPEED fault leaves both directions of the Module at one width"
    ) {
      /* Spec 4.5.3.4.13 Step 2 is MBTRAIN.REPAIR's copy of the MBINIT.REPAIRMB
         rule the multi-module Stage 13 checks. Only die 1's Transmitter into
         die 0 sees the fault, so die 1 degrades it and die 0's Transmitter
         back has nothing to repair. A multi-module Link never reaches REPAIR
         with a clean Module, because the MMPL's width degrade halves every
         Module (spec 4.7.1.2.1), but a one-Module Link does -- and REPAIR has
         no point test of its own to bring the clean direction down. Without
         the rule the Module left REPAIR with its two directions at x16 and
         x8. */
      val upperHalf = "b010".U(3.W)
      simulate(
        new MmplLoopbackHarness(
          params = params,
          modulePairing = Seq(0),
          dataPath = true,
          laneErrorInjection = true,
          timeoutCyclesOverride = Some(trainingTimeout)
        ),
        firtoolOpts = firtoolOpts
      ) { h =>
        h.io.injectLaneError.get(0)(0).poke(true.B)
        coldStart(h)
        stepWhileFailing(
          h,
          mbInitCycles + 2 * mbTrainCycles,
          "die 1 width degraded its Transmitter"
        ) {
          h.io.moduleLinkWidth(1)(0).peek().litValue == LinkWidth.x8.litValue
        }
        h.io.injectLaneError.get(0)(0).poke(false.B)

        trainThenActivate(h, 3 * mbTrainCycles, "the Module reached ACTIVE")(
          _ => Seq(0)
        )
        for (die <- 0 until 2) {
          h.io
            .moduleTxLanes(die)(0)
            .expect(upperHalf, s"die $die transmit Lane map")
          h.io
            .moduleRxLanes(die)(0)
            .expect(upperHalf, s"die $die receive Lane map")
        }

        for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
        stepWhileFailing(h, rdiFlagCycles, "the RDI reached Active") {
          (0 until 2).forall(die =>
            h.io.plStateSts(die).peek().litValue == RDIState.active.litValue
          )
        }
        for (die <- 0 until 2) {
          h.io.plLnkCfg(die).expect(LinkWidth.x8, s"die $die Link width")
          h.io.plTrainError(die).expect(false.B, s"die $die training error")
        }
        for (seq <- 0 until 2) {
          sendBothWaysMultiBeat(
            h,
            rdiWordBits,
            (0 until 2).map(payload(rdiWordBits, _, seq))
          )
        }
      }
    }
  }

  /** Names the first faulting sideband packet on every Module that saw one,
    * decoded per the header layout SBMsgCreate builds.
    */
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
      val msgInfo = ((hdr >> 40) & 0xffff).toInt
      f"die $die module $m unhandled=${h.io.sbUnhandledSeen(die)(m).peekBoolean()} " +
        f"opcode=$opcode%02x msgCode=$msgCode%02x subcode=$msgSubcode%02x info=$msgInfo%04x"
    }).mkString("; ")
}
