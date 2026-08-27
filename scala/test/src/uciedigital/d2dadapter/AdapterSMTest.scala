/*
  Description:
    Tests for AdapterSM, the unified D2D adapter state machine (link init,
    link management, link reset, disabled).

    Two harness styles are used:
      - Single instance, with the test playing the protocol layer (FDI), the
        physical layer (RDI) and the remote partner's sideband. This gives
        direct control over every arc, including error and abort paths.
      - Back-to-back pair whose sideband message ports are cross-connected, so
        the request/response exchanges run against a real partner and the full
        RESET -> ACTIVE bringup is exercised end to end.
 */
package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams
import org.scalatest.funspec.AnyFunSpec

/** Two AdapterSMs with their sideband message ports cross-connected: what one
  * sends this cycle, the other receives the next. Mirrors the D2D link where
  * both dies run the same state machine.
  */
class AdapterSMPairHarness(
    fdiParams: FdiParams,
    rdiParams: RdiParams,
    sbParams: SidebandParams
) extends Module {
  val io = IO(new Bundle {
    val a = new AdapterSMIO(fdiParams, rdiParams)
    val b = new AdapterSMIO(fdiParams, rdiParams)
  })

  private def hookup(port: AdapterSMIO): AdapterSM = {
    val sm = Module(new AdapterSM(fdiParams, rdiParams, sbParams))
    sm.io.fdi_lp_state_req := port.fdi_lp_state_req
    sm.io.fdi_lp_linkerror := port.fdi_lp_linkerror
    sm.io.fdi_lp_rx_active_sts := port.fdi_lp_rx_active_sts
    port.fdi_pl_state_sts := sm.io.fdi_pl_state_sts
    port.fdi_pl_rx_active_req := sm.io.fdi_pl_rx_active_req
    port.fdi_pl_inband_pres := sm.io.fdi_pl_inband_pres
    port.rdi_lp_linkerror := sm.io.rdi_lp_linkerror
    port.rdi_lp_state_req := sm.io.rdi_lp_state_req
    sm.io.rdi_pl_state_sts := port.rdi_pl_state_sts
    sm.io.rdi_pl_inband_pres := port.rdi_pl_inband_pres
    port.sb_snd := sm.io.sb_snd
    port.linkmgmt_stallreq := sm.io.linkmgmt_stallreq
    sm.io.linkmgmt_stalldone := port.linkmgmt_stalldone
    // Register-block status outputs, forwarded so the bundle is fully driven.
    port.link_state := sm.io.link_state
    port.param_exch_success := sm.io.param_exch_success
    sm
  }

  private val dieA = hookup(io.a)
  private val dieB = hookup(io.b)

  // Sideband cross-connect: one cycle of delay models the message crossing the
  // link, and the channel is always ready to take a message.
  // The io.a/io.b sb_rcv and sb_rdy inputs are unused: the pair drives its own
  // sideband internally.
  dieA.io.sb_rcv := RegNext(dieB.io.sb_snd, SideBandMessage.NOP)
  dieB.io.sb_rcv := RegNext(dieA.io.sb_snd, SideBandMessage.NOP)
  dieA.io.sb_rdy := true.B
  dieB.io.sb_rdy := true.B
}

class AdapterSMTest extends AnyFunSpec with ChiselSim {
  private val fdiParams = FdiParams(nBytes = 64, ncWidth = 32)
  private val rdiParams = RdiParams(nBytes = 64, ncWidth = 32)
  private val sbParams = SidebandParams()

  private def dut = new AdapterSM(fdiParams, rdiParams, sbParams)

  // ==========================================================================
  // Single-instance helpers
  // ==========================================================================

  /** Idle inputs. sb_rdy starts low so that outgoing messages stay presented
    * until the test explicitly accepts them (see expectSbAndAccept) instead of
    * disappearing after the single cycle the adapter would otherwise need.
    */
  private def init(c: AdapterSM): Unit = {
    c.io.fdi_lp_state_req.poke(RDIStateReq.nop)
    c.io.fdi_lp_linkerror.poke(false.B)
    c.io.fdi_lp_rx_active_sts.poke(false.B)
    c.io.rdi_pl_state_sts.poke(RDIState.reset)
    c.io.rdi_pl_inband_pres.poke(false.B)
    c.io.sb_rcv.poke(SideBandMessage.NOP)
    c.io.sb_rdy.poke(false.B)
    c.io.linkmgmt_stalldone.poke(false.B)
  }

  private def waitForState(
      c: AdapterSM,
      state: RDIState.Type,
      maxCycles: Int = 60
  ): Unit = {
    var guard = 0
    while (
      c.io.fdi_pl_state_sts
        .peek()
        .litValue != state.litValue && guard < maxCycles
    ) {
      c.clock.step()
      guard += 1
    }
    assert(guard < maxCycles, s"timed out waiting for adapter state $state")
    // Side outputs (inband presence, rx-active request, rdi state request) are
    // registered off the state, so give them a cycle to settle.
    c.clock.step(2)
  }

  /** Step until a condition holds, for outputs that lag their trigger. */
  private def waitUntil(c: AdapterSM, maxCycles: Int = 40)(
      cond: => Boolean
  ): Unit = {
    var guard = 0
    while (!cond && guard < maxCycles) {
      c.clock.step()
      guard += 1
    }
    assert(guard < maxCycles, "condition never became true")
  }

  /** Wait until the adapter offers the given sideband message, then let the
    * channel consume it.
    */
  private def expectSbAndAccept(
      c: AdapterSM,
      msg: UInt,
      maxCycles: Int = 40
  ): Unit = {
    var guard = 0
    while (c.io.sb_snd.peek().litValue != msg.litValue && guard < maxCycles) {
      c.clock.step()
      guard += 1
    }
    assert(guard < maxCycles, s"adapter never offered sideband message $msg")
    c.io.sb_rdy.poke(true.B)
    c.clock.step()
    c.io.sb_rdy.poke(false.B)
  }

  /** Deliver one message from the remote partner. */
  private def rcvSb(c: AdapterSM, msg: UInt): Unit = {
    c.io.sb_rcv.poke(msg)
    c.clock.step(2)
    c.io.sb_rcv.poke(SideBandMessage.NOP)
  }

  /** Walk a single instance from RESET to ACTIVE, playing the physical layer
    * (RDI status) and the remote partner (sideband messages).
    */
  private def bringUpToActive(c: AdapterSM): Unit = {
    init(c)
    c.clock.step(2)

    // INIT_START -> RDI_BRINGUP: physical layer reports inband presence.
    c.io.rdi_pl_inband_pres.poke(true.B)
    c.clock.step(2)
    // RDI_BRINGUP -> PARAM_EXCH once RDI itself is active.
    c.io.rdi_pl_state_sts.poke(RDIState.active)
    c.clock.step(2)

    // PARAM_EXCH: exchange capability advertisements both ways.
    expectSbAndAccept(c, SideBandMessage.ADV_CAP)
    rcvSb(c, SideBandMessage.ADV_CAP)

    // FDI_BRINGUP: the partner requests Active, the protocol layer brings RX
    // up, we answer, and the partner's response closes the exchange.
    c.io.fdi_lp_rx_active_sts.poke(true.B)
    rcvSb(c, SideBandMessage.REQ_ACTIVE)
    expectSbAndAccept(c, SideBandMessage.RSP_ACTIVE)
    rcvSb(c, SideBandMessage.RSP_ACTIVE)

    waitForState(c, RDIState.active)
  }

  /** Complete the FDI stall handshake the way FDIStallHandler would, and drop
    * RX as the protocol layer does on an Active exit.
    */
  private def ackStall(c: AdapterSM): Unit = {
    var guard = 0
    while (!c.io.linkmgmt_stallreq.peek().litToBoolean && guard < 30) {
      c.clock.step()
      guard += 1
    }
    assert(guard < 30, "linkmgmt_stallreq never rose")
    c.io.linkmgmt_stalldone.poke(true.B)
    c.io.fdi_lp_rx_active_sts.poke(false.B)
  }

  // ==========================================================================
  // Link initialisation
  // ==========================================================================
  describe("AdapterSM link initialisation") {
    it("Walked the link-init sub-states from reset to active") {
      simulate(dut) { c =>
        init(c)
        c.clock.step(2)
        c.io.fdi_pl_state_sts.expect(RDIState.reset)
        c.io.fdi_pl_inband_pres.expect(false.B)
        c.io.rdi_lp_state_req.expect(RDIStateReq.nop)

        // INIT_START: nothing is requested until the PHY reports presence.
        c.clock.step(2)
        c.io.rdi_lp_state_req.expect(RDIStateReq.nop)

        // RDI_BRINGUP: the adapter asks RDI for Active.
        c.io.rdi_pl_inband_pres.poke(true.B)
        c.clock.step(2)
        c.io.rdi_lp_state_req.expect(RDIStateReq.active)

        // PARAM_EXCH: the capability advertisement goes out on sideband.
        c.io.rdi_pl_state_sts.poke(RDIState.active)
        expectSbAndAccept(c, SideBandMessage.ADV_CAP)

        // Partner advertises too -> FDI_BRINGUP, inband presence rises.
        rcvSb(c, SideBandMessage.ADV_CAP)
        waitUntil(c)(c.io.fdi_pl_inband_pres.peek().litToBoolean)

        // Partner requests Active; the adapter mirrors it to the protocol
        // layer as an RX-active request.
        rcvSb(c, SideBandMessage.REQ_ACTIVE)
        waitUntil(c)(c.io.fdi_pl_rx_active_req.peek().litToBoolean)

        // Protocol layer brings RX up -> the adapter answers on sideband.
        c.io.fdi_lp_rx_active_sts.poke(true.B)
        expectSbAndAccept(c, SideBandMessage.RSP_ACTIVE)

        // The partner's response completes init -> INIT_DONE -> ACTIVE.
        rcvSb(c, SideBandMessage.RSP_ACTIVE)
        waitForState(c, RDIState.active)
        c.io.fdi_pl_rx_active_req.expect(true.B)
        c.io.fdi_pl_inband_pres.expect(true.B)
        c.io.rdi_lp_state_req.expect(RDIStateReq.active)
      }
    }

    it("Sent a local Active request when the protocol layer asked first") {
      simulate(dut) { c =>
        init(c)
        c.io.rdi_pl_inband_pres.poke(true.B)
        c.io.rdi_pl_state_sts.poke(RDIState.active)
        c.clock.step(4)
        expectSbAndAccept(c, SideBandMessage.ADV_CAP)
        rcvSb(c, SideBandMessage.ADV_CAP)

        // In FDI_BRINGUP with no partner request seen yet, a nop -> active
        // edge from the protocol layer makes the adapter issue REQ_ACTIVE.
        c.io.fdi_lp_state_req.poke(RDIStateReq.active)
        expectSbAndAccept(c, SideBandMessage.REQ_ACTIVE)
      }
    }
  }

  // ==========================================================================
  // State exits
  // ==========================================================================
  describe("AdapterSM state exits") {
    it("Entered linkReset from active after the stall handshake") {
      simulate(dut) { c =>
        bringUpToActive(c)

        c.io.fdi_lp_state_req.poke(RDIStateReq.linkReset)
        expectSbAndAccept(c, SideBandMessage.REQ_LINKRESET)
        c.io.sb_rcv.poke(SideBandMessage.RSP_LINKRESET)
        ackStall(c)
        waitForState(c, RDIState.linkReset)
        c.io.fdi_pl_inband_pres.expect(false.B)
        c.io.rdi_lp_state_req.expect(RDIStateReq.linkReset)

        // Requesting Active again returns the adapter to reset.
        c.io.sb_rcv.poke(SideBandMessage.NOP)
        c.io.fdi_lp_state_req.poke(RDIStateReq.active)
        waitForState(c, RDIState.reset)
        c.io.rdi_lp_state_req.expect(RDIStateReq.active)
      }
    }

    it("Responded to a remote link-reset request") {
      simulate(dut) { c =>
        bringUpToActive(c)

        // The partner initiates the link reset.
        c.io.sb_rcv.poke(SideBandMessage.REQ_LINKRESET)
        c.clock.step(2)
        c.io.sb_rcv.poke(SideBandMessage.NOP)
        expectSbAndAccept(c, SideBandMessage.RSP_LINKRESET)

        ackStall(c)
        waitForState(c, RDIState.linkReset)
      }
    }

    it("Entered disabled from active and returned to reset") {
      simulate(dut) { c =>
        bringUpToActive(c)

        c.io.fdi_lp_state_req.poke(RDIStateReq.disabled)
        expectSbAndAccept(c, SideBandMessage.REQ_DISABLED)
        c.io.sb_rcv.poke(SideBandMessage.RSP_DISABLED)
        ackStall(c)
        waitForState(c, RDIState.disabled)
        c.io.fdi_pl_inband_pres.expect(false.B)
        c.io.rdi_lp_state_req.expect(RDIStateReq.disabled)

        c.io.sb_rcv.poke(SideBandMessage.NOP)
        c.io.fdi_lp_state_req.poke(RDIStateReq.active)
        waitForState(c, RDIState.reset)
        c.io.rdi_lp_state_req.expect(RDIStateReq.active)
      }
    }

    it("Responded to a remote disable request and exited on a PHY reset") {
      simulate(dut) { c =>
        bringUpToActive(c)
        c.io.sb_rcv.poke(SideBandMessage.REQ_DISABLED)
        c.clock.step(2)
        c.io.sb_rcv.poke(SideBandMessage.NOP)
        expectSbAndAccept(c, SideBandMessage.RSP_DISABLED)
        ackStall(c)
        waitForState(c, RDIState.disabled)

        // The physical layer dropping back to reset also exits disabled.
        c.io.rdi_pl_state_sts.poke(RDIState.reset)
        waitForState(c, RDIState.reset)
      }
    }

    it("Followed the physical layer into retrain and out to linkReset") {
      simulate(dut) { c =>
        bringUpToActive(c)

        // The PHY reports retrain: the adapter requests retrain on RDI, stalls
        // the protocol layer and drops RX before following.
        c.io.rdi_pl_state_sts.poke(RDIState.retrain)
        c.clock.step(2)
        c.io.rdi_lp_state_req.expect(RDIStateReq.retrain)
        ackStall(c)
        waitForState(c, RDIState.retrain)
        c.clock.step(2)
        c.io.rdi_lp_state_req.expect(RDIStateReq.nop)

        // From retrain a link reset can still be requested.
        c.io.linkmgmt_stalldone.poke(false.B)
        c.io.fdi_lp_state_req.poke(RDIStateReq.linkReset)
        expectSbAndAccept(c, SideBandMessage.REQ_LINKRESET)
        c.io.sb_rcv.poke(SideBandMessage.RSP_LINKRESET)
        waitForState(c, RDIState.linkReset)
      }
    }

    it("Entered disabled directly from retrain") {
      simulate(dut) { c =>
        bringUpToActive(c)
        c.io.rdi_pl_state_sts.poke(RDIState.retrain)
        ackStall(c)
        waitForState(c, RDIState.retrain)

        c.io.linkmgmt_stalldone.poke(false.B)
        c.io.fdi_lp_state_req.poke(RDIStateReq.disabled)
        expectSbAndAccept(c, SideBandMessage.REQ_DISABLED)
        c.io.sb_rcv.poke(SideBandMessage.RSP_DISABLED)
        waitForState(c, RDIState.disabled)
      }
    }

    it("Entered disabled from linkReset") {
      simulate(dut) { c =>
        bringUpToActive(c)
        c.io.fdi_lp_state_req.poke(RDIStateReq.linkReset)
        expectSbAndAccept(c, SideBandMessage.REQ_LINKRESET)
        c.io.sb_rcv.poke(SideBandMessage.RSP_LINKRESET)
        ackStall(c)
        waitForState(c, RDIState.linkReset)

        // In linkReset only the disabled flow is arbitrated onto sideband.
        c.io.sb_rcv.poke(SideBandMessage.NOP)
        c.io.fdi_lp_state_req.poke(RDIStateReq.disabled)
        expectSbAndAccept(c, SideBandMessage.REQ_DISABLED)
        c.io.sb_rcv.poke(SideBandMessage.RSP_DISABLED)
        waitForState(c, RDIState.disabled)
      }
    }

    it("Entered linkError from active and recovered to reset") {
      simulate(dut) { c =>
        bringUpToActive(c)

        c.io.rdi_pl_state_sts.poke(RDIState.linkError)
        waitForState(c, RDIState.linkError)
        c.io.fdi_pl_inband_pres.expect(false.B)

        // RX is still up, so the adapter stays in linkError: that is where the
        // Active request from the protocol layer gets forwarded to RDI.
        c.io.fdi_lp_state_req.poke(RDIStateReq.active)
        c.clock.step(2)
        c.io.rdi_lp_state_req.expect(RDIStateReq.active)

        // Dropping RX satisfies the exit condition and the adapter re-inits.
        c.io.fdi_lp_rx_active_sts.poke(false.B)
        waitForState(c, RDIState.reset)
      }
    }

    it("Entered linkError from reset during link init") {
      simulate(dut) { c =>
        init(c)
        c.io.rdi_pl_inband_pres.poke(true.B)
        c.clock.step(3)
        c.io.rdi_pl_state_sts.poke(RDIState.linkError)
        waitForState(c, RDIState.linkError)
        c.io.fdi_pl_inband_pres.expect(false.B)
      }
    }

    it("Entered linkError from linkReset") {
      simulate(dut) { c =>
        bringUpToActive(c)
        c.io.fdi_lp_state_req.poke(RDIStateReq.linkReset)
        expectSbAndAccept(c, SideBandMessage.REQ_LINKRESET)
        c.io.sb_rcv.poke(SideBandMessage.RSP_LINKRESET)
        ackStall(c)
        waitForState(c, RDIState.linkReset)
        c.io.sb_rcv.poke(SideBandMessage.NOP)

        // The PHY still reports Active here, so the adapter stays in linkReset
        // until the error is injected.
        c.io.rdi_pl_state_sts.poke(RDIState.linkError)
        waitForState(c, RDIState.linkError)
        c.io.fdi_pl_inband_pres.expect(false.B)
      }
    }

    it("Entered linkError from retrain") {
      simulate(dut) { c =>
        bringUpToActive(c)
        c.io.rdi_pl_state_sts.poke(RDIState.retrain)
        ackStall(c)
        waitForState(c, RDIState.retrain)

        c.io.rdi_pl_state_sts.poke(RDIState.linkError)
        waitForState(c, RDIState.linkError)
        c.io.fdi_pl_inband_pres.expect(false.B)
      }
    }

    it("Entered linkError from disabled") {
      simulate(dut) { c =>
        bringUpToActive(c)
        c.io.fdi_lp_state_req.poke(RDIStateReq.disabled)
        expectSbAndAccept(c, SideBandMessage.REQ_DISABLED)
        c.io.sb_rcv.poke(SideBandMessage.RSP_DISABLED)
        ackStall(c)
        waitForState(c, RDIState.disabled)
        c.io.sb_rcv.poke(SideBandMessage.NOP)

        c.io.rdi_pl_state_sts.poke(RDIState.linkError)
        waitForState(c, RDIState.linkError)
      }
    }

    it("Entered linkReset straight out of reset during link init") {
      simulate(dut) { c =>
        init(c)
        c.io.rdi_pl_inband_pres.poke(true.B)
        c.io.rdi_pl_state_sts.poke(RDIState.active)
        c.clock.step(3)

        // A link-reset request during link init: the nop -> linkReset edge is
        // latched while still in reset, so the adapter leaves for linkReset
        // without ever reaching Active. RX is down throughout.
        c.io.fdi_lp_state_req.poke(RDIStateReq.linkReset)
        expectSbAndAccept(c, SideBandMessage.REQ_LINKRESET)
        c.io.sb_rcv.poke(SideBandMessage.RSP_LINKRESET)
        waitForState(c, RDIState.linkReset)
        c.io.sb_rcv.poke(SideBandMessage.NOP)
        c.io.fdi_pl_inband_pres.expect(false.B)
      }
    }

    it("Entered disabled straight out of reset during link init") {
      simulate(dut) { c =>
        init(c)
        c.io.rdi_pl_inband_pres.poke(true.B)
        c.io.rdi_pl_state_sts.poke(RDIState.active)
        c.clock.step(3)

        // A disable request during link init: the nop -> disabled edge is
        // latched while still in reset, so the adapter leaves for disabled
        // without ever reaching Active. RX is down the whole time.
        c.io.fdi_lp_state_req.poke(RDIStateReq.disabled)
        expectSbAndAccept(c, SideBandMessage.REQ_DISABLED)
        c.io.sb_rcv.poke(SideBandMessage.RSP_DISABLED)
        waitForState(c, RDIState.disabled)
        c.io.sb_rcv.poke(SideBandMessage.NOP)
        c.io.fdi_pl_inband_pres.expect(false.B)
      }
    }

    it("Restarted link init when the link left reset mid-bringup") {
      simulate(dut) { c =>
        // Reach FDI_BRINGUP (inband presence is the observable marker) ...
        init(c)
        c.io.rdi_pl_inband_pres.poke(true.B)
        c.io.rdi_pl_state_sts.poke(RDIState.active)
        c.clock.step(3)
        expectSbAndAccept(c, SideBandMessage.ADV_CAP)
        rcvSb(c, SideBandMessage.ADV_CAP)
        waitUntil(c)(c.io.fdi_pl_inband_pres.peek().litToBoolean)

        // ... then have the physical layer report an error. The link state
        // leaves reset, so the link-init sub-state machine must rewind all the
        // way to INIT_START rather than resuming mid-bringup.
        c.io.rdi_pl_state_sts.poke(RDIState.linkError)
        waitForState(c, RDIState.linkError)
        c.io.fdi_pl_inband_pres.expect(false.B)

        // Recovering re-runs link init from the beginning: the capability
        // advertisement is sent again. Leaving linkError needs an Active
        // request from the protocol layer while RX is down.
        c.io.fdi_lp_rx_active_sts.poke(false.B)
        c.io.fdi_lp_state_req.poke(RDIStateReq.active)
        waitForState(c, RDIState.reset)
        c.io.rdi_pl_state_sts.poke(RDIState.active)
        expectSbAndAccept(c, SideBandMessage.ADV_CAP)
      }
    }

    it("Switched from its own Active request to answering the partner's") {
      simulate(dut) { c =>
        init(c)
        c.io.rdi_pl_inband_pres.poke(true.B)
        c.io.rdi_pl_state_sts.poke(RDIState.active)
        c.clock.step(3)
        expectSbAndAccept(c, SideBandMessage.ADV_CAP)
        rcvSb(c, SideBandMessage.ADV_CAP)

        // In FDI_BRINGUP the protocol layer asks for Active first, so the
        // adapter offers REQ_ACTIVE (held, because the channel is not ready).
        c.io.fdi_lp_state_req.poke(RDIStateReq.active)
        waitUntil(c)(
          c.io.sb_snd.peek().litValue == SideBandMessage.REQ_ACTIVE.litValue
        )

        // The partner's own request then arrives while RX is up: answering it
        // takes priority, so the offered message switches straight from
        // REQ_ACTIVE to RSP_ACTIVE without an idle cycle in between.
        c.io.fdi_lp_rx_active_sts.poke(true.B)
        c.io.sb_rcv.poke(SideBandMessage.REQ_ACTIVE)
        waitUntil(c)(
          c.io.sb_snd.peek().litValue == SideBandMessage.RSP_ACTIVE.litValue
        )
        c.io.sb_rcv.poke(SideBandMessage.NOP)
      }
    }

    it("Returned to Active after the physical layer finished retraining") {
      simulate(dut) { c =>
        bringUpToActive(c)

        // PHY retrains; the adapter follows it into retrain.
        c.io.rdi_pl_state_sts.poke(RDIState.retrain)
        ackStall(c)
        waitForState(c, RDIState.retrain)

        // Retraining completes: the PHY is back in Active and the protocol
        // layer is ready again, so the adapter should resume Active.
        c.io.linkmgmt_stalldone.poke(false.B)
        c.io.rdi_pl_state_sts.poke(RDIState.active)
        c.io.fdi_lp_state_req.poke(RDIStateReq.active)
        c.io.fdi_lp_rx_active_sts.poke(true.B)
        waitForState(c, RDIState.active, maxCycles = 80)
      }
    }

    it("Propagated the protocol link-error flag to the physical layer") {
      simulate(dut) { c =>
        init(c)
        c.clock.step()
        c.io.rdi_lp_linkerror.expect(false.B)
        c.io.fdi_lp_linkerror.poke(true.B)
        c.clock.step()
        c.io.rdi_lp_linkerror.expect(true.B)
        c.io.fdi_lp_linkerror.poke(false.B)
        c.clock.step()
        c.io.rdi_lp_linkerror.expect(false.B)
      }
    }

    it("Held the sideband message until the channel accepted it") {
      simulate(dut) { c =>
        bringUpToActive(c)

        // sb_rdy stays low, so the request is presented indefinitely.
        c.io.fdi_lp_state_req.poke(RDIStateReq.linkReset)
        c.clock.step(2)
        c.io.sb_snd.expect(SideBandMessage.REQ_LINKRESET)
        c.clock.step(4)
        c.io.sb_snd.expect(SideBandMessage.REQ_LINKRESET)

        // Once accepted, the exchange can complete.
        c.io.sb_rdy.poke(true.B)
        c.clock.step()
        c.io.sb_rdy.poke(false.B)
        c.io.sb_rcv.poke(SideBandMessage.RSP_LINKRESET)
        ackStall(c)
        waitForState(c, RDIState.linkReset)
      }
    }

    it("Prioritised a disable request over a link reset") {
      simulate(dut) { c =>
        bringUpToActive(c)

        // Both flows pending: the arbiter must pick the disabled response.
        c.io.sb_rcv.poke(SideBandMessage.REQ_LINKRESET)
        c.clock.step(2)
        c.io.sb_rcv.poke(SideBandMessage.REQ_DISABLED)
        c.clock.step(2)
        c.io.sb_rcv.poke(SideBandMessage.NOP)
        c.io.sb_snd.expect(SideBandMessage.RSP_DISABLED)
      }
    }
  }

  // ==========================================================================
  // Back-to-back pair
  // ==========================================================================
  describe("AdapterSM pair") {
    def initPair(c: AdapterSMPairHarness, phyState: RDIState.Type): Unit = {
      for (p <- Seq(c.io.a, c.io.b)) {
        p.fdi_lp_state_req.poke(RDIStateReq.nop)
        p.fdi_lp_linkerror.poke(false.B)
        p.fdi_lp_rx_active_sts.poke(false.B)
        p.rdi_pl_state_sts.poke(phyState)
        p.rdi_pl_inband_pres.poke(false.B)
        p.sb_rcv.poke(SideBandMessage.NOP)
        p.sb_rdy.poke(false.B)
        p.linkmgmt_stalldone.poke(false.B)
      }
    }

    def waitPair(
        c: AdapterSMPairHarness,
        state: RDIState.Type,
        maxCycles: Int = 200
    ): Unit = {
      var guard = 0
      while (
        !(c.io.a.fdi_pl_state_sts.peek().litValue == state.litValue &&
          c.io.b.fdi_pl_state_sts.peek().litValue == state.litValue)
        && guard < maxCycles
      ) {
        c.clock.step()
        guard += 1
      }
      assert(guard < maxCycles, s"the adapter pair never both reached $state")
      c.clock.step(2) // let the registered side outputs settle
    }

    it("Completed a mutual bringup to active over the sideband link") {
      simulate(new AdapterSMPairHarness(fdiParams, rdiParams, sbParams)) { c =>
        initPair(c, RDIState.reset)
        c.clock.step(2)

        // Both physical layers come up; the adapters exchange capabilities on
        // their own and reach FDI_BRINGUP, signalled by inband presence.
        for (p <- Seq(c.io.a, c.io.b)) {
          p.rdi_pl_inband_pres.poke(true.B)
          p.rdi_pl_state_sts.poke(RDIState.active)
        }
        var guard = 0
        while (
          !(c.io.a.fdi_pl_inband_pres.peek().litToBoolean &&
            c.io.b.fdi_pl_inband_pres.peek().litToBoolean) && guard < 100
        ) {
          c.clock.step()
          guard += 1
        }
        assert(guard < 100, "the pair never reached FDI bringup")

        // Only now do the protocol layers request Active: the state machine
        // latches the nop -> active edge while in FDI_BRINGUP.
        for (p <- Seq(c.io.a, c.io.b)) {
          p.fdi_lp_state_req.poke(RDIStateReq.active)
          p.fdi_lp_rx_active_sts.poke(true.B)
        }

        waitPair(c, RDIState.active)
        for (p <- Seq(c.io.a, c.io.b)) {
          p.fdi_pl_inband_pres.expect(true.B)
          p.fdi_pl_rx_active_req.expect(true.B)
          p.rdi_lp_state_req.expect(RDIStateReq.active)
        }
      }
    }

    it("Propagated a link reset from one die to the other") {
      simulate(new AdapterSMPairHarness(fdiParams, rdiParams, sbParams)) { c =>
        initPair(c, RDIState.active)
        for (p <- Seq(c.io.a, c.io.b)) p.rdi_pl_inband_pres.poke(true.B)
        var guard = 0
        while (
          !(c.io.a.fdi_pl_inband_pres.peek().litToBoolean &&
            c.io.b.fdi_pl_inband_pres.peek().litToBoolean) && guard < 100
        ) {
          c.clock.step()
          guard += 1
        }
        assert(guard < 100, "the pair never reached FDI bringup")
        for (p <- Seq(c.io.a, c.io.b)) {
          p.fdi_lp_state_req.poke(RDIStateReq.active)
          p.fdi_lp_rx_active_sts.poke(true.B)
        }
        waitPair(c, RDIState.active)

        // Only die A's protocol layer requests the reset; die B has to follow
        // through the sideband request it receives.
        c.io.a.fdi_lp_state_req.poke(RDIStateReq.linkReset)
        c.io.b.fdi_lp_state_req.poke(RDIStateReq.nop)
        for (p <- Seq(c.io.a, c.io.b)) {
          p.linkmgmt_stalldone.poke(true.B)
          p.fdi_lp_rx_active_sts.poke(false.B)
        }

        waitPair(c, RDIState.linkReset)
      }
    }
  }
}
