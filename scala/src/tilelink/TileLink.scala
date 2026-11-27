package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem.{
  BaseSubsystem,
  PBUS,
  SBUS,
  TLBusWrapperLocation
}
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.regmapper.{RegField, RegWriteFn, RegFieldDesc}
import freechips.rocketchip.tilelink._
import edu.berkeley.cs.uciedigital.phy._
import edu.berkeley.cs.uciedigital.phytest._
import edu.berkeley.cs.uciedigital.top.{
  UcieDigitalTop,
  UcieDigitalTopChipIO,
  UcieDigitalTopParams,
  UcieDigitalTopPhyIO
}
import edu.berkeley.cs.uciedigital.regs.UcieRegBlock
import freechips.rocketchip.diplomacy.{SimpleDevice, AddressSet}
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.lazymodule._
import edu.berkeley.cs.uciedigital.phy.macros.{
  PadDriverCtlIO,
  SbSerialIO,
  TxLaneCtlIO
}
import edu.berkeley.cs.uciedigital.phy.macros.clocking.{
  ClockingTile,
  PllCfgIO,
  RstSync
}
import freechips.rocketchip.util.AsyncQueueParams
import freechips.rocketchip.util.AsyncQueue
import freechips.rocketchip.diplomacy.BufferParams
import freechips.rocketchip.diplomacy.RegionType
import freechips.rocketchip.diplomacy.TransferSizes
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.diplomacy.BundleBridgeSource
import testchipip.soc.{
  ChipletLinkParams,
  ChipletLinkWrapperInstantiationLike,
  ChipletLinkWrapper,
  OffchipSubsystemParams,
  ChipletIO
}

case class UcieTLParams(
    address: BigInt = 0x200000,
    bufferDepthPerLane: Int = 11,
    numLanes: Int = 16,
    bitCounterWidth: Int = 64,
    creditCounterSize: Int = 128,
    creditRetThreshhold: Int = 31,
    creditRetTimerWidth: Int = 7,
    tlBufferDepth: Int = 63,
    managerWhere: TLBusWrapperLocation = PBUS,
    queueParams: AsyncQueueParams = AsyncQueueParams(depth = 32),
    maxInflight: Int = 1,
    clientIdBits: Int = 8,
    includeDefaultModels: Boolean = false,
    sim: Boolean = false,
    ucieRegsBaseAddress: BigInt = 0x40000,
    // Frames the sideband TL receiver can hold before the digital domain has
    // to drain them. Must be a power of two.
    sbRxQueueDepth: Int = 4,
    // Names the emitted module (see `moduleSuffix`). Instances with the same id
    // share a SystemVerilog module. Give an instance its own id to harden it
    // separately in physical design.
    moduleId: Int = 0,
    // Netlist for the PHY's lane clock distribution network: the behavioral
    // model, or a physical network from physical design (see `ClkDistLayout`).
    // A physical network is laid out for one instance, so give that instance
    // its own `moduleId`.
    clkDistLayout: edu.berkeley.cs.uciedigital.phy.macros.clocking.ClkDistLayout =
      edu.berkeley.cs.uciedigital.phy.macros.clocking.ClkDistLayout.Behavioral,
    // Queues between the manager port and the link logic: A channel in, D
    // channel out. None by default. With them, every manager port signal is a
    // queue register or feeds only one. Then an outside bus whose `ready`
    // depends on `valid` in the same cycle (such as an arbiter in front of
    // several links) cannot form a combinational loop through the link. A link
    // hardened as its own block needs this at its boundary. Costs one cycle
    // each way.
    managerBuffer: BufferParams = BufferParams.none,
    // Whether this link's tester has the TX data debug lane and its `txData`
    // bump, which a chip with several links can keep to one of them. Without
    // it the lane's registers stay in the map but read 0 and drop writes, so
    // every link keeps one register layout.
    hasTxDebugLane: Boolean = true
) extends ChipletLinkParams
    with ChipletLinkWrapperInstantiationLike {
  def managerBusWhere = managerWhere
  // `UcieChipletLink` hands the router no control node to attach; see there.
  def controlManagerBusWhere = None

  /** Suffix appended to the module names of this instance.
    *
    * Instances with the same parameters elaborate to the same hardware, so
    * Chisel puts them in one dedup group (the proposed module name) and firtool
    * emits one SystemVerilog module. That is right when they are hardened
    * together. When physical design needs one module per instance (e.g.
    * different orientations, hardened separately), different `moduleId`s split
    * the groups and keep the hierarchies apart. Id 0 keeps the unsuffixed name.
    */
  def moduleSuffix: String = if (moduleId == 0) "" else s"_$moduleId"
  // Width of the credit fields carried alongside every framed TL packet.
  def creditBits: Int = log2Up(tlBufferDepth + 1)
  def instantiate(params: OffchipSubsystemParams, id: Int)(implicit
      p: Parameters
  ): ChipletLinkWrapper = LazyModule(new UcieChipletLink(this, params, id))
  assert(isPow2(creditCounterSize), s"Credit counter size must be a power of 2")
  assert(
    tlBufferDepth < creditCounterSize / 2,
    s"TL buffer depth must be less than half of max credits"
  )
}

case object UcieTLKey extends Field[Option[Seq[UcieTLParams]]](None)

class UcieBumpsIO(numLanes: Int = 16, hasTxDebugLane: Boolean = true)
    extends ChipletIO {
  val phy = new PhyBumpsIO(numLanes)
  val debug = new DebugBumpsIO(hasTxDebugLane)

  def tieoff: Unit = {
    phy.rxData := DontCare
    phy.rxValid := false.B
    phy.rxTrack := false.B
    phy.rxClkP := false.B.asClock
    phy.rxClkN := false.B.asClock
    phy.sbRxClk := false.B.asClock
    phy.sbRxData := DontCare
    phy.bypassClk := false.B.asClock
    phy.digitalBypassClk := false.B.asClock
    phy.sidebandBypassClk := false.B.asClock
  }

  // Bypass and reference clocks should be connected at top level
  def connect(io: ChipletIO): Unit = io match {
    case io: UcieBumpsIO => {
      phy.rxData := io.phy.txData
      phy.rxValid := io.phy.txValid
      phy.rxTrack := io.phy.txTrack
      phy.rxClkP := io.phy.txClkP
      phy.rxClkN := io.phy.txClkN
      phy.sbRxClk := io.phy.sbTxClk
      phy.sbRxData := io.phy.sbTxData

      io.phy.rxData := phy.txData
      io.phy.rxValid := phy.txValid
      io.phy.rxTrack := phy.txTrack
      io.phy.rxClkP := phy.txClkP
      io.phy.rxClkN := phy.txClkN
      io.phy.sbRxClk := phy.sbTxClk
      io.phy.sbRxData := phy.sbTxData
    }
    case _ => assert(false, s"IO does not match UcieBumpsIO: ${io.getClass}")
  }

  def loopback: Unit = {
    // Does this require a delayer?
    phy.rxData := phy.txData
    phy.rxValid := phy.txValid
    phy.rxTrack := phy.txTrack
    phy.rxClkP := phy.txClkP
    phy.rxClkN := phy.txClkN
    phy.sbRxClk := phy.sbTxClk
    phy.sbRxData := phy.sbTxData
  }
}

/** Which controller drives the link. */
object ControllerSel extends ChiselEnum {

  /** PhyTest owns both bands. `BandMode` then picks, per band, whether PhyTest
    * drives it itself or TileLink frames do.
    */
  val phytest = Value(0.U(1.W))

  /** The UCIe digital stack (`UcieDigitalTop`) owns both bands. */
  val ucie = Value(1.U(1.W))
}

class UcieTLRegsIO(
    bufferDepthPerLane: Int = 11,
    numLanes: Int = 16,
    bitCounterWidth: Int = 64,
    addrWidth: Int = 64
) extends Bundle {
  val test = Flipped(
    new PhyTestRegsIO(bufferDepthPerLane, numLanes, bitCounterWidth)
  )
  val phy = Flipped(new PhyRegsIO(numLanes))
  val controllerSel = Output(ControllerSel())
  val mainbandMode = Output(BandMode())
  val sidebandMode = Output(BandMode())
  val creditFlowEnable = Output(Bool())
  val lastSeenTLReq = Input(UInt(addrWidth.W))
  val sbTlRxOverflow = Input(Bool())
  // Drives `txClkP`/`txClkN` on the forwarded-clock lanes from the registers
  // instead of the selected controller's TX queue, for either controller.
  // The clock then keeps running through an empty queue or a datapath reset.
  // Overrides any clock gating by the controller.
  val txClkDirect = Output(Bool())
  // Sticky flags for PhyTest's mainband crossings, each in the clock domain of
  // its PHY end. TX: the TX queue ran empty while PhyTest was feeding it. RX:
  // the RX queue got a word it had no room for. Either means the PHY test
  // clock is slower than the packet rate and words were lost. Cleared by the
  // TX or RX datapath reset.
  val txTestFifoUnderflow = Input(Bool())
  val rxTestFifoOverflow = Input(Bool())
  // Sticky, on the RX divided clock: the UCIe controller's RX queue got a
  // word it had no room for while the controller owned the lanes. The cause
  // is a packet clock faster than the sideband clock that drains the queue.
  // Cleared by the RX datapath reset.
  val controllerRxFifoOverflow = Input(Bool())
  // The same for the TL path's RX queue, while `mainbandMode` is `tl`. The
  // cause is a packet clock faster than the chip clock that drains it.
  val tlRxFifoOverflow = Input(Bool())
  // Caps how many words PhyTest keeps in its TX queue; zero leaves it
  // uncapped. See `txQueueLimit` in the register map.
  val txQueueLimit = Output(UInt(UcieTLRegs.txQueueLimitWidth.W))
}

object UcieTLRegs {

  /** Cycles the `txDatapathRst` and `rxDatapathRst` strobes are held for after
    * a register write, counted in the PhyTest clock the register block and
    * PhyTest share.
    */
  val rstStrobeCycles = 8

  /** Bytes this block's registers span, from `UcieTLParams.address` up to the
    * UCIe controller's controls.
    */
  val regionSize = UcieController.ctrlBase

  /** Width of `txQueueLimit`, enough to name any occupancy of the TX queue. */
  val txQueueLimitWidth = 6

  /** `txQueueLimit` reset value: half the TX queue. It works for any PHY test
    * clock down to the packet clock (where 10 is the minimum that works), and
    * keeps a word's wait in the queue to about half that of a full queue.
    */
  val txQueueLimitReset = 16
}

/** Clock source controls, on their own clock.
  *
  * These choose where the PHY's clocks come from, so they cannot run on one of
  * them: at power up nothing would run until they are set. They run on the chip
  * clock, which runs before any PHY clock. This is a separate block, not an
  * extra domain in another, because most registers belong on the clock of the
  * logic they control.
  *
  * The selects need no synchronizer: they settle during bring-up while the
  * clocks they steer are quiet.
  */
class UcieClkRegs(
    params: UcieTLParams,
    beatBytes: Int
)(implicit
    p: Parameters
) extends ClockSinkDomain(ClockSinkParameters()) {
  def toRegFieldRw[T <: Data](r: T, name: String): RegField = {
    RegField(
      r.getWidth,
      r.asUInt,
      RegWriteFn((valid, data) => {
        when(valid) {
          r := data.asTypeOf(r)
        }
        true.B
      }),
      Some(RegFieldDesc(name, ""))
    )
  }
  def toRegFieldR[T <: Data](r: T, name: String): RegField = {
    RegField.r(r.getWidth, r.asUInt, RegFieldDesc(name, ""))
  }
  val regionSize = 0x1000
  val device = new SimpleDevice("ucie_clk_control", Seq("ucbbar,ucie-clk"))
  val node = TLRegisterNode(
    Seq(AddressSet(params.address + UcieClkRegs.offset, regionSize - 1)),
    device,
    "reg/control",
    beatBytes = beatBytes
  )

  override lazy val module = new UcieClkRegsImpl
  class UcieClkRegsImpl extends Impl with ImplicitClock with ImplicitReset {
    // Every register here is on the chip clock with an asynchronous chip
    // reset. Most drive the clocking tile (PLL and X2 enables, gates, muxes,
    // dividers) or the UCIe reset, and must hold reset values as soon as chip
    // reset asserts, even before the chip clock runs. Otherwise a PLL enable
    // that powered up on burns power until the chip clock arrives. Chip reset
    // releases in step with the chip clock, so release is clean. The clock
    // meter counters are the exception; see below.
    override protected def implicitClock = clock
    override protected def implicitReset = reset.asAsyncReset

    val io = IO(new Bundle {
      val mainClkSel = Output(UInt(ClockingTile.mainClkSelWidth.W))
      val pll8En = Output(Bool())
      val pll12En = Output(Bool())
      val x2En = Output(Bool())
      // Each PLL's loop settings and RDAC code, and PLL8's coarse frequency
      // code; see `pll8Cfg`. Each PLL's loop state comes back for reading.
      val pll8Cfg = Output(new PllCfgIO)
      val pll12Cfg = Output(new PllCfgIO)
      val pll8Dcoarse = Output(UInt(ClockingTile.pllDcoarseWidth.W))
      val pll8CodeFull = Input(UInt(20.W))
      val pll8CodeInt = Input(UInt(8.W))
      val pll12CodeFull = Input(UInt(20.W))
      val pll12CodeInt = Input(UInt(8.W))
      val txClkDiv = Output(UInt(ClockingTile.txClkDivWidth.W))
      val txClkPhase = Output(UInt(ClockingTile.txClkPhaseWidth.W))
      val phyTestClkDiv = Output(UInt(ClockingTile.phyTestClkDivWidth.W))
      val phyTestClkSrcSel = Output(UInt(ClockingTile.phyTestClkSrcSelWidth.W))
      val phyTestClkBypassEn = Output(Bool())
      val sbClkDiv = Output(UInt(ClockingTile.sbClkDivWidth.W))
      val sbClkSrcSel = Output(UInt(ClockingTile.sbClkSrcSelWidth.W))
      val sbClkBypassEn = Output(Bool())
      val rxClkFromTxQ = Output(Bool())
      val clkPhaseSel = Output(UInt(ClockingTile.phaseSelWidth.W))
      val clkEn = Output(Bool())
      val clkGateEdgeSel = Output(Bool())
      val rxClkEn = Output(Bool())
      val phyTestClkEn = Output(Bool())
      val sbClkEn = Output(Bool())
      // Divider resets, one per direction. See `txDividerRst`.
      val txDividerRstb = Output(AsyncReset())
      val rxDividerRstb = Output(AsyncReset())
      // The TX clock generator's two dividers alone. See `cfgTxClkGenRst`.
      val txClkGenRstb = Output(AsyncReset())
      // The rate link training has negotiated, from the UCIe controller. It is
      // produced on the TX sideband clock, which this block does not run on.
      val freqSel = Input(UInt(4.W))
      // Per-PLL lock out of the clocking tile: PLL8 in bit 0, PLL12 in bit 1.
      val pllLock = Input(UInt(2.W))
      // The UCIe reset: holds the rest of the UCIe block in reset. See
      // `ucieRstReq`.
      val ucieRst = Output(Bool())
      // Clock status for the UCIe controller, on the chip clock: clocks up,
      // the rate that status is for, and clocks ungated. See `clksReady`.
      val clksReady = Output(Bool())
      val clksReadyFreqSel = Output(UInt(4.W))
      val clksUngated = Output(Bool())
      // The clocks the meters below count, in `UcieClkRegs.meteredClocks`
      // order.
      val meterClocks =
        Input(Vec(UcieClkRegs.meteredClocks.length, Clock()))
    })

    // Reset values describe a part that has been told nothing:
    // - PHY test and TX sideband clocks on their bypass pins and running.
    // - UCIe main clock on the analog bypass pin.
    // - No PLL or X2 multiplier running, no division, no phase.
    // - Main clock and recovered RX forwarded clock both off.
    // A testbench can run this from its own clocks with two register writes,
    // `clkEn` and `rxClkEn`. Every test here starts that way. The PHY test
    // divider is preset to /8, 1 GHz off PLL8.
    //
    // The clocks reset off, and software releases the UCIe reset before
    // turning either on. So every TX and RX divider leaves reset with no
    // clock present, and they start in a fixed phase with each other without
    // a restart.
    val (
      mainClkSel,
      pll8En,
      pll12En,
      x2En,
      txClkDiv,
      txClkPhase,
      phyTestClkDiv,
      phyTestClkBypassEn,
      clkPhaseSel,
      clkEn,
      rxClkEn
    ) =
      (
        RegInit(3.U(ClockingTile.mainClkSelWidth.W)),
        RegInit(false.B),
        RegInit(false.B),
        RegInit(false.B),
        RegInit(0.U(ClockingTile.txClkDivWidth.W)),
        RegInit(0.U(ClockingTile.txClkPhaseWidth.W)),
        RegInit(4.U(ClockingTile.phyTestClkDivWidth.W)),
        RegInit(true.B),
        RegInit(UcieClkRegs.maxPhaseSel.U(ClockingTile.phaseSelWidth.W)),
        RegInit(false.B),
        RegInit(false.B)
      )

    // Translation table: what to program for each rate the controller can
    // negotiate. Software fills it during bringup. The PHY's clocking is not
    // fixed: a part may use its PLLs or a bypass pin, and which PLL serves
    // which rate depends on the board and bringup, not the RTL.
    val rateCfg = RegInit(
      VecInit(
        Seq.fill(UcieClkRegs.rateCfgs)(0.U.asTypeOf(new ClkRateCfgIO))
      )
    )
    // Low: the direct registers control the clocking, as a testbench or
    // bringup script uses by hand. High: the table controls it, indexed by the
    // rate training settled on. The entry is applied automatically whenever
    // training moves to a new rate; see `autoApply`.
    val freqSelAutoEn = RegInit(false.B)

    // `freqSel` crosses from the TX sideband clock through two flops per bit.
    // It is a held value that changes once per rate negotiation, not a pulse.
    // The only risk is a cycle of skew between bits while it settles, and the
    // table is read only after the controller stops changing it.
    val freqSelSync = {
      val stage0 = RegInit(0.U(4.W))
      val stage1 = RegInit(0.U(4.W))
      stage0 := io.freqSel
      stage1 := stage0
      stage1
    }
    val selected = rateCfg(freqSelSync(2, 0))

    // Lock comes from the tile's own timing, not any clock here, so it gets
    // the same two flops. It is a level that settles once per wake-up, not a
    // pulse, so the delay loses nothing.
    val pllLockSync = {
      val stage0 = RegInit(0.U(2.W))
      val stage1 = RegInit(0.U(2.W))
      stage0 := io.pllLock
      stage1 := stage0
      stage1
    }

    // CLOCK METERS
    //
    // Cycles each PHY clock ran in the last `UcieClkRegs.meterWindowCycles`
    // chip clock cycles. This block always answers, so software can check
    // here whether a clock runs and at what rate. A nonzero `clkMeterPhyTest`
    // means a PhyTest register access will not hang. `clkMeterRxDiv` shows
    // whether the partner's forwarded clock is arriving.
    //
    // Each clock runs its own counter, and its Gray code crosses into this
    // domain, so every sample is a value the counter held. The counters reset
    // only on the chip reset, so they keep counting through the UCIe and
    // datapath resets. The chip reset reaches each counter through a
    // synchronizer on that counter's clock, like every UCIe domain's reset.
    // It asserts at once (so a stopped counter still clears) and releases on
    // the counter's clock (so all bits leave reset on the same edge).
    def grayToBinary(g: UInt): UInt =
      VecInit((0 until g.getWidth).map(i => g(g.getWidth - 1, i).xorR)).asUInt
    val meterWindow = RegInit(0.U(log2Ceil(UcieClkRegs.meterWindowCycles).W))
    meterWindow := meterWindow + 1.U
    val clkMeters =
      io.meterClocks.zip(UcieClkRegs.meteredClocks).map { case (clk, name) =>
        val counterRstSync = Module(new RstSync()(params.includeDefaultModels))
        counterRstSync.suggestName(s"meterRstSync$name")
        counterRstSync.io.clk := clk
        counterRstSync.io.rstbAsync := !reset.asBool
        val counterRst = (!counterRstSync.io.rstbSync).asAsyncReset
        val gray = withClockAndReset(clk, counterRst) {
          val count = RegInit(0.U(UcieClkRegs.meterWidth.W))
          count := count + 1.U
          val gray = RegInit(0.U(UcieClkRegs.meterWidth.W))
          gray := count ^ (count >> 1)
          gray
        }
        val now = grayToBinary(
          freechips.rocketchip.util.SynchronizerShiftReg(gray, 2)
        )
        val last = RegInit(0.U(UcieClkRegs.meterWidth.W))
        val rate = RegInit(0.U(UcieClkRegs.meterWidth.W))
        when(meterWindow === 0.U) {
          rate := now - last
          last := now
        }
        rate
      }

    // Sticky per PLL, PLL8 in bit 0: the PLL lost lock while enabled, after
    // having locked since it was last enabled. Written to zero to clear.
    val pllLockLost = RegInit(VecInit(Seq.fill(2)(false.B)))
    val pllLockedSinceEn = RegInit(VecInit(Seq.fill(2)(false.B)))
    for ((en, i) <- Seq(io.pll8En, io.pll12En).zipWithIndex) {
      when(!en) {
        pllLockedSinceEn(i) := false.B
      }.elsewhen(pllLockSync(i)) {
        pllLockedSinceEn(i) := true.B
      }
      when(en && pllLockedSinceEn(i) && !pllLockSync(i)) {
        pllLockLost(i) := true.B
      }
    }
    // Two more selects, held like the rest.
    val (sbClkDiv, sbClkBypassEn, rxClkFromTxQ) =
      (
        RegInit(2.U(ClockingTile.sbClkDivWidth.W)),
        RegInit(true.B),
        RegInit(false.B)
      )
    // What the PHY test and TX sideband dividers each divide (PLL8, PLL12 or
    // the analog bypass pin), held like the rest. Both reset to PLL8, which
    // has no effect until a clock leaves its bypass pin.
    val (phyTestClkSrcSel, sbClkSrcSel) =
      (
        RegInit(0.U(ClockingTile.phyTestClkSrcSelWidth.W)),
        RegInit(0.U(ClockingTile.sbClkSrcSelWidth.W))
      )

    // Which tap of the TX clock gate's data clock chain the TX quadrature
    // clock's flops use; see the tile's `ClkGateEdgeSel`. The right tap
    // depends on the lag from the delay code and coarse phase, so every apply
    // (phase-only included) moves it with them, but writing it starts none.
    // Written before the delay code or phase, it moves in the same apply, so
    // the gate never shuts or opens on one tap at a lag meant for the other.
    // Resets low: the tap that changes as the data clock rises.
    val clkGateEdgeSel = RegInit(false.B)

    // Each PLL's digital loop settings and RDAC code, and PLL8's coarse
    // frequency code. Applied, like the PLL enables, so settings and enable
    // can move together. They reset to placeholders until the PLL designer
    // gives nominal values. A change under a running PLL can unlock it while
    // the clocks it feeds keep running, so change them with the PLL off.
    val pll8Cfg = RegInit(ClockingTile.pllCfgReset(ClockingTile.pll8Settings))
    val pll12Cfg =
      RegInit(ClockingTile.pllCfgReset(ClockingTile.pll12Settings))
    val pll8Dcoarse = RegInit(
      ClockingTile.pll8Settings.dcoarse.U(ClockingTile.pllDcoarseWidth.W)
    )

    // Software enables for the PHY test and TX sideband clock gates, after
    // each bypass mux. On at reset: the register blocks behind the UCIe reset
    // run on these clocks, which must run for that reset to release.
    //
    // `gatePhyTestOnApply`/`gateSbOnApply`: when on, an apply shuts that
    // clock's gate during the switch only if it changes what the clock
    // depends on (bypass select, or PLL select or ratio while on the
    // divider). A table rate change changes neither clock, so it does not
    // stop the UCIe controller. When off, the apply leaves the gate to
    // software.
    val (phyTestClkEn, sbClkEn, gatePhyTestOnApply, gateSbOnApply) =
      (RegInit(true.B), RegInit(true.B), RegInit(true.B), RegInit(true.B))

    // Manual mode, for sequencing the clocking by hand (e.g. after tapeout, or
    // to work around an FSM bug).
    //
    // Low (default): FSM mode, as described here. The apply sequencer moves
    // the applied fields under a gate, `freqSelAutoEn` hands control to the
    // rate table, and the sequencer tells the controller when its clocks are
    // ready.
    //
    // High: every output follows its own register only and takes effect when
    // written (applied fields, PLL enables, three gate enables). The table and
    // sequencer are idle, and the `manualClks*` registers drive the
    // controller's clock handshake. Clean sequencing is up to software. The
    // divider resets and the UCIe reset behave the same in both modes.
    //
    // Switching mode moves no output. On entry, each register loads its
    // output's current value (e.g. applied fields from their live copies),
    // which software then reads back. In manual mode the live copies follow
    // the registers. On exit the FSM continues from there, and with
    // `freqSelAutoEn` set the rate table applies.
    //
    // `manual` lags `manualReq` by a cycle. The registers load in that cycle,
    // so the outputs switch to registers already holding their values.
    val manualReq = RegInit(false.B)
    val manual = RegNext(manualReq, false.B)
    val enteringManual = manualReq && !manual
    val (manualClksReady, manualClksReadyFreqSel, manualClksUngated) =
      (RegInit(false.B), RegInit(0.U(4.W)), RegInit(false.B))

    // The copy the tile runs on. The registers above hold what software
    // wrote. Nothing reaches the tile until an apply moves it here, which
    // happens only while the TX clock is gated.
    //
    // Resets to match the shadow registers: analog bypass pin, no PLL. Zero
    // would select PLL8 with that PLL off, a dead UCIe main clock that the
    // PHY test clock domain (on its own bypass pin) would not notice.
    val live = RegInit(
      (new ClkRateCfgIO).Lit(
        _.mainClkSel -> 3.U,
        _.txClkDiv -> 0.U,
        _.pll8En -> false.B,
        _.pll12En -> false.B,
        _.x2En -> false.B
      )
    )
    // The PHY test and TX sideband divisions are not in the rate table, so they
    // have live copies of their own, reset to what the shadow registers say.
    val livePhyTestDiv = RegInit(4.U(ClockingTile.phyTestClkDivWidth.W))
    val liveSbDiv = RegInit(2.U(ClockingTile.sbClkDivWidth.W))
    val liveSbBypass = RegInit(true.B)
    val livePhyTestBypass = RegInit(true.B)
    val liveSbSrc = RegInit(0.U(ClockingTile.sbClkSrcSelWidth.W))
    val livePhyTestSrc = RegInit(0.U(ClockingTile.phyTestClkSrcSelWidth.W))
    // So do the TX quadrature clock's coarse phase and the global delay line,
    // which are training results rather than properties of a rate, and the TX
    // gate's edge select that goes with them.
    val liveTxClkPhase = RegInit(0.U(ClockingTile.txClkPhaseWidth.W))
    val liveClkPhaseSel =
      RegInit(UcieClkRegs.maxPhaseSel.U(ClockingTile.phaseSelWidth.W))
    val liveClkGateEdgeSel = RegInit(false.B)
    // And each PLL's loop settings, applied with its enable.
    val livePll8Cfg =
      RegInit(ClockingTile.pllCfgReset(ClockingTile.pll8Settings))
    val livePll12Cfg =
      RegInit(ClockingTile.pllCfgReset(ClockingTile.pll12Settings))
    val livePll8Dcoarse = RegInit(
      ClockingTile.pll8Settings.dcoarse.U(ClockingTile.pllDcoarseWidth.W)
    )

    // The table entry an apply copies: the one for `freqSel` as the apply
    // started, when it had settled. `freqSel` read at the copy, 32 cycles
    // later, could be mid-change.
    val applyRate = RegInit(0.U(3.W))
    val applyEntry = rateCfg(applyRate)
    val pending = Wire(new ClkRateCfgIO)
    pending.mainClkSel := Mux(freqSelAutoEn, applyEntry.mainClkSel, mainClkSel)
    pending.txClkDiv := Mux(freqSelAutoEn, applyEntry.txClkDiv, txClkDiv)
    pending.pll8En := Mux(freqSelAutoEn, applyEntry.pll8En, pll8En)
    pending.pll12En := Mux(freqSelAutoEn, applyEntry.pll12En, pll12En)
    pending.x2En := Mux(freqSelAutoEn, applyEntry.x2En, x2En)

    // Which lock the gate waits on. The tile reports each PLL's lock raw, so
    // the selection is decoded here, with every selector listed. Then a macro
    // that leaves an unselected PLL's pin undriven (or has none on the bypass
    // path) cannot feed a stale value or an X into the wait. Selector 2 (PLL8
    // X2 clock) has no lock and is ready once PLL8 is. Selector 3 (analog
    // bypass pin) has no lock and counts as ready. Ready is also the default,
    // so a selector added to the tile later fails safe instead of indexing
    // past the lock vector.
    val mainClkLocked = MuxLookup(live.mainClkSel, true.B)(
      Seq(
        0.U -> pllLockSync(0),
        1.U -> pllLockSync(1),
        2.U -> pllLockSync(0)
      )
    )

    // What ends the gate once the new configuration is in, and the delay the
    // `delay` source counts out.
    val ungateSrc = RegInit(ClkUngateSrc.pllLock)
    val ungateDelay = RegInit(
      UcieClkRegs.defaultUngateDelay.U(UcieClkRegs.ungateDelayWidth.W)
    )
    // Chip clock cycles the gates stay shut before the configuration moves,
    // and again after they open.
    val gateDwell = RegInit(
      UcieClkRegs.defaultGateDwell.U(UcieClkRegs.ungateDelayWidth.W)
    )
    // Write to release the gate by hand. Works for any source, so software
    // can recover from a condition that never arrives (a PLL that will not
    // lock, a delay set too long) without a hidden timeout in the sequencer.
    // The sequencer clears it once acted on.
    val ungateReq = RegInit(false.B)

    // The UCIe reset. Resets the UCIe block (PHY, PhyTest, the controller, the
    // queue ends and `UcieTLRegs`) but not this register file, which runs on
    // the chip clock and chip reset. Configure the clocking here, then
    // restart everything else on it. `UcieTLRegs` is reset too, so set that
    // block up after this.
    //
    // It is the only reset the UCIe clock domains take; the chip reset stops
    // here. It resets asserted, so the block stays in reset until software
    // sets up the clocking and releases it. Until then the PhyTest and
    // controller register blocks do not answer.
    //
    // Bringup only. An access in flight to `UcieTLRegs`, or mainband traffic,
    // is in this reset's domain and behind the same crossbar, and a response
    // lost to the reset hangs it. `setup_ucie` asserts this first, with both
    // quiet.
    //
    // Set asynchronously by the chip reset, since the UCIe clocks may run
    // before the chip clock and would otherwise start in their power-up state.
    val ucieRstReq = RegInit(true.B)
    io.ucieRst := ucieRstReq

    // Resets one direction's clock dividers: the global one in the PHY and
    // one in each tile, including PhyTest's lanes. A level, not a strobe:
    // assert and release it while that direction's clock is stopped (`clkEn`
    // low for TX, `rxClkEn` low for RX), then turn the clock back on. Every
    // divider then restarts on a common edge.
    //
    // These reach the dividers as asynchronous resets, so release is clean
    // only with the divided clocks stopped. They live here on the chip clock,
    // not with the lane controls, so they stay writable while the UCIe main
    // clock and every PHY clock divided from it are stopped. `ucieRst` holds
    // them too, so restarting the block restarts its dividers. Each comes
    // straight off a flop, so the async reset never sees a glitch from an OR
    // of changing inputs.
    val txDividerRst = RegInit(false.B)
    val rxDividerRst = RegInit(false.B)
    io.txDividerRstb :=
      RegNext(!(ucieRstReq || txDividerRst), false.B).asAsyncReset
    io.rxDividerRstb :=
      RegNext(!(ucieRstReq || rxDividerRst), false.B).asAsyncReset

    // Applying a configuration: gate, switch under the gate, ungate. A mux,
    // divider or delay code changed under a running clock glitches the
    // domains behind it. The TX clock gate follows the global delay line and
    // every TX mux and divider, so it covers the TX data and TX quadrature
    // clocks; every apply shuts it. The PHY test and TX sideband clocks have
    // their own gates after their PLL dividers, shut only when an apply
    // changes something under them; see `gatePhyTestOnApply`.
    //
    // With `phaseAutoApply` set, in FSM mode, writing the TX quadrature coarse
    // phase or the global delay line with a value the tile does not run
    // starts an apply by itself. It shuts only the TX gate, moves only those
    // two and `clkGateEdgeSel`, and skips the ungate wait, since no PLL
    // changed. In manual mode they drive their pins directly, like every
    // other field.
    val applyReq = RegInit(false.B)
    val applyBusy = RegInit(false.B)
    // Whether the apply under way is a whole one, as opposed to a phase alone.
    val applyFull = RegInit(false.B)
    val applyCount = RegInit(0.U(UcieClkRegs.ungateDelayWidth.W))
    // `>=` rather than `===`, so that a dwell shortened mid-wait still ends
    // it. A dwell of zero acts as one.
    val gateDwellDone = applyCount +& 1.U >= gateDwell
    val cfgGate = RegInit(false.B)
    val cfgGatePhyTest = RegInit(false.B)
    val cfgGateSb = RegInit(false.B)

    // Holds the TX data and quadrature dividers (which make the TX clocks from
    // the main clock) in reset during the switch. They are ahead of the TX
    // gate and run whenever the main clock does, so a new source or coarse
    // phase can give them a runt edge and leave them a cycle apart. (The
    // phase depends partly on which main clock edge the quadrature divider
    // counts.) Released before the gate opens, they restart together at the
    // requested phase. The lane dividers, past the gate, are not reset and
    // keep their count across an apply.
    //
    // Also asserted by `txDividerRstb`'s sources (software's divider reset
    // and the UCIe reset), so these two restart with the rest.
    val cfgTxClkGenRst = RegInit(false.B)
    io.txClkGenRstb := RegNext(
      !(ucieRstReq || txDividerRst || cfgTxClkGenRst),
      false.B
    ).asAsyncReset

    // High: writing the coarse phase or the delay code starts an apply by
    // itself (see above). Low (reset): they are applied fields like the rest
    // and wait for `clkCfgApply`.
    val phaseAutoApply = RegInit(false.B)

    // A coarse phase or delay code written that the tile does not run yet.
    val phaseChanges = txClkPhase =/= liveTxClkPhase ||
      clkPhaseSel =/= liveClkPhaseSel

    // What an apply changes under the PHY test and TX sideband clocks: the
    // bypass select, or the PLL select or ratio while the clock stays on its
    // divider. On the bypass pin, those two are behind the mux and do nothing.
    val phyTestChanges = phyTestClkBypassEn =/= livePhyTestBypass ||
      (!phyTestClkBypassEn && (phyTestClkSrcSel =/= livePhyTestSrc ||
        phyTestClkDiv =/= livePhyTestDiv))
    val sbChanges = sbClkBypassEn =/= liveSbBypass ||
      (!sbClkBypassEn && (sbClkSrcSel =/= liveSbSrc || sbClkDiv =/= liveSbDiv))
    // Whether an apply may move those fields now: their gate is shut, software
    // runs that gate, or they change nothing.
    val phyTestSafe = cfgGatePhyTest || !gatePhyTestOnApply || !phyTestChanges
    val sbSafe = cfgGateSb || !gateSbOnApply || !sbChanges

    // A PHY test or TX sideband clock reopened onto its divider must also
    // wait for the PLL it divides, which this apply may have just turned on.
    // The analog bypass pin has no lock and counts as ready (as for the UCIe
    // main clock), and so does the spare code.
    def srcLocked(src: UInt): Bool = MuxLookup(src, true.B)(
      Seq(0.U -> pllLockSync(0), 1.U -> pllLockSync(1))
    )
    val phyTestLocked = !cfgGatePhyTest || livePhyTestBypass ||
      srcLocked(livePhyTestSrc)
    val sbLocked = !cfgGateSb || liveSbBypass || srcLocked(liveSbSrc)
    val applyPhase = RegInit(ClkApplyPhase.idle)

    // `>=` rather than `===` so that a delay shortened mid-wait still ends it,
    // and a delay of zero releases the gate the cycle it is reached.
    val ungateOk = ungateReq || MuxLookup(ungateSrc, false.B)(
      Seq(
        ClkUngateSrc.pllLock -> (mainClkLocked && phyTestLocked && sbLocked),
        ClkUngateSrc.delay -> (applyCount >= ungateDelay)
      )
    )

    // Whether the tile is running the table's entry for the rate training has
    // settled on.
    val liveIsSelected = live.asUInt === selected.asUInt

    // With the table in charge, the sequencer applies a new rate without
    // software. It starts whenever the live configuration differs from the
    // table's entry for the current rate (after a training rate change,
    // turning `freqSelAutoEn` on, or rewriting the entry in use). `freqSel`
    // crosses as a multi-bit level, so it must first hold steady for a few
    // cycles, or a bit arriving a cycle late could apply the wrong entry.
    val freqSelSettled = {
      val last = RegNext(freqSelSync, 0.U)
      val held = RegInit(0.U(log2Ceil(UcieClkRegs.freqSelSettleCycles + 1).W))
      when(freqSelSync =/= last) {
        held := 0.U
      }.elsewhen(held =/= UcieClkRegs.freqSelSettleCycles.U) {
        held := held + 1.U
      }
      held === UcieClkRegs.freqSelSettleCycles.U
    }
    val autoApply = freqSelAutoEn && freqSelSettled && !liveIsSelected

    // Manual mode, from the request on: the sequencer is idle with its gates
    // open and nothing pending, and an apply written meanwhile is dropped.
    when(manualReq || manual) {
      applyPhase := ClkApplyPhase.idle
      applyBusy := false.B
      applyFull := false.B
      applyReq := false.B
      ungateReq := false.B
      cfgGate := false.B
      cfgGatePhyTest := false.B
      cfgGateSb := false.B
      cfgTxClkGenRst := false.B
      applyCount := 0.U
    }.otherwise {
      switch(applyPhase) {
        is(ClkApplyPhase.idle) {
          // With the table in charge, a requested apply also waits for
          // `freqSel` to settle, so the entry it latches is a real one.
          when(
            (applyReq && (!freqSelAutoEn || freqSelSettled)) || autoApply
          ) {
            applyBusy := true.B
            applyFull := true.B
            applyRate := freqSelSync(2, 0)
            cfgGate := true.B
            cfgGatePhyTest := gatePhyTestOnApply && phyTestChanges
            cfgGateSb := gateSbOnApply && sbChanges
            // A release written before this apply must not end its wait.
            ungateReq := false.B
            applyCount := 0.U
            applyPhase := ClkApplyPhase.gateIn
          }.elsewhen(phaseAutoApply && phaseChanges) {
            applyBusy := true.B
            applyFull := false.B
            cfgGate := true.B
            applyCount := 0.U
            applyPhase := ClkApplyPhase.gateIn
          }
        }
        is(ClkApplyPhase.gateIn) {
          // Long enough for the gate to have taken on the slowest clock the
          // tile can produce before anything moves.
          applyCount := applyCount + 1.U
          when(gateDwellDone) {
            liveTxClkPhase := txClkPhase
            liveClkPhaseSel := clkPhaseSel
            liveClkGateEdgeSel := clkGateEdgeSel
            when(applyFull) {
              // Taken now, so a request written from here on runs again
              // after this apply, with the shadows as they are then. So does
              // a PHY test or sideband field written after this apply chose
              // which gates to shut: copied now, it would switch that clock
              // with its gate open.
              applyReq := !(phyTestSafe && sbSafe)
              live := pending
              livePll8Cfg := pll8Cfg
              livePll12Cfg := pll12Cfg
              livePll8Dcoarse := pll8Dcoarse
              when(phyTestSafe) {
                livePhyTestDiv := phyTestClkDiv
                livePhyTestBypass := phyTestClkBypassEn
                livePhyTestSrc := phyTestClkSrcSel
              }
              when(sbSafe) {
                liveSbDiv := sbClkDiv
                liveSbBypass := sbClkBypassEn
                liveSbSrc := sbClkSrcSel
              }
            }
            // Whenever this apply can have upset the TX dividers: a new
            // source or ratio, or a new coarse phase. A delay code alone is
            // past them.
            cfgTxClkGenRst := applyFull || txClkPhase =/= liveTxClkPhase
            applyCount := 0.U
            applyPhase := ClkApplyPhase.ungateWait
          }
        }
        is(ClkApplyPhase.ungateWait) {
          // A phase alone has no new source to wait out.
          applyCount := applyCount + 1.U
          when(!applyFull || ungateOk) {
            when(applyFull) {
              ungateReq := false.B
            }
            cfgTxClkGenRst := false.B
            applyCount := 0.U
            applyPhase := ClkApplyPhase.rstOut
          }
        }
        is(ClkApplyPhase.rstOut) {
          // The TX dividers out of reset and running before the gate opens
          // on them.
          applyCount := applyCount + 1.U
          when(applyCount === (UcieClkRegs.clkGenRstDwell - 1).U) {
            cfgGate := false.B
            cfgGatePhyTest := false.B
            cfgGateSb := false.B
            applyCount := 0.U
            applyPhase := ClkApplyPhase.gateOut
          }
        }
        is(ClkApplyPhase.gateOut) {
          // The same dwell on the way out, so the clocks are back before
          // software is told the apply is done.
          applyCount := applyCount + 1.U
          when(gateDwellDone) {
            applyBusy := false.B
            applyPhase := ClkApplyPhase.idle
          }
        }
      }
    }

    // What `clkCfgBusy` reads: an apply under way, or a request or phase
    // written that one is about to carry, so a read straight after the write
    // already sees it.
    val busyObserved = applyBusy ||
      (!manual && (applyReq || (phaseAutoApply && phaseChanges)))

    // Stands in for a PHY PLL lock to the UCIe controller. An apply sets it
    // when it ungates, by the `clkCfgUngateSrc` condition, and it holds from
    // then on: no apply in progress and, with the PLL source, that PLL still
    // locked. The delay and software sources are for an untrusted or unwired
    // lock, so for those a finished apply is enough.
    //
    // With the rate table in charge, it also waits until the live
    // configuration is the table's entry for the rate training asked for
    // (`autoApply`), so a rate change holds training until its apply ends.
    // The rate goes out with it, so the controller can tell this reply from
    // one about the previous rate.
    //
    // A phase-only apply leaves it set: rate and lock do not change, and the
    // TX gate shuts briefly, as when software drops `clkEn`. Only the ungated
    // flag drops.
    val clksStable = !(applyBusy && applyFull) &&
      (ungateSrc =/= ClkUngateSrc.pllLock || mainClkLocked)
    val rateLive = !freqSelAutoEn || liveIsSelected
    val (fsmClksReady, fsmClksReadyFreqSel, fsmClksUngated) =
      (
        RegNext(clksStable && rateLive, false.B),
        RegNext(freqSelSync, 0.U),
        // Ungated is the same, less the rate, with no apply of any kind under
        // way and with software's `clkEn` on.
        RegNext(clksStable && !applyBusy && clkEn, false.B)
      )

    // Each output's FSM-mode value. The gates are software's enable, shut
    // while an apply holds them. The PLL enables are exactly what was
    // applied, even for a PLL the PHY test or TX sideband divider uses:
    // turning that PLL off stops that clock with its gate open.
    val fsmClkEn = clkEn && !cfgGate
    val fsmPhyTestClkEn = phyTestClkEn && !cfgGatePhyTest
    val fsmSbClkEn = sbClkEn && !cfgGateSb

    // Into manual mode: every register takes what its output is, so the
    // outputs do not move when they switch over to the registers.
    when(enteringManual) {
      mainClkSel := live.mainClkSel
      txClkDiv := live.txClkDiv
      txClkPhase := liveTxClkPhase
      clkPhaseSel := liveClkPhaseSel
      clkGateEdgeSel := liveClkGateEdgeSel
      x2En := live.x2En
      pll8En := live.pll8En
      pll12En := live.pll12En
      pll8Cfg := livePll8Cfg
      pll12Cfg := livePll12Cfg
      pll8Dcoarse := livePll8Dcoarse
      phyTestClkDiv := livePhyTestDiv
      phyTestClkSrcSel := livePhyTestSrc
      phyTestClkBypassEn := livePhyTestBypass
      sbClkDiv := liveSbDiv
      sbClkSrcSel := liveSbSrc
      sbClkBypassEn := liveSbBypass
      clkEn := fsmClkEn
      phyTestClkEn := fsmPhyTestClkEn
      sbClkEn := fsmSbClkEn
      manualClksReady := fsmClksReady
      manualClksReadyFreqSel := fsmClksReadyFreqSel
      manualClksUngated := fsmClksUngated
    }
    // In manual mode: the live copies follow the registers, so the FSM picks
    // up from them when it is handed back the outputs.
    when(manual) {
      live.mainClkSel := mainClkSel
      live.txClkDiv := txClkDiv
      liveTxClkPhase := txClkPhase
      liveClkPhaseSel := clkPhaseSel
      liveClkGateEdgeSel := clkGateEdgeSel
      live.x2En := x2En
      live.pll8En := pll8En
      live.pll12En := pll12En
      livePll8Cfg := pll8Cfg
      livePll12Cfg := pll12Cfg
      livePll8Dcoarse := pll8Dcoarse
      livePhyTestDiv := phyTestClkDiv
      livePhyTestSrc := phyTestClkSrcSel
      livePhyTestBypass := phyTestClkBypassEn
      liveSbDiv := sbClkDiv
      liveSbSrc := sbClkSrcSel
      liveSbBypass := sbClkBypassEn
    }

    // Every output that picks between the registers and the FSM goes out
    // through a flop, so a mode switch or an apply can't glitch a PLL enable,
    // a clock mux select or a gate enable. Each resets to its source's reset
    // value, asynchronously, like the rest.
    def flop[T <: Data](x: T, init: T): T = RegNext(x, init)
    io.mainClkSel := flop(Mux(manual, mainClkSel, live.mainClkSel), 3.U)
    io.pll8En := flop(Mux(manual, pll8En, live.pll8En), false.B)
    io.pll12En := flop(Mux(manual, pll12En, live.pll12En), false.B)
    io.x2En := flop(Mux(manual, x2En, live.x2En), false.B)
    io.pll8Cfg := flop(
      Mux(manual, pll8Cfg, livePll8Cfg),
      ClockingTile.pllCfgReset(ClockingTile.pll8Settings)
    )
    io.pll12Cfg := flop(
      Mux(manual, pll12Cfg, livePll12Cfg),
      ClockingTile.pllCfgReset(ClockingTile.pll12Settings)
    )
    io.pll8Dcoarse := flop(
      Mux(manual, pll8Dcoarse, livePll8Dcoarse),
      ClockingTile.pll8Settings.dcoarse.U(ClockingTile.pllDcoarseWidth.W)
    )
    io.txClkDiv := flop(Mux(manual, txClkDiv, live.txClkDiv), 0.U)
    io.txClkPhase := flop(Mux(manual, txClkPhase, liveTxClkPhase), 0.U)
    io.phyTestClkDiv :=
      flop(Mux(manual, phyTestClkDiv, livePhyTestDiv), 4.U)
    io.phyTestClkSrcSel :=
      flop(Mux(manual, phyTestClkSrcSel, livePhyTestSrc), 0.U)
    io.phyTestClkBypassEn :=
      flop(Mux(manual, phyTestClkBypassEn, livePhyTestBypass), true.B)
    io.sbClkDiv := flop(Mux(manual, sbClkDiv, liveSbDiv), 2.U)
    io.sbClkSrcSel := flop(Mux(manual, sbClkSrcSel, liveSbSrc), 0.U)
    io.sbClkBypassEn := flop(Mux(manual, sbClkBypassEn, liveSbBypass), true.B)
    io.rxClkFromTxQ := rxClkFromTxQ
    io.clkPhaseSel := flop(
      Mux(manual, clkPhaseSel, liveClkPhaseSel),
      UcieClkRegs.maxPhaseSel.U(ClockingTile.phaseSelWidth.W)
    )
    io.clkEn := flop(Mux(manual, clkEn, fsmClkEn), false.B)
    io.clkGateEdgeSel :=
      flop(Mux(manual, clkGateEdgeSel, liveClkGateEdgeSel), false.B)
    io.rxClkEn := rxClkEn
    io.phyTestClkEn := flop(Mux(manual, phyTestClkEn, fsmPhyTestClkEn), true.B)
    io.sbClkEn := flop(Mux(manual, sbClkEn, fsmSbClkEn), true.B)
    io.clksReady := flop(Mux(manual, manualClksReady, fsmClksReady), false.B)
    io.clksReadyFreqSel := flop(
      Mux(manual, manualClksReadyFreqSel, fsmClksReadyFreqSel),
      0.U
    )
    io.clksUngated := flop(
      Mux(manual, manualClksUngated, fsmClksUngated),
      false.B
    )

    // Each PLL's loop state, through two flops per bit. The codes move while
    // the PLL runs, so a read can mix two consecutive values.
    def sync2(x: UInt): UInt = {
      val (stage0, stage1) =
        (RegInit(0.U(x.getWidth.W)), RegInit(0.U(x.getWidth.W)))
      stage0 := x
      stage1 := stage0
      stage1
    }
    val (pll8CodeFull, pll8CodeInt, pll12CodeFull, pll12CodeInt) = (
      sync2(io.pll8CodeFull),
      sync2(io.pll8CodeInt),
      sync2(io.pll12CodeFull),
      sync2(io.pll12CodeInt)
    )

    val regmap: Seq[(Int, Seq[RegField])] = {
      val scalars = Seq(
        toRegFieldRw(mainClkSel, "mainClkSel"),
        toRegFieldRw(pll8En, "pll8En"),
        toRegFieldRw(pll12En, "pll12En"),
        toRegFieldRw(x2En, "x2En"),
        toRegFieldRw(txClkDiv, "txClkDiv"),
        toRegFieldRw(txClkPhase, "txClkPhase"),
        toRegFieldRw(phyTestClkDiv, "phyTestClkDiv"),
        toRegFieldRw(phyTestClkBypassEn, "phyTestClkBypassEn"),
        toRegFieldRw(clkPhaseSel, "clkPhaseSel"),
        toRegFieldRw(clkEn, "clkEn"),
        toRegFieldRw(rxClkEn, "rxClkEn"),
        toRegFieldRw(freqSelAutoEn, "freqSelAutoEn"),
        toRegFieldR(freqSelSync, "freqSelObserved"),
        toRegFieldRw(sbClkDiv, "sbClkDiv"),
        toRegFieldRw(sbClkBypassEn, "sbClkBypassEn"),
        toRegFieldRw(rxClkFromTxQ, "rxClkFromTxQ"),
        toRegFieldRw(applyReq, "clkCfgApply"),
        toRegFieldR(busyObserved, "clkCfgBusy"),
        toRegFieldRw(ungateSrc, "clkCfgUngateSrc"),
        toRegFieldRw(ungateDelay, "clkCfgUngateDelay"),
        toRegFieldRw(ungateReq, "clkCfgUngateReq"),
        toRegFieldR(pllLockSync, "pllLockObserved"),
        toRegFieldRw(ucieRstReq, "ucieRst"),
        // Later additions, after the rest so that no earlier address moves.
        toRegFieldRw(phyTestClkSrcSel, "phyTestClkSrcSel"),
        toRegFieldRw(sbClkSrcSel, "sbClkSrcSel"),
        toRegFieldRw(txDividerRst, "txDividerRst"),
        toRegFieldRw(rxDividerRst, "rxDividerRst"),
        toRegFieldRw(phyTestClkEn, "phyTestClkEn"),
        toRegFieldRw(sbClkEn, "sbClkEn"),
        toRegFieldRw(gatePhyTestOnApply, "clkCfgGatePhyTest"),
        toRegFieldRw(gateSbOnApply, "clkCfgGateSb"),
        toRegFieldRw(manualReq, "clkManualEn"),
        toRegFieldRw(manualClksReady, "manualClksReady"),
        toRegFieldRw(manualClksReadyFreqSel, "manualClksReadyFreqSel"),
        toRegFieldRw(manualClksUngated, "manualClksUngated")
      ) ++ UcieClkRegs.meteredClocks.zip(clkMeters).map { case (name, rate) =>
        toRegFieldR(rate, s"clkMeter$name")
      } ++ Seq(
        toRegFieldRw(pllLockLost, "pllLockLost"),
        toRegFieldRw(clkGateEdgeSel, "clkGateEdgeSel"),
        toRegFieldRw(phaseAutoApply, "clkPhaseAutoApply")
      ) ++ Seq((pll8Cfg, "pll8"), (pll12Cfg, "pll12")).flatMap {
        case (cfg, pll) =>
          Seq(
            toRegFieldRw(cfg.autoEn, s"${pll}AutoEn"),
            toRegFieldRw(cfg.kp, s"${pll}Kp"),
            toRegFieldRw(cfg.ki, s"${pll}Ki"),
            toRegFieldRw(cfg.codeInit, s"${pll}CodeInit"),
            toRegFieldRw(cfg.codeByp, s"${pll}CodeByp"),
            toRegFieldRw(cfg.dbias, s"${pll}Dbias")
          )
      } ++ Seq(
        toRegFieldRw(pll8Dcoarse, "pll8Dcoarse"),
        toRegFieldR(pll8CodeFull, "pll8CodeFull"),
        toRegFieldR(pll8CodeInt, "pll8CodeInt"),
        toRegFieldR(pll12CodeFull, "pll12CodeFull"),
        toRegFieldR(pll12CodeInt, "pll12CodeInt"),
        toRegFieldRw(gateDwell, "clkCfgGateDwell")
      )
      // The table is at a fixed slot so adding a scalar does not move every
      // entry. But one scalar too many then silently overlaps `rateCfg_0`.
      // This has happened before, so fail elaboration instead of producing a
      // map with two registers at one address.
      require(
        scalars.length <= UcieClkRegs.rateCfgBase,
        s"UcieClkRegs has ${scalars.length} scalar registers but the rate " +
          s"table starts at slot ${UcieClkRegs.rateCfgBase}; raise " +
          "`rateCfgBase` (and regenerate collateral) or drop a scalar."
      )
      scalars.zipWithIndex.map { case (f, i) => (i * 8) -> Seq(f) } ++
        (0 until UcieClkRegs.rateCfgs).map { r =>
          ((UcieClkRegs.rateCfgBase + r) * 8) -> Seq(
            toRegFieldRw(rateCfg(r), s"rateCfg_$r")
          )
        }
    }

    node.regmap(regmap: _*)
  }
}

/** Phases of a clock configuration apply: shut the gate, switch under it, wait
  * out the new source's wake-up, let the TX dividers back out of reset, then
  * let the clocks back out.
  */
object ClkApplyPhase extends ChiselEnum {
  val idle, gateIn, ungateWait, rstOut, gateOut = Value
}

/** What ends the clock gate at the end of an apply.
  *
  * The gate must stay shut through a newly enabled PLL's wake-up. That is far
  * longer than the few cycles the gate needs, and it depends on the part, so
  * the release is a choice, not a constant.
  */
object ClkUngateSrc extends ChiselEnum {

  /** Lock from whichever PLL `mainClkSel` names. The analog bypass pin has no
    * lock to report and counts as ready the moment it is selected.
    */
  val pllLock = Value(0.U(2.W))

  /** Nothing automatic: the gate stays shut until software writes
    * `clkCfgUngateReq`. For a bringup that wants to look at the clock before
    * the lanes see it.
    */
  val mmio = Value(1.U(2.W))

  /** `clkCfgUngateDelay` cycles of the chip clock, counted from the switch. For
    * a part whose lock is not trustworthy, or not wired.
    */
  val delay = Value(2.U(2.W))
}

/** One rate's analog clocking configuration.
  *
  * Holds everything that sets the lane rate, and nothing else. The sampling
  * phase is a training result, not a property of the rate, so it has its own
  * register. So do the PHY test and TX sideband clocks, whose rate does not
  * follow the lanes. The PLL enables cover the whole part, so an entry that
  * turns off a PLL one of those clocks divides stops that clock.
  */
class ClkRateCfgIO extends Bundle {
  val mainClkSel = UInt(ClockingTile.mainClkSelWidth.W)
  val txClkDiv = UInt(ClockingTile.txClkDivWidth.W)
  val pll8En = Bool()
  val pll12En = Bool()
  val x2En = Bool()
}

object UcieClkRegs {

  /** The clocks the clock meters count, in register order. */
  val meteredClocks = Seq("PhyTest", "Sb", "TxDiv", "RxDiv")

  /** Chip clock cycles each meter counts over. At a 500 MHz chip clock that is
    * 2.048 us, so a count of N is N / 2.048 MHz.
    */
  val meterWindowCycles = 1024

  /** Width of each meter's count: a clock up to 64x the chip clock. */
  val meterWidth = 16

  /** Rates the translation table holds, one per `SpeedMode` code. */
  val rateCfgs = 8

  /** Reset value of `clkCfgGateDwell`: cycles the gate is held before the
    * configuration moves, and again after ungating. Covers each gate taking a
    * new enable on the slowest clock it can be on. The PHY test and TX sideband
    * gates take theirs through three flops on the falling edge of the gated
    * clock, so they need up to four of its periods: 7.5 ns at /15 of PLL8. 32
    * cycles of a 500 MHz chip clock (64 ns) covers a gated clock down to about
    * 60 MHz. The TX gate needs up to nine TX clock periods, so a TX clock of
    * 140 MHz or more. Raise it for a slower clock or a faster chip clock.
    * Unrelated to PLL wake-up.
    */
  val defaultGateDwell = 32

  /** Cycles between the TX dividers' release and the TX gate opening on them:
    * the release crossing its flop here, then the dividers' own synchronizer on
    * the main clock, with room to spare.
    */
  val clkGenRstDwell = 4

  /** Cycles `freqSel` must hold still, after its synchronizer, before a change
    * starts an apply. Covers bits of one change arriving a cycle apart.
    */
  val freqSelSettleCycles = 3

  /** Width of `clkCfgUngateDelay`, in cycles of the chip clock. */
  val ungateDelayWidth = 16

  /** Reset value of the global delay line: every tap on, the longest code.
    * Training moves it from there.
    */
  val maxPhaseSel = (BigInt(1) << ClockingTile.phaseSelWidth) - 1

  /** Default count for `ClkUngateSrc.delay`. Sized to clear a PLL wake-up with
    * margin at any plausible chip clock.
    */
  val defaultUngateDelay = 1024

  /** Which 8-byte slot the table starts at, clear of the scalar registers.
    * Fixed, not counted, so adding a scalar does not move every table entry.
    */
  val rateCfgBase = 64

  /** Where this block sits above `UcieTLParams.address`, clear of the PhyTest
    * and controller register blocks below it.
    */
  val offset = 0x10000
}

/** PhyTest's registers, and the lane controls and modes around it, on the
  * PhyTest clock.
  */
class UcieTLRegs(
    params: UcieTLParams,
    beatBytes: Int
)(implicit
    p: Parameters
) extends ClockSinkDomain(ClockSinkParameters()) {
  def toRegFieldRw[T <: Data](r: T, name: String): RegField = {
    RegField(
      r.getWidth,
      r.asUInt,
      RegWriteFn((valid, data) => {
        when(valid) {
          r := data.asTypeOf(r)
        }
        true.B
      }),
      Some(RegFieldDesc(name, ""))
    )
  }
  def toRegFieldR[T <: Data](r: T, name: String): RegField = {
    RegField.r(r.getWidth, r.asUInt, RegFieldDesc(name, ""))
  }
  val device = new SimpleDevice("ucie_control", Seq("ucbbar,ucie"))
  val node = TLRegisterNode(
    AddressSet.misaligned(params.address, UcieTLRegs.regionSize),
    device,
    "reg/control",
    beatBytes = beatBytes
  )

  override lazy val module = new UcieTLRegsImpl
  class UcieTLRegsImpl extends Impl {
    val io = IO(
      new UcieTLRegsIO(
        params.bufferDepthPerLane,
        params.numLanes,
        params.bitCounterWidth
      )
    )

    val regmap = withClockAndReset(clock, reset) {
      // MMIO registers.
      val testTarget = RegInit(TestTarget.mainband)
      val txTestMode = RegInit(TxTestMode.manual)
      val txDataMode = RegInit(DataMode.finite)
      // A different seed per lane out of reset, so that a run started
      // without writing any still has every lane on its own stream.
      val txLfsrSeed = RegInit(
        VecInit(
          (0 until PhyTest.numTestLanes(params.numLanes)).map(lane =>
            PhyTest.defaultLfsrSeed(lane).U(io.test.txLfsrSeed(0).getWidth.W)
          )
        )
      )
      val txDatapathRst = Wire(DecoupledIO(UInt(1.W)))
      val txFsmRst = Wire(DecoupledIO(UInt(1.W)))
      val txExecute = Wire(DecoupledIO(UInt(1.W)))
      val txWriteChunk = Wire(DecoupledIO(UInt(1.W)))
      val txManualRepeatPeriod =
        RegInit(0.U(io.test.txManualRepeatPeriod.getWidth.W))
      val txPacketsToSend =
        RegInit(0.U(io.test.txPacketsToSend.getWidth.W))
      // A running forwarded clock out of reset, so that `TxClkGater` and
      // `txClkDirect` clock the link before anything is written.
      val txClkP = RegInit(Codegen.defaultClkP.U(32.W))
      val txClkN = RegInit(Codegen.defaultClkN.U(32.W))
      val txValid = RegInit(0.U(32.W))
      val txDataLaneGroup =
        RegInit(0.U(io.test.txDataLaneGroup.getWidth.W))
      val txDataOffset = RegInit(0.U(io.test.txDataOffset.getWidth.W))
      val txDataChunkIn0 = RegInit(0.U(64.W))
      val txDataChunkIn1 = RegInit(0.U(64.W))
      val rxDataMode = RegInit(DataMode.infinite)
      val rxLfsrSeed = RegInit(
        VecInit(
          (0 until PhyTest.numTestLanes(params.numLanes)).map(lane =>
            PhyTest.defaultLfsrSeed(lane).U(io.test.rxLfsrSeed(0).getWidth.W)
          )
        )
      )
      val rxValid = RegInit(0.U(32.W))
      val rxDatapathRst = Wire(DecoupledIO(UInt(1.W)))
      val rxFsmRst = Wire(DecoupledIO(UInt(1.W)))
      val rxPacketsToReceive =
        RegInit(0.U(io.test.rxPacketsToReceive.getWidth.W))
      val rxPauseCounters = RegInit(0.U(1.W))
      val rxDataLane = RegInit(0.U(io.test.rxDataLane.getWidth.W))
      val rxDataOffset = RegInit(0.U(io.test.rxDataOffset.getWidth.W))

      // Physical lane carrying the valid signal in each direction. Reset to the
      // dedicated valid lane; see `PhyRegsIO` for the other select codes.
      val txValidLaneSel = RegInit(
        Phy
          .defaultValidLaneSel(params.numLanes)
          .U(Phy.validLaneSelWidth(params.numLanes).W)
      )
      val rxValidLaneSel = RegInit(
        Phy
          .defaultValidLaneSel(params.numLanes)
          .U(Phy.validLaneSelWidth(params.numLanes).W)
      )
      val txctl = RegInit(VecInit(Seq.fill(params.numLanes + 5)({
        val w = Wire(new TxLaneDigitalCtlIO)
        // Every driver segment off out of reset, so a lane stays quiet until
        // software brings it up.
        w.tile := TxLaneCtlIO.off
        // The TX tile serializes through an adjacent-pairing tree, so it sends
        // `DataIN[bitrev5(t)]` in UI `t`. Applying the same permutation here
        // cancels it, so the word goes on the wire in plain bit order.
        // Software can still program any other mapping.
        for (i <- 0 until 32) {
          w.shuffler(i) := Phy.treeBitOrder(i).U(5.W)
        }
        w.sample_negedge := false.B
        w
      })))
      val rxctl = RegInit(VecInit(Seq.fill(params.numLanes + 5)({
        val w = Wire(new RxLaneDigitalCtlIO)
        // No added delay out of reset; software trims from here.
        w.Dctrl := 0.U
        w.zen := false.B
        w.zctl := 0.U
        w.vref_sel := 63.U
        w.afeBypassEn := false.B
        w.afeOpCycles := 16.U
        w.afeOverlapCycles := 2.U
        w.afeBypass.aEn := false.B
        w.afeBypass.aPc := true.B
        w.afeBypass.bEn := false.B
        w.afeBypass.bPc := true.B
        w.afeBypass.selA := false.B
        // The RX tile deserializes through the mirror of the TX tile's tree,
        // so `dout(j)` holds the bit received in UI `bitrev5(j)`. Applying the
        // same permutation here cancels it, so the digital side reads the word
        // in wire order. Software can still program any other mapping.
        for (i <- 0 until 32) {
          w.shuffler(i) := Phy.treeBitOrder(i).U(5.W)
        }
        w.sample_negedge := false.B
        w
      })))
      // DEBUG CIRCUITRY
      // Everything below drives the tester's debug hardware rather than the
      // link: the observation bumps and the TX data debug lane.
      //
      // Pad drivers for the observation bumps, `mux0` then `mux1`.
      val debugDriverctl = RegInit(VecInit(Seq.fill(PhyTest.NumDebugDrivers)({
        val w = Wire(new PadDriverCtlIO)
        w.pu_ctl := 0.U
        w.pd_ctl := 0.U
        w.en := false.B
        w.en_b := true.B
        w
      })))
      // What each observation bump watches, `mux0` then `mux1`: independent
      // indices into `PhyTest.DebugMuxInputs`.
      val debugMuxSel = RegInit(
        VecInit(
          Seq.fill(PhyTest.NumDebugDrivers)(
            0.U(io.test.debugMuxSel.head.getWidth.W)
          )
        )
      )
      val debugTxctl = RegInit({
        val w = Wire(new TxLaneDigitalCtlIO)
        // Every driver segment off out of reset, so a lane stays quiet until
        // software brings it up.
        w.tile := TxLaneCtlIO.off
        // Cancels the tile's tree, as on the other TX lanes, so the word goes
        // on the wire in plain bit order.
        for (i <- 0 until 32) {
          w.shuffler(i) := Phy.treeBitOrder(i).U(5.W)
        }
        w.sample_negedge := false.B
        w
      })

      val debugTxTestMode = RegInit(TxTestMode.manual)
      val debugTxDataMode = RegInit(DataMode.finite)
      val debugTxLfsrSeed = RegInit(1.U(64.W))
      val debugTxFsmRst = Wire(DecoupledIO(UInt(1.W)))
      val debugTxExecute = Wire(DecoupledIO(UInt(1.W)))
      debugTxFsmRst.ready := true.B
      debugTxExecute.ready := true.B
      val debugTxManualRepeatPeriod = RegInit(0.U(6.W))
      val debugTxPacketsToSend = RegInit(0.U(params.bitCounterWidth.W))
      val debugData = RegInit(VecInit(Seq.fill(16)(0.U(64.W))))

      // Sideband tester: stages one 64-bit packet each way over the
      // single-bit sideband. See `SidebandTestRegsIO`.
      val sbTxPacket = RegInit(0.U(io.test.sb.txPacket.getWidth.W))
      val sbTxSend = Wire(DecoupledIO(UInt(1.W)))
      val sbRxPop = Wire(DecoupledIO(UInt(1.W)))
      val sbRxRst = Wire(DecoupledIO(UInt(1.W)))
      sbTxSend.ready := true.B
      sbRxPop.ready := true.B
      sbRxRst.ready := true.B

      val controllerSel = RegInit(ControllerSel.phytest)
      val mainbandMode = RegInit(BandMode.manual)
      val sidebandMode = RegInit(BandMode.manual)
      io.controllerSel := controllerSel
      io.mainbandMode := mainbandMode
      io.sidebandMode := sidebandMode

      val creditFlowEnable = RegInit(true.B)
      io.creditFlowEnable := creditFlowEnable

      val lastSeenTLReq = RegInit(0.U)
      lastSeenTLReq := io.lastSeenTLReq

      // 2-FF sync: the flag is sticky and set in the digital clock domain,
      // which is asynchronous to the UCIe clock the registers run on.
      val sbTlRxOverflow =
        RegNext(RegNext(io.sbTlRxOverflow, false.B), false.B)

      // Controls added on top of the original test modes. Each resets to the
      // value that leaves those modes unchanged. See `PhyTestRegsIO`.
      val rxStartMode = RegInit(RxStartMode.valid)
      val rxBurstMode = RegInit(false.B)
      val rxValidStartThreshold = RegInit(
        PhyTest.rxValidStartThresholdReset.U(
          log2Ceil(Phy.SerdesRatio + 1).W
        )
      )
      val rxSelfSync = RegInit(false.B)
      val rxAutoSeed = RegInit(false.B)
      val rxManualCheck = RegInit(false.B)
      val rxMonitor = RegInit(false.B)
      val rxCaptureMode = RegInit(CaptureMode.first)
      // Half the SRAM after the trigger, so it is centered in what is kept.
      val rxCapturePostTrigger = RegInit(
        (1 << (params.bufferDepthPerLane - 5 - 1))
          .U(io.test.rxCapturePostTrigger.getWidth.W)
      )
      val rxTriggerLaneMask = RegInit(
        ((BigInt(1) << PhyTest.numTestLanes(params.numLanes)) - 1)
          .U(io.test.rxTriggerLaneMask.getWidth.W)
      )
      val txBurstPackets = RegInit(0.U(io.test.txBurstPackets.getWidth.W))
      val txIdlePackets = RegInit(0.U(io.test.txIdlePackets.getWidth.W))
      val txClkGate = RegInit(false.B)
      // No preamble, as UCIe sends, and a packet of postamble: twice what
      // UCIe asks for, but a whole packet keeps the RX word boundary in place.
      // See `PhyTestRegsIO.txClkGate`.
      val txClkPreamble = RegInit(0.U(io.test.txClkPreamble.getWidth.W))
      val txClkPostamble = RegInit(
        (32 / PhyTest.ClkPostambleUi).U(io.test.txClkPostamble.getWidth.W)
      )
      val txClkParkAlternate = RegInit(true.B)
      val txTrackClk = RegInit(false.B)
      // A clock, as the forwarded-clock lanes carry it.
      val txTrackWord = RegInit(BigInt("55555555", 16).U(32.W))
      val rxCaptureStart = RegInit(0.U(io.test.rxCaptureStart.getWidth.W))
      val txInjectError = Wire(DecoupledIO(UInt(1.W)))
      txInjectError.ready := true.B
      val txInjectLane = RegInit(0.U(io.test.txInjectLane.getWidth.W))
      val trackCntRst = Wire(DecoupledIO(UInt(1.W)))
      trackCntRst.ready := true.B
      val trackCntHold = RegInit(false.B)
      val txClkDirect = RegInit(false.B)
      io.txClkDirect := txClkDirect
      // 2-FF syncs, like `sbTlRxOverflow`: each flag is sticky and set on a
      // packet clock.
      val txTestFifoUnderflow =
        RegNext(RegNext(io.txTestFifoUnderflow, false.B), false.B)
      val rxTestFifoOverflow =
        RegNext(RegNext(io.rxTestFifoOverflow, false.B), false.B)
      val controllerRxFifoOverflow =
        RegNext(RegNext(io.controllerRxFifoOverflow, false.B), false.B)
      val tlRxFifoOverflow =
        RegNext(RegNext(io.tlRxFifoOverflow, false.B), false.B)
      val txLaneFixed = RegInit(0.U(io.test.txLaneFixed.getWidth.W))
      val txLaneInvert = RegInit(0.U(io.test.txLaneInvert.getWidth.W))
      val txFixedWord = RegInit(0.U(io.test.txFixedWord.getWidth.W))
      val rxLaneFixed = RegInit(0.U(io.test.rxLaneFixed.getWidth.W))
      val rxLaneInvert = RegInit(0.U(io.test.rxLaneInvert.getWidth.W))
      val rxFixedWord = RegInit(0.U(io.test.rxFixedWord.getWidth.W))
      val rxErrPosLane = RegInit(0.U(io.test.rxErrPosLane.getWidth.W))
      val rxErrClassLane = RegInit(0.U(io.test.rxErrClassLane.getWidth.W))
      // Caps the words PhyTest keeps in its TX queue, for low latency from its
      // TX to the wire. Zero lets the queue fill, which costs its whole depth.
      // A cap smaller than the queue's synchronizer lag runs it empty, which
      // `txTestFifoUnderflow` reports. The closer the PHY test clock is to the
      // packet clock, the larger the cap must be. At 16 GT/s, 8 works with an
      // 800 MHz PHY test clock and 10 with 500 MHz, the slowest that keeps up.
      // See `UcieTLRegs.txQueueLimitReset`.
      val txQueueLimit =
        RegInit(UcieTLRegs.txQueueLimitReset.U(UcieTLRegs.txQueueLimitWidth.W))
      io.txQueueLimit := txQueueLimit
      // See `PhyRegsIO.txUiDelay` and `rxUiDelay`.
      val txUiDelay = RegInit(
        VecInit(
          Seq.fill(Phy.numTxLanes(params.numLanes))(
            0.U(Phy.uiDelayWidth.W)
          )
        )
      )
      val rxUiDelay = RegInit(
        VecInit(
          Seq.fill(Phy.numRxDataLanes(params.numLanes))(
            0.U(Phy.uiDelayWidth.W)
          )
        )
      )

      txDatapathRst.ready := true.B
      txFsmRst.ready := true.B
      txExecute.ready := true.B
      txWriteChunk.ready := true.B
      rxDatapathRst.ready := true.B
      rxFsmRst.ready := true.B

      // Stretches a one-cycle register write to `UcieTLRegs.rstStrobeCycles`
      // cycles, for the datapath resets. The FSM resets stay one cycle: they
      // clear logic on this clock, and a longer one would swallow a
      // `txExecute` or `txInjectError` written just after.
      def stretched(pulse: Bool): Bool = {
        val remaining =
          RegInit(0.U(log2Ceil(UcieTLRegs.rstStrobeCycles).W))
        when(pulse) {
          remaining := (UcieTLRegs.rstStrobeCycles - 1).U
        }.elsewhen(remaining =/= 0.U) {
          remaining := remaining - 1.U
        }
        pulse || remaining =/= 0.U
      }

      def applyShift[T <: Data](data: T, cycles: Int = 0): T = {
        if (cycles > 0) {
          ShiftRegister(data, cycles, true.B)
        } else {
          data
        }
      }

      io.test.txDataChunkIn.bits := applyShift(
        Cat(txDataChunkIn1, txDataChunkIn0)
      )
      io.test.txDataChunkIn.valid := applyShift(
        txWriteChunk.valid
      )
      io.test.txDataLaneGroup := applyShift(txDataLaneGroup)
      io.test.txDataOffset := applyShift(txDataOffset)

      io.test.testTarget := applyShift(testTarget)
      io.test.mainbandMode := applyShift(mainbandMode)
      io.test.sidebandMode := applyShift(sidebandMode)
      io.test.txTestMode := applyShift(txTestMode)
      io.test.txDataMode := applyShift(txDataMode)
      io.test.txLfsrSeed := applyShift(txLfsrSeed)
      // Registered: each drives an async reset synchronizer's input, where a
      // glitch from the write decode or counter compare would reset the
      // datapath mid-run.
      io.test.txDatapathRst :=
        applyShift(RegNext(stretched(txDatapathRst.valid), false.B))
      io.test.txFsmRst := applyShift(txFsmRst.valid)
      io.test.txExecute := applyShift(txExecute.valid)
      io.test.txManualRepeatPeriod := applyShift(txManualRepeatPeriod)
      io.test.txPacketsToSend := applyShift(txPacketsToSend)
      io.test.txClkP := applyShift(txClkP)
      io.test.txClkN := applyShift(txClkN)
      io.test.txValid := applyShift(txValid)
      io.test.rxDataMode := applyShift(rxDataMode)
      io.test.rxLfsrSeed := applyShift(rxLfsrSeed)
      io.test.rxValid := applyShift(rxValid)
      io.test.rxDatapathRst :=
        applyShift(RegNext(stretched(rxDatapathRst.valid), false.B))
      io.test.rxFsmRst := applyShift(rxFsmRst.valid)
      io.test.rxPacketsToReceive := applyShift(rxPacketsToReceive)
      io.test.rxPauseCounters := applyShift(rxPauseCounters)
      io.test.rxDataLane := applyShift(rxDataLane)
      io.test.rxDataOffset := applyShift(rxDataOffset)
      io.test.sb.txPacket := applyShift(sbTxPacket)
      io.test.sb.txSend := applyShift(sbTxSend.valid)
      io.test.sb.rxPop := applyShift(sbRxPop.valid)
      io.test.sb.rxRst := applyShift(sbRxRst.valid)
      // Moving valid onto another lane is a test function, not a UCIe spec
      // feature, so these go to PhyTest, not the PHY.
      io.test.txValidLaneSel := applyShift(txValidLaneSel)
      io.test.rxValidLaneSel := applyShift(rxValidLaneSel)
      io.phy.txctl := applyShift(VecInit(txctl.take(params.numLanes + 4)))
      io.phy.rxctl := applyShift(VecInit(rxctl.take(params.numLanes + 4)))
      io.phy.txUiDelay := applyShift(txUiDelay)
      io.phy.rxUiDelay := applyShift(rxUiDelay)
      io.test.rxUiDelay := applyShift(rxUiDelay)
      // The last lane control slot belongs to the tester's loopback pair, which
      // lives in PhyTest rather than in the PHY.
      io.test.loopbackTxctl := applyShift(txctl(params.numLanes + 4))
      io.test.loopbackRxctl := applyShift(rxctl(params.numLanes + 4))

      // Debug circuitry: the observation bump drivers and the TX data debug
      // lane, all owned by PhyTest.
      io.test.driverctl := applyShift(debugDriverctl)
      io.test.debugMuxSel := applyShift(debugMuxSel)
      io.test.txctl := applyShift(debugTxctl)
      io.test.txDebugTestMode := applyShift(debugTxTestMode)
      io.test.txDebugDataMode := applyShift(debugTxDataMode)
      io.test.txDebugLfsrSeed := applyShift(debugTxLfsrSeed)
      io.test.txDebugFsmRst := applyShift(debugTxFsmRst.valid)
      io.test.txDebugExecute := applyShift(debugTxExecute.valid)
      io.test.txDebugManualRepeatPeriod := applyShift(
        debugTxManualRepeatPeriod
      )
      io.test.txDebugPacketsToSend := applyShift(debugTxPacketsToSend)
      io.test.txDebugData := applyShift(debugData)

      io.test.rxStartMode := applyShift(rxStartMode)
      io.test.rxBurstMode := applyShift(rxBurstMode)
      io.test.rxValidStartThreshold := applyShift(rxValidStartThreshold)
      io.test.rxSelfSync := applyShift(rxSelfSync)
      io.test.rxAutoSeed := applyShift(rxAutoSeed)
      io.test.rxManualCheck := applyShift(rxManualCheck)
      io.test.rxMonitor := applyShift(rxMonitor)
      io.test.rxCaptureMode := applyShift(rxCaptureMode)
      io.test.rxCapturePostTrigger := applyShift(rxCapturePostTrigger)
      io.test.rxTriggerLaneMask := applyShift(rxTriggerLaneMask)
      io.test.txBurstPackets := applyShift(txBurstPackets)
      io.test.txIdlePackets := applyShift(txIdlePackets)
      io.test.txClkGate := applyShift(txClkGate)
      io.test.txClkPreamble := applyShift(txClkPreamble)
      io.test.txClkPostamble := applyShift(txClkPostamble)
      io.test.txClkParkAlternate := applyShift(txClkParkAlternate)
      io.test.txTrackClk := applyShift(txTrackClk)
      io.test.txTrackWord := applyShift(txTrackWord)
      io.test.rxCaptureStart := applyShift(rxCaptureStart)
      io.test.txInjectError := applyShift(txInjectError.valid)
      io.test.txInjectLane := applyShift(txInjectLane)
      io.test.trackCntRst := applyShift(trackCntRst.valid)
      io.test.trackCntHold := applyShift(trackCntHold)
      io.test.txLaneFixed := applyShift(txLaneFixed)
      io.test.txLaneInvert := applyShift(txLaneInvert)
      io.test.txFixedWord := applyShift(txFixedWord)
      io.test.rxLaneFixed := applyShift(rxLaneFixed)
      io.test.rxLaneInvert := applyShift(rxLaneInvert)
      io.test.rxFixedWord := applyShift(rxFixedWord)
      io.test.rxErrPosLane := applyShift(rxErrPosLane)
      io.test.rxErrClassLane := applyShift(rxErrClassLane)
      // A link without the TX data debug lane keeps that lane's register slots
      // and names, so every link has one register layout, but backs them with
      // nothing: each reads 0 and drops writes.
      def txDebugField(f: RegField): RegField =
        if (params.hasTxDebugLane) f
        else RegField.r(f.width, 0.U(f.width.W), f.desc.orNull)
      if (!params.hasTxDebugLane) {
        Seq(debugTxFsmRst, debugTxExecute).foreach { strobe =>
          strobe.valid := false.B
          strobe.bits := 0.U
        }
      }

      // String name should always be camel case with an underscore to separate indices.
      // Adjacent indices should be contiguous in memory. Increasing index should correspond to increasing memory address.
      val mmioRegs = Seq(
        toRegFieldRw(testTarget, "testTarget"),
        toRegFieldRw(txTestMode, "txTestMode"),
        toRegFieldRw(txDataMode, "txDataMode")
      ) ++ (0 until PhyTest.numTestLanes(params.numLanes)).map((i: Int) => {
        toRegFieldRw(txLfsrSeed(i), s"txLfsrSeed_$i")
      }) ++ Seq(
        RegField.w(1, txDatapathRst, RegFieldDesc("txDatapathRst", "")),
        RegField.w(1, txFsmRst, RegFieldDesc("txFsmRst", "")),
        RegField.w(1, txExecute, RegFieldDesc("txExecute", "")),
        RegField.w(1, txWriteChunk, RegFieldDesc("txWriteChunk", "")),
        toRegFieldR(
          applyShift(io.test.txPacketsSent),
          "txPacketsSent"
        ),
        toRegFieldRw(txManualRepeatPeriod, "txManualRepeatPeriod"),
        toRegFieldRw(txPacketsToSend, "txPacketsToSend"),
        toRegFieldRw(txClkP, "txClkP"),
        toRegFieldRw(txClkN, "txClkN"),
        toRegFieldRw(txDataLaneGroup, "txDataLaneGroup"),
        toRegFieldRw(txDataOffset, "txDataOffset"),
        toRegFieldRw(txDataChunkIn0, "txDataChunkIn0"),
        toRegFieldRw(txDataChunkIn1, "txDataChunkIn1"),
        toRegFieldR(
          applyShift(io.test.txDataChunkOut(63, 0)),
          "txDataChunkOut0"
        ),
        toRegFieldR(
          applyShift(io.test.txDataChunkOut(127, 64)),
          "txDataChunkOut1"
        )
      ) ++ Seq(
        toRegFieldR(
          applyShift(io.test.txTestState),
          "txTestState"
        ),
        toRegFieldRw(rxDataMode, s"rxDataMode")
      ) ++ (0 until PhyTest.numTestLanes(params.numLanes)).map((i: Int) => {
        toRegFieldRw(rxLfsrSeed(i), s"rxLfsrSeed_$i")
      }) ++ (0 until PhyTest.numTestLanes(params.numLanes)).map((i: Int) => {
        toRegFieldR(
          applyShift(io.test.rxBitErrors(i)),
          s"rxBitErrors_$i"
        )
      }) ++ (0 until PhyTest.numTestLanes(params.numLanes)).map((i: Int) => {
        toRegFieldR(
          applyShift(io.test.rxBitErrorsEarly(i)),
          s"rxBitErrorsEarly_$i"
        )
      }) ++ (0 until PhyTest.numTestLanes(params.numLanes)).map((i: Int) => {
        toRegFieldR(
          applyShift(io.test.rxBitErrorsLate(i)),
          s"rxBitErrorsLate_$i"
        )
      }) ++ Seq(
        RegField.w(1, rxDatapathRst, RegFieldDesc("rxDatapathRst", "")),
        RegField.w(1, rxFsmRst, RegFieldDesc("rxFsmRst", "")),
        toRegFieldRw(rxPacketsToReceive, "rxPacketsToReceive"),
        toRegFieldRw(rxPauseCounters, "rxPauseCounters"),
        toRegFieldR(
          applyShift(io.test.rxPacketsReceived),
          "rxPacketsReceived"
        ),
        toRegFieldR(
          applyShift(io.test.rxSignature),
          "rxSignature"
        ),
        toRegFieldR(
          applyShift(io.test.rxIdleWordsObserved),
          "rxIdleWordsObserved"
        ),
        toRegFieldRw(rxDataLane, "rxDataLane"),
        toRegFieldRw(rxDataOffset, "rxDataOffset"),
        toRegFieldR(
          applyShift(io.test.rxDataChunk),
          "rxDataChunk"
        )
      ) ++ (0 until params.numLanes + 5).flatMap((i: Int) => {
        Seq(
          toRegFieldRw(txctl(i).tile, s"txctl_${i}_tile")
        ) ++ (0 until 32).map((j: Int) =>
          toRegFieldRw(txctl(i).shuffler(j), s"txctl_${i}_shuffler_$j")
        ) ++ Seq(
          toRegFieldRw(txctl(i).sample_negedge, s"txctl_${i}_sampleNegedge")
        )
      }) ++ (0 until params.numLanes + 5).flatMap((i: Int) => {
        Seq(
          toRegFieldRw(rxctl(i).zen, s"rxctl_${i}_zen"),
          toRegFieldRw(rxctl(i).zctl, s"rxctl_${i}_zctl"),
          toRegFieldRw(rxctl(i).vref_sel, s"rxctl_${i}_vrefSel"),
          toRegFieldRw(rxctl(i).afeBypassEn, s"rxctl_${i}_afeBypassEn"),
          toRegFieldRw(rxctl(i).afeBypass, s"rxctl_${i}_afeBypass"),
          toRegFieldRw(rxctl(i).afeOpCycles, s"rxctl_${i}_afeOpCycles"),
          toRegFieldRw(
            rxctl(i).afeOverlapCycles,
            s"rxctl_${i}_afeOverlapCycles"
          )
        ) ++ (0 until 32).map((j: Int) =>
          toRegFieldRw(rxctl(i).shuffler(j), s"rxctl_${i}_shuffler_$j")
        ) ++ Seq(
          toRegFieldRw(rxctl(i).sample_negedge, s"rxctl_${i}_sampleNegedge"),
          // Where `rxDelay` was, a field nothing read, so no RX address
          // moves.
          toRegFieldRw(rxctl(i).Dctrl, s"rxctl_${i}_dctrl")
        )
      }) ++ Seq(
        toRegFieldRw(debugTxTestMode, "debugTxTestMode"),
        toRegFieldRw(debugTxDataMode, "debugTxDataMode"),
        toRegFieldRw(debugTxLfsrSeed, s"debugTxLfsrSeed"),
        RegField.w(1, debugTxFsmRst, RegFieldDesc("debugTxFsmRst", "")),
        RegField.w(1, debugTxExecute, RegFieldDesc("debugTxExecute", "")),
        toRegFieldRw(debugTxManualRepeatPeriod, "debugTxManualRepeatPeriod"),
        toRegFieldRw(debugTxPacketsToSend, "debugTxPacketsToSend")
      ).map(txDebugField) ++ (0 until 16).map((i: Int) => {
        txDebugField(toRegFieldRw(debugData(i), s"debugData_${i}"))
      }) ++ (0 until debugDriverctl.length).map((i: Int) => {
        toRegFieldRw(debugDriverctl(i), s"debugDriverctl_${i}")
      }) ++ (Seq(
        toRegFieldRw(debugTxctl.tile, s"debugTxctlTile")
      ) ++ (0 until 32).map((j: Int) =>
        toRegFieldRw(debugTxctl.shuffler(j), s"debugTxctlShuffler_$j")
      ) ++ Seq(
        toRegFieldR(
          applyShift(io.test.txDebugState),
          "debugTxTestState"
        ),
        toRegFieldR(
          applyShift(io.test.txDebugPacketsEnqueued),
          "debugTxPacketsSent"
        )
      )).map(txDebugField) ++ (0 until debugMuxSel.length).map((i: Int) =>
        toRegFieldRw(debugMuxSel(i), s"debugMuxSel_${i}")
      ) ++ Seq(
        toRegFieldRw(txValid, "txValid"),
        toRegFieldRw(rxValid, "rxValid"),
        toRegFieldRw(controllerSel, "controllerSel"),
        toRegFieldRw(mainbandMode, "mainbandMode"),
        toRegFieldRw(sidebandMode, "sidebandMode"),
        toRegFieldRw(creditFlowEnable, "creditFlowEnable"),
        toRegFieldRw(txValidLaneSel, "txValidLaneSel"),
        toRegFieldRw(rxValidLaneSel, "rxValidLaneSel"),
        RegField.r(64, lastSeenTLReq, RegFieldDesc("lastSeenTLReq", "")),
        toRegFieldRw(sbTxPacket, "sbTxPacket"),
        RegField.w(1, sbTxSend, RegFieldDesc("sbTxSend", "")),
        toRegFieldR(applyShift(io.test.sb.txBusy), "sbTxBusy"),
        toRegFieldR(applyShift(io.test.sb.rxPacket), "sbRxPacket"),
        toRegFieldR(applyShift(io.test.sb.rxValid), "sbRxValid"),
        RegField.w(1, sbRxPop, RegFieldDesc("sbRxPop", "")),
        toRegFieldR(applyShift(io.test.sb.rxOverflow), "sbRxOverflow"),
        RegField.w(1, sbRxRst, RegFieldDesc("sbRxRst", "")),
        toRegFieldR(sbTlRxOverflow, "sbTlRxOverflow")
      ) ++ Seq(
        // Everything from here on was added after the original test modes, and
        // goes at the end so that no earlier address moves.
        toRegFieldRw(rxStartMode, "rxStartMode"),
        toRegFieldRw(rxBurstMode, "rxBurstMode"),
        toRegFieldRw(rxSelfSync, "rxSelfSync"),
        toRegFieldRw(rxMonitor, "rxMonitor"),
        toRegFieldRw(rxCaptureMode, "rxCaptureMode"),
        toRegFieldRw(rxCapturePostTrigger, "rxCapturePostTrigger"),
        toRegFieldRw(rxTriggerLaneMask, "rxTriggerLaneMask"),
        toRegFieldR(
          applyShift(io.test.rxCaptureTriggered),
          "rxCaptureTriggered"
        ),
        toRegFieldR(
          applyShift(io.test.rxCaptureTriggerPacket),
          "rxCaptureTriggerPacket"
        ),
        toRegFieldRw(txBurstPackets, "txBurstPackets"),
        toRegFieldRw(txIdlePackets, "txIdlePackets"),
        toRegFieldRw(txClkGate, "txClkGate"),
        toRegFieldRw(txClkPreamble, "txClkPreamble"),
        toRegFieldRw(txClkPostamble, "txClkPostamble"),
        toRegFieldRw(txTrackClk, "txTrackClk"),
        RegField.w(1, txInjectError, RegFieldDesc("txInjectError", "")),
        toRegFieldRw(txInjectLane, "txInjectLane"),
        toRegFieldR(applyShift(io.test.trackOnes), "trackOnes"),
        toRegFieldR(applyShift(io.test.trackZeros), "trackZeros"),
        RegField.w(1, trackCntRst, RegFieldDesc("trackCntRst", "")),
        toRegFieldRw(trackCntHold, "trackCntHold"),
        toRegFieldRw(txClkDirect, "txClkDirect"),
        toRegFieldR(txTestFifoUnderflow, "txTestFifoUnderflow"),
        toRegFieldR(rxTestFifoOverflow, "rxTestFifoOverflow"),
        toRegFieldR(
          applyShift(io.test.loopbackRxOverflow),
          "loopbackRxFifoOverflow"
        ),
        toRegFieldRw(txTrackWord, "txTrackWord"),
        toRegFieldRw(rxCaptureStart, "rxCaptureStart"),
        toRegFieldR(
          applyShift(io.test.loopbackTxUnderflow),
          "loopbackTxFifoUnderflow"
        ),
        toRegFieldR(
          applyShift(io.test.txDebugUnderflow),
          "txDebugFifoUnderflow"
        ),
        toRegFieldR(applyShift(io.test.trackOnesEven), "trackOnesEven"),
        toRegFieldRw(rxManualCheck, "rxManualCheck"),
        toRegFieldRw(txLaneFixed, "txLaneFixed"),
        toRegFieldRw(txLaneInvert, "txLaneInvert"),
        toRegFieldRw(txFixedWord, "txFixedWord"),
        toRegFieldRw(rxLaneFixed, "rxLaneFixed"),
        toRegFieldRw(rxLaneInvert, "rxLaneInvert"),
        toRegFieldRw(rxFixedWord, "rxFixedWord"),
        toRegFieldR(applyShift(io.test.rxLaneErrored), "rxLaneErrored"),
        toRegFieldRw(rxErrPosLane, "rxErrPosLane"),
        toRegFieldR(applyShift(io.test.rxErrPosMask), "rxErrPosMask"),
        toRegFieldRw(rxErrClassLane, "rxErrClassLane"),
        toRegFieldR(applyShift(io.test.rxErrRiseOne), "rxErrRiseOne"),
        toRegFieldR(applyShift(io.test.rxErrRiseZero), "rxErrRiseZero"),
        toRegFieldR(applyShift(io.test.rxErrFallOne), "rxErrFallOne"),
        toRegFieldR(applyShift(io.test.rxErrFallZero), "rxErrFallZero"),
        toRegFieldR(controllerRxFifoOverflow, "controllerRxFifoOverflow"),
        toRegFieldRw(txQueueLimit, "txQueueLimit"),
        // At the end, out of place, so that no register before them moves.
        toRegFieldRw(txClkParkAlternate, "txClkParkAlternate"),
        toRegFieldR(applyShift(io.test.rxFrameOffset), "rxFrameOffset")
      ) ++ txUiDelay.zipWithIndex.map { case (d, i) =>
        toRegFieldRw(d, s"txUiDelay_$i")
      } ++ rxUiDelay.zipWithIndex.map { case (d, i) =>
        toRegFieldRw(d, s"rxUiDelay_$i")
      } ++ Seq(
        toRegFieldRw(rxValidStartThreshold, "rxValidStartThreshold"),
        txDebugField(
          toRegFieldRw(debugTxctl.sample_negedge, "debugTxctlSampleNegedge")
        ),
        toRegFieldRw(rxAutoSeed, "rxAutoSeed"),
        toRegFieldR(tlRxFifoOverflow, "tlRxFifoOverflow")
      )

      require(
        mmioRegs.size * 8 <= UcieTLRegs.regionSize,
        "PhyTest's registers have grown into the UCIe controller's"
      )
      mmioRegs.zipWithIndex.map({
        case (f, i) => {
          i * 8 -> Seq(f)
        }
      })
    }
    node.regmap(regmap: _*)
  }
}

/** The UCIe controller and its registers (the spec-defined register block and
  * the controls this wrapper adds), on the sideband clock.
  *
  * Its own clock domain, because diplomacy builds every lazy child of
  * [[UcieTL]] on UcieTL's clock, whatever clock the parent asks for.
  */
class UcieController(
    params: UcieTLParams,
    beatBytes: Int,
    digitalParams: UcieDigitalTopParams
)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters()) {
  override lazy val desiredName = "UcieController"
  private val regParams = digitalParams.regs
  private val retryW = digitalParams.logPhy.retryW
  val ucieDigital = LazyModule(new UcieDigitalTop(digitalParams))

  // The controller's own controls, then the spec registers. One aligned window
  // holds both ranges, so the offsets are from `UcieTLParams.address`.
  import UcieController.{ctrlBase, specBase}
  val device = new SimpleDevice("ucie_controller", Seq("ucbbar,ucie"))
  val node = TLRegisterNode(
    AddressSet.misaligned(params.address + ctrlBase, specBase - ctrlBase) :+
      AddressSet(
        params.address + specBase,
        regParams.allocation.regionSize - 1
      ),
    device,
    "reg/control",
    beatBytes = beatBytes
  )

  override lazy val module = new UcieControllerImpl
  class UcieControllerImpl extends Impl {
    val io = IO(new Bundle {
      val chipFacingIo = new UcieDigitalTopChipIO(digitalParams.protocol)
      val phyFacingIo = new UcieDigitalTopPhyIO(
        digitalParams.logPhy.afe,
        digitalParams.logPhy.sideband
      )
    })
    private val top = ucieDigital.module
    io.chipFacingIo <> top.io.chipFacingIo
    io.phyFacingIo <> top.io.phyFacingIo

    val regmap: Seq[RegField.Map] = withClockAndReset(clock, reset) {
      // pwrGood defaults set so the link can train without sw intervention.
      // linkReset only clears the non-sticky register state.
      val linkReset = RegInit(false.B)
      val pwrGood = RegInit(true.B)
      val retryTrainingAmt = RegInit(0.U(retryW.W))
      top.io.ctrl.linkReset := linkReset
      top.io.ctrl.pwrGood := pwrGood
      top.io.ctrl.retryTrainingAmt := retryTrainingAmt
      // Holds the controller (logical PHY, adapter, protocol layer) in reset,
      // but not these registers or the crossing into them. The controller
      // sees the RX words and the partner's sideband whichever controller
      // owns the lanes. Out of reset while PhyTest or the TL path owns them,
      // it would train against their traffic in the background. Set by the
      // UCIe reset; `setup_ucie_digital` clears it once the controller owns
      // the lanes.
      val controllerRst = RegInit(true.B)
      top.reset := reset.asBool || controllerRst

      val (spec, _, _) =
        UcieRegBlock.build(top.io.regBlockIo.get, reset, regParams, specBase)
      spec ++ Seq(
        ctrlBase -> Seq(
          RegField(1, linkReset, RegFieldDesc("ucieLinkReset", ""))
        ),
        (ctrlBase + 8) -> Seq(
          RegField(1, pwrGood, RegFieldDesc("uciePwrGood", ""))
        ),
        (ctrlBase + 16) -> Seq(
          RegField(
            retryW,
            retryTrainingAmt,
            RegFieldDesc("ucieRetryTrainingAmt", "")
          )
        ),
        (ctrlBase + 24) -> Seq(
          RegField(1, controllerRst, RegFieldDesc("ucieControllerRst", ""))
        )
      )
    }
    node.regmap(regmap: _*)
  }
}

object UcieController {

  /** Where the controller's own controls sit above `UcieTLParams.address`, just
    * above PhyTest's registers.
    */
  val ctrlBase = 0x3c00

  /** Where the spec-defined registers sit above `UcieTLParams.address`. */
  val specBase = 0x4000
}

object UcieTL {
  val dataBits = 256

  /** Width of the register port and every register block behind it, except the
    * mainband's. The registers are 64 bits apart, and a narrower port keeps the
    * async queues into the PHY test and TX sideband clocks small.
    */
  val regBeatBytes = 8

  /** Register port requests in flight at once: the source IDs its shrinker
    * hands out, and so the flight bits its FIFO fixer keeps.
    *
    * The clock registers answer in the same cycle and the shrinker can reuse an
    * ID the next cycle, so one in flight gives a request per cycle. The PhyTest
    * and controller blocks are behind one-entry async queues (`regXing`) that
    * pass a request every ~7 chip cycles against a ~8 cycle round trip, so two
    * keep them full. The fixer allows only one block in flight at a time, so
    * two would do; four adds margin on those estimates. More than one in flight
    * happens only when several masters access the registers at once.
    */
  val regsInFlight = 4

  /** Bits in a framed TL packet: the wider of the two channel payloads plus the
    * one-bit tag that says which channel it is. The mainband pads this out to
    * `numLanes * Phy.SerdesRatio` bits; the sideband shifts exactly this many.
    */
  def frameBits(creditBits: Int): Int =
    math.max(
      new UcieTXA(creditBits).getWidth,
      new UcieTXD(creditBits).getWidth
    ) + 1
}

class UcieTLBundleA extends Bundle {
  val opcode = UInt(3.W)
  val param = UInt(3.W)
  val size = UInt(4.W)
  val address = UInt(64.W) // to
  val mask = UInt((UcieTL.dataBits / 8).W)
  val data = UInt(UcieTL.dataBits.W)
  val source = UInt(8.W) // to
  val corrupt = Bool()
}

class UcieTLBundleD extends Bundle {
  // fixed fields during multibeat:
  val opcode = UInt(3.W)
  val param = UInt(2.W)
  val size = UInt(4.W)
  val data = UInt(UcieTL.dataBits.W)
  val source = UInt(8.W) // to
  val sink = UInt(1.W) // from
  val denied = Bool() // implies corrupt iff *Data
  val corrupt = Bool()
}

class UcieTXA(creditBits: Int = 5) extends Bundle {
  val tl_valid = Bool()
  val credit_valid = Bool()
  val credit_a = UInt(creditBits.W)
  val credit_d = UInt(creditBits.W)
  val tl = new UcieTLBundleA
}

class UcieTXD(creditBits: Int = 5) extends Bundle {
  val tl_valid = Bool()
  val credit_valid = Bool()
  val credit_a = UInt(creditBits.W)
  val credit_d = UInt(creditBits.W)
  val tl = new UcieTLBundleD
}

class UcieTL(
    params: UcieTLParams,
    managerRegion: Seq[AddressSet],
    beatBytes: Int,
    blockBytes: Int
)(implicit
    p: Parameters
) extends LazyModule {
  override lazy val desiredName = s"UcieTL${params.moduleSuffix}"

  // Clocks. The chip clock comes in on `digitalClockNode`. The TL ports,
  // register port and clock register block run on it, and it is the default
  // clock of this module's children. The PHY makes the other two: the PHY test
  // clock (PhyTest and its register block) and the TX sideband clock (the UCIe
  // controller and its register block).
  val digitalClockNode = ClockIdentityNode()
  val phyTestClockNode = ClockSourceNode(Seq(ClockSourceParameters()))
  val sidebandClockNode = ClockSourceNode(Seq(ClockSourceParameters()))

  val ucieRegParams = UcieDigitalTopParams
    .default()
    .regs
    .copy(
      baseAddress = params.ucieRegsBaseAddress,
      numModules = 1,
      includeRegNode = false,
      includeInterruptNode = false
    )
  val ucieDigitalParams = {
    val d = UcieDigitalTopParams.default()
    d.copy(
      regs = ucieRegParams,
      logPhy = d.logPhy.copy(sim = params.sim)
    )
  }
  val regs = LazyModule(new UcieTLRegs(params, UcieTL.regBeatBytes))
  val controller = LazyModule(
    new UcieController(params, UcieTL.regBeatBytes, ucieDigitalParams)
  )

  val device = new SimpleDevice("ucie", Seq("ucbbar,ucie"))
  // Manager node to send and acquire traffic to partner die
  val managerNode = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        managers = managerRegion.map { as =>
          TLSlaveParameters.v1(
            address = AddressSet.misaligned(as.base, as.mask + 1),
            resources = device.reg,
            regionType =
              RegionType.UNCACHED, // Should be changed to CACHED eventually
            executable = true,
            supportsGet = TransferSizes(1, blockBytes),
            supportsPutFull = TransferSizes(1, blockBytes),
            supportsPutPartial = TransferSizes(1, blockBytes),
            fifoId = Some(0)
          )
        },
        beatBytes = beatBytes
      )
    )
  )
  // Client node to reply to send and acquire traffic from partner die
  val clientNode = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "ucie-client",
            sourceId = IdRange(0, 1 << params.clientIdBits)
          )
        )
      )
    )
  )
  val clkRegs = LazyModule(new UcieClkRegs(params, UcieTL.regBeatBytes))
  regs.clockNode := phyTestClockNode
  controller.clockNode := sidebandClockNode
  clkRegs.clockNode := digitalClockNode

  // The register port, synchronous to the chip clock, fanned out to all
  // three blocks. The PhyTest and controller blocks are each behind a
  // one-entry async queue, like the debug module's register port. The FIFO
  // fixer makes the port one FIFO domain, which a fragmenter in front of it
  // needs. The name node keeps it named `regs`.
  //
  // The source shrinker keeps the fixer small. The fixer keeps a flight bit
  // per source ID it sees. A fragmenter in front of the port presents every
  // bus source ID times its fragments as one in-order client. Behind Iris's
  // control bus fragmenter that was 20112 flight bits, 11% of the cells of
  // its two links. Behind the shrinker the fixer tracks `UcieTL.regsInFlight`
  // IDs. It still sends requests to one block back to back, and holds a
  // request to another block until every response is in.
  private val regXbar = TLXbar()
  private val regXing = AsynchronousCrossing(depth = 1)
  regs.crossIn(regs.node)(ValName("phytest_regs"))(regXing) := regXbar
  controller.crossIn(controller.node)(ValName("controller_regs"))(regXing) :=
    regXbar
  clkRegs.node := regXbar
  val regNode: TLInwardNode =
    regXbar :=* TLFIFOFixer(TLFIFOFixer.all) :=*
      TLSourceShrinker(UcieTL.regsInFlight) :=* TLNameNode("regs")

  override lazy val module = new UcieTLImpl
  class UcieTLImpl extends LazyRawModuleImp(this) {
    childClock := digitalClockNode.in(0)._1.clock
    childReset := digitalClockNode.in(0)._1.reset
    override def provideImplicitClockToLazyChildren = true

    // Every block, at its own base, so generated collateral sees one map.
    val regmap = regs.module.regmap ++ controller.module.regmap ++
      clkRegs.module.regmap.map { case (off, fields) =>
        (off + UcieClkRegs.offset) -> fields
      }
    val io = IO(new UcieBumpsIO(params.numLanes, params.hasTxDebugLane))

    // PHY
    val phy = Module(
      new Phy(params.numLanes, params.clkDistLayout)(
        params.includeDefaultModels
      )
    )
    io.phy <> phy.io.top
    // The UCIe reset, and only it: every UCIe clock domain resets on this,
    // not on the chip reset, which stops at the clock register block. That
    // block holds it asserted after chip reset, so the domains still start in
    // reset. Assertion is asynchronous to every domain behind it; release is
    // synchronized per domain in the PHY.
    phy.io.clkRst.reset := clkRegs.module.io.ucieRst
    phyTestClockNode.out(0)._1.clock := phy.io.clkRst.phyTestClk
    phyTestClockNode.out(0)._1.reset := phy.io.clkRst.phyTestRst.asAsyncReset
    sidebandClockNode.out(0)._1.clock := phy.io.clkRst.sbClk
    sidebandClockNode.out(0)._1.reset := phy.io.clkRst.sbRst
    phy.io.regs <> regs.module.io.phy

    // TEST HARNESS
    val test = withClockAndReset(
      phy.io.clkRst.phyTestClk,
      phy.io.clkRst.phyTestRst.asAsyncReset
    ) {
      Module(
        new PhyTest(
          params.bufferDepthPerLane,
          params.numLanes,
          params.bitCounterWidth,
          queueParams = params.queueParams,
          hasTxDebugLane = params.hasTxDebugLane
        )(params.includeDefaultModels)
      )
    }
    io.debug <> test.io.bumps
    test.io.debug <> phy.io.debug
    // The divider resets come from the clock register block, on the chip
    // clock, to the PHY's dividers and PhyTest's lanes alike.
    phy.io.clkRst.txDividerRstb := clkRegs.module.io.txDividerRstb
    phy.io.clkRst.txClkGenRstb := clkRegs.module.io.txClkGenRstb
    phy.io.clkRst.rxDividerRstb := clkRegs.module.io.rxDividerRstb
    test.io.txDividerRstb := clkRegs.module.io.txDividerRstb
    test.io.rxDividerRstb := clkRegs.module.io.rxDividerRstb
    phy.io.clkRst.mainClkSel := clkRegs.module.io.mainClkSel
    phy.io.clkRst.pll8En := clkRegs.module.io.pll8En
    clkRegs.module.io.meterClocks := VecInit(
      phy.io.clkRst.phyTestClk,
      phy.io.clkRst.sbClk,
      phy.io.clkRst.txDivClk,
      phy.io.clkRst.rxDivClk
    )
    phy.io.clkRst.pll12En := clkRegs.module.io.pll12En
    phy.io.clkRst.x2En := clkRegs.module.io.x2En
    phy.io.clkRst.pll8Cfg := clkRegs.module.io.pll8Cfg
    phy.io.clkRst.pll12Cfg := clkRegs.module.io.pll12Cfg
    phy.io.clkRst.pll8Dcoarse := clkRegs.module.io.pll8Dcoarse
    clkRegs.module.io.pll8CodeFull := phy.io.clkRst.pll8CodeFull
    clkRegs.module.io.pll8CodeInt := phy.io.clkRst.pll8CodeInt
    clkRegs.module.io.pll12CodeFull := phy.io.clkRst.pll12CodeFull
    clkRegs.module.io.pll12CodeInt := phy.io.clkRst.pll12CodeInt
    phy.io.clkRst.txClkDiv := clkRegs.module.io.txClkDiv
    phy.io.clkRst.txClkPhase := clkRegs.module.io.txClkPhase
    phy.io.clkRst.phyTestClkDiv := clkRegs.module.io.phyTestClkDiv
    phy.io.clkRst.phyTestClkSrcSel := clkRegs.module.io.phyTestClkSrcSel
    phy.io.clkRst.phyTestClkBypassEn := clkRegs.module.io.phyTestClkBypassEn
    phy.io.clkRst.sbClkDiv := clkRegs.module.io.sbClkDiv
    phy.io.clkRst.sbClkSrcSel := clkRegs.module.io.sbClkSrcSel
    phy.io.clkRst.sbClkBypassEn := clkRegs.module.io.sbClkBypassEn
    phy.io.clkRst.rxClkFromTxQ := clkRegs.module.io.rxClkFromTxQ
    phy.io.clkRst.clkPhaseSel := clkRegs.module.io.clkPhaseSel
    phy.io.clkRst.clkEn := clkRegs.module.io.clkEn
    phy.io.clkRst.clkGateEdgeSel := clkRegs.module.io.clkGateEdgeSel
    phy.io.clkRst.rxClkEn := clkRegs.module.io.rxClkEn
    phy.io.clkRst.phyTestClkEn := clkRegs.module.io.phyTestClkEn
    phy.io.clkRst.sbClkEn := clkRegs.module.io.sbClkEn
    phy.io.clkRst.txDatapathRst := test.io.txDatapathRst
    phy.io.clkRst.rxDatapathRst := test.io.rxDatapathRst
    test.io.regs <> regs.module.io.test

    // One controller select, plus a per-band mode that matters only while
    // PhyTest is the controller. Only one band may carry TileLink at a time
    // (they share the credit counters and A/D buffers). If both modes say
    // `tl`, the mainband wins and the sideband stays idle.
    val selUcie = regs.module.io.controllerSel === ControllerSel.ucie
    val selMbTl = !selUcie && regs.module.io.mainbandMode === BandMode.tl
    val selSbTl =
      !selUcie && !selMbTl && regs.module.io.sidebandMode === BandMode.tl

    // Mainband crossings to and from the PHY's packet clocks (TX and RX
    // divided clocks): PhyTest's from the PHY test clock, the controller's
    // from the TX sideband clock. The PHY ends ignore the selects (which are
    // on the PHY test clock): TX queues are always drained and RX queues
    // always fed, so no select reaches their pointers on a packet clock. Only
    // the selected controller's TX words reach the lanes; the rest are
    // dropped, and the other RX sides get words not meant for them. (The TL
    // path's RX queue is the exception; see there.) After changing a select,
    // software resets the datapath, which empties every packet-clock queue at
    // both ends.
    val txTestFifo = Module(
      new AsyncQueue(new TxQueueWord(params.numLanes), params.queueParams)
    )
    txTestFifo.io.enq_clock := phy.io.clkRst.phyTestClk
    txTestFifo.io.enq_reset := phy.io.clkRst.phyTestRst
    txTestFifo.io.deq_clock := phy.io.clkRst.txDivClk
    txTestFifo.io.deq_reset := phy.io.clkRst.txDatapathRstSync

    // PhyTest offers a word every cycle on a clock faster than the packet
    // clock, so unchecked it keeps this queue full: `queueParams.depth` words
    // of latency, about 30 of PhyTest's 33 words TX to RX at 16 GT/s. A
    // nonzero `txQueueLimit` (the reset value) holds PhyTest off once that
    // many words are queued. The count, kept at this end, is enqueues minus
    // the dequeues the PHY end reports back in Gray code. It lags, so it can
    // only read high: the queue never exceeds the cap, and a cap too small for
    // the lag shows up as `txTestFifoUnderflow`.
    val txQueueCountWidth = log2Ceil(params.queueParams.depth) + 1
    val txTestDequeuedGray = withClockAndReset(
      phy.io.clkRst.txDivClk,
      phy.io.clkRst.txDatapathRstSync.asAsyncReset
    ) {
      val count = RegInit(0.U(txQueueCountWidth.W))
      when(txTestFifo.io.deq.fire) { count := count + 1.U }
      val gray = RegInit(0.U(txQueueCountWidth.W))
      gray := count ^ (count >> 1)
      gray
    }
    val txTestQueued = withClockAndReset(
      phy.io.clkRst.phyTestClk,
      phy.io.clkRst.phyTestRst.asAsyncReset
    ) {
      val enqueued = RegInit(0.U(txQueueCountWidth.W))
      when(txTestFifo.io.enq.fire) { enqueued := enqueued + 1.U }
      // The datapath reset empties the queue, both ends.
      when(regs.module.io.test.txDatapathRst) { enqueued := 0.U }
      val gray =
        freechips.rocketchip.util.SynchronizerShiftReg(txTestDequeuedGray, 3)
      val dequeued = VecInit(
        (0 until txQueueCountWidth).map(i =>
          gray(txQueueCountWidth - 1, i).xorR
        )
      ).asUInt
      enqueued - dequeued
    }
    val txQueueRoom = regs.module.io.txQueueLimit === 0.U ||
      txTestQueued < regs.module.io.txQueueLimit
    txTestFifo.io.enq.valid := test.io.tx.valid && txQueueRoom
    txTestFifo.io.enq.bits.word := test.io.tx.bits
    txTestFifo.io.enq.bits.gapAfter := test.io.txGapAfter
    txTestFifo.io.enq.bits.continues := test.io.txContinues
    test.io.tx.ready := txTestFifo.io.enq.ready && txQueueRoom

    val txUcieFifo =
      Module(new AsyncQueue(new TxIO(params.numLanes), params.queueParams))
    txUcieFifo.io.enq_clock := phy.io.clkRst.sbClk
    txUcieFifo.io.enq_reset := phy.io.clkRst.sbRst
    txUcieFifo.io.deq_clock := phy.io.clkRst.txDivClk
    txUcieFifo.io.deq_reset := phy.io.clkRst.txDatapathRstSync
    txUcieFifo.io.deq.ready := true.B

    val rxTestFifo =
      Module(new AsyncQueue(new RxIO(params.numLanes), params.queueParams))
    rxTestFifo.io.enq.bits := phy.io.rx
    rxTestFifo.io.enq.valid := true.B
    rxTestFifo.io.enq_clock := phy.io.clkRst.rxDivClk
    rxTestFifo.io.enq_reset := phy.io.clkRst.rxDatapathRstSync
    rxTestFifo.io.deq_clock := phy.io.clkRst.phyTestClk
    rxTestFifo.io.deq_reset := phy.io.clkRst.phyTestRst
    test.io.rx <> rxTestFifo.io.deq

    // PhyTest's crossings drop words silently when the PHY test clock is
    // slower than the packet rate. That looks exactly like bit errors on the
    // link, so flag it instead. PhyTest drains its RX queue whatever owns the
    // mainband, so that flag is always armed. Its TX queue is empty between
    // runs and bursts by design, so `TxClkGater` flags it, only when a word
    // said more of its run follows; see there.
    val selTestMb = !selUcie && !selMbTl
    regs.module.io.rxTestFifoOverflow := withClockAndReset(
      phy.io.clkRst.rxDivClk,
      phy.io.clkRst.rxDatapathRstSync.asAsyncReset
    ) {
      PhyTest.overflowFlag(rxTestFifo.io.enq.ready)
    }

    val rxUcieFifo =
      Module(new AsyncQueue(new RxIO(params.numLanes), params.queueParams))
    rxUcieFifo.io.enq.bits := phy.io.rx
    rxUcieFifo.io.enq.valid := true.B
    rxUcieFifo.io.enq_clock := phy.io.clkRst.rxDivClk
    rxUcieFifo.io.enq_reset := phy.io.clkRst.rxDatapathRstSync
    rxUcieFifo.io.deq_clock := phy.io.clkRst.sbClk
    rxUcieFifo.io.deq_reset := phy.io.clkRst.sbRst
    // The PHY cannot be held off, so a full queue loses an RX word. That
    // happens when the RX divided clock is faster than the TX sideband clock
    // the controller drains on (1 GHz at 32 GT/s, against 800 MHz). Flag it
    // instead of dropping words silently.
    //
    // Only while the controller owns the lanes. Before then the queue gets
    // PhyTest or TL path traffic at any rate and may be full. So the check
    // arms at a datapath reset with the controller selected (the order
    // software switches in) and disarms on the first cycle it is not. It also
    // waits for the queue's first ready: ready is low for a few cycles after
    // the reset, until the ends handshake and the partner forwards a clock,
    // and that is not overflow.
    regs.module.io.controllerRxFifoOverflow := withClockAndReset(
      phy.io.clkRst.rxDivClk,
      phy.io.clkRst.rxDatapathRstSync.asAsyncReset
    ) {
      val selectedSinceReset = RegInit(true.B)
      when(!selUcie) { selectedSinceReset := false.B }
      val rxUcieFifoPrimed = RegInit(false.B)
      when(rxUcieFifo.io.enq.ready) { rxUcieFifoPrimed := true.B }
      val overflow = selUcie && selectedSinceReset && rxUcieFifoPrimed &&
        !rxUcieFifo.io.enq.ready
      block(Verification) {
        block(Verification.Assert) {
          assert(
            !overflow,
            "UcieTL: the controller's RX queue overflowed; the PHY's packet " +
              "clock is faster than the sideband clock the controller runs on"
          )
        }
      }
      // And on silicon, where nothing asserts.
      val flag = RegInit(false.B)
      when(overflow) { flag := true.B }
      flag
    }

    // The UCIe controller, on the TX sideband clock with its register block.
    val ucieDigital = controller.module
    // The rate training negotiated. The table in the clock register block
    // translates it into analog controls. Software decides what each entry
    // means, so a change in how the part is clocked needs no RTL change.
    clkRegs.module.io.freqSel := ucieDigital.io.phyFacingIo.ctrl.freqSel.asUInt
    clkRegs.module.io.pllLock := Cat(
      phy.io.clkRst.pll12Lock,
      phy.io.clkRst.pll8Lock
    )
    // And back: that block's clock status, in place of the PLL lock and
    // clocks-ungated status a PHY reports. It crosses as held levels, two
    // flops per bit, like the lock into that block. Lock must be for the rate
    // requested now: during a rate change, status for the old rate arrives
    // for a few more cycles and would let training continue before the
    // clocks move. So lock drops the cycle the request changes. It counts only
    // after holding for `lockHoldCycles`, since a multi-bit level can arrive
    // skewed for a cycle.
    withClockAndReset(phy.io.clkRst.sbClk, phy.io.clkRst.sbRst) {
      def sync[T <: Data](x: T): T =
        RegNext(RegNext(x, 0.U.asTypeOf(x)), 0.U.asTypeOf(x))
      val lockHoldCycles = 4
      val ready = sync(clkRegs.module.io.clksReady)
      val readyFreqSel = sync(clkRegs.module.io.clksReadyFreqSel)
      val lockNow = ready &&
        readyFreqSel === ucieDigital.io.phyFacingIo.ctrl.freqSel.asUInt
      val lockHeld = RegInit(0.U(log2Ceil(lockHoldCycles + 1).W))
      when(!lockNow) {
        lockHeld := 0.U
      }.elsewhen(lockHeld =/= lockHoldCycles.U) {
        lockHeld := lockHeld + 1.U
      }
      ucieDigital.io.phyFacingIo.status.pllLock :=
        lockNow && lockHeld === lockHoldCycles.U
      ucieDigital.io.phyFacingIo.status.clocksUngatedAndStable :=
        sync(clkRegs.module.io.clksUngated)
    }
    // The PHY has no TX self-calibration steps yet, so there is never any
    // left to do.
    ucieDigital.io.phyFacingIo.status.txSelfCalDone := true.B

    val digiToPhyTx = ucieDigital.io.phyFacingIo.mainbandLink.tx
    txUcieFifo.io.enq.valid := digiToPhyTx.valid
    txUcieFifo.io.enq.bits.data := digiToPhyTx.bits.data
    txUcieFifo.io.enq.bits.valid := digiToPhyTx.bits.valid
    txUcieFifo.io.enq.bits.track := digiToPhyTx.bits.trk
    txUcieFifo.io.enq.bits.clkp := digiToPhyTx.bits.clkP
    txUcieFifo.io.enq.bits.clkn := digiToPhyTx.bits.clkN
    digiToPhyTx.ready := txUcieFifo.io.enq.ready

    val digiToPhyRx = ucieDigital.io.phyFacingIo.mainbandLink.rx
    digiToPhyRx.bits.data := rxUcieFifo.io.deq.bits.data
    digiToPhyRx.bits.valid := rxUcieFifo.io.deq.bits.valid
    digiToPhyRx.bits.trk := rxUcieFifo.io.deq.bits.track
    // TODO: RxIO has no clkp/clkn; use the forwarded-clock patterns until sampled clkP/clkN exist.
    // Need them for training and link bringup.
    digiToPhyRx.bits.clkP := "h55555555".U
    digiToPhyRx.bits.clkN := "haaaaaaaa".U
    digiToPhyRx.valid := rxUcieFifo.io.deq.valid
    rxUcieFifo.io.deq.ready := digiToPhyRx.ready

    // Sideband TL link: in `tl` sideband mode, the framed TL packets that a
    // `tl` mainband spreads across its lanes are shifted out of the sideband
    // instead, one frame at a time. The link is in the chip clock domain with
    // the rest of the TL path. Its serializer runs on the TX sideband clock and
    // its deserializer on the RX sideband clock, each behind a queue.
    val sbTlFrameBits = UcieTL.frameBits(params.creditBits)
    val sbTl = withClockAndReset(childClock, childReset) {
      Module(new SidebandSerial(sbTlFrameBits, params.sbRxQueueDepth))
    }
    sbTl.io.txClock := phy.io.clkRst.sbClk
    sbTl.io.txReset := phy.io.clkRst.sbRst
    // Held in reset otherwise. The receiver has no framing beyond its bit
    // counter, so it must start counting at the partner's first bit after
    // both dies enter the mode. The link needs its resets from a flop. This
    // one is on the PHY test clock, with the selects it decodes.
    val sbTlRst = withClockAndReset(
      phy.io.clkRst.phyTestClk,
      phy.io.clkRst.phyTestRst.asAsyncReset
    ) { RegNext(!selSbTl, true.B) }
    sbTl.io.txRst := sbTlRst
    sbTl.io.rxRst := sbTlRst

    // Sideband bumps: ucie uses ucieDigital, a `tl` sideband uses the TL link,
    // and otherwise PhyTest's tester drives. Rx goes to all of them; PhyTest
    // holds its own tester in reset when its sideband is not in `manual`. All
    // three serialize on the TX sideband clock.
    val digiSb = ucieDigital.io.phyFacingIo.sidebandLink
    test.io.sbTxClock := phy.io.clkRst.sbClk
    test.io.sbTxReset := phy.io.clkRst.sbRst
    // Each producer serializes in its own clock domain, so the clock travels
    // with the half rate bits and is muxed alongside them.
    val digiSbTxClk = Wire(new SbSerialIO)
    digiSbTxClk.clk := digiSb.out.clk
    digiSbTxClk.d0 := digiSb.out.fwClockD0.asBool
    digiSbTxClk.d1 := digiSb.out.fwClockD1.asBool
    val digiSbTxData = Wire(new SbSerialIO)
    digiSbTxData.clk := digiSb.out.clk
    digiSbTxData.d0 := digiSb.out.d0.asBool
    digiSbTxData.d1 := digiSb.out.d1.asBool
    phy.io.sb.txClk := Mux(
      selUcie,
      digiSbTxClk,
      Mux(selSbTl, sbTl.io.sb.txClk, test.io.sb.txClk)
    )
    phy.io.sb.txData := Mux(
      selUcie,
      digiSbTxData,
      Mux(selSbTl, sbTl.io.sb.txData, test.io.sb.txData)
    )
    test.io.sb.rxClk := phy.io.sb.rxClk
    test.io.sb.rxData := phy.io.sb.rxData
    sbTl.io.sb.rxClk := phy.io.sb.rxClk
    sbTl.io.sb.rxData := phy.io.sb.rxData
    digiSb.in.bits := phy.io.sb.rxData.asUInt
    digiSb.in.fwClock := phy.io.sb.rxClk.asUInt

    withClockAndReset(childClock, childReset) {
      val clientTl = clientNode.out(0)._1
      // The manager port's A channel, through `params.managerBuffer`; its D
      // channel goes out through the same below (`managerD`).
      val managerPort = managerNode.in(0)._1
      val managerA = params.managerBuffer(managerPort.a)

      val ucieClientTlD = Wire(new UcieTLBundleD)
      val ucieManagerTlA = Wire(new UcieTLBundleA)

      require(ucieClientTlD.opcode.getWidth >= clientTl.d.bits.opcode.getWidth)
      require(ucieClientTlD.param.getWidth >= clientTl.d.bits.param.getWidth)
      require(ucieClientTlD.size.getWidth >= clientTl.d.bits.size.getWidth)
      require(ucieClientTlD.data.getWidth >= clientTl.d.bits.data.getWidth)
      require(ucieClientTlD.source.getWidth >= clientTl.d.bits.source.getWidth)
      require(ucieClientTlD.denied.getWidth >= clientTl.d.bits.denied.getWidth)
      require(
        ucieClientTlD.corrupt.getWidth >= clientTl.d.bits.corrupt.getWidth
      )
      ucieClientTlD.opcode := clientTl.d.bits.opcode
      ucieClientTlD.param := clientTl.d.bits.param
      ucieClientTlD.size := clientTl.d.bits.size
      ucieClientTlD.data := clientTl.d.bits.data
      ucieClientTlD.source := clientTl.d.bits.source
      ucieClientTlD.sink := clientTl.d.bits.sink(
        ucieClientTlD.sink.getWidth - 1,
        0
      ) // Truncate since sink will always be 0
      ucieClientTlD.denied := clientTl.d.bits.denied
      ucieClientTlD.corrupt := clientTl.d.bits.corrupt

      require(
        ucieManagerTlA.opcode.getWidth >= managerA.bits.opcode.getWidth
      )
      require(ucieManagerTlA.param.getWidth >= managerA.bits.param.getWidth)
      require(ucieManagerTlA.size.getWidth >= managerA.bits.size.getWidth)
      require(
        ucieManagerTlA.address.getWidth >= managerA.bits.address.getWidth
      )
      require(ucieManagerTlA.mask.getWidth >= managerA.bits.mask.getWidth)
      require(ucieManagerTlA.data.getWidth >= managerA.bits.data.getWidth)
      require(
        ucieManagerTlA.source.getWidth >= managerA.bits.source.getWidth
      )
      require(
        ucieManagerTlA.corrupt.getWidth >= managerA.bits.corrupt.getWidth
      )
      ucieManagerTlA.opcode := managerA.bits.opcode
      ucieManagerTlA.param := managerA.bits.param
      ucieManagerTlA.size := managerA.bits.size
      ucieManagerTlA.address := managerA.bits.address
      ucieManagerTlA.mask := managerA.bits.mask
      ucieManagerTlA.data := managerA.bits.data
      ucieManagerTlA.source := managerA.bits.source
      ucieManagerTlA.corrupt := managerA.bits.corrupt

      val creditBits = params.creditBits

      // Credits to return to the partner: first half = A channel, second half = D channel.
      val aCreditsToReturn = RegInit(0.U(creditBits.W))
      val dCreditsToReturn = RegInit(0.U(creditBits.W))
      val creditRetValid = Wire(Bool())
      val creditRetTimer =
        RegInit(0.U(params.creditRetTimerWidth.W)) // Arbitrary width for now
      val creditsFull = Wire(Bool())
      val aAvail = Wire(Bool())
      val dAvail = Wire(Bool())
      // Whether the selected TL path can take a frame this cycle.
      val txPathReady = Wire(Bool())

      creditRetTimer := creditRetTimer + 1.U
      creditsFull := aCreditsToReturn === 0.U && dCreditsToReturn === 0.U
      creditRetValid := ((clientTl.d.fire ||
        managerA.fire ||
        creditRetTimer === (1 << params.creditRetTimerWidth - 1).U ||
        aCreditsToReturn > params.creditRetThreshhold.U ||
        dCreditsToReturn > params.creditRetThreshhold.U) &&
        !creditsFull &&
        (selUcie || selMbTl || selSbTl) &&
        // Returning credits clears the counters whether or not the frame goes
        // out, so hold the return off until the selected path can take it.
        txPathReady)

      val ucieClientTxD = Wire(new UcieTXD(creditBits))
      ucieClientTxD.tl_valid := clientTl.d.fire
      ucieClientTxD.credit_valid := creditRetValid
      ucieClientTxD.credit_a := aCreditsToReturn
      ucieClientTxD.credit_d := dCreditsToReturn
      dontTouch(ucieClientTxD.tl_valid)
      dontTouch(ucieClientTxD.credit_valid)
      dontTouch(ucieClientTxD.credit_a)
      dontTouch(ucieClientTxD.credit_d)
      ucieClientTxD.tl := ucieClientTlD

      val ucieManagerTxA = Wire(new UcieTXA(creditBits))
      ucieManagerTxA.tl_valid := managerA.fire
      ucieManagerTxA.credit_valid := creditRetValid
      ucieManagerTxA.credit_a := aCreditsToReturn
      ucieManagerTxA.credit_d := dCreditsToReturn
      dontTouch(ucieManagerTxA.tl_valid)
      dontTouch(ucieManagerTxA.credit_valid)
      dontTouch(ucieManagerTxA.credit_a)
      dontTouch(ucieManagerTxA.credit_d)
      ucieManagerTxA.tl := ucieManagerTlA

      val lastSeenAddr = RegInit(0.U(64.W))
      when(managerA.valid) {
        lastSeenAddr := managerA.bits.address
      }
      regs.module.io.lastSeenTLReq := lastSeenAddr
      regs.module.io.sbTlRxOverflow := sbTl.io.rxOverflow

      val rxABuffer =
        Module(new Queue(new UcieTLBundleA, params.tlBufferDepth))
      val rxDBuffer =
        Module(new Queue(new UcieTLBundleD, params.tlBufferDepth))
      val txTlFifo =
        Module(new AsyncQueue(new TxIO(params.numLanes), params.queueParams))
      // Only frames that carry something go in. The PHY end sends an idle
      // frame whenever the queue is empty (see `tlTxWord`), so a frame never
      // waits behind idle ones. Queuing idle frames would keep the queue full
      // whenever the bus clock is faster than the packet clock, and every
      // frame would wait through all of it.
      val txValid = clientTl.d.fire || managerA.fire || creditRetValid
      txTlFifo.io.enq.valid := selMbTl && txValid
      txTlFifo.io.enq.bits.valid := "h0000ffff".U
      // Overwritten at the PHY end; see `tlTxWord`.
      txTlFifo.io.enq.bits.track := "h55555555".U
      txTlFifo.io.enq.bits.clkp := "h55555555".U
      txTlFifo.io.enq.bits.clkn := "haaaaaaaa".U
      val txFramedData = Mux(
        clientTl.d.valid,
        Cat(ucieClientTxD.asUInt, 1.U),
        Cat(ucieManagerTxA.asUInt, 0.U)
      )
      txTlFifo.io.enq.bits.data := txFramedData.asTypeOf(
        txTlFifo.io.enq.bits.data
      )

      // chipFacing TX: route the same framed data into ucieDigital (ucie
      // mode), crossing childClock -> TX sideband clock. Only the protocol data
      // crosses (no track/clkp/clkn/valid lanes).
      val txAQ = Module(
        new AsyncQueue(
          chiselTypeOf(ucieDigital.io.chipFacingIo.mainbandTx.bits),
          params.queueParams
        )
      )

      val ucieTxBeat = (clientTl.d.valid && dAvail) ||
        (managerA.valid && aAvail && !clientTl.d.valid) ||
        creditRetValid
      txAQ.io.enq.valid := selUcie && ucieTxBeat
      txAQ.io.enq.bits.data := txFramedData.asTypeOf(txAQ.io.enq.bits.data)
      txAQ.io.enq_clock := childClock
      txAQ.io.enq_reset := childReset
      txAQ.io.deq_clock := phy.io.clkRst.sbClk
      txAQ.io.deq_reset := phy.io.clkRst.sbRst
      ucieDigital.io.chipFacingIo.mainbandTx.valid := txAQ.io.deq.valid
      ucieDigital.io.chipFacingIo.mainbandTx.bits := txAQ.io.deq.bits
      txAQ.io.deq.ready := ucieDigital.io.chipFacingIo.mainbandTx.ready

      // Sideband TX: the same framed data, offered only when there is
      // something to send, since the sideband has no idle frame to hide a beat
      // in. `sbTl.io.tx.ready` is a plain register, so its feedback into the
      // offer through `creditRetValid` cannot form a combinational loop.
      sbTl.io.tx.valid := selSbTl && ((clientTl.d.valid && dAvail) ||
        (managerA.valid && aAvail && !clientTl.d.valid) ||
        creditRetValid)
      sbTl.io.tx.bits := txFramedData

      // With no TL path selected there is nowhere for a beat to go, so hold
      // the channels off rather than accepting beats that would be dropped.
      val txEnqReady = Mux(
        selUcie,
        txAQ.io.enq.ready,
        Mux(
          selMbTl,
          txTlFifo.io.enq.ready,
          Mux(selSbTl, sbTl.io.tx.ready, false.B)
        )
      )

      txPathReady := txEnqReady

      clientTl.d.ready := txEnqReady && dAvail
      managerA.ready := txEnqReady && aAvail && !clientTl.d.valid

      when(rxABuffer.io.deq.fire) {
        aCreditsToReturn := aCreditsToReturn + 1.U
      }
      when(creditRetValid) {
        aCreditsToReturn := Mux(rxABuffer.io.deq.fire, 1.U, 0.U)
      }
      when(rxDBuffer.io.deq.fire) {
        dCreditsToReturn := dCreditsToReturn + 1.U
      }
      when(creditRetValid) {
        dCreditsToReturn := Mux(rxDBuffer.io.deq.fire, 1.U, 0.U)
      }

      txTlFifo.io.enq_clock := childClock
      txTlFifo.io.enq_reset := childReset
      txTlFifo.io.deq_clock := phy.io.clkRst.txDivClk
      txTlFifo.io.deq_reset := phy.io.clkRst.txDatapathRstSync
      // Drained by `TxClkGater` below.

      val rxTlFifo =
        Module(new AsyncQueue(new RxIO(params.numLanes), params.queueParams))
      val validFramer = Module(new ValidFramer(params.numLanes))
      rxTlFifo.io.enq.bits := phy.io.rx
      // Unlike the PHY's other RX queues, fed only in `tl` mode. Its output is
      // decoded into TL requests on the bus, so another controller's words
      // must never reach it. The select comes from the PHY test clock, so a
      // change on an edge can corrupt this queue's pointers. The datapath
      // reset software issues after the change fixes them.
      rxTlFifo.io.enq.valid := selMbTl
      rxTlFifo.io.enq_clock := phy.io.clkRst.rxDivClk
      rxTlFifo.io.enq_reset := phy.io.clkRst.rxDatapathRstSync
      rxTlFifo.io.deq <> validFramer.io.phy
      rxTlFifo.io.deq_clock := childClock
      rxTlFifo.io.deq_reset := childReset
      // The PHY can't be held off, so a full queue loses a word. That
      // happens when the RX divided clock outruns the chip clock (above
      // 16 GT/s at 500 MHz). Armed as the controller's flag is: by a datapath
      // reset with `tl` selected, and once the queue is first ready.
      regs.module.io.tlRxFifoOverflow := withClockAndReset(
        phy.io.clkRst.rxDivClk,
        phy.io.clkRst.rxDatapathRstSync.asAsyncReset
      ) {
        val selectedSinceReset = RegInit(true.B)
        when(!selMbTl) { selectedSinceReset := false.B }
        val primed = RegInit(false.B)
        when(rxTlFifo.io.enq.ready) { primed := true.B }
        val flag = RegInit(false.B)
        when(
          selMbTl && selectedSinceReset && primed && !rxTlFifo.io.enq.ready
        ) {
          flag := true.B
        }
        flag
      }
      // Replace decoupled IOs that need ready to be true with validIO
      validFramer.io.digital.ready := true.B
      rxABuffer.io.enq.valid := false.B
      rxDBuffer.io.enq.valid := false.B

      // chipFacing RX: ucieDigital's 512b (ucie mode) feeds the credit path,
      // crossing TX sideband clock -> childClock. Muxed with the tl-path
      // ValidFramer output by mode.
      val rxAQ = Module(
        new AsyncQueue(
          chiselTypeOf(ucieDigital.io.chipFacingIo.mainbandRx.bits),
          params.queueParams
        )
      )
      rxAQ.io.enq.valid := ucieDigital.io.chipFacingIo.mainbandRx.valid && selUcie
      rxAQ.io.enq.bits := ucieDigital.io.chipFacingIo.mainbandRx.bits
      ucieDigital.io.chipFacingIo.mainbandRx.ready := rxAQ.io.enq.ready && selUcie
      rxAQ.io.enq_clock := phy.io.clkRst.sbClk
      rxAQ.io.enq_reset := phy.io.clkRst.sbRst
      rxAQ.io.deq_clock := childClock
      rxAQ.io.deq_reset := childReset
      rxAQ.io.deq.ready := selUcie

      // Sideband RX: like the mainband's ValidFramer, frames are taken as they
      // arrive. Credit flow keeps room in the A/D buffers for every frame the
      // partner is allowed to send.
      sbTl.io.rx.ready := true.B

      val framedBits = Mux(
        selSbTl,
        sbTl.io.rx.bits,
        Mux(
          selUcie,
          rxAQ.io.deq.bits.data,
          validFramer.io.digital.bits.asUInt
        )
      )
      val framedValid = Mux(
        selSbTl,
        sbTl.io.rx.valid,
        Mux(selUcie, rxAQ.io.deq.valid, validFramer.io.digital.valid)
      )

      val tlBits = framedBits(framedBits.getWidth - 1, 1)
      val rxIsD = framedBits.asUInt(0)
      val rxFrameA = tlBits.asTypeOf(new UcieTXA(creditBits))
      val rxFrameD = tlBits.asTypeOf(new UcieTXD(creditBits))
      val rxTlValid = Mux(rxIsD, rxFrameD.tl_valid, rxFrameA.tl_valid)
      rxABuffer.io.enq.bits := rxFrameA.tl
      rxDBuffer.io.enq.bits := rxFrameD.tl
      when(framedValid && rxTlValid) {
        when(rxIsD) {
          rxDBuffer.io.enq.valid := true.B
        }.otherwise {
          rxABuffer.io.enq.valid := true.B
        }
      }

      clientTl.a <> rxABuffer.io.deq.map(bits => {
        val tlBundleA = Wire(chiselTypeOf(clientTl.a.bits))
        tlBundleA.opcode := bits.opcode
        tlBundleA.param := bits.param
        tlBundleA.size := bits.size
        tlBundleA.address := bits.address
        tlBundleA.mask := bits.mask
        tlBundleA.data := bits.data
        tlBundleA.source := bits.source
        tlBundleA.corrupt := bits.corrupt
        tlBundleA
      })
      val rxD = rxDBuffer.io.deq.map(bits => {
        val tlBundleD = Wire(chiselTypeOf(managerPort.d.bits))
        tlBundleD.opcode := bits.opcode
        tlBundleD.param := bits.param
        tlBundleD.size := bits.size
        tlBundleD.data := bits.data
        tlBundleD.source := bits.source
        tlBundleD.sink := bits.sink
        tlBundleD.denied := bits.denied
        tlBundleD.corrupt := bits.corrupt
        tlBundleD
      })
      val managerD = params.managerBuffer(rxD)
      managerPort.d <> managerD
      dontTouch(managerPort.d.bits.opcode)

      // A TL frame as it goes on the lanes: data and valid from the queue,
      // the clock and track lanes running.
      val tlTxWord = Wire(new TxIO(params.numLanes))
      tlTxWord := txTlFifo.io.deq.bits
      tlTxWord.track := "h55555555".U
      tlTxWord.clkp := regs.module.io.test.txClkP
      tlTxWord.clkn := regs.module.io.test.txClkN

      // Every word on the lanes comes from `TxClkGater`: the selected
      // controller's next queued word or, between those, one of its own (idle,
      // with the clock running or gated). Each controller queues only words
      // that carry something, so nothing waits behind idle words. The other
      // controllers' queues are drained and their words dropped.
      val txGater = withClockAndReset(
        phy.io.clkRst.txDivClk,
        phy.io.clkRst.txDatapathRstSync.asAsyncReset
      ) { Module(new TxClkGater(params.numLanes)) }
      def queued(w: TxIO): TxQueueWord = {
        val q = Wire(new TxQueueWord(params.numLanes))
        q.word := w
        q.gapAfter := false.B
        q.continues := false.B
        q
      }
      txGater.io.in.valid := Mux(
        selMbTl,
        txTlFifo.io.deq.valid,
        Mux(selUcie, txUcieFifo.io.deq.valid, txTestFifo.io.deq.valid)
      )
      txGater.io.in.bits := Mux(
        selMbTl,
        queued(tlTxWord),
        Mux(selUcie, queued(txUcieFifo.io.deq.bits), txTestFifo.io.deq.bits)
      )
      txTlFifo.io.deq.ready := !selMbTl || txGater.io.in.ready
      txUcieFifo.io.deq.ready := !selUcie || txGater.io.in.ready
      txTestFifo.io.deq.ready := !selTestMb || txGater.io.in.ready
      // PhyTest's bands gate on `txClkGate`. The controller gates once it has
      // negotiated strobe mode and reached ACTIVE. Either can change at any
      // time, so the gate crosses into the packet clock through a
      // synchronizer. The rest are quasi-static registers shared by every
      // controller.
      val testRegs = regs.module.io.test
      val gateNow = Mux(
        selUcie,
        ucieDigital.io.phyFacingIo.ctrl.mbTxClkGate,
        testRegs.txClkGate
      )
      txGater.io.gate := withClockAndReset(
        phy.io.clkRst.txDivClk,
        phy.io.clkRst.txDatapathRstSync.asAsyncReset
      ) { freechips.rocketchip.util.SynchronizerShiftReg(gateNow, 2) }
      txGater.io.preamble := testRegs.txClkPreamble
      txGater.io.postamble := testRegs.txClkPostamble
      txGater.io.idlePackets := testRegs.txIdlePackets
      txGater.io.parkAlternate := testRegs.txClkParkAlternate
      txGater.io.clkP := testRegs.txClkP
      txGater.io.clkN := testRegs.txClkN
      // What each controller sends between words: valid low and the data
      // lanes at zero, and track as that controller runs it.
      txGater.io.fill := 0.U.asTypeOf(txGater.io.fill)
      txGater.io.fill.track := Mux(
        selMbTl,
        "h55555555".U,
        Mux(
          selUcie,
          testRegs.txClkP,
          Mux(
            testRegs.txTrackClk &&
              testRegs.txValidLaneSel =/= Phy
                .trackValidLaneSel(params.numLanes)
                .U,
            testRegs.txTrackWord,
            0.U
          )
        )
      )
      regs.module.io.txTestFifoUnderflow := txGater.io.underflow

      phy.io.tx := txGater.io.out
      // Or the clock lanes skip the queues and the gating altogether. The
      // words are held registers, so like the selects above they need no
      // synchronizer.
      when(regs.module.io.txClkDirect) {
        phy.io.tx.clkp := regs.module.io.test.txClkP
        phy.io.tx.clkn := regs.module.io.test.txClkN
      }

      val rxCreditValid =
        framedValid && Mux(rxIsD, rxFrameD.credit_valid, rxFrameA.credit_valid)

      val aCreditCounter = Module(
        new CreditCounter(params.creditCounterSize, params.tlBufferDepth)
      )
      aCreditCounter.io.used := managerA.fire
      aCreditCounter.io.ret.valid := rxCreditValid
      aCreditCounter.io.ret.bits :=
        Mux(rxIsD, rxFrameD.credit_a, rxFrameA.credit_a)
      aCreditCounter.io.mode := regs.module.io.creditFlowEnable
      aAvail := aCreditCounter.io.avail

      val dCreditCounter = Module(
        new CreditCounter(params.creditCounterSize, params.tlBufferDepth)
      )
      dCreditCounter.io.used := clientTl.d.fire
      dCreditCounter.io.ret.valid := rxCreditValid
      dCreditCounter.io.ret.bits :=
        Mux(rxIsD, rxFrameD.credit_d, rxFrameA.credit_d)
      dCreditCounter.io.mode := regs.module.io.creditFlowEnable
      dAvail := dCreditCounter.io.avail
    }
  }
}

trait CanHavePeripheryUcieTL { this: BaseSubsystem =>
  private val portName = "ucie"

  private val pbus = locateTLBusWrapper(PBUS)
  private val sbus = locateTLBusWrapper(SBUS)

  val uciephy = p(UcieTLKey) match {
    case Some(params) => {
      val uciephy =
        params.map(x =>
          LazyModule(
            new UcieTL(
              x,
              Seq(AddressSet(0x0, 0xffffL)),
              pbus.beatBytes,
              pbus.blockBytes
            )(p)
          )
        )

      lazy val uciephy_tlbus =
        params.map(x => locateTLBusWrapper(x.managerWhere))

      for (
        (((ucie, ucie_params), tlbus), n) <- uciephy
          .zip(params)
          .zip(uciephy_tlbus)
          .zipWithIndex
      ) {
        ucie.digitalClockNode := sbus.fixedClockNode
        pbus.coupleTo(s"uciephytest{$n}") {
          ucie.regNode := TLWidthWidget(pbus.beatBytes) := TLBuffer() :=
            TLFragmenter(pbus.beatBytes, pbus.blockBytes) := TLBuffer() := _
        }
      }
      Some(uciephy)
    }
    case None => None
  }
}

class UcieChipletLink(
    val params: UcieTLParams,
    val sys_params: OffchipSubsystemParams,
    val id: Int
)(implicit p: Parameters)
    extends ChipletLinkWrapper {
  // Follows the UcieTL naming: the wrapper wraps exactly one UcieTL, so it has
  // to split along with it.
  override lazy val desiredName = s"UcieChipletLink${params.moduleSuffix}"

  val ucie = LazyModule(
    new UcieTL(
      params,
      sys_params.managerRegion,
      sys_params.managerBeatBytes,
      sys_params.managerBlockBytes
    )(p)
  )
  val client_node = ucie.clientNode
  val manager_node = ucie.managerNode
  // The router attaches a bare `TLRegisterNode`, but UcieTL's register port
  // is a crossbar in front of three. Attach `regNode` to a control bus
  // yourself.
  val control_manager_node: Option[TLRegisterNode] = None
  val clock_node = Some(ucie.digitalClockNode)
  val top_IO = BundleBridgeSource(() =>
    new UcieBumpsIO(params.numLanes, params.hasTxDebugLane)
  )

  /** UcieTL's register port, synchronous to this link's clock. */
  def regNode: TLInwardNode = ucie.regNode

  override lazy val module = new UcieChipletLinkImpl(this)
}

class UcieChipletLinkImpl(outer: UcieChipletLink) extends LazyModuleImp(outer) {
  val io = outer.top_IO.out(0)._1
  outer.ucie.module.io <> io
}

class WithUcieTL(params: Seq[UcieTLParams])
    extends Config((site, here, up) => { case UcieTLKey =>
      Some(params)
    })

class WithUcieTLDefaultModels
    extends Config((site, here, up) => { case UcieTLKey =>
      up(UcieTLKey, site).map(u => u.map(_.copy(includeDefaultModels = true)))
    })

class RTLHarness(ucie: => UcieTL)(implicit p: Parameters) extends LazyModule {
  val clockNode = ClockSourceNode(Seq(ClockSourceParameters()))
  val node = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        clients = Seq(
          TLMasterParameters.v1(
            name = "dummy-node"
          )
        )
      )
    )
  )
  val ucieTL = LazyModule(ucie)
  // Counterparts for the mainband ports. Reusing the UcieTL node parameters keeps the negotiated
  // edges identical to a direct `managerNode := clientNode` loopback.
  val mbClientNode = TLClientNode(ucieTL.clientNode.portParams)
  val mbManagerNode = TLManagerNode(ucieTL.managerNode.portParams)

  ucieTL.digitalClockNode := clockNode
  ucieTL.regNode := node
  ucieTL.managerNode := mbClientNode
  mbManagerNode := ucieTL.clientNode

  // Bring every diplomatic port out to the top level so that the logic behind them is not
  // optimized out of UcieTL.
  val io_reg = InModuleBody { node.makeIOs() }
  val io_mb_in = InModuleBody { mbClientNode.makeIOs() }
  val io_mb_out = InModuleBody { mbManagerNode.makeIOs() }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    ucieTL.module.io := DontCare
    dontTouch(ucieTL.module.io)
    for (o <- clockNode.out) {
      o._1.clock := clock
      o._1.reset := reset
    }
    val regmap = ucieTL.module.regmap
  }
}
