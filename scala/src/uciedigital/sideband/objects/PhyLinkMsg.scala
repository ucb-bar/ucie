/*
  Sideband messages from the Logical PHY to the partner's Logical PHY
  (sec 7.1).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._

object PhyLinkMsg {
  def create(
      base: MixedVec[UInt],
      msgInfo: UInt = 0.U(16.W),
      data: UInt = 0.U(64.W)
  ): UInt =
    SBMsgCreate(
      base,
      "PHY",
      "PHY",
      remote = true,
      msgInfo = msgInfo,
      data = data
    )
}
