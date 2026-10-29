/*
  The clock crossings. RDI breaks sec 10.1.2's one clock, so each is explicit.
 */

package edu.berkeley.cs.uciedigital.utils

import chisel3._

object Sync3 {
  // A level must hold for three destination edges. Events cross as toggles.
  def apply(in: Bool, init: Bool = false.B, bypass: Boolean = false): Bool =
    if (bypass) in else RegNext(RegNext(RegNext(in, init), init), init)

  // Note: Each bit settles on its own, so never use it for a value that steps.
  def word(in: UInt, bypass: Boolean = false): UInt =
    if (bypass) in
    else VecInit(in.asBools.map(b => apply(b))).asUInt

  // A source decode can glitch, so one flop in the source domain removes it.
  // Note: Call this from the destination scope.
  def from(srcClock: Clock, srcReset: Bool)(
      in: Bool,
      init: Bool = false.B,
      bypass: Boolean = false
  ): Bool =
    if (bypass) in
    else apply(withClockAndReset(srcClock, srcReset)(RegNext(in, init)), init)

  // Rises with `in` at once and falls on the third edge after `in` falls. It
  // reads 0 from reset with no edge, in an asynchronous reset domain.
  // Note: `in` must be a control-domain flop that feeds only this logic.
  def setAsync(in: Bool, bypass: Boolean = false): Bool =
    if (bypass) in
    else {
      // The arc through the OR has no destination flop, so give it a false path
      // or a max delay, as for an asynchronous set.
      // `in` rises only when no word is due (sec 10.2.8 steps 4 and 5).
      in || apply(in)
    }

  // Raw while parked, so the first edge after a park reads the current value.
  // Note: `parked` must rise when no edge is due, and fall 3 or more edges
  // after the edges start again.
  def parkable(in: Bool, parked: Bool, bypass: Boolean = false): Bool =
    if (bypass) in
    else {
      // The arcs through the Mux have no destination flop, so false-path them.
      // `in` must hold before `parked` falls, so the two Mux inputs are equal.
      Mux(parked, in, apply(in))
    }
}

// A level crossing with an echo, so one change at most is in flight.
// Note: `settled` with `cmd` equal to v means the destination holds v.
class SettledLevel(init: Boolean = false, bypass: Boolean = false)
    extends Module {
  val io = IO(new Bundle {
    val desired = Input(Bool())
    val cmd = Output(Bool())
    val settled = Output(Bool())
    val dstClock = Input(Clock())
    val dstReset = Input(Bool())
    val out = Output(Bool())
  })

  if (bypass) {
    // Still a register, so the single-clock build gates from a flop.
    val cmd = RegNext(io.desired, init.B)
    io.cmd := cmd
    io.settled := true.B
    io.out := cmd
  } else {
    val cmd = RegInit(init.B)

    // The reset is asynchronous, so the destination holds init while parked.
    val out = withClockAndReset(io.dstClock, io.dstReset.asAsyncReset) {
      Sync3(cmd, init.B)
    }
    val settled = Sync3(out, init.B) === cmd
    when(settled) {
      cmd := io.desired
    }
    io.cmd := cmd
    io.settled := settled
    io.out := out
  }
}

// In strobe mode the partner parks its forwarded clock and rxDivClk stops, so
// a toggle with an acknowledge carries the event.
class TogglePulseSource(bypass: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val pulse = Input(Bool())
    val ackToggle = Input(Bool())
    val reqToggle = Output(Bool())
    val outstanding = Output(Bool())
  })

  if (bypass) {
    // A wire, because state machines need the same-cycle arrival on one clock.
    io.reqToggle := io.pulse
    io.outstanding := false.B
  } else {
    val req = RegInit(false.B)
    val ackSync = Sync3(io.ackToggle)

    // Derived from the toggles, so no separate busy flag can fall out of step.
    val outstanding = req =/= ackSync

    // A pulse while one is in flight is dropped, so callers see `outstanding`.
    when(io.pulse && !outstanding) {
      req := !req
    }

    io.reqToggle := req
    io.outstanding := outstanding
  }
}

// Acknowledges in the cycle it gives the event, so no ack is forgotten.
class TogglePulseSink(bypass: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val reqToggle = Input(Bool())
    val pulse = Output(Bool())
    val ackToggle = Output(Bool())
  })

  if (bypass) {
    io.pulse := io.reqToggle
    io.ackToggle := false.B
  } else {
    val reqSync = Sync3(io.reqToggle)
    val acked = RegNext(reqSync, false.B)

    io.pulse := reqSync =/= acked
    io.ackToggle := acked
  }
}
