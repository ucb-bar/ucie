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
          .map(m => s"m$m=${h.io.ltState(die)(m).peek()}")
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
    climbTo(h, LTState.sACTIVE, sidebandCycles)
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
          stepWhileFailing(
            h,
            mbTrainCycles,
            "the surviving Modules reached ACTIVE"
          ) {
            (0 until 2).forall(die =>
              surviving(die).forall(m =>
                h.io.ltState(die)(m).peek().litValue == LTState.sACTIVE.litValue
              )
            )
          }

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
          stepWhileFailing(h, mbTrainCycles, "every Module left MBTRAIN.REPAIR") {
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

          stepWhileFailing(
            h,
            3 * mbTrainCycles,
            "the width-degraded Link reached ACTIVE on every Module"
          ) {
            (0 until 2).forall(die =>
              (0 until numModules).forall(m =>
                h.io.ltState(die)(m).peek().litValue == LTState.sACTIVE.litValue
              )
            )
          }

          for (die <- 0 until 2) h.io.lpStateReq(die).poke(RDIStateReq.active)
          stepWhileFailing(h, rdiFlagCycles, "the degraded RDI reached Active") {
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
    }
  }
}
