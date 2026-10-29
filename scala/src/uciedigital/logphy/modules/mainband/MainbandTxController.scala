/*
  The mainband transmit path, from RDI (sec 10.1.4) to the Lanes by Table 4-9.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.rdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.utils._
import chisel3._
import chisel3.util._

class MainbandTxComposeIO(afeParams: AfeParams) extends Bundle {
  val mainbandActive = Input(Bool())
  val laneReversal = Input(Bool())
  val lfsr =
    Input(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
  val patternSelected = Input(Bool())
  val patternData =
    Input(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
  val parkActive = Input(Bool())
  val parkLevel = Input(Vec(afeParams.mbLanes, Bool()))
  val txLastBit = Output(Vec(afeParams.mbLanes, Bool()))
}

class MainbandTxController(afeParams: AfeParams, rdiParams: RdiParams)
    extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpIrdy = Input(Bool())
      val lpValid = Input(Bool())
      val lpData = Input(UInt((rdiParams.nBytes * 8).W))
      val plTrdy = Output(Bool())
    }
    val lanes = Decoupled(
      new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
    )
    val ctrl = new Bundle {
      val clear = Input(Bool())
      val localTxFunctionalLanes = Input(UInt(3.W))
      val negotiatedBy8 = Input(Bool())
      val txTrackPattern = Input(Bool())
      val txTrackLevel = Input(Bool())
      val quarterRate = Input(Bool())
      val stuckAtLaneMask = Input(UInt(afeParams.mbLanes.W))
    }
    val compose = new MainbandTxComposeIO(afeParams)
  })

  val nBytes = rdiParams.nBytes
  val nLanes = afeParams.mbLanes
  val serRatio = afeParams.mbSerializerRatio
  val bytesPerLane = serRatio / 8 // bytes per Lane per word

  require(
    nLanes == 8 || nLanes == 16,
    "only 8- and 16-lane configurations are supported"
  )
  require(serRatio % 8 == 0, "serRatio must be a multiple of 8")
  require(Seq(64, 128, 256, 512).contains(nBytes), "Not a valid RDI nBytes")

  val byteMap = new MainbandByteMap(nLanes, serRatio, nBytes)
  val widthMaps = byteMap.widths
  val beatSelW = byteMap.beatSelW

  val maxPossibleBeats = nBytes / (4 * bytesPerLane)
  val beatCtrW = log2Ceil(maxPossibleBeats + 1)

  val numTxBeats = byteMap.beatsFor(io.ctrl.localTxFunctionalLanes)

  // ===========================================================================
  // Beat counter
  // ===========================================================================
  // The Adapter holds lp_data until pl_trdy (sec 10.1.4), so it is not latched.
  val txBeatCtr = RegInit(0.U(beatCtrW.W))
  val txLastBeat = txBeatCtr === (numTxBeats - 1.U)

  // A Decoupled producer must not make valid depend on its consumer's ready.
  val txWants = !io.ctrl.clear && io.rdi.lpValid && io.rdi.lpIrdy
  val beatFire = txWants && io.lanes.ready

  // A withdrawn transfer restarts at beat 0, or a resume would join two Flits.
  when(io.ctrl.clear || !txWants) {
    txBeatCtr := 0.U
  }.elsewhen(beatFire) {
    txBeatCtr := Mux(txLastBeat, 0.U, txBeatCtr + 1.U)
  }

  // ===========================================================================
  // Clock, Track and Valid
  // ===========================================================================
  // MainbandTxDomain replaces CLKP and CLKN, so these two reach no pin.
  val clkPBits = ForwardedClock.clkP(serRatio, io.ctrl.quarterRate)
  val clkNBits = ForwardedClock.clkN(serRatio, io.ctrl.quarterRate)

  val validFrame = ValidFraming.framePattern(serRatio)

  val txBundle = Wire(new MainbandLanes(nLanes, serRatio))
  txBundle.clkP := clkPBits
  txBundle.clkN := clkNBits

  // sec 5.5.1 sends the Track pattern only when the partner asks for it.
  txBundle.trk :=
    Mux(
      io.ctrl.txTrackPattern,
      ForwardedClock.track(serRatio, io.ctrl.quarterRate),
      ForwardedClock.parkWord(serRatio, io.ctrl.txTrackLevel)
    )
  txBundle.valid := Mux(txWants, validFrame, 0.U)

  // ===========================================================================
  // Data Lanes
  // ===========================================================================
  val lpDataBytes = VecInit(
    Seq.tabulate(nBytes)(i => io.rdi.lpData(i * 8 + 7, i * 8))
  )

  // One selection per byte, because a mux chain costs a level per source.
  val srcNone = 0
  val srcRdi = 1
  val srcPattern = 2
  val srcPark = 3

  // Park wins, because sec 5.11 holds the Lanes parked for the whole idle.
  val txSrc = Mux(
    io.compose.parkActive,
    srcPark.U(2.W),
    Mux(
      io.compose.mainbandActive,
      srcRdi.U(2.W),
      Mux(io.compose.patternSelected, srcPattern.U(2.W), srcNone.U(2.W))
    )
  )

  // A UCIe-S x8 Link reverses within 8 Lanes (sec 4.5.3.3.5).
  // A x16 Link degraded to x8 still reverses across 16.
  val revSpans = Seq(nLanes, 8).distinct
  val revNone = 0
  def revModeOf(n: Int): Int = revSpans.indexOf(n) + 1
  val revSelW = log2Ceil(revSpans.length + 1)

  // Three modes, because with reversal off the span does not matter.
  val revMode = Mux(
    !io.compose.laneReversal,
    revNone.U(revSelW.W),
    Mux(
      io.ctrl.negotiatedBy8,
      revModeOf(8).U(revSelW.W),
      revModeOf(nLanes).U(revSelW.W)
    )
  )

  // The key uses only registers, so only txSrc arrives late, at the final 4:1.
  // Note: Do not register parkActive, because it would park one word late.
  val rdiSel = Cat(
    io.ctrl.localTxFunctionalLanes,
    if (beatSelW == 0) 0.U(0.W) else txBeatCtr(beatSelW - 1, 0),
    revMode
  )

  def rdiKey(code: Int, beat: Int, mode: Int): UInt =
    ((BigInt(code) << (beatSelW + revSelW)) | (BigInt(beat) << revSelW) |
      mode).U((3 + beatSelW + revSelW).W)

  val txComposed = Wire(Vec(nLanes, UInt(serRatio.W)))
  val txLaneMap =
    PatternLaneMap.decodeLaneMap(io.ctrl.localTxFunctionalLanes, nLanes)

  for (lane <- 0 until nLanes) {
    // Source indices are elaboration constants, so no index is dynamic.
    def mirrorOf(n: Int): Int = if (lane < n) n - 1 - lane else lane
    def pick(v: Int => UInt): UInt =
      MuxLookup(revMode, v(lane))(
        revSpans.map(n => revModeOf(n).U(revSelW.W) -> v(mirrorOf(n)))
      )
    val maskWord = pick(i => io.compose.lfsr(i))
    val maskLaneOk = pick(i => txLaneMap(i).asUInt).asBool

    val slotBytes = Wire(Vec(bytesPerLane, UInt(8.W)))
    for (slot <- 0 until bytesPerLane) {
      val lo = slot * 8
      val rdiEntries = collection.mutable.ArrayBuffer[(UInt, UInt)]()
      for (w <- widthMaps; beat <- 0 until w.beats) {
        val modes = (revNone, Some(lane)) +:
          revSpans.map(n => (revModeOf(n), w.mirror(n)(lane)))
        for ((mode, src) <- modes) {
          src.flatMap(w.sourceByte(beat, _, slot)).foreach { idx =>
            rdiEntries += rdiKey(w.codeBits, beat, mode) -> lpDataBytes(idx)
          }
        }
      }

      // A Lane outside the map has no RDI entry, so it gets the default.
      val rdiByte = MuxLookup(rdiSel, 0.U(8.W))(rdiEntries.toSeq)

      // The XOR is here, because gating it after the mux adds a late term.
      val rdiScrambled = rdiByte ^ Mux(maskLaneOk, maskWord(lo + 7, lo), 0.U)

      val patternByte = pick(i => io.compose.patternData(i))(lo + 7, lo)
      val parkByte = Fill(8, pick(i => io.compose.parkLevel(i).asUInt).asBool)

      slotBytes(slot) := MuxLookup(txSrc, 0.U(8.W))(
        Seq(
          srcRdi.U(2.W) -> rdiScrambled,
          srcPattern.U(2.W) -> patternByte,
          srcPark.U(2.W) -> parkByte
        )
      )
    }

    // The sec 9.5.3.36 fault goes last, so it covers every source.
    // The spec gives no stuck-at polarity, so 0 is a choice.
    txComposed(lane) := Mux(
      io.ctrl.stuckAtLaneMask(lane),
      0.U(serRatio.W),
      slotBytes.asUInt
    )
    txBundle.data(lane) := txComposed(lane)

    // MainbandClockWaveform latches this only on a sent word, never on park.
    io.compose.txLastBit(lane) := txComposed(lane)(serRatio - 1)
  }

  io.rdi.plTrdy := !io.ctrl.clear && txLastBeat && io.lanes.ready

  io.lanes.valid := txWants
  io.lanes.bits := txBundle
}

class MainbandTxDomainCtrlIO(afeParams: AfeParams) extends Bundle {
  val mainbandActive = Input(Bool())
  val isActive = Input(Bool())
  val inLinkInit = Input(Bool())
  val inPatternState = Input(Bool())
  val txClkHeldLow = Input(Bool())
  val localTxFunctionalLanes = Input(UInt(3.W))
  val negotiatedBy8 = Input(Bool())
  val laneReversal = Input(Bool())
  val txTrackPattern = Input(Bool())
  val txTrackLevel = Input(Bool())
  val continuous = Input(Bool())
  val quarterRate = Input(Bool())
  val rxClkCalSendFwClkPattern = Input(Bool())
  val rxClkCalSendTrkPattern = Input(Bool())
  val stuckAtLaneMask = Input(UInt(afeParams.mbLanes.W))
  val patternBurst = new PatternBurstIO
  val scramblerResetLfsr = Input(Bool())
  val stallDrain = Input(Bool())
}

// txDivClk never stops, because the forwarded clock parks as constant words.
// The RDI transmit signals all share this clock (sec 10.1.2).
class MainbandTxDomain(
    afeParams: AfeParams,
    rdiParams: RdiParams,
    separateDivClocks: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val lpIrdy = Input(Bool())
      val lpValid = Input(Bool())
      val lpData = Input(UInt((rdiParams.nBytes * 8).W))
      val plTrdy = Output(Bool())
    }
    val analogTx = Decoupled(
      new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
    )
    val ctrl = new MainbandTxDomainCtrlIO(afeParams)
    val patternCross = Flipped(new PatternWriterCrossIO)
  })

  val bypass = !separateDivClocks

  // ===========================================================================
  // Clock crossings
  // ===========================================================================
  // Note: Sync3 needs slow levels, so do not add a value that steps.
  val mainbandActive = Sync3(io.ctrl.mainbandActive, false.B, bypass)
  val stallDrain = Sync3(io.ctrl.stallDrain, false.B, bypass)
  val isActive = Sync3(io.ctrl.isActive, false.B, bypass)
  val inLinkInit = Sync3(io.ctrl.inLinkInit, false.B, bypass)
  val inPatternState = Sync3(io.ctrl.inPatternState, false.B, bypass)
  val txClkHeldLow = Sync3(io.ctrl.txClkHeldLow, false.B, bypass)
  val localTxFunctionalLanes =
    Sync3.word(io.ctrl.localTxFunctionalLanes, bypass)
  val negotiatedBy8 = Sync3(io.ctrl.negotiatedBy8, false.B, bypass)
  val laneReversal = Sync3(io.ctrl.laneReversal, false.B, bypass)
  val txTrackPattern = Sync3(io.ctrl.txTrackPattern, false.B, bypass)
  val txTrackLevel = Sync3(io.ctrl.txTrackLevel, false.B, bypass)

  // The LTSM changes the stuck-at mask only at a retrain, so it is static here.
  val stuckAtLaneMask = Sync3.word(io.ctrl.stuckAtLaneMask, bypass)
  val continuous = Sync3(io.ctrl.continuous, false.B, bypass)
  val quarterRate = Sync3(io.ctrl.quarterRate, false.B, bypass)
  val rxClkCalSendFwClkPattern =
    Sync3(io.ctrl.rxClkCalSendFwClkPattern, false.B, bypass)
  val rxClkCalSendTrkPattern =
    Sync3(io.ctrl.rxClkCalSendTrkPattern, false.B, bypass)

  // Software sets these, and the pattern writer samples them at a request.
  val patternBurstEnable = Sync3(io.ctrl.patternBurst.enable, false.B, bypass)
  val patternBurstCount = Sync3.word(io.ctrl.patternBurst.burstCount, bypass)
  val patternIdleCount = Sync3.word(io.ctrl.patternBurst.idleCount, bypass)
  val patternIterationCount =
    Sync3.word(io.ctrl.patternBurst.iterationCount, bypass)

  // ===========================================================================
  // Modules
  // ===========================================================================
  val txController = Module(new MainbandTxController(afeParams, rdiParams))
  val patternWriter = Module(new PatternWriter(afeParams))
  val scrambler = Module(new UcieLFSR(afeParams))
  val clockWaveform = Module(
    new MainbandClockWaveform(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )

  val patternCross = Module(new PatternWriterCrossDevice(bypass))
  patternCross.io.cross <> io.patternCross
  patternCross.io.engine <> patternWriter.io.interfaceIo

  // ===========================================================================
  // Source select
  // ===========================================================================
  val fwClkPBits =
    ForwardedClock.clkP(afeParams.mbSerializerRatio, quarterRate)
  val fwClkNBits =
    ForwardedClock.clkN(afeParams.mbSerializerRatio, quarterRate)
  val fwTrkBits =
    ForwardedClock.track(afeParams.mbSerializerRatio, quarterRate)

  val rxClkCalOverride =
    rxClkCalSendFwClkPattern && rxClkCalSendTrkPattern
  val patternWriterSelectedForTx =
    inPatternState && !rxClkCalOverride && !isActive

  // Between bursts the engine holds stale words, so select it only while valid.
  val patternPresenting =
    patternWriterSelectedForTx && patternWriter.io.mbTxLaneIo.valid

  // sec 4.5.3.4.5 step 1 needs a free running clock with Data and Valid low.
  val rxClkCalTxBits = Wire(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  rxClkCalTxBits.data.foreach(_ := 0.U)
  rxClkCalTxBits.valid := 0.U
  rxClkCalTxBits.clkP := Mux(rxClkCalSendFwClkPattern, fwClkPBits, 0.U)
  rxClkCalTxBits.clkN := Mux(rxClkCalSendFwClkPattern, fwClkNBits, 0.U)
  rxClkCalTxBits.trk := Mux(rxClkCalSendTrkPattern, fwTrkBits, 0.U)

  val selectedTxBits = Wire(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  selectedTxBits := 0.U.asTypeOf(chiselTypeOf(selectedTxBits))
  val selectedTxValid = WireDefault(false.B)

  when(mainbandActive) {
    selectedTxBits := txController.io.lanes.bits
    selectedTxValid := txController.io.lanes.valid
  }.elsewhen(rxClkCalOverride) {
    selectedTxBits := rxClkCalTxBits
    selectedTxValid := true.B
  }.elsewhen(patternPresenting) {
    selectedTxBits := patternWriter.io.mbTxLaneIo.bits
    selectedTxValid := patternWriter.io.mbTxLaneIo.valid
  }

  // MainbandTxController already chose the Data Lanes, so this wins.
  selectedTxBits.data := txController.io.lanes.bits.data

  // ===========================================================================
  // Clock waveform
  // ===========================================================================
  // sec 4.1.3 owes a postamble after a Valid burst, and REPAIRCLK has none.
  val patternOwesPostamble =
    patternWriterSelectedForTx && patternWriter.io.patternDrivesValid

  // Between bursts the training states park the clock unless it runs free.
  val composerOwnsClock = mainbandActive || inLinkInit || continuous ||
    (patternOwesPostamble &&
      (selectedTxValid || clockWaveform.io.owesPostamble))

  // From MBTRAIN.VALTRAINCENTER to LINKSPEED the LTSM sets no txClkHeldLow, so
  // in Strobe mode no word goes out between bursts.
  val clkHeldLow = txClkHeldLow && !composerOwnsClock && !selectedTxValid

  // Zeros would put 0 V across a differential receiver, so hold the park pair.
  val (heldP, heldN) = ForwardedClock.parkPair(false.B, quarterRate)

  // Note: Demand must not read a ready that predriveWord lowers, or it loops.
  clockWaveform.io.txDemand :=
    (mainbandActive || inLinkInit || patternOwesPostamble) && selectedTxValid
  clockWaveform.io.analogReady := io.analogTx.ready
  clockWaveform.io.txLastBit := txController.io.compose.txLastBit
  clockWaveform.io.continuous := continuous
  clockWaveform.io.quarterRate := quarterRate

  // Only ACTIVE alternates the park level (sec 5.11), training states fix it.
  clockWaveform.io.parkLow := !mainbandActive

  // ===========================================================================
  // Transmit controller
  // ===========================================================================
  txController.io.rdi.lpIrdy := io.rdi.lpIrdy && mainbandActive
  txController.io.rdi.lpValid := io.rdi.lpValid && mainbandActive
  txController.io.rdi.lpData := io.rdi.lpData

  // In LinkError the drained beats are dropped (sec 10.3.3.7).
  io.rdi.plTrdy := txController.io.rdi.plTrdy || stallDrain
  txController.io.ctrl.clear := !mainbandActive
  txController.io.ctrl.localTxFunctionalLanes := localTxFunctionalLanes
  txController.io.ctrl.negotiatedBy8 := negotiatedBy8
  txController.io.ctrl.txTrackPattern := txTrackPattern
  txController.io.ctrl.txTrackLevel := txTrackLevel
  txController.io.ctrl.quarterRate := quarterRate
  txController.io.ctrl.stuckAtLaneMask := stuckAtLaneMask
  txController.io.compose.mainbandActive := mainbandActive
  txController.io.compose.laneReversal := laneReversal
  txController.io.compose.lfsr := scrambler.io.lfsrOutput
  txController.io.compose.patternSelected := patternPresenting
  txController.io.compose.patternData := patternWriter.io.mbTxLaneIo.bits.data

  // ACTIVE only, because sec 4.5.3.5 holds the Lanes low in LINKINIT.
  txController.io.compose.parkActive :=
    mainbandActive && clockWaveform.io.inIdle
  txController.io.compose.parkLevel := clockWaveform.io.parkLevel

  // ===========================================================================
  // Lane output
  // ===========================================================================
  val composedTxBits = Wire(chiselTypeOf(selectedTxBits))
  composedTxBits := selectedTxBits
  when(composerOwnsClock) {
    composedTxBits.clkP := clockWaveform.io.clkP
    composedTxBits.clkN := clockWaveform.io.clkN
    when(clockWaveform.io.inIdle) {
      // Track keeps its word, because sec 4.6 runs it with or without data.
      composedTxBits.valid := 0.U
    }
  }.elsewhen(clkHeldLow) {
    composedTxBits.clkP :=
      ForwardedClock.parkWord(afeParams.mbSerializerRatio, heldP)
    composedTxBits.clkN :=
      ForwardedClock.parkWord(afeParams.mbSerializerRatio, heldN)

    // Zero all Lanes, or the engine's stale words put a pattern on Track.
    composedTxBits.data.foreach(_ := 0.U)
    composedTxBits.valid := 0.U
    composedTxBits.trk := 0.U
  }

  // The composer sends a word every cycle, and the partner skips idle ones.
  io.analogTx.valid :=
    Mux(composerOwnsClock || clkHeldLow, true.B, selectedTxValid)
  io.analogTx.bits := composedTxBits

  // The pre-drive takes one word, so the source waits for that cycle.
  txController.io.lanes.ready :=
    io.analogTx.ready && mainbandActive &&
      !clockWaveform.io.predriveWord
  patternWriter.io.mbTxLaneIo.ready :=
    patternWriterSelectedForTx && io.analogTx.ready &&
      !clockWaveform.io.predriveWord

  // ===========================================================================
  // Pattern writer and scrambler
  // ===========================================================================
  patternWriter.io.quarterRate := quarterRate
  patternWriter.io.burst.enable := patternBurstEnable
  patternWriter.io.burst.burstCount := patternBurstCount
  patternWriter.io.burst.idleCount := patternIdleCount
  patternWriter.io.burst.iterationCount := patternIterationCount
  patternWriter.io.txLfsrCtrl.pattern := scrambler.io.lfsrOutput
  val txTrainingLfsrActive = patternWriter.io.txLfsrCtrl.valid

  // The scrambler does not advance on an idle word (sec 5.11).
  val txRuntimeIncrement =
    mainbandActive && !clockWaveform.io.inIdle
  val scramblerIncrement = Mux(
    txTrainingLfsrActive,
    patternWriter.io.txLfsrCtrl.increment,
    txRuntimeIncrement
  )

  // ScramblerResetCrossing already brings the LINKINIT reset into this domain.
  val scramblerReset =
    io.ctrl.scramblerResetLfsr || patternWriter.io.txLfsrCtrl.resetLfsr
  scrambler.io.increment := VecInit(
    Seq.fill(afeParams.mbLanes)(scramblerIncrement)
  )
  scrambler.io.resetLfsr := VecInit(Seq.fill(afeParams.mbLanes)(scramblerReset))
}
