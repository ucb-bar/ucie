/*
  The sideband packet parity (sec 7.1.2), shared by both nodes so they cannot
  drift. A parity error is an Uncorrectable Internal Error (sec 7.1.3.1,
  sec 7.1.3.2).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._

object SidebandParity {
  // Priority packets and Management Port messages with data use a different
  // header layout (sec 7.1.1, sec 7.1.2.4).
  def usesDp(opcode: UInt): Bool =
    !(SBMsgOpcode.OpsThatDontUseDPField.map(_.asUInt === opcode).reduce(_ || _))

  // CP at [62] is even parity over header [61:0], so it excludes CP and DP.
  def cp(packet: UInt): Bool = packet(61, 0).xorR

  // DP at [63] is even parity over payload [127:64].
  def dp(packet: UInt, applies: Bool): Bool =
    Mux(applies, packet(127, 64), 0.U).xorR

  def set(packet: UInt): UInt =
    Cat(
      packet(127, 64),
      dp(packet, usesDp(packet(4, 0))),
      cp(packet),
      packet(61, 0)
    )

  case class Errors(cp: Bool, dp: Bool) {
    def any: Bool = cp || dp
  }

  // A packet with no data payload sets DP to 0b, so a set bit is the error.
  def check(packet: UInt): Errors = {
    val opcode = packet(4, 0)

    val cpError = packet(62) ^ cp(packet)

    val standardNoData = Seq(
      SBMsgOpcode.MemoryRead_32b,
      SBMsgOpcode.DMSRegisterRead_32b,
      SBMsgOpcode.ConfigurationRead_32b,
      SBMsgOpcode.MemoryRead_64b,
      SBMsgOpcode.DMSRegisterRead_64b,
      SBMsgOpcode.ConfigurationRead_64b,
      SBMsgOpcode.CompletionWithoutData,
      SBMsgOpcode.MessageWithoutData
    ).map(_.asUInt === opcode).reduce(_ || _)

    val doDp = usesDp(opcode)
    val expectedDp = packet(63)
    val dpError =
      Mux(standardNoData, expectedDp, doDp && (expectedDp ^ dp(packet, doDp)))

    Errors(cpError, dpError)
  }
}
