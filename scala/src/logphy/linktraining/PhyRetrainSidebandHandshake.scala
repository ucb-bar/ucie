package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Description:
    This module takes care of the sideband handshake pertaining to the PHYRETRAIN state.
      - LTSM will signal the responder to be alert for a Remote's request for a PHYRETRAIN, 
      and then signal back.
    The LTSM PHYRETRAIN state takes care of the RDI handshakes, does detection of the 
    Runtime Link Test Control registers, and maintains the PHY_IN_RETRAIN variable.
*/

object PhyRetrainState extends ChiselEnum {
  val sRDI_MSG, sPHYRETRAIN_MSG, sDONE = Value
}

class PhyRetrainSidebandHandshake(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val startRdiMsgExch = Input(Bool())
    val startPhyRetrainMsgExch = Input(Bool())
    val requesterLocalRetrainEncoding = Flipped(Valid(UInt(3.W)))
    
    val waitForRemoteRequest = Input(Bool())
    val sendRdiRetrainResp = Input(Bool())        
    val responderLocalRetrainEncoding = Flipped(Valid(UInt(3.W)))
    
    // OUT
    val requesterRemoteRetrainEncoding = Valid(UInt(3.W))
    val rdiRespRecieved = Output(Bool())

    val responderRemoteRetrainEncoding = Valid(UInt(3.W))
    val remoteRequestedRetrain = Output(Bool())
    
    val done = Output(Bool())

    // Bundle with IN & OUT IOs
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })

  val requester = Module(new PhyRetrainRequester(sbParams))
  val responder = Module(new PhyRetrainResponder(sbParams))

  // Requester IN
  requester.io.startRdiMsgExch := io.startRdiMsgExch
  requester.io.startPhyRetrainMsgExch := io.startPhyRetrainMsgExch
  requester.io.localRetrainEncoding := io.requesterLocalRetrainEncoding
  requester.io.responderRdy := responder.io.responderRdy 
  requester.io.sbLaneIo <> io.requesterSbLaneIo

  // Responder IN
  responder.io.waitForRemoteRequest := io.waitForRemoteRequest
  responder.io.startPhyRetrainMsgExch := io.startPhyRetrainMsgExch
  responder.io.sendRdiRetrainResp := io.sendRdiRetrainResp
  responder.io.localRetrainEncoding := io.responderLocalRetrainEncoding
  responder.io.requesterRdy := requester.io.requesterRdy
  responder.io.sbLaneIo <> io.responderSbLaneIo

  // OUT
  io.requesterRemoteRetrainEncoding := requester.io.remoteRetrainEncoding
  io.rdiRespRecieved := requester.io.rdiRespRecieved  // TODO: used as indicator to transition to PHYRETRAIN msg exchange
  io.responderRemoteRetrainEncoding := responder.io.remoteRetrainEncoding
  io.remoteRequestedRetrain := responder.io.remoteRequestedRetrain
  io.done := requester.io.done && responder.io.done
}

class PhyRetrainRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val startRdiMsgExch = Input(Bool())    
    val startPhyRetrainMsgExch = Input(Bool())
    val localRetrainEncoding = Flipped(Valid(UInt(3.W)))  // TODO: I think valid is always true\
    val responderRdy = Input(Bool())

    // OUT
    val remoteRetrainEncoding = Valid(UInt(3.W))
    val rdiRespRecieved = Output(Bool())
    val requesterRdy = Output(Bool())
    val done = Output(Bool())

    // Bundle with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // State register
  val currentState = RegInit(PhyRetrainState.sRDI_MSG)
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

  io.remoteRetrainEncoding.valid := false.B
  io.remoteRetrainEncoding.bits := sbMsgExchanger.io.resp.bits(74, 72)
  io.rdiRespRecieved := false.B
  io.done := false.B

  switch(currentState) {
    is(PhyRetrainState.sRDI_MSG) {
      when(io.startRdiMsgExch) {
        sbMsgExchanger.io.req.valid := true.B
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_RETRAIN, 
                                                  "PHY", "PHY", true)

        sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                              
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LINKMGMT_RDI_RSP_RETRAIN 

        io.rdiRespRecieved := sbMsgExchanger.io.msgReceived               
      }

      when(io.startPhyRetrainMsgExch) {
        nextState := PhyRetrainState.sPHYRETRAIN_MSG
      }
    }
    is(PhyRetrainState.sPHYRETRAIN_MSG) {
      sbMsgExchanger.io.req.valid := io.localRetrainEncoding.valid
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.PHYRETRAIN_RETRAIN_START_REQ, 
                                                "PHY", "PHY", true,
                                                msgInfo = Cat(0.U(12.W),
                                                              io.localRetrainEncoding.bits))

      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                              
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.PHYRETRAIN_RETRAIN_START_RESP
      io.remoteRetrainEncoding.valid := sbMsgExchanger.io.resp.valid

      requesterRdy := sbMsgExchanger.io.done 
      when(io.requesterRdy && io.responderRdy) {
        nextState := PhyRetrainState.sDONE
      }
    }
    is(PhyRetrainState.sDONE) {
      io.done := true.B
      nextState := PhyRetrainState.sRDI_MSG
    }
  }
}

class PhyRetrainResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val waitForRemoteRequest = Input(Bool())
    val startPhyRetrainMsgExch = Input(Bool())
    val sendRdiRetrainResp = Input(Bool())
    val localRetrainEncoding = Flipped(Valid(UInt(3.W)))  // valid goes HIGH after resolving
    val requesterRdy = Input(Bool())

    // OUT
    val remoteRequestedRetrain = Output(Bool()) // goes high when resp is valid
    val remoteRetrainEncoding = Valid(UInt(3.W)) 
    val responderRdy = Output(Bool())
    val done = Output(Bool())

    // Bundle with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // State register
  val currentState = RegInit(PhyRetrainState.sRDI_MSG)
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

  io.remoteRetrainEncoding.valid := false.B
  io.remoteRetrainEncoding.bits := sbMsgExchanger.io.resp.bits(74, 72)
  io.remoteRequestedRetrain := false.B
  io.done := false.B

  switch(currentState) {
    is(PhyRetrainState.sRDI_MSG) {
      sbMsgExchanger.io.rxRefBitPattern.valid := io.waitForRemoteRequest                                             
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LINKMGMT_RDI_RSP_RETRAIN 

      io.remoteRequestedRetrain := sbMsgExchanger.io.msgReceived

      sbMsgExchanger.io.req.valid := io.sendRdiRetrainResp
      sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_RETRAIN, 
                                                "PHY", "PHY", true)
              
      when(io.startPhyRetrainMsgExch) {
        nextState := PhyRetrainState.sPHYRETRAIN_MSG
      }
    }
    is(PhyRetrainState.sPHYRETRAIN_MSG) {
      sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                             
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.PHYRETRAIN_RETRAIN_START_REQ 

      io.remoteRetrainEncoding.valid := sbMsgExchanger.io.resp.valid

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
      io.done := true.B
      nextState := PhyRetrainState.sRDI_MSG
    }
  }
}


object MainPhyRetrainSidebandHandshake extends App {
  ChiselStage.emitSystemVerilogFile(
    new PhyRetrainSidebandHandshake(new SidebandParams()),
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

object MainPhyRetrainRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new PhyRetrainRequester(new SidebandParams()),
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

object MainPhyRetrainResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new PhyRetrainResponder(new SidebandParams()),
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