/*
  The Transmitter-initiated Data to Clock eye width sweep (sec 4.5.1.2).
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._

import chisel3._
import chisel3.util._

// The directions are those of the module that uses the requester.
class TxInitEyeWidthSweepRequesterInterfaceIO(afeParams: AfeParams)
    extends Bundle {
  val done = Input(Bool())
  val eyeSweepTestResults = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))

  val start = Output(Bool())
  val linkTrainingParameters = Flipped(new LinkOperationParameters)
  val patternType = Output(PatternSelect())
}

class TxD2CEyeWidthSweepRequester(
    afeParams: AfeParams,
    sbParams: SidebandParams
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val patternType = Input(PatternSelect())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternWriterIo = Flipped(new PatternWriterIO)
    val usingPatternWriter = Output(Bool())
    val linkTrainingParameters = new LinkOperationParameters
    val txInitEyeWidthSweepResults = Valid(Vec(afeParams.mbLanes, UInt(1.W)))
    val decision = Flipped(Decoupled(PhyEyeDecisionKind()))
  })

  // ===========================================================================
  // State and sideband
  // ===========================================================================
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE)

  val numStates = 4
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // ===========================================================================
  // Results
  // ===========================================================================
  // The aggregate and Valid Lane results go in txInitEyeWidthSweepResults(0).
  val txInitEyeWidthSweepResults = WireInit(
    VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W)))
  )
  switch(comparisonModeReg) {
    is(ComparisonMode.AGGREGATE) {
      // Table 7-11 MsgInfo[4] is the cumulative result of all Lanes.
      txInitEyeWidthSweepResults(0) := SBMsgField.msgInfo(
        sbMsgExchanger.io.resp.bits
      )(4)
    }
    is(ComparisonMode.PERLANE) {
      switch(patternTypeReg) {
        is(PatternSelect.VALTRAIN) {
          // Table 7-11 MsgInfo[5] is the Valid Lane comparison result.
          txInitEyeWidthSweepResults(0) := SBMsgField.msgInfo(
            sbMsgExchanger.io.resp.bits
          )(5)
        }
        is(PatternSelect.LFSR) {
          for (i <- 0 until afeParams.mbLanes) {
            txInitEyeWidthSweepResults(i) := sbMsgExchanger.io.resp.bits(64 + i)
          }
        }
      }
    }
  }

  val resultValid = RegInit(false.B)
  val resultsReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))

  io.txInitEyeWidthSweepResults.valid := resultValid
  io.txInitEyeWidthSweepResults.bits := resultsReg

  // ===========================================================================
  // Start and pattern writer
  // ===========================================================================
  assert(
    ((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
      (io.patternType === PatternSelect.LFSR))),
    "PatternType should only be VALTRAIN or LFSR"
  )

  val inProgress = RegInit(false.B)

  // Latch the start edge, because callers hold or pulse it (sec 7.1.3.3).
  val startArmed = RegInit(false.B)
  val startPrev = RegNext(io.start, false.B)
  when(io.start && !startPrev && !inProgress) {
    startArmed := true.B

    // MBTrainSM drives patternType for about one cycle, so sample it here.
    patternTypeReg := io.patternType
  }

  io.done := false.B
  io.patternWriterIo.req.valid := false.B
  io.patternWriterIo.req.bits.patternType := patternTypeReg
  io.usingPatternWriter := inProgress

  val startReqData = Wire(UInt(64.W))
  startReqData := LinkOperationParameters.startReqData(
    io.linkTrainingParameters
  )
  val maxErrorThreshold = io.linkTrainingParameters.maxErrorThreshold

  io.decision.ready := false.B

  // ===========================================================================
  // State machine
  // ===========================================================================
  currentState := nextState
  switch(currentState) {
    is(0.U) {
      resultValid := false.B
      when(startArmed) {
        comparisonModeReg := io.linkTrainingParameters.comparisonMode.asTypeOf(
          ComparisonMode()
        )

        sbMsgExchanger.io.req.valid := true.B
        sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
          SBM.START_TX_INIT_D2C_EYE_SWEEP_REQ,
          msgInfo = maxErrorThreshold,
          data = startReqData
        )
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_TX_INIT_D2C_EYE_SWEEP_RESP

        when(sbMsgExchanger.io.exchDone) {
          nextState := 1.U
          inProgress := true.B
          startArmed := false.B
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
        SBM.TX_INIT_D2C_RESULTS_REQ
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TX_INIT_D2C_RESULTS_RESP

      when(sbMsgExchanger.io.resp.valid) {
        resultValid := true.B
        resultsReg := txInitEyeWidthSweepResults
      }

      when(sbMsgExchanger.io.exchDone) {
        io.decision.ready := true.B
        when(io.decision.fire) {
          nextState := Mux(
            io.decision.bits === PhyEyeDecisionKind.Finish,
            3.U,
            1.U
          )
          resultValid := false.B
        }
      }
    }
    is(3.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.END_TX_INIT_D2C_EYE_SWEEP_REQ
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_TX_INIT_D2C_EYE_SWEEP_RESP

      when(sbMsgExchanger.io.exchDone) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
      }
    }
  }
}

class TxInitEyeWidthSweepResponderInterfaceIO extends Bundle {
  val done = Input(Bool())

  val start = Output(Bool())
  val patternType = Output(PatternSelect())
}

class TxD2CEyeWidthSweepResponder(
    afeParams: AfeParams,
    sbParams: SidebandParams
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect())
    val patternReaderIo = Flipped(new PatternReaderClientIO(afeParams.mbLanes))
    val usingPatternReader = Output(Bool())
  })

  // ===========================================================================
  // State and sideband
  // ===========================================================================
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val numStates = 5
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  // Set when state 3 takes the next LFSR_CLEAR_ERROR_REQ (sec 4.5.1.2 step 6).
  val gotLFSRClearReq = RegInit(
    false.B
  )

  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE)
  val maxErrorThresholdReg = RegInit(0.U(16.W))

  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  // State 3 waits for one of two messages, so it drives rx.ready itself.
  when(currentState === 3.U) {
    io.sbLaneIo.rx.ready := false.B
  }.otherwise {
    io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready
  }

  io.done := false.B

  val inProgress = RegInit(false.B)
  io.usingPatternReader := inProgress

  // ===========================================================================
  // Pattern reader
  // ===========================================================================
  // The resp waits for the reader to re-arm (sec 4.5.1.2 step 2).
  val readerArmed = RegInit(false.B)
  when(currentState =/= nextState) {
    readerArmed := false.B
  }

  assert(
    ((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
      (io.patternType === PatternSelect.LFSR))),
    "PatternType should only be VALTRAIN or LFSR"
  )
  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits.patternType := patternTypeReg
  io.patternReaderIo.req.bits.comparisonMode := comparisonModeReg
  io.patternReaderIo.req.bits.errorThreshold := maxErrorThresholdReg

  // Link operations never use the consecutive count.
  io.patternReaderIo.req.bits.doConsecutiveCount := false.B
  io.patternReaderIo.done := false.B
  io.patternReaderIo.resp.ready := false.B

  // ===========================================================================
  // Message fields
  // ===========================================================================
  val dataField = Wire(UInt(64.W))
  val msgInfoField = Wire(UInt(16.W))

  // VALTRAIN compares only the Valid Lane (MsgInfo[5]), so others report Pass.
  val valTrain = patternTypeReg === PatternSelect.VALTRAIN
  val laneBits = io.patternReaderIo.resp.bits.perLaneStatusBits.asUInt
  dataField := Cat(
    0.U((64 - afeParams.mbLanes).W),
    laneBits | Fill(afeParams.mbLanes, valTrain)
  )
  msgInfoField := Cat(
    0.U(10.W),
    !valTrain || laneBits(0), // Valid Lane
    io.patternReaderIo.resp.bits.aggregateStatus.asUInt,
    0.U(4.W)
  )

  // ===========================================================================
  // State machine
  // ===========================================================================
  currentState := nextState
  switch(currentState) {
    is(0.U) {
      when(io.start) {
        patternTypeReg := io.patternType
        gotLFSRClearReq := false.B

        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_TX_INIT_D2C_EYE_SWEEP_REQ

        when(sbMsgExchanger.io.resp.valid) {
          maxErrorThresholdReg := SBMsgField.msgInfo(
            sbMsgExchanger.io.resp.bits
          )

          // Table 7-11 Comparison Mode is data bit 59.
          comparisonModeReg := SBMsgField
            .data(sbMsgExchanger.io.resp.bits)(59)
            .asTypeOf(ComparisonMode())
        }
        sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
        sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
          SBM.START_TX_INIT_D2C_EYE_SWEEP_RESP
        )
        when(sbMsgExchanger.io.exchDone) {
          nextState := 1.U
          inProgress := true.B
        }
      }
    }
    is(1.U) {
      when(!gotLFSRClearReq) {
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LFSR_CLEAR_ERROR_REQ
      }

      io.patternReaderIo.req.valid :=
        (sbMsgExchanger.io.msgReceived || gotLFSRClearReq) && !readerArmed
      when(io.patternReaderIo.req.fire) {
        readerArmed := true.B
      }

      sbMsgExchanger.io.req.valid := readerArmed
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(SBM.LFSR_CLEAR_ERROR_RESP)

      when(sbMsgExchanger.io.msgSent) {
        gotLFSRClearReq := false.B
        nextState := 2.U
      }
    }
    is(2.U) {
      assert(
        io.patternReaderIo.req.ready === false.B,
        "PatternReader should be started"
      )

      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TX_INIT_D2C_RESULTS_REQ

      io.patternReaderIo.done := sbMsgExchanger.io.msgReceived

      // The pattern runs to step 7 (sec 4.5.1.2), so wait for verdictFinal.
      sbMsgExchanger.io.req.valid :=
        io.patternReaderIo.resp.valid && io.patternReaderIo.verdictFinal
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.TX_INIT_D2C_RESULTS_RESP,
        msgInfo = msgInfoField,
        data = dataField
      )

      when(sbMsgExchanger.io.exchDone) {
        io.patternReaderIo.resp.ready := true.B
        nextState := 3.U
      }
    }
    is(3.U) {
      when(io.sbLaneIo.rx.valid) {
        when(SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LFSR_CLEAR_ERROR_REQ)) {
          io.sbLaneIo.rx.ready := true.B
          gotLFSRClearReq := true.B
          nextState := 1.U
        }.elsewhen(
          // Note: The point test End req (85h/04h) has its own responder.
          SBMsgCompare(
            io.sbLaneIo.rx.bits.data,
            SBM.END_TX_INIT_D2C_EYE_SWEEP_REQ
          )
        ) {
          io.sbLaneIo.rx.ready := true.B
          nextState := 4.U
        }
      }
    }
    is(4.U) {
      // Note: Do not check req.ready, because the reader idles late.
      assert(
        !io.patternReaderIo.resp.valid,
        "PatternReader result should have been taken"
      )

      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.END_TX_INIT_D2C_EYE_SWEEP_RESP
      )
      when(sbMsgExchanger.io.msgSent) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
      }
    }
  }
}
