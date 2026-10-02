package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import chisel3.util.random._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
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
import edu.berkeley.cs.uciedigital.logphy.{ClkRepairStatus, ClkRepairStatusIO}
import edu.berkeley.cs.uciedigital.top.{
  UcieDigitalTop,
  UcieDigitalTopParams,
  UcieRegBridgeCtrlIO
}
import edu.berkeley.cs.uciedigital.regs.{
  UcieRegBlock,
  UcieRegBlockIO,
  UcieRegParams,
  AdapterToRegs,
  PhyToRegs,
  LinkToRegs,
  MailboxSbResp,
  PhyToVendor
}
import edu.berkeley.cs.chippy._
import freechips.rocketchip.diplomacy.{SimpleDevice, AddressSet}
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.lazymodule._
import edu.berkeley.cs.uciedigital.phy.macros.{
  PadDriverCtlIO,
  SbSerialIO,
  TxLaneCtlIO
}
import edu.berkeley.cs.uciedigital.phy.macros.clocking.ClockingTile
import freechips.rocketchip.util.AsyncQueueParams
import freechips.rocketchip.util.AsyncQueue
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
    ucieRegsBaseAddress: BigInt = 0x40000,
    // Frames the sideband TL receiver can hold before the digital domain has
    // to drain them. Must be a power of two.
    sbRxQueueDepth: Int = 4,
    // Names the emitted module (see `moduleSuffix`). Instances that share an id
    // share a SystemVerilog module; give an instance its own id to harden it
    // separately in physical design.
    moduleId: Int = 0
) extends ChipletLinkParams
    with ChipletLinkWrapperInstantiationLike {
  def managerBusWhere = managerWhere
  def controlManagerBusWhere = Some(managerWhere)

  /** Suffix appended to the module names of this instance.
    *
    * Two UCIe instances with the same parameters elaborate to the same
    * hardware, so Chisel puts them in one dedup group (the group is the
    * proposed module name) and firtool folds them into a single SystemVerilog
    * module -- which is what you want when they are hardened together. Physical
    * design sometimes needs one module per instance instead, e.g. when the two
    * are placed in different orientations and hardened separately; giving them
    * different `moduleId`s splits the dedup groups and keeps the hierarchies
    * apart. Id 0 keeps the unsuffixed name.
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

class UcieBumpsIO(numLanes: Int = 16) extends ChipletIO {
  val phy = new PhyBumpsIO(numLanes)
  val debug = new DebugBumpsIO

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
    addrWidth: Int = 64,
    retryW: Int = 10
) extends Bundle {
  val ucieCtrl = Flipped(new UcieRegBridgeCtrlIO(retryW))
  val test = Flipped(
    new PhyTestRegsIO(bufferDepthPerLane, numLanes, bitCounterWidth)
  )
  val phy = Flipped(new PhyRegsIO(numLanes))
  val repair = Flipped(new PhyRepairIO)
  val repairStatus = Output(new ClkRepairStatusIO)
  // MBINIT.REPAIRCLK asking for a measurement window, from the controller.
  // Already on this block's clock, so no crossing.
  val repairClkEnReq = Input(Bool())
  val repairClkEn = Output(Bool())
  val controllerSel = Output(ControllerSel())
  val mainbandMode = Output(BandMode())
  val sidebandMode = Output(BandMode())
  val creditFlowEnable = Output(Bool())
  val lastSeenTLReq = Input(UInt(addrWidth.W))
  val sbTlRxOverflow = Input(Bool())
}

object UcieTLRegs {

  /** Cycles the `txDatapathRst` and `rxDatapathRst` strobes are held for after
    * a register write, counted in the UCIe digital clock the register block and
    * PhyTest share.
    */
  val rstStrobeCycles = 8

  /** Words a REPAIRCLK window accumulates out of reset.
    *
    * Divides by a whole number of clock repair pattern periods at every
    * oversample ratio from 2 to 8, so the same default can be left alone across
    * a ratio sweep without the partial period at the end eating the thresholds'
    * margin.
    */
  val defaultRepairWindow: Int = ClkRepairExpect.alignedWindow(768)

  /** Gap threshold out of reset, for an oversample ratio of four: the ratio
    * MBINIT runs at with an 8 GHz main clock and the lanes at 4 GT/s.
    */
  val defaultRepairGapThresh: Int = ClkRepair.GapThreshUi * 4
}

/** Clock source controls, on a clock of their own.
  *
  * These decide where the PHY's clocks come from, so they cannot sit in the
  * block that runs on one of those clocks: at power up it has nothing to run on
  * until these are decided. `chipDigitalClk` is the chip's own digital clock,
  * running before any of the PHY's exist, and it clocks this block alone -- a
  * separate block rather than a domain bolted onto the main one, because most
  * registers do belong on `ucieClk` with the logic they control.
  *
  * The selects need no synchronizer: they settle during bring-up with the
  * clocks they steer quiet.
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
  class UcieClkRegsImpl extends Impl {
    val io = IO(new Bundle {
      val mainClkSel = Output(UInt(ClockingTile.mainClkSelWidth.W))
      val pll8En = Output(Bool())
      val pll12En = Output(Bool())
      val pll16En = Output(Bool())
      val txClkDiv = Output(UInt(ClockingTile.txClkDivWidth.W))
      val txClkPhase = Output(UInt(ClockingTile.txClkPhaseWidth.W))
      val repairClkDiv = Output(UInt(ClockingTile.repairClkDivWidth.W))
      // High while a clock configuration is being applied. The REPAIRCLK
      // branch divides the same main clock an apply moves out from under every
      // divider, so its gate is held shut across one -- but the gate itself
      // lives in the block that also reports the measurement, so the two can
      // be written and read in one clock domain.
      val cfgBusy = Output(Bool())
      val digClkDiv = Output(UInt(ClockingTile.digClkDivWidth.W))
      val digClkBypassEn = Output(Bool())
      val sbClkDiv = Output(UInt(ClockingTile.sbClkDivWidth.W))
      val sbClkBypassEn = Output(Bool())
      val rxClkFromTxQ = Output(Bool())
      val clkPhaseSel = Output(UInt(ClockingTile.phaseSelWidth.W))
      val clkGateEn = Output(Bool())
      val rxClkGateEn = Output(Bool())
      // The rate link training has negotiated, from the UCIe controller. It
      // is produced on `ucieClk`, which this block does not run on.
      val freqSel = Input(UInt(4.W))
      // Per-PLL lock out of the clocking tile, in `mainClkSel` order.
      val pllLock = Input(UInt(3.W))
      // Holds the rest of the UCIe block in reset. See `ucieRstReq`.
      val ucieRst = Output(Bool())
    })

    // Reset defaults describe a part that has been told nothing: the digital
    // clock on its bypass pin, the main clock on the analog bypass pin, no
    // PLL running, no division, no phase, both clock gates open. That is a
    // design a testbench can drive from its own two clocks without writing a
    // register, which is how every test here starts.
    val (
      mainClkSel,
      pll8En,
      pll12En,
      pll16En,
      txClkDiv,
      txClkPhase,
      digClkDiv,
      digClkBypassEn,
      clkPhaseSel,
      clkGateEn,
      rxClkGateEn
    ) =
      withClockAndReset(clock, reset) {
        (
          RegInit(3.U(ClockingTile.mainClkSelWidth.W)),
          RegInit(false.B),
          RegInit(false.B),
          RegInit(false.B),
          RegInit(0.U(ClockingTile.txClkDivWidth.W)),
          RegInit(0.U(ClockingTile.txClkPhaseWidth.W)),
          RegInit(2.U(ClockingTile.digClkDivWidth.W)),
          RegInit(true.B),
          RegInit(UcieClkRegs.maxPhaseSel.U(ClockingTile.phaseSelWidth.W)),
          RegInit(true.B),
          RegInit(true.B)
        )
      }

    // Translation table: what to program for each rate the controller can
    // negotiate. Software fills it during bringup, because how the PHY is
    // clocked is not fixed -- a part may end up on its PLLs or on a bypass
    // pin, and which PLL serves which rate is a board and bringup question
    // rather than something to bake into the RTL.
    val rateCfg = withClockAndReset(clock, reset) {
      RegInit(
        VecInit(
          Seq.fill(UcieClkRegs.rateCfgs)(0.U.asTypeOf(new ClkRateCfgIO))
        )
      )
    }
    // Low leaves the direct registers in charge, which is how a testbench or
    // a bringup script drives the clocking by hand. High hands it to the
    // table, indexed by whatever rate training has settled on.
    val freqSelAutoEn = withClockAndReset(clock, reset) { RegInit(false.B) }

    // `freqSel` crosses from `ucieClk`. Two flops a bit: it is a held
    // configuration value that changes once per rate negotiation, not a
    // pulse, so the only exposure is a cycle of skew between bits while it
    // settles -- and the table is only consulted once the controller has
    // stopped moving it.
    val freqSelSync = withClockAndReset(clock, reset) {
      val stage0 = RegInit(0.U(4.W))
      val stage1 = RegInit(0.U(4.W))
      stage0 := io.freqSel
      stage1 := stage0
      stage1
    }
    val selected = rateCfg(freqSelSync(2, 0))

    // Lock crosses from the tile's own timing rather than any clock here, so
    // it gets the same two flops. It is a level that settles once per wake-up,
    // not a pulse, so nothing is lost to the delay.
    val pllLockSync = withClockAndReset(clock, reset) {
      val stage0 = RegInit(0.U(3.W))
      val stage1 = RegInit(0.U(3.W))
      stage0 := io.pllLock
      stage1 := stage0
      stage1
    }
    // Two more selects, held like the rest.
    val (sbClkDiv, sbClkBypassEn, rxClkFromTxQ) =
      withClockAndReset(clock, reset) {
        (
          RegInit(2.U(ClockingTile.sbClkDivWidth.W)),
          RegInit(true.B),
          RegInit(false.B)
        )
      }

    // The REPAIRCLK sampling clock's division, a shadow like the other
    // dividers and moved to the tile by an apply. The gate that goes with it
    // is not here: it belongs beside the counters it starts and stops, which
    // are in the block on `ucieClk`.
    val repairClkDiv = withClockAndReset(clock, reset) {
      RegInit(0.U(ClockingTile.repairClkDivWidth.W))
    }

    // The copy the tile runs on. Everything above is what software wrote;
    // nothing reaches the tile until an apply moves it here, and it only moves
    // while the clocks are gated.
    //
    // It resets to the same part the shadow registers describe: the analog
    // bypass pin, no PLL. Zero would select PLL8 with that PLL off -- a dead
    // main clock, and the digital domain on its own bypass pin would not
    // notice.
    val live = withClockAndReset(clock, reset) {
      RegInit(
        (new ClkRateCfgIO).Lit(
          _.mainClkSel -> 3.U,
          _.txClkDiv -> 0.U,
          _.repairClkDiv -> 0.U,
          _.digClkDiv -> 2.U,
          _.pll8En -> false.B,
          _.pll12En -> false.B,
          _.pll16En -> false.B
        )
      )
    }
    val liveSbDiv = withClockAndReset(clock, reset) {
      RegInit(2.U(ClockingTile.sbClkDivWidth.W))
    }
    val liveSbBypass = withClockAndReset(clock, reset) { RegInit(true.B) }
    val liveDigBypass = withClockAndReset(clock, reset) { RegInit(true.B) }

    val pending = Wire(new ClkRateCfgIO)
    pending.mainClkSel := Mux(freqSelAutoEn, selected.mainClkSel, mainClkSel)
    pending.txClkDiv := Mux(freqSelAutoEn, selected.txClkDiv, txClkDiv)
    pending.repairClkDiv :=
      Mux(freqSelAutoEn, selected.repairClkDiv, repairClkDiv)
    pending.digClkDiv := Mux(freqSelAutoEn, selected.digClkDiv, digClkDiv)
    pending.pll8En := Mux(freqSelAutoEn, selected.pll8En, pll8En)
    pending.pll12En := Mux(freqSelAutoEn, selected.pll12En, pll12En)
    pending.pll16En := Mux(freqSelAutoEn, selected.pll16En, pll16En)

    // Which lock the gate waits on. Decided here rather than in the clocking
    // tile: the tile reports each PLL's lock raw and knows nothing about what
    // is selected, so a macro that leaves an unselected PLL's pin undriven --
    // or that has no pin to drive at all on the bypass path -- cannot feed a
    // stale value or an X into the wait.
    //
    // Hence a table with every selector spelled out rather than an index into
    // the lock vector. Selector 3 is the analog bypass pin, which has no lock
    // to report and counts as ready; that is the default, so a selector the
    // tile grows later fails safe the same way rather than indexing off the
    // end of the vector.
    val mainClkLocked = MuxLookup(live.mainClkSel, true.B)(
      Seq(
        0.U -> pllLockSync(0),
        1.U -> pllLockSync(1),
        2.U -> pllLockSync(2)
      )
    )

    // What ends the gate once the new configuration is in, and the delay the
    // `delay` source counts out.
    val ungateSrc = withClockAndReset(clock, reset) {
      RegInit(ClkUngateSrc.pllLock)
    }
    val ungateDelay = withClockAndReset(clock, reset) {
      RegInit(UcieClkRegs.defaultUngateDelay.U(UcieClkRegs.ungateDelayWidth.W))
    }
    // Written to release the gate by hand. Honoured whatever the source is, so
    // a condition that never arrives -- a PLL that will not lock, a delay set
    // longer than intended -- can be recovered from without a hidden timeout
    // in the sequencer. Cleared by the sequencer once it has been acted on.
    val ungateReq = withClockAndReset(clock, reset) { RegInit(false.B) }

    // Resets the UCIe block -- PHY, PhyTest, the controller, the queue ends and
    // `UcieTLRegs` -- but not this register file, which runs on the chip's own
    // clock and reset. That split is the point: configure the clocking here,
    // then restart everything else on it. `UcieTLRegs` does not survive, so
    // this goes before that block is set up.
    //
    // Bringup only. An access in flight to `UcieTLRegs`, or mainband traffic,
    // is in the reset's domain and behind the same crossbar, so a response
    // lost to the reset wedges it. `setup_ucie` asserts this first, with both
    // quiet.
    val ucieRstReq = withClockAndReset(clock, reset) { RegInit(false.B) }
    io.ucieRst := ucieRstReq

    // Applying a configuration: gate, switch under the gate, let the clocks
    // back out. Changing a mux or a divider under a running clock hands the
    // domains behind it a runt. The gate covers all three derived clocks --
    // the main clock select moves what every divider counts.
    val applyReq = withClockAndReset(clock, reset) { RegInit(false.B) }
    val applyBusy = withClockAndReset(clock, reset) { RegInit(false.B) }
    val applyCount = withClockAndReset(clock, reset) {
      RegInit(0.U(UcieClkRegs.ungateDelayWidth.W))
    }
    val cfgGate = withClockAndReset(clock, reset) { RegInit(false.B) }
    val applyPhase = withClockAndReset(clock, reset) {
      RegInit(ClkApplyPhase.idle)
    }

    // `>=` rather than `===` so that a delay shortened mid-wait still ends it,
    // and a delay of zero releases the gate the cycle it is reached.
    val ungateOk = ungateReq || MuxLookup(ungateSrc, false.B)(
      Seq(
        ClkUngateSrc.pllLock -> mainClkLocked,
        ClkUngateSrc.delay -> (applyCount >= ungateDelay)
      )
    )

    withClockAndReset(clock, reset) {
      switch(applyPhase) {
        is(ClkApplyPhase.idle) {
          when(applyReq) {
            applyBusy := true.B
            cfgGate := true.B
            applyCount := 0.U
            applyPhase := ClkApplyPhase.gateIn
          }
        }
        is(ClkApplyPhase.gateIn) {
          // Long enough for the gate to have taken on the slowest clock the
          // tile can produce before anything moves.
          applyCount := applyCount + 1.U
          when(applyCount === (UcieClkRegs.gateDwell - 1).U) {
            live := pending
            liveSbDiv := sbClkDiv
            liveSbBypass := sbClkBypassEn
            liveDigBypass := digClkBypassEn
            applyCount := 0.U
            applyPhase := ClkApplyPhase.ungateWait
          }
        }
        is(ClkApplyPhase.ungateWait) {
          applyCount := applyCount + 1.U
          when(ungateOk) {
            ungateReq := false.B
            cfgGate := false.B
            applyCount := 0.U
            applyPhase := ClkApplyPhase.gateOut
          }
        }
        is(ClkApplyPhase.gateOut) {
          // The same dwell on the way out, so the clocks are back before
          // software is told the apply is done.
          applyCount := applyCount + 1.U
          when(applyCount === (UcieClkRegs.gateDwell - 1).U) {
            applyBusy := false.B
            applyReq := false.B
            applyPhase := ClkApplyPhase.idle
          }
        }
      }
    }

    io.mainClkSel := live.mainClkSel
    io.pll8En := live.pll8En
    io.pll12En := live.pll12En
    io.pll16En := live.pll16En
    io.txClkDiv := live.txClkDiv
    io.txClkPhase := txClkPhase
    io.repairClkDiv := live.repairClkDiv
    io.cfgBusy := applyBusy || cfgGate
    io.digClkDiv := live.digClkDiv
    io.digClkBypassEn := liveDigBypass
    io.sbClkDiv := liveSbDiv
    io.sbClkBypassEn := liveSbBypass
    io.rxClkFromTxQ := rxClkFromTxQ
    io.clkPhaseSel := clkPhaseSel
    io.clkGateEn := clkGateEn && !cfgGate
    io.rxClkGateEn := rxClkGateEn

    val regmap: Seq[(Int, Seq[RegField])] = withClockAndReset(clock, reset) {
      val scalars = Seq(
        toRegFieldRw(mainClkSel, "mainClkSel"),
        toRegFieldRw(pll8En, "pll8En"),
        toRegFieldRw(pll12En, "pll12En"),
        toRegFieldRw(pll16En, "pll16En"),
        toRegFieldRw(txClkDiv, "txClkDiv"),
        toRegFieldRw(txClkPhase, "txClkPhase"),
        toRegFieldRw(digClkDiv, "digClkDiv"),
        toRegFieldRw(digClkBypassEn, "digClkBypassEn"),
        toRegFieldRw(clkPhaseSel, "clkPhaseSel"),
        toRegFieldRw(clkGateEn, "clkGateEn"),
        toRegFieldRw(rxClkGateEn, "rxClkGateEn"),
        toRegFieldRw(freqSelAutoEn, "freqSelAutoEn"),
        toRegFieldR(freqSelSync, "freqSelObserved"),
        toRegFieldRw(sbClkDiv, "sbClkDiv"),
        toRegFieldRw(sbClkBypassEn, "sbClkBypassEn"),
        toRegFieldRw(rxClkFromTxQ, "rxClkFromTxQ"),
        toRegFieldRw(repairClkDiv, "repairClkDiv"),
        toRegFieldRw(applyReq, "clkCfgApply"),
        toRegFieldR(applyBusy, "clkCfgBusy"),
        toRegFieldRw(ungateSrc, "clkCfgUngateSrc"),
        toRegFieldRw(ungateDelay, "clkCfgUngateDelay"),
        toRegFieldRw(ungateReq, "clkCfgUngateReq"),
        toRegFieldR(pllLockSync, "pllLockObserved"),
        toRegFieldRw(ucieRstReq, "ucieRst")
      )
      // The table sits at a fixed slot so that adding a scalar does not move
      // every entry -- which means adding one too many silently lands on top
      // of `rateCfg_0` instead. This has overlapped once before; fail
      // elaboration rather than hand out a map with two registers at one
      // address.
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

/** One rate's worth of analog clocking configuration.
  *
  * Everything that decides what the lanes run at, and nothing that does not:
  * the sampling phase is a training result rather than a property of the rate,
  * so it stays in its own register.
  */
/** Phases of a clock configuration apply: shut the gate, switch under it, wait
  * out the new source's wake-up, then let the clocks back out.
  */
object ClkApplyPhase extends ChiselEnum {
  val idle, gateIn, ungateWait, gateOut = Value
}

/** What ends the clock gate at the end of an apply.
  *
  * The gate has to stay shut across a newly enabled PLL's wake-up, which is far
  * longer than the few cycles the gate itself needs, and how long that is
  * depends on the part. So the release is a choice rather than a constant.
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

  /** `clkCfgUngateDelay` cycles of this block's clock, counted from the switch.
    * For a part whose lock is not trustworthy, or not wired.
    */
  val delay = Value(2.U(2.W))
}

class ClkRateCfgIO extends Bundle {
  val mainClkSel = UInt(ClockingTile.mainClkSelWidth.W)
  val txClkDiv = UInt(ClockingTile.txClkDivWidth.W)

  /** The MBINIT.REPAIRCLK sampling clock's division of the same main clock.
    *
    * It rides in the rate table beside `txClkDiv` because the oversample ratio
    * REPAIRCLK measures at is the ratio of the two -- `2^(txClkDiv -
    * repairClkDiv)` -- so a rate entry that set one without the other would let
    * software and the hardware disagree about what a count means.
    */
  val repairClkDiv = UInt(ClockingTile.repairClkDivWidth.W)
  val digClkDiv = UInt(ClockingTile.digClkDivWidth.W)
  val pll8En = Bool()
  val pll12En = Bool()
  val pll16En = Bool()
}

object UcieClkRegs {

  /** Rates the translation table holds, one per `SpeedMode` code. */
  val rateCfgs = 8

  /** Cycles the gate is held before the configuration moves, and again after it
    * is let back out. Covers the gate reaching the slowest clock the tile can
    * produce; it has nothing to do with a PLL waking up.
    */
  val gateDwell = 4

  /** Width of `clkCfgUngateDelay`, in cycles of this block's clock. */
  val ungateDelayWidth = 16

  /** Every tap of the global delay line switched on, which is where it comes
    * up: the longest code, not the shortest. Training moves it from there.
    */
  val maxPhaseSel = (BigInt(1) << ClockingTile.phaseSelWidth) - 1

  /** What `ClkUngateSrc.delay` counts out unless software says otherwise. Sized
    * to clear a PLL wake-up with room to spare at any plausible chip digital
    * clock rather than to be tight at one.
    */
  val defaultUngateDelay = 1024

  /** Which 8-byte slot the table starts at, clear of the scalar registers above
    * it. Fixed rather than counted so that adding a scalar does not silently
    * move every table entry.
    */
  val rateCfgBase = 32

  /** Where this block sits above `UcieTLParams.address`. Clear of the main
    * block, which runs to `ucieTLRegionSize` plus the UCIe digital region.
    */
  val offset = 0x10000
}

class UcieTLRegs(
    params: UcieTLParams,
    beatBytes: Int,
    ucieRegParams: UcieRegParams,
    ucieRetryW: Int = 10
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
  val ucieTLRegionSize = 0x4000
  val device = new SimpleDevice("ucie_control", Seq("ucbbar,ucie"))
  val node = TLRegisterNode(
    Seq(
      AddressSet(
        params.address,
        ucieTLRegionSize + ucieRegParams.allocation.regionSize - 1
      )
    ),
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
        params.bitCounterWidth,
        retryW = ucieRetryW
      )
    )

    val ucieBlockIo = IO(new UcieRegBlockIO(ucieRegParams))

    val regmap = withClockAndReset(clock, reset) {
      // TODO: Remove and add necessary registers
      io.test := DontCare

      // pwrGood defaults set so the link can train without sw intervention
      // linkReset only clears the non-sticky register state.
      val ucieLinkReset = RegInit(false.B)
      val uciePwrGood = RegInit(true.B)
      val ucieRetryTrainingAmt = RegInit(0.U(ucieRetryW.W))
      io.ucieCtrl.linkReset := ucieLinkReset
      io.ucieCtrl.pwrGood := uciePwrGood
      io.ucieCtrl.retryTrainingAmt := ucieRetryTrainingAmt
      // MMIO registers.
      val testTarget = RegInit(TestTarget.mainband)
      val txTestMode = RegInit(TxTestMode.manual)
      val txDataMode = RegInit(DataMode.finite)
      val txLfsrSeed = RegInit(
        VecInit(
          Seq.fill(PhyTest.numTestLanes(params.numLanes))(
            1.U(io.test.txLfsrSeed(0).getWidth.W)
          )
        )
      )
      val txDividerRst = RegInit(false.B)
      val rxDividerRst = RegInit(false.B)
      val txDatapathRst = Wire(DecoupledIO(UInt(1.W)))
      val txFsmRst = Wire(DecoupledIO(UInt(1.W)))
      val txExecute = Wire(DecoupledIO(UInt(1.W)))
      val txWriteChunk = Wire(DecoupledIO(UInt(1.W)))
      val txManualRepeatPeriod =
        RegInit(0.U(io.test.txManualRepeatPeriod.getWidth.W))
      val txPacketsToSend =
        RegInit(0.U(io.test.txPacketsToSend.getWidth.W))
      // Three words each, repeating with `txClkPatternPeriod`. See
      // `PhyTest.ClkPatternWords`: a 48 bit pattern on a 32 bit lane word
      // repeats every three, and everything else a clock lane sends fits in
      // one, which is what the period comes up as.
      val txClkP = RegInit(
        VecInit(Seq.fill(PhyTest.ClkPatternWords)(0.U(32.W)))
      )
      val txClkN = RegInit(
        VecInit(Seq.fill(PhyTest.ClkPatternWords)(0.U(32.W)))
      )
      val txClkPatternPeriod = RegInit(
        1.U(log2Ceil(PhyTest.ClkPatternWords + 1).W)
      )
      val txClkPatternOnTrack = RegInit(false.B)
      val txValid = RegInit(0.U(32.W))
      val txDataLaneGroup =
        RegInit(0.U(io.test.txDataLaneGroup.getWidth.W))
      val txDataOffset = RegInit(0.U(io.test.txDataOffset.getWidth.W))
      val txDataChunkIn0 = RegInit(0.U(64.W))
      val txDataChunkIn1 = RegInit(0.U(64.W))
      val rxDataMode = RegInit(DataMode.infinite)
      val rxLfsrSeed = RegInit(
        VecInit(
          Seq.fill(PhyTest.numTestLanes(params.numLanes))(
            1.U(io.test.rxLfsrSeed(0).getWidth.W)
          )
        )
      )
      val rxLfsrValid = RegInit(0.U(32.W))
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
        // `DataIN[bitrev5(t)]` in UI `t`. Pre-applying that same permutation
        // here cancels it, putting the word on the wire in plain bit order;
        // software can still program any other mapping.
        for (i <- 0 until 32) {
          w.shuffler(i) := Phy.treeBitOrder(i).U(5.W)
        }
        w.sample_negedge := false.B
        w.delay := 0.U
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
        // The RX tile deserializes through the mirror image of the TX tile's
        // adjacent-pairing tree, so the bit it puts in `dout(j)` is the one
        // received in UI `bitrev5(j)`. Applying that same permutation here
        // cancels it, so the digital side reads the word in the order it came
        // off the wire; software can still program any other mapping.
        for (i <- 0 until 32) {
          w.shuffler(i) := Phy.treeBitOrder(i).U(5.W)
        }
        w.sample_negedge := false.B
        w.delay := 0.U
        w
      })))
      // MBINIT.REPAIRCLK measurement controls. `windowWords` and `gapThresh`
      // are both functions of the oversample ratio, which is set by
      // `txClkDiv` and `repairClkDiv` in the clock block -- software owns
      // keeping the three consistent, and `ClkRepairExpect` is where the
      // arithmetic that ties them together lives.
      //
      // The defaults cover a whole number of pattern periods at every ratio
      // from 2 to 8, and a gap threshold for a ratio of 4.
      val repairWindowWords = RegInit(
        UcieTLRegs.defaultRepairWindow.U(ClkRepair.WordCountWidth.W)
      )
      val repairGapThresh = RegInit(
        UcieTLRegs.defaultRepairGapThresh.U(ClkRepair.CounterWidth.W)
      )
      // Opens a measurement window, and the whole control surface for one.
      //
      // It lives here rather than with the other clocking controls on purpose.
      // A reader opens a window and then polls `repairDone`, and the two have
      // to be ordered: from a different block on a different clock the poll
      // can beat the gate across and read the PREVIOUS window's answer, and
      // then shut the gate again before anything has been counted. Same clock,
      // same write ordering, no race.
      val repairClkEn = RegInit(false.B)
      val repairCapLane = RegInit(0.U(log2Ceil(ClkRepair.Lanes).W))
      val repairCapOffset = RegInit(
        0.U(log2Ceil(ClkRepair.CaptureDepth).W)
      )
      // Thresholds the counters are judged against.
      //
      // One band per counter rather than one per lane: during REPAIRCLK all
      // three lanes carry the same word (`PatternWriter` sends `clkRepairWord`
      // on clkP, clkN and track alike), so they have one expectation between
      // them.
      //
      // Registers rather than constants because the right band depends on the
      // oversample ratio, on how much the front end's auto-zero handover costs
      // in spurious transitions, and on how far the two dies' clocks have
      // drifted -- none of which is an RTL decision.
      val repairBands = RegInit({
        val w = Wire(new ClkRepairBandsIO)
        val d = ClkRepairExpect.defaultBands
        w.transMin := d.transMin.U
        w.transMax := d.transMax.U
        w.onesMin := d.onesMin.U
        w.onesMax := d.onesMax.U
        w.gapsMin := d.gapsMin.U
        w.gapsMax := d.gapsMax.U
        w.maxRunMin := d.maxRunMin.U
        w.maxRunMax := d.maxRunMax.U
        w
      })

      val repairctl = RegInit(VecInit(Seq.fill(ClkRepair.Lanes)({
        val w = Wire(new RxRepairDigitalCtlIO)
        w.delay := 0.U
        // Same default as every other lane's: cancel the tile's serdes tree so
        // the tap's word reads in the order it came off the wire. Load bearing
        // for the measurement, not just for legibility -- see
        // `RxRepairDigitalCtlIO`.
        for (i <- 0 until 32) {
          w.shuffler(i) := Phy.treeBitOrder(i).U(5.W)
        }
        w
      })))

      // DEBUG CIRCUITRY
      // Everything below drives the tester's debug hardware rather than the
      // link: the observation bumps and the TX data debug lane.
      //
      // Pad drivers for the observation bumps: TX clock, RX clock, RX data,
      // clock mux.
      val debugDriverctl = RegInit(VecInit(Seq.fill(PhyTest.NumDebugDrivers)({
        val w = Wire(new PadDriverCtlIO)
        w.pu_ctl := 0.U
        w.pd_ctl := 0.U
        w.en := false.B
        w.en_b := true.B
        w
      })))
      // Which clock the clock mux bump watches; see the mux input list in
      // PhyTest for what each index selects.
      val debugClkMuxSel = RegInit(0.U(io.test.clkMuxSel.getWidth.W))
      // Which RX lane, and which bit of its deserialized word, the RX data bump
      // watches.
      val debugRxLane = RegInit(0.U(io.test.rxDebugLane.getWidth.W))
      val debugRxBit = RegInit(0.U(io.test.rxDebugBit.getWidth.W))
      val debugTxctl = RegInit({
        val w = Wire(new TxLaneDigitalCtlIO)
        // Every driver segment off out of reset, so a lane stays quiet until
        // software brings it up.
        w.tile := TxLaneCtlIO.off
        for (i <- 0 until 32) {
          w.shuffler(i) := i.U(5.W)
        }
        w.sample_negedge := false.B
        w.delay := 0.U
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

      txDatapathRst.ready := true.B
      txFsmRst.ready := true.B
      txExecute.ready := true.B
      txWriteChunk.ready := true.B
      rxDatapathRst.ready := true.B
      rxFsmRst.ready := true.B

      // Holds a one-cycle register write out for `UcieTLRegs.rstStrobeCycles`
      // cycles. The datapath resets cross into a divided clock domain a
      // quarter the rate of this one and `txFsmRst`/`rxFsmRst` into the UCIe
      // clock domain, so a single-cycle strobe is uncomfortably short.
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
      io.test.txDividerRst := applyShift(txDividerRst)
      io.test.rxDividerRst := applyShift(rxDividerRst)
      io.test.txDatapathRst := applyShift(stretched(txDatapathRst.valid))
      io.test.txFsmRst := applyShift(stretched(txFsmRst.valid))
      io.test.txExecute := applyShift(txExecute.valid)
      io.test.txManualRepeatPeriod := applyShift(txManualRepeatPeriod)
      io.test.txPacketsToSend := applyShift(txPacketsToSend)
      io.test.txClkP := applyShift(txClkP)
      io.test.txClkN := applyShift(txClkN)
      io.test.txClkPatternPeriod := applyShift(txClkPatternPeriod)
      io.test.txClkPatternOnTrack := applyShift(txClkPatternOnTrack)
      io.test.txValid := applyShift(txValid)
      io.test.rxDataMode := applyShift(rxDataMode)
      io.test.rxLfsrSeed := applyShift(rxLfsrSeed)
      io.test.rxLfsrValid := applyShift(rxLfsrValid)
      io.test.rxDatapathRst := applyShift(stretched(rxDatapathRst.valid))
      io.test.rxFsmRst := applyShift(stretched(rxFsmRst.valid))
      io.test.rxPacketsToReceive := applyShift(rxPacketsToReceive)
      io.test.rxPauseCounters := applyShift(rxPauseCounters)
      io.test.rxDataLane := applyShift(rxDataLane)
      io.test.rxDataOffset := applyShift(rxDataOffset)
      io.test.sb.txPacket := applyShift(sbTxPacket)
      io.test.sb.txSend := applyShift(sbTxSend.valid)
      io.test.sb.rxPop := applyShift(sbRxPop.valid)
      io.test.sb.rxRst := applyShift(sbRxRst.valid)
      // Moving valid onto another lane is a test function, not something the
      // UCIe spec asks the link to do, so these go to PhyTest rather than to
      // the PHY.
      io.test.txValidLaneSel := applyShift(txValidLaneSel)
      io.test.rxValidLaneSel := applyShift(rxValidLaneSel)
      io.phy.txctl := applyShift(VecInit(txctl.take(params.numLanes + 4)))
      io.phy.rxctl := applyShift(VecInit(rxctl.take(params.numLanes + 4)))
      io.phy.repairctl := applyShift(repairctl)
      io.repair.windowWords := applyShift(repairWindowWords)
      io.repair.gapThresh := applyShift(repairGapThresh)
      io.repair.capLane := applyShift(repairCapLane)
      io.repair.capOffset := applyShift(repairCapOffset)
      // Either source opens a window: the controller during MBINIT, or this
      // register with no controller present at all.
      val repairWindowOpen = repairClkEn || io.repairClkEnReq
      io.repairClkEn := repairWindowOpen

      // `done` has to stop being the last window's answer the instant a new
      // one is asked for. Clearing the counters takes a few cycles to reach
      // the repair clock domain and a few more to come back, so the flag is
      // raised here -- in the same cycle as the write, which is what makes it
      // race free -- and held until the clear has been seen coming back.
      // "Has not started" is not "has finished".
      val repairArmPending = RegInit(false.B)
      val repairWindowOpenPrev = RegNext(repairWindowOpen, false.B)
      when(repairWindowOpen && !repairWindowOpenPrev) {
        repairArmPending := true.B
      }.elsewhen(!io.repair.done) {
        repairArmPending := false.B
      }
      val repairDone = io.repair.done && !repairArmPending

      // The verdict, for the controller and for a readable register. A lane
      // passes when every counter lands inside its band; `done` says the
      // window is complete, without which none of them mean anything.
      val repairLaneOk = VecInit((0 until ClkRepair.Lanes).map { i =>
        val o = io.repair.obs(i)
        o.transitions >= repairBands.transMin &&
        o.transitions <= repairBands.transMax &&
        o.ones >= repairBands.onesMin &&
        o.ones <= repairBands.onesMax &&
        o.gaps >= repairBands.gapsMin &&
        o.gaps <= repairBands.gapsMax &&
        o.maxRun >= repairBands.maxRunMin &&
        o.maxRun <= repairBands.maxRunMax
      })
      require(
        ClkRepairStatus.Lanes == ClkRepair.Lanes,
        s"the controller expects ${ClkRepairStatus.Lanes} REPAIRCLK lanes " +
          s"but the PHY measures ${ClkRepair.Lanes}"
      )
      io.repairStatus.done := repairDone
      io.repairStatus.laneOk := repairLaneOk
      // The last lane control slot belongs to the tester's loopback pair, which
      // lives in PhyTest rather than in the PHY.
      io.test.loopbackTxctl := applyShift(txctl(params.numLanes + 4))
      io.test.loopbackRxctl := applyShift(rxctl(params.numLanes + 4))

      // Debug circuitry: the observation bump drivers and the TX data debug
      // lane, all owned by PhyTest.
      io.test.driverctl := applyShift(debugDriverctl)
      io.test.clkMuxSel := applyShift(debugClkMuxSel)
      io.test.rxDebugLane := applyShift(debugRxLane)
      io.test.rxDebugBit := applyShift(debugRxBit)
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

      // String name should always be camel case with an underscore to separate indices.
      // Adjacent indices should be contiguous in memory. Increasing index should correspond to increasing memory address.
      val mmioRegs = Seq(
        toRegFieldRw(testTarget, "testTarget"),
        toRegFieldRw(txTestMode, "txTestMode"),
        toRegFieldRw(txDataMode, "txDataMode")
      ) ++ (0 until PhyTest.numTestLanes(params.numLanes)).map((i: Int) => {
        toRegFieldRw(txLfsrSeed(i), s"txLfsrSeed_$i")
      }) ++ Seq(
        toRegFieldRw(txDividerRst, "txDividerRst"),
        toRegFieldRw(rxDividerRst, "rxDividerRst"),
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
        toRegFieldRw(txClkPatternPeriod, "txClkPatternPeriod"),
        toRegFieldRw(txClkPatternOnTrack, "txClkPatternOnTrack")
      ) ++ (0 until PhyTest.ClkPatternWords).flatMap((i: Int) =>
        Seq(
          toRegFieldRw(txClkP(i), s"txClkP_$i"),
          toRegFieldRw(txClkN(i), s"txClkN_$i")
        )
      ) ++ Seq(
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
          toRegFieldRw(txctl(i).sample_negedge, s"txctl_${i}_sampleNegedge"),
          toRegFieldRw(txctl(i).delay, s"txctl_${i}_delay")
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
          toRegFieldRw(rxctl(i).delay, s"rxctl_${i}_rxDelay")
        )
      }) ++ Seq(
        toRegFieldRw(repairWindowWords, "repairWindowWords"),
        toRegFieldRw(repairGapThresh, "repairGapThresh"),
        toRegFieldRw(repairCapLane, "repairCapLane"),
        toRegFieldRw(repairCapOffset, "repairCapOffset"),
        toRegFieldRw(repairClkEn, "repairClkEn"),
        toRegFieldR(applyShift(repairWindowOpen), "repairClkEnObserved"),
        toRegFieldR(applyShift(repairDone), "repairDone"),
        toRegFieldR(applyShift(io.repair.wordsObserved), "repairWordsObserved"),
        toRegFieldR(applyShift(io.repair.capWord), "repairCapWord"),
        toRegFieldR(applyShift(repairLaneOk.asUInt), "repairLaneOk"),
        toRegFieldRw(repairBands.transMin, "repairTransMin"),
        toRegFieldRw(repairBands.transMax, "repairTransMax"),
        toRegFieldRw(repairBands.onesMin, "repairOnesMin"),
        toRegFieldRw(repairBands.onesMax, "repairOnesMax"),
        toRegFieldRw(repairBands.gapsMin, "repairGapsMin"),
        toRegFieldRw(repairBands.gapsMax, "repairGapsMax"),
        toRegFieldRw(repairBands.maxRunMin, "repairMaxRunMin"),
        toRegFieldRw(repairBands.maxRunMax, "repairMaxRunMax")
      ) ++ (0 until ClkRepair.Lanes).flatMap((i: Int) => {
        Seq(
          toRegFieldR(
            applyShift(io.repair.obs(i).transitions),
            s"repairTransitions_$i"
          ),
          toRegFieldR(applyShift(io.repair.obs(i).ones), s"repairOnes_$i"),
          toRegFieldR(applyShift(io.repair.obs(i).gaps), s"repairGaps_$i"),
          toRegFieldR(applyShift(io.repair.obs(i).maxRun), s"repairMaxRun_$i")
        )
      }) ++ (0 until ClkRepair.Lanes).flatMap((i: Int) => {
        Seq(
          toRegFieldRw(repairctl(i).delay, s"repairctl_${i}_delay")
        ) ++ (0 until 32).map((j: Int) =>
          toRegFieldRw(repairctl(i).shuffler(j), s"repairctl_${i}_shuffler_$j")
        )
      }) ++ Seq(
        toRegFieldRw(debugTxTestMode, "debugTxTestMode"),
        toRegFieldRw(debugTxDataMode, "debugTxDataMode"),
        toRegFieldRw(debugTxLfsrSeed, s"debugTxLfsrSeed"),
        RegField.w(1, debugTxFsmRst, RegFieldDesc("debugTxFsmRst", "")),
        RegField.w(1, debugTxExecute, RegFieldDesc("debugTxExecute", "")),
        toRegFieldRw(debugTxManualRepeatPeriod, "debugTxManualRepeatPeriod"),
        toRegFieldRw(debugTxPacketsToSend, "debugTxPacketsToSend")
      ) ++ (0 until 16).map((i: Int) => {
        toRegFieldRw(debugData(i), s"debugData_${i}")
      }) ++ (0 until debugDriverctl.length).map((i: Int) => {
        toRegFieldRw(debugDriverctl(i), s"debugDriverctl_${i}")
      }) ++ Seq(
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
        ),
        toRegFieldRw(debugClkMuxSel, "debugClkMuxSel"),
        toRegFieldRw(debugRxLane, "debugRxLane"),
        toRegFieldRw(debugRxBit, "debugRxBit"),
        toRegFieldRw(txValid, "txValid"),
        toRegFieldRw(rxLfsrValid, "rxLfsrValid"),
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
        toRegFieldR(sbTlRxOverflow, "sbTlRxOverflow"),
        toRegFieldRw(ucieLinkReset, "ucieLinkReset"),
        toRegFieldRw(uciePwrGood, "uciePwrGood"),
        toRegFieldRw(ucieRetryTrainingAmt, "ucieRetryTrainingAmt")
      )

      mmioRegs.zipWithIndex.map({
        case (f, i) => {
          i * 8 -> Seq(f)
        }
      })
    }

    // Spec-defined UCIe digital registers. Added after UCIe TL Regs.
    val ucieRegmap = withClockAndReset(clock, reset) {
      val (entries, _, _) =
        UcieRegBlock.build(ucieBlockIo, reset, ucieRegParams, ucieTLRegionSize)
      entries
    }
    node.regmap((regmap ++ ucieRegmap): _*)
  }
}

object UcieTL {
  val dataBits = 256

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

  // Main digital clock node.
  val digitalClockNode = ClockSinkNode(Seq(ClockSinkParameters()))
  // The chip's own digital clock. Runs before the PHY's do, which is what the
  // clock source registers need. Taken in on a sink and handed to the clock
  // register block through a source, as the UCIe digital clock is.
  val chipDigitalClockNode = ClockSinkNode(Seq(ClockSinkParameters()))
  val chipClockSourceNode = ClockSourceNode(Seq(ClockSourceParameters()))
  val ucieDigitalClockNode = ClockSourceNode(Seq(ClockSourceParameters()))

  val ucieRegParams = UcieDigitalTopParams
    .default()
    .regs
    .copy(
      baseAddress = params.ucieRegsBaseAddress,
      numModules = 1,
      includeRegNode = false,
      includeInterruptNode = false
    )
  val ucieDigitalParams =
    UcieDigitalTopParams.default().copy(regs = ucieRegParams)
  val ucieDigitalLazy: UcieDigitalTop =
    LazyModule(new UcieDigitalTop(ucieDigitalParams))
  val regs = LazyModule(
    new UcieTLRegs(
      params,
      beatBytes,
      ucieRegParams,
      ucieDigitalParams.logPhy.retryW
    )
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
  val clkRegs = LazyModule(new UcieClkRegs(params, beatBytes))
  clkRegs.clockNode := chipClockSourceNode

  // Attached separately rather than behind a crossbar here: `regNode` is a
  // `TLRegisterNode` in the chiplet interface, so the two blocks come out as
  // two nodes and whoever attaches them decides how to fan out.
  val regNode = regs.node
  val clkRegNode = clkRegs.node
  regs.clockNode := ucieDigitalClockNode

  override lazy val module = new UcieTLImpl
  class UcieTLImpl extends LazyRawModuleImp(this) {
    childClock := digitalClockNode.in(0)._1.clock
    childReset := digitalClockNode.in(0)._1.reset
    override def provideImplicitClockToLazyChildren = true

    // Both blocks, at their own bases, so generated collateral sees one map.
    val regmap = regs.module.regmap ++ clkRegs.module.regmap.map {
      case (off, fields) => (off + UcieClkRegs.offset) -> fields
    }
    val io = IO(new UcieBumpsIO(params.numLanes))

    // PHY
    val phy = Module(new Phy(params.numLanes)(params.includeDefaultModels))
    io.phy <> phy.io.top
    // The block reset, plus the software one that outlives it. Assertion is
    // asynchronous to every domain behind it and release is synchronized by
    // the same reset synchronizers the block reset already goes through.
    phy.io.clkRst.reset :=
      digitalClockNode.in(0)._1.reset.asBool || clkRegs.module.io.ucieRst
    chipClockSourceNode.out(0)._1.clock := chipDigitalClockNode.in(0)._1.clock
    chipClockSourceNode.out(0)._1.reset := chipDigitalClockNode.in(0)._1.reset
    ucieDigitalClockNode.out(0)._1.clock := phy.io.clkRst.ucieClk
    ucieDigitalClockNode.out(0)._1.reset := phy.io.clkRst.ucieRst
    phy.io.regs <> regs.module.io.phy

    // TEST HARNESS
    val test = withClockAndReset(
      phy.io.clkRst.ucieClk,
      phy.io.clkRst.ucieRst
    ) {
      Module(
        new PhyTest(
          params.bufferDepthPerLane,
          params.numLanes,
          params.bitCounterWidth,
          queueParams = params.queueParams
        )(params.includeDefaultModels)
      )
    }
    io.debug <> test.io.bumps
    test.io.debug <> phy.io.debug
    phy.io.clkRst.txDividerRstb := test.io.txDividerRstb
    phy.io.clkRst.rxDividerRstb := test.io.rxDividerRstb
    phy.io.clkRst.mainClkSel := clkRegs.module.io.mainClkSel
    phy.io.clkRst.pll8En := clkRegs.module.io.pll8En
    phy.io.clkRst.pll12En := clkRegs.module.io.pll12En
    phy.io.clkRst.pll16En := clkRegs.module.io.pll16En
    phy.io.clkRst.txClkDiv := clkRegs.module.io.txClkDiv
    phy.io.clkRst.txClkPhase := clkRegs.module.io.txClkPhase
    phy.io.clkRst.repairClkDiv := clkRegs.module.io.repairClkDiv
    // Held shut across a clock configuration apply: that moves the main clock
    // select out from under every divider, and this branch counts the same
    // main clock.
    phy.io.clkRst.repairClkEn :=
      regs.module.io.repairClkEn && !clkRegs.module.io.cfgBusy
    phy.io.repair <> regs.module.io.repair
    phy.io.clkRst.digClkDiv := clkRegs.module.io.digClkDiv
    phy.io.clkRst.digClkBypassEn := clkRegs.module.io.digClkBypassEn
    phy.io.clkRst.sbClkDiv := clkRegs.module.io.sbClkDiv
    phy.io.clkRst.sbClkBypassEn := clkRegs.module.io.sbClkBypassEn
    phy.io.clkRst.rxClkFromTxQ := clkRegs.module.io.rxClkFromTxQ
    phy.io.clkRst.clkPhaseSel := clkRegs.module.io.clkPhaseSel
    phy.io.clkRst.clkGateEn := clkRegs.module.io.clkGateEn
    phy.io.clkRst.rxClkGateEn := clkRegs.module.io.rxClkGateEn
    phy.io.clkRst.txDatapathRst := test.io.txDatapathRst
    phy.io.clkRst.rxDatapathRst := test.io.rxDatapathRst
    test.io.regs <> regs.module.io.test

    // One controller select, plus a per-band mode that only means anything
    // while PhyTest is the controller. Only one band may carry TileLink at a
    // time -- they share the credit counters and the A/D buffers -- so if both
    // modes say `tl` the mainband wins and the sideband stays idle.
    val selUcie = regs.module.io.controllerSel === ControllerSel.ucie
    val selMbTl = !selUcie && regs.module.io.mainbandMode === BandMode.tl
    val selSbTl =
      !selUcie && !selMbTl && regs.module.io.sidebandMode === BandMode.tl

    // Async crossings
    val txTestFifo =
      Module(new AsyncQueue(new TxIO(params.numLanes), params.queueParams))
    txTestFifo.io.enq_clock := phy.io.clkRst.ucieClk
    txTestFifo.io.enq_reset := phy.io.clkRst.ucieRst
    txTestFifo.io.deq_clock := phy.io.clkRst.txDivClk
    txTestFifo.io.deq_reset := phy.io.clkRst.txDatapathRstSync
    // TODO: should deq ready be synchronous to deq clock?
    // txTestFifo crosses both the phytest and ucie mainband tx signals to the PHY.
    txTestFifo.io.deq.ready := !selMbTl

    val rxTestFifo =
      Module(new AsyncQueue(new RxIO(params.numLanes), params.queueParams))
    rxTestFifo.io.enq.bits := phy.io.rx
    rxTestFifo.io.enq.valid := !selMbTl
    rxTestFifo.io.enq_clock := phy.io.clkRst.rxDivClk
    rxTestFifo.io.enq_reset := phy.io.clkRst.rxDatapathRstSync
    rxTestFifo.io.deq_clock := phy.io.clkRst.ucieClk
    rxTestFifo.io.deq_reset := phy.io.clkRst.ucieRst

    val ucieDigital =
      withClockAndReset(phy.io.clkRst.ucieClk, phy.io.clkRst.ucieRst) {
        ucieDigitalLazy.module
      }
    // The rate training negotiated, translated by the table in the clock
    // register block into the analog controls that realise it. Which entry
    // means what is software's to decide, so a change in how the part is
    // clocked during bringup does not need an RTL change.
    clkRegs.module.io.freqSel := ucieDigital.io.phyFacingIo.ctrl.freqSel.asUInt
    // MBINIT.REPAIRCLK asking for a measurement window. Only while the UCIe
    // controller owns the link: with PhyTest in charge the window is opened
    // by `repairClkEn` over MMIO instead, and a controller left mid-MBINIT
    // should not be holding the gate open underneath it.
    ucieDigital.io.phyFacingIo.repairStatus := regs.module.io.repairStatus
    regs.module.io.repairClkEnReq :=
      selUcie && ucieDigital.io.phyFacingIo.ctrl.repairClkEn
    // In `mainClkSel` order, so the block can index it with that selector.
    clkRegs.module.io.pllLock := Cat(
      phy.io.clkRst.pll16Lock,
      phy.io.clkRst.pll12Lock,
      phy.io.clkRst.pll8Lock
    )
    ucieDigital.io.regBlockIo.foreach { rb => regs.module.ucieBlockIo <> rb }
    ucieDigital.io.ctrl <> regs.module.io.ucieCtrl
    // phyFacing TX: mux PhyTest vs ucieDigital into txTestFifo.enq (both ucieClk).
    val digiToPhyTx = ucieDigital.io.phyFacingIo.mainbandLink.tx
    val digiTxAsTxIo = Wire(new TxIO(params.numLanes))
    digiTxAsTxIo.data := digiToPhyTx.bits.data
    digiTxAsTxIo.valid := digiToPhyTx.bits.valid
    digiTxAsTxIo.track := digiToPhyTx.bits.trk
    digiTxAsTxIo.clkp := digiToPhyTx.bits.clkP
    digiTxAsTxIo.clkn := digiToPhyTx.bits.clkN
    txTestFifo.io.enq.valid := Mux(selUcie, digiToPhyTx.valid, test.io.tx.valid)
    txTestFifo.io.enq.bits := Mux(selUcie, digiTxAsTxIo, test.io.tx.bits)
    test.io.tx.ready := txTestFifo.io.enq.ready && !selUcie
    digiToPhyTx.ready := txTestFifo.io.enq.ready && selUcie

    // phyFacing RX: rxTestFifo.deq routed to PhyTest or ucieDigital by sel.
    val digiToPhyRx = ucieDigital.io.phyFacingIo.mainbandLink.rx
    digiToPhyRx.bits.data := rxTestFifo.io.deq.bits.data
    digiToPhyRx.bits.valid := rxTestFifo.io.deq.bits.valid
    digiToPhyRx.bits.trk := rxTestFifo.io.deq.bits.track
    // TODO: RxIO has no clkp/clkn; use the forwarded-clock patterns until sampled clkP/clkN exist.
    // Need them for training and link bringup.
    digiToPhyRx.bits.clkP := "h55555555".U
    digiToPhyRx.bits.clkN := "haaaaaaaa".U
    digiToPhyRx.valid := rxTestFifo.io.deq.valid && selUcie
    test.io.rx.bits := rxTestFifo.io.deq.bits
    test.io.rx.valid := rxTestFifo.io.deq.valid && !selUcie
    rxTestFifo.io.deq.ready := Mux(selUcie, digiToPhyRx.ready, test.io.rx.ready)

    // Sideband TL link: with the sideband in `tl` mode the framed TL packets a
    // `tl` mainband spreads across its lanes are shifted out of the sideband
    // instead, one frame at a time. The sideband is source synchronous, so this
    // runs in the digital clock domain alongside the rest of the TL path and
    // forwards a gated copy of that clock; the frames need no async crossing.
    val sbTlFrameBits = UcieTL.frameBits(params.creditBits)
    val sbTl = withClockAndReset(childClock, childReset) {
      Module(new SidebandSerial(sbTlFrameBits, params.sbRxQueueDepth))
    }
    // Held in reset otherwise: the receiver has no framing beyond its bit
    // counter, so it has to start counting at the first bit the partner sends
    // after both dies enter the mode.
    sbTl.io.txRst := !selSbTl
    sbTl.io.rxRst := !selSbTl

    // Sideband bumps: ucie uses ucieDigital, a `tl` sideband uses the TL link,
    // and otherwise PhyTest's tester drives. Rx goes to all of them; PhyTest
    // holds its own tester in reset when its sideband is not in `manual`.
    val digiSb = ucieDigital.io.phyFacingIo.sidebandLink
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
      val managerTl = managerNode.in(0)._1

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
        ucieManagerTlA.opcode.getWidth >= managerTl.a.bits.opcode.getWidth
      )
      require(ucieManagerTlA.param.getWidth >= managerTl.a.bits.param.getWidth)
      require(ucieManagerTlA.size.getWidth >= managerTl.a.bits.size.getWidth)
      require(
        ucieManagerTlA.address.getWidth >= managerTl.a.bits.address.getWidth
      )
      require(ucieManagerTlA.mask.getWidth >= managerTl.a.bits.mask.getWidth)
      require(ucieManagerTlA.data.getWidth >= managerTl.a.bits.data.getWidth)
      require(
        ucieManagerTlA.source.getWidth >= managerTl.a.bits.source.getWidth
      )
      require(
        ucieManagerTlA.corrupt.getWidth >= managerTl.a.bits.corrupt.getWidth
      )
      ucieManagerTlA.opcode := managerTl.a.bits.opcode
      ucieManagerTlA.param := managerTl.a.bits.param
      ucieManagerTlA.size := managerTl.a.bits.size
      ucieManagerTlA.address := managerTl.a.bits.address
      ucieManagerTlA.mask := managerTl.a.bits.mask
      ucieManagerTlA.data := managerTl.a.bits.data
      ucieManagerTlA.source := managerTl.a.bits.source
      ucieManagerTlA.corrupt := managerTl.a.bits.corrupt

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

      creditRetTimer := creditRetTimer + 1.U
      creditsFull := aCreditsToReturn === 0.U && dCreditsToReturn === 0.U
      creditRetValid := ((clientTl.d.fire ||
        managerTl.a.fire ||
        creditRetTimer === (1 << params.creditRetTimerWidth - 1).U ||
        aCreditsToReturn > params.creditRetThreshhold.U ||
        dCreditsToReturn > params.creditRetThreshhold.U) &&
        !creditsFull &&
        (selUcie || selMbTl || selSbTl) &&
        // The mainband carries a frame every cycle, so a return always gets a
        // ride. The sideband carries one frame at a time, and returning credits
        // clears the counters whether or not the frame goes out, so hold the
        // return off until the serializer can take it.
        (!selSbTl || sbTl.io.tx.ready))

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
      ucieManagerTxA.tl_valid := managerTl.a.fire
      ucieManagerTxA.credit_valid := creditRetValid
      ucieManagerTxA.credit_a := aCreditsToReturn
      ucieManagerTxA.credit_d := dCreditsToReturn
      dontTouch(ucieManagerTxA.tl_valid)
      dontTouch(ucieManagerTxA.credit_valid)
      dontTouch(ucieManagerTxA.credit_a)
      dontTouch(ucieManagerTxA.credit_d)
      ucieManagerTxA.tl := ucieManagerTlA

      val lastSeenAddr = RegInit(0.U(64.W))
      when(managerTl.a.valid) {
        lastSeenAddr := managerTl.a.bits.address
      }
      regs.module.io.lastSeenTLReq := lastSeenAddr
      regs.module.io.sbTlRxOverflow := sbTl.io.rxOverflow

      val rxABuffer =
        Module(new Queue(new UcieTXA(creditBits), params.tlBufferDepth))
      val rxDBuffer =
        Module(new Queue(new UcieTXD(creditBits), params.tlBufferDepth))
      val txTlFifo =
        Module(new AsyncQueue(new TxIO(params.numLanes), params.queueParams))
      // Always true to send clock when tl path.
      txTlFifo.io.enq.valid := selMbTl
      val txValid = clientTl.d.fire || managerTl.a.fire || creditRetValid
      txTlFifo.io.enq.bits.valid := Mux(txValid, "h0000ffff".U, 0.U)
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

      // chipFacing TX: route the same framed data into ucieDigital (ucie mode), crossing
      // childClock -> ucieClk. Only the protocol data crosses (no track/clkp/clkn/valid lanes); deq is ucieClk.
      val txAQ = Module(
        new AsyncQueue(
          chiselTypeOf(ucieDigital.io.chipFacingIo.mainbandTx.bits),
          params.queueParams
        )
      )
      txAQ.io.enq.valid := selUcie
      txAQ.io.enq.bits.data := txFramedData.asTypeOf(txAQ.io.enq.bits.data)
      txAQ.io.enq_clock := childClock
      txAQ.io.enq_reset := childReset
      txAQ.io.deq_clock := phy.io.clkRst.ucieClk
      txAQ.io.deq_reset := phy.io.clkRst.ucieRst
      ucieDigital.io.chipFacingIo.mainbandTx.valid := txAQ.io.deq.valid
      ucieDigital.io.chipFacingIo.mainbandTx.bits := txAQ.io.deq.bits
      txAQ.io.deq.ready := ucieDigital.io.chipFacingIo.mainbandTx.ready

      // Sideband TX: the same framed data, but offered only when there is
      // something to send, since the sideband has no idle frame to hide a beat
      // in. `sbTl.io.tx.ready` is a plain register, so letting it feed back into
      // the offer through `creditRetValid` cannot close a combinational loop.
      sbTl.io.tx.valid := selSbTl && ((clientTl.d.valid && dAvail) ||
        (managerTl.a.valid && aAvail && !clientTl.d.valid) ||
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

      clientTl.d.ready := txEnqReady && dAvail
      managerTl.a.ready := txEnqReady && aAvail && !clientTl.d.valid

      when(rxABuffer.io.deq.fire && rxABuffer.io.deq.bits.tl_valid) {
        aCreditsToReturn := aCreditsToReturn + 1.U
      }
      when(creditRetValid) {
        aCreditsToReturn := Mux(rxABuffer.io.deq.fire, 1.U, 0.U)
      }
      when(rxDBuffer.io.deq.fire && rxDBuffer.io.deq.bits.tl_valid) {
        dCreditsToReturn := dCreditsToReturn + 1.U
      }
      when(creditRetValid) {
        dCreditsToReturn := Mux(rxDBuffer.io.deq.fire, 1.U, 0.U)
      }

      txTlFifo.io.enq_clock := childClock
      txTlFifo.io.enq_reset := childReset
      txTlFifo.io.deq_clock := phy.io.clkRst.txDivClk
      txTlFifo.io.deq_reset := phy.io.clkRst.txDatapathRstSync
      txTlFifo.io.deq.ready := selMbTl

      val rxTlFifo =
        Module(new AsyncQueue(new RxIO(params.numLanes), params.queueParams))
      val validFramer = Module(new ValidFramer(params.numLanes))
      rxTlFifo.io.enq.bits := phy.io.rx
      rxTlFifo.io.enq.valid := selMbTl
      rxTlFifo.io.enq_clock := phy.io.clkRst.rxDivClk
      rxTlFifo.io.enq_reset := phy.io.clkRst.rxDatapathRstSync
      rxTlFifo.io.deq <> validFramer.io.phy
      rxTlFifo.io.deq_clock := childClock
      rxTlFifo.io.deq_reset := childReset
      // Replace decoupled IOs that need ready to be true with validIO
      validFramer.io.digital.ready := true.B
      rxABuffer.io.enq.valid := false.B
      rxDBuffer.io.enq.valid := false.B

      // chipFacing RX: ucieDigital's 512b (ucie mode) feeds the credit path, crossing
      // ucieClk -> childClock. Muxed with the tl-path ValidFramer output by mode.
      val rxAQ = Module(
        new AsyncQueue(
          chiselTypeOf(ucieDigital.io.chipFacingIo.mainbandRx.bits),
          params.queueParams
        )
      )
      rxAQ.io.enq.valid := ucieDigital.io.chipFacingIo.mainbandRx.valid && selUcie
      rxAQ.io.enq.bits := ucieDigital.io.chipFacingIo.mainbandRx.bits
      ucieDigital.io.chipFacingIo.mainbandRx.ready := rxAQ.io.enq.ready && selUcie
      rxAQ.io.enq_clock := phy.io.clkRst.ucieClk
      rxAQ.io.enq_reset := phy.io.clkRst.ucieRst
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
      rxABuffer.io.enq.bits := tlBits.asTypeOf(rxABuffer.io.enq.bits)
      rxDBuffer.io.enq.bits := tlBits.asTypeOf(rxDBuffer.io.enq.bits)
      when(framedValid) {
        when(framedBits.asUInt(0)) {
          rxDBuffer.io.enq.valid := true.B
        }.otherwise {
          rxABuffer.io.enq.valid := true.B
        }
      }

      clientTl.a <> rxABuffer.io.deq.map(bits => {
        val tlBundleA = Wire(chiselTypeOf(clientTl.a.bits))
        tlBundleA.opcode := bits.tl.opcode
        tlBundleA.param := bits.tl.param
        tlBundleA.size := bits.tl.size
        tlBundleA.address := bits.tl.address
        tlBundleA.mask := bits.tl.mask
        tlBundleA.data := bits.tl.data
        tlBundleA.source := bits.tl.source
        tlBundleA.corrupt := bits.tl.corrupt
        tlBundleA
      })
      clientTl.a.valid := rxABuffer.io.deq.valid && rxABuffer.io.deq.bits.tl_valid
      managerTl.d <> rxDBuffer.io.deq.map(bits => {
        val tlBundleD = Wire(chiselTypeOf(managerTl.d.bits))
        tlBundleD.opcode := bits.tl.opcode
        tlBundleD.param := bits.tl.param
        tlBundleD.size := bits.tl.size
        tlBundleD.data := bits.tl.data
        tlBundleD.source := bits.tl.source
        tlBundleD.sink := bits.tl.sink
        tlBundleD.denied := bits.tl.denied
        tlBundleD.corrupt := bits.tl.corrupt
        tlBundleD
      })
      managerTl.d.valid := rxDBuffer.io.deq.valid && rxDBuffer.io.deq.bits.tl_valid
      dontTouch(managerTl.d.bits.opcode)

      // The word the TL path puts on the lanes. Data and valid come through the
      // queue; the clock and track lanes are forced to their fixed patterns
      // here regardless of what it carried. The queue is enqueued
      // unconditionally while the mainband is in `tl` mode, so these keep going
      // out through gaps in TL traffic rather than stopping with it.
      val tlTxWord = Wire(new TxIO(params.numLanes))
      tlTxWord.data := txTlFifo.io.deq.bits.data
      tlTxWord.valid := txTlFifo.io.deq.bits.valid
      tlTxWord.track := "h55555555".U
      tlTxWord.clkp := "h55555555".U
      tlTxWord.clkn := "haaaaaaaa".U

      // A mainband in tl mode drives from txTlFifo; otherwise PhyTest and ucie
      // both drive from txTestFifo.
      phy.io.tx := Mux(
        selMbTl,
        Mux(
          txTlFifo.io.deq.valid,
          tlTxWord,
          0.U.asTypeOf(phy.io.tx)
        ),
        Mux(
          txTestFifo.io.deq.valid,
          txTestFifo.io.deq.bits,
          0.U.asTypeOf(phy.io.tx)
        )
      )

      val creditAValid =
        rxABuffer.io.deq.valid && rxABuffer.io.deq.bits.credit_valid
      val creditDValid =
        rxDBuffer.io.deq.valid && rxDBuffer.io.deq.bits.credit_valid

      val aCreditCounter = Module(
        new CreditCounter(params.creditCounterSize, params.tlBufferDepth)
      )
      aCreditCounter.io.used := managerTl.a.fire
      aCreditCounter.io.ret.valid := creditAValid || creditDValid
      aCreditCounter.io.ret.bits := Mux(
        creditAValid,
        rxABuffer.io.deq.bits.credit_a,
        rxDBuffer.io.deq.bits.credit_a
      )
      aCreditCounter.io.mode := regs.module.io.creditFlowEnable
      aAvail := aCreditCounter.io.avail

      val dCreditCounter = Module(
        new CreditCounter(params.creditCounterSize, params.tlBufferDepth)
      )
      dCreditCounter.io.used := clientTl.d.fire
      dCreditCounter.io.ret.valid := creditAValid || creditDValid
      dCreditCounter.io.ret.bits := Mux(
        creditAValid,
        rxABuffer.io.deq.bits.credit_d,
        rxDBuffer.io.deq.bits.credit_d
      )
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
        // The clock source registers run on the chip's digital clock, which
        // here is the same always running bus clock.
        ucie.chipDigitalClockNode := sbus.fixedClockNode
        pbus.coupleTo(s"uciephytest{$n}") {
          val xbar = TLXbar()
          ucie.regNode := xbar
          ucie.clkRegNode := xbar
          xbar := TLBuffer() := TLFragmenter(
            pbus.beatBytes,
            pbus.blockBytes
          ) := TLBuffer() := _
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
  val control_manager_node = Some(ucie.regNode)
  val clock_node = Some(ucie.digitalClockNode)
  val top_IO = BundleBridgeSource(() => new UcieBumpsIO(params.numLanes))
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
  // Two sinks: the UCIe digital domain and the chip digital domain.
  val clockNode = ClockSourceNode(Seq.fill(2)(ClockSourceParameters()))
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
  ucieTL.chipDigitalClockNode := clockNode
  private val regXbar = TLXbar()
  ucieTL.regNode := regXbar
  ucieTL.clkRegNode := regXbar
  regXbar := node
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
