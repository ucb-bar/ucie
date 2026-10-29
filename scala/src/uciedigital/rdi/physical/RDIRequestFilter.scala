/*
  Filters lp_state_req by the current RDI state (Table 10-4), including the
  NOP to X edge rule (footnote a and sec 10.3.3.4 rule 1).
 */

package edu.berkeley.cs.uciedigital.rdi

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.common._

class RDIRequestFilter extends Module {
  val io = IO(new Bundle {
    val plStateSts = Input(RDIState())
    val lpStateReq = Input(RDIStateReq())
    val effectiveStateReq = Output(RDIStateReq())
  })

  // Every PM request gets PMNAK, so Retrain is entered only from Active and
  // Retrain to Active always needs the edge.
  val nopSeen = RegInit(false.B)
  val statePrev = RegNext(io.plStateSts, RDIState.reset)
  val stateChanged = io.plStateSts =/= statePrev

  // The upper layer may drop to NOP right after the trigger (sec 10.3.3.8),
  // so the edge is latched.
  when(stateChanged) {
    nopSeen := false.B
  }.elsewhen(io.lpStateReq === RDIStateReq.nop) {
    nopSeen := true.B
  }

  // The clear lands one cycle after the status moves. A NOP from the previous
  // state must not count as an edge in this state.
  val edgeSeen = nopSeen && !stateChanged

  val req = io.lpStateReq
  val considered = WireDefault(false.B)
  val needsNopFirst = WireDefault(false.B)

  switch(io.plStateSts) {
    is(RDIState.reset) {
      when(
        req === RDIStateReq.active || req === RDIStateReq.linkReset ||
          req === RDIStateReq.disabled
      ) {
        considered := true.B
        needsNopFirst := true.B
      }
    }
    is(RDIState.active) {
      // An Active request in Active is Ignore. Footnote b makes it Yes only in
      // the Active.PMNAK substate below.
      when(
        req === RDIStateReq.l1 || req === RDIStateReq.l2 ||
          req === RDIStateReq.linkReset || req === RDIStateReq.retrain ||
          req === RDIStateReq.disabled
      ) {
        considered := true.B
      }
    }
    is(RDIState.activePmNak) {
      // Footnote b makes an Active request the way out. L1 and L2 are ignored,
      // because sec 10.3.3.2 never moves Active.PMNAK to either.
      when(
        req === RDIStateReq.active || req === RDIStateReq.linkReset ||
          req === RDIStateReq.retrain || req === RDIStateReq.disabled
      ) {
        considered := true.B
      }
    }
    is(RDIState.retrain) {
      when(req === RDIStateReq.active) {
        considered := true.B
        needsNopFirst := true.B
      }.elsewhen(
        req === RDIStateReq.linkReset || req === RDIStateReq.disabled
      ) {
        // Rule 2 of sec 10.3.3.4 permits reaching these through Active.
        considered := true.B
      }
    }
    is(RDIState.linkReset) {
      when(req === RDIStateReq.active || req === RDIStateReq.disabled) {
        considered := true.B
      }
    }
    is(RDIState.disabled) {
      when(req === RDIStateReq.active) {
        considered := true.B
      }
    }
    is(RDIState.linkError) {
      when(req === RDIStateReq.active) {
        considered := true.B
      }
    }
  }

  assert(
    io.plStateSts =/= RDIState.l1 && io.plStateSts =/= RDIState.l2,
    "FATAL: RDI reached a PM state, which this filter does not decode"
  )

  io.effectiveStateReq := Mux(
    considered && (!needsNopFirst || edgeSeen),
    io.lpStateReq,
    RDIStateReq.nop
  )
}
