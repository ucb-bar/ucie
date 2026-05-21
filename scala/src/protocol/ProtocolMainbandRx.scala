/*
  Description:
    Receives raw mainband beats from FDI and buffers them for the chip-facing interface.
*/

package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

class ProtocolMainbandRxIO(nBytes: Int, depth: Int) extends Bundle {
  val fdi = new Bundle {
    val plValid = Input(Bool())
    val plData = Input(UInt((8 * nBytes).W))
  }
  val chip = Decoupled(new ProtocolRawBeat(nBytes))
  val active = Input(Bool())
  val rxPathActive = Input(Bool())
  val clear = Input(Bool())
  val rxReadyForActive = Output(Bool())
  val rxOverflow = Output(Bool())
}

class ProtocolMainbandRx(nBytes: Int, depth: Int) extends Module {
  val io = IO(new ProtocolMainbandRxIO(nBytes, depth))

  val queue = withReset(reset.asBool || io.clear) {
    Module(new Queue(new ProtocolRawBeat(nBytes), depth, pipe = true))
  }
  val rxOverflowReg = RegInit(false.B) // Can't overflow; no spec-defined protocol backpressure

  val captureEnabled = io.active && io.rxPathActive
  val rxBeat = Wire(new ProtocolRawBeat(nBytes))
  rxBeat.data := io.fdi.plData

  queue.io.enq.valid := io.fdi.plValid && captureEnabled
  queue.io.enq.bits := rxBeat
  io.chip <> queue.io.deq

  when(io.clear) {
    rxOverflowReg := false.B
  }.elsewhen(io.fdi.plValid && captureEnabled && !queue.io.enq.ready) {
    rxOverflowReg := true.B
  }

  io.rxReadyForActive := queue.io.enq.ready && !rxOverflowReg
  io.rxOverflow := rxOverflowReg

  block(Verification) {
    block(Verification.Assert) {
      when(io.fdi.plValid) {
        assert(captureEnabled,
          "FATAL: ProtocolMainbandRx observed payload data while RX path was not ACTIVE")
      }
      assert(!rxOverflowReg, "FATAL: ProtocolMainbandRx overflowed its buffer")
    }
  }
}
