/*
  Forwarded clock and Track Lane patterns (sec 5.5.1, Table 5-12).
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

object ForwardedClock {
  def periodUI(quarterRate: Boolean): Int = if (quarterRate) 4 else 2

  def phase1(i: Int, quarterRate: Boolean): Boolean = {
    val p = periodUI(quarterRate)
    (i % p) < (p / 2)
  }

  // Phase-2 lags Phase-1 by one UI, which is the complement at half rate.
  def phase2(i: Int, quarterRate: Boolean): Boolean = {
    val p = periodUI(quarterRate)
    phase1((i + p - 1) % p, quarterRate)
  }

  def word(
      serRatio: Int,
      quarterRate: Boolean,
      ui: (Int, Boolean) => Boolean
  ): UInt =
    VecInit(Seq.tabulate(serRatio)(i => ui(i, quarterRate).B)).asUInt

  // No phase carries between words, because both periods divide 8 UI.
  def checked(serRatio: Int): Unit =
    require(
      serRatio % 8 == 0,
      s"serRatio must be a whole number of 8 UI frames, got $serRatio"
    )

  def clkPWord(serRatio: Int, quarterRate: Boolean): UInt = {
    checked(serRatio); word(serRatio, quarterRate, phase1)
  }

  def clkNWord(serRatio: Int, quarterRate: Boolean): UInt = {
    checked(serRatio); word(serRatio, quarterRate, phase2)
  }

  // sec 5.5.1 aligns the Track pattern with Phase-1, so it is the CLKP word.
  def trackWord(serRatio: Int, quarterRate: Boolean): UInt =
    clkPWord(serRatio, quarterRate)

  // One 8 UI tile, LSB first, for the pattern engine.
  def tile(
      quarterRate: Boolean,
      ui: (Int, Boolean) => Boolean
  ): BigInt =
    (0 until 8).foldLeft(BigInt(0)) { (acc, i) =>
      if (ui(i, quarterRate)) acc.setBit(i) else acc
    }

  val TileUI = 8
  def clkPTile(quarterRate: Boolean): BigInt = tile(quarterRate, phase1)
  def clkNTile(quarterRate: Boolean): BigInt = tile(quarterRate, phase2)

  def clkP(serRatio: Int, quarterRate: Bool): UInt =
    Mux(quarterRate, clkPWord(serRatio, true), clkPWord(serRatio, false))

  def clkN(serRatio: Int, quarterRate: Bool): UInt =
    Mux(quarterRate, clkNWord(serRatio, true), clkNWord(serRatio, false))

  def track(serRatio: Int, quarterRate: Bool): UInt =
    Mux(quarterRate, trackWord(serRatio, true), trackWord(serRatio, false))

  // Differential low at half rate and simultaneous low at quarter rate.
  def parkPair(high: Bool, quarterRate: Bool): (Bool, Bool) =
    (high, Mux(quarterRate, high, !high))

  def parkWord(serRatio: Int, level: Bool): UInt = Fill(serRatio, level)
}
