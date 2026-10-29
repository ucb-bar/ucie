/*
  Tables 4-10 and 4-12, the PHYRETRAIN exit state (sec 4.5.3.7).
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._

object RetrainResolution {

  // Table 4-10. A Standard Package repair is a width degrade (sec 4.5.3.4.13).
  def localEncoding(
      busyBit: Bool,
      repairNeeded: Bool,
      repairResourcesAvailable: Bool
  ): UInt =
    Mux(
      busyBit && repairNeeded,
      Mux(
        repairResourcesAvailable,
        RetrainEncoding.REPAIR,
        RetrainEncoding.SPEEDIDLE
      ),
      RetrainEncoding.TXSELFCAL
    )

  // Table 4-12. A pair with no row keeps the local encoding.
  def rows: Seq[((UInt, UInt), UInt)] = Seq(
    (RetrainEncoding.TXSELFCAL, RetrainEncoding.TXSELFCAL) ->
      RetrainEncoding.TXSELFCAL,
    (RetrainEncoding.TXSELFCAL, RetrainEncoding.REPAIR) ->
      RetrainEncoding.REPAIR,
    (RetrainEncoding.TXSELFCAL, RetrainEncoding.SPEEDIDLE) ->
      RetrainEncoding.SPEEDIDLE,
    (RetrainEncoding.REPAIR, RetrainEncoding.TXSELFCAL) ->
      RetrainEncoding.REPAIR,
    (RetrainEncoding.REPAIR, RetrainEncoding.REPAIR) ->
      RetrainEncoding.REPAIR,
    (RetrainEncoding.REPAIR, RetrainEncoding.SPEEDIDLE) ->
      RetrainEncoding.SPEEDIDLE,
    (RetrainEncoding.SPEEDIDLE, RetrainEncoding.TXSELFCAL) ->
      RetrainEncoding.SPEEDIDLE,
    (RetrainEncoding.SPEEDIDLE, RetrainEncoding.REPAIR) ->
      RetrainEncoding.SPEEDIDLE,
    (RetrainEncoding.SPEEDIDLE, RetrainEncoding.SPEEDIDLE) ->
      RetrainEncoding.SPEEDIDLE
  )

  def resolve(local: UInt, remote: UInt): UInt = {
    val out = WireDefault(local)
    for (((loc, rem), res) <- rows) {
      when(local === loc && remote === rem) { out := res }
    }
    out
  }
}

// Table 4-11 retrain encodings.
object RetrainEncoding {
  val TXSELFCAL = "b001".U(3.W)
  val SPEEDIDLE = "b010".U(3.W)
  val REPAIR = "b100".U(3.W)
}
