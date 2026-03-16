package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import circt.stage.ChiselStage
import chisel3.util._


/*
  Description:
    This module takes care of the sideband handshake pertaining to the TrainError state.
      - The Requester initiates a TrainError sideband message for the Local die, if needed.
      - The Responder reports whenever a {TRAINERROR Entry Req} has been detected, so the Local die
      can transition into TrainError when remote is requesting it.
*/

object TrainErrorState extends ChiselEnum {
  val sTRAINERROR_ENTRY_MSG, sDONE = Value
}

class TrainErrorRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val sendReq = Input(Bool())           // TODO: no handshake needed for SBINIT when previous state was SBINIT
    val resetSbMsg = Input(Bool())

    // OUT
    val done = Output(Bool())

      // Bundles with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

    // State register
  val currentState = RegInit(TrainErrorState.sTRAINERROR_ENTRY_MSG)
  val nextState = WireInit(currentState)
  currentState := nextState

  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState) || io.resetSbMsg
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.done := false.B

  switch(currentState) {  
    is(TrainErrorState.sTRAINERROR_ENTRY_MSG) {
      sbMsgExchanger.io.req.valid := io.sendReq       
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.TRAINERROR_ENTRY_REQ, 
                                                "PHY", "PHY", true)
      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                               
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TRAINERROR_ENTRY_RESP
  
      when(sbMsgExchanger.io.done) {
        nextState := TrainErrorState.sDONE
      }      
    }
    is(TrainErrorState.sDONE) {
      io.done := true.B
      nextState := TrainErrorState.sTRAINERROR_ENTRY_MSG
    }
  }
}

class TrainErrorResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val wakeUp = Input(Bool())    // TODO: wakeUp only high when NOT in RESET or TRAINERROR or SBINIT
    val resetSbMsg = Input(Bool())
    val sendResp = Input(Bool())

    // OUT
    val remoteRequestingTrainError = Output(Bool())
    val done = Output(Bool())

    // Bundles with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // State register
  val currentState = RegInit(TrainErrorState.sTRAINERROR_ENTRY_MSG)
  val nextState = WireInit(currentState)
  currentState := nextState

  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState) || io.resetSbMsg
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.done := false.B

  io.remoteRequestingTrainError := sbMsgExchanger.io.msgReceived

  switch(currentState) {  
    is(TrainErrorState.sTRAINERROR_ENTRY_MSG) {
      sbMsgExchanger.io.rxRefBitPattern.valid := io.wakeUp                                              
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TRAINERROR_ENTRY_REQ
      
      sbMsgExchanger.io.req.valid := io.sendResp && sbMsgExchanger.io.msgReceived
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.TRAINERROR_ENTRY_RESP, 
                                                "PHY", "PHY", true)
      
      when(sbMsgExchanger.io.done) {
        nextState := TrainErrorState.sDONE
      }    
    }
    is(TrainErrorState.sDONE) {
      io.done := true.B
      nextState := TrainErrorState.sTRAINERROR_ENTRY_MSG
    }
  }
}


object MainTrainErrorRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new TrainErrorRequester(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}

object MainTrainErrorResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new TrainErrorResponder(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}