// Sideband mailbox request/response bundles.
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
}

class MailboxToSideband extends Bundle {
  val req: DecoupledIO[MailboxSbReq] = Decoupled(new MailboxSbReq)
  val resp: DecoupledIO[MailboxSbResp] = Flipped(Decoupled(new MailboxSbResp))
}
