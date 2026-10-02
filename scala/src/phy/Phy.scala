package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.phy.macros._
import edu.berkeley.cs.uciedigital.phy.macros.clocking._

/** Lane numbering.
  *
  * One order, both directions: the `numLanes` data lanes, then valid, then
  * track, then the two forwarded-clock lanes last. TX has all `numLanes + 4`;
  * RX has the same numbering but only the first `numLanes + 2` carry a word,
  * since its clock lanes recover a clock rather than deserializing one.
  *
  * The per-lane controls in [[PhyRegsIO]], the lane clocks out of
  * `ClkDistNetwork`, the observation taps in [[PhyDebugIO]], and the bump
  * fan-out all index that same way, so lane `i` means one thing everywhere.
  * [[TxIO]] and [[RxIO]] name their lanes instead, since which lane carries
  * valid, track, or a forwarded clock matters to the controller above and to
  * the partner die.
  *
  * The PHY does not repair lanes. Moving the valid waveform onto another lane
  * is a test function and lives in `PhyTest`.
  */
object Phy {
  // Bits per lane per divided clock cycle, set by the TX tile's serializer.
  val SerdesRatio = TxLane.SerdesRatio

  // Lanes that can be selected to carry the valid waveform in a test: the
  // `numLanes` data lanes, the dedicated valid lane, and the track lane. The
  // forwarded clock lanes are excluded since the RX side has no counterpart for
  // them. These codes coincide with physical lane indices for the data lanes
  // and the dedicated valid lane; `trackValidLaneSel` names TX physical lane
  // `numLanes + 3` and RX physical lane `numLanes + 1`, since only TX has clock
  // lanes in between.
  def validLaneSelCount(numLanes: Int): Int = numLanes + 2

  // Width of a valid lane select field.
  def validLaneSelWidth(numLanes: Int): Int =
    log2Ceil(validLaneSelCount(numLanes))

  // Default select code, and the reset value of both selects: the dedicated
  // valid lane, i.e. valid where it normally goes.
  def defaultValidLaneSel(numLanes: Int): Int = numLanes

  // Select code for the track lane. The mainband protocol does not use track,
  // so moving valid there works around a broken valid lane without giving up a
  // data lane.
  def trackValidLaneSel(numLanes: Int): Int = numLanes + 1

  // Lane layout. Data lanes come first, then valid and track, then the two
  // forwarded-clock lanes. Both directions number the same way; the RX clock
  // lanes just carry no word.
  def numTxLanes(numLanes: Int): Int = numLanes + 4
  def numRxDataLanes(numLanes: Int): Int = numLanes + 2
  def validLane(numLanes: Int): Int = numLanes
  def trackLane(numLanes: Int): Int = numLanes + 1
  def clkPLane(numLanes: Int): Int = numLanes + 2
  def clkNLane(numLanes: Int): Int = numLanes + 3

  // The RX words in lane order. Useful wherever a lane index is dynamic, since
  // a bundle cannot be indexed.
  def rxLaneWords(rx: RxIO, numLanes: Int): Vec[UInt] =
    VecInit((0 until numLanes).map(rx.data(_)) ++ Seq(rx.valid, rx.track))

  // The order a tile puts a word on the wire, and takes one off it. The TX tile
  // serializes through an adjacent-pairing binary tree, so the bit sent in UI
  // `t` is `din(treeBitOrder(t))` -- bit reversal of the five index bits,
  // giving D0 D16 D8 D24 D4 D20 ... rather than D0 D1 D2 D3. The RX tile
  // deserializes through the mirror image of that tree, so `dout(j)` is the bit
  // received in UI `treeBitOrder(j)`.
  //
  // Each lane's shuffler therefore applies this permutation to cancel its own
  // tile's tree, which is what both reset values do. `treeBitOrder` is its own
  // inverse, so the same mapping serves in either direction. Two tiles wired
  // together would cancel each other without any of this, but going through
  // plain bit order is what makes a lane interoperable, and it is also what
  // lets the RX realign on an arbitrary word boundary: past the shuffler an
  // offset capture is a rotation of the stream, where in tree order it is a
  // scramble.
  def treeBitOrder(t: Int): Int =
    (0 until 5).map(b => ((t >> b) & 1) << (4 - b)).sum

  // Instance names for the lane tiles. The PHY does not otherwise care what a
  // lane carries, but the physical flow and every waveform are far easier to
  // read when the tiles are named for their role.
  def laneName(prefix: String, lane: Int, numLanes: Int): String =
    if (lane < numLanes) s"${prefix}data$lane"
    else if (lane == validLane(numLanes)) s"${prefix}valid"
    else if (lane == trackLane(numLanes)) s"${prefix}track"
    else if (lane == clkPLane(numLanes)) s"${prefix}clkp"
    else s"${prefix}clkn"
}

/** One serdes word per TX lane, declared in lane order. */
class TxIO(numLanes: Int = 16) extends Bundle {
  val data = Vec(numLanes, Bits(Phy.SerdesRatio.W))
  val valid = Bits(Phy.SerdesRatio.W)
  val track = Bits(Phy.SerdesRatio.W)
  val clkp = Bits(Phy.SerdesRatio.W)
  val clkn = Bits(Phy.SerdesRatio.W)
}

/** One deserialized word per RX lane that carries data, in the same lane order
  * as [[TxIO]]. The forwarded-clock lanes recover a clock instead of a word, so
  * they have no entry here.
  */
class RxIO(numLanes: Int = 16) extends Bundle {
  val data = Vec(numLanes, Bits(Phy.SerdesRatio.W))
  val valid = Bits(Phy.SerdesRatio.W)
  val track = Bits(Phy.SerdesRatio.W)
}

// The sideband bumps as the PHY sees them. The TX side is pre-serialization:
// the `SbDriver` on each bump does the 2:1, so the digital side hands over the
// two half rate bits and the clock to serialize them on.
class SbIO extends Bundle {
  val txClk = Input(new SbSerialIO)
  val txData = Input(new SbSerialIO)
  val rxClk = Output(Clock())
  val rxData = Output(Bool())
}

/** Nets the PHY taps for observation.
  *
  * These are plain fanouts of nets the link already has: the debug bumps, the
  * mux that picks what lands on each one, and their pad drivers all live in
  * `PhyTest`, so the PHY itself carries only RTL the link needs.
  */
class PhyDebugIO(numLanes: Int = 16) extends Bundle {
  // Full-rate TX clock, tapped at the clocking tile output that feeds the lane
  // clock distribution network. Also clocks the tester's own TX lanes (the TX
  // data debug lane and the loopback pair), which sit outside that network.
  val txClk = Output(Clock())
  // Forwarded clock as recovered by the RX clock lane, before distribution.
  val rxClk = Output(Clock())
  // TX global divided clock, i.e. the clock the TX lanes take their words on.
  val txDivClk = Output(Clock())
  // Sideband forwarded clock as the PHY transmits it. Tapped here rather than
  // taken from the tester's own sideband output so that the observed clock is
  // whichever controller currently owns the sideband.
  val sbTxClk = Output(Clock())
  // Deserialized RX lane words, in the RX divided clock domain, in lane order:
  // `numLanes` data lanes, then valid, then track.
  val rxData = Output(
    Vec(Phy.numRxDataLanes(numLanes), Bits(Phy.SerdesRatio.W))
  )
}

class PhyBumpsIO(numLanes: Int = 16) extends Bundle {
  val txData = Output(Vec(numLanes, Bool()))
  val txValid = Output(Bool())
  val txTrack = Output(Bool())
  val txClkP = Output(Clock())
  val txClkN = Output(Clock())
  val rxData = Input(Vec(numLanes, Bool()))
  val rxValid = Input(Bool())
  val rxTrack = Input(Bool())
  val rxClkP = Input(Clock())
  val rxClkN = Input(Clock())
  val sbTxClk = Output(Clock())
  val sbTxData = Output(Bool())
  val sbRxClk = Input(Clock())
  val sbRxData = Input(Bool())
  val bypassClk = Input(Clock())
  val digitalBypassClk = Input(Clock())
  // 800 MHz bypass for the sideband, which runs at its own rate.
  val sidebandBypassClk = Input(Clock())
  // 100 MHz reference for the clocking tile's PLL.
  val refClk = Input(Clock())

}

// PHY clock and reset IOs.
class PhyClkRstIO extends Bundle {
  // Main digital reset, asynchronous to PHY clocks.
  val reset = Input(Bool())
  // Divider holds, one per direction. These carry the main reset and the
  // dedicated divider reset, nothing else, so the global divider and every
  // tile divider start together and keep a fixed phase; between resets the
  // divided clocks free-run.
  //
  // Asserting one with the TX clock gated is how a delay code is changed: the
  // tile dividers reset with no clock present, and every one of them restarts
  // on the first edge after the clock comes back.
  val txDividerRstb = Input(AsyncReset())
  val rxDividerRstb = Input(AsyncReset())
  // Per-direction restarts. These do not touch a divider: they reset the logic
  // on the divided clock -- the serdes handoff registers and the PHY side of
  // the async queues -- while that clock keeps running, so the reset actually
  // applies and then releases synchronously.
  val txDatapathRst = Input(Bool())
  val rxDatapathRst = Input(Bool())

  // Clocking tile configuration. All of it decides where `ucieClk` comes
  // from, or has to be settled before it exists, so it is held in a register
  // block on the chip's own digital clock rather than in the one that runs on
  // `ucieClk` -- and it arrives here rather than through `PhyRegsIO`.
  val mainClkSel = Input(UInt(ClockingTile.mainClkSelWidth.W))
  val pll8En = Input(Bool())
  val pll12En = Input(Bool())
  val pll16En = Input(Bool())
  val txClkDiv = Input(UInt(ClockingTile.txClkDivWidth.W))
  val txClkPhase = Input(UInt(ClockingTile.txClkPhaseWidth.W))
  val digClkDiv = Input(UInt(ClockingTile.digClkDivWidth.W))
  val digClkBypassEn = Input(Bool())
  val sbClkDiv = Input(UInt(ClockingTile.sbClkDivWidth.W))
  val sbClkBypassEn = Input(Bool())
  // MBINIT.REPAIRCLK sampling clock division of the main clock, same codes as
  // `txClkDiv`. The ratio between the two is the oversample ratio the repair
  // taps measure at: `N = 2^(txClkDiv - repairClkDiv)`.
  val repairClkDiv = Input(UInt(ClockingTile.repairClkDivWidth.W))
  // Opens a REPAIRCLK measurement window.
  //
  // This one signal is the whole control surface. Raising it ungates the
  // repair clock, releases the repair dividers -- which were held with no
  // clock present, so every one of them restarts on the first edge -- and
  // clears the counters. Lowering it stops the clock, which is what freezes
  // the counters and the capture so they can be read from a domain that is
  // still running. Nothing else has to be sequenced.
  val repairClkEn = Input(Bool())

  // Runs the RX lanes from TXCLKQ rather than the recovered forwarded clock,
  // for when the far side's clock is not behaving.
  val rxClkFromTxQ = Input(Bool())
  // The global delay line on TXCLKQ.
  val clkPhaseSel = Input(UInt(ClockingTile.phaseSelWidth.W))
  // Stops the clock reaching the TX lanes while low, so a delay code can be
  // changed with no edge in flight.
  val clkGateEn = Input(Bool())
  // Stops the recovered forwarded clock at the RX clock lanes, which keeps
  // the whole RX tree quiet rather than clocking lanes with nothing to
  // sample.
  val rxClkGateEn = Input(Bool())

  // Per-PLL lock, straight out of the clocking tile. The register block waits
  // on whichever one `mainClkSel` names before it lets a new configuration's
  // clocks out.
  val pll8Lock = Output(Bool())
  val pll12Lock = Output(Bool())
  val pll16Lock = Output(Bool())

  // UCIe digital clock (800 MHz).
  //
  // Should always be toggling when RX AFEs must be active.
  val ucieClk = Output(Clock())
  // UCIe digital reset (synchronous to `clk`).
  val ucieRst = Output(Bool())

  // The sideband's own clock, for the TX sideband serializer, and its reset.
  val sbClk = Output(Clock())
  val sbRst = Output(Bool())

  val txDivClk = Output(Clock())
  // Reset for the logic clocked by `txDivClk` -- the serdes handoff registers
  // and the PHY side of the async queues. Not a divider reset; the dividers
  // themselves are held by `txDividerRstb`.
  // Synchronized here rather than by the consumer because `txDivClk` only
  // exists inside the PHY: the divided-clock ends of the async queues live
  // outside it and need this same reset.
  val txDatapathRstSync = Output(Bool())

  val rxDivClk = Output(Clock())
  val rxDatapathRstSync = Output(Bool())
}

// Combinational bit remap of a serdes word: `dout(i)` is driven by
// `din(permutation(i))`.
//
// One sits on each TX lane's serializer input and each RX lane's
// deserializer output so that a bit ordering mismatch between the digital
// word and the analog tile (or between the two dies) can be corrected from
// software. Both reset to [[Phy.treeBitOrder]], which cancels the tile's own
// serdes tree: past a TX shuffler bit 0 of the word is the first bit on the
// wire, and past an RX shuffler bit 0 is the first bit received. The identity
// permutation (`permutation(i) = i`) leaves the tile's tree order exposed
// instead.
//
// `permutation` is not required to be a bijection: repeating an index
// broadcasts that bit, which is useful for driving fixed patterns during
// bring-up.
class Shuffler(width: Int) extends RawModule {
  val io = IO(new Bundle {
    val din = Input(UInt(width.W))
    val dout = Output(UInt(width.W))
    val permutation = Input(Vec(width, UInt(log2Ceil(width).W)))
  })

  io.dout := VecInit((0 until width).map(i => io.din(io.permutation(i)))).asUInt
}

class TxLaneDigitalCtlIO extends Bundle {
  // Control that goes straight out to the analog tile's pins.
  val tile = new TxLaneCtlIO
  val shuffler = Vec(32, UInt(5.W))
  val sample_negedge = Bool()
  val delay = UInt(7.W)
}

class RxLaneDigitalCtlIO extends Bundle {
  // Delay taps on this lane's sampling clock, thermometer coded. The RX
  // counterpart of the TX tile's `Dctrl`: it moves where in the UI this lane
  // samples, independently of every other lane.
  val Dctrl = UInt(RxDataLane.DelayTaps.W)
  val zen = Bool()
  val zctl = UInt(5.W)
  val vref_sel = UInt(7.W)
  val afeBypass = new RxAfeIO
  val afeBypassEn = Bool()
  val afeOpCycles = UInt(16.W)
  val afeOverlapCycles = UInt(16.W)
  val shuffler = Vec(32, UInt(5.W))
  val sample_negedge = Bool()
  val delay = UInt(7.W)
}

/** Per-lane control for a [[RxRepairTapIO]].
  *
  * Its own group rather than more fields on `rxctl` because a tap and the
  * trained datapath sample the same bump on different clocks: they share the
  * front end, which `rxctl` configures, and nothing behind it.
  */
class RxRepairDigitalCtlIO extends Bundle {

  /** Taps on this tap's sampling clock. The repair measurement is phase
    * independent by construction, so this is not needed to make it work -- it
    * is there to sweep the sampling point and prove that it is.
    */
  val delay = UInt(7.W)

  /** Bit permutation on the tap's word, as on every other lane. Load bearing
    * here: in the tile's own tree order an alternating pattern deserializes as
    * one long square wave, so a tap left unshuffled reads one transition a word
    * where it should read thirty-two.
    */
  val shuffler = Vec(32, UInt(5.W))
}

/** The MBINIT.REPAIRCLK measurement, as the register block and the controller
  * see it.
  *
  * Everything below the handshake runs on the repair divided clock, which only
  * exists while `repairClkEn` is high. The outputs are therefore read the way a
  * stopped domain should be: open the window, wait for `done`, close the
  * window, and only then read. Closing it stops the clock, so the counters and
  * the capture are static by the time the next access returns -- and a window
  * that never finished is frozen mid-flight and still readable, with
  * `wordsObserved` saying how far it got.
  */
class PhyRepairIO extends Bundle {
  val windowWords = Input(UInt(ClkRepair.WordCountWidth.W))
  val gapThresh = Input(UInt(ClkRepair.CounterWidth.W))
  val capOffset = Input(UInt(log2Ceil(ClkRepair.CaptureDepth).W))
  val capLane = Input(UInt(log2Ceil(ClkRepair.Lanes).W))

  /** The window has accumulated `windowWords`. Synchronized into the digital
    * clock domain, since this is the one output that is read while the repair
    * clock is still running.
    */
  val done = Output(Bool())
  val wordsObserved = Output(UInt(ClkRepair.WordCountWidth.W))
  val obs = Output(Vec(ClkRepair.Lanes, new ClkRepairLaneObsIO))
  val capWord = Output(UInt(Phy.SerdesRatio.W))
}

class PhyRegsIO(numLanes: Int = 16) extends Bundle {
  // TX CONTROL
  // Per-tile lane control, one entry per lane in the layout order described on
  // `Phy`. Each `shuffler` is a bit permutation within its own lane.
  val txctl = Input(Vec(numLanes + 4, new TxLaneDigitalCtlIO))

  // RX CONTROL
  // Per-tile lane control, indexed exactly like `txctl`. The two
  // forwarded-clock lanes recover a clock rather than a word, so only their AFE
  // settings do anything.
  val rxctl = Input(Vec(numLanes + 4, new RxLaneDigitalCtlIO))

  // One entry per MBINIT.REPAIRCLK tap, in `ClkRepair` lane order: clkP,
  // clkN, track.
  val repairctl = Input(Vec(ClkRepair.Lanes, new RxRepairDigitalCtlIO))
}

class PhyIO(numLanes: Int = 16) extends Bundle {
  // DIGITAL INTERFACE
  // =====================
  val clkRst = new PhyClkRstIO
  val regs = new PhyRegsIO(numLanes)
  val tx = Input(new TxIO(numLanes))
  val rx = Output(new RxIO(numLanes))
  val sb = new SbIO
  // The MBINIT.REPAIRCLK measurement.
  val repair = new PhyRepairIO
  // Observation taps for the tester's debug bumps.
  val debug = new PhyDebugIO(numLanes)

  // TOP INTERFACE
  // =====================
  val top = new PhyBumpsIO(numLanes)
}

class Phy(numLanes: Int = 16)(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new PhyIO(numLanes))

  // Bypass clock pad: ESD clamp, then the clock receiver that restores the
  // single-ended pad signal to a full-swing clock.
  val bypassClkEsd = Module(new Esd)
  bypassClkEsd.io.IO_signal := io.top.bypassClk.asBool
  val bypassClkRx = Module(new ClkRx)
  bypassClkRx.io.Vin := io.top.bypassClk

  // Clocking tile: sources the digital clock and the I/Q TX clocks.
  val clkTile = Module(new ClockingTile)
  clkTile.io.DigBypassClk := io.top.digitalBypassClk
  clkTile.io.BypassClk := bypassClkRx.io.Vout
  clkTile.io.PhaseSel := io.clkRst.clkPhaseSel
  clkTile.io.MainClkSel := io.clkRst.mainClkSel
  clkTile.io.Pll8En := io.clkRst.pll8En
  clkTile.io.Pll12En := io.clkRst.pll12En
  clkTile.io.Pll16En := io.clkRst.pll16En
  clkTile.io.TxClkDiv := io.clkRst.txClkDiv
  clkTile.io.TxClkPhase := io.clkRst.txClkPhase
  clkTile.io.DigClkDiv := io.clkRst.digClkDiv
  clkTile.io.ClkGateEn := io.clkRst.clkGateEn
  clkTile.io.DigClkBypassEn := io.clkRst.digClkBypassEn
  clkTile.io.SbClkDiv := io.clkRst.sbClkDiv
  clkTile.io.SbClkBypassEn := io.clkRst.sbClkBypassEn
  clkTile.io.SbBypassClk := io.top.sidebandBypassClk
  clkTile.io.RefClk := io.top.refClk
  clkTile.io.RepairClkDiv := io.clkRst.repairClkDiv
  clkTile.io.RepairClkEn := io.clkRst.repairClkEn

  io.clkRst.pll8Lock := clkTile.io.Pll8Lock
  io.clkRst.pll12Lock := clkTile.io.Pll12Lock
  io.clkRst.pll16Lock := clkTile.io.Pll16Lock

  io.clkRst.ucieClk := clkTile.io.DigitalClk
  io.clkRst.sbClk := clkTile.io.SbClk
  // The sideband clock is its own domain now -- divided from the main clock
  // or taken from its own pin -- so the logic on it needs a reset released
  // against that clock rather than against the digital one.
  val sbRstSync = Module(new RstSync)
  sbRstSync.io.rstbAsync := !io.clkRst.reset
  sbRstSync.io.clk := io.clkRst.sbClk
  io.clkRst.sbRst := !sbRstSync.io.rstbSync
  val digitalRstSync = Module(new RstSync)
  digitalRstSync.io.rstbAsync := !io.clkRst.reset
  digitalRstSync.io.clk := io.clkRst.ucieClk
  io.clkRst.ucieRst := !digitalRstSync.io.rstbSync

  // All lane clocks are single-ended; each tile does its own single-to-
  // differential conversion. The network sends the in-phase clock to the data,
  // valid, and track lanes and the quadrature clock to the two forwarded-clock
  // lanes.
  val clkDist = Module(new ClkDistNetwork)
  clkDist.io.txClk := clkTile.io.TxClk
  clkDist.io.txClkQ := clkTile.io.TxClkQ
  clkDist.io.repairClk := clkTile.io.RepairClk
  // The tester's own TX lanes sit outside the distribution network, so they
  // take the in-phase clock from the same place the network does.
  io.debug.txClk := clkTile.io.TxClk

  // TODO do we need to set pu/pd ctl to 0 when driver en is low?

  // Set up sideband. Each bump gets an `SbDriver`, which is the 2:1 serializer
  // and the pad driver as one cell.
  val sbTxClk = Module(new SbDriver)
  sbTxClk.io.in := io.sb.txClk
  io.top.sbTxClk := sbTxClk.io.out.asClock
  sbTxClk.io.ctl := PadDriverCtlIO.full
  val sbTxData = Module(new SbDriver)
  sbTxData.io.in := io.sb.txData
  io.top.sbTxData := sbTxData.io.out
  sbTxData.io.ctl := PadDriverCtlIO.full
  val esdSbRxClk = Module(new EsdRoutable)
  val esdSbRxData = Module(new EsdRoutable)
  esdSbRxClk.io.term := io.top.sbRxClk.asBool
  esdSbRxData.io.term := io.top.sbRxData.asBool
  io.sb.rxClk := io.top.sbRxClk
  io.sb.rxData := io.top.sbRxData
  // The forwarded clock as actually transmitted, i.e. after the bump driver
  // has serialized the half rate pair.
  io.debug.sbTxClk := sbTxClk.io.out.asClock

  // Global clock dividers.
  //
  // Both dividers come out of reset with every stage of the cascade toggling on
  // the first edge, so `clkout_3` rises on the same edge the lane serdes loads
  // its word on. Handing words over on that edge would sample them as they
  // change, so both directions invert it: the divided clock then rises half a
  // word period away from the load, in the middle of the stable window. The
  // serdes shares each direction's reset, so that phase is fixed.
  // TX
  val txClkDiv = Module(new ClkDiv4)
  txClkDiv.io.clk := clkDist.io.txClkDivClk
  txClkDiv.io.resetb := io.clkRst.txDividerRstb
  io.clkRst.txDivClk := (!txClkDiv.io.clkout_3.asBool).asClock
  val txRstSync = Module(new RstSync)
  txRstSync.io.rstbAsync := !(io.clkRst.reset || io.clkRst.txDatapathRst)
  txRstSync.io.clk := io.clkRst.txDivClk
  io.clkRst.txDatapathRstSync := !txRstSync.io.rstbSync
  io.debug.txDivClk := io.clkRst.txDivClk
  // RX
  val rxClkDiv = Module(new ClkDiv4)
  rxClkDiv.io.clk := clkDist.io.rxClkDivClk
  rxClkDiv.io.resetb := io.clkRst.rxDividerRstb
  io.clkRst.rxDivClk := (!rxClkDiv.io.clkout_3.asBool).asClock
  val rxRstSync = Module(new RstSync)
  rxRstSync.io.rstbAsync := !(io.clkRst.reset || io.clkRst.rxDatapathRst)
  rxRstSync.io.clk := io.clkRst.rxDivClk
  io.clkRst.rxDatapathRstSync := !rxRstSync.io.rstbSync

  // REPAIRCLK sampling domain.
  //
  // Same three layers as TX and RX -- a divider hold, a datapath reset, and
  // the logic on the divided clock -- but both resets come off `repairClkEn`
  // instead of a register of their own, because the gate already says exactly
  // when this domain is supposed to exist.
  //
  // The dividers are held whenever the gate is shut, which is the pattern a
  // delay code change already uses here: they clear with no clock present and
  // every one of them restarts on the first edge after the clock comes back,
  // so a tap's load edge and the divided clock keep a fixed phase window to
  // window.
  val repairDividerRstb =
    (!(io.clkRst.reset || !io.clkRst.repairClkEn)).asAsyncReset
  val repairClkDiv = Module(new ClkDiv4)
  repairClkDiv.io.clk := clkDist.io.repairClkDivClk
  repairClkDiv.io.resetb := repairDividerRstb
  // Inverted for the same reason as the other two: the divided clock then
  // rises half a word period from the edge a tap loads on.
  val repairDivClk = (!repairClkDiv.io.clkout_3.asBool).asClock

  // The counters clear when a window OPENS, not while the gate is shut.
  // Closing the gate is how a result is frozen for reading, so tying their
  // reset to the gate the way the dividers are tied would wipe the very thing
  // the window was run to produce.
  val repairArm = withClockAndReset(io.clkRst.ucieClk, io.clkRst.ucieRst) {
    // Unlike the other clocking selects, this one moves while the part is
    // running -- it is how a training stage asks for a window -- so it crosses
    // from the chip's digital clock through two flops before anything acts on
    // an edge of it.
    val sync = RegNext(RegNext(io.clkRst.repairClkEn, false.B), false.B)
    val prev = RegNext(sync, false.B)
    sync && !prev
  }
  val repairRstSync = Module(new RstSync)
  repairRstSync.io.rstbAsync := !(io.clkRst.reset || repairArm)
  repairRstSync.io.clk := repairDivClk
  val repairDatapathRstSync = !repairRstSync.io.rstbSync

  // One oversampled word per measured lane, past its shuffler, in
  // `ClkRepair` lane order: clkP, clkN, track.
  val repairTapWord = Wire(Vec(ClkRepair.Lanes, UInt(Phy.SerdesRatio.W)))

  // Wires a tap up and returns its word in wire order. The shuffle is not
  // cosmetic: the tile's serdes tree reverses bit order, and an alternating
  // pattern read in tree order is one long square wave rather than an edge a
  // UI, so an unshuffled tap would score a live lane as dead.
  def connectRepairTap(
      tap: RxRepairTapIO,
      slot: Int,
      name: String
  ): Unit = {
    tap.clk := clkDist.io.repairLaneClk(slot)
    tap.rstb := repairDividerRstb
    tap.Dctrl := VecInit(
      RxDataLane.thermometer(io.regs.repairctl(slot).delay).asBools
    )
    val shuffler = Module(new Shuffler(Phy.SerdesRatio))
    shuffler.suggestName(s"${name}_repair_shuffler")
    shuffler.io.din := tap.dout.asUInt
    shuffler.io.permutation := io.regs.repairctl(slot).shuffler
    repairTapWord(slot) := shuffler.io.dout
  }

  // The TX words in lane order, for the uniform lane pipeline below.
  val txLaneDin = Wire(Vec(Phy.numTxLanes(numLanes), Bits(Phy.SerdesRatio.W)))
  for (lane <- 0 until numLanes) {
    txLaneDin(lane) := io.tx.data(lane)
  }
  txLaneDin(Phy.validLane(numLanes)) := io.tx.valid
  txLaneDin(Phy.trackLane(numLanes)) := io.tx.track
  txLaneDin(Phy.clkPLane(numLanes)) := io.tx.clkp
  txLaneDin(Phy.clkNLane(numLanes)) := io.tx.clkn

  // The backup TX handoff phase. The queue hands a word over on the rising edge
  // of `txDivClk` and the tiles load half a word period later, which is only
  // where the clock distribution actually puts that load edge. This set
  // relaunches the words on the other edge of `txDivClk`, half a period away,
  // for lanes whose `sample_negedge` picks it; which of the two lands in the
  // middle of a tile's load window is a bring-up question, hence per lane. A
  // lane that moves sends one word period later than one that did not.
  val txLaneDinNeg = withClockAndReset(
    (!io.clkRst.txDivClk.asBool).asClock,
    io.clkRst.txDatapathRstSync
  ) {
    RegNext(txLaneDin, 0.U.asTypeOf(chiselTypeOf(txLaneDin)))
  }

  // TX lanes. Every lane is the same: a bit shuffle, then a serializer.
  for (lane <- 0 until Phy.numTxLanes(numLanes)) {
    val laneName = Phy.laneName("tx", lane, numLanes)

    // Bit remap applied immediately before the serializer, so the permutation
    // is in terms of the lane's own word.
    val txShuffler = Module(new Shuffler(Phy.SerdesRatio))
    txShuffler.suggestName(s"${laneName}_shuffler")
    txShuffler.io.din := Mux(
      io.regs.txctl(lane).sample_negedge,
      txLaneDinNeg(lane),
      txLaneDin(lane)
    )
    txShuffler.io.permutation := io.regs.txctl(lane).shuffler

    val txLane = Module(new TxLane);
    txLane.suggestName(laneName);
    // The tile's divider reset is active high, `txDividerRstb` active low.
    txLane.io.rst := (!io.clkRst.txDividerRstb.asBool).asAsyncReset
    txLane.io.clk := clkDist.io.txLaneClk(lane)
    txLane.io.din := txShuffler.io.dout
    // The bumps are named, so this is the one place the TX side maps a lane
    // index onto a role.
    if (lane < numLanes) {
      io.top.txData(lane) := txLane.io.dout
    } else if (lane == Phy.validLane(numLanes)) {
      io.top.txValid := txLane.io.dout
    } else if (lane == Phy.trackLane(numLanes)) {
      io.top.txTrack := txLane.io.dout
    } else if (lane == Phy.clkPLane(numLanes)) {
      io.top.txClkP := txLane.io.dout.asClock
    } else {
      io.top.txClkN := txLane.io.dout.asClock
    }
    txLane.io.ctl := io.regs.txctl(lane).tile
  }

  // RX Lanes
  //
  // RX AFE control is on the UCIe digital clock to ensure that it is always toggling,
  // even when forwarded clock is gated.
  //
  val rxLaneDout = Wire(
    Vec(Phy.numRxDataLanes(numLanes), Bits(Phy.SerdesRatio.W))
  )
  withClockAndReset(io.clkRst.ucieClk, io.clkRst.ucieRst) {
    // Set up clocking
    val rxClkP = Module(new RxClkLane)
    val rxClkPAfeCtl =
      RxAfeCtl.connect(rxClkP, io.regs.rxctl(Phy.clkPLane(numLanes)))
    rxClkP.io.clk_gate_en := io.clkRst.rxClkGateEn
    rxClkP.io.clkin := io.top.rxClkP
    connectRepairTap(rxClkP.io.repair, ClkDistNetwork.repairClkP, "rxclkp")
    // The forwarded clock arrives as a bump pair, but everything past the
    // clock lanes is single-ended, so the distribution network is driven from
    // the P lane alone. The N lane still terminates its bump and carries its
    // own AFE control.
    // Either the clock the far side forwarded, or this die's own TXCLKQ --
    // the copy after the global delay line, so the RX samples on the phase
    // the TX is transmitting.
    clkDist.io.rxClk := Mux(
      io.clkRst.rxClkFromTxQ,
      clkTile.io.TxClkQ,
      rxClkP.io.clkout
    )
    io.debug.rxClk := rxClkP.io.clkout

    val rxClkN = Module(new RxClkLane)
    val rxClkNAfeCtl =
      RxAfeCtl.connect(rxClkN, io.regs.rxctl(Phy.clkNLane(numLanes)))
    rxClkN.io.clk_gate_en := io.clkRst.rxClkGateEn
    rxClkN.io.clkin := io.top.rxClkN
    connectRepairTap(rxClkN.io.repair, ClkDistNetwork.repairClkN, "rxclkn")

    // Every lane that carries a word is the same: a deserializer, then a bit
    // shuffle.
    for (lane <- 0 until Phy.numRxDataLanes(numLanes)) {
      val laneName = Phy.laneName("rx", lane, numLanes)

      // Track is the one data lane MBINIT.REPAIRCLK measures, so it is the
      // one that carries a sampling tap. See `RxRepairTapIO`.
      val isTrack = lane == Phy.trackLane(numLanes)
      val rxLane = Module(new RxDataLane(withRepairTap = isTrack))
      val rxLaneAfeCtl = RxAfeCtl.connect(rxLane, io.regs.rxctl(lane))
      rxLane.suggestName(laneName)
      rxLane.io.repair.foreach(
        connectRepairTap(_, ClkDistNetwork.repairTrack, laneName)
      )
      // As on TX, the bump fan-out is the one place a lane index becomes a
      // role.
      if (lane < numLanes) {
        rxLane.io.din := io.top.rxData(lane)
      } else if (lane == Phy.validLane(numLanes)) {
        rxLane.io.din := io.top.rxValid
      } else {
        rxLane.io.din := io.top.rxTrack
      }

      // Bit remap applied immediately after the deserializer, mirroring the
      // TX side.
      val rxShuffler = Module(new Shuffler(Phy.SerdesRatio))
      rxShuffler.suggestName(s"${laneName}_shuffler")
      rxShuffler.io.din := rxLane.io.dout.asUInt
      rxShuffler.io.permutation := io.regs.rxctl(lane).shuffler

      rxLaneDout(lane) := rxShuffler.io.dout
      rxLane.io.clk := clkDist.io.rxLaneClk(lane)
      rxLane.io.rstb := io.clkRst.rxDividerRstb
    }
  }

  // The backup RX handoff phase, the mirror of the TX one: a tile updates its
  // word on the falling edge of `rxDivClk` and the queue samples it on the
  // rising edge, so this set samples on the other edge instead and the queue
  // reads a register in its own domain. Same one word period of added latency.
  val rxLaneDoutNeg = withClockAndReset(
    (!io.clkRst.rxDivClk.asBool).asClock,
    io.clkRst.rxDatapathRstSync
  ) {
    RegNext(rxLaneDout, 0.U.asTypeOf(chiselTypeOf(rxLaneDout)))
  }
  val rxLaneWord = VecInit(
    (0 until Phy.numRxDataLanes(numLanes)).map(lane =>
      Mux(
        io.regs.rxctl(lane).sample_negedge,
        rxLaneDoutNeg(lane),
        rxLaneDout(lane)
      )
    )
  )

  for (lane <- 0 until numLanes) {
    io.rx.data(lane) := rxLaneWord(lane)
  }
  io.rx.valid := rxLaneWord(Phy.validLane(numLanes))
  io.rx.track := rxLaneWord(Phy.trackLane(numLanes))
  io.debug.rxData := rxLaneDout

  // MBINIT.REPAIRCLK scoring, on the repair divided clock.
  val clkRepair = withClockAndReset(repairDivClk, repairDatapathRstSync) {
    Module(new ClkRepair)
  }
  clkRepair.io.word := repairTapWord
  clkRepair.io.windowWords := io.repair.windowWords
  clkRepair.io.gapThresh := io.repair.gapThresh
  clkRepair.io.capOffset := io.repair.capOffset
  clkRepair.io.capLane := io.repair.capLane

  // The counters, the word count and the capture are read straight across.
  // They are static by the time anything reads them: the reader closes the
  // gate first, and with no repair clock there is nothing to update them. See
  // `PhyRepairIO`.
  io.repair.wordsObserved := clkRepair.io.wordsObserved
  io.repair.obs := clkRepair.io.obs
  io.repair.capWord := clkRepair.io.capWord
  // `done` is the exception: it is polled while the window is still running,
  // so it gets a synchronizer.
  //
  // It is still the LAST window's answer for the few cycles a new window's
  // clear takes to reach this domain and come back. Masking that cannot be
  // done here, because nothing in this module knows when the request was
  // made -- `repairClkEn` arrives already late. The register block holds it
  // down instead, in the same cycle as the write that asked for the window.
  io.repair.done := withClockAndReset(io.clkRst.ucieClk, io.clkRst.ucieRst) {
    ShiftRegister(clkRepair.io.done, 2, false.B, true.B)
  }

}
