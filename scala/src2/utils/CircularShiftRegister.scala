package edu.berkeley.cs.uciedigital.utils

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

class CircularShiftRegister(width: Int, shiftAmt: Int = 1) extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool()) // needs to be a synchronous reset
    val en = Input(Bool())    // when high shifts on next cycle
    val loadVal = Input(UInt(width.W))    
    val output = Output(UInt(width.W))    
  })

  assert(width > 0, "Width of circular shift register needs to be positive")
  assert(shiftAmt >= 1 && shiftAmt < width, 
         "Shift amount needs to be in between 1 and selected width. 1 <= shiftAmt < width")
  
  val circularReg = Reg(Vec(width, UInt(1.W)))

  io.output := circularReg.asUInt  

  when(reset.asBool || io.reset) {
    for(i <- 0 until width) {
      circularReg(i) := io.loadVal(i)
    }    
  }.otherwise {
    when(io.en) {
      for(i <- 0 until width) {             
        circularReg(i) := circularReg((i + shiftAmt) % width)
      }
    }
  }
}

object MainCircularShiftRegister extends App {
  ChiselStage.emitSystemVerilogFile(
    new CircularShiftRegister(5, 4),
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
