/*
  The requester side of the stall handshake (sec 10.3.2), shared by RDI and
  FDI. RDI and FDI Raw Format stall on each exit from Active to Retrain, PM,
  LinkReset or Disabled. FDI Flit Mode stalls only on a PM exit.
 */

package edu.berkeley.cs.uciedigital.common

import chisel3._
import chisel3.util._

class StallRequesterCtrlIO extends Bundle {
  val startStall = Input(Bool())
  val releaseStall = Input(Bool())
  val downstreamQuiesced = Input(Bool())
  val awaitingAck = Output(Bool())
  val isStalled = Output(Bool())
  val inIdle = Output(Bool())
  val timedOut = Output(Bool())
}

class StallRequester(timeoutCycles: Int) extends Module {
  require(timeoutCycles > 1)

  val io = IO(new Bundle {
    val ctrl = new StallRequesterCtrlIO
    val req = Output(Bool())
    val ack = Input(Bool())
  })

  // ===========================================================================
  // State machine
  // ===========================================================================
  object State extends ChiselEnum {
    val sIDLE, sWAIT_ACK_ASSERT, sSTALLED, sWAIT_ACK_DEASSERT = Value
  }

  val currentState = RegInit(State.sIDLE)
  val nextState = WireDefault(currentState)
  currentState := nextState

  switch(currentState) {
    is(State.sIDLE) {
      // Rule 2 lets the request assert only while the ack is low. Rule 7 lets
      // it assert only while this layer can still accept data.
      when(io.ctrl.startStall && !io.ack && !io.ctrl.downstreamQuiesced) {
        nextState := State.sWAIT_ACK_ASSERT
      }
    }
    is(State.sWAIT_ACK_ASSERT) {
      when(io.ack) {
        nextState := State.sSTALLED
      }
    }
    is(State.sSTALLED) {
      // Also wait for the ack. Rule 3 applies to this side even when a peer
      // breaks rule 5 and de-asserts its ack early.
      when(io.ctrl.releaseStall && io.ack) {
        nextState := State.sWAIT_ACK_DEASSERT
      }
    }
    is(State.sWAIT_ACK_DEASSERT) {
      when(!io.ack) {
        nextState := State.sIDLE
      }
    }
  }

  // ===========================================================================
  // Outputs
  // ===========================================================================
  // Register pl_stallreq, because RDI sends it into another clock domain and
  // the state decode can glitch. Loading it from nextState adds no delay.
  val reqReg = RegInit(false.B)
  reqReg := nextState === State.sWAIT_ACK_ASSERT ||
    nextState === State.sSTALLED
  io.req := reqReg
  io.ctrl.awaitingAck := currentState === State.sWAIT_ACK_ASSERT
  io.ctrl.isStalled := currentState === State.sSTALLED
  io.ctrl.inIdle := currentState === State.sIDLE

  // ===========================================================================
  // Timeout
  // ===========================================================================
  // Count only while the peer must send an edge. In sSTALLED this side waits
  // for its own work, which has its own 8 ms bound on RDI (sec 10.3.3.8).
  val handshakeOutstanding =
    (currentState === State.sIDLE && io.ctrl.startStall) ||
      (currentState === State.sWAIT_ACK_ASSERT) ||
      (currentState === State.sWAIT_ACK_DEASSERT)

  // Report the timeout only. Rule 3 lets the request de-assert only while
  // lp_stallack is asserted, so a timeout cannot withdraw it.
  val timeoutCounter = RegInit(0.U(log2Ceil(timeoutCycles).W))
  val timedOut = timeoutCounter === (timeoutCycles - 1).U
  when(!handshakeOutstanding) {
    timeoutCounter := 0.U
  }.elsewhen(!timedOut) {
    timeoutCounter := timeoutCounter + 1.U
  }
  io.ctrl.timedOut := handshakeOutstanding && timedOut
}
