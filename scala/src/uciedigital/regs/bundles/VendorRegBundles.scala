/*
  The IO between the vendor register blocks and their datapaths (sec 9.5.5).
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util.Valid
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.logphy.{
  AfeParams,
  VendorTrainerCtrl,
  VendorTrainerStatus
}

class VendorToPhy(
    p: PhyBridgeParams = PhyBridgeParams(),
    slots: PhySlots = PhySlots(16)
) extends Bundle {
  val tsOverrideEnable = Bool()
  val remoteRxTerminated = Bool()
  val bridgeConfig = new PhyBridgeConfig(p, slots)
  val trainer = new VendorTrainerCtrl(
    AfeParams(mbLanes = slots.numDataLanes, bridge = p)
  )
}

class PhyToVendor(
    p: PhyBridgeParams = PhyBridgeParams(),
    slots: PhySlots = PhySlots(16)
) extends Bundle {
  val bridgeStatus = new PhyBridgeStatus(p, slots)
  val trainer = new VendorTrainerStatus(
    AfeParams(mbLanes = slots.numDataLanes, bridge = p)
  )
  val partnerVoltageSwing = Valid(UInt(5.W))
  val partnerEyeEdges = Valid(new PartnerEyeEdges)
}

// Table 9-9 has no LinkReset, Disable or LinkError bit, so vendor bits do.
class VendorToD2d extends Bundle {
  val protoLinkReset = Bool()
  val protoDisable = Bool()
  val protoLinkError = Bool()
}

class D2dToVendor extends Bundle {
  val plErrorReceived = Bool()
}
