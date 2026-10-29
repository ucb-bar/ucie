/*
  The Adapter's lp_state_req on RDI (Table 10-4). It follows rdi_pl_state_sts,
  because the table is indexed by the Lower Layer's status.
 */

package edu.berkeley.cs.uciedigital.rdi

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import edu.berkeley.cs.uciedigital.common._

class RdiStateRequestSM extends Module {
  val io = IO(new Bundle {
    val desired = Input(RDIStateReq())
    val plStateSts = Input(RDIState())
    val lpStateReq = Output(RDIStateReq())
  })

  val inReset = io.plStateSts === RDIState.reset
  val inRetrain = io.plStateSts === RDIState.retrain

  // Footnote a needs a NOP first for these Reset requests, because a request
  // held as the status lands in Reset is ignored. sec 10.3.3.4 rule 1 adds
  // Active in Retrain.
  val needsNopFirst = (inReset && (
    io.desired === RDIStateReq.active ||
      io.desired === RDIStateReq.linkReset ||
      io.desired === RDIStateReq.disabled
  )) || (inRetrain && io.desired === RDIStateReq.active)

  // Set once NOP has held a cycle in this status. The clear lands late, so
  // read it only after the status has held for a cycle.
  val statusPrev = RegNext(io.plStateSts, RDIState.reset)
  val stable = io.plStateSts === statusPrev
  val nopSeen = RegInit(false.B)
  val request = RegInit(RDIStateReq.nop)
  io.lpStateReq := request

  // Table 10-4 marks other requests Ignore, so they pass unmasked. The PHY's
  // PMNAK rule needs lp_state_req = Active held in Active (sec 10.1.7).
  val next =
    Mux(needsNopFirst && !(nopSeen && stable), RDIStateReq.nop, io.desired)
  request := next

  when(!stable) {
    nopSeen := false.B
  }.elsewhen(next === RDIStateReq.nop) {
    nopSeen := true.B
  }

  block(Verification) {
    // The first cycle is exempt, because a mask would put rdi_pl_state_sts
    // combinationally into lp_state_req.
    when(inReset && stable && request =/= RDIStateReq.nop) {
      assert(
        nopSeen,
        "a Reset-column request on RDI must be preceded by a NOP cycle"
      )
    }
    when(inRetrain && stable && request === RDIStateReq.active) {
      assert(
        nopSeen,
        "an Active request in Retrain must be preceded by a NOP cycle"
      )
    }
  }
}
