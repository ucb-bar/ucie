/*
  The responder to the remote Adapter's link management requests. It records
  a request in any state, because sec 9.5.3.8 encoding 0011b allows up to 8 ms
  between the two Adapters' Active Requests.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.sideband._

class AdapterLSMResponder(val sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val rx = new AdapterSbRxIO(sbParams)
    val tx = new AdapterSbTxIO(sbParams)
    val activeAllowed = Input(Bool())
    val clearActive = Input(Bool())
    val clearDown = Input(Bool())
    val clearPending = Input(Bool())
    val rxActiveReady = Input(Bool())
    val fdiStallDone = Input(Bool())
    val lsmState = Input(FDIState())
    val plStateReq = Input(FDIStateReq())

    val peerReqActive = Output(Bool())
    val peerReqLinkReset = Output(Bool())
    val peerReqDisable = Output(Bool())
    val sentRspActive = Output(Bool())
    val sentRspLinkReset = Output(Bool())
    val sentRspDisable = Output(Bool())
    val peerReqL1 = Output(Bool())
    val peerReqL2 = Output(Bool())
    val answeredPm = Output(Bool())
  })

  // ===========================================================================
  // Request tracking
  // ===========================================================================
  val reqActiveRcvd = RegInit(false.B)
  val reqLinkResetRcvd = RegInit(false.B)
  val reqDisableRcvd = RegInit(false.B)
  val rspActiveSent = RegInit(false.B)
  val rspLinkResetSent = RegInit(false.B)
  val rspDisableSent = RegInit(false.B)

  // One pending PM request is enough, because sec 7.1.3.3 allows one
  // outstanding request for each MsgCode.
  val pmReqPending = RegInit(false.B)
  val pmReqL2 = RegInit(false.B)

  // sec 3.9 leaves parity in Raw Format to the implementation. This design
  // does not support it, so the answer is always Nak.
  val parityReqPending = RegInit(false.B)

  // Note: keep this clear before the receive block, so a request that arrives
  // in the same cycle is kept.
  when(io.clearDown) {
    reqLinkResetRcvd := false.B
    reqDisableRcvd := false.B
    rspLinkResetSent := false.B
    rspDisableSent := false.B
  }

  // ===========================================================================
  // Receive
  // ===========================================================================
  val rx = io.rx.bits
  when(io.rx.valid) {
    when(AdapterLinkMsg.isRegular(rx, SBM.LINKMGMT_ADAPTER0_REQ_ACTIVE)) {
      reqActiveRcvd := true.B
    }

    // sec 7.1.3.3 gives every request a response, also a repeat of one that
    // is already answered.
    when(AdapterLinkMsg.isRegular(rx, SBM.LINKMGMT_ADAPTER0_REQ_LINKRESET)) {
      reqLinkResetRcvd := true.B
      rspLinkResetSent := false.B
    }
    when(AdapterLinkMsg.isRegular(rx, SBM.LINKMGMT_ADAPTER0_REQ_DISABLE)) {
      reqDisableRcvd := true.B
      rspDisableSent := false.B
    }
    when(SBMsgCompare(rx, SBM.LINKMGMT_ADAPTER0_REQ_L1)) {
      pmReqPending := true.B
      pmReqL2 := false.B
    }
    when(SBMsgCompare(rx, SBM.LINKMGMT_ADAPTER0_REQ_L2)) {
      pmReqPending := true.B
      pmReqL2 := true.B
    }
    when(SBMsgCompare(rx, SBM.PARITY_FEATURE_REQ)) {
      parityReqPending := true.B
    }
  }

  // ===========================================================================
  // PM response decision
  // ===========================================================================
  // sec 10.2.9 sends PMNAK only after 1 us in Active. A registered flag keeps
  // the compare off the transmit valid path.
  val pmReqArrived = io.rx.valid && (
    SBMsgCompare(rx, SBM.LINKMGMT_ADAPTER0_REQ_L1) ||
      SBMsgCompare(rx, SBM.LINKMGMT_ADAPTER0_REQ_L2)
  )
  val inActiveColumn = io.lsmState === FDIState.active ||
    io.lsmState === FDIState.activePmNak
  val floorWidth = math.max(1, log2Ceil(sbParams.pmNakFloorCycles))
  val pmFloorLeft = RegInit(0.U(floorWidth.W))
  val pmNakDue = RegInit(false.B)
  when(pmReqArrived) {
    pmFloorLeft := (sbParams.pmNakFloorCycles - 1).U
    pmNakDue := false.B
  }.elsewhen(pmReqPending && inActiveColumn) {
    when(pmFloorLeft === 0.U) {
      pmNakDue := true.B
    }.otherwise {
      pmFloorLeft := pmFloorLeft - 1.U
    }
  }

  // sec 10.2.9 gives the PM response in PM, or when this side's Protocol Layer
  // asks for the same state and is stalled.
  val localAsks = io.lsmState === FDIState.active &&
    io.plStateReq === Mux(pmReqL2, FDIStateReq.l2, FDIStateReq.l1)
  val inPm = io.lsmState === FDIState.l1 || io.lsmState === FDIState.l2
  val owePmRsp = pmReqPending && (inPm || (localAsks && io.fdiStallDone))

  // sec 10.2.9 sends PMNAK at once in Retrain. LinkReset and Disabled do the
  // same, because outside Active only the partner's 8 ms timeout runs.
  val owePmNak = pmReqPending && !inPm && !localAsks && (
    io.lsmState === FDIState.retrain || io.lsmState === FDIState.linkReset ||
      io.lsmState === FDIState.disabled || (inActiveColumn && pmNakDue)
  )

  // ===========================================================================
  // Response priority and transmit
  // ===========================================================================
  // PM goes first for its 2 us limit. Parity is next, because the partner
  // holds its Retrain or L1 exit until it has the answer (sec 3.9).
  val oweParityNak = parityReqPending

  // Waiting for the stall makes the response also say that this side sends
  // nothing more.
  val oweRspDisable = reqDisableRcvd && !rspDisableSent && io.fdiStallDone
  val oweRspLinkReset =
    reqLinkResetRcvd && !rspLinkResetSent && io.fdiStallDone

  // Rsp.Active waits for RDI Active (sec 3.5), and for pl_rx_active_req and
  // lp_rx_active_sts both 1 (sec 10.2.8 step 4).
  val oweRspActive = reqActiveRcvd && !rspActiveSent && io.rxActiveReady &&
    io.activeAllowed

  val message = WireDefault(0.U(sbParams.sbNodeMsgWidth.W))
  val send = WireDefault(false.B)
  when(owePmRsp) {
    message := Mux(
      pmReqL2,
      AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_RSP_L2),
      AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_RSP_L1)
    )
    send := true.B
  }.elsewhen(owePmNak) {
    message := AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_RSP_PMNAK)
    send := true.B
  }.elsewhen(oweParityNak) {
    message := AdapterLinkMsg.create(SBM.PARITY_FEATURE_NAK)
    send := true.B
  }.elsewhen(oweRspDisable) {
    message := AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_RSP_DISABLE)
    send := true.B
  }.elsewhen(oweRspLinkReset) {
    message := AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_RSP_LINKRESET)
    send := true.B
  }.elsewhen(oweRspActive) {
    message := AdapterLinkMsg.create(SBM.LINKMGMT_ADAPTER0_RSP_ACTIVE)
    send := true.B
  }

  io.tx.valid := send
  io.tx.bits := message
  val accepted = io.tx.valid && io.tx.rdy

  when(accepted) {
    when(owePmRsp || owePmNak) {
      pmReqPending := false.B
    }.elsewhen(oweParityNak) {
      parityReqPending := false.B
    }.elsewhen(oweRspDisable) {
      rspDisableSent := true.B
    }.elsewhen(oweRspLinkReset) {
      rspLinkResetSent := true.B
    }.elsewhen(oweRspActive) {
      rspActiveSent := true.B
    }
  }

  // ===========================================================================
  // Clear and outputs
  // ===========================================================================
  // Every entry to Active must run a new Active Entry Handshake (sec 10.2.8).
  when(io.clearActive) {
    reqActiveRcvd := false.B
    rspActiveSent := false.B
  }

  // sec 10.2.9 still requires an answer to a PM request that arrives in
  // Retrain, so only LinkError resets an outstanding PM handshake.
  when(io.clearPending) {
    pmReqPending := false.B
    parityReqPending := false.B
  }

  io.peerReqActive := reqActiveRcvd
  io.peerReqLinkReset := reqLinkResetRcvd
  io.peerReqDisable := reqDisableRcvd
  io.sentRspActive := rspActiveSent
  io.sentRspLinkReset := rspLinkResetSent
  io.sentRspDisable := rspDisableSent
  io.peerReqL1 := pmReqPending && !pmReqL2
  io.peerReqL2 := pmReqPending && pmReqL2
  io.answeredPm := accepted && owePmRsp
}
