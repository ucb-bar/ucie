/*
  The Adapter side of the RDI stall handshake (sec 10.3.2). It acks
  pl_stallreq once the mainband has drained, as rules 4 and 5 require.
 */

package edu.berkeley.cs.uciedigital.rdi

import chisel3._
import chisel3.util._

class RdiStallResponderIO() extends Bundle {
  val mainbandStallDone = Input(Bool())
  val plStallReq = Input(Bool())
  val lpStallAck = Output(Bool())
}

class RdiStallResponder() extends Module {
  val io = IO(new RdiStallResponderIO())

  object State extends ChiselEnum {
    val sIDLE, sWAIT_ACK_ASSERT, sSTALLED, sWAIT_ACK_DEASSERT = Value
  }

  val currentState = RegInit(State.sIDLE)
  val nextState = WireDefault(currentState)

  io.lpStallAck := currentState === State.sSTALLED

  switch(currentState) {
    is(State.sIDLE) {
      when(io.plStallReq) {
        nextState := State.sWAIT_ACK_ASSERT
      }
    }
    is(State.sWAIT_ACK_ASSERT) {
      // Rule 4 lets lp_stallack rise only while pl_stallreq is held. A good
      // peer keeps it held until the ack (rule 3), so only a bad peer drops it.
      when(!io.plStallReq) {
        nextState := State.sIDLE
      }.elsewhen(io.mainbandStallDone) {
        nextState := State.sSTALLED
      }
    }
    is(State.sSTALLED) {
      when(!io.plStallReq) {
        nextState := State.sWAIT_ACK_DEASSERT
      }
    }
    is(State.sWAIT_ACK_DEASSERT) {
      nextState := State.sIDLE
    }
  }

  currentState := nextState
}
