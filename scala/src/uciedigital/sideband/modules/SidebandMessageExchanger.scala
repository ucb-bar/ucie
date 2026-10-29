/*
  Sends one sideband message and waits for one reference message.
 */

package edu.berkeley.cs.uciedigital.sideband

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util._

class SidebandMessageExchanger(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
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

  io.resp.bits := io.sbLaneIo.rx.bits.data
  io.resp.valid := false.B

  // Note: hold io.req.valid until msgSent, or the message can be lost.
  io.sbLaneIo.tx.valid := !msgSent && io.req.valid
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

    // Note: callers read resp.bits in this cycle, which is a long path from
    // sbLaneIo.rx.bits.data. If it fails timing, register the reference first.
    io.resp.valid := true.B
  }

  when(io.clear) {
    msgSent := false.B
    msgReceived := false.B
  }

  io.msgSent := msgSent
  io.msgReceived := msgReceived

  io.exchDone := msgSent && msgReceived
}

object SidebandMessageExchanger {
  // A caller overrides this in the sub-state that uses the exchanger. The
  // reference is derived from the port, so it cannot differ from the MixedVec.
  def tieOff(x: SidebandMessageExchanger): Unit = {
    x.io.req.valid := false.B
    x.io.req.bits := 0.U
    x.io.rxRefBitPattern.valid := false.B
    x.io.rxRefBitPattern.bits := 0.U.asTypeOf(
      chiselTypeOf(x.io.rxRefBitPattern.bits)
    )
  }
}
