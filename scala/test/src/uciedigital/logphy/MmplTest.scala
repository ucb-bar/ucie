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

  // Spec 10.3.3.7's 16 ms LinkError residency is 12.8M cycles; far more than
  // these checks need.
  private val linkErrorResidency = 64

  private def dut(numModules: Int) =
    new Mmpl(
      params(numModules),
      aggRdi(numModules),
      new SidebandParams(),
      linkErrorResidency
    )

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
    st.trainingEpisode.active.poke(false.B)
    st.trainingEpisode.local.poke(false.B)
    st.trainingEpisode.retries.poke(0.U)
    st.trainingEpisode.retryMax.poke(0.U)
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
    st.linkSpeedRespMismatch.poke(false.B)
    st.localTxFunctionalLanes.poke(laneCode.U(3.W))
    st.remoteTxFunctionalLanes.poke(laneCode.U(3.W))
    st.retrainEncoding.poke(RetrainEncoding.TXSELFCAL)
    clearReport(c, m)

    // On a multi-module Link the RDI state machine lives in the MMPL, so each
    // Module hands up its view of training instead of its own RDI status.
    c.io.modules(m).rdiHost.foreach { host =>
      host.ltsmState.poke(LTState.sMBTRAIN)
      host.doRdiBringup.poke(false.B)
      host.internalLinkError.poke(false.B)
      host.swRetrainRequest.poke(false.B)
      host.txIdle.poke(true.B)
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

  /** A Module that has acted on its directive drops its report *and* leaves
    * MBTRAIN.LINKSPEED. The MMPL holds the directive until the whole Link has
    * gone, so that a Module still working its way towards the substate that
    * samples one does not arrive to find it withdrawn (spec 4.7.1.2's stagger).
    */
  private def actOnResolution(c: Mmpl, m: Int): Unit = {
    clearReport(c, m)
    c.io.modules(m).status.currentState.poke(LTSMState.sLINKINIT)
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

  // ==========================================================================
  // Bringing the hosted RDI state machine up
  // ==========================================================================

  /** The header fields SBMsgCompare looks at: opcode, MsgCode and MsgSubcode of
    * a message without data.
    */
  private def sbMsg(code: Int, subcode: Int): BigInt =
    BigInt(0x12) | (BigInt(code) << 14) | (BigInt(subcode) << 32)
  private val rdiReqActive = sbMsg(0x01, 0x01)
  private val rdiRspActive = sbMsg(0x02, 0x01)
  private val rdiRspPmNak = sbMsg(0x02, 0x02)

  /** Offers a message from the remote die on Module `m` until the hosted RDI
    * state machine takes it.
    */
  private def offerRemote(c: Mmpl, m: Int, msg: BigInt): Unit = {
    val rx = c.io.modules(m).rdiHost.get.sbLaneIo.rx
    rx.valid.poke(true.B)
    rx.bits.data.poke(msg.U)
    var claimed = false
    var left = 256
    while (!claimed && left > 0) {
      claimed = rx.ready.peek().litToBoolean
      c.clock.step()
      left -= 1
    }
    rx.valid.poke(false.B)
    rx.bits.data.poke(0.U)
    assert(claimed, f"the hosted RDI state machine never took 0x$msg%x")
  }

  private def awaitRdiState(c: Mmpl, state: RDIState.Type): Unit = {
    var left = 256
    while (c.io.rdi.plStateSts.peek().litValue != state.litValue && left > 0) {
      c.clock.step()
      left -= 1
    }
    c.io.rdi.plStateSts.expect(state)
  }

  private def setLtsm(
      c: Mmpl,
      m: Int,
      lt: LTState.Type,
      ltsm: LTSMState.Type
  ) = {
    c.io.modules(m).status.ltState.poke(lt)
    c.io.modules(m).status.currentState.poke(ltsm)
    c.io.modules(m).rdiHost.foreach(_.ltsmState.poke(lt))
  }

  /** Every Module trained and the hosted RDI state machine in Active, its
    * bring-up handshake answered on Module 0 as the remote die would. Sideband
    * cfg activity is left asserted so the clock handshake the bring-up opens
    * never closes: with lp_clk_ack tied high here, a new one could not start,
    * and RDIController requires one around any sideband traffic it sends.
    */
  private def bringLinkUp(c: Mmpl, n: Int): Unit = {
    c.io.rdi.lpClkAck.poke(true.B)
    c.io.modules(0).rdiHost.get.cfgSidebandActive.poke(true.B)
    // The state machine only leaves Reset after it has seen a NOP there.
    c.clock.step()
    for (m <- 0 until n) setLtsm(c, m, LTState.sLINKINIT, LTSMState.sLINKINIT)
    // As an Adapter does on pl_inband_pres (spec 10.1.6 Step 2).
    c.io.rdi.lpStateReq.poke(RDIStateReq.active)
    c.clock.step()
    // Both halves of the Active Entry handshake: the remote die's request,
    // which this die answers, and the answer to this die's own.
    offerRemote(c, 0, rdiReqActive)
    offerRemote(c, 0, rdiRspActive)
    awaitRdiState(c, RDIState.active)
    for (m <- 0 until n) setLtsm(c, m, LTState.sACTIVE, LTSMState.sACTIVE)
    c.clock.step()
  }

  /** A PM request the remote die NAKs: Active to Active.PMNAK. */
  private def enterPmNak(c: Mmpl): Unit = {
    c.io.rdi.lpStateReq.poke(RDIStateReq.l1)
    offerRemote(c, 0, rdiRspPmNak)
    awaitRdiState(c, RDIState.activePmNak)
  }

  private def leavePmNak(c: Mmpl): Unit = {
    c.io.rdi.lpStateReq.poke(RDIStateReq.nop)
    awaitRdiState(c, RDIState.active)
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
  describe("MMPL transmit across Active.PMNAK") {
    it("Finishes a transmit word across Active.PMNAK") {
      /* Two of four Modules carry the Link, so each aggregate word takes two
         beats. The Adapter hands the word over on the first; the second must
         still go out after an Active.PMNAK rather than be dropped with the
         Adapter believing the word sent. */
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        for (m <- Seq(2, 3)) c.io.moduleConnected(m).poke(false.B)
        bringLinkUp(c, n)
        val random = new Random(randomSeed)
        val word = BigInt(aggRdi(n).nBytes * 8, random)
        def slice(m: Int, beat: Int) =
          expectedSlice(word, n, 2, m, beat)
            .U((params(n).bytesPerModule * 8).W)

        c.io.rdi.lpData.poke(word.U((aggRdi(n).nBytes * 8).W))
        c.io.rdi.lpValid.poke(true.B)
        c.io.rdi.lpIrdy.poke(true.B)
        c.io.rdi.plTrdy.expect(true.B, "the MMPL takes the word")
        for (m <- 0 until 2) {
          c.io.modules(m).rdi.lpValid.expect(true.B, s"beat 0 on Module $m")
          c.io.modules(m).rdi.lpData.expect(slice(m, 0))
        }
        c.clock.step()
        c.io.rdi.lpValid.poke(false.B)
        c.io.rdi.lpIrdy.poke(false.B)

        // The Modules stop accepting for the PM exchange, holding beat 1 back.
        for (m <- 0 until n) c.io.modules(m).rdi.plTrdy.poke(false.B)
        enterPmNak(c)
        leavePmNak(c)
        for (m <- 0 until n) c.io.modules(m).rdi.plTrdy.poke(true.B)
        for (m <- 0 until 2) {
          c.io
            .modules(m)
            .rdi
            .lpValid
            .expect(true.B, s"Module $m lost beat 1 of the word")
          c.io.modules(m).rdi.lpData.expect(slice(m, 1))
        }
      }
    }
  }

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

    it("Raises pl_error with the word the corrupted slice belongs to") {
      /* Table 10-1: pl_error reaches the Adapter "before or at the same time as
         the corrupted data", and once it has, "pl_valid should not be asserted
         (without pl_error assertion in the same cycle)" until retrain. Module 0
         runs two beats ahead of Module 1 and flags a Valid framing error on its
         third slice, then withholds pl_valid as a LogicalPhy does. The error
         has to go up with that word -- not beside an older, good one, with the
         corrupted word following it clean. */
      val n = 2
      val lead = 2
      val corrupted = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        val random = new Random(randomSeed)
        val words = Seq.fill(4)(BigInt(aggRdi(n).nBytes * 8, random))
        def slice(w: BigInt, m: Int) =
          expectedSlice(w, n, n, m, 0).U((params(n).bytesPerModule * 8).W)

        val seen = scala.collection.mutable.ArrayBuffer[(BigInt, Boolean)]()
        for (cycle <- 0 until words.length + lead + 2) {
          val m0Valid = cycle <= corrupted
          c.io.modules(0).rdi.plValid.poke(m0Valid.B)
          if (m0Valid) c.io.modules(0).rdi.plData.poke(slice(words(cycle), 0))
          c.io.modules(0).rdi.plError.poke((cycle >= corrupted).B)

          val m1 = cycle - lead
          val m1Valid = m1 >= 0 && m1 < words.length
          c.io.modules(1).rdi.plValid.poke(m1Valid.B)
          if (m1Valid) c.io.modules(1).rdi.plData.poke(slice(words(m1), 1))

          if (c.io.rdi.plValid.peek().litToBoolean) {
            seen += ((
              c.io.rdi.plData.peek().litValue,
              c.io.rdi.plError.peek().litToBoolean
            ))
          }
          c.clock.step()
        }
        c.io.modules(0).rdi.plValid.poke(false.B)
        c.io.modules(1).rdi.plValid.poke(false.B)

        assert(
          seen.map(_._1) == words.take(corrupted + 1),
          "the good words go up, then the corrupted one, then nothing"
        )
        assert(
          seen.map(_._2) == (Seq.fill(corrupted)(false) :+ true),
          s"pl_error rode with the wrong words: ${seen.map(_._2)}"
        )
        c.io.rdi.plError.expect(true.B, "it stands until the Link retrains")
      }
    }

    it("Stands in for a slice the Module squashed after a framing error") {
      /* A Module that detects the error on an earlier beat of a word raises
         pl_error and never delivers the word at all. Its siblings' slices of
         that word still arrive, and the word they make up must go up with the
         error rather than clean. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        val random = new Random(randomSeed)
        val words = Seq.fill(3)(BigInt(aggRdi(n).nBytes * 8, random))
        def slice(w: BigInt, m: Int) =
          expectedSlice(w, n, n, m, 0).U((params(n).bytesPerModule * 8).W)

        for (m <- 0 until n) {
          c.io.modules(m).rdi.plValid.poke(true.B)
          c.io.modules(m).rdi.plData.poke(slice(words(0), m))
        }
        c.io.rdi.plValid.expect(true.B)
        c.io.rdi.plError.expect(false.B, "word 0 is good")
        c.clock.step()

        c.io.modules(0).rdi.plValid.poke(false.B)
        c.io.modules(0).rdi.plError.poke(true.B)
        c.io.modules(1).rdi.plData.poke(slice(words(1), 1))
        c.io.rdi.plValid.expect(true.B, "word 1 still completes")
        c.io.rdi.plError.expect(true.B, "and carries the error")
        c.clock.step()

        c.io.modules(1).rdi.plData.poke(slice(words(2), 1))
        c.io.rdi.plValid.expect(false.B, "nothing after the error")
        c.io.rdi.plError.expect(true.B)
      }
    }

    it("Raises pl_error on the beat it lands in when a word takes two beats") {
      /* With M1 and M3 not connected (spec Figure 4-46) every word takes two
         beats. Module 0 flags a Valid framing error on the first beat of word
         1 and withholds pl_valid from then on, as a LogicalPhy does, so the
         second beat of that word never comes. Table 10-1 lets the Physical
         Layer squash the corrupted data as long as pl_error reaches the Adapter
         no later than it would have: the error goes up at once, on its own,
         and nothing follows it -- while Module 2 keeps delivering. */
      val n = 4
      val active = Seq(0, 2)
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        for (m <- 0 until n if !active.contains(m)) {
          c.io.moduleConnected(m).poke(false.B)
        }
        bringLinkUp(c, n)
        val random = new Random(randomSeed)
        val words = Seq.fill(2)(BigInt(aggRdi(n).nBytes * 8, random))
        def slice(w: BigInt, m: Int, beat: Int) =
          expectedSlice(w, n, active.length, active.indexOf(m), beat)
            .U((params(n).bytesPerModule * 8).W)
        def deliver(word: Int, beat: Int, error: Boolean): Unit = {
          for (m <- active) {
            c.io.modules(m).rdi.plValid.poke((!(error && m == 0)).B)
            c.io.modules(m).rdi.plData.poke(slice(words(word), m, beat))
          }
          c.io.modules(0).rdi.plError.poke(error.B)
        }

        deliver(0, 0, error = false)
        c.io.rdi.plValid.expect(false.B, "half of word 0 has arrived")
        c.clock.step()
        deliver(0, 1, error = false)
        c.io.rdi.plValid.expect(true.B, "word 0 is complete")
        c.io.rdi.plData.expect(words(0).U((aggRdi(n).nBytes * 8).W))
        c.io.rdi.plError.expect(false.B, "and good")
        c.clock.step()

        deliver(1, 0, error = true)
        c.io.rdi.plError
          .expect(true.B, "the error goes up with word 1's first beat")
        c.io.rdi.plValid.expect(false.B, "without the half word it corrupted")
        c.clock.step()

        c.io.modules(0).rdi.plValid.poke(false.B)
        c.io.modules(2).rdi.plValid.poke(true.B)
        for (beat <- 1 until 2 * params(n).rxAlignDepth + 2) {
          c.io.modules(2).rdi.plData.poke(slice(words(1), 2, beat % 2))
          c.io.rdi.plValid.expect(false.B, s"nothing after the error ($beat)")
          c.io.rdi.plError.expect(true.B, "it stands until the Link retrains")
          c.clock.step()
        }
      }
    }

    it("Keeps a receive slice waiting on a sibling across Active.PMNAK") {
      /* Spec 10.3: "Because Active.PMNAK is a sub-state of Active, all rules
         that apply for Active are also applicable for Active.PMNAK" -- the
         Modules keep delivering through it. A slice waiting on a skewed
         sibling must still be there when the sibling's arrives. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        val random = new Random(randomSeed)
        val word = BigInt(aggRdi(n).nBytes * 8, random)
        def slice(m: Int) =
          expectedSlice(word, n, n, m, 0).U((params(n).bytesPerModule * 8).W)

        c.io.modules(0).rdi.plValid.poke(true.B)
        c.io.modules(0).rdi.plData.poke(slice(0))
        c.clock.step()
        c.io.modules(0).rdi.plValid.poke(false.B)

        enterPmNak(c)
        // The sibling's slice comes in a few cycles into Active.PMNAK.
        c.clock.step(2)
        c.io.modules(1).rdi.plValid.poke(true.B)
        c.io.modules(1).rdi.plData.poke(slice(1))
        c.io.rdi.plValid.expect(true.B, "the word completes in Active.PMNAK")
        c.io.rdi.plData.expect(word.U((aggRdi(n).nBytes * 8).W))
        c.clock.step()
        c.io.modules(1).rdi.plValid.poke(false.B)
        leavePmNak(c)
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
      // pl_error is the exception: it travels with the receive data instead
      // (see "Raises pl_error with the word the corrupted slice belongs to").
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        for (
          (poke, read) <- Seq[(Int => Unit, () => Bool)](
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

    it(
      "Defers rather than rejects a message on a Module that loses the grant"
    ) {
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
          c.io.modules(m).rdiHost.get.internalLinkError.poke(true.B)
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
  // The hosted RDI state machine
  // ==========================================================================
  describe("MMPL hosted RDI state machine") {
    val reqActive = (0x01, 0x01)
    val rspActive = (0x02, 0x01)
    val reqLinkError = (0x01, 0x0a)
    val reqRetrain = (0x01, 0x0b)
    val rspRetrain = (0x02, 0x0b)
    val rdiReqRetrain = sbMsg(0x01, 0x0b)
    val rdiRspRetrain = sbMsg(0x02, 0x0b)
    val rdiRspLinkError = sbMsg(0x02, 0x0a)
    val rdiReqLinkError = sbMsg(0x01, 0x0a)
    val rdiReqLinkReset = sbMsg(0x01, 0x09)
    val rspLinkReset = (0x02, 0x09)
    val rspLinkError = (0x02, 0x0a)

    /** The {LinkMgmt.RDI.*} messages, as (MsgCode, MsgSubcode), the hosted
      * state machine puts on any Module's sideband over the next `cycles`, with
      * lp_stallack following pl_stallreq if `ackStall`, as an Adapter's would
      * once it has stopped at a Flit boundary.
      */
    def transmitted(
        c: Mmpl,
        n: Int,
        cycles: Int,
        ackStall: Boolean = false
    ): Seq[(Int, Int)] = {
      val sent = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
      for (_ <- 0 until cycles) {
        if (ackStall) c.io.rdi.lpStallAck.poke(c.io.rdi.plStallReq.peek())
        for (m <- 0 until n) {
          val tx = c.io.modules(m).rdiHost.get.sbLaneIo.tx
          if (tx.valid.peekBoolean() && tx.ready.peekBoolean()) {
            val d = tx.bits.data.peek().litValue
            sent += (((d >> 14) & 0xff).toInt -> ((d >> 32) & 0xff).toInt)
          }
        }
        c.clock.step()
      }
      sent.toSeq
    }

    /** The Adapter asks for Retrain; this die stalls it, asks the remote die,
      * and enters Retrain on the answer.
      */
    def retrainFromActive(c: Mmpl, n: Int): Unit = {
      c.io.rdi.lpStateReq.poke(RDIStateReq.retrain)
      val sent = transmitted(c, n, 64, ackStall = true)
      assert(sent == Seq(reqRetrain), s"one {Req.Retrain}: $sent")
      offerRemote(c, 1, rdiRspRetrain)
      awaitRdiState(c, RDIState.retrain)
      c.io.rdi.lpStallAck.poke(false.B)
    }

    it("Leaves Reset only when the Adapter asks, once the Link has trained") {
      /* Spec 10.3.3.1: "The pl_state_sts is not permitted to exit Reset state
         until requested by the upper layer", and the Active Entry handshake
         follows "Once Physical Layer has completed Link training" (10.1.6).
         The remote die asks while this die's Modules are still training. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        c.io.rdi.lpClkAck.poke(true.B)
        c.io.modules(0).rdiHost.get.cfgSidebandActive.poke(true.B)
        c.clock.step()
        offerRemote(c, 0, rdiReqActive)
        assert(transmitted(c, n, 64).isEmpty, "the Modules are still training")

        for (m <- 0 until n) {
          setLtsm(c, m, LTState.sLINKINIT, LTSMState.sLINKINIT)
        }
        assert(transmitted(c, n, 64).isEmpty, "the Adapter has not asked")
        c.io.rdi.plStateSts.expect(RDIState.reset)

        c.io.rdi.lpStateReq.poke(RDIStateReq.active)
        val sent = transmitted(c, n, 64)
        assert(
          sent.sorted == Seq(reqActive, rspActive).sorted,
          s"both halves of the Active Entry handshake go out: $sent"
        )
        c.io.rdi.plStateSts.expect(
          RDIState.reset,
          "sent {Rsp.Active} but not yet received one"
        )
        offerRemote(c, 0, rdiRspActive)
        awaitRdiState(c, RDIState.active)
      }
    }

    it("Leaves Retrain only once the Modules have retrained") {
      /* Spec 10.3.3.4: "Exit from Retrain on RDI requires the Active Entry
         handshakes to have completed between Physical Layers", begun "only
         after observing a NOP-> Active transition on lp_state_req"; 10.1.6:
         {Rsp.Active} "must only be sent after the Physical Layer has sampled
         lp_state_req = Active", and pl_state_sts goes to Active "once the
         Physical Layer has sent and received the {LinkMgmt.RDI.Rsp.Active}".
         The remote die asks early, while every Module is still retraining. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        retrainFromActive(c, n)
        for (m <- 0 until n) {
          setLtsm(c, m, LTState.sPHYRETRAIN, LTSMState.sPHYRETRAIN)
        }
        c.io.rdi.lpStateReq.poke(RDIStateReq.nop)
        c.clock.step(2)
        c.io.rdi.lpStateReq.poke(RDIStateReq.active)

        offerRemote(c, 1, rdiReqActive)
        assert(
          transmitted(c, n, 64).isEmpty,
          "nothing goes out while the Modules are still retraining"
        )
        c.io.rdi.plStateSts.expect(RDIState.retrain)

        for (m <- 0 until n) {
          setLtsm(c, m, LTState.sLINKINIT, LTSMState.sLINKINIT)
        }
        val sent = transmitted(c, n, 64)
        assert(
          sent.sorted == Seq(reqActive, rspActive).sorted,
          s"both halves of the Active Entry handshake go out: $sent"
        )
        c.io.rdi.plStateSts.expect(
          RDIState.retrain,
          "sent {Rsp.Active} but not yet received one"
        )
        offerRemote(c, 0, rdiRspActive)
        awaitRdiState(c, RDIState.active)
      }
    }

    it("Answers a remote Retrain only once the Adapter has stalled") {
      /* Spec 10.3.2: "the Stallreq/Ack mechanism must be used when exiting
         Active state to Retrain", and 4.5.3.7.1 Step 3 has the partner move
         to Retrain "after completion of stall Req/Ack handshake on its RDI"
         and answer only then. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        offerRemote(c, 1, rdiReqRetrain)
        assert(transmitted(c, n, 32).isEmpty, "not before lp_stallack")
        c.io.rdi.plStallReq.expect(true.B, "the Adapter is asked to stall")
        c.io.rdi.plStateSts.expect(RDIState.active)
        assert(
          transmitted(c, n, 32, ackStall = true) == Seq(rspRetrain),
          "{Rsp.Retrain} once stalled"
        )
        c.io.rdi.plStateSts.expect(RDIState.retrain)
      }
    }

    it("Keeps a spread transmit word whole across the stall") {
      /* With a Module gone each aggregate word takes two beats (Figure 4-46).
         4.5.3.7.1 Step 2: the request goes out "After completion of stall
         Req/Ack handshake and transmitting any pending data over mainband" --
         leaving Active part-way through the word used to cut it short. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        c.io.moduleConnected(1).poke(false.B)
        bringLinkUp(c, n)
        c.io.rdi.lpData.poke(BigInt(1).U)
        c.io.rdi.lpValid.poke(true.B)
        c.io.rdi.lpIrdy.poke(true.B)
        c.io.rdi.plTrdy.expect(true.B)
        // The Module takes beat 0 and then stops taking beats.
        c.clock.step()
        c.io.rdi.lpValid.poke(false.B)
        c.io.rdi.lpIrdy.poke(false.B)
        c.io.modules(0).rdi.plTrdy.poke(false.B)
        c.io.rdi.lpStateReq.poke(RDIStateReq.retrain)
        assert(
          transmitted(c, n, 32, ackStall = true).isEmpty,
          "not while beat 1 is still to go"
        )
        c.io.modules(0).rdi.plTrdy.poke(true.B)
        c.io.modules(0).rdi.lpValid.expect(true.B, "beat 1 goes out")
        assert(
          transmitted(c, n, 32, ackStall = true) == Seq(reqRetrain),
          "then {Req.Retrain}"
        )
      }
    }

    it("Retrains the whole Link on one Module's software retrain request") {
      /* Spec 3.5: a multi-module Link has a single RDI state machine, and
         10.3.3.2 takes Active to Retrain "due to an internal request to
         retrain the Link". The request goes through that RDI, stall and all,
         rather than taking one Module out of ACTIVE on its own. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        c.io.modules(1).rdiHost.get.swRetrainRequest.poke(true.B)
        assert(
          transmitted(c, n, 64, ackStall = true) == Seq(reqRetrain),
          "{Req.Retrain} after the stall"
        )
        offerRemote(c, 0, rdiRspRetrain)
        awaitRdiState(c, RDIState.retrain)
        for (m <- 0 until n) {
          c.io
            .modules(m)
            .rdiHost
            .get
            .plStateSts
            .expect(RDIState.retrain, s"Module $m")
        }
      }
    }

    it("Enters LinkError at once with no Module to carry the request") {
      /* Spec 10.3.3.7: "It is not required to complete the stallreq/ack
         handshake before entering this state", and a minimum residency is what
         forces the remote die there "for cases where the LinkError transition
         happened and sideband was not functional". Here training has given up
         with every Module in RESET, so there is no sideband at all -- and the
         {Req.LinkError} must not go out once the next training has one. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        c.io.rdi.lpClkAck.poke(true.B)
        for (m <- 0 until n) setLtsm(c, m, LTState.sRESET, LTSMState.sRESET)
        c.clock.step(2)
        c.io.modules(0).rdiHost.get.internalLinkError.poke(true.B)
        c.clock.step()
        c.io.rdi.plStateSts.expect(RDIState.linkError, "at once")
        c.io.modules(0).rdiHost.get.internalLinkError.poke(false.B)

        for (m <- 0 until n) setLtsm(c, m, LTState.sSBINIT, LTSMState.sSBINIT)
        c.clock.step(4)
        for (m <- 0 until n) {
          setLtsm(c, m, LTState.sMBINIT, LTSMState.sMBINIT_PARAM)
        }
        val sent = transmitted(c, n, 128)
        assert(sent.isEmpty, s"a stale request went out: $sent")
        c.io.rdi.plStateSts.expect(RDIState.linkError)
      }
    }

    it("Enters LinkError on lp_linkerror and stays while it is held") {
      /* Spec 10.3.3.7: "The lower layer enters LinkError state when directed
         by an lp_linkerror signal", Table 10-1: "Physical Layer must move to
         LinkError state and stay there as long as lp_linkerror=1". The remote
         die is told, once. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        c.io.rdi.lpLinkError.poke(true.B)
        c.clock.step()
        c.io.rdi.plStateSts.expect(RDIState.linkError, "no handshake first")
        assert(transmitted(c, n, 32) == Seq(reqLinkError), "{Req.LinkError}")
        offerRemote(c, 1, rdiRspLinkError)

        // The Adapter asks to leave, but lp_linkerror is still up.
        c.io.rdi.lpStateReq.poke(RDIStateReq.active)
        assert(transmitted(c, n, 2 * linkErrorResidency).isEmpty)
        c.io.rdi.plStateSts.expect(RDIState.linkError)
        for (m <- 0 until n) {
          c.io.modules(m).rdiHost.get.plStateSts.expect(RDIState.linkError)
        }
        c.io.rdi.plClkReq.expect(true.B, "no clock gating in LinkError")

        c.io.rdi.lpLinkError.poke(false.B)
        c.clock.step(2)
        c.io.rdi.plStateSts.expect(
          RDIState.reset,
          "LinkError is left for Reset"
        )
      }
    }

    it("Drains the Adapter when a stall is caught by LinkError") {
      /* Spec 10.3.3.7: "If the lower layer decides to perform a
         pl_stallreq/lp_stallack handshake, it must provide pl_trdy to the
         upper layer to drain the packets", which may be dropped. Without it
         the stall stayed up through LinkError, was acked once the Link came
         back, and then never released in Active. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        c.io.rdi.lpStateReq.poke(RDIStateReq.retrain)
        c.clock.step(4)
        c.io.rdi.plStallReq.expect(true.B, "the stall is up, not yet acked")
        c.io.rdi.lpLinkError.poke(true.B)
        c.clock.step()
        c.io.rdi.lpLinkError.poke(false.B)
        c.io.rdi.plStateSts.expect(RDIState.linkError)
        // A Module offers pl_trdy only in Active (LogicalPhy).
        for (m <- 0 until n) c.io.modules(m).rdi.plTrdy.poke(false.B)
        c.io.rdi.plTrdy.expect(true.B, "pl_trdy to drain against")
        c.io.rdi.lpValid.poke(true.B)
        c.io.rdi.lpIrdy.poke(true.B)
        c.io.modules(0).rdi.lpValid.expect(false.B, "and the data is dropped")
        c.clock.step()
        c.io.rdi.lpValid.poke(false.B)
        c.io.rdi.lpIrdy.poke(false.B)
        c.io.rdi.lpStallAck.poke(true.B)
        c.clock.step(2)
        c.io.rdi.plStallReq.expect(false.B, "released once acked")
        c.io.rdi.plTrdy.expect(false.B)
        c.io.rdi.lpStallAck.poke(false.B)
      }
    }

    it("Releases a stall that nothing needs any more, in Active too") {
      /* The Adapter asks for Retrain and then withdraws it before the stall
         completes. Once lp_stallack comes, nothing holds the stall: it goes
         down in Active rather than staying up with nothing flowing. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        c.io.rdi.lpStateReq.poke(RDIStateReq.retrain)
        c.clock.step(4)
        c.io.rdi.plStallReq.expect(true.B)
        c.io.rdi.lpStateReq.poke(RDIStateReq.active)
        c.io.rdi.lpStallAck.poke(true.B)
        c.clock.step(4)
        c.io.rdi.plStallReq.expect(false.B)
        c.io.rdi.plStateSts.expect(RDIState.active)
        c.io.rdi.lpStallAck.poke(false.B)
      }
    }

    it("Keeps a Retrain that a LinkReset got ahead of out of LinkReset") {
      /* Table 10-4: in LinkReset a Retrain request is "Ignore". The remote
         die's {Req.LinkReset} crosses this die's {Req.Retrain}; the late
         {Rsp.Retrain} must not take the RDI from LinkReset to Retrain -- the
         remote die stays in LinkReset, and the two would disagree. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        c.io.rdi.lpStateReq.poke(RDIStateReq.retrain)
        assert(
          transmitted(c, n, 64, ackStall = true) == Seq(reqRetrain),
          "{Req.Retrain} goes out"
        )
        offerRemote(c, 1, rdiReqLinkReset)
        assert(
          transmitted(c, n, 32, ackStall = true) == Seq(rspLinkReset),
          "{Rsp.LinkReset}"
        )
        c.io.rdi.plStateSts.expect(RDIState.linkReset)
        val rx = c.io.modules(0).rdiHost.get.sbLaneIo.rx
        rx.valid.poke(true.B)
        rx.bits.data.poke(rdiRspRetrain.U)
        c.clock.step(4)
        rx.valid.poke(false.B)
        c.clock.step(4)
        c.io.rdi.plStateSts.expect(RDIState.linkReset, "the Retrain is moot")
        c.io.rdi.lpStallAck.poke(false.B)
      }
    }

    it("Ignores a Retrain request in Reset") {
      // Table 10-4, Reset column: Retrain "Ignore".
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        c.io.rdi.lpClkAck.poke(true.B)
        c.clock.step(2)
        offerRemote(c, 0, rdiReqRetrain)
        assert(transmitted(c, n, 32).isEmpty, "no {Rsp.Retrain}")
        c.io.rdi.plStateSts.expect(RDIState.reset)
      }
    }

    it("Drops a LinkError answer it could not send before leaving LinkError") {
      /* Both dies go to LinkError together. The remote die's {Req.LinkError}
         arrives, but no Module can carry the answer; the RDI goes to LinkError
         on its own, and later leaves for Reset. The answer is moot then: sent
         in the next training, it took the RDI from Reset straight back to
         LinkError. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        for (m <- 0 until n) {
          c.io.modules(m).rdiHost.get.sbLaneIo.tx.ready.poke(false.B)
        }
        offerRemote(c, 1, rdiReqLinkError)
        c.io.rdi.lpLinkError.poke(true.B)
        c.clock.step()
        c.io.rdi.lpLinkError.poke(false.B)
        c.io.rdi.plStateSts.expect(RDIState.linkError)
        awaitRdiState(c, RDIState.reset)
        for (m <- 0 until n) {
          c.io.modules(m).rdiHost.get.sbLaneIo.tx.ready.poke(true.B)
        }
        val sent = transmitted(c, n, 64)
        assert(!sent.contains(rspLinkError), s"a stale answer went out: $sent")
        c.io.rdi.plStateSts.expect(RDIState.reset)
      }
    }

    it("Leaves LinkError for Reset only after the minimum residency") {
      /* Spec 10.3.3.7: "LinkError->Reset: ... (lp_state_req == Active and
         lp_linkerror = 0, while pl_state_sts == LinkError AND minimum
         residency requirements are met ...). Lower Layer must implement a
         minimum residency time in LinkError of 16 ms". It is local: no
         sideband message goes out for it. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        bringLinkUp(c, n)
        c.io.rdi.lpLinkError.poke(true.B)
        c.clock.step()
        c.io.rdi.lpLinkError.poke(false.B)
        c.io.rdi.plStateSts.expect(RDIState.linkError)
        offerRemote(c, 1, rdiRspLinkError)

        var cycles = 0
        while (
          c.io.rdi.plStateSts.peek().litValue ==
            RDIState.linkError.litValue && cycles < 4 * linkErrorResidency
        ) {
          c.clock.step()
          cycles += 1
        }
        c.io.rdi.plStateSts.expect(RDIState.reset)
        assert(
          cycles >= linkErrorResidency - 2 &&
            cycles <= linkErrorResidency + 2,
          s"left LinkError after $cycles cycles, not about $linkErrorResidency"
        )
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

    it(
      "Starts a packet on the next Module when the least one is in TRAINERROR"
    ) {
      /* A Module in TRAINERROR is on its way to RESET, and RESET resets its
         whole sideband channel: whatever it still holds is lost, and the
         lp_cfg credit with it. The remote die takes register traffic on any
         Module ID (spec 4.7.1.1), so the next Module up carries it instead. */
      val n = 4
      val chunksPerPacket = new SidebandParams().sbNodeMsgWidth / 32
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        c.io.modules(0).status.ltState.poke(LTState.sTRAINERROR)
        val packet = Seq.tabulate(chunksPerPacket)(i => BigInt(0xc0 + i))
        sendCfgPacket(c, packet)

        val seen = collectCfgTx(c, n, 16).filter(_.nonEmpty)
        assert(
          seen.map(_.head._1).distinct == Seq(1),
          s"expected the packet on Module 1, got ${seen.map(_.head._1)}"
        )
        assert(seen.map(_.head._2) == packet)
      }
    }

    it("Returns the credits of packets a Module takes down into RESET") {
      /* A Module returns an lp_cfg credit once the packet leaves its priority
         queue. One that drops to RESET first resets its sideband channel with
         the packet still inside, so that credit would never come back and the
         Adapter's pool would shrink for good. */
      val n = 2
      val chunksPerPacket = new SidebandParams().sbNodeMsgWidth / 32
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        // Opcode 0h, a register read: it consumes a credit.
        val packet = Seq.tabulate(chunksPerPacket)(i => BigInt(i << 8))
        sendCfgPacket(c, packet)
        val seen = collectCfgTx(c, n, 16).filter(_.nonEmpty)
        assert(seen.map(_.head._1).distinct == Seq(0))
        c.io.rdi.plCfgCrd.expect(false.B, "Module 0 still holds it")

        c.io.modules(0).status.ltState.poke(LTState.sRESET)
        var returned = 0
        for (_ <- 0 until 8) {
          if (c.io.rdi.plCfgCrd.peek().litToBoolean) returned += 1
          c.clock.step()
        }
        assert(returned == 1, s"expected one credit back, got $returned")
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
        for (m <- 0 until n) actOnResolution(c, m)
        c.clock.step()

        c.io.status.moduleEnable(0).expect(true.B)
        c.io.status.moduleEnable(1).expect(false.B, "Module 1 is disabled")
      }
    }

    it("Holds a PHYRETRAIN directive for Modules that reach LINKSPEED late") {
      /* Spec 4.5.3.4.12 Step 5: an {exit to phy retrain req} received on any
         Module takes every Module of the Link to PHYRETRAIN. The resolver
         answers that on one Module's report without waiting for the rest, so
         the directive has to outlive the Module that raised it -- spec 4.7.1.2
         allows the others to be a long way behind, and a Module only samples
         the directive once it reaches the substate that waits for one. */
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))

        // Only Module 2 has anything to say, and it is heading for PHYRETRAIN.
        val r = c.io.modules(2).status.linkSpeedReport
        r.valid.poke(true.B)
        r.bits.recvdPhyRetrain.poke(true.B)
        c.clock.step()

        for (m <- 0 until n) {
          c.io.modules(m).ctrl.resolution.valid.expect(true.B)
          c.io
            .modules(m)
            .ctrl
            .resolution
            .bits
            .expect(MmplResolution.phyRetrain, s"Module $m")
        }

        // Module 2 acts and leaves; the others are still working towards the
        // substate that samples a directive.
        actOnResolution(c, 2)
        c.clock.step()
        for (m <- Seq(0, 1, 3)) {
          c.io
            .modules(m)
            .ctrl
            .resolution
            .valid
            .expect(true.B, s"Module $m must still see the directive")
          c.io
            .modules(m)
            .ctrl
            .resolution
            .bits
            .expect(MmplResolution.phyRetrain, s"Module $m")
        }

        // Once the whole Link has left LINKSPEED the directive is retired, and
        // a PHY retrain drops no Module.
        for (m <- Seq(0, 1, 3)) actOnResolution(c, m)
        c.clock.step()
        c.io.modules(0).ctrl.resolution.valid.expect(false.B)
        for (m <- 0 until n) {
          c.io.status.moduleEnable(m).expect(true.B, s"Module $m stays in")
        }
      }
    }

    it(
      "Holds a PHYRETRAIN directive for a Module that has not reached LINKSPEED"
    ) {
      /* Spec 4.5.3.4.12 Step 5 makes a PHY retrain request received on any
         Module a directive for every Module of the Link, and spec 4.7.1.2 lets
         a sibling be a long way behind -- still in DATATRAINCENTER2, say. Being
         outside LINKSPEED does not mean it has acted: it has yet to reach the
         substate that samples a directive, so the directive must wait for it. */
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        c.io
          .modules(3)
          .status
          .currentState
          .poke(LTSMState.sMBTRAIN_DATATRAINCENTER2)

        val r = c.io.modules(2).status.linkSpeedReport
        r.valid.poke(true.B)
        r.bits.recvdPhyRetrain.poke(true.B)
        c.clock.step()
        c.io.modules(3).ctrl.resolution.valid.expect(true.B)
        c.io.modules(3).ctrl.resolution.bits.expect(MmplResolution.phyRetrain)

        // Everyone in LINKSPEED acts; the straggler has not even arrived.
        for (m <- Seq(0, 1, 2)) actOnResolution(c, m)
        c.clock.step()
        c.io
          .modules(3)
          .ctrl
          .resolution
          .valid
          .expect(true.B, "the directive must wait for the straggler")
        c.io.modules(3).ctrl.resolution.bits.expect(MmplResolution.phyRetrain)

        // It arrives in LINKSPEED, finds the directive waiting, and acts on it.
        c.io.modules(3).status.currentState.poke(LTSMState.sMBTRAIN_LINKSPEED)
        c.clock.step()
        c.io.modules(3).ctrl.resolution.valid.expect(true.B)
        actOnResolution(c, 3)
        c.clock.step()
        c.io.modules(3).ctrl.resolution.valid.expect(false.B, "retired")
        for (m <- 0 until n) {
          c.io.status.moduleEnable(m).expect(true.B, s"Module $m stays in")
        }
      }
    }

    it("Withdraws the directive from a Module once it has acted on it") {
      /* A Module that finishes its exchange walks the rest of MBTRAIN and can be
         back in LINKSPEED before a slow sibling has left it. It must not find
         last pass's answer still on offer there. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        for (m <- 0 until n) {
          val r = c.io.modules(m).status.linkSpeedReport
          r.valid.poke(true.B)
          r.bits.sentDone.poke(true.B)
          r.bits.recvdDone.poke(true.B)
        }
        c.clock.step()
        for (m <- 0 until n) {
          c.io.modules(m).ctrl.resolution.valid.expect(true.B, s"Module $m")
          c.io.modules(m).ctrl.resolution.bits.expect(MmplResolution.done)
        }

        // Module 0 acts and is back in LINKSPEED for the next pass before
        // Module 1 has finished exchanging its response.
        actOnResolution(c, 0)
        c.clock.step()
        c.io.modules(0).ctrl.resolution.valid.expect(false.B, "acted")
        c.io.modules(1).ctrl.resolution.valid.expect(true.B, "still exchanging")
        c.io.modules(0).status.currentState.poke(LTSMState.sMBTRAIN_LINKSPEED)
        c.clock.step()
        c.io
          .modules(0)
          .ctrl
          .resolution
          .valid
          .expect(false.B, "must not be offered last pass's answer")
        c.io.modules(1).ctrl.resolution.valid.expect(true.B)

        actOnResolution(c, 1)
        c.clock.step()
        c.io.modules(1).ctrl.resolution.valid.expect(false.B)
        c.io.status.resolutionApplied.expect(false.B)
      }
    }

    it(
      "Keeps pl_inband_pres up while a disabled Module passes through TRAINERROR"
    ) {
      /* A Module the resolution disables goes to TRAINERROR and RESET by design
         (spec 4.5.3.4.12 Step 5d). Until its siblings have finished their own
         exchanges it is still counted operational, and the hosted RDI state
         machine must not read its TRAINERROR as the Link going down: Table 10-1
         has pl_inband_pres stay high until the Link is down. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        // The hosted machine raised pl_inband_pres when the Modules reached
        // LINKINIT, and the Modules are now back in LINKSPEED. Sideband cfg
        // activity keeps the clock handshake open, which RDIController requires
        // of any pl_inband_pres it presents from Reset.
        for (m <- 0 until n) {
          c.io.modules(m).rdiHost.get.cfgSidebandActive.poke(true.B)
          c.io.modules(m).rdiHost.get.ltsmState.poke(LTState.sLINKINIT)
        }
        c.clock.step()
        c.io.rdi.plInbandPres.expect(true.B)
        for (m <- 0 until n) {
          c.io.modules(m).rdiHost.get.ltsmState.poke(LTState.sMBTRAIN)
        }
        c.clock.step()
        c.io.rdi.plInbandPres.expect(true.B)

        // Module 1 asked for a width degrade. Fewer than half reported, so it is
        // disabled and Module 0 goes on to LINKINIT.
        for (m <- 0 until n) {
          val r = c.io.modules(m).status.linkSpeedReport
          r.valid.poke(true.B)
          r.bits.sentDone.poke((m == 0).B)
          r.bits.sentRepair.poke((m == 1).B)
          r.bits.recvdDone.poke(true.B)
        }
        c.clock.step()
        c.io
          .modules(1)
          .ctrl
          .resolution
          .bits
          .expect(MmplResolution.disableModule)

        // Module 1 finishes its exchange first and drops to TRAINERROR while
        // Module 0 is still exchanging its {done resp}.
        clearReport(c, 1)
        c.io.modules(1).status.ltState.poke(LTState.sTRAINERROR)
        c.io.modules(1).status.currentState.poke(LTSMState.sTRAINERROR)
        c.io.modules(1).rdiHost.get.ltsmState.poke(LTState.sTRAINERROR)
        c.clock.step(4)
        c.io.status
          .moduleEnable(1)
          .expect(true.B, "still counted until Module 0 has acted")
        c.io.rdi.plInbandPres
          .expect(true.B, "a Module leaving by directive is not the Link down")

        // Module 0 acts; the operational set shrinks and Module 1 no longer
        // reaches the RDI at all.
        actOnResolution(c, 0)
        c.clock.step()
        c.io.status.moduleEnable(1).expect(false.B, "Module 1 is disabled")
        c.io.rdi.plInbandPres.expect(true.B)
      }
    }

    it(
      "Takes every Module to TRAINERROR when one reports a response mismatch"
    ) {
      /* Spec 4.5.3.4.12 Step 5d: "Any mismatch on received message vs. expected
         resolution must take all modules to TRAINERROR." A Module only sees its
         own exchange, so the escalation has to come from here -- otherwise the
         Module that caught it goes down alone while its siblings complete a
         handshake the remote Link partner has already contradicted, and the two
         die end up in different configurations. */
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))

        // A clean pass: every Module reports done, so the Link resolves `done`.
        for (m <- 0 until n) {
          val r = c.io.modules(m).status.linkSpeedReport
          r.valid.poke(true.B)
          r.bits.sentDone.poke(true.B)
          r.bits.recvdDone.poke(true.B)
        }
        c.clock.step()
        for (m <- 0 until n) {
          c.io.modules(m).ctrl.resolution.bits.expect(MmplResolution.done)
        }

        // Module 2's partner answers something other than {done resp}.
        c.io.modules(2).status.linkSpeedRespMismatch.poke(true.B)
        c.clock.step()

        for (m <- 0 until n) {
          c.io.modules(m).ctrl.resolution.valid.expect(true.B, s"Module $m")
          c.io
            .modules(m)
            .ctrl
            .resolution
            .bits
            .expect(
              MmplResolution.trainError,
              s"Module $m must follow the mismatch to TRAINERROR"
            )
        }
        c.io.status.linkResolution.expect(MmplResolution.trainError)

        // The operational set is kept: the Modules reach TRAINERROR and RESET on
        // their own, and dropping them here would silence pl_trainerror.
        for (m <- 0 until n) actOnResolution(c, m)
        c.clock.step()
        for (m <- 0 until n) {
          c.io.status.moduleEnable(m).expect(true.B, s"Module $m stays counted")
        }
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
        for (m <- 0 until n) actOnResolution(c, m)
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

    it("Disables a Module that falls out of training before reporting") {
      /* Spec 4.7.1: "if any module failed to train, the MMPL must ensure that
         the multi-module configuration degrades to the next permitted
         configuration". Module 0 drops to TRAINERROR on its own while the rest
         wait in LINKSPEED on its report. It is disabled with the other Module of
         its half (spec 5.7.3.4.1), and the rest go on to LINKINIT. */
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        for (m <- 1 until n) {
          val r = c.io.modules(m).status.linkSpeedReport
          r.valid.poke(true.B)
          r.bits.sentDone.poke(true.B)
          r.bits.recvdDone.poke(true.B)
        }
        c.clock.step()
        c.io.status.resolutionApplied.expect(false.B, "Module 0 is silent")

        setLtsm(c, 0, LTState.sTRAINERROR, LTSMState.sTRAINERROR)
        c.clock.step()
        c.io.modules(0).ctrl.moduleDisabled.expect(true.B, "held in RESET")
        c.clock.step()
        c.io.status.resolutionApplied.expect(true.B, "resolved without it")
        c.io
          .modules(1)
          .ctrl
          .resolution
          .bits
          .expect(MmplResolution.disableModule)
        for (m <- Seq(2, 3)) {
          c.io.modules(m).ctrl.resolution.bits.expect(MmplResolution.done)
        }

        for (m <- 1 until n) actOnResolution(c, m)
        c.clock.step()
        for (m <- 0 until n) {
          c.io.status.moduleEnable(m).expect((m >= 2).B, s"Module $m")
        }
      }
    }

    it("Drops a Module that times out without taking the RDI to LinkError") {
      /* Spec 4.7.1 again, for a Module whose training times out rather than
         failing a handshake -- in SBINIT, say, against a partner it never
         hears. Its LTSM raises the timeout, which is what the RDI state machine
         goes to LinkError on, before it reaches TRAINERROR, and its sibling is
         already waiting on it in LINKSPEED. The MMPL has to count it failed
         from that moment, or one Module's timeout takes the whole Link down. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        val r = c.io.modules(0).status.linkSpeedReport
        r.valid.poke(true.B)
        r.bits.sentDone.poke(true.B)
        r.bits.recvdDone.poke(true.B)
        setLtsm(c, 1, LTState.sSBINIT, LTSMState.sSBINIT)
        c.clock.step(4)
        c.io.status.resolutionApplied.expect(false.B, "Module 1 is training")

        c.io.modules(1).status.trainingTimedout.poke(true.B)
        c.io.modules(1).rdiHost.get.internalLinkError.poke(true.B)
        for (_ <- 0 until 16) {
          c.clock.step()
          c.io.rdi.plStateSts.expect(RDIState.reset, "no LinkError")
        }
        c.io.modules(1).ctrl.moduleDisabled.expect(true.B, "held in RESET")
        c.io.status.resolutionApplied.expect(true.B, "resolved without it")
        c.io.modules(0).ctrl.resolution.bits.expect(MmplResolution.done)
        c.io
          .modules(1)
          .ctrl
          .resolution
          .bits
          .expect(MmplResolution.disableModule)
      }
    }

    it("Keeps a dropped Module's pl_trainerror off the RDI") {
      /* Table 10-1 pl_trainerror "Indicates a fatal error from the Physical
         Layer" and "must transition pl_state_sts to LinkError". A Module the
         MMPL is degrading around is no such error, whatever its own LTSM
         reports on the way out. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        val r = c.io.modules(0).status.linkSpeedReport
        r.valid.poke(true.B)
        r.bits.sentDone.poke(true.B)
        r.bits.recvdDone.poke(true.B)
        setLtsm(c, 1, LTState.sSBINIT, LTSMState.sSBINIT)
        c.io.modules(1).status.trainingTimedout.poke(true.B)
        c.clock.step()
        c.io.modules(1).ctrl.moduleDisabled.expect(true.B, "Module 1 failed")
        c.io.modules(1).rdi.plTrainError.poke(true.B)
        c.io.rdi.plTrainError.expect(false.B, "not the Link's error")
        c.io.modules(0).rdi.plTrainError.poke(true.B)
        c.io.rdi.plTrainError.expect(true.B, "the rest of the Link's is")
      }
    }

    it("Keeps Modules that fall together in the Link, to retry or escalate") {
      /* Modules leave RESET together, so they time out together. None of them
         is still training for the others to be degraded around: the Link has
         failed, and must retry or, with no retries left, escalate (Table 10-1
         pl_trainerror: "Physical Layer must transition pl_state_sts to
         LinkError"). Counting a timed-out sibling as still training latched
         them all failed, which also stopped every one of them escalating. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        for (m <- 0 until n) {
          setLtsm(c, m, LTState.sSBINIT, LTSMState.sSBINIT)
          c.io.modules(m).status.trainingTimedout.poke(true.B)
        }
        c.clock.step(4)
        for (m <- 0 until n) {
          c.io.modules(m).ctrl.moduleDisabled.expect(false.B, s"Module $m")
          c.io.status.moduleEnable(m).expect(true.B, s"Module $m")
        }
      }
    }

    it(
      "Does not count a Module the directive is disabling as the one still training"
    ) {
      val n = 2
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
        c.io.status.resolutionApplied.expect(true.B)
        c.io
          .modules(1)
          .ctrl
          .resolution
          .bits
          .expect(MmplResolution.disableModule)
        // Module 0 times out in its exchange while Module 1 leaves.
        c.io.modules(0).status.trainingTimedout.poke(true.B)
        c.clock.step(2)
        c.io
          .modules(0)
          .ctrl
          .moduleDisabled
          .expect(false.B, "the Link's last Module, not one to degrade around")
      }
    }

    it("Hands a restored Module the Link's retry episode") {
      /* Module 0 failed while Module 1 still trained, so it was taken out;
         then Module 1 failed too. That is the Link failing, and Module 1 holds
         its retry episode. Restored, Module 0 takes that episode up instead of
         starting over with none: whichever Module fails last on the retry
         then retries or escalates for the Link. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        setLtsm(c, 0, LTState.sSBINIT, LTSMState.sSBINIT)
        setLtsm(c, 1, LTState.sMBINIT, LTSMState.sMBINIT_PARAM)
        c.io.modules(0).status.trainingTimedout.poke(true.B)
        c.clock.step()
        c.io.modules(0).ctrl.moduleDisabled.expect(true.B, "Module 0 failed")
        c.io.modules(0).status.trainingTimedout.poke(false.B)
        setLtsm(c, 0, LTState.sRESET, LTSMState.sRESET)
        setLtsm(c, 1, LTState.sTRAINERROR, LTSMState.sTRAINERROR)
        c.clock.step(2)
        c.io.modules(1).ctrl.moduleDisabled.expect(false.B, "the last to fail")

        val ep = c.io.modules(1).status.trainingEpisode
        ep.active.poke(true.B)
        ep.local.poke(true.B)
        ep.retries.poke(1.U)
        ep.retryMax.poke(2.U)
        setLtsm(c, 1, LTState.sRESET, LTSMState.sRESET)
        var restarted = false
        for (_ <- 0 until 8) {
          if (c.io.modules(0).ctrl.restart.peekBoolean()) {
            restarted = true
            val got = c.io.modules(0).ctrl.restartEpisode
            got.active.expect(true.B)
            got.local.expect(true.B)
            got.retries.expect(1.U)
            got.retryMax.expect(2.U)
          }
          c.clock.step()
        }
        assert(restarted, "Module 0 was restored")
        c.io.modules(0).ctrl.moduleDisabled.expect(false.B)
      }
    }

    it("Still takes the RDI to LinkError when every Module escalates") {
      // Every Module falling together is the Link going down.
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        for (m <- 0 until n) {
          setLtsm(c, m, LTState.sSBINIT, LTSMState.sSBINIT)
          c.io.modules(m).status.trainingTimedout.poke(true.B)
          c.io.modules(m).rdiHost.get.internalLinkError.poke(true.B)
        }
        c.clock.step(2)
        c.io.rdi.plStateSts.expect(RDIState.linkError)
      }
    }

    it("Retires a PHYRETRAIN directive while an early Module reports again") {
      /* The PHY retrain directive is held for a straggler, and a Module that
         already acted can be back in LINKSPEED with its next pass's report
         meanwhile. That report is not owed this directive: waiting on it left
         the directive unable to retire, and every Module timed out. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        c.io
          .modules(1)
          .status
          .currentState
          .poke(LTSMState.sMBTRAIN_DATATRAINCENTER2)
        val r0 = c.io.modules(0).status.linkSpeedReport
        r0.valid.poke(true.B)
        r0.bits.recvdPhyRetrain.poke(true.B)
        c.clock.step()
        c.io.modules(0).ctrl.resolution.bits.expect(MmplResolution.phyRetrain)

        // Module 0 acts, goes round, and is back with a clean report before
        // Module 1 has reached LINKSPEED at all.
        actOnResolution(c, 0)
        c.clock.step()
        c.io.modules(0).status.currentState.poke(LTSMState.sMBTRAIN_LINKSPEED)
        r0.valid.poke(true.B)
        r0.bits.sentDone.poke(true.B)
        r0.bits.recvdDone.poke(true.B)
        c.clock.step()
        c.io.modules(0).ctrl.resolution.valid.expect(false.B, "already acted")

        c.io.modules(1).status.currentState.poke(LTSMState.sMBTRAIN_LINKSPEED)
        c.clock.step()
        c.io.modules(1).ctrl.resolution.valid.expect(true.B)
        c.io.modules(1).ctrl.resolution.bits.expect(MmplResolution.phyRetrain)
        actOnResolution(c, 1)
        c.clock.step()
        c.io.status.resolutionApplied.expect(false.B, "the directive retired")
        c.clock.step()
        c.io.status.resolutionApplied.expect(
          false.B,
          "and waits for Module 1's next report before resolving again"
        )
      }
    }

    it(
      "Restarts a restored Module and takes it out of RESET with its siblings"
    ) {
      /* A Module the Link dropped carries stale training state -- an episode,
         a retry count, trigger levels -- and relaunched on its own once
         restored. It is restarted when the whole Link is back in RESET, and
         then follows its siblings out of RESET. */
      val n = 2
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1))
        setLtsm(c, 1, LTState.sTRAINERROR, LTSMState.sTRAINERROR)
        c.clock.step()
        c.io.modules(1).ctrl.moduleDisabled.expect(true.B, "failed")

        for (m <- 0 until n) setLtsm(c, m, LTState.sRESET, LTSMState.sRESET)
        c.io.modules(1).ctrl.restart.expect(true.B, "taken back")
        c.io.modules(0).ctrl.restart.expect(false.B, "never left")
        c.clock.step()
        c.io.modules(1).ctrl.restart.expect(false.B, "one pulse")
        c.io.modules(1).ctrl.moduleDisabled.expect(false.B)

        c.io.modules(1).ctrl.joinResetExit.expect(false.B)
        setLtsm(c, 0, LTState.sSBINIT, LTSMState.sSBINIT)
        c.io.modules(1).ctrl.joinResetExit.expect(true.B, "follows Module 0")
        c.io.modules(0).ctrl.joinResetExit.expect(false.B)
      }
    }

    it("Runs the single-module handshake when only one Module is connected") {
      /* A die whose only connected Module faces a single-module die -- Table
         5-28's x1 pairings, or a UCIe-S x8 port on Module 0 (spec 5.7.3.3) --
         must run the single-module MBTRAIN.LINKSPEED flow its partner runs. */
      val n = 4
      simulate(dut(n)) { c =>
        initLink(c, n, Seq(0, 1, 2, 3))
        for (m <- 1 until n) c.io.moduleConnected(m).poke(false.B)
        for (m <- 0 until n) c.io.modules(m).ctrl.multiModule.expect(false.B)
        c.io.moduleConnected(2).poke(true.B)
        for (m <- 0 until n) c.io.modules(m).ctrl.multiModule.expect(true.B)
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

        // Module 0 is out again, but its siblings have yet to take their turn
        // -- a PHY retrain from LINKSPEED brings them in one by one -- and the
        // first of them must find the same REPAIR waiting, not what Module 0
        // leaving did to the aggregate.
        c.io.modules(0).status.ltState.poke(LTState.sMBTRAIN)
        c.clock.step()
        c.io.modules(1).status.ltState.poke(LTState.sPHYRETRAIN)
        c.clock.step()
        for (m <- 0 until n) {
          assert(
            encoding(c, m) == RetrainEncoding.REPAIR.litValue,
            s"module $m saw the encoding change between two Modules' turns"
          )
        }
        for (m <- Seq(2, 3)) {
          c.io.modules(m).status.ltState.poke(LTState.sPHYRETRAIN)
        }
        c.clock.step()

        // Once every Module has been through, the aggregate tracks again.
        for (m <- 1 until n) {
          c.io.modules(m).status.ltState.poke(LTState.sMBTRAIN)
        }
        c.clock.step(2)
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
