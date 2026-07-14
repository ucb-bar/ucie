// Sideband Mailbox register set and trigger FSM for the UCIe Link DVSEC.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util._
import freechips.rocketchip.regmapper.{RegField, RegFieldAccessType, RegFieldDesc, RegWriteFn}

class SidebandMailbox(f: RegFieldTypes, sb: MailboxToSideband, numLocators: Int) {

  private val StatusCA = 0.U(2.W)
  private val StatusUR = 1.U(2.W)

  private def isRead(opcode: UInt): Bool = !opcode(0)

  private val opcode = f.RW(5, 0x04, "mailbox_opcode",
    "Sideband Mailbox Index Low Opcode")
  private val be = f.RW(8, 0xff, "mailbox_be",
    "Sideband Mailbox Index Low Byte Enables")
  private val addrLo = f.RW(19, 0, "mailbox_addr_lo",
    "Sideband Mailbox Index Low Addr of sideband accesses")
  private val addrHi = f.RW(5, 0, "mailbox_addr_hi",
    "Sideband Mailbox Index High Addr of sideband accesses")

  private val sIdle :: sReq :: sResp :: Nil = Enum(3)
  private val state = withReset(f.nonStickyReset)(RegInit(sIdle))
  private val respFire = sb.resp.valid && (state === sResp)

  private val trigger = f.RWautoClear(
    hwDone = respFire,
    ignoreWhen = false.B,
    name = "mailbox_trigger",
    description = "Sideband Mailbox Control Write/Read Trigger (auto-clear on completion)"
  )

  private val readCapture = respFire && isRead(opcode.reg)
  private val dataLoReg = withReset(f.nonStickyReset)(RegInit(0.U(32.W)))
  private val dataHiReg = withReset(f.nonStickyReset)(RegInit(0.U(32.W)))
  private def dataField(reg: UInt, hwHalf: UInt, name: String, description: String): RegField = {
    when(readCapture) { reg := hwHalf }
    val writeFn = RegWriteFn { (valid, data) =>
      when(valid && !readCapture) { reg := data }
      true.B
    }
    val d = RegFieldDesc(name, description, access = RegFieldAccessType.RW, reset = Some(0), volatile = true)
    RegField(32, reg, writeFn, Some(d))
  }
  private val dataLo = dataField(dataLoReg, sb.resp.bits.rdata(31, 0), "mailbox_data_lo",
    "Sideband Mailbox Data Low write data (SW) / read data (HW on read completion)")
  private val dataHi = dataField(dataHiReg, sb.resp.bits.rdata(63, 32), "mailbox_data_hi",
    "Sideband Mailbox Data High write data (SW) / read data (HW on read completion)")

  private val statusHwSet = WireDefault(0.U(2.W))
  private val statusField = f.RW1C(2, statusHwSet, "mailbox_status",
    "Sideband Mailbox Status: Write/Read status (CA/UR/Success)")

  val headerLog1: Valid[UInt] = {
    val w = Wire(Valid(UInt(64.W)))
    w.valid := respFire && ((sb.resp.bits.status === StatusCA) || (sb.resp.bits.status === StatusUR))
    w.bits := sb.resp.bits.header
    w
  }

  sb.req.valid := (state === sReq)
  sb.req.bits.opcode := opcode.reg
  sb.req.bits.be := be.reg
  sb.req.bits.addr := Cat(addrHi.reg, addrLo.reg)
  sb.req.bits.wdata := Cat(dataHiReg, dataLoReg)
  sb.resp.ready := (state === sResp)

  when(respFire) { statusHwSet := sb.resp.bits.status }

  switch(state) {
    is(sIdle) { when(trigger.fire) { state := sReq } }
    is(sReq) { when(sb.req.ready) { state := sResp } }
    is(sResp) { when(sb.resp.valid) { state := sIdle } }
  }

  def entries(base: BigInt): Seq[RegField.Map] = {
    val mb = DvsecOffsets.mailboxBase(numLocators)
    def at(off: Int): Int = (base + mb + off).toInt
    Seq(
      at(0x00) -> Seq(opcode.field, be.field, addrLo.field),
      at(0x04) -> Seq(addrHi.field, f.RsvdP(27, "mailbox_index_hi_rsvd")),
      at(0x08) -> Seq(dataLo),
      at(0x0c) -> Seq(dataHi),
      at(0x10) -> Seq(trigger.field, f.RsvdP(31, "mailbox_ctrl_rsvd")),
      at(0x14) -> Seq(statusField.field, f.RsvdZ(30, "mailbox_status_rsvd"))
    )
  }
}
