/*
  The mainband receive path from the Lanes to RDI (sec 4.1.2, Table 4-9).
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.rdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.utils._
import chisel3._
import chisel3.util._

class MainbandRxController(
    afeParams: AfeParams,
    rdiParams: RdiParams,
    asyncHistoryClear: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val plValid = Output(Bool())
      val plData = Output(UInt((rdiParams.nBytes * 8).W))
    }
    val lanes = Flipped(
      Decoupled(
        new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
      )
    )
    val ctrl = new Bundle {
      val clear = Input(Bool())
      val clearFramingHistory = Input(Bool())
      val localRxFunctionalLanes = Input(UInt(3.W))
      val validFramingError = Output(Bool())
      val framingErrorEvent = Output(Bool())
    }
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

  val numRxBeats = byteMap.beatsFor(io.ctrl.localRxFunctionalLanes)
  def rxSel: UInt =
    byteMap.sel(io.ctrl.localRxFunctionalLanes, rxBeatCtr)
  def mapSel(code: String, beat: Int): UInt = byteMap.key(code, beat)

  // ===========================================================================
  // Beat gather
  // ===========================================================================
  val rxBeatCtr = RegInit(0.U(beatCtrW.W))
  val rxDataAccum = Reg(Vec(nBytes, UInt(8.W)))
  val rxLastBeat = rxBeatCtr === (numRxBeats - 1.U)

  // An idle word (sec 5.11) must not advance the beat counter.
  val rxWordBad = ValidFraming.isBad(io.lanes.bits.valid, serRatio)
  val rxWordHasData = ValidFraming.hasData(io.lanes.bits.valid, serRatio)
  val rxAccepting =
    !io.ctrl.clear && io.lanes.valid && io.lanes.ready && rxWordHasData

  when(io.ctrl.clear) {
    rxBeatCtr := 0.U
    rxDataAccum.foreach(_ := 0.U)
  }.elsewhen(rxAccepting) {
    rxBeatCtr := Mux(rxLastBeat, 0.U, rxBeatCtr + 1.U)
  }

  val rxBundle = io.lanes.bits

  // A gather, so each RDI byte is at most a 6:1 select.
  val currentRxData = Wire(Vec(nBytes, UInt(8.W)))
  for (i <- 0 until nBytes) {
    val entries = for {
      w <- widthMaps
      beat <- 0 until w.beats
      lane <- 0 until nLanes
      slot <- 0 until bytesPerLane
      if w.sourceByte(beat, lane, slot).contains(i)
    } yield mapSel(w.code, beat) -> rxBundle.data(lane)(slot * 8 + 7, slot * 8)

    // A byte with no entry for this code and beat keeps its earlier value.
    currentRxData(i) := MuxLookup(rxSel, rxDataAccum(i))(entries)
    when(rxAccepting) {
      rxDataAccum(i) := currentRxData(i)
    }
  }

  // sec 10.1.2 allows no back pressure on the receive data path.
  io.lanes.ready := true.B

  // Table 10-1 lets the PHY squash pl_valid for a corrupt word.
  io.rdi.plValid := rxAccepting && rxLastBeat && !rxWordBad
  io.rdi.plData := currentRxData.asUInt

  // ===========================================================================
  // Framing errors
  // ===========================================================================
  val currentFramingError = io.lanes.valid && rxWordBad

  // Only the LINKINIT reset clears it, so it lasts through its own retrain.
  val stickyError =
    if (asyncHistoryClear) {
      // No flit arrives in LINKINIT (sec 10.2.8), so the async release is safe.
      val sticky = withReset(
        (reset.asBool || io.ctrl.clearFramingHistory).asAsyncReset
      )(RegInit(false.B))
      when(currentFramingError) {
        sticky := true.B
      }
      sticky
    } else {
      val sticky = RegInit(false.B)

      // Set wins, because a corrupt word as the Link returns is a new error.
      when(currentFramingError) {
        sticky := true.B
      }.elsewhen(io.ctrl.clearFramingHistory) {
        sticky := false.B
      }
      sticky
    }

  // Sync3 needs a clean source, so the divided build crosses only the flop.
  io.ctrl.validFramingError :=
    (if (asyncHistoryClear) stickyError else currentFramingError || stickyError)

  // sec 10.0 counts a rising edge as the event, so this is the unlatched error.
  io.ctrl.framingErrorEvent := currentFramingError
}

class MainbandRxDomainCtrlIO(numMbLanes: Int) extends Bundle {
  val mainbandActive = Input(Bool())
  val localRxFunctionalLanes = Input(UInt(3.W))
  val laneMaskRx = Input(UInt(numMbLanes.W))
  val linkInitReset = Input(Bool())
  val validFramingError = Output(Bool())
}

// rxDivClk can stop (sec 4.1.3), so signals leaving this domain are levels.
// All RDI receive signals are here, so pl_error arrives with its data.
class MainbandRxDomain(
    afeParams: AfeParams,
    rdiParams: RdiParams,
    separateDivClocks: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val plValid = Output(Bool())
      val plData = Output(UInt((rdiParams.nBytes * 8).W))
      val plError = Output(Bool())
    }
    val analogRx = Flipped(
      Decoupled(
        new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
      )
    )
    val ctrl = new MainbandRxDomainCtrlIO(afeParams.mbLanes)
    val patternCross = Flipped(new PatternReaderCrossIO(afeParams.mbLanes))
  })

  val bypass = !separateDivClocks

  // Opens with no rxDivClk edge, because the clock can be parked (sec 4.5.3.5).
  val mainbandActive = Sync3.setAsync(io.ctrl.mainbandActive, bypass)
  val localRxFunctionalLanes =
    Sync3.word(io.ctrl.localRxFunctionalLanes, bypass)

  // Written while the Link is down (sec 9.5.3.28), so a word crossing is safe.
  val laneMaskRx = Sync3.word(io.ctrl.laneMaskRx, bypass)

  // ===========================================================================
  // Modules
  // ===========================================================================
  val rxController = Module(
    new MainbandRxController(afeParams, rdiParams, asyncHistoryClear = !bypass)
  )

  val patternReader =
    Module(new PatternReader(afeParams, restartOnRequest = true))

  // Held at the seed through LINKINIT, so the first edge sees the seed.
  val descrambler =
    if (bypass) Module(new UcieLFSR(afeParams))
    else {
      val hold = reset.asBool || io.ctrl.linkInitReset
      withReset(hold.asAsyncReset)(Module(new UcieLFSR(afeParams)))
    }

  val patternCross = Module(
    new PatternReaderCrossDevice(afeParams.mbLanes, bypass)
  )
  patternCross.io.cross <> io.patternCross
  patternCross.io.engine <> patternReader.io.interfaceIo

  val aligner = Module(new MainbandRxAligner(afeParams))
  aligner.io.in := io.analogRx.bits
  aligner.io.inValid := io.analogRx.valid
  val rxLaneBits = aligner.io.out

  // ===========================================================================
  // Receive path
  // ===========================================================================
  patternReader.io.laneMaskRx := laneMaskRx
  patternReader.io.mbRxLaneIo := Mux(
    io.analogRx.valid,
    rxLaneBits,
    0.U.asTypeOf(chiselTypeOf(rxLaneBits))
  )
  patternReader.io.mbRxValid := io.analogRx.valid

  // Wait for a burst start, because a parked clock can deliver a stale word.
  val heldWord =
    if (bypass) false.B
    else {
      val awaitBurst = RegInit(true.B)
      when(!mainbandActive) {
        awaitBurst := true.B
      }.elsewhen(aligner.io.burstStart) {
        awaitBurst := false.B
      }
      awaitBurst && !aligner.io.burstStart
    }

  // No advance on an idle word (sec 5.11), judged on the aligned Valid Lane.
  val rxRuntimeIncrement =
    io.analogRx.valid && ValidFraming.hasData(
      rxLaneBits.valid,
      afeParams.mbSerializerRatio
    ) && io.analogRx.ready && mainbandActive && !heldWord
  patternReader.io.rxLfsrCtrl.pattern := descrambler.io.lfsrOutput
  val descramblerIncrement = Mux(
    mainbandActive,
    rxRuntimeIncrement,
    patternReader.io.rxLfsrCtrl.increment
  )

  // The divided build applies the LINKINIT reset as the async hold above.
  val readerReset = patternReader.io.rxLfsrCtrl.resetLfsr
  val descramblerReset =
    if (bypass) io.ctrl.linkInitReset || readerReset else readerReset
  descrambler.io.increment := VecInit(
    Seq.fill(afeParams.mbLanes)(descramblerIncrement)
  )
  descrambler.io.resetLfsr := VecInit(
    Seq.fill(afeParams.mbLanes)(descramblerReset)
  )

  val descrambledRxBits = Wire(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  descrambledRxBits := rxLaneBits

  // Note: no Lane mask, because the byte gather reads only functional Lanes.
  for (lane <- 0 until afeParams.mbLanes) {
    descrambledRxBits.data(lane) :=
      rxLaneBits.data(lane) ^ descrambler.io.lfsrOutput(lane)
  }

  rxController.io.lanes.valid :=
    io.analogRx.valid && mainbandActive && !heldWord
  rxController.io.lanes.bits := descrambledRxBits
  rxController.io.ctrl.clear := !mainbandActive

  // LINKINIT clears the framing history and the pl_valid squash (Table 10-1).
  rxController.io.ctrl.clearFramingHistory := io.ctrl.linkInitReset
  rxController.io.ctrl.localRxFunctionalLanes := localRxFunctionalLanes
  io.analogRx.ready := Mux(
    mainbandActive,
    rxController.io.lanes.ready,
    true.B
  )
  io.ctrl.validFramingError := rxController.io.ctrl.validFramingError

  // One pl_error pulse for each corrupt word (sec 10.0).
  val plErrorPulse =
    mainbandActive && rxController.io.ctrl.framingErrorEvent
  val suppressPlValidAfterError =
    if (bypass) {
      val suppress = RegInit(false.B)
      when(plErrorPulse) {
        suppress := true.B
      }.elsewhen(io.ctrl.linkInitReset) {
        suppress := false.B
      }
      suppress
    } else {
      val suppress = withReset(
        (reset.asBool || io.ctrl.linkInitReset).asAsyncReset
      )(RegInit(false.B))
      when(plErrorPulse) {
        suppress := true.B
      }
      suppress
    }

  io.rdi.plError := plErrorPulse

  io.rdi.plValid := Mux(
    mainbandActive && !suppressPlValidAfterError,
    rxController.io.rdi.plValid,
    false.B
  )
  io.rdi.plData := Mux(
    mainbandActive,
    rxController.io.rdi.plData,
    0.U
  )
}
