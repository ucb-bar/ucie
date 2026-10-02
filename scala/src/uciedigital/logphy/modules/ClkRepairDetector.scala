/*
  Description:
    Runs the MBINIT.REPAIRCLK measurement on behalf of the MBInit responder.

  REPAIRCLK does not go through `PatternReader`. That module is a phase
  tracked comparison against a reference word, which is the right instrument
  for a pattern arriving on a clock the receiver has already recovered -- and
  the wrong one for the forwarded clock itself. At REPAIRCLK there is no
  recovered clock to trust and no trained sampling phase, so the clock and
  track lanes are oversampled by a locally divided copy of the main clock and
  scored on the shape of the waveform instead. See `phy/ClkRepair.scala`.

  This module is only the handshake around that: it presents the same
  `PatternReaderIO` the state machine already drives, opens a window in the
  PHY, and turns the verdict into the three per-lane status bits
  `MBINIT_REPAIRCLK_RESULT_RESP` carries.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

object ClkRepairDetectorState extends ChiselEnum {
  val sIdle, sMeasure, sHold, sResult = Value
}

class ClkRepairDetector(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {

    /** Driven exactly as `PatternReader`'s is, so the state machine does not
      * know which of the two answered it.
      */
    val interfaceIo = new PatternReaderIO(afeParams.mbLanes)

    /** The measurement, from the PHY by way of the register block. */
    val status = Input(new ClkRepairStatusIO)

    /** Opens a window. One signal: the PHY ungates its repair sampling clock,
      * releases the repair dividers and clears its counters while this is high.
      */
    val repairClkEn = Output(Bool())
  })

  require(
    afeParams.mbLanes >= ClkRepairStatus.Lanes,
    s"${afeParams.mbLanes} mainband lanes cannot carry " +
      s"${ClkRepairStatus.Lanes} REPAIRCLK status bits"
  )

  val state = RegInit(ClkRepairDetectorState.sIdle)
  val laneOk = RegInit(VecInit(Seq.fill(ClkRepairStatus.Lanes)(false.B)))

  io.interfaceIo.req.ready := state === ClkRepairDetectorState.sIdle
  io.repairClkEn := state === ClkRepairDetectorState.sMeasure

  io.interfaceIo.resp.valid := state === ClkRepairDetectorState.sResult
  // Lanes beyond the three REPAIRCLK measures report clean rather than
  // nothing: the result message only carries three bits, and a zero in the
  // rest would read as a lane that failed.
  io.interfaceIo.resp.bits.perLaneStatusBits.zipWithIndex.foreach {
    case (bit, lane) =>
      bit := (if (lane < ClkRepairStatus.Lanes) laneOk(lane) else true.B)
  }
  io.interfaceIo.resp.bits.aggregateStatus := laneOk.asUInt.andR

  switch(state) {
    is(ClkRepairDetectorState.sIdle) {
      when(io.interfaceIo.req.fire) {
        state := ClkRepairDetectorState.sMeasure
      }
    }
    is(ClkRepairDetectorState.sMeasure) {
      // The window is a fixed number of words, so it ends on its own. The
      // requester's `done` is not what closes it: that arrives when the
      // sideband exchange reaches the result substate, which is paced by
      // messages rather than by anything the lanes are doing, and thresholds
      // that depended on it would have to be retuned whenever the sideband
      // timing moved.
      when(io.status.done) {
        laneOk := io.status.laneOk
        state := ClkRepairDetectorState.sHold
      }
    }
    is(ClkRepairDetectorState.sHold) {
      // The result is ready but is not offered until the requester strobes
      // `done`, which is what `PatternReader` does and what the MBInit
      // responder is built around: it drives the sideband send from
      // `resp.valid`, so offering a result early makes it transmit
      // MBINIT_REPAIRCLK_RESULT_RESP before the matching REQ has arrived, and
      // the exchange deadlocks.
      //
      // The window still closes on its own, so what a count means stays a
      // property of the configuration rather than of sideband timing. Only
      // when the answer is handed over waits for the protocol.
      when(io.interfaceIo.done) {
        state := ClkRepairDetectorState.sResult
      }
    }
    is(ClkRepairDetectorState.sResult) {
      when(io.interfaceIo.resp.fire) {
        state := ClkRepairDetectorState.sIdle
      }
    }
  }
}
