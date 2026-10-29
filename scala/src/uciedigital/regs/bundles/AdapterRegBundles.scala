/*
  The IO between the D2D Adapter and its registers (sec 9.5.3.2 to 9.5.3.21).
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util.Valid

class HeaderLog2Fields extends Bundle {
  val timeoutEnc = UInt(4.W)
  val rxOverflowEnc = UInt(3.W)
  val lsmResponse = UInt(3.W)
  val lsmId = Bool()
  val paramExchSuccess = Bool()
  val flitFormat = UInt(4.W)
}

class AdapterToRegs extends Bundle {
  val uncorrErrSet = Vec(6, Bool())
  val corrErrSet = Vec(5, Bool())
  val headerLog1 = Valid(UInt(64.W))
  val headerLog1Rearm = Bool()
  val headerLog2 = new HeaderLog2Fields
  val advCapAdapter = Valid(UInt(64.W))
  val finCapAdapter = Valid(UInt(64.W))
}

class RegsToAdapter extends Bundle {
  val uncorrMask = UInt(6.W)
  val uncorrSeverity = UInt(6.W)
  val corrMask = UInt(5.W)
  val remoteRegAccessThreshold = UInt(4.W)
}
