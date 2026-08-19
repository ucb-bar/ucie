// UCIe register-block IO bundle and reg-map builder.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import freechips.rocketchip.regmapper.RegField

class UcieRegBlockIO(params: UcieRegParams) extends Bundle {
  val linkReset = Input(Bool())
  val adapterToRegs = Input(new AdapterToRegs)
  val regsToAdapter = Output(new RegsToAdapter)
  val phyToRegs = Input(new PhyToRegs(params.numModules))
  val regsToPhy = Output(new RegsToPhy(params.numModules))
  val linkToRegs = Input(new LinkToRegs)
  val regsToLink = Output(new RegsToLink)
  val mailboxSideband = new MailboxToSideband
  val vendorToPhy =
    if (params.hasVendorPhyBlock) Some(Output(new VendorToPhy)) else None
  val phyToVendor =
    if (params.hasVendorPhyBlock) Some(Input(new PhyToVendor)) else None
  val vendorToD2d =
    if (params.hasVendorD2dBlock) Some(Output(new VendorToD2d)) else None
  val d2dToVendor =
    if (params.hasVendorD2dBlock) Some(Input(new D2dToVendor)) else None
}

object UcieRegBlock {
  // Builds the register state + fields; `base` offsets the block. Returns the entries and the two IRQ wires.
  def build(
      io: UcieRegBlockIO,
      implicitReset: Reset,
      params: UcieRegParams,
      base: BigInt = 0
  ): (Seq[RegField.Map], Bool, Bool) = {
    val alloc = params.allocation
    val (stickyReset, nonStickyReset) = UcieResets(implicitReset, io.linkReset)
    val f = new RegFieldTypes(stickyReset, nonStickyReset)

    val dvsec = new UcieLinkDvsecRegs(
      f,
      io.linkToRegs,
      io.regsToLink,
      io.mailboxSideband,
      params
    )
    val adapterMerged = Wire(new AdapterToRegs)
    adapterMerged := io.adapterToRegs
    when(dvsec.mailboxHeaderLog1.valid) {
      adapterMerged.headerLog1.valid := true.B
      adapterMerged.headerLog1.bits := dvsec.mailboxHeaderLog1.bits
    }
    val block =
      new D2DPhyRegisterBlock(
        f,
        adapterMerged,
        io.regsToAdapter,
        io.phyToRegs,
        io.regsToPhy,
        params
      )

    val vendorEntries: Seq[RegField.Map] =
      alloc.phyVendorBase.toSeq.flatMap { b =>
        new PhyVendorRegBlock(f, params, io.phyToVendor.get, io.vendorToPhy.get)
          .entries(base + b)
      } ++ alloc.d2dVendorBase.toSeq.flatMap { b =>
        new D2dVendorRegBlock(f, params, io.d2dToVendor.get, io.vendorToD2d.get)
          .entries(base + b)
      }

    val entries: Seq[RegField.Map] =
      dvsec.entries(base + alloc.dvsecBase) ++ block.entries(
        base + alloc.d2dPhyBase
      ) ++ vendorEntries
    (entries, dvsec.linkEventIrq, dvsec.linkErrorIrq)
  }
}
