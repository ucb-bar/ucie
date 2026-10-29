/*
  The Protocol Layer state control for FDI. It holds the negotiated protocol
  latch and the levels that gate the FDI datapath. All inputs and outputs use
  the control clock.
 */

package edu.berkeley.cs.uciedigital.fdi

import edu.berkeley.cs.uciedigital.common._
import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._
import edu.berkeley.cs.uciedigital.fdi._

class ProtocolStateControllerIO() extends Bundle {
  val ctrl = new ProtocolLayerCtrlIO()
  val fdi = new Bundle {
    val plStateSts = Input(FDIState())
    val plInbandPres = Input(Bool())
    val plRxActiveReq = Input(Bool())
    val plProtocol = Input(FDIProtocol())
    val plProtocolFlitFmt = Input(FDIFlitFormat())
    val plProtocolVld = Input(Bool())
    val plClkReq = Input(Bool())
    val plWakeAck = Input(Bool())
    val plPhyInRecenter = Input(Bool())
    val plPhyInL1 = Input(Bool())
    val plPhyInL2 = Input(Bool())
    val plSpeedmode = Input(SpeedMode())
    val plMaxSpeedmode = Input(Bool())
    val plLnkCfg = Input(LinkWidth())
    val plCError = Input(Bool())
    val plNfError = Input(Bool())
    val plTrainError = Input(Bool())
    val lpStateReq = Output(FDIStateReq())
    val lpLinkError = Output(Bool())
    val lpWakeReq = Output(Bool())
    val lpClkAck = Output(Bool())
    val lpRxActiveSts = Output(Bool())
  }
  val plErrorSeen = Input(Bool())
  val lpStallAck = Input(Bool())
  val sidebandParityErrEvent = Input(Bool())
  val txAllowed = Output(Bool())
  val rxClose = Output(Bool())
  val status = new ProtocolLayerStatusIO()
}

class ProtocolStateController() extends Module {
  val io = IO(new ProtocolStateControllerIO())

  // ===========================================================================
  // Modules
  // ===========================================================================
  val stateRequest = Module(new ProtocolStateRequestSM())
  val rxActive = Module(new FdiRxActiveResponder())
  val clkHandshake = Module(new ReqAckResponder())
  val wakeHandshake = Module(new ReqAckRequester())

  // ===========================================================================
  // Negotiated protocol and state request
  // ===========================================================================
  val negotiatedProtocolReg =
    RegInit(FDIProtocol.streamingNoManagementTransport)
  val negotiatedFlitFormatReg = RegInit(FDIFlitFormat.rawFormat)
  val negotiatedValidReg = RegInit(false.B)

  // Table 10-3 holds the negotiated value until Reset AND pl_inband_pres is 0.
  // The presence signal alone drops in LinkReset, Disabled and LinkError.
  when((io.fdi.plStateSts === FDIState.reset) && !io.fdi.plInbandPres) {
    negotiatedValidReg := false.B
  }.elsewhen(
    (io.fdi.plStateSts === FDIState.reset) &&
      io.fdi.plInbandPres &&
      io.fdi.plProtocolVld
  ) {
    negotiatedProtocolReg := io.fdi.plProtocol
    negotiatedFlitFormatReg := io.fdi.plProtocolFlitFmt
    negotiatedValidReg := true.B
  }

  stateRequest.io.ctrl := io.ctrl
  stateRequest.io.plStateSts := io.fdi.plStateSts
  stateRequest.io.plInbandPres := io.fdi.plInbandPres
  stateRequest.io.negotiatedValid := negotiatedValidReg
  stateRequest.io.negotiatedFlitFormat := negotiatedFlitFormatReg
  io.fdi.lpStateReq := stateRequest.io.lpStateReq

  // ===========================================================================
  // Link error
  // ===========================================================================
  val parityLinkErrorReg = RegInit(false.B)

  // Registered, so no logic drives lp_linkerror from the sideband parity XOR
  // trees. It is the event, because the parity status bit never clears.
  val parityErrEventReg = RegNext(io.sidebandParityErrEvent, false.B)

  when(io.fdi.plStateSts === FDIState.linkError) {
    parityLinkErrorReg := false.B
  }.elsewhen(parityErrEventReg) {
    parityLinkErrorReg := true.B
  }

  // The Adapter stays in LinkError "as long as lp_linkerror=1" (Table 10-3),
  // so the request is a level. A flop drives the pin.
  val requestLinkErrorReg = RegNext(io.ctrl.requestLinkError, false.B)
  io.fdi.lpLinkError := parityLinkErrorReg || parityErrEventReg ||
    requestLinkErrorReg

  // ===========================================================================
  // FDI handshakes
  // ===========================================================================
  rxActive.io.fdi.plRxActiveReq := io.fdi.plRxActiveReq

  // The receiver is one register stage that always has room. A receiver that
  // must defer drives this input.
  rxActive.io.ctrl.receiverReady := true.B
  io.fdi.lpRxActiveSts := rxActive.io.fdi.lpRxActiveSts

  clkHandshake.io.req := io.fdi.plClkReq
  clkHandshake.io.ctrl.clocksUngatedAndStable := true.B
  io.fdi.lpClkAck := clkHandshake.io.ack

  wakeHandshake.io.ack := io.fdi.plWakeAck
  wakeHandshake.io.ctrl.startHandshake := true.B
  wakeHandshake.io.ctrl.releaseReq := false.B
  io.fdi.lpWakeReq := wakeHandshake.io.req

  // ===========================================================================
  // Datapath gating
  // ===========================================================================
  val inActive = io.fdi.plStateSts === FDIState.active
  val inPmNak = io.fdi.plStateSts === FDIState.activePmNak
  val inTeardown = (io.fdi.plStateSts === FDIState.linkReset) ||
    (io.fdi.plStateSts === FDIState.disabled) ||
    (io.fdi.plStateSts === FDIState.linkError)

  // sec 10.2.7 rule 1 makes the transmitter wait for Active. Active.PMNAK
  // counts, and pl_trdy still arbitrates every beat.
  io.txAllowed := inActive || inPmNak

  // sec 10.2.7 rule 3 keeps the receiver open in Reset, Retrain and Active,
  // and no open level can cross a parked rxLclk in time. So only a teardown
  // closes the receive path. sec 10.3.3.5 to 10.3.3.7 exit these only to Reset.
  io.rxClose := inTeardown

  // ===========================================================================
  // Status outputs
  // ===========================================================================
  // plErrorSeen is the pl_error event from rxLclk, crossed into this clock.
  // Table 10-3 makes the pl_error and pl_cerror OR mandatory for correctable
  // logging, and Table 9-12 decides whether this layer reports it on.
  io.status.errCorrectable :=
    (io.plErrorSeen || io.fdi.plCError) && io.ctrl.corrProtoReport

  // A dropped beat is not a correctable error (Table 10-1), and sec 3.3.1
  // rules out Adapter retry, so pl_error is also logged uncorrectable.
  io.status.errUncorrectable := io.plErrorSeen

  // sec 9.5.3.3 forwards Adapter errors to the Protocol Layer for signaling,
  // so pl_nferror goes to the chip and is not logged again.
  io.status.fdiNonFatalError := io.fdi.plNfError && io.ctrl.nonFatalProtoReport

  // Table 10-3 holds pl_trainerror until FDI leaves LinkError for Reset, so it
  // passes as the level the Adapter drives. Table 9-12 enables reporting it.
  io.status.fdiFatalError := io.fdi.plTrainError && io.ctrl.fatalProtoReport

  // Table 10-3 lets the upper layer use this for the Link Training bit.
  val phyInRecenterReg = RegNext(io.fdi.plPhyInRecenter, false.B)
  io.status.phyInRecenter := phyInRecenterReg

  // Raw Format does not use these two here. They go to the chip unchanged.
  io.status.phyInL1 := io.fdi.plPhyInL1
  io.status.phyInL2 := io.fdi.plPhyInL2
  io.status.speedmode := io.fdi.plSpeedmode
  io.status.maxSpeedmode := io.fdi.plMaxSpeedmode
  io.status.lnkCfg := io.fdi.plLnkCfg
  io.status.linkState := io.fdi.plStateSts
  io.status.negotiatedProtocolValid := negotiatedValidReg
  io.status.negotiatedProtocol := negotiatedProtocolReg
  io.status.negotiatedFlitFormat := negotiatedFlitFormatReg
  io.status.stalled := io.lpStallAck

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    val prevClkReqReg = RegNext(io.fdi.plClkReq, false.B)
    val prevRxActiveReqReg = RegNext(io.fdi.plRxActiveReq, false.B)
    val prevNegotiatedValidReg = RegNext(negotiatedValidReg, false.B)
    val prevNegotiatedProtocolReg =
      RegNext(negotiatedProtocolReg, FDIProtocol.streamingNoManagementTransport)
    val prevNegotiatedFlitFormatReg =
      RegNext(negotiatedFlitFormatReg, FDIFlitFormat.rawFormat)
    block(Verification.Assert) {
      when(io.fdi.plClkReq && !prevClkReqReg) {
        assert(
          !io.fdi.lpClkAck,
          "FATAL: lp_clk_ack must not assert in the same cycle as pl_clk_req"
        )
      }
      when(io.fdi.plRxActiveReq && !prevRxActiveReqReg) {
        assert(
          !io.fdi.lpRxActiveSts,
          "FATAL: sec 10.2.7 rule 2 needs a clock between the request and " +
            "lp_rx_active_sts"
        )
      }
      when(prevNegotiatedValidReg && io.fdi.plInbandPres) {
        assert(
          negotiatedProtocolReg === prevNegotiatedProtocolReg,
          "FATAL: the negotiated protocol changed while pl_inband_pres was " +
            "still asserted"
        )
        assert(
          negotiatedFlitFormatReg === prevNegotiatedFlitFormatReg,
          "FATAL: the negotiated flit format changed while pl_inband_pres " +
            "was still asserted"
        )
      }
    }
  }
}
