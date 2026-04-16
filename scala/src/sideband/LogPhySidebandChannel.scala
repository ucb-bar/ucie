/*
  The top level associated with the Sideband channel for the LogPHY Layer

  Contains a SidebandInterfaceNode to facilitate communication over RDI, SidebandSwitch to route
  packets and SidebandLinkNode to facilitate communication over physical link.
*/
package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

class LogPhySidebandChannel(
  sbMsgWidth: Int, sbLinkWidth: Int, rdiNcWidth: Int, numCredits: Int, desTimeoutCycles: Int,
  queueDepths: SidebandPriorityQueueDepths) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val in = Flipped(Valid(UInt(rdiNcWidth.W)))
      val out = Valid(UInt(rdiNcWidth.W))
      val txCreditReturn = Input(Bool())
      val rxCreditReturn = Output(Bool()) 
    }
    val layer = new Bundle {
      val in = Flipped(Decoupled(UInt(sbMsgWidth.W)))
      val out = Decoupled(UInt(sbMsgWidth.W))    
      val status = new Bundle {
        val sbParityErr = Output(Bool())
        val rxPriorityQueuesFull = Output(Bool())
        val desTimedout = Output(Bool())
        val invalidRouteUpper = Output(Bool())
        val invalidRouteCurr = Output(Bool())
        val invalidRouteLower = Output(Bool())        
      }
    }
    val link = new Bundle {
      val in = new Bundle {
        val bits = Input(UInt(sbLinkWidth.W))
        val fwClock = Input(UInt(1.W))
      }
      val out = new Bundle {
        val bits = Output(UInt(sbLinkWidth.W))
        val fwClock = Output(UInt(1.W))
      }
      val ctrl = new Bundle {
        val txMode = Input(SBRxTxMode())
        val rxMode = Input(SBRxTxMode())
      }
    }
  })

  val layerId = LayerId.logPhy  // 2
  val upperIds = Seq(0, 1)      // Protocol(0) and D2D(1)
  val lowerIds = Seq()          // No lower layers

  val rdiIntfNode = Module(new SidebandInterfaceNode(sbMsgWidth, rdiNcWidth, numCredits, queueDepths))
  val switch = Module(new SidebandSwitch(layerId, upperIds, lowerIds, sbMsgWidth))
  val linkNode = Module(new SidebandLinkNode(sbMsgWidth, sbLinkWidth, numCredits, desTimeoutCycles, queueDepths))

  // IOs for module
  io.rdi.rxCreditReturn := rdiIntfNode.io.rxCreditReturn
  io.layer.status.sbParityErr := rdiIntfNode.io.sbParityErr || linkNode.io.err.sbParityErr
  io.layer.status.rxPriorityQueuesFull := rdiIntfNode.io.rxPriorityQueuesFull || 
                                          linkNode.io.err.rxPriorityQueuesFull
  io.layer.status.desTimedout := linkNode.io.err.desTimedout
  io.layer.status.invalidRouteUpper := switch.io.err.invalidRouteUpper
  io.layer.status.invalidRouteCurr := switch.io.err.invalidRouteCurr
  io.layer.status.invalidRouteLower := switch.io.err.invalidRouteLower

  io.link.out.bits := linkNode.io.txOut.bits
  io.link.out.fwClock := linkNode.io.txOut.fwClock
   
  // IOs for SidebandInterfaceNode
  rdiIntfNode.io.txCreditReturn := io.rdi.txCreditReturn
  rdiIntfNode.io.rxIn <> io.rdi.in
  rdiIntfNode.io.txOut <> io.rdi.out
  rdiIntfNode.io.txIn <> switch.io.upperLayer.to

  // IOs for SidebandSwitch
  switch.io.currLayer.from <> io.layer.in
  switch.io.currLayer.to <> io.layer.out
  switch.io.lowerLayer.from <> linkNode.io.rxOut
  switch.io.upperLayer.from <> rdiIntfNode.io.rxOut

  // IOs for SidebandLinkNode
  linkNode.io.txIn <> switch.io.lowerLayer.to
  linkNode.io.rxIn.bits := io.link.in.bits
  linkNode.io.rxIn.fwClock := io.link.in.fwClock  
  linkNode.io.ctrl.txMode := io.link.ctrl.txMode
  linkNode.io.ctrl.rxMode := io.link.ctrl.rxMode  
  

  // TODO: Maybe add buffers for tx and rx packets to/from the layer, might cause issues with
  // timeout cycles (not sure if a concern) but breaks up long combinational path.
  // --- Alternative solution is to add them in the layer itself
}

object MainLogPhySidebandChannel extends App {
  ChiselStage.emitSystemVerilogFile(
    new LogPhySidebandChannel(sbMsgWidth=128, sbLinkWidth=1, rdiNcWidth=32, numCredits=32, 
      desTimeoutCycles=512, queueDepths=SidebandPriorityQueueDepths()),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}