/*
  Description:
    File contains the RDI requester/responder state machines.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.util._

// Used for internal transitions
object RDIStateMachineKind extends ChiselEnum {
  val none, active, pmL1, pmL2, linkReset, linkError, retrain, disabled = Value
}

object RDIStateMachineHelpers {
  def isPmReq(req: RDIStateReq.Type): Bool = {
    (req === RDIStateReq.l1) || (req === RDIStateReq.l2)
  }

  def isPmKind(kind: RDIStateMachineKind.Type): Bool = {
    (kind === RDIStateMachineKind.pmL1) || (kind === RDIStateMachineKind.pmL2)
  }

  // Spec 10.3.2: "On RDI, the Stallreq/Ack mechanism must be used when exiting
  // Active state to Retrain, PM, LinkReset or Disabled states." PM here only
  // ever answers PMNAK, which stays in Active.
  def leavesActive(kind: RDIStateMachineKind.Type): Bool = {
    (kind === RDIStateMachineKind.retrain) ||
    (kind === RDIStateMachineKind.linkReset) ||
    (kind === RDIStateMachineKind.disabled)
  }

  def isUp(state: RDIState.Type): Bool = {
    (state === RDIState.active) || (state === RDIState.activePmNak)
  }

  private def in(state: RDIState.Type, states: RDIState.Type*): Bool =
    states.map(state === _).reduce(_ || _)

  /** Whether the RDI may go from `cur` to `tgt` on a sideband exchange: Table
    * 10-4 and the state rules of 10.3.3. Everything may go to LinkError.
    * Active is entered from Reset, Retrain, LinkReset and Disabled -- LinkError
    * is left only for Reset, and locally. Retrain and PM are entered only from
    * Active. LinkReset is ignored in LinkReset and Disabled, and Disabled in
    * Disabled ("Disabled transition takes priority over LinkReset
    * transition", spec 3.5).
    */
  def allowedFrom(cur: RDIState.Type, tgt: RDIState.Type): Bool = {
    import RDIState._
    val ok = WireDefault(false.B)
    switch(tgt) {
      is(linkError) { ok := true.B }
      is(active) {
        ok := in(cur, reset, retrain, linkReset, disabled, activePmNak)
      }
      is(activePmNak) { ok := in(cur, active, activePmNak) }
      is(retrain) { ok := in(cur, active, activePmNak) }
      is(linkReset) { ok := in(cur, reset, active, activePmNak, retrain) }
      is(disabled) {
        ok := in(cur, reset, active, activePmNak, retrain, linkReset)
      }
    }
    ok
  }

  /** A completed exchange still means something in `cur`: the RDI is already
    * where it leads, or may go there from here.
    */
  def stillApplies(cur: RDIState.Type, tgt: RDIState.Type): Bool =
    (cur === tgt) || allowedFrom(cur, tgt)
}

/*
  @param linkErrorResidencyCycles
    spec 10.3.3.7: "Lower Layer must implement a minimum residency time in
    LinkError of 16 ms".
 */
class RDIStateMachine(sbParams: SidebandParams, linkErrorResidencyCycles: Int)
    extends Module {
  require(
    linkErrorResidencyCycles > 0,
    s"LinkError residency must be at least a cycle, got $linkErrorResidencyCycles"
  )

  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val lpLinkError = Input(Bool())
      val plWakeAck = Input(Bool())
      val plStateSts = Output(RDIState())
    }
    // The Physical Layer's own reasons to go to LinkError (spec 10.3.3.7's
    // "Internal LinkError conditions"): a fatal training error, or a Link that
    // was up going down.
    val internalLinkError = Input(Bool())
    // The Physical Layer has finished (re)training: its LTSM is in LINKINIT,
    // or still in ACTIVE because it never left.
    val linkTrained = Input(Bool())
    // No sideband can carry a message to the remote Physical Layer now.
    val sbLinkDown = Input(Bool())
    // pl_stallreq/lp_stallack is complete and nothing is left in flight on the
    // mainband, so the RDI may leave Active.
    val readyToLeaveActive = Input(Bool())
    // pl_clk_req/lp_clk_ack is complete.
    val clocksAcked = Input(Bool())
    // The responder holds a response that takes the RDI out of Active, and
    // needs the stall handshake done first.
    val stallNeeded = Output(Bool())
    val sidebandBusy = Output(Bool())
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })

  import RDIStateMachineHelpers._

  val currentState = RegInit(RDIState.reset)
  val prevState = RegNext(currentState, RDIState.reset)
  val resetReqObserved = RegInit(false.B)

  when(currentState === RDIState.reset) {
    when(io.rdi.lpStateReq === RDIStateReq.nop) {
      resetReqObserved := true.B
    }
  }.otherwise {
    resetReqObserved := false.B
  }

  /* Spec 10.3.3.4, Retrain->Active: "If Retrain was entered from Active, the
     lower layer begins Active Entry handshakes only after observing a
     NOP->Active transition on lp_state_req." Retrain is only ever entered from
     Active here (L1 is not implemented). An Adapter that simply holds Active
     through a Retrain the Physical Layer started on its own -- a Valid framing
     error -- would otherwise have the Link back in Active while the PHY is
     still retraining. */
  val retrainNopSeen = RegInit(false.B)
  when(currentState =/= RDIState.retrain) {
    retrainNopSeen := false.B
  }.elsewhen(io.rdi.lpStateReq === RDIStateReq.nop) {
    retrainNopSeen := true.B
  }

  /* Spec 10.1.6: "Once the Physical Layer has sent and received the
     {LinkMgmt.RDI.Rsp.Active} sideband message, it must transition pl_state_sts
     to Active". 10.3.3.1 says the same of Reset ("when the Physical Layer has
     sent and received an Active Response sideband message"), and 10.3.3.4 of
     Retrain ("Exit from Retrain on RDI requires the Active Entry handshakes to
     have completed between Physical Layers"). Out of either, neither direction
     of the handshake moves the RDI on its own: these remember the one that
     finished first. */
  val inActiveEntry =
    (currentState === RDIState.reset) || (currentState === RDIState.retrain)
  val activeRspSent = RegInit(false.B)
  val activeRspRcvd = RegInit(false.B)

  val requester = Module(new RDIStateMachineRequester(sbParams))
  requester.io.currentState := currentState
  requester.io.resetReqObserved := resetReqObserved
  requester.io.retrainNopSeen := retrainNopSeen
  requester.io.linkTrained := io.linkTrained
  requester.io.activeRspRcvd := activeRspRcvd
  requester.io.readyToLeaveActive := io.readyToLeaveActive
  requester.io.sbLinkDown := io.sbLinkDown
  requester.io.rdi.lpStateReq := io.rdi.lpStateReq
  requester.io.rdi.lpLinkError := io.rdi.lpLinkError
  requester.io.rdi.plWakeAck := io.rdi.plWakeAck
  requester.io.internalLinkError := io.internalLinkError
  requester.io.sbLaneIo <> io.requesterSbLaneIo

  val responder = Module(new RDIStateMachineResponder(sbParams))
  responder.io.currentState := currentState
  responder.io.resetReqObserved := resetReqObserved
  responder.io.retrainNopSeen := retrainNopSeen
  responder.io.linkTrained := io.linkTrained
  responder.io.readyToLeaveActive := io.readyToLeaveActive
  responder.io.rdi.lpStateReq := io.rdi.lpStateReq
  responder.io.rdi.plWakeAck := io.rdi.plWakeAck
  responder.io.sbLaneIo <> io.responderSbLaneIo

  val reqActiveDone = requester.io.transitionDone &&
    (requester.io.targetState === RDIState.active)
  val rspActiveDone = responder.io.transitionDone &&
    (responder.io.targetState === RDIState.active)
  val activeEntryDone =
    (activeRspRcvd || reqActiveDone) && (activeRspSent || rspActiveDone)
  when(!inActiveEntry || (currentState =/= prevState)) {
    activeRspSent := false.B
    activeRspRcvd := false.B
  }
  when(inActiveEntry) {
    when(reqActiveDone) { activeRspRcvd := true.B }
    when(rspActiveDone) { activeRspSent := true.B }
  }
  val partialActiveEntry = inActiveEntry && !activeEntryDone
  val requesterApplies = requester.io.transitionDone &&
    !(reqActiveDone && partialActiveEntry)
  val responderApplies = responder.io.transitionDone &&
    !(rspActiveDone && partialActiveEntry)

  /* Spec 10.3.3.7, "LinkError->Reset: The lower layer transitions to Reset due
     to an internal request to move to Reset OR (lp_state_req == Active and
     lp_linkerror = 0, while pl_state_sts == LinkError AND minimum residency
     requirements are met AND no internal condition such as an error state
     requires the lower layer to remain in LinkError)." It is local: there is
     no sideband message for it, and the remote die leaves on its own terms.
     Table 10-4 considers nothing else in LinkError. The clock handshake is
     also finished first (spec 10.1.3.2 rule 6: a Physical Layer that entered
     LinkError on its own "must wait for lp_clk_ack before performing another
     state transition"). */
  val linkErrorCycles =
    RegInit(0.U(log2Ceil(linkErrorResidencyCycles + 1).W))
  val linkErrorResidencyMet =
    linkErrorCycles === linkErrorResidencyCycles.U
  when(currentState =/= RDIState.linkError) {
    linkErrorCycles := 0.U
  }.elsewhen(!linkErrorResidencyMet) {
    linkErrorCycles := linkErrorCycles + 1.U
  }
  val leaveLinkError = (currentState === RDIState.linkError) &&
    (io.rdi.lpStateReq === RDIStateReq.active) && !io.rdi.lpLinkError &&
    !io.internalLinkError && linkErrorResidencyMet && io.clocksAcked

  when(currentState === RDIState.activePmNak && !isPmReq(io.rdi.lpStateReq)) {
    currentState := RDIState.active
  }

  // LinkError is entered on the spot (see the requester), so it can land in the
  // same cycle as a transition the other side finishes; it wins.
  val toLinkError =
    (requesterApplies && (requester.io.targetState === RDIState.linkError)) ||
      (responderApplies && (responder.io.targetState === RDIState.linkError))
  when(toLinkError) {
    currentState := RDIState.linkError
  }.elsewhen(leaveLinkError) {
    currentState := RDIState.reset
  }.elsewhen(responderApplies && requesterApplies) {
    assert(
      responder.io.targetState === requester.io.targetState,
      "FATAL: RDI requester/responder completed conflicting transitions"
    )
    currentState := responder.io.targetState
  }.elsewhen(responderApplies) {
    currentState := responder.io.targetState
  }.elsewhen(requesterApplies) {
    currentState := requester.io.targetState
  }

  assert(
    (currentState =/= RDIState.l1) && (currentState =/= RDIState.l2),
    "FATAL: PM entry is not implemented in the RDI state machine"
  )

  io.rdi.plStateSts := currentState
  io.stallNeeded := responder.io.stallNeeded || requester.io.stallNeeded
  io.sidebandBusy := requester.io.busy || responder.io.busy
}

class RDIStateMachineRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val currentState = Input(RDIState())
    val resetReqObserved = Input(Bool())
    // Retrain: lp_state_req has been NOP since the RDI entered it.
    val retrainNopSeen = Input(Bool())
    val linkTrained = Input(Bool())
    // Reset or Retrain: this die's {Req.Active} has already had its
    // {Rsp.Active}.
    val activeRspRcvd = Input(Bool())
    val readyToLeaveActive = Input(Bool())
    val sbLinkDown = Input(Bool())
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val lpLinkError = Input(Bool())
      val plWakeAck = Input(Bool())
    }
    val internalLinkError = Input(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    // An exchange that takes the RDI out of Active is in flight: the stall it
    // was started under has to stay up until the state has changed.
    val stallNeeded = Output(Bool())
    val busy = Output(Bool())
    val transitionDone = Output(Bool())
    val targetState = Output(RDIState())
  })

  object Substate extends ChiselEnum {
    val sIdle, sExchange, sNotifyLinkError = Value
  }

  import RDIStateMachineHelpers._

  val substateReg = RegInit(Substate.sIdle)
  val pendingKindReg = RegInit(RDIStateMachineKind.none)
  val pendingTargetReg = RegInit(RDIState.reset)
  // The RDI state the exchange in flight was started from.
  val startStateReg = RegInit(RDIState.reset)
  val abandonExchange = WireDefault(false.B)

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(
    0.U(5.W),
    0.U(8.W),
    0.U(8.W)
  )
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.clear := (substateReg === Substate.sIdle) || abandonExchange
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.transitionDone := false.B
  io.targetState := pendingTargetReg
  io.busy := substateReg =/= Substate.sIdle
  io.stallNeeded := (substateReg === Substate.sExchange) &&
    leavesActive(pendingKindReg) && isUp(startStateReg)

  /* Spec 10.3.3.7: "The lower layer enters LinkError state when directed by an
     lp_linkerror signal or due to Internal LinkError conditions", and "It is
     not required to complete the stallreq/ack handshake before entering this
     state." Table 10-4 considers LinkError in every state. Neither waits on the
     remote die: entry used to be the {Req.LinkError}/{Rsp.LinkError} exchange,
     so with no sideband to carry it the RDI stayed where it was, pl_trainerror
     up and pl_state_sts in Reset, and the request went out stale on the next
     training. And "Lower Layer must implement a minimum residency time in
     LinkError of 16 ms to ensure that the remote Link partner will be forced to
     enter LinkError due to timeouts (to cover for cases where the LinkError
     transition happened and sideband was not functional)" -- so the remote die
     is told when a sideband can carry it, and otherwise left to find out. */
  val linkErrorReq = io.internalLinkError || io.rdi.lpLinkError
  val enterLinkError = linkErrorReq && (io.currentState =/= RDIState.linkError)

  /* Spec 10.1.6 Step 1 has the Active Entry handshake follow "Once Physical
     Layer has completed Link training", and 4.5.3.6 makes ACTIVE "Physical
     layer initialization is complete, RDI is in Active state". So only once
     the LTSM is in LINKINIT -- asking earlier took the RDI to Active while
     every Module was still training -- and once per Reset or Retrain: the other
     half of the handshake may still be outstanding when this one finishes. */
  val mayAskForActive = io.linkTrained && !io.activeRspRcvd

  val startKind = WireDefault(RDIStateMachineKind.none)
  val startTarget = WireDefault(io.currentState)
  val startTransition = WireDefault(false.B)

  switch(io.currentState) {
    is(RDIState.reset) {
      // Spec 10.3.3.1: Reset is left only on the upper layer's NOP -> Active.
      when(
        io.rdi.lpStateReq === RDIStateReq.active &&
          io.resetReqObserved &&
          io.rdi.plWakeAck && mayAskForActive
      ) {
        startTransition := true.B
        startKind := RDIStateMachineKind.active
        startTarget := RDIState.active
      }
    }
    is(RDIState.active) {
      /* Leaving Active waits for the stall (spec 10.3.2), and 4.5.3.7.1 Step 2
         has the request go out only "After completion of stall Req/Ack
         handshake and transmitting any pending data over mainband". */
      when(io.rdi.lpStateReq === RDIStateReq.retrain) {
        startTransition := io.readyToLeaveActive
        startKind := RDIStateMachineKind.retrain
        startTarget := RDIState.retrain
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.linkReset) {
        startTransition := io.readyToLeaveActive
        startKind := RDIStateMachineKind.linkReset
        startTarget := RDIState.linkReset
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.disabled) {
        startTransition := io.readyToLeaveActive
        startKind := RDIStateMachineKind.disabled
        startTarget := RDIState.disabled
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.l1) {
        startTransition := true.B
        startKind := RDIStateMachineKind.pmL1
        startTarget := RDIState.activePmNak
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.l2) {
        startTransition := true.B
        startKind := RDIStateMachineKind.pmL2
        startTarget := RDIState.activePmNak
      }
    }
    is(RDIState.activePmNak) {
      when(io.rdi.lpStateReq === RDIStateReq.l1) {
        startTransition := true.B
        startKind := RDIStateMachineKind.pmL1
        startTarget := RDIState.activePmNak
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.l2) {
        startTransition := true.B
        startKind := RDIStateMachineKind.pmL2
        startTarget := RDIState.activePmNak
      }
    }
    is(RDIState.retrain) {
      when(
        (io.rdi.lpStateReq === RDIStateReq.active) && io.retrainNopSeen &&
          mayAskForActive
      ) {
        startTransition := true.B
        startKind := RDIStateMachineKind.active
        startTarget := RDIState.active
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.linkReset) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkReset
        startTarget := RDIState.linkReset
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.disabled) {
        startTransition := true.B
        startKind := RDIStateMachineKind.disabled
        startTarget := RDIState.disabled
      }
    }
    is(RDIState.linkReset) {
      when(io.rdi.lpStateReq === RDIStateReq.active) {
        startTransition := true.B
        startKind := RDIStateMachineKind.active
        startTarget := RDIState.active
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.disabled) {
        startTransition := true.B
        startKind := RDIStateMachineKind.disabled
        startTarget := RDIState.disabled
      }
    }
    is(RDIState.disabled) {
      when(io.rdi.lpStateReq === RDIStateReq.active) {
        startTransition := true.B
        startKind := RDIStateMachineKind.active
        startTarget := RDIState.active
      }
    }
    // LinkError is left for Reset, locally; see RDIStateMachine.
  }

  val txPattern = WireDefault(0.U(sbParams.sbNodeMsgWidth.W))
  val rxPattern = WireDefault(VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W)))

  switch(pendingKindReg) {
    is(RDIStateMachineKind.active) {
      txPattern := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_ACTIVE, "PHY", "PHY", true)
      rxPattern := SBM.LINKMGMT_RDI_RSP_ACTIVE
    }
    is(RDIStateMachineKind.pmL1) {
      txPattern := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_L1, "PHY", "PHY", true)
      rxPattern := SBM.LINKMGMT_RDI_RSP_PMNAK
    }
    is(RDIStateMachineKind.pmL2) {
      txPattern := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_L2, "PHY", "PHY", true)
      rxPattern := SBM.LINKMGMT_RDI_RSP_PMNAK
    }
    is(RDIStateMachineKind.linkReset) {
      txPattern := SBMsgCreate(
        SBM.LINKMGMT_RDI_REQ_LINKRESET,
        "PHY",
        "PHY",
        true
      )
      rxPattern := SBM.LINKMGMT_RDI_RSP_LINKRESET
    }
    is(RDIStateMachineKind.linkError) {
      txPattern := SBMsgCreate(
        SBM.LINKMGMT_RDI_REQ_LINKERROR,
        "PHY",
        "PHY",
        true
      )
      rxPattern := SBM.LINKMGMT_RDI_RSP_LINKERROR
    }
    is(RDIStateMachineKind.retrain) {
      txPattern := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_RETRAIN, "PHY", "PHY", true)
      rxPattern := SBM.LINKMGMT_RDI_RSP_RETRAIN
    }
    is(RDIStateMachineKind.disabled) {
      txPattern := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_DISABLE, "PHY", "PHY", true)
      rxPattern := SBM.LINKMGMT_RDI_RSP_DISABLE
    }
  }

  switch(substateReg) {
    is(Substate.sIdle) {
      when(startTransition) {
        pendingKindReg := startKind
        pendingTargetReg := startTarget
        startStateReg := io.currentState
        substateReg := Substate.sExchange
      }
    }
    is(Substate.sExchange) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := txPattern
      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
      sbMsgExchanger.io.rxRefBitPattern.bits := rxPattern

      /* Spec Table 10-4: an exchange the RDI has since moved past -- the
         remote die took it to LinkError, or to a state the request is ignored
         in (a Retrain crossed by a LinkReset, a LinkReset by a Disable) -- no
         longer applies; send nothing more for it, and do not apply its
         answer. Applying it put the two die in different states. So does a
         {Req.Active} not yet sent once the Link is no longer trained: it goes
         out again once it is. */
      val movedPast = (io.currentState =/= startStateReg) &&
        !stillApplies(io.currentState, pendingTargetReg)
      val untrained = (pendingKindReg === RDIStateMachineKind.active) &&
        !sbMsgExchanger.io.msgSent && !io.linkTrained
      when(movedPast || untrained) {
        sbMsgExchanger.io.req.valid := false.B
        abandonExchange := true.B
        substateReg := Substate.sIdle
        pendingKindReg := RDIStateMachineKind.none
      }.elsewhen(sbMsgExchanger.io.exchDone) {
        io.transitionDone := true.B
        io.targetState := pendingTargetReg
        substateReg := Substate.sIdle
        pendingKindReg := RDIStateMachineKind.none
      }
    }
    is(Substate.sNotifyLinkError) {
      // {Req.LinkError} to the remote die, which already happened here.
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := txPattern
      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
      sbMsgExchanger.io.rxRefBitPattern.bits := rxPattern

      /* Only for as long as it is still news. Once the sideband is gone, or the
         RDI has left LinkError, the message would reach a remote die that has
         moved on -- that is exactly the stale request this replaces. */
      val moot = io.sbLinkDown || (io.currentState =/= RDIState.linkError)
      when(moot) {
        sbMsgExchanger.io.req.valid := false.B
      }
      when(moot || sbMsgExchanger.io.exchDone) {
        substateReg := Substate.sIdle
        pendingKindReg := RDIStateMachineKind.none
      }
    }
  }

  // Last, so that it wins over whatever the exchange in flight was doing.
  when(enterLinkError) {
    io.transitionDone := true.B
    io.targetState := RDIState.linkError
    sbMsgExchanger.io.req.valid := false.B
    abandonExchange := true.B
    pendingKindReg := RDIStateMachineKind.linkError
    pendingTargetReg := RDIState.linkError
    startStateReg := RDIState.linkError
    substateReg := Mux(io.sbLinkDown, Substate.sIdle, Substate.sNotifyLinkError)
  }
}

class RDIStateMachineResponder(sbParams: SidebandParams) extends Module {
  import RDIStateMachineHelpers._

  val io = IO(new Bundle {
    val currentState = Input(RDIState())
    val resetReqObserved = Input(Bool())
    val retrainNopSeen = Input(Bool())
    val linkTrained = Input(Bool())
    val readyToLeaveActive = Input(Bool())
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool())
    }
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val stallNeeded = Output(Bool())
    val busy = Output(Bool())
    val transitionDone = Output(Bool())
    val targetState = Output(RDIState())
  })

  object Substate extends ChiselEnum {
    val sIdle, sRespond = Value
  }

  val substateReg = RegInit(Substate.sIdle)
  val pendingKindReg = RegInit(RDIStateMachineKind.none)
  val pendingTargetReg = RegInit(RDIState.reset)
  // The RDI state the request being answered arrived in.
  val pendingFromStateReg = RegInit(RDIState.reset)
  // The RDI has been in LinkError since the request arrived.
  val pendingSawLinkError = RegInit(false.B)

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(
    0.U(5.W),
    0.U(8.W),
    0.U(8.W)
  )
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.clear := substateReg === Substate.sIdle
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  io.transitionDone := false.B
  io.targetState := pendingTargetReg
  io.busy := substateReg =/= Substate.sIdle
  io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready

  val rxIsReqActive =
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_ACTIVE)
  val rxIsReqL1 =
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_L1)
  val rxIsReqL2 =
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_L2)
  val rxIsReqLinkReset =
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_LINKRESET)
  val rxIsReqLinkError =
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_LINKERROR)
  val rxIsReqRetrain =
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_RETRAIN)
  val rxIsReqDisabled =
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_DISABLE)
  val rxIsReq = io.sbLaneIo.rx.valid &&
    (rxIsReqActive || rxIsReqL1 || rxIsReqL2 || rxIsReqLinkReset ||
      rxIsReqLinkError || rxIsReqRetrain || rxIsReqDisabled)
  // Where a request that leaves the state it arrives in leads.
  val rxLeadsTo = MuxCase(
    io.currentState,
    Seq(
      rxIsReqLinkReset -> RDIState.linkReset,
      rxIsReqLinkError -> RDIState.linkError,
      rxIsReqRetrain -> RDIState.retrain,
      rxIsReqDisabled -> RDIState.disabled
    )
  )

  /* Spec 10.1.6 Step 4: "The {LinkMgmt.RDI.Rsp.Active} sideband message must
     only be sent after the Physical Layer has sampled lp_state_req = Active
     from its local RDI" -- out of Reset after the NOP 10.3.3.1 requires, and
     out of Retrain after the "NOP-> Active transition" 10.3.3.4 requires -- and
     only once this die has trained too. A request that arrives early is
     remembered rather than held, so the remote die's other requests (LinkError
     above all) still get through. One that arrives in LinkError is for the
     Reset this die will leave LinkError to; any other state change makes it
     moot. */
  val activeReqPending = RegInit(false.B)
  val activeReqFrom = RegInit(RDIState.reset)
  val mayAnswerActiveLater =
    (io.currentState === RDIState.reset) ||
      (io.currentState === RDIState.retrain) ||
      (io.currentState === RDIState.linkError)
  val activeReady = WireDefault(false.B)
  when(io.currentState === RDIState.reset) {
    activeReady := io.resetReqObserved && io.rdi.plWakeAck &&
      (io.rdi.lpStateReq === RDIStateReq.active) && io.linkTrained
  }.elsewhen(io.currentState === RDIState.retrain) {
    activeReady := (io.rdi.lpStateReq === RDIStateReq.active) &&
      io.retrainNopSeen && io.linkTrained
  }
  when(activeReqPending && (io.currentState =/= activeReqFrom)) {
    when(
      (activeReqFrom === RDIState.linkError) &&
        (io.currentState === RDIState.reset)
    ) {
      activeReqFrom := RDIState.reset
    }.otherwise {
      activeReqPending := false.B
    }
  }

  val rspPattern = WireDefault(0.U(sbParams.sbNodeMsgWidth.W))
  val canSendResponse = WireDefault(true.B)

  switch(pendingKindReg) {
    is(RDIStateMachineKind.active) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_ACTIVE, "PHY", "PHY", true)
    }
    is(RDIStateMachineKind.pmL1) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_PMNAK, "PHY", "PHY", true)
    }
    is(RDIStateMachineKind.pmL2) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_PMNAK, "PHY", "PHY", true)
    }
    is(RDIStateMachineKind.linkReset) {
      rspPattern := SBMsgCreate(
        SBM.LINKMGMT_RDI_RSP_LINKRESET,
        "PHY",
        "PHY",
        true
      )
    }
    is(RDIStateMachineKind.linkError) {
      rspPattern := SBMsgCreate(
        SBM.LINKMGMT_RDI_RSP_LINKERROR,
        "PHY",
        "PHY",
        true
      )
    }
    is(RDIStateMachineKind.retrain) {
      rspPattern := SBMsgCreate(
        SBM.LINKMGMT_RDI_RSP_RETRAIN,
        "PHY",
        "PHY",
        true
      )
    }
    is(RDIStateMachineKind.disabled) {
      rspPattern := SBMsgCreate(
        SBM.LINKMGMT_RDI_RSP_DISABLE,
        "PHY",
        "PHY",
        true
      )
    }
  }

  /* Spec 10.3.2 and 4.5.3.7.1 Step 3: "The UCIe Module Partner on receiving
     the sideband message {LinkMgmt.RDI.Req.Retrain} must transition its RDI
     state to Retrain after completion of stall Req/Ack handshake on its RDI",
     and responds "After completion of stall Req/Ack handshake and transmitting
     any pending data over mainband". The same holds for any response that
     takes the RDI out of Active. */
  val respondingOutOfActive = (substateReg === Substate.sRespond) &&
    leavesActive(pendingKindReg) && isUp(pendingFromStateReg)
  when(respondingOutOfActive) {
    canSendResponse := io.readyToLeaveActive
  }

  switch(substateReg) {
    is(Substate.sIdle) {
      io.sbLaneIo.rx.ready := false.B
      when(rxIsReq) {
        io.sbLaneIo.rx.ready := true.B
        pendingFromStateReg := io.currentState
        pendingSawLinkError := io.currentState === RDIState.linkError
        substateReg := Substate.sRespond
        when(
          (io.currentState === RDIState.linkError) &&
            !rxIsReqLinkError && !rxIsReqActive
        ) {
          /* Spec 10.3.3.7 and Table 10-4: LinkError is left only for Reset,
             and "LinkError transition takes priority over LinkReset or
             Disabled transitions" (spec 3.5). The request is moot. */
          substateReg := Substate.sIdle
        }.elsewhen(!rxIsReqActive && !stillApplies(io.currentState, rxLeadsTo)) {
          /* Nor is one the current state ignores (Table 10-4): a Retrain in
             Reset, LinkReset or Disabled, a LinkReset in Disabled. Answering it
             moved the RDI there anyway. */
          substateReg := Substate.sIdle
        }.elsewhen(rxIsReqActive) {
          pendingKindReg := RDIStateMachineKind.active
          pendingTargetReg := RDIState.active
          when(mayAnswerActiveLater) {
            activeReqPending := true.B
            activeReqFrom := io.currentState
            substateReg := Substate.sIdle
          }
        }.elsewhen(rxIsReqL1) {
          pendingKindReg := RDIStateMachineKind.pmL1
          pendingTargetReg := Mux(
            io.currentState === RDIState.active,
            RDIState.activePmNak,
            io.currentState
          )
        }.elsewhen(rxIsReqL2) {
          pendingKindReg := RDIStateMachineKind.pmL2
          pendingTargetReg := Mux(
            io.currentState === RDIState.active,
            RDIState.activePmNak,
            io.currentState
          )
        }.elsewhen(rxIsReqLinkReset) {
          pendingKindReg := RDIStateMachineKind.linkReset
          pendingTargetReg := RDIState.linkReset
        }.elsewhen(rxIsReqLinkError) {
          pendingKindReg := RDIStateMachineKind.linkError
          pendingTargetReg := RDIState.linkError
        }.elsewhen(rxIsReqRetrain) {
          pendingKindReg := RDIStateMachineKind.retrain
          pendingTargetReg := RDIState.retrain
        }.elsewhen(rxIsReqDisabled) {
          pendingKindReg := RDIStateMachineKind.disabled
          pendingTargetReg := RDIState.disabled
        }
      }.elsewhen(activeReqPending && activeReady) {
        activeReqPending := false.B
        pendingKindReg := RDIStateMachineKind.active
        pendingTargetReg := RDIState.active
        pendingFromStateReg := io.currentState
        pendingSawLinkError := false.B
        substateReg := Substate.sRespond
      }
    }
    is(Substate.sRespond) {
      /* A response the RDI has since moved past no longer applies: anything
         but LinkError once the RDI is in LinkError (whose entry needs nothing
         from the remote die), and an Active Entry another transition got ahead
         of. Answering either would pull the RDI back out of the state it is
         now in. */
      val moot = ((io.currentState =/= pendingFromStateReg) &&
        (((io.currentState === RDIState.linkError) &&
          (pendingTargetReg =/= RDIState.linkError)) ||
          (pendingKindReg === RDIStateMachineKind.active) ||
          !stillApplies(io.currentState, pendingTargetReg))) ||
        /* A LinkError request is answered in LinkError or not at all: once the
           RDI has been through LinkError and left it, the answer would take a
           Reset (or the next training) straight back there. Such a response,
           still waiting because no Module could carry it, went out in the next
           training's MBINIT. */
        (pendingSawLinkError && (io.currentState =/= RDIState.linkError))
      /* And an Active Entry answer only while this die is still trained and
         asking for Active (spec 10.1.7: it "must make sure it has finished any
         Link retraining steps before it responds with the
         {LinkMgmt.RDI.Rsp.Active}"): one that could not go out before the Link
         fell back to training waits again rather than go out in MBINIT. */
      val activeNoLongerReady =
        (pendingKindReg === RDIStateMachineKind.active) &&
          ((io.currentState === RDIState.reset) ||
            (io.currentState === RDIState.retrain)) &&
          !activeReady && !sbMsgExchanger.io.msgSent

      sbMsgExchanger.io.req.valid := canSendResponse && !moot &&
        !activeNoLongerReady
      sbMsgExchanger.io.req.bits := rspPattern

      when(io.currentState === RDIState.linkError) {
        pendingSawLinkError := true.B
      }
      when(moot) {
        substateReg := Substate.sIdle
        pendingKindReg := RDIStateMachineKind.none
      }.elsewhen(activeNoLongerReady) {
        activeReqPending := true.B
        activeReqFrom := io.currentState
        substateReg := Substate.sIdle
        pendingKindReg := RDIStateMachineKind.none
      }.elsewhen(sbMsgExchanger.io.msgSent) {
        io.transitionDone := true.B
        io.targetState := pendingTargetReg
        substateReg := Substate.sIdle
        pendingKindReg := RDIStateMachineKind.none
      }
    }
  }

  io.stallNeeded := respondingOutOfActive
}
