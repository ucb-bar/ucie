/*
  Carries the LINKINIT scrambler reset (sec 4.5.3.5) into txDivClk as a toggle.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.utils._
import chisel3._

class ScramblerResetCrossingIO extends Bundle {
  val pulse = Input(Bool())
  val txDivClk = Input(Clock())
  val txDivRst = Input(Bool())
  val txResetLfsr = Output(Bool())
}

// Transmit only, because rxDivClk can stay parked through LINKINIT.
class ScramblerResetCrossing(separateDivClocks: Boolean = false)
    extends Module {
  val io = IO(new ScramblerResetCrossingIO)

  val bypass = !separateDivClocks

  // Nothing waits for the ack, because no word leaves without txDivClk edges.
  val txSource = Module(new TogglePulseSource(bypass))

  // Reset with no txDivClk edge, as the transmit domain is (sec 10.1.1).
  val txSink = withClockAndReset(io.txDivClk, io.txDivRst.asAsyncReset) {
    Module(new TogglePulseSink(bypass))
  }

  txSource.io.pulse := io.pulse
  txSink.io.reqToggle := txSource.io.reqToggle
  txSource.io.ackToggle := txSink.io.ackToggle

  // One cycle is enough, because a seed load wins over an increment.
  io.txResetLfsr := txSink.io.pulse
}
