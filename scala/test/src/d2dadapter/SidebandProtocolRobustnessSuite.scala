package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

/**
  * Robustness tests for sideband message handling around link initialization.
  *
  * Focus:
  * - illegal ordering
  * - duplicate or stale messages
  * - repeated ready/receive strobes
  *
  * Scope:
  * - top-level observable behavior on LinkManagementController IO only
  * - sideband send checks are based on accepted-send semantics
  *   (`sb_snd == msg && sb_rdy`) and message episodes, since `sb_snd` can be
  *   level-held by RTL until acceptance
  * - no full protocol-side model
  */
class SidebandProtocolRobustnessSuite extends AnyFlatSpec with ChiselScalatestTester {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams

  private def initDut(dut: LinkManagementController): Unit = {
    dut.io.fdi_lp_state_req.poke(PhyStateReq.nop)
    dut.io.fdi_lp_linkerror.poke(false.B)
    dut.io.fdi_lp_rx_active_sts.poke(false.B)

    dut.io.rdi_pl_state_sts.poke(PhyState.reset)
    dut.io.rdi_pl_inband_pres.poke(false.B)

    dut.io.sb_rcv.poke(SideBandMessage.NOP)
    dut.io.sb_rdy.poke(false.B)

    dut.io.linkmgmt_stalldone.poke(false.B)
    dut.io.cycles_1us.poke(100.U)
    dut.io.parity_tx_sw_en.poke(false.B)
    dut.io.parity_rx_sw_en.poke(false.B)

    dut.clock.step(2)
  }

  private def waitUntil(
    dut: LinkManagementController,
    maxCycles: Int,
    reason: String
  )(cond: => Boolean): Unit = {
    var waited = 0
    while (waited < maxCycles && !cond) {
      dut.clock.step(1)
      waited += 1
    }
    assert(
      cond,
      s"Timeout waiting for: $reason after $maxCycles cycles. " +
        snapshot(dut)
    ) // UNKNOWN: needs spec/RTL audit
  }

  private def snapshot(dut: LinkManagementController): String =
    s"state=${dut.io.fdi_pl_state_sts.peek().litValue} " +
      s"rdi_lp_state_req=${dut.io.rdi_lp_state_req.peek().litValue} " +
      s"sb_snd=0x${dut.io.sb_snd.peek().litValue.toString(16)} " +
      s"fdi_pl_inband_pres=${dut.io.fdi_pl_inband_pres.peek().litToBoolean} " +
      s"fdi_pl_rx_active_req=${dut.io.fdi_pl_rx_active_req.peek().litToBoolean}"

  /**
    * One-cycle pulses are valid for this RTL because both sb_rdy and sb_rcv are
    * sampled as levels each cycle and latched into sticky progress flags.
    */
  private def pulseSbReady(dut: LinkManagementController, highCycles: Int = 1): Unit = {
    require(highCycles > 0, s"highCycles must be > 0, got $highCycles")
    dut.io.sb_rdy.poke(true.B)
    dut.clock.step(highCycles)
    dut.io.sb_rdy.poke(false.B)
  }

  private def pulseSbReceive(dut: LinkManagementController, msg: UInt, highCycles: Int = 1): Unit = {
    require(highCycles > 0, s"highCycles must be > 0, got $highCycles")
    dut.io.sb_rcv.poke(msg)
    dut.clock.step(highCycles)
    dut.io.sb_rcv.poke(SideBandMessage.NOP)
  }

  private def reachParamExchange(dut: LinkManagementController): Unit = {
    dut.io.rdi_pl_inband_pres.poke(true.B)
    waitUntil(dut, maxCycles = 40, reason = "RDI bring-up request") {
      dut.io.rdi_lp_state_req.peek().litValue == PhyStateReq.active.litValue
    }

    dut.io.rdi_pl_state_sts.poke(PhyState.active)
    waitUntil(dut, maxCycles = 40, reason = "ADV_CAP emitted in PARAM_EXCH") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.ADV_CAP.litValue
    }
    dut.io.fdi_pl_state_sts.expect(PhyState.reset)
  }

  private def completeParamExchangeToFdiBringup(dut: LinkManagementController): Unit = {
    waitUntil(dut, maxCycles = 40, reason = "ADV_CAP available for completion") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.ADV_CAP.litValue
    }
    pulseSbReceive(dut, SideBandMessage.ADV_CAP)
    pulseSbReady(dut)

    waitUntil(dut, maxCycles = 40, reason = "FDI inband presence in FDI bring-up") {
      dut.io.fdi_pl_inband_pres.peek().litToBoolean
    }
    dut.io.fdi_pl_state_sts.expect(PhyState.reset)
  }

  private def driveToActive(dut: LinkManagementController): Unit = {
    reachParamExchange(dut)
    completeParamExchangeToFdiBringup(dut)

    dut.io.fdi_lp_state_req.poke(PhyStateReq.nop)
    dut.clock.step(1)
    dut.io.fdi_lp_state_req.poke(PhyStateReq.active)
    dut.clock.step(1)

    waitUntil(dut, maxCycles = 40, reason = "REQ_ACTIVE sideband send") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.REQ_ACTIVE.litValue
    }
    pulseSbReady(dut)
    pulseSbReceive(dut, SideBandMessage.REQ_ACTIVE)

    waitUntil(dut, maxCycles = 40, reason = "fdi_pl_rx_active_req asserted") {
      dut.io.fdi_pl_rx_active_req.peek().litToBoolean
    }

    dut.io.fdi_lp_rx_active_sts.poke(true.B)
    waitUntil(dut, maxCycles = 40, reason = "RSP_ACTIVE sideband send") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.RSP_ACTIVE.litValue
    }
    pulseSbReady(dut)
    pulseSbReceive(dut, SideBandMessage.RSP_ACTIVE)

    waitUntil(dut, maxCycles = 40, reason = "top-level ACTIVE state") {
      dut.io.fdi_pl_state_sts.peek().litValue == PhyState.active.litValue
    }
  }

  behavior of "SidebandProtocolRobustnessSuite"

  it should "RSP_ACTIVE before REQ_ACTIVE: ignore early response and stay non-ACTIVE" in {
    test(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      reachParamExchange(dut)

      // Illegal early RSP_ACTIVE before ADV_CAP completion / REQ_ACTIVE phase.
      pulseSbReceive(dut, SideBandMessage.RSP_ACTIVE)
      pulseSbReceive(dut, SideBandMessage.RSP_ACTIVE)

      for (_ <- 0 until 12) {
        dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
        dut.io.fdi_pl_rx_active_req.expect(false.B) // SPEC-DERIVED
        dut.io.fdi_pl_inband_pres.expect(false.B) // SPEC-DERIVED
        dut.io.sb_snd.expect(SideBandMessage.ADV_CAP) // RTL-DERIVED
        dut.clock.step(1)
      }
    }
  }

  it should "duplicate REQ_ACTIVE: not advance twice and remain stable once ACTIVE" in {
    test(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      reachParamExchange(dut)
      completeParamExchangeToFdiBringup(dut)

      dut.io.fdi_lp_state_req.poke(PhyStateReq.nop)
      dut.clock.step(1)
      dut.io.fdi_lp_state_req.poke(PhyStateReq.active)
      dut.clock.step(1)

      waitUntil(dut, maxCycles = 40, reason = "REQ_ACTIVE sideband send") {
        dut.io.sb_snd.peek().litValue == SideBandMessage.REQ_ACTIVE.litValue
      }
      pulseSbReady(dut)

      // Duplicate REQ_ACTIVE receive.
      pulseSbReceive(dut, SideBandMessage.REQ_ACTIVE)
      pulseSbReceive(dut, SideBandMessage.REQ_ACTIVE)

      waitUntil(dut, maxCycles = 40, reason = "fdi_pl_rx_active_req asserted") {
        dut.io.fdi_pl_rx_active_req.peek().litToBoolean
      }
      dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
      dut.io.fdi_pl_inband_pres.expect(true.B) // SPEC-DERIVED
      for (_ <- 0 until 4) {
        // Duplicate REQ_ACTIVE receive should not retrigger local REQ_ACTIVE TX
        // after the initial request has already been accepted.
        val sb = dut.io.sb_snd.peek().litValue
        assert(
          sb != SideBandMessage.REQ_ACTIVE.litValue,
          s"Unexpected repeated REQ_ACTIVE transmit episode after duplicate receive. ${snapshot(dut)}"
        ) // UNKNOWN: needs spec/RTL audit
        dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
        dut.clock.step(1)
      }

      dut.io.fdi_lp_rx_active_sts.poke(true.B)
      waitUntil(dut, maxCycles = 40, reason = "RSP_ACTIVE sideband send") {
        dut.io.sb_snd.peek().litValue == SideBandMessage.RSP_ACTIVE.litValue
      }
      pulseSbReady(dut)
      pulseSbReceive(dut, SideBandMessage.RSP_ACTIVE)
      waitUntil(dut, maxCycles = 40, reason = "ACTIVE reached once") {
        dut.io.fdi_pl_state_sts.peek().litValue == PhyState.active.litValue
      }

      // Another duplicate REQ_ACTIVE while ACTIVE should not regress state.
      pulseSbReceive(dut, SideBandMessage.REQ_ACTIVE)
      for (_ <- 0 until 8) {
        dut.io.fdi_pl_state_sts.expect(PhyState.active) // SPEC-DERIVED
        dut.io.sb_snd.expect(SideBandMessage.NOP) // RTL-DERIVED
        dut.clock.step(1)
      }
    }
  }

  it should "stale ADV_CAP after ACTIVE: no state regression and no unexpected sb_snd" in {
    test(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)
      dut.io.fdi_pl_state_sts.expect(PhyState.active) // SPEC-DERIVED

      pulseSbReceive(dut, SideBandMessage.ADV_CAP)
      pulseSbReceive(dut, SideBandMessage.ADV_CAP)

      for (_ <- 0 until 12) {
        dut.io.fdi_pl_state_sts.expect(PhyState.active) // SPEC-DERIVED
        dut.io.fdi_pl_rx_active_req.expect(true.B) // SPEC-DERIVED
        dut.io.fdi_pl_inband_pres.expect(true.B) // SPEC-DERIVED
        dut.io.sb_snd.expect(SideBandMessage.NOP) // RTL-DERIVED
        dut.clock.step(1)
      }
    }
  }

  it should "repeated ready pulses: TX handshake completes once for ADV_CAP" in {
    test(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      reachParamExchange(dut)

      // Remote ADV_CAP present; local ADV_CAP send should complete once even if
      // sb_rdy toggles repeatedly.
      pulseSbReceive(dut, SideBandMessage.ADV_CAP)

      var advCapSendEpisodes = 0
      var advCapAcceptedSends = 0
      var prevAdvCapSending = false
      var prevFdiInbandPres = dut.io.fdi_pl_inband_pres.peek().litToBoolean
      var fdiInbandPresRises = 0

      for (cycle <- 0 until 12) {
        val readyPulse = (cycle % 2) == 0
        dut.io.sb_rdy.poke(readyPulse.B)

        val sb = dut.io.sb_snd.peek().litValue
        val sendingAdvCap = sb == SideBandMessage.ADV_CAP.litValue
        if (sendingAdvCap && !prevAdvCapSending) advCapSendEpisodes += 1
        if (sendingAdvCap && readyPulse) advCapAcceptedSends += 1

        val fdiInbandPres = dut.io.fdi_pl_inband_pres.peek().litToBoolean
        if (fdiInbandPres && !prevFdiInbandPres) fdiInbandPresRises += 1
        prevFdiInbandPres = fdiInbandPres

        dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
        dut.clock.step(1)
        prevAdvCapSending = sendingAdvCap
      }
      dut.io.sb_rdy.poke(false.B)

      assert(
        advCapSendEpisodes == 1,
        s"ADV_CAP transmit episode observed $advCapSendEpisodes times (expected exactly 1). ${snapshot(dut)}"
      ) // UNKNOWN: needs spec/RTL audit
      assert(
        advCapAcceptedSends == 1,
        s"ADV_CAP accepted-send observed $advCapAcceptedSends times (expected exactly 1). ${snapshot(dut)}"
      ) // UNKNOWN: needs spec/RTL audit
      assert(
        fdiInbandPresRises == 1,
        s"FDI bring-up progression signature (fdi_pl_inband_pres rising) observed $fdiInbandPresRises times (expected exactly 1). ${snapshot(dut)}"
      ) // UNKNOWN: needs spec/RTL audit

      // Should have moved to FDI bring-up once, while still top-level RESET.
      dut.io.fdi_pl_inband_pres.expect(true.B) // SPEC-DERIVED
      dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
      dut.io.fdi_pl_rx_active_req.expect(false.B) // SPEC-DERIVED
      for (_ <- 0 until 6) {
        dut.io.sb_rdy.poke(true.B)
        dut.io.sb_snd.expect(SideBandMessage.NOP) // RTL-DERIVED
        dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
        dut.clock.step(1)
      }
      dut.io.sb_rdy.poke(false.B)
    }
  }

  it should "multi-cycle receive message: consume REQ_ACTIVE effect once" in {
    test(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      reachParamExchange(dut)
      completeParamExchangeToFdiBringup(dut)

      // Prepare responder side: able to send a response once REQ_ACTIVE is seen.
      dut.io.fdi_lp_rx_active_sts.poke(true.B)
      dut.io.sb_rcv.poke(SideBandMessage.REQ_ACTIVE)

      var rspSendEpisodes = 0
      var rspAcceptedSends = 0
      var prevRspSending = false
      var prevRxActiveReq = dut.io.fdi_pl_rx_active_req.peek().litToBoolean
      var rxActiveReqRises = 0

      for (cycle <- 0 until 12) {
        // Hold REQ_ACTIVE high across multiple cycles; provide multiple ready pulses.
        val readyPulse = cycle == 3 || cycle == 6 || cycle == 9
        dut.io.sb_rdy.poke(readyPulse.B)

        val sb = dut.io.sb_snd.peek().litValue
        val sendingRsp = sb == SideBandMessage.RSP_ACTIVE.litValue
        if (sendingRsp && !prevRspSending) rspSendEpisodes += 1
        if (sendingRsp && readyPulse) rspAcceptedSends += 1

        val rxActiveReq = dut.io.fdi_pl_rx_active_req.peek().litToBoolean
        if (rxActiveReq && !prevRxActiveReq) rxActiveReqRises += 1
        prevRxActiveReq = rxActiveReq

        dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
        dut.io.fdi_pl_inband_pres.expect(true.B) // SPEC-DERIVED
        dut.clock.step(1)
        prevRspSending = sendingRsp
      }
      dut.io.sb_rcv.poke(SideBandMessage.NOP)
      dut.io.sb_rdy.poke(false.B)

      assert(
        rspSendEpisodes == 1,
        s"RSP_ACTIVE transmit episode observed $rspSendEpisodes times with held REQ_ACTIVE (expected exactly 1). ${snapshot(dut)}"
      ) // UNKNOWN: needs spec/RTL audit
      assert(
        rspAcceptedSends == 1,
        s"RSP_ACTIVE accepted-send observed $rspAcceptedSends times with held REQ_ACTIVE (expected exactly 1). ${snapshot(dut)}"
      ) // UNKNOWN: needs spec/RTL audit
      assert(
        rxActiveReqRises == 1,
        s"fdi_pl_rx_active_req rising-edge observed $rxActiveReqRises times with held REQ_ACTIVE (expected exactly 1). ${snapshot(dut)}"
      ) // UNKNOWN: needs spec/RTL audit

      // Should still be RESET until legal RSP_ACTIVE receive is provided.
      dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
      dut.io.fdi_pl_rx_active_req.expect(true.B) // SPEC-DERIVED
      dut.io.fdi_pl_inband_pres.expect(true.B) // SPEC-DERIVED
      for (_ <- 0 until 6) {
        dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
        dut.io.sb_snd.expect(SideBandMessage.NOP) // RTL-DERIVED
        dut.clock.step(1)
      }
    }
  }
}
