package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

class D2DMainbandStateIO(
  val fdiParams: FdiParams,
  val rdiParams: RdiParams,
  val sbParams: SidebandParams,
) extends Bundle {
  val d2dState = Input(RDIState())
  val rxActiveReq = Input(Bool())
  val rxActiveStatus = Output(Bool())
  val mainbandStallReq = Input(Bool())
  val mainbandStallDone = Output(Bool())
}

object D2DMainbandTxStallState extends ChiselEnum {
  val running, draining, stalled = Value
}

class D2DMainbandModule(
  val fdiParams: FdiParams,
  val rdiParams: RdiParams,
  val sbParams: SidebandParams,
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
      val lpData = Input(Bits((8 * fdiParams.width).W))
      val plTrdy = Output(Bool())

      // Adapter -> Protocol path.
      val plValid = Output(Bool())
      val plData = Output(Bits((8 * fdiParams.width).W))
    }
  })

  // Base TX buffer: Protocol -> Adapter -> Physical
  val dataBuffSntReg = Reg(Bits((8 * fdiParams.width).W))
  val dataBuffSntFillReg = RegInit(false.B)
  val dataBuffRcvReg = Reg(Bits((8 * rdiParams.nBytes).W))
  val dataBuffRcvFillReg = RegInit(false.B)

  // Stall Control
  val txStallStateReg = RegInit(D2DMainbandTxStallState.running)
  val txStallRequested = io.state.mainbandStallReq && (io.state.d2dState === RDIState.active)
  val txBufferEmpty = !dataBuffSntFillReg

  val stallBlocksFdiIngress =
    (txStallStateReg =/= D2DMainbandTxStallState.running) || txStallRequested
  val stallBlocksRdiTx = txStallStateReg === D2DMainbandTxStallState.stalled

  val txBeatSentToRdi =
    io.rdi.plTrdy && dataBuffSntFillReg && !stallBlocksRdiTx
  val txDrainComplete = txBufferEmpty || txBeatSentToRdi

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

  val canAcceptFdi = (!dataBuffSntFillReg || txBeatSentToRdi) && !stallBlocksFdiIngress
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

  // RX Control
  val rxCaptureEnabled =
    (io.state.d2dState === RDIState.active) && io.state.rxActiveReq
  val rxBeatAcceptedFromRdi = io.rdi.plValid && rxCaptureEnabled
  val rxActiveStatusReg = RegInit(false.B)
  when(io.state.rxActiveReq) {
    rxActiveStatusReg := true.B
  }.elsewhen(!dataBuffRcvFillReg) {
    rxActiveStatusReg := false.B
  }
  io.state.rxActiveStatus := rxActiveStatusReg

  // RX datapath: Physical -> Adapter -> Protocol
  io.fdi.plData := dataBuffRcvReg
  io.fdi.plValid := dataBuffRcvFillReg

  dataBuffRcvReg := dataBuffRcvReg
  when(rxBeatAcceptedFromRdi) {
    dataBuffRcvReg := io.rdi.plData
  }

  dataBuffRcvFillReg := dataBuffRcvFillReg
  when(rxBeatAcceptedFromRdi) {
    dataBuffRcvFillReg := true.B
  }.elsewhen(!io.state.rxActiveReq) {
    dataBuffRcvFillReg := false.B
  }
}
