/*
  Description: 
    Simple that module takes care of conducting the pl_clk_req/lp_clk_ack handshake
    in a centralized place. Interfacing with the module is done through the `ctrl` signals.

    Note: LogPHY is the requester in this handshake.
*/

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._


// Module specific bundle
class RDIClkHsRequesterCtrlIO extends Bundle {
  val startHandshake = Input(Bool())
  val releaseReq = Input(Bool())
  val doneHandshake = Output(Bool())            
  val inIdle = Output(Bool())
}

// Module
class RDIClockHandshakeRequester() extends Module {
  val io = IO(new Bundle {
    val ctrl = new RDIClkHsRequesterCtrlIO()
    val rdi = new Bundle {
      val plClkReq = Output(Bool())
      val lpClkAck = Input(Bool())
    }          
  })

  // Module specific state
  object State extends ChiselEnum {
    val sIDLE, sWAIT_ACK_ASSERT, sACTIVE_HOLD, sWAIT_ACK_DEASSERT = Value
  }

  val currentState = RegInit(State.sIDLE)
  val nextState = WireInit(currentState)
  currentState := nextState

  io.rdi.plClkReq := false.B
  io.ctrl.doneHandshake := false.B  // doneHandshake goes high when lpClkAck goes HIGH

  // Once inIdle cycles back to sIDLE, after start, then handshake fully compelete.
  io.ctrl.inIdle := currentState === State.sIDLE

  // Treat the clock request as level-sensitive after the initial 4-phase handshake:
  // keep pl_clk_req asserted while clocks are still needed, then allow de-assertion
  // either when the caller explicitly releases or when the request level drops.
  val releaseCondition = io.ctrl.releaseReq || !io.ctrl.startHandshake

  switch(currentState) {
    is(State.sIDLE) {
      when(io.ctrl.startHandshake) {
        nextState := State.sWAIT_ACK_ASSERT
      }
    } 
    is(State.sWAIT_ACK_ASSERT) {
      io.rdi.plClkReq := true.B
      when(io.rdi.lpClkAck) {
        nextState := State.sACTIVE_HOLD
      }.elsewhen(releaseCondition) {
        nextState := State.sIDLE
      }
    }
    is(State.sACTIVE_HOLD) {
      assert(io.rdi.lpClkAck === true.B, 
        "FATAL: Adapter dropped lpClkAck while plClkReq was still asserted (Clk Handshake Rule 3)")
        
      io.rdi.plClkReq := true.B
      io.ctrl.doneHandshake := true.B
      // Per the handshake rules, pl_clk_req stays asserted only while this side
      // still requires clocks. Once that need is removed, de-assert first and
      // then wait for lp_clk_ack to drop.
      when(releaseCondition) {
        nextState := State.sWAIT_ACK_DEASSERT
      }
    }
    is(State.sWAIT_ACK_DEASSERT) {
      // TODO: If D2D has a bug and lpClkAck just stays asserted then this will get stuck
      // Either make sure D2D implementation is watertight, or insert a timeout here.

      // Rule 3: pl_clk_req MUST de-assert before lp_clk_ack
      io.rdi.plClkReq := false.B
      when(!io.rdi.lpClkAck) {
        nextState := State.sIDLE
      }      
    }
  }
}

