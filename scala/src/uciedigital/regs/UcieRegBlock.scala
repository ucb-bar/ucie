/*
  The UCIe register block IO and map builder (sec 9.5.1, 9.5.3, 9.5.5).
 */

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
  val sidebandRegTarget = new SidebandRegTargetIO
  val vendorToPhy =
    if (params.hasVendorPhyBlock)
      Some(Output(new VendorToPhy(params.phyBridge, params.slots)))
    else None
  val phyToVendor =
    if (params.hasVendorPhyBlock)
      Some(Input(new PhyToVendor(params.phyBridge, params.slots)))
    else None
  val vendorToD2d =
    if (params.hasVendorD2dBlock) Some(Output(new VendorToD2d)) else None
  val d2dToVendor =
    if (params.hasVendorD2dBlock) Some(Input(new D2dToVendor)) else None
}

// A named set, so a new source does not change callers that ignore them.
final case class UcieIrqs(
    linkEvent: Bool,
    linkError: Bool,
    training: Bool,
    plError: Bool
) {
  def toSeq: Seq[Bool] = Seq(linkEvent, linkError, training, plError)
}

object UcieIrqs {
  val count = 4
}

object UcieRegBlock {
  def build(
      io: UcieRegBlockIO,
      implicitReset: Reset,
      params: UcieRegParams,
      base: BigInt = 0
  ): (Seq[RegField.Map], UcieIrqs) = {
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
    adapterMerged.headerLog1Rearm := dvsec.mailboxHeaderLog1Rearm
    val block =
      new D2DPhyRegisterBlock(
        f,
        adapterMerged,
        io.regsToAdapter,
        io.phyToRegs,
        io.regsToPhy,
        params
      )

    // Kept in a val, because its interrupt output is used below.
    val phyVendor: Option[(PhyVendorRegBlock, BigInt)] =
      alloc.phyVendorBase.map { b =>
        (
          new PhyVendorRegBlock(
            f,
            params,
            io.phyToVendor.get,
            io.vendorToPhy.get
          ),
          b
        )
      }

    val d2dVendor: Option[(D2dVendorRegBlock, BigInt)] =
      alloc.d2dVendorBase.map { b =>
        (
          new D2dVendorRegBlock(
            f,
            params,
            io.d2dToVendor.get,
            io.vendorToD2d.get
          ),
          b
        )
      }

    val vendorEntries: Seq[RegField.Map] =
      phyVendor.toSeq.flatMap { case (v, b) => v.entries(base + b) } ++
        d2dVendor.toSeq.flatMap { case (v, b) => v.entries(base + b) }

    val entries: Seq[RegField.Map] =
      dvsec.entries(base + alloc.dvsecBase) ++ block.entries(
        base + alloc.d2dPhyBase
      ) ++ vendorEntries

    // The vendor blocks give these two. A build without one drives it false.
    (
      entries,
      UcieIrqs(
        linkEvent = dvsec.linkEventIrq,
        linkError = dvsec.linkErrorIrq,
        training = phyVendor.map(_._1.trainingIrq).getOrElse(false.B),
        plError = d2dVendor.map(_._1.plErrorIrq).getOrElse(false.B)
      )
    )
  }
}
