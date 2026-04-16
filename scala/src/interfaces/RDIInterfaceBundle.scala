/*
  Raw Die-to-Die Interface (RDI) Bundle

  pl_* indicates Physical Layer -> Die-to-Die Adapter
  lp_* indicates Die-to-Die Adapter -> Physical Layer
*/
package edu.berkeley.cs.uciedigital.interfaces

import chisel3._

case class RdiParams(nBytes: Int, ncWidth: Int) 

class Rdi(params: RdiParams) extends Bundle {
  val lclk = Input(Bool())
  val lpIrdy = Input(Bool())
  val lpValid = Input(Bool())
  val lpData = Input(UInt((params.nBytes * 8).W))
  val plTrdy = Output(Bool())
  val plValid = Output(Bool())
  val plData = Output(UInt((params.nBytes * 8).W))
  val lpStateReq = Input(RDIStateReq())
  val lpLinkError = Input(Bool())
  val plStateSts = Output(RDIState())
  val plInbandPres = Output(Bool())
  val plError = Output(Bool())
  val plCError = Output(Bool())
  val plNfError = Output(Bool())
  val plTrainError = Output(Bool())
  val plPhyInRecenter = Output(Bool())
  val plStallReq = Output(Bool())
  val lpStallAck = Input(Bool())
  val plSpeedmode = Output(SpeedMode())
  val plMaxSpeedmode = Output(Bool())
  val plLnkCfg = Output(LinkWidth())
  val plClkReq = Output(Bool())
  val lpClkAck = Input(Bool())
  val lpWakeReq = Input(Bool())
  val plWakeAck = Output(Bool())
  val plCfg = Output(UInt(params.ncWidth.W))
  val plCfgVld = Output(Bool())
  val plCfgCrd = Output(Bool())
  val lpCfg = Input(UInt(params.ncWidth.W))
  val lpCfgVld = Input(Bool())
  val lpCfgCrd = Input(Bool())
}
