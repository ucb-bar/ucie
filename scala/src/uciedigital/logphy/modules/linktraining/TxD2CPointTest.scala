/*
  Description:
    Transmitter initiated Data to Clock Point Test modules
    Contains TxD2CPointTestRequester, and TxD2CPointTestResponder

    Requester initiates the test, and Responder will be reactive to the remote Die's messages.

    `start` is a level on both sides: the test runs while it is held, and a caller that drops it
    before `done` abandons the test. MBTRAIN.LINKSPEED does this when it exits to PHYRETRAIN with
    Step 2 still running (spec 4.5.3.4.12 Steps 3 and 5: "any outstanding messages are
    abandoned"). An abandoned test goes straight back to idle and sends nothing more, but the
    remote die may already have answered, or asked for, the step that was in flight. That one
    message is claimed and dropped when it lands, until the remote die's
    {PHYRETRAIN.retrain start req} (or a TRAINERROR entry) shows it has nothing more of this test
    to send, or the next test starts.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util._

class TxInitPtTestRequesterInterfaceIO(afeParams: AfeParams) extends Bundle {
  // IOs are in relation to modules using the TX Point Test Requester
  // IN
  val done = Input(Bool())
  val ptTestResults = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))

  // OUT
  val start = Output(Bool())
  val linkTrainingParameters = Flipped(new LinkOperationParameters)
  val patternType = Output(PatternSelect())
}

class TxD2CPointTestRequester(afeParams: AfeParams, sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val patternType = Input(PatternSelect()) // LTSM controls the patternType
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternWriterIo = Flipped(new PatternWriterIO())
    val usingPatternWriter = Output(Bool())
    val linkTrainingParameters = new LinkOperationParameters()
    val txInitPtTestResults = Valid(Vec(afeParams.mbLanes, UInt(1.W)))
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // State registers
  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)
  val comparisonModeReg = RegInit(0.U(1.W))

  val numStates = 4
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  // Defaults
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(
    0.U(5.W),
    0.U(8.W),
    0.U(8.W)
  )
  sbMsgExchanger.io.clear := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // Aggregate result and valid comparison result will be in txInitPtTestResults(0)
  val txInitPtTestResults = WireInit(
    VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W)))
  )
  switch(comparisonModeReg) {
    is(1.U) { // Aggregate comparison
      txInitPtTestResults(0) := sbMsgExchanger.io.resp.bits(18)
    }
    is(0.U) { // Per-lane comparison
      switch(patternTypeReg) {
        is(PatternSelect.VALTRAIN) {
          txInitPtTestResults(0) := sbMsgExchanger.io.resp.bits(19)
        }
        // MBINIT.REPAIRMB runs this test on the Per Lane ID pattern (spec
        // 4.5.3.3.6 Step 2), and its per-Lane results come back in the same
        // data field as LFSR's. Leaving them at 0 read as "no Lane failed" to
        // REPAIRMB, which therefore never repaired or degraded anything.
        is(PatternSelect.LFSR, PatternSelect.PERLANEID) {
          for (i <- 0 until afeParams.mbLanes) {
            txInitPtTestResults(i) := sbMsgExchanger.io.resp.bits(64 + i)
          }
        }
      }
    }
  }

  val resultValid = RegInit(false.B)
  val resultReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))

  io.txInitPtTestResults.valid := resultValid
  io.txInitPtTestResults.bits := resultReg

  // If start is HIGH, then patternType can be VALTRAIN or LFSR, or PERLANEID
  // for MBINIT.REPAIRMB
  assert(
    ((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
      (io.patternType === PatternSelect.LFSR) ||
      (io.patternType === PatternSelect.PERLANEID))),
    "PatternType should only be VALTRAIN, LFSR or PERLANEID"
  )
  io.patternWriterIo.req.bits.patternType := patternTypeReg
  io.patternWriterIo.req.valid := false.B
  io.done := false.B

  val startReqData = Wire(UInt(64.W))
  startReqData := Cat(
    0.U(4.W),
    io.linkTrainingParameters.comparisonMode,
    io.linkTrainingParameters.iterationCount,
    io.linkTrainingParameters.idleCount,
    io.linkTrainingParameters.patternMode,
    io.linkTrainingParameters.clockPhase,
    io.linkTrainingParameters.validPattern,
    io.linkTrainingParameters.dataPattern
  )
  val maxErrorThreshold = io.linkTrainingParameters.maxErrorThreshold

  val inProgress = RegInit(false.B)
  io.usingPatternWriter := inProgress

  currentState := nextState
  switch(currentState) {
    is(0.U) {
      resultValid := false.B
      when(io.start) {
        patternTypeReg := io.patternType
        comparisonModeReg := io.linkTrainingParameters.comparisonMode

        sbMsgExchanger.io.req.valid := true.B
        sbMsgExchanger.io.req.bits := SBMsgCreate(
          SBM.START_TX_INIT_D2C_POINT_TEST_REQ,
          "PHY",
          "PHY",
          true,
          msgInfo = maxErrorThreshold,
          data = startReqData
        )
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_TX_INIT_D2C_POINT_TEST_RESP

        when(sbMsgExchanger.io.exchDone) {
          nextState := 1.U
          inProgress := true.B
        }
      }
    }
    is(1.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := SBMsgCreate(
        SBM.LFSR_CLEAR_ERROR_REQ,
        "PHY",
        "PHY",
        true
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LFSR_CLEAR_ERROR_RESP

      io.patternWriterIo.req.valid := sbMsgExchanger.io.exchDone && io.patternWriterIo.req.ready

      // Change states once transmission of selected pattern is done
      when(io.patternWriterIo.resp.complete) {
        nextState := 2.U
      }
    }
    is(2.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := SBMsgCreate(
        SBM.TX_INIT_D2C_RESULTS_REQ,
        "PHY",
        "PHY",
        true
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TX_INIT_D2C_RESULTS_RESP

      when(sbMsgExchanger.io.resp.valid) {
        resultValid := true.B
        resultReg := txInitPtTestResults
      }

      when(sbMsgExchanger.io.exchDone) {
        nextState := 3.U
        resultValid := false.B
      }
    }
    is(3.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := SBMsgCreate(
        SBM.END_TX_INIT_D2C_POINT_TEST_REQ,
        "PHY",
        "PHY",
        true
      )
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_TX_INIT_D2C_POINT_TEST_RESP

      when(sbMsgExchanger.io.exchDone) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
      }
    }
  }

  // ==========================================================================
  // Abandoning a test (see the header)
  // ==========================================================================
  // A test finishing in the cycle it is abandoned counts as finished.
  val completing = (currentState === 3.U) && sbMsgExchanger.io.exchDone
  val busy = (currentState =/= 0.U) || sbMsgExchanger.io.msgSent
  val abandoning = !io.start && busy && !completing

  // The response to the request this test last put on the wire, if it has not
  // arrived yet: the one message the remote die may still send.
  val staleResp = RegInit(0.U.asTypeOf(Valid(UInt(2.W))))
  val staleRespPatterns = Seq(
    0.U -> SBM.START_TX_INIT_D2C_POINT_TEST_RESP,
    1.U -> SBM.LFSR_CLEAR_ERROR_RESP,
    2.U -> SBM.TX_INIT_D2C_RESULTS_RESP,
    3.U -> SBM.END_TX_INIT_D2C_POINT_TEST_RESP
  )
  val staleRespArrived = io.sbLaneIo.rx.valid && staleResp.valid &&
    staleRespPatterns
      .map { case (state, msg) =>
        (staleResp.bits === state) && SBMsgCompare(
          io.sbLaneIo.rx.bits.data,
          msg
        )
      }
      .reduce(_ || _)
  val remoteDoneWithTest = io.sbLaneIo.rx.valid && Seq(
    SBM.PHYRETRAIN_RETRAIN_START_REQ,
    SBM.TRAINERROR_ENTRY_REQ,
    SBM.TRAINERROR_ENTRY_RESP
  ).map(SBMsgCompare(io.sbLaneIo.rx.bits.data, _)).reduce(_ || _)

  when(abandoning) {
    // Last connect wins over the state's own drives: nothing more goes out.
    sbMsgExchanger.io.req.valid := false.B
    sbMsgExchanger.io.rxRefBitPattern.valid := false.B
    sbMsgExchanger.io.clear := true.B
    io.patternWriterIo.req.valid := false.B
    nextState := 0.U
    inProgress := false.B
    resultValid := false.B
    staleResp.valid := sbMsgExchanger.io.msgSent && !sbMsgExchanger.io.msgReceived
    staleResp.bits := currentState
  }.elsewhen(io.start || remoteDoneWithTest) {
    staleResp.valid := false.B
  }.elsewhen(staleRespArrived) {
    io.sbLaneIo.rx.ready := true.B
    staleResp.valid := false.B
  }
}

class TxInitPtTestResponderInterfaceIO extends Bundle {
  // IOs are in relation to modules using the TX Point Test Responder
  // IN
  val done = Input(Bool())

  // OUT
  val start = Output(Bool())
  val patternType = Output(PatternSelect())
}

class TxD2CPointTestResponder(afeParams: AfeParams, sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect()) // LTSM controls the patternType
    val patternReaderIo = Flipped(new PatternReaderIO(afeParams.mbLanes))
    val usingPatternReader = Output(Bool())
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val numStates = 4
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  val patternTypeReg = RegInit(PatternSelect.VALTRAIN) // from LTSM
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE) // from remote die
  val maxErrorThresholdReg = RegInit(0.U(16.W)) // from remote die

  // Defaults
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(
    0.U(5.W),
    0.U(8.W),
    0.U(8.W)
  )
  sbMsgExchanger.io.clear := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.done := false.B

  // If start is HIGH, then patternType can be VALTRAIN or LFSR, or PERLANEID
  // for MBINIT.REPAIRMB
  assert(
    ((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
      (io.patternType === PatternSelect.LFSR) ||
      (io.patternType === PatternSelect.PERLANEID))),
    "PatternType should only be VALTRAIN, LFSR or PERLANEID"
  )
  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits.patternType := patternTypeReg // from LTSM
  io.patternReaderIo.req.bits.comparisonMode := comparisonModeReg // from remote die
  io.patternReaderIo.req.bits.errorThreshold := maxErrorThresholdReg // from remote die
  // Link operations count errors. The Per Lane ID pattern is only sent by
  // MBINIT.REPAIRMB, where a Lane passes on "at least 16 consecutive
  // iterations" (spec 4.5.3.3.6 Step 2b).
  io.patternReaderIo.req.bits.doConsecutiveCount :=
    patternTypeReg === PatternSelect.PERLANEID
  io.patternReaderIo.done := false.B
  io.patternReaderIo.resp.ready := false.B
  io.patternReaderIo.remoteFuncLanes := "b011".U

  val dataField = Wire(UInt(64.W))
  val msgInfoField = Wire(UInt(16.W))
  // Note: Can register the response if combinational path is long, then next cycle
  // drive the sbMsgExchanger.io.req.valid HIGH
  dataField := Cat(
    0.U((64 - afeParams.mbLanes).W),
    io.patternReaderIo.resp.bits.perLaneStatusBits.asUInt
  )
  msgInfoField := Cat(
    0.U(10.W),
    io.patternReaderIo.resp.bits.perLaneStatusBits(0).asUInt, // Valid status
    io.patternReaderIo.resp.bits.aggregateStatus.asUInt,
    0.U(4.W)
  )

  val inProgress = RegInit(false.B)
  io.usingPatternReader := inProgress

  currentState := nextState
  switch(currentState) {
    is(0.U) {
      when(io.start) {
        patternTypeReg := io.patternType
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_TX_INIT_D2C_POINT_TEST_REQ

        when(sbMsgExchanger.io.resp.valid) {
          maxErrorThresholdReg := sbMsgExchanger.io.resp.bits(21, 14)
          comparisonModeReg := sbMsgExchanger.io.resp
            .bits(123)
            .asTypeOf(ComparisonMode())
        }
        sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
        sbMsgExchanger.io.req.bits := SBMsgCreate(
          SBM.START_TX_INIT_D2C_POINT_TEST_RESP,
          "PHY",
          "PHY",
          true
        )
        when(sbMsgExchanger.io.exchDone) {
          nextState := 1.U
          inProgress := true.B
        }
      }
    }
    is(1.U) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LFSR_CLEAR_ERROR_REQ

      assert(
        io.patternReaderIo.req.ready === true.B,
        "PatternReader should be ready to accept"
      )

      io.patternReaderIo.req.valid := sbMsgExchanger.io.resp.valid
      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := SBMsgCreate(
        SBM.LFSR_CLEAR_ERROR_RESP,
        "PHY",
        "PHY",
        true
      )

      when(sbMsgExchanger.io.exchDone) {
        nextState := 2.U
      }
    }
    is(2.U) {
      assert(
        io.patternReaderIo.req.ready === false.B,
        "PatternReader should be started"
      )

      // Will only receive this message once Partner die wants local RX to stop comparing
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TX_INIT_D2C_RESULTS_REQ

      io.patternReaderIo.done := sbMsgExchanger.io.msgReceived // stop the PatternReader

      sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid
      sbMsgExchanger.io.req.bits := SBMsgCreate(
        SBM.TX_INIT_D2C_RESULTS_RESP,
        "PHY",
        "PHY",
        true,
        msgInfo = msgInfoField,
        data = dataField
      )
      when(sbMsgExchanger.io.exchDone) {
        io.patternReaderIo.resp.ready := true.B
        nextState := 3.U
      }
    }
    is(3.U) {
      assert(
        io.patternReaderIo.req.ready === true.B,
        "PatternReader should be ready to accept"
      )

      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_TX_INIT_D2C_POINT_TEST_REQ

      // Offered until it is sent, not only in the cycle the request lands: the
      // sideband transmit arbiter need not grant it then, and a response
      // offered once and lost left both dies waiting on each other.
      sbMsgExchanger.io.req.valid :=
        sbMsgExchanger.io.resp.valid || sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := SBMsgCreate(
        SBM.END_TX_INIT_D2C_POINT_TEST_RESP,
        "PHY",
        "PHY",
        true
      )
      when(sbMsgExchanger.io.exchDone) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
      }
    }
  }

  // ==========================================================================
  // Abandoning a test (see the header)
  // ==========================================================================
  // A test finishing in the cycle it is abandoned counts as finished.
  val completing = (currentState === 3.U) && sbMsgExchanger.io.exchDone
  // In idle, only a {start req} already taken counts: one still on its way
  // is indistinguishable from none and is left unclaimed.
  val busy = (currentState =/= 0.U) || sbMsgExchanger.io.msgReceived
  val abandoning = !io.start && busy && !completing

  /* The request this state is still waiting for, if any: the one message the
     remote die may still send. Once a state's exchange is complete the next
     state's request is the one that can follow; a request that has landed
     but not been answered is the last, since the remote die waits for the
     answer. */
  val staleReq = RegInit(0.U.asTypeOf(Valid(UInt(2.W))))
  val staleReqPatterns = Seq(
    1.U -> SBM.LFSR_CLEAR_ERROR_REQ,
    2.U -> SBM.TX_INIT_D2C_RESULTS_REQ,
    3.U -> SBM.END_TX_INIT_D2C_POINT_TEST_REQ
  )
  val staleReqArrived = io.sbLaneIo.rx.valid && staleReq.valid &&
    staleReqPatterns
      .map { case (state, msg) =>
        (staleReq.bits === state) && SBMsgCompare(io.sbLaneIo.rx.bits.data, msg)
      }
      .reduce(_ || _)
  val remoteDoneWithTest = io.sbLaneIo.rx.valid && Seq(
    SBM.PHYRETRAIN_RETRAIN_START_REQ,
    SBM.TRAINERROR_ENTRY_REQ,
    SBM.TRAINERROR_ENTRY_RESP
  ).map(SBMsgCompare(io.sbLaneIo.rx.bits.data, _)).reduce(_ || _)

  /* The Pattern Reader is started by {LFSR_CLEAR_ERROR req} and only stopped
     by {TX_INIT_D2C_RESULTS req}. Left running, it would still be counting
     when the next test asks for it, and that test would report the errors
     this window collected. Stop it and take its result on the way out. */
  val readerStarted =
    ((currentState === 1.U) && sbMsgExchanger.io.msgReceived) ||
      (currentState === 2.U)
  val drainReader = RegInit(false.B)

  when(abandoning) {
    // Last connect wins over the state's own drives: nothing more goes out.
    sbMsgExchanger.io.req.valid := false.B
    sbMsgExchanger.io.rxRefBitPattern.valid := false.B
    sbMsgExchanger.io.clear := true.B
    io.patternReaderIo.req.valid := false.B
    nextState := 0.U
    inProgress := false.B
    drainReader := readerStarted
    val exchanged = sbMsgExchanger.io.msgReceived && sbMsgExchanger.io.msgSent
    staleReq.valid := !sbMsgExchanger.io.msgReceived ||
      (exchanged && (currentState =/= 3.U))
    staleReq.bits := Mux(exchanged, currentState + 1.U, currentState)
  }.elsewhen(io.start || remoteDoneWithTest) {
    staleReq.valid := false.B
  }.elsewhen(staleReqArrived) {
    io.sbLaneIo.rx.ready := true.B
    staleReq.valid := false.B
  }

  when(drainReader) {
    io.patternReaderIo.done := true.B
    io.patternReaderIo.resp.ready := true.B
    when(io.patternReaderIo.req.ready) { drainReader := false.B }
  }
  io.usingPatternReader := inProgress || drainReader
}
