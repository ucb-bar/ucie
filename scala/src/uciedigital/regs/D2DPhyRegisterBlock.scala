// 8 KB D2D/PHY register block shared header plus the adapter and PHY register halves.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import freechips.rocketchip.regmapper.RegField

class D2DPhyRegisterBlock(
    f: RegFieldTypes,
    adapterIo: AdapterToRegs,
    adapterOut: RegsToAdapter,
    phyIn: PhyToRegs,
    phyOut: RegsToPhy,
    params: UcieRegParams
) {
  private val adapter = new D2DAdapterRegs(f, adapterIo, adapterOut)
  private val phyGlobal = new LogPhyRegsGlobal(f, params, phyIn, phyOut)
  private val phyPerModule = new LogPhyRegsPerModule(f, params, phyIn, phyOut)

  private val vendorRegBlockLength: BigInt = UcieRegMap.D2dPhySize

  private def headerRows(base: BigInt): Seq[RegField.Map] = {
    def at(off: Int): Int = (base + off).toInt
    Seq(
      at(0x0) -> Seq(
        f.HWInit(16, params.vendorId, "Vendor_Id", "Vendor ID"),
        f.HWInit(
          16,
          0x0,
          "Vendor_Id_Reg_Block",
          "Vendor ID Register Block (D2D/PHY)"
        )
      ),
      at(0x4) -> Seq(
        f.HWInit(
          4,
          0x0,
          "Vendor_Reg_Block_Version",
          "Vendor Register Block Version"
        ),
        f.RsvdP(28, "Vendor_Reg_Block_Reserved_63_36")
      ),
      at(0x8) -> Seq(
        f.HWInit(
          32,
          vendorRegBlockLength,
          "Vendor_Reg_Block_Length",
          "Vendor Register Block Length"
        )
      ),
      at(0xc) -> Seq(f.RsvdP(32, "Vendor_Reg_Block_Reserved_127_96"))
    )
  }

  def entries(base: BigInt): Seq[RegField.Map] =
    headerRows(base) ++ adapter.entries(base) ++ phyGlobal.entries(
      base
    ) ++ phyPerModule.entries(base)
}
