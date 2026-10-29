/*
  The RDI controller of the Logical PHY (sec 10.1 and sec 10.3). It drives RDI
  from the link training state.
 */

package edu.berkeley.cs.uciedigital.rdi

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.common._
import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

class RDIController(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val lpWakeReq = Input(Bool())
      val lpClkAck = Input(Bool())
      val lpStallAck = Input(Bool())
      val plWakeAck = Output(Bool())
      val plClkReq = Output(Bool())
      val plStallReq = Output(Bool())
      val plStateSts = Output(RDIState())
      val plInbandPres = Output(Bool())
    }
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val ltsmState = Input(LTState())
    val doRdiBringup = Input(Bool())
    val trainingTimeout = Input(Bool())
    val internalLinkError = Input(Bool())
    val internalRetrainRequest = Input(Bool())
    val linkErrorHold = Input(Bool())
    val internalReset = Input(Bool())
    val cfgSidebandActive = Input(Bool())
    val plPhyInRecenter = Input(Bool())
    val clocksUngatedAndStable = Input(Bool())
    val ungateClocks = Output(Bool())
    val handshakeTimedOut = Output(Bool())
    val cfgClockReady = Output(Bool())
    val remoteLinkErrorRequest = Output(Bool())
    val remoteOnlyLinkError = Output(Bool())
    val liveLinkTimeout = Output(Bool())
    val stallDrain = Output(Bool())
  })

  // ===========================================================================
  // Modules
  // ===========================================================================
  val wakeResponder = Module(new ReqAckResponder())
  val rdiStateMachine = Module(new RDIStateMachine(sbParams))
  val requestFilter = Module(new RDIRequestFilter())
  val stallRequester = Module(
    new StallRequester(sbParams.stallTimeoutCycles)
  )
  val clockRequester = Module(
    new ReqAckRequester(Some(sbParams.clockHandshakeTimeoutCycles))
  )
  val txArbiter = Module(new RRArbiter(chiselTypeOf(io.sbLaneIo.tx.bits), 2))

  // ===========================================================================
  // Wake handshake and request filter
  // ===========================================================================
  wakeResponder.io.req := io.rdi.lpWakeReq
  wakeResponder.io.ctrl.clocksUngatedAndStable := io.clocksUngatedAndStable
  io.rdi.plWakeAck := wakeResponder.io.ack
  io.ungateClocks := wakeResponder.io.ctrl.ungateClocks

  val currentState = rdiStateMachine.io.rdi.plStateSts

  requestFilter.io.plStateSts := currentState
  requestFilter.io.lpStateReq := io.rdi.lpStateReq
  val effectiveLpStateReq = requestFilter.io.effectiveStateReq

  // ===========================================================================
  // Clock request
  // ===========================================================================
  val currentStateIsResetOrPm =
    (currentState === RDIState.reset) || (currentState === RDIState.activePmNak)
  val localStateTransitionRequested = effectiveLpStateReq =/= RDIStateReq.nop
  val mustHoldClocksUntilStateChanges =
    currentStateIsResetOrPm && localStateTransitionRequested

  // sec 10.1.3 permits gating only in Reset, LinkReset, Disabled and PM.
  val gatingForbiddenState =
    (currentState === RDIState.active) ||
      (currentState === RDIState.activePmNak) ||
      (currentState === RDIState.retrain) ||
      (currentState === RDIState.linkError)

  // sec 10.1.3.2 rule 6 wants the request up before an undirected LinkError.
  val linkErrorClockNeed = io.trainingTimeout || io.internalLinkError ||
    io.linkErrorHold || stallRequester.io.ctrl.timedOut
  val keepClockRequested =
    io.doRdiBringup ||
      (io.ltsmState === LTState.sLINKINIT) ||
      io.plPhyInRecenter ||
      gatingForbiddenState ||
      mustHoldClocksUntilStateChanges ||
      io.cfgSidebandActive ||
      linkErrorClockNeed ||
      rdiStateMachine.io.sidebandBusy

  clockRequester.io.ctrl.startHandshake := keepClockRequested
  clockRequester.io.ctrl.releaseReq := !keepClockRequested
  clockRequester.io.ack := io.rdi.lpClkAck
  io.rdi.plClkReq := clockRequester.io.req

  // ===========================================================================
  // Active bring up
  // ===========================================================================
  // sec 10.3 applies every rule of Active to Active.PMNAK. Our Adapter never
  // requests PM, but another Adapter may.
  val inActiveOrPmNak = (currentState === RDIState.active) ||
    (currentState === RDIState.activePmNak)

  val inbandPresent = RegInit(false.B)
  when(
    (io.ltsmState === LTState.sRESET) || (io.ltsmState === LTState.sTRAINERROR)
  ) {
    inbandPresent := false.B
  }.elsewhen(
    (io.ltsmState === LTState.sLINKINIT) || (io.ltsmState === LTState.sACTIVE)
  ) {
    inbandPresent := true.B
  }

  val activeBringupReady =
    (wakeResponder.io.ack || !io.rdi.lpWakeReq) &&
      (clockRequester.io.ctrl.doneHandshake || io.rdi.lpClkAck || !keepClockRequested)

  // Before presence in Reset, an Active request only triggers training
  // (sec 10.1.6, sec 4.5). Out of Retrain, it waits for LINKINIT (sec 4.5.3.5).
  rdiStateMachine.io.activeEntryReady := Mux(
    currentState === RDIState.retrain,
    io.ltsmState === LTState.sLINKINIT,
    currentState =/= RDIState.reset || inbandPresent
  )
  rdiStateMachine.io.rdi.plWakeAck := activeBringupReady

  // ===========================================================================
  // Stall request
  // ===========================================================================
  // The framing error trigger lasts one cycle and the stall comes first
  // (sec 4.5.3.7.2), so hold it until RDI leaves Active.
  val internalRetrainPending = RegInit(false.B)
  when(!inActiveOrPmNak) {
    internalRetrainPending := false.B
  }.elsewhen(io.internalRetrainRequest) {
    internalRetrainPending := true.B
  }

  // A partner's Retrain, LinkReset or Disable needs the same stall
  // (sec 4.5.3.7.3 step 1).
  val holdUpperLayerStall =
    internalRetrainPending || rdiStateMachine.io.remoteExitPending ||
      (inActiveOrPmNak &&
        ((effectiveLpStateReq === RDIStateReq.retrain) ||
          (effectiveLpStateReq === RDIStateReq.linkReset) ||
          (effectiveLpStateReq === RDIStateReq.disabled) ||
          (effectiveLpStateReq === RDIStateReq.l1) ||
          (effectiveLpStateReq === RDIStateReq.l2)))

  stallRequester.io.ctrl.startStall := holdUpperLayerStall && !stallRequester.io.ctrl.isStalled

  // The Physical Layer has no layer below it to quiesce (sec 10.3.2 rule 7).
  stallRequester.io.ctrl.downstreamQuiesced := false.B

  // Note: test Active only here. Entering Active.PMNAK ends a PM entry, and
  // the stall must end with it.
  stallRequester.io.ctrl.releaseStall :=
    !holdUpperLayerStall && (currentState =/= RDIState.active)
  stallRequester.io.ack := io.rdi.lpStallAck
  io.rdi.plStallReq := stallRequester.io.req

  // sec 10.3.3.7 gives pl_trdy to drain the Adapter while a stall finishes
  // in LinkError.
  io.stallDrain := (currentState === RDIState.linkError) &&
    stallRequester.io.ctrl.awaitingAck

  // ===========================================================================
  // State machine inputs
  // ===========================================================================
  rdiStateMachine.io.rdi.lpStateReq := effectiveLpStateReq

  // sec 10.3.2 rule 3 forbids withdrawing pl_stallreq on a timer, so a timeout
  // takes a stalled Active exit to LinkError (sec 10.3.3.8).
  rdiStateMachine.io.internalLinkError :=
    io.internalLinkError || stallRequester.io.ctrl.timedOut ||
      clockRequester.io.ctrl.timedOut

  rdiStateMachine.io.internalRetrainRequest := internalRetrainPending

  // sec 10.3.2 makes the stall mandatory on every Active exit but LinkError.
  rdiStateMachine.io.mayExitActive := stallRequester.io.ctrl.isStalled
  rdiStateMachine.io.linkErrorHold := io.linkErrorHold
  rdiStateMachine.io.internalReset := io.internalReset

  // The note after sec 10.3.3.8 skips the handshake when down and not training.
  rdiStateMachine.io.linkDownLocal :=
    (io.ltsmState === LTState.sRESET) || (io.ltsmState === LTState.sTRAINERROR)
  rdiStateMachine.io.sidebandTraining := io.ltsmState === LTState.sSBINIT

  // ===========================================================================
  // Sideband
  // ===========================================================================
  val requesterSbLane = Wire(new SidebandLaneIO(sbParams))
  val responderSbLane = Wire(new SidebandLaneIO(sbParams))

  requesterSbLane.rx.valid := io.sbLaneIo.rx.valid
  requesterSbLane.rx.bits := io.sbLaneIo.rx.bits

  responderSbLane.rx.valid := io.sbLaneIo.rx.valid
  responderSbLane.rx.bits := io.sbLaneIo.rx.bits

  rdiStateMachine.io.requesterSbLaneIo <> requesterSbLane
  rdiStateMachine.io.responderSbLaneIo <> responderSbLane

  io.sbLaneIo.rx.ready := requesterSbLane.rx.ready || responderSbLane.rx.ready

  txArbiter.io.in(0) <> requesterSbLane.tx
  txArbiter.io.in(1) <> responderSbLane.tx
  io.sbLaneIo.tx <> txArbiter.io.out

  // ===========================================================================
  // Outputs
  // ===========================================================================
  io.handshakeTimedOut := rdiStateMachine.io.handshakeTimedOut
  io.remoteLinkErrorRequest := rdiStateMachine.io.remoteLinkErrorRequest
  io.remoteOnlyLinkError := rdiStateMachine.io.remoteOnlyLinkError

  // sec 4.5 escalates a Physical Layer timeout that takes an up Link down.
  io.liveLinkTimeout :=
    (inActiveOrPmNak || (currentState === RDIState.retrain)) &&
      (rdiStateMachine.io.handshakeTimedOut ||
        stallRequester.io.ctrl.timedOut)

  // pl_cfg may move only after lp_clk_ack (sec 10.1.3.2 rule 9).
  io.cfgClockReady := clockRequester.io.ctrl.doneHandshake || io.rdi.lpClkAck
  io.rdi.plStateSts := currentState
  io.rdi.plInbandPres := inbandPresent

  // ===========================================================================
  // Verification
  // ===========================================================================
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

      when(
        (currentState === RDIState.active) &&
          !RegNext(currentState === RDIState.active, false.B)
      ) {
        assert(
          activeBringupReady,
          "FATAL: RDI ACTIVE requires wake and clock prerequisites to be complete"
        )
      }
      when((currentState === RDIState.reset) && io.rdi.plInbandPres) {
        assert(
          clockRequester.io.req || clockRequester.io.ctrl.doneHandshake,
          "FATAL: pl_inband_pres assertion in RESET must be covered by the clock handshake"
        )
      }
      when(
        mustHoldClocksUntilStateChanges && RegNext(
          mustHoldClocksUntilStateChanges,
          false.B
        )
      ) {
        assert(
          clockRequester.io.req,
          "FATAL: pl_clk_req must remain asserted while leaving RESET/PM states"
        )
      }

      // Allow one cycle for the requester to leave sIDLE.
      when(gatingForbiddenState && RegNext(gatingForbiddenState, false.B)) {
        assert(
          clockRequester.io.req,
          "FATAL: pl_clk_req must stay asserted in Active, Retrain and LinkError"
        )
      }
      when(io.cfgSidebandActive && RegNext(io.cfgSidebandActive, false.B)) {
        assert(
          clockRequester.io.req,
          "FATAL: RDI cfg sideband activity must be covered by the clock handshake"
        )
      }
      assert(
        (currentState =/= RDIState.l1) && (currentState =/= RDIState.l2),
        "FATAL: PM entry is not implemented in the RDI controller"
      )
    }
  }
}
