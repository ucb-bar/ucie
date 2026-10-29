/*
  The states of the remote register access requester in
  SidebandMailboxController.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._

object MailboxRequesterState extends ChiselEnum {
  val idle, offer, awaitingCommit, outstanding, response = Value
}
