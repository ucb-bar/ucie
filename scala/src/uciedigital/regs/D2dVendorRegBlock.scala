// D2D Adapter vendor register block (Block ID 2h) with its own header and room for custom registers.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import freechips.rocketchip.regmapper.RegField

class D2dVendorRegBlock(f: RegFieldTypes, params: UcieRegParams, vIn: D2dToVendor, vOut: VendorToD2d) {
  private val BlockId = 0x2
  private val blockLength: BigInt = UcieRegMap.VendorBlockSize
  private val FirstCustom = 0x10

  protected def customRegs: Seq[(Int, Seq[RegField])] = Seq.empty

  private val headerRows: Seq[(Int, Seq[RegField])] = Seq(
    0x0 -> Seq(
      f.HWInit(16, params.vendorId, "vendor_id", "Vendor ID (implementation-specific block)"),
      f.HWInit(16, BlockId, "vendor_id_reg_block", "Vendor ID Register Block D2D")
    ),
    0x4 -> Seq(
      f.HWInit(4, 0x0, "vendor_reg_block_version", "Vendor Register Block Version"),
      f.RsvdP(28, "vendor_reg_block_rsvd_63_36")
    ),
    0x8 -> Seq(
      f.HWInit(32, blockLength, "vendor_reg_block_length",
        "Vendor Register Block Length")
    ),
    0xc -> Seq(f.RsvdP(32, "vendor_reg_block_rsvd_127_96"))
  )

  def entries(base: BigInt): Seq[RegField.Map] = {
    val occupied = headerRows.map(_._1).toSet
    val customOffs = customRegs.map(_._1)
    customOffs.foreach { off =>
      require(off >= FirstCustom,
        s"D2D vendor customReg offset 0x${off.toHexString} must be >= 0x${FirstCustom.toHexString} (past the header)")
      require(off % 4 == 0, s"D2D vendor customReg offset 0x${off.toHexString} must be 4-byte aligned")
      require(BigInt(off) < blockLength,
        s"D2D vendor customReg offset 0x${off.toHexString} must be < block length 0x${blockLength.toString(16)}")
      require(!occupied.contains(off),
        s"D2D vendor customReg offset 0x${off.toHexString} collides with the header")
    }
    require(customOffs.distinct.size == customOffs.size,
      s"D2D vendor customReg offsets must be distinct, got ${customOffs.map(_.toHexString)}")

    (headerRows ++ customRegs).map { case (off, fields) => (base + off).toInt -> fields }
  }
}
