package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.ReferenceLFSR
import org.scalatest.funspec.AnyFunSpec
import scala.util.Random

// ============================================================================
// Harness
// ============================================================================
class PatternWriterWithLfsrHarness(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val interfaceIo = new PatternWriterIO
    val mbTxLaneIo = Decoupled(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
    val txLfsrCtrl = new Bundle {
      val valid = Output(Bool())
      val resetLfsr = Output(Bool())
      val increment = Output(Bool())
    }
  })

  val patternWriter = Module(new PatternWriter(afeParams))
  val txLfsr = Module(new UcieLFSR(afeParams))

  patternWriter.io.interfaceIo.req.valid := io.interfaceIo.req.valid
  patternWriter.io.interfaceIo.req.bits := io.interfaceIo.req.bits
  io.interfaceIo.req.ready := patternWriter.io.interfaceIo.req.ready
  io.interfaceIo.resp.complete := patternWriter.io.interfaceIo.resp.complete

  io.mbTxLaneIo.valid := patternWriter.io.mbTxLaneIo.valid
  io.mbTxLaneIo.bits := patternWriter.io.mbTxLaneIo.bits
  patternWriter.io.mbTxLaneIo.ready := io.mbTxLaneIo.ready

  patternWriter.io.txLfsrCtrl.pattern := txLfsr.io.lfsrOutput
  txLfsr.io.increment := VecInit(Seq.fill(afeParams.mbLanes)(patternWriter.io.txLfsrCtrl.increment))
  txLfsr.io.resetLfsr := VecInit(Seq.fill(afeParams.mbLanes)(patternWriter.io.txLfsrCtrl.resetLfsr))

  io.txLfsrCtrl.valid := patternWriter.io.txLfsrCtrl.valid
  io.txLfsrCtrl.resetLfsr := patternWriter.io.txLfsrCtrl.resetLfsr
  io.txLfsrCtrl.increment := patternWriter.io.txLfsrCtrl.increment
}

class PatternWriterTest extends AnyFunSpec with ChiselSim {

  // ==========================================================================
  // Test configuration
  // ==========================================================================
  val serializerRatios = Seq(4, 8, 16, 32, 64)
  val lanes = 16
  val maxReadyLowCycles = 3
  val randomSeed = 0x70617474L
  val printDebug = false // Set true to see output to screen

  // ==========================================================================
  // LFSR reference parameters
  // ==========================================================================
  val lfsrWidth = 23
  val polynomial = BigInt(0x210125)
  val laneSeeds = Seq(
    BigInt(0x1DBFBC),
    BigInt(0x0607BB),
    BigInt(0x1EC760),
    BigInt(0x18C0DB),
    BigInt(0x010F12),
    BigInt(0x19CFC9),
    BigInt(0x0277CE),
    BigInt(0x1BB807)
  )

  // ==========================================================================
  // Scoreboard types
  // ==========================================================================
  // One cycle of expected mainband output.
  case class ExpectedMainband(
    data: Seq[BigInt],
    valid: BigInt,
    clkP: BigInt,
    clkN: BigInt,
    trk: BigInt
  )

  // One field of the mainband output paired with its expected value.
  case class ScoreboardRow(field: String, actual: Bits, expected: BigInt)

  // ==========================================================================
  // Reference model
  // ==========================================================================
  // Holds all ser. ratio dependent reference state and the stimulus driver
  class RefModel(val serializerRatio: Int) {
    def params = AfeParams(mbSerializerRatio = serializerRatio, mbLanes = lanes)

    // ------------------------------------------------------------------------
    // Reference pattern words
    // ------------------------------------------------------------------------
    // Expands a repeating bit pattern into the sequence of ser. ratio wide
    // words the PatternWriter is expected to output per fire
    def repeatedPatternWords(pattern: BigInt, patternWidth: Int): Seq[BigInt] = {
      val commonDivisor = BigInt(patternWidth).gcd(BigInt(serializerRatio)).toInt
      val numPhases = patternWidth / commonDivisor

      Seq.tabulate(numPhases) { phase =>
        Seq.tabulate(serializerRatio) { bit =>
          ((pattern >> ((phase * serializerRatio + bit) % patternWidth)) & 1) << bit
        }.foldLeft(BigInt(0))(_ | _)
      }
    }

    val clkRepairPatternWords = repeatedPatternWords(BigInt("000055555555", 16), 48)
    val valTrainPatternWords = repeatedPatternWords(BigInt("00001111", 2), 8)
    val fwClkPPatternWords = repeatedPatternWords(BigInt("01010101", 2), 8)
    val fwClkNPatternWords = repeatedPatternWords(BigInt("10101010", 2), 8)
    val perLaneIdPatternWords = Seq.tabulate(lanes) { lane =>
      val perLaneIdPattern =
        (BigInt("1010", 2) << 12) | (BigInt(lane & 0xff) << 4) | BigInt("1010", 2)
      repeatedPatternWords(perLaneIdPattern, 16)
    }

    // ------------------------------------------------------------------------
    // Cycle counts
    // ------------------------------------------------------------------------
    def exactNumCycles(patternName: String, numBits: Int): Int = {
      require(
        numBits % serializerRatio == 0,
        s"$patternName has $numBits bits, which is not divisible by serializer ratio $serializerRatio"
      )
      numBits / serializerRatio
    }

    val clkRepairCycles = exactNumCycles("CLKREPAIR", 128 * 48)
    val valTrainCycles = exactNumCycles("VALTRAIN", 128 * 8)
    val perLaneIdCycles = exactNumCycles("PERLANEID", 128 * 16)
    val lfsrCycles = exactNumCycles("LFSR", 4096)

    // ------------------------------------------------------------------------
    // Reference LFSR model
    // ------------------------------------------------------------------------
    def laneReferenceModels(): Seq[ReferenceLFSR] =
      Seq.tabulate(lanes) { lane =>
        new ReferenceLFSR(laneSeeds(lane % laneSeeds.length), polynomial, lfsrWidth)
      }

    // ------------------------------------------------------------------------
    // Expected output
    // ------------------------------------------------------------------------
    // Expected mainband output for the given pattern on a given fire.
    def expectedOutput(patternType: PatternSelect.Type, fireCount: Int, refs: Seq[ReferenceLFSR]): ExpectedMainband = {
      patternType match {
        case PatternSelect.CLKREPAIR =>
          val clkRepairWord = clkRepairPatternWords(fireCount % clkRepairPatternWords.length)
          ExpectedMainband(
            data = Seq.fill(lanes)(BigInt(0)),
            valid = BigInt(0),
            clkP = clkRepairWord,
            clkN = clkRepairWord,
            trk = clkRepairWord
          )

        case PatternSelect.VALTRAIN =>
          ExpectedMainband(
            data = Seq.fill(lanes)(BigInt(0)),
            valid = valTrainPatternWords(fireCount % valTrainPatternWords.length),
            clkP = fwClkPPatternWords(fireCount % fwClkPPatternWords.length),
            clkN = fwClkNPatternWords(fireCount % fwClkNPatternWords.length),
            trk = BigInt(0)
          )

        case PatternSelect.PERLANEID =>
          ExpectedMainband(
            data = Seq.tabulate(lanes) { lane =>
              perLaneIdPatternWords(lane)(fireCount % perLaneIdPatternWords(lane).length)
            },
            valid = valTrainPatternWords(fireCount % valTrainPatternWords.length),
            clkP = fwClkPPatternWords(fireCount % fwClkPPatternWords.length),
            clkN = fwClkNPatternWords(fireCount % fwClkNPatternWords.length),
            trk = BigInt(0)
          )

        case PatternSelect.LFSR =>
          ExpectedMainband(
            data = refs.map(_.peekOutputWord(serializerRatio)),
            valid = valTrainPatternWords(fireCount % valTrainPatternWords.length),
            clkP = fwClkPPatternWords(fireCount % fwClkPPatternWords.length),
            clkN = fwClkNPatternWords(fireCount % fwClkNPatternWords.length),
            trk = BigInt(0)
          )
      }
    }

    // ------------------------------------------------------------------------
    // DUT stimulus helpers
    // ------------------------------------------------------------------------
    def clearRequest(dut: PatternWriterWithLfsrHarness): Unit = {
      dut.io.interfaceIo.req.valid.poke(false.B)
      dut.io.interfaceIo.req.bits.patternType.poke(PatternSelect.CLKREPAIR)
    }

    // Spuriously asserts req.valid mid-pattern and checks the DUT ignores it
    // (req.ready stays low while busy).
    def randomlyPulseRequestWhileBusy(dut: PatternWriterWithLfsrHarness, random: Random, context: String): Unit = {
      val pulseBusyRequest = random.nextBoolean()
      dut.io.interfaceIo.req.valid.poke(pulseBusyRequest.B)
      dut.io.interfaceIo.req.bits.patternType.poke(PatternSelect.LFSR)
      dut.io.interfaceIo.req.ready.expect(false.B, s"$context req.ready while busy")
    }

    // ------------------------------------------------------------------------
    // Scoreboard
    // ------------------------------------------------------------------------
    // The mainband output fields paired with their expected values.
    def scoreboardRows(dut: PatternWriterWithLfsrHarness, expected: ExpectedMainband): Seq[ScoreboardRow] =
      Seq(
        ScoreboardRow("valid", dut.io.mbTxLaneIo.bits.valid, expected.valid),
        ScoreboardRow("clkP", dut.io.mbTxLaneIo.bits.clkP, expected.clkP),
        ScoreboardRow("clkN", dut.io.mbTxLaneIo.bits.clkN, expected.clkN),
        ScoreboardRow("trk", dut.io.mbTxLaneIo.bits.trk, expected.trk)
      ) ++ expected.data.zipWithIndex.map {
        case (word, lane) => ScoreboardRow(s"data[$lane]", dut.io.mbTxLaneIo.bits.data(lane), word)
      }

    // Checks every mainband field against the reference, optionally printing it.
    def checkScoreboard(
      dut: PatternWriterWithLfsrHarness,
      patternName: String,
      fireCount: Int,
      totalCycles: Int,
      expected: ExpectedMainband,
      context: String,
      printThisCycle: Boolean
    ): Unit = {
      val rows = scoreboardRows(dut, expected)

      if (printDebug && printThisCycle) {
        printScoreboard(dut, patternName, fireCount, totalCycles, rows)
      }

      rows.foreach { row =>
        row.actual.expect(row.expected.U, s"$context ${row.field} expected ${hexWord(row.expected)}")
      }
    }

    // Checks the LFSR control signals match LFSR-vs-non-LFSR mode expectations.
    def expectLfsrCtrl(
      dut: PatternWriterWithLfsrHarness,
      isLfsrPattern: Boolean,
      requestCycle: Boolean,
      txFire: Boolean,
      finalFire: Boolean,
      context: String
    ): Unit = {
      dut.io.txLfsrCtrl.valid.expect(
        (isLfsrPattern && !requestCycle).B,
        s"$context txLfsrCtrl.valid"
      )
      dut.io.txLfsrCtrl.resetLfsr.expect(
        (isLfsrPattern && requestCycle).B,
        s"$context txLfsrCtrl.resetLfsr"
      )
      dut.io.txLfsrCtrl.increment.expect(
        (isLfsrPattern && !requestCycle && txFire && !finalFire).B,
        s"$context txLfsrCtrl.increment"
      )
    }

    // ------------------------------------------------------------------------
    // Debug printing
    // ------------------------------------------------------------------------
    def hexWord(value: BigInt): String = {
      val hexDigits = (serializerRatio + 3) / 4
      "0x" + value.toString(16).reverse.padTo(hexDigits, '0').reverse
    }

    def padRight(value: String, width: Int): String =
      value + (" " * math.max(0, width - value.length))

    def printScoreboard(
      dut: PatternWriterWithLfsrHarness,
      patternName: String,
      fireCount: Int,
      totalCycles: Int,
      rows: Seq[ScoreboardRow]
    ): Unit = {
      val fieldColumnWidth = math.max(10, rows.map(_.field.length).max)
      val valueColumnWidth = math.max(10, hexWord(BigInt(0)).length)
      val divider = "=" * (fieldColumnWidth + (2 * valueColumnWidth) + 24)
      val separator = "-" * (fieldColumnWidth + (2 * valueColumnWidth) + 24)

      println(s"[PatternWriterTest] $divider")
      println(s"[PatternWriterTest] $patternName (serRatio=$serializerRatio) fire ${fireCount + 1}/$totalCycles")
      println(
        s"[PatternWriterTest] complete=${dut.io.interfaceIo.resp.complete.peek().litToBoolean} " +
          s"lfsrValid=${dut.io.txLfsrCtrl.valid.peek().litToBoolean} " +
          s"lfsrReset=${dut.io.txLfsrCtrl.resetLfsr.peek().litToBoolean} " +
          s"lfsrIncrement=${dut.io.txLfsrCtrl.increment.peek().litToBoolean}"
      )
      println(s"[PatternWriterTest] $separator")
      println(
        s"[PatternWriterTest] ${padRight("field", fieldColumnWidth)} ${padRight("actual", valueColumnWidth)} " +
          s"${padRight("expected", valueColumnWidth)}"
      )
      println(s"[PatternWriterTest] $separator")
      rows.foreach { row =>
        val actualValue = row.actual.peek().litValue
        println(
          s"[PatternWriterTest] ${padRight(row.field, fieldColumnWidth)} " +
            s"${padRight(hexWord(actualValue), valueColumnWidth)} ${padRight(hexWord(row.expected), valueColumnWidth)}"
        )
      }
    }

    // ------------------------------------------------------------------------
    // Pattern driver
    // ------------------------------------------------------------------------
    // Drives one full pattern: requests it, then fires every cycle while
    // injecting random TX backpressure and spurious requests, checking the
    // mainband output and LFSR control signals throughout.
    def runPattern(
      dut: PatternWriterWithLfsrHarness,
      patternName: String,
      patternType: PatternSelect.Type,
      totalCycles: Int,
      random: Random
    ): Unit = {
      val isLfsrPattern = patternName == "LFSR"
      val refs = laneReferenceModels()

      // Request the pattern.
      dut.io.interfaceIo.req.bits.patternType.poke(patternType)
      dut.io.interfaceIo.req.valid.poke(true.B)
      dut.io.interfaceIo.req.ready.expect(true.B, s"$patternName request req.ready")
      dut.io.mbTxLaneIo.valid.expect(false.B, s"$patternName request mbTxLaneIo.valid")
      dut.io.interfaceIo.resp.complete.expect(false.B, s"$patternName request resp.complete")
      expectLfsrCtrl(dut, isLfsrPattern, requestCycle = true, txFire = false, finalFire = false, s"$patternName request")
      dut.clock.step()
      clearRequest(dut)

      // Fire the pattern for the expected number of cycles.
      for (fireCount <- 0 until totalCycles) {
        val readyLowCycles = random.nextInt(maxReadyLowCycles + 1)
        val expected = expectedOutput(patternType, fireCount, refs)

        // Hold mbTxLaneIo.ready low for a random number of cycles, then fire.
        for (attempt <- 0 to readyLowCycles) {
          val txFire = attempt == readyLowCycles
          val finalFire = txFire && (fireCount == totalCycles - 1)
          val context =
            if (txFire) s"$patternName fire $fireCount"
            else s"$patternName fire $fireCount stall $attempt"

          dut.io.mbTxLaneIo.ready.poke(txFire.B)
          randomlyPulseRequestWhileBusy(dut, random, context)
          dut.io.mbTxLaneIo.valid.expect(true.B, s"$context valid")
          dut.io.interfaceIo.resp.complete.expect(finalFire.B, s"$context complete")
          checkScoreboard(
            dut,
            patternName,
            fireCount,
            totalCycles,
            expected,
            context,
            printThisCycle = txFire // prints when there's no backpressure
          )
          expectLfsrCtrl(dut, isLfsrPattern, requestCycle = false, txFire = txFire, finalFire = finalFire, context)
          dut.clock.step()
          clearRequest(dut)
        }

        if (isLfsrPattern && fireCount != totalCycles - 1) {
          refs.foreach(_.advanceState(serializerRatio))
        }
      }

      // Pattern complete: DUT should be idle and ready for the next request.
      dut.io.mbTxLaneIo.ready.poke(false.B)
      clearRequest(dut)
      dut.io.interfaceIo.req.ready.expect(true.B, s"$patternName done req.ready")
      dut.io.mbTxLaneIo.valid.expect(false.B, s"$patternName done mbTxLaneIo.valid")
      dut.io.interfaceIo.resp.complete.expect(false.B, s"$patternName done resp.complete")
      expectLfsrCtrl(dut, isLfsrPattern = false, requestCycle = false, txFire = false, finalFire = false, s"$patternName done")
    }
  }

  // ==========================================================================
  // Tests
  // ==========================================================================
  describe("PatternWriter") {
    serializerRatios.foreach { ratio =>
      val refModel = new RefModel(ratio)

      it(s"writes CLKREPAIR with randomized request delay and TX backpressure (serRatio=$ratio)") {
        simulate(new PatternWriterWithLfsrHarness(refModel.params)) { dut =>
          val random = new Random(randomSeed)

          refModel.clearRequest(dut)
          dut.io.mbTxLaneIo.ready.poke(false.B)

          refModel.runPattern(dut, "CLKREPAIR", PatternSelect.CLKREPAIR, refModel.clkRepairCycles, random)
        }
      }

      it(s"writes VALTRAIN with randomized request delay and TX backpressure (serRatio=$ratio)") {
        simulate(new PatternWriterWithLfsrHarness(refModel.params)) { dut =>
          val random = new Random(randomSeed)

          refModel.clearRequest(dut)
          dut.io.mbTxLaneIo.ready.poke(false.B)

          refModel.runPattern(dut, "VALTRAIN", PatternSelect.VALTRAIN, refModel.valTrainCycles, random)
        }
      }

      it(s"writes PERLANEID with randomized request delay and TX backpressure (serRatio=$ratio)") {
        simulate(new PatternWriterWithLfsrHarness(refModel.params)) { dut =>
          val random = new Random(randomSeed)

          refModel.clearRequest(dut)
          dut.io.mbTxLaneIo.ready.poke(false.B)

          refModel.runPattern(dut, "PERLANEID", PatternSelect.PERLANEID, refModel.perLaneIdCycles, random)
        }
      }

      it(s"writes LFSR with randomized request delay and TX backpressure (serRatio=$ratio)") {
        simulate(new PatternWriterWithLfsrHarness(refModel.params)) { dut =>
          val random = new Random(randomSeed)

          refModel.clearRequest(dut)
          dut.io.mbTxLaneIo.ready.poke(false.B)

          refModel.runPattern(dut, "LFSR", PatternSelect.LFSR, refModel.lfsrCycles, random)
        }
      }
    }
  }
}
