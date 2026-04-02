package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

/**
  * Minimal sideband/control-FSM suite for LinkManagementController.
  *
  * Scope:
  * - verifies top-level link-management FSM state progression and sideband handshakes
  * - does not build a full sideband packet/model environment
  * - does not attempt full UCIe protocol coverage
  *
  * Note:
  * - this RTL has explicit failure (LinkError) and retrain behaviors
  * - there is no explicit top-level LinkManagementController timeout state/output to assert directly
  */
class LinkManagementStateMachineSuite extends AnyFlatSpec with ChiselSim {
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
        s"state=${dut.io.fdi_pl_state_sts.peek().litValue} " +
        s"sb_snd=0x${dut.io.sb_snd.peek().litValue.toString(16)}"
    ) // UNKNOWN: needs spec/RTL audit
  }

  private def pulseSbReady(dut: LinkManagementController): Unit = {
    dut.io.sb_rdy.poke(true.B)
    dut.clock.step(1)
    dut.io.sb_rdy.poke(false.B)
  }

  private def pulseSbReceive(dut: LinkManagementController, msg: UInt): Unit = {
    dut.io.sb_rcv.poke(msg)
    dut.clock.step(1)
    dut.io.sb_rcv.poke(SideBandMessage.NOP)
  }

  private def completeFdiBringupToActive(dut: LinkManagementController): Unit = {
    waitUntil(dut, maxCycles = 40, reason = "FDI inband present asserted") {
      dut.io.fdi_pl_inband_pres.peek().litToBoolean
    }

    // Trigger nop -> active edge required by LinkInitSubmodule.
    dut.io.fdi_lp_state_req.poke(PhyStateReq.nop)
    dut.clock.step(1)
    dut.io.fdi_lp_state_req.poke(PhyStateReq.active)
    dut.clock.step(1)

    waitUntil(dut, maxCycles = 40, reason = "REQ_ACTIVE sideband request") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.REQ_ACTIVE.litValue
    }
    pulseSbReady(dut)

    // Remote side requests Active, then eventually responds Active.
    pulseSbReceive(dut, SideBandMessage.REQ_ACTIVE)

    waitUntil(dut, maxCycles = 40, reason = "fdi_pl_rx_active_req high") {
      dut.io.fdi_pl_rx_active_req.peek().litToBoolean
    }

    pulseSbReceive(dut, SideBandMessage.RSP_ACTIVE)

    // Local protocol indicates rx path is ready.
    dut.io.fdi_lp_rx_active_sts.poke(true.B)

    waitUntil(dut, maxCycles = 40, reason = "RSP_ACTIVE sideband response") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.RSP_ACTIVE.litValue
    }
    pulseSbReady(dut)

    waitUntil(dut, maxCycles = 40, reason = "top-level ACTIVE state") {
      dut.io.fdi_pl_state_sts.peek().litValue == PhyState.active.litValue
    }
  }

  private def bringLinkToActive(dut: LinkManagementController): Unit = {
    // Start LinkInit from RESET.
    dut.io.rdi_pl_inband_pres.poke(true.B)
    waitUntil(dut, maxCycles = 30, reason = "rdi_lp_state_req ACTIVE during RDI bringup") {
      dut.io.rdi_lp_state_req.peek().litValue == PhyStateReq.active.litValue
    }

    // Physical layer reports ACTIVE.
    dut.io.rdi_pl_state_sts.poke(PhyState.active)

    // Parameter exchange: ADV_CAP send/recv handshake.
    waitUntil(dut, maxCycles = 40, reason = "ADV_CAP sideband send") {
      dut.io.sb_snd.peek().litValue == SideBandMessage.ADV_CAP.litValue
    }
    pulseSbReceive(dut, SideBandMessage.ADV_CAP)
    pulseSbReady(dut)

    completeFdiBringupToActive(dut)
  }

  behavior of "LinkManagementStateMachineSuite(LinkManagementController)"

  it should "stay in RESET at idle with no spontaneous outputs" in {
    simulate(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      // SPEC-DERIVED
      for (_ <- 0 until 20) {
        dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
        dut.io.rdi_lp_state_req.expect(PhyStateReq.nop) // SPEC-DERIVED
        dut.io.fdi_pl_inband_pres.expect(false.B) // SPEC-DERIVED
        dut.io.fdi_pl_rx_active_req.expect(false.B) // SPEC-DERIVED
        dut.io.sb_snd.expect(SideBandMessage.NOP) // RTL-DERIVED
        dut.clock.step(1)
      }
    }
  }

  it should "complete nominal bring-up and reach ACTIVE through sideband handshakes" in {
    simulate(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      bringLinkToActive(dut)

      dut.io.fdi_pl_state_sts.expect(PhyState.active) // SPEC-DERIVED
      dut.io.fdi_pl_inband_pres.expect(true.B) // SPEC-DERIVED
      dut.io.fdi_pl_rx_active_req.expect(true.B) // SPEC-DERIVED
    }
  }

  it should "stay in RESET until ADV_CAP exchange is fully completed" in {
    simulate(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      dut.io.rdi_pl_inband_pres.poke(true.B)
      waitUntil(dut, maxCycles = 30, reason = "rdi_lp_state_req ACTIVE during RDI bringup") {
        dut.io.rdi_lp_state_req.peek().litValue == PhyStateReq.active.litValue
      }
      dut.io.rdi_pl_state_sts.poke(PhyState.active)

      waitUntil(dut, maxCycles = 40, reason = "ADV_CAP sideband send") {
        dut.io.sb_snd.peek().litValue == SideBandMessage.ADV_CAP.litValue
      }

      // Hold required exchange completion low/high (no sb_rdy, no ADV_CAP recv).
      for (_ <- 0 until 12) {
        dut.io.fdi_pl_state_sts.expect(PhyState.reset) // SPEC-DERIVED
        dut.io.sb_snd.expect(SideBandMessage.ADV_CAP) // RTL-DERIVED
        dut.clock.step(1)
      }

      // Release missing conditions and verify forward progress to ACTIVE.
      pulseSbReceive(dut, SideBandMessage.ADV_CAP)
      pulseSbReady(dut)
      completeFdiBringupToActive(dut)
      dut.io.fdi_pl_state_sts.expect(PhyState.active) // SPEC-DERIVED
    }
  }

  it should "enter LINKERROR on PHY fault and recover to RESET when RX deactivates" in {
    simulate(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      bringLinkToActive(dut)

      // Physical layer failure indication.
      dut.io.rdi_pl_state_sts.poke(PhyState.linkError)
      waitUntil(dut, maxCycles = 20, reason = "LINKERROR entry") {
        dut.io.fdi_pl_state_sts.peek().litValue == PhyState.linkError.litValue
      }
      dut.io.fdi_pl_state_sts.expect(PhyState.linkError) // SPEC-DERIVED

      // Keep RX active status asserted and verify no premature recovery.
      dut.io.fdi_lp_rx_active_sts.poke(true.B)
      for (_ <- 0 until 6) {
        dut.io.fdi_pl_state_sts.expect(PhyState.linkError) // RTL-DERIVED
        dut.clock.step(1)
      }

      // Recovery condition in this RTL: linkerror + rx_deactive moves back to RESET.
      dut.io.fdi_lp_state_req.poke(PhyStateReq.active) // conservative bringup intent
      dut.io.fdi_lp_rx_active_sts.poke(false.B)
      waitUntil(dut, maxCycles = 20, reason = "recovery to RESET") {
        dut.io.fdi_pl_state_sts.peek().litValue == PhyState.reset.litValue
      }
      dut.io.fdi_pl_state_sts.expect(PhyState.reset) // RTL-DERIVED
    }
  }

  it should "require stall-done before transitioning from ACTIVE to RETRAIN" in {
    simulate(new LinkManagementController(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      bringLinkToActive(dut)

      // Request retrain from PHY.
      dut.io.rdi_pl_state_sts.poke(PhyState.retrain)
      dut.io.fdi_lp_rx_active_sts.poke(false.B) // allow rx_deactive condition
      dut.io.linkmgmt_stalldone.poke(false.B)

      waitUntil(dut, maxCycles = 20, reason = "stall request asserted") {
        dut.io.linkmgmt_stallreq.peek().litToBoolean
      }

      // No stall-done => no transition yet.
      for (_ <- 0 until 8) {
        dut.io.fdi_pl_state_sts.expect(PhyState.active) // SPEC-DERIVED
        dut.clock.step(1)
      }

      // Complete stall handshake and transition to RETRAIN.
      dut.io.linkmgmt_stalldone.poke(true.B)
      waitUntil(dut, maxCycles = 20, reason = "ACTIVE->RETRAIN transition") {
        dut.io.fdi_pl_state_sts.peek().litValue == PhyState.retrain.litValue
      }
      dut.io.fdi_pl_state_sts.expect(PhyState.retrain) // SPEC-DERIVED

      // With no extra triggers, remain in RETRAIN.
      dut.io.linkmgmt_stalldone.poke(false.B)
      for (_ <- 0 until 8) {
        dut.io.fdi_pl_state_sts.expect(PhyState.retrain) // RTL-DERIVED
        dut.clock.step(1)
      }
    }
  }
}
