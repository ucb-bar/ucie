package edu.berkeley.cs.uciedigital.utils

import chisel3._
import chisel3.util._

class Ser21 extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val d0 = Input(UInt(1.W))
    val d1 = Input(UInt(1.W))
    val out = Output(UInt(1.W))
  })

  override def desiredName = "ser21"

  addResource("/vsrc/ser21.v")
}