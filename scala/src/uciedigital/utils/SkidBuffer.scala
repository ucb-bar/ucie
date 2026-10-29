/*
  A skid buffer. On a stall it holds the beat in dataReg and drops in.ready.
 */

package edu.berkeley.cs.uciedigital.utils

import chisel3._
import chisel3.util._

class SkidBuffer(dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(dataWidth.W)))
    val out = Decoupled(UInt(dataWidth.W))
  })

  val dataReg = RegInit(0.U(dataWidth.W))
  val bypassReg = RegInit(true.B)

  when(bypassReg) {
    when(!io.out.ready && io.in.valid) {
      dataReg := io.in.bits
      bypassReg := false.B
    }
  }.otherwise {
    when(io.out.ready) {
      bypassReg := true.B
    }
  }

  io.in.ready := bypassReg
  io.out.bits := Mux(bypassReg, io.in.bits, dataReg)
  io.out.valid := Mux(
    bypassReg,
    io.in.valid,
    true.B
  )
}
