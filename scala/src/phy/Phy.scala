package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.phy.macros._
import edu.berkeley.cs.uciedigital.phy.macros.clocking._

/** Lane numbering, the same in both directions: the `numLanes` data lanes,
  * then valid, track, and the two forwarded-clock lanes. TX has all
  * `numLanes + 4`. RX uses the same numbering, but only the first
  * `numLanes + 2` carry a word; its clock lanes recover a clock.
  *
  * Per-lane controls in [[PhyRegsIO]], lane clocks from `ClkDistNetwork`, taps
  * in [[PhyDebugIO]] and the bump fan-out all use this order. [[TxIO]] and
  * [[RxIO]] name their lanes instead.
  *
  * The PHY does not repair lanes. Moving valid to another lane is a test
  * function in `PhyTest`.
  */
object Phy {
  // Bits per lane per divided clock cycle, set by the TX tile's serializer.
  val SerdesRatio = TxLane.SerdesRatio

  // Lanes that can carry the valid waveform in a test: the data lanes, the
  // valid lane and the track lane. Not the clock lanes, which have no RX
  // counterpart. Codes equal the physical lane index for data and valid.
  // `trackValidLaneSel` names TX physical lane `numLanes + 3` and RX physical
  // lane `numLanes + 1`, because only TX has clock lanes in between.
  def validLaneSelCount(numLanes: Int): Int = numLanes + 2

  // Width of a valid lane select field.
  def validLaneSelWidth(numLanes: Int): Int =
    log2Ceil(validLaneSelCount(numLanes))

  // Default and reset value of both selects: the valid lane.
  def defaultValidLaneSel(numLanes: Int): Int = numLanes

  // Select code for the track lane. The mainband protocol doesn't use track,
  // so moving valid there works around a broken valid lane without losing a
  // data lane.
  def trackValidLaneSel(numLanes: Int): Int = numLanes + 1

  // Lane layout; see the numbering above.
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

  // The bit order a tile uses on the wire. The TX tile serializes through a
  // binary tree, so the bit sent in UI `t` is `din(treeBitOrder(t))`: the five
  // index bits reversed, giving D0 D16 D8 D24 D4 D20 ... The RX tile's tree is
  // the mirror image, so `dout(j)` is the bit received in UI `treeBitOrder(j)`.
  //
  // Each lane's shuffler applies this permutation to cancel its tile's tree;
  // both reset values do this. The mapping is its own inverse. Plain bit order
  // makes a lane interoperable and lets the RX realign on any word boundary:
  // after the shuffler, an offset capture is a rotation of the stream, not a
  // scramble.
  def treeBitOrder(t: Int): Int =
    (0 until 5).map(b => ((t >> b) & 1) << (4 - b)).sum

  // Maximum `PhyRegsIO.txUiDelay` and `rxUiDelay`: two words. Enough to move
  // any word boundary to any other, plus a word of lane-to-lane skew.
  val MaxUiDelay = 2 * SerdesRatio
  val uiDelayWidth = log2Ceil(MaxUiDelay + 1)

  // Delays a lane's stream by `delay` UI, using the two previous words. 0
  // returns `word` with no added latency; larger than `MaxUiDelay` is clamped.
  // Takes one word per cycle of the current clock.
  def delayUi(word: UInt, delay: UInt): UInt = {
    val prev = RegNext(word, 0.U)
    val prev2 = RegNext(prev, 0.U)
    val clamped =
      Mux(delay > MaxUiDelay.U, MaxUiDelay.U, delay)
    (Cat(word, prev, prev2) >> (MaxUiDelay.U - clamped))(SerdesRatio - 1, 0)
  }

  // Lane tile instance names, by role, so the physical flow and waveforms are
  // easier to read.
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

/** One deserialized word per RX lane that carries data, in [[TxIO]]'s order.
  * The forwarded-clock lanes recover a clock, so they have no entry.
  */
class RxIO(numLanes: Int = 16) extends Bundle {
  val data = Vec(numLanes, Bits(Phy.SerdesRatio.W))
  val valid = Bits(Phy.SerdesRatio.W)
  val track = Bits(Phy.SerdesRatio.W)
}

// Sideband bumps as the PHY sees them. On TX, the `SbDriver` on each bump does
// the 2:1 serialization, so the digital side provides two half-rate bits and
// their clock.
class SbIO extends Bundle {
  val txClk = Input(new SbSerialIO)
  val txData = Input(new SbSerialIO)
  val rxClk = Output(Clock())
  val rxData = Output(Bool())
}

/** Nets the PHY taps for observation: fanouts of existing link nets. The debug
  * bumps, their mux and their pad drivers are in `PhyTest`.
  */
class PhyDebugIO(numLanes: Int = 16) extends Bundle {
  // Lane clocks for the tester's own lanes (TX data debug, loopback TX,
  // loopback RX, in that order), from TxClk through the lane clock network.
  val testTxLaneClk = Output(Vec(ClkDistNetwork.numTestLanes, Clock()))
  // Full-rate TX and forwarded RX clocks for the clock observation pads.
  // Buffered taps, so the pad wires don't load the network roots.
  val testTxPadClk = Output(Clock())
  val testRxPadClk = Output(Clock())
  // TX global divided clock, i.e. the clock the TX lanes take their words on.
  val txDivClk = Output(Clock())
  // Sideband forwarded clock as transmitted, from whichever controller owns
  // the sideband.
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
  // The UCIe reset, from the clock register block on the chip clock, so
  // asynchronous to every PHY clock. Each UCIe clock domain synchronizes it
  // separately. The chip reset doesn't reach them.
  val reset = Input(Bool())
  // Divider resets, one per direction, from the clock register block. Only the
  // UCIe reset and the divider reset bit assert them, so the global divider
  // and every tile divider start together and keep a fixed phase. They are
  // asynchronous, so release them only with that direction's clock stopped
  // (`clkEn` low for TX, `rxClkEn` low for RX). Every divider then restarts on
  // the first edge after the clock returns.
  val txDividerRstb = Input(AsyncReset())
  val rxDividerRstb = Input(AsyncReset())
  // Resets only the clocking tile's TX data and quadrature dividers. They are
  // before the TX clock gate, so this reaches no lane. An apply uses it to
  // restart both together after a change that may upset their phase. It also
  // asserts with `txDividerRstb`.
  val txClkGenRstb = Input(AsyncReset())
  // Per-direction datapath resets for logic on the TX or RX divided clock
  // (serdes handoff registers, PHY side of the async queues), not the
  // dividers. The clock keeps running, so the reset applies and releases
  // synchronously.
  val txDatapathRst = Input(Bool())
  val rxDatapathRst = Input(Bool())

  // Clocking tile configuration. It decides where the PHY clocks come from, so
  // it lives in a register block on the chip clock and arrives here, not
  // through `PhyRegsIO`.
  val mainClkSel = Input(UInt(ClockingTile.mainClkSelWidth.W))
  val pll8En = Input(Bool())
  val pll12En = Input(Bool())
  // The X2 multiplier, which makes the 16 GHz PLL8 X2 clock from PLL8.
  val x2En = Input(Bool())
  val txClkDiv = Input(UInt(ClockingTile.txClkDivWidth.W))
  val txClkPhase = Input(UInt(ClockingTile.txClkPhaseWidth.W))
  val phyTestClkDiv = Input(UInt(ClockingTile.phyTestClkDivWidth.W))
  // Sources of the PHY test and TX sideband dividers: a PLL or the analog
  // bypass pin.
  val phyTestClkSrcSel = Input(UInt(ClockingTile.phyTestClkSrcSelWidth.W))
  val phyTestClkBypassEn = Input(Bool())
  val sbClkDiv = Input(UInt(ClockingTile.sbClkDivWidth.W))
  val sbClkSrcSel = Input(UInt(ClockingTile.sbClkSrcSelWidth.W))
  val sbClkBypassEn = Input(Bool())
  // Enables for the PHY test and TX sideband clocks, each at a glitch-free
  // gate after its bypass mux. Low stops the clock so its source, ratio or
  // bypass select can change safely.
  val phyTestClkEn = Input(Bool())
  val sbClkEn = Input(Bool())
  // Runs the RX lanes from the TX quadrature clock rather than the recovered
  // RX forwarded clock, for when the far side's clock is not behaving.
  val rxClkFromTxQ = Input(Bool())
  // The global delay line on the TX quadrature clock.
  val clkPhaseSel = Input(UInt(ClockingTile.phaseSelWidth.W))
  // Enables TxClk and TxClkQ at the TX clock gate after the global delay line.
  // Low stops both, so the source, ratio, phase or delay code can change with
  // no edge reaching a lane. The PHY test and sideband clocks keep running.
  val clkEn = Input(Bool())
  // The TX clock gate's tap select: 0 the tap that changes as TxClk rises, 1
  // the one half a period earlier. See `ClkGateEdgeSel`.
  val clkGateEdgeSel = Input(Bool())
  // Enables the recovered RX forwarded clock at the RX clock lanes. Low stops
  // the whole RX clock tree. When `rxClkFromTxQ` selects TxClkQ, it joins
  // after this gate, and `clkEn` stops it instead.
  val rxClkEn = Input(Bool())

  // Per-PLL lock from the clocking tile. After a change, the register block
  // waits for the PLL `mainClkSel` selects before opening the clocks. X2
  // counts as locked when PLL8 is.
  val pll8Lock = Output(Bool())
  val pll12Lock = Output(Bool())

  // PHY test clock (~1 GHz), the tile's `PhyTestClk`. Clocks PhyTest, its
  // register block and the lane controls. It must run whenever the RX AFEs
  // must be active: its source running and `phyTestClkEn` on. The TX clock
  // gate doesn't affect it.
  val phyTestClk = Output(Clock())
  // The UCIe reset for the PHY test clock domain, released against
  // `phyTestClk`.
  val phyTestRst = Output(Bool())

  // TX sideband clock (800 MHz), the tile's `SbClk`, and its reset. Clocks the
  // UCIe controller, its registers and the sideband serializers.
  val sbClk = Output(Clock())
  val sbRst = Output(Bool())

  val txDivClk = Output(Clock())
  // Reset for logic on `txDivClk`: the serdes handoff registers and the PHY
  // side of the async queues. Not a divider reset (see `txDividerRstb`).
  // Synchronized here because `txDivClk` exists only inside the PHY, but the
  // queues' divided-clock ends outside it need this reset too.
  val txDatapathRstSync = Output(Bool())

  val rxDivClk = Output(Clock())
  val rxDatapathRstSync = Output(Bool())
}

// Combinational bit remap of a serdes word: `dout(i)` is `din(permutation(i))`.
//
// One sits at each TX serializer input and each RX deserializer output, so
// software can fix a bit-order mismatch. Both reset to [[Phy.treeBitOrder]],
// which cancels the tile's serdes tree: bit 0 is then the first bit on the
// wire (TX) or the first received (RX). The identity permutation exposes the
// tree order. `permutation` need not be a bijection; repeating an index
// broadcasts that bit, which helps drive fixed patterns in bring-up.
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
}

class RxLaneDigitalCtlIO extends Bundle {
  // Delay taps on this lane's received data, after its clock tree replica,
  // thermometer coded. Moves where in the UI this lane samples; more taps
  // sample earlier. The clock lanes have no delay line, so theirs does
  // nothing.
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
}

class PhyRegsIO(numLanes: Int = 16) extends Bundle {
  // TX CONTROL
  // Per-tile lane control, one entry per lane in the layout order described on
  // `Phy`. Each `shuffler` is a bit permutation within its own lane.
  val txctl = Input(Vec(numLanes + 4, new TxLaneDigitalCtlIO))

  // RX CONTROL
  // Per-tile lane control, indexed like `txctl`. On the forwarded-clock lanes
  // only the AFE settings do anything.
  val rxctl = Input(Vec(numLanes + 4, new RxLaneDigitalCtlIO))
  // Per TX lane (indexed like `txctl`): whole-UI delay before the serializer,
  // 0 to `Phy.MaxUiDelay`. At 1, a word's last UI goes out first in the next
  // word. Removes whole-UI lane-to-lane skew at the far RX; the tile's `Dctrl`
  // handles fractions of a UI. A gated clock's postamble must cover the
  // largest data lane delay, or the end of a burst has no clock. On a
  // forwarded clock lane, an odd delay inverts the clock relative to the data.
  val txUiDelay =
    Input(Vec(Phy.numTxLanes(numLanes), UInt(Phy.uiDelayWidth.W)))
  // Per RX lane (data, valid, track): whole-UI delay after the shuffler, 0 to
  // `Phy.MaxUiDelay`. At 1, a word's last UI becomes the first UI of the next.
  // The RX word boundary depends on where the RX dividers left reset, so it
  // need not match the far TX's words. A controller that can't frame on its
  // own (PhyTest frames on valid) needs these set so each lane's words start
  // where the far TX's do; `align_rx_words` measures this with PhyTest.
  // Different values per lane remove whole-UI skew. After a gated burst, the
  // RX hands on its last word only after `rxUiDelay` more UI of forwarded
  // clock arrive.
  val rxUiDelay =
    Input(Vec(Phy.numRxDataLanes(numLanes), UInt(Phy.uiDelayWidth.W)))
}

class PhyIO(numLanes: Int = 16) extends Bundle {
  // DIGITAL INTERFACE
  // =====================
  val clkRst = new PhyClkRstIO
  val regs = new PhyRegsIO(numLanes)
  val tx = Input(new TxIO(numLanes))
  val rx = Output(new RxIO(numLanes))
  val sb = new SbIO
  // Observation taps for the tester's debug bumps.
  val debug = new PhyDebugIO(numLanes)

  // TOP INTERFACE
  // =====================
  val top = new PhyBumpsIO(numLanes)
}

class Phy(
    numLanes: Int = 16,
    clkDistLayout: ClkDistLayout = ClkDistLayout.Behavioral
)(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new PhyIO(numLanes))

  // Analog bypass clock pad: ESD clamp, then the clock receiver that restores
  // the single-ended pad signal to a full-swing clock.
  val bypassClkEsd = Module(new Esd)
  bypassClkEsd.io.IO_signal := io.top.bypassClk.asBool
  val bypassClkRx = Module(new ClkRx)
  bypassClkRx.io.Vin := io.top.bypassClk

  // Clocking tile: sources the PHY test, TX sideband, TX data, and TX
  // quadrature clocks.
  val clkTile = Module(new ClockingTile)
  clkTile.io.PhaseSel := io.clkRst.clkPhaseSel
  clkTile.io.MainClkSel := io.clkRst.mainClkSel
  clkTile.io.Pll8En := io.clkRst.pll8En
  clkTile.io.Pll12En := io.clkRst.pll12En
  clkTile.io.X2En := io.clkRst.x2En
  clkTile.io.TxClkDiv := io.clkRst.txClkDiv
  clkTile.io.TxClkPhase := io.clkRst.txClkPhase
  clkTile.io.PhyTestClkDiv := io.clkRst.phyTestClkDiv
  clkTile.io.PhyTestClkSrcSel := io.clkRst.phyTestClkSrcSel
  // The tile pin keeps the IP's name; `clkEn` is the same enable.
  clkTile.io.ClkGateEn := io.clkRst.clkEn
  clkTile.io.ClkGateEdgeSel := io.clkRst.clkGateEdgeSel
  clkTile.io.TxClkGenRstb := io.clkRst.txClkGenRstb
  clkTile.io.PhyTestClkBypassEn := io.clkRst.phyTestClkBypassEn
  clkTile.io.PhyTestClkEn := io.clkRst.phyTestClkEn
  clkTile.io.SbClkDiv := io.clkRst.sbClkDiv
  clkTile.io.SbClkSrcSel := io.clkRst.sbClkSrcSel
  clkTile.io.SbClkBypassEn := io.clkRst.sbClkBypassEn
  clkTile.io.SbClkEn := io.clkRst.sbClkEn
  clkTile.io.SbBypassClk := io.top.sidebandBypassClk
  clkTile.io.RefClk := io.top.refClk

  io.clkRst.pll8Lock := clkTile.io.Pll8Lock
  io.clkRst.pll12Lock := clkTile.io.Pll12Lock

  io.clkRst.phyTestClk := clkTile.io.PhyTestClk
  io.clkRst.sbClk := clkTile.io.SbClk
  // The TX sideband clock is its own domain, so its logic needs the UCIe reset
  // released on that clock, not the PHY test clock.
  val sbRstSync = Module(new RstSync)
  sbRstSync.io.rstbAsync := !io.clkRst.reset
  sbRstSync.io.clk := io.clkRst.sbClk
  io.clkRst.sbRst := !sbRstSync.io.rstbSync
  val phyTestRstSync = Module(new RstSync)
  phyTestRstSync.io.rstbAsync := !io.clkRst.reset
  phyTestRstSync.io.clk := io.clkRst.phyTestClk
  io.clkRst.phyTestRst := !phyTestRstSync.io.rstbSync

  // Lane clocks are single-ended; each tile makes its own differential pair.
  // The network sends TxClk to the data, valid and track lanes and TxClkQ to
  // the two forwarded-clock lanes. It also carries the analog and PHY test
  // bypass clocks into the clocking tile.
  val clkDist = Module(new ClkDistNetwork(clkDistLayout))
  clkDist.io.bypassClk := bypassClkRx.io.Vout
  clkDist.io.digitalBypassClk := io.top.digitalBypassClk
  clkTile.io.BypassClk := clkDist.io.tileBypassClk
  clkTile.io.DigBypassClk := clkDist.io.tileDigitalBypassClk
  clkDist.io.txClk := clkTile.io.TxClk
  clkDist.io.txClkQ := clkTile.io.TxClkQ
  // The tester's lanes and clock observation pads also take clocks from the
  // network, so nothing else loads its roots.
  io.debug.testTxLaneClk := clkDist.io.testTxLaneClk
  io.debug.testTxPadClk := clkDist.io.testTxPadClk
  io.debug.testRxPadClk := clkDist.io.testRxPadClk

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
  // The forwarded sideband clock as transmitted, after the bump driver.
  io.debug.sbTxClk := sbTxClk.io.out.asClock

  // Global clock dividers.
  //
  // These and the lane tile dividers share each direction's reset and start
  // on the same edge, so `clkout_3` runs in step with every tile's divided
  // clock. Words are handed over half a word period from where the tile acts:
  //  - A TX tile loads on its divided clock's rising edge, so the TX divided
  //    clock is `clkout_3` inverted.
  //  - An RX tile outputs on its divided clock's falling edge, so the RX
  //    divided clock is `clkout_3`.
  // TX
  // Each datapath reset comes from a flop, so the synchronizer input can't
  // glitch the way an OR of the UCIe reset and the strobe could. The flop is
  // set by the PHY test domain's reset (which asserts with the UCIe reset even
  // with no clock) and otherwise follows the strobe.
  val (txDatapathRstReg, rxDatapathRstReg) = withClockAndReset(
    io.clkRst.phyTestClk,
    io.clkRst.phyTestRst.asAsyncReset
  ) {
    (
      RegNext(io.clkRst.txDatapathRst, true.B),
      RegNext(io.clkRst.rxDatapathRst, true.B)
    )
  }
  val txClkDiv = Module(new ClkDiv4)
  txClkDiv.io.clk := clkDist.io.txClkDivClk
  txClkDiv.io.resetb := io.clkRst.txDividerRstb
  io.clkRst.txDivClk := (!txClkDiv.io.clkout_3.asBool).asClock
  val txRstSync = Module(new RstSync)
  txRstSync.io.rstbAsync := !txDatapathRstReg
  txRstSync.io.clk := io.clkRst.txDivClk
  io.clkRst.txDatapathRstSync := !txRstSync.io.rstbSync
  io.debug.txDivClk := io.clkRst.txDivClk
  // RX
  val rxClkDiv = Module(new ClkDiv4)
  rxClkDiv.io.clk := clkDist.io.rxClkDivClk
  rxClkDiv.io.resetb := io.clkRst.rxDividerRstb
  io.clkRst.rxDivClk := rxClkDiv.io.clkout_3
  val rxRstSync = Module(new RstSync)
  rxRstSync.io.rstbAsync := !rxDatapathRstReg
  rxRstSync.io.clk := io.clkRst.rxDivClk
  io.clkRst.rxDatapathRstSync := !rxRstSync.io.rstbSync

  // The TX words in lane order, for the uniform lane pipeline below.
  val txLaneDin = Wire(Vec(Phy.numTxLanes(numLanes), Bits(Phy.SerdesRatio.W)))
  for (lane <- 0 until numLanes) {
    txLaneDin(lane) := io.tx.data(lane)
  }
  txLaneDin(Phy.validLane(numLanes)) := io.tx.valid
  txLaneDin(Phy.trackLane(numLanes)) := io.tx.track
  txLaneDin(Phy.clkPLane(numLanes)) := io.tx.clkp
  txLaneDin(Phy.clkNLane(numLanes)) := io.tx.clkn
  val txLaneDelayed = withClockAndReset(
    io.clkRst.txDivClk,
    io.clkRst.txDatapathRstSync.asAsyncReset
  ) {
    VecInit(
      (0 until Phy.numTxLanes(numLanes)).map(lane =>
        Phy.delayUi(txLaneDin(lane), io.regs.txUiDelay(lane))
      )
    )
  }

  // Backup TX handoff phase. Normally the queue hands a word over on the rising
  // edge of `txDivClk` and the tile loads half a word period later, if the
  // clock distribution is as designed. Lanes with `sample_negedge` set
  // relaunch words on the other edge instead. Which edge is centered in the
  // load window is found in bring-up, so it is per lane. A moved lane sends
  // one word period later.
  val txLaneDinNeg = withClockAndReset(
    (!io.clkRst.txDivClk.asBool).asClock,
    io.clkRst.txDatapathRstSync.asAsyncReset
  ) {
    RegNext(txLaneDelayed, 0.U.asTypeOf(chiselTypeOf(txLaneDelayed)))
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
      txLaneDelayed(lane)
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
  // RX AFE control is on the PHY test clock to ensure that it is always
  // toggling, even when the RX forwarded clock is gated.
  //
  val rxLaneDout = Wire(
    Vec(Phy.numRxDataLanes(numLanes), Bits(Phy.SerdesRatio.W))
  )
  withClockAndReset(io.clkRst.phyTestClk, io.clkRst.phyTestRst.asAsyncReset) {
    // Set up clocking
    val rxClkP = Module(new RxClkLane)
    val rxClkPAfeCtl =
      RxAfeCtl.connect(rxClkP, io.regs.rxctl(Phy.clkPLane(numLanes)))
    rxClkP.io.clk_gate_en := io.clkRst.rxClkEn
    rxClkP.io.clkin := io.top.rxClkP
    // The RX forwarded clock is a bump pair, but everything after the clock
    // lanes is single-ended, so only the P lane drives the network. The N lane
    // still terminates its bump and has its own AFE control.
    // The RX clock is the RX forwarded clock or this die's TxClkQ after the
    // global delay line, so the RX samples on the phase the TX sends.
    clkDist.io.rxClk := Mux(
      io.clkRst.rxClkFromTxQ,
      clkTile.io.TxClkQ,
      rxClkP.io.clkout
    )

    val rxClkN = Module(new RxClkLane)
    val rxClkNAfeCtl =
      RxAfeCtl.connect(rxClkN, io.regs.rxctl(Phy.clkNLane(numLanes)))
    rxClkN.io.clk_gate_en := io.clkRst.rxClkEn
    rxClkN.io.clkin := io.top.rxClkN

    // Every lane that carries a word is the same: a deserializer, then a bit
    // shuffle.
    for (lane <- 0 until Phy.numRxDataLanes(numLanes)) {
      val laneName = Phy.laneName("rx", lane, numLanes)

      val rxLane = Module(new RxDataLane)
      val rxLaneAfeCtl = RxAfeCtl.connect(rxLane, io.regs.rxctl(lane))
      rxLane.suggestName(laneName)
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

  // Backup RX handoff phase, mirroring TX: the tile updates on the falling
  // edge of `rxDivClk` and the queue samples on the rising edge. This set
  // samples on the other edge instead. Adds one word period of latency.
  val rxLaneDoutNeg = withClockAndReset(
    (!io.clkRst.rxDivClk.asBool).asClock,
    io.clkRst.rxDatapathRstSync.asAsyncReset
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

  // The words the RX queues take each `rxDivClk` cycle, delayed in UI as they
  // came off the shufflers.
  val rxLaneDelayed = withClockAndReset(
    io.clkRst.rxDivClk,
    io.clkRst.rxDatapathRstSync.asAsyncReset
  ) {
    VecInit(
      (0 until Phy.numRxDataLanes(numLanes)).map(lane =>
        Phy.delayUi(rxLaneWord(lane), io.regs.rxUiDelay(lane))
      )
    )
  }

  for (lane <- 0 until numLanes) {
    io.rx.data(lane) := rxLaneDelayed(lane)
  }
  io.rx.valid := rxLaneDelayed(Phy.validLane(numLanes))
  io.rx.track := rxLaneDelayed(Phy.trackLane(numLanes))
  io.debug.rxData := rxLaneDout

}
