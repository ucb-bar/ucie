package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.phy.{
  ClkRepair,
  ClkRepairBandsIO,
  ClkRepairExpect,
  ClkRepairVerdict,
  Phy
}

/** The seam between the PHY's MBINIT.REPAIRCLK measurement and the controller
  * that asks for it, with nothing of the protocol in between.
  *
  * Everything from the state machine's request to its answer is here: the
  * detector's handshake, the window running only while `repairClkEn` is up, the
  * counters, the bands, and the three status bits that end up on the sideband.
  * The one thing left out is the FSM reaching MBINIT.REPAIRCLK, which is
  * protocol and is covered by the two-die loopback suites.
  *
  * `repairClkEn` gating a clock is modelled the way it behaves: while it is low
  * the measurement sees no words and is held in reset, which is what a stopped
  * clock does.
  */
class ClkRepairSeam(
    afeParams: AfeParams,
    laneAlive: Seq[Boolean],
    n: Int,
    windowWords: Int,
    ratioOk: Boolean
) extends Module {
  require(laneAlive.length == ClkRepair.Lanes)

  val io = IO(new Bundle {

    /** Pulsed to issue a CLKREPAIR request, as the MBInit responder does. */
    val start = Input(Bool())

    /** The requester's `done` strobe, which is what releases the answer. */
    val done = Input(Bool())
    val reqReady = Output(Bool())
    val respValid = Output(Bool())
    val respReady = Input(Bool())
    val laneOk = Output(Vec(ClkRepair.Lanes, Bool()))
    val aggregate = Output(Bool())
    val repairClkEn = Output(Bool())
    val wordsObserved = Output(UInt(ClkRepair.WordCountWidth.W))
  })

  val detector = Module(new ClkRepairDetector(afeParams))
  io.reqReady := detector.io.interfaceIo.req.ready
  detector.io.interfaceIo.req.valid := io.start
  detector.io.interfaceIo.req.bits.patternType := PatternSelect.CLKREPAIR
  detector.io.interfaceIo.req.bits.comparisonMode := ComparisonMode.PERLANE
  detector.io.interfaceIo.req.bits.errorThreshold := 0.U
  detector.io.interfaceIo.req.bits.doConsecutiveCount := false.B
  detector.io.interfaceIo.remoteFuncLanes := "b011".U
  detector.io.interfaceIo.done := io.done
  detector.io.interfaceIo.resp.ready := io.respReady
  io.respValid := detector.io.interfaceIo.resp.valid
  io.aggregate := detector.io.interfaceIo.resp.bits.aggregateStatus
  io.repairClkEn := detector.io.repairClkEn

  // The clock-repair pattern as the lane words a tap hands over at this
  // oversample ratio, in wire order. The whole pattern is
  // `PatternUi * n` samples, so it closes after this many words and repeats.
  val patternWords: Int = (ClkRepair.PatternUi * n) / Phy.SerdesRatio
  require(
    patternWords * Phy.SerdesRatio == ClkRepair.PatternUi * n,
    s"an oversample ratio of $n does not land the pattern on a word boundary"
  )
  val pattern = BigInt("000055555555", 16)
  def word(w: Int): BigInt =
    (0 until Phy.SerdesRatio).foldLeft(BigInt(0)) { case (acc, b) =>
      val ui = ((w * Phy.SerdesRatio + b) / n) % ClkRepair.PatternUi
      if (((pattern >> ui) & 1) == 1) acc | (BigInt(1) << b) else acc
    }
  val rom = VecInit(
    Seq.tabulate(patternWords)(w => word(w).U(Phy.SerdesRatio.W))
  )

  // The measurement only advances while the window is open, which is what the
  // gate does in the PHY: no repair clock, no words and no counting.
  val meas = withReset(!io.repairClkEn || reset.asBool) {
    Module(new ClkRepair)
  }
  val phase = withReset(!io.repairClkEn || reset.asBool) {
    RegInit(0.U(log2Ceil(patternWords).W))
  }
  when(io.repairClkEn) {
    phase := Mux(phase === (patternWords - 1).U, 0.U, phase + 1.U)
  }
  for (lane <- 0 until ClkRepair.Lanes) {
    meas.io.word(lane) := (if (laneAlive(lane)) rom(phase) else 0.U)
  }
  meas.io.windowWords := windowWords.U
  meas.io.gapThresh := (ClkRepair.GapThreshUi * n).U
  meas.io.capLane := 0.U
  meas.io.capOffset := 0.U
  // Latched at completion rather than read afterwards.
  //
  // This harness models a shut gate as holding the measurement in reset, so
  // the counters clear the moment the window closes. The PHY does NOT do that
  // -- there the counters clear on the gate OPENING, because closing it is how
  // a result is frozen for reading, and tying the clear to the level wipes the
  // very thing the window produced. The detector samples its verdict on the
  // cycle `done` is high, so it is unaffected either way; only a reader coming
  // along later can tell the difference, and here that reader is the test.
  val wordsAtDone = RegInit(0.U(ClkRepair.WordCountWidth.W))
  when(io.repairClkEn && meas.io.done) {
    wordsAtDone := meas.io.wordsObserved
  }
  io.wordsObserved := wordsAtDone

  // The register block's half of the seam: the bands, and the verdict.
  val bands = Wire(new ClkRepairBandsIO)
  val expect = ClkRepairExpect.bands(windowWords, n)
  bands.transMin := expect.transMin.U
  bands.transMax := expect.transMax.U
  bands.onesMin := expect.onesMin.U
  bands.onesMax := expect.onesMax.U
  bands.gapsMin := expect.gapsMin.U
  bands.gapsMax := expect.gapsMax.U
  bands.maxRunMin := expect.maxRunMin.U
  bands.maxRunMax := expect.maxRunMax.U

  val laneOk = VecInit((0 until ClkRepair.Lanes).map { lane =>
    ClkRepairVerdict(meas.io.obs(lane), bands, ratioOk.B)
  })
  detector.io.status.done := meas.io.done
  detector.io.status.laneOk := laneOk
  io.laneOk := detector.io.interfaceIo.resp.bits.perLaneStatusBits
    .take(ClkRepair.Lanes)
}

class ClkRepairIntegrationTest extends AnyFunSpec with ChiselSim {
  val afeParams = new AfeParams()
  // A whole number of pattern periods at every ratio this exercises, short
  // enough to simulate in seconds.
  val windowWords: Int = ClkRepairExpect.alignedWindow(96)

  /** Runs one request through the seam and returns what came back: the three
    * status bits, whether the answer was offered before the requester asked for
    * it, and how many words the window counted.
    */
  def run(
      laneAlive: Seq[Boolean],
      n: Int = 4,
      ratioOk: Boolean = true
  ): (Seq[Boolean], Boolean, BigInt) = {
    var bits: Seq[Boolean] = Seq.empty
    var early = false
    var words = BigInt(0)
    simulate(
      new ClkRepairSeam(afeParams, laneAlive, n, windowWords, ratioOk)
    ) { c =>
      c.io.start.poke(false.B)
      c.io.done.poke(false.B)
      c.io.respReady.poke(false.B)
      c.clock.step(2)

      assert(c.io.reqReady.peek().litToBoolean, "detector was not idle")
      c.io.start.poke(true.B)
      c.clock.step()
      c.io.start.poke(false.B)

      // The window has to run on its own: the request opens it, and nothing
      // the requester does afterwards is what ends it.
      var ran = 0
      while (ran < windowWords + 16) {
        c.clock.step()
        ran += 1
      }
      words = c.io.wordsObserved.peek().litValue

      // The answer must NOT be offered yet. The MBInit responder drives its
      // sideband send straight off `resp.valid`, so offering a result before
      // the requester strobes `done` makes it transmit the RESULT response
      // ahead of the matching request and the exchange deadlocks. That is a
      // real bug this caught once already.
      early = c.io.respValid.peek().litToBoolean

      c.io.done.poke(true.B)
      c.clock.step()
      c.io.done.poke(false.B)
      c.clock.step(2)

      assert(c.io.respValid.peek().litToBoolean, "no answer after done")
      bits =
        (0 until ClkRepair.Lanes).map(l => c.io.laneOk(l).peek().litToBoolean)
      c.io.respReady.poke(true.B)
      c.clock.step()
      c.io.respReady.poke(false.B)
      c.clock.step()
      assert(
        c.io.reqReady.peek().litToBoolean,
        "detector did not return to idle after the answer was taken"
      )
    }
    (bits, early, words)
  }

  describe("the REPAIRCLK seam between the PHY and the controller") {
    it("reports every lane good when every lane carries the pattern") {
      val (bits, early, words) = run(Seq(true, true, true))
      assert(words == windowWords, s"window counted $words words")
      assert(!early, "the answer was offered before the requester asked")
      assert(bits == Seq(true, true, true), s"$bits")
    }

    it("reports only the dead lane when one lane carries nothing") {
      for (dead <- 0 until ClkRepair.Lanes) {
        val alive = Seq.tabulate(ClkRepair.Lanes)(_ != dead)
        val (bits, _, _) = run(alive)
        assert(bits == alive, s"lane $dead dead: got $bits, wanted $alive")
      }
    }

    it("holds the answer until the requester asks for it") {
      // The deadlock guard, stated on its own: the measurement finishes long
      // before `done` arrives, and the detector must sit on the result.
      val (_, early, words) = run(Seq(true, true, true))
      assert(words == windowWords)
      assert(!early)
    }

    it("passes no lane when the oversample ratio is unusable") {
      // Below two samples a UI the counters mean nothing, so a part left at
      // its reset clocking must fail rather than agree with the bands by luck.
      val (bits, _, _) = run(Seq(true, true, true), ratioOk = false)
      assert(bits == Seq(false, false, false), s"$bits")
    }

    it("works at every oversample ratio the dividers can produce") {
      for (n <- Seq(2, 4, 8)) {
        val (bits, _, words) = run(Seq(true, true, true), n = n)
        assert(words == windowWords, s"x$n counted $words")
        assert(bits == Seq(true, true, true), s"x$n: $bits")
      }
    }
  }
}
