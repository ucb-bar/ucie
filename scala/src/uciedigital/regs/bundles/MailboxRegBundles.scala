/*
  The IO of the sideband mailbox and of the sideband register target.
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO}

class MailboxSbReq extends Bundle {
  val opcode = UInt(5.W)
  val be = UInt(8.W)
  val addr = UInt(24.W)
  val wdata = UInt(64.W)
}

class MailboxSbResp extends Bundle {
  val status = UInt(2.W)
  val rdata = UInt(64.W)
  val header = UInt(64.W)
  val headerValid = Bool()
}

class MailboxToSideband extends Bundle {
  val req: DecoupledIO[MailboxSbReq] = Decoupled(new MailboxSbReq)
  val resp: DecoupledIO[MailboxSbResp] = Flipped(Decoupled(new MailboxSbResp))
}

class SidebandRegTargetReq extends Bundle {
  val write = Bool()
  val is64 = Bool()
  val logicalOffset = UInt(24.W)
  val be = UInt(8.W)
  val wdata = UInt(64.W)
}

class SidebandRegTargetResp extends Bundle {
  val rdata = UInt(64.W)
  val unsupported = Bool()
  val internalError = Bool()
}

class SidebandRegTargetIO extends Bundle {
  val req: DecoupledIO[SidebandRegTargetReq] =
    Flipped(Decoupled(new SidebandRegTargetReq))
  val resp: DecoupledIO[SidebandRegTargetResp] =
    Decoupled(new SidebandRegTargetResp)
}
