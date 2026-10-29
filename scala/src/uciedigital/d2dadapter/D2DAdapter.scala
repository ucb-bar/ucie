/*
  The top level of the D2D Adapter (Chapter 3). It connects the Adapter LSM,
  the mainband path, the sideband path and the stall handshakes to FDI and RDI.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import edu.berkeley.cs.uciedigital.rdi._
import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import chisel3.layer.block
import chisel3.layers.Verification
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.utils._

class D2DAdapter(
    val fdiParams: FdiParams,
    val rdiParams: RdiParams,
    val sbParams: SidebandParams,
    val regLocators: Seq[(BigInt, BigInt)] = Seq(
      BigInt(0x1000) -> BigInt(0x2000)
    ),
    val separateDivClocks: Boolean = false,
    val divClockRatio: Int = 1
) extends Module {
  val io = IO(new D2DAdapterIO(fdiParams, rdiParams))
  val bypass = !separateDivClocks

  require(fdiParams.nBytes == rdiParams.nBytes)
  require(fdiParams.ncWidth == rdiParams.ncWidth)

  // ===========================================================================
  // Modules
  // ===========================================================================
  val linkManager = Module(new AdapterLSM(fdiParams, rdiParams, sbParams))
  val fdiStallRequester = Module(
    new StallRequester(sbParams.stallTimeoutCycles)
  )
  val rdiStallResponder = Module(new RdiStallResponder())
  val fdiClockRequester = Module(new ReqAckRequester())
  val fdiWakeResponder = Module(new ReqAckResponder())
  val rdiClockResponder = Module(new ReqAckResponder())
  val rdiWakeRequester = Module(new ReqAckRequester())

  val d2dSideband = Module(
    new D2DSidebandModule(fdiParams, sbParams, regLocators)
  )
  val d2dMainband = Module(
    new D2DMainbandModule(fdiParams, rdiParams, separateDivClocks)
  )

  // ===========================================================================
  // FDI status and errors
  // ===========================================================================
  val fdiPlStateSts = linkManager.io.fdi_pl_state_sts
  val fdiPlStatePrev = RegNext(fdiPlStateSts, FDIState.reset)
  val fdiLinkErrorToReset =
    fdiPlStatePrev === FDIState.linkError &&
      fdiPlStateSts === FDIState.reset

  // Table 10-3 keeps pl_trainerror high until FDI changes from LinkError to
  // Reset. The flop also ends the long logic path from the register block.
  val trainErrorReg = RegInit(false.B)
  when(io.regs.internalFatalError) {
    trainErrorReg := true.B
  }.elsewhen(fdiLinkErrorToReset) {
    trainErrorReg := false.B
  }

  io.fdi.txLclk := io.rdi.txLclk
  io.fdi.rxLclk := io.rdi.rxLclk

  io.fdi.plSpeedmode := io.rdi.plSpeedmode
  io.fdi.plMaxSpeedmode := io.rdi.plMaxSpeedmode
  io.fdi.plLnkCfg := io.rdi.plLnkCfg
  io.fdi.plStateSts := fdiPlStateSts
  io.fdi.plStream := StreamId.stack0Streaming
  io.fdi.plInbandPres := linkManager.io.fdi_pl_inband_pres
  io.fdi.plTrainError := trainErrorReg && !fdiLinkErrorToReset

  // Table 10-3 tells the Adapter to OR its internal errors with RDI pl_nferror.
  // The Table 9-12 enable bits control the reports of the Protocol Layer, not
  // these pins.
  io.fdi.plNfError := io.rdi.plNfError || io.regs.internalNonFatalError

  // This pin does not include RDI pl_cerror, unlike pl_nferror. In Raw Format,
  // Table 10-1 lets the Adapter only log it, and Table 10-3 sends the logged
  // errors here.
  io.fdi.plCError := io.regs.internalCorrError

  // Table 10-3 sets this signal while RDI trains or while the Adapter LSM is
  // not up. Active.PMNAK, L1 and L2 are up states. A flop drives the pin,
  // because a state decode ORed with a pin can glitch.
  val lsmNotUp = linkManager.io.link_state === FDIState.reset ||
    linkManager.io.link_state === FDIState.retrain ||
    linkManager.io.link_state === FDIState.linkReset ||
    linkManager.io.link_state === FDIState.disabled ||
    linkManager.io.link_state === FDIState.linkError
  io.fdi.plPhyInRecenter :=
    RegNext(io.rdi.plPhyInRecenter || lsmNotUp, false.B)

  // The Adapter keeps PM on FDI, as sec 10.2.9 permits. So RDI is never in L1
  // or L2.
  io.fdi.plPhyInL1 := false.B
  io.fdi.plPhyInL2 := false.B

  // Table 10-3 clears pl_protocol_vld only when FDI enters LinkError or Reset.
  // It does not clear when inband presence goes low.
  val protocolVldReg = RegInit(false.B)
  val enteredReset = fdiPlStateSts === FDIState.reset &&
    fdiPlStatePrev =/= FDIState.reset
  val enteredLinkError = fdiPlStateSts === FDIState.linkError &&
    fdiPlStatePrev =/= FDIState.linkError
  when(enteredReset || enteredLinkError) {
    protocolVldReg := false.B
  }.elsewhen(linkManager.io.fdi_pl_inband_pres) {
    protocolVldReg := true.B
  }
  io.fdi.plProtocolVld := protocolVldReg

  // These two signals are 0 until pl_protocol_vld is set. sec 10.2.1 drives all
  // signals to 0 after reset, and Table 10-3 gives these a meaning only after
  // pl_protocol_vld.
  io.fdi.plProtocol := Mux(
    protocolVldReg,
    FDIProtocol.streamingNoManagementTransport,
    0.U.asTypeOf(FDIProtocol())
  )
  io.fdi.plProtocolFlitFmt :=
    Mux(protocolVldReg, FDIFlitFormat.rawFormat, FDIFlitFormat.reserved)
  io.fdi.plRxActiveReq := linkManager.io.fdi_pl_rx_active_req

  // ===========================================================================
  // Clock and wake handshakes
  // ===========================================================================
  // The Adapter does not gate its clocks. So it keeps its own requests set and
  // answers each request from the other side at once, as Tables 10-1 and 10-3
  // permit.
  fdiClockRequester.io.ctrl.startHandshake := true.B
  fdiClockRequester.io.ctrl.releaseReq := false.B
  fdiClockRequester.io.ack := io.fdi.lpClkAck
  io.fdi.plClkReq := fdiClockRequester.io.req

  fdiWakeResponder.io.ctrl.clocksUngatedAndStable := true.B
  fdiWakeResponder.io.req := io.fdi.lpWakeReq
  io.fdi.plWakeAck := fdiWakeResponder.io.ack

  rdiClockResponder.io.ctrl.clocksUngatedAndStable := true.B
  rdiClockResponder.io.req := io.rdi.plClkReq
  io.rdi.lpClkAck := rdiClockResponder.io.ack

  rdiWakeRequester.io.ctrl.startHandshake := true.B
  rdiWakeRequester.io.ctrl.releaseReq := false.B
  rdiWakeRequester.io.ack := io.rdi.plWakeAck
  io.rdi.lpWakeReq := rdiWakeRequester.io.req

  // ===========================================================================
  // Link state machine
  // ===========================================================================
  // These errors take the Link down, whatever the mask is (sec 3.4, Table 9-27
  // bits 0 to 2). The flop ends the logic path from the parity trees and the
  // register block.
  val internalLinkErrorRequest = RegNext(
    d2dSideband.io.linkErrorRequest ||
      d2dSideband.io.regs.parityErrEvent ||
      d2dSideband.io.regs.invalidRoute ||
      d2dSideband.io.regs.fdiRxQueuesFull ||
      d2dSideband.io.regs.rdiRxQueuesFull ||
      fdiStallRequester.io.ctrl.timedOut ||
      linkManager.io.timeout.valid ||
      linkManager.io.invalid_param_exchange ||
      io.regs.internalFatalError ||
      io.regs.linkDownError,
    false.B
  )
  linkManager.io.fdi_lp_state_req := io.fdi.lpStateReq
  linkManager.io.fdi_lp_linkerror := io.fdi.lpLinkError
  linkManager.io.internal_linkerror := internalLinkErrorRequest
  linkManager.io.fdi_lp_rx_active_sts := io.fdi.lpRxActiveSts
  linkManager.io.sw_retrain := io.regs.swRetrain

  io.rdi.lpLinkError := linkManager.io.rdi_lp_linkerror
  io.rdi.lpStateReq := linkManager.io.rdi_lp_state_req
  linkManager.io.rdi_pl_state_sts := io.rdi.plStateSts
  linkManager.io.rdi_pl_inband_pres := io.rdi.plInbandPres

  // Table 3-1 sets each bit from UCIe Link Control. Table 9-9 resets Raw Format
  // to 0 on a Root Port, so software must set it before training starts.
  linkManager.io.adv_cap_payload := Cat(
    0.U(56.W),
    true.B, // bit 7 Stack0_Enable
    0.U(2.W),
    true.B, // bit 4 Streaming
    0.U(3.W),
    io.regs.rawFormatEnable // bit 0 Raw Format
  )

  // ===========================================================================
  // Register block
  // ===========================================================================
  io.regs.linkState := linkManager.io.link_state
  io.regs.paramExchSuccess := linkManager.io.param_exch_success
  io.regs.invalidParamExchange := linkManager.io.invalid_param_exchange
  io.regs.adapterTimeout := linkManager.io.timeout
  io.regs.sideband <> d2dSideband.io.regs

  // sec 9.5.3.14 logs the {AdvCap.Adapter} bits that the Adapter sent, not the
  // bits it received. So this line overrides the bulk connect above.
  io.regs.sideband.advCapAdapter := linkManager.io.adv_cap_sent
  io.regs.remoteRegAccessTimeout := d2dSideband.io.remoteRegAccessTimeout

  io.regs.rdiState := io.rdi.plStateSts
  io.regs.phyFatalError := io.rdi.plTrainError
  io.regs.phyCorrError := io.rdi.plCError && !RegNext(io.rdi.plCError, false.B)
  io.regs.phyNonFatalError :=
    io.rdi.plNfError && !RegNext(io.rdi.plNfError, false.B)

  // ===========================================================================
  // Sideband
  // ===========================================================================
  d2dSideband.io.mailbox <> io.regs.mailboxSideband
  d2dSideband.io.regTarget <> io.regs.sidebandRegTarget
  d2dSideband.io.remoteRegAccessThreshold := io.regs.remoteRegAccessThreshold
  d2dSideband.io.linkReset := io.regs.linkReset
  d2dSideband.io.rdiState := io.rdi.plStateSts
  val adapterFlushPending = linkManager.io.rdi_lp_linkerror

  // sec 7.1.3.3 makes register access credits depend on RDI, not on the Adapter
  // LSM. So the mailbox stays usable while the LSM is not up.
  d2dSideband.io.adapterLinkInvalid := adapterFlushPending

  d2dSideband.io.sb.sntValid := linkManager.io.sb_snd.valid
  d2dSideband.io.sb.sntBits := linkManager.io.sb_snd.bits
  linkManager.io.sb_rcv.valid := d2dSideband.io.sb.rcvValid
  linkManager.io.sb_rcv.bits := d2dSideband.io.sb.rcvBits
  linkManager.io.sb_rdy := d2dSideband.io.sb.rdy

  io.fdi.plCfg := d2dSideband.io.fdi.plCfg
  io.fdi.plCfgVld := d2dSideband.io.fdi.plCfgVld
  d2dSideband.io.fdi.lpCfgCrd := io.fdi.lpCfgCrd
  d2dSideband.io.fdi.lpCfg := io.fdi.lpCfg
  d2dSideband.io.fdi.lpCfgVld := io.fdi.lpCfgVld
  d2dSideband.io.fdi.lpClkAck := io.fdi.lpClkAck
  io.fdi.plCfgCrd := d2dSideband.io.fdi.plCfgCrd

  d2dSideband.io.rdi.plCfg := io.rdi.plCfg
  d2dSideband.io.rdi.plCfgVld := io.rdi.plCfgVld
  d2dSideband.io.rdi.plCfgCrd := io.rdi.plCfgCrd
  d2dSideband.io.rdi.plWakeAck := io.rdi.plWakeAck
  io.rdi.lpCfg := d2dSideband.io.rdi.lpCfg
  io.rdi.lpCfgVld := d2dSideband.io.rdi.lpCfgVld
  io.rdi.lpCfgCrd := d2dSideband.io.rdi.lpCfgCrd

  // ===========================================================================
  // Mainband
  // ===========================================================================
  d2dMainband.io.clkRst := io.clkRst

  val rdiState = io.rdi.plStateSts
  val lsmState = linkManager.io.link_state
  val rdiDown = rdiState === RDIState.linkReset ||
    rdiState === RDIState.disabled || rdiState === RDIState.linkError
  val lsmDown = lsmState === FDIState.linkReset ||
    lsmState === FDIState.disabled || lsmState === FDIState.linkError
  val stalled = fdiStallRequester.io.ctrl.isStalled

  // The gate uses the held rdi_lp_linkerror request, not the RDI state. So the
  // gate shuts one time and stays shut.
  val txGate = rdiState === RDIState.active &&
    (lsmState === FDIState.active || lsmState === FDIState.activePmNak) &&
    !io.fdi.lpLinkError && !linkManager.io.rdi_lp_linkerror && !stalled

  // When RDI goes down, an open FDI stall cannot complete. Rule 7 still
  // requires the Adapter to accept data, and rule 3 forbids a withdrawal. So
  // the Protocol Layer drains into a sink.
  val txSink = linkManager.io.linkmgmt_stallreq && rdiDown && !stalled

  // A backup for sec 10.2.7 rule 6. It shuts the receive path when either state
  // machine starts a teardown, until both are in Reset again.
  val rxTeardown = RegInit(false.B)
  when(rdiDown || lsmDown) {
    rxTeardown := true.B
  }.elsewhen(lsmState === FDIState.reset && rdiState === RDIState.reset) {
    rxTeardown := false.B
  }
  d2dMainband.io.state.txGate := txGate
  d2dMainband.io.state.txSink := txSink
  d2dMainband.io.state.rxTeardown := rxTeardown

  // Active.PMNAK is a sub-state of Active (sec 10.2.9).
  d2dMainband.io.state.rxAllowed :=
    (rdiState === RDIState.active || rdiState === RDIState.activePmNak) &&
      io.fdi.plRxActiveReq && io.fdi.lpRxActiveSts
  val gateShut = d2dMainband.io.state.txGateSettled &&
    !d2dMainband.io.state.txGateCmd
  val sinkShut = d2dMainband.io.state.txSinkSettled &&
    !d2dMainband.io.state.txSinkCmd

  d2dMainband.io.fdi.lpIrdy := io.fdi.lpIrdy
  d2dMainband.io.fdi.lpValid := io.fdi.lpValid
  d2dMainband.io.fdi.lpData := io.fdi.lpData
  io.fdi.plTrdy := d2dMainband.io.fdi.plTrdy
  io.fdi.plValid := d2dMainband.io.fdi.plValid
  io.fdi.plData := d2dMainband.io.fdi.plData.asUInt

  // Note: Do not add an Active term here. The mainband already gates these
  // signals, and an AND here adds logic on 512 data bits.
  io.rdi.lpIrdy := d2dMainband.io.rdi.lpIrdy
  io.rdi.lpValid := d2dMainband.io.rdi.lpValid
  io.rdi.lpData := d2dMainband.io.rdi.lpData.asUInt
  d2dMainband.io.rdi.plTrdy := io.rdi.plTrdy
  d2dMainband.io.rdi.plValid := io.rdi.plValid
  d2dMainband.io.rdi.plData := io.rdi.plData

  // Table 10-1 requires pl_error in the same cycle as its data. Table 10-3
  // permits it only while the FDI receiver is active. So it uses the pl_valid
  // gate.
  d2dMainband.io.rdi.plError := io.rdi.plError
  io.fdi.plError := d2dMainband.io.fdi.plError

  // ===========================================================================
  // Stall handshakes
  // ===========================================================================
  // The mainband has no buffer, so the FDI stall must occur inside the RDI
  // stall (sec 10.3.2 rule 7). The gate shuts on the FDI ack, and RDI
  // lp_stallack follows the gate.

  // pl_stallreq comes from a txLclk flop, and the handshakes use the control
  // clock. sec 10.3.2 does not depend on latency, so a synchroniser is safe.
  val rdiStallReq = Sync3(io.rdi.plStallReq, bypass = bypass)
  rdiStallResponder.io.plStallReq := rdiStallReq
  io.rdi.lpStallAck := rdiStallResponder.io.lpStallAck
  linkManager.io.rdi_pl_stallreq := rdiStallReq

  // Use the live gate state, not a latched event. Then a done from an earlier
  // stall cannot answer this stall.
  rdiStallResponder.io.mainbandStallDone := gateShut

  // The link manager keeps its request set while it needs the stall. So a low
  // request is the release.
  fdiStallRequester.io.ctrl.startStall := linkManager.io.linkmgmt_stallreq
  fdiStallRequester.io.ctrl.releaseStall := !linkManager.io.linkmgmt_stallreq
  io.fdi.plStallReq := fdiStallRequester.io.req
  fdiStallRequester.io.ack := io.fdi.lpStallAck

  // An FDI stall window (sec 10.3.2 rule 7) can open only while the gate or the
  // sink is open and its command keeps it open.
  fdiStallRequester.io.ctrl.downstreamQuiesced :=
    !(txGate && d2dMainband.io.state.txGateCmd) &&
      !(txSink && d2dMainband.io.state.txSinkCmd)

  // The status leaves Active only when both are shut. So pl_trdy cannot stay
  // high after the status changes.
  linkManager.io.linkmgmt_stalldone := gateShut && sinkShut &&
    ((fdiStallRequester.io.ctrl.awaitingAck && io.fdi.lpStallAck) ||
      (stalled && linkManager.io.linkmgmt_stallreq))

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    when(d2dSideband.io.regs.parityErrEvent) {
      assert(!d2dSideband.io.mailbox.req.ready)
    }

    // The pl_phyinl1 and pl_phyinl2 tie-offs are correct only if this holds.
    assert(
      io.rdi.plStateSts =/= RDIState.l1 && io.rdi.plStateSts =/= RDIState.l2,
      "RDI reported a PM state the Adapter never asked for"
    )

    when(RegNext(protocolVldReg, false.B) && !protocolVldReg) {
      assert(
        fdiPlStateSts === FDIState.linkError ||
          fdiPlStateSts === FDIState.reset,
        "pl_protocol_vld de-asserted outside a LinkError or Reset transition"
      )
    }

    // Check rule 6 in the clock of the pins. The ack comes after the gate
    // shuts, so its synchronised copy is high only while the gate is shut.
    val txClock = if (separateDivClocks) io.clkRst.txDivClk else clock
    val txReset: Reset =
      if (separateDivClocks) io.clkRst.txDivRst.asAsyncReset else reset
    withClockAndReset(txClock, txReset) {
      val ackSync = Sync3.from(clock, reset.asBool)(
        io.rdi.lpStallAck,
        bypass = bypass
      )
      when(ackSync) {
        assert(
          !io.rdi.lpValid && !io.rdi.lpIrdy,
          "RDI lp_valid and lp_irdy must be low while lp_stallack is asserted"
        )
      }
    }

    // Do not count waits on an open FDI window, because the FDI timeout bounds
    // them. The bound is three round trips of 3 divided and 4 control clock
    // edges.
    val rdiStallWaitBound =
      math.max(32, 3 * (3 * divClockRatio + 4) + 3)
    val rdiStallPending = rdiStallReq && !io.rdi.lpStallAck &&
      !(linkManager.io.linkmgmt_stallreq && !stalled)
    val rdiStallWait =
      RegInit(0.U(math.max(8, log2Ceil(rdiStallWaitBound + 1)).W))
    when(rdiStallPending) {
      rdiStallWait := rdiStallWait + 1.U
    }.otherwise {
      rdiStallWait := 0.U
    }
    assert(
      rdiStallWait < rdiStallWaitBound.U,
      "RDI pl_stallreq must draw lp_stallack in every state, not only Active"
    )

    // lp_stallack shuts the mainband. So the Adapter cannot accept the data
    // that it owes the Protocol Layer under rule 7.
    assert(
      !(io.rdi.lpStallAck && fdiStallRequester.io.ctrl.awaitingAck),
      "the Adapter must not hold RDI lp_stallack while it owes FDI rule 7 acceptance"
    )
    cover(
      fdiStallRequester.io.ctrl.awaitingAck && rdiStallReq,
      "FdiStallNestedInsideRdiStall"
    )
  }
}
