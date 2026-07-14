package edu.berkeley.cs.uciedigital.utils

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

class SkidBuffer(dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(dataWidth.W)))
    val out = Decoupled(UInt(dataWidth.W))
  })

  val dataReg = RegInit(0.U(dataWidth.W))
  val bypassReg = RegInit(true.B)

  when(bypassReg) { // bypass state
    when(!io.out.ready && io.in.valid) {
      dataReg := io.in.bits
      bypassReg := false.B
    }
  }.otherwise {     // skid state, bypassReg == false
    when(io.out.ready) {
      bypassReg := true.B
    }
  }

  io.in.ready := bypassReg
  io.out.bits := Mux(bypassReg, io.in.bits, dataReg)
  io.out.valid := Mux(bypassReg, io.in.valid, true.B) // valid data in the dataReg
}

object MainSkidBuffer extends App {
  ChiselStage.emitSystemVerilogFile(
    new SkidBuffer(128),
    args = Array("-td", "./generatedVerilog/utils/"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables",
    ),
  )
}