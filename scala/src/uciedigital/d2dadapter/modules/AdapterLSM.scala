/*
  The Adapter link state machine (LSM) of sec 3.5, sec 10.2 and sec 10.3.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import edu.berkeley.cs.uciedigital.rdi._
import chisel3._
import chisel3.util._
import chisel3.layer.block
import chisel3.layers.Verification
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._

class AdapterLSM(
    val fdiParams: FdiParams,
    val rdiParams: RdiParams,
    val sbParams: SidebandParams
) extends Module {
  val io = IO(new AdapterLSMIO(fdiParams, rdiParams, sbParams))

  // ===========================================================================
  // Modules
  // ===========================================================================
  val fsm = Module(new FdiStateMachine(sbParams))
  val initSM = Module(new AdapterInitSM(sbParams))
  val requester = Module(new AdapterLSMRequester(sbParams))
  val responder = Module(new AdapterLSMResponder(sbParams))
  val rdiRequest = Module(new RdiStateRequestSM())
  val rxActive = Module(new FdiRxActiveRequester())

  // ===========================================================================
  // Shared state decodes
  // ===========================================================================
  val state = fsm.io.plStateSts
  val statePrev = RegNext(state, FDIState.reset)
  val rdiActive = io.rdi_pl_state_sts === RDIState.active
  val lsmDown = state === FDIState.linkReset ||
    state === FDIState.disabled || state === FDIState.linkError

  // sec 10.3 applies every Active rule to Active.PMNAK.
  val inActiveColumn =
    state === FDIState.active || state === FDIState.activePmNak

  // sec 10.3.3.4 lets the Protocol Layer and the software Retrain bit ask for
  // Retrain. Only RDI Retrain moves the LSM (sec 3.5).
  val retrainRequest =
    io.fdi_lp_state_req === FDIStateReq.retrain || io.sw_retrain
  val retrainStatus = io.rdi_pl_state_sts === RDIState.retrain
  val plAsksL1 = io.fdi_lp_state_req === FDIStateReq.l1
  val plAsksL2 = io.fdi_lp_state_req === FDIStateReq.l2

  // ===========================================================================
  // Protocol Layer requests
  // ===========================================================================
  // sec 10.3.3.1 rule 1 and sec 10.3.3.4 rule 1 need a NOP before Active. The
  // NOP can come before pl_inband_pres (sec 10.3.3.8), so it is latched.
  val fdiNopSeen = RegInit(false.B)
  when(state =/= statePrev) {
    fdiNopSeen := false.B
  }.elsewhen(
    io.fdi_lp_state_req === FDIStateReq.nop &&
      (state === FDIState.reset || state === FDIState.retrain)
  ) {
    fdiNopSeen := true.B
  }

  // The latch clears one cycle late. Wait one cycle in the state, so a NOP
  // from the previous state does not count.
  val nopFirst = fdiNopSeen && state === statePrev
  val nopToActive = nopFirst && io.fdi_lp_state_req === FDIStateReq.active
  val activeReqEdgeReg = RegInit(false.B)
  when(nopToActive) {
    activeReqEdgeReg := true.B
  }.elsewhen(state === FDIState.active || lsmDown) {
    activeReqEdgeReg := false.B
  }

  // sec 10.3.3.1 rules 2 and 3 need a NOP before a LinkReset or Disabled
  // request while the status is Reset. From Active the held level is enough.
  val linkResetTrackable = state === FDIState.reset || inActiveColumn ||
    state === FDIState.retrain || state === FDIState.l1 ||
    state === FDIState.l2
  val disableTrackable = linkResetTrackable || state === FDIState.linkReset
  val fdiReqLinkReset = RegInit(false.B)
  val fdiReqDisable = RegInit(false.B)

  val linkResetAsked = Mux(
    state === FDIState.reset,
    io.fdi_lp_state_req === FDIStateReq.linkReset && nopFirst,
    io.fdi_lp_state_req === FDIStateReq.linkReset
  )
  val disableAsked = Mux(
    state === FDIState.reset,
    io.fdi_lp_state_req === FDIStateReq.disabled && nopFirst,
    io.fdi_lp_state_req === FDIStateReq.disabled
  )
  when(linkResetTrackable) {
    when(linkResetAsked) { fdiReqLinkReset := true.B }
  }.otherwise {
    fdiReqLinkReset := false.B
  }
  when(disableTrackable) {
    when(disableAsked) { fdiReqDisable := true.B }
  }.otherwise {
    fdiReqDisable := false.B
  }

  // ===========================================================================
  // Down state agreement
  // ===========================================================================
  // sec 3.5 passes RDI LinkReset and Disabled to the LSM. Act on the entry
  // edge, because the level still holds when the LSM is back in Reset.
  def rdiIs(s: RDIState.Type): Bool = io.rdi_pl_state_sts === s
  val rdiPrev = RegNext(io.rdi_pl_state_sts, RDIState.reset)
  val takeLinkReset = rdiIs(RDIState.linkReset) &&
    rdiPrev =/= RDIState.linkReset && !fsm.io.linkResetAgreed &&
    !fsm.io.disabledAgreed
  val takeDisable = rdiIs(RDIState.disabled) &&
    rdiPrev =/= RDIState.disabled && !fsm.io.disabledAgreed
  val rdiLinkResetPending = RegInit(false.B)
  val rdiDisablePending = RegInit(false.B)
  when(lsmDown) {
    rdiLinkResetPending := false.B
  }.elsewhen(takeLinkReset) {
    rdiLinkResetPending := true.B
  }
  when(state === FDIState.disabled || state === FDIState.linkError) {
    rdiDisablePending := false.B
  }.elsewhen(takeDisable) {
    rdiDisablePending := true.B
  }
  val askLinkReset = fdiReqLinkReset || rdiLinkResetPending
  val askDisable = fdiReqDisable || rdiDisablePending

  // The note after sec 10.3.3.8 needs no sideband handshake when RDI is in
  // Reset or already in the requested state.
  def rose(x: Bool): Bool = x && !RegNext(x, false.B)
  val answeredLinkReset = rose(responder.io.sentRspLinkReset)
  val answeredDisable = rose(responder.io.sentRspDisable)
  val agreeLinkReset = answeredLinkReset ||
    rose(requester.io.rcvdRspLinkReset) ||
    (rdiIs(RDIState.reset) && askLinkReset) ||
    (rdiIs(RDIState.linkReset) && fdiReqLinkReset)
  val agreeDisabled = answeredDisable ||
    rose(requester.io.rcvdRspDisable) ||
    (rdiIs(RDIState.reset) && askDisable) ||
    (rdiIs(RDIState.disabled) && fdiReqDisable)

  // End of the Active Entry Handshake (sec 10.2.8 step 5).
  val activeEntryDone = responder.io.sentRspActive && requester.io.rcvdRspActive

  // ===========================================================================
  // FDI state machine
  // ===========================================================================
  fsm.io.lpStateReq := io.fdi_lp_state_req
  fsm.io.lpLinkError := io.fdi_lp_linkerror
  fsm.io.internalLinkError := io.internal_linkerror
  fsm.io.rdiPlStateSts := io.rdi_pl_state_sts
  fsm.io.rxDeactive := rxActive.io.rxDeactive
  val stallDone = fsm.io.stallReq && io.linkmgmt_stalldone
  fsm.io.stallDone := stallDone
  fsm.io.initDone := initSM.io.initDone
  fsm.io.activeEntryDone := activeEntryDone
  fsm.io.agreeLinkReset := agreeLinkReset
  fsm.io.agreeDisabled := agreeDisabled
  fsm.io.activeReqEdge := activeReqEdgeReg
  fsm.io.stallTrigger := io.rdi_pl_stallreq || io.sw_retrain ||
    plAsksL1 || plAsksL2 || askLinkReset || askDisable ||
    responder.io.peerReqLinkReset || responder.io.peerReqDisable

  // A PM response in either direction agrees PM (sec 10.2.9). A received
  // response counts only if this Protocol Layer also asks.
  val pmAgree = state === FDIState.active && (
    (requester.io.rcvdRspPm && (plAsksL1 || plAsksL2)) ||
      responder.io.answeredPm
  )
  fsm.io.pmNak := requester.io.rcvdPmNak
  fsm.io.pmAgree := pmAgree
  fsm.io.pmAgreeL2 := plAsksL2
  fsm.io.peerReqActive := responder.io.peerReqActive

  // ===========================================================================
  // Stage 3 sequencer
  // ===========================================================================
  initSM.io.rdiPlInbandPres := io.rdi_pl_inband_pres
  initSM.io.rdiActive := rdiActive
  initSM.io.transitionToActive := activeReqEdgeReg
  initSM.io.peerReqActive := responder.io.peerReqActive
  initSM.io.sentRspActive := responder.io.sentRspActive
  initSM.io.rcvdRspActive := requester.io.rcvdRspActive
  initSM.io.advCapPayload := io.adv_cap_payload

  // Restart only on entry to a down state. RDI reaches Reset only through
  // these, so each new link gets a fresh exchange (sec 3.4). Retrain keeps the
  // exchange, because it sends no new {AdvCap.Adapter}.
  initSM.io.restart := lsmDown && state =/= statePrev
  initSM.io.hold := lsmDown

  // The L2 exit returns through Reset with nothing renegotiated, so only part
  // 3 runs again (sec 10.3.3.8).
  initSM.io.resumeFdiBringup :=
    state === FDIState.reset && statePrev === FDIState.l2

  // ===========================================================================
  // Sideband
  // ===========================================================================
  // Stage 3 and the Active negotiation wait for RDI Active (sec 3.5).
  // LinkReset and Disable need only RDI out of Reset (Table 10-4).
  requester.io.activeAllowed := rdiActive
  requester.io.downAllowed := !rdiIs(RDIState.reset)
  responder.io.activeAllowed := rdiActive
  initSM.io.txAllowed := rdiActive

  // sec 10.2.8 runs the Active Entry Handshake on every entry to Active, so the
  // tracking clears when RDI leaves Active or PM is agreed.
  val leftDownState = state =/= statePrev &&
    (statePrev === FDIState.linkReset || statePrev === FDIState.disabled ||
      statePrev === FDIState.linkError)
  requester.io.clearActive := !rdiActive || pmAgree
  responder.io.clearActive := !rdiActive || pmAgree
  requester.io.clearDown := leftDownState
  responder.io.clearDown := leftDownState

  // sec 10.2.9 lets only LinkError reset an outstanding PM handshake.
  requester.io.clearPm := state === FDIState.linkError
  responder.io.clearPending := state === FDIState.linkError

  requester.io.rx.valid := io.sb_rcv.valid
  requester.io.rx.bits := io.sb_rcv.bits
  responder.io.rx.valid := io.sb_rcv.valid
  responder.io.rx.bits := io.sb_rcv.bits
  initSM.io.rx.valid := io.sb_rcv.valid
  initSM.io.rx.bits := io.sb_rcv.bits

  // sec 10.2.8 step 3 sends Req.Active on lp_state_req Active. A PM exit uses
  // the held Active with no NOP edge (sec 10.3.3.4 rule 1).
  val pmExit = state === FDIState.l1 || state === FDIState.l2 ||
    (state === FDIState.retrain && fsm.io.retrainFromPm)
  requester.io.reqActive := io.fdi_lp_state_req === FDIStateReq.active && (
    pmExit || (activeReqEdgeReg &&
      ((state === FDIState.reset &&
        initSM.io.state === LinkInitState.FDI_BRINGUP) ||
        state === FDIState.retrain))
  )
  requester.io.reqLinkReset := askLinkReset
  requester.io.reqDisable := askDisable

  // Registered, so the stall logic from other modules does not add to the
  // sideband transmit valid path.
  val stallAcked = RegNext(stallDone, false.B)

  // Both sides send a PM request in Streaming (sec 10.2.9), so it stays owed
  // in PM until it is sent.
  val inPm = state === FDIState.l1 || state === FDIState.l2
  val pmOwed = RegInit(false.B)
  val pmOwedL2 = RegInit(false.B)
  val plAsksPm = plAsksL1 || plAsksL2
  when(rose(requester.io.sentReqPm) || !(state === FDIState.active || inPm)) {
    pmOwed := false.B
  }.elsewhen(state === FDIState.active && plAsksPm && stallAcked) {
    pmOwed := true.B
    pmOwedL2 := plAsksL2
  }
  requester.io.reqL1 := pmOwed && !pmOwedL2
  requester.io.reqL2 := pmOwed && pmOwedL2

  // A partner request for the same state lifts the retry floor.
  requester.io.peerAskedPm := (plAsksL1 && responder.io.peerReqL1) ||
    (plAsksL2 && responder.io.peerReqL2)
  responder.io.rxActiveReady := rxActive.io.rxActive

  // Outside Active and Active.PMNAK no data path needs a stall (sec 10.3.2).
  responder.io.fdiStallDone := !inActiveColumn || stallAcked
  responder.io.lsmState := state
  responder.io.plStateReq := io.fdi_lp_state_req

  // Responses go first, because each one retires a partner transaction.
  io.sb_snd.valid := responder.io.tx.valid || requester.io.tx.valid ||
    initSM.io.tx.valid
  io.sb_snd.bits := Mux(
    responder.io.tx.valid,
    responder.io.tx.bits,
    Mux(requester.io.tx.valid, requester.io.tx.bits, initSM.io.tx.bits)
  )
  responder.io.tx.rdy := io.sb_rdy
  requester.io.tx.rdy := io.sb_rdy && !responder.io.tx.valid
  initSM.io.tx.rdy := io.sb_rdy && !responder.io.tx.valid &&
    !requester.io.tx.valid

  // ===========================================================================
  // rx_active handshake
  // ===========================================================================
  // After this side answers LinkReset or Disable, the partner can still send.
  // So receive stays open until an RDI stall, RDI leaving Active, the 8 ms
  // bound or the partner's answer (sec 10.2.7 rule 6).
  val rspHold = RegInit(false.B)
  val rspHoldTimer = RegInit(
    0.U(math.max(1, log2Ceil(sbParams.lsmRequestTimeoutCycles)).W)
  )
  val rspHoldExpired = RegInit(false.B)
  when(
    !inActiveColumn || !rdiActive || io.rdi_pl_stallreq || rspHoldExpired ||
      requester.io.rcvdRspLinkReset || requester.io.rcvdRspDisable
  ) {
    rspHold := false.B
  }.elsewhen(answeredLinkReset || answeredDisable) {
    rspHold := true.B
  }
  when(!rspHold) {
    rspHoldTimer := 0.U
  }.elsewhen(rspHoldTimer =/= (sbParams.lsmRequestTimeoutCycles - 1).U) {
    rspHoldTimer := rspHoldTimer + 1.U
  }

  // Registered, so the wide compare does not add to a timing path. The spec
  // has no encoding for this wait (Table 9-33), so nothing is logged.
  rspHoldExpired := rspHold &&
    rspHoldTimer === (sbParams.lsmRequestTimeoutCycles - 1).U

  // A PM agreement stops receive at once, because both Protocol Layers are
  // already stalled (sec 10.2.9).
  val rxQuiesce = fsm.io.linkErrorQuiesce || fsm.io.pmAgreed ||
    ((fsm.io.linkResetAgreed || fsm.io.disabledAgreed) && !rspHold)
  rxActive.io.enable := Mux(
    inActiveColumn,
    !(rxQuiesce || retrainStatus),
    !rxQuiesce && initSM.io.rxActiveReqEnable
  )

  // sec 10.2.7 rule 1 allows the rising edge only in Reset, Retrain or Active.
  rxActive.io.stateAllowsAssert := state === FDIState.reset ||
    state === FDIState.retrain || state === FDIState.active
  rxActive.io.lpRxActiveSts := io.fdi_lp_rx_active_sts

  // ===========================================================================
  // RDI state request
  // ===========================================================================
  // The Protocol Layer may stop asking once it is stalled, so the Retrain
  // request holds until RDI reports Retrain or LinkError.
  val retrainHeld = RegInit(false.B)
  when(
    retrainStatus || io.rdi_pl_state_sts === RDIState.linkError ||
      !inActiveColumn
  ) {
    retrainHeld := false.B
  }.elsewhen(retrainRequest) {
    retrainHeld := true.B
  }
  val rdiDesired = WireDefault(RDIStateReq.nop)
  switch(state) {
    is(FDIState.reset) {
      when(initSM.io.wantRdiActive) { rdiDesired := RDIStateReq.active }
    }
    is(FDIState.active, FDIState.activePmNak) {
      // Ask only while RDI is Active. In any other RDI state the LSM is about
      // to follow it, and sec 3.5 forbids asking RDI out of Retrain.
      when(rdiActive) {
        rdiDesired := Mux(
          retrainRequest || retrainHeld,
          RDIStateReq.retrain,
          RDIStateReq.active
        )
      }
    }
    is(FDIState.retrain) {
      when(activeReqEdgeReg) { rdiDesired := RDIStateReq.active }
    }

    // RDI stays Active through FDI PM. RdiStateRequestSM adds the NOP if RDI
    // goes to Retrain (sec 10.3.3.4 rule 1).
    is(FDIState.l1, FDIState.l2) {
      rdiDesired := RDIStateReq.active
    }
    is(FDIState.linkError) {
      when(fsm.io.linkErrorRecoveryReady) { rdiDesired := RDIStateReq.active }
    }

    // Once RDI reports the state the request is NOP, so an RDI that returns to
    // Reset by itself (sec 10.3.3.5 rule 1) is not asked back.
    is(FDIState.disabled) {
      rdiDesired := Mux(fsm.io.rdiDone, RDIStateReq.nop, RDIStateReq.disabled)
    }
    is(FDIState.linkReset) {
      rdiDesired := Mux(fsm.io.rdiDone, RDIStateReq.nop, RDIStateReq.linkReset)
    }
  }

  // Table 10-4 lets an Active request take RDI out of LinkReset or Disabled,
  // so none goes out while the LSM still owes that state.
  val owesRdiDown = takeLinkReset || takeDisable || rdiLinkResetPending ||
    rdiDisablePending || fsm.io.linkResetAgreed || fsm.io.disabledAgreed
  when(
    (rdiIs(RDIState.linkReset) || rdiIs(RDIState.disabled)) && owesRdiDown
  ) {
    rdiDesired := RDIStateReq.nop
  }
  rdiRequest.io.desired := rdiDesired
  rdiRequest.io.plStateSts := io.rdi_pl_state_sts

  // ===========================================================================
  // FDI outputs
  // ===========================================================================
  // Table 10-3 keeps pl_inband_pres at 1 until FDI moves to Active or
  // LinkError, so a LinkError that is only requested does not clear it.
  val inbandPresReg = RegInit(false.B)
  when(state === FDIState.reset) {
    inbandPresReg := initSM.io.fdiPlInbandPres
  }.elsewhen(
    state === FDIState.linkError || state === FDIState.disabled ||
      state === FDIState.linkReset
  ) {
    inbandPresReg := false.B
  }.otherwise {
    inbandPresReg := true.B
  }

  io.fdi_pl_state_sts := state
  io.fdi_pl_rx_active_req := rxActive.io.plRxActiveReq
  io.fdi_pl_inband_pres := inbandPresReg
  io.rdi_lp_linkerror := fsm.io.rdiLpLinkError
  io.rdi_lp_state_req := rdiRequest.io.lpStateReq
  io.linkmgmt_stallreq := fsm.io.stallReq
  io.link_state := state
  io.param_exch_success := initSM.io.paramExchSuccess
  io.adv_cap_sent := initSM.io.advCapSent
  io.invalid_param_exchange := initSM.io.invalidParamExchange

  // ===========================================================================
  // Timeouts
  // ===========================================================================
  // Table 9-33 encoding 0011b. The partner answered our Active Request, but
  // its own did not follow within 8 ms (sec 3.2.1.2).
  require(sbParams.lsmRequestTimeoutCycles > 1)
  val activeTimerWidth =
    math.max(1, log2Ceil(sbParams.lsmRequestTimeoutCycles))
  val activeEntryTimer = RegInit(0.U(activeTimerWidth.W))
  val awaitingPeerActiveReq = requester.io.sentReqActive &&
    requester.io.rcvdRspActive && !responder.io.peerReqActive
  val activeEntryTimedOut =
    activeEntryTimer === (sbParams.lsmRequestTimeoutCycles - 1).U
  when(!awaitingPeerActiveReq || !rdiActive || requester.io.stallSeen) {
    activeEntryTimer := 0.U
  }.elsewhen(!activeEntryTimedOut) {
    activeEntryTimer := activeEntryTimer + 1.U
  }
  val activeEntryTimeout = awaitingPeerActiveReq && activeEntryTimedOut

  // Table 9-33 gives the encodings. The priority only picks which reason is
  // logged, because every reason escalates the same way.
  io.timeout.valid := initSM.io.paramExchTimeout || requester.io.timeout ||
    activeEntryTimeout
  io.timeout.bits.enc := Mux(
    initSM.io.paramExchTimeout,
    "b0001".U,
    Mux(requester.io.timeout, "b0010".U, "b0011".U)
  )
  io.timeout.bits.lsmResponse := Mux(
    requester.io.timeout,
    MuxLookup(requester.io.outstanding.asUInt, 0.U)(
      Seq(
        AdapterLSMRequest.active.asUInt -> "b001".U(3.W),
        AdapterLSMRequest.linkReset.asUInt -> "b100".U(3.W),
        AdapterLSMRequest.disable.asUInt -> "b101".U(3.W),
        AdapterLSMRequest.l1.asUInt -> "b010".U(3.W),
        AdapterLSMRequest.l2.asUInt -> "b011".U(3.W)
      )
    ),
    Mux(activeEntryTimeout, "b001".U(3.W), 0.U(3.W))
  )

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    when(
      io.sb_snd.valid && (SBMsgCompare(io.sb_snd.bits, SBM.ADVCAP_ADAPTER) ||
        SBMsgCompare(io.sb_snd.bits, SBM.LINKMGMT_ADAPTER0_REQ_ACTIVE) ||
        SBMsgCompare(io.sb_snd.bits, SBM.LINKMGMT_ADAPTER0_RSP_ACTIVE))
    ) {
      assert(
        rdiActive,
        "AdvCap and the Active negotiation may not go out before RDI Active"
      )
    }

    // sec 10.2.8 step 4 orders the rx_active handshake before Rsp.Active.
    when(
      io.sb_snd.valid &&
        SBMsgCompare(io.sb_snd.bits, SBM.LINKMGMT_ADAPTER0_RSP_ACTIVE)
    ) {
      assert(
        rxActive.io.rxActive,
        "Rsp.Active was offered before the rx_active handshake completed"
      )
    }

    // sec 10.2.9 allows a PM request on RDI only after every FDI is in PM.
    assert(
      io.rdi_lp_state_req =/= RDIStateReq.l1 &&
        io.rdi_lp_state_req =/= RDIStateReq.l2,
      "the Adapter must never request a PM state on RDI"
    )
  }
}
