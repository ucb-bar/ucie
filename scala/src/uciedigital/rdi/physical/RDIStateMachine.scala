/*
  The RDI state machine of the Logical PHY. It implements sec 10.1.6 (Active
  entry), sec 10.1.7 (PM), sec 10.3.2 (stall) and sec 10.3.3 (state rules and
  the 8 ms handshake timeout).
 */

package edu.berkeley.cs.uciedigital.rdi

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.common._
import chisel3._
import chisel3.util._

object RDIStateMachineKind extends ChiselEnum {
  val none, active, pmL1, pmL2, linkReset, linkError, retrain, disabled = Value
}

object RDIStateMachineHelpers {
  def isPmKind(kind: RDIStateMachineKind.Type): Bool = {
    (kind === RDIStateMachineKind.pmL1) || (kind === RDIStateMachineKind.pmL2)
  }
}

class RDIStateMachine(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool())
      val plStateSts = Output(RDIState())
    }
    val internalLinkError = Input(Bool())
    val internalRetrainRequest = Input(Bool())
    val mayExitActive = Input(Bool())
    val activeEntryReady = Input(Bool())
    val remoteExitPending = Output(Bool())
    val linkErrorHold = Input(Bool())
    val internalReset = Input(Bool())
    val linkDownLocal = Input(Bool())
    val sidebandTraining = Input(Bool())
    val sidebandBusy = Output(Bool())
    val handshakeTimedOut = Output(Bool())
    val remoteLinkErrorRequest = Output(Bool())
    val remoteOnlyLinkError = Output(Bool())
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })

  import RDIStateMachineHelpers._

  val currentState = RegInit(RDIState.reset)

  // ===========================================================================
  // Modules and link error events
  // ===========================================================================
  val requester = Module(new RDIStateMachineRequester(sbParams))
  val responder = Module(new RDIStateMachineResponder(sbParams))

  val internalLinkErrorPrev = RegNext(io.internalLinkError, false.B)
  val linkErrorHoldPrev = RegNext(io.linkErrorHold, false.B)

  val handshakeTimedOut = Wire(Bool())
  val handshakeTimedOutPrev = RegNext(handshakeTimedOut, false.B)
  val localLinkErrorEvent =
    (io.internalLinkError && !internalLinkErrorPrev) ||
      (io.linkErrorHold && !linkErrorHoldPrev) ||
      (handshakeTimedOut && !handshakeTimedOutPrev)
  val localLinkErrorCondition =
    io.internalLinkError || io.linkErrorHold || handshakeTimedOut

  val linkErrorCountWidth = math.max(
    1,
    log2Ceil(sbParams.linkErrorMinResidencyCycles)
  )
  val linkErrorCount = RegInit(0.U(linkErrorCountWidth.W))
  val linkErrorResidencyDone = if (sbParams.linkErrorMinResidencyCycles == 1) {
    true.B
  } else {
    linkErrorCount === (sbParams.linkErrorMinResidencyCycles - 1).U
  }

  when(currentState =/= RDIState.linkError) {
    linkErrorCount := 0.U
  }.elsewhen(!linkErrorResidencyDone) {
    linkErrorCount := linkErrorCount + 1.U
  }

  // ===========================================================================
  // Requester and responder
  // ===========================================================================
  requester.io.currentState := currentState
  requester.io.rdi.lpStateReq := io.rdi.lpStateReq
  requester.io.rdi.plWakeAck := io.rdi.plWakeAck

  // sec 10.3.3.8's request moves a partner that is still up, so it goes only
  // from Active or Retrain. From a down state, LinkError is local.
  val rdiUp = (currentState === RDIState.active) ||
    (currentState === RDIState.activePmNak) ||
    (currentState === RDIState.retrain)
  val localOnlyLinkError = localLinkErrorEvent && !rdiUp &&
    (currentState =/= RDIState.linkError)
  requester.io.linkErrorRequest := localLinkErrorEvent && rdiUp

  requester.io.internalRetrainRequest := io.internalRetrainRequest

  requester.io.mayExitActive := io.mayExitActive
  requester.io.activeEntryReady := io.activeEntryReady
  requester.io.linkDownLocal := io.linkDownLocal
  requester.io.sidebandTraining := io.sidebandTraining
  requester.io.sbLaneIo <> io.requesterSbLaneIo
  handshakeTimedOut := requester.io.handshakeTimedOut

  // sec 9.5.3.35 bit 9 reports this timeout. The bit excludes Link Training
  // messages, so the training residency does not feed it.
  io.handshakeTimedOut := handshakeTimedOut

  responder.io.currentState := currentState
  responder.io.rdi.lpStateReq := io.rdi.lpStateReq
  responder.io.rdi.plWakeAck := io.rdi.plWakeAck
  responder.io.mayExitActive := io.mayExitActive
  responder.io.activeEntryReady := io.activeEntryReady
  responder.io.sbLaneIo <> io.responderSbLaneIo
  io.remoteExitPending := responder.io.exitFromActivePending

  // ===========================================================================
  // Active entry and Link down exit
  // ===========================================================================
  // RDI enters Active when Rsp.Active is sent and received (sec 10.1.6
  // step 5), so remember our sent response until our request completes.
  val responderActiveDone = responder.io.transitionDone &&
    (responder.io.targetState === RDIState.active)
  val responderMoves = responder.io.transitionDone && !responderActiveDone
  val rspActiveSent = RegInit(false.B)
  when(
    (currentState =/= RDIState.reset) && (currentState =/= RDIState.retrain)
  ) {
    rspActiveSent := false.B
  }.elsewhen(responderActiveDone) {
    rspActiveSent := true.B
  }
  requester.io.rspActiveSent := rspActiveSent

  // sec 10.3.3.5 rule 1 and sec 10.3.3.6 take an Active request to Reset once
  // the LTSM was down. No sideband message exists for this (Table 7-8).
  val inLinkDown = (currentState === RDIState.linkReset) ||
    (currentState === RDIState.disabled)

  // Hold this, because the partner's SBINIT pattern (sec 4.5) can take the
  // LTSM out of RESET before the Adapter asks for Active.
  val linkDownDone = RegInit(false.B)
  when(!inLinkDown) {
    linkDownDone := false.B
  }.elsewhen(io.internalReset) {
    linkDownDone := true.B
  }
  val linkDownExit = inLinkDown &&
    (io.rdi.lpStateReq === RDIStateReq.active) &&
    !requester.io.busy && (io.internalReset || linkDownDone)

  // ===========================================================================
  // LinkError entry and exit
  // ===========================================================================
  val linkErrorEntry = currentState =/= RDIState.linkError &&
    (localLinkErrorCondition || responder.io.linkErrorRequestSeen)

  // sec 9.5.3.35 bit 10 counts the partner's Req.LinkError, one cycle.
  io.remoteLinkErrorRequest := responder.io.linkErrorRequestSeen

  // Set when the partner's request alone took RDI to LinkError. Only then can
  // software clearing Error Log 1 bit 10 end LinkError (sec 10.3.3.7).
  val remoteOnlyLinkError = RegInit(false.B)
  when(linkErrorEntry) {
    remoteOnlyLinkError :=
      responder.io.linkErrorRequestSeen && !localLinkErrorCondition
  }.elsewhen(
    (currentState =/= RDIState.linkError) || localLinkErrorCondition
  ) {
    remoteOnlyLinkError := false.B
  }
  io.remoteOnlyLinkError := remoteOnlyLinkError

  // sec 10.3.3.7 lets LinkError end only when no internal condition needs it.
  val activeExitRequested = io.rdi.lpStateReq === RDIStateReq.active &&
    !io.linkErrorHold && !io.internalLinkError

  // Table 10-1 holds LinkError while lp_linkerror is 1.
  val linkErrorExit = currentState === RDIState.linkError &&
    linkErrorResidencyDone &&
    !localLinkErrorCondition && !responder.io.linkErrorRequestSeen &&
    (io.internalReset ||
      (activeExitRequested && !responder.io.linkErrorResponsePending))
  requester.io.abort := linkErrorExit || localOnlyLinkError ||
    (responder.io.linkErrorRequestSeen && !localLinkErrorEvent &&
      !requester.io.linkErrorExchangePending)
  responder.io.abort := linkErrorExit ||
    (localLinkErrorEvent && !responder.io.linkErrorRequestSeen &&
      !responder.io.linkErrorResponsePending)

  // ===========================================================================
  // State update
  // ===========================================================================
  when(linkErrorEntry) {
    currentState := RDIState.linkError
  }.elsewhen(linkErrorExit) {
    currentState := RDIState.reset
  }.elsewhen(currentState =/= RDIState.linkError) {
    // Footnote b leaves Active.PMNAK on the Active request.
    when(
      currentState === RDIState.activePmNak &&
        io.rdi.lpStateReq === RDIStateReq.active
    ) {
      currentState := RDIState.active
    }
    when(linkDownExit) {
      currentState := RDIState.reset
    }

    when(responderMoves && requester.io.transitionDone) {
      assert(
        responder.io.targetState === requester.io.targetState,
        "FATAL: RDI requester/responder completed conflicting transitions"
      )
      currentState := responder.io.targetState
    }.elsewhen(responderMoves) {
      currentState := responder.io.targetState
    }.elsewhen(requester.io.transitionDone) {
      currentState := requester.io.targetState
    }
  }

  assert(
    (currentState =/= RDIState.l1) && (currentState =/= RDIState.l2),
    "FATAL: PM entry is not implemented in the RDI state machine"
  )

  io.rdi.plStateSts := currentState
  io.sidebandBusy := requester.io.busy || responder.io.busy
}

class RDIStateMachineRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val currentState = Input(RDIState())
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool())
    }
    val activeEntryReady = Input(Bool())
    val linkDownLocal = Input(Bool())
    val sidebandTraining = Input(Bool())
    val linkErrorRequest = Input(Bool())
    val internalRetrainRequest = Input(Bool())
    val mayExitActive = Input(Bool())
    val rspActiveSent = Input(Bool())
    val abort = Input(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val busy = Output(Bool())
    val linkErrorExchangePending = Output(Bool())
    val transitionDone = Output(Bool())
    val targetState = Output(RDIState())
    val handshakeTimedOut = Output(Bool())
  })

  object Substate extends ChiselEnum {
    val sIdle, sExchange = Value
  }

  import RDIStateMachineHelpers._

  val substateReg = RegInit(Substate.sIdle)
  val pendingKindReg = RegInit(RDIStateMachineKind.none)
  val pendingTargetReg = RegInit(RDIState.reset)

  // ===========================================================================
  // Modules and exchange status
  // ===========================================================================
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))
  SidebandMessageExchanger.tieOff(sbMsgExchanger)

  // A response with MsgInfo FFFFh is a Stall (sec 7.1). SBMsgCompare ignores
  // MsgInfo, so decode it here.
  val respIsStall = sbMsgExchanger.io.resp.valid &&
    SBMsgField.msgInfo(sbMsgExchanger.io.resp.bits) === "hFFFF".U
  val stallPending = RegInit(false.B)

  sbMsgExchanger.io.clear := substateReg === Substate.sIdle ||
    io.abort || io.linkErrorRequest || stallPending
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.transitionDone := false.B
  io.targetState := pendingTargetReg
  io.busy := substateReg =/= Substate.sIdle
  io.linkErrorExchangePending := substateReg === Substate.sExchange &&
    pendingKindReg === RDIStateMachineKind.linkError

  // ===========================================================================
  // PM retry
  // ===========================================================================
  // sec 10.1.7 allows a PM retry at least 2 us after a PMNAK. The release on
  // a partner PM Request is not taken, because it only shortens the wait.
  val pmNakReceived = substateReg === Substate.sExchange &&
    isPmKind(pendingKindReg) &&
    sbMsgExchanger.io.exchDone && !stallPending && !respIsStall
  val pmRetryCountWidth = log2Ceil(sbParams.pmRetryFloorCycles)

  // Starts expired, so the first PM request after reset is not delayed.
  val pmRetryCount = RegInit(
    (sbParams.pmRetryFloorCycles - 1).U(pmRetryCountWidth.W)
  )
  val pmRetryBlocked = pmRetryCount =/= (sbParams.pmRetryFloorCycles - 1).U
  when(pmNakReceived) {
    pmRetryCount := 0.U
  }.elsewhen(pmRetryBlocked) {
    pmRetryCount := pmRetryCount + 1.U
  }

  // ===========================================================================
  // Transition start
  // ===========================================================================
  val startKind = WireDefault(RDIStateMachineKind.none)
  val startTarget = WireDefault(io.currentState)
  val startTransition = WireDefault(false.B)

  switch(io.currentState) {
    is(RDIState.reset) {
      when(
        io.rdi.lpStateReq === RDIStateReq.active && io.rdi.plWakeAck &&
          io.activeEntryReady
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

    // sec 10.3 applies every rule of Active to Active.PMNAK.
    is(RDIState.active, RDIState.activePmNak) {
      // The local trigger comes first, so a framing error never waits for a
      // request. sec 4.5.3.7.2 has no wait for pending receive data.
      when(
        io.mayExitActive &&
          (io.internalRetrainRequest ||
            io.rdi.lpStateReq === RDIStateReq.retrain)
      ) {
        startTransition := true.B
        startKind := RDIStateMachineKind.retrain
        startTarget := RDIState.retrain
      }.elsewhen(
        io.mayExitActive && io.rdi.lpStateReq === RDIStateReq.linkReset
      ) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkReset
        startTarget := RDIState.linkReset
      }.elsewhen(
        io.mayExitActive && io.rdi.lpStateReq === RDIStateReq.disabled
      ) {
        startTransition := true.B
        startKind := RDIStateMachineKind.disabled
        startTarget := RDIState.disabled
      }.elsewhen(
        io.mayExitActive && !pmRetryBlocked &&
          io.rdi.lpStateReq === RDIStateReq.l1
      ) {
        startTransition := true.B
        startKind := RDIStateMachineKind.pmL1
        startTarget := RDIState.activePmNak
      }.elsewhen(
        io.mayExitActive && !pmRetryBlocked &&
          io.rdi.lpStateReq === RDIStateReq.l2
      ) {
        startTransition := true.B
        startKind := RDIStateMachineKind.pmL2
        startTarget := RDIState.activePmNak
      }
    }
    is(RDIState.retrain) {
      when(io.rdi.lpStateReq === RDIStateReq.active && io.activeEntryReady) {
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
      // The parent moves to Reset locally on an Active request.
      when(io.rdi.lpStateReq === RDIStateReq.disabled) {
        startTransition := true.B
        startKind := RDIStateMachineKind.disabled
        startTarget := RDIState.disabled
      }
    }
    is(RDIState.linkError) {}
  }

  // ===========================================================================
  // Message patterns
  // ===========================================================================
  val txPattern = WireDefault(0.U(sbParams.sbNodeMsgWidth.W))
  val rxPattern = WireDefault(VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W)))

  switch(pendingKindReg) {
    is(RDIStateMachineKind.active) {
      txPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_REQ_ACTIVE)
      rxPattern := SBM.LINKMGMT_RDI_RSP_ACTIVE
    }
    is(RDIStateMachineKind.pmL1) {
      txPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_REQ_L1)
      rxPattern := SBM.LINKMGMT_RDI_RSP_PMNAK
    }
    is(RDIStateMachineKind.pmL2) {
      txPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_REQ_L2)
      rxPattern := SBM.LINKMGMT_RDI_RSP_PMNAK
    }
    is(RDIStateMachineKind.linkReset) {
      txPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_REQ_LINKRESET)
      rxPattern := SBM.LINKMGMT_RDI_RSP_LINKRESET
    }
    is(RDIStateMachineKind.linkError) {
      txPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_REQ_LINKERROR)
      rxPattern := SBM.LINKMGMT_RDI_RSP_LINKERROR
    }
    is(RDIStateMachineKind.retrain) {
      txPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_REQ_RETRAIN)
      rxPattern := SBM.LINKMGMT_RDI_RSP_RETRAIN
    }
    is(RDIStateMachineKind.disabled) {
      txPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_REQ_DISABLE)
      rxPattern := SBM.LINKMGMT_RDI_RSP_DISABLE
    }
  }

  // ===========================================================================
  // Handshake timeout
  // ===========================================================================
  // sec 10.3.3.8 bounds the handshake at 8 ms. The count restarts with each
  // exchange, also on a LinkError preempt that skips sIdle. The LinkError
  // exchange has no bound, because the bound exists to reach LinkError.
  val inDown = (io.currentState === RDIState.reset) ||
    (io.currentState === RDIState.linkReset)
  val isDownKind = (pendingKindReg === RDIStateMachineKind.linkReset) ||
    (pendingKindReg === RDIStateMachineKind.disabled)

  // A down Link that is not training needs no partner (note in sec 10.3.3.8).
  val completeLocally = inDown && isDownKind && io.linkDownLocal

  // Active also waits for our own Rsp.Active (sec 10.1.6 step 5). The bound
  // restarts on completion, so it includes this wait.
  val exchangeComplete = completeLocally ||
    (sbMsgExchanger.io.exchDone && !stallPending && !respIsStall &&
      ((pendingKindReg =/= RDIStateMachineKind.active) || io.rspActiveSent))
  val exchangeRestart = io.abort || io.linkErrorRequest ||
    (substateReg === Substate.sIdle && startTransition) ||
    (substateReg === Substate.sExchange && exchangeComplete)
  val exchangeBounded = substateReg === Substate.sExchange &&
    pendingKindReg =/= RDIStateMachineKind.linkError
  val handshakeCountWidth =
    math.max(1, log2Ceil(sbParams.rdiHandshakeTimeoutCycles))
  val handshakeCounter = RegInit(0.U(handshakeCountWidth.W))
  val handshakeExpired =
    handshakeCounter === (sbParams.rdiHandshakeTimeoutCycles - 1).U
  when(exchangeRestart || !exchangeBounded || respIsStall) {
    handshakeCounter := 0.U
  }.elsewhen(!handshakeExpired) {
    handshakeCounter := handshakeCounter + 1.U
  }

  // A Stall clears the exchanger on the next cycle, so the request goes out
  // again.
  when(respIsStall) {
    stallPending := true.B
  }.elsewhen(stallPending) {
    stallPending := false.B
  }
  io.handshakeTimedOut := exchangeBounded && handshakeExpired

  // ===========================================================================
  // State update
  // ===========================================================================
  when(io.abort) {
    substateReg := Substate.sIdle
    pendingKindReg := RDIStateMachineKind.none
  }.elsewhen(io.linkErrorRequest) {
    pendingKindReg := RDIStateMachineKind.linkError
    pendingTargetReg := RDIState.linkError
    substateReg := Substate.sExchange
  }.otherwise {
    switch(substateReg) {
      is(Substate.sIdle) {
        val isDownStart = (startKind === RDIStateMachineKind.linkReset) ||
          (startKind === RDIStateMachineKind.disabled)
        val holdDown = inDown && io.sidebandTraining && isDownStart

        // The sideband is RAW in SBINIT, so wait (note in sec 10.3.3.8).
        when(startTransition && !holdDown) {
          pendingKindReg := startKind
          pendingTargetReg := startTarget
          substateReg := Substate.sExchange
        }
      }
      is(Substate.sExchange) {
        sbMsgExchanger.io.req.valid := !completeLocally
        sbMsgExchanger.io.req.bits := txPattern
        sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
        sbMsgExchanger.io.rxRefBitPattern.bits := rxPattern

        when(exchangeComplete) {
          io.transitionDone := true.B
          io.targetState := pendingTargetReg
          substateReg := Substate.sIdle
          pendingKindReg := RDIStateMachineKind.none
        }
      }
    }
  }
}

class RDIStateMachineResponder(sbParams: SidebandParams) extends Module {
  import RDIStateMachineHelpers._

  val io = IO(new Bundle {
    val currentState = Input(RDIState())
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool())
    }
    val abort = Input(Bool())
    val activeEntryReady = Input(Bool())
    val mayExitActive = Input(Bool())
    val exitFromActivePending = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val busy = Output(Bool())
    val linkErrorResponsePending = Output(Bool())
    val transitionDone = Output(Bool())
    val targetState = Output(RDIState())
    val linkErrorRequestSeen = Output(Bool())
  })

  object Substate extends ChiselEnum {
    val sIdle, sRespond = Value
  }

  val substateReg = RegInit(Substate.sIdle)
  val pendingKindReg = RegInit(RDIStateMachineKind.none)
  val pendingTargetReg = RegInit(RDIState.reset)

  // ===========================================================================
  // Exit from Active
  // ===========================================================================
  val inActiveOrPmNak = io.currentState === RDIState.active ||
    io.currentState === RDIState.activePmNak
  val exitKind = pendingKindReg === RDIStateMachineKind.retrain ||
    pendingKindReg === RDIStateMachineKind.linkReset ||
    pendingKindReg === RDIStateMachineKind.disabled

  io.exitFromActivePending :=
    substateReg === Substate.sRespond && exitKind && inActiveOrPmNak

  // sec 4.5.3.7.3 step 1 and sec 10.3.2 put the stall before every exit from
  // Active, so a partner LinkReset or Disable waits too.
  val exitGranted = !inActiveOrPmNak || io.mayExitActive

  // ===========================================================================
  // Modules
  // ===========================================================================
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))
  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  val preemptForLinkError = WireDefault(false.B)
  sbMsgExchanger.io.clear := substateReg === Substate.sIdle || io.abort ||
    preemptForLinkError
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  io.transitionDone := false.B
  io.targetState := pendingTargetReg
  io.busy := substateReg =/= Substate.sIdle
  io.linkErrorResponsePending := substateReg === Substate.sRespond &&
    pendingKindReg === RDIStateMachineKind.linkError
  io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready

  // ===========================================================================
  // Request decode
  // ===========================================================================
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
  io.linkErrorRequestSeen := io.sbLaneIo.rx.valid && rxIsReqLinkError
  preemptForLinkError := io.linkErrorRequestSeen &&
    !io.linkErrorResponsePending

  // sec 10.1.6 step 4 permits Rsp.Active only after sampling lp_state_req ==
  // Active on this RDI.
  val canAcceptActiveFromReset =
    (io.currentState === RDIState.reset) &&
      io.rdi.plWakeAck && io.activeEntryReady &&
      (io.rdi.lpStateReq === RDIStateReq.active)

  // ===========================================================================
  // PM response
  // ===========================================================================
  // sec 10.1.7 PM response bounds. PM entry is not implemented, so the answer
  // is always PMNAK and the bounds only decide when it goes out.
  val pmResponsePending = substateReg === Substate.sRespond &&
    isPmKind(pendingKindReg)
  val pmNakCondition = io.currentState === RDIState.active &&
    io.rdi.lpStateReq === RDIStateReq.active

  // The 1 us condition must hold without a break, so a flag tracks it. If it
  // breaks, only the 2 us deadline forces an answer.
  val pmNakConditionHeld = RegInit(false.B)
  when(pmResponsePending) {
    pmNakConditionHeld := pmNakConditionHeld && pmNakCondition
  }.otherwise {
    pmNakConditionHeld := pmNakCondition
  }

  // The count does not restart when the condition breaks, so the deadline
  // holds.
  val pmResponseCount = RegInit(
    0.U(log2Ceil(sbParams.pmResponseDeadlineCycles).W)
  )
  val pmDeadlineMet =
    pmResponseCount === (sbParams.pmResponseDeadlineCycles - 1).U
  val pmFloorMet = pmResponseCount >= (sbParams.pmNakFloorCycles - 1).U
  when(!pmResponsePending) {
    pmResponseCount := 0.U
  }.elsewhen(!pmDeadlineMet) {
    pmResponseCount := pmResponseCount + 1.U
  }

  // ===========================================================================
  // Response
  // ===========================================================================
  val rspPattern = WireDefault(0.U(sbParams.sbNodeMsgWidth.W))
  val canSendResponse = WireDefault(true.B)

  switch(pendingKindReg) {
    is(RDIStateMachineKind.active) {
      rspPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_RSP_ACTIVE)
      when(io.currentState === RDIState.reset) {
        canSendResponse := canAcceptActiveFromReset
      }.elsewhen(io.currentState === RDIState.retrain) {
        // The same step 4 out of Retrain, once retraining is done.
        canSendResponse := io.activeEntryReady &&
          (io.rdi.lpStateReq === RDIStateReq.active)
      }.elsewhen(
        (io.currentState === RDIState.linkReset) ||
          (io.currentState === RDIState.disabled)
      ) {
        // Figure 10-6 has no transition from these states to Active.
        canSendResponse := false.B
      }
    }
    is(RDIStateMachineKind.pmL1) {
      rspPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_RSP_PMNAK)
      canSendResponse := (pmNakConditionHeld && pmFloorMet) || pmDeadlineMet
    }
    is(RDIStateMachineKind.pmL2) {
      rspPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_RSP_PMNAK)
      canSendResponse := (pmNakConditionHeld && pmFloorMet) || pmDeadlineMet
    }
    is(RDIStateMachineKind.linkReset) {
      rspPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_RSP_LINKRESET)
      canSendResponse := exitGranted
    }
    is(RDIStateMachineKind.linkError) {
      rspPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_RSP_LINKERROR)
    }
    is(RDIStateMachineKind.retrain) {
      rspPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_RSP_RETRAIN)

      // sec 4.5.3.7.1 step 3 also needs our mainband data sent and none left
      // in the Receiver. No path holds a word after its last beat, so the stall
      // ack (sec 10.3.2 rule 6) and the partner's step 2 cover this.
      canSendResponse := exitGranted
    }
    is(RDIStateMachineKind.disabled) {
      rspPattern := PhyLinkMsg.create(SBM.LINKMGMT_RDI_RSP_DISABLE)
      canSendResponse := exitGranted
    }
  }

  // ===========================================================================
  // State update
  // ===========================================================================
  when(io.abort) {
    substateReg := Substate.sIdle
    pendingKindReg := RDIStateMachineKind.none
  }.elsewhen(preemptForLinkError) {
    io.sbLaneIo.rx.ready := true.B
    pendingKindReg := RDIStateMachineKind.linkError
    pendingTargetReg := RDIState.linkError
    substateReg := Substate.sRespond
  }.otherwise {
    switch(substateReg) {
      is(Substate.sIdle) {
        io.sbLaneIo.rx.ready := false.B
        when(
          io.sbLaneIo.rx.valid &&
            io.currentState =/= RDIState.linkError
        ) {
          when(rxIsReqActive) {
            io.sbLaneIo.rx.ready := true.B
            pendingKindReg := RDIStateMachineKind.active
            pendingTargetReg := RDIState.active
            substateReg := Substate.sRespond
          }.elsewhen(rxIsReqL1) {
            io.sbLaneIo.rx.ready := true.B
            pendingKindReg := RDIStateMachineKind.pmL1
            substateReg := Substate.sRespond
          }.elsewhen(rxIsReqL2) {
            io.sbLaneIo.rx.ready := true.B
            pendingKindReg := RDIStateMachineKind.pmL2
            substateReg := Substate.sRespond
          }.elsewhen(rxIsReqLinkReset) {
            io.sbLaneIo.rx.ready := true.B
            pendingKindReg := RDIStateMachineKind.linkReset
            pendingTargetReg := RDIState.linkReset
            substateReg := Substate.sRespond
          }.elsewhen(rxIsReqLinkError) {
            io.sbLaneIo.rx.ready := true.B
            pendingKindReg := RDIStateMachineKind.linkError
            pendingTargetReg := RDIState.linkError
            substateReg := Substate.sRespond
          }.elsewhen(rxIsReqRetrain) {
            io.sbLaneIo.rx.ready := true.B
            pendingKindReg := RDIStateMachineKind.retrain
            pendingTargetReg := RDIState.retrain
            substateReg := Substate.sRespond
          }.elsewhen(rxIsReqDisabled) {
            io.sbLaneIo.rx.ready := true.B
            pendingKindReg := RDIStateMachineKind.disabled
            pendingTargetReg := RDIState.disabled
            substateReg := Substate.sRespond
          }
        }
      }
      is(Substate.sRespond) {
        sbMsgExchanger.io.req.valid := canSendResponse
        sbMsgExchanger.io.req.bits := rspPattern

        when(sbMsgExchanger.io.msgSent) {
          // Sending a PMNAK changes no state (sec 10.1.7).
          io.transitionDone := !isPmKind(pendingKindReg)
          io.targetState := pendingTargetReg
          substateReg := Substate.sIdle
          pendingKindReg := RDIStateMachineKind.none
        }
      }
    }
  }

  when(io.currentState === RDIState.linkError && io.sbLaneIo.tx.valid) {
    assert(
      io.linkErrorResponsePending,
      "FATAL: Only a LinkError response may be sent while RDI is in LinkError"
    )
  }
}
