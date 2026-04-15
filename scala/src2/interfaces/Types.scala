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

/** ALL OLD IMPL BELOW **/

/** The state of the logical PHY. */
object PhyState extends ChiselEnum {
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

/** A request for the PHY to change state. */
object PhyStateReq extends ChiselEnum {
  val nop = Value(0x0.U(4.W))
  val active = Value(0x1.U(4.W))
  val l1 = Value(0x4.U(4.W))
  val l2 = Value(0x8.U(4.W))
  val linkReset = Value(0x9.U(4.W))
  val retrain = Value(0xb.U(4.W))
  val disabled = Value(0xc.U(4.W))
}

/** The number of physical lanes in the PHY, after link degradation. */
object PhyWidth extends ChiselEnum {
  val width8 = Value(0x1.U(3.W))
  val width16 = Value(0x2.U(3.W))
  val width32 = Value(0x3.U(3.W))
  val width64 = Value(0x4.U(3.W))
  val width128 = Value(0x5.U(3.W))
  val width256 = Value(0x6.U(3.W))
}

/** The protocol stack. Defaults to stack 0.
  *
  * Some UCIe links can support running multiple protocols over the same
  * physical link. In this case, `ProtoStack` indicates which protocol stack a
  * message is associated with.
  */
object ProtoStack extends ChiselEnum {
  val stack0 = Value(0x0.U(4.W))
  val stack1 = Value(0x1.U(4.W))
}

/** The protocol type running on the UCIe link. */
object ProtoStreamType extends ChiselEnum {

  /** PCIe */
  val PCIe = Value(0x1.U(4.W))

  /** CXL.io */
  val CXLI = Value(0x2.U(4.W))

  /** CXL.cache */
  val CXLC = Value(0x3.U(4.W))

  /** Streaming */
  val Stream = Value(0x4.U(4.W))
}

class ProtoStream extends Bundle {
  val protoStack = ProtoStack()
  val protoType = ProtoStreamType()
}

object Protocol extends ChiselEnum {
  val pcie = Value(0x0.U(3.W))
  val cxl1 = Value(0x3.U(3.W))
  val cxl2 = Value(0x4.U(3.W))
  val cxl3 = Value(0x5.U(3.W))
  val cxl4 = Value(0x6.U(3.W))
  val streaming = Value(0x7.U(3.W))
}

object FlitFormat extends ChiselEnum {
  val raw = Value(0x1.U(4.W))
  val flit68 = Value(0x2.U(4.W))
  val standard256EndHeader = Value(0x3.U(4.W))
  val standard256StartHeader = Value(0x4.U(4.W))
  val latencyOpt256NoOptional = Value(0x5.U(4.W))
  val latencyOpt256Optional = Value(0x6.U(4.W))
}
