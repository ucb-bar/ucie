/*
  The Table 7-11 parameters of a link operation start req.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

class LinkOperationParameters extends Bundle {
  val clockPhase = Input(UInt(4.W))

  val dataPattern = Input(UInt(3.W))

  val validPattern = Input(UInt(3.W))

  val patternMode = Input(UInt(1.W))

  val iterationCount = Input(UInt(16.W))
  val idleCount = Input(UInt(16.W))
  val burstCount = Input(UInt(16.W))

  val maxErrorThreshold = Input(UInt(16.W))

  val comparisonMode = Input(UInt(1.W))
}

object LinkOperationParameters {

  def startReqData(p: LinkOperationParameters): UInt = {
    val data = Cat(
      0.U(4.W), // [63:60] Reserved
      p.comparisonMode, // [59] 0 per Lane, 1 aggregate
      p.iterationCount, // [58:43]
      p.idleCount, // [42:27]
      p.burstCount, // [26:11]
      p.patternMode, // [10] 0 continuous, 1 burst
      p.clockPhase, // [9:6] 0h center, 1h left edge, 2h right edge
      p.validPattern, // [5:3] 0h functional pattern (VALTRAIN)
      p.dataPattern // [2:0] 0h LFSR, 1h Per Lane ID
    )

    // A short Cat would zero extend and shift every field above the gap.
    require(
      data.getWidth == 64,
      s"Table 7-11 data field is 64 bits, built ${data.getWidth}"
    )
    data
  }

  // Continuous mode Burst Count in UI (Table 4-5 notes).
  def burstCountFor(patternType: PatternSelect.Type): UInt =
    MuxLookup(patternType.asUInt, 4096.U(16.W))(
      Seq(
        PatternSelect.VALTRAIN.asUInt -> 1024.U(16.W),
        PatternSelect.PERLANEID.asUInt -> 2048.U(16.W),
        PatternSelect.LFSR.asUInt -> 4096.U(16.W)
      )
    )

  // Table 7-11 has no code for no data pattern, so VALTRAIN sends 0h by choice.
  def dataPatternFor(patternType: PatternSelect.Type): UInt =
    Mux(patternType === PatternSelect.PERLANEID, 1.U(3.W), 0.U(3.W))
}

object ComparisonMode extends ChiselEnum {
  val PERLANE, AGGREGATE = Value
}
