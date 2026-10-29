/*
  Sideband message helpers for the Adapter LSM (sec 7.1).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._

object AdapterLinkMsg {
  // sec 7.1 says a Stall response only resets the 8 ms timeout. SBMsgCompare
  // ignores MsgInfo, so check the Stall here (FFFFh in Table 7-8).
  val stallMsgInfo: UInt = "hFFFF".U(16.W)

  def create(base: MixedVec[UInt], data: UInt = 0.U(64.W)): UInt =
    SBMsgCreate(base, "D2D", "D2D", remote = true, data = data)

  def msgInfo(packet: UInt): UInt = packet(55, 40)

  def isRegular(packet: UInt, base: MixedVec[UInt]): Bool =
    SBMsgCompare(packet, base) && msgInfo(packet) =/= stallMsgInfo

  def isStall(packet: UInt, base: MixedVec[UInt]): Bool =
    SBMsgCompare(packet, base) && msgInfo(packet) === stallMsgInfo
}
