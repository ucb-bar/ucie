/*
  Description:
    Handles protocol-layer FDI control handshakes and tracks the negotiated runtime state.
*/

package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._
import edu.berkeley.cs.uciedigital.interfaces._

class ProtocolStateControllerIO() extends Bundle {
  val ctrl = new ProtocolLayerCtrlIO()
  val fdi = new Bundle {
    val plStateSts = Input(FDIState())
    val plInbandPres = Input(Bool())
    val plRxActiveReq = Input(Bool())
    val plProtocol = Input(FDIProtocol())
    val plProtocolFlitFmt = Input(FDIFlitFormat())
    val plProtocolVld = Input(Bool())
    val plStallReq = Input(Bool())
    val plClkReq = Input(Bool())
    val lpStateReq = Output(FDIStateReq())
    val lpLinkError = Output(Bool())
    val lpStallAck = Output(Bool())
    val lpWakeReq = Output(Bool())
    val lpClkAck = Output(Bool())
    val lpRxActiveSts = Output(Bool())
  }
  val txIdle = Input(Bool())
  val rxReadyForActive = Input(Bool())
  val clearRuntimeState = Output(Bool())
  val status = new ProtocolLayerStatusIO()
}

class ProtocolStateController() extends Module {
  val io = IO(new ProtocolStateControllerIO())

  val negotiatedProtocolReg = RegInit(FDIProtocol.pcieNoManagementTransport)
  val negotiatedFlitFormatReg = RegInit(FDIFlitFormat.rawFormat)
  val negotiatedValidReg = RegInit(false.B)
  val stallAckReg = RegInit(false.B)

  when(!io.fdi.plInbandPres) {
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

  val requestedState = WireDefault(FDIStateReq.nop)
  when(io.ctrl.requestDisable) {
    requestedState := FDIStateReq.disabled
  }.elsewhen(io.ctrl.requestLinkReset) {
    requestedState := FDIStateReq.linkReset
  }.elsewhen(io.ctrl.requestRetrain) {
    requestedState := FDIStateReq.retrain
  }

  when(!io.fdi.plStallReq) {
    stallAckReg := false.B
  }.elsewhen(io.txIdle) {
    stallAckReg := true.B
  }
  val clkAckReg = RegInit(false.B)
  clkAckReg := io.fdi.plClkReq

  object RxActiveState extends ChiselEnum {
    val sIdle, sWaitAssert, sAsserted, sWaitDeassert = Value
  }

  val rxActiveStateReg = RegInit(RxActiveState.sIdle)
  val nextRxActiveState = WireDefault(rxActiveStateReg)

  io.fdi.lpRxActiveSts := false.B

  switch(rxActiveStateReg) {
    is(RxActiveState.sIdle) {
      when(io.fdi.plRxActiveReq) {
        nextRxActiveState := RxActiveState.sWaitAssert
      }
    }
    is(RxActiveState.sWaitAssert) {
      when(!io.fdi.plRxActiveReq) {
        nextRxActiveState := RxActiveState.sIdle
      }.elsewhen(io.rxReadyForActive) {
        nextRxActiveState := RxActiveState.sAsserted
      }
    }
    is(RxActiveState.sAsserted) {
      io.fdi.lpRxActiveSts := true.B
      when(!io.fdi.plRxActiveReq) {
        nextRxActiveState := RxActiveState.sWaitDeassert
      }
    }
    is(RxActiveState.sWaitDeassert) {
      when(!io.fdi.plRxActiveReq) {
        nextRxActiveState := RxActiveState.sIdle
      }.elsewhen(io.rxReadyForActive) {
        nextRxActiveState := RxActiveState.sAsserted
      }
    }
  }

  rxActiveStateReg := nextRxActiveState

  io.fdi.lpStateReq := requestedState
  io.fdi.lpLinkError := false.B
  io.fdi.lpStallAck := stallAckReg
  io.fdi.lpWakeReq := true.B
  io.fdi.lpClkAck := clkAckReg
  io.clearRuntimeState := !io.fdi.plInbandPres || (io.fdi.plStateSts =/= FDIState.active)

  io.status.linkState := io.fdi.plStateSts
  io.status.negotiatedProtocolValid := negotiatedValidReg
  io.status.negotiatedProtocol := negotiatedProtocolReg
  io.status.negotiatedFlitFormat := negotiatedFlitFormatReg
  io.status.stalled := stallAckReg
  io.status.rxOverflow := false.B

  val prevClkReqReg = RegNext(io.fdi.plClkReq, false.B)
  val prevRxActiveReqReg = RegNext(io.fdi.plRxActiveReq, false.B)
  val prevNegotiatedValidReg = RegNext(negotiatedValidReg, false.B)
  val prevNegotiatedProtocolReg = RegNext(negotiatedProtocolReg)
  val prevNegotiatedFlitFormatReg = RegNext(negotiatedFlitFormatReg)

  block(Verification) {
    block(Verification.Assert) {
      when(io.fdi.plClkReq && !prevClkReqReg) {
        assert(!io.fdi.lpClkAck,
          "FATAL: lp_clk_ack must not assert in the same cycle as pl_clk_req")
      }
      when(io.fdi.plRxActiveReq && !prevRxActiveReqReg) {
        assert(!io.fdi.lpRxActiveSts,
          "FATAL: lp_rx_active_sts must not assert in the same cycle as pl_rx_active_req")
      }
      when(io.fdi.lpStallAck) {
        assert(io.txIdle,
          "FATAL: lp_stall_ack must only assert after TX goes idle")
      }
      when(prevNegotiatedValidReg && io.fdi.plInbandPres) {
        assert(
          negotiatedProtocolReg === prevNegotiatedProtocolReg,
          "FATAL: negotiated protocol changed while pl_inband_pres remained asserted"
        )
        assert(
          negotiatedFlitFormatReg === prevNegotiatedFlitFormatReg,
          "FATAL: negotiated flit format changed while pl_inband_pres remained asserted"
        )
      }
    }
  }
}
