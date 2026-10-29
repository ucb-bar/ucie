/*
  Runs software training jobs, or by default the tests sec 4.5.3.4 requires.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.common._

// A different trainer implements this bundle, so nothing else changes.
class PhyTrainerIO(afeParams: AfeParams) extends Bundle {
  val slots = afeParams.slots

  val ltsmState = Input(LTSMState())
  val speedMode = Input(SpeedMode())
  val hasOperation = Input(Bool())
  val opRequired = Input(Bool())

  val residencyLeft = Input(UInt(LogPhyTrainerParams.residencyWidth.W))

  val holdCodes = Input(Bool())

  val operationOpen = Output(Bool())

  val blocked = Input(Bool())

  val action = Decoupled(new PhyTrainAction)
  val result = Flipped(Decoupled(new PhyTrainResult(afeParams)))

  val eyeDecision = Decoupled(new PhyEyeDecision)

  val codeWritten = Input(Valid(new PhyCodeWrite(afeParams.bridge, slots)))

  val codeWriteRefused = Input(Bool())

  val remoteResult = Flipped(Valid(new PhyTrainResult(afeParams)))

  val settleCycles = Input(UInt(8.W))

  val ctrl = new SwTrainerCtrlIO(afeParams)
}

// How software drives this trainer. Another trainer need not present it.
class SwTrainerCtrlIO(afeParams: AfeParams) extends Bundle {
  val swJobsEn = Input(Bool())

  val autoFinishEn = Input(Bool())
  val finishWatermark = Input(UInt(LogPhyTrainerParams.residencyWidth.W))

  val substateEn = Input(UInt(MBTrainOperation.table.size.W))

  val job = Flipped(Decoupled(new PhyTrainJob))

  val finish = Input(Bool())
  val abort = Input(Bool())

  val result = Decoupled(new PhyTrainResultEntry(afeParams))

  val jobBusy = Output(Bool())
  val waitingForJob = Output(Bool())
  val substate = Output(LTSMState())
  val residencyLeft = Output(UInt(LogPhyTrainerParams.residencyWidth.W))
  val fifoLevel = Output(UInt(log2Ceil(LogPhyTrainerParams.resultDepth + 1).W))

  val codeSettling = Output(Bool())

  val refused = Output(Bool())

  val overflow = Output(Bool())
}

object LogPhyTrainerParams {
  // 8 ms at 800 MHz fits 23 bits, and the 24th covers the +50% of sec 4.5.
  val residencyWidth: Int = 24

  // Eight entries batch a sweep, and more would only let results go stale.
  val resultDepth: Int = 8
}

object SwTrainerCtrlIO {

  // Drives the surface so the sec 4.5.3.4 default runs and nothing waits.
  def tieOff(ctrl: SwTrainerCtrlIO): Unit = {
    ctrl.swJobsEn := false.B
    ctrl.autoFinishEn := false.B
    ctrl.finishWatermark := 0.U

    // This matters only once swJobsEn is set.
    ctrl.substateEn := ((BigInt(1) << MBTrainOperation.table.size) - 1).U
    ctrl.job.valid := false.B
    ctrl.job.bits := 0.U.asTypeOf(chiselTypeOf(ctrl.job.bits))
    ctrl.finish := false.B
    ctrl.abort := false.B
    ctrl.result.ready := true.B
  }
}

class SwTrainer(afeParams: AfeParams) extends Module {
  val p = afeParams.bridge
  val slots = afeParams.slots
  val codeW = p.maxCodeWidth

  val io = IO(new Bundle {
    val trainer = new PhyTrainerIO(afeParams)
  })

  // PhyTrainerIO takes this module's view, so `io.trainer` is not Flipped.
  val tr = io.trainer
  val ct = tr.ctrl

  object TrainerState extends ChiselEnum {
    val sIdle, sApply, sRunning, sFinish = Value
  }
  val state = RegInit(TrainerState.sIdle)

  // ===========================================================================
  // Per sub-state bookkeeping
  // ===========================================================================
  // One run per entry, or the default test repeats for the whole sub-state.
  val ranThisState = RegInit(false.B)
  val lastState = RegInit(LTSMState.sRESET)
  val stateChanged = tr.ltsmState =/= lastState
  lastState := tr.ltsmState

  // One bit per row of MBTrainOperation.table, in its order.
  val substateEnabled = MBTrainOperation.table.zipWithIndex
    .map { case (row, i) =>
      (tr.ltsmState === row.state) && ct.substateEn(i)
    }
    .reduce(_ || _)

  // Below the watermark the trainer leaves before the sec 4.5 timeout.
  val watermarked =
    ct.autoFinishEn && (tr.residencyLeft < ct.finishWatermark)

  // ===========================================================================
  // The job in flight
  // ===========================================================================
  val job = Reg(new PhyTrainJob)
  val jobValid = RegInit(false.B)

  // An operation must wait out the settle, or it measures the old code.
  val settleLeft = RegInit(0.U(8.W))

  // A result reports the last code written, or codeValid says there was none.
  val lastCode = Reg(UInt(codeW.W))
  val lastKnob = Reg(UInt(log2Ceil(p.numKnobs max 2).W))
  val lastSlot = Reg(UInt(log2Ceil(slots.count max 2).W))
  val lastCodeValid = RegInit(false.B)
  when(tr.codeWritten.valid) {
    lastCode := tr.codeWritten.bits.code
    lastKnob := tr.codeWritten.bits.knobSel
    lastSlot := tr.codeWritten.bits.slotSel
    lastCodeValid := true.B
    settleLeft := tr.settleCycles
  }.elsewhen(settleLeft =/= 0.U) {
    settleLeft := settleLeft - 1.U
  }

  // ===========================================================================
  // Results
  // ===========================================================================
  val fifo = Module(
    new Queue(
      new PhyTrainResultEntry(afeParams),
      LogPhyTrainerParams.resultDepth
    )
  )
  val seqCounter = RegInit(0.U(4.W))
  val overflowPulse = WireDefault(false.B)
  ct.result <> fifo.io.deq
  ct.fifoLevel := fifo.io.count
  ct.overflow := overflowPulse

  def pushEntry(r: PhyTrainResult, remote: Bool): Unit = {
    val v = Wire(new PhyTrainResultEntry(afeParams))
    v.passVec := VecInit((0 until slots.count).map { sl =>
      if (sl < afeParams.mbLanes) r.pass(sl)
      else if (sl == slots.valid) r.validPass
      else true.B
    }).asUInt
    v.aggregatePass := v.passVec.andR
    v.code := lastCode
    v.knobSel := lastKnob
    v.slotSel := lastSlot
    v.codeValid := lastCodeValid
    v.seq := seqCounter
    v.isRemote := remote
    fifo.io.enq.bits := v
  }

  fifo.io.enq.valid := false.B
  fifo.io.enq.bits := 0.U.asTypeOf(new PhyTrainResultEntry(afeParams))

  // The sideband cannot wait, so only this capture can overflow.
  when(tr.remoteResult.valid) {
    fifo.io.enq.valid := true.B
    pushEntry(tr.remoteResult.bits, true.B)
    when(fifo.io.enq.ready) {
      seqCounter := seqCounter + 1.U
    }.otherwise {
      overflowPulse := true.B
    }
  }

  // ===========================================================================
  // Outputs and decisions
  // ===========================================================================
  tr.action.valid := false.B
  tr.action.bits.kind := PhyTrainActionKind.FinishState
  tr.action.bits.testKind := TrainingTestType.PointTest
  tr.action.bits.clockPhase := SemanticClockPhase.Center
  tr.result.ready := false.B

  // A Tx sweep needs an answer, or the sec 4.5 residency ends in TRAINERROR.
  val eyeFinishPending = RegInit(false.B)
  tr.eyeDecision.valid := eyeFinishPending
  tr.eyeDecision.bits.kind := PhyEyeDecisionKind.Finish
  tr.eyeDecision.bits.target := PhyEyeDecisionTarget.TxRequester
  when(tr.eyeDecision.fire) { eyeFinishPending := false.B }
  when(stateChanged) { eyeFinishPending := false.B }

  ct.job.ready := false.B
  ct.jobBusy := jobValid

  // Software derives the rest from the sub-state with MBTrainOperation.table.
  ct.substate := tr.ltsmState
  ct.residencyLeft := tr.residencyLeft

  ct.codeSettling := settleLeft =/= 0.U
  val refusedPulse = WireDefault(false.B)

  // One flag covers a refused job and a refused code write.
  ct.refused := refusedPulse || tr.codeWriteRefused

  // A phase apply waits while this is open, or it moves the code under test.
  tr.operationOpen := state === TrainerState.sRunning ||
    state === TrainerState.sApply

  // One point test meets sec 4.5.3.4's (AND/OR) rule, whatever the knob table.
  val defaultWantsRun =
    !ct.swJobsEn && tr.hasOperation && tr.opRequired &&
      !tr.holdCodes && !ranThisState
  val softwareActive =
    ct.swJobsEn && tr.hasOperation && substateEnabled && !tr.holdCodes

  ct.waitingForJob :=
    softwareActive && !jobValid && !watermarked && state === TrainerState.sIdle

  // ct.finish is a pulse and the bridge can be busy, so hold it until taken.
  val finishRequested = RegInit(false.B)
  when(ct.finish) { finishRequested := true.B }

  val wantsFinish =
    tr.hasOperation && !ranThisState && !defaultWantsRun &&
      (!softwareActive || watermarked || finishRequested)

  when(stateChanged) {
    ranThisState := false.B

    // A finish must not carry into the next sub-state.
    finishRequested := false.B
    jobValid := false.B
    state := TrainerState.sIdle

    // A zero code is legal, so zero cannot mean none.
    lastCodeValid := false.B
  }

  // ===========================================================================
  // Sequencing
  // ===========================================================================
  def issueRun(
      testKind: TrainingTestType.Type,
      phase: SemanticClockPhase.Type
  ): Unit = {
    tr.action.valid := true.B
    tr.action.bits.kind := PhyTrainActionKind.RunTest
    tr.action.bits.testKind := testKind
    tr.action.bits.clockPhase := phase
  }

  // No action issues while `blocked`, as the code must settle before a test.
  switch(state) {
    is(TrainerState.sIdle) {
      when(ct.abort) {
        jobValid := false.B
      }.elsewhen(!tr.blocked && defaultWantsRun) {
        issueRun(TrainingTestType.PointTest, SemanticClockPhase.Center)
        when(tr.action.fire) { state := TrainerState.sRunning }
      }.elsewhen(!tr.blocked && wantsFinish) {
        tr.action.valid := true.B
        tr.action.bits.kind := PhyTrainActionKind.FinishState
        when(tr.action.fire) {
          ranThisState := true.B
          finishRequested := false.B
        }
      }.elsewhen(softwareActive && !watermarked && !tr.blocked) {
        // The LTSM can move on alone, so a job must name the current sub-state.
        val fresh = ct.job.bits.substate === tr.ltsmState

        ct.job.ready := true.B
        when(ct.job.fire) {
          when(fresh) {
            job := ct.job.bits
            jobValid := true.B
            state := TrainerState.sApply
          }.otherwise {
            refusedPulse := true.B
          }
        }
      }
    }

    is(TrainerState.sApply) {
      when(ct.abort) {
        jobValid := false.B
        state := TrainerState.sIdle
      }.otherwise {
        // The settle timer runs from the code write, job or no job.
        when(settleLeft === 0.U && !tr.blocked) {
          // Only an Rx-initiated point test uses the clock phase (Table 4-5).
          issueRun(job.testKind, job.clockPhase)
          when(tr.action.fire) { state := TrainerState.sRunning }
        }
      }
    }

    is(TrainerState.sRunning) {
      // Our own results wait for room, so a slow reader loses none of them.
      tr.result.ready := !tr.blocked && fifo.io.enq.ready && !tr.remoteResult.valid

      // Abort drops the job. The engines still finish, so collect and drop it.
      when(ct.abort) { jobValid := false.B }

      when(tr.result.fire) {
        // Answer sec 4.5.1.2 step 6 with Finish, so a job is one phase.
        when(job.testKind === TrainingTestType.EyeWidthSweep && jobValid) {
          eyeFinishPending := true.B
        }

        when(jobValid && !ct.abort) {
          fifo.io.enq.valid := true.B
          pushEntry(tr.result.bits, false.B)
          seqCounter := seqCounter + 1.U
          jobValid := false.B
          state := TrainerState.sIdle
        }.otherwise {
          // The default runs one test and leaves. Nothing reads its verdict.
          state := TrainerState.sFinish
        }
      }
    }

    is(TrainerState.sFinish) {
      when(!tr.blocked) {
        tr.action.valid := true.B
        tr.action.bits.kind := PhyTrainActionKind.FinishState
        when(tr.action.fire) {
          ranThisState := true.B
          state := TrainerState.sIdle
        }
      }
    }
  }
}
