package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Description: 
    Simple that module takes care of conducting the lp_wake_req/pl_wake_ack handshake
    in a centralized place. Interfacing with the module is done through the `ctrl` signals.

    Note: LogPHY is the responder in this handshake.
*/

class RDIWakeHsResponderCtrlIO extends Bundle {
  val ungateClocks = Output(Bool())           // To Clock Control Unit
  val clocksUngatedAndStable = Input(Bool())  // From Clock Control Unit
}

class RDIWakeHandshakeResponder() extends Module {
  val io = IO(new Bundle {
    val ctrl = new RDIWakeHsResponderCtrlIO()
    val rdi = new Bundle {
      val lpWakeReq = Input(Bool())   // From D2D
      val plWakeAck = Output(Bool())  // To D2D
    }          
  })  

  // Module specific state
  object State extends ChiselEnum {
    val sIDLE, sUNGATE, sACK_ASSERT, sWAKE_ACTIVE, sACK_DEASSERT = Value
  }

  val currentState = RegInit(State.sIDLE)
  val nextState = WireInit(currentState)
  currentState  := nextState

  io.rdi.plWakeAck := false.B
  io.ctrl.ungateClocks := false.B 

  switch(currentState) {
    is(State.sIDLE) {
      // TODO: If D2D has a bug and lpWakeReq just stays asserted then this will always trigger.
      when(io.rdi.lpWakeReq) {
        nextState := State.sUNGATE
      }
    } 
    is(State.sUNGATE) {      
      io.ctrl.ungateClocks := true.B
      // Rule 2: Wait for clocks to be ready + bubble cycle
      when(io.ctrl.clocksUngatedAndStable) {
        nextState := State.sACK_ASSERT
      }
    }
    is(State.sACK_ASSERT) {
      io.ctrl.ungateClocks := true.B
      io.rdi.plWakeAck := true.B
      nextState := State.sWAKE_ACTIVE
    }
    is(State.sWAKE_ACTIVE) {
      // TODO: If D2D has a bug and lpWakeReq just stays asserted then this will get stuck.
      // Either make sure D2D implementation is watertight, or insert a timeout here.

      io.ctrl.ungateClocks := true.B
      io.rdi.plWakeAck := true.B
      // Rule 3: Wait for Adapter to drop Req first
      when(!io.rdi.lpWakeReq) {
        nextState := State.sACK_DEASSERT
      }  
    }
    is(State.sACK_DEASSERT) {
      // Drop ack only after req is low
      io.rdi.plWakeAck := false.B
      nextState := State.sIDLE
    }
  }
}

object MainRDIWakeHandshakeResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIWakeHandshakeResponder(),
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