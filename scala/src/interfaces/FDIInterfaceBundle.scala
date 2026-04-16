/*
  Flit-Aware Die-to-Die Interface (FDI) Bundle

  pl_* indicates Die-to-Die Adapter -> Protocol Layer
  lp_* indicates Protocol Layer -> Die-to-Die Adapter
*/
package edu.berkeley.cs.uciedigital.interfaces

import chisel3._

case class FdiParams(width: Int, sbWidth: Int)

class Fdi(params: FdiParams) extends Bundle {
  val lclk = Input(Bool())
  val lpIrdy = Output(Bool())
  val lpValid = Output(Bool())
  val lpData = Output(UInt((params.width * 8).W))
  val plTrdy = Input(Bool())
  val plValid = Input(Bool())
  val plData = Input(UInt((params.width * 8).W))
  val lpStateReq = Output(RDIStateReq())
  val lpLinkError = Output(Bool())
  val plStateSts = Input(RDIState())
  val plInbandPres = Input(Bool())
  val plError = Input(Bool())
  val plCError = Input(Bool())
  val plNfError = Input(Bool())
  val plTrainError = Input(Bool())
  val plPhyInRecenter = Input(Bool())
  val plStallReq = Input(Bool())
  val lpStallAck = Output(Bool())
  val plSpeedmode = Input(SpeedMode())
  val plMaxSpeedmode = Input(Bool())
  val plLnkCfg = Input(LinkWidth())
  val plClkReq = Input(Bool())
  val plWakeAck = Input(Bool())
  val plCfg = Input(UInt(params.sbWidth.W))
  val plCfgVld = Input(Bool())
  val plCfgCrd = Output(Bool())
  val lpCfg = Output(UInt(params.sbWidth.W))
  val lpCfgVld = Output(Bool())
  val lpCfgCrd = Input(Bool())
}
