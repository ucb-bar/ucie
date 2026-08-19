package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.ReferenceLFSR
import org.scalatest.funspec.AnyFunSpec

// ============================================================================
// Harness
// ============================================================================
// PatternReader plus the RX LFSR it controls, wired as LogicalPhy does. mbRxLaneIo
// is a plain input so the test can stream whatever a remote die would send, and
// rxLfsrCtrl is exposed so the LFSR control handshake can be observed.
class PatternReaderWithLfsrHarness(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val interfaceIo = new PatternReaderIO(afeParams.mbLanes)
    val mbRxLaneIo =
      Input(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
    val rxLfsrCtrl = new Bundle {
      val increment = Output(Bool())
      val resetLfsr = Output(Bool())
    }
  })

  val patternReader = Module(new PatternReader(afeParams))
  val rxLfsr = Module(new UcieLFSR(afeParams))

  patternReader.io.interfaceIo.req.valid := io.interfaceIo.req.valid
  patternReader.io.interfaceIo.req.bits := io.interfaceIo.req.bits
  io.interfaceIo.req.ready := patternReader.io.interfaceIo.req.ready
  patternReader.io.interfaceIo.done := io.interfaceIo.done
  patternReader.io.interfaceIo.remoteFuncLanes := io.interfaceIo.remoteFuncLanes
  io.interfaceIo.resp.valid := patternReader.io.interfaceIo.resp.valid
  io.interfaceIo.resp.bits := patternReader.io.interfaceIo.resp.bits
  patternReader.io.interfaceIo.resp.ready := io.interfaceIo.resp.ready

  patternReader.io.mbRxLaneIo := io.mbRxLaneIo

  patternReader.io.rxLfsrCtrl.pattern := rxLfsr.io.lfsrOutput
  rxLfsr.io.increment := VecInit(
    Seq.fill(afeParams.mbLanes)(patternReader.io.rxLfsrCtrl.increment)
  )
  rxLfsr.io.resetLfsr := VecInit(
    Seq.fill(afeParams.mbLanes)(patternReader.io.rxLfsrCtrl.resetLfsr)
  )

  io.rxLfsrCtrl.increment := patternReader.io.rxLfsrCtrl.increment
  io.rxLfsrCtrl.resetLfsr := patternReader.io.rxLfsrCtrl.resetLfsr
}

class PatternReaderTest extends AnyFunSpec with ChiselSim {

  // ==========================================================================
  // Test configuration (sweep serializer ratio x lane count)
  // ==========================================================================
  val serializerRatios = Seq(4, 8, 16, 32, 64)
  val laneCounts = Seq(16, 8)
  val numConsecutive =
    16 // Spec: 16 consecutive clean iterations needed to pass.
  val randomScenarios =
    20 // constrained-random scenarios per (serRatio, lane count); raise for long regressions.
  val randomSeed = 0x70617474L
  val printDebugEnable = false // Set true to print perlane results to screen.

  // ==========================================================================
  // LFSR reference parameters (match UcieLFSR defaults)
  // ==========================================================================
  val lfsrWidth = 23
  val polynomial = BigInt(0x210125)
  val laneSeeds = Seq(
    BigInt(0x1dbfbc),
    BigInt(0x0607bb),
    BigInt(0x1ec760),
    BigInt(0x18c0db),
    BigInt(0x010f12),
    BigInt(0x19cfc9),
    BigInt(0x0277ce),
    BigInt(0x1bb807)
  )

  // ==========================================================================
  // Pattern / lane helpers (mirror of PatternLaneMap)
  // ==========================================================================
  val laneMask = Map(
    0 -> 0x0000,
    1 -> 0x00ff,
    2 -> 0xff00,
    3 -> 0xffff,
    4 -> 0x000f,
    5 -> 0x00f0
  )
  val allLanes =
    3 // remoteFuncLanes "b011": every lane functional, for any lane count.

  def isActive(code: Int, lane: Int): Boolean =
    ((laneMask(code) >> lane) & 1) == 1
  // Physical lane -> logical (per-lane reference) index; the upper degraded maps shift down.
  def logicalLane(code: Int, lane: Int): Int = code match {
    case 2 => math.max(0, lane - 8)
    case 5 => math.max(0, lane - 4)
    case _ => lane
  }

  def patternWidth(p: PatternSelect.Type): Int =
    p match {
      case PatternSelect.CLKREPAIR => 48; case PatternSelect.VALTRAIN => 8;
      case _                       => 16
    }

  // remoteFuncLanes maps exercised for a given lane count: (label, code).
  def laneMapsFor(laneCount: Int): Seq[(String, Int)] = laneCount match {
    case 16 => Seq("all 16" -> 3, "lower 8" -> 1, "upper 8" -> 2, "none" -> 0)
    case 8  => Seq("lower 4" -> 4, "upper 4" -> 5, "none" -> 0)
    case _  => Seq("all" -> 3, "none" -> 0)
  }

  // ==========================================================================
  // Reference model + driver
  // ==========================================================================
  class RefModel(val serRatio: Int, val lanes: Int) {
    def params = AfeParams(mbSerializerRatio = serRatio, mbLanes = lanes)

    // ------------------------------------------------------------------------
    // Reference pattern words
    // ------------------------------------------------------------------------
    // Expand a repeating bit pattern into the serRatio-wide words sent each cycle.
    def repeatedPatternWords(
        pattern: BigInt,
        patternWidth: Int
    ): Seq[BigInt] = {
      val numPhases =
        patternWidth / BigInt(patternWidth).gcd(BigInt(serRatio)).toInt
      Seq.tabulate(numPhases) { phase =>
        Seq
          .tabulate(serRatio) { bit =>
            ((pattern >> ((phase * serRatio + bit) % patternWidth)) & 1) << bit
          }
          .foldLeft(BigInt(0))(_ | _)
      }
    }

    val clkRepairWords = repeatedPatternWords(BigInt("000055555555", 16), 48)
    val valTrainWords = repeatedPatternWords(BigInt("00001111", 2), 8)
    val perLaneIdWords = Seq.tabulate(lanes) { lane =>
      val pat = (BigInt("1010", 2) << 12) | (BigInt(lane & 0xff) << 4) | BigInt(
        "1010",
        2
      )
      repeatedPatternWords(pat, 16)
    }

    // Cycles to stream a given number of whole iterations.
    def cyclesFor(p: PatternSelect.Type, iterations: Int): Int =
      iterations * patternWidth(p) / serRatio

    def laneReferenceModels(): Seq[ReferenceLFSR] =
      Seq.tabulate(lanes)(lane =>
        new ReferenceLFSR(
          laneSeeds(lane % laneSeeds.length),
          polynomial,
          lfsrWidth
        )
      )

    // Lanes producing a meaningful result: clk lanes for CLKREPAIR, the valid lane
    // for VALTRAIN, the functional data lanes otherwise.
    def activeLanes(p: PatternSelect.Type, code: Int): Set[Int] = p match {
      case PatternSelect.CLKREPAIR => Set(0, 1, 2).filter(_ < lanes)
      case PatternSelect.VALTRAIN  => Set(0)
      case _ => (0 until lanes).filter(isActive(code, _)).toSet
    }

    // ------------------------------------------------------------------------
    // RX stimulus
    // ------------------------------------------------------------------------
    // Drive one cycle of the remote stream for a pattern. corruptData flips one bit,
    // blankData zeroes the data (gross error vs a non-zero reference); both apply only
    // to faultLanes (None = every active lane). corruptValid breaks the valid framing.
    // For CLKREPAIR, lanes 0/1/2 are clkP/clkN/trk; for VALTRAIN, lane 0 is the valid lane.
    def driveRxWord(
        dut: PatternReaderWithLfsrHarness,
        patternType: PatternSelect.Type,
        cycle: Int,
        refs: Seq[ReferenceLFSR],
        code: Int,
        corruptData: Boolean = false,
        corruptValid: Boolean = false,
        blankData: Boolean = false,
        faultLanes: Option[Set[Int]] = None
    ): Unit = {
      zeroLanes(dut)

      def flip(w: BigInt, corrupt: Boolean): BigInt =
        if (corrupt) w ^ BigInt(1) else w
      def faulted(lane: Int): Boolean = faultLanes.forall(_.contains(lane))
      // Perlane word: blank or bit-flip when this lane is faulted, else the reference.
      def laneWord(reference: BigInt, lane: Int): BigInt =
        if (blankData && faulted(lane)) BigInt(0)
        else flip(reference, corruptData && faulted(lane))

      patternType match {
        case PatternSelect.CLKREPAIR =>
          val w = clkRepairWords(cycle % clkRepairWords.length)
          dut.io.mbRxLaneIo.clkP.poke(laneWord(w, 0).U)
          dut.io.mbRxLaneIo.clkN.poke(laneWord(w, 1).U)
          dut.io.mbRxLaneIo.trk.poke(laneWord(w, 2).U)

        case PatternSelect.VALTRAIN =>
          val w = valTrainWords(cycle % valTrainWords.length)
          dut.io.mbRxLaneIo.valid
            .poke(flip(w, (corruptData && faulted(0)) || corruptValid).U)

        case PatternSelect.PERLANEID =>
          dut.io.mbRxLaneIo.valid.poke(
            flip(valTrainWords(cycle % valTrainWords.length), corruptValid).U
          )
          for (lane <- 0 until lanes if isActive(code, lane)) {
            val laneWords = perLaneIdWords(logicalLane(code, lane))
            dut.io.mbRxLaneIo
              .data(lane)
              .poke(laneWord(laneWords(cycle % laneWords.length), lane).U)
          }

        case PatternSelect.LFSR =>
          dut.io.mbRxLaneIo.valid.poke(
            flip(valTrainWords(cycle % valTrainWords.length), corruptValid).U
          )
          for (lane <- 0 until lanes if isActive(code, lane)) {
            val w = refs(logicalLane(code, lane)).peekOutputWord(serRatio)
            dut.io.mbRxLaneIo.data(lane).poke(laneWord(w, lane).U)
          }
      }
    }

    // Stream nCycles, applying the corruption flags to every cycle driven (so a
    // single bad cycle is nCycles = 1, a bad run is nCycles = N). Returns the next
    // cycle index so calls chain.
    def stream(
        dut: PatternReaderWithLfsrHarness,
        patternType: PatternSelect.Type,
        code: Int,
        nCycles: Int,
        start: Int,
        refs: Seq[ReferenceLFSR],
        corruptData: Boolean = false,
        corruptValid: Boolean = false,
        blankData: Boolean = false,
        faultLanes: Option[Set[Int]] = None
    ): Int = {
      var c = start
      for (_ <- 0 until nCycles) {
        driveRxWord(
          dut,
          patternType,
          c,
          refs,
          code,
          corruptData,
          corruptValid,
          blankData,
          faultLanes
        )
        dut.clock.step()
        if (patternType == PatternSelect.LFSR)
          refs.foreach(_.advanceState(serRatio))
        c += 1
      }
      c
    }

    // ------------------------------------------------------------------------
    // DUT helpers
    // ------------------------------------------------------------------------
    def zeroLanes(dut: PatternReaderWithLfsrHarness): Unit = {
      dut.io.mbRxLaneIo.valid.poke(0.U)
      dut.io.mbRxLaneIo.clkP.poke(0.U)
      dut.io.mbRxLaneIo.clkN.poke(0.U)
      dut.io.mbRxLaneIo.trk.poke(0.U)
      for (lane <- 0 until lanes) dut.io.mbRxLaneIo.data(lane).poke(0.U)
    }

    def printResult(
        dut: PatternReaderWithLfsrHarness,
        expectedPerLane: Int => Boolean,
        context: String
    ): Unit = {
      if (!printDebugEnable) return
      val agg = dut.io.interfaceIo.resp.bits.aggregateStatus.peek().litToBoolean
      println(s"[PatternReaderTest] ===== $context =====")
      println(
        f"[PatternReaderTest] aggregateStatus actual=$agg%-5s expected=${expectedPerLane(0)}%-5s"
      )
      for (lane <- 0 until lanes) {
        val actual = dut.io.interfaceIo.resp.bits
          .perLaneStatusBits(lane)
          .peek()
          .litToBoolean
        val expected = expectedPerLane(lane)
        val mark = if (actual == expected) "" else "  <-- mismatch"
        println(
          f"[PatternReaderTest] lane $lane%2d actual=$actual%-5s expected=$expected%-5s$mark"
        )
      }
    }

    def checkResult(
        dut: PatternReaderWithLfsrHarness,
        expectedPerLane: Int => Boolean,
        context: String
    ): Unit = {
      for (lane <- 0 until lanes)
        dut.io.interfaceIo.resp.bits
          .perLaneStatusBits(lane)
          .expect(expectedPerLane(lane).B, s"$context perLaneStatusBits($lane)")
      dut.io.interfaceIo.resp.bits.aggregateStatus
        .expect(expectedPerLane(0).B, s"$context aggregateStatus")
    }

    // End the window (done counts the final word), verify the held result survives
    // backpressure, then accept it and return to idle.
    def finishAndExpect(
        dut: PatternReaderWithLfsrHarness,
        expectedPerLane: Int => Boolean,
        context: String
    ): Unit = {
      dut.io.interfaceIo.done.poke(true.B)
      dut.clock.step() // done seen in sDetect -> sResult next cycle
      dut.io.interfaceIo.done.poke(false.B)

      dut.io.interfaceIo.resp.ready.poke(false.B)
      for (_ <- 0 until 2) { // result holds stable under backpressure
        dut.io.interfaceIo.resp.valid
          .expect(true.B, s"$context resp.valid held")
        checkResult(dut, expectedPerLane, context)
        dut.clock.step()
      }

      printResult(dut, expectedPerLane, context)
      dut.io.interfaceIo.resp.valid.expect(true.B, s"$context resp.valid")
      checkResult(dut, expectedPerLane, context)
      dut.io.interfaceIo.resp.ready.poke(true.B) // accept -> back to idle
      dut.clock.step()
      dut.io.interfaceIo.resp.ready.poke(false.B)
      dut.io.interfaceIo.req.ready.expect(true.B, s"$context returns to idle")
    }
  }

  // ==========================================================================
  // Request helpers
  // ==========================================================================
  def idleReq(dut: PatternReaderWithLfsrHarness): Unit = {
    dut.io.interfaceIo.req.valid.poke(false.B)
    dut.io.interfaceIo.done.poke(false.B)
    dut.io.interfaceIo.resp.ready.poke(false.B)
  }

  // Issue a request and consume the accepting cycle (returns with the DUT detecting,
  // and checks it now rejects further requests while busy).
  def startRequest(
      dut: PatternReaderWithLfsrHarness,
      patternType: PatternSelect.Type,
      comparisonMode: ComparisonMode.Type,
      errorThreshold: Int,
      doConsecutiveCount: Boolean,
      code: Int
  ): Unit = {
    dut.io.interfaceIo.remoteFuncLanes.poke(code.U)
    dut.io.interfaceIo.req.bits.patternType.poke(patternType)
    dut.io.interfaceIo.req.bits.comparisonMode.poke(comparisonMode)
    dut.io.interfaceIo.req.bits.errorThreshold.poke(errorThreshold.U)
    dut.io.interfaceIo.req.bits.doConsecutiveCount.poke(doConsecutiveCount.B)
    dut.io.interfaceIo.done.poke(false.B)
    dut.io.interfaceIo.resp.ready.poke(false.B)
    dut.io.interfaceIo.req.valid.poke(true.B)
    dut.io.interfaceIo.req.ready.expect(true.B, "request accepted when idle")
    dut.clock.step()
    dut.io.interfaceIo.req.valid.poke(false.B)
    dut.io.interfaceIo.req.ready
      .expect(false.B, "request rejected while detecting")
  }

  // ==========================================================================
  // Tests
  // ==========================================================================
  describe("PatternReader") {
    for (lanes <- laneCounts; ratio <- serializerRatios) {
      val ref = new RefModel(ratio, lanes)
      val tag = s"lanes=$lanes, serRatio=$ratio"

      it(s"consecutive: detects clean CLKREPAIR, VALTRAIN, PERLANEID ($tag)") {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          Seq(
            PatternSelect.CLKREPAIR,
            PatternSelect.VALTRAIN,
            PatternSelect.PERLANEID
          ).foreach { p =>
            startRequest(
              dut,
              p,
              ComparisonMode.PERLANE,
              errorThreshold = 0,
              doConsecutiveCount = true,
              allLanes
            )
            ref.stream(
              dut,
              p,
              allLanes,
              ref.cyclesFor(p, numConsecutive),
              0,
              Seq.empty
            )
            val active = ref.activeLanes(p, allLanes)
            ref.finishAndExpect(dut, lane => active.contains(lane), s"$p clean")
          }
        }
      }

      it(s"consecutive: a mid-stream error forfeits the run ($tag)") {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          // For each pattern: two clean runs of 8 iterations split by one bad iteration.
          // Neither side reaches 16 consecutive, so no lane ever passes. corruptData
          // hits clkP/clkN/trk for CLKREPAIR, the valid lane for VALTRAIN, the data for PERLANEID.
          Seq(
            PatternSelect.CLKREPAIR,
            PatternSelect.VALTRAIN,
            PatternSelect.PERLANEID
          ).foreach { p =>
            startRequest(
              dut,
              p,
              ComparisonMode.PERLANE,
              0,
              doConsecutiveCount = true,
              allLanes
            )
            var c = 0
            c = ref.stream(dut, p, allLanes, ref.cyclesFor(p, 8), c, Seq.empty)
            c = ref.stream(
              dut,
              p,
              allLanes,
              nCycles = 1,
              c,
              Seq.empty,
              corruptData = true
            )
            c = ref.stream(dut, p, allLanes, ref.cyclesFor(p, 8), c, Seq.empty)
            ref.finishAndExpect(dut, _ => false, s"$p forfeit")
          }
        }
      }

      it(s"consecutive: re-counts after an error and still passes ($tag)") {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          // A short clean run, one bad iteration, then a full clean run: the run after
          // the error reaches 16 and the active lanes pass.
          startRequest(
            dut,
            PatternSelect.PERLANEID,
            ComparisonMode.PERLANE,
            0,
            doConsecutiveCount = true,
            allLanes
          )
          var c = 0
          c = ref.stream(
            dut,
            PatternSelect.PERLANEID,
            allLanes,
            ref.cyclesFor(PatternSelect.PERLANEID, 8),
            c,
            Seq.empty
          )
          c = ref.stream(
            dut,
            PatternSelect.PERLANEID,
            allLanes,
            nCycles = 1,
            c,
            Seq.empty,
            corruptData = true
          )
          c = ref.stream(
            dut,
            PatternSelect.PERLANEID,
            allLanes,
            ref.cyclesFor(PatternSelect.PERLANEID, numConsecutive + 2),
            c,
            Seq.empty
          )
          val active = ref.activeLanes(PatternSelect.PERLANEID, allLanes)
          ref.finishAndExpect(
            dut,
            lane => active.contains(lane),
            "PERLANEID recount"
          )
        }
      }

      it(s"consecutive: bad valid framing forfeits the data lanes ($tag)") {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          // Data stays correct but the valid lane breaks framing for one cycle, which
          // forfeits the iteration on every data lane, so the run never reaches 16.
          startRequest(
            dut,
            PatternSelect.PERLANEID,
            ComparisonMode.PERLANE,
            0,
            doConsecutiveCount = true,
            allLanes
          )
          var c = 0
          c = ref.stream(
            dut,
            PatternSelect.PERLANEID,
            allLanes,
            ref.cyclesFor(PatternSelect.PERLANEID, 8),
            c,
            Seq.empty
          )
          c = ref.stream(
            dut,
            PatternSelect.PERLANEID,
            allLanes,
            nCycles = 1,
            c,
            Seq.empty,
            corruptValid = true
          )
          c = ref.stream(
            dut,
            PatternSelect.PERLANEID,
            allLanes,
            ref.cyclesFor(PatternSelect.PERLANEID, 8),
            c,
            Seq.empty
          )
          ref.finishAndExpect(dut, _ => false, "PERLANEID valid framing")
        }
      }

      it(s"error-count: passes within threshold and fails beyond it ($tag)") {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          // (a) Clean LFSR with a zero threshold passes on every lane.
          startRequest(
            dut,
            PatternSelect.LFSR,
            ComparisonMode.PERLANE,
            errorThreshold = 0,
            doConsecutiveCount = false,
            allLanes
          )
          ref.stream(
            dut,
            PatternSelect.LFSR,
            allLanes,
            nCycles = 12,
            0,
            ref.laneReferenceModels()
          )
          ref.finishAndExpect(dut, _ => true, "LFSR clean")

          // (b) One single-bit error per lane with threshold 2 stays within budget.
          startRequest(
            dut,
            PatternSelect.LFSR,
            ComparisonMode.PERLANE,
            errorThreshold = 2,
            doConsecutiveCount = false,
            allLanes
          )
          val refsB = ref.laneReferenceModels()
          var c = 0
          c =
            ref.stream(dut, PatternSelect.LFSR, allLanes, nCycles = 4, c, refsB)
          c = ref.stream(
            dut,
            PatternSelect.LFSR,
            allLanes,
            nCycles = 1,
            c,
            refsB,
            corruptData = true
          )
          c =
            ref.stream(dut, PatternSelect.LFSR, allLanes, nCycles = 4, c, refsB)
          ref.finishAndExpect(dut, _ => true, "LFSR within threshold")

          // (c) Gross errors (blank data vs non-zero reference) blow past threshold 4.
          startRequest(
            dut,
            PatternSelect.LFSR,
            ComparisonMode.PERLANE,
            errorThreshold = 4,
            doConsecutiveCount = false,
            allLanes
          )
          ref.stream(
            dut,
            PatternSelect.LFSR,
            allLanes,
            nCycles = 4,
            0,
            ref.laneReferenceModels(),
            blankData = true
          )
          ref.finishAndExpect(dut, _ => false, "LFSR beyond threshold")
        }
      }

      it(
        s"error-count: aggregate mode ORs lane mismatches onto lane 0 ($tag)"
      ) {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          // Clean aggregate: lane 0 (the aggregate result) passes.
          startRequest(
            dut,
            PatternSelect.LFSR,
            ComparisonMode.AGGREGATE,
            errorThreshold = 0,
            doConsecutiveCount = false,
            allLanes
          )
          ref.stream(
            dut,
            PatternSelect.LFSR,
            allLanes,
            nCycles = 12,
            0,
            ref.laneReferenceModels()
          )
          ref.finishAndExpect(dut, _ => true, "AGGREGATE clean")

          // Gross errors: the OR of all lanes lands on lane 0 and exceeds threshold.
          startRequest(
            dut,
            PatternSelect.LFSR,
            ComparisonMode.AGGREGATE,
            errorThreshold = 4,
            doConsecutiveCount = false,
            allLanes
          )
          ref.stream(
            dut,
            PatternSelect.LFSR,
            allLanes,
            nCycles = 4,
            0,
            ref.laneReferenceModels(),
            blankData = true
          )
          ref.finishAndExpect(
            dut,
            lane => lane != 0,
            "AGGREGATE error"
          ) // only lane 0 carries the aggregate
        }
      }

      it(s"respects the remote functional lane map ($tag)") {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          // Each lane map for this width, on both lane-mapped patterns.
          laneMapsFor(lanes).foreach { case (label, code) =>
            val active = ref.activeLanes(PatternSelect.PERLANEID, code)

            // PERLANEID consecutive: only the functional lanes reach 16 and pass.
            startRequest(
              dut,
              PatternSelect.PERLANEID,
              ComparisonMode.PERLANE,
              0,
              doConsecutiveCount = true,
              code
            )
            ref.stream(
              dut,
              PatternSelect.PERLANEID,
              code,
              ref.cyclesFor(PatternSelect.PERLANEID, numConsecutive),
              0,
              Seq.empty
            )
            ref.finishAndExpect(
              dut,
              lane => active.contains(lane),
              s"PERLANEID lane map $label"
            )

            // LFSR error-count: clean data on the logically-mapped functional lanes passes
            // everywhere; a wrong logical mapping would mis-compare the active lanes and fail them.
            startRequest(
              dut,
              PatternSelect.LFSR,
              ComparisonMode.PERLANE,
              errorThreshold = 0,
              doConsecutiveCount = false,
              code
            )
            ref.stream(
              dut,
              PatternSelect.LFSR,
              code,
              nCycles = 12,
              0,
              ref.laneReferenceModels()
            )
            ref.finishAndExpect(dut, _ => true, s"LFSR lane map $label")
          }
        }
      }

      it(s"drives the RX LFSR control handshake ($tag)") {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          // Idle: never advance the LFSR.
          dut.io.rxLfsrCtrl.increment.expect(false.B, "no increment while idle")

          // Requesting LFSR resets the LFSR on the accepting cycle.
          dut.io.interfaceIo.remoteFuncLanes.poke(allLanes.U)
          dut.io.interfaceIo.req.bits.patternType.poke(PatternSelect.LFSR)
          dut.io.interfaceIo.req.bits.comparisonMode
            .poke(ComparisonMode.PERLANE)
          dut.io.interfaceIo.req.bits.errorThreshold.poke(0.U)
          dut.io.interfaceIo.req.bits.doConsecutiveCount.poke(false.B)
          dut.io.interfaceIo.req.valid.poke(true.B)
          dut.io.rxLfsrCtrl.resetLfsr.expect(true.B, "reset on LFSR request")
          dut.clock.step()
          dut.io.interfaceIo.req.valid.poke(false.B)

          // Detecting LFSR advances it every cycle.
          val refs = ref.laneReferenceModels()
          var c = 0
          for (_ <- 0 until 3) {
            ref.driveRxWord(dut, PatternSelect.LFSR, c, refs, allLanes)
            dut.io.rxLfsrCtrl.increment
              .expect(true.B, "increment while detecting LFSR")
            dut.clock.step()
            refs.foreach(_.advanceState(ratio))
            c += 1
          }
          ref.finishAndExpect(dut, _ => true, "LFSR ctrl")

          // A non-LFSR pattern never advances the LFSR.
          startRequest(
            dut,
            PatternSelect.CLKREPAIR,
            ComparisonMode.PERLANE,
            0,
            doConsecutiveCount = true,
            allLanes
          )
          ref.driveRxWord(dut, PatternSelect.CLKREPAIR, 0, Seq.empty, allLanes)
          dut.io.rxLfsrCtrl.increment
            .expect(false.B, "no increment for CLKREPAIR")
          dut.clock.step()
          ref.finishAndExpect(
            dut,
            _ => false,
            "CLKREPAIR ctrl"
          ) // too short to reach 16
        }
      }

      it(s"per-lane: only the corrupted lanes fail ($tag)") {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          val active = ref.activeLanes(PatternSelect.PERLANEID, allLanes)
          val bad = active.filter(_ % 2 == 0) // corrupt the even lanes only

          // PERLANEID consecutive: the blanked lanes never reach 16, the clean lanes do.
          startRequest(
            dut,
            PatternSelect.PERLANEID,
            ComparisonMode.PERLANE,
            0,
            doConsecutiveCount = true,
            allLanes
          )
          ref.stream(
            dut,
            PatternSelect.PERLANEID,
            allLanes,
            ref.cyclesFor(PatternSelect.PERLANEID, numConsecutive),
            0,
            Seq.empty,
            blankData = true,
            faultLanes = Some(bad)
          )
          ref.finishAndExpect(
            dut,
            lane => active.contains(lane) && !bad.contains(lane),
            "perlane consecutive"
          )

          // LFSR error-count: the blanked lanes exceed the threshold, the clean lanes stay at 0.
          startRequest(
            dut,
            PatternSelect.LFSR,
            ComparisonMode.PERLANE,
            errorThreshold = 0,
            doConsecutiveCount = false,
            allLanes
          )
          ref.stream(
            dut,
            PatternSelect.LFSR,
            allLanes,
            nCycles = 8,
            0,
            ref.laneReferenceModels(),
            blankData = true,
            faultLanes = Some(bad)
          )
          ref.finishAndExpect(
            dut,
            lane => !bad.contains(lane),
            "perlane error-count"
          )
        }
      }

      it(s"constrained-random per-lane outcomes ($tag)") {
        simulate(new PatternReaderWithLfsrHarness(ref.params)) { dut =>
          val rng =
            new scala.util.Random(randomSeed + ratio.toLong * 131 + lanes)
          idleReq(dut); ref.zeroLanes(dut); dut.clock.step()

          // Functional lane maps for this width (exclude "none" so there is something to pass).
          val codes = laneMapsFor(lanes)
            .map(_._2)
            .filter(c => ref.activeLanes(PatternSelect.PERLANEID, c).nonEmpty)

          for (_ <- 0 until randomScenarios) {
            val code = codes(rng.nextInt(codes.length))
            val active = ref.activeLanes(PatternSelect.PERLANEID, code)
            val bad = active.filter(_ =>
              rng.nextBoolean()
            ) // random subset of active lanes blanked

            if (rng.nextBoolean()) {
              // PERLANEID consecutive: clean active lanes pass, blanked ones fail.
              startRequest(
                dut,
                PatternSelect.PERLANEID,
                ComparisonMode.PERLANE,
                0,
                doConsecutiveCount = true,
                code
              )
              ref.stream(
                dut,
                PatternSelect.PERLANEID,
                code,
                ref.cyclesFor(PatternSelect.PERLANEID, numConsecutive),
                0,
                Seq.empty,
                blankData = true,
                faultLanes = Some(bad)
              )
              ref.finishAndExpect(
                dut,
                lane => active.contains(lane) && !bad.contains(lane),
                "random consecutive"
              )
            } else {
              // LFSR error-count: blanked active lanes exceed the threshold; everything else passes.
              val threshold = rng.nextInt(5)
              startRequest(
                dut,
                PatternSelect.LFSR,
                ComparisonMode.PERLANE,
                threshold,
                doConsecutiveCount = false,
                code
              )
              ref.stream(
                dut,
                PatternSelect.LFSR,
                code,
                nCycles = 8,
                0,
                ref.laneReferenceModels(),
                blankData = true,
                faultLanes = Some(bad)
              )
              ref.finishAndExpect(
                dut,
                lane => !(active.contains(lane) && bad.contains(lane)),
                "random error-count"
              )
            }
          }
        }
      }
    }
  }
}
