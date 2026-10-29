/*
  The TRAINERROR entry handshake of sec 4.5.3.8, one module for each side.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._

class TrainErrorRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val sendReq = Input(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := reset.asBool
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  sbMsgExchanger.io.req.valid := io.sendReq
  sbMsgExchanger.io.req.bits := PhyLinkMsg.create(SBM.TRAINERROR_ENTRY_REQ)
  sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
  sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TRAINERROR_ENTRY_RESP

  io.done := sbMsgExchanger.io.exchDone
}

class TrainErrorResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val wakeUp = Input(Bool())
    val sendResp = Input(Bool())
    val remoteRequestingTrainError =
      Output(Bool())
    val done = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  SidebandMessageExchanger.tieOff(sbMsgExchanger)
  sbMsgExchanger.io.clear := reset.asBool
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  io.remoteRequestingTrainError := sbMsgExchanger.io.msgReceived

  sbMsgExchanger.io.rxRefBitPattern.valid := io.wakeUp
  sbMsgExchanger.io.rxRefBitPattern.bits := SBM.TRAINERROR_ENTRY_REQ

  sbMsgExchanger.io.req.valid := io.sendResp && sbMsgExchanger.io.msgReceived
  sbMsgExchanger.io.req.bits := PhyLinkMsg.create(SBM.TRAINERROR_ENTRY_RESP)
  io.done := sbMsgExchanger.io.exchDone
}
