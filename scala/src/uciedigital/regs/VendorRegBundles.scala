// HW-side bundles between the PHY/D2D vendor register blocks and their datapaths.
package edu.berkeley.cs.uciedigital.regs

import chisel3._

class VendorToPhy extends Bundle {
  val debugUnlock = Bool()
  val forceLinkActive = Bool()
  val stageSkip = UInt(15.W)
  val singleStep = Bool()
}

class PhyToVendor extends Bundle {
  val forceActiveDone = Bool()
}

class VendorToD2d extends Bundle {}

class D2dToVendor extends Bundle {}
