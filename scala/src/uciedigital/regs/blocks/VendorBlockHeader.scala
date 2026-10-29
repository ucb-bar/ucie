/*
  The shared vendor block header and its custom register checks (sec 9.5.5).
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import freechips.rocketchip.regmapper.RegField

object VendorBlockHeader {
  def rows(
      f: RegFieldTypes,
      vendorId: BigInt,
      blockId: BigInt,
      blockLength: BigInt,
      blockName: String
  ): Seq[(Int, Seq[RegField])] = Seq(
    0x0 -> Seq(
      f.HWInit(
        16,
        vendorId,
        "vendor_id",
        "Vendor ID (implementation-specific block)"
      ),
      f.HWInit(
        16,
        blockId,
        "vendor_id_reg_block",
        s"Vendor ID Register Block $blockName"
      )
    ),
    0x4 -> Seq(
      f.HWInit(
        4,
        0x0,
        "vendor_reg_block_version",
        "Vendor Register Block Version"
      ),
      f.RsvdP(28, "vendor_reg_block_rsvd_63_36")
    ),
    0x8 -> Seq(
      f.HWInit(
        32,
        blockLength,
        "vendor_reg_block_length",
        "Vendor Register Block Length"
      )
    ),
    0xc -> Seq(f.RsvdP(32, "vendor_reg_block_rsvd_127_96"))
  )

  // A custom register goes past the built-in region, 4-byte aligned, inside the
  // block length, and clear of every placed register and of each other.
  def requireCustomRegs(
      label: String,
      offsets: Seq[Int],
      firstCustom: Int,
      blockLength: BigInt,
      occupied: Set[Int]
  ): Unit = {
    offsets.foreach { off =>
      require(
        off >= firstCustom,
        s"$label customReg offset 0x${off.toHexString} must be >= " +
          s"0x${firstCustom.toHexString} (past the built-in registers)"
      )
      require(
        off % 4 == 0,
        s"$label customReg offset 0x${off.toHexString} must be 4-byte aligned"
      )
      require(
        BigInt(off) < blockLength,
        s"$label customReg offset 0x${off.toHexString} must be < block " +
          s"length 0x${blockLength.toString(16)}"
      )
      require(
        !occupied.contains(off),
        s"$label customReg offset 0x${off.toHexString} collides with a " +
          "built-in register"
      )
    }
    require(
      offsets.distinct.size == offsets.size,
      s"$label customReg offsets must be distinct, got " +
        s"${offsets.map(_.toHexString)}"
    )
  }
}
