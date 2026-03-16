package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Description:
    This module takes care of the sideband handshake pertraining to the LinkInit state.
    The RDI interface handshakes are in the top level Link Training State Machine.    
*/

class LinkInitSidebandHandshake(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val start = Input(Bool())
    val safeToSendReq = Input(Bool())
    val safeToSendResp = Input(Bool())
    
    // OUT
    val done = Output(Bool())

    // Bundles with IN & OUT IOs
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })

  val requester = Module(new LinkInitRequester(sbParams))
  val responder = Module(new LinkInitResponder(sbParams))

  // Requester IN
  requester.io.start := io.start
  requester.io.safeToSendReq := io.safeToSendReq
  requester.io.responderRdy := responder.io.responderRdy
  requester.io.sbLaneIo <> io.requesterSbLaneIo

  // Responder IN
  responder.io.start := io.start
  responder.io.safeToSendResp := io.safeToSendResp
  responder.io.requesterRdy := requester.io.requesterRdy
  responder.io.sbLaneIo <> io.responderSbLaneIo

  // OUT
  io.done := requester.io.done && responder.io.done
}

object LinkInitState extends ChiselEnum {
  val sLINKMGMT_ACTIVE_MSG, sDONE = Value
}

class LinkInitRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val start = Input(Bool())
    val safeToSendReq = Input(Bool())
    val responderRdy = Input(Bool())

    // OUT
    val done = Output(Bool())
    val requesterRdy = Output(Bool())

      // Bundles with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // State register
  val currentState = RegInit(LinkInitState.sLINKMGMT_ACTIVE_MSG)
  val nextState = WireInit(currentState)
  currentState := nextState

  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState)
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

  io.done := false.B

  // TODO: I think the starts can go
  
  switch(currentState) {  
    is(LinkInitState.sLINKMGMT_ACTIVE_MSG) {
      when(io.start) {
        sbMsgExchanger.io.req.valid := io.safeToSendReq       
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_ACTIVE, 
                                                  "PHY", "PHY", true)
        sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent                                               
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LINKMGMT_RDI_RSP_ACTIVE

        requesterRdy := sbMsgExchanger.io.done 
        when(io.requesterRdy && io.responderRdy) {
          nextState := LinkInitState.sDONE
        }
      }
    }
    is(LinkInitState.sDONE) {
      io.done := true.B
    }
  }
}

class LinkInitResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // IN
    val start = Input(Bool())
    val safeToSendResp = Input(Bool())
    val requesterRdy = Input(Bool())

    // OUT
    val done = Output(Bool())
    val responderRdy = Output(Bool())

    // Bundles with IN & OUT IOs
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // Helper modules
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  // State register
  val currentState = RegInit(LinkInitState.sLINKMGMT_ACTIVE_MSG)
  val nextState = WireInit(currentState)
  currentState := nextState

  // sbMsgExchanger Module Defaults
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState)
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

  io.done := false.B

  switch(currentState) {  
    is(LinkInitState.sLINKMGMT_ACTIVE_MSG) {
      when(io.start) {
        sbMsgExchanger.io.rxRefBitPattern.valid := true.B                                               
        sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LINKMGMT_RDI_REQ_ACTIVE
        
        sbMsgExchanger.io.req.valid := io.safeToSendResp && sbMsgExchanger.io.msgReceived
        sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_ACTIVE, 
                                                  "PHY", "PHY", true)
        
        responderRdy := sbMsgExchanger.io.done 
        when(io.requesterRdy && io.responderRdy) {
          nextState := LinkInitState.sDONE
        }
      }
    }
    is(LinkInitState.sDONE) {
      io.done := true.B
    }
  }
}


object MainLinkInitSidebandHandshake extends App {
  ChiselStage.emitSystemVerilogFile(
    new LinkInitSidebandHandshake(new SidebandParams()),
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

object MainLinkInitRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new LinkInitRequester(new SidebandParams()),
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

object MainLinkInitResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new LinkInitResponder(new SidebandParams()),
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