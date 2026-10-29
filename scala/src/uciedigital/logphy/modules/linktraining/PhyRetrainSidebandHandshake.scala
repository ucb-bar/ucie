/*
  The PHYRETRAIN handshake of sec 4.5.3.7, which exchanges retrain encodings.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util._

object PhyRetrainState extends ChiselEnum {
  val sPHYRETRAIN_MSG, sDONE = Value
}

class PhyRetrainSidebandHandshake(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val startPhyRetrainMsgExch = Input(Bool())
    val requesterLocalRetrainEncoding = Flipped(Valid(UInt(3.W)))
    val waitForRemoteRequest = Input(Bool())
    val responderLocalRetrainEncoding = Flipped(Valid(UInt(3.W)))

    val requesterRemoteRetrainEncoding = Valid(UInt(3.W))
    val responderRemoteRetrainEncoding = Valid(UInt(3.W))

    val done = Output(Bool())

    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })

  val requester = Module(new PhyRetrainRequester(sbParams))
  val responder = Module(new PhyRetrainResponder(sbParams))

  requester.io.startPhyRetrainMsgExch := io.startPhyRetrainMsgExch
  requester.io.localRetrainEncoding := io.requesterLocalRetrainEncoding
  requester.io.responderRdy := responder.io.responderRdy
  requester.io.sbLaneIo <> io.requesterSbLaneIo

  responder.io.waitForRemoteRequest := io.waitForRemoteRequest
  responder.io.localRetrainEncoding := io.responderLocalRetrainEncoding
  responder.io.requesterRdy := requester.io.requesterRdy
  responder.io.sbLaneIo <> io.responderSbLaneIo

  io.requesterRemoteRetrainEncoding := requester.io.remoteRetrainEncoding
  io.responderRemoteRetrainEncoding := responder.io.remoteRetrainEncoding
  io.done := requester.io.requesterRdy && responder.io.responderRdy
}

class PhyRetrainRequester(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val startPhyRetrainMsgExch = Input(Bool())
    val localRetrainEncoding =
      Flipped(Valid(UInt(3.W)))
    val responderRdy = Input(Bool())

    val remoteRetrainEncoding = Valid(UInt(3.W))
    val requesterRdy = Output(Bool())

    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val currentState = RegInit(PhyRetrainState.sPHYRETRAIN_MSG)
  val nextState = WireInit(currentState)
  currentState := nextState

  SidebandMessageExchanger.tieOff(sbMsgExchanger)

  // TODO: might need a reset?
  sbMsgExchanger.io.clear := (currentState =/= nextState)
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  val requesterRdyStatusReg = RegInit(false.B)
  val requesterRdy = WireInit(false.B)

  // The clear wins, because a rdy in the transition cycle is the old state's.
  when(currentState =/= nextState) {
    requesterRdyStatusReg := false.B
  }.elsewhen(requesterRdy) {
    requesterRdyStatusReg := true.B
  }
  io.requesterRdy := requesterRdyStatusReg || requesterRdy

  val validRemoteRetrainEncoding = RegInit(false.B)
  val remoteRetrainEncoding = RegInit(0.U(3.W))

  io.remoteRetrainEncoding.valid := validRemoteRetrainEncoding
  io.remoteRetrainEncoding.bits := remoteRetrainEncoding

  switch(currentState) {
    is(PhyRetrainState.sPHYRETRAIN_MSG) {
      sbMsgExchanger.io.req.valid := io.localRetrainEncoding.valid && io.startPhyRetrainMsgExch
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.PHYRETRAIN_RETRAIN_START_REQ,
        msgInfo = Cat(0.U(13.W), io.localRetrainEncoding.bits)
      )

      sbMsgExchanger.io.rxRefBitPattern.valid := sbMsgExchanger.io.msgSent
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.PHYRETRAIN_RETRAIN_START_RESP

      when(sbMsgExchanger.io.resp.valid) {
        validRemoteRetrainEncoding := true.B
        remoteRetrainEncoding :=
          SBMsgField.msgInfo(sbMsgExchanger.io.resp.bits)(2, 0)
      }

      requesterRdy := sbMsgExchanger.io.exchDone
      when(io.requesterRdy && io.responderRdy) {
        nextState := PhyRetrainState.sDONE
      }
    }
    is(PhyRetrainState.sDONE) {
      validRemoteRetrainEncoding := false.B
      nextState := PhyRetrainState.sPHYRETRAIN_MSG
    }
  }

  when(currentState =/= nextState) {
    sbMsgExchanger.io.req.valid := false.B
  }
}

class PhyRetrainResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val waitForRemoteRequest = Input(Bool())
    val localRetrainEncoding =
      Flipped(Valid(UInt(3.W)))
    val requesterRdy = Input(Bool())

    val remoteRetrainEncoding = Valid(UInt(3.W))
    val responderRdy = Output(Bool())

    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  val sbMsgExchanger = Module(new SidebandMessageExchanger(sbParams))

  val currentState = RegInit(PhyRetrainState.sPHYRETRAIN_MSG)
  val nextState = WireInit(currentState)
  currentState := nextState

  SidebandMessageExchanger.tieOff(sbMsgExchanger)

  // TODO: might need a reset?
  sbMsgExchanger.io.clear := (currentState =/= nextState)
  sbMsgExchanger.io.sbLaneIo <> io.sbLaneIo

  val responderRdyStatusReg = RegInit(false.B)
  val responderRdy = WireInit(false.B)

  when(currentState =/= nextState) {
    responderRdyStatusReg := false.B
  }.elsewhen(responderRdy) {
    responderRdyStatusReg := true.B
  }
  io.responderRdy := responderRdyStatusReg || responderRdy

  val validRemoteRetrainEncoding = RegInit(false.B)
  val remoteRetrainEncoding = RegInit(0.U(3.W))

  io.remoteRetrainEncoding.valid := validRemoteRetrainEncoding
  io.remoteRetrainEncoding.bits := remoteRetrainEncoding

  switch(currentState) {
    is(PhyRetrainState.sPHYRETRAIN_MSG) {
      sbMsgExchanger.io.rxRefBitPattern.valid := io.waitForRemoteRequest
      sbMsgExchanger.io.rxRefBitPattern.bits := SBM.PHYRETRAIN_RETRAIN_START_REQ

      when(sbMsgExchanger.io.resp.valid) {
        validRemoteRetrainEncoding := true.B
        remoteRetrainEncoding :=
          SBMsgField.msgInfo(sbMsgExchanger.io.resp.bits)(2, 0)
      }

      sbMsgExchanger.io.req.valid := io.localRetrainEncoding.valid
      sbMsgExchanger.io.req.bits := PhyLinkMsg.create(
        SBM.PHYRETRAIN_RETRAIN_START_RESP,
        msgInfo = Cat(0.U(13.W), io.localRetrainEncoding.bits)
      )

      responderRdy := sbMsgExchanger.io.exchDone
      when(io.requesterRdy && io.responderRdy) {
        nextState := PhyRetrainState.sDONE
      }
    }
    is(PhyRetrainState.sDONE) {
      validRemoteRetrainEncoding := false.B
      nextState := PhyRetrainState.sPHYRETRAIN_MSG
    }
  }

  when(currentState =/= nextState) {
    sbMsgExchanger.io.req.valid := false.B
  }
}
