package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._

import edu.berkeley.cs.uciedigital.phy.{Phy, TxIO}

/** A word for the TX lanes, as a controller queues it for [[TxClkGater]]. */
class TxQueueWord(numLanes: Int) extends Bundle {
  val word = new TxIO(numLanes)
  // Leave `idlePackets` words of gap after this one before the next. Only
  // PhyTest's bursts set it.
  val gapAfter = Bool()
  // More data follows this word in the same run, so the queue running dry
  // before it comes is a word lost. Only PhyTest sets it.
  val continues = Bool()
}

/** Puts a word on the TX lanes every packet, whichever controller drives them:
  * the controller's next queued word when there is one and nothing else is due,
  * and otherwise a word of its own. Controllers therefore queue only the words
  * that carry something, and nothing waits behind idle words.
  *
  * A word of its own carries `fill` on the data, valid and track lanes. Its
  * clock lanes run `clkP`/`clkN` unless `gate` is set, in which case the clock
  * runs only around data, the way UCIe's strobe mode asks:
  *
  *   - after a word from the queue it runs on for `postamble` units of
  *     `PhyTest.ClkPostambleUi` UI, then parks;
  *   - parked, each lane holds its pattern's UI 1 level (with the usual
  *     patterns, P low and N high: a differential low), or with
  *     `parkAlternate`, every other park, its UI 0 level (a differential high);
  *   - a high park keeps the level of the postamble's last rising edge through
  *     the UI it would have fallen on, and that falling edge goes out
  *     `PhyTest.ClkPreconditionUi` UI before the clock runs again instead, so
  *     every gap carries exactly the postamble's edges and the far RX's word
  *     boundary stays where it was whenever that is a whole word;
  *   - before data after a park, `preamble` words of running clock.
  *
  * A queued word goes out exactly as queued, clock lanes included, so a
  * controller's own clock patterns pass straight through. A park straight after
  * one (`postamble` = 0) stays low, since a high park would change that word's
  * last UI; so does one that comes from the queue running dry.
  *
  * Gaps come one of two ways. After a word with `gapAfter`, exactly
  * `idlePackets` words of its own, with the preamble in the last of them and a
  * precondition in the word before it, as PhyTest's bursts have always had.
  * Otherwise for as long as the queue is empty; then the clock can only start
  * once the next word is there, so after a high park one word goes out first
  * just for the precondition.
  *
  * Every setting but `gate` is a quasi-static register, read as it stands each
  * packet; change one only while the queue is empty and no gap is running.
  * `gate` may change at any time: it applies from the next word. Runs on the TX
  * divided clock, one word per cycle.
  */
class TxClkGater(numLanes: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TxQueueWord(numLanes)))
    val out = Output(new TxIO(numLanes))
    val gate = Input(Bool())
    val preamble = Input(UInt(16.W))
    val postamble = Input(UInt(16.W))
    val idlePackets = Input(UInt(32.W))
    val parkAlternate = Input(Bool())
    val clkP = Input(UInt(Phy.SerdesRatio.W))
    val clkN = Input(UInt(Phy.SerdesRatio.W))
    // The data, valid and track lanes of a word of its own.
    val fill = Input(new TxIO(numLanes))
    // Sticky: the queue was empty where a word with `continues` said more
    // data would follow.
    val underflow = Output(Bool())
  })

  val n = Phy.SerdesRatio
  val full = ((BigInt(1) << n) - 1).U(n.W)

  // Words of its own since the last queued word, saturating. Starts
  // saturated, so a gated clock is parked out of reset.
  val sinceDataMax = (1 << 16) - 1
  val sinceData = RegInit(sinceDataMax.U(16.W))
  // Words of a `gapAfter` gap still to go, this one included.
  val gapLeft = RegInit(0.U(32.W))
  // The level of the clock's latest park, which it may still be in.
  val parkHigh = RegInit(false.B)
  // Whether the clock ran in the last UI sent: if not, it is parked.
  val lastRunning = RegInit(false.B)
  // The precondition has gone out since the clock last ran, so a high park is
  // low again.
  val preconditioned = RegInit(false.B)
  // Starting the clock for queued data after a park: preamble words sent.
  val waking = RegInit(false.B)
  val wakeCount = RegInit(0.U(16.W))
  val expectMore = RegInit(false.B)
  val underflow = RegInit(false.B)
  io.underflow := underflow

  val inGap = gapLeft =/= 0.U
  val stopped = !lastRunning
  val heldHigh = stopped && parkHigh && !preconditioned

  // Which UIs a word `s` words after the last queued one runs the clock for,
  // by the postamble alone. Bit 0 goes out first, so a postamble ending
  // partway through a word keeps its low bits.
  val unitsPerWord = n / PhyTest.ClkPostambleUi
  val postamblePackets = io.postamble >> log2Ceil(unitsPerWord)
  val postambleUnitsLeft = io.postamble(log2Ceil(unitsPerWord) - 1, 0)
  def postambleMask(s: UInt): UInt = Mux(
    s < postamblePackets,
    full,
    Mux(
      s === postamblePackets,
      VecInit(
        (0 until unitsPerWord).map(u =>
          ((BigInt(1) << (u * PhyTest.ClkPostambleUi)) - 1).U(n.W)
        )
      )(postambleUnitsLeft),
      0.U
    )
  )
  // In a gap of known length: the postamble, then the preamble in its last
  // words.
  def gapMask(s: UInt, left: UInt): UInt =
    postambleMask(s) | Mux(left <= io.preamble, full, 0.U)
  val sinceNext = Mux(sinceData === sinceDataMax.U, sinceData, sinceData + 1.U)

  // What goes out this packet, and the clock it runs: `clocked` for this
  // word's UIs, `nextClocked0` for the next word's first UI as far as can be
  // told now.
  val sendData = WireDefault(false.B)
  val clocked = WireDefault(0.U(n.W))
  val nextClocked0 = WireDefault(false.B)
  when(inGap) {
    clocked := Mux(io.gate, gapMask(sinceData, gapLeft), full)
    nextClocked0 := !io.gate || gapLeft === 1.U ||
      gapMask(sinceNext, gapLeft - 1.U)(0)
  }.elsewhen(io.in.valid) {
    when(heldHigh) {
      // Down from a high park before the clock can start.
      nextClocked0 := true.B
    }.elsewhen(io.gate && (stopped || waking) && wakeCount < io.preamble) {
      clocked := full
      nextClocked0 := true.B
    }.otherwise {
      sendData := true.B
    }
  }.elsewhen(io.gate) {
    clocked := postambleMask(sinceData)
    nextClocked0 := postambleMask(sinceNext)(0)
  }.otherwise {
    // Running, after the precondition if the gate just opened on a high park.
    clocked := Mux(heldHigh, 0.U, full)
    nextClocked0 := true.B
  }
  io.in.ready := sendData

  // The UI the clock stops after, if it stops in this word: at most one,
  // since it only ever starts again at a word boundary.
  val stopsAfter = clocked & ~Cat(nextClocked0, clocked)(n, 1)
  // Or it stopped at the boundary before this word without the last one
  // seeing it coming: after a queued word with no postamble, or a queue that
  // ran dry. That park is low, since a high one would have changed the last
  // word's last UI, which has gone.
  val stopsAtStart = !sendData && lastRunning && !clocked(0)
  val parkHighNow = Mux(
    stopsAfter.orR,
    io.parkAlternate && !parkHigh,
    Mux(stopsAtStart, false.B, parkHigh)
  )
  // A high park keeps the last rising edge's level through the UI the
  // postamble would have fallen on.
  val kept = Mux(parkHighNow, stopsAfter, 0.U)
  val precondition = Mux(
    nextClocked0,
    ~clocked & (((BigInt(1) << PhyTest.ClkPreconditionUi) - 1).U <<
      (n - PhyTest.ClkPreconditionUi)),
    0.U
  )
  val running = clocked & ~kept
  val parkedHigh = parkHighNow && !preconditioned
  def clkLane(pattern: UInt): UInt = {
    val high = Fill(n, pattern(0))
    val low = Fill(n, pattern(1))
    val parked = ~running & ~precondition
    (pattern & running) | (Mux(parkedHigh, high, low) & parked) |
      (low & precondition)
  }

  io.out := io.fill
  io.out.clkp := clkLane(io.clkP)
  io.out.clkn := clkLane(io.clkN)
  when(sendData) {
    io.out := io.in.bits.word
  }

  when(sendData) {
    sinceData := 0.U
    gapLeft := Mux(io.in.bits.gapAfter, io.idlePackets, 0.U)
    expectMore := io.in.bits.continues
    lastRunning := true.B
    preconditioned := false.B
    waking := false.B
    wakeCount := 0.U
  }.otherwise {
    sinceData := sinceNext
    when(inGap) { gapLeft := gapLeft - 1.U }
    when(stopsAfter.orR || stopsAtStart) { parkHigh := parkHighNow }
    lastRunning := running(n - 1)
    when(running.orR) {
      preconditioned := false.B
    }.elsewhen(precondition.orR) {
      preconditioned := true.B
    }
    when(io.in.valid && !inGap && io.gate && clocked.orR) {
      waking := true.B
      wakeCount := wakeCount + 1.U
    }
    when(!inGap && !io.in.valid && expectMore) { underflow := true.B }
  }
}
