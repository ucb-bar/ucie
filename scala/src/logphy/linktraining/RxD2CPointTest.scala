package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Description:
    Receiver initiated Data to Clock Point Test modules
    Contains RxD2CPointTestRequester, and RxD2CPointTestResponder

    Requester initiates the test, and Responder will be reactive to the remote Die's messages.
*/
class RxInitPtTestRequesterInterfaceIO(afeParams: AfeParams) extends Bundle {
  // IOs are in relation to modules using the RX Point Test Requester
  // IN
  val done = Input(Bool())
  val ptTestResults = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))

  // OUT
  val start = Output(Bool())          
  val linkTrainingParameters = Flipped(new LinkOperationParameters)
  val patternType = Output(PatternSelect())
}

class RxD2CPointTestRequester(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val rxInitPtTestResults = Output(Valid(Vec(afeParams.mbLanes, Bool())))
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect())  // LTSM controls the patternType    
    val patternReaderIo = Flipped(new PatternReaderIO(afeParams.mbLanes))
    val usingPatternReader = Output(Bool())
    val linkTrainingParameters = new LinkOperationParameters        
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)
  val comparisonModeReg = RegInit(0.U(1.W))  
  val maxErrorThresholdReg = RegInit(0.U(16.W))             

  val numStates = 4
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.resetReg := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // Aggregate result and valid comparison result will be in rxInitPtTestResults(0)
  val rxInitPtTestResults = WireInit(VecInit(Seq.fill(afeParams.mbLanes)(false.B)))
  switch(comparisonModeReg) {
    is(1.U) { // Aggregate comparison
      rxInitPtTestResults(0) := io.patternReaderIo.resp.bits.aggregateStatus
    }
    is(0.U) { // Per-lane comparison
      for(i <- 0 until afeParams.mbLanes) {
        rxInitPtTestResults(i) := io.patternReaderIo.resp.bits.perLaneStatusBits(i)
      }
    }
  }
  io.rxInitPtTestResults.valid := false.B
  io.rxInitPtTestResults.bits := rxInitPtTestResults

  // If start is HIGH, then patternType can either be VALTRAIN or LFSR
  assert(((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
                          (io.patternType === PatternSelect.LFSR))),
        "PatternType should only be VALTRAIN or LFSR")
  
  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits.patternType := patternTypeReg                 // from LTSM
  io.patternReaderIo.req.bits.comparisonMode := comparisonModeReg.asTypeOf(ComparisonMode()) 
  io.patternReaderIo.req.bits.errorThreshold := maxErrorThresholdReg        // from LTSM
  io.patternReaderIo.req.bits.doConsecutiveCount := false.B   // link ops never consecutive counts
  io.patternReaderIo.req.bits.done := false.B
  io.patternReaderIo.req.bits.clear := false.B
  
  io.done := false.B

  val inProgress = RegInit(false.B)
  io.usingPatternReader := inProgress

  // FSM helpers
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

  currentState := nextState
  switch(currentState) {
    is(0.U) {
      when(io.start) {
        patternTypeReg := io.patternType  // valtrain vs lfsr
        comparisonModeReg := io.linkTrainingParameters.comparisonMode // aggergate vs perlane
        maxErrorThresholdReg := io.linkTrainingParameters.maxErrorThreshold

        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.START_RX_INIT_D2C_POINT_TEST_REQ, 
                                                  "PHY", "PHY", true,
                                                  msgInfo = maxErrorThreshold,
                                                  data = startReqData)
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                     
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_RX_INIT_D2C_POINT_TEST_RESP    

        when(sbMsgExchanger.io.done) {
          nextState := 1.U
          inProgress := true.B
        }        
      }
    }
    is(1.U) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LFSR_CLEAR_ERROR_REQ

      io.patternReaderIo.req.valid := sbMsgExchanger.io.resp.valid    
      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived 
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LFSR_CLEAR_ERROR_RESP, "PHY", "PHY", true)

      when(sbMsgExchanger.io.done) {
        nextState := 2.U
      }
    }
    is(2.U) {
      assert(io.patternReaderIo.req.ready === false.B, "Pattern Reader should be running")

      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.RX_INIT_D2C_TX_COUNT_DONE_REQ
      
      io.patternReaderIo.req.bits.done := sbMsgExchanger.io.msgReceived // stop the PatternReader

      sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.RX_INIT_D2C_TX_COUNT_DONE_RESP, 
                                                "PHY", "PHY", true)
      io.rxInitPtTestResults.valid := io.patternReaderIo.resp.valid                                                  

      when(sbMsgExchanger.io.done) {
        io.patternReaderIo.req.bits.clear := true.B
        nextState := 3.U
      }
    }
    is(3.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.END_RX_INIT_D2C_POINT_TEST_REQ,
                                                  "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_RX_INIT_D2C_POINT_TEST_RESP

      when(sbMsgExchanger.io.done) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
      }
    }
  }
}

class RxInitPtTestResponderInterfaceIO extends Bundle {
  // IOs are in relation to modules using the RX Point Test Responder
  // IN
  val done = Input(Bool())

  // OUT
  val start = Output(Bool())          
  val patternType = Output(PatternSelect())
}

class RxD2CPointTestResponder(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())    
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect())  // LTSM controls the patternType
    val patternWriterIo = Flipped(new PatternWriterIO) 
    val usingPatternWriter = Output(Bool())

    // h0: Center, h1: Left, h2: Right
    val clockPhaseSelect = Valid(Output(UInt(4.W)))
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // State registers
  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)
  val clockPhaseSelectReg = RegInit(0.U(4.W))

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

  assert(((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
                          (io.patternType === PatternSelect.LFSR))),
        "PatternType should only be VALTRAIN or LFSR")
  io.patternWriterIo.req.bits.patternType := patternTypeReg
  io.patternWriterIo.req.valid := false.B
  io.done := false.B

  io.clockPhaseSelect.bits := clockPhaseSelectReg
  io.clockPhaseSelect.valid := false.B

  val inProgress = RegInit(false.B)
  io.usingPatternWriter := inProgress

  currentState := nextState
  switch(currentState) {
    is(0.U) {
      when(io.start) {  
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                                     
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_RX_INIT_D2C_POINT_TEST_REQ
        patternTypeReg := io.patternType

        when(sbMsgExchanger.io.resp.valid) {
          clockPhaseSelectReg := sbMsgExchanger.io.resp.bits(73, 70)

          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.START_RX_INIT_D2C_POINT_TEST_RESP, 
                                                    "PHY", "PHY", true)                                                     
        }
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
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.RX_INIT_D2C_TX_COUNT_DONE_REQ, 
                                                "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.RX_INIT_D2C_TX_COUNT_DONE_RESP

      when(sbMsgExchanger.io.done) {
        nextState := 3.U
      }

    }
    is(3.U) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_RX_INIT_D2C_POINT_TEST_REQ

      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.END_RX_INIT_D2C_POINT_TEST_RESP,
                                                "PHY", "PHY", true)

      when(sbMsgExchanger.io.done) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
      }
    }
  }
}

object MainRxD2CPointTestRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CPointTestRequester(new AfeParams, new SidebandParams),
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

object MainRxD2CPointTestResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CPointTestResponder(new AfeParams, new SidebandParams),
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