/*
  The IO of the Protocol Layer.
 */

package edu.berkeley.cs.uciedigital.protocollayer

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common.MainbandClkRstIO

class ProtocolRawBeat(nBytes: Int) extends Bundle {
  val data = UInt((8 * nBytes).W)
}

class ProtocolLayerIO(val fdiParams: FdiParams) extends Bundle {
  val clkRst = new MainbandClkRstIO
  val fdi = new Fdi(fdiParams)
  val ctrl = new ProtocolLayerCtrlIO()
  val status = new ProtocolLayerStatusIO()
  val mainbandTx = Flipped(Decoupled(new ProtocolRawBeat(fdiParams.nBytes)))
  val mainbandRx = Valid(new ProtocolRawBeat(fdiParams.nBytes))
}
