package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.util._

class SBInitSM(sbParams: SidebandParams, timeoutCyclesMax: Int) extends Module {
  val io = IO(new Bundle {
    val fsmCtrl = new SubFsmControlIO()
    // val sidebandCtrlIo = new SidebandCtrlIO()
    val sbRxTxMode = Output(SBRxTxMode()) // TODO: only ctrl needed? revisit during integration.
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })
  
  val requester = Module(new SBInitRequester(sbParams, timeoutCyclesMax))
  val responder = Module(new SBInitResponder(sbParams))

  requester.io.start := io.fsmCtrl.start
  responder.io.start := requester.io.outOfReset
  
  io.sbRxTxMode := requester.io.rxTxMode
  io.requesterSbLaneIo <> requester.io.sbLaneIo
  io.responderSbLaneIo <> responder.io.sbLaneIo

  io.fsmCtrl.substateTransitioning := false.B // No spec defined substates. Keep false.  
  io.fsmCtrl.error := false.B
  io.fsmCtrl.done := requester.io.done && responder.io.done
}

object SBInitStateRequester extends ChiselEnum {
  val sPATTERN, sOUT_OF_RESET, sSBINIT_DONE_MSG = Value
}
class SBInitRequester(sbParams: SidebandParams, timeoutCyclesMax: Int) extends Module {  
  val io = IO(new Bundle {
    val start = Input(Bool())    
    val done = Output(Bool())
    val rxTxMode = Output(SBRxTxMode())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val outOfReset = Output(Bool())
  })

  val sbInitClkPattern = BigInt("5555555555555555", 16).U(64.W) // 0b0101_0101...0101

  // send/detect clock pattern wires and regs
  val oneMsCycles = timeoutCyclesMax / 8
  val sendPatternCounter = RegInit(0.U(log2Ceil(oneMsCycles).W))
  val fourPatternCounter = RegInit(0.U(2.W))
  val isSendingPattern = RegInit(true.B)  
  val detectPatternCounter = RegInit(0.U(2.W))
  val patternDetected = Wire(Bool())
  
  patternDetected := detectPatternCounter === 2.U
  isSendingPattern := ((sendPatternCounter === (oneMsCycles - 1).U) ^ isSendingPattern) ||
                      patternDetected

  // out of reset wires and regs
  val outOfResetDetected = WireInit(false.B)

  // sbinit done wire and regs
  val msgSent = RegInit(false.B)
  val msgReceived = RegInit(false.B)

  val currentState = RegInit(SBInitStateRequester.sPATTERN) 
  val nextState = WireInit(currentState)

  io.sbLaneIo.rx.ready := false.B
  io.sbLaneIo.tx.valid := false.B
  io.sbLaneIo.tx.bits.data := 0.U

  io.rxTxMode := Mux(fourPatternCounter === 3.U, SBRxTxMode.PACKET, SBRxTxMode.RAW)
  io.outOfReset := currentState === SBInitStateRequester.sSBINIT_DONE_MSG

  io.done := msgSent && msgReceived
  
  currentState := nextState
  switch(currentState) {
    is(SBInitStateRequester.sPATTERN) {
      when(io.start) {
        // detect pattern
        io.sbLaneIo.rx.ready := true.B
        when(io.sbLaneIo.rx.valid && !patternDetected) {
          when(io.sbLaneIo.rx.bits.data(63,0) === sbInitClkPattern) {
            when(detectPatternCounter =/= 2.U) {
              detectPatternCounter := detectPatternCounter + 1.U
            }
            // .otherwise {
            //   when(detectPatternCounter === 1.U) {
            //     detectPatternCounter := 0.U
            //   }
            // }
          }
        }

        // send pattern
        io.sbLaneIo.tx.valid := isSendingPattern
        io.sbLaneIo.tx.bits.data := Cat(0.U(64.W), sbInitClkPattern)

        when(!patternDetected) {    
          when(io.sbLaneIo.tx.ready && sendPatternCounter === (oneMsCycles-1).U) {
            sendPatternCounter := sendPatternCounter + 1.U
          }.otherwise {
            sendPatternCounter := 0.U
          }
        }.otherwise {
          when(io.sbLaneIo.tx.ready && fourPatternCounter =/= 3.U) {
            fourPatternCounter := fourPatternCounter + 1.U
          }
        }

        when(fourPatternCounter === 3.U) {
          nextState := SBInitStateRequester.sOUT_OF_RESET
        }
      }
    }
    is(SBInitStateRequester.sOUT_OF_RESET) {
      // detect {SBINIT Out of Reset}
      io.sbLaneIo.rx.ready := true.B          
      when(io.sbLaneIo.rx.valid && SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.SBINIT_OUT_OF_RESET)) {
        outOfResetDetected := true.B
      }

      // send {SBINIT Out of Reset}
      io.sbLaneIo.tx.valid := !outOfResetDetected
      when(io.sbLaneIo.tx.ready) {         
        io.sbLaneIo.tx.bits.data := SBMsgCreate(SBM.SBINIT_OUT_OF_RESET, "PHY", true, "PHY")        
      }

      when(outOfResetDetected) {
        nextState := SBInitStateRequester.sSBINIT_DONE_MSG
      }
    }
    is(SBInitStateRequester.sSBINIT_DONE_MSG) {
      // send {SBINIT done req} once
      io.sbLaneIo.tx.valid := !msgSent
      when(!msgSent && io.sbLaneIo.tx.ready) {        
        io.sbLaneIo.tx.bits.data := SBMsgCreate(SBM.SBINIT_DONE_REQ, "PHY", true, "PHY")
        msgSent := true.B
      }

      // wait for {SBINIT done resp}
      when(!msgReceived && io.sbLaneIo.rx.valid &&
           SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.SBINIT_DONE_RESP)) {
        io.sbLaneIo.rx.ready := true.B
        msgReceived := true.B
      }  
    }    
  }
}

class SBInitResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  // sbinit done wire and regs
  val msgSent = RegInit(false.B)
  val msgReceived = RegInit(false.B)

  io.done := msgSent
  io.sbLaneIo.rx.ready := false.B
  io.sbLaneIo.tx.valid := false.B
  io.sbLaneIo.tx.bits.data := 0.U

  // once partner {SBInit done req} is received, send {SBInit done resp}, and signal done
  when(io.start) {
    // detect {SBINIT done req}.
    // since {SBINIT Out Of Reset} is continuously sent, it needs to flush them out
    when(!msgReceived && io.sbLaneIo.rx.valid) {
      io.sbLaneIo.rx.ready := SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.SBINIT_DONE_REQ) ||
                              SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.SBINIT_OUT_OF_RESET)
      msgReceived := SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.SBINIT_DONE_REQ)
    }

    // once detected send {SBINIT done resp}, and signal done hold high
    io.sbLaneIo.tx.valid := msgReceived
    when(msgReceived && io.sbLaneIo.tx.ready) {                
      io.sbLaneIo.tx.bits.data := SBMsgCreate(SBM.SBINIT_DONE_RESP, "PHY", true, "PHY")
      msgSent := true.B
    }
  }
}