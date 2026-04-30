/*
  The top level associated with the Sideband channel for the D2D Layer

  Contains a SidebandInterfaceNode to facilitate communication over FDI, SidebandSwitch to route
  packets and a SidebandInterfaceNode to facilitate communication over RDI.
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

class D2DSidebandChannel(
  sbMsgWidth: Int, sbLinkWidth: Int, fdiNcWidth: Int, rdiNcWidth: Int, numCredits: Int,
  queueDepths: SidebandPriorityQueueDepths) extends Module {
  val io = IO(new Bundle {
    val fdi = new Bundle {
      val in = Flipped(Valid(UInt(fdiNcWidth.W)))
      val out = Valid(UInt(fdiNcWidth.W))
      val txCreditReturn = Input(Bool())
      val rxCreditReturn = Output(Bool()) 
    }
    val layer = new Bundle {
      val in = Flipped(Decoupled(UInt(sbMsgWidth.W)))
      val out = Decoupled(UInt(sbMsgWidth.W))    
      val status = new Bundle {
        val sbParityErr = Output(Bool())
        val rxPriorityQueuesFull = Output(Bool())
        val invalidRouteUpper = Output(Bool())
        val invalidRouteCurr = Output(Bool())
        val invalidRouteLower = Output(Bool())        
      }
    }
    val rdi = new Bundle {
      val in = Flipped(Valid(UInt(rdiNcWidth.W)))
      val out = Valid(UInt(rdiNcWidth.W))
      val txCreditReturn = Input(Bool())
      val rxCreditReturn = Output(Bool()) 
    }
  })

  val layerId = LayerId.d2d   // 1
  val upperIds = Seq(0)       // Protocol(0)
  val lowerIds = Seq(2)       // LogPHY(2)

  val fdiIntfNode = Module(new SidebandInterfaceNode(sbMsgWidth, fdiNcWidth, numCredits, queueDepths))  
  val switch = Module(new SidebandSwitch(layerId, upperIds, lowerIds, sbMsgWidth))
  val rdiIntfNode = Module(new SidebandInterfaceNode(sbMsgWidth, rdiNcWidth, numCredits, queueDepths))

  // IOs for module
  io.rdi.rxCreditReturn := rdiIntfNode.io.rxCreditReturn
  io.fdi.rxCreditReturn := fdiIntfNode.io.rxCreditReturn
  io.layer.status.sbParityErr := rdiIntfNode.io.sbParityErr || fdiIntfNode.io.sbParityErr
  io.layer.status.rxPriorityQueuesFull := rdiIntfNode.io.rxPriorityQueuesFull || 
                                          fdiIntfNode.io.rxPriorityQueuesFull
  io.layer.status.invalidRouteUpper := switch.io.err.invalidRouteUpper
  io.layer.status.invalidRouteCurr := switch.io.err.invalidRouteCurr
  io.layer.status.invalidRouteLower := switch.io.err.invalidRouteLower

  
  // IOs for SidebandInterfaceNode (FDI)
  fdiIntfNode.io.txCreditReturn := io.fdi.txCreditReturn
  fdiIntfNode.io.rxIn <> io.fdi.in
  fdiIntfNode.io.txOut <> io.fdi.out
  fdiIntfNode.io.txIn <> switch.io.upperLayer.to

  // IOs for SidebandSwitch
  switch.io.currLayer.from <> io.layer.in
  switch.io.currLayer.to <> io.layer.out
  switch.io.upperLayer.from <> fdiIntfNode.io.rxOut
  switch.io.lowerLayer.from <> rdiIntfNode.io.rxOut
  
  // IOs for SidebandInterfaceNode (RDI)
  rdiIntfNode.io.txCreditReturn := io.rdi.txCreditReturn
  rdiIntfNode.io.rxIn <> io.rdi.in
  rdiIntfNode.io.txOut <> io.rdi.out
  rdiIntfNode.io.txIn <> switch.io.lowerLayer.to

  // TODO: Maybe add buffers for tx and rx packets to/from the layer, might cause issues with
  // timeout cycles (not sure if a concern) but breaks up long combinational path.
  // --- Alternative solution is to add them in the layer itself
}

object MainD2DSidebandChannel extends App {
  ChiselStage.emitSystemVerilogFile(
    new D2DSidebandChannel(sbMsgWidth=128, sbLinkWidth=1, rdiNcWidth=32, fdiNcWidth=32, 
    numCredits=32, queueDepths=SidebandPriorityQueueDepths()),
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