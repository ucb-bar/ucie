/*
  The four-phase request/acknowledge handshake that RDI and FDI use to ask for
  clocks and for wake (sec 10.1.3, sec 10.2.3). There is one module for each
  side, and the caller connects the pins.
 */

package edu.berkeley.cs.uciedigital.common

import chisel3._
import chisel3.util._

class ReqAckRequesterCtrlIO extends Bundle {
  val startHandshake = Input(Bool())
  val releaseReq = Input(Bool())
  val doneHandshake = Output(Bool())
  val inIdle = Output(Bool())
  val timedOut = Output(Bool())
}

class ReqAckResponderCtrlIO extends Bundle {
  val ungateClocks = Output(Bool())
  val clocksUngatedAndStable = Input(Bool())
}

// Note: Give timeoutCycles only when the caller escalates on timedOut.
class ReqAckRequester(timeoutCycles: Option[Int] = None) extends Module {
  timeoutCycles.foreach(c => require(c > 1))

  val io = IO(new Bundle {
    val ctrl = new ReqAckRequesterCtrlIO
    val req = Output(Bool())
    val ack = Input(Bool())
  })

  // ===========================================================================
  // State machine
  // ===========================================================================
  object State extends ChiselEnum {
    val sIDLE, sWAIT_ACK_ASSERT, sACTIVE_HOLD, sWAIT_ACK_DEASSERT = Value
  }

  // Reset with the request low, as sec 10.1.1 requires. The tie-off to 1 in
  // Table 10-1 and Table 10-3 is only for designs without clock gating.
  val currentState = RegInit(State.sIDLE)
  val nextState = WireInit(currentState)
  currentState := nextState

  io.req := false.B
  io.ctrl.doneHandshake := false.B
  io.ctrl.inIdle := currentState === State.sIDLE

  val releaseCondition = io.ctrl.releaseReq || !io.ctrl.startHandshake

  switch(currentState) {
    is(State.sIDLE) {
      when(io.ctrl.startHandshake) {
        nextState := State.sWAIT_ACK_ASSERT
      }
    }
    is(State.sWAIT_ACK_ASSERT) {
      io.req := true.B
      when(io.ack) {
        nextState := State.sACTIVE_HOLD
      }.elsewhen(releaseCondition) {
        nextState := State.sIDLE
      }
    }
    is(State.sACTIVE_HOLD) {
      assert(
        io.ack === true.B,
        "FATAL: the peer dropped its acknowledge while the request was still " +
          "asserted (req/ack handshake rule 3)"
      )
      io.req := true.B
      io.ctrl.doneHandshake := true.B
      when(releaseCondition) {
        nextState := State.sWAIT_ACK_DEASSERT
      }
    }
    is(State.sWAIT_ACK_DEASSERT) {
      io.req := false.B
      when(!io.ack) {
        nextState := State.sIDLE
      }
    }
  }

  // ===========================================================================
  // Timeout
  // ===========================================================================
  // sec 10.1.3.2 rule 9 makes pl_cfg wait on this handshake, so a peer that
  // never answers would block the sideband. Count only while the peer must send
  // an edge.
  val waitingOnPeer = currentState === State.sWAIT_ACK_ASSERT ||
    currentState === State.sWAIT_ACK_DEASSERT

  // Report the timeout, but do not de-assert the request. Rule 3 lets this side
  // de-assert it only after the handshake actions are complete, and a timer
  // completes no action.
  val timedOut = WireDefault(false.B)
  timeoutCycles.foreach { cycles =>
    val counter = RegInit(0.U(log2Ceil(cycles).W))
    val expired = counter === (cycles - 1).U
    when(!waitingOnPeer) {
      counter := 0.U
    }.elsewhen(!expired) {
      counter := counter + 1.U
    }
    timedOut := waitingOnPeer && expired
  }
  io.ctrl.timedOut := timedOut
}

class ReqAckResponder extends Module {
  val io = IO(new Bundle {
    val ctrl = new ReqAckResponderCtrlIO
    val req = Input(Bool())
    val ack = Output(Bool())
  })

  object State extends ChiselEnum {
    val sIDLE, sUNGATE, sACK_ASSERT, sACK_ACTIVE, sACK_DEASSERT = Value
  }

  val currentState = RegInit(State.sIDLE)
  val nextState = WireInit(currentState)
  currentState := nextState

  io.ack := false.B

  // Note: No logic reads this output yet.
  io.ctrl.ungateClocks := currentState =/= State.sIDLE

  switch(currentState) {
    is(State.sIDLE) {
      when(io.req) {
        nextState := State.sUNGATE
      }
    }
    is(State.sUNGATE) {
      // Do not answer a withdrawn request, because the acknowledge would be
      // stray. This state also gives the one-cycle bubble that rule 2 requires.
      when(!io.req) {
        nextState := State.sIDLE
      }.elsewhen(io.ctrl.clocksUngatedAndStable) {
        nextState := State.sACK_ASSERT
      }
    }
    is(State.sACK_ASSERT) {
      // Table 10-1 and Table 10-3 make the acknowledge a registered copy of the
      // request. So it can stay high for one cycle after the request is
      // withdrawn.
      io.ack := true.B
      nextState := State.sACK_ACTIVE
    }
    is(State.sACK_ACTIVE) {
      io.ack := true.B
      when(!io.req) {
        nextState := State.sACK_DEASSERT
      }
    }
    is(State.sACK_DEASSERT) {
      io.ack := false.B
      nextState := State.sIDLE
    }
  }
}
