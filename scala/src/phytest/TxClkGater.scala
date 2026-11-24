package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._

import edu.berkeley.cs.uciedigital.phy.{Phy, TxIO}

/** A word for the TX lanes, as a controller queues it for [[TxClkGater]]. */
class TxQueueWord(numLanes: Int) extends Bundle {
  val word = new TxIO(numLanes)
  // Leave `idlePackets` words of gap after this word. Only PhyTest's bursts
  // set it.
  val gapAfter = Bool()
  // More data follows in the same run, so if the queue runs dry first, a word
  // is lost. Only PhyTest sets it.
  val continues = Bool()
}

/** Puts a word on the TX lanes every packet, for whichever controller drives
  * them: the controller's next queued word if one is waiting and nothing else
  * is due, otherwise a word of its own. Controllers queue only words that
  * carry something, so nothing waits behind idle words.
  *
  * Its own words carry `fill` on the data, valid and track lanes and
  * `clkP`/`clkN` on the clock lanes. With `gate` set, the clock runs only
  * around data, as in UCIe strobe mode:
  *
  *   - After a queued word it runs for `postamble` units of
  *     `PhyTest.ClkPostambleUi` UI, then parks.
  *   - A park holds each lane's UI 1 level (with the usual patterns, P low
  *     and N high: differential low). With `parkAlternate`, every other park
  *     holds the UI 0 level (differential high).
  *   - A high park holds the postamble's last rising edge through the UI it
  *     would fall on. That falling edge goes out `PhyTest.ClkPreconditionUi`
  *     UI before the clock restarts instead. So every gap has exactly the
  *     postamble's edges, and the far RX's word boundary stays put when the
  *     postamble is a whole word.
  *   - After a park, `preamble` words of running clock precede data.
  *
  * Queued words go out unchanged, clock lanes included, so a controller's
  * own clock patterns pass through. A park right after a queued word
  * (`postamble` = 0) or caused by the queue running dry stays low, since a
  * high park would change the last word's last UI.
  *
  * Gaps:
  *   - After a word with `gapAfter`: exactly `idlePackets` own words, with the
  *     preamble in the last of them and a precondition in the one before.
  *   - Otherwise, while the queue is empty. The clock starts only once the
  *     next word is there, so after a high park one word goes out first just
  *     for the precondition.
  *
  * Settings other than `gate` are quasi-static, read each packet: change them
  * only while the queue is empty and no gap is running. `gate` may change any
  * time and applies from the next word. Runs on the TX divided clock, one
  * word per cycle.
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

  // The UIs the postamble alone clocks in the word `s` words after the last
  // queued one. Bit 0 goes out first, so a postamble that ends mid-word sets
  // the low bits.
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

  // What goes out this packet. `clocked` marks this word's UIs that run the
  // clock. `nextClocked0` is the best guess now for the next word's first UI.
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
  // Or the clock stopped at the start of this word, which the last word could
  // not predict: after a queued word with no postamble, or when the queue ran
  // dry. That park is low, since a high park would change the last word's
  // last UI, which is already sent.
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
