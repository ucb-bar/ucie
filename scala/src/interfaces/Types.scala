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

object LinkWidth extends ChiselEnum {
  val x4 = Value("b000".U(3.W))
  val x8 = Value("b001".U(3.W))
  val x16 = Value("b010".U(3.W))
  val x32 = Value("b011".U(3.W))
  val x64 = Value("b100".U(3.W))
  val x128 = Value("b101".U(3.W))
  val x256 = Value("b110".U(3.W))
}

object RDIState extends ChiselEnum {
  val reset = Value("b0000".U(4.W))
  val active = Value("b0001".U(4.W))
  val activePmNak = Value("b0011".U(4.W))
  val l1 = Value("b0100".U(4.W))
  val l2 = Value("b1000".U(4.W))
  val linkReset = Value("b1001".U(4.W))
  val linkError = Value("b1010".U(4.W))
  val retrain = Value("b1011".U(4.W))
  val disabled = Value("b1100".U(4.W))
}

object RDIStateReq extends ChiselEnum {
  val nop = Value("b0000".U(4.W))
  val active = Value("b0001".U(4.W))
  val l1 = Value("b0100".U(4.W))
  val l2 = Value("b1000".U(4.W))
  val linkReset = Value("b1001".U(4.W))
  val retrain = Value("b1011".U(4.W))
  val disabled = Value("b1100".U(4.W))
}

object FDIState extends ChiselEnum {
  val reset = Value(0x0.U(4.W))
  val active = Value(0x1.U(4.W))
  val activePmNak = Value(0x3.U(4.W))
  val l1 = Value(0x4.U(4.W))
  val l2 = Value(0x8.U(4.W))
  val linkReset = Value(0x9.U(4.W))
  val linkError = Value(0xa.U(4.W))
  val retrain = Value(0xb.U(4.W))
  val disabled = Value(0xc.U(4.W))
}

object FDIStateReq extends ChiselEnum {
  val nop = Value(0x0.U(4.W))
  val active = Value(0x1.U(4.W))
  val l1 = Value(0x4.U(4.W))
  val l2 = Value(0x8.U(4.W))
  val linkReset = Value(0x9.U(4.W))
  val retrain = Value(0xb.U(4.W))
  val disabled = Value(0xc.U(4.W))
}