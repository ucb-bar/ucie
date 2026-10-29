/*
  The Raw Format mainband path of the D2D Adapter. Table 3-6 Format 1 passes
  protocol data to RDI without change, so both data buses are wires. The
  transmit gates run on txDivClk and the receive gate on rxDivClk, and each
  takes its control-clock level through SettledLevel or Sync3.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import edu.berkeley.cs.uciedigital.rdi._
import chisel3._
import chisel3.util.ShiftRegisters
import chisel3.layer.block
import chisel3.layers.Verification
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.utils._

class D2DMainbandModule(
    val fdiParams: FdiParams,
    val rdiParams: RdiParams,
    val separateDivClocks: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val clkRst = new MainbandClkRstIO
    val state = new D2DMainbandStateIO
    val rdi = new Bundle {
      val lpIrdy = Output(Bool())
      val lpValid = Output(Bool())
      val lpData = Output(Bits((8 * rdiParams.nBytes).W))
      val plTrdy = Input(Bool())

      val plValid = Input(Bool())
      val plData = Input(Bits((8 * rdiParams.nBytes).W))
      val plError = Input(Bool())
    }
    val fdi = new Bundle {
      val lpIrdy = Input(Bool())
      val lpValid = Input(Bool())
      val lpData = Input(Bits((8 * fdiParams.nBytes).W))
      val plTrdy = Output(Bool())

      val plValid = Output(Bool())
      val plData = Output(Bits((8 * fdiParams.nBytes).W))
      val plError = Output(Bool())
    }
  })

  // ===========================================================================
  // Clocks and resets
  // ===========================================================================
  // With one clock, the divided clocks are this clock and the crossings are
  // removed. The divided domains reset asynchronously, so a stopped clock
  // still holds their flops in reset (sec 10.1.1 and sec 10.2.1).
  val bypass = !separateDivClocks
  val ctrlClock = clock
  val ctrlReset = reset.asBool
  val txClock = if (separateDivClocks) io.clkRst.txDivClk else ctrlClock
  val txReset: Reset =
    if (separateDivClocks) io.clkRst.txDivRst.asAsyncReset else ctrlReset
  val rxClock = if (separateDivClocks) io.clkRst.rxDivClk else ctrlClock
  val rxReset: Reset =
    if (separateDivClocks) io.clkRst.rxDivRst.asAsyncReset else ctrlReset

  // ===========================================================================
  // Transmit gates
  // ===========================================================================
  def crossing(): SettledLevel = {
    val s = Module(new SettledLevel(bypass = bypass))
    s.io.dstClock := txClock
    s.io.dstReset := txReset.asBool
    s
  }
  val txGate = crossing()
  val txSink = crossing()

  // Each level crosses on its own. One level waits while the other changes,
  // or txDivClk could see both open for a few edges.
  txGate.io.desired := io.state.txGate &&
    !(txSink.io.cmd && !txSink.io.settled)
  txSink.io.desired := io.state.txSink &&
    !(txGate.io.cmd && !txGate.io.settled)
  io.state.txGateCmd := txGate.io.cmd
  io.state.txGateSettled := txGate.io.settled
  io.state.txSinkCmd := txSink.io.cmd
  io.state.txSinkSettled := txSink.io.settled

  // ===========================================================================
  // Transmit path
  // ===========================================================================
  // lp_data has no gate, because sec 10.1.1 gives it meaning only with
  // lp_valid.
  io.rdi.lpData := io.fdi.lpData

  // lp_valid and lp_irdy share one gate, so a beat is never split. pl_trdy uses
  // the same gate. The sink accepts a beat and does not send it.
  io.rdi.lpValid := txGate.io.out && io.fdi.lpValid
  io.rdi.lpIrdy := txGate.io.out && io.fdi.lpIrdy
  io.fdi.plTrdy := (txGate.io.out && io.rdi.plTrdy) || txSink.io.out

  // ===========================================================================
  // Receive path
  // ===========================================================================
  // This gate only shuts. It is open from reset. It shuts from a teardown until
  // both state machines are in Reset again. No Active term opens it, because a
  // stopped receive clock would deliver that level after the first Flits.
  val rxOpen = withClockAndReset(rxClock, rxReset) {
    !Sync3.from(ctrlClock, ctrlReset)(io.state.rxTeardown, bypass = bypass)
  }
  io.fdi.plData := io.rdi.plData

  // pl_error goes through the same gate as pl_valid (Table 10-3).
  io.fdi.plValid := rxOpen && io.rdi.plValid
  io.fdi.plError := rxOpen && io.rdi.plError

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    // Table 3-6 Format 1 gives zero latency and no loss in both directions. The
    // one exception is a beat the sink takes. RDI never also gets that beat.
    withClockAndReset(txClock, txReset) {
      val fdiBeat = io.fdi.lpValid && io.fdi.lpIrdy
      val fdiBeatAccepted = fdiBeat && io.fdi.plTrdy
      val rdiBeatSent = io.rdi.lpValid && io.rdi.lpIrdy && io.rdi.plTrdy
      assert(
        !(txGate.io.out && txSink.io.out),
        "the transmit gate and the sink are never open together"
      )
      assert(
        fdiBeatAccepted === (rdiBeatSent || (fdiBeat && txSink.io.out)),
        "an FDI beat is accepted exactly when it is sent on RDI or sunk"
      )
      when(rdiBeatSent) {
        assert(
          io.rdi.lpData === io.fdi.lpData,
          "the mainband must forward TX data unmodified"
        )
      }
    }

    // sec 10.2.7 rule 6 depends on order and not on a gate, so this checks it.
    // A beat can come up to three receive edges after rxAllowed falls, because
    // of the PHY synchroniser.
    val plValidSeen = withClockAndReset(rxClock, rxReset) {
      when(io.fdi.plValid) {
        assert(
          io.fdi.plData === io.rdi.plData && io.rdi.plValid,
          "the mainband must forward RX data unmodified in the same cycle"
        )
      }
      val allowed = Sync3.from(ctrlClock, ctrlReset)(
        io.state.rxAllowed,
        bypass = bypass
      )
      val trailing =
        (allowed +: ShiftRegisters(allowed, 3, false.B, true.B)).reduce(_ || _)
      val seen = RegInit(false.B)
      when(allowed) {
        seen := false.B
      }.elsewhen((io.fdi.plValid || io.fdi.plError) && !trailing) {
        seen := true.B
      }
      seen
    }

    // The control clock reads the flag only while rxAllowed is low.
    assert(
      !Sync3(plValidSeen, bypass = bypass) || io.state.rxAllowed,
      "FDI pl_valid or pl_error with RDI not Active or the receiver closed"
    )
  }
}
