/*
  The forwarded clock and Data Lane park in mainband gaps (sec 4.1.3, sec 5.11).
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

class MainbandClockWaveformIO(nLanes: Int, serRatio: Int) extends Bundle {
  val txDemand = Input(Bool())
  val analogReady = Input(Bool())
  val txLastBit = Input(Vec(nLanes, Bool()))
  val continuous = Input(Bool())
  val quarterRate = Input(Bool())
  val parkLow = Input(Bool())

  val clkP = Output(UInt(serRatio.W))
  val clkN = Output(UInt(serRatio.W))
  val parkLevel = Output(Vec(nLanes, Bool()))
  val inIdle = Output(Bool())
  val owesPostamble = Output(Bool())
  val predriveWord = Output(Bool())
}

class MainbandClockWaveform(nLanes: Int, serRatio: Int) extends Module {
  require(serRatio % 8 == 0, "serRatio must be a whole number of 8 UI frames")

  val io = IO(new MainbandClockWaveformIO(nLanes, serRatio))

  val halfRatePostambleUI = 16
  val quarterRatePostambleUI = 32

  // 8 UI of sec 5.11's 1 to 8, so the gated period stays a multiple of 8 UI.
  val predriveUI = 8

  val postambleUI =
    Mux(io.quarterRate, quarterRatePostambleUI.U, halfRatePostambleUI.U)
  val postambleWordSpan =
    (quarterRatePostambleUI + serRatio - 1) / serRatio

  // ===========================================================================
  // Gap tracking
  // ===========================================================================
  // Saturates one word after the longest postamble.
  val ctrMax = postambleWordSpan + 1
  val idleWordCtr = RegInit(ctrMax.U(log2Ceil(ctrMax + 1).W))

  // Before the first word there is no gap, so nothing parks or pre-drives.
  val everTransmitted = RegInit(false.B)

  val predrivePending = RegInit(false.B)
  val canSend = io.txDemand && io.analogReady
  val predriveNow = predrivePending && canSend
  val transmitting = canSend && !predriveNow

  val lastBit = RegInit(VecInit(Seq.fill(nLanes)(false.B)))
  when(transmitting) {
    lastBit := io.txLastBit
    everTransmitted := true.B
  }

  when(transmitting) {
    idleWordCtr := 0.U
  }.elsewhen(idleWordCtr =/= ctrMax.U) {
    idleWordCtr := idleWordCtr + 1.U
  }

  val postambleUiSoFar = idleWordCtr * serRatio.U

  // No demand, because a pre-drive word must not count as a gap and re-arm.
  val inGap = !canSend && everTransmitted

  // The gating event of sec 5.11 starts at the first parked UI.
  val wordHasParkedUI = !io.continuous && inGap &&
    ((postambleUiSoFar + (serRatio - 1).U) >= postambleUI)
  val parkingStart = wordHasParkedUI && !RegNext(wordHasParkedUI, false.B)

  val parkHigh = RegInit(false.B)

  // The first parked UI is in this word, so the register alone is a word late.
  val parkHighNow = !io.parkLow && Mux(parkingStart, !parkHigh, parkHigh)
  when(parkingStart) {
    parkHigh := Mux(io.parkLow, parkHigh, !parkHigh)
    predrivePending := true.B
  }

  // Free running drops it, or it would stop the clock for 8 UI (sec 4.1.4).
  when(predriveNow || io.continuous) {
    predrivePending := false.B
  }

  // ===========================================================================
  // Clock words
  // ===========================================================================
  def levelPair(high: Bool): (Bool, Bool) =
    ForwardedClock.parkPair(high, io.quarterRate)

  val (parkP, parkN) = levelPair(parkHighNow)
  val (lowP, lowN) = levelPair(false.B)

  val clkPBits = Wire(Vec(serRatio, Bool()))
  val clkNBits = Wire(Vec(serRatio, Bool()))
  for (i <- 0 until serRatio) {
    // A postamble can end inside a word, so the check is per UI.
    val inPostamble = (postambleUiSoFar + i.U) < postambleUI
    val togglePBit = Mux(
      io.quarterRate,
      ForwardedClock.phase1(i, true).B,
      ForwardedClock.phase1(i, false).B
    )
    val toggleNBit = Mux(
      io.quarterRate,
      ForwardedClock.phase2(i, true).B,
      ForwardedClock.phase2(i, false).B
    )

    when(predriveNow) {
      // sec 5.11 puts the low period just before the clock starts again.
      clkPBits(i) := Mux(i.U < predriveUI.U, lowP, togglePBit)
      clkNBits(i) := Mux(i.U < predriveUI.U, lowN, toggleNBit)
    }.elsewhen(transmitting || io.continuous || inPostamble) {
      clkPBits(i) := togglePBit
      clkNBits(i) := toggleNBit
    }.otherwise {
      clkPBits(i) := parkP
      clkNBits(i) := parkN
    }
  }

  io.clkP := clkPBits.asUInt
  io.clkN := clkNBits.asUInt

  // The last bit meets sec 5.11 for both termination cases.
  io.parkLevel := lastBit
  io.inIdle := !transmitting
  io.owesPostamble := inGap && (postambleUiSoFar < postambleUI)
  io.predriveWord := predriveNow
}
