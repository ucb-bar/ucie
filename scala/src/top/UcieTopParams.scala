/*
  Description:
    Parameter groupings for the UCIe top-level integration wrapper.
*/
package edu.berkeley.cs.uciedigital.top

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy.AfeParams
import edu.berkeley.cs.uciedigital.protocol.ProtocolLayerParams
import edu.berkeley.cs.uciedigital.sideband.{SidebandPriorityQueueDepths, SidebandParams}

case class ProtocolTopParams(
  // Shared interface-shape parameter. Change only when the protocol-facing
  // data width or config-credit width must change across the integration.
  fdi: FdiParams,
  // Protocol-layer local behavior knobs.
  layer: ProtocolLayerParams = ProtocolLayerParams(),
)

case class AdapterTopParams(
  // Keep these aligned with the protocol and logical PHY sides unless
  // the wrapper itself is redesigned.
  fdi: FdiParams,
  rdi: RdiParams,
  sideband: SidebandParams = new SidebandParams(),
)

case class LogicalPhyTopParams(
  // Shared interface-shape parameters for the PHY facing side.
  afe: AfeParams = AfeParams(),
  sideband: SidebandParams = new SidebandParams(),
  rdi: RdiParams,
  retryW: Int = 10,
  desTimeoutCycles: Int = 512,
  queueDepths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths(),
)

case class UcieTopParams(
  protocol: ProtocolTopParams,
  adapter: AdapterTopParams,
  logPhy: LogicalPhyTopParams,
) {
  def validate(): UcieTopParams = {
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

object UcieTopParams {
  def default(): UcieTopParams = {
    val explicitFdi = FdiParams(nBytes = 64, ncWidth = 32)
    val explicitRdi = RdiParams(nBytes = 64, ncWidth = 32)

    UcieTopParams(
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
  ): UcieTopParams = {
    UcieTopParams(
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
