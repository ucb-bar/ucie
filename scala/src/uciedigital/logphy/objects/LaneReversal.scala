/*
  Transmitter Data Lane reversal (sec 4.2). The Receiver has none.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._

object LaneReversal {

  // Spans the module width, because MBINIT.REVERSALMB runs before any degrade.
  def tx(bits: MainbandLanes, enabled: Bool): MainbandLanes = {
    val nLanes = bits.data.length
    val out = Wire(chiselTypeOf(bits))
    out := bits
    for (lane <- 0 until nLanes) {
      out.data(lane) :=
        Mux(enabled, bits.data(nLanes - 1 - lane), bits.data(lane))
    }
    out
  }
}
