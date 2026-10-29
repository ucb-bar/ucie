/*
  The Protocol Layer's lp_state_req on FDI. It turns the software requests
  into requests that Table 10-4 accepts in the current status.
 */

package edu.berkeley.cs.uciedigital.fdi

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import edu.berkeley.cs.uciedigital.fdi._

class ProtocolStateRequestSMIO extends Bundle {
  val ctrl = new ProtocolLayerCtrlIO()
  val plStateSts = Input(FDIState())
  val plInbandPres = Input(Bool())
  val negotiatedValid = Input(Bool())
  val negotiatedFlitFormat = Input(FDIFlitFormat())
  val lpStateReq = Output(FDIStateReq())
}

class ProtocolStateRequestSM extends Module {
  val io = IO(new ProtocolStateRequestSMIO())

  // Registered so the request holds a full clock at the pin. The Adapter
  // samples it against its own registered copy, so the NOP to Active edge
  // survives.
  val reqReg = RegInit(FDIStateReq.nop)
  io.lpStateReq := reqReg

  val inReset = io.plStateSts === FDIState.reset
  val inActive = io.plStateSts === FDIState.active
  val inPmNak = io.plStateSts === FDIState.activePmNak
  val inRetrain = io.plStateSts === FDIState.retrain
  val inLinkReset = io.plStateSts === FDIState.linkReset
  val inLinkError = io.plStateSts === FDIState.linkError
  val inDisabled = io.plStateSts === FDIState.disabled

  // sec 10.3 applies every rule of Active to Active.PMNAK, so they share the
  // Active column.
  val inActiveColumn = inActive || inPmNak

  val prevStatusReg = RegNext(io.plStateSts, FDIState.reset)
  val nopPresentedReg = RegNext(reqReg === FDIStateReq.nop, true.B)
  val statusStable = io.plStateSts === prevStatusReg

  // ===========================================================================
  // Table 10-4 legality
  // ===========================================================================
  // Masking stops a stuck request from parking lp_state_req at an ignored
  // encoding. sec 10.3.3.8 lets Active in Reset come before pl_inband_pres,
  // and nothing else retrains this chip after a Link down, so it goes at once.
  val activeLegal = inReset || inRetrain || inLinkReset || inDisabled ||
    inLinkError

  // sec 10.3.3.4 allows Retrain only while UCIe operates in Raw Format.
  val retrainLegal = inActiveColumn && io.negotiatedValid &&
    io.negotiatedFlitFormat === FDIFlitFormat.rawFormat

  // Table 10-4 ignores a request in its own state's column, so a held
  // LinkReset or Disable can stay on the pin once the status arrives.
  val linkResetLegal = inReset || inActiveColumn || inRetrain || inLinkReset
  val disableLegal = inReset || inActiveColumn || inRetrain || inLinkReset ||
    inDisabled

  // sec 10.2.9 bases the PMNAK answer on a standing Active request, so hold it
  // while the status says Active. Footnote b uses the same level to leave
  // Active.PMNAK.
  val holdActive = inActiveColumn

  // sec 10.3.3.4 rule 1 lets the upper layer delay the Retrain exit. Nothing
  // waits here, so this layer starts the request. In Reset, pl_inband_pres is
  // the trigger to request Active (sec 10.2.8 step 2).
  val wantActive = io.ctrl.requestActive || (inReset && io.plInbandPres) ||
    inRetrain || holdActive

  // Priority picks the request, and NOP first decides when it goes on the pin.
  // Active is last so a held request never masks a teardown.
  val desiredState = WireDefault(FDIStateReq.nop)
  when(io.ctrl.requestDisable && disableLegal) {
    desiredState := FDIStateReq.disabled
  }.elsewhen(io.ctrl.requestLinkReset && linkResetLegal) {
    desiredState := FDIStateReq.linkReset
  }.elsewhen(io.ctrl.requestRetrain && retrainLegal) {
    desiredState := FDIStateReq.retrain
  }.elsewhen(wantActive && (activeLegal || holdActive)) {
    desiredState := FDIStateReq.active
  }

  // ===========================================================================
  // NOP first sequencing
  // ===========================================================================
  // Footnote a on Reset and sec 10.3.3.4 rule 1 on the Retrain exit gate a
  // change of request. A request already on the pin stays.
  val needsNopFirst = inReset ||
    (inRetrain && (desiredState === FDIStateReq.active))
  val nopFirstDone = nopPresentedReg && statusStable
  val blockedOnNop = (desiredState =/= reqReg) && !nopFirstDone

  // A status change invalidates a request made under the old status. This
  // forces a new NOP to Active edge on the Retrain exit.
  val forceNop = needsNopFirst && (blockedOnNop || !statusStable)
  val requestedState = Mux(forceNop, FDIStateReq.nop, desiredState)

  reqReg := requestedState

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    val prevReqReg = RegNext(io.lpStateReq, FDIStateReq.nop)
    val activeRequestEdge = (io.lpStateReq === FDIStateReq.active) &&
      (prevReqReg =/= FDIStateReq.active)

    block(Verification.Assert) {
      // The stable qualifier covers one cycle where a request from the previous
      // state is still on the pin. Every such Table 10-4 cell is Ignore.
      when(statusStable) {
        when(io.lpStateReq === FDIStateReq.retrain) {
          assert(
            retrainLegal,
            "FATAL: Table 10-4 considers Retrain only while the status is " +
              "Active"
          )
        }
        when(io.lpStateReq === FDIStateReq.linkReset) {
          assert(
            linkResetLegal,
            "FATAL: LinkReset is neither considered nor held in this status"
          )
        }
        when(io.lpStateReq === FDIStateReq.disabled) {
          assert(
            disableLegal,
            "FATAL: Disable is neither considered nor held in this status"
          )
        }

        when(
          inReset && (io.lpStateReq =/= prevReqReg) &&
            (io.lpStateReq =/= FDIStateReq.nop)
        ) {
          assert(
            prevReqReg === FDIStateReq.nop,
            "FATAL: sec 10.3.3.1 rule 1 needs NOP before a request in Reset"
          )
        }

        when(inRetrain && activeRequestEdge) {
          assert(
            prevReqReg === FDIStateReq.nop,
            "FATAL: sec 10.3.3.4 rule 1 needs a NOP to Active edge in Retrain"
          )
        }
      }
    }

    block(Verification.Cover) {
      cover(
        (io.lpStateReq === FDIStateReq.active) && inActive,
        "StateRequestHoldsActiveWhileActive"
      )
      cover(
        (io.lpStateReq === FDIStateReq.active) && inPmNak,
        "StateRequestDrivesActiveInPmNak"
      )
      cover(activeRequestEdge && inReset, "StateRequestResetToActiveEdge")
      cover(activeRequestEdge && inRetrain, "StateRequestRetrainToActiveEdge")
      cover(io.lpStateReq === FDIStateReq.retrain, "StateRequestDrivesRetrain")
      cover(
        io.ctrl.requestRetrain && !retrainLegal,
        "StateRequestMasksRetrainOutsideActive"
      )
      cover(
        io.ctrl.requestRetrain && inReset &&
          (desiredState === FDIStateReq.active),
        "StateRequestBringsUpDespiteStuckRetrain"
      )
    }
  }
}
