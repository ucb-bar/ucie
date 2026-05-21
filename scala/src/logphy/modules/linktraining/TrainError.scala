/*
  Description:
    This module takes care of the sideband handshake pertaining to the TrainError state.
      - The Requester initiates a TrainError sideband message for the Local die, if needed.
      - The Responder reports whenever a {TRAINERROR Entry Req} has been detected, so the Local die
      can transition into TrainError when remote is requesting it.
*/

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._

object TrainErrorState extends ChiselEnum {
  val sTRAINERROR_ENTRY_MSG, sDONE = Value
}

class TrainErrorRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val sendReq = Input(Bool())

    // OUT
    val done = Output(Bool())

      // Bundles with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))


  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := reset.asBool
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // Message exchange
  sbMsgExchanger.io.req.valid := io.sendReq       
  sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.TRAINERROR_ENTRY_REQ, 
                                            "PHY", "PHY", true)
  sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                               
  sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TRAINERROR_ENTRY_RESP

  io.done := sbMsgExchanger.io.done 
}

class TrainErrorResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val wakeUp = Input(Bool())      // wakeUp only high when LTSM NOT in Reset or TrainError
    val sendResp = Input(Bool())    // Goes high when local is in TrainError

    // OUT
    val remoteRequestingTrainError = Output(Bool()) // Trigger used to transition LTSM to TrainError
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
  sbMsgExchanger.io.resetReg := reset.asBool
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.remoteRequestingTrainError := sbMsgExchanger.io.msgReceived

  // Message exchange
  sbMsgExchanger.io.rxRefBitPattern.valid := io.wakeUp                                              
  sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TRAINERROR_ENTRY_REQ
  
  sbMsgExchanger.io.req.valid := io.sendResp && sbMsgExchanger.io.msgReceived
  sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.TRAINERROR_ENTRY_RESP, 
                                            "PHY", "PHY", true)
  io.done := sbMsgExchanger.io.done 
}


