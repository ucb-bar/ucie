/*
  Flit-Aware Die-to-Die Interface (FDI) Bundle

  pl_* indicates Die-to-Die Adapter -> Protocol Layer
  lp_* indicates Protocol Layer -> Die-to-Die Adapter

  Signals are relative to the Protocol Layer
*/
package edu.berkeley.cs.uciedigital.interfaces

import chisel3._

case class FdiParams(nBytes: Int, ncWidth: Int)

class Fdi(params: FdiParams) extends Bundle {
  val lclk = Input(Bool())
  val lpIrdy = Output(Bool())
  val lpValid = Output(Bool())
  val lpData = Output(UInt((params.nBytes * 8).W))
  val plTrdy = Input(Bool())
  val plValid = Input(Bool())
  val plData = Input(UInt((params.nBytes * 8).W))
  val lpStateReq = Output(FDIStateReq())
  val lpLinkError = Output(Bool())
  val plStateSts = Input(FDIState())
  val plInbandPres = Input(Bool())
  val plRxActiveReq = Input(Bool())
  val lpRxActiveSts = Output(Bool())
  val plError = Input(Bool())
  val plCError = Input(Bool())
  val plNfError = Input(Bool())
  val plTrainError = Input(Bool())
  val plPhyInRecenter = Input(Bool())
  val plProtocol = Input(FDIProtocol())
  val plProtocolFlitFmt = Input(FDIFlitFormat())
  val plProtocolVld = Input(Bool())
  val plStallReq = Input(Bool())
  val lpStallAck = Output(Bool())
  val plSpeedmode = Input(SpeedMode())
  val plMaxSpeedmode = Input(Bool())
  val plLnkCfg = Input(LinkWidth())
  val plClkReq = Input(Bool())
  val lpClkAck = Output(Bool())
  val lpWakeReq = Output(Bool())
  val plWakeAck = Input(Bool())
  val plCfg = Input(UInt(params.ncWidth.W))
  val plCfgVld = Input(Bool())
  val plCfgCrd = Output(Bool())
  val lpCfg = Output(UInt(params.ncWidth.W))
  val lpCfgVld = Output(Bool())
  val lpCfgCrd = Input(Bool())
}
