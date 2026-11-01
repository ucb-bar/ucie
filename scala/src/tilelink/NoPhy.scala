package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.diplomacy.{AddressSet, BundleBridgeSource, SimpleDevice}
import freechips.rocketchip.prci._
import freechips.rocketchip.tilelink._
import testchipip.soc.{
  ChipletIO,
  ChipletLinkWrapper,
  OffchipSubsystemParams
}

/** UCIe's TileLink frames, straight off the protocol: one frame per cycle in
  * each direction, with no handshake.
  *
  * Credit flow keeps the receiver from overflowing, and credits come back in
  * band, so the link may delay frames by any fixed number of cycles as long as
  * the credits (`UcieTLParams.tlBufferDepth`) cover the round trip. Both ends
  * have to run on the same clock; `clock` and `reset` are the link's, for
  * whatever carries the frames (e.g. a FireSim bridge).
  */
class UciePacketIO(val frameBits: Int) extends ChipletIO {
  val clock = Output(Clock())
  val reset = Output(Bool())
  val tx = Valid(UInt(frameBits.W))
  val rx = Flipped(Valid(UInt(frameBits.W)))

  def tieoff: Unit = {
    rx.valid := false.B
    rx.bits := DontCare
  }

  def connect(io: ChipletIO): Unit = io match {
    case io: UciePacketIO => {
      rx := io.tx
      io.rx := tx
    }
    case _ => assert(false, s"IO does not match UciePacketIO: ${io.getClass}")
  }

  def loopback: Unit = {
    rx := tx
  }
}

/** `UcieTL` without the PHY: the protocol (TL framing and credit flow) with
  * its frames on a `UciePacketIO`.
  *
  * There are no registers. TileLink mode and credit flow are always on, and
  * credit returns go out whenever they are due, since the link takes a frame
  * every cycle. Everything runs on the clock from `digitalClockNode`.
  */
class UcieTLNoPhy(
    params: UcieTLParams,
    managerRegion: Seq[AddressSet],
    beatBytes: Int,
    blockBytes: Int
)(implicit p: Parameters)
    extends LazyModule {
  override lazy val desiredName = s"UcieTLNoPhy${params.moduleSuffix}"

  val digitalClockNode = ClockSinkNode(Seq(ClockSinkParameters()))

  val device = new SimpleDevice("ucie", Seq("ucbbar,ucie"))
  val managerNode = TLManagerNode(
    Seq(UcieTL.managerPortParams(managerRegion, device, beatBytes, blockBytes))
  )
  val clientNode = TLClientNode(Seq(UcieTL.clientPortParams(params)))

  override lazy val module = new UcieTLNoPhyImpl
  class UcieTLNoPhyImpl extends LazyRawModuleImp(this) {
    val clock = digitalClockNode.in(0)._1.clock
    val reset = digitalClockNode.in(0)._1.reset
    childClock := clock
    childReset := reset
    override def provideImplicitClockToLazyChildren = true

    val (managerTl, _) = managerNode.in(0)
    val (clientTl, _) = clientNode.out(0)

    val protocol = withClockAndReset(clock, reset) {
      Module(new UcieTLProtocol(params, managerTl.params, clientTl.params))
    }
    val io = IO(new UciePacketIO(protocol.frameBits))

    protocol.io.manager <> managerTl
    clientTl <> protocol.io.client
    protocol.io.creditFlowEnable := true.B
    protocol.io.creditRetEnable := true.B

    io.clock := clock
    io.reset := reset.asBool
    io.tx.valid := protocol.io.tx.valid
    io.tx.bits := protocol.io.tx.bits
    protocol.io.tx.ready := true.B
    protocol.io.rx := io.rx
  }
}

/** The chiplet-router port for a PHY-less UCIe link (`UcieTLParams.phy` off). */
class UcieNoPhyChipletLink(
    val params: UcieTLParams,
    val sys_params: OffchipSubsystemParams,
    val id: Int
)(implicit p: Parameters)
    extends ChipletLinkWrapper {
  override lazy val desiredName = s"UcieNoPhyChipletLink${params.moduleSuffix}"

  val ucie = LazyModule(
    new UcieTLNoPhy(
      params,
      sys_params.managerRegion,
      sys_params.managerBeatBytes,
      sys_params.managerBlockBytes
    )(p)
  )
  val client_node = ucie.clientNode
  val manager_node = ucie.managerNode
  val control_manager_node: Option[TLRegisterNode] = None
  val clock_node = Some(ucie.digitalClockNode)
  val top_IO = BundleBridgeSource(() =>
    new UciePacketIO(UcieTLFrame.frameBits(params.creditBits))
  )

  override lazy val module = new UcieNoPhyChipletLinkImpl(this)
}

class UcieNoPhyChipletLinkImpl(outer: UcieNoPhyChipletLink)
    extends LazyModuleImp(outer) {
  val io = outer.top_IO.out(0)._1
  io <> outer.ucie.module.io
}
