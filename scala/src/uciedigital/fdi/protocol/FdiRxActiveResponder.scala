/*
  The Protocol Layer side of the pl_rx_active_req and lp_rx_active_sts
  handshake (sec 10.2.7). FdiRxActiveRequester is the Adapter side.
 */

package edu.berkeley.cs.uciedigital.fdi

import chisel3._
import chisel3.util._

class FdiRxActiveResponderCtrlIO extends Bundle {
  val receiverReady = Input(Bool())
  val receiverActive = Output(Bool())
}

class FdiRxActiveResponder() extends Module {
  val io = IO(new Bundle {
    val ctrl = new FdiRxActiveResponderCtrlIO()
    val fdi = new Bundle {
      val plRxActiveReq = Input(Bool())
      val lpRxActiveSts = Output(Bool())
    }
  })

  object State extends ChiselEnum {
    val sIDLE, sWAIT_READY, sASSERTED, sTURNAROUND = Value
  }

  val currentState = RegInit(State.sIDLE)
  val nextState = WireInit(currentState)
  currentState := nextState

  io.fdi.lpRxActiveSts := false.B

  switch(currentState) {
    is(State.sIDLE) {
      when(io.fdi.plRxActiveReq) {
        nextState := State.sWAIT_READY
      }
    }
    is(State.sWAIT_READY) {
      // Rule 2 wants at least one clock between the request and the status, so
      // this state is spent even when the receiver is ready immediately.
      when(!io.fdi.plRxActiveReq) {
        nextState := State.sIDLE
      }.elsewhen(io.ctrl.receiverReady) {
        nextState := State.sASSERTED
      }
    }
    is(State.sASSERTED) {
      io.fdi.lpRxActiveSts := true.B

      // Rule 4 makes the falling edge of the request the trigger to drop the
      // status, which is what completes the exit from Active.
      when(!io.fdi.plRxActiveReq) {
        nextState := State.sTURNAROUND
      }
    }
    is(State.sTURNAROUND) {
      when(!io.fdi.plRxActiveReq) {
        nextState := State.sIDLE
      }.elsewhen(io.ctrl.receiverReady) {
        nextState := State.sASSERTED
      }
    }
  }

  io.ctrl.receiverActive := io.fdi.plRxActiveReq && io.fdi.lpRxActiveSts
}
