package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

object PatternLaneMap {
  def decodeLaneMap(code: UInt, nLanes: Int): UInt = {
    require(nLanes <= 16, "PatternLaneMap only supports up to 16 lanes")

    val fullMask = MuxLookup(code, 0.U(16.W))(
      Seq(
        "b000".U -> "h0000".U(16.W),
        "b001".U -> "h00FF".U(16.W),
        "b010".U -> "hFF00".U(16.W),
        "b011".U -> "hFFFF".U(16.W),
        "b100".U -> "h000F".U(16.W),
        "b101".U -> "h00F0".U(16.W)
      )
    )

    fullMask(nLanes - 1, 0)
  }
}

/*
  What a Module sets its Transmitter and Receiver to once it has sent its own
  functional-Lane code in {apply degrade req} and received its partner's
  (spec 4.5.3.3.6 Step 3 for MBINIT.REPAIRMB, 4.5.3.4.13 Step 2 for
  MBTRAIN.REPAIR). `own` is what this Module's Transmitter point test found,
  `partner` what the partner's found; both are Table 4-9 codes.

  The spec's two rules:
 * The partner indicated a width degrade: apply it to the Receiver.
 * The partner indicated all Lanes functional: set the Transmitter and the
     Receiver to the Lane map determined on this Module's Transmitter.

  and the counterpart the second rule leaves to a later point test: a Module
  whose Transmitter found every Lane good, facing a partner that degraded,
  moves its Transmitter onto the partner's Lane map -- which is where the
  partner's Receiver now listens. In MBINIT.REPAIRMB the spec gets there one
  pass later, through the repeated point test failing on the Lanes that
  Receiver disabled. MBTRAIN.REPAIR has no point test of its own, so there it
  would take another trip through MBTRAIN.LINKSPEED. Taking the partner's map
  directly reaches the same end state in the same exchange. Every pairing
  then has both directions of a Module at one width, and the two die agree
  on it: whichever of them degraded, the Lane map each Transmitter uses is the
  one its partner's Receiver was set to.

  "Degrade not possible" (000b) is never adopted; it takes the Module to
  TRAINERROR regardless.
 */
object DegradeLaneMaps {
  private def allLanes(code: UInt): Bool = code === "b011".U
  private def degraded(code: UInt): Bool = !allLanes(code) && (code =/= 0.U)

  /** The Lane map this Module's Transmitter uses. */
  def tx(own: UInt, partner: UInt): UInt =
    Mux(allLanes(own) && degraded(partner), partner, own)

  /** The Lane map this Module's Receiver uses. */
  def rx(own: UInt, partner: UInt): UInt =
    Mux(allLanes(partner) && degraded(own), own, partner)
}
