/*
  The only module that touches the analog PHY. It owns the codes and calibration.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._
import chisel3.layer.block
import chisel3.layers.Verification
import edu.berkeley.cs.uciedigital.common._

// The state machine side of the bridge.
class PhyTrainIO(afeParams: AfeParams) extends Bundle {
  val ltsmState = Input(LTSMState())
  val speedMode = Input(SpeedMode())
  val residencyLeft = Input(UInt(LogPhyTrainerParams.residencyWidth.W))

  val action = Decoupled(new PhyTrainAction)
  val result = Flipped(Decoupled(new PhyTrainResult(afeParams)))

  val eyeDecision = Decoupled(new PhyEyeDecision)

  val remoteResult = Flipped(Decoupled(new PhyTrainResult(afeParams)))

  val eyeEdges = Output(new PhyEyeEdges(afeParams.bridge))

  val semanticPhase = new SemanticPhaseApplyIO

  val mbInitCalStart = Input(Bool())
  val mbInitCalDone = Output(Bool())
  val txSelfCalStart = Input(Bool())
  val rxClkCalStart = Input(Bool())
}

// TXSELFCAL and RXCLKCAL leave through FinishState, so the bridge issues it.
object BridgeState extends ChiselEnum {
  // The request is CAL_STATUS.cal_pending, so no state issues it.
  val sIdle, sWaitCalTrigger, sWaitCal, sIssueCalDone = Value
}

class PhyBridge(afeParams: AfeParams) extends Module {
  val p = afeParams.bridge
  val slots = afeParams.slots
  val nKnobs = p.numKnobs
  val codeW = p.maxCodeWidth

  // Optional, because a PHY with a fixed clock phase declares no phase knob.
  val clockKnob: Option[Int] =
    p.knob(PhyBridgeParams.ClockPhaseKnob)
      .map(_ => p.index(PhyBridgeParams.ClockPhaseKnob))

  val io = IO(new Bundle {
    val phyTrainIo = new PhyTrainIO(afeParams)
    val config = Input(new PhyBridgeConfig(p, slots))
    val status = Output(new PhyBridgeStatus(p, slots))
    val analog = new PhyBridgeAnalogIO(afeParams)

    val laneCtrl = Input(new MainbandLaneCtrlIO(afeParams))
    val sbTxEn = Input(Bool())
    val sbRxEn = Input(Bool())
    val freqSel = Input(SpeedMode())
    val doElectricalIdleTx = Input(Bool())
    val doElectricalIdleRx = Input(Bool())
    val txEqEnable = Input(Bool())
    val pllLock = Output(Bool())

    val trainerCtrl = new SwTrainerCtrlIO(afeParams)

    val swCodeWrite = Flipped(Valid(new PhyCodeWrite(p, slots)))

    val swCalDone = Input(Bool())

    val calPending = Output(Bool())

    val eyePub = Flipped(Valid(new PhyEyeEdges(p)))
  })

  // ===========================================================================
  // Code registers and analog ports
  // ===========================================================================
  val code = RegInit(
    VecInit(p.knobs.map { k =>
      VecInit(Seq.fill(slots.count)(k.reset.U(codeW.W)))
    })
  )

  // Software can pin one slot's code while the others keep training.
  val applied = Wire(Vec(nKnobs, Vec(slots.count, UInt(codeW.W))))
  for (k <- 0 until nKnobs; s <- 0 until slots.count) {
    applied(k)(s) := Mux(
      io.config.pinnedEnable(k)(s),
      io.config.pinned(k)(s),
      code(k)(s)
    )
  }

  p.knobs.zipWithIndex.foreach { case (knob, k) =>
    if (knob.perLane) {
      val port = io.analog.codes.perLane(knob.name)
      for (s <- 0 until slots.count) port(s) := applied(k)(s)(knob.width - 1, 0)
    } else {
      // A shared knob keeps its single code in slot 0.
      io.analog.codes.shared(knob.name) := applied(k)(0)(knob.width - 1, 0)
    }
  }

  io.status.applied := applied

  // A direct write is refused while a job runs, so a code has one writer.
  val swWriteAllowed = Wire(Bool())
  val swWriteTaken = io.swCodeWrite.valid && swWriteAllowed
  when(swWriteTaken) {
    for (k <- 0 until nKnobs; sl <- 0 until slots.count) {
      when(io.swCodeWrite.bits.knobSel === k.U) {
        // allSlots serves a module-scope knob, or sets every Lane in one write.
        when(
          io.swCodeWrite.bits.allSlots || io.swCodeWrite.bits.slotSel === sl.U
        ) {
          code(k)(sl) := io.swCodeWrite.bits.code
        }
      }
    }
  }

  // ===========================================================================
  // Control passthrough
  // ===========================================================================
  // The bridge only forwards these, so one module is wired to the PHY.
  val tr = io.phyTrainIo
  io.analog.lanes.mbTxDataEn := io.laneCtrl.txDataEn
  io.analog.lanes.mbTxClkEn := io.laneCtrl.txClkEn
  io.analog.lanes.mbTxValidEn := io.laneCtrl.txValidEn
  io.analog.lanes.mbTxTrackEn := io.laneCtrl.txTrackEn
  io.analog.lanes.mbRxDataEn := io.laneCtrl.rxDataEn
  io.analog.lanes.mbRxClkEn := io.laneCtrl.rxClkEn
  io.analog.lanes.mbRxValidEn := io.laneCtrl.rxValidEn
  io.analog.lanes.mbRxTrackEn := io.laneCtrl.rxTrackEn
  io.analog.lanes.sbTxDataEn := io.sbTxEn
  io.analog.lanes.sbTxClkEn := io.sbTxEn
  io.analog.lanes.sbRxDataEn := io.sbRxEn
  io.analog.lanes.sbRxClkEn := io.sbRxEn
  io.analog.freqSel := io.freqSel
  io.analog.doElectricalIdleTx := io.doElectricalIdleTx
  io.analog.doElectricalIdleRx := io.doElectricalIdleRx

  // sec 9.5.3.23 bit 4. A software setting with no training axis (Table 5-6).
  io.analog.txEqEnable := io.txEqEnable

  // One reset per divider, so a Tx idle cannot stop the Rx domain's clock.
  io.analog.txResetb := io.laneCtrl.txClkEn && !io.doElectricalIdleTx
  io.analog.rxResetb := io.laneCtrl.rxClkEn && !io.doElectricalIdleRx
  io.pllLock := io.analog.pllLock

  // ===========================================================================
  // Sub-state decode
  // ===========================================================================
  // MBTrainSM alone drives LINKSPEED, because sec 4.5.3.4.12 step 2 fixes it.
  val hasOperation = MBTrainOperation.hasOperation(tr.ltsmState) &&
    tr.ltsmState =/= LTSMState.sMBTRAIN_LINKSPEED
  val inStage = hasOperation

  val isCal = tr.ltsmState === LTSMState.sMBINIT_CAL ||
    tr.ltsmState === LTSMState.sMBTRAIN_TXSELFCAL ||
    tr.ltsmState === LTSMState.sMBTRAIN_RXCLKCAL

  // sec 4.5.3.4.5 forbids Transmitter or PI phase changes in RXCLKCAL.
  val freezeCodes = tr.ltsmState === LTSMState.sMBTRAIN_RXCLKCAL

  // ===========================================================================
  // Per-speed eye banks
  // ===========================================================================
  // sec 4.5.1.1 takes best known values for each speed, so eyes are per speed.
  val bank = tr.speedMode.asUInt

  // VALVREF and DATAVREF run before any 4 GT/s eye exists, so it can be empty.
  val eyeLeft = RegInit(VecInit(Seq.fill(p.speedBanks)(0.U(codeW.W))))
  val eyeCenter = RegInit(VecInit(Seq.fill(p.speedBanks)(0.U(codeW.W))))
  val eyeRight = RegInit(VecInit(Seq.fill(p.speedBanks)(0.U(codeW.W))))
  val eyeLearned = RegInit(VecInit(Seq.fill(p.speedBanks)(false.B)))

  when(tr.ltsmState === LTSMState.sRESET) {
    eyeLearned.foreach(_ := false.B)
  }

  // Software publishes the eye, and RXCLKCAL refuses it (sec 4.5.3.4.5).
  when(io.eyePub.valid && !freezeCodes) {
    eyeLeft(bank) := io.eyePub.bits.left
    eyeCenter(bank) := io.eyePub.bits.center
    eyeRight(bank) := io.eyePub.bits.right
    eyeLearned(bank) := io.eyePub.bits.valid
  }

  io.status.eye.left := eyeLeft(bank)
  io.status.eye.center := eyeCenter(bank)
  io.status.eye.right := eyeRight(bank)
  io.status.eye.valid := eyeLearned(bank)

  // A level, because sec 4.5.1.4 step 7 sends it after the Finish.
  tr.eyeEdges := io.status.eye
  io.status.codesFrozen := freezeCodes

  // ===========================================================================
  // Semantic clock phase
  // ===========================================================================
  // TRAIN_TIMING with no fallback, so zero means no settle.
  val settleTarget = io.config.settleCycles

  // The completion releases RxD2CPointTestResponder's start resp.
  val phasePending = RegInit(false.B)
  val phaseCompletionValid = RegInit(false.B)
  val phaseApplied = RegInit(SemanticClockPhase.Center)
  val phaseSuccess = RegInit(true.B)
  val phaseSettle = RegInit(0.U(9.W))

  // Our Tx-initiated test holds one PI phase (sec 4.5.1.1), so an apply waits.
  val operationOpen = Wire(Bool())
  val ownTxTestOpen =
    operationOpen && MBTrainOperation.txInitiated(tr.ltsmState)
  tr.semanticPhase.request.ready :=
    !phasePending && !freezeCodes && !ownTxTestOpen
  tr.semanticPhase.completion.valid := phaseCompletionValid
  tr.semanticPhase.completion.bits.phase := phaseApplied
  tr.semanticPhase.completion.bits.success := phaseSuccess

  when(tr.semanticPhase.request.fire) {
    phasePending := true.B
    phaseCompletionValid := false.B
    phaseApplied := tr.semanticPhase.request.bits
    phaseSettle := settleTarget

    // With no best known values yet (sec 4.5.1.1), the current code stands in.
    phaseSuccess := true.B

    // With no clock phase knob there is nothing to move, so the apply succeeds.
    clockKnob.foreach { ck =>
      when(eyeLearned(bank)) {
        switch(tr.semanticPhase.request.bits) {
          is(SemanticClockPhase.Center) { code(ck)(0) := eyeCenter(bank) }
          is(SemanticClockPhase.Left) { code(ck)(0) := eyeLeft(bank) }
          is(SemanticClockPhase.Right) { code(ck)(0) := eyeRight(bank) }
        }
      }
    }
  }
  when(phasePending && !phaseCompletionValid) {
    when(phaseSettle =/= 0.U) {
      phaseSettle := phaseSettle - 1.U
    }.otherwise {
      phaseCompletionValid := true.B
    }
  }
  when(tr.semanticPhase.completion.fire) {
    phasePending := false.B
    phaseCompletionValid := false.B
  }
  val phaseBlocks = phasePending

  // ===========================================================================
  // Calibration state
  // ===========================================================================
  val state = RegInit(BridgeState.sIdle)
  val calKind = RegInit(PhyCalKind.MbInitCal)
  val calDone = RegInit(false.B)

  tr.mbInitCalDone := calDone && calKind === PhyCalKind.MbInitCal

  // The sub-state starts before the request, so it cannot show this.
  io.calPending := state === BridgeState.sWaitCal

  // ===========================================================================
  // Trainer and action producers
  // ===========================================================================
  // The trainer lives here, so a swap touches one file and adds no port.
  val trainer = Module(new SwTrainer(afeParams))
  val tt = trainer.io.trainer

  // A write during a job moves the code under test.
  // The trainer runs no operation in RXCLKCAL, so only software needs the gate.
  swWriteAllowed := !tt.ctrl.jobBusy && !freezeCodes
  tt.codeWritten.valid := swWriteTaken
  tt.codeWritten.bits := io.swCodeWrite.bits
  tt.codeWriteRefused := io.swCodeWrite.valid && !swWriteAllowed

  tt.ltsmState := tr.ltsmState
  tt.speedMode := tr.speedMode
  tt.hasOperation := hasOperation
  tt.opRequired := MBTrainOperation.runsByDefault(tr.ltsmState)
  tt.residencyLeft := tr.residencyLeft

  // holdCodes skips the operations (sec 4.5.3.4), for bench-trained codes.
  tt.holdCodes := io.config.holdCodes
  tt.blocked := phaseBlocks
  tt.settleCycles := settleTarget
  io.trainerCtrl <> tt.ctrl
  operationOpen := tt.operationOpen

  // The bridge answers the partner, so this capture cannot stall the Finish.
  tt.remoteResult.valid := tr.remoteResult.fire
  tt.remoteResult.bits := tr.remoteResult.bits

  // Calibration states run no operation, so the trainer is silent there.
  val calFinish = state === BridgeState.sIssueCalDone
  tr.action.valid := calFinish || tt.action.valid
  tr.action.bits.kind := Mux(
    calFinish,
    PhyTrainActionKind.FinishState,
    tt.action.bits.kind
  )
  tr.action.bits.testKind := tt.action.bits.testKind
  tr.action.bits.clockPhase := tt.action.bits.clockPhase
  tt.action.ready := tr.action.ready && !calFinish

  tr.result.ready := tt.result.ready
  tt.result.valid := tr.result.valid
  tt.result.bits := tr.result.bits

  // We answer a partner's Rx sweep with Finish, as sec 4.5.1.4 step 6 allows.
  val peerPending = RegInit(false.B)
  tr.remoteResult.ready := inStage && !peerPending
  when(tr.remoteResult.fire) { peerPending := true.B }

  // The responder's Finish wins, and `target` keeps the two sessions apart.
  tr.eyeDecision.valid := peerPending || tt.eyeDecision.valid
  tr.eyeDecision.bits.kind := Mux(
    peerPending,
    PhyEyeDecisionKind.Finish,
    tt.eyeDecision.bits.kind
  )

  // The trainer's `target` is unread, because it can only be the Tx session.
  tr.eyeDecision.bits.target := Mux(
    peerPending,
    PhyEyeDecisionTarget.RxResponder,
    PhyEyeDecisionTarget.TxRequester
  )
  tt.eyeDecision.ready := tr.eyeDecision.ready && !peerPending
  when(tr.eyeDecision.fire && peerPending) { peerPending := false.B }
  when(!inStage) { peerPending := false.B }

  // ===========================================================================
  // Calibration sequencing
  // ===========================================================================
  switch(state) {
    is(BridgeState.sIdle) {
      // The starts are held levels, so `!calDone` asks once per entry.
      when(isCal && !calDone) { state := BridgeState.sWaitCalTrigger }
    }

    is(BridgeState.sWaitCalTrigger) {
      when(!isCal) {
        state := BridgeState.sIdle
      }.elsewhen(
        (tr.ltsmState === LTSMState.sMBINIT_CAL && tr.mbInitCalStart) ||
          (tr.ltsmState === LTSMState.sMBTRAIN_TXSELFCAL && tr.txSelfCalStart) ||
          (tr.ltsmState === LTSMState.sMBTRAIN_RXCLKCAL && tr.rxClkCalStart)
      ) {
        calKind := Mux(
          tr.ltsmState === LTSMState.sMBINIT_CAL,
          PhyCalKind.MbInitCal,
          Mux(
            tr.ltsmState === LTSMState.sMBTRAIN_TXSELFCAL,
            PhyCalKind.TxSelfCal,
            PhyCalKind.RxClockCal
          )
        )
        state := BridgeState.sWaitCal
      }
    }

    is(BridgeState.sWaitCal) {
      // Software completes it over MMIO. The spec names no failure here.
      when(io.swCalDone) {
        // This carries no eye. Software publishes one through TRAIN_EYE.
        calDone := true.B

        // Only the MBTRAIN calibrations leave through a FinishState.
        state := Mux(
          tr.ltsmState === LTSMState.sMBINIT_CAL,
          BridgeState.sIdle,
          BridgeState.sIssueCalDone
        )
      }
    }

    is(BridgeState.sIssueCalDone) {
      when(tr.action.fire) { state := BridgeState.sIdle }
    }
  }

  // A pending calibration must still be answered, or the next finds it open.
  when(!inStage && !isCal) {
    when(
      state =/= BridgeState.sWaitCal &&
        !phaseBlocks
    ) {
      state := BridgeState.sIdle
    }
  }

  // TXSELFCAL and RXCLKCAL are adjacent, so isCal cannot clear calDone.
  val calStateStillMatches = MuxLookup(calKind.asUInt, false.B)(
    Seq(
      PhyCalKind.MbInitCal.asUInt -> (tr.ltsmState === LTSMState.sMBINIT_CAL),
      PhyCalKind.TxSelfCal.asUInt ->
        (tr.ltsmState === LTSMState.sMBTRAIN_TXSELFCAL),
      PhyCalKind.RxClockCal.asUInt ->
        (tr.ltsmState === LTSMState.sMBTRAIN_RXCLKCAL)
    )
  )
  when(!calStateStillMatches) { calDone := false.B }

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      // Note: Assert on `applied`, because that is what reaches the analog.
      when(freezeCodes) {
        for (k <- 0 until nKnobs; sl <- 0 until slots.count) {
          assert(
            applied(k)(sl) === RegNext(applied(k)(sl)),
            "FATAL: an analog code moved during MBTRAIN.RXCLKCAL"
          )
        }
      }

      // Step 4 of sec 4.5.3.4.12 wants the optimal phase on leaving LINKSPEED.
      when(tr.eyeEdges.valid) {
        assert(
          eyeLearned(bank),
          "FATAL: reported an eye for a speed whose eye was never learned"
        )
      }
    }
  }
}
