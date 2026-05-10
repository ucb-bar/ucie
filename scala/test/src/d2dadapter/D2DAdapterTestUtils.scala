package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import edu.berkeley.cs.uciedigital.interfaces._

object D2DAdapterTestUtils {
  val OpcodeMsgNoData: Int = 0x12
  val OpcodeMsgWith64B: Int = 0x1b

  val MsgCodeAdvCapAdapter: Int = 0x01
  val MsgCodeAdapter0ReqActive: Int = 0x03
  val MsgCodeAdapter0RspActive: Int = 0x04

  val SubcodeAdvCap: Int = 0x00
  val SubcodeActive: Int = 0x01

  val AdvCapRawStreamingStack0: BigInt = BigInt("91", 16)

  def parity(value: BigInt, width: Int): Int = {
    var p = 0
    var i = 0
    while (i < width) {
      p ^= ((value >> i) & 1).toInt
      i += 1
    }
    p & 1
  }

  def sbMsg(
      opcode: Int,
      msgCode: Int,
      msgSubcode: Int,
      msgInfo: Int = 0,
      data: BigInt = 0
  ): BigInt = {
    var header = BigInt(0)
    header |= BigInt(opcode & 0x1f)
    header |= BigInt(msgCode & 0xff) << 14
    header |= BigInt(0x14) << 22 // remote + D2D layer + reserved
    header |= BigInt(0x1) << 29 // D2D source
    header |= BigInt(msgSubcode & 0xff) << 32
    header |= BigInt(msgInfo & 0xffff) << 40
    header |= BigInt(0x5) << 56 // remote D2D destination
    header |= BigInt(parity(header, 62)) << 62
    if (opcode == OpcodeMsgWith64B) {
      header |= BigInt(parity(data, 64)) << 63
    }
    (data << 64) | header
  }

  def sbAdvcapAdapter(data: BigInt = AdvCapRawStreamingStack0, msgInfo: Int = 0): BigInt =
    sbMsg(OpcodeMsgWith64B, MsgCodeAdvCapAdapter, SubcodeAdvCap, msgInfo, data)

  def sbAdapter0ReqActive(): BigInt =
    sbMsg(OpcodeMsgNoData, MsgCodeAdapter0ReqActive, SubcodeActive)

  def sbAdapter0RspActive(): BigInt =
    sbMsg(OpcodeMsgNoData, MsgCodeAdapter0RspActive, SubcodeActive)

  def msgMatches(msg: BigInt, opcode: Int, msgCode: Int, msgSubcode: Int): Boolean = {
    val op = (msg & BigInt(0x1f)).toInt
    val code = ((msg >> 14) & BigInt(0xff)).toInt
    val sub = ((msg >> 32) & BigInt(0xff)).toInt
    op == opcode && code == msgCode && sub == msgSubcode
  }

  def initAdapterInputs(dut: D2DAdapter): Unit = {
    dut.io.fdi.lpIrdy.poke(false.B)
    dut.io.fdi.lpValid.poke(false.B)
    dut.io.fdi.lpData.poke(0.U)
    dut.io.fdi.lpStateReq.poke(FDIStateReq.nop)
    dut.io.fdi.lpLinkError.poke(false.B)
    dut.io.fdi.lpRxActiveSts.poke(false.B)
    dut.io.fdi.lpStallAck.poke(false.B)
    dut.io.fdi.lpClkAck.poke(false.B)
    dut.io.fdi.lpWakeReq.poke(false.B)
    dut.io.fdi.lpCfg.poke(0.U)
    dut.io.fdi.lpCfgVld.poke(false.B)
    dut.io.fdi.plCfgCrd.poke(true.B)

    dut.io.rdi.plTrdy.poke(true.B)
    dut.io.rdi.plValid.poke(false.B)
    dut.io.rdi.plData.poke(0.U)
    dut.io.rdi.plStateSts.poke(RDIState.reset)
    dut.io.rdi.plInbandPres.poke(false.B)
    dut.io.rdi.plError.poke(false.B)
    dut.io.rdi.plCError.poke(false.B)
    dut.io.rdi.plNfError.poke(false.B)
    dut.io.rdi.plTrainError.poke(false.B)
    dut.io.rdi.plPhyInRecenter.poke(false.B)
    dut.io.rdi.plStallReq.poke(false.B)
    dut.io.rdi.plSpeedmode.poke(SpeedMode.speed16)
    dut.io.rdi.plMaxSpeedmode.poke(false.B)
    dut.io.rdi.plLnkCfg.poke(LinkWidth.x16)
    dut.io.rdi.plClkReq.poke(false.B)
    dut.io.rdi.plWakeAck.poke(false.B)
    dut.io.rdi.plCfg.poke(0.U)
    dut.io.rdi.plCfgVld.poke(false.B)
    dut.io.rdi.plCfgCrd.poke(true.B)
  }

  def sendRdiSidebandMsg(dut: D2DAdapter, msg: BigInt, sidebandWidth: Int = 32): Unit = {
    val beats = 128 / sidebandWidth
    val mask = (BigInt(1) << sidebandWidth) - 1
    for (beat <- 0 until beats) {
      val lane = (msg >> (beat * sidebandWidth)) & mask
      dut.io.rdi.plCfgVld.poke(true.B)
      dut.io.rdi.plCfg.poke(lane.U)
      dut.clock.step()
    }
    dut.io.rdi.plCfgVld.poke(false.B)
    dut.io.rdi.plCfg.poke(0.U)
  }

  def recvRdiSidebandMsg(dut: D2DAdapter, maxCycles: Int = 200, sidebandWidth: Int = 32): BigInt = {
    val beats = 128 / sidebandWidth
    var cycle = 0
    while (!dut.io.rdi.lpCfgVld.peekBoolean() && cycle < maxCycles) {
      dut.clock.step()
      cycle += 1
    }
    require(dut.io.rdi.lpCfgVld.peekBoolean(), s"Timed out waiting for sideband output after $maxCycles cycles")

    var msg = BigInt(0)
    for (beat <- 0 until beats) {
      val lane = dut.io.rdi.lpCfg.peek().litValue
      msg |= lane << (beat * sidebandWidth)
      dut.clock.step()
    }
    msg
  }
}

