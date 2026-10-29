/*
  The sideband channel of the Logical PHY, between RDI and the sideband link.
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.SkidBuffer

class LogPhySidebandChannel(
    sbMsgWidth: Int,
    sbLinkWidth: Int,
    rdiNcWidth: Int,
    numCredits: Int,
    desTimeoutCycles: Int,
    queueDepths: SidebandPriorityQueueDepths
) extends Module {
  val io = IO(new Bundle {
    val linkReset = Input(Bool())
    val flushRegAccess = Input(Bool())
    val clearParityContainment = Input(Bool())
    val rdi = new Bundle {
      val in = Flipped(Valid(UInt(rdiNcWidth.W)))
      val out = Valid(UInt(rdiNcWidth.W))
      val txCreditReturn = Input(Bool())
      val rxCreditReturn = Output(Bool())
      val activity = Output(Bool())
      val txHold = Input(Bool())
    }
    val layer = new Bundle {
      val in = Flipped(Decoupled(UInt(sbMsgWidth.W)))
      val out = Decoupled(new SbLinkRxWord(sbMsgWidth))
      val status = new Bundle {
        val sbParityErr = Output(Bool())
        val sbParityErrEvent = Output(Bool())
        val rxPriorityQueuesFull = Output(Bool())
        val desTimedout = Output(Bool())
        val invalidRouteUpper = Output(Bool())
        val invalidRouteCurr = Output(Bool())
        val invalidRouteLower = Output(Bool())
      }
    }
    val link = new Bundle {
      val in = new SidebandRxPins(sbLinkWidth)
      val out = new SidebandTxPins(sbLinkWidth)
      val ctrl = new Bundle {
        val txMode = Input(SBRxTxMode())
        val rxMode = Input(SBRxTxMode())
        val freezeAcceptingPackets = Input(Bool())
        val allPacketsSent = Output(Bool())
      }
    }
  })

  // ===========================================================================
  // Modules and status outputs
  // ===========================================================================
  val layerId = LayerId.logPhy
  val upperIds = Seq(0, 1) // Protocol(0) and D2D(1)
  val lowerIds = Seq()

  val rdiIntfNode = Module(
    new SidebandInterfaceNode(sbMsgWidth, rdiNcWidth, numCredits, queueDepths)
  )
  val switch = Module(
    new SidebandSwitch(
      layerId,
      upperIds,
      lowerIds,
      sbMsgWidth,
      routeRemoteRegRequestsToUpper = true
    )
  )

  // Table 10-1 sets RDI credits only on domain reset exit, so an LTSM RESET
  // resets the link side alone.
  val linkSideReset = reset.asBool || io.linkReset

  val linkNode = withReset(linkSideReset) {
    Module(
      new SidebandLinkNode(
        sbMsgWidth,
        sbLinkWidth,
        desTimeoutCycles,
        queueDepths
      )
    )
  }

  // layer.out is registered to break the long receive path. layer.in stays a
  // bypass skid, so the transmit path is combinational.
  val layerInBuffer = Module(new SkidBuffer(sbMsgWidth))
  val layerOutBuffer = Module(new Queue(new SbLinkRxWord(sbMsgWidth), 2))

  io.layer.status.sbParityErr := rdiIntfNode.io.sbParityErr || linkNode.io.err.sbParityErr
  io.layer.status.sbParityErrEvent := rdiIntfNode.io.sbParityErrEvent ||
    linkNode.io.err.sbParityErrEvent
  io.layer.status.rxPriorityQueuesFull := rdiIntfNode.io.rxPriorityQueuesFull ||
    linkNode.io.err.rxPriorityQueuesFull
  io.layer.status.desTimedout := linkNode.io.err.desTimedout
  io.layer.status.invalidRouteUpper := switch.io.err.invalidRouteUpper
  io.layer.status.invalidRouteCurr := switch.io.err.invalidRouteCurr
  io.layer.status.invalidRouteLower := switch.io.err.invalidRouteLower

  // ===========================================================================
  // RDI sideband node
  // ===========================================================================
  io.rdi.rxCreditReturn := rdiIntfNode.io.rxCreditReturn

  // Counts a queued transfer too, because the clock request must come before
  // pl_cfg_vld moves (sec 10.1.3.2 rule 9). Receive activity needs the clocks.
  io.rdi.activity := io.rdi.in.valid || rdiIntfNode.io.txPending

  rdiIntfNode.io.txHold := io.rdi.txHold
  rdiIntfNode.io.txCreditReturn := io.rdi.txCreditReturn

  // LogPHY has no requester/completer split, so one input fans to both.
  rdiIntfNode.io.flushRequesterRegAccess := io.flushRegAccess
  rdiIntfNode.io.flushCompleterRegAccess := io.flushRegAccess
  rdiIntfNode.io.rxIn <> io.rdi.in
  rdiIntfNode.io.txOut <> io.rdi.out
  rdiIntfNode.io.txIn <> switch.io.upperLayer.to
  switch.io.upperLayer.from <> rdiIntfNode.io.rxOut

  // ===========================================================================
  // Switch and layer path
  // ===========================================================================
  layerInBuffer.io.in <> io.layer.in
  switch.io.currLayer.from <> layerInBuffer.io.out
  switch.io.lowerLayer.toClassReady := VecInit(Seq.fill(4)(true.B))

  // Each word carries its capture framing, so a mode change cannot re-route a
  // queued word.
  val rxIsRaw = linkNode.io.rxOut.valid && linkNode.io.rxOut.bits.isRaw

  // A RAW word has no header for the switch to route on, so it bypasses it.
  switch.io.lowerLayer.from.valid := linkNode.io.rxOut.valid && !rxIsRaw
  switch.io.lowerLayer.from.bits := linkNode.io.rxOut.bits.data

  layerOutBuffer.io.enq.valid := Mux(
    rxIsRaw,
    linkNode.io.rxOut.valid,
    switch.io.currLayer.to.valid
  )
  layerOutBuffer.io.enq.bits.data := Mux(
    rxIsRaw,
    linkNode.io.rxOut.bits.data,
    switch.io.currLayer.to.bits
  )

  layerOutBuffer.io.enq.bits.isRaw := rxIsRaw

  switch.io.currLayer.to.ready := layerOutBuffer.io.enq.ready && !rxIsRaw
  linkNode.io.rxOut.ready := Mux(
    rxIsRaw,
    layerOutBuffer.io.enq.ready,
    switch.io.lowerLayer.from.ready
  )
  io.layer.out <> layerOutBuffer.io.deq

  // ===========================================================================
  // Link node
  // ===========================================================================
  io.link.out <> linkNode.io.txOut
  io.link.ctrl.allPacketsSent := linkNode.io.ctrl.allPacketsSent

  linkNode.io.txIn <> switch.io.lowerLayer.to
  linkNode.io.rxIn <> io.link.in
  linkNode.io.ctrl.txMode := io.link.ctrl.txMode
  linkNode.io.ctrl.rxMode := io.link.ctrl.rxMode

  // The freeze reaches the link node only once the layer path ahead of it is
  // empty, so a word already queued still finishes (sec 4.5.3.8).
  linkNode.io.ctrl.freezeAcceptingPackets :=
    io.link.ctrl.freezeAcceptingPackets && !layerInBuffer.io.out.valid
  linkNode.io.flushRegAccess := io.flushRegAccess
  linkNode.io.clearParityContainment := io.clearParityContainment
}
