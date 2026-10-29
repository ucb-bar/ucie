/*
  Training patterns (sec 4.1.2, sec 4.5.3), shared by the writer and reader.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._

object PatternDefs {

  // sec 4.5.3.3.3, 16 clock cycles then 8 low, LSB first.
  val clkRepairWidth = 48
  val clkRepair = BigInt("000055555555", 16)

  // sec 4.1.2 framing, 4 UI high then 4 low.
  val valTrainWidth = 8
  val valTrain = BigInt("00001111", 2)

  // Table 4-7, `0101`, the 8-bit Lane ID, then `0101`.
  val perLaneIdWidth = 16

  // Note: `1010` is `0101` sent LSB first, so do not swap it.
  def perLaneId(lane: Int): BigInt =
    (BigInt("1010", 2) << 12) | (BigInt(lane & 0xff) << 4) | BigInt("1010", 2)

  // sec 4.5.3.2 step 1, 64 UI of alternating bits.
  val sbInitClock = BigInt("5555555555555555", 16)

  // One word per alignment. Both engines must walk this same sequence.
  def repeatedPatternWords(
      pattern: BigInt,
      patternWidth: Int,
      serRatio: Int
  ): Seq[UInt] = {
    val numPhases =
      patternWidth / BigInt(patternWidth).gcd(BigInt(serRatio)).toInt
    Seq.tabulate(numPhases) { phase =>
      VecInit(Seq.tabulate(serRatio) { bit =>
        ((pattern >> (phase * serRatio + bit) % patternWidth) & 1).U(1.W)
      }).asUInt
    }
  }
}

object PatternSelect extends ChiselEnum {
  val CLKREPAIR, VALTRAIN, PERLANEID, LFSR = Value
}
