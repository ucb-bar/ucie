/*
  Sends the mainband training patterns, continuous or in bursts (sec 4.5.1.1).
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import chisel3.util._

// A named Bundle, so a clock crossing can carry it as a payload.
class PatternWriterReq extends Bundle {
  val patternType = PatternSelect()
}

class PatternBurstIO extends Bundle {
  val enable = Input(Bool())
  val burstCount = Input(UInt(16.W))
  val idleCount = Input(UInt(16.W))
  val iterationCount = Input(UInt(16.W))
}

class PatternWriterIO extends Bundle {
  val req = Flipped(Decoupled(new PatternWriterReq))
  val resp = new Bundle {
    val complete = Output(Bool())
  }
}

class PatternWriter(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val interfaceIo = new PatternWriterIO
    val mbTxLaneIo = Decoupled(
      new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
    )
    val quarterRate = Input(Bool())
    val patternDrivesValid = Output(Bool())
    val burst = new PatternBurstIO
    val txLfsrCtrl = new Bundle {
      val pattern =
        Input(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
      val increment = Output(Bool())
      val resetLfsr = Output(Bool())
      val valid = Output(Bool())
    }
  })

  // ===========================================================================
  // Parameters and helpers
  // ===========================================================================
  val serRatio = afeParams.mbSerializerRatio
  require(
    serRatio > 0,
    "PatternWriter requires a positive mainband serializer ratio"
  )
  require(
    Seq(8, 16).contains(afeParams.mbLanes),
    "PatternWriter supports spec-defined 8 or 16 mainband lanes"
  )

  // Burst mode divides the UI counts by the serializer ratio with a shift.
  require(
    isPow2(serRatio),
    s"PatternWriter requires a power-of-two serializer ratio, got $serRatio"
  )
  val serRatioLog = log2Ceil(serRatio)

  val numIter = 128 // Iterations sent for the finite training patterns

  def exactNumCycles(patternName: String, numBits: Int): Int = {
    // The patterns go out with no padding.
    require(
      numBits % serRatio == 0,
      s"PatternWriter $patternName pattern length $numBits must be divisible by serializer ratio $serRatio"
    )
    numBits / serRatio
  }

  // Words before the pattern of one field starts at a word boundary again.
  def patternNumPhases(patternWidth: Int): Int =
    patternWidth / BigInt(patternWidth).gcd(BigInt(serRatio)).toInt

  def lcm(a: Int, b: Int): Int = a / BigInt(a).gcd(BigInt(b)).toInt * b

  def selectPatternWord(words: Seq[UInt], phase: UInt): UInt = {
    require(
      words.nonEmpty,
      "PatternWriter pattern look up must contain at least one word"
    )

    if (words.length == 1) {
      words.head
    } else {
      // An index modulo this length stays aligned at any ratio.
      MuxLookup(phase, words.head)(
        Seq.tabulate(maxPatternPhases) { idx =>
          idx.U(patternPhaseWidth.W) -> words(idx % words.length)
        }
      )
    }
  }

  // ===========================================================================
  // Pattern references and phase
  // ===========================================================================
  val clkPatternWidth = PatternDefs.clkRepairWidth
  val clkRepairPattern = PatternDefs.clkRepair
  val clkRepairPatternWords =
    PatternDefs.repeatedPatternWords(
      clkRepairPattern,
      clkPatternWidth,
      serRatio
    )
  val clkRepairPatternNumCycles =
    exactNumCycles("CLKREPAIR", numIter * clkPatternWidth)

  val valTrainPattern = PatternDefs.valTrain
  val valTrainWidth = PatternDefs.valTrainWidth
  val valTrainPatternWords =
    PatternDefs.repeatedPatternWords(valTrainPattern, valTrainWidth, serRatio)
  val valTrainNumCycles = exactNumCycles("VALTRAIN", numIter * valTrainWidth)

  val perLanePatternWidth = PatternDefs.perLaneIdWidth
  val perLaneIdPatternWords = Seq.tabulate(afeParams.mbLanes) { lane =>
    PatternDefs.repeatedPatternWords(
      PatternDefs.perLaneId(lane),
      perLanePatternWidth,
      serRatio
    )
  }
  val perLaneNumCycles =
    exactNumCycles("PERLANEID", numIter * perLanePatternWidth)

  val lfsrNumBits = 4096
  val lfsrNumCycles = exactNumCycles("LFSR", lfsrNumBits)

  val fwClkPatternWidth = ForwardedClock.TileUI
  val fwClkPHalfWords =
    PatternDefs.repeatedPatternWords(
      ForwardedClock.clkPTile(false),
      fwClkPatternWidth,
      serRatio
    )
  val fwClkNHalfWords =
    PatternDefs.repeatedPatternWords(
      ForwardedClock.clkNTile(false),
      fwClkPatternWidth,
      serRatio
    )
  val fwClkPQuarterWords =
    PatternDefs.repeatedPatternWords(
      ForwardedClock.clkPTile(true),
      fwClkPatternWidth,
      serRatio
    )
  val fwClkNQuarterWords =
    PatternDefs.repeatedPatternWords(
      ForwardedClock.clkNTile(true),
      fwClkPatternWidth,
      serRatio
    )

  // A shared phase counter wraps at the LCM of the fields' phase counts.
  val clkRepairPhases = patternNumPhases(clkPatternWidth)
  val fwClkAndValidPhases =
    lcm(patternNumPhases(valTrainWidth), patternNumPhases(fwClkPatternWidth))
  val valTrainPatternPhases = fwClkAndValidPhases
  val perLanePatternPhases =
    lcm(patternNumPhases(perLanePatternWidth), fwClkAndValidPhases)
  val lfsrPatternPhases = fwClkAndValidPhases

  val maxPatternPhases = Seq(
    clkRepairPhases,
    valTrainPatternPhases,
    perLanePatternPhases,
    lfsrPatternPhases
  ).max
  val patternPhaseWidth = log2Ceil(math.max(2, maxPatternPhases))
  val patternPhase = RegInit(0.U(patternPhaseWidth.W))

  val largestCycleCount = Seq(
    clkRepairPatternNumCycles,
    valTrainNumCycles,
    perLaneNumCycles,
    lfsrNumCycles
  ).max

  // Fits the longest fixed pattern and the longest 16-bit Burst Count in words.
  val cycleCountWidth =
    math.max(log2Ceil(math.max(2, largestCycleCount)), 16 - serRatioLog)
  val cycleCount = RegInit(0.U(cycleCountWidth.W))

  // ===========================================================================
  // State
  // ===========================================================================
  val inProgress = RegInit(false.B)
  val patternTypeReg = RegInit(PatternSelect.CLKREPAIR)

  // Latched at the request, so the burst-end compare reads a register.
  val burstLimit = RegInit(0.U(cycleCountWidth.W))
  val idleLimit = RegInit(0.U(cycleCountWidth.W))
  val hasIdleSpan = RegInit(false.B)
  val itersLeft = RegInit(0.U(16.W))
  val inIdleSpan = RegInit(false.B)
  val idleCounter = RegInit(0.U(cycleCountWidth.W))

  val patternPhaseLimit =
    MuxLookup(patternTypeReg, (clkRepairPhases - 1).U(patternPhaseWidth.W))(
      Seq(
        PatternSelect.CLKREPAIR -> (clkRepairPhases - 1).U,
        PatternSelect.VALTRAIN -> (valTrainPatternPhases - 1).U,
        PatternSelect.PERLANEID -> (perLanePatternPhases - 1).U,
        PatternSelect.LFSR -> (lfsrPatternPhases - 1).U
      )
    )

  // ===========================================================================
  // Requests and bursts
  // ===========================================================================
  val reqPatternType = io.interfaceIo.req.bits.patternType

  // Table 7-11 cannot name the clock repair pattern, so it has no burst mode.
  val burstSelected =
    io.burst.enable && (reqPatternType =/= PatternSelect.CLKREPAIR)

  val reqBurstWords = io.burst.burstCount >> serRatioLog
  val reqIdleWords = io.burst.idleCount >> serRatioLog
  val reqPatternCycles = MuxLookup(
    reqPatternType,
    clkRepairPatternNumCycles.U(cycleCountWidth.W)
  )(
    Seq(
      PatternSelect.CLKREPAIR -> clkRepairPatternNumCycles.U,
      PatternSelect.VALTRAIN -> valTrainNumCycles.U,
      PatternSelect.PERLANEID -> perLaneNumCycles.U,
      PatternSelect.LFSR -> lfsrNumCycles.U
    )
  )

  // Whole words cover VALTRAIN, so only Per Lane ID adds a sec 4.5.1.1 check.
  val burstCountWholeWords = io.burst.burstCount(serRatioLog - 1, 0) === 0.U
  val idleCountWholeWords = io.burst.idleCount(serRatioLog - 1, 0) === 0.U
  val burstCountWholePatterns =
    (reqPatternType =/= PatternSelect.PERLANEID) ||
      (io.burst.burstCount(3, 0) === 0.U)

  val burstConfigValid =
    (io.burst.burstCount =/= 0.U) && (io.burst.iterationCount =/= 0.U) &&
      burstCountWholeWords && idleCountWholeWords && burstCountWholePatterns

  // Refuse the burst, because a padded one matches no reference.
  // The substate then stalls until sec 4.5's timeout sets Error Log 1 bit 8.
  val burstConfigRefused = burstSelected && !burstConfigValid

  io.interfaceIo.req.ready := !inProgress && !burstConfigRefused

  val atBurstEnd = cycleCount === burstLimit
  val lastIteration = itersLeft <= 1.U
  val finalPatternWord = atBurstEnd && lastIteration

  // An idle span holds the phase, as the partner's reader does (sec 5.11).
  when(io.mbTxLaneIo.fire && !inIdleSpan) {
    when(patternPhase === patternPhaseLimit) {
      patternPhase := 0.U
    }.otherwise {
      patternPhase := patternPhase + 1.U
    }
  }

  // These connects come last, so their resets win over the phase advance above.
  when(io.interfaceIo.req.fire) {
    inProgress := true.B
    patternTypeReg := reqPatternType
    burstLimit := Mux(burstSelected, reqBurstWords, reqPatternCycles) - 1.U
    val idleWords = Mux(burstSelected, reqIdleWords, 0.U)
    hasIdleSpan := idleWords =/= 0.U
    idleLimit := Mux(idleWords =/= 0.U, idleWords - 1.U, 0.U)
    itersLeft := Mux(burstSelected, io.burst.iterationCount, 1.U)
    cycleCount := 0.U
    patternPhase := 0.U
    idleCounter := 0.U
    inIdleSpan := false.B
  }.elsewhen(io.mbTxLaneIo.fire) {
    when(inIdleSpan) {
      when(idleCounter === idleLimit) {
        inIdleSpan := false.B
        idleCounter := 0.U
        cycleCount := 0.U
      }.otherwise {
        idleCounter := idleCounter + 1.U
      }
    }.elsewhen(atBurstEnd) {
      when(lastIteration) {
        inProgress := false.B
        cycleCount := 0.U
        patternPhase := 0.U
      }.otherwise {
        itersLeft := itersLeft - 1.U
        inIdleSpan := hasIdleSpan
        idleCounter := 0.U
        when(!hasIdleSpan) {
          cycleCount := 0.U
        }
      }
    }.otherwise {
      cycleCount := cycleCount + 1.U
    }
  }

  // ===========================================================================
  // Output words
  // ===========================================================================
  val clkRepairWord = selectPatternWord(clkRepairPatternWords, patternPhase)
  val valTrainWord = selectPatternWord(valTrainPatternWords, patternPhase)
  val fwClkPWord = Mux(
    io.quarterRate,
    selectPatternWord(fwClkPQuarterWords, patternPhase),
    selectPatternWord(fwClkPHalfWords, patternPhase)
  )
  val fwClkNWord = Mux(
    io.quarterRate,
    selectPatternWord(fwClkNQuarterWords, patternPhase),
    selectPatternWord(fwClkNHalfWords, patternPhase)
  )

  io.mbTxLaneIo.bits := 0.U.asTypeOf(chiselTypeOf(io.mbTxLaneIo.bits))
  io.mbTxLaneIo.valid := inProgress
  io.patternDrivesValid := patternTypeReg =/= PatternSelect.CLKREPAIR

  io.interfaceIo.resp.complete :=
    io.mbTxLaneIo.fire && !inIdleSpan && finalPatternWord

  io.txLfsrCtrl.valid := inProgress && (patternTypeReg === PatternSelect.LFSR)
  io.txLfsrCtrl.resetLfsr :=
    io.interfaceIo.req.fire && (io.interfaceIo.req.bits.patternType === PatternSelect.LFSR)

  // Hold the LFSR through an idle span, as the partner's reader does.
  io.txLfsrCtrl.increment :=
    inProgress && (patternTypeReg === PatternSelect.LFSR) && io.mbTxLaneIo.fire &&
      !inIdleSpan && !finalPatternWord

  switch(patternTypeReg) {
    is(PatternSelect.CLKREPAIR) {
      io.mbTxLaneIo.bits.clkP := clkRepairWord
      io.mbTxLaneIo.bits.clkN := clkRepairWord
      io.mbTxLaneIo.bits.trk := clkRepairWord
    }
    is(PatternSelect.VALTRAIN) {
      io.mbTxLaneIo.bits.valid := valTrainWord
      io.mbTxLaneIo.bits.clkP := fwClkPWord
      io.mbTxLaneIo.bits.clkN := fwClkNWord
    }
    is(PatternSelect.PERLANEID) {
      for (lane <- 0 until afeParams.mbLanes) {
        io.mbTxLaneIo.bits.data(lane) :=
          selectPatternWord(perLaneIdPatternWords(lane), patternPhase)
      }
      io.mbTxLaneIo.bits.valid := valTrainWord
      io.mbTxLaneIo.bits.clkP := fwClkPWord
      io.mbTxLaneIo.bits.clkN := fwClkNWord
    }
    is(PatternSelect.LFSR) {
      for (lane <- 0 until afeParams.mbLanes) {
        io.mbTxLaneIo.bits.data(lane) := io.txLfsrCtrl.pattern(lane)
      }
      io.mbTxLaneIo.bits.valid := valTrainWord
      io.mbTxLaneIo.bits.clkP := fwClkPWord
      io.mbTxLaneIo.bits.clkN := fwClkNWord
    }
  }

  // The clock keeps toggling in the gap, because the partner receives on it.
  when(inIdleSpan) {
    io.mbTxLaneIo.bits.data.foreach(_ := 0.U)
    io.mbTxLaneIo.bits.valid := 0.U
  }

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(io.interfaceIo.req.fire) |=>
          Sequence.BoolSequence(inProgress),
        label = Some("PatternWriterReqFireStartsPattern")
      )
      AssertProperty(
        Sequence.BoolSequence(io.mbTxLaneIo.valid && !io.mbTxLaneIo.ready) |=>
          Sequence.BoolSequence(io.mbTxLaneIo.valid),
        label = Some("PatternWriterStaysValidUnderBackpressure")
      )

      AssertProperty(
        Sequence.BoolSequence(
          io.mbTxLaneIo.fire && !inIdleSpan && finalPatternWord
        ) |=>
          Sequence.BoolSequence(!inProgress),
        label = Some("PatternWriterFinalFireClearsInProgress")
      )
      AssertProperty(
        Sequence.BoolSequence(cycleCount <= burstLimit),
        label = Some("PatternWriterCycleCountWithinPattern")
      )

      // Note: burstConfigRefused is the hardware that enforces this property.
      // Do not route a request around it.
      AssertProperty(
        Sequence.BoolSequence(io.interfaceIo.req.fire) |->
          Sequence.BoolSequence(!burstConfigRefused),
        label = Some("PatternWriterBurstStartsOnlyWithAValidConfig")
      )

      AssertProperty(
        Sequence.BoolSequence(inIdleSpan) |->
          Sequence.BoolSequence(
            io.mbTxLaneIo.bits.valid === 0.U &&
              io.mbTxLaneIo.bits.data.map(_ === 0.U).reduce(_ && _)
          ),
        label = Some("PatternWriterIdleSpanCarriesNoPattern")
      )

      AssertProperty(
        Sequence.BoolSequence(
          !(io.txLfsrCtrl.resetLfsr && io.txLfsrCtrl.increment)
        ),
        label = Some("PatternWriterLfsrResetAndIncrementMutuallyExclusive")
      )

      AssertProperty(
        Sequence.BoolSequence(io.interfaceIo.resp.complete) |=>
          Sequence.BoolSequence(!io.interfaceIo.resp.complete),
        label = Some("PatternWriterCompleteIsOneCyclePulse")
      )

      // The Chisel 7 LTL assertions have no $stable for UInt.
      val heldLastCycle =
        RegNext(io.mbTxLaneIo.valid && !io.mbTxLaneIo.ready, false.B)
      val previousOutputBits = RegNext(io.mbTxLaneIo.bits.asUInt)
      val previousCycleCount = RegNext(cycleCount)
      val previousPatternPhase = RegNext(patternPhase)
      val previousPatternType = RegNext(patternTypeReg)
      val previousIdleCounter = RegNext(idleCounter)
      val previousInIdleSpan = RegNext(inIdleSpan, false.B)

      AssertProperty(
        Sequence.BoolSequence(heldLastCycle) |->
          Sequence.BoolSequence(
            io.mbTxLaneIo.bits.asUInt === previousOutputBits &&
              cycleCount === previousCycleCount &&
              patternPhase === previousPatternPhase &&
              patternTypeReg === previousPatternType &&
              idleCounter === previousIdleCounter &&
              inIdleSpan === previousInIdleSpan
          ),
        label = Some("PatternWriterHoldsStateAndBitsUnderBackpressure")
      )
    }
  }
}
