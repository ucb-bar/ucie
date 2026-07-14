/*
  The top level associated with the Sideband channel for the Protocol Layer.

  Contains a SidebandInterfaceNode to facilitate communication over FDI, SidebandSwitch to route
  packets.
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.SkidBuffer

class ProtocolSidebandChannel(
  sbMsgWidth: Int, fdiNcWidth: Int, numCredits: Int,
  queueDepths: SidebandPriorityQueueDepths) extends Module {
  val io = IO(new Bundle {    
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
    val fdi = new Bundle {
      val in = Flipped(Valid(UInt(fdiNcWidth.W)))
      val out = Valid(UInt(fdiNcWidth.W))
      val txCreditReturn = Input(Bool())
      val rxCreditReturn = Output(Bool()) 
    }
  })

  val layerId = LayerId.protocol  // 0
  val upperIds = Seq()            // No layers above
  val lowerIds = Seq(1, 2)        // D2D(1), LogPHY(2)

  val switch = Module(new SidebandSwitch(layerId, upperIds, lowerIds, sbMsgWidth))
  val fdiIntfNode = Module(new SidebandInterfaceNode(sbMsgWidth, fdiNcWidth, numCredits, queueDepths))  
  val layerInBuffer = Module(new SkidBuffer(sbMsgWidth))
  val layerOutBuffer = Module(new SkidBuffer(sbMsgWidth))

  // IOs for module
  io.fdi.rxCreditReturn := fdiIntfNode.io.rxCreditReturn
  io.layer.status.sbParityErr := fdiIntfNode.io.sbParityErr
  io.layer.status.rxPriorityQueuesFull := fdiIntfNode.io.rxPriorityQueuesFull
  io.layer.status.invalidRouteUpper := switch.io.err.invalidRouteUpper
  io.layer.status.invalidRouteCurr := switch.io.err.invalidRouteCurr
  io.layer.status.invalidRouteLower := switch.io.err.invalidRouteLower

  
  // IOs for SidebandInterfaceNode (FDI)
  fdiIntfNode.io.txCreditReturn := io.fdi.txCreditReturn
  fdiIntfNode.io.rxIn <> io.fdi.in
  fdiIntfNode.io.txOut <> io.fdi.out
  fdiIntfNode.io.txIn <> switch.io.lowerLayer.to

  // IOs for SidebandSwitch
  layerInBuffer.io.in <> io.layer.in
  switch.io.currLayer.from <> layerInBuffer.io.out
  switch.io.currLayer.to <> layerOutBuffer.io.in
  io.layer.out <> layerOutBuffer.io.out
  switch.io.lowerLayer.from <> fdiIntfNode.io.rxOut
  switch.io.upperLayer.from.valid := false.B
  switch.io.upperLayer.from.bits := 0.U
  switch.io.upperLayer.to.ready := true.B

  // TODO: Maybe add buffers for tx and rx packets to/from the layer, might cause issues with
  // timeout cycles (not sure if a concern) but breaks up long combinational path.
  // --- Alternative solution is to add them in the layer itself
}

object MainProtocolSidebandChannel extends App {
  ChiselStage.emitSystemVerilogFile(
    new ProtocolSidebandChannel(sbMsgWidth=128, fdiNcWidth=32, numCredits=32,
    queueDepths=SidebandPriorityQueueDepths()),
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
