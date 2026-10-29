/*
  The link management message ports between the Adapter LSM and the sideband
  path. Whole sideband packets cross these ports, so the LSM builds each
  message and the sideband path only forwards it.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import edu.berkeley.cs.uciedigital.sideband._

class D2DSidebandModuleIO(val sbParams: SidebandParams) extends Bundle {
  val rcvValid = Output(Bool())
  val rcvBits = Output(UInt(sbParams.sbNodeMsgWidth.W))
  val sntValid = Input(Bool())
  val sntBits = Input(UInt(sbParams.sbNodeMsgWidth.W))
  val rdy = Output(Bool())
}

class AdapterSbTxIO(val sbParams: SidebandParams) extends Bundle {
  val valid = Output(Bool())
  val bits = Output(UInt(sbParams.sbNodeMsgWidth.W))
  val rdy = Input(Bool())
}

class AdapterSbRxIO(val sbParams: SidebandParams) extends Bundle {
  val valid = Input(Bool())
  val bits = Input(UInt(sbParams.sbNodeMsgWidth.W))
}
