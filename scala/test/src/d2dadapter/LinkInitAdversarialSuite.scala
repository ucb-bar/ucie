package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

/**
  * Adversarial initialization tests for LinkManagementController link-init FSM.
  *
  * Focus:
  * - illegal/out-of-order sideband inputs
  * - duplicate control messages
  * - interrupted init + restart behavior
  *
  * Note:
  * - Tests observe top-level controller IO only (no direct access to internal
  *   LinkInitSubmodule state), so assertions are phrased as externally visible
  *   safety/progress properties.
  * - For interruption behavior, this suite intentionally checks observable
  *   safety (no premature ACTIVE / no illegal sideband advance) rather than
  *   claiming direct proof of internal sub-FSM state transitions.
  */
class LinkInitAdversarialSuite extends AnyFlatSpec with ChiselScalatestTester {
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
    * One-cycle pulse is sufficient for this RTL because:
    * - sb_rdy is sampled level-wise each cycle while the corresponding sb_snd is driven.
    * - sb_rcv is sampled level-wise and latched into sticky progress flags.
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

  private def assertHeldInResetSafety(
    dut: LinkManagementController,
    cycle: Int,
    expectedSbSnd: UInt,
    expectedRdiLpStateReq: PhyStateReq.Type = PhyStateReq.active,
    phase: String
  ): Unit = {
    val fdiState = dut.io.fdi_pl_state_sts.peek().litValue
    val rdiReq = dut.io.rdi_lp_state_req.peek().litValue
    val sbSnd = dut.io.sb_snd.peek().litValue
    val fdiInbandPres = dut.io.fdi_pl_inband_pres.peek().litToBoolean
    val fdiRxActiveReq = dut.io.fdi_pl_rx_active_req.peek().litToBoolean

    assert(
      fdiState == PhyState.reset.litValue,
      s"[$phase] cycle=$cycle expected RESET, got state=$fdiState. ${snapshot(dut)}"
    ) // SPEC-DERIVED
    assert(
      rdiReq == expectedRdiLpStateReq.litValue,
      s"[$phase] cycle=$cycle expected rdi_lp_state_req=${expectedRdiLpStateReq.litValue}, got $rdiReq. ${snapshot(dut)}"
    ) // UNKNOWN: needs spec/RTL audit
    assert(
      sbSnd == expectedSbSnd.litValue,
      s"[$phase] cycle=$cycle expected sb_snd=0x${expectedSbSnd.litValue.toString(16)}, got 0x${sbSnd.toString(16)}. ${snapshot(dut)}"
    ) // RTL-DERIVED
    assert(
      !fdiInbandPres,
      s"[$phase] cycle=$cycle expected fdi_pl_inband_pres=false before FDI bring-up, observed true. ${snapshot(dut)}"
    ) // SPEC-DERIVED
    assert(
      !fdiRxActiveReq,
      s"[$phase] cycle=$cycle expected fdi_pl_rx_active_req=false before active-handshake phase, observed true. ${snapshot(dut)}"
    ) // SPEC-DERIVED
    assert(
      sbSnd != SideBandMessage.REQ_ACTIVE.litValue && sbSnd != SideBandMessage.RSP_ACTIVE.litValue,
      s"[$phase] cycle=$cycle observed illegal active-handshake sideband emission 0x${sbSnd.toString(16)}. ${snapshot(dut)}"
    ) // SPEC-DERIVED
  }

  private def reachParamExchange(dut: LinkManagementController): Unit = {
    // INIT_START -> RDI_BRINGUP
    dut.io.rdi_pl_inband_pres.poke(true.B)
    waitUntil(dut, maxCycles = 40, reason = "RDI bring-up request") {
      dut.io.rdi_lp_state_req.peek().litValue == PhyStateReq.active.litValue
    }

    // RDI_BRINGUP -> PARAM_EXCH
    dut.io.rdi_pl_state_sts.poke(PhyState.active)
    waitUntil(dut, maxCycles = 40, reason = "ADV_CAP emitted in PARAM_EXCH") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.ADV_CAP.litValue
    }
    dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
  }

  private def completeToActive(dut: LinkManagementController): Unit = {
    // Complete PARAM_EXCH.
    waitUntil(dut, maxCycles = 40, reason = "ADV_CAP present before completion") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.ADV_CAP.litValue
    }
    pulseSbReceive(dut, SideBandMessage.ADV_CAP)
    pulseSbReady(dut)

    // Move through FDI bring-up.
    completeFdiBringupToActive(dut)
  }

  private def completeFdiBringupToActive(dut: LinkManagementController): Unit = {
    waitUntil(dut, maxCycles = 40, reason = "FDI inband presence asserted") {
      dut.io.fdi_pl_inband_pres.peek().litToBoolean
    }
    dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED

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

    pulseSbReceive(dut, SideBandMessage.RSP_ACTIVE)
    dut.io.fdi_lp_rx_active_sts.poke(true.B)

    waitUntil(dut, maxCycles = 40, reason = "RSP_ACTIVE sideband send") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.RSP_ACTIVE.litValue
    }
    pulseSbReady(dut)

    waitUntil(dut, maxCycles = 40, reason = "top-level ACTIVE state") {
      dut.io.fdi_pl_state_sts.peek().litValue == PhyState.active.litValue
    }
  }

  private def interruptInitByDroppingInband(dut: LinkManagementController): Unit = {
    // Minimal interruption model for this top-level RTL:
    // - withdraw partner inband presence and report partner reset state
    // - keep sideband channel idle/no-credit and local active intent deasserted
    // This models a safe "partner disappeared / init interrupted" environment.
    dut.io.rdi_pl_inband_pres.poke(false.B)
    dut.io.rdi_pl_state_sts.poke(PhyState.reset)
    dut.io.sb_rcv.poke(SideBandMessage.NOP)
    dut.io.sb_rdy.poke(false.B)
    dut.io.fdi_lp_state_req.poke(PhyStateReq.nop)
    dut.io.fdi_lp_rx_active_sts.poke(false.B)
  }

  behavior of "LinkInitAdversarialSuite"

  it should "out-of-order handshake: ignore REQ_ACTIVE before ADV_CAP exchange completion" in {
    test(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      reachParamExchange(dut)

      // Illegal early REQ_ACTIVE before parameter exchange has completed.
      pulseSbReceive(dut, SideBandMessage.REQ_ACTIVE)
      pulseSbReceive(dut, SideBandMessage.REQ_ACTIVE)

      for (cycle <- 0 until 12) {
        // Still in PARAM_EXCH waiting for legal ADV_CAP exchange completion.
        assertHeldInResetSafety(
          dut = dut,
          cycle = cycle,
          expectedSbSnd = SideBandMessage.ADV_CAP,
          phase = "out_of_order_req_active_before_param_exchange_complete"
        )
        dut.clock.step(1)
      }

      // Ensure legal completion still works after adversarial inputs.
      completeToActive(dut)
      dut.io.fdi_pl_state_sts.expect(PhyState.active) // SPEC-DERIVED
    }
  }

  it should "duplicate ADV_CAP receive: advance once and never jump to ACTIVE prematurely" in {
    test(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      reachParamExchange(dut)

      // Duplicate ADV_CAP receive before local send is acknowledged.
      pulseSbReceive(dut, SideBandMessage.ADV_CAP)
      pulseSbReceive(dut, SideBandMessage.ADV_CAP)

      // Without sb_rdy, PARAM_EXCH should not complete.
      for (cycle <- 0 until 8) {
        assertHeldInResetSafety(
          dut = dut,
          cycle = cycle,
          expectedSbSnd = SideBandMessage.ADV_CAP,
          phase = "duplicate_adv_cap_without_local_tx_ack"
        )
        dut.clock.step(1)
      }

      // A single local send acknowledgment should be enough to advance once.
      pulseSbReady(dut)
      waitUntil(dut, maxCycles = 40, reason = "advance to FDI bring-up signature") {
        dut.io.fdi_pl_inband_pres.peek().litToBoolean
      }

      // Still not ACTIVE until full active handshake is completed.
      dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
      for (_ <- 0 until 6) {
        assert(
          dut.io.sb_snd.peek().litValue != SideBandMessage.REQ_ACTIVE.litValue &&
            dut.io.sb_snd.peek().litValue != SideBandMessage.RSP_ACTIVE.litValue,
          s"[duplicate_adv_cap_after_transition_to_fdi_bringup] unexpected active-handshake sideband emission. ${snapshot(dut)}"
        ) // SPEC-DERIVED
        dut.io.fdi_pl_rx_active_req.expect(false.B) // SPEC-DERIVED
        dut.clock.step(1)
      }
    }
  }

  it should "interrupted initialization: drop inband presence and remain safely non-ACTIVE" in {
    test(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      reachParamExchange(dut)

      // Create partial init progress: local ADV_CAP sent (snt=true), no receive yet.
      pulseSbReady(dut)
      waitUntil(dut, maxCycles = 20, reason = "partial progress reflected as sb_snd NOP in PARAM_EXCH") {
        dut.io.sb_snd.peek().litValue == SideBandMessage.NOP.litValue &&
        dut.io.fdi_pl_state_sts.peek().litValue == PhyState.reset.litValue &&
        dut.io.rdi_lp_state_req.peek().litValue == PhyStateReq.active.litValue
      }

      // Interrupt by removing inband presence and forcing partner state back to reset.
      interruptInitByDroppingInband(dut)

      // Safety expectation: no premature ACTIVE and no active-handshake sideband emission.
      // In this RTL, once PARAM_EXCH is entered, rdi_lp_state_req remains active
      // until link-init progression changes state; dropping inband alone does not
      // force an internal init-state reset.
      for (cycle <- 0 until 8) {
        assertHeldInResetSafety(
          dut = dut,
          cycle = cycle,
          expectedSbSnd = SideBandMessage.NOP,
          expectedRdiLpStateReq = PhyStateReq.active,
          phase = "interrupted_init_holdoff_window"
        )
        dut.clock.step(1)
      }
    }
  }

  it should "restart after interrupted init: resume cleanly and reach ACTIVE with legal completion" in {
    test(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      reachParamExchange(dut)

      // Same interruption setup as previous test.
      pulseSbReady(dut) // partial progress only
      waitUntil(dut, maxCycles = 20, reason = "partial init progress before interruption") {
        dut.io.sb_snd.peek().litValue == SideBandMessage.NOP.litValue &&
        dut.io.rdi_lp_state_req.peek().litValue == PhyStateReq.active.litValue
      }
      interruptInitByDroppingInband(dut)

      // During interruption window, remain safely non-ACTIVE.
      for (cycle <- 0 until 6) {
        assertHeldInResetSafety(
          dut = dut,
          cycle = cycle,
          expectedSbSnd = SideBandMessage.NOP,
          expectedRdiLpStateReq = PhyStateReq.active,
          phase = "restart_after_interrupt_holdoff_window"
        )
        dut.clock.step(1)
      }

      // Resume sequence: restore partner signaling and complete legal remaining steps.
      dut.io.rdi_pl_inband_pres.poke(true.B)
      dut.io.rdi_pl_state_sts.poke(PhyState.active)

      // RTL-specific retained-progress behavior:
      // param_exch_sbmsg_snt_flag remains latched while link_state is still RESET
      // and the init substate stays in PARAM_EXCH, so after interruption/resume a
      // remote ADV_CAP receive can legally complete PARAM_EXCH.
      pulseSbReceive(dut, SideBandMessage.ADV_CAP)
      waitUntil(dut, maxCycles = 40, reason = "advance from retained PARAM_EXCH to FDI bring-up") {
        dut.io.fdi_pl_inband_pres.peek().litToBoolean &&
        dut.io.fdi_pl_state_sts.peek().litValue == PhyState.reset.litValue
      } // RTL-DERIVED
      completeFdiBringupToActive(dut)
      dut.io.fdi_pl_state_sts.expect(PhyState.active) // SPEC-DERIVED
    }
  }
}
