/*
  Description:
    Receiever initiated Data to Clock Eye Width Sweep modules
    Contains RxD2CEyeWidthSweepRequester, and RxD2CEyeWidthSweepResponder

    Requester initiates the test, and Responder will be reactive to the remote Die's messages.
*/

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util._

class RxInitEyeWidthSweepRequesterInterfaceIO(afeParams: AfeParams) extends Bundle {
  // IOs are in relation to modules using the RX Eye Width Sweep Requester
  // IN
  val done = Input(Bool())
  val eyeSweepTestResults = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))

  // OUT
  val start = Output(Bool())          
  val linkTrainingParameters = Flipped(new LinkOperationParameters)
  val patternType = Output(PatternSelect())
}

class RxD2CEyeWidthSweepRequester(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect())  // LTSM controls the patternType    
    val patternReaderIo = Flipped(new PatternReaderIO(afeParams.mbLanes))
    val usingPatternReader = Output(Bool())
    val linkTrainingParameters = new LinkOperationParameters    
    val rxInitEyeWidthSweepResults = Output(Valid(Vec(afeParams.mbLanes, UInt(1.W))))
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // Internal state registers
  val numStates = 5
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)
  val gotLFSRClearReq = RegInit(false.B) // HIGH if {LFSR clear error req} received in state 3.U

  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)        // from LTSM
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE)     // from LTSM
  val maxErrorThresholdReg = RegInit(0.U(16.W))               // from LTSM

  // Defaults
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.resetReg := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  when(currentState === 3.U) { // need to wait for two messages in state 3.U
    io.sbLaneIo.rx.ready := false.B    
  }.otherwise {
    io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready 
  }

  // Aggregate result and valid comparison result will be in rxInitEyeWidthSweepResults(0)
  val rxInitEyeWidthSweepResults = WireInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))
  switch(comparisonModeReg) {
    is(ComparisonMode.AGGREGATE) { // Aggregate comparison
      rxInitEyeWidthSweepResults(0) := io.patternReaderIo.resp.bits.aggregateStatus
    }
    is(ComparisonMode.PERLANE) { // Per-lane comparison
      for(i <- 0 until afeParams.mbLanes) {
        rxInitEyeWidthSweepResults(i) := io.patternReaderIo.resp.bits.perLaneStatusBits(i)
      }
    }
  }

  val resultValid = RegInit(false.B)
  val resultsReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))

  io.rxInitEyeWidthSweepResults.valid := resultValid
  io.rxInitEyeWidthSweepResults.bits := resultsReg

  io.done := false.B
  
  val inProgress = RegInit(false.B)
  io.usingPatternReader := inProgress

  // If start is HIGH, then patternType can either be VALTRAIN or LFSR
  assert(((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
                          (io.patternType === PatternSelect.LFSR))),
        "PatternType should only be VALTRAIN or LFSR")
  io.patternReaderIo.req.valid := false.B
  io.patternReaderIo.req.bits.patternType := patternTypeReg             // from LTSM
  io.patternReaderIo.req.bits.comparisonMode := comparisonModeReg       // from remote die
  io.patternReaderIo.req.bits.errorThreshold := maxErrorThresholdReg    // from remote die
  io.patternReaderIo.req.bits.doConsecutiveCount := false.B // link ops never consecutive counts
  io.patternReaderIo.req.bits.done := false.B
  io.patternReaderIo.req.bits.clear := false.B
  io.patternReaderIo.functionalLanes := "b011".U

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
      resultValid := false.B
      when(io.start) {
        patternTypeReg := io.patternType
        comparisonModeReg := io.linkTrainingParameters.comparisonMode.asTypeOf(ComparisonMode())
        maxErrorThresholdReg := io.linkTrainingParameters.maxErrorThreshold
        gotLFSRClearReq := false.B

        sbMsgExchanger.io.req.valid := true.B      
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.START_RX_INIT_D2C_EYE_SWEEP_REQ,
                                                  "PHY", "PHY", true,
                                                  msgInfo = maxErrorThreshold,
                                                  data = startReqData)
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_RX_INIT_D2C_EYE_SWEEP_RESP
        when(sbMsgExchanger.io.done) {
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
      assert(io.patternReaderIo.req.ready === true.B, "PatternReader should be ready to accept")

      io.patternReaderIo.req.valid := sbMsgExchanger.io.resp.valid    
      sbMsgExchanger.io.req.valid := sbMsgExchanger.io.msgReceived           
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LFSR_CLEAR_ERROR_RESP, "PHY", "PHY", true)        

      when(sbMsgExchanger.io.done) {
        gotLFSRClearReq := false.B
        nextState := 2.U
      }
    }
    is(2.U) {
      assert(io.patternReaderIo.req.ready === false.B, "PatternReader should be started")

      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.RX_INIT_D2C_RESULTS_REQ

      io.patternReaderIo.req.bits.done := sbMsgExchanger.io.msgReceived // stop the PatternReader
             
      sbMsgExchanger.io.req.valid := io.patternReaderIo.resp.valid
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.RX_INIT_D2C_RESULTS_RESP,
                                                "PHY", "PHY", true,
                                                msgInfo = msgInfoField,
                                                data = dataField)
       
      when(io.patternReaderIo.resp.valid) {
        resultValid := true.B
        resultsReg := rxInitEyeWidthSweepResults
      }      

      when(sbMsgExchanger.io.done) {
        io.patternReaderIo.req.bits.clear := true.B
        nextState := 3.U
      }
    }
    is(3.U) {
      when(io.sbLaneIo.rx.valid) {
        when(SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LFSR_CLEAR_ERROR_REQ)) {
          io.sbLaneIo.rx.ready := true.B
          gotLFSRClearReq := true.B
          nextState := 1.U
          resultValid := false.B    // Reset result valid
        }.elsewhen(SBMsgCompare(io.sbLaneIo.rx.bits.data, 
                                SBM.RX_INIT_D2C_SWEEP_DONE_WITH_RESULTS)) {                                  
          // NOTE/TODO: RX_INIT_D2C SWEEP_DONE_WITH_RESULTS data field bits has ([7:0] left edge)
          //            and ([15:8] right edge) info. Currently not being used, so not outputting.          
          io.sbLaneIo.rx.ready := true.B
          nextState := 4.U
          resultValid := false.B
        }
      }
    }
    is(4.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.END_RX_INIT_D2C_EYE_SWEEP_REQ,
                                                  "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_RX_INIT_D2C_EYE_SWEEP_RESP

      when(sbMsgExchanger.io.done) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B 
        resultValid := false.B       
      }
    }
  }
}

class RxInitEyeWidthSweepResponderInterfaceIO(afeParams: AfeParams) extends Bundle {
  // IOs are in relation to modules using the RX Eye Width Sweep Responder
  // IN
  val done = Input(Bool())
  val remoteEyeSweepTestResults = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))

  // OUT
  val start = Output(Bool())          
  val patternType = Output(PatternSelect())
}

class RxD2CEyeWidthSweepResponder(afeParams: AfeParams, sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val patternType = Input(PatternSelect())  // LTSM controls the patternType 
    val patternWriterIo = Flipped(new PatternWriterIO)
    val usingPatternWriter = Output(Bool())
    val phyTrainIo = new Bundle {
      val waitingForCommand = Output(Bool()) 
      val step = Input(Bool())
      val doneStepping = Input(Bool())
    }
    val rxEyeWidthSweepRemoteResults = Output(Valid(Vec(afeParams.mbLanes, UInt(1.W))))
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // Internal state registers
  val patternTypeReg = RegInit(PatternSelect.VALTRAIN)
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE)  
  
  val numStates = 5
  val currentState = RegInit(0.U(log2Ceil(numStates).W))
  val nextState = WireInit(currentState)

  // SidebandMessageExchanger defaults
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.resetReg := currentState =/= nextState
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // If start is HIGH, then patternType can either be VALTRAIN or LFSR
  assert(((!io.start) || ((io.patternType === PatternSelect.VALTRAIN) ||
                          (io.patternType === PatternSelect.LFSR))),
        "PatternType should only be VALTRAIN or LFSR")
  io.patternWriterIo.req.bits.patternType := patternTypeReg
  io.patternWriterIo.req.valid := false.B
  io.patternWriterIo.functionalLanes := "b011".U
  io.done := false.B

  val rxInitEyeWidthSweepRemoteResults = WireInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(1.W))))
  switch(comparisonModeReg) {
    is(ComparisonMode.AGGREGATE) { // Aggregate comparison
      rxInitEyeWidthSweepRemoteResults(0) := sbMsgExchanger.io.resp.bits(18)
    }
    is(ComparisonMode.PERLANE) { // Per-lane comparison
      switch(patternTypeReg) {
        is(PatternSelect.VALTRAIN) {
          rxInitEyeWidthSweepRemoteResults(0) := sbMsgExchanger.io.resp.bits(19)
        }
        is(PatternSelect.LFSR) {
          for(i <- 0 until afeParams.mbLanes) {
          rxInitEyeWidthSweepRemoteResults(i) := sbMsgExchanger.io.resp.bits(64 + i)
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

  io.phyTrainIo.waitingForCommand := false.B

  currentState := nextState
  switch(currentState) {
    is(0.U) {
      when(io.start) {
        resultValid := false.B 
        patternTypeReg := io.patternType
        
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.START_TX_INIT_D2C_EYE_SWEEP_REQ

        when(sbMsgExchanger.io.resp.valid) {
          comparisonModeReg := sbMsgExchanger.io.resp.bits(123).asTypeOf(ComparisonMode())
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.START_TX_INIT_D2C_EYE_SWEEP_RESP, 
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
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.RX_INIT_D2C_RESULTS_REQ, "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.RX_INIT_D2C_RESULTS_RESP

      when(sbMsgExchanger.io.resp.valid) {
        resultValid := true.B
        resultsReg := rxInitEyeWidthSweepRemoteResults
      }
    
      when(sbMsgExchanger.io.done) {
        io.phyTrainIo.waitingForCommand := true.B
        when(io.phyTrainIo.doneStepping) {  // PhyLaneTrainer controller signaled to finish test
          nextState := 3.U
          resultValid := false.B
        }.elsewhen(io.phyTrainIo.step) {    // PhyLaneTrainer controller signaled to redo test
          nextState := 1.U        
          resultValid := false.B    
        }
      }
    }
    is(3.U) {
      sbMsgExchanger.io.req.valid := true.B     
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.RX_INIT_D2C_SWEEP_DONE_WITH_RESULTS, 
                                                "PHY", "PHY", true)
      // NOTE: Currently the data field for RX_INIT_D2C_SWEEP_DONE_WITH_RESULTS is zeroed out
      /*
        Fields:
          [63:16]: Reserved
          [15:8]: Right Edge
          [7:0]: Left Edge
      */
      when(sbMsgExchanger.io.msgSent) {
        nextState := 4.U
      }
    }
    is(4.U) {
      sbMsgExchanger.io.req.valid := true.B
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.END_RX_INIT_D2C_EYE_SWEEP_RESP,
                                                  "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.END_RX_INIT_D2C_EYE_SWEEP_REQ

      when(sbMsgExchanger.io.done) {
        nextState := 0.U
        io.done := true.B
        inProgress := false.B
        resultValid := false.B
      }
    }
  }
}