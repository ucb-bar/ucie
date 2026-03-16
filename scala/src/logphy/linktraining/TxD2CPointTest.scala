package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Description:
    Transmitter initiated Data to Clock Point Test modules
    Contains TxD2CPointTestRequester, and TxD2CPointTestResponder

    Requester initiates the test, and Responder will be reactive to the remote Die's messages.
*/

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

class TxD2CPointTestRequester(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val patternType = Input(PatternSelect())  // LTSM controls the patternType    
    val done = Output(Bool())
    val txInitPtTestResults = Valid(Vec(afeParams.mbLanes, UInt(1.W)))
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternWriterIo = Flipped(new PatternWriterIO)
    val usingPatternWriter = Output(Bool())
    val linkTrainingParameters = new LinkOperationParameters        
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
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.resetReg := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // Aggregate result and valid comparison result will be in txInitPtTestResults(0)
  val txInitPtTestResults = WireInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))
  switch(comparisonModeReg) {
    is(1.U) { // Aggregate comparison
      txInitPtTestResults(0) := sbMsgExchanger.io.resp.bits(18)
    }
    is(0.U) { // Per-lane comparison
      switch(patternTypeReg) {
        is(PatternSelect.VALTRAIN) {
          txInitPtTestResults(0) := sbMsgExchanger.io.resp.bits(19)
        }
        is(PatternSelect.LFSR) {
          for(i <- 0 until afeParams.mbLanes) {
          txInitPtTestResults(i) := sbMsgExchanger.io.resp.bits(64 + i)
          }
        }
      }
    }
  }
  io.txInitPtTestResults.valid := false.B
  io.txInitPtTestResults.bits := txInitPtTestResults

  // If start is HIGH, then patternType can either be VALTRAIN or LFSR
  assert(((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
                          (io.patternType === PatternSelect.LFSR))),
        "PatternType should only be VALTRAIN or LFSR")
  io.patternWriterIo.req.bits.patternType := patternTypeReg
  io.patternWriterIo.req.valid := false.B
  io.done := false.B

  val startReqData = Wire(UInt(64.W))
  startReqData := Cat(0.U(4.W),
                      io.linkTrainingParameters.comparisonMode,
                      io.linkTrainingParameters.iterationCount,
                      io.linkTrainingParameters.idleCount,
                      io.linkTrainingParameters.patternMode,
                      io.linkTrainingParameters.clockPhase,
                      io.linkTrainingParameters.validPattern,
                      io.linkTrainingParameters.dataPattern)
  val maxErrorThreshold = io.linkTrainingParameters.maxErrorThreshold

  val inProgress = RegInit(false.B)
  io.usingPatternWriter := inProgress

  currentState := nextState
  switch(currentState) {
    is(0.U) {
      when(io.start) {
        patternTypeReg := io.patternType
        comparisonModeReg := io.linkTrainingParameters.comparisonMode
        
        sbMsgExchanger.io.req.valid := true.B      
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.START_TX_INIT_D2C_POINT_TEST_REQ, 
                                                  "PHY", "PHY", true,
                                                  msgInfo = maxErrorThreshold,
                                                  data = startReqData)
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                     
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_TX_INIT_D2C_POINT_TEST_RESP    

        when(sbMsgExchanger.io.done) {
          nextState := 1.U
          inProgress := true.B
        }
      }
    }
    is(1.U) {
      sbMsgExchanger.io.req.valid := true.B        
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LFSR_CLEAR_ERROR_REQ, "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LFSR_CLEAR_ERROR_RESP

      io.patternWriterIo.req.valid := sbMsgExchanger.io.done && io.patternWriterIo.req.ready     

      // Change states once transmission of selected pattern is done
      when(io.patternWriterIo.resp.complete) {
        nextState := 2.U
      }
    }
    is(2.U) {
      sbMsgExchanger.io.req.valid := true.B     
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.TX_INIT_D2C_RESULTS_REQ, "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TX_INIT_D2C_RESULTS_RESP

      // NOTE: io.txInitPtTestResult.valid will only be valid for one cycle
      io.txInitPtTestResults.valid := sbMsgExchanger.io.resp.valid

      when(sbMsgExchanger.io.done) {
        nextState := 3.U
      }
    }
    is(3.U) {      
      sbMsgExchanger.io.req.valid := true.B       
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.END_TX_INIT_D2C_POINT_TEST_REQ, 
                                                "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_TX_INIT_D2C_POINT_TEST_RESP

      when(sbMsgExchanger.io.done) {        
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
      }
    }
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

class TxD2CPointTestResponder(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect())  // LTSM controls the patternType       
    val patternReaderIo = Flipped(new PatternReaderIO(afeParams.mbLanes))
    val usingPatternReader = Output(Bool())
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val numStates = 4
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)        // from LTSM
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE)     // from remote die
  val maxErrorThresholdReg = RegInit(0.U(16.W))               // from remote die             

  // Defaults
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.resetReg := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.done := false.B

  // If start is HIGH, then patternType can either be VALTRAIN or LFSR
  assert(((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
                          (io.patternType === PatternSelect.LFSR))),
        "PatternType should only be VALTRAIN or LFSR")
  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits.patternType := patternTypeReg             // from LTSM
  io.patternReaderIo.req.bits.comparisonMode := comparisonModeReg       // from remote die
  io.patternReaderIo.req.bits.errorThreshold := maxErrorThresholdReg    // from remote die
  io.patternReaderIo.req.bits.doConsecutiveCount := false.B   // link ops never consecutive counts
  io.patternReaderIo.req.bits.done := false.B
  io.patternReaderIo.req.bits.clear := false.B
 
  val dataField = Wire(UInt(64.W))
  val msgInfoField = Wire(UInt(15.W))
  // Note: Can register the response if combinational path is long, then next cycle
  // drive the sbMsgExchanger.io.req.valid HIGH
  dataField := Cat(0.U((64 - afeParams.mbLanes).W), 
                   io.patternReaderIo.resp.bits.perLaneStatusBits.asUInt)        
  msgInfoField := Cat(0.U(10.W), 
                      io.patternReaderIo.resp.bits.perLaneStatusBits(0).asUInt, // Valid status
                      io.patternReaderIo.resp.bits.aggregateStatus.asUInt,                
                      0.U(4.W))

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
          comparisonModeReg := sbMsgExchanger.io.resp.bits(123).asTypeOf(ComparisonMode())
        }
        sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.START_TX_INIT_D2C_POINT_TEST_RESP, 
                                                  "PHY", "PHY", true)
        when(sbMsgExchanger.io.done) {
          nextState := 1.U
          inProgress := true.B
        }
      }
    }
    is(1.U) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LFSR_CLEAR_ERROR_REQ

      assert(io.patternReaderIo.req.ready === true.B, "PatternReader should be ready to accept")

      io.patternReaderIo.req.valid := sbMsgExchanger.io.resp.valid
      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LFSR_CLEAR_ERROR_RESP, "PHY", "PHY", true)

      when(sbMsgExchanger.io.done) {
        nextState := 2.U
      }
    }
    is(2.U) {
      assert(io.patternReaderIo.req.ready === false.B, "PatternReader should be started")   

      // Will only receive this message once Partner die wants local RX to stop comparing   
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TX_INIT_D2C_RESULTS_REQ

      io.patternReaderIo.req.bits.done := sbMsgExchanger.io.msgReceived // stop the PatternReader

      sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid  
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.TX_INIT_D2C_RESULTS_RESP,
                                                "PHY", "PHY", true,
                                                msgInfo = msgInfoField,
                                                data = dataField)
      when(sbMsgExchanger.io.done) {
        io.patternReaderIo.req.bits.clear := true.B
        nextState := 3.U
      }
    }
    is(3.U) {
      assert(io.patternReaderIo.req.ready === true.B, "PatternReader should be ready to accept")

      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_TX_INIT_D2C_POINT_TEST_REQ

      when(sbMsgExchanger.io.resp.valid) {
        sbMsgExchanger.io.req.valid := true.B
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.END_TX_INIT_D2C_POINT_TEST_RESP,
                                                  "PHY", "PHY", true)
      }
      when(sbMsgExchanger.io.done) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
      }
    }
  }
}

object MainTxD2CPointTestRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CPointTestRequester(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables",
    ),
  )
}

object MainTxD2CPointTestResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CPointTestResponder(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables",
    ),
  )
}