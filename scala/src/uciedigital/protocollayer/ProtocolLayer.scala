/*
  The Protocol Layer top level. The transmit path runs on txLclk and the receive
  path on rxLclk, as the FDI data groups do. sec 10.2.2 wants one clock, so this
  design does not comply.
 */

package edu.berkeley.cs.uciedigital.protocollayer

import chisel3._
import chisel3.util.ShiftRegisters
import chisel3.layer.block
import chisel3.layers.Verification
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.utils._

class ProtocolLayer(
    val fdiParams: FdiParams = FdiParams(64, 32),
    val sbParams: SidebandParams = new SidebandParams(),
    val separateDivClocks: Boolean = false
) extends Module {
  val io = IO(new ProtocolLayerIO(fdiParams))

  // ===========================================================================
  // Clocks and modules
  // ===========================================================================
  // In the single-clock build the divided clocks are this clock, and every
  // crossing below collapses. The divided domains reset asynchronously, so a
  // parked clock still holds their flops at reset (sec 10.2.1).
  val bypass = !separateDivClocks
  val ctrlClock = clock
  val ctrlReset = reset.asBool

  // Everything outside the two data paths is on the control clock.
  val txClock = if (separateDivClocks) io.clkRst.txDivClk else ctrlClock
  val txReset: Reset =
    if (separateDivClocks) io.clkRst.txDivRst.asAsyncReset else reset
  val rxClock = if (separateDivClocks) io.clkRst.rxDivClk else ctrlClock
  val rxReset: Reset =
    if (separateDivClocks) io.clkRst.rxDivRst.asAsyncReset else reset

  val stateController = Module(new ProtocolStateController())
  val sidebandChannel = Module(
    new ProtocolSidebandChannel(
      sbMsgWidth = sbParams.sbNodeMsgWidth,
      fdiNcWidth = fdiParams.ncWidth,
      numCredits = sbParams.maxCrd,
      queueDepths = SidebandPriorityQueueDepths()
    )
  )

  // ===========================================================================
  // FDI state handling
  // ===========================================================================
  stateController.io.ctrl <> io.ctrl
  stateController.io.fdi.plStateSts := io.fdi.plStateSts
  stateController.io.fdi.plInbandPres := io.fdi.plInbandPres
  stateController.io.fdi.plRxActiveReq := io.fdi.plRxActiveReq
  stateController.io.fdi.plProtocol := io.fdi.plProtocol
  stateController.io.fdi.plProtocolFlitFmt := io.fdi.plProtocolFlitFmt
  stateController.io.fdi.plProtocolVld := io.fdi.plProtocolVld
  stateController.io.fdi.plClkReq := io.fdi.plClkReq
  stateController.io.fdi.plWakeAck := io.fdi.plWakeAck
  stateController.io.fdi.plPhyInRecenter := io.fdi.plPhyInRecenter
  stateController.io.fdi.plPhyInL1 := io.fdi.plPhyInL1
  stateController.io.fdi.plPhyInL2 := io.fdi.plPhyInL2
  stateController.io.fdi.plSpeedmode := io.fdi.plSpeedmode
  stateController.io.fdi.plMaxSpeedmode := io.fdi.plMaxSpeedmode
  stateController.io.fdi.plLnkCfg := io.fdi.plLnkCfg
  stateController.io.fdi.plCError := io.fdi.plCError
  stateController.io.fdi.plNfError := io.fdi.plNfError
  stateController.io.fdi.plTrainError := io.fdi.plTrainError
  stateController.io.sidebandParityErrEvent :=
    sidebandChannel.io.layer.status.sbParityErrEvent

  io.fdi.lpStateReq := stateController.io.fdi.lpStateReq
  io.fdi.lpLinkError := stateController.io.fdi.lpLinkError
  io.fdi.lpWakeReq := stateController.io.fdi.lpWakeReq
  io.fdi.lpClkAck := stateController.io.fdi.lpClkAck
  io.fdi.lpRxActiveSts := stateController.io.fdi.lpRxActiveSts

  // ===========================================================================
  // Mainband TX datapath
  // ===========================================================================
  // The status and the stall reach txLclk as settled levels, so the control
  // clock knows when the wire obeys them.
  val txAllowed = Module(new SettledLevel(bypass = bypass))
  txAllowed.io.desired := stateController.io.txAllowed
  txAllowed.io.dstClock := txClock
  txAllowed.io.dstReset := txReset.asBool
  val stallBlock = Module(new SettledLevel(bypass = bypass))
  stallBlock.io.dstClock := txClock
  stallBlock.io.dstReset := txReset.asBool

  // presented means the last beat on the wire was not taken. A stall keeps it
  // until pl_trdy takes it (sec 10.3.2 rule 7). It is rebuilt from the wire
  // each cycle.
  val presented = withClockAndReset(txClock, txReset) {
    RegNext(io.fdi.lpValid && !io.fdi.plTrdy, false.B)
  }

  // The clean boundary of sec 10.3.2 rule 4. It stays set while the block
  // holds. A flop, so it can cross.
  val quiescedTx = withClockAndReset(txClock, txReset) {
    RegNext(stallBlock.io.out && !io.fdi.lpValid, false.B)
  }
  val quiescedSync = Sync3(quiescedTx, bypass = bypass)

  // A new block waits for the last release, or the old quiesce would
  // acknowledge it. After a teardown it falls only once txLclk has seen
  // txAllowed low.
  val teardownHold = !stateController.io.txAllowed &&
    !(txAllowed.io.settled && !txAllowed.io.cmd)
  stallBlock.io.desired := Mux(
    stallBlock.io.cmd,
    io.fdi.plStallReq || teardownHold,
    io.fdi.plStallReq && !quiescedSync
  )

  // sec 10.3.2 rules 4, 5 and 8. It rises only after txLclk has drained and
  // falls with the request. It never reads the chip's valid, which can be high
  // for a later beat.
  val lpStallAck = stallBlock.io.cmd && quiescedSync
  io.fdi.lpStallAck := lpStallAck
  stateController.io.lpStallAck := lpStallAck

  // The chip's Decoupled holds the beat until taken, so nothing is buffered.
  // A teardown leaves Active, which withdraws the beat.
  io.fdi.lpValid := io.mainbandTx.valid && txAllowed.io.out &&
    !(stallBlock.io.out && !presented)
  io.fdi.lpIrdy := io.fdi.lpValid

  // sec 10.2.1 wants 0 out of reset, but the data means nothing without
  // lp_valid, so it follows the chip's port and saves a 512-bit gate.
  io.fdi.lpData := io.mainbandTx.bits.data
  io.mainbandTx.ready := io.fdi.lpValid && io.fdi.plTrdy

  // Table 10-3 makes lp_stream relevant only while lp_valid is 1, and this
  // configuration only ever carries Stack 0 Streaming.
  io.fdi.lpStream := StreamId.stack0Streaming

  // ===========================================================================
  // Mainband RX datapath
  // ===========================================================================
  // sec 10.2.4 has no backpressure, so the beat is a wire. The one gate is
  // close-only, because no level that opens it can cross a parked rxLclk
  // before the first Flit. sec 10.3.1 scopes pl_error to the beat it drops.
  val rxCloseSync = withClockAndReset(rxClock, rxReset) {
    Sync3.from(ctrlClock, ctrlReset)(
      stateController.io.rxClose,
      bypass = bypass
    )
  }

  // The chip side takes every beat, so mainbandRx has no ready.
  io.mainbandRx.valid := io.fdi.plValid && !io.fdi.plError && !rxCloseSync
  io.mainbandRx.bits.data := io.fdi.plData

  // pl_error is a pulse on rxLclk, so it crosses as an event. Two inside one
  // round trip count once, which a log of "seen" accepts.
  val plErrorSource = withClockAndReset(rxClock, rxReset) {
    Module(new TogglePulseSource(bypass))
  }
  val plErrorSink = Module(new TogglePulseSink(bypass))
  plErrorSource.io.pulse := io.fdi.plError
  plErrorSink.io.reqToggle := plErrorSource.io.reqToggle
  plErrorSource.io.ackToggle := plErrorSink.io.ackToggle
  stateController.io.plErrorSeen := plErrorSink.io.pulse

  // ===========================================================================
  // Sideband
  // ===========================================================================
  sidebandChannel.io.fdi.in.bits := io.fdi.plCfg
  sidebandChannel.io.fdi.in.valid := io.fdi.plCfgVld
  sidebandChannel.io.fdi.txCreditReturn := io.fdi.plCfgCrd
  sidebandChannel.io.fdi.txHold := !io.fdi.plWakeAck
  io.fdi.lpCfg := sidebandChannel.io.fdi.out.bits
  io.fdi.lpCfgVld := sidebandChannel.io.fdi.out.valid
  io.fdi.lpCfgCrd := sidebandChannel.io.fdi.rxCreditReturn

  // TODO: Expose a protocol-layer sideband interface once upper-layer messages
  // exist. Table 7-2 note a permits no Adapter request over FDI sideband.
  sidebandChannel.io.layer.in.bits := 0.U
  sidebandChannel.io.layer.in.valid := false.B
  sidebandChannel.io.layer.out.ready := true.B

  // ===========================================================================
  // Status
  // ===========================================================================
  io.status.linkState := stateController.io.status.linkState
  io.status.negotiatedProtocolValid :=
    stateController.io.status.negotiatedProtocolValid
  io.status.negotiatedProtocol := stateController.io.status.negotiatedProtocol
  io.status.negotiatedFlitFormat :=
    stateController.io.status.negotiatedFlitFormat
  io.status.stalled := stateController.io.status.stalled
  io.status.phyInRecenter := stateController.io.status.phyInRecenter
  io.status.phyInL1 := stateController.io.status.phyInL1
  io.status.phyInL2 := stateController.io.status.phyInL2
  io.status.speedmode := stateController.io.status.speedmode
  io.status.maxSpeedmode := stateController.io.status.maxSpeedmode
  io.status.lnkCfg := stateController.io.status.lnkCfg
  io.status.errCorrectable := stateController.io.status.errCorrectable
  io.status.errUncorrectable := stateController.io.status.errUncorrectable
  io.status.fdiNonFatalError := stateController.io.status.fdiNonFatalError
  io.status.fdiFatalError := stateController.io.status.fdiFatalError

  // ===========================================================================
  // Verification
  // ===========================================================================
  // Each assertion checks a guarantee of the Adapter, so it fires on the
  // Adapter defect it names. Each reads its pins in their own clock.
  block(Verification) {
    val prevProtocolVldReg = RegNext(io.fdi.plProtocolVld, false.B)
    val prevStateStsReg = RegNext(io.fdi.plStateSts, FDIState.reset)
    val rxActive = io.fdi.plRxActiveReq && io.fdi.lpRxActiveSts

    block(Verification.Assert) {
      when(prevProtocolVldReg && !io.fdi.plProtocolVld) {
        assert(
          (io.fdi.plStateSts === FDIState.linkError) ||
            (io.fdi.plStateSts === FDIState.reset),
          "FATAL: Tbl 10-3 drops pl_protocol_vld only into LinkError or Reset"
        )
      }

      // sec 10.2.7 rule 7 exempts the LinkError exit, which is permitted to
      // move the status first and drop the handshake on the way out.
      when(
        (prevStateStsReg === FDIState.active) &&
          (io.fdi.plStateSts =/= FDIState.active) &&
          (io.fdi.plStateSts =/= FDIState.linkError)
      ) {
        assert(
          !io.fdi.plRxActiveReq && !io.fdi.lpRxActiveSts,
          "FATAL: sec 10.2.7 rule 5 wants rx_active down before leaving Active"
        )
      }
      assert(
        !sidebandChannel.io.layer.out.valid,
        "FATAL: Tbl 7-2 note a permits no Adapter request over FDI sideband"
      )
    }

    block(Verification.Cover) {
      cover(io.fdi.plNfError, "ProtocolLayerFdiNonFatalError")
      cover(io.fdi.plTrainError, "ProtocolLayerFdiTrainError")
    }

    // sec 10.3.2 rule 6 is checked against the copy of the ack that the wire
    // obeys. The status copy lands with txAllowed, so Reset is never late.
    withClockAndReset(txClock, txReset) {
      val ackTx = stallBlock.io.out && Sync3(quiescedSync, bypass = bypass)
      val inResetTx = Sync3.from(ctrlClock, ctrlReset)(
        io.fdi.plStateSts === FDIState.reset,
        bypass = bypass
      )
      block(Verification.Assert) {
        when(ackTx) {
          assert(
            !io.fdi.lpValid && !io.fdi.lpIrdy,
            "FATAL: sec 10.3.2 rule 6 wants no beat while lp_stallack is high"
          )
        }
        when(inResetTx) {
          assert(
            !io.fdi.lpIrdy,
            "FATAL: Tbl 10-3 forbids lp_irdy while pl_state_sts is Reset"
          )
        }
      }
    }

    // Table 10-3 allows pl_error, and sec 10.2.7 rule 6 allows pl_valid, only
    // with the receiver active. The handshake order keeps this with no gate, so
    // the flag checks it. A beat can come 3 edges after the handshake drops.
    val rxSeen = withClockAndReset(rxClock, rxReset) {
      val inRetrain = Sync3.from(ctrlClock, ctrlReset)(
        io.fdi.plStateSts === FDIState.retrain,
        bypass = bypass
      )
      val inPmNak = Sync3.from(ctrlClock, ctrlReset)(
        io.fdi.plStateSts === FDIState.activePmNak,
        bypass = bypass
      )
      block(Verification.Assert) {
        when(io.fdi.plValid) {
          assert(
            io.fdi.plStream === StreamId.stack0Streaming,
            "FATAL: Tbl 10-3 wants pl_stream at 04h for Stack 0 Streaming"
          )
        }
      }
      block(Verification.Cover) {
        cover(io.fdi.plError, "ProtocolLayerFdiErrorBeatDropped")
        cover(io.fdi.plValid && inRetrain, "ProtocolLayerReceivesInRetrain")
        cover(io.fdi.plValid && inPmNak, "ProtocolLayerReceivesInPmNak")
      }
      val allowed = Sync3.from(ctrlClock, ctrlReset)(rxActive, bypass = bypass)
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
    block(Verification.Assert) {
      assert(
        !Sync3(rxSeen, bypass = bypass) || rxActive,
        "FATAL: Tbl 10-3 and sec 10.2.7 rule 6 allow pl_valid and pl_error " +
          "only while the receiver is active"
      )
    }
  }
}
