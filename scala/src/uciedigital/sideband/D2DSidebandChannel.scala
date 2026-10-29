/*
  The sideband channel of the D2D Adapter, between FDI and RDI.
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.SkidBuffer

class D2DSidebandChannel(
    sbMsgWidth: Int,
    fdiNcWidth: Int,
    rdiNcWidth: Int,
    numCredits: Int,
    queueDepths: SidebandPriorityQueueDepths
) extends Module {
  val io = IO(new Bundle {
    val flushRequesterRegAccess = Input(Bool())
    val flushCompleterRegAccess = Input(Bool())
    val fdi = new Bundle {
      val in = Flipped(Valid(UInt(fdiNcWidth.W)))
      val out = Valid(UInt(fdiNcWidth.W))
      val txCreditReturn = Input(Bool())
      val rxCreditReturn = Output(Bool())
      val txHold = Input(Bool())
    }
    val layer = new Bundle {
      val in = Flipped(Decoupled(UInt(sbMsgWidth.W)))
      val out = Decoupled(UInt(sbMsgWidth.W))
      val status = new Bundle {
        val sbParityErr = Output(Bool())
        val sbParityErrEvent = Output(Bool())
        val fdiRxQueuesFull = Output(Bool())
        val rdiRxQueuesFull = Output(Bool())
        val invalidRouteUpper = Output(Bool())
        val invalidRouteCurr = Output(Bool())
        val invalidRouteLower = Output(Bool())
      }
    }
    val rdi = new Bundle {
      val in = Flipped(Valid(UInt(rdiNcWidth.W)))
      val out = Valid(UInt(rdiNcWidth.W))
      val txPacketCommit = Valid(UInt(sbMsgWidth.W))
      val txCreditReturn = Input(Bool())
      val txCreditAvailable = Output(Bool())
      val rxCreditReturn = Output(Bool())
      val txHold = Input(Bool())
    }
  })

  // ===========================================================================
  // Modules and status outputs
  // ===========================================================================
  val layerId = LayerId.d2d
  val upperIds = Seq(0) // Protocol(0)
  val lowerIds = Seq(2) // LogPHY(2)

  val fdiIntfNode = Module(
    new SidebandInterfaceNode(sbMsgWidth, fdiNcWidth, numCredits, queueDepths)
  )
  val switch = Module(
    new SidebandSwitch(
      layerId,
      upperIds,
      lowerIds,
      sbMsgWidth,
      routeRegAccessToCurrent = true,
      routeUpperRegRequestsToCurrent = true,
      classAwareLowerArbitration = true
    )
  )
  val rdiIntfNode = Module(
    new SidebandInterfaceNode(sbMsgWidth, rdiNcWidth, numCredits, queueDepths)
  )

  // layer.out is registered to break the long receive path. layer.in stays a
  // bypass skid, so the transmit path is combinational.
  val layerInBuffer = Module(new SkidBuffer(sbMsgWidth))
  val layerOutBuffer = Module(new Queue(UInt(sbMsgWidth.W), 2))

  io.layer.status.sbParityErr := rdiIntfNode.io.sbParityErr || fdiIntfNode.io.sbParityErr
  io.layer.status.sbParityErrEvent := rdiIntfNode.io.sbParityErrEvent ||
    fdiIntfNode.io.sbParityErrEvent

  // Table 9-33 [6:4] logs the two apart, 011b for FDI and 100b for RDI.
  io.layer.status.fdiRxQueuesFull := fdiIntfNode.io.rxPriorityQueuesFull
  io.layer.status.rdiRxQueuesFull := rdiIntfNode.io.rxPriorityQueuesFull
  io.layer.status.invalidRouteUpper := switch.io.err.invalidRouteUpper
  io.layer.status.invalidRouteCurr := switch.io.err.invalidRouteCurr
  io.layer.status.invalidRouteLower := switch.io.err.invalidRouteLower

  // ===========================================================================
  // FDI sideband node
  // ===========================================================================
  io.fdi.rxCreditReturn := fdiIntfNode.io.rxCreditReturn

  // sec 10.2.3.2 rule 8 and Table 10-3 hold pl_cfg, pl_cfg_vld and pl_cfg_crd
  // until lp_clk_ack.
  fdiIntfNode.io.txHold := io.fdi.txHold
  fdiIntfNode.io.txCreditReturn := io.fdi.txCreditReturn
  fdiIntfNode.io.flushRequesterRegAccess := false.B
  fdiIntfNode.io.flushCompleterRegAccess := false.B
  fdiIntfNode.io.rxIn <> io.fdi.in
  fdiIntfNode.io.txOut <> io.fdi.out
  fdiIntfNode.io.txIn <> switch.io.upperLayer.to

  // ===========================================================================
  // Switch and layer path
  // ===========================================================================
  layerInBuffer.io.in <> io.layer.in

  // A completion with dstid[2] clear answers the Protocol Layer on FDI and
  // never crosses the link. The RDI completer flush leaves it alone.
  val flushLayerPacket =
    (layerInBuffer.io.out.bits(58) &&
      SBM.isRemoteRegAccessRequest(layerInBuffer.io.out.bits(4, 0)) &&
      io.flushRequesterRegAccess) ||
      (layerInBuffer.io.out.bits(58) &&
        SBM.isRegAccessComplete(layerInBuffer.io.out.bits(4, 0)) &&
        io.flushCompleterRegAccess)
  switch.io.currLayer.from.valid := layerInBuffer.io.out.valid &&
    !flushLayerPacket
  switch.io.currLayer.from.bits := layerInBuffer.io.out.bits
  layerInBuffer.io.out.ready := flushLayerPacket ||
    switch.io.currLayer.from.ready
  switch.io.currLayer.to <> layerOutBuffer.io.enq

  // A flush can be a one-cycle pulse, and the test below sees only the head
  // of layerOutBuffer. So latch the occupancy and test until those entries
  // leave, or a stale completion can match in the new epoch.
  val deqFiring = layerOutBuffer.io.deq.fire
  val queuedAfterThisCycle = Mux(
    deqFiring && layerOutBuffer.io.count =/= 0.U,
    layerOutBuffer.io.count - 1.U,
    layerOutBuffer.io.count
  )

  def flushCoveringQueued(level: Bool): Bool = {
    val pending = RegInit(0.U(layerOutBuffer.io.count.getWidth.W))
    when(level) {
      pending := queuedAfterThisCycle
    }.elsewhen(pending =/= 0.U && deqFiring) {
      pending := pending - 1.U
    }
    level || pending =/= 0.U
  }
  val flushCompleterActive = flushCoveringQueued(io.flushCompleterRegAccess)
  val flushRequesterActive = flushCoveringQueued(io.flushRequesterRegAccess)

  val flushLayerOutPacket =
    (layerOutBuffer.io.deq.bits(58) &&
      SBM.isRemoteRegAccessRequest(layerOutBuffer.io.deq.bits(4, 0)) &&
      flushCompleterActive) ||
      (SBM.isRegAccessComplete(layerOutBuffer.io.deq.bits(4, 0)) &&
        flushRequesterActive)
  io.layer.out.valid := layerOutBuffer.io.deq.valid && !flushLayerOutPacket
  io.layer.out.bits := layerOutBuffer.io.deq.bits
  layerOutBuffer.io.deq.ready := flushLayerOutPacket || io.layer.out.ready
  switch.io.upperLayer.from <> fdiIntfNode.io.rxOut
  switch.io.lowerLayer.from <> rdiIntfNode.io.rxOut
  switch.io.lowerLayer.toClassReady := rdiIntfNode.io.txClassReady

  // ===========================================================================
  // RDI sideband node
  // ===========================================================================
  io.rdi.rxCreditReturn := rdiIntfNode.io.rxCreditReturn
  io.rdi.txPacketCommit := rdiIntfNode.io.txPacketCommit
  io.rdi.txCreditAvailable := rdiIntfNode.io.txCreditAvailable

  // sec 10.1.3.1 rule 6 and Table 10-1 hold lp_cfg, lp_cfg_vld and lp_cfg_crd
  // until pl_wake_ack.
  rdiIntfNode.io.txHold := io.rdi.txHold
  rdiIntfNode.io.txCreditReturn := io.rdi.txCreditReturn
  rdiIntfNode.io.flushRequesterRegAccess := io.flushRequesterRegAccess
  rdiIntfNode.io.flushCompleterRegAccess := io.flushCompleterRegAccess
  rdiIntfNode.io.rxIn <> io.rdi.in
  rdiIntfNode.io.txOut <> io.rdi.out
  rdiIntfNode.io.txIn <> switch.io.lowerLayer.to
}
