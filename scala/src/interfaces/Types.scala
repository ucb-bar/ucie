package edu.berkeley.cs.uciedigital.interfaces

import chisel3._

/** The speed of the physical layer of the link, in GT/s. */
object SpeedMode extends ChiselEnum {
  val speed4 = Value(0x0.U(3.W))
  val speed8 = Value(0x1.U(3.W))
  val speed12 = Value(0x2.U(3.W))
  val speed16 = Value(0x3.U(3.W))
  val speed24 = Value(0x4.U(3.W))
  val speed32 = Value(0x5.U(3.W))
  val speed48 = Value(0x6.U(3.W))
  val speed64 = Value(0x7.U(3.W))
}