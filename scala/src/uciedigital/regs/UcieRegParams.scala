// Elaboration params and memory-map allocation for the UCIe register block.
package edu.berkeley.cs.uciedigital.regs

import chisel3.util.log2Ceil

object UcieDeviceType extends Enumeration {
  val RP, DSP, EP, USP, Retimer = Value
}

object UciePackageType extends Enumeration {
  val Standard, Advanced = Value
}

case class PhyCapabilityRegParams(
    terminatedLink: Boolean,
    txEqSupport: Boolean,
    txVswingCode: Int,
    rxClockModeSupport: Int,
    rxClockPhaseSupport: Int,
    packageType: UciePackageType.Value = UciePackageType.Standard,
    tcmSupport: Boolean = false,
    tarrSupport: Boolean = false
) {
  require(
    txVswingCode >= 0x1 && txVswingCode <= 0x10,
    s"txVswingCode must be a valid Tx Vswing encoding 0x1..0x10 (Table 9-47), got 0x${txVswingCode.toHexString}"
  )
  require(
    Set(0x0, 0x2).contains(rxClockModeSupport),
    s"rxClockModeSupport must be 0x0 or 0x2 (Table 9-47), got 0x${rxClockModeSupport.toHexString}"
  )
  require(
    Set(0x0, 0x1, 0x2).contains(rxClockPhaseSupport),
    s"rxClockPhaseSupport must be 0x0, 0x1, or 0x2 (Table 9-47), got 0x${rxClockPhaseSupport.toHexString}"
  )
}

case class UcieRegParams(
    phyCapability: PhyCapabilityRegParams,
    baseAddress: BigInt = 0x0,
    deviceType: UcieDeviceType.Value = UcieDeviceType.RP,
    numModules: Int = 1,
    maxLinkWidth: Int = 16,
    maxLinkSpeedGTs: Int = 24,
    hasSbMailbox: Boolean = true,
    hasCxlLogs: Boolean = false,
    rawFormatCapable: Boolean = true,
    vendorId: Int = 0xd2de,
    hasVendorPhyBlock: Boolean = true,
    hasVendorD2dBlock: Boolean = false,
    includeRegNode: Boolean = true,
    includeInterruptNode: Boolean = false
) {
  def packageType: UciePackageType.Value = phyCapability.packageType
  require(
    vendorId >= 0 && vendorId <= 0xffff,
    s"vendorId must be a 16-bit value, got 0x${vendorId.toHexString}"
  )
  require(
    numModules >= 1 && numModules <= 4,
    s"numModules must be in 1..4, got $numModules"
  )
  if (packageType == UciePackageType.Standard) {
    require(
      Seq(8, 16).contains(maxLinkWidth),
      s"Standard package supports only x8/x16, got x$maxLinkWidth"
    )
  }
  require(
    UcieRegParams.speedEncoding.contains(maxLinkSpeedGTs),
    s"maxLinkSpeedGTs must be one of ${UcieRegParams.speedEncoding.keys.toSeq.sorted}, got $maxLinkSpeedGTs"
  )

  lazy val allocation: UcieRegAllocation = UcieRegMap(this)

  require(
    (baseAddress & (allocation.regionSize - 1)) == 0,
    s"baseAddress 0x${baseAddress.toString(16)} must be aligned to region size " +
      s"0x${allocation.regionSize.toString(16)}"
  )
}

object UcieRegParams {
  val widthEncoding: Map[Int, Int] =
    Map(16 -> 0x0, 32 -> 0x1, 64 -> 0x2, 128 -> 0x3, 256 -> 0x4, 8 -> 0x7)
  val speedEncoding: Map[Int, Int] =
    Map(4 -> 0x0, 8 -> 0x1, 12 -> 0x2, 16 -> 0x3, 24 -> 0x4, 32 -> 0x5, 48 -> 0x6, 64 -> 0x7)

  def locatorCountEncoding(numLocators: Int): Int = numLocators match {
    case 1 => 0x7
    case 2 => 0x0
    case 3 => 0x1
    case 4 => 0x2
    case n =>
      throw new IllegalArgumentException(
        s"Register locator count must be 1..4 for UCIe 3.0 (Table 9-7 note), got $n"
      )
  }
}

case class UcieRegAllocation(
    dvsecBase: BigInt,
    d2dPhyBase: BigInt,
    phyVendorBase: Option[BigInt],
    d2dVendorBase: Option[BigInt],
    numLocators: Int,
    capabilityDescriptorLocatorCode: Int,
    usedBytes: BigInt,
    regionSize: BigInt
)

object UcieRegMap {
  val DvsecPageSize: BigInt = 0x1000
  val D2dPhySize: BigInt = 0x2000
  val VendorBlockSize: BigInt = 0x1000

  private def nextPow2(n: BigInt): BigInt = BigInt(1) << log2Ceil(n)

  def apply(params: UcieRegParams): UcieRegAllocation = {
    val dvsecBase = BigInt(0x0)
    val d2dPhyBase = dvsecBase + DvsecPageSize
    val vendorBase = d2dPhyBase + D2dPhySize

    val phyVendorBase = if (params.hasVendorPhyBlock) Some(vendorBase) else None
    val d2dVendorBase =
      if (params.hasVendorD2dBlock) {
        Some(if (params.hasVendorPhyBlock) vendorBase + VendorBlockSize else vendorBase)
      } else None

    val numVendorBlocks =
      (if (params.hasVendorPhyBlock) 1 else 0) + (if (params.hasVendorD2dBlock) 1 else 0)
    val usedBytes = vendorBase + numVendorBlocks * VendorBlockSize

    val numLocators = 1 + numVendorBlocks

    UcieRegAllocation(
      dvsecBase = dvsecBase,
      d2dPhyBase = d2dPhyBase,
      phyVendorBase = phyVendorBase,
      d2dVendorBase = d2dVendorBase,
      numLocators = numLocators,
      capabilityDescriptorLocatorCode = UcieRegParams.locatorCountEncoding(numLocators),
      usedBytes = usedBytes,
      regionSize = nextPow2(usedBytes)
    )
  }
}
