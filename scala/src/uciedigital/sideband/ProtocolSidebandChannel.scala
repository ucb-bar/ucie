/*
  The sideband channel of the Protocol Layer, on FDI.
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.SkidBuffer

class ProtocolSidebandChannel(
    sbMsgWidth: Int,
    fdiNcWidth: Int,
    numCredits: Int,
    queueDepths: SidebandPriorityQueueDepths
) extends Module {
  val io = IO(new Bundle {
    val layer = new Bundle {
      val in = Flipped(Decoupled(UInt(sbMsgWidth.W)))
      val out = Decoupled(UInt(sbMsgWidth.W))
      val status = new Bundle {
        val sbParityErr = Output(Bool())
        val sbParityErrEvent = Output(Bool())
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
      val txHold = Input(Bool())
    }
  })

  // ===========================================================================
  // Modules and status outputs
  // ===========================================================================
  val layerId = LayerId.protocol
  val upperIds = Seq()
  val lowerIds = Seq(1, 2) // D2D(1), LogPHY(2)

  val switch = Module(
    new SidebandSwitch(layerId, upperIds, lowerIds, sbMsgWidth)
  )
  val fdiIntfNode = Module(
    new SidebandInterfaceNode(sbMsgWidth, fdiNcWidth, numCredits, queueDepths)
  )
  val layerInBuffer = Module(new SkidBuffer(sbMsgWidth))
  val layerOutBuffer = Module(new SkidBuffer(sbMsgWidth))

  io.layer.status.sbParityErr := fdiIntfNode.io.sbParityErr
  io.layer.status.sbParityErrEvent := fdiIntfNode.io.sbParityErrEvent
  io.layer.status.rxPriorityQueuesFull := fdiIntfNode.io.rxPriorityQueuesFull
  io.layer.status.invalidRouteUpper := switch.io.err.invalidRouteUpper
  io.layer.status.invalidRouteCurr := switch.io.err.invalidRouteCurr
  io.layer.status.invalidRouteLower := switch.io.err.invalidRouteLower

  // ===========================================================================
  // FDI sideband node
  // ===========================================================================
  io.fdi.rxCreditReturn := fdiIntfNode.io.rxCreditReturn

  // sec 10.2.3.1 rule 6 and Table 10-3 hold lp_cfg, lp_cfg_vld and lp_cfg_crd
  // until pl_wake_ack.
  fdiIntfNode.io.txHold := io.fdi.txHold
  fdiIntfNode.io.txCreditReturn := io.fdi.txCreditReturn
  fdiIntfNode.io.flushRequesterRegAccess := false.B
  fdiIntfNode.io.flushCompleterRegAccess := false.B
  fdiIntfNode.io.rxIn <> io.fdi.in
  fdiIntfNode.io.txOut <> io.fdi.out
  fdiIntfNode.io.txIn <> switch.io.lowerLayer.to

  // ===========================================================================
  // Switch
  // ===========================================================================
  layerInBuffer.io.in <> io.layer.in
  switch.io.currLayer.from <> layerInBuffer.io.out
  switch.io.currLayer.to <> layerOutBuffer.io.in
  io.layer.out <> layerOutBuffer.io.out
  switch.io.lowerLayer.from <> fdiIntfNode.io.rxOut
  switch.io.upperLayer.from.valid := false.B
  switch.io.upperLayer.from.bits := 0.U
  switch.io.upperLayer.to.ready := true.B

  // Unused here, because classAwareLowerArbitration is off.
  switch.io.lowerLayer.toClassReady := VecInit(Seq.fill(4)(true.B))
}
