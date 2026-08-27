/*
  Description:
    Register-facing port of the D2D adapter. Carries the status the UCIe register block logs
    and the notification enables that gate FDI error reporting.
 */
package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util.Valid
import edu.berkeley.cs.uciedigital.interfaces._

class AdapterSidebandStatusIO extends Bundle {
  val parityErr = Output(Bool())
  val rxQueuesFull = Output(Bool())
  val invalidRoute = Output(Bool())
  val errMsgFatal = Output(Bool())
  val errMsgNonFatal = Output(Bool())
  val errMsgCorrectable = Output(Bool())
  val advCapAdapter = Valid(UInt(64.W))
}

class AdapterRegIO extends Bundle {
  val linkState = Output(RDIState())
  val paramExchSuccess = Output(Bool())
  val sideband = new AdapterSidebandStatusIO()
  val corrProtoReport = Input(Bool())
  val nonFatalProtoReport = Input(Bool())
  val fatalProtoReport = Input(Bool())
}
