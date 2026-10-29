/*
  The sec 4.6 Runtime Recalibration responder, which sends the Track pattern.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util._

object RuntimeRecalState extends ChiselEnum {
  val sIDLE, sTRACKING, sFINISH = Value
}

object RuntimeRecalStatus {
  // Table 7-9 Status. A MsgInfo of 0 would send the Reserved encoding 00b.
  def driftNotCompensated = 2.U(2.W)
}

class RuntimeRecalResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val inActive = Input(Bool())
    val recalInProgress = Output(Bool())
    val patternSbLaneIo = new SidebandLaneIO(sbParams)
    val adjustSbLaneIo = new SidebandLaneIO(sbParams)
  })

  // sTRACKING waits for both a done req and an adjust req, so two exchangers.
  val patternExch = Module(new SidebandMessageExchanger(sbParams))
  val adjustExch = Module(new SidebandMessageExchanger(sbParams))

  val currentState = RegInit(RuntimeRecalState.sIDLE)
  val nextState = WireInit(currentState)
  currentState := nextState

  patternExch.io.req.bits := 0.U
  patternExch.io.req.valid := false.B
  patternExch.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  patternExch.io.rxRefBitPattern.valid := false.B

  // sFINISH waits for the same done req, so keep one taken in the exit cycle.
  val keepRef = (currentState === RuntimeRecalState.sTRACKING) &&
    (nextState === RuntimeRecalState.sFINISH)
  patternExch.io.clear := (currentState =/= nextState) && !keepRef
  patternExch.io.sbLaneIo <> io.patternSbLaneIo

  adjustExch.io.req.bits := 0.U
  adjustExch.io.req.valid := false.B
  adjustExch.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  adjustExch.io.rxRefBitPattern.valid := false.B

  // Clear on completion too, so a second {tx adjust req} gets an answer.
  adjustExch.io.clear := (currentState =/= nextState) || adjustExch.io.exchDone
  adjustExch.io.sbLaneIo <> io.adjustSbLaneIo

  io.recalInProgress := currentState === RuntimeRecalState.sTRACKING

  switch(currentState) {
    is(RuntimeRecalState.sIDLE) {
      // Arm through ACTIVE, because an unarmed message is dropped at once.
      patternExch.io.rxRefBitPattern.valid := io.inActive
      patternExch.io.rxRefBitPattern.bits := SBM.RECAL_TRACK_PATTERN_INIT_REQ

      // sec 4.6 step 2 starts the pattern only after the response is sent.
      patternExch.io.req.valid := patternExch.io.msgReceived
      patternExch.io.req.bits := PhyLinkMsg.create(
        SBM.RECAL_TRACK_PATTERN_INIT_RESP
      )

      when(patternExch.io.exchDone) {
        nextState := RuntimeRecalState.sTRACKING
      }
    }
    is(RuntimeRecalState.sTRACKING) {
      patternExch.io.rxRefBitPattern.valid := true.B
      patternExch.io.rxRefBitPattern.bits := SBM.RECAL_TRACK_PATTERN_DONE_REQ

      patternExch.io.req.valid := patternExch.io.msgReceived
      patternExch.io.req.bits := PhyLinkMsg.create(
        SBM.RECAL_TRACK_PATTERN_DONE_RESP
      )

      adjustExch.io.rxRefBitPattern.valid := true.B
      adjustExch.io.rxRefBitPattern.bits := SBM.RECAL_TRACK_TX_ADJUST_REQ

      // sec 4.6 step 5 needs an answer, so send Drift not Compensated at once.
      adjustExch.io.req.valid := adjustExch.io.msgReceived && !patternExch.io.msgReceived
      adjustExch.io.req.bits := PhyLinkMsg.create(
        SBM.RECAL_TRACK_TX_ADJUST_RESP,
        msgInfo = RuntimeRecalStatus.driftNotCompensated.pad(16)
      )

      when(patternExch.io.exchDone) {
        nextState := RuntimeRecalState.sIDLE
      }
    }
    is(RuntimeRecalState.sFINISH) {
      // sec 4.6 step 6 puts no ACTIVE condition on the done handshake.
      patternExch.io.rxRefBitPattern.valid := true.B
      patternExch.io.rxRefBitPattern.bits := SBM.RECAL_TRACK_PATTERN_DONE_REQ

      patternExch.io.req.valid := patternExch.io.msgReceived
      patternExch.io.req.bits := PhyLinkMsg.create(
        SBM.RECAL_TRACK_PATTERN_DONE_RESP
      )

      // The sub-state reset also ends it, as the sideband is RAW in RESET.
      when(patternExch.io.exchDone || io.inActive) {
        nextState := RuntimeRecalState.sIDLE
      }
    }
  }

  // Use the exit edge, so sFINISH runs once. This last connect wins.
  val wasActive = RegNext(io.inActive, false.B)
  when(wasActive && !io.inActive) {
    nextState := RuntimeRecalState.sFINISH
  }

  when(currentState =/= nextState) {
    patternExch.io.req.valid := false.B
    adjustExch.io.req.valid := false.B
  }
}
