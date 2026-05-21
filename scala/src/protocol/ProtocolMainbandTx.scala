/*
  Description:
    Sends raw mainband beats from the chip-facing interface onto FDI when the link is active.
*/

package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

class ProtocolMainbandTxIO(nBytes: Int, depth: Int) extends Bundle {
  val chip = Flipped(Decoupled(new ProtocolRawBeat(nBytes)))
  val fdi = new Bundle {
    val plTrdy = Input(Bool())
    val lpIrdy = Output(Bool())
    val lpValid = Output(Bool())
    val lpData = Output(UInt((8 * nBytes).W))
  }
  val active = Input(Bool())
  val stallRequested = Input(Bool())
  val txIdle = Output(Bool())
}

class ProtocolMainbandTx(nBytes: Int, depth: Int) extends Module {
  val io = IO(new ProtocolMainbandTxIO(nBytes, depth))

  val flushQueue = WireDefault(false.B)
  val queue = withReset(reset.asBool || flushQueue) {
    Module(new Queue(new ProtocolRawBeat(nBytes), depth, pipe = true))
  }

  val ingressBlocked = io.stallRequested || !io.active
  io.chip.ready := queue.io.enq.ready && !ingressBlocked
  queue.io.enq.valid := io.chip.valid && !ingressBlocked
  queue.io.enq.bits := io.chip.bits

  val allowTransmit = io.active && !io.stallRequested

  io.fdi.lpIrdy := queue.io.deq.valid && allowTransmit
  io.fdi.lpValid := queue.io.deq.valid && allowTransmit
  io.fdi.lpData := queue.io.deq.bits.data
  queue.io.deq.ready := allowTransmit && io.fdi.plTrdy

  when(io.stallRequested) {
    flushQueue := true.B
  }
  
  io.txIdle := !queue.io.deq.valid || flushQueue

  block(Verification) {
    block(Verification.Assert) {
      when(io.fdi.lpValid) {
        assert(io.active,
          "FATAL: ProtocolMainbandTx presented TX data while FDI was not ACTIVE")
      }
    }
  }
}
