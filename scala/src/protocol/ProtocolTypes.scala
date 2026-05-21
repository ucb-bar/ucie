/*
  Description:
    Shared protocol-layer types, parameters, and IO bundles.
*/

package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.interfaces._

case class ProtocolLayerParams(
  txQueueDepth: Int = 2,
  rxQueueDepth: Int = 2,
)

class ProtocolRawBeat(nBytes: Int) extends Bundle {
  val data = UInt((8 * nBytes).W)
}

class ProtocolLayerCtrlIO extends Bundle {
  val requestRetrain = Input(Bool())
  val requestLinkReset = Input(Bool())
  val requestDisable = Input(Bool())
}

class ProtocolLayerStatusIO extends Bundle {
  val linkState = Output(FDIState())
  val negotiatedProtocolValid = Output(Bool())
  val negotiatedProtocol = Output(FDIProtocol())
  val negotiatedFlitFormat = Output(FDIFlitFormat())
  val stalled = Output(Bool())
  val rxOverflow = Output(Bool())
}

class ProtocolLayerIO(
  val params: ProtocolLayerParams,
  val fdiParams: FdiParams,
) extends Bundle {
  val fdi = new Fdi(fdiParams)
  val ctrl = new ProtocolLayerCtrlIO()
  val status = new ProtocolLayerStatusIO()
  val mainbandTx = Flipped(Decoupled(new ProtocolRawBeat(fdiParams.nBytes)))
  val mainbandRx = Decoupled(new ProtocolRawBeat(fdiParams.nBytes))
}
