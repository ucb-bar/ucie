/*
  The Protocol Layer's control and status for its side of FDI. Software asks
  for an FDI state through ctrl, and status reports the FDI state and the
  negotiated protocol.
 */

package edu.berkeley.cs.uciedigital.fdi

import chisel3._
import edu.berkeley.cs.uciedigital.common.{LinkWidth, SpeedMode}

class ProtocolLayerCtrlIO extends Bundle {
  val requestActive = Input(Bool())
  val requestRetrain = Input(Bool())
  val requestLinkReset = Input(Bool())
  val requestDisable = Input(Bool())
  val requestLinkError = Input(Bool())

  val corrProtoReport = Input(Bool())
  val nonFatalProtoReport = Input(Bool())
  val fatalProtoReport = Input(Bool())
}

class ProtocolLayerStatusIO extends Bundle {
  val linkState = Output(FDIState())
  val negotiatedProtocolValid = Output(Bool())
  val negotiatedProtocol = Output(FDIProtocol())
  val negotiatedFlitFormat = Output(FDIFlitFormat())
  val stalled = Output(Bool())

  val phyInRecenter = Output(Bool())
  val phyInL1 = Output(Bool())
  val phyInL2 = Output(Bool())
  val speedmode = Output(SpeedMode())
  val maxSpeedmode = Output(Bool())
  val lnkCfg = Output(LinkWidth())

  val errCorrectable = Output(Bool())
  val errUncorrectable = Output(Bool())

  val fdiNonFatalError = Output(Bool())
  val fdiFatalError = Output(Bool())
}
