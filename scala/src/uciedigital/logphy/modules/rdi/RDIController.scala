package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.layer.{Layer, LayerConfig, block}
import chisel3.layers.Verification
import chisel3.util._

/*
  @param linkErrorResidencyCycles
    spec 10.3.3.7's 16 ms minimum LinkError residency, in cycles.
 */
class RDIController(sbParams: SidebandParams, linkErrorResidencyCycles: Int)
    extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val lpWakeReq = Input(Bool())
      val lpClkAck = Input(Bool())
      val lpStallAck = Input(Bool())
      val lpLinkError = Input(Bool())
      val plWakeAck = Output(Bool())
      val plClkReq = Output(Bool())
      val plStallReq = Output(Bool())
      val plStateSts = Output(RDIState())
      val plInbandPres = Output(Bool())
    }
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val ltsmState = Input(LTState())
    val doRdiBringup = Input(Bool())
    val doingRdiBringup = Output(Bool())
    // The Physical Layer's own LinkError escalation: spec 10.3.3.7's "Internal
    // LinkError conditions" -- a fatal training error, or a Link that was up
    // going down.
    val internalLinkError = Input(Bool())
    val validFramingError = Input(Bool())
    // Any other request of the Physical Layer's own to retrain the Link (spec
    // 10.3.3.2, "due to an internal request to retrain the Link").
    val internalRetrainReq = Input(Bool())
    // Nothing is left in flight on the mainband transmit path.
    val txDrained = Input(Bool())
    val cfgSidebandActive = Input(Bool())
    val plPhyInRecenter = Input(Bool())
    val clocksUngatedAndStable = Input(Bool())
    /* No sideband can carry this machine's messages to the remote Physical
       Layer: the LTSM is in RESET or SBINIT (spec 4.7.1.1), or -- behind an
       MMPL -- no Module is eligible to carry them. */
    val sbLinkDown = Input(Bool())
    val ungateClocks = Output(Bool())
    /* A stall asked for in Active is still waiting for lp_stallack after the
       RDI has gone to LinkError. Spec 10.3.3.7: "If the lower layer decides to
       perform a pl_stallreq/lp_stallack handshake, it must provide pl_trdy to
       the upper layer to drain the packets" -- which may be dropped ("these
       packets could be dropped and not transmitted on the Link"). pl_trdy is
       then this, not the mainband's. */
    val stallDrain = Output(Bool())
  })

  val wakeResponder = Module(new RDIWakeHandshakeResponder())
  wakeResponder.io.rdi.lpWakeReq := io.rdi.lpWakeReq
  wakeResponder.io.ctrl.clocksUngatedAndStable := io.clocksUngatedAndStable
  io.rdi.plWakeAck := wakeResponder.io.rdi.plWakeAck
  io.ungateClocks := wakeResponder.io.ctrl.ungateClocks

  /* Spec 10.3.3.1: "The pl_state_sts is not permitted to exit Reset state
     until requested by the upper layer." This used to stand in an Active
     request of its own once the LTSM reached LINKINIT, and bring the RDI up
     whether or not the Adapter had asked. */
  val rdiStateMachine =
    Module(new RDIStateMachine(sbParams, linkErrorResidencyCycles))
  val currentState = rdiStateMachine.io.rdi.plStateSts
  val rdiUp =
    (currentState === RDIState.active) || (currentState === RDIState.activePmNak)

  /* Spec 4.5.3.7.2, PHY initiated PHY retrain: on a Valid framing error the
     Physical Layer asserts pl_error, completes the pl_stallreq/lp_stallack
     handshake, and then sends {LinkMgmt.RDI.Req.Retrain} itself -- spec
     10.3.3.4 lists "Valid framing errors are observed" among the Physical
     Layer's own triggers for Retrain, and 10.3.3.2 allows Active to Retrain
     "due to an internal request to retrain the Link". Holding only the stall,
     as this used to, left the Link stalled in Active until the Adapter happened
     to ask for Retrain. The request is pending from the error until the RDI
     has left Active, and goes to the state machine once the stall is complete
     and the clocks are up to carry the sideband exchange. Any other retrain of
     the Physical Layer's own takes the same path, so the RDI goes to Retrain
     with the LTSM instead of staying Active while it retrains underneath. */
  val phyRetrainPending = RegInit(false.B)
  val phyRetrainGo = Wire(Bool())
  val effectiveLpStateReq =
    Mux(phyRetrainGo, RDIStateReq.retrain, io.rdi.lpStateReq)

  val currentStateIsResetOrPm =
    (currentState === RDIState.reset) || (currentState === RDIState.activePmNak)
  val localStateTransitionRequested = effectiveLpStateReq =/= RDIStateReq.nop
  val mustHoldClocksUntilStateChanges =
    currentStateIsResetOrPm && localStateTransitionRequested
  val activeLifetimeClockNeed =
    (io.ltsmState === LTState.sACTIVE) && rdiStateMachine.io.sidebandBusy
  val keepClockRequested =
    io.doRdiBringup ||
      (io.ltsmState === LTState.sLINKINIT) ||
      io.plPhyInRecenter ||
      activeLifetimeClockNeed ||
      mustHoldClocksUntilStateChanges ||
      io.cfgSidebandActive ||
      rdiStateMachine.io.sidebandBusy ||
      phyRetrainPending ||
      // Spec 10.1.3: "clock gating is not permitted in LinkError state".
      (currentState === RDIState.linkError)

  val clockRequester = Module(new RDIClockHandshakeRequester())
  clockRequester.io.ctrl.startHandshake := keepClockRequested
  clockRequester.io.ctrl.releaseReq := !keepClockRequested
  clockRequester.io.rdi.lpClkAck := io.rdi.lpClkAck
  io.rdi.plClkReq := clockRequester.io.rdi.plClkReq

  val stallRequester = Module(new RDIStallRequester())
  // Only while the RDI is up: the LTSM can still be in ACTIVE for a while
  // after the RDI has gone to LinkError, and a stall started there has no
  // pl_trdy to drain against.
  val framingErrorInActive =
    rdiUp && (io.ltsmState === LTState.sACTIVE) && io.validFramingError
  val retrainRequestInActive =
    rdiUp && (io.ltsmState === LTState.sACTIVE) && io.internalRetrainReq

  when(!rdiUp) {
    phyRetrainPending := false.B
  }.elsewhen(framingErrorInActive || retrainRequestInActive) {
    phyRetrainPending := true.B
  }
  phyRetrainGo := phyRetrainPending && rdiUp &&
    stallRequester.io.ctrl.isStalled && clockRequester.io.ctrl.doneHandshake

  val activeBringupReady =
    (wakeResponder.io.rdi.plWakeAck || !io.rdi.lpWakeReq) &&
      (clockRequester.io.ctrl.doneHandshake || io.rdi.lpClkAck || !keepClockRequested)

  // Spec 10.3.2: the stall precedes every exit from Active -- the ones the
  // remote die asks for as well, which the state machine answers only once it
  // is done.
  val holdUpperLayerStall =
    framingErrorInActive || retrainRequestInActive ||
      rdiStateMachine.io.stallNeeded ||
      ((currentState === RDIState.active) &&
        ((effectiveLpStateReq === RDIStateReq.retrain) ||
          (effectiveLpStateReq === RDIStateReq.linkReset) ||
          (effectiveLpStateReq === RDIStateReq.disabled) ||
          (effectiveLpStateReq === RDIStateReq.l1) ||
          (effectiveLpStateReq === RDIStateReq.l2)))

  stallRequester.io.ctrl.startStall := holdUpperLayerStall && !stallRequester.io.ctrl.isStalled
  /* Released as soon as nothing holds it, in Active too: an exit from Active
     in progress holds it through stallNeeded until the state has changed. It
     used to be released only outside Active, so a stall that was still waiting
     for lp_stallack when the RDI went to LinkError was acked after the Link
     came back, and then held in Active for good with nothing flowing. */
  stallRequester.io.ctrl.releaseStall := !holdUpperLayerStall
  io.stallDrain := (currentState === RDIState.linkError) &&
    stallRequester.io.ctrl.waitingForAck
  stallRequester.io.rdi.lpStallAck := io.rdi.lpStallAck
  io.rdi.plStallReq := stallRequester.io.rdi.plStallReq

  rdiStateMachine.io.rdi.lpStateReq := effectiveLpStateReq
  rdiStateMachine.io.rdi.plWakeAck := activeBringupReady
  rdiStateMachine.io.rdi.lpLinkError := io.rdi.lpLinkError
  rdiStateMachine.io.internalLinkError := io.internalLinkError
  rdiStateMachine.io.linkTrained :=
    (io.ltsmState === LTState.sLINKINIT) || (io.ltsmState === LTState.sACTIVE)
  rdiStateMachine.io.sbLinkDown := io.sbLinkDown
  rdiStateMachine.io.readyToLeaveActive :=
    stallRequester.io.ctrl.isStalled && io.txDrained
  rdiStateMachine.io.clocksAcked := clockRequester.io.ctrl.doneHandshake

  val requesterSbLane = Wire(new SidebandLaneIO(sbParams))
  val responderSbLane = Wire(new SidebandLaneIO(sbParams))
  val inbandPresent = RegInit(false.B)

  requesterSbLane.rx.valid := io.sbLaneIo.rx.valid
  requesterSbLane.rx.bits := io.sbLaneIo.rx.bits

  responderSbLane.rx.valid := io.sbLaneIo.rx.valid
  responderSbLane.rx.bits := io.sbLaneIo.rx.bits

  rdiStateMachine.io.requesterSbLaneIo <> requesterSbLane
  rdiStateMachine.io.responderSbLaneIo <> responderSbLane

  block(Verification) {
    block(Verification.Assert) {
      val activeReaderClients = PopCount(
        Seq(
          requesterSbLane.rx.ready,
          responderSbLane.rx.ready
        )
      )
      assert(
        activeReaderClients <= 1.U,
        "FATAL: Multiple RDI sideband subclients asserted RX ready in the same cycle"
      )
      when(currentState === RDIState.active) {
        assert(
          activeBringupReady,
          "FATAL: RDI ACTIVE requires wake and clock prerequisites to be complete"
        )
      }
      when((currentState === RDIState.reset) && io.rdi.plInbandPres) {
        assert(
          clockRequester.io.rdi.plClkReq || clockRequester.io.ctrl.doneHandshake,
          "FATAL: pl_inband_pres assertion in RESET must be covered by the clock handshake"
        )
      }
      // The clock requester needs one cycle to leave sIDLE and raise
      // pl_clk_req, so the check starts once the handshake is under way. From
      // there the requester cannot fall back to sIDLE while the transition is
      // still pending, so a de-assertion would be a real violation.
      when(mustHoldClocksUntilStateChanges && !clockRequester.io.ctrl.inIdle) {
        assert(
          clockRequester.io.rdi.plClkReq,
          "FATAL: pl_clk_req must remain asserted while leaving RESET/PM states"
        )
      }
      // Same one-cycle allowance as for cfg below: LinkError is entered on the
      // spot, and the notice to the remote die that follows can be the first
      // thing to need clocks.
      when(rdiStateMachine.io.sidebandBusy && !clockRequester.io.ctrl.inIdle) {
        assert(
          clockRequester.io.rdi.plClkReq,
          "FATAL: Sideband traffic to the Adapter must be covered by the clock handshake"
        )
      }
      // Same one-cycle allowance as above: cfg activity feeds
      // keepClockRequested, and the requester needs a cycle to leave sIDLE and
      // raise pl_clk_req. Once it has left, a de-assertion is a real violation.
      when(io.cfgSidebandActive && !clockRequester.io.ctrl.inIdle) {
        assert(
          clockRequester.io.rdi.plClkReq,
          "FATAL: RDI cfg sideband activity must be covered by the clock handshake"
        )
      }
      when(stallRequester.io.ctrl.startStall && io.validFramingError) {
        assert(
          io.ltsmState === LTState.sACTIVE,
          "FATAL: Framing-error-triggered stall is only valid while LT is ACTIVE"
        )
      }
      assert(
        (currentState =/= RDIState.l1) && (currentState =/= RDIState.l2),
        "FATAL: PM entry is not implemented in the RDI controller"
      )
    }
  }

  io.sbLaneIo.rx.ready := requesterSbLane.rx.ready || responderSbLane.rx.ready

  val txArbiter = Module(new RRArbiter(chiselTypeOf(io.sbLaneIo.tx.bits), 2))
  txArbiter.io.in(0) <> requesterSbLane.tx
  txArbiter.io.in(1) <> responderSbLane.tx
  io.sbLaneIo.tx <> txArbiter.io.out

  when(
    (io.ltsmState === LTState.sRESET) || (io.ltsmState === LTState.sTRAINERROR)
  ) {
    inbandPresent := false.B
  }.elsewhen(
    (io.ltsmState === LTState.sLINKINIT) || (io.ltsmState === LTState.sACTIVE)
  ) {
    inbandPresent := true.B
  }

  io.rdi.plStateSts := currentState
  io.rdi.plInbandPres := inbandPresent
  io.doingRdiBringup := (currentState === RDIState.reset) &&
    (effectiveLpStateReq === RDIStateReq.active)
}
