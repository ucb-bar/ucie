/*
  The IO of the sideband mailbox controller. D2DSidebandModule decodes the link
  state terms once and passes them down, so both halves of the Adapter sideband
  agree on when the link is up.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.regs._
import edu.berkeley.cs.uciedigital.sideband._

class SidebandMailboxControllerIO(val sbParams: SidebandParams) extends Bundle {
  val mailbox = Flipped(new MailboxToSideband)
  val regTarget = Flipped(new SidebandRegTargetIO)
  val remoteRegAccessThreshold = Input(UInt(4.W))
  val linkReset = Input(Bool())
  val parityErrEvent = Input(Bool())

  val rdiActive = Input(Bool())
  val clearEngine = Input(Bool())
  val regCompleterFlush = Input(Bool())
  val newRdiEpoch = Input(Bool())
  val regTrafficEligible = Input(Bool())

  val rxValid = Input(Bool())
  val rxBits = Input(UInt(sbParams.sbNodeMsgWidth.W))
  val rxReady = Output(Bool())

  val completion = Valid(UInt(sbParams.sbNodeMsgWidth.W))
  val request = Valid(UInt(sbParams.sbNodeMsgWidth.W))
  val completionFire = Input(Bool())
  val requestFire = Input(Bool())
  val txCreditAvailable = Input(Bool())
  val txPacketCommit = Flipped(Valid(UInt(sbParams.sbNodeMsgWidth.W)))

  val remoteRegAccessTimeout = Output(Bool())
  val linkErrorRequest = Output(Bool())
}
