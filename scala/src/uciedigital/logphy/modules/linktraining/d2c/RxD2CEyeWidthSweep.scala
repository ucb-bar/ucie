/*
  The Receiver-initiated Data to Clock eye width sweep (sec 4.5.1.4).
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.common._
import chisel3._
import chisel3.util._

// The directions are those of the module that uses the requester.
class RxInitEyeWidthSweepRequesterInterfaceIO(afeParams: AfeParams)
    extends Bundle {
  val done = Input(Bool())
  val eyeSweepTestResults = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))

  val start = Output(Bool())
  val linkTrainingParameters = Flipped(new LinkOperationParameters)
  val patternType = Output(PatternSelect())
}

class RxD2CEyeWidthSweepRequester(
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
    val linkTrainingParameters = new LinkOperationParameters
    val rxInitEyeWidthSweepResults =
      Output(Valid(Vec(afeParams.mbLanes, UInt(1.W))))
    val partnerEyeEdges = Output(Valid(new PartnerEyeEdges))
  })

  // ===========================================================================
  // State and sideband
  // ===========================================================================
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val numStates = 5
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  // Set when state 3 takes the next LFSR_CLEAR_ERROR_REQ (sec 4.5.1.4 step 6).
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

  // ===========================================================================
  // Results
  // ===========================================================================
  // The aggregate and Valid Lane results go in rxInitEyeWidthSweepResults(0).
  val rxInitEyeWidthSweepResults = WireInit(
    VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W)))
  )
  switch(comparisonModeReg) {
    is(ComparisonMode.AGGREGATE) {
      rxInitEyeWidthSweepResults(
        0
      ) := io.patternReaderIo.resp.bits.aggregateStatus
    }
    is(ComparisonMode.PERLANE) {
      for (i <- 0 until afeParams.mbLanes) {
        rxInitEyeWidthSweepResults(i) := io.patternReaderIo.resp.bits
          .perLaneStatusBits(i)
      }
    }
  }

  val resultValid = RegInit(false.B)
  val resultsReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))

  io.rxInitEyeWidthSweepResults.valid := resultValid
  io.rxInitEyeWidthSweepResults.bits := resultsReg

  // ===========================================================================
  // Start and pattern reader
  // ===========================================================================
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

  // The resp waits for the reader to re-arm (sec 4.5.1.4 step 2).
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

  // In an Rx-initiated test the receiver uses its own threshold (sec 9.5.3.29).
  io.patternReaderIo.req.bits.errorThreshold := maxErrorThresholdReg

  // Link operations never use the consecutive count.
  io.patternReaderIo.req.bits.doConsecutiveCount := false.B
  io.patternReaderIo.done := false.B
  io.patternReaderIo.resp.ready := false.B

  io.partnerEyeEdges.valid := false.B
  io.partnerEyeEdges.bits.left := 0.U
  io.partnerEyeEdges.bits.right := 0.U

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
      resultValid := false.B
      when(startArmed) {
        comparisonModeReg := io.linkTrainingParameters.comparisonMode.asTypeOf(
          ComparisonMode()
        )
        maxErrorThresholdReg := io.linkTrainingParameters.maxErrorThreshold
        gotLFSRClearReq := false.B

        sbMsgExchanger.io.req.valid := true.B
        sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
          SBM.START_RX_INIT_D2C_EYE_SWEEP_REQ,
          msgInfo = maxErrorThreshold,
          data = startReqData
        )
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_RX_INIT_D2C_EYE_SWEEP_RESP
        when(sbMsgExchanger.io.exchDone) {
          nextState := 1.U
          inProgress := true.B
          startArmed := false.B

          // Start at Fail, because state 2 ORs each phase result into it.
          resultsReg.foreach(_ := 0.U)
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
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.RX_INIT_D2C_RESULTS_REQ

      io.patternReaderIo.done := sbMsgExchanger.io.msgReceived

      // The pattern can still arrive, so wait for verdictFinal.
      sbMsgExchanger.io.req.valid :=
        io.patternReaderIo.resp.valid && io.patternReaderIo.verdictFinal
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.RX_INIT_D2C_RESULTS_RESP,
        msgInfo = msgInfoField,
        data = dataField
      )

      when(sbMsgExchanger.io.exchDone) {
        io.patternReaderIo.resp.ready := true.B

        // A Lane passes if it passes at any phase (sec 4.5.1.4 step 6).
        for (i <- 0 until afeParams.mbLanes) {
          resultsReg(i) := resultsReg(i) | rxInitEyeWidthSweepResults(i)
        }
        nextState := 3.U
      }
    }
    is(3.U) {
      when(io.sbLaneIo.rx.valid) {
        when(SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LFSR_CLEAR_ERROR_REQ)) {
          io.sbLaneIo.rx.ready := true.B
          gotLFSRClearReq := true.B
          nextState := 1.U
          resultValid := false.B
        }.elsewhen(
          SBMsgCompare(
            io.sbLaneIo.rx.bits.data,
            SBM.RX_INIT_D2C_SWEEP_DONE_WITH_RESULTS
          )
        ) {
          io.partnerEyeEdges.valid := true.B
          io.partnerEyeEdges.bits.right :=
            SBMsgField.data(io.sbLaneIo.rx.bits.data)(15, 8)
          io.partnerEyeEdges.bits.left :=
            SBMsgField.data(io.sbLaneIo.rx.bits.data)(7, 0)
          io.sbLaneIo.rx.ready := true.B
          nextState := 4.U
          resultValid := true.B
        }
      }
    }
    is(4.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.END_RX_INIT_D2C_EYE_SWEEP_REQ
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_RX_INIT_D2C_EYE_SWEEP_RESP

      when(sbMsgExchanger.io.exchDone) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
        resultValid := false.B
      }
    }
  }
}

class RxInitEyeWidthSweepResponderInterfaceIO(afeParams: AfeParams)
    extends Bundle {
  val done = Input(Bool())
  val remoteEyeSweepTestResults = Flipped(
    Valid(Vec(afeParams.mbLanes, UInt(1.W)))
  )

  val start = Output(Bool())
  val patternType = Output(PatternSelect())
}

class RxD2CEyeWidthSweepResponder(
    afeParams: AfeParams,
    sbParams: SidebandParams
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect())
    val patternWriterIo = Flipped(new PatternWriterIO)
    val usingPatternWriter = Output(Bool())
    val decision = Flipped(Decoupled(PhyEyeDecisionKind()))
    val localEyeEdges = Input(new PhyEyeEdges(afeParams.bridge))
    val rxEyeWidthSweepRemoteResults =
      Output(Valid(Vec(afeParams.mbLanes, UInt(1.W))))
  })

  // ===========================================================================
  // State and sideband
  // ===========================================================================
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE)

  val numStates = 5
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // ===========================================================================
  // Pattern writer and results
  // ===========================================================================
  assert(
    ((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
      (io.patternType === PatternSelect.LFSR))),
    "PatternType should only be VALTRAIN or LFSR"
  )
  io.patternWriterIo.req.bits.patternType := patternTypeReg
  io.patternWriterIo.req.valid := false.B
  io.done := false.B

  val rxInitEyeWidthSweepRemoteResults = WireInit(
    VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W)))
  )
  switch(comparisonModeReg) {
    is(ComparisonMode.AGGREGATE) {
      // Table 7-11 MsgInfo[4] is the cumulative result of all Lanes.
      rxInitEyeWidthSweepRemoteResults(0) := SBMsgField.msgInfo(
        sbMsgExchanger.io.resp.bits
      )(4)
    }
    is(ComparisonMode.PERLANE) {
      switch(patternTypeReg) {
        is(PatternSelect.VALTRAIN) {
          // Table 7-11 MsgInfo[5] is the Valid Lane comparison result.
          rxInitEyeWidthSweepRemoteResults(0) := SBMsgField.msgInfo(
            sbMsgExchanger.io.resp.bits
          )(5)
        }
        is(PatternSelect.LFSR) {
          for (i <- 0 until afeParams.mbLanes) {
            rxInitEyeWidthSweepRemoteResults(i) := sbMsgExchanger.io.resp.bits(
              64 + i
            )
          }
        }
      }
    }
  }

  val resultValid = RegInit(false.B)
  val resultsReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))

  io.rxEyeWidthSweepRemoteResults.valid := resultValid
  io.rxEyeWidthSweepRemoteResults.bits := resultsReg

  val inProgress = RegInit(false.B)
  io.usingPatternWriter := inProgress

  io.decision.ready := false.B

  // ===========================================================================
  // State machine
  // ===========================================================================
  currentState := nextState
  switch(currentState) {
    is(0.U) {
      when(io.start) {
        resultValid := false.B
        patternTypeReg := io.patternType
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_RX_INIT_D2C_EYE_SWEEP_REQ

        when(sbMsgExchanger.io.resp.valid) {
          // Table 7-11 Comparison Mode is data bit 59.
          comparisonModeReg := SBMsgField
            .data(sbMsgExchanger.io.resp.bits)(59)
            .asTypeOf(ComparisonMode())
        }
        sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
        sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
          SBM.START_RX_INIT_D2C_EYE_SWEEP_RESP
        )

        when(sbMsgExchanger.io.exchDone) {
          nextState := 1.U
          inProgress := true.B
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
        SBM.RX_INIT_D2C_RESULTS_REQ
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.RX_INIT_D2C_RESULTS_RESP

      when(sbMsgExchanger.io.resp.valid) {
        resultValid := true.B
        resultsReg := rxInitEyeWidthSweepRemoteResults
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

      // Table 7-11 has 8-bit edges and no "no result" code, so 0 until valid.
      val sweepEdges = Mux(
        io.localEyeEdges.valid,
        Cat(io.localEyeEdges.right(7, 0), io.localEyeEdges.left(7, 0)),
        0.U(16.W)
      )
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.RX_INIT_D2C_SWEEP_DONE_WITH_RESULTS,
        data = Cat(0.U(48.W), sweepEdges)
      )
      when(sbMsgExchanger.io.msgSent) {
        nextState := 4.U
      }
    }
    is(4.U) {
      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.END_RX_INIT_D2C_EYE_SWEEP_RESP
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_RX_INIT_D2C_EYE_SWEEP_REQ

      when(sbMsgExchanger.io.exchDone) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
        resultValid := false.B
      }
    }
  }
}
