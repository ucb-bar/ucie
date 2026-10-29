/*
  The Sideband Mailbox registers (sec 9.5.1.11 to 9.5.1.16) and their FSM.
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util._
import freechips.rocketchip.regmapper.{
  RegField,
  RegFieldAccessType,
  RegFieldDesc,
  RegFieldWrType,
  RegWriteFn
}

class SidebandMailboxRegBlock(
    f: RegFieldTypes,
    sb: MailboxToSideband,
    numLocators: Int
) {
  val StatusCA = 0.U(2.W)
  val StatusUR = 1.U(2.W)
  val StatusSuccess = 3.U(2.W)

  def isRead(opcode: UInt): Bool = !opcode(0)

  // ===========================================================================
  // Request
  // ===========================================================================
  val opcode =
    f.RW(5, 0x04, "mailbox_opcode", "Sideband Mailbox Index Low Opcode")
  val be =
    f.RW(8, 0x0f, "mailbox_be", "Sideband Mailbox Index Low Byte Enables")
  val addrLo = f.RW(
    19,
    0,
    "mailbox_addr_lo",
    "Sideband Mailbox Index Low Addr of sideband accesses"
  )
  val addrHi = f.RW(
    5,
    0,
    "mailbox_addr_hi",
    "Sideband Mailbox Index High Addr of sideband accesses"
  )

  val sIdle :: sReq :: sResp :: Nil = Enum(3)
  val state = withReset(f.nonStickyReset)(RegInit(sIdle))
  val respFire = sb.resp.valid && (state === sResp)

  val trigger = f.RWautoClear(
    hwDone = respFire,
    ignoreWhen = false.B,
    name = "mailbox_trigger",
    description =
      "Sideband Mailbox Control Write/Read Trigger (auto-clear on completion)"
  )

  val dataLoReg = withReset(f.nonStickyReset)(RegInit(0.U(32.W)))
  val dataHiReg = withReset(f.nonStickyReset)(RegInit(0.U(32.W)))
  val reqOpcode = withReset(f.nonStickyReset)(RegInit(0.U(5.W)))
  val reqBe = withReset(f.nonStickyReset)(RegInit(0.U(8.W)))
  val reqAddr = withReset(f.nonStickyReset)(RegInit(0.U(24.W)))
  val reqWdata = withReset(f.nonStickyReset)(RegInit(0.U(64.W)))
  when(trigger.fire) {
    reqOpcode := opcode.reg
    reqBe := be.reg
    reqAddr := Cat(addrHi.reg, addrLo.reg)
    reqWdata := Cat(dataHiReg, dataLoReg)
  }

  // ===========================================================================
  // Data and status
  // ===========================================================================
  val readCapture = respFire &&
    (sb.resp.bits.status === StatusSuccess) && isRead(reqOpcode)
  def dataField(
      reg: UInt,
      hwHalf: UInt,
      name: String,
      description: String
  ): RegField = {
    when(readCapture) { reg := hwHalf }
    val writeFn = RegWriteFn { (valid, data) =>
      when(valid && !readCapture) { reg := data }
      true.B
    }
    val d = RegFieldDesc(
      name,
      description,
      access = RegFieldAccessType.RW,
      reset = Some(0),
      volatile = true
    )
    RegField(32, reg, writeFn, Some(d))
  }
  val dataLo = dataField(
    dataLoReg,
    sb.resp.bits.rdata(31, 0),
    "mailbox_data_lo",
    "Sideband Mailbox Data Low write data (SW) / read data (HW on read completion)"
  )
  val dataHi = dataField(
    dataHiReg,
    sb.resp.bits.rdata(63, 32),
    "mailbox_data_hi",
    "Sideband Mailbox Data High write data (SW) / read data (HW on read completion)"
  )

  val statusReg = withReset(f.nonStickyReset)(RegInit(0.U(2.W)))
  val statusValid = withReset(f.nonStickyReset)(RegInit(false.B))
  val statusWrite = RegWriteFn { (valid, data) =>
    when(valid && !respFire) { statusReg := statusReg & ~data(1, 0) }
    true.B
  }
  val statusDesc = RegFieldDesc(
    "mailbox_status",
    "Sideband Mailbox Status: Write/Read status (CA/UR/Success)",
    access = RegFieldAccessType.RW,
    wrType = Some(RegFieldWrType.ONE_TO_CLEAR),
    reset = Some(0),
    volatile = true
  )
  val statusReadData = Mux(statusValid, statusReg, 0.U)
  val statusField = RegField(
    2,
    statusReadData,
    statusWrite,
    Some(statusDesc)
  )

  when(trigger.fire) { statusValid := false.B }
  when(respFire) {
    statusReg := sb.resp.bits.status
    statusValid := true.B
  }

  val headerLog1: Valid[UInt] = {
    val w = Wire(Valid(UInt(64.W)))
    w.valid := respFire && sb.resp.bits.headerValid &&
      ((sb.resp.bits.status === StatusCA) || (sb.resp.bits.status === StatusUR))
    w.bits := sb.resp.bits.header
    w
  }

  // Table 9-32 rearms Header Log 1 on each trigger, so no old header shows.
  val headerLog1Rearm: Bool = trigger.fire

  // ===========================================================================
  // State machine
  // ===========================================================================
  sb.req.valid := (state === sReq)
  sb.req.bits.opcode := reqOpcode
  sb.req.bits.be := reqBe
  sb.req.bits.addr := reqAddr
  sb.req.bits.wdata := reqWdata
  sb.resp.ready := (state === sResp)

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
      at(0x10) -> Seq(
        trigger.field,
        f.RsvdP(7, "mailbox_ctrl_rsvd"),
        statusField,
        f.RsvdZ(6, "mailbox_status_rsvd"),
        f.RsvdP(16, "mailbox_ctrl_status_rsvd_31_16")
      )
    )
  }
}
