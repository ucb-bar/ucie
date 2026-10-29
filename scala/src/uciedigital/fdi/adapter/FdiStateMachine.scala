/*
  The Adapter LSM, exposed on FDI pl_state_sts for Streaming (sec 3.5 and
  sec 10.2.6). It never asks RDI for PM (sec 10.2.9), so RDI reads Active in
  L1 and L2.
 */

package edu.berkeley.cs.uciedigital.fdi

import edu.berkeley.cs.uciedigital.rdi._
import chisel3._
import chisel3.util._
import chisel3.layer.block
import chisel3.layers.Verification
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.sideband._

class FdiStateMachine(val sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val lpStateReq = Input(FDIStateReq())
    val lpLinkError = Input(Bool())
    val internalLinkError = Input(Bool())
    val plStateSts = Output(FDIState())

    val rdiPlStateSts = Input(RDIState())
    val rdiLpLinkError = Output(Bool())

    val rxDeactive = Input(Bool())
    val stallDone = Input(Bool())

    val initDone = Input(Bool())
    val activeEntryDone = Input(Bool())
    val agreeLinkReset = Input(Bool())
    val agreeDisabled = Input(Bool())
    val activeReqEdge = Input(Bool())
    val stallTrigger = Input(Bool())
    val pmNak = Input(Bool())
    val pmAgree = Input(Bool())
    val pmAgreeL2 = Input(Bool())
    val peerReqActive = Input(Bool())

    val stallReq = Output(Bool())
    val linkResetAgreed = Output(Bool())
    val disabledAgreed = Output(Bool())
    val rdiDone = Output(Bool())
    val linkErrorQuiesce = Output(Bool())
    val linkErrorRecoveryReady = Output(Bool())
    val pmAgreed = Output(Bool())
    val retrainFromPm = Output(Bool())
  })

  // ===========================================================================
  // State and RDI status
  // ===========================================================================
  val stateReg = RegInit(FDIState.reset)
  val nextState = WireDefault(stateReg)
  stateReg := nextState
  val rdiLpLinkErrorReg = RegInit(false.B)

  // Once the request is answered, both Adapters enter the state (note after
  // sec 10.3.3.8). So hold the agreement until then, even if RDI leaves Active.
  val linkResetAgreed = RegInit(false.B)
  val disabledAgreed = RegInit(false.B)
  val stallReqReg = RegInit(false.B)
  io.plStateSts := stateReg
  io.rdiLpLinkError := io.lpLinkError || rdiLpLinkErrorReg
  io.stallReq := stallReqReg

  val linkErrorPhySts = io.rdiPlStateSts === RDIState.linkError

  // sec 3.5 requires RDI in LinkError before the Adapter LSM follows (Table
  // 10-3 says the same for pl_trainerror). sec 10.2.7 rule 7 lets it come
  // before rx_active drops, so entry does not wait for rxDeactive.
  val linkErrorEntry = linkErrorPhySts
  val linkErrorRequested =
    io.lpLinkError || io.internalLinkError || rdiLpLinkErrorReg
  val linkErrorQuiesce =
    stateReg === FDIState.linkError || linkErrorEntry || linkErrorRequested
  io.linkErrorQuiesce := linkErrorQuiesce

  val rdiActive = io.rdiPlStateSts === RDIState.active
  val retrainPhySts = io.rdiPlStateSts === RDIState.retrain

  // A Protocol Layer Retrain request (Raw Format, sec 10.3.3.4) starts the
  // stall. sec 3.5 moves the LSM only once RDI reports Retrain.
  val retrainRequested = io.lpStateReq === FDIStateReq.retrain

  // ===========================================================================
  // LinkError
  // ===========================================================================
  // Table 10-1 has the Adapter OR its own errors with lp_linkerror. The
  // Adapter's own are events, so hold them until RDI reports LinkError.
  when(io.rdiPlStateSts === RDIState.linkError) {
    rdiLpLinkErrorReg := false.B
  }.elsewhen(io.internalLinkError) {
    rdiLpLinkErrorReg := true.B
  }

  val residencyWidth =
    math.max(1, log2Ceil(sbParams.linkErrorMinResidencyCycles))
  val linkErrorCount = RegInit(0.U(residencyWidth.W))
  val residencyDone = if (sbParams.linkErrorMinResidencyCycles == 1) {
    true.B
  } else {
    linkErrorCount === (sbParams.linkErrorMinResidencyCycles - 1).U
  }
  when(stateReg =/= FDIState.linkError) {
    linkErrorCount := 0.U
  }.elsewhen(!residencyDone) {
    linkErrorCount := linkErrorCount + 1.U
  }

  val linkErrorSourcesClear =
    !io.lpLinkError && !io.internalLinkError && !rdiLpLinkErrorReg
  val linkErrorActiveReqPendingReg = RegInit(false.B)
  val sawPhyLinkErrorReg = RegInit(false.B)
  when(linkErrorQuiesce) {
    // sec 10.3.3.7 exits on an Active request in LinkError. It is sampled only
    // after the residency, so a request held from before the error has dropped.
    when(
      stateReg === FDIState.linkError && residencyDone &&
        linkErrorSourcesClear && io.lpStateReq === FDIStateReq.active
    ) {
      linkErrorActiveReqPendingReg := true.B
    }
    when(linkErrorPhySts) {
      sawPhyLinkErrorReg := true.B
    }
  }.otherwise {
    linkErrorActiveReqPendingReg := false.B
    sawPhyLinkErrorReg := false.B
  }

  // ===========================================================================
  // RDI done
  // ===========================================================================
  // sec 10.3.3.5 and sec 10.3.3.6 leave on Active once "all necessary actions"
  // are done. The note after sec 10.3.3.8 recommends waiting for RDI.
  val rdiReported = io.rdiPlStateSts === RDIState.reset ||
    (stateReg === FDIState.linkReset &&
      io.rdiPlStateSts === RDIState.linkReset) ||
    (stateReg === FDIState.disabled && io.rdiPlStateSts === RDIState.disabled)
  val rdiDoneReg = RegInit(false.B)
  when(nextState =/= stateReg) {
    rdiDoneReg := false.B
  }.elsewhen(rdiReported) {
    rdiDoneReg := true.B
  }
  val rdiDone = rdiDoneReg || rdiReported
  io.rdiDone := rdiDone

  // sec 10.3.3.7 allows Reset once RDI has been there. RDI may already have
  // left again for the partner's training.
  val linkErrorResetExitReady = residencyDone && linkErrorSourcesClear &&
    io.rxDeactive && rdiDone

  // sec 10.3.3.7 lets RDI be asked to come back when the Protocol Layer asks
  // for Active again, the residency is served and every source has cleared.
  io.linkErrorRecoveryReady := residencyDone && linkErrorSourcesClear &&
    (linkErrorActiveReqPendingReg || io.lpStateReq === FDIStateReq.active) &&
    (sawPhyLinkErrorReg || linkErrorPhySts) && io.rxDeactive && linkErrorPhySts

  // ===========================================================================
  // Stall
  // ===========================================================================
  // sec 10.3.2 requires the stall on every Active exit in Raw Format, so it
  // rises on anything that leads to one. Rule 3 forbids withdrawing it before
  // the ack, even across a move to LinkError.
  val inActiveColumn = stateReg === FDIState.active ||
    stateReg === FDIState.activePmNak

  when(
    inActiveColumn &&
      (linkResetAgreed || disabledAgreed || retrainPhySts ||
        retrainRequested || io.stallTrigger)
  ) {
    stallReqReg := true.B
  }.elsewhen(io.stallDone) {
    stallReqReg := false.B
  }

  // ===========================================================================
  // Power management
  // ===========================================================================
  val pmAgreed = RegInit(false.B)
  val pmAgreedL2 = RegInit(false.B)
  when(io.pmAgree && stateReg === FDIState.active) {
    pmAgreed := true.B
    pmAgreedL2 := io.pmAgreeL2
  }
  when(stateReg =/= FDIState.active) {
    pmAgreed := false.B
  }
  io.pmAgreed := pmAgreed

  // sec 10.3.3.4 rule 1 lets a Retrain entered from L1 exit on the held Active
  // request, with no NOP to Active transition.
  val retrainFromPm = RegInit(false.B)
  when(stateReg === FDIState.l1) {
    retrainFromPm := true.B
  }.elsewhen(stateReg =/= FDIState.retrain) {
    retrainFromPm := false.B
  }
  io.retrainFromPm := retrainFromPm

  // ===========================================================================
  // State transitions
  // ===========================================================================
  switch(stateReg) {
    is(FDIState.reset) {
      // Active needs the Active Entry handshake (sec 10.2.8 step 5).
      when(linkErrorEntry) {
        nextState := FDIState.linkError
      }.elsewhen(disabledAgreed && io.rxDeactive) {
        nextState := FDIState.disabled
      }.elsewhen(linkResetAgreed && io.rxDeactive) {
        nextState := FDIState.linkReset
      }.elsewhen(io.initDone && io.activeEntryDone && rdiActive) {
        nextState := FDIState.active
      }
    }
    is(FDIState.active) {
      when(linkErrorEntry) {
        nextState := FDIState.linkError
      }.elsewhen(disabledAgreed && io.rxDeactive && io.stallDone) {
        nextState := FDIState.disabled
      }.elsewhen(linkResetAgreed && io.rxDeactive && io.stallDone) {
        nextState := FDIState.linkReset
      }.elsewhen(retrainPhySts && io.rxDeactive && io.stallDone) {
        nextState := FDIState.retrain
      }.elsewhen(pmAgreed && io.rxDeactive && io.stallDone) {
        nextState := Mux(pmAgreedL2, FDIState.l2, FDIState.l1)
      }.elsewhen(io.pmNak) {
        nextState := FDIState.activePmNak
      }
    }
    is(FDIState.activePmNak) {
      // sec 10.3 applies every rule of Active here. Table 10-4 footnote b
      // returns to Active on the Active request, with no sideband.
      when(linkErrorEntry) {
        nextState := FDIState.linkError
      }.elsewhen(disabledAgreed && io.rxDeactive && io.stallDone) {
        nextState := FDIState.disabled
      }.elsewhen(linkResetAgreed && io.rxDeactive && io.stallDone) {
        nextState := FDIState.linkReset
      }.elsewhen(retrainPhySts && io.rxDeactive && io.stallDone) {
        nextState := FDIState.retrain
      }.elsewhen(io.lpStateReq === FDIStateReq.active) {
        nextState := FDIState.active
      }
    }

    // sec 10.2.9 PM exit. The partner's Active request moves L1 to Retrain
    // and L2 to Reset, where the Active Entry Handshake completes.
    is(FDIState.l1) {
      when(linkErrorEntry) {
        nextState := FDIState.linkError
      }.elsewhen(disabledAgreed) {
        nextState := FDIState.disabled
      }.elsewhen(linkResetAgreed) {
        nextState := FDIState.linkReset
      }.elsewhen(io.peerReqActive) {
        nextState := FDIState.retrain
      }
    }
    is(FDIState.l2) {
      when(linkErrorEntry) {
        nextState := FDIState.linkError
      }.elsewhen(disabledAgreed) {
        nextState := FDIState.disabled
      }.elsewhen(linkResetAgreed) {
        nextState := FDIState.linkReset
      }.elsewhen(io.peerReqActive) {
        nextState := FDIState.reset
      }
    }
    is(FDIState.retrain) {
      when(linkErrorEntry) {
        nextState := FDIState.linkError
      }.elsewhen(disabledAgreed) {
        nextState := FDIState.disabled
      }.elsewhen(linkResetAgreed) {
        nextState := FDIState.linkReset
      }.elsewhen(
        rdiActive && io.activeEntryDone && (
          io.activeReqEdge ||
            (retrainFromPm && io.lpStateReq === FDIStateReq.active)
        )
      ) {
        // sec 10.3.3.4 rule 1 needs RDI in Active, a NOP to Active transition
        // and the Active entry handshake (sec 10.2.8). sec 10.2.9 makes that
        // wait for an owed PM Response.
        nextState := FDIState.active
      }
    }
    is(FDIState.linkError) {
      when(linkErrorResetExitReady) {
        nextState := FDIState.reset
      }
    }
    is(FDIState.disabled) {
      when(linkErrorEntry) {
        nextState := FDIState.linkError
      }.elsewhen(io.lpStateReq === FDIStateReq.active && rdiDone) {
        // sec 10.3.3.6. NOP is Ignore here (Table 10-4).
        nextState := FDIState.reset
      }
    }
    is(FDIState.linkReset) {
      when(linkErrorEntry) {
        nextState := FDIState.linkError
      }.elsewhen(disabledAgreed && io.rxDeactive) {
        nextState := FDIState.disabled
      }.elsewhen(io.lpStateReq === FDIStateReq.active && rdiDone) {
        // sec 10.3.3.5 rule 1. NOP is Ignore here (Table 10-4).
        nextState := FDIState.reset
      }
    }
  }

  // ===========================================================================
  // Agreements
  // ===========================================================================
  def entering(s: FDIState.Type): Bool = nextState === s && stateReg =/= s
  val inLinkError = stateReg === FDIState.linkError

  when(io.agreeLinkReset && !inLinkError) { linkResetAgreed := true.B }
  when(io.agreeDisabled && !inLinkError) { disabledAgreed := true.B }

  // Note: keep the clears after the sets, so the clear wins.
  val agreementsEnd =
    entering(FDIState.linkError) || entering(FDIState.reset)
  when(
    entering(FDIState.linkReset) || entering(FDIState.disabled) ||
      agreementsEnd
  ) {
    linkResetAgreed := false.B
  }
  when(entering(FDIState.disabled) || agreementsEnd) {
    disabledAgreed := false.B
  }
  io.linkResetAgreed := linkResetAgreed
  io.disabledAgreed := disabledAgreed

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    when(stateReg === FDIState.active && past(stateReg) =/= FDIState.active) {
      assert(
        past(io.activeEntryDone),
        "every entry to Active needs the Active Entry Handshake completed"
      )
    }

    when(
      past(stateReg) === FDIState.linkError && stateReg =/= FDIState.linkError
    ) {
      assert(past(residencyDone), "LinkError residency was left too early")
    }

    // sec 10.3 forbids Active.PMNAK to L1 or L2.
    when(
      (stateReg === FDIState.l1 || stateReg === FDIState.l2) &&
        past(stateReg) =/= stateReg
    ) {
      assert(
        past(stateReg) === FDIState.active && past(pmAgreed),
        "L1 or L2 entered other than from Active with a PM agreement"
      )
    }
    when(
      stateReg === FDIState.activePmNak && past(stateReg) =/= stateReg
    ) {
      assert(
        past(stateReg) === FDIState.active,
        "Active.PMNAK entered other than from Active"
      )
    }
  }

  def past(x: FDIState.Type): FDIState.Type =
    RegNext(x, FDIState.reset)
  def past(x: Bool): Bool = RegNext(x, false.B)
}
