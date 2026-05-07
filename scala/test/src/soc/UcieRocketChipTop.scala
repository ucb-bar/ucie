package edu.berkeley.cs.uciedigital.soc

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._

import examples.rocketconfig._
import edu.berkeley.cs.uciedigital.tilelink.{UcieTLParams, UcieBumpsIO}

/** RocketSystem extended with the chiplet router so it can expose D2D ports. */
class UcieRocketSystem(implicit p: Parameters)
    extends RocketSystem
    with testchipip.soc.CanHaveChipletRouting

/** Rocket chip top with everything `RocketChipTop` exposes plus a `c2c_ucie<i>`
  * top-level IO for every UCIe port declared in `ChipletRoutingKey`.
  */
class UcieRocketChipTop(implicit p: Parameters) extends RocketChipTop {
  override protected def makeSystem(): UcieRocketSystem = new UcieRocketSystem

  override lazy val module = new UcieRocketChipTopImpl
  class UcieRocketChipTopImpl extends RocketChipTopImpl {
    private val ucieSystem = system.asInstanceOf[UcieRocketSystem]
    private val ports = p(testchipip.soc.ChipletRoutingKey).get.ports

    val c2c_ucie = ports.zipWithIndex.collect {
      case (params: UcieTLParams, i) =>
        val io = IO(new UcieBumpsIO(params.numLanes)).suggestName(s"c2c_ucie$i")
        io <> ucieSystem.d2d_port_ios.get(i)
        io
    }
  }
}

/** Adds a single UCIe D2D port to `RocketChipConfig`. The chiplet router
  * itself provides any extra MMIO surface; `WithMaxOffchipAddressRange` widens
  * the addressable off-chip region used by the router for outbound traffic.
  */
class UcieRocketChipConfig(sim: Boolean = false) extends Config(
  new testchipip.soc.WithMaxOffchipAddressRange(
    AddressSet.misaligned(0x800000000L, 0x2000000000L)
  ) ++
  new testchipip.soc.WithChipletRouting(
    testchipip.soc.ChipletRoutingParams(
      routerParams =
        testchipip.soc.OffchipRouterParams(tableEntries = 4),
      ports = Seq(
        UcieTLParams(
          address = 0x8000,
          managerWhere = SBUS,
          numLanes = 16,
          includeDefaultModels = true
        )
      )
    )
  ) ++
  new RocketChipConfig(sim)
)
