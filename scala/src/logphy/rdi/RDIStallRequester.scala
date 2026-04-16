package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Description: 
    This module handles the LogPHY (Requester) side of the pl_stallreq/lp_stallack 
    handshake. It enforces the strict 4-phase sequence required by UCIe to cleanly halt
    flit transmission before state transitions.
*/

class RDIStallRequesterCtrlIO extends Bundle {
  val startStall   = Input(Bool())  // Trigger from main FSM to stop data
  val releaseStall = Input(Bool())  // Trigger from main FSM to end the stall
  val isStalled    = Output(Bool()) // Tells FSM: Adapter is cleanly stalled
  val inIdle       = Output(Bool()) // Indicates handshake is fully reset
}

class RDIStallRequester() extends Module {
  val io = IO(new Bundle {
    val ctrl = new RDIStallRequesterCtrlIO()
    val rdi = new Bundle {
      val plStallReq = Output(Bool())
      val lpStallAck = Input(Bool())
    }
  })

  // Module specifc state
  object State extends ChiselEnum {
    val sIDLE, sWAIT_ACK_ASSERT, sSTALLED, sWAIT_ACK_DEASSERT = Value
  }

  val currentState = RegInit(State.sIDLE)
  val nextState = WireDefault(currentState)
  currentState := nextState

  // Defaults
  // Note on Rule 8: Because these are driven directly by `currentState` (which is a flip-flop), 
  // there is inherently at least one flip-flop between lpStallAck (input) and plStallReq (output),
  // preventing a combinatorial loop.
  io.rdi.plStallReq := false.B
  io.ctrl.isStalled := false.B  
  io.ctrl.inIdle := currentState === State.sIDLE

  switch(currentState) {
    is(State.sIDLE) {
      // Rule 2: A rising edge on pl_stallreq must ONLY occur when lp_stallack is de-asserted.
      // Gate the start condition with `!io.rdi.lpStallAck` to guarantee this.
      when(io.ctrl.startStall && !io.rdi.lpStallAck) {
        nextState := State.sWAIT_ACK_ASSERT
      }
    }     
    is(State.sWAIT_ACK_ASSERT) {      
      io.rdi.plStallReq := true.B

      // Need to wait for the Adapter to reach a clean flit boundary and assert Ack
      when(io.rdi.lpStallAck) {
        nextState := State.sSTALLED
      }
    }
    is(State.sSTALLED) {
      io.rdi.plStallReq := true.B
      io.ctrl.isStalled := true.B
      
      // Hold the stall here while RDI FSM does work. 
      // Once the RDI FSM is done, it asserts releaseStall.
      when(io.ctrl.releaseStall) {        
        nextState := State.sWAIT_ACK_DEASSERT
      }
    }    
    is(State.sWAIT_ACK_DEASSERT) {
      // Rule 3: A falling edge on pl_stallreq must only occur when lp_stallack is asserted.
      // By entering this state, plStallReq falls to false.B. Because we came from sSTALLED, 
      // we know lpStallAck is currently HIGH.
      
      // Wait for Adapter to drop Ack before returning to sIDLE (Completing the 4-phase handshake)
      when(!io.rdi.lpStallAck) {
        nextState := State.sIDLE
      }      
    }
  }
}

object MainRDIStallRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStallRequester(),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}