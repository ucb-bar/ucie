/*
  Parameter groups for the UCIe top level. validate() checks the layers agree.
 */

package edu.berkeley.cs.uciedigital.top

import edu.berkeley.cs.uciedigital.rdi._
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.logphy.AfeParams
import edu.berkeley.cs.uciedigital.regs.{PhyCapabilityRegParams, UcieRegParams}
import edu.berkeley.cs.uciedigital.sideband.{
  SidebandPriorityQueueDepths,
  SidebandParams
}

case class ProtocolTopParams(fdi: FdiParams)

case class AdapterTopParams(
    fdi: FdiParams,
    rdi: RdiParams,
    sideband: SidebandParams = new SidebandParams()
)

case class LogicalPhyTopParams(
    afe: AfeParams = AfeParams(),
    sideband: SidebandParams = new SidebandParams(),
    rdi: RdiParams,
    retryW: Int = 10,
    desTimeoutCycles: Int = 512,
    queueDepths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths(),

    // True uses the PHY divided clocks. Only false complies with sec 10.1.2.
    separateDivClocks: Boolean = false
)

case class UcieDigitalTopParams(
    protocol: ProtocolTopParams,
    adapter: AdapterTopParams,
    logPhy: LogicalPhyTopParams,
    regs: UcieRegParams = UcieRegParams(
      phyCapability = PhyCapabilityRegParams(
        // Integration sets this per channel from Tables 5-10 and 5-11.
        terminatedAtRate = Map.empty,

        // sec 5.3.3 requires Tx Eq at 24 GT/s, the rate this build advertises.
        txEqSupport = true,
        txVswingCode = 0x1,
        rxClockModeSupport = 0x0,
        rxClockPhaseSupport = 0x0
      ),
      numModules = 1,

      // It holds the Protocol Layer's link triggers and its pl_error log.
      hasVendorD2dBlock = true,
      includeInterruptNode = true
    )
) {
  def validate(): UcieDigitalTopParams = {
    require(
      protocol.fdi == adapter.fdi,
      s"Protocol FDI params ${protocol.fdi} must match adapter FDI params ${adapter.fdi}"
    )
    require(
      adapter.rdi == logPhy.rdi,
      s"Adapter RDI params ${adapter.rdi} must match logical PHY RDI params ${logPhy.rdi}"
    )
    require(
      adapter.sideband == logPhy.sideband,
      "Adapter sideband params must match logical PHY sideband params"
    )
    require(
      regs.maxLinkWidth == logPhy.afe.mbLanes,
      s"Register link width x${regs.maxLinkWidth} must match the logical PHY x${logPhy.afe.mbLanes}"
    )
    require(
      regs.phyBridge == logPhy.afe.bridge,
      "The register block and the logical PHY must be built from the same PhyBridgeParams, " +
        s"got ${regs.phyBridge.knobs
            .map(_.name)} against ${logPhy.afe.bridge.knobs.map(_.name)}"
    )
    this
  }
}

object UcieDigitalTopParams {
  def default(): UcieDigitalTopParams = {
    val explicitFdi = FdiParams(nBytes = 64, ncWidth = 32)
    val explicitRdi = RdiParams(nBytes = 64, ncWidth = 32)

    UcieDigitalTopParams(
      protocol = ProtocolTopParams(fdi = explicitFdi),
      adapter = AdapterTopParams(fdi = explicitFdi, rdi = explicitRdi),
      logPhy = LogicalPhyTopParams(rdi = explicitRdi)
    ).validate()
  }
}
