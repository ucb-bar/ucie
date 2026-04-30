/*
  Description:
    Top-level protocol layer that ties together mainband, sideband, and FDI state handling.
*/

package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

class ProtocolLayer(
  val params: ProtocolLayerParams = ProtocolLayerParams(),
  val fdiParams: FdiParams = FdiParams(64, 32),
  val sbParams: SidebandParams = new SidebandParams(),
) extends Module {
  val io = IO(new ProtocolLayerIO(params, fdiParams))

  val stateController = Module(new ProtocolStateController())
  val mainbandTx = Module(new ProtocolMainbandTx(fdiParams.nBytes, params.txQueueDepth))
  val mainbandRx = Module(new ProtocolMainbandRx(fdiParams.nBytes, params.rxQueueDepth))
  val sidebandChannel = Module(new ProtocolSidebandChannel(
    sbMsgWidth = sbParams.sbNodeMsgWidth,
    sbLinkWidth = sbParams.sbLinkWidth,
    fdiNcWidth = fdiParams.ncWidth,
    numCredits = sbParams.maxCrd,
    queueDepths = SidebandPriorityQueueDepths()
  ))

  stateController.io.ctrl <> io.ctrl
  stateController.io.fdi.plStateSts := io.fdi.plStateSts
  stateController.io.fdi.plInbandPres := io.fdi.plInbandPres
  stateController.io.fdi.plRxActiveReq := io.fdi.plRxActiveReq
  stateController.io.fdi.plProtocol := io.fdi.plProtocol
  stateController.io.fdi.plProtocolFlitFmt := io.fdi.plProtocolFlitFmt
  stateController.io.fdi.plProtocolVld := io.fdi.plProtocolVld
  stateController.io.fdi.plStallReq := io.fdi.plStallReq
  stateController.io.fdi.plClkReq := io.fdi.plClkReq
  stateController.io.txIdle := mainbandTx.io.txIdle
  stateController.io.rxReadyForActive := mainbandRx.io.rxReadyForActive

  io.fdi.lpStateReq := stateController.io.fdi.lpStateReq
  io.fdi.lpLinkError := stateController.io.fdi.lpLinkError
  io.fdi.lpStallAck := stateController.io.fdi.lpStallAck
  io.fdi.lpWakeReq := stateController.io.fdi.lpWakeReq
  io.fdi.lpClkAck := stateController.io.fdi.lpClkAck
  io.fdi.lpRxActiveSts := stateController.io.fdi.lpRxActiveSts

  mainbandTx.io.chip <> io.mainbandTx
  mainbandTx.io.fdi.plTrdy := io.fdi.plTrdy
  mainbandTx.io.active := io.fdi.plStateSts === FDIState.active
  mainbandTx.io.stallRequested := io.fdi.plStallReq
  io.fdi.lpIrdy := mainbandTx.io.fdi.lpIrdy
  io.fdi.lpValid := mainbandTx.io.fdi.lpValid
  io.fdi.lpData := mainbandTx.io.fdi.lpData

  mainbandRx.io.fdi.plValid := io.fdi.plValid
  mainbandRx.io.fdi.plData := io.fdi.plData
  mainbandRx.io.active := io.fdi.plStateSts === FDIState.active
  mainbandRx.io.rxPathActive := stateController.io.fdi.lpRxActiveSts
  mainbandRx.io.clear := stateController.io.clearRuntimeState
  io.mainbandRx <> mainbandRx.io.chip

  sidebandChannel.io.fdi.in.bits := io.fdi.plCfg
  sidebandChannel.io.fdi.in.valid := io.fdi.plCfgVld
  sidebandChannel.io.fdi.txCreditReturn := io.fdi.lpCfgCrd
  io.fdi.lpCfg := sidebandChannel.io.fdi.out.bits
  io.fdi.lpCfgVld := sidebandChannel.io.fdi.out.valid
  io.fdi.plCfgCrd := sidebandChannel.io.fdi.rxCreditReturn

  // TODO: Expose a protocol-layer sideband interface once upper-layer protocol
  // messages are defined. For now, always sink sideband traffic so it never blocks.
  sidebandChannel.io.layer.in.bits := 0.U
  sidebandChannel.io.layer.in.valid := false.B
  sidebandChannel.io.layer.out.ready := true.B

  io.status.linkState := stateController.io.status.linkState
  io.status.negotiatedProtocolValid := stateController.io.status.negotiatedProtocolValid
  io.status.negotiatedProtocol := stateController.io.status.negotiatedProtocol
  io.status.negotiatedFlitFormat := stateController.io.status.negotiatedFlitFormat
  io.status.stalled := stateController.io.status.stalled
  io.status.rxOverflow := mainbandRx.io.rxOverflow
}

object MainProtocolLayer extends App {
  ChiselStage.emitSystemVerilogFile(
    new ProtocolLayer(),
    args = Array("-td", "./generatedVerilog/protocol"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}
