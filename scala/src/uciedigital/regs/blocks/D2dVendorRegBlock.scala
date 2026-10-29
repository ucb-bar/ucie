/*
  The D2D Adapter vendor register block (Block ID 2h, sec 9.5.5).
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import freechips.rocketchip.regmapper.RegField

object D2dVendorOffsets {
  val ProtoCtrl = 0x10
  val PlErrorStatus = 0x14
  val PlErrorMask = 0x18
}

class D2dVendorRegBlock(
    f: RegFieldTypes,
    params: UcieRegParams,
    vIn: D2dToVendor,
    vOut: VendorToD2d
) {
  import D2dVendorOffsets._
  val BlockId = 0x2
  val blockLength: BigInt = UcieRegMap.VendorBlockSize
  val FirstCustom = 0x10

  // The Protocol Layer holds the state until software clears the bit.
  val protoLinkReset = f.RW(
    1,
    0,
    "proto_link_reset",
    "Protocol Layer LinkReset request, held while set"
  )
  val protoDisable = f.RW(
    1,
    0,
    "proto_disable",
    "Protocol Layer Disable request, held while set"
  )
  val protoLinkError = f.RW(
    1,
    0,
    "proto_link_error",
    "Protocol Layer lp_linkerror, held while set"
  )
  vOut.protoLinkReset := protoLinkReset.reg.asBool
  vOut.protoDisable := protoDisable.reg.asBool
  vOut.protoLinkError := protoLinkError.reg.asBool

  // The mask stops the interrupt below, never the status (Tables 9-30, 9-31).
  val plErrorStatus = f.RW1CS(
    1,
    vIn.plErrorReceived.asUInt,
    "pl_error_received",
    "Protocol Layer pl_error received"
  )
  val plErrorMask = f.RWS(
    1,
    1,
    "pl_error_received_mask",
    "Protocol Layer pl_error received Mask"
  )

  // A dropped beat in Raw Format is lost (no retry), so software is told.
  val plErrorIrq: Bool = plErrorStatus.reg.asBool && !plErrorMask.reg.asBool

  def customRegs: Seq[(Int, Seq[RegField])] = Seq(
    ProtoCtrl -> Seq(
      protoLinkReset.field,
      protoDisable.field,
      protoLinkError.field,
      f.RsvdP(29, "proto_ctrl_rsvd_31_3")
    ),
    PlErrorStatus -> Seq(
      plErrorStatus.field,
      f.RsvdZ(31, "pl_error_status_rsvd_31_1")
    ),
    PlErrorMask -> Seq(
      plErrorMask.field,
      f.RsvdP(31, "pl_error_mask_rsvd_31_1")
    )
  )

  val headerRows: Seq[(Int, Seq[RegField])] =
    VendorBlockHeader.rows(f, params.vendorId, BlockId, blockLength, "D2D")

  def entries(base: BigInt): Seq[RegField.Map] = {
    val occupied = headerRows.map(_._1).toSet
    VendorBlockHeader.requireCustomRegs(
      "D2D vendor",
      customRegs.map(_._1),
      FirstCustom,
      blockLength,
      occupied
    )

    (headerRows ++ customRegs).map { case (off, fields) =>
      (base + off).toInt -> fields
    }
  }
}
