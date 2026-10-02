package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

class D2DMainbandStateIO(
    val fdiParams: FdiParams,
    val rdiParams: RdiParams,
    val sbParams: SidebandParams
) extends Bundle {
  val d2dState = Input(RDIState())
  val rxActiveReq = Input(Bool())
  val rxActiveSts = Input(Bool())
  val mainbandStallReq = Input(Bool())
  val mainbandStallDone = Output(Bool())
}

object D2DMainbandTxStallState extends ChiselEnum {
  val running, draining, stalled = Value
}

class D2DMainbandModule(
    val fdiParams: FdiParams,
    val rdiParams: RdiParams,
    val sbParams: SidebandParams
) extends Module {
  val io = IO(new Bundle {
    val state = new D2DMainbandStateIO(fdiParams, rdiParams, sbParams)
    val rdi = new Bundle {
      // Adapter -> Physical path.
      val lpIrdy = Output(Bool())
      val lpValid = Output(Bool())
      val lpData = Output(Bits((8 * rdiParams.nBytes).W))
      val plTrdy = Input(Bool())

      // Physical -> Adapter path.
      val plValid = Input(Bool())
      val plData = Input(Bits((8 * rdiParams.nBytes).W))
    }
    val fdi = new Bundle {
      // Protocol -> Adapter path.
      val lpIrdy = Input(Bool())
      val lpValid = Input(Bool())
      val lpData = Input(Bits((8 * fdiParams.nBytes).W))
      val plTrdy = Output(Bool())

      // Adapter -> Protocol path.
      val plValid = Output(Bool())
      val plData = Output(Bits((8 * fdiParams.nBytes).W))
    }
  })

  // Base TX buffer: Protocol -> Adapter -> Physical
  val dataBuffSntReg = Reg(Bits((8 * fdiParams.nBytes).W))
  val dataBuffSntFillReg = RegInit(false.B)
  val dataBuffRcvReg = Reg(Bits((8 * rdiParams.nBytes).W))
  val dataBuffRcvFillReg = RegInit(false.B)

  // Stall Control
  val txStallStateReg = RegInit(D2DMainbandTxStallState.running)
  /* Every pl_stallreq is answered, whatever state the Adapter is in. Spec 10.3.2
     makes the handshake four-phase -- pl_stallreq falls "only when lp_stallack
     is asserted" -- and requires it "when exiting Active state to Retrain, PM,
     LinkReset or Disabled", which the Adapter asks RDI for only once its own
     LSM has left Active (spec 3.5). Answering only in Active left those exits,
     and a stall caught by LinkError, waiting for lp_stallack for ever. Outside
     Active the transmit path is idle, so the stall completes at once. */
  val txStallRequested = io.state.mainbandStallReq
  val txBufferEmpty = !dataBuffSntFillReg

  val stallBlocksFdiIngress =
    (txStallStateReg =/= D2DMainbandTxStallState.running) || txStallRequested
  val stallBlocksRdiTx = txStallStateReg === D2DMainbandTxStallState.stalled

  val txBeatSentToRdi =
    io.rdi.plTrdy && dataBuffSntFillReg && !stallBlocksRdiTx
  // Spec 10.3.3.7: in LinkError "It is required for the upper layer to
  // internally clean up the data path", so a beat still held there is dropped.
  val txDrainComplete = txBufferEmpty || txBeatSentToRdi ||
    (io.state.d2dState === RDIState.linkError)

  switch(txStallStateReg) {
    is(D2DMainbandTxStallState.running) {
      when(txStallRequested) {
        txStallStateReg := D2DMainbandTxStallState.draining
      }
    }
    is(D2DMainbandTxStallState.draining) {
      when(!txStallRequested) {
        txStallStateReg := D2DMainbandTxStallState.running
      }.elsewhen(txDrainComplete) {
        txStallStateReg := D2DMainbandTxStallState.stalled
      }
    }
    is(D2DMainbandTxStallState.stalled) {
      when(!txStallRequested) {
        txStallStateReg := D2DMainbandTxStallState.running
      }
    }
  }

  // TX datapath with stall gating
  io.rdi.lpData := dataBuffSntReg
  io.rdi.lpIrdy := dataBuffSntFillReg && !stallBlocksRdiTx
  io.rdi.lpValid := dataBuffSntFillReg && !stallBlocksRdiTx

  val canAcceptFdi =
    (!dataBuffSntFillReg || txBeatSentToRdi) && !stallBlocksFdiIngress
  val txBeatAcceptedFromFdi = canAcceptFdi && io.fdi.lpValid && io.fdi.lpIrdy
  io.fdi.plTrdy := canAcceptFdi

  io.state.mainbandStallDone := txStallStateReg === D2DMainbandTxStallState.stalled

  dataBuffSntReg := dataBuffSntReg
  dataBuffSntFillReg := dataBuffSntFillReg
  when(!dataBuffSntFillReg) {
    when(txBeatAcceptedFromFdi) {
      dataBuffSntFillReg := true.B
      dataBuffSntReg := io.fdi.lpData
    }.otherwise {
      dataBuffSntFillReg := false.B
    }
  }.elsewhen(txBeatSentToRdi) {
    when(txBeatAcceptedFromFdi) {
      dataBuffSntFillReg := true.B
      dataBuffSntReg := io.fdi.lpData
    }.otherwise {
      dataBuffSntFillReg := false.B
    }
  }
  // The beat LinkError caught is dropped, not sent once the Link is back.
  when(io.state.d2dState === RDIState.linkError) {
    dataBuffSntFillReg := false.B
  }

  // RX Control
  val rxCaptureEnabled =
    (io.state.d2dState === RDIState.active) &&
      io.state.rxActiveReq &&
      io.state.rxActiveSts
  val rxBeatAcceptedFromRdi = io.rdi.plValid && rxCaptureEnabled

  // RX datapath: Physical -> Adapter -> Protocol
  io.fdi.plData := dataBuffRcvReg
  io.fdi.plValid := dataBuffRcvFillReg

  dataBuffRcvReg := dataBuffRcvReg
  when(rxBeatAcceptedFromRdi) {
    dataBuffRcvReg := io.rdi.plData
  }

  dataBuffRcvFillReg := rxBeatAcceptedFromRdi
}
