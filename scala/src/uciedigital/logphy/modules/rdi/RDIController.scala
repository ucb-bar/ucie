package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.layer.{Layer, LayerConfig, block}
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
    val doingRdiBringup = Output(Bool())
    val trainingTimeout = Input(Bool())
    val validFramingError = Input(Bool())
    val cfgSidebandActive = Input(Bool())
    val plPhyInRecenter = Input(Bool())
    val clocksUngatedAndStable = Input(Bool())
    val ungateClocks = Output(Bool())
  })

  val wakeResponder = Module(new RDIWakeHandshakeResponder())
  wakeResponder.io.rdi.lpWakeReq := io.rdi.lpWakeReq
  wakeResponder.io.ctrl.clocksUngatedAndStable := io.clocksUngatedAndStable
  io.rdi.plWakeAck := wakeResponder.io.rdi.plWakeAck
  io.ungateClocks := wakeResponder.io.ctrl.ungateClocks

  val rdiStateMachine = Module(new RDIStateMachine(sbParams))
  val currentState = rdiStateMachine.io.rdi.plStateSts
  val shouldForceBringupActive = (currentState === RDIState.reset) &&
    (io.doRdiBringup || (io.ltsmState === LTState.sLINKINIT))
  val effectiveLpStateReq = Mux(shouldForceBringupActive, RDIStateReq.active, io.rdi.lpStateReq)

  val currentStateIsResetOrPm = (currentState === RDIState.reset) || (currentState === RDIState.activePmNak)
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
    rdiStateMachine.io.sidebandBusy

  val clockRequester = Module(new RDIClockHandshakeRequester())
  clockRequester.io.ctrl.startHandshake := keepClockRequested
  clockRequester.io.ctrl.releaseReq := !keepClockRequested
  clockRequester.io.rdi.lpClkAck := io.rdi.lpClkAck
  io.rdi.plClkReq := clockRequester.io.rdi.plClkReq

  val stallRequester = Module(new RDIStallRequester())
  val framingErrorInActive = (io.ltsmState === LTState.sACTIVE) && io.validFramingError

  val activeBringupReady =
    (wakeResponder.io.rdi.plWakeAck || !io.rdi.lpWakeReq) &&
    (clockRequester.io.ctrl.doneHandshake || io.rdi.lpClkAck || !keepClockRequested)

  val holdUpperLayerStall =
    framingErrorInActive ||
    ((currentState === RDIState.active) &&
      ((effectiveLpStateReq === RDIStateReq.retrain) ||
       (effectiveLpStateReq === RDIStateReq.linkReset) ||
       (effectiveLpStateReq === RDIStateReq.disabled) ||
       (effectiveLpStateReq === RDIStateReq.l1) ||
       (effectiveLpStateReq === RDIStateReq.l2)))

  stallRequester.io.ctrl.startStall := holdUpperLayerStall && !stallRequester.io.ctrl.isStalled
  stallRequester.io.ctrl.releaseStall := !holdUpperLayerStall && (currentState =/= RDIState.active)
  stallRequester.io.rdi.lpStallAck := io.rdi.lpStallAck
  io.rdi.plStallReq := stallRequester.io.rdi.plStallReq

  rdiStateMachine.io.rdi.lpStateReq := effectiveLpStateReq
  rdiStateMachine.io.rdi.plWakeAck := activeBringupReady
  rdiStateMachine.io.trainingTimeout := io.trainingTimeout

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
      val activeReaderClients = PopCount(Seq(
        requesterSbLane.rx.ready,
        responderSbLane.rx.ready
      ))
      assert(activeReaderClients <= 1.U,
        "FATAL: Multiple RDI sideband subclients asserted RX ready in the same cycle")
      when(currentState === RDIState.active) {
        assert(activeBringupReady,
          "FATAL: RDI ACTIVE requires wake and clock prerequisites to be complete")
      }
      when((currentState === RDIState.reset) && io.rdi.plInbandPres) {
        assert(clockRequester.io.rdi.plClkReq || clockRequester.io.ctrl.doneHandshake,
          "FATAL: pl_inband_pres assertion in RESET must be covered by the clock handshake")
      }
      when(mustHoldClocksUntilStateChanges) {
        assert(clockRequester.io.rdi.plClkReq,
          "FATAL: pl_clk_req must remain asserted while leaving RESET/PM states")
      }
      when(rdiStateMachine.io.sidebandBusy) {
        assert(clockRequester.io.rdi.plClkReq,
          "FATAL: Sideband traffic to the Adapter must be covered by the clock handshake")
      }
      when(io.cfgSidebandActive) {
        assert(clockRequester.io.rdi.plClkReq,
          "FATAL: RDI cfg sideband activity must be covered by the clock handshake")
      }
      when(stallRequester.io.ctrl.startStall && io.validFramingError) {
        assert(io.ltsmState === LTState.sACTIVE,
          "FATAL: Framing-error-triggered stall is only valid while LT is ACTIVE")
      }
      assert((currentState =/= RDIState.l1) && (currentState =/= RDIState.l2),
        "FATAL: PM entry is not implemented in the RDI controller")
    }
  }

  io.sbLaneIo.rx.ready := requesterSbLane.rx.ready || responderSbLane.rx.ready

  val txArbiter = Module(new RRArbiter(chiselTypeOf(io.sbLaneIo.tx.bits), 2))
  txArbiter.io.in(0) <> requesterSbLane.tx
  txArbiter.io.in(1) <> responderSbLane.tx
  io.sbLaneIo.tx <> txArbiter.io.out

  when((io.ltsmState === LTState.sRESET) || (io.ltsmState === LTState.sTRAINERROR)) {
    inbandPresent := false.B
  }.elsewhen((io.ltsmState === LTState.sLINKINIT) || (io.ltsmState === LTState.sACTIVE)) {
    inbandPresent := true.B
  }

  io.rdi.plStateSts := currentState
  io.rdi.plInbandPres := inbandPresent
  io.doingRdiBringup := shouldForceBringupActive || (currentState === RDIState.reset &&
    effectiveLpStateReq === RDIStateReq.active)
}

