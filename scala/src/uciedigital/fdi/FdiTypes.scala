/*
  FDI encodings from Table 10-3. The widths match the table from spec.
 */

package edu.berkeley.cs.uciedigital.fdi

import chisel3._

object FDIProtocol extends ChiselEnum {
  val pcieNoManagementTransport = Value("b0000".U(4.W))
  val cxl1NoManagementTransport = Value("b0011".U(4.W))
  val cxl2NoManagementTransport = Value("b0100".U(4.W))
  val cxl3NoManagementTransport = Value("b0101".U(4.W))
  val cxl4NoManagementTransport = Value("b0110".U(4.W))
  val streamingNoManagementTransport = Value("b0111".U(4.W))
  val pcieWithManagementTransport = Value("b1000".U(4.W))
  val managementTransport = Value("b1001".U(4.W))
  val cxl1WithManagementTransport = Value("b1011".U(4.W))
  val cxl2WithManagementTransport = Value("b1100".U(4.W))
  val cxl3WithManagementTransport = Value("b1101".U(4.W))
  val cxl4WithManagementTransport = Value("b1110".U(4.W))
  val streamingWithManagementTransport = Value("b1111".U(4.W))
}

object FDIFlitFormat extends ChiselEnum {
  // Table 10-3 calls 0000b Reserved. The Adapter drives it out of reset
  // (sec 10.2.1), before pl_protocol_vld makes the field meaningful.
  val reserved = Value("b0000".U(4.W))
  val rawFormat = Value("b0001".U(4.W))
  val format68B = Value("b0010".U(4.W))
  val standard256BEndHeader = Value("b0011".U(4.W))
  val standard256BStartHeader = Value("b0100".U(4.W))
  val latencyOptimized256BNoOptionalBytes = Value("b0101".U(4.W))
  val latencyOptimized256BWithOptionalBytes = Value("b0110".U(4.W))
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

// lp_stream and pl_stream matter only while the matching valid is asserted.
// This configuration uses only stack0Streaming.
object StreamId extends ChiselEnum {
  val reserved = Value(0x00.U(8.W))
  val stack0Pcie = Value(0x01.U(8.W))
  val stack0CxlIo = Value(0x02.U(8.W))
  val stack0CxlCacheMem = Value(0x03.U(8.W))
  val stack0Streaming = Value(0x04.U(8.W))
  val stack0ManagementTransport = Value(0x05.U(8.W))
  val stack1Pcie = Value(0x11.U(8.W))
  val stack1CxlIo = Value(0x12.U(8.W))
  val stack1CxlCacheMem = Value(0x13.U(8.W))
  val stack1Streaming = Value(0x14.U(8.W))
  val stack1ManagementTransport = Value(0x15.U(8.W))
}
