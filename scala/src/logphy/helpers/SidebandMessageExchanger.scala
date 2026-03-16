package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Description:
    Used as a helper module for link training fsms and training operations to exchange sideband
    messages, since each module can exchange their own unique sideband message.

    Improvements can likely be done by creating a centrailized arbiter that receives and sends 
    messages which can be instantiated in the top level fsm (LinkTrainingSM.scala), and have the
    modules that sends messages connect to it. Need to see the difference in area and power as 
    such an arbiter may have more complex logic

    NOTE: To fix any timing issues, can register the req and reference messages before sending them
*/

class SidebandMessageExchanger(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // io.req.valid and io.rxRefBitPattern.valid is decoupled because 
    // Responder FSMs need to wait for a request before sending a response

    val req = Flipped(Valid(Input((UInt(sbParams.sbNodeMsgWidth.W)))))
    val rxRefBitPattern = Flipped(Valid(Input(MixedVec(UInt(5.W), UInt(8.W), UInt(8.W)))))
    val resp = Output(Valid((UInt(sbParams.sbNodeMsgWidth.W))))
    val msgSent = Output(Bool())
    val msgReceived = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val done = Output(Bool())
    val resetReg = Input(Bool())
  })  

  val msgSent = RegInit(false.B)
  val msgReceived = RegInit(false.B)

  when(io.resetReg) {
    msgSent := false.B
    msgReceived := false.B
  }

  io.resp.bits := io.sbLaneIo.rx.bits.data
  io.resp.valid := false.B
  
  io.sbLaneIo.tx.valid := !msgSent && io.req.valid  // io.req.valid should be sticky
  io.sbLaneIo.tx.bits.data := io.req.bits
  io.sbLaneIo.rx.ready := false.B
  
  when(!msgSent && io.sbLaneIo.tx.ready) {
    msgSent := true.B
  }

  val refPattern = 
        VecInit(io.rxRefBitPattern.bits(0), io.rxRefBitPattern.bits(1), io.rxRefBitPattern.bits(2))
  when(!msgReceived && io.sbLaneIo.rx.valid && io.rxRefBitPattern.valid && 
       SBMsgCompare(io.sbLaneIo.rx.bits.data, refPattern)) {
    io.sbLaneIo.rx.ready := true.B
    msgReceived := true.B

    // Used to do any checks with the bits of response received. should be done
    // combinationally, and within the cycle resp valid goes high
    // Note: This might cause sbLaneIo.rx.bits.data to be a long path
    io.resp.valid := true.B  
  }    

  // This module can be used to send or receive a single message by just driving the io.req.valid
  // or io.rxRefBitPattern.valid HIGH, respectively. io.msgSent and io.MsgReceived is used to
  // check the status.
  io.msgSent := msgSent
  io.msgReceived := msgReceived

  // Message has been exchanged
  io.done := msgSent && msgReceived
}

object MainSidebandMessageExchanger extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandMessageExchanger(new SidebandParams()),
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