/*
  Description:
    Parameter groupings for the UCIe top-level integration wrapper.
*/
package edu.berkeley.cs.uciedigital.top

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy.AfeParams
import edu.berkeley.cs.uciedigital.protocol.ProtocolLayerParams
import edu.berkeley.cs.uciedigital.regs.{PhyCapabilityRegParams, UcieRegParams}
import edu.berkeley.cs.uciedigital.sideband.{SidebandPriorityQueueDepths, SidebandParams}

case class ProtocolTopParams(
  fdi: FdiParams,
  layer: ProtocolLayerParams = ProtocolLayerParams(),
)

case class AdapterTopParams(
  fdi: FdiParams,
  rdi: RdiParams,
  sideband: SidebandParams = new SidebandParams(),
)

case class LogicalPhyTopParams(
  afe: AfeParams = AfeParams(),
  sideband: SidebandParams = new SidebandParams(),
  rdi: RdiParams,
  retryW: Int = 10,
  desTimeoutCycles: Int = 512,
  queueDepths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths(),
)

case class UcieDigitalTopParams(
  protocol: ProtocolTopParams,
  adapter: AdapterTopParams,
  logPhy: LogicalPhyTopParams,
  regs: UcieRegParams = UcieRegParams(
    phyCapability = PhyCapabilityRegParams(
      terminatedLink = false,
      txEqSupport = false,
      txVswingCode = 0x1,
      rxClockModeSupport = 0x0,
      rxClockPhaseSupport = 0x0
    ),
    numModules = 1,
    includeInterruptNode = true
  ),
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

  // Convenience helper to build a fully aligned top-level parameter set from shared interface values.
  def withSharedInterfaces(
    fdi: FdiParams,
    rdi: RdiParams,
    protocolLayer: ProtocolLayerParams = ProtocolLayerParams(),
    afe: AfeParams = AfeParams(),
    sideband: SidebandParams = new SidebandParams(),
    retryW: Int = 10,
    desTimeoutCycles: Int = 512,
    queueDepths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths(),
  ): UcieDigitalTopParams = {
    UcieDigitalTopParams(
      protocol = ProtocolTopParams(
        fdi = fdi,
        layer = protocolLayer
      ),
      adapter = AdapterTopParams(
        fdi = fdi,
        rdi = rdi,
        sideband = sideband
      ),
      logPhy = LogicalPhyTopParams(
        afe = afe,
        sideband = sideband,
        rdi = rdi,
        retryW = retryW,
        desTimeoutCycles = desTimeoutCycles,
        queueDepths = queueDepths
      )
    ).validate()
  }
}
