/*
  The Raw Die-to-Die Interface (RDI) bundle and its state encodings
  (Table 10-1). Directions are relative to the Logical PHY.
 */

package edu.berkeley.cs.uciedigital.rdi

import chisel3._
import edu.berkeley.cs.uciedigital.common.{LinkWidth, SpeedMode}

case class RdiParams(nBytes: Int, ncWidth: Int)

// txLclk carries lp_irdy, lp_valid, lp_data and pl_trdy, and rxLclk carries
// pl_valid, pl_data and pl_error. The rest is control, but each side syncs the
// stall pair it receives. This deviates from sec 10.1.2's one clock.
class Rdi(params: RdiParams) extends Bundle {
  val txLclk = Output(Clock())
  val rxLclk = Output(Clock())
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

object RDIState extends ChiselEnum {
  val reset = Value("b0000".U(4.W))
  val active = Value("b0001".U(4.W))
  val activePmNak = Value("b0011".U(4.W))
  val l1 = Value("b0100".U(4.W))
  val l2 = Value("b1000".U(4.W))
  val linkReset = Value("b1001".U(4.W))
  val linkError = Value("b1010".U(4.W))
  val retrain = Value("b1011".U(4.W))
  val disabled = Value("b1100".U(4.W))
}

object RDIStateReq extends ChiselEnum {
  val nop = Value("b0000".U(4.W))
  val active = Value("b0001".U(4.W))
  val l1 = Value("b0100".U(4.W))
  val l2 = Value("b1000".U(4.W))
  val linkReset = Value("b1001".U(4.W))
  val retrain = Value("b1011".U(4.W))
  val disabled = Value("b1100".U(4.W))
}
