/*
  Compares the received Lanes with a training pattern (sec 4.4, Table 7-11).
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import chisel3.util._

// Named Bundles, so a clock crossing can carry them as payloads.
class PatternReaderReq extends Bundle {
  val patternType = PatternSelect()
  val comparisonMode = ComparisonMode()
  val errorThreshold = UInt(16.W)
  val doConsecutiveCount = Bool()
}

class PatternReaderResp(numMbLanes: Int) extends Bundle {
  val perLaneStatusBits = Vec(numMbLanes, Bool())
  val aggregateStatus = Bool()
}

// The Lane code is not here, because it belongs to the Link (Table 4-9).
class PatternReaderClientIO(numMbLanes: Int) extends Bundle {
  val req = Flipped(Decoupled(new PatternReaderReq))
  val done = Input(Bool())
  val resp = Decoupled(new PatternReaderResp(numMbLanes))
  val verdictFinal = Output(Bool())
}

class PatternReaderIO(numMbLanes: Int)
    extends PatternReaderClientIO(numMbLanes) {
  val remoteFuncLanes = Input(UInt(3.W))
}

// A parked clock can strand a window, so restartOnRequest restarts any state.
class PatternReader(afeParams: AfeParams, restartOnRequest: Boolean = false)
    extends Module {
  val io = IO(new Bundle {
    val interfaceIo = new PatternReaderIO(afeParams.mbLanes)
    val rxLfsrCtrl = new Bundle {
      val increment = Output(Bool())
      val resetLfsr = Output(Bool())
      val pattern =
        Input(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
    }
    val laneMaskRx = Input(UInt(afeParams.mbLanes.W))
    val mbRxLaneIo =
      Input(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
    val mbRxValid = Input(Bool())
  })

  // ===========================================================================
  // Parameters and helpers
  // ===========================================================================
  val serRatio = afeParams.mbSerializerRatio
  require(
    serRatio > 0,
    "PatternReader requires a positive mainband serializer ratio"
  )

  val errCountWidth = 16
  val counterMax = ((BigInt(1) << errCountWidth) - 1).U(errCountWidth.W)
  val mismatchCountWidth = log2Ceil(serRatio + 1)
  val numConsecutive = 16 // 16 iterations in a row pass (sec 4.5.3.3.3)

  // Splits one phase's word into (lo, hi, closes) segments, one per iteration.
  def iterationSegments(
      patternWidth: Int,
      phase: Int
  ): Seq[(Int, Int, Boolean)] = {
    val segs = scala.collection.mutable.ListBuffer.empty[(Int, Int, Boolean)]
    var lo = 0
    for (b <- 0 until serRatio) {
      val closesIteration =
        ((phase * serRatio + b) % patternWidth) == (patternWidth - 1)
      if (closesIteration) {
        segs += ((lo, b, true))
        lo = b + 1
      }
    }
    if (lo <= serRatio - 1) { segs += ((lo, serRatio - 1, false)) }
    segs.toSeq
  }

  // The count stops at the target, so a later mismatch cannot undo a pass.
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

    // Note: The count ripples through all segments in a cycle. Check it in PD.
    for ((lo, hi, closes) <- segs) {
      val running = dirty || validFrameBad || xorWord(hi, lo).orR
      if (closes) {
        count = Mux(count === target, count, Mux(running, 0.U, count + 1.U))
        dirty = false.B
      } else {
        dirty = running
      }
    }
    (count, dirty)
  }

  // Only a phase that closes an iteration gets a fold, to save area.
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
      foldWordIterations(
        xorWord,
        validFrameBad,
        iterationSegments(patternWidth, 0),
        prevDirty,
        prevCount,
        target
      )
    } else {
      val closingPhases = (0 until numPhases).filter(p =>
        iterationSegments(patternWidth, p).exists(_._3)
      )
      val closingFolds = closingPhases.map { p =>
        p -> foldWordIterations(
          xorWord,
          validFrameBad,
          iterationSegments(patternWidth, p),
          prevDirty,
          prevCount,
          target
        )
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

  def selectPatternWord(
      words: Seq[UInt],
      phase: UInt,
      phaseWidth: Int
  ): UInt = {
    require(
      words.nonEmpty,
      "PatternReader pattern lookup must contain at least one word"
    )

    if (words.length == 1) {
      words.head
    } else {
      MuxLookup(phase, words.head)(
        words.zipWithIndex.map { case (word, idx) =>
          idx.U(phaseWidth.W) -> word
        }
      )
    }
  }

  // ===========================================================================
  // Pattern references
  // ===========================================================================
  // clkP, clkN and trk carry one pattern, so one phase counter serves them.
  val clkPatternWidth = PatternDefs.clkRepairWidth
  val clkRepairPattern = PatternDefs.clkRepair
  val clkRepairPatternWords =
    PatternDefs.repeatedPatternWords(
      clkRepairPattern,
      clkPatternWidth,
      serRatio
    )
  val clkRepairPhaseWidth = log2Ceil(math.max(2, clkRepairPatternWords.length))
  val clkPhaseReg = RegInit(0.U(clkRepairPhaseWidth.W))
  val clkRepairPhaseLimit =
    (clkRepairPatternWords.length - 1).U(clkRepairPhaseWidth.W)
  val clkRepairRefPattern =
    selectPatternWord(clkRepairPatternWords, clkPhaseReg, clkRepairPhaseWidth)

  // The burst can start at any word phase (sec 4.5.3.3.3), so lock on a match.
  val clkLocked = RegInit(false.B)

  // A zero word is a parked Lane. It shows no phase, so it never locks.
  val clkLockPhases = clkRepairPatternWords.indices.filter { phase =>
    (0 until serRatio).exists(bit =>
      clkRepairPattern.testBit((phase * serRatio + bit) % clkPatternWidth)
    )
  }
  val clkRxWords =
    Seq(io.mbRxLaneIo.clkP, io.mbRxLaneIo.clkN, io.mbRxLaneIo.trk)
  val clkPhaseHits = clkLockPhases.map(phase =>
    clkRxWords.map(_ === clkRepairPatternWords(phase)).reduce(_ || _)
  )
  val clkFound = clkPhaseHits.reduce(_ || _)

  // A word seen at two phases locks on the lower, where a burst starts.
  // Note: Feed the search only into registers, never into the reference.
  val clkNextPhase = MuxCase(
    0.U(clkRepairPhaseWidth.W),
    clkLockPhases.zip(clkPhaseHits).map { case (phase, hit) =>
      hit -> ((phase + 1) % clkRepairPatternWords.length)
        .U(clkRepairPhaseWidth.W)
    }
  )

  val valTrainWidth = PatternDefs.valTrainWidth
  val valTrainPattern = PatternDefs.valTrain
  val valTrainPatternWords =
    PatternDefs.repeatedPatternWords(valTrainPattern, valTrainWidth, serRatio)
  val validPhaseWidth = log2Ceil(math.max(2, valTrainPatternWords.length))
  val validPhaseReg = RegInit(0.U(validPhaseWidth.W))
  val validPhaseLimit = (valTrainPatternWords.length - 1).U(validPhaseWidth.W)
  val valTrainRefPattern =
    selectPatternWord(valTrainPatternWords, validPhaseReg, validPhaseWidth)

  val perLanePatternWidth = PatternDefs.perLaneIdWidth
  val perLaneIdPatternWords = Seq.tabulate(afeParams.mbLanes) { lane =>
    PatternDefs.repeatedPatternWords(
      PatternDefs.perLaneId(lane),
      perLanePatternWidth,
      serRatio
    )
  }
  val perLaneIdPhaseWidth = log2Ceil(
    math.max(2, perLaneIdPatternWords.head.length)
  )
  val perLaneIdPhaseReg = RegInit(0.U(perLaneIdPhaseWidth.W))
  val perLaneIdPhaseLimit =
    (perLaneIdPatternWords.head.length - 1).U(perLaneIdPhaseWidth.W)
  val perLaneIdRefPattern = VecInit(
    perLaneIdPatternWords.map(words =>
      selectPatternWord(words, perLaneIdPhaseReg, perLaneIdPhaseWidth)
    )
  )

  // ===========================================================================
  // State
  // ===========================================================================
  val sIdle :: sDetect :: sResult :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val patternTypeReg = RegInit(PatternSelect.CLKREPAIR)
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE)
  val doConsecutiveCountReg = RegInit(false.B)

  // In consecutive mode, the number of clean iterations to pass.
  val errorThresholdReg = RegInit(0.U(errCountWidth.W))

  // Mismatches in error-count mode, clean iterations in consecutive mode.
  val patternCounterReg = RegInit(
    VecInit(Seq.fill(afeParams.mbLanes)(0.U(errCountWidth.W)))
  )

  // Marks the open iteration dirty across the cycles that it spans.
  val iterDirtyReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(false.B)))
  val laneMap = PatternLaneMap.decodeLaneMap(
    io.interfaceIo.remoteFuncLanes,
    afeParams.mbLanes
  )

  // Gate on mbRxValid, so the first word lands at phase 0.
  val counterEn = state === sDetect && io.mbRxValid

  // Silence gives no mismatches, so a window with no data word must fail.
  val sawBurst = RegInit(false.B)

  io.interfaceIo.req.ready := state === sIdle
  io.interfaceIo.resp.valid := state === sResult

  // counterEn needs sDetect, so leaving sDetect freezes every count here.
  val verdictFinal = RegInit(true.B)

  // Note: MBINIT cannot wait for this, because its clock parks (sec 4.5.3.3.4).
  io.interfaceIo.verdictFinal := verdictFinal

  // The clears for start come last in this module, so they win over counting.
  val start =
    if (restartOnRequest) io.interfaceIo.req.valid
    else io.interfaceIo.req.fire

  // verdictFinal is a flop set with the state, because a Sync3 reads it.
  // Note: Do not RegNext it, because rxDivClk may not tick again (sec 4.1.3).
  switch(state) {
    is(sDetect) {
      when(io.interfaceIo.done) {
        state := sResult
        verdictFinal := true.B
      }
    }
    is(sResult) {
      when(io.interfaceIo.resp.fire) { state := sIdle }
    }
  }
  when(start) {
    state := sDetect
    verdictFinal := false.B
    patternTypeReg := io.interfaceIo.req.bits.patternType
    comparisonModeReg := io.interfaceIo.req.bits.comparisonMode
    doConsecutiveCountReg := io.interfaceIo.req.bits.doConsecutiveCount

    when(io.interfaceIo.req.bits.doConsecutiveCount) {
      errorThresholdReg := numConsecutive.U
    }.otherwise {
      errorThresholdReg := io.interfaceIo.req.bits.errorThreshold
    }
  }

  val notClkRepair = patternTypeReg =/= PatternSelect.CLKREPAIR

  // A word spans whole framing groups, so an all-zero Valid Lane is idle.
  val validIdle = notClkRepair && (io.mbRxLaneIo.valid === 0.U)
  val validBad =
    notClkRepair && (io.mbRxLaneIo.valid =/= valTrainRefPattern) && !validIdle

  // An idle word advances nothing (sec 5.11).
  // CLKREPAIR has no idle word, because a parked clock Lane is a fail.
  val patternWordSeen = counterEn && !validIdle

  val clkWord = patternWordSeen && (patternTypeReg === PatternSelect.CLKREPAIR)
  val clkLockNow = clkWord && !clkLocked && clkFound

  // The lock word is dirty, which costs 1 of 128 iterations against 16 needed.
  val clkUncounted = clkWord && !clkLocked

  when(clkLockNow) {
    clkLocked := true.B
    clkPhaseReg := clkNextPhase
  }.elsewhen(clkWord && clkLocked) {
    clkPhaseReg := Mux(
      clkPhaseReg === clkRepairPhaseLimit,
      0.U,
      clkPhaseReg + 1.U
    )
  }

  // The Valid Lane carries valtrain in every pattern except CLKREPAIR.
  when(patternWordSeen && (patternTypeReg =/= PatternSelect.CLKREPAIR)) {
    validPhaseReg := Mux(
      validPhaseReg === validPhaseLimit,
      0.U,
      validPhaseReg + 1.U
    )
  }
  when(patternWordSeen && (patternTypeReg === PatternSelect.PERLANEID)) {
    perLaneIdPhaseReg := Mux(
      perLaneIdPhaseReg === perLaneIdPhaseLimit,
      0.U,
      perLaneIdPhaseReg + 1.U
    )
  }

  // ===========================================================================
  // Comparison, stage 1 of 2
  // ===========================================================================
  // A degrade keeps each logical Lane's ID and seed (Table 4-4, Table 4-9).
  val remotePattern = Wire(Vec(afeParams.mbLanes, UInt(serRatio.W)))
  val localPattern = Wire(Vec(afeParams.mbLanes, UInt(serRatio.W)))
  remotePattern.foreach(_ := 0.U)
  localPattern.foreach(_ := 0.U)

  when(counterEn) {
    switch(patternTypeReg) {
      is(PatternSelect.CLKREPAIR) {
        remotePattern(0) := io.mbRxLaneIo.clkP
        localPattern(0) := clkRepairRefPattern
        remotePattern(1) := io.mbRxLaneIo.clkN
        localPattern(1) := clkRepairRefPattern
        remotePattern(2) := io.mbRxLaneIo.trk
        localPattern(2) := clkRepairRefPattern
      }
      is(PatternSelect.VALTRAIN) {
        remotePattern(0) := io.mbRxLaneIo.valid
        localPattern(0) := valTrainRefPattern
      }
      is(PatternSelect.PERLANEID) {
        remotePattern.zipWithIndex.foreach { case (res, i) =>
          res := Mux(laneMap(i), io.mbRxLaneIo.data(i), 0.U)
        }
        localPattern.zipWithIndex.foreach { case (res, i) =>
          res := Mux(laneMap(i), perLaneIdRefPattern(i), 0.U)
        }
      }
      is(PatternSelect.LFSR) {
        remotePattern.zipWithIndex.foreach { case (res, i) =>
          res := Mux(laneMap(i), io.mbRxLaneIo.data(i), 0.U)
        }
        localPattern.zipWithIndex.foreach { case (res, i) =>
          res := Mux(laneMap(i), io.rxLfsrCtrl.pattern(i), 0.U)
        }
      }
    }
  }

  // The mask names Data Lanes, so it applies only to PERLANEID and LFSR.
  val maskAppliesToSlot =
    (patternTypeReg === PatternSelect.PERLANEID) ||
      (patternTypeReg === PatternSelect.LFSR)

  // A set bit masks a Lane, the opposite of the other Lane vectors here.
  val laneMasked = VecInit(Seq.tabulate(afeParams.mbLanes) { i =>
    maskAppliesToSlot && io.laneMaskRx(i)
  })

  // Per-Lane mode only, because in aggregate mode slot 0 holds every verdict.
  val laneResultMasked = VecInit(Seq.tabulate(afeParams.mbLanes) { i =>
    (comparisonModeReg === ComparisonMode.PERLANE) && laneMasked(i)
  })

  // Mask the XOR, or a masked Lane still spoils the aggregate and dirty bit.
  val xorResult = VecInit(
    Seq.tabulate(afeParams.mbLanes)(i =>
      Mux(laneMasked(i), 0.U(serRatio.W), remotePattern(i) ^ localPattern(i))
    )
  )

  // Zero in per-Lane mode, so the aggregate PopCount does not toggle unused.
  val aggregateOrResult = Wire(UInt(serRatio.W))
  aggregateOrResult := 0.U
  when(comparisonModeReg === ComparisonMode.AGGREGATE) {
    aggregateOrResult := xorResult.reduceTree(_ | _)
  }

  val popCountResult = Wire(Vec(afeParams.mbLanes, UInt(mismatchCountWidth.W)))
  when(comparisonModeReg === ComparisonMode.AGGREGATE) {
    popCountResult(0) := PopCount(aggregateOrResult)
    for (i <- 1 until afeParams.mbLanes) { popCountResult(i) := 0.U }
  }.otherwise { // ComparisonMode.PERLANE
    popCountResult.zipWithIndex.foreach { case (res, i) =>
      res := PopCount(xorResult(i))
    }
  }

  // A Valid framing error counts against these Lanes.
  val laneActive = Wire(Vec(afeParams.mbLanes, Bool()))
  for (i <- 0 until afeParams.mbLanes) {
    laneActive(i) := false.B
    when(comparisonModeReg === ComparisonMode.AGGREGATE) {
      laneActive(i) := (if (i == 0) true.B else false.B)
    }.otherwise {
      switch(patternTypeReg) {
        is(PatternSelect.CLKREPAIR) {
          laneActive(i) := (if (i < 3) true.B else false.B)
        }
        is(PatternSelect.VALTRAIN) {
          laneActive(i) := (if (i == 0) true.B else false.B)
        }

        // A masked Lane is inactive, so no framing error counts against it.
        is(PatternSelect.PERLANEID) {
          laneActive(i) := laneMap(i) && !laneMasked(i)
        }
        is(PatternSelect.LFSR) { laneActive(i) := laneMap(i) && !laneMasked(i) }
      }
    }
  }

  // detectPipeValid excludes an idle word, so there is no idle case here.
  val effectivePopCountResult = Wire(
    Vec(afeParams.mbLanes, UInt(mismatchCountWidth.W))
  )
  effectivePopCountResult.zipWithIndex.foreach { case (res, i) =>
    res := Mux(
      counterEn && validBad,
      Mux(
        laneActive(i),
        1.U(mismatchCountWidth.W),
        0.U(mismatchCountWidth.W)
      ),
      popCountResult(i)
    )
  }

  // These registers split the comparison into two stages to meet timing.
  val popCountPipe = RegNext(effectivePopCountResult)
  val xorPipe = RegNext(xorResult)
  val validBadPipe = RegNext(validBad)
  val clkPhasePipe = RegNext(clkPhaseReg)
  val clkUncountedPipe = RegNext(clkUncounted)
  val validPhasePipe = RegNext(validPhaseReg)
  val perLaneIdPhasePipe = RegNext(perLaneIdPhaseReg)

  // Skip idle words, because a zeroed XOR would close iterations never sent.
  val detectPipeValid = RegNext(patternWordSeen && !start, false.B)

  when(patternWordSeen) {
    sawBurst := true.B
  }

  // ===========================================================================
  // Counters, stage 2 of 2
  // ===========================================================================
  val patternCompStatus = VecInit(patternCounterReg.map { count =>
    Mux(
      doConsecutiveCountReg,
      count === errorThresholdReg,
      count <= errorThresholdReg
    )
  })

  // sDetect also gates, because detectPipeValid outlasts it by a cycle.
  when(detectPipeValid && (state === sDetect)) {
    when(doConsecutiveCountReg) {
      {
        for (i <- 0 until afeParams.mbLanes) {
          val xorWord = xorPipe(i)

          // Every Lane can carry PERLANEID.
          val (perLaneCount, perLaneDirty) = consecutiveIterNext(
            perLanePatternWidth,
            perLaneIdPatternWords.head.length,
            perLaneIdPhasePipe,
            xorWord,
            validBadPipe,
            iterDirtyReg(i),
            patternCounterReg(i),
            errorThresholdReg
          )

          // Fold only the patterns that this Lane can carry.
          val (nextCount, nextDirty) =
            if (i == 0) {
              // Lane 0 carries clkP (CLKREPAIR), Valid (VALTRAIN) or PERLANEID.
              val (clkCount, clkDirty) = consecutiveIterNext(
                clkPatternWidth,
                clkRepairPatternWords.length,
                clkPhasePipe,
                xorWord,
                false.B,
                iterDirtyReg(i),
                patternCounterReg(i),
                errorThresholdReg
              )
              val (valCount, valDirty) = consecutiveIterNext(
                valTrainWidth,
                valTrainPatternWords.length,
                validPhasePipe,
                xorWord,
                validBadPipe,
                iterDirtyReg(i),
                patternCounterReg(i),
                errorThresholdReg
              )
              (
                MuxLookup(patternTypeReg, patternCounterReg(i))(
                  Seq(
                    PatternSelect.CLKREPAIR -> clkCount,
                    PatternSelect.VALTRAIN -> valCount,
                    PatternSelect.PERLANEID -> perLaneCount
                  )
                ),
                MuxLookup(patternTypeReg, iterDirtyReg(i))(
                  Seq(
                    PatternSelect.CLKREPAIR -> clkDirty,
                    PatternSelect.VALTRAIN -> valDirty,
                    PatternSelect.PERLANEID -> perLaneDirty
                  )
                )
              )
            } else if (i == 1 || i == 2) {
              // Lanes 1 and 2 carry clkN and trk (CLKREPAIR) or PERLANEID.
              val (clkCount, clkDirty) = consecutiveIterNext(
                clkPatternWidth,
                clkRepairPatternWords.length,
                clkPhasePipe,
                xorWord,
                false.B,
                iterDirtyReg(i),
                patternCounterReg(i),
                errorThresholdReg
              )
              (
                MuxLookup(patternTypeReg, patternCounterReg(i))(
                  Seq(
                    PatternSelect.CLKREPAIR -> clkCount,
                    PatternSelect.PERLANEID -> perLaneCount
                  )
                ),
                MuxLookup(patternTypeReg, iterDirtyReg(i))(
                  Seq(
                    PatternSelect.CLKREPAIR -> clkDirty,
                    PatternSelect.PERLANEID -> perLaneDirty
                  )
                )
              )
            } else {
              // Lanes above 2 carry PERLANEID only.
              (perLaneCount, perLaneDirty)
            }

          // The fold stops the count at the target, so take it as it is.
          when(laneActive(i) && !clkUncountedPipe) {
            patternCounterReg(i) := nextCount
            iterDirtyReg(i) := nextDirty
          }

          // A lock past phase 0 joins its iteration late, so mark it dirty.
          when(laneActive(i) && clkUncountedPipe) {
            iterDirtyReg(i) := clkPhaseReg =/= 0.U
          }
        }
      }
    }.otherwise {
      for (i <- 0 until afeParams.mbLanes) {
        val errorSum = patternCounterReg(i) +& popCountPipe(i)
        val errorCount = Mux(
          errorSum(errCountWidth),
          counterMax,
          errorSum(errCountWidth - 1, 0)
        )

        // Active Lanes count until they pass the threshold, and then stop.
        when(laneActive(i) && patternCompStatus(i)) {
          patternCounterReg(i) := errorCount
        }
      }
    }
  }

  // ===========================================================================
  // Outputs
  // ===========================================================================
  // A masked Lane reports Pass, or REPAIRMB would drop it (sec 4.5.3.3.6).
  val perLaneResult = VecInit(Seq.tabulate(afeParams.mbLanes) { i =>
    laneResultMasked(i) || (patternCompStatus(i) && sawBurst)
  })
  io.interfaceIo.resp.bits.perLaneStatusBits := perLaneResult

  // AND the active Lanes in both modes, for Table 7-11's cumulative result.
  io.interfaceIo.resp.bits.aggregateStatus := perLaneResult.zipWithIndex
    .map { case (res, i) => !laneActive(i) || res }
    .reduce(_ && _)

  // An idle word must not advance this LFSR, or it runs ahead (sec 5.11).
  io.rxLfsrCtrl.increment := counterEn && !validIdle && !start &&
    (patternTypeReg === PatternSelect.LFSR)
  io.rxLfsrCtrl.resetLfsr :=
    start && (io.interfaceIo.req.bits.patternType === PatternSelect.LFSR)

  when(start) {
    patternCounterReg.foreach(x => x := 0.U)
    iterDirtyReg.foreach(x => x := false.B)
    sawBurst := false.B
    clkPhaseReg := 0.U
    clkLocked := false.B
    validPhaseReg := 0.U
    perLaneIdPhaseReg := 0.U
  }

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(
          start && io.interfaceIo.req.bits.doConsecutiveCount
        ) |->
          Sequence.BoolSequence(
            io.interfaceIo.req.bits.comparisonMode === ComparisonMode.PERLANE
          ),
        label = Some("PatternReaderConsecutiveCountRequiresPerLane")
      )

      AssertProperty(
        // The spec has no consecutive count for the LFSR pattern.
        Sequence.BoolSequence(
          start && io.interfaceIo.req.bits.doConsecutiveCount
        ) |->
          Sequence.BoolSequence(
            io.interfaceIo.req.bits.patternType =/= PatternSelect.LFSR
          ),
        label = Some("PatternReaderConsecutiveCountDoesNotSupportLfsr")
      )

      AssertProperty(
        Sequence.BoolSequence(
          !(io.interfaceIo.req.ready && io.interfaceIo.resp.valid)
        ),
        label = Some("PatternReaderReadyAndRespValidExclusive")
      )

      // A new window drops a result that nobody took.
      val respStalled = RegNext(
        io.interfaceIo.resp.valid && !io.interfaceIo.resp.ready && !start,
        false.B
      )
      val previousRespBits = RegNext(io.interfaceIo.resp.bits.asUInt)
      AssertProperty(
        Sequence.BoolSequence(respStalled) |->
          Sequence.BoolSequence(
            io.interfaceIo.resp.valid && (io.interfaceIo.resp.bits.asUInt === previousRespBits)
          ),
        label = Some("PatternReaderRespHeldUnderBackpressure")
      )

      AssertProperty(
        Sequence.BoolSequence(
          !io.interfaceIo.req.ready && !io.interfaceIo.resp.valid &&
            io.interfaceIo.done && !start
        ) |=> Sequence.BoolSequence(io.interfaceIo.resp.valid),
        label = Some("PatternReaderDoneProducesResult")
      )

      AssertProperty(
        Sequence.BoolSequence(
          io.interfaceIo.req.ready && io.interfaceIo.done
        ) |=>
          Sequence.BoolSequence(!io.interfaceIo.resp.valid),
        label = Some("PatternReaderIdleDoneIsInert")
      )

      AssertProperty(
        Sequence.BoolSequence(
          !(io.rxLfsrCtrl.resetLfsr && io.rxLfsrCtrl.increment)
        ),
        label = Some("PatternReaderLfsrResetAndIncrementMutuallyExclusive")
      )

      AssertProperty(
        Sequence.BoolSequence(io.interfaceIo.req.ready) |->
          Sequence.BoolSequence(!io.rxLfsrCtrl.increment),
        label = Some("PatternReaderNoLfsrIncrementWhenIdle")
      )
    }
  }
}
