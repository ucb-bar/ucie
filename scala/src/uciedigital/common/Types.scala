/*
  The link speed and width encodings that FDI, RDI and most blocks share.
  SpeedMode follows Table 9-8 and LinkWidth follows pl_lnk_cfg in Table 10-1.
 */

package edu.berkeley.cs.uciedigital.common

import chisel3._

// The encoding has four bits, as in Max IO Link Speed (Table 7-11) and Max Link
// Speeds (Table 9-8). pl_speedmode in Table 10-1 uses only three bits.
object SpeedMode extends ChiselEnum {
  val speed4 = Value(0x0.U(4.W))
  val speed8 = Value(0x1.U(4.W))
  val speed12 = Value(0x2.U(4.W))
  val speed16 = Value(0x3.U(4.W))
  val speed24 = Value(0x4.U(4.W))
  val speed32 = Value(0x5.U(4.W))
  val speed48 = Value(0x6.U(4.W))
  val speed64 = Value(0x7.U(4.W))

  // The rates do not follow a formula of the code, so take them from here.
  val gtsPerCode: Seq[Int] = Seq(4, 8, 12, 16, 24, 32, 48, 64)
  require(
    gtsPerCode.length == all.length,
    s"SpeedMode has ${all.length} encodings and Table 9-8 gives " +
      s"${gtsPerCode.length} rates"
  )
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
