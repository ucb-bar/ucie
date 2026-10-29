/*
  The IO between the D2D Adapter and the register block. It carries the status
  that the block logs, and the controls and errors that the block sends back.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util.Valid
import edu.berkeley.cs.uciedigital.fdi.FDIState
import edu.berkeley.cs.uciedigital.rdi.RDIState
import edu.berkeley.cs.uciedigital.regs.{MailboxToSideband, SidebandRegTargetIO}

class AdapterSidebandStatusIO extends Bundle {
  val parityErrEvent = Output(Bool())
  val fdiRxQueuesFull = Output(Bool())
  val rdiRxQueuesFull = Output(Bool())
  val invalidRoute = Output(Bool())
  val errMsgFatal = Output(Bool())
  val errMsgNonFatal = Output(Bool())
  val errMsgCorrectable = Output(Bool())
  val advCapAdapter = Valid(UInt(64.W))
}

class AdapterRegIO extends Bundle {
  val linkState = Output(FDIState())
  val rdiState = Output(RDIState())
  val paramExchSuccess = Output(Bool())
  val sideband = new AdapterSidebandStatusIO()
  val mailboxSideband = Flipped(new MailboxToSideband)
  val sidebandRegTarget = Flipped(new SidebandRegTargetIO)
  val remoteRegAccessThreshold = Input(UInt(4.W))
  val linkReset = Input(Bool())
  val swRetrain = Input(Bool())
  val rawFormatEnable = Input(Bool())
  val invalidParamExchange = Output(Bool())
  val remoteRegAccessTimeout = Output(Bool())
  val adapterTimeout = Output(Valid(new AdapterTimeoutFields))
  val phyFatalError = Output(Bool())
  val phyCorrError = Output(Bool())
  val phyNonFatalError = Output(Bool())
  val internalCorrError = Input(Bool())
  val internalNonFatalError = Input(Bool())
  val internalFatalError = Input(Bool())
  val linkDownError = Input(Bool())
}
