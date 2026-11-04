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

/** UCIe's TileLink frames, straight off the protocol: up to one frame per
  * cycle in each direction.
  *
  * `tx.valid` means a frame was sent this cycle. The die sends only while
  * `txReady` is high, which is how a link model (`UcieLinkModel`) limits the
  * rate; `rx` has no backpressure. Credit flow keeps the receiver from
  * overflowing, and credits come back in band, so the link may delay frames by
  * any fixed number of cycles as long as the credits
  * (`UcieTLParams.tlBufferDepth`) cover the round trip. Both ends have to run
  * on the same clock (the router's, i.e. the system bus's); `clock` and
  * `reset` are the link's, for whatever carries the frames (e.g. a FireSim
  * bridge).
  *
  * `tieoff`, `connect` and `loopback` leave the link unthrottled (`txReady`
  * high); a link model drives `txReady` after them.
  */
class UciePacketIO(val frameBits: Int) extends ChipletIO {
  val clock = Output(Clock())
  val reset = Output(Bool())
  val tx = Valid(UInt(frameBits.W))
  val txReady = Input(Bool())
  val rx = Flipped(Valid(UInt(frameBits.W)))

  def tieoff: Unit = {
    txReady := true.B
    rx.valid := false.B
    rx.bits := DontCare
  }

  def connect(io: ChipletIO): Unit = io match {
    case io: UciePacketIO => {
      txReady := true.B
      io.txReady := true.B
      rx := io.tx
      io.rx := tx
    }
    case _ => assert(false, s"IO does not match UciePacketIO: ${io.getClass}")
  }

  def loopback: Unit = {
    txReady := true.B
    rx := tx
  }
}

/** `UcieTL` without the PHY: the protocol (TL framing and credit flow) with
  * its frames on a `UciePacketIO`.
  *
  * There are no registers. TileLink mode and credit flow are always on, and
  * owed credits go out with the next frame the link takes.
  *
  * Everything runs on the parent's clock, with no clock node of its own: the TL
  * ports connect to the chiplet router without a crossing, so a separate link
  * clock would only work while it happened to match the router's. Clocks the
  * real link has beyond that (the PHY's `txDivClk`, the sideband) are modeled
  * as a rate and a latency in this clock (`UcieLinkModel`), not as clock
  * domains.
  */
class UcieTLNoPhy(
    params: UcieTLParams,
    managerRegion: Seq[AddressSet],
    beatBytes: Int,
    blockBytes: Int
)(implicit p: Parameters)
    extends LazyModule {
  override lazy val desiredName = s"UcieTLNoPhy${params.moduleSuffix}"

  val device = new SimpleDevice("ucie", Seq("ucbbar,ucie"))
  val managerNode = TLManagerNode(
    Seq(UcieTL.managerPortParams(managerRegion, device, beatBytes, blockBytes))
  )
  val clientNode = TLClientNode(Seq(UcieTL.clientPortParams(params)))

  override lazy val module = new UcieTLNoPhyImpl
  class UcieTLNoPhyImpl extends LazyModuleImp(this) {
    val (managerTl, _) = managerNode.in(0)
    val (clientTl, _) = clientNode.out(0)

    val protocol = Module(
      new UcieTLProtocol(params, managerTl.params, clientTl.params)
    )
    val io = IO(new UciePacketIO(protocol.frameBits))

    protocol.io.manager <> managerTl
    clientTl <> protocol.io.client
    protocol.io.creditFlowEnable := true.B
    protocol.io.creditRetEnable := true.B

    io.clock := clock
    io.reset := reset.asBool
    // Frames wait here, if anywhere, for the link to take them. The queue
    // passes a frame straight through when empty, so it adds no latency.
    val tx =
      if (params.noPhyTxQueueDepth > 0)
        Queue(protocol.io.tx, params.noPhyTxQueueDepth, flow = true)
      else protocol.io.tx
    io.tx.valid := tx.fire
    io.tx.bits := tx.bits
    tx.ready := io.txReady
    protocol.io.rx :=
      (if (params.noPhyRxClockRatio > 0)
         UcieRxCrossing(io.rx, params.noPhyRxClockRatio)
       else io.rx)
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
  val clock_node: Option[ClockNode] = None
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

/** The timing of the controller's RX clock crossing, from a slower clock into
  * this one: frames are delivered only on cycles where the slower clock has an
  * edge (`ratio` of them, from a free-running fixed-point phase), and a frame
  * that arrives on a cycle without one waits for the next. At 800 MHz into
  * 1 GHz that is 4 cycles in 5, so a frame due on the fifth lands next to the
  * one after it, as in the RTL.
  */
class UcieRxCrossing(frameBits: Int, ratio: Double, depth: Int = 4,
    fracBits: Int = 16) extends Module {
  require(ratio > 0 && ratio <= 1)
  val io = IO(new Bundle {
    val in = Flipped(Valid(UInt(frameBits.W)))
    val out = Valid(UInt(frameBits.W))
  })
  val one = BigInt(1) << fracBits
  val inc = BigInt(math.round(ratio * one.toDouble))
  val phase = RegInit(0.U((fracBits + 1).W))
  val next = phase +& inc.U
  val edge = next >= one.U
  phase := Mux(edge, next - one.U, next)

  val q = Module(new Queue(UInt(frameBits.W), depth))
  q.io.enq.valid := io.in.valid
  q.io.enq.bits := io.in.bits
  assert(!io.in.valid || q.io.enq.ready, "UcieRxCrossing overflow")
  q.io.deq.ready := edge
  io.out.valid := q.io.deq.valid && edge
  io.out.bits := q.io.deq.bits
}

object UcieRxCrossing {
  def apply(in: Valid[UInt], ratio: Double): Valid[UInt] = {
    val c = Module(new UcieRxCrossing(in.bits.getWidth, ratio))
    c.io.in := in
    c.io.out
  }
}
