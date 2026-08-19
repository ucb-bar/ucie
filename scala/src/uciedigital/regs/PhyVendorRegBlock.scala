// PHY vendor register block (Block ID 3h) with header and training override controls.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util._
import freechips.rocketchip.regmapper.RegField

class PhyVendorRegBlock(
    f: RegFieldTypes,
    params: UcieRegParams,
    vIn: PhyToVendor,
    vOut: VendorToPhy
) {
  private val BlockId = 0x3
  private val blockLength: BigInt = UcieRegMap.VendorBlockSize
  private val FirstCustom = 0x20

  protected def customRegs: Seq[(Int, Seq[RegField])] = Seq.empty

  private val headerRows: Seq[(Int, Seq[RegField])] = Seq(
    0x0 -> Seq(
      f.HWInit(
        16,
        params.vendorId,
        "vendor_id",
        "Vendor ID (implementation-specific block)"
      ),
      f.HWInit(
        16,
        BlockId,
        "vendor_id_reg_block",
        "Vendor ID Register Block PHY"
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

  private val overrideRows: Seq[(Int, Seq[RegField])] = {
    val debugUnlock = f.RW(
      1,
      0,
      "vendor_debug_unlock",
      "VENDOR_DEBUG_CTRL Debug unlock (gates all training overrides; no-op when 0)"
    )
    val unlock = debugUnlock.reg.asBool

    val forceActive = f.RWautoClear(
      vIn.forceActiveDone,
      !unlock,
      "vendor_force_link_active",
      "TRAIN_OVERRIDE_CTRL Force Link Active (bypass mainband training; auto-clear)"
    )
    val stageSkip = f.RW(
      15,
      0,
      "vendor_stage_skip",
      "TRAIN_OVERRIDE_CTRL Stage-skip bitmap (one bit per training sub-state)"
    )
    val singleStep = f.RW(
      1,
      0,
      "vendor_single_step",
      "TRAIN_OVERRIDE_CTRL Single-step mode (halt SM at each state boundary)"
    )

    vOut.debugUnlock := unlock
    vOut.forceLinkActive := forceActive.pending && unlock
    vOut.stageSkip := Mux(unlock, stageSkip.reg, 0.U)
    vOut.singleStep := singleStep.reg.asBool && unlock

    Seq(
      0x10 -> Seq(
        debugUnlock.field,
        f.RsvdP(31, "vendor_debug_ctrl_rsvd_31_1")
      ),
      0x14 -> Seq(
        forceActive.field,
        stageSkip.field,
        singleStep.field,
        f.RsvdP(15, "train_override_ctrl_rsvd_31_17")
      ),
      0x18 -> Seq(f.RsvdP(32, "train_seq_ctrl_rsvd"))
    )
  }

  def entries(base: BigInt): Seq[RegField.Map] = {
    val occupied = (headerRows ++ overrideRows).map(_._1).toSet
    val customOffs = customRegs.map(_._1)
    customOffs.foreach { off =>
      require(
        off >= FirstCustom,
        s"PHY vendor customReg offset 0x${off.toHexString} must be >= 0x${FirstCustom.toHexString} (past built-ins)"
      )
      require(
        off % 4 == 0,
        s"PHY vendor customReg offset 0x${off.toHexString} must be 4-byte aligned"
      )
      require(
        BigInt(off) < blockLength,
        s"PHY vendor customReg offset 0x${off.toHexString} must be < block length 0x${blockLength.toString(16)}"
      )
      require(
        !occupied.contains(off),
        s"PHY vendor customReg offset 0x${off.toHexString} collides with a built-in register"
      )
    }
    require(
      customOffs.distinct.size == customOffs.size,
      s"PHY vendor customReg offsets must be distinct, got ${customOffs.map(_.toHexString)}"
    )

    (headerRows ++ overrideRows ++ customRegs).map { case (off, fields) =>
      (base + off).toInt -> fields
    }
  }
}
