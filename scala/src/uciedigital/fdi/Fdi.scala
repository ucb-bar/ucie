/*
  The Flit-Aware Die-to-Die Interface (FDI) bundle (Table 10-3). Directions
  are relative to the Protocol Layer.
 */

package edu.berkeley.cs.uciedigital.fdi

import chisel3._
import edu.berkeley.cs.uciedigital.common.{LinkWidth, SpeedMode}

// nBytes is the data width and ncWidth the sideband width.
case class FdiParams(nBytes: Int, ncWidth: Int)

// The transmit and receive data groups use txLclk and rxLclk, as on RDI. The
// rest use the control clock. This deviates from sec 10.2.2's one clock.
class Fdi(params: FdiParams) extends Bundle {
  val txLclk = Input(Clock())
  val rxLclk = Input(Clock())
  val lpIrdy = Output(Bool())
  val lpValid = Output(Bool())
  val lpData = Output(UInt((params.nBytes * 8).W))
  val lpStream = Output(StreamId())
  val plTrdy = Input(Bool())
  val plValid = Input(Bool())
  val plData = Input(UInt((params.nBytes * 8).W))
  val plStream = Input(StreamId())
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
  val plPhyInL1 = Input(Bool())
  val plPhyInL2 = Input(Bool())
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
  val plCfgCrd = Input(Bool())
  val lpCfg = Output(UInt(params.ncWidth.W))
  val lpCfgVld = Output(Bool())
  val lpCfgCrd = Output(Bool())
}
