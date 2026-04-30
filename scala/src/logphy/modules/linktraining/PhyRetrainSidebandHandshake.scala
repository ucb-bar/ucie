/*
  Description:
    This module takes care of the sideband handshake pertaining to the PHYRETRAIN state.
*/

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util._

object PhyRetrainState extends ChiselEnum {
  val sPHYRETRAIN_MSG, sDONE = Value
}

class PhyRetrainSidebandHandshake(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val startPhyRetrainMsgExch = Input(Bool())
    val requesterLocalRetrainEncoding = Flipped(Valid(UInt(3.W)))
    val waitForRemoteRequest = Input(Bool())        
    val responderLocalRetrainEncoding = Flipped(Valid(UInt(3.W)))
    
    // OUT
    val requesterRemoteRetrainEncoding = Valid(UInt(3.W)) 
    val responderRemoteRetrainEncoding = Valid(UInt(3.W))
    
    val done = Output(Bool())

    // Bundle with IN & OUT IOs
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })

  val requester = Module(new PhyRetrainRequester(sbParams))
  val responder = Module(new PhyRetrainResponder(sbParams))

  // Requester IN
  requester.io.startPhyRetrainMsgExch := io.startPhyRetrainMsgExch
  requester.io.localRetrainEncoding := io.requesterLocalRetrainEncoding
  requester.io.responderRdy := responder.io.responderRdy 
  requester.io.sbLaneIo <> io.requesterSbLaneIo

  // Responder IN
  responder.io.waitForRemoteRequest := io.waitForRemoteRequest
  responder.io.localRetrainEncoding := io.responderLocalRetrainEncoding
  responder.io.requesterRdy := requester.io.requesterRdy
  responder.io.sbLaneIo <> io.responderSbLaneIo

  // OUT
  io.requesterRemoteRetrainEncoding := requester.io.remoteRetrainEncoding
  io.responderRemoteRetrainEncoding := responder.io.remoteRetrainEncoding
  io.done := requester.io.requesterRdy && responder.io.requesterRdy
}

class PhyRetrainRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val startPhyRetrainMsgExch = Input(Bool())
    val localRetrainEncoding = Flipped(Valid(UInt(3.W)))  // TODO: I think valid is always true
    val responderRdy = Input(Bool())

    // OUT
    val remoteRetrainEncoding = Valid(UInt(3.W))
    val requesterRdy = Output(Bool())

    // Bundle with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // State register
  val currentState = RegInit(PhyRetrainState.sPHYRETRAIN_MSG)
  val nextState = WireInit(currentState)
  currentState := nextState

  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState)  // TODO: might need a reset?
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // Requester ready logic -- used by responder
  val requesterRdyStatusReg = RegInit(false.B)
  val requesterRdy = WireInit(false.B)
  when(currentState =/= nextState) {
    requesterRdyStatusReg := false.B 
  }  
  when(requesterRdy) {
    requesterRdyStatusReg := true.B
  }
  io.requesterRdy := requesterRdyStatusReg || requesterRdy

  val validRemoteRetrainEncoding = RegInit(false.B)
  val remoteRetrainEncoding = RegInit(0.U(3.W))

  io.remoteRetrainEncoding.valid := validRemoteRetrainEncoding
  io.remoteRetrainEncoding.bits := remoteRetrainEncoding

  switch(currentState) {
    is(PhyRetrainState.sPHYRETRAIN_MSG) {
      sbMsgExchanger.io.req.valid := io.localRetrainEncoding.valid && io.startPhyRetrainMsgExch
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.PHYRETRAIN_RETRAIN_START_REQ, 
                                                "PHY", "PHY", true,
                                                msgInfo = Cat(0.U(12.W),
                                                              io.localRetrainEncoding.bits))

      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent 
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.PHYRETRAIN_RETRAIN_START_RESP

      when(sbMsgExchanger.io.resp.valid) {
        validRemoteRetrainEncoding := true.B
        remoteRetrainEncoding := sbMsgExchanger.io.resp.bits(74, 72)
      }

      requesterRdy := sbMsgExchanger.io.done 
      when(io.requesterRdy && io.responderRdy) {
        nextState := PhyRetrainState.sDONE
      }
    }
    is(PhyRetrainState.sDONE) {
      validRemoteRetrainEncoding := false.B
      nextState := PhyRetrainState.sPHYRETRAIN_MSG
    }
  }
}

class PhyRetrainResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val waitForRemoteRequest = Input(Bool())
    val localRetrainEncoding = Flipped(Valid(UInt(3.W)))  // valid goes HIGH after resolving
    val requesterRdy = Input(Bool())

    // OUT
    val remoteRetrainEncoding = Valid(UInt(3.W)) 
    val responderRdy = Output(Bool())

    // Bundle with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // State register
  val currentState = RegInit(PhyRetrainState.sPHYRETRAIN_MSG)
  val nextState = WireInit(currentState)
  currentState := nextState

  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState)  // TODO: might need a reset?
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // Responder ready logic -- used by requester
  val responderRdyStatusReg = RegInit(false.B)
  val responderRdy = WireInit(false.B)
  when(currentState =/= nextState) {
    responderRdyStatusReg := false.B 
  }  
  when(responderRdy) {
    responderRdyStatusReg := true.B
  }
  io.responderRdy := responderRdyStatusReg || responderRdy

  val validRemoteRetrainEncoding = RegInit(false.B)
  val remoteRetrainEncoding = RegInit(0.U(3.W))

  io.remoteRetrainEncoding.valid := validRemoteRetrainEncoding
  io.remoteRetrainEncoding.bits := remoteRetrainEncoding

  switch(currentState) {
    is(PhyRetrainState.sPHYRETRAIN_MSG) {
      sbMsgExchanger.io.rxRefBitPattern.valid := io.waitForRemoteRequest                                            
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.PHYRETRAIN_RETRAIN_START_REQ 

      when(sbMsgExchanger.io.resp.valid) {
        validRemoteRetrainEncoding := true.B
        remoteRetrainEncoding := sbMsgExchanger.io.resp.bits(74, 72)
      }

      sbMsgExchanger.io.req.valid := io.localRetrainEncoding.valid
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.PHYRETRAIN_RETRAIN_START_RESP, 
                                                "PHY", "PHY", true,
                                                msgInfo = Cat(0.U(12.W),
                                                              io.localRetrainEncoding.bits))

      responderRdy := sbMsgExchanger.io.done 
      when(io.requesterRdy && io.responderRdy) {
        nextState := PhyRetrainState.sDONE
      }
    }
    is(PhyRetrainState.sDONE) {
      validRemoteRetrainEncoding := false.B
      nextState := PhyRetrainState.sPHYRETRAIN_MSG
    }
  }
}


