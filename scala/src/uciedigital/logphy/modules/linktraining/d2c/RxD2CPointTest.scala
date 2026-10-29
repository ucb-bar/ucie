/*
  The Receiver-initiated Data to Clock point test (sec 4.5.1.3).
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._

import chisel3._
import chisel3.util._

// The directions are those of the module that uses the requester.
class RxInitPtTestRequesterInterfaceIO(afeParams: AfeParams) extends Bundle {
  val done = Input(Bool())
  val ptTestResults = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))

  val start = Output(Bool())
  val linkTrainingParameters = Flipped(new LinkOperationParameters)
  val patternType = Output(PatternSelect())
}

class RxD2CPointTestRequester(afeParams: AfeParams, sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect())
    val patternReaderIo = Flipped(new PatternReaderClientIO(afeParams.mbLanes))
    val usingPatternReader = Output(Bool())
    val linkTrainingParameters = new LinkOperationParameters
    val rxInitPtTestLocalResults =
      Output(Valid(Vec(afeParams.mbLanes, UInt(1.W))))
  })

  // ===========================================================================
  // State and sideband
  // ===========================================================================
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)
  val comparisonModeReg = RegInit(0.U(1.W))
  val maxErrorThresholdReg = RegInit(0.U(16.W))

  val numStates = 4
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // ===========================================================================
  // Results
  // ===========================================================================
  // The aggregate and Valid Lane results go in rxInitPtTestLocalResults(0).
  val rxInitPtTestLocalResults = WireInit(
    VecInit(Seq.fill(afeParams.mbLanes)(false.B))
  )
  switch(comparisonModeReg) {
    is(1.U) { // Aggregate comparison
      rxInitPtTestLocalResults(
        0
      ) := io.patternReaderIo.resp.bits.aggregateStatus
    }
    is(0.U) { // Per-lane comparison
      for (i <- 0 until afeParams.mbLanes) {
        rxInitPtTestLocalResults(i) := io.patternReaderIo.resp.bits
          .perLaneStatusBits(i)
      }
    }
  }

  val resultValid = RegInit(false.B)
  val resultReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))

  io.rxInitPtTestLocalResults.valid := resultValid
  io.rxInitPtTestLocalResults.bits := resultReg

  // ===========================================================================
  // Start and pattern reader
  // ===========================================================================
  assert(
    ((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
      (io.patternType === PatternSelect.LFSR))),
    "PatternType should only be VALTRAIN or LFSR"
  )

  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits.patternType := patternTypeReg
  io.patternReaderIo.req.bits.comparisonMode := comparisonModeReg.asTypeOf(
    ComparisonMode()
  )
  io.patternReaderIo.req.bits.errorThreshold := maxErrorThresholdReg

  // Link operations never use the consecutive count.
  io.patternReaderIo.req.bits.doConsecutiveCount := false.B
  io.patternReaderIo.done := false.B
  io.patternReaderIo.resp.ready := false.B

  io.done := false.B

  val inProgress = RegInit(false.B)

  // Latch the start edge, because callers hold or pulse it (sec 7.1.3.3).
  val startArmed = RegInit(false.B)
  val startPrev = RegNext(io.start, false.B)
  when(io.start && !startPrev && !inProgress) {
    startArmed := true.B

    // MBTrainSM drives patternType for about one cycle, so sample it here.
    patternTypeReg := io.patternType
  }
  io.usingPatternReader := inProgress

  // The resp waits for the reader to re-arm (sec 4.5.1.3 step 2).
  val readerArmed = RegInit(false.B)
  when(currentState =/= nextState) {
    readerArmed := false.B
  }

  val startReqData = Wire(UInt(64.W))
  startReqData := LinkOperationParameters.startReqData(
    io.linkTrainingParameters
  )
  val maxErrorThreshold = io.linkTrainingParameters.maxErrorThreshold

  // ===========================================================================
  // State machine
  // ===========================================================================
  currentState := nextState
  switch(currentState) {
    is(0.U) {
      when(startArmed) {
        resultValid := false.B
        comparisonModeReg := io.linkTrainingParameters.comparisonMode
        maxErrorThresholdReg := io.linkTrainingParameters.maxErrorThreshold

        sbMsgExchanger.io.req.valid := true.B
        sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
          SBM.START_RX_INIT_D2C_POINT_TEST_REQ,
          msgInfo = maxErrorThreshold,
          data = startReqData
        )
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_RX_INIT_D2C_POINT_TEST_RESP

        when(sbMsgExchanger.io.exchDone) {
          nextState := 1.U
          inProgress := true.B
          startArmed := false.B
        }
      }
    }
    is(1.U) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LFSR_CLEAR_ERROR_REQ

      io.patternReaderIo.req.valid :=
        sbMsgExchanger.io.msgReceived && !readerArmed
      when(io.patternReaderIo.req.fire) {
        readerArmed := true.B
      }

      sbMsgExchanger.io.req.valid := readerArmed
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(SBM.LFSR_CLEAR_ERROR_RESP)

      when(sbMsgExchanger.io.exchDone) {
        nextState := 2.U
      }
    }
    is(2.U) {
      assert(
        io.patternReaderIo.req.ready === false.B,
        "Pattern Reader should be running"
      )

      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.RX_INIT_D2C_TX_COUNT_DONE_REQ

      io.patternReaderIo.done := sbMsgExchanger.io.msgReceived

      // Wait for verdictFinal, so words still in flight are counted.
      sbMsgExchanger.io.req.valid :=
        io.patternReaderIo.resp.valid && io.patternReaderIo.verdictFinal
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.RX_INIT_D2C_TX_COUNT_DONE_RESP
      )

      when(io.patternReaderIo.resp.valid && io.patternReaderIo.verdictFinal) {
        resultValid := true.B
        resultReg := rxInitPtTestLocalResults
      }
      io.rxInitPtTestLocalResults.valid := resultValid

      when(sbMsgExchanger.io.exchDone) {
        io.patternReaderIo.resp.ready := true.B
        nextState := 3.U
      }
    }
    is(3.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.END_RX_INIT_D2C_POINT_TEST_REQ
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_RX_INIT_D2C_POINT_TEST_RESP

      when(sbMsgExchanger.io.exchDone) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
        resultValid := false.B
      }
    }
  }

  when(currentState =/= nextState) {
    sbMsgExchanger.io.req.valid := false.B
  }
}

class RxInitPtTestResponderInterfaceIO extends Bundle {
  val done = Input(Bool())

  val start = Output(Bool())
  val patternType = Output(PatternSelect())
}

class RxD2CPointTestResponder(afeParams: AfeParams, sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect())
    val patternWriterIo = Flipped(new PatternWriterIO)
    val usingPatternWriter = Output(Bool())
    val semanticPhase = Flipped(new SemanticPhaseApplyIO)
    val error = Output(Bool())
  })

  // ===========================================================================
  // State and sideband
  // ===========================================================================
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)
  val phaseRequest = RegInit(SemanticClockPhase.Center)
  val phaseRequestValid = RegInit(false.B)
  val waitingForPhase = RegInit(false.B)
  val phaseApplied = RegInit(false.B)
  val phaseError = RegInit(false.B)

  val numStates = 4
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // ===========================================================================
  // Pattern writer
  // ===========================================================================
  assert(
    ((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
      (io.patternType === PatternSelect.LFSR))),
    "PatternType should only be VALTRAIN or LFSR"
  )
  io.patternWriterIo.req.bits.patternType := patternTypeReg
  io.patternWriterIo.req.valid := false.B
  io.done := false.B

  io.semanticPhase.request.valid := phaseRequestValid
  io.semanticPhase.request.bits := phaseRequest
  io.semanticPhase.completion.ready := waitingForPhase
  io.error := phaseError

  when(io.semanticPhase.request.fire) {
    phaseRequestValid := false.B
    waitingForPhase := true.B
  }
  when(io.semanticPhase.completion.fire) {
    waitingForPhase := false.B
    when(
      io.semanticPhase.completion.bits.success &&
        io.semanticPhase.completion.bits.phase === phaseRequest
    ) {
      phaseApplied := true.B
    }.otherwise {
      phaseError := true.B
    }
  }

  val inProgress = RegInit(false.B)
  io.usingPatternWriter := inProgress

  when(currentState === 0.U && !io.start) {
    phaseRequestValid := false.B
    waitingForPhase := false.B
    phaseApplied := false.B
    phaseError := false.B
  }

  // ===========================================================================
  // State machine
  // ===========================================================================
  currentState := nextState
  switch(currentState) {
    is(0.U) {
      when(io.start) {
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_RX_INIT_D2C_POINT_TEST_REQ
        patternTypeReg := io.patternType

        when(
          sbMsgExchanger.io.resp.valid && !phaseRequestValid &&
            !waitingForPhase && !phaseApplied
        ) {
          val encodedPhase = sbMsgExchanger.io.resp.bits(73, 70)
          when(encodedPhase === 0.U) {
            phaseRequest := SemanticClockPhase.Center
            phaseRequestValid := true.B
          }.elsewhen(encodedPhase === 1.U) {
            phaseRequest := SemanticClockPhase.Left
            phaseRequestValid := true.B
          }.elsewhen(encodedPhase === 2.U) {
            phaseRequest := SemanticClockPhase.Right
            phaseRequestValid := true.B
          }.otherwise {
            phaseError := true.B
          }
        }
        when(phaseApplied && !phaseError) {
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
            SBM.START_RX_INIT_D2C_POINT_TEST_RESP
          )
        }
        when(sbMsgExchanger.io.exchDone) {
          nextState := 1.U
          inProgress := true.B
          phaseApplied := false.B
        }
      }
    }
    is(1.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(SBM.LFSR_CLEAR_ERROR_REQ)
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LFSR_CLEAR_ERROR_RESP

      io.patternWriterIo.req.valid := sbMsgExchanger.io.exchDone && io.patternWriterIo.req.ready

      when(io.patternWriterIo.resp.complete) {
        nextState := 2.U
      }
    }
    is(2.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.RX_INIT_D2C_TX_COUNT_DONE_REQ
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.RX_INIT_D2C_TX_COUNT_DONE_RESP

      when(sbMsgExchanger.io.exchDone) {
        nextState := 3.U
      }
    }
    is(3.U) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_RX_INIT_D2C_POINT_TEST_REQ

      when(
        sbMsgExchanger.io.resp.valid && !phaseRequestValid &&
          !waitingForPhase && !phaseApplied
      ) {
        phaseRequest := SemanticClockPhase.Center
        phaseRequestValid := true.B
      }

      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived && phaseApplied
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.END_RX_INIT_D2C_POINT_TEST_RESP
      )

      when(sbMsgExchanger.io.exchDone) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
        phaseApplied := false.B
      }
    }
  }

  when(currentState =/= nextState) {
    sbMsgExchanger.io.req.valid := false.B
  }
}
