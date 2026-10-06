package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.phy.macros.clocking.ClkDistNetwork

object ClkRepair {

  /** Lanes measured, in [[ClkDistNetwork]] order: 0 clkP, 1 clkN, 2 track. Same
    * order as the three status bits MBINIT.REPAIRCLK reports over the sideband.
    */
  val Lanes: Int = ClkDistNetwork.repairLanes

  /** Lanes that carry a sampling tap of their own, and so have their own trim
    * and shuffler: the two forwarded-clock lanes, which have no data path to
    * borrow. Track is measured through its own deserializer and is configured
    * by its `rxctl` like any other lane.
    */
  val TapLanes: Int = ClkDistNetwork.repairTrack

  /** Words of the window the capture ring keeps, per lane. The first ones, so
    * an offset is a word index and nothing has to be unwrapped. Sixteen words
    * is 512 samples, which is two and a half repeats of the clock repair
    * pattern at an oversample ratio of four -- enough to read the shape rather
    * than infer it from counters.
    */
  val CaptureDepth = 16

  /** Width of every accumulator. A window is bounded by `windowWords`, so the
    * largest any of them can reach is 32 * 65535, which fits with room over.
    */
  val CounterWidth = 32

  val WordCountWidth = 16

  /** Transmitted UI of low that separate a repair pattern's gap from the one UI
    * lows inside its clock burst, as a multiple of the oversample ratio. The
    * gap is sixteen UI and the lows in the burst are one, so eight sits clear
    * of both by the same factor.
    */
  val GapThreshUi = 8

  /** Transitions one 48 UI period of the clock repair pattern carries: the 32
    * UI burst alternates every UI, and the wrap back out of the 16 UI gap adds
    * the last one.
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
}

/** What one lane's window comes back with.
  *
  * Four counters rather than one because each depends on the oversample ratio
  * differently -- transitions and gaps fall as 1/N, `maxRun` rises with N, and
  * `ones` does not move at all. A ratio that is not what the registers say
  * therefore shows up as three counters disagreeing in three directions, which
  * is what makes the declared ratio checkable without measuring a frequency.
  */
class ClkRepairLaneObsIO extends Bundle {

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

  /** The longest such run. Confirms the gap is the length it should be, which
    * is what catches a remote sending a differently shaped gapped pattern.
    *
    * Reads zero on a lane that never leaves zero, because a run that never ends
    * never closes. `ones` is what separates that from a healthy lane; `maxRun`
    * alone must not be read as "no long runs, so nothing is wrong".
    */
  val maxRun = UInt(ClkRepair.CounterWidth.W)
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

  val done = Output(Bool())
  val wordsObserved = Output(UInt(ClkRepair.WordCountWidth.W))
  val obs = Output(Vec(ClkRepair.Lanes, new ClkRepairLaneObsIO))
  val capWord = Output(UInt(Phy.SerdesRatio.W))
}

/** Scores the three lanes MBINIT.REPAIRCLK measures from their oversampled
  * words.
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

    // Bit 0 is the sample taken first, so adjacent samples are (b, b+1) and
    // the word boundary pairs this word's bit 0 with the last word's bit 31.
    val inWordTransitions = PopCount(
      w(Phy.SerdesRatio - 1, 1) ^ w(Phy.SerdesRatio - 2, 0)
    )
    val boundaryTransition = w(0) ^ prevLast

    // Zeros before the first high sample and after the last one. Both are read
    // only on the branch where the word has a high sample, so a priority
    // encoder with nothing to find never reaches the counters.
    val leadingZeros = PriorityEncoder(w)
    val trailingZeros = PriorityEncoder(Reverse(w))
    val allZero = !w.orR

    when(running) {
      transitions := transitions + inWordTransitions + boundaryTransition
      ones := ones + PopCount(w)
      prevLast := w(Phy.SerdesRatio - 1)

      when(allZero) {
        zeroRun := zeroRun + Phy.SerdesRatio.U
      }.otherwise {
        val closed = zeroRun + leadingZeros
        when(closed >= io.gapThresh) { gaps := gaps + 1.U }
        when(closed > maxRun) { maxRun := closed }
        zeroRun := trailingZeros
      }
    }

    io.obs(lane).transitions := transitions
    io.obs(lane).ones := ones
    io.obs(lane).gaps := gaps
    io.obs(lane).maxRun := maxRun
  }
}

/** Thresholds a window's counters are judged against, one band per counter.
  *
  * Shared by every lane, because during REPAIRCLK all three carry the same
  * word.
  */
class ClkRepairBandsIO extends Bundle {
  val transMin = UInt(ClkRepair.CounterWidth.W)
  val transMax = UInt(ClkRepair.CounterWidth.W)
  val onesMin = UInt(ClkRepair.CounterWidth.W)
  val onesMax = UInt(ClkRepair.CounterWidth.W)
  val gapsMin = UInt(ClkRepair.CounterWidth.W)
  val gapsMax = UInt(ClkRepair.CounterWidth.W)
  val maxRunMin = UInt(ClkRepair.CounterWidth.W)
  val maxRunMax = UInt(ClkRepair.CounterWidth.W)
}

object ClkRepairVerdict {

  /** Whether one lane's counters say it carried the clock repair pattern.
    *
    * Here rather than inline in the register block so the seam between the
    * measurement and the controller can be simulated on its own: this is the
    * step that turns counts into the three status bits MBINIT puts on the
    * sideband, and it is the only place a band is interpreted.
    *
    * `ratioOk` is the oversample ratio being at least two. Below that a sample
    * lands wherever the two clocks happen to sit and no count means anything,
    * so no lane passes -- otherwise a part left at its reset clocking samples
    * once a UI and can agree with bands written for a ratio of four by luck.
    */
  def apply(
      obs: ClkRepairLaneObsIO,
      bands: ClkRepairBandsIO,
      ratioOk: Bool
  ): Bool =
    obs.transitions >= bands.transMin &&
      obs.transitions <= bands.transMax &&
      obs.ones >= bands.onesMin &&
      obs.ones <= bands.onesMax &&
      obs.gaps >= bands.gapsMin &&
      obs.gaps <= bands.gapsMax &&
      obs.maxRun >= bands.maxRunMin &&
      obs.maxRun <= bands.maxRunMax &&
      ratioOk
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
}
