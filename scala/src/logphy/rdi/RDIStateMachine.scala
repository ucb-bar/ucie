package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Defined in: scala/interfaces/Types.scala
  Included here for convenience.

  object RDIState extends ChiselEnum {
    val reset = Value("b0000".U(4.W))
    val active = Value("b0001".U(4.W))
    val activePmNak = Value("b0011".U(4.W))
    val l1 = Value("b0100".U(4.W))
    val l2 = Value("b1000".U(4.W))
    val linkReset = Value("b1001".U(4.W))
    val linkError = Value("b1010".U(4.W))
    val retrain = Value("b1011".U(4.W))
    val disabled = Value("b1100".U(4.W))
  }

  object RDIStateReq extends ChiselEnum {
    val nop = Value("b0000".U(4.W))
    val active = Value("b0001".U(4.W))
    val l1 = Value("b0100".U(4.W))
    val l2 = Value("b1000".U(4.W))
    val linkReset = Value("b1001".U(4.W))
    val retrain = Value("b1011".U(4.W))
    val disabled = Value("b1100".U(4.W))
  }
*/

/*
  Description:
    File contains the logic for the RDI state machine. It is split into a requester and responder
    FSM and communicates to ensure proper state transition.

    Note: Currently only transition from RESET -> ACTIVE is implemented. Other transition will
    be implemented ASAP.
*/
class RDIStateMachine(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool()) 
      val plStateSts = Output(RDIState())
    }
    val trainingTimeout = Input(Bool())  
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })

  val requester = Module(new RDIStateMachineRequester(sbParams))
  val responder = Module(new RDIStateMachineResponder(sbParams))

  // Requester IO
  requester.io.rdi.lpStateReq := io.rdi.lpStateReq
  requester.io.rdi.plWakeAck := io.rdi.plWakeAck
  requester.io.trainingTimeout := io.trainingTimeout
  requester.io.sbLaneIo <> io.requesterSbLaneIo
  requester.io.responderRdy := responder.io.responderRdy

  // Responder IO
  responder.io.rdi.lpStateReq := io.rdi.lpStateReq
  responder.io.rdi.plWakeAck := io.rdi.plWakeAck
  responder.io.trainingTimeout := io.trainingTimeout
  responder.io.sbLaneIo <> io.responderSbLaneIo
  responder.io.requesterRdy := requester.io.requesterRdy

  // RDIStateMachine Output IO
  // Can safely use requester current state since responder and requester transition together
  io.rdi.plStateSts := requester.io.currentState 
}

// Note: When transitioning into active from reset much check that plWakeAck it true along
// with lp_state_req = ACTIVE

class RDIStateMachineRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool())
    }    
    val currentState = Output(RDIState())
    val trainingTimeout = Input(Bool())   // TODO: Maybe we can just reset the FSM when there's a timeout
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val responderRdy = Input(Bool())
    val requesterRdy = Output(Bool())
  })

  object Substate extends ChiselEnum {
    val s0, s1 = Value
  }
  
  // State registers
  val currentState = RegInit(RDIState.reset)
  val nextState = WireInit(currentState)
  currentState := nextState
  io.currentState := currentState
  
  // Substate registers
  val substateReg = RegInit(Substate.s0)
  val nextSubstate = WireInit(substateReg)
  substateReg := nextSubstate

  // SidebandMessageExchanger instantiation and signal defaults
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))  
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState) || (substateReg =/= nextSubstate)
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  // Requester ready logic -- used by responder
  val requesterRdyStatusReg = RegInit(false.B)
  val requesterRdy = WireInit(false.B)
  when((currentState =/= nextState) || (substateReg =/= nextSubstate)) {
    requesterRdyStatusReg := false.B 
  }  
  when(requesterRdy) {
    requesterRdyStatusReg := true.B
  }
  io.requesterRdy := requesterRdyStatusReg || requesterRdy

  // Reset State Rules (Rule 1 -- For RESET -> ACTIVE)
  val resetReqObserved = RegInit(false.B)  
  when((io.rdi.lpStateReq === RDIStateReq.nop) && (currentState === RDIState.reset)) {
    resetReqObserved := true.B
  }.elsewhen(nextState =/= currentState) {
    resetReqObserved := false.B
  }
  
  switch(currentState) {
    is(RDIState.reset) {
      switch(substateReg) {
        is(Substate.s0) { // Used to transition into intermediate states
          when(io.rdi.lpStateReq === RDIStateReq.active && resetReqObserved) {
            nextSubstate := Substate.s1
          }          
        }
        is(Substate.s1) { // TO ACTIVE: Send {req.active}, wait for {rsp.active}
          sbMsgExchanger.io.req.valid := true.B
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LINKMGMT_RDI_REQ_ACTIVE, "PHY", "PHY", true)
          sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
          sbMsgExchanger.io.rxRefBitPattern.bits := SBM.LINKMGMT_RDI_RSP_ACTIVE  

          requesterRdy := sbMsgExchanger.io.done

          when(io.requesterRdy && io.responderRdy) {
            nextState := RDIState.active
            nextSubstate := Substate.s0                
          }
        }
      }
    } 
    is(RDIState.active) {
    }
  }
}


class RDIStateMachineResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpStateReq = Input(RDIStateReq())
      val plWakeAck = Input(Bool())
    }    
    val currentState = Output(RDIState())
    val trainingTimeout = Input(Bool())    
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val responderRdy = Output(Bool())
    val requesterRdy = Input(Bool())
  })

  object Substate extends ChiselEnum {
    val s0, s1, s2 = Value
  }

  // State registers
  val currentState = RegInit(RDIState.reset)
  val nextState = WireInit(currentState)
  currentState := nextState
  io.currentState := currentState
  
  // Substate registers
  val substateReg = RegInit(Substate.s0)
  val nextSubstate = WireInit(substateReg)
  substateReg := nextSubstate
      
  // SidebandMessageExchanger instantiation and signal defaults
  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))  
  sbMsgExchanger.io.req.bits := 0.U
  sbMsgExchanger.io.req.valid := false.B
  sbMsgExchanger.io.rxRefBitPattern.bits := VecInit(0.U(5.W), 0.U(8.W), 0.U(8.W))
  sbMsgExchanger.io.rxRefBitPattern.valid := false.B
  sbMsgExchanger.io.resetReg := (currentState =/= nextState) || (substateReg =/= nextSubstate)
  sbMsgExchanger.io.sbLaneIo.tx <> io.sbLaneIo.tx
  sbMsgExchanger.io.sbLaneIo.rx.valid := io.sbLaneIo.rx.valid
  sbMsgExchanger.io.sbLaneIo.rx.bits.data := io.sbLaneIo.rx.bits.data

  // need to wait for different messages in RDIState.reset
  when((currentState === RDIState.reset)) { 
    io.sbLaneIo.rx.ready := false.B    
  }.otherwise {
    io.sbLaneIo.rx.ready := sbMsgExchanger.io.sbLaneIo.rx.ready 
  }

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

  // Reset State Rules (Rule 1 -- For RESET -> ACTIVE)
  val resetReqObserved = RegInit(false.B)  
  when((io.rdi.lpStateReq === RDIStateReq.nop) && (currentState === RDIState.reset)) {
    resetReqObserved := true.B
  }.elsewhen(nextState =/= currentState) {
    resetReqObserved := false.B
  }

  switch(currentState) {
    is(RDIState.reset) {
      switch(substateReg) {
        is(Substate.s0) { // Recognize a request
          when(io.sbLaneIo.rx.valid) {
            when(SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.LINKMGMT_RDI_REQ_ACTIVE)) {
              io.sbLaneIo.rx.ready := true.B
              nextSubstate := Substate.s1
            }
          }
        }
        is(Substate.s1) { // Send {rsp.active}
          sbMsgExchanger.io.req.valid :=  (io.rdi.lpStateReq === RDIStateReq.active) && 
                                          (resetReqObserved)                                       
          sbMsgExchanger.io.req.bits := SBMsgCreate(SBM.LINKMGMT_RDI_RSP_ACTIVE,
                                                    "PHY", "PHY", true)
          responderRdy := sbMsgExchanger.io.msgSent

          when(io.responderRdy && io.requesterRdy) {
            nextState := RDIState.active
            nextSubstate := Substate.s0
          }         
        }
      }
    } 
    is(RDIState.active) {

    }
  }
}


object MainRDIStateMachine extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStateMachine(new SidebandParams()),
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

object MainRDIStateMachineRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStateMachineRequester(new SidebandParams()),
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

object MainRDIStateMachineResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStateMachineResponder(new SidebandParams()),
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