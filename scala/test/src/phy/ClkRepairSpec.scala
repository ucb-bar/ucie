package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

/** Scores [[ClkRepair]] against synthetic oversampled streams.
  *
  * This is where the measurement's claims are actually proved. The AMS runs put
  * it in front of real analog models, but a wrapback bench feeds a die its own
  * transmitter, so the sampling clock and the sampled waveform come from one
  * PLL and never drift apart. Tolerance to a far side on its own clock has to
  * be shown here or not at all.
  */
object ClkRepairStimulus {

  /** The 48 bit clock repair pattern, bit `i` being UI `i` on the wire: 32 UI
    * alternating, then 16 UI low.
    */
  val pattern: BigInt = BigInt("000055555555", 16)
  val patternUi: Int = ClkRepair.PatternUi

  def patternBit(ui: Int): Boolean = ((pattern >> (ui % patternUi)) & 1) == 1

  /** `count` samples of the repair pattern at `n` samples per UI, starting
    * `phase` samples into it.
    *
    * `driftPeriod` inserts one extra sample every that many, which is a
    * frequency offset between the far side's transmitter and the local sampling
    * clock -- the thing a wrapback bench cannot produce.
    */
  def repair(
      n: Int,
      count: Int,
      phase: Int = 0,
      driftPeriod: Int = 0
  ): Seq[Boolean] =
    Seq.tabulate(count) { s =>
      val drift = if (driftPeriod > 0) s / driftPeriod else 0
      patternBit((s + phase + drift) / n)
    }

  /** A plain clock of the same UI rate, with no gap in it: what a lane shorted
    * to a neighbouring forwarded clock looks like.
    */
  def bareClock(n: Int, count: Int, phase: Int = 0): Seq[Boolean] =
    Seq.tabulate(count)(s => (((s + phase) / n) % 2) == 0)

  /** One UI high, two UI low, forever.
    *
    * Constructed to defeat three of the four counters at once: it has the same
    * edge rate as the repair pattern (two edges every three UI, against the
    * pattern's 32 every 48) and the same duty cycle (a third), so `transitions`
    * and `ones` both land exactly where a good lane's would. Only the gap tells
    * it apart. This is the case that justifies counting runs at all, and a
    * plain gapless clock is not it -- that one fails on edge rate and duty
    * cycle before the gap is ever consulted.
    */
  def dutyMatchedGapless(n: Int, count: Int): Seq[Boolean] =
    Seq.tabulate(count)(s => ((s / n) % 3) == 0)

  def stuck(level: Boolean, count: Int): Seq[Boolean] =
    Seq.fill(count)(level)

  /** Packs samples into lane words, earliest sample in bit 0 -- wire order,
    * which is what the lane's shuffler delivers.
    */
  def words(samples: Seq[Boolean]): Seq[BigInt] =
    samples
      .grouped(Phy.SerdesRatio)
      .map { word =>
        word.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (bit, b)) =>
          if (bit) acc | (BigInt(1) << b) else acc
        }
      }
      .toSeq

  case class Counts(transitions: Int, ones: Int, gaps: Int, maxRun: Int)

  /** The same accumulation [[ClkRepair]] performs, in Scala.
    *
    * Written from the same description rather than from the RTL, including the
    * detail that the first word's leading boundary is compared against a
    * `prevLast` of zero. A model that quietly skipped that would hide a real
    * off-by-one.
    */
  def model(samples: Seq[Boolean], gapThresh: Int): Counts = {
    var transitions = 0
    var ones = 0
    var gaps = 0
    var maxRun = 0
    var zeroRun = 0
    var prevLast = false
    samples.grouped(Phy.SerdesRatio).foreach { word =>
      ones += word.count(identity)
      for (b <- 1 until word.length) {
        if (word(b) != word(b - 1)) transitions += 1
      }
      if (word.head != prevLast) transitions += 1
      prevLast = word.last
      if (!word.exists(identity)) {
        zeroRun += word.length
      } else {
        val closed = zeroRun + word.indexWhere(identity)
        if (closed >= gapThresh) gaps += 1
        if (closed > maxRun) maxRun = closed
        zeroRun = word.length - 1 - word.lastIndexWhere(identity)
      }
    }
    Counts(transitions, ones, gaps, maxRun)
  }

  def inBand(c: Counts, b: ClkRepairExpect.Bands): Boolean =
    c.transitions >= b.transMin && c.transitions <= b.transMax &&
      c.ones >= b.onesMin && c.ones <= b.onesMax &&
      c.gaps >= b.gapsMin && c.gaps <= b.gapsMax &&
      c.maxRun >= b.maxRunMin && c.maxRun <= b.maxRunMax
}

class ClkRepairSpec extends AnyFunSpec with ChiselSim {
  // The simulator comes from the `phy` package object. `ClkRepair` is plain
  // Chisel with no analog macros behind it, so it needs no lint relief.
  import ClkRepairStimulus._

  // Short enough to simulate quickly, still a whole number of pattern periods
  // at every ratio from 2 to 8.
  val windowWords: Int = ClkRepairExpect.alignedWindow(96)
  val windowSamples: Int = windowWords * Phy.SerdesRatio

  /** Runs one window with a stream per lane and returns what the hardware
    * counted.
    */
  def run(lanes: Seq[Seq[Boolean]], n: Int): Seq[Counts] = {
    require(lanes.length == ClkRepair.Lanes)
    val gapThresh = ClkRepair.GapThreshUi * n
    val laneWords = lanes.map(words)
    laneWords.foreach(w =>
      require(w.length >= windowWords, s"${w.length} words is short")
    )
    var out: Seq[Counts] = Seq.empty
    simulate(new ClkRepair) { c =>
      // A window of zero never starts, so nothing is counted while the reset
      // settles and the first real word is the first one scored.
      c.io.windowWords.poke(0.U)
      c.io.gapThresh.poke(gapThresh.U)
      c.io.capLane.poke(0.U)
      c.io.capOffset.poke(0.U)
      c.io.word.foreach(_.poke(0.U))
      c.clock.step(2)

      c.io.windowWords.poke(windowWords.U)
      for (w <- 0 until windowWords) {
        for (lane <- 0 until ClkRepair.Lanes) {
          c.io.word(lane).poke(laneWords(lane)(w).U)
        }
        c.clock.step()
      }
      assert(c.io.done.peek().litToBoolean, "window did not close")
      assert(c.io.wordsObserved.peek().litValue == windowWords)
      out = (0 until ClkRepair.Lanes).map { lane =>
        Counts(
          transitions = c.io.obs(lane).transitions.peek().litValue.toInt,
          ones = c.io.obs(lane).ones.peek().litValue.toInt,
          gaps = c.io.obs(lane).gaps.peek().litValue.toInt,
          maxRun = c.io.obs(lane).maxRun.peek().litValue.toInt
        )
      }
    }
    out
  }

  /** The same stream on all three lanes, which is what REPAIRCLK sends. */
  def runOne(stream: Seq[Boolean], n: Int): Counts =
    run(Seq.fill(ClkRepair.Lanes)(stream), n).head

  describe("ClkRepair counters") {
    for (n <- Seq(2, 4, 8)) {
      it(s"should match the reference model at every phase, oversampled x$n") {
        // Every sample offset within a UI, plus one well into the pattern, so
        // a window that opens mid burst is covered as well as one that opens
        // on a UI boundary.
        for (phase <- (0 until n) ++ Seq(7 * n + 3)) {
          val stream = repair(n, windowSamples, phase)
          val got = runOne(stream, n)
          val want = model(stream, ClkRepair.GapThreshUi * n)
          assert(
            got == want,
            s"x$n phase $phase: hardware $got, model $want"
          )
        }
      }

      it(s"should land inside the default bands at every phase, x$n") {
        val bands = ClkRepairExpect.bands(windowWords, n)
        for (phase <- 0 until n) {
          val got = runOne(repair(n, windowSamples, phase), n)
          assert(
            inBand(got, bands),
            s"x$n phase $phase: $got outside $bands"
          )
        }
      }
    }

    it("should scale with the oversample ratio exactly as predicted") {
      // Four counters, four different dependencies on the ratio -- transitions
      // and gaps fall as 1/N, maxRun rises with N, ones does not move at all.
      // That is what makes a declared ratio checkable without measuring a
      // frequency, so it is worth pinning rather than leaving to the bands.
      val measured = Seq(2, 4, 8).map { n =>
        n -> runOne(repair(n, windowSamples, phase = 0), n)
      }
      for ((n, got) <- measured) {
        val want = ClkRepairExpect(windowWords, n)
        assert(got.transitions == want.transitions, s"x$n transitions $got")
        assert(got.ones == want.ones, s"x$n ones $got")
        assert(got.maxRun == want.maxRun, s"x$n maxRun $got")
        // A window that is a whole number of periods and opens on a period
        // boundary ends inside the last gap, which therefore never closes and
        // is not counted. One short is correct, not an error.
        assert(
          got.gaps == want.gaps - 1,
          s"x$n gaps $got, expected ${want.gaps} less the one left open"
        )
      }
      val byN = measured.toMap
      assert(byN(2).transitions == 2 * byN(4).transitions)
      assert(byN(4).transitions == 2 * byN(8).transitions)
      assert(byN(2).ones == byN(4).ones && byN(4).ones == byN(8).ones)
      assert(byN(8).maxRun == 2 * byN(4).maxRun)
    }

    it("should tolerate a far side on its own clock") {
      // One sample inserted every 400 is about 2500 ppm, far wider than two
      // parts ever are, and the window still has to score clean. This is the
      // case the wrapback benches cannot produce.
      val n = 4
      val bands = ClkRepairExpect.bands(windowWords, n)
      for (driftPeriod <- Seq(400, 997)) {
        val got =
          runOne(repair(n, windowSamples, phase = 1, driftPeriod), n)
        assert(
          inBand(got, bands),
          s"drift 1 in $driftPeriod: $got outside $bands"
        )
      }
    }
  }

  describe("ClkRepair failure signatures") {
    val n = 4
    val bands = ClkRepairExpect.bands(windowWords, n)

    it("should read all zero on a lane that never leaves ground") {
      val got = runOne(stuck(false, windowSamples), n)
      assert(got == Counts(0, 0, 0, 0), s"$got")
      assert(!inBand(got, bands))
      // Both of the run counters read zero on a dead lane, because a run that
      // never ends never closes. Only `ones` tells this from a healthy lane,
      // which is why it is worth a register of its own -- and why `maxRun` of
      // zero must never be read as "no long runs, so nothing is wrong".
      assert(got.maxRun == 0 && got.gaps == 0)
    }

    it("should read all ones on a lane stuck at the rail") {
      val got = runOne(stuck(true, windowSamples), n)
      // One transition, not none: the first word's leading sample is compared
      // against a carry-in of zero, so a lane that comes up high registers the
      // edge into the window. It is one count against a lower bound in the
      // hundreds, so no band cares -- but the test says so rather than
      // pretending the number is zero.
      assert(got == Counts(1, windowSamples, 0, 0), s"$got")
      assert(!inBand(got, bands))
    }

    it("should reject a plain clock of the same rate") {
      val got = runOne(bareClock(n, windowSamples), n)
      assert(got.gaps == 0, s"a gapless clock should close no gap: $got")
      assert(!inBand(got, bands), s"$got passed the bands")
    }

    it("should reject a gapless pattern matched on edge rate and duty cycle") {
      // The case the gap counter exists for, and the only one that needs it:
      // one UI high and two low has exactly the repair pattern's edge rate and
      // exactly its duty cycle, so `transitions` and `ones` both land inside
      // their bands. Without a run length check this would score as a healthy
      // forwarded clock.
      val got = runOne(dutyMatchedGapless(n, windowSamples), n)
      assert(
        got.transitions >= bands.transMin && got.transitions <= bands.transMax,
        s"the point of this stimulus is that transitions pass: $got"
      )
      assert(
        got.ones >= bands.onesMin && got.ones <= bands.onesMax,
        s"the point of this stimulus is that ones pass: $got"
      )
      assert(got.gaps == 0, s"it has no gap to close: $got")
      assert(!inBand(got, bands), s"$got passed the bands")
    }

    it("should reject a far side transmitting at the wrong rate") {
      for (actual <- Seq(2, 8)) {
        // Sampled at x4 while the far side runs at a rate that makes it x2 or
        // x8 of what was declared.
        val got = runOne(repair(actual, windowSamples), n)
        assert(!inBand(got, bands), s"rate x$actual read as good: $got")
      }
    }

    it("should score each lane independently") {
      val good = repair(n, windowSamples)
      val got = run(Seq(good, stuck(false, windowSamples), good), n)
      assert(inBand(got(0), bands), s"clkP ${got(0)}")
      assert(!inBand(got(1), bands), s"clkN ${got(1)} should have failed")
      assert(inBand(got(2), bands), s"track ${got(2)}")
    }
  }

  describe("ClkRepair capture") {
    it("should return the window's first words, per lane") {
      val n = 4
      val streams = Seq(
        repair(n, windowSamples),
        bareClock(n, windowSamples),
        repair(n, windowSamples, phase = 3)
      )
      val expected = streams.map(words)
      simulate(new ClkRepair) { c =>
        c.io.windowWords.poke(0.U)
        c.io.gapThresh.poke((ClkRepair.GapThreshUi * n).U)
        c.io.capLane.poke(0.U)
        c.io.capOffset.poke(0.U)
        c.io.word.foreach(_.poke(0.U))
        c.clock.step(2)
        c.io.windowWords.poke(windowWords.U)
        for (w <- 0 until windowWords) {
          for (lane <- 0 until ClkRepair.Lanes) {
            c.io.word(lane).poke(expected(lane)(w).U)
          }
          c.clock.step()
        }
        for (lane <- 0 until ClkRepair.Lanes) {
          c.io.capLane.poke(lane.U)
          for (off <- 0 until ClkRepair.CaptureDepth) {
            c.io.capOffset.poke(off.U)
            c.clock.step(0)
            assert(
              c.io.capWord.peek().litValue == expected(lane)(off),
              s"lane $lane offset $off: " +
                f"${c.io.capWord.peek().litValue}%x != ${expected(lane)(off)}%x"
            )
          }
        }
      }
    }
  }
}
