package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import org.scalatest.funspec.AnyFunSpec

import scala.util.Random

/** The MMPL aggregator: one RDI over several Modules, spec 4.7.
  *
  * The Modules are stubbed at their RDI, so this exercises the aggregation and
  * the beat handling rather than any real training.
  */
class MmplTest extends AnyFunSpec with ChiselSim {
  import MmplByteMap._

  private val randomSeed = 0x616767L // "agg"
  private val fullLanes = "b011"

  private def params(numModules: Int) = MmplParams(numModules = numModules)
  private def aggRdi(numModules: Int) =
    RdiParams(numModules * params(numModules).bytesPerModule, 32)

  private def dut(numModules: Int) =
    new Mmpl(params(numModules), aggRdi(numModules), new SidebandParams())

  // ==========================================================================
  // Driving the stubbed Modules
  // ==========================================================================

  /** A trained, idle Module reporting nothing to the MMPL. */
  private def initModule(
      c: Mmpl,
      m: Int,
      remoteModuleId: Int,
      laneCode: String = fullLanes,
      width: LinkWidth.Type = LinkWidth.x16
  ): Unit = {
    val rdi = c.io.modules(m).rdi
    rdi.plTrdy.poke(true.B)
    rdi.plValid.poke(false.B)
    rdi.plData.poke(0.U)
    rdi.plStateSts.poke(RDIState.active)
    rdi.plInbandPres.poke(true.B)
    rdi.plError.poke(false.B)
    rdi.plCError.poke(false.B)
    rdi.plNfError.poke(false.B)
    rdi.plTrainError.poke(false.B)
    rdi.plPhyInRecenter.poke(false.B)
    rdi.plStallReq.poke(false.B)
    rdi.plClkReq.poke(false.B)
    rdi.plWakeAck.poke(false.B)
    rdi.plSpeedmode.poke(SpeedMode.speed16)
    rdi.plMaxSpeedmode.poke(false.B)
    rdi.plLnkCfg.poke(width)
    rdi.plCfg.poke(0.U)
    rdi.plCfgVld.poke(false.B)
    rdi.plCfgCrd.poke(false.B)

    val st = c.io.modules(m).status
    st.ltState.poke(LTState.sMBTRAIN)
    st.currentState.poke(LTSMState.sMBTRAIN_LINKSPEED)
    st.trainingTimedout.poke(false.B)
    st.fatalTrainingError.poke(false.B)
    st.negotiatedPhyParamSettings.valid.poke(true.B)
    st.negotiatedPhyParamSettings.bits.voltageSwing.poke(0.U)
    st.negotiatedPhyParamSettings.bits.maxDataRate.poke(0.U)
    st.negotiatedPhyParamSettings.bits.clockMode.poke(0.U)
    st.negotiatedPhyParamSettings.bits.clockPhase.poke(0.U)
    st.negotiatedPhyParamSettings.bits.ucieSx8.poke(0.U)
    st.negotiatedPhyParamSettings.bits.sbFeatExt.poke(0.U)
    st.negotiatedPhyParamSettings.bits.txAdjRuntime.poke(0.U)
    st.negotiatedPhyParamSettings.bits.moduleId.poke(remoteModuleId.U)
    st.linkWidth.poke(width)
    st.freqSel.poke(SpeedMode.speed16)
    st.doLaneReversal.poke(false.B)
    st.widthDegraded.poke(false.B)
    st.txLaneMask.poke("hFFFF".U)
    st.rxLaneMask.poke("hFFFF".U)
    st.remoteRequestingTrainError.poke(false.B)
    st.localTxFunctionalLanes.poke(laneCode.U(3.W))
    st.remoteTxFunctionalLanes.poke(laneCode.U(3.W))
    st.retrainEncoding.poke(RetrainEncoding.TXSELFCAL)
    clearReport(c, m)

    // On a multi-module Link the RDI state machine lives in the MMPL, so each
    // Module hands up its view of training instead of its own RDI status.
    c.io.modules(m).rdiHost.foreach { host =>
      host.ltsmState.poke(LTState.sMBTRAIN)
      host.doRdiBringup.poke(false.B)
      host.trainingTimeout.poke(false.B)
      host.validFramingError.poke(false.B)
      host.cfgSidebandActive.poke(false.B)
      host.plPhyInRecenter.poke(false.B)
      host.clocksUngatedAndStable.poke(true.B)
      host.sbLaneIo.tx.ready.poke(true.B)
      host.sbLaneIo.rx.valid.poke(false.B)
      host.sbLaneIo.rx.bits.data.poke(0.U)
    }

    st.sideband.sbParityErrSeen.poke(false.B)
    st.sideband.sbRxPriorityQueuesFullSeen.poke(false.B)
    st.sideband.sbDeserializerTimedoutSeen.poke(false.B)
    st.sideband.sbInvalidRouteUpperSeen.poke(false.B)
    st.sideband.sbInvalidRouteCurrSeen.poke(false.B)
    st.sideband.sbInvalidRouteLowerSeen.poke(false.B)
    st.sideband.sbUnhandledCurrentLayerMsgSeen.poke(false.B)
    st.sideband.sbFirstFaultValid.poke(false.B)
    st.sideband.sbFirstFaultOpcode.poke(0.U)
    st.sideband.sbFirstFaultHeader.poke(0.U)
  }

  private def clearReport(c: Mmpl, m: Int): Unit = {
    val r = c.io.modules(m).status.linkSpeedReport
    r.valid.poke(false.B)
    r.bits.sentDone.poke(false.B)
    r.bits.sentRepair.poke(false.B)
    r.bits.sentSpeedDegrade.poke(false.B)
    r.bits.sentError.poke(false.B)
    r.bits.sentPhyRetrain.poke(false.B)
    r.bits.recvdDone.poke(false.B)
    r.bits.recvdRepair.poke(false.B)
    r.bits.recvdSpeedDegrade.poke(false.B)
    r.bits.recvdError.poke(false.B)
    r.bits.recvdPhyRetrain.poke(false.B)
  }

  private def initLink(c: Mmpl, n: Int, remoteIds: Seq[Int]): Unit = {
    for (m <- 0 until n) {
      initModule(c, m, remoteIds(m))
      c.io.moduleConnected(m).poke(true.B)
    }
    c.io.rdi.lclk.poke(false.B)
    c.io.rdi.lpIrdy.poke(false.B)
    c.io.rdi.lpValid.poke(false.B)
    c.io.rdi.lpData.poke(0.U)
    // The RDI state machine only leaves RESET after observing a NOP there,
    // which is what an Adapter presents before Stage 3 bring-up.
    c.io.rdi.lpStateReq.poke(RDIStateReq.nop)
    c.io.rdi.lpLinkError.poke(false.B)
    c.io.rdi.lpStallAck.poke(false.B)
    c.io.rdi.lpClkAck.poke(false.B)
    c.io.rdi.lpWakeReq.poke(false.B)
    c.io.rdi.lpCfg.poke(0.U)
    c.io.rdi.lpCfgVld.poke(false.B)
    c.io.rdi.lpCfgCrd.poke(false.B)
  }

  private def expectedSlice(
      lpData: BigInt,
      numModules: Int,
      numActive: Int,
      rank: Int,
      beat: Int,
      activeLanes: Int = 16
  ): BigInt = {
    val bytesPerModule = params(numModules).bytesPerModule
    (0 until bytesPerModule).foldLeft(BigInt(0)) { case (acc, j) =>
      val g =
        globalByte(j, activeLanes, numActive, bytesPerModule, beat, rank)
      acc | (((lpData >> (g * 8)) & 0xff) << (j * 8))
    }
  }

  // ==========================================================================
  // Transmit
  // ==========================================================================
  describe("MMPL transmit") {
    it("Scatters an RDI word by remote Module ID") {
      // Table 5-27, x4 unstacked Standard Die Rotate: M0 faces M2 and M1 faces
      // M3. Ranking transmit by the remote ID is what puts the bytes where the
      // remote Receiver looks for them (spec Figure 4-44).
      val n = 4
      val remoteIds = Seq(2, 3, 0, 1)
      simulate(dut(n)) { c =>
        initLink(c, n, remoteIds)
        val random = new Random(randomSeed)
        val lpData = BigInt(aggRdi(n).nBytes * 8, random)

        c.io.rdi.lpData.poke(lpData.U((aggRdi(n).nBytes * 8).W))
        c.io.rdi.lpValid.poke(true.B)
        c.io.rdi.lpIrdy.poke(true.B)

        c.io.rdi.plTrdy.expect(true.B, "every Module is ready")
        for (m <- 0 until n) {
          // The remote IDs are a permutation, so rank equals the remote ID.
          val expected = expectedSlice(lpData, n, n, remoteIds(m), 0)
          c.io
            .modules(m)
            .rdi
            .lpData
            .expect(
              expected.U((params(n).bytesPerModule * 8).W),
              s"module $m carries the bytes remote M${remoteIds(m)} expects"
            )
          c.io.modules(m).rdi.lpValid.expect(true.B)
          c.io.modules(m).rdi.lpIrdy.expect(true.B)
        }
      }
    }

    it("Holds pl_trdy until every Module is ready") {
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        c.io.rdi.plTrdy.expect(true.B)

        c.io.modules(1).rdi.plTrdy.poke(false.B)
        c.io.rdi.plTrdy.expect(
          false.B,
          "one Module back-pressuring must stall the whole RDI"
        )

        c.io.modules(1).rdi.plTrdy.poke(true.B)
        c.io.rdi.plTrdy.expect(true.B)
      }
    }

    it("Does not present data to the Modules while the Adapter is idle") {
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        for (m <- 0 until n) {
          c.io.modules(m).rdi.lpValid.expect(false.B)
          c.io.modules(m).rdi.lpIrdy.expect(false.B)
        }
      }
    }

    it("Withholds the beat from a ready Module until every Module is ready") {
      /* A Module latches whenever its own pl_trdy meets lp_valid and lp_irdy
         (spec Table 10-1). Offering the beat to whichever Modules happen to be
         ready would have them transmit their slice now and then again when the
         aggregate finally fires, leaving the Modules' byte streams permanently
         one beat apart on the wire. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        val random = new Random(randomSeed)
        val word = BigInt(aggRdi(n).nBytes * 8, random)
        c.io.rdi.lpData.poke(word.U((aggRdi(n).nBytes * 8).W))
        c.io.rdi.lpValid.poke(true.B)
        c.io.rdi.lpIrdy.poke(true.B)

        // Module 1's AFE is backed up; Module 0 is ready and must not go.
        c.io.modules(1).rdi.plTrdy.poke(false.B)
        c.io.rdi.plTrdy.expect(false.B, "the aggregate must not accept a word")
        for (m <- 0 until n) {
          c.io
            .modules(m)
            .rdi
            .lpValid
            .expect(false.B, s"module $m must not latch the beat alone")
          c.io.modules(m).rdi.lpIrdy.expect(false.B, s"module $m")
        }

        // Once both are ready the whole beat goes out together.
        c.io.modules(1).rdi.plTrdy.poke(true.B)
        c.io.rdi.plTrdy.expect(true.B)
        for (m <- 0 until n) {
          c.io.modules(m).rdi.lpValid.expect(true.B, s"module $m")
          c.io.modules(m).rdi.lpIrdy.expect(true.B, s"module $m")
        }
      }
    }
  }

  // ==========================================================================
  // Receive
  // ==========================================================================
  describe("MMPL receive") {
    it("Gathers a word with no skew in a single cycle") {
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        val random = new Random(randomSeed)
        val word = BigInt(aggRdi(n).nBytes * 8, random)

        for (m <- 0 until n) {
          c.io.modules(m).rdi.plValid.poke(true.B)
          c.io
            .modules(m)
            .rdi
            .plData
            .poke(
              expectedSlice(word, n, n, m, 0)
                .U((params(n).bytesPerModule * 8).W)
            )
        }
        c.io.rdi.plValid.expect(true.B, "both slices are present")
        c.io.rdi.plData.expect(
          word.U((aggRdi(n).nBytes * 8).W),
          "the aggregate word is the two slices interleaved"
        )
      }
    }

    it("Aligns Modules that deliver their slice cycles apart") {
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        val random = new Random(randomSeed)
        val word = BigInt(aggRdi(n).nBytes * 8, random)
        val slices = (0 until n).map(m => expectedSlice(word, n, n, m, 0))
        // Module m arrives m cycles late.
        val arrival = (0 until n).map(identity)

        for (cycle <- 0 to n) {
          for (m <- 0 until n) {
            val here = arrival(m) == cycle
            c.io.modules(m).rdi.plValid.poke(here.B)
            if (here) {
              c.io
                .modules(m)
                .rdi
                .plData
                .poke(slices(m).U((params(n).bytesPerModule * 8).W))
            }
          }
          val complete = cycle == arrival.max
          if (complete) {
            c.io.rdi.plValid.expect(
              true.B,
              s"the last slice landed on cycle $cycle"
            )
            c.io.rdi.plData.expect(word.U((aggRdi(n).nBytes * 8).W))
          } else {
            c.io.rdi.plValid.expect(
              false.B,
              s"cycle $cycle is still waiting on a slice"
            )
          }
          c.clock.step()
        }
      }
    }

    it("Waits for every operational Module before presenting a word") {
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        // A Module only offers a slice once its own RDI is Active, so a gather
        // that is missing one cannot complete.
        c.io.modules(0).rdi.plValid.poke(true.B)
        c.io.modules(0).rdi.plData.poke("hAA".U)
        c.io.rdi.plValid.expect(false.B)
      }
    }

    it("Absorbs a run of beats from a Module that is ahead of its siblings") {
      /* pl_valid has no backpressure (spec 10.1.4) and spec 4.7.1.2 warns that
         Modules of one Link can be staggered, so a Module streaming ahead has
         nowhere to go but the alignment queue. */
      val n = 2
      val depth = params(n).rxAlignDepth
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        val random = new Random(randomSeed)
        val words =
          Seq.fill(depth)(BigInt(aggRdi(n).nBytes * 8, random))

        // Module 0 delivers every slice back to back; Module 1 says nothing.
        for (w <- words) {
          c.io.modules(0).rdi.plValid.poke(true.B)
          c.io
            .modules(0)
            .rdi
            .plData
            .poke(
              expectedSlice(w, n, n, 0, 0).U((params(n).bytesPerModule * 8).W)
            )
          c.io.rdi.plValid.expect(false.B, "no word is complete yet")
          c.clock.step()
        }
        c.io.modules(0).rdi.plValid.poke(false.B)

        // Module 1 catches up; the words must come back out in order.
        for (w <- words) {
          c.io.modules(1).rdi.plValid.poke(true.B)
          c.io
            .modules(1)
            .rdi
            .plData
            .poke(
              expectedSlice(w, n, n, 1, 0).U((params(n).bytesPerModule * 8).W)
            )
          c.io.rdi.plValid.expect(true.B, "the pair should now align")
          c.io.rdi.plData.expect(w.U((aggRdi(n).nBytes * 8).W))
          c.clock.step()
        }
        c.io.modules(1).rdi.plValid.poke(false.B)
      }
    }
  }

  // ==========================================================================
  // Aggregate status
  // ==========================================================================
  describe("MMPL aggregate status") {
    it("Sums the Module widths into pl_lnk_cfg") {
      for (
        (n, moduleWidth, expected) <- Seq(
          (1, LinkWidth.x16, LinkWidth.x16),
          (2, LinkWidth.x16, LinkWidth.x32),
          (4, LinkWidth.x16, LinkWidth.x64),
          (2, LinkWidth.x8, LinkWidth.x16),
          (4, LinkWidth.x8, LinkWidth.x32)
        )
      ) {
        simulate(dut(n)) { c =>
          initLink(c, n, (0 until n))
          for (m <- 0 until n) {
            c.io.modules(m).status.linkWidth.poke(moduleWidth)
            c.io.modules(m).rdi.plLnkCfg.poke(moduleWidth)
          }
          c.io.rdi.plLnkCfg.expect(
            expected,
            s"$n modules of $moduleWidth should aggregate to $expected"
          )
        }
      }
    }

    it("Takes the RDI state from the one hosted state machine") {
      // Spec 3.5: a multi-module Link has a single RDI state machine, hosted
      // here. The Modules have none, so nothing they say about RDI state can
      // reach the Adapter.
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        c.io.rdi.plStateSts.expect(
          RDIState.reset,
          "the hosted machine has not been brought up"
        )
        c.io.rdi.plInbandPres.expect(false.B)

        for (
          state <- Seq(RDIState.active, RDIState.retrain, RDIState.linkError)
        ) {
          for (m <- 0 until n) c.io.modules(m).rdi.plStateSts.poke(state)
          c.io.rdi.plStateSts.expect(
            RDIState.reset,
            s"a Module reporting $state must not move the Link's RDI state"
          )
        }
      }
    }

    it("ORs the per-Module error signals onto the RDI") {
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        for (
          (poke, read) <- Seq[(Int => Unit, () => Bool)](
            (
              m => c.io.modules(m).rdi.plError.poke(true.B),
              () => c.io.rdi.plError.peek()
            ),
            (
              m => c.io.modules(m).rdi.plTrainError.poke(true.B),
              () => c.io.rdi.plTrainError.peek()
            ),
            (
              m => c.io.modules(m).rdi.plPhyInRecenter.poke(true.B),
              () => c.io.rdi.plPhyInRecenter.peek()
            )
          )
        ) {
          poke(1)
          assert(
            read().litToBoolean,
            "an error on any Module must reach the RDI"
          )
        }
      }
    }

    it("Defers rather than rejects a message on a Module that loses the grant") {
      /* `sbLaneIo.rx.ready` is a claim decode, not flow control: a LogicalPhy
         retires anything no consumer claimed in the cycle it was offered. With
         one hosted state machine serving several Modules, a Module that loses
         arbitration has not been rejected -- it has not been looked at yet, and
         reading its low ready as a rejection destroys the packet. Spec 4.7.1.1
         makes this reachable: "A packet sent on a given Module ID could be
         received on a different Module ID." */
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))

        // Modules 1 and 3 both present a message in the same cycle.
        for (m <- Seq(1, 3)) {
          c.io.modules(m).rdiHost.get.sbLaneIo.rx.valid.poke(true.B)
          c.io.modules(m).rdiHost.get.sbLaneIo.rx.bits.data.poke((0x50 + m).U)
        }

        val held = (0 until n).filter(m =>
          c.io.modules(m).rdiHost.get.rxHold.peek().litToBoolean
        )
        // Exactly one of the two is being looked at this cycle; the other has
        // not been rejected, it has not had its turn, and must be told to hold
        // rather than left to read a low ready as "nobody wants this".
        assert(
          held.toSet.subsetOf(Set(1, 3)),
          s"a Module with nothing pending was held: $held"
        )
        assert(
          held.length == 1,
          s"expected exactly one of {1, 3} to be deferred, held=$held"
        )

        // Once the Module under consideration goes quiet, the deferred one
        // gets its turn instead of having been dropped.
        val deferred = held.head
        val underConsideration = Seq(1, 3).filterNot(_ == deferred).head
        c.io
          .modules(underConsideration)
          .rdiHost
          .get
          .sbLaneIo
          .rx
          .valid
          .poke(false.B)
        assert(
          !c.io.modules(deferred).rdiHost.get.rxHold.peek().litToBoolean,
          s"module $deferred should now be the one being looked at"
        )
      }
    }

    it("Never collapses two sideband credit returns into one") {
      /* Table 10-1: a 1 on pl_cfg_crd is exactly one credit return, so two
         Modules returning one on the same cycle cannot share a wire without
         the second being lost for good. */
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))

        // All four return a credit on the same cycle, then go quiet.
        var returned = 0
        for (m <- 0 until n) c.io.modules(m).rdi.plCfgCrd.poke(true.B)
        if (c.io.rdi.plCfgCrd.peek().litToBoolean) returned += 1
        c.clock.step()
        for (m <- 0 until n) c.io.modules(m).rdi.plCfgCrd.poke(false.B)

        for (_ <- 0 until 16) {
          if (c.io.rdi.plCfgCrd.peek().litToBoolean) returned += 1
          c.clock.step()
        }
        assert(
          returned == n,
          s"$n Modules returned a credit each but the Adapter saw $returned"
        )
      }
    }

    it("Leaves an unconnected Module out of every aggregate") {
      /* Chapter 5 permits a Link whose two die have different Module counts
         (Table 5-28), where some local Modules are NC and never train. Left in
         the aggregate, such a Module blocks bring-up for ever and its eventual
         training timeout drags the whole RDI into LinkError. */
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        // M1 and M3 are not wired to anything, as in spec Figure 4-46.
        c.io.moduleConnected(1).poke(false.B)
        c.io.moduleConnected(3).poke(false.B)
        for (m <- Seq(1, 3)) {
          c.io.modules(m).status.ltState.poke(LTState.sRESET)
          c.io.modules(m).rdiHost.get.ltsmState.poke(LTState.sRESET)
          c.io.modules(m).rdiHost.get.trainingTimeout.poke(true.B)
          c.io.modules(m).rdi.plTrainError.poke(true.B)
        }
        c.clock.step()

        for (m <- Seq(1, 3)) {
          assert(
            !c.io.status.moduleEnable(m).peek().litToBoolean,
            s"module $m is not connected and must not be operational"
          )
        }
        for (m <- Seq(0, 2)) {
          assert(
            c.io.status.moduleEnable(m).peek().litToBoolean,
            s"module $m is connected and must stay operational"
          )
        }
        c.io.rdi.plTrainError.expect(
          false.B,
          "an unconnected Module must not take the Link into LinkError"
        )
        // Two active Modules, x16 each, so the aggregate is x32.
        c.io.rdi.plLnkCfg.expect(
          LinkWidth.x32,
          "the width must count only the connected Modules"
        )
      }
    }

    it("Sends RDI link management on the least Module past SBINIT") {
      // Spec 4.7.1.1: {LinkMgmt.RDI.*} is in Table 7-8, so it uses a single
      // sideband -- the numerically least Module ID whose LTSM is not in RESET
      // or SBINIT. The receive direction, where a response can land on a
      // different Module ID, is covered end to end by MmplStagedBringupTest,
      // whose permuted pairings put it on another Module for real.
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))

        def transmittingModule: Option[Int] =
          (0 until n).find(m =>
            c.io.modules(m).rdiHost.get.sbLaneIo.tx.valid.peekBoolean()
          )

        /** Step while acking the clock and stall handshakes, as an Adapter
          * would; the state machine will not reach Active without them.
          */
        def stepAcked(cycles: Int = 1): Unit = for (_ <- 0 until cycles) {
          c.io.rdi.lpClkAck.poke(c.io.rdi.plClkReq.peek())
          c.io.rdi.lpStallAck.poke(c.io.rdi.plStallReq.peek())
          c.clock.step()
        }

        /** The state machine takes a few cycles to build its request. */
        def waitForTransmit(context: String): Int = {
          var left = 128
          while (left > 0 && transmittingModule.isEmpty) {
            stepAcked()
            left -= 1
          }
          transmittingModule.getOrElse(
            fail(s"$context: no Module transmitted an RDI message")
          )
        }

        // NOP in RESET first, then take the Modules to LINKINIT, which is what
        // asks the Physical Layer to bring RDI up.
        stepAcked(2)
        c.io.rdi.lpStateReq.poke(RDIStateReq.active)
        for (m <- 0 until n) {
          c.io.modules(m).rdiHost.get.ltsmState.poke(LTState.sLINKINIT)
          c.io.modules(m).rdiHost.get.doRdiBringup.poke(true.B)
        }

        assert(
          waitForTransmit("all Modules trained") == 0,
          "Module 0 is the numerically least Module past SBINIT"
        )

        c.io.modules(0).status.ltState.poke(LTState.sRESET)
        c.io.modules(1).status.ltState.poke(LTState.sSBINIT)
        assert(
          waitForTransmit("Modules 0 and 1 not past SBINIT") == 2,
          "Module 2 becomes the numerically least Module past SBINIT"
        )
      }
    }

    it("Broadcasts the Adapter's requests and handshakes to every Module") {
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        c.io.rdi.lpStateReq.poke(RDIStateReq.retrain)
        c.io.rdi.lpStallAck.poke(true.B)
        c.io.rdi.lpClkAck.poke(true.B)
        c.io.rdi.lpWakeReq.poke(true.B)
        c.io.rdi.lpLinkError.poke(true.B)
        for (m <- 0 until n) {
          c.io.modules(m).rdi.lpStateReq.expect(RDIStateReq.retrain)
          c.io.modules(m).rdi.lpStallAck.expect(true.B)
          c.io.modules(m).rdi.lpClkAck.expect(true.B)
          c.io.modules(m).rdi.lpWakeReq.expect(true.B)
          c.io.modules(m).rdi.lpLinkError.expect(true.B)
        }
      }
    }
  }

  // ==========================================================================
  // Sideband cfg routing, spec 4.7.1.1
  // ==========================================================================
  describe("MMPL sideband cfg routing") {
    /** Presents one whole cfg packet on the aggregate lp_cfg. */
    def sendCfgPacket(c: Mmpl, chunks: Seq[BigInt]): Unit = {
      for (chunk <- chunks) {
        c.io.rdi.lpCfg.poke(chunk.U(32.W))
        c.io.rdi.lpCfgVld.poke(true.B)
        c.clock.step()
      }
      c.io.rdi.lpCfgVld.poke(false.B)
      c.io.rdi.lpCfg.poke(0.U)
    }

    /** Collects what each Module was handed, cycle by cycle, for `cycles`. */
    def collectCfgTx(
        c: Mmpl,
        n: Int,
        cycles: Int,
        onStep: Int => Unit = _ => ()
    ): Seq[Seq[(Int, BigInt)]] = {
      val perCycle = scala.collection.mutable.ArrayBuffer[Seq[(Int, BigInt)]]()
      for (i <- 0 until cycles) {
        perCycle += (0 until n)
          .filter(c.io.modules(_).rdi.lpCfgVld.peek().litToBoolean)
          .map(m => (m, c.io.modules(m).rdi.lpCfg.peek().litValue))
        onStep(i)
        c.clock.step()
      }
      perCycle.toSeq
    }

    it("Transmits a whole packet on the numerically least Module past SBINIT") {
      val n = 4
      val chunksPerPacket = new SidebandParams().sbNodeMsgWidth / 32
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        val packet = Seq.tabulate(chunksPerPacket)(i => BigInt(0xd0 + i))
        sendCfgPacket(c, packet)

        val seen = collectCfgTx(c, n, 16).filter(_.nonEmpty)
        assert(
          seen.forall(_.length == 1),
          s"more than one Module carried a chunk at once: $seen"
        )
        assert(
          seen.map(_.head._1).distinct == Seq(0),
          s"expected the whole packet on Module 0, got ${seen.map(_.head._1)}"
        )
        assert(
          seen.map(_.head._2) == packet,
          s"packet came out as ${seen.map(_.head._2)}, expected $packet"
        )
      }
    }

    it("Picks the next eligible Module when Module 0 is not past SBINIT") {
      val n = 4
      val chunksPerPacket = new SidebandParams().sbNodeMsgWidth / 32
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        // Module 0 back in RESET and Module 1 still in SBINIT, so Module 2 wins.
        c.io.modules(0).status.ltState.poke(LTState.sRESET)
        c.io.modules(1).status.ltState.poke(LTState.sSBINIT)

        val packet = Seq.tabulate(chunksPerPacket)(i => BigInt(0xa0 + i))
        sendCfgPacket(c, packet)

        val seen = collectCfgTx(c, n, 16).filter(_.nonEmpty)
        assert(
          seen.map(_.head._1).distinct == Seq(2),
          s"expected Module 2 to carry it, got ${seen.map(_.head._1)}"
        )
        assert(seen.map(_.head._2) == packet, s"got ${seen.map(_.head._2)}")
      }
    }

    it("Keeps a packet on one Module even if the eligible set moves") {
      /* Spec 7.1.4: the phases of a packet go out on consecutive cycles, so the
         transmitting Module cannot change part way through. The eligible set is
         a live function of the LTSM states, and Modules train staggered, so
         without a registered grant a Module coming out of SBINIT mid-packet
         would take over and both halves would be framed as garbage. */
      val n = 4
      val chunksPerPacket = new SidebandParams().sbNodeMsgWidth / 32
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        // Only Module 2 is eligible when the packet starts.
        c.io.modules(0).status.ltState.poke(LTState.sSBINIT)
        c.io.modules(1).status.ltState.poke(LTState.sSBINIT)

        val packet = Seq.tabulate(chunksPerPacket)(i => BigInt(0xb0 + i))
        sendCfgPacket(c, packet)

        // Module 0 finishes SBINIT one cycle into the transfer, which would
        // otherwise make it the numerically least eligible Module.
        val seen = collectCfgTx(
          c,
          n,
          16,
          onStep = i =>
            if (i == 1) c.io.modules(0).status.ltState.poke(LTState.sMBTRAIN)
        ).filter(_.nonEmpty)

        assert(
          seen.map(_.head._1).distinct == Seq(2),
          s"the packet was split across Modules ${seen.map(_.head._1)}"
        )
        assert(seen.map(_.head._2) == packet, s"got ${seen.map(_.head._2)}")
      }
    }

    it("Stages a packet the Adapter sends before any Module is eligible") {
      /* lp_cfg has no ready line, so chunks that arrive while every Module is
         still in RESET or SBINIT cannot be back-pressured -- and dropping them
         also strands the credit the Adapter already spent. */
      val n = 2
      val chunksPerPacket = new SidebandParams().sbNodeMsgWidth / 32
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        for (m <- 0 until n) {
          c.io.modules(m).status.ltState.poke(LTState.sSBINIT)
        }

        val packet = Seq.tabulate(chunksPerPacket)(i => BigInt(0xc0 + i))
        sendCfgPacket(c, packet)

        // Nothing may go out while no Module can carry it.
        val whileIneligible = collectCfgTx(c, n, 8).filter(_.nonEmpty)
        assert(
          whileIneligible.isEmpty,
          s"chunks went out with no eligible Module: $whileIneligible"
        )

        // Module 1 trains; the staged packet must come out whole, on Module 1.
        c.io.modules(1).status.ltState.poke(LTState.sMBTRAIN)
        val seen = collectCfgTx(c, n, 16).filter(_.nonEmpty)
        assert(
          seen.map(_.head._1).distinct == Seq(1),
          s"expected Module 1 to carry it, got ${seen.map(_.head._1)}"
        )
        assert(
          seen.map(_.head._2) == packet,
          s"staged packet came out as ${seen.map(_.head._2)}"
        )
      }
    }

    it("Forwards a received packet from any Module, one packet at a time") {
      val n = 2
      val chunksPerPacket = new SidebandParams().sbNodeMsgWidth / 32
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))

        // Both Modules present a whole packet at once; spec 4.7.1.1 allows a
        // packet sent on one Module ID to arrive on another, so neither may be
        // dropped and their chunks must not interleave.
        val packets = Seq(
          Seq.tabulate(chunksPerPacket)(i => BigInt(0x10 + i)),
          Seq.tabulate(chunksPerPacket)(i => BigInt(0x20 + i))
        )
        for (chunk <- 0 until chunksPerPacket) {
          for (m <- 0 until n) {
            c.io.modules(m).rdi.plCfgVld.poke(true.B)
            c.io.modules(m).rdi.plCfg.poke(packets(m)(chunk).U(32.W))
          }
          c.clock.step()
        }
        for (m <- 0 until n) c.io.modules(m).rdi.plCfgVld.poke(false.B)

        // Drain and check both packets came out whole and in order.
        val seen = scala.collection.mutable.ArrayBuffer[BigInt]()
        var cycles = 0
        while (seen.length < 2 * chunksPerPacket && cycles < 64) {
          if (c.io.rdi.plCfgVld.peek().litToBoolean) {
            seen += c.io.rdi.plCfg.peek().litValue
          }
          c.clock.step()
          cycles += 1
        }
        assert(
          seen.toSeq == packets(0) ++ packets(1) ||
            seen.toSeq == packets(1) ++ packets(0),
          s"expected two whole packets back to back, saw $seen"
        )
      }
    }
  }

  // ==========================================================================
  // Resolution and the degraded datapath
  // ==========================================================================
  describe("MMPL resolution") {
    it("Directs every Module and then shrinks the operational set") {
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))

        // Module 1 asked for a width degrade at 16 GT/s. Fewer than half the
        // Modules reported, so spec 4.7.1 disables Module 1 and Module 0 goes
        // on to LINKINIT.
        for (m <- 0 until n) {
          val r = c.io.modules(m).status.linkSpeedReport
          r.valid.poke(true.B)
          r.bits.sentDone.poke((m == 0).B)
          r.bits.sentRepair.poke((m == 1).B)
          r.bits.recvdDone.poke(true.B)
        }
        c.clock.step()

        c.io.modules(0).ctrl.resolution.valid.expect(true.B)
        c.io.modules(0).ctrl.resolution.bits.expect(MmplResolution.done)
        c.io.modules(1).ctrl.resolution.valid.expect(true.B)
        c.io
          .modules(1)
          .ctrl
          .resolution
          .bits
          .expect(MmplResolution.disableModule)
        c.io.status.moduleEnable(1).expect(true.B, "not dropped yet")

        // The Modules act on the directive and leave MBTRAIN.LINKSPEED.
        for (m <- 0 until n) clearReport(c, m)
        c.clock.step()

        c.io.status.moduleEnable(0).expect(true.B)
        c.io.status.moduleEnable(1).expect(false.B, "Module 1 is disabled")
      }
    }

    it("Spreads the RDI word over successive beats once a Module is gone") {
      // Spec Figure 4-46: with half the Modules disabled the surviving Lanes
      // carry the remaining bytes of the RDI in later 8-UI intervals.
      val n = 2
      val bytesPerModule = params(n).bytesPerModule
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))

        for (m <- 0 until n) {
          val r = c.io.modules(m).status.linkSpeedReport
          r.valid.poke(true.B)
          r.bits.sentDone.poke((m == 0).B)
          r.bits.sentRepair.poke((m == 1).B)
          r.bits.recvdDone.poke(true.B)
        }
        c.clock.step()
        for (m <- 0 until n) clearReport(c, m)
        c.clock.step()
        c.io.status.moduleEnable(1).expect(false.B)

        val random = new Random(randomSeed)
        val lpData = BigInt(aggRdi(n).nBytes * 8, random)
        c.io.rdi.lpData.poke(lpData.U((aggRdi(n).nBytes * 8).W))
        c.io.rdi.lpValid.poke(true.B)
        c.io.rdi.lpIrdy.poke(true.B)

        // Beat 0 carries the low half of the word, beat 1 the high half.
        c.io.rdi.plTrdy.expect(true.B, "beat 0 accepts the word")
        c.io
          .modules(0)
          .rdi
          .lpData
          .expect(
            expectedSlice(lpData, n, 1, 0, 0).U((bytesPerModule * 8).W),
            "beat 0"
          )
        c.io.modules(1).rdi.lpValid.expect(false.B, "Module 1 is disabled")
        c.clock.step()

        c.io.rdi.plTrdy.expect(false.B, "the word is mid-flight")
        c.io
          .modules(0)
          .rdi
          .lpData
          .expect(
            expectedSlice(lpData, n, 1, 0, 1).U((bytesPerModule * 8).W),
            "beat 1"
          )
        c.clock.step()
        c.io.rdi.plTrdy.expect(true.B, "ready for the next word")

        // The receive direction reassembles the same way.
        c.io.rdi.lpValid.poke(false.B)
        c.io.rdi.lpIrdy.poke(false.B)
        val rxWord = BigInt(aggRdi(n).nBytes * 8, random)
        c.io.modules(0).rdi.plValid.poke(true.B)
        c.io
          .modules(0)
          .rdi
          .plData
          .poke(
            expectedSlice(rxWord, n, 1, 0, 0).U((bytesPerModule * 8).W)
          )
        c.io.rdi.plValid.expect(false.B, "only half the word has arrived")
        c.clock.step()
        c.io
          .modules(0)
          .rdi
          .plData
          .poke(
            expectedSlice(rxWord, n, 1, 0, 1).U((bytesPerModule * 8).W)
          )
        c.io.rdi.plValid.expect(true.B, "the word is complete")
        c.io.rdi.plData.expect(rxWord.U((aggRdi(n).nBytes * 8).W))
      }
    }

    it("Makes the PHYRETRAIN encoding common to the Link") {
      // Spec 4.5.3.7: all Modules must retrain the same way, and Table 4-12
      // resolves a conflict in favour of the more drastic action.
      val n = 4
      def encoding(c: Mmpl, m: Int) =
        c.io.modules(m).ctrl.commonRetrainEncoding.bits.peek().litValue

      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        c.clock.step()
        for (m <- 0 until n) {
          c.io.modules(m).ctrl.commonRetrainEncoding.valid.expect(true.B)
          assert(
            encoding(c, m) == RetrainEncoding.TXSELFCAL.litValue,
            s"module $m did not start at TXSELFCAL"
          )
        }

        c.io.modules(2).status.retrainEncoding.poke(RetrainEncoding.REPAIR)
        c.clock.step()
        for (m <- 0 until n) {
          assert(
            encoding(c, m) == RetrainEncoding.REPAIR.litValue,
            s"module $m did not take the Link's REPAIR encoding"
          )
        }

        c.io.modules(0).status.retrainEncoding.poke(RetrainEncoding.SPEEDIDLE)
        c.clock.step()
        for (m <- 0 until n) {
          assert(
            encoding(c, m) == RetrainEncoding.SPEEDIDLE.litValue,
            s"module $m did not take the Link's SPEEDIDLE encoding"
          )
        }
      }
    }

    it("Holds the retrain encoding steady once a Module enters PHYRETRAIN") {
      /* Spec 4.5.3.7 requires the Retrain encoding, and therefore the exit
         resolution, to be the same on every Module. A Module puts its encoding
         on the wire in {PHYRETRAIN.retrain start req} and then resolves the
         remote's reply against it, and Modules enter PHYRETRAIN staggered -- so
         a value that kept tracking live per-Module state would let one Module
         transmit one encoding and resolve with another, and the two die would
         leave PHYRETRAIN for different states. */
      val n = 4
      def encoding(c: Mmpl, m: Int) =
        c.io.modules(m).ctrl.commonRetrainEncoding.bits.peek().litValue

      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        c.io.modules(1).status.retrainEncoding.poke(RetrainEncoding.REPAIR)
        c.clock.step()
        assert(
          encoding(c, 0) == RetrainEncoding.REPAIR.litValue,
          "the Link should have settled on REPAIR before anyone retrains"
        )

        // Module 0 gets there first and transmits REPAIR.
        c.io.modules(0).status.ltState.poke(LTState.sPHYRETRAIN)
        c.clock.step()

        // Module 3 only now discovers an unrepairable fault. The aggregate must
        // not move under Module 0, which has already committed to REPAIR.
        c.io.modules(3).status.retrainEncoding.poke(RetrainEncoding.SPEEDIDLE)
        c.clock.step()
        for (m <- 0 until n) {
          assert(
            encoding(c, m) == RetrainEncoding.REPAIR.litValue,
            s"module $m saw the encoding change during PHYRETRAIN"
          )
        }

        // Once the Link is out of PHYRETRAIN the aggregate tracks again.
        c.io.modules(0).status.ltState.poke(LTState.sMBTRAIN)
        c.clock.step()
        for (m <- 0 until n) {
          assert(
            encoding(c, m) == RetrainEncoding.SPEEDIDLE.litValue,
            s"module $m did not pick the new encoding back up"
          )
        }
      }
    }
  }

  // ==========================================================================
  // One module
  // ==========================================================================
  describe("MMPL with one module") {
    it("Passes the RDI straight through") {
      simulate(dut(1)) { c =>
        initLink(c, 1, Seq(0))
        val random = new Random(randomSeed)
        val word = BigInt(aggRdi(1).nBytes * 8, random)

        c.io.rdi.lpData.poke(word.U((aggRdi(1).nBytes * 8).W))
        c.io.rdi.lpValid.poke(true.B)
        c.io.rdi.lpIrdy.poke(true.B)
        c.io
          .modules(0)
          .rdi
          .lpData
          .expect(word.U((aggRdi(1).nBytes * 8).W), "identity scatter")
        c.io.rdi.plTrdy.expect(true.B)

        c.io.modules(0).rdi.plValid.poke(true.B)
        c.io.modules(0).rdi.plData.poke(word.U((aggRdi(1).nBytes * 8).W))
        c.io.rdi.plValid.expect(true.B, "no added latency")
        c.io.rdi.plData.expect(word.U((aggRdi(1).nBytes * 8).W))

        c.io.rdi.plLnkCfg.expect(LinkWidth.x16)
        c.io.modules(0).ctrl.multiModule.expect(false.B)
      }
    }
  }
}
