package edu.berkeley.cs.uciedigital.d2dadapter

object D2DAdapterTestUtils {
  val OpcodeMsgNoData: Int = 0x12
  val OpcodeMsgWith64B: Int = 0x1b

  val MsgCodeAdvCapAdapter: Int = 0x01
  val MsgCodeFinCapAdapter: Int = 0x02
  val MsgCodeAdapter0ReqActive: Int = 0x03
  val MsgCodeAdapter0RspActive: Int = 0x04
  val MsgCodeAdapter1ReqActive: Int = 0x05
  val MsgCodeAdapter1RspActive: Int = 0x06

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

  def msgMatches(msg: BigInt, opcode: Int, expectedCode: Int, expectedSubcode: Int): Boolean = {
    val op = msgOpcode(msg)
    val code = msgCode(msg)
    val sub = msgSubcode(msg)
    op == opcode && code == expectedCode && sub == expectedSubcode
  }

  def msgOpcode(msg: BigInt): Int = (msg & BigInt(0x1f)).toInt

  def msgCode(msg: BigInt): Int = ((msg >> 14) & BigInt(0xff)).toInt

  def msgSubcode(msg: BigInt): Int = ((msg >> 32) & BigInt(0xff)).toInt

}
 
