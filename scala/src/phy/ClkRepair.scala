package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.phy.macros.clocking.ClkDistNetwork

object ClkRepair {

  /** Lanes measured, in [[ClkDistNetwork]] order: 0 clkP, 1 clkN, 2 track. Same
    * order as the three status bits MBINIT.REPAIRCLK reports over the sideband.
    */
  val Lanes: Int = ClkDistNetwork.repairLanes

  /** Lanes with a tap of their own: clkP and clkN.
    *
    * They have no deserializer otherwise, so a tap is the only way to sample
    * them, and it brings its own trim and shuffler. Track borrows the
    * deserializer it already has and is trimmed by its `rxctl`.
    */
  val TapLanes = 2
  require(
    ClkDistNetwork.repairTrack == TapLanes,
    "the tap lanes must be the low slots, so `repairctl` can be indexed by slot"
  )

  /** Words the capture ring keeps per lane, from the start of the window so an
    * offset is just a word index. Sixteen is 512 samples, two and a half
    * pattern repeats at a ratio of four -- enough to read the shape rather than
    * infer it from counters.
    */
  val CaptureDepth = 16

  /** Width of every accumulator. A window is bounded by `windowWords`, so the
    * largest any of them can reach is 32 * 65535, which fits with room over.
    */
  val CounterWidth = 32

  val WordCountWidth = 16

  /** UI of low that mark the pattern's gap, times the oversample ratio. The gap
    * is sixteen UI and the lows inside the burst are one, so eight is clear of
    * both by the same factor.
    */
  val GapThreshUi = 8

  /** Transitions in one 48 UI period: 31 inside the alternating burst, plus the
    * wrap back out of the gap.
    */
  val TransitionsPerPeriod = 32

  /** UI in one period of the clock repair pattern. */
  val PatternUi = 48

  /** UI of the alternating clock burst that opens the pattern. */
  val PatternBurstUi = 32

  /** UI of low that close it. */
  val PatternGapUi = 16

  /** UI of that period the pattern spends high: half the burst. */
  val PatternHighUi = PatternBurstUi / 2

  /** UI in the longest run of low, which is one more than the gap.
    *
    * The burst is `0x55555555` sent lowest bit first, so its last UI is low and
    * merges with the gap that follows. Worth spelling out: a threshold or an
    * expectation written from the spec's "sixteen UI low" is one UI short of
    * what a receiver actually sees.
    */
  val PatternLowRunUi = PatternGapUi + 1

  /** Periods that must pass back to back before a lane counts as detected.
    *
    * UCIe 3.0 4.5.3.3.3: "Detection is considered successful if at least 16
    * consecutive cycles of clock repair pattern are detected." The figure
    * `PatternReader.numConsecutive` already uses for the aligned patterns.
    */
  val NumConsecutive = 16

  /** Width of the consecutive-period counter. It saturates at the target, so it
    * only has to hold one, with room for a larger target set over MMIO.
    */
  val PeriodCountWidth = 16
}

/** What one lane's window comes back with.
  *
  * Four counters because each depends on the oversample ratio differently:
  * transitions and gaps fall as 1/N, `maxRun` rises with N, `ones` does not
  * move. A wrong ratio therefore shows up as three of them disagreeing in three
  * directions, which is how a declared ratio is checked without measuring a
  * frequency.
  */
class ClkRepairLaneCountsIO extends Bundle {

  /** Sample to sample changes over the window, counted across word boundaries.
    * Zero on a lane that is open or stuck at either rail.
    */
  val transitions = UInt(ClkRepair.CounterWidth.W)

  /** Samples that read high. A third of the window for the repair pattern, and
    * the one counter that is independent of the oversample ratio, so it tells a
    * dead lane (0) from a shorted one (all of them).
    */
  val ones = UInt(ClkRepair.CounterWidth.W)

  /** Runs of low at least `gapThresh` samples long that ended inside the window
    * -- i.e. how many times the pattern's gap went by. The only counter that
    * distinguishes the repair pattern from a bare clock of the same rate, and
    * the only one nothing else on the link produces by accident.
    */
  val gaps = UInt(ClkRepair.CounterWidth.W)

  /** The longest such run, which confirms the gap is the right length and
    * catches a differently shaped gapped pattern.
    *
    * Reads zero on a lane stuck at a rail, because a run that never ends never
    * closes -- so `maxRun` of zero is not "no long runs, nothing wrong". `ones`
    * is what tells the two apart.
    */
  val maxRun = UInt(ClkRepair.CounterWidth.W)

  /** Pattern periods that have passed back to back, saturating at the target.
    *
    * This is what the verdict rests on. The four counters above are
    * diagnostics: they are what makes a failure legible, but a window total
    * cannot express "consecutive".
    */
  val consecutive = UInt(ClkRepair.PeriodCountWidth.W)

  /** Whether `consecutive` reached the target. False for a target of zero,
    * which is a misconfiguration rather than a lane that passes for free.
    */
  val detected = Bool()
}

class ClkRepairIO extends Bundle {

  /** One oversampled word per measured lane, in wire order -- bit 0 is the
    * sample taken first. Wire order is the shuffler's job and it matters here:
    * in the tile's own tree order an alternating pattern deserializes as a
    * single long square wave, so transitions would read 1 instead of 32.
    */
  val word = Input(Vec(ClkRepair.Lanes, UInt(Phy.SerdesRatio.W)))

  /** Words to accumulate before the window closes. Fixed rather than paced by
    * whoever is waiting, so the thresholds a result is judged against are a
    * property of the configuration and not of sideband timing.
    */
  val windowWords = Input(UInt(ClkRepair.WordCountWidth.W))

  /** Samples of low that count as the pattern's gap, i.e.
    * [[ClkRepair.GapThreshUi]] times the oversample ratio.
    */
  val gapThresh = Input(UInt(ClkRepair.CounterWidth.W))

  /** Which word of the window [[capWord]] returns. */
  val capOffset = Input(UInt(log2Ceil(ClkRepair.CaptureDepth).W))

  /** Which lane [[capWord]] returns it for. */
  val capLane = Input(UInt(log2Ceil(ClkRepair.Lanes).W))

  /** What one period must look like and how many must pass in a row. */
  val detect = Input(new ClkRepairDetectIO)

  val done = Output(Bool())
  val wordsObserved = Output(UInt(ClkRepair.WordCountWidth.W))
  val counts = Output(Vec(ClkRepair.Lanes, new ClkRepairLaneCountsIO))
  val capWord = Output(UInt(Phy.SerdesRatio.W))
}

/** Scores the three lanes MBINIT.REPAIRCLK measures from their oversampled
  * words.
  *
  * A window has three states, and "open" below means the middle one:
  *
  *   - SHUT. The gate is closed, this clock does not run, and every register
  *     here holds whatever the last window left in it.
  *   - OPEN. The gate is ungated and `words < windowWords`, so the counters are
  *     accumulating. This is the only state in which anything here changes.
  *   - COMPLETE. The gate is still ungated but `words` has reached
  *     `windowWords`, so `running` is low and the counters are static even
  *     though the clock is still going. `done` is high.
  *
  * The last one is why the result can be read without stopping the clock first,
  * and the first is why a window that never completed can be read at all.
  *
  * Runs on the repair divided clock, which exists only while the repair clock
  * is ungated. That is what makes the result readable from the digital domain
  * without a queue or a handshake: when the window closes and the gate shuts,
  * every register here stops, and the reader sees static values.
  *
  * Reset here is the repair datapath reset, which is asserted whenever the gate
  * is shut. So a window does not need to be cleared -- opening the gate is the
  * clear.
  */
class ClkRepair extends Module {
  val io = IO(new ClkRepairIO)

  // Deserialized words consumed, one per cycle of this clock. Not a count of
  // transmitted pattern periods: nothing here parses the pattern, and the
  // receiver's word boundary has no relationship to where the far side
  // started sending. A transmitted period straddling two deserializations is
  // carried across by `prevLast` and `zeroRun` below.
  val words = RegInit(0.U(ClkRepair.WordCountWidth.W))
  // `<` rather than `=/=` so a window of zero never starts and a window that
  // has finished cannot be restarted by a counter wrapping.
  val running = words < io.windowWords
  when(running) { words := words + 1.U }

  io.done := !running
  io.wordsObserved := words

  // The capture holds the window's first words, so an offset is a word index.
  // A ring would show steady state at the cost of making the reader unwrap it
  // against the write pointer, and the start is the more informative end: it
  // is where a tile that has not woken, or a gate that opened mid pattern,
  // shows itself.
  val capture = Reg(
    Vec(ClkRepair.CaptureDepth, Vec(ClkRepair.Lanes, UInt(Phy.SerdesRatio.W)))
  )
  when(running && words < ClkRepair.CaptureDepth.U) {
    capture(words(log2Ceil(ClkRepair.CaptureDepth) - 1, 0)) := io.word
  }
  io.capWord := capture(io.capOffset)(io.capLane)

  for (lane <- 0 until ClkRepair.Lanes) {
    val w = io.word(lane)

    val transitions = RegInit(0.U(ClkRepair.CounterWidth.W))
    val ones = RegInit(0.U(ClkRepair.CounterWidth.W))
    val gaps = RegInit(0.U(ClkRepair.CounterWidth.W))
    val maxRun = RegInit(0.U(ClkRepair.CounterWidth.W))
    // Low samples carried over from the words before this one, so a run that
    // spans a word boundary -- which the pattern's gap always does at any
    // oversample ratio above one -- is measured whole.
    val zeroRun = RegInit(0.U(ClkRepair.CounterWidth.W))
    val prevLast = RegInit(false.B)
    // Per period, cleared at every gap. These are what the verdict reads; the
    // window counters above only describe the window as a whole.
    val perTransitions = RegInit(0.U(ClkRepair.CounterWidth.W))
    val perOnes = RegInit(0.U(ClkRepair.CounterWidth.W))
    val perSamples = RegInit(0.U(ClkRepair.CounterWidth.W))
    // Alternation violations seen in this period: positions where a sample
    // and the one a UI earlier are both high.
    val perAlt = RegInit(0.U(ClkRepair.CounterWidth.W))
    val prevWord = RegInit(0.U(Phy.SerdesRatio.W))
    val primed = RegInit(false.B)
    val consecutive = RegInit(0.U(ClkRepair.PeriodCountWidth.W))

    // Bit 0 is the sample taken first, so adjacent samples are (b, b+1) and
    // the word boundary pairs this word's bit 0 with the last word's bit 31.
    val inWordTransitions = PopCount(
      w(Phy.SerdesRatio - 1, 1) ^ w(Phy.SerdesRatio - 2, 0)
    )
    val boundaryTransition = w(0) ^ prevLast
    val wordTransitions = inWordTransitions +& boundaryTransition
    val wordOnes = PopCount(w)

    // Zeros before the first high sample and after the last one. Both are read
    // only on the branch where the word has a high sample, so a priority
    // encoder with nothing to find never reaches the counters.
    val leadingZeros = PriorityEncoder(w)
    val trailingZeros = PriorityEncoder(Reverse(w))
    val allZero = !w.orR

    // The low run ending in this word, if one does. Only read where the word
    // has a high sample, so a priority encoder with nothing to find never
    // reaches a counter.
    val closed = zeroRun + leadingZeros

    // The pattern's gap is the only unambiguous period marker in an
    // oversampled stream: the longest low inside the burst is one UI and the
    // gap is sixteen. Finding it needs no alignment to a reference word, which
    // is what makes per-period checking possible here at all.
    val periodEnd = !allZero && closed >= io.gapThresh

    // The alternation itself, and the only check here that looks at the shape
    // of the burst rather than at a total of it.
    //
    // Inside the burst every run is one UI, so a sample and the one a UI
    // earlier are never both high. Two highs a UI apart mean a high run longer
    // than a UI; and because a short LOW run brings the highs on either side of
    // it within a UI of each other, the same test catches that too. With the
    // period's transitions, ones, gap and length all pinned, "no two highs a UI
    // apart" is what leaves exactly one waveform: sixteen high runs of one UI,
    // fifteen low runs of one UI, and the gap.
    val older = Cat(w, prevWord) >> (Phy.SerdesRatio.U - io.detect.uiSamples)
    val wordAlt = PopCount(w & older(Phy.SerdesRatio - 1, 0))

    // The samples this period ran for, closed off at the gap that ends it.
    val periodSamples = perSamples +& leadingZeros

    val periodOk =
      perTransitions >= io.detect.transMin &&
        perTransitions <= io.detect.transMax &&
        perOnes >= io.detect.onesMin &&
        perOnes <= io.detect.onesMax &&
        closed >= io.detect.gapMin &&
        closed <= io.detect.gapMax &&
        periodSamples >= io.detect.periodMin &&
        periodSamples <= io.detect.periodMax &&
        // `perAlt` alone: a violation needs two high samples, and every sample
        // of this word below the gap end is low, so anything this word trips
        // belongs to the period starting here, not the one closing.
        perAlt <= io.detect.altMax

    // Freezes at the target so a later bad period cannot undo a pass, the rule
    // `PatternReader.foldWordIterations` uses for the aligned patterns.
    val reached = consecutive === io.detect.target

    when(running) {
      transitions := transitions + wordTransitions
      ones := ones + wordOnes
      prevLast := w(Phy.SerdesRatio - 1)

      when(allZero) {
        zeroRun := zeroRun + Phy.SerdesRatio.U
      }.otherwise {
        when(periodEnd) { gaps := gaps + 1.U }
        when(closed > maxRun) { maxRun := closed }
        zeroRun := trailingZeros
      }

      prevWord := w

      when(periodEnd) {
        // Every sample before the first high one is part of the gap that is
        // closing and carries neither a one nor a transition, so this whole
        // word's contribution belongs to the period starting here.
        perTransitions := wordTransitions
        perOnes := wordOnes
        perSamples := Phy.SerdesRatio.U - leadingZeros
        perAlt := wordAlt
        // The first boundary only starts the count: what precedes it is a
        // part period, whose totals mean nothing.
        primed := true.B
        when(primed && !reached) {
          consecutive := Mux(periodOk, consecutive + 1.U, 0.U)
        }
      }.otherwise {
        perTransitions := perTransitions + wordTransitions
        perOnes := perOnes + wordOnes
        perSamples := perSamples + Phy.SerdesRatio.U
        perAlt := perAlt + wordAlt
      }
    }

    io.counts(lane).transitions := transitions
    io.counts(lane).ones := ones
    io.counts(lane).gaps := gaps
    io.counts(lane).maxRun := maxRun
    io.counts(lane).consecutive := consecutive
    io.counts(lane).detected := reached && io.detect.target =/= 0.U
  }
}

/** What one pattern period must look like, and how many must pass in a row.
  *
  * Shared by every lane, because during REPAIRCLK all three carry the same
  * word.
  */
class ClkRepairDetectIO extends Bundle {

  /** Transitions in one period. Exact in practice: the count is a property of
    * the pattern and not of the oversample ratio, so a period an edge off is
    * corrupt rather than badly sampled.
    */
  val transMin = UInt(ClkRepair.CounterWidth.W)
  val transMax = UInt(ClkRepair.CounterWidth.W)

  /** High samples in one period. Scales with the ratio and moves with the
    * sampling phase, so it gets a UI of slack.
    */
  val onesMin = UInt(ClkRepair.CounterWidth.W)
  val onesMax = UInt(ClkRepair.CounterWidth.W)

  /** Length in samples of the low run that closes the period. */
  val gapMin = UInt(ClkRepair.CounterWidth.W)
  val gapMax = UInt(ClkRepair.CounterWidth.W)

  /** Samples between one gap and the next, i.e. the whole period. */
  val periodMin = UInt(ClkRepair.CounterWidth.W)
  val periodMax = UInt(ClkRepair.CounterWidth.W)

  /** Samples in one UI, the oversample ratio. This is what makes the check a
    * detection rather than a tally: inside the burst a sample and the one
    * `uiSamples` earlier are never both high, which is the alternation itself.
    */
  val uiSamples = UInt(log2Ceil(Phy.SerdesRatio + 1).W)

  /** Alternation violations tolerated in one period.
    *
    * Not zero, because a far side on its own clock drifts: an inserted sample
    * stretches a high run to a UI and a sample, which is one violation and not
    * a corruption. A stretch that means anything is many -- a run of `k` UI
    * trips `(k - 1) * uiSamples` of them -- so the two are nowhere near each
    * other and the allowance does not have to be tight.
    */
  val altMax = UInt(ClkRepair.CounterWidth.W)

  /** Periods that must pass back to back. */
  val target = UInt(ClkRepair.PeriodCountWidth.W)
}

object ClkRepairVerdict {

  /** Whether one lane detected the clock repair pattern.
    *
    * Here rather than inline in the register block so the seam between the
    * measurement and the controller can be simulated on its own: this is the
    * step that turns a measurement into the three status bits MBINIT puts on
    * the sideband.
    *
    * `ratioOk` is the oversample ratio being at least two. Below that a sample
    * lands wherever the two clocks happen to sit and nothing measured means
    * anything, so no lane passes -- otherwise a part left at its reset clocking
    * samples once a UI and can agree by luck.
    */
  def apply(counts: ClkRepairLaneCountsIO, ratioOk: Bool): Bool =
    counts.detected && ratioOk
}

object ClkRepairExpect {

  /** What a window of `words` at oversample ratio `n` should come back with on
    * a lane that is carrying the clock repair pattern.
    *
    * Kept next to the hardware so the test drivers, the controller's default
    * thresholds and the RTL cannot drift apart. `words` should cover a whole
    * number of pattern periods -- 32 * words must divide by 48 * n -- or the
    * partial period at the end costs a few counts of margin.
    */
  /** @param gaps
    *   runs of low long enough to be the pattern's gap that CLOSED inside the
    *   window. A window that ends part way through a gap leaves its last one
    *   open, so the real count is this or one less depending on where the
    *   window landed -- which is why the band around it is absolute and at
    *   least two wide.
    */
  case class Expectation(
      periods: Int,
      transitions: Int,
      ones: Int,
      gaps: Int,
      maxRun: Int
  )

  def apply(words: Int, n: Int): Expectation = {
    require(n >= 2, s"oversample ratio $n must be at least 2")
    val samples = words * Phy.SerdesRatio
    val periods = samples / (ClkRepair.PatternUi * n)
    Expectation(
      periods = periods,
      transitions = periods * ClkRepair.TransitionsPerPeriod,
      ones = periods * ClkRepair.PatternHighUi * n,
      gaps = periods,
      maxRun = ClkRepair.PatternLowRunUi * n
    )
  }

  /** Words that cover a whole number of pattern periods at every oversample
    * ratio up to `maxN`, nearest at or below `target`.
    */
  def alignedWindow(target: Int, maxN: Int = 8): Int = {
    val step = (ClkRepair.PatternUi * maxN) / Phy.SerdesRatio
    math.max(step, (target / step) * step)
  }

  case class Bands(
      transMin: Int,
      transMax: Int,
      onesMin: Int,
      onesMax: Int,
      gapsMin: Int,
      gapsMax: Int,
      maxRunMin: Int,
      maxRunMax: Int
  )

  /** Thresholds for a window of `words` at ratio `n`.
    *
    * `transitions` and `ones` get a proportional margin: the front end's
    * auto-zero handover costs an edge each time it swaps, and two clocks that
    * are not the same clock drift through a sample or two over a window. `gaps`
    * gets an absolute one, since the only thing that moves it is the partial
    * period at either end of the window. `maxRun` gets a margin in samples,
    * which is the resolution the measurement has.
    */
  def bands(words: Int, n: Int, pctTol: Int = 3): Bands = {
    val e = apply(words, n)
    def lo(v: Int) = math.max(0, v - (v * pctTol) / 100)
    def hi(v: Int) = v + (v * pctTol) / 100
    Bands(
      transMin = lo(e.transitions),
      transMax = hi(e.transitions),
      onesMin = lo(e.ones),
      onesMax = hi(e.ones),
      gapsMin = math.max(0, e.gaps - 2),
      gapsMax = e.gaps + 2,
      maxRunMin = math.max(0, e.maxRun - 2 * n),
      maxRunMax = e.maxRun + 2 * n
    )
  }

  /** What the register block comes up holding: the window `UcieTLRegs` comes up
    * with, at the oversample ratio MBINIT runs at with an 8 GHz main clock and
    * the lanes at 4 GT/s.
    */
  /** The configuration the register defaults describe: MBINIT on an 8 GHz main
    * clock, the lanes divided to 2 GHz (4 GT/s, which is what the controller
    * asks for as `SpeedMode.speed4`), and the sampling clock undivided.
    *
    * The clocking registers themselves come up at `txClkDiv` and `repairClkDiv`
    * of zero, which is a ratio of ONE and not a usable configuration -- that is
    * the unconfigured state, and the rate table is what moves it. Everything
    * below is derived from these two so that the window, the gap threshold and
    * the bands cannot describe different configurations from each other.
    */
  val defaultN = 4
  val defaultWindow: Int = alignedWindow(768)
  lazy val defaultBands: Bands = bands(defaultWindow, defaultN)

  case class Detect(
      transMin: Int,
      transMax: Int,
      onesMin: Int,
      onesMax: Int,
      gapMin: Int,
      gapMax: Int,
      periodMin: Int,
      periodMax: Int,
      uiSamples: Int,
      altMax: Int,
      target: Int
  )

  /** What one period must look like at ratio `n`.
    *
    * `transitions` is exact. The count is a property of the pattern, not of the
    * sampling, so a period an edge out is corrupt -- and that exactness is what
    * the window bands could not provide, since there a corrupt UI disappears
    * into a margin sized for drift. `ones` and the gap are measured in samples
    * and do move with the sampling phase, so each gets one UI.
    */
  def detect(n: Int, target: Int = ClkRepair.NumConsecutive): Detect = {
    require(n >= 2, s"oversample ratio $n must be at least 2")
    Detect(
      transMin = ClkRepair.TransitionsPerPeriod,
      transMax = ClkRepair.TransitionsPerPeriod,
      onesMin = ClkRepair.PatternHighUi * n - n,
      onesMax = ClkRepair.PatternHighUi * n + n,
      gapMin = ClkRepair.PatternLowRunUi * n - n,
      gapMax = ClkRepair.PatternLowRunUi * n + n,
      periodMin = ClkRepair.PatternUi * n - n,
      periodMax = ClkRepair.PatternUi * n + n,
      uiSamples = n,
      altMax = n,
      target = target
    )
  }

  /** Words covering `target` + 1 periods at every ratio up to `maxN`: one
    * period to find the first gap, then `target` whole ones.
    */
  def detectWindow(
      target: Int = ClkRepair.NumConsecutive,
      maxN: Int = 8
  ): Int = {
    val step = (ClkRepair.PatternUi * maxN) / Phy.SerdesRatio
    // Two periods of slack, not one: the first gap only starts the count, and
    // a window that is a whole number of periods ends INSIDE the last gap, so
    // that one never closes and its period is never scored.
    val need = (target + 2) * ClkRepair.PatternUi * maxN / Phy.SerdesRatio
    ((need + step - 1) / step) * step
  }

  lazy val defaultDetect: Detect = detect(defaultN)
}
