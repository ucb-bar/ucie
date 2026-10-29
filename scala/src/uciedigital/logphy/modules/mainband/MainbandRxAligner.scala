/*
  Aligns received words to the partner's boundary at any offset (sec 4.1.2).
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

class MainbandRxAlignerIO(afeParams: AfeParams) extends Bundle {
  val in = Input(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  val inValid = Input(Bool())

  val out = Output(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  val burstStart = Output(Bool())

  val locked = Output(Bool())
  val offset = Output(UInt(log2Ceil(afeParams.mbSerializerRatio).W))
  val offsetMoved = Output(Bool())
}

// Our capture counter starts at our reset, so any UI can start a partner word.
class MainbandRxAligner(afeParams: AfeParams = new AfeParams()) extends Module {
  val io = IO(new MainbandRxAlignerIO(afeParams))

  val serRatio = afeParams.mbSerializerRatio
  val offsetW = log2Ceil(serRatio)
  val groupHigh = ValidFraming.GroupUI / 2

  // A flit has at most 4 low Valid UI (sec 10.1.4), so 5 marks a gap.
  val gapUI = ValidFraming.GroupUI / 2 + 1

  val prevData =
    RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(serRatio.W))))
  val prevValid = RegInit(0.U(serRatio.W))
  when(io.inValid) {
    prevData := io.in.data
    prevValid := io.in.valid
  }

  // The shift less one. It resets to pass the raw word until the first burst.
  val shiftReg = RegInit((serRatio - 1).U(offsetW.W))
  val locked = RegInit(false.B)
  val moved = RegInit(false.B)

  // Starts at zero, so a reset release mid-burst does not start a burst.
  val idleRun = RegInit(0.U(log2Ceil(gapUI + 1).W))
  val inFirstGroup = RegInit(false.B)

  // ===========================================================================
  // Burst detection
  // ===========================================================================
  // Bit 0 is the first UI.
  val word = io.in.valid
  val anyValid = word.orR
  val trailingLow = PriorityEncoder(Reverse(word))

  // At most one start per word, and each bit is a small AND.
  val starts = VecInit(Seq.tabulate(serRatio) { i =>
    val lowBefore =
      if (i >= gapUI) !word(i - 1, i - gapUI).orR
      else {
        val lowHere = if (i == 0) true.B else !word(i - 1, 0).orR
        lowHere && idleRun >= (gapUI - i).U
      }
    word(i) && lowBefore
  }).asUInt
  val burstStart = io.inValid && starts.orR
  val startPos = PriorityEncoder(starts)

  // A fall before the start ends the last burst.
  val falls = ~word & Cat(word(serRatio - 2, 0), prevValid(serRatio - 1))
  val afterStart = VecInit(Seq.tabulate(serRatio) { i =>
    if (i == 0) false.B else starts(i - 1, 0).orR
  }).asUInt
  val groupFalls = Mux(burstStart, falls & afterStart, falls)
  val firstFall = PriorityEncoder(groupFalls)
  val groupEnd =
    io.inValid && (burstStart || inFirstGroup) && groupFalls.orR

  // A burst that starts at UI p needs the shift p, or a full word at p = 0.
  val fromRise = Mux(
    startPos === 0.U,
    (serRatio - 1).U(offsetW.W),
    (startPos - 1.U)(offsetW - 1, 0)
  )

  // The fall comes groupHigh UI after the start, which can be a word earlier.
  val fromFall = Mux(
    firstFall < (groupHigh + 1).U,
    (firstFall + (serRatio - groupHigh - 1).U)(offsetW - 1, 0),
    (firstFall - (groupHigh + 1).U)(offsetW - 1, 0)
  )

  when(io.inValid) {
    inFirstGroup := (burstStart || inFirstGroup) && !groupFalls.orR

    // A word is longer than gapUI, so a word with no Valid UI fills the run.
    idleRun := Mux(
      anyValid && trailingLow < gapUI.U,
      trailingLow(idleRun.getWidth - 1, 0),
      gapUI.U
    )
  }

  // Each burst sets it again, because sec 5.11 gates in any multiple of 8 UI.
  when(burstStart) {
    shiftReg := fromRise
  }

  // The first fall corrects the shift, so up to 3 lost leading UI do no harm.
  when(groupEnd) {
    shiftReg := fromFall
    locked := true.B
  }
  when(burstStart && locked && fromRise =/= shiftReg) {
    moved := true.B
  }

  // ===========================================================================
  // Realignment
  // ===========================================================================
  // No register, so a word leaves in the cycle its last UI arrives.
  def realign(curr: UInt, older: UInt): UInt =
    ((Cat(curr, older) >> 1) >> shiftReg)(serRatio - 1, 0)

  // A start at UI 0 is a whole new word, aligned as it is.
  val startsAtZero = starts(0)
  def select(curr: UInt, older: UInt): UInt =
    Mux(startsAtZero, curr, realign(curr, older))

  // A start inside the old shift's window makes this word idle.
  val oldWindow =
    VecInit(Seq.tabulate(serRatio)(i => i.U <= shiftReg)).asUInt
  val startInOldWindow = (starts & oldWindow).orR

  // One shift for all Lanes. Deskew training covers skew between Lanes.
  for (lane <- 0 until afeParams.mbLanes) {
    io.out.data(lane) := select(io.in.data(lane), prevData(lane))
  }
  io.out.valid := Mux(
    burstStart && startInOldWindow,
    Mux(startsAtZero, word, 0.U),
    realign(word, prevValid)
  )

  // Raw, because sec 4.5.3.3.3 sends clock repair with no Valid to align by.
  io.out.clkP := io.in.clkP
  io.out.clkN := io.in.clkN
  io.out.trk := io.in.trk

  io.burstStart := burstStart
  io.locked := locked

  // How far into the partner's word our word starts.
  io.offset := (serRatio - 1).U - shiftReg
  io.offsetMoved := moved

  // Bring-up status that no logic reads.
  dontTouch(io.locked)
  dontTouch(io.offset)
  dontTouch(io.offsetMoved)
}
