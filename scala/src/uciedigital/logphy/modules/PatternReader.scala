/*
  Description:
    PatternReader takes care of pattern comparison done during training of the mainband.
    The RX mainband lanes (data, valid, clkN, clkP, track) will enter and depending on the
    control signals from the LTSM, it will do the appropriate pattern comparison

  NOTE:
  * Pattern lookup tables are used for patterns whose alignment phase can change with the
    mainband serializer ratio.
*/

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import chisel3.util._

class PatternReaderIO(numMbLanes: Int) extends Bundle {
  val req = Flipped(Decoupled(new Bundle {
    val patternType = PatternSelect()     // Which pattern to detect
    val comparisonMode = ComparisonMode() // Which type of error detection to do
    val errorThreshold = UInt(16.W)       // Max amount before failure
    val doConsecutiveCount = Bool()       // Whether to detect consecutive patterns or not
  }))
  val done = Input(Bool())                // Requester ends the detection window
  val remoteFuncLanes = Input(UInt(3.W))
  val resp = Decoupled(new Bundle {
    val perLaneStatusBits = Vec(numMbLanes, Bool())
    val aggregateStatus = Bool()
  })
}

class PatternReader(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val interfaceIo = new PatternReaderIO(afeParams.mbLanes)
    val rxLfsrCtrl = new Bundle {
      val increment = Output(Bool())
      val resetLfsr = Output(Bool())
      val pattern = Input(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
    }
    val mbRxLaneIo = Input(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
  })

  // ==========================================================================
  // Parameters & helpers
  // ==========================================================================
  val serRatio = afeParams.mbSerializerRatio
  require(serRatio > 0, "PatternReader requires a positive mainband serializer ratio")

  val errCountWidth = 16
  val counterMax = ((BigInt(1) << errCountWidth) - 1).U(errCountWidth.W)
  val mismatchCountWidth = log2Ceil(serRatio + 1)
  val numConsecutive = 16 // Spec requires at least 16 consecutive detections

  // ==========================================================================
  // Consecutive detection helpers (iteration aligned)
  // ==========================================================================
  // Partition a phase's serRatio bit word into segments that each belong to one
  // patternWidth bit iteration (a boundary can fall mid-word); marks the 
  // segment holding an iter's final bit as the one that "closes" it. Returns
  // (loBit, hiBit, closesIteration) covering [0, serRatio); emits no hardware.
  def iterationSegments(patternWidth: Int, phase: Int): Seq[(Int, Int, Boolean)] = {
    val segs = scala.collection.mutable.ListBuffer.empty[(Int, Int, Boolean)]
    var lo = 0
    for (b <- 0 until serRatio) {
      val closesIteration = ((phase * serRatio + b) % patternWidth) == (patternWidth - 1)
      if (closesIteration) {
        segs += ((lo, b, true))
        lo = b + 1
      }
    }
    if (lo <= serRatio - 1) { segs += ((lo, serRatio - 1, false)) }
    segs.toSeq
  }

  // Fold a word's segments, any bad bit dirties the in-progress iteration, and 
  // each closing segment commits +1 if clean or resets to 0; freezes at the 
  // target so a later mismatch can't undo a pass.
  def foldWordIterations(
    xorWord: UInt,
    validFrameBad: Bool,
    segs: Seq[(Int, Int, Boolean)],
    prevDirty: Bool,
    prevCount: UInt,
    target: UInt
  ): (UInt, Bool) = {
    var dirty: Bool = prevDirty
    var count: UInt = prevCount    
    // Count ripples through the closing segments in one cycle. If there are many
    // closing segs (large serRatio, narrow pattern width), there are many iters 
    // in one cycle, so many closing segs. Each adds a mux/comp/inc to the 
    // ripple. Can fix by pipelining, or doing the computation in parallel --
    // should run PD to see.
    for ((lo, hi, closes) <- segs) {
      val running = dirty || validFrameBad || xorWord(hi, lo).orR
      if (closes) {
        // +1 per clean close, reset on dirty; freeze at the target
        count = Mux(count === target, count, Mux(running, 0.U, count + 1.U))
        dirty = false.B
      } else {
        dirty = running
      }
    }
    (count, dirty)
  }

  // Next (count, dirty) for one pattern at the active phase: phases that close
  // an iter fold the word to update the count; others just hold it and extend
  // dirtiness. Only closing phases enter the select, so HW cost scales with 
  // iters per period, not numPhases -- efficient for small serializer ratios.
  def consecutiveIterNext(
    patternWidth: Int,
    numPhases: Int,
    phaseReg: UInt,
    xorWord: UInt,
    validFrameBad: Bool,
    prevDirty: Bool,
    prevCount: UInt,
    target: UInt
  ): (UInt, Bool) = {
    if (numPhases == 1) {
      foldWordIterations(xorWord, validFrameBad, iterationSegments(patternWidth, 0), prevDirty, prevCount, target)
    } else {
      val closingPhases = (0 until numPhases).filter(p => iterationSegments(patternWidth, p).exists(_._3))
      val closingFolds = closingPhases.map { p =>
        p -> foldWordIterations(xorWord, validFrameBad, iterationSegments(patternWidth, p), prevDirty, prevCount, target)
      }  
      val nonClosingCount = prevCount
      val nonClosingDirty = prevDirty || validFrameBad || xorWord.orR
      val selectedCount = MuxLookup(phaseReg, nonClosingCount)(
        closingFolds.map { case (p, (count, _)) => p.U -> count }
      )
      val selectedDirty = MuxLookup(phaseReg, nonClosingDirty)(
        closingFolds.map { case (p, (_, dirty)) => p.U -> dirty }
      )
      (selectedCount, selectedDirty)
    }
  }

  def repeatedPatternWords(pattern: BigInt, patternWidth: Int): Seq[UInt] = {
    val numPhases = patternWidth / BigInt(patternWidth).gcd(BigInt(serRatio)).toInt

    Seq.tabulate(numPhases) { phase =>
      VecInit(Seq.tabulate(serRatio) { bit =>
        ((pattern >> (phase * serRatio + bit) % patternWidth) & 1).U(1.W)
      }).asUInt
    }
  }

  def selectPatternWord(words: Seq[UInt], phase: UInt, phaseWidth: Int): UInt = {
    require(words.nonEmpty, "PatternReader pattern lookup must contain at least one word")

    if (words.length == 1) {
      words.head
    } else {
      MuxLookup(phase, words.head)(
        words.zipWithIndex.map {
          case (word, idx) => idx.U(phaseWidth.W) -> word
        }
      )
    }
  }

  // ==========================================================================
  // Pattern references & phase tracking
  // ==========================================================================
  // Each pattern repeats over the serializer ratio; a free-running phase counter
  // reconstructs the reference each cycle.

  // Clock Repair: clkP, clkN and trk all carry the same repeating pattern;
  // single phase counter reconstructs the reference for all three.
  val clkPatternWidth = 48
  val clkRepairPattern = BigInt("000055555555", 16)
  val clkRepairPatternWords = repeatedPatternWords(clkRepairPattern, clkPatternWidth)
  val clkRepairPhaseWidth = log2Ceil(math.max(2, clkRepairPatternWords.length))
  val clkPhaseReg = RegInit(0.U(clkRepairPhaseWidth.W))
  val clkRepairPhaseLimit = (clkRepairPatternWords.length - 1).U(clkRepairPhaseWidth.W)
  val clkRepairRefPattern = selectPatternWord(clkRepairPatternWords, clkPhaseReg, clkRepairPhaseWidth)

  // Valtrain: pattern carried on the valid lane during VALTRAIN/PERLANEID/LFSR.
  val valTrainWidth = 8
  val valTrainPattern = BigInt("00001111", 2)
  val valTrainPatternWords = repeatedPatternWords(valTrainPattern, valTrainWidth)
  val validPhaseWidth = log2Ceil(math.max(2, valTrainPatternWords.length))
  val validPhaseReg = RegInit(0.U(validPhaseWidth.W))
  val validPhaseLimit = (valTrainPatternWords.length - 1).U(validPhaseWidth.W)
  val valTrainRefPattern = selectPatternWord(valTrainPatternWords, validPhaseReg, validPhaseWidth)

  // PerLane ID: perlane data pattern.
  val perLanePatternWidth = 16
  val perLaneIdPatternWords = Seq.tabulate(afeParams.mbLanes) { lane =>
    val perLaneIdPattern =
      (BigInt("1010", 2) << 12) | (BigInt(lane & 0xff) << 4) | BigInt("1010", 2)
    repeatedPatternWords(perLaneIdPattern, perLanePatternWidth)
  }
  val perLaneIdPhaseWidth = log2Ceil(math.max(2, perLaneIdPatternWords.head.length))
  val perLaneIdPhaseReg = RegInit(0.U(perLaneIdPhaseWidth.W))
  val perLaneIdPhaseLimit = (perLaneIdPatternWords.head.length - 1).U(perLaneIdPhaseWidth.W)
  val perLaneIdRefPattern = VecInit(
    perLaneIdPatternWords.map(words => selectPatternWord(words, perLaneIdPhaseReg, perLaneIdPhaseWidth))
  )

  // ==========================================================================
  // Control / state
  // ==========================================================================
  val sIdle :: sDetect :: sResult :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val patternTypeReg = RegInit(PatternSelect.CLKREPAIR)
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE)
  val doConsecutiveCountReg = RegInit(false.B)
  // Holds the error threshold, or (in consecutive mode) the required number of
  // consecutive clean iterations.
  val errorThresholdReg = RegInit(0.U(errCountWidth.W))

  // In error-count mode this accumulates mismatches; in consecutive mode it counts
  // consecutive clean pattern iterations.
  val patternCounterReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(errCountWidth.W))))
  // Consecutive mode: sticky dirtiness of the curr iter in progress on
  // each lane (carried across the cycles an iteration spans).
  val iterDirtyReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(false.B)))
  val laneMap = PatternLaneMap.decodeLaneMap(io.interfaceIo.remoteFuncLanes, afeParams.mbLanes)

  // Count only while detecting.
  // Result is stable until the requester accepts it via resp.fire.
  val counterEn = state === sDetect

  io.interfaceIo.req.ready := state === sIdle
  io.interfaceIo.resp.valid := state === sResult

  switch(state) {
    is(sIdle) {
      when(io.interfaceIo.req.fire) {
        state := sDetect
        patternTypeReg := io.interfaceIo.req.bits.patternType
        comparisonModeReg := io.interfaceIo.req.bits.comparisonMode
        doConsecutiveCountReg := io.interfaceIo.req.bits.doConsecutiveCount
        patternCounterReg.foreach(x => x := 0.U)
        iterDirtyReg.foreach(x => x := false.B)
        clkPhaseReg := 0.U
        validPhaseReg := 0.U
        perLaneIdPhaseReg := 0.U

        // Reuse errorThresholdReg to hold the consecutive clean iter target.
        when(io.interfaceIo.req.bits.doConsecutiveCount) {
          errorThresholdReg := numConsecutive.U
        }.otherwise {
          errorThresholdReg := io.interfaceIo.req.bits.errorThreshold
        }
      }
    }
    is(sDetect) {
      when(io.interfaceIo.done) { state := sResult }
    }
    is(sResult) {
      when(io.interfaceIo.resp.fire) { state := sIdle }
    }
  }

  // Need to free run pattern phase counter so the phase can wrap back around
  when(counterEn && (patternTypeReg === PatternSelect.CLKREPAIR)) {
    clkPhaseReg := Mux(clkPhaseReg === clkRepairPhaseLimit, 0.U, clkPhaseReg + 1.U)
  }
  // The valid lane carries the valtrain pattern for every non-clock-repair pattern.
  when(counterEn && (patternTypeReg =/= PatternSelect.CLKREPAIR)) {
    validPhaseReg := Mux(validPhaseReg === validPhaseLimit, 0.U, validPhaseReg + 1.U)
  }
  when(counterEn && (patternTypeReg === PatternSelect.PERLANEID)) {
    perLaneIdPhaseReg := Mux(perLaneIdPhaseReg === perLaneIdPhaseLimit, 0.U, perLaneIdPhaseReg + 1.U)
  }

  // Pipeline data path to meet timing (2 stages)  
  // ==========================================================================
  // Comparison datapath (stage 1)
  // ==========================================================================
  val logicalLane = VecInit(Seq.tabulate(afeParams.mbLanes) { i =>
    PatternLaneMap.activeLaneIndex(io.interfaceIo.remoteFuncLanes, i, afeParams.mbLanes)})
  val remotePattern = Wire(Vec(afeParams.mbLanes, UInt(serRatio.W)))
  val localPattern = Wire(Vec(afeParams.mbLanes, UInt(serRatio.W)))
  remotePattern.foreach(_ := 0.U)
  localPattern.foreach(_ := 0.U)
  
  when(counterEn) {
    switch(patternTypeReg) {
      is(PatternSelect.CLKREPAIR) {
        remotePattern(0) := io.mbRxLaneIo.clkP
        localPattern(0)  := clkRepairRefPattern
        remotePattern(1) := io.mbRxLaneIo.clkN
        localPattern(1)  := clkRepairRefPattern
        remotePattern(2) := io.mbRxLaneIo.trk
        localPattern(2)  := clkRepairRefPattern
      }
      is(PatternSelect.VALTRAIN) {
        remotePattern(0) := io.mbRxLaneIo.valid
        localPattern(0)  := valTrainRefPattern
      }
      is(PatternSelect.PERLANEID) {
        remotePattern.zipWithIndex.foreach { case (res, i) =>
          res := Mux(laneMap(i), io.mbRxLaneIo.data(i), 0.U)
        }
        localPattern.zipWithIndex.foreach { case (res, i) =>
          res := Mux(laneMap(i), perLaneIdRefPattern(logicalLane(i)), 0.U)
        }
      }
      is(PatternSelect.LFSR) {
        remotePattern.zipWithIndex.foreach { case (res, i) =>
          res := Mux(laneMap(i), io.mbRxLaneIo.data(i), 0.U)
        }
        localPattern.zipWithIndex.foreach { case (res, i) =>
          res := Mux(laneMap(i), io.rxLfsrCtrl.pattern(logicalLane(i)), 0.U)
        }
      }
    }
  }

  val xorResult = VecInit(Seq.tabulate(afeParams.mbLanes)(i => remotePattern(i) ^ localPattern(i)))

  // Valid lane needs to carry valtrain for every non-clock-repair pattern.
  // Used to detect if there is valid data on the data lanes.
  val validBad = (patternTypeReg =/= PatternSelect.CLKREPAIR) && (io.mbRxLaneIo.valid =/= valTrainRefPattern)

  // Aggregate mode ORs the perlane mismatches; held at 0 in perlane mode so the
  // aggregate PopCount doesn't toggle when unused.
  val aggregateOrResult = Wire(UInt(serRatio.W))
  aggregateOrResult := 0.U
  when(comparisonModeReg === ComparisonMode.AGGREGATE) {
    aggregateOrResult := xorResult.reduceTree(_ | _)
  }

  // Need to popcount the XOR of the reference and local when serRatio > 1.
  val popCountResult = Wire(Vec(afeParams.mbLanes, UInt(mismatchCountWidth.W)))
  when(comparisonModeReg === ComparisonMode.AGGREGATE) {
    popCountResult(0) := PopCount(aggregateOrResult)
    for (i <- 1 until afeParams.mbLanes) { popCountResult(i) := 0.U }
  }.otherwise { // ComparisonMode.PERLANE
    popCountResult.zipWithIndex.foreach { case (res, i) => res := PopCount(xorResult(i)) }
  }

  // Lanes that carry a meaningful comparison for the current pattern and mode. In
  // aggregate mode the result lands on lane 0; otherwise it is the pattern's active
  // lane set. Reused to target valid-framing errors and to gate the counters.
  val laneActive = Wire(Vec(afeParams.mbLanes, Bool()))
  for (i <- 0 until afeParams.mbLanes) {
    laneActive(i) := false.B
    when(comparisonModeReg === ComparisonMode.AGGREGATE) {
      laneActive(i) := (if (i == 0) true.B else false.B)
    }.otherwise {
      switch(patternTypeReg) {
        is(PatternSelect.CLKREPAIR) { laneActive(i) := (if (i < 3) true.B else false.B) }
        is(PatternSelect.VALTRAIN)  { laneActive(i) := (if (i == 0) true.B else false.B) }
        is(PatternSelect.PERLANEID) { laneActive(i) := laneMap(i) }
        is(PatternSelect.LFSR)      { laneActive(i) := laneMap(i) }
      }
    }
  }

  // Perlane mismatch count for error-count mode, gated by counterEn so the
  // pipeline fill word from the idle cycle before detection is a no-op.
  val effectivePopCountResult = Wire(Vec(afeParams.mbLanes, UInt(mismatchCountWidth.W)))
  effectivePopCountResult.zipWithIndex.foreach { case (res, i) =>
    res := Mux(
      counterEn && validBad,
      Mux(laneActive(i), 1.U(mismatchCountWidth.W), 0.U(mismatchCountWidth.W)),
      popCountResult(i)
    )
  }

  // Pipeline registers: error-count mode uses the popcount;
  // consecutive mode uses the XOR, valid-framing, and phase regs.
  val popCountPipe = RegNext(effectivePopCountResult)
  val xorPipe = RegNext(xorResult)
  val validBadPipe = RegNext(validBad)
  val clkPhasePipe = RegNext(clkPhaseReg)
  val validPhasePipe = RegNext(validPhaseReg)
  val perLaneIdPhasePipe = RegNext(perLaneIdPhaseReg)
  // High once the pipeline holds a real detect word; ignore the idle fill word.
  val detectPipeValid = RegNext(counterEn, false.B)

  // ==========================================================================
  // Error / consecutive counters (stage 2)
  // ==========================================================================
  // Consecutive mode: pass once the clean iteration count reaches the target.
  // Error-count mode: pass while within budget.
  val patternCompStatus = VecInit(patternCounterReg.map { count =>
    Mux(doConsecutiveCountReg,
        count === errorThresholdReg,
        count <= errorThresholdReg)
  })

  when(counterEn) {
    // Iteration aligned consecutive counting.
    when(doConsecutiveCountReg) {
      when(detectPipeValid) {
        for (i <- 0 until afeParams.mbLanes) {
          val xorWord = xorPipe(i)
          // Every lane can carry PERLANEID.
          val (perLaneCount, perLaneDirty) = consecutiveIterNext(
            perLanePatternWidth, perLaneIdPatternWords.head.length, perLaneIdPhasePipe,
            xorWord, validBadPipe, iterDirtyReg(i), patternCounterReg(i), errorThresholdReg)

          // Per lane, only fold the patterns that lane can actually carry, 
          // then pick the active one with patternTypeReg.
          val (nextCount, nextDirty) =
            if (i == 0) {
              // Lane 0: clkP (CLKREPAIR), the valid lane (VALTRAIN), or PERLANEID.
              val (clkCount, clkDirty) = consecutiveIterNext(
                clkPatternWidth, clkRepairPatternWords.length, clkPhasePipe,
                xorWord, false.B, iterDirtyReg(i), patternCounterReg(i), errorThresholdReg)
              val (valCount, valDirty) = consecutiveIterNext(
                valTrainWidth, valTrainPatternWords.length, validPhasePipe,
                xorWord, validBadPipe, iterDirtyReg(i), patternCounterReg(i), errorThresholdReg)
              (MuxLookup(patternTypeReg, patternCounterReg(i))(Seq(
                  PatternSelect.CLKREPAIR -> clkCount,
                  PatternSelect.VALTRAIN  -> valCount,
                  PatternSelect.PERLANEID -> perLaneCount)),
               MuxLookup(patternTypeReg, iterDirtyReg(i))(Seq(
                  PatternSelect.CLKREPAIR -> clkDirty,
                  PatternSelect.VALTRAIN  -> valDirty,
                  PatternSelect.PERLANEID -> perLaneDirty)))
            } else if (i == 1 || i == 2) {
              // Lanes 1-2: clkN/trk (CLKREPAIR) or PERLANEID.
              val (clkCount, clkDirty) = consecutiveIterNext(
                clkPatternWidth, clkRepairPatternWords.length, clkPhasePipe,
                xorWord, false.B, iterDirtyReg(i), patternCounterReg(i), errorThresholdReg)
              (MuxLookup(patternTypeReg, patternCounterReg(i))(Seq(
                  PatternSelect.CLKREPAIR -> clkCount,
                  PatternSelect.PERLANEID -> perLaneCount)),
               MuxLookup(patternTypeReg, iterDirtyReg(i))(Seq(
                  PatternSelect.CLKREPAIR -> clkDirty,
                  PatternSelect.PERLANEID -> perLaneDirty)))
            } else {
              // Lanes >2: PERLANEID only.
              (perLaneCount, perLaneDirty)
            }

          // Inactive lanes hold (stay at 0). Counting freezes inside the fold once
          // the target is met, so an active lane just tracks nextCount/nextDirty.
          when(laneActive(i)) {
            patternCounterReg(i) := nextCount
            iterDirtyReg(i) := nextDirty
          }
        }
      }
    }.otherwise {
      for (i <- 0 until afeParams.mbLanes) {
        // Error-count mode: sum mismatches, saturate preventing overflow.
        val errorSum = patternCounterReg(i) +& popCountPipe(i)
        val errorCount = Mux(errorSum(errCountWidth), counterMax, errorSum(errCountWidth - 1, 0))
        // Active lanes accumulate until they exceed the threshold, then freeze.
        when(laneActive(i) && patternCompStatus(i)) {
          patternCounterReg(i) := errorCount
        }
      }
    }
  }

  // ==========================================================================
  // Outputs
  // ==========================================================================
  io.interfaceIo.resp.bits.perLaneStatusBits.zipWithIndex.foreach { case (res, i) =>
    res := patternCompStatus(i)
  }
  // Only meaningful in aggregate mode; in perlane mode read perLaneStatusBits.
  io.interfaceIo.resp.bits.aggregateStatus := patternCompStatus(0)

  io.rxLfsrCtrl.increment := counterEn && (patternTypeReg === PatternSelect.LFSR)
  io.rxLfsrCtrl.resetLfsr :=
    io.interfaceIo.req.fire && (io.interfaceIo.req.bits.patternType === PatternSelect.LFSR)

  // ==========================================================================
  // Assertions
  // ==========================================================================
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        // Need to be in perlane mode when consecutive counting.
        Sequence.BoolSequence(io.interfaceIo.req.fire && io.interfaceIo.req.bits.doConsecutiveCount) |->
          Sequence.BoolSequence(io.interfaceIo.req.bits.comparisonMode === ComparisonMode.PERLANE),
        label = Some("PatternReaderConsecutiveCountRequiresPerLane")
      )

      AssertProperty(
        // Spec doesn't have consecutive count with LFSR pattern.
        Sequence.BoolSequence(io.interfaceIo.req.fire && io.interfaceIo.req.bits.doConsecutiveCount) |->
          Sequence.BoolSequence(io.interfaceIo.req.bits.patternType =/= PatternSelect.LFSR),
        label = Some("PatternReaderConsecutiveCountDoesNotSupportLfsr")
      )

      // Idle (accepting) XOR holding a result.
      AssertProperty(
        Sequence.BoolSequence(!(io.interfaceIo.req.ready && io.interfaceIo.resp.valid)),
        label = Some("PatternReaderReadyAndRespValidExclusive")
      )

      // Held result must stay asserted and stable until the requester accepts it.
      val respStalled = RegNext(io.interfaceIo.resp.valid && !io.interfaceIo.resp.ready, false.B)
      val previousRespBits = RegNext(io.interfaceIo.resp.bits.asUInt)
      AssertProperty(
        Sequence.BoolSequence(respStalled) |->
          Sequence.BoolSequence(
            io.interfaceIo.resp.valid && (io.interfaceIo.resp.bits.asUInt === previousRespBits)
          ),
        label = Some("PatternReaderRespHeldUnderBackpressure")
      )

      // When detection is running, asserting done produces the result next cycle.
      AssertProperty(
        Sequence.BoolSequence(
          !io.interfaceIo.req.ready && !io.interfaceIo.resp.valid && io.interfaceIo.done
        ) |=> Sequence.BoolSequence(io.interfaceIo.resp.valid),
        label = Some("PatternReaderDoneProducesResult")
      )

      // Toggling done HIGH while idle must not fabricate a result.
      AssertProperty(
        Sequence.BoolSequence(io.interfaceIo.req.ready && io.interfaceIo.done) |=>
          Sequence.BoolSequence(!io.interfaceIo.resp.valid),
        label = Some("PatternReaderIdleDoneIsInert")
      )

      // Never reset and advance LFSR the same cycle. 
      AssertProperty(
        Sequence.BoolSequence(!(io.rxLfsrCtrl.resetLfsr && io.rxLfsrCtrl.increment)),
        label = Some("PatternReaderLfsrResetAndIncrementMutuallyExclusive")
      )
      
      // Never advance LFSR while idle.
      AssertProperty(
        Sequence.BoolSequence(io.interfaceIo.req.ready) |->
          Sequence.BoolSequence(!io.rxLfsrCtrl.increment),
        label = Some("PatternReaderNoLfsrIncrementWhenIdle")
      )
    }
  }
}
