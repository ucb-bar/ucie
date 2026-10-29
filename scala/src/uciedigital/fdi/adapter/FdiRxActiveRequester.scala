/*
  The Adapter side of the pl_rx_active_req and lp_rx_active_sts handshake
  (sec 10.2.7). FdiRxActiveResponder is the Protocol Layer side.
 */

package edu.berkeley.cs.uciedigital.fdi

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification

class FdiRxActiveRequesterIO extends Bundle {
  val enable = Input(Bool())
  val stateAllowsAssert = Input(Bool())
  val plRxActiveReq = Output(Bool())
  val lpRxActiveSts = Input(Bool())
  val rxActive = Output(Bool())
  val rxDeactive = Output(Bool())
}

class FdiRxActiveRequester extends Module {
  val io = IO(new FdiRxActiveRequesterIO())

  val reqReg = RegInit(false.B)
  io.plRxActiveReq := reqReg

  when(!reqReg) {
    // Rule 1 allows a rising edge only while lp_rx_active_sts is 0 and
    // pl_state_sts is Reset, Retrain or Active.
    when(io.enable && !io.lpRxActiveSts && io.stateAllowsAssert) {
      reqReg := true.B
    }
  }.otherwise {
    when(!io.enable && io.lpRxActiveSts) {
      reqReg := false.B
    }
  }

  io.rxActive := reqReg && io.lpRxActiveSts

  // Rule 5 makes both down the precondition for a graceful exit from Active.
  io.rxDeactive := !reqReg && !io.lpRxActiveSts

  block(Verification) {
    val prevReq = RegNext(reqReg, false.B)
    val prevSts = RegNext(io.lpRxActiveSts, false.B)
    val prevAllows = RegNext(io.stateAllowsAssert, false.B)
    when(reqReg && !prevReq) {
      assert(!prevSts, "pl_rx_active_req rose while lp_rx_active_sts was high")
      assert(prevAllows, "pl_rx_active_req rose in a state that forbids it")
    }
    when(!reqReg && prevReq) {
      assert(prevSts, "pl_rx_active_req fell while lp_rx_active_sts was low")
    }
  }
}
