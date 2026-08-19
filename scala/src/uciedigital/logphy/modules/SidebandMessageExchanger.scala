/*
  Description:
    Used as a helper module for link training fsms and training operations to exchange sideband
    messages, since each module can exchange their own unique sideband message.

    NOTE: To fix any timing issues, can register the req and reference messages before sending them
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util._

class SidebandMessageExchanger(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    // io.req.valid and io.rxRefBitPattern.valid is decoupled because
    // Responder FSMs need to wait for a request before sending a response
    val req = Flipped(Valid(UInt(sbParams.sbNodeMsgWidth.W)))
    val rxRefBitPattern =
      Flipped(Valid(MixedVec(UInt(5.W), UInt(8.W), UInt(8.W))))
    val resp = Valid((UInt(sbParams.sbNodeMsgWidth.W)))
    val msgSent = Output(Bool())
    val msgReceived = Output(Bool())
    val exchDone = Output(Bool())
    val clear = Input(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  val msgSent = RegInit(false.B)
  val msgReceived = RegInit(false.B)

  assert(
    !(io.clear && io.req.valid),
    "[SidebandMessageExchanger] Can't assert clear and req.valid together."
  )

  when(io.clear) {
    msgSent := false.B
    msgReceived := false.B
  }

  io.resp.bits := io.sbLaneIo.rx.bits.data
  io.resp.valid := false.B

  io.sbLaneIo.tx.valid := !msgSent && io.req.valid // io.req.valid should be sticky
  io.sbLaneIo.tx.bits.data := io.req.bits
  io.sbLaneIo.rx.ready := false.B

  when((!msgSent && io.req.valid) && io.sbLaneIo.tx.ready) {
    msgSent := true.B
  }

  when(
    !msgReceived && io.sbLaneIo.rx.valid && io.rxRefBitPattern.valid &&
      SBMsgCompare(io.sbLaneIo.rx.bits.data, io.rxRefBitPattern.bits)
  ) {
    io.sbLaneIo.rx.ready := true.B
    msgReceived := true.B

    // Used to do any checks with the bits of response received. should be done
    // combinationally, and within the cycle resp valid goes high
    // Note: This might cause sbLaneIo.rx.bits.data to be a long path
    io.resp.valid := true.B
  }

  // This module can be used to send or receive a single message by driving io.req.valid
  // or io.rxRefBitPattern.valid HIGH, respectively. Use io.msgSent for send-only flows,
  // io.msgReceived for receive-only flows, and io.exchDone for send-and-receive flows.
  io.msgSent := msgSent
  io.msgReceived := msgReceived

  // Message exchange has completed.
  io.exchDone := msgSent && msgReceived
}
