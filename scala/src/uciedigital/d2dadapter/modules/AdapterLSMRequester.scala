/*
  The sender of the Adapter's link management requests. It does not depend on
  AdapterLSMResponder, because sec 3.2.1.2 lets the two directions progress
  alone.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._

class AdapterLSMRequester(val sbParams: SidebandParams) extends Module {
  require(sbParams.lsmRequestTimeoutCycles > 1)
  val io = IO(new Bundle {
    val rx = new AdapterSbRxIO(sbParams)
    val tx = new AdapterSbTxIO(sbParams)
    val activeAllowed = Input(Bool())
    val downAllowed = Input(Bool())
    val clearActive = Input(Bool())
    val clearDown = Input(Bool())

    val reqActive = Input(Bool())
    val reqLinkReset = Input(Bool())
    val reqDisable = Input(Bool())
    val reqL1 = Input(Bool())
    val reqL2 = Input(Bool())
    val peerAskedPm = Input(Bool())
    val clearPm = Input(Bool())

    val sentReqActive = Output(Bool())
    val sentReqLinkReset = Output(Bool())
    val sentReqDisable = Output(Bool())
    val rcvdRspActive = Output(Bool())
    val rcvdRspLinkReset = Output(Bool())
    val rcvdRspDisable = Output(Bool())
    val sentReqPm = Output(Bool())
    val rcvdPmNak = Output(Bool())
    val rcvdRspPm = Output(Bool())
    val stallSeen = Output(Bool())
    val outstanding = Output(AdapterLSMRequest())
    val timeout = Output(Bool())
  })

  // ===========================================================================
  // Request tracking
  // ===========================================================================
  val sentReqActive = RegInit(false.B)
  val sentReqLinkReset = RegInit(false.B)
  val sentReqDisable = RegInit(false.B)
  val rcvdRspActive = RegInit(false.B)
  val rcvdRspLinkReset = RegInit(false.B)
  val rcvdRspDisable = RegInit(false.B)
  val sentReqPm = RegInit(false.B)
  val sentReqL2 = RegInit(false.B)

  // sec 10.2.9 allows a PM retry 2 us after a PMNAK. A registered flag keeps
  // the compare off the transmit valid path.
  val retryWidth = math.max(1, log2Ceil(sbParams.pmRetryFloorCycles))
  val retryLeft = RegInit(0.U(retryWidth.W))
  val retryHeld = RegInit(false.B)

  // ===========================================================================
  // Request priority and transmit
  // ===========================================================================
  // sec 10.3.3.8 puts Disabled above LinkReset. Each sent flag stays set, so a
  // lower request still goes out after a higher one.
  val wantDisable = io.reqDisable && !sentReqDisable
  val wantLinkReset = io.reqLinkReset && !sentReqLinkReset
  val wantActive = io.reqActive && !sentReqActive
  val wantPm = (io.reqL1 || io.reqL2) && !sentReqPm && !retryHeld

  val awaitingDisable = sentReqDisable && !rcvdRspDisable
  val awaitingLinkReset = sentReqLinkReset && !rcvdRspLinkReset
  val awaitingActive = sentReqActive && !rcvdRspActive
  val busy = awaitingDisable || awaitingLinkReset || awaitingActive ||
    sentReqPm

  val kind = WireDefault(AdapterLSMRequest.none)
  val message = WireDefault(0.U(sbParams.sbNodeMsgWidth.W))
  when(wantDisable) {
    kind := AdapterLSMRequest.disable
    message := AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_REQ_DISABLE)
  }.elsewhen(wantLinkReset) {
    kind := AdapterLSMRequest.linkReset
    message := AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_REQ_LINKRESET)
  }.elsewhen(wantActive) {
    kind := AdapterLSMRequest.active
    message := AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_REQ_ACTIVE)
  }.elsewhen(wantPm) {
    kind := Mux(io.reqL2, AdapterLSMRequest.l2, AdapterLSMRequest.l1)
    message := Mux(
      io.reqL2,
      AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_REQ_L2),
      AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_REQ_L1)
    )
  }

  // PM waits for RDI Active, like the Active request.
  val allowed = Mux(
    kind === AdapterLSMRequest.linkReset || kind === AdapterLSMRequest.disable,
    io.downAllowed,
    io.activeAllowed
  )
  io.tx.valid := (kind =/= AdapterLSMRequest.none) && !busy && allowed
  io.tx.bits := message
  val accepted = io.tx.valid && io.tx.rdy

  when(accepted) {
    switch(kind) {
      is(AdapterLSMRequest.disable) { sentReqDisable := true.B }
      is(AdapterLSMRequest.linkReset) { sentReqLinkReset := true.B }
      is(AdapterLSMRequest.active) { sentReqActive := true.B }
      is(AdapterLSMRequest.l1) { sentReqPm := true.B; sentReqL2 := false.B }
      is(AdapterLSMRequest.l2) { sentReqPm := true.B; sentReqL2 := true.B }
    }
  }

  // ===========================================================================
  // Responses
  // ===========================================================================
  val rx = io.rx.bits
  val stallSeen = WireDefault(false.B)
  val pmNak = io.rx.valid && sentReqPm &&
    AdapterLinkMsg.isRegular(rx, SBM.LINKMGMT_ADAPTER0_RSP_PMNAK)
  val pmRsp = io.rx.valid && sentReqPm && Mux(
    sentReqL2,
    AdapterLinkMsg.isRegular(rx, SBM.LINKMGMT_ADAPTER0_RSP_L2),
    AdapterLinkMsg.isRegular(rx, SBM.LINKMGMT_ADAPTER0_RSP_L1)
  )

  when(pmNak || pmRsp) {
    sentReqPm := false.B
  }

  // A PM request from the partner lifts the retry floor (sec 10.2.9).
  when(pmNak) {
    retryLeft := (sbParams.pmRetryFloorCycles - 1).U
    retryHeld := true.B
  }.elsewhen(io.peerAskedPm) {
    retryLeft := 0.U
    retryHeld := false.B
  }.elsewhen(retryLeft =/= 0.U) {
    retryLeft := retryLeft - 1.U
  }.otherwise {
    retryHeld := false.B
  }
  when(io.rx.valid) {
    when(AdapterLinkMsg.isRegular(rx, SBM.LINKMGMT_ADAPTER0_RSP_ACTIVE)) {
      rcvdRspActive := true.B
    }
    when(AdapterLinkMsg.isRegular(rx, SBM.LINKMGMT_ADAPTER0_RSP_LINKRESET)) {
      rcvdRspLinkReset := true.B
    }
    when(AdapterLinkMsg.isRegular(rx, SBM.LINKMGMT_ADAPTER0_RSP_DISABLE)) {
      rcvdRspDisable := true.B
    }

    // A Stall response restarts only the timeout, not the request (sec 7.1).
    stallSeen :=
      AdapterLinkMsg.isStall(rx, SBM.LINKMGMT_ADAPTER0_RSP_ACTIVE) ||
        AdapterLinkMsg.isStall(rx, SBM.LINKMGMT_ADAPTER0_RSP_LINKRESET) ||
        AdapterLinkMsg.isStall(rx, SBM.LINKMGMT_ADAPTER0_RSP_DISABLE) ||
        AdapterLinkMsg.isStall(rx, SBM.LINKMGMT_ADAPTER0_RSP_PMNAK) ||
        AdapterLinkMsg.isStall(rx, SBM.LINKMGMT_ADAPTER0_RSP_L1) ||
        AdapterLinkMsg.isStall(rx, SBM.LINKMGMT_ADAPTER0_RSP_L2)
  }

  // ===========================================================================
  // Timeout
  // ===========================================================================
  // sec 7.1 gives each request 8 ms for its response. Expiry is an
  // uncorrectable internal error (sec 3.2.1.2).
  val timerWidth =
    math.max(1, log2Ceil(sbParams.lsmRequestTimeoutCycles))
  val requestTimer = RegInit(0.U(timerWidth.W))
  val requestTimedOut =
    requestTimer === (sbParams.lsmRequestTimeoutCycles - 1).U
  val clearsOutstanding = (io.clearActive && awaitingActive) ||
    (io.clearDown && (awaitingLinkReset || awaitingDisable)) ||
    (io.clearPm && sentReqPm)
  when(!busy || stallSeen || clearsOutstanding) {
    requestTimer := 0.U
  }.elsewhen(!requestTimedOut) {
    requestTimer := requestTimer + 1.U
  }
  io.timeout := busy && requestTimedOut

  // ===========================================================================
  // Clear and outputs
  // ===========================================================================
  when(io.clearActive) {
    sentReqActive := false.B
    rcvdRspActive := false.B
  }
  when(io.clearDown) {
    sentReqLinkReset := false.B
    sentReqDisable := false.B
    rcvdRspLinkReset := false.B
    rcvdRspDisable := false.B
  }
  when(io.clearPm) {
    sentReqPm := false.B
  }

  io.sentReqActive := sentReqActive
  io.sentReqLinkReset := sentReqLinkReset
  io.sentReqDisable := sentReqDisable
  io.rcvdRspActive := rcvdRspActive
  io.rcvdRspLinkReset := rcvdRspLinkReset
  io.rcvdRspDisable := rcvdRspDisable
  io.sentReqPm := sentReqPm
  io.rcvdPmNak := pmNak
  io.rcvdRspPm := pmRsp
  io.stallSeen := stallSeen
  io.outstanding := Mux(
    awaitingDisable,
    AdapterLSMRequest.disable,
    Mux(
      awaitingLinkReset,
      AdapterLSMRequest.linkReset,
      Mux(
        awaitingActive,
        AdapterLSMRequest.active,
        Mux(
          sentReqPm,
          Mux(sentReqL2, AdapterLSMRequest.l2, AdapterLSMRequest.l1),
          AdapterLSMRequest.none
        )
      )
    )
  )
}
