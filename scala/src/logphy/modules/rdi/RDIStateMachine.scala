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
}

class RDIStateMachine(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool())
      val plStateSts = Output(RDIState())
    }
    val trainingTimeout = Input(Bool())
    val sidebandBusy = Output(Bool())
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })

  import RDIStateMachineHelpers._

  val currentState = RegInit(RDIState.reset)
  val resetReqObserved = RegInit(false.B)

  when(currentState === RDIState.reset) {
    when(io.rdi.lpStateReq === RDIStateReq.nop) {
      resetReqObserved := true.B
    }
  }.otherwise {
    resetReqObserved := false.B
  }

  val requester = Module(new RDIStateMachineRequester(sbParams))
  requester.io.currentState := currentState
  requester.io.resetReqObserved := resetReqObserved
  requester.io.rdi.lpStateReq := io.rdi.lpStateReq
  requester.io.rdi.plWakeAck := io.rdi.plWakeAck
  requester.io.trainingTimeout := io.trainingTimeout
  requester.io.sbLaneIo <> io.requesterSbLaneIo

  val responder = Module(new RDIStateMachineResponder(sbParams))
  responder.io.currentState := currentState
  responder.io.resetReqObserved := resetReqObserved
  responder.io.rdi.lpStateReq := io.rdi.lpStateReq
  responder.io.rdi.plWakeAck := io.rdi.plWakeAck
  responder.io.trainingTimeout := io.trainingTimeout
  responder.io.sbLaneIo <> io.responderSbLaneIo

  when(currentState === RDIState.activePmNak && !isPmReq(io.rdi.lpStateReq)) {
    currentState := RDIState.active
  }

  when(responder.io.transitionDone && requester.io.transitionDone) {
    assert(responder.io.targetState === requester.io.targetState,
      "FATAL: RDI requester/responder completed conflicting transitions")
    currentState := responder.io.targetState
  }.elsewhen(responder.io.transitionDone) {
    currentState := responder.io.targetState
  }.elsewhen(requester.io.transitionDone) {
    currentState := requester.io.targetState
  }

  assert((currentState =/= RDIState.l1) && (currentState =/= RDIState.l2),
    "FATAL: PM entry is not implemented in the RDI state machine")

  io.rdi.plStateSts := currentState
  io.sidebandBusy := requester.io.busy || responder.io.busy
}

class RDIStateMachineRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val currentState = Input(RDIState())
    val resetReqObserved = Input(Bool())
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool())
    }
    val trainingTimeout = Input(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val busy = Output(Bool())
    val transitionDone = Output(Bool())
    val targetState = Output(RDIState())
  })

  object Substate extends ChiselEnum {
    val sIdle, sExchange = Value
  }

  import RDIStateMachineHelpers._

  val substateReg = RegInit(Substate.sIdle)
  val pendingKindReg = RegInit(RDIStateMachineKind.none)
  val pendingTargetReg = RegInit(RDIState.reset)

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := substateReg === Substate.sIdle
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.transitionDone := false.B
  io.targetState := pendingTargetReg
  io.busy := substateReg =/= Substate.sIdle

  val startKind = WireDefault(RDIStateMachineKind.none)
  val startTarget = WireDefault(io.currentState)
  val startTransition = WireDefault(false.B)

  switch(io.currentState) {
    is(RDIState.reset) {
      when(io.trainingTimeout) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkError
        startTarget := RDIState.linkError
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.active &&
          io.resetReqObserved &&
          io.rdi.plWakeAck) {
        startTransition := true.B
        startKind := RDIStateMachineKind.active
        startTarget := RDIState.active
      }
    }
    is(RDIState.active) {
      when(io.trainingTimeout) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkError
        startTarget := RDIState.linkError
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.retrain) {
        startTransition := true.B
        startKind := RDIStateMachineKind.retrain
        startTarget := RDIState.retrain
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.linkReset) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkReset
        startTarget := RDIState.linkReset
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.disabled) {
        startTransition := true.B
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
      when(io.trainingTimeout) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkError
        startTarget := RDIState.linkError
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
    is(RDIState.retrain) {
      when(io.trainingTimeout) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkError
        startTarget := RDIState.linkError
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.active) {
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
      when(io.trainingTimeout) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkError
        startTarget := RDIState.linkError
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.active) {
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
      when(io.trainingTimeout) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkError
        startTarget := RDIState.linkError
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.active) {
        startTransition := true.B
        startKind := RDIStateMachineKind.active
        startTarget := RDIState.active
      }
    }
    is(RDIState.linkError) {
      when(io.rdi.lpStateReq === RDIStateReq.linkReset) {
        startTransition := true.B
        startKind := RDIStateMachineKind.linkReset
        startTarget := RDIState.linkReset
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.disabled) {
        startTransition := true.B
        startKind := RDIStateMachineKind.disabled
        startTarget := RDIState.disabled
      }.elsewhen(io.rdi.lpStateReq === RDIStateReq.active) {
        startTransition := true.B
        startKind := RDIStateMachineKind.active
        startTarget := RDIState.active
      }
    }
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
      txPattern := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_LINKRESET, "PHY", "PHY", true)
      rxPattern := SBM.LINKMGMT_RDI_RSP_LINKRESET
    }
    is(RDIStateMachineKind.linkError) {
      txPattern := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_LINKERROR, "PHY", "PHY", true)
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
        substateReg := Substate.sExchange
      }
    }
    is(Substate.sExchange) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := txPattern
      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
      sbMsgExchanger.io.rxRefBitPattern.bits := rxPattern

      when(sbMsgExchanger.io.done) {
        io.transitionDone := true.B
        io.targetState := pendingTargetReg
        substateReg := Substate.sIdle
        pendingKindReg := RDIStateMachineKind.none
      }
    }
  }
}

class RDIStateMachineResponder(sbParams: SidebandParams) extends Module {
  import RDIStateMachineHelpers._

  val io = IO(new Bundle {
    val currentState = Input(RDIState())
    val resetReqObserved = Input(Bool())
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool())
    }
    val trainingTimeout = Input(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
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

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := substateReg === Substate.sIdle
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  io.transitionDone := false.B
  io.targetState := pendingTargetReg
  io.busy := substateReg =/= Substate.sIdle
  io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready

  val rxIsReqActive = SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_ACTIVE)
  val rxIsReqL1 = SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_L1)
  val rxIsReqL2 = SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_L2)
  val rxIsReqLinkReset = SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_LINKRESET)
  val rxIsReqLinkError = SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_LINKERROR)
  val rxIsReqRetrain = SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_RETRAIN)
  val rxIsReqDisabled = SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_DISABLE)

  val canAcceptActiveFromReset =
    (io.currentState === RDIState.reset) &&
    io.resetReqObserved &&
    io.rdi.plWakeAck &&
    (io.rdi.lpStateReq === RDIStateReq.active)

  val rspPattern = WireDefault(0.U(sbParams.sbNodeMsgWidth.W))
  val canSendResponse = WireDefault(true.B)

  switch(pendingKindReg) {
    is(RDIStateMachineKind.active) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_ACTIVE, "PHY", "PHY", true)
      when(io.currentState === RDIState.reset) {
        canSendResponse := canAcceptActiveFromReset
      }
    }
    is(RDIStateMachineKind.pmL1) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_PMNAK, "PHY", "PHY", true)
    }
    is(RDIStateMachineKind.pmL2) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_PMNAK, "PHY", "PHY", true)
    }
    is(RDIStateMachineKind.linkReset) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_LINKRESET, "PHY", "PHY", true)
    }
    is(RDIStateMachineKind.linkError) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_LINKERROR, "PHY", "PHY", true)
    }
    is(RDIStateMachineKind.retrain) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_RETRAIN, "PHY", "PHY", true)
    }
    is(RDIStateMachineKind.disabled) {
      rspPattern := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_DISABLE, "PHY", "PHY", true)
    }
  }

  switch(substateReg) {
    is(Substate.sIdle) {
      io.sbLaneIo.rx.ready := false.B
      when(io.sbLaneIo.rx.valid) {
        when(rxIsReqActive) {
          io.sbLaneIo.rx.ready := true.B
          pendingKindReg := RDIStateMachineKind.active
          pendingTargetReg := RDIState.active
          substateReg := Substate.sRespond
        }.elsewhen(rxIsReqL1) {
          io.sbLaneIo.rx.ready := true.B
          pendingKindReg := RDIStateMachineKind.pmL1
          pendingTargetReg := Mux(io.currentState === RDIState.active,
            RDIState.activePmNak, io.currentState)
          substateReg := Substate.sRespond
        }.elsewhen(rxIsReqL2) {
          io.sbLaneIo.rx.ready := true.B
          pendingKindReg := RDIStateMachineKind.pmL2
          pendingTargetReg := Mux(io.currentState === RDIState.active,
            RDIState.activePmNak, io.currentState)
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
        io.transitionDone := true.B
        io.targetState := pendingTargetReg
        substateReg := Substate.sIdle
        pendingKindReg := RDIStateMachineKind.none
      }
    }
  }
}

