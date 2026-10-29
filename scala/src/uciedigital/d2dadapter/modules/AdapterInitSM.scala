/*
  Stage 3 link initialization (sec 3.2.1). Part 1 is local, part 2 is the
  {AdvCap.Adapter} exchange and part 3 is the FDI bring up. The Active Entry
  handshake is in AdapterLSMRequester and AdapterLSMResponder, because
  sec 10.2.8 needs it on every entry to Active.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._

class AdapterInitSM(val sbParams: SidebandParams) extends Module {
  require(sbParams.paramExchTimeoutCycles > 1)
  val io = IO(new Bundle {
    val rdiPlInbandPres = Input(Bool())
    val rdiActive = Input(Bool())
    val transitionToActive = Input(Bool())

    val peerReqActive = Input(Bool())
    val sentRspActive = Input(Bool())
    val rcvdRspActive = Input(Bool())

    val rx = new AdapterSbRxIO(sbParams)
    val tx = new AdapterSbTxIO(sbParams)
    val txAllowed = Input(Bool())
    val advCapPayload = Input(UInt(64.W))
    val restart = Input(Bool())
    val hold = Input(Bool())
    val resumeFdiBringup = Input(Bool())

    val state = Output(LinkInitState())
    val fdiPlInbandPres = Output(Bool())
    val rxActiveReqEnable = Output(Bool())
    val wantRdiActive = Output(Bool())
    val initDone = Output(Bool())
    val paramExchSuccess = Output(Bool())
    val paramExchTimeout = Output(Bool())
    val invalidParamExchange = Output(Bool())
    val advCapSent = Output(Valid(UInt(64.W)))
  })

  // ===========================================================================
  // State and outputs
  // ===========================================================================
  val stateReg = RegInit(LinkInitState.INIT_START)

  // These flags stay set after the move to Active. sec 9.5.3.8 bit 13 reports
  // parameter exchange success while the link is up. Only restart clears them.
  val advCapSentFlag = RegInit(false.B)
  val advCapRcvdFlag = RegInit(false.B)

  io.state := stateReg
  io.initDone := stateReg === LinkInitState.INIT_DONE

  // sec 10.3.3.8 has the Adapter pass the Protocol Layer's Active trigger to
  // RDI even while pl_inband_pres is low. sec 10.1.3.2 rule 8 waits for
  // presence only in an Adapter that gates its clocks, and this one does not.
  val leaveInitStart = io.rdiPlInbandPres || io.transitionToActive

  io.wantRdiActive := stateReg =/= LinkInitState.INIT_START
  io.fdiPlInbandPres := stateReg === LinkInitState.FDI_BRINGUP ||
    stateReg === LinkInitState.INIT_DONE
  io.rxActiveReqEnable := Mux(
    stateReg === LinkInitState.FDI_BRINGUP,
    io.peerReqActive,
    stateReg === LinkInitState.INIT_DONE
  )

  // ===========================================================================
  // Parameter exchange
  // ===========================================================================
  val sendAdvCap = stateReg === LinkInitState.PARAM_EXCH && !advCapSentFlag
  io.tx.valid := sendAdvCap && io.txAllowed
  io.tx.bits := AdapterLinkMsg.create(SBM.ADVCAP_ADAPTER, io.advCapPayload)
  val advCapAccepted = io.tx.valid && io.tx.rdy

  when(advCapAccepted) {
    advCapSentFlag := true.B
  }

  // sec 9.5.3.14 logs the bits this Adapter sent, not the bits it received. The
  // log is RW1C, so this is valid for one cycle and a software clear stays
  // clear.
  io.advCapSent.valid := advCapAccepted
  io.advCapSent.bits := io.advCapPayload

  // Accept the message in every state. sec 3.2.1.2 sets no DP and UP order for
  // Streaming, so the partner can send first, and it does not send again.
  val advCapRcvdPayload = Reg(UInt(64.W))
  when(
    io.rx.valid && AdapterLinkMsg.isRegular(io.rx.bits, SBM.ADVCAP_ADAPTER)
  ) {
    advCapRcvdFlag := true.B
    advCapRcvdPayload := io.rx.bits(127, 64)
  }

  // sec 3.2.1.2 part 2 resolves the two advertisements. Table 7-10 puts Raw
  // Format at bit 0, Streaming at bit 4 and Stack0_Enable at bit 7. Table 3-7
  // makes Raw Format mandatory for Streaming. This Adapter has only Stack 0.
  def streamingRawStack0(p: UInt): Bool = p(0) && p(4) && p(7)
  val bothResolve = streamingRawStack0(io.advCapPayload) &&
    streamingRawStack0(advCapRcvdPayload)
  val resolvedReg = RegInit(false.B)
  val invalidReg = RegInit(false.B)
  val resolveNow =
    advCapSentFlag && advCapRcvdFlag && !resolvedReg && !invalidReg
  when(resolveNow) {
    resolvedReg := bothResolve
    invalidReg := !bothResolve
  }

  // This output is an event, so the RW1C bit it sets (sec 9.5.3.2 bit 5) stays
  // clear after software clears it.
  io.invalidParamExchange := resolveNow && !bothResolve

  // sec 9.5.3.8 bit 13 says "after successful Parameter exchange". An exchange
  // that resolves to no common protocol is not successful.
  io.paramExchSuccess := resolvedReg

  // ===========================================================================
  // Timeout
  // ===========================================================================
  // sec 3.2.1.2 times part 1 and part 2 together for 8 ms. The timer counts
  // only while RDI is in Active state. An {AdvCap.*.Stall} from the partner
  // resets it.
  val timerWidth =
    math.max(1, log2Ceil(sbParams.paramExchTimeoutCycles))
  val paramTimer = RegInit(0.U(timerWidth.W))
  val paramExchDone = advCapSentFlag && advCapRcvdFlag
  val paramTimerRunning = io.rdiActive && !paramExchDone
  val paramTimedOut = paramTimer === (sbParams.paramExchTimeoutCycles - 1).U
  val advCapStall =
    io.rx.valid && AdapterLinkMsg.isStall(io.rx.bits, SBM.ADVCAP_ADAPTER)
  when(!paramTimerRunning || advCapStall || io.restart || io.hold) {
    paramTimer := 0.U
  }.elsewhen(!paramTimedOut) {
    paramTimer := paramTimer + 1.U
  }

  // sec 3.2.1.2 treats expiry as an uncorrectable internal error. The Adapter
  // must take RDI to LinkError.
  io.paramExchTimeout := paramTimerRunning && paramTimedOut

  // ===========================================================================
  // State machine
  // ===========================================================================
  switch(stateReg) {
    is(LinkInitState.INIT_START) {
      when(leaveInitStart) {
        stateReg := LinkInitState.RDI_BRINGUP
      }
    }
    is(LinkInitState.RDI_BRINGUP) {
      when(io.rdiActive) {
        stateReg := LinkInitState.PARAM_EXCH
      }
    }
    is(LinkInitState.PARAM_EXCH) {
      // sec 3.2.1.3 starts part 3 only "once Parameter Exchanges have
      // successfully completed". So a pair that does not resolve must not
      // advance.
      when(advCapSentFlag && advCapRcvdFlag && resolvedReg) {
        stateReg := LinkInitState.FDI_BRINGUP
      }
    }
    is(LinkInitState.FDI_BRINGUP) {
      // sec 10.2.8 step 5. The state moves to Active after the Adapter sends
      // and receives Rsp.Active.
      when(io.sentRspActive && io.rcvdRspActive) {
        stateReg := LinkInitState.INIT_DONE
      }
    }
  }

  // The L2 exit through Reset goes back to part 3 and keeps the exchange.
  when(io.resumeFdiBringup) {
    stateReg := LinkInitState.FDI_BRINGUP
  }

  // While held, the LSM is down and this side sends nothing. The Adapter still
  // takes a partner advertisement, because sec 3.2.1.2 sets no order.
  when(io.hold) {
    stateReg := LinkInitState.INIT_START
  }
  when(io.restart) {
    stateReg := LinkInitState.INIT_START
    advCapSentFlag := false.B
    advCapRcvdFlag := false.B
    resolvedReg := false.B
    invalidReg := false.B
  }
}
