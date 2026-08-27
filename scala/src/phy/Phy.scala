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
  val SerdesRatio = 32

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

  // The order the TX tile puts a word on the wire. It serializes through an
  // adjacent-pairing binary tree, so the bit sent in UI `t` is
  // `din(treeBitOrder(t))` -- bit reversal of the five index bits, giving
  // D0 D16 D8 D24 D4 D20 ... rather than D0 D1 D2 D3.
  //
  // The RX tile deserializes in time order, so a lane's shuffler has to undo
  // this for a word to arrive as it was sent. `treeBitOrder` is its own
  // inverse, so applying it on either side cancels it.
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

class SbIO extends Bundle {
  val txClk = Input(Clock())
  val txData = Input(Bool())
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
}

// PHY clock and reset IOs.
class PhyClkRstIO extends Bundle {
  // Main digital reset, asynchronous to PHY clocks.
  val reset = Input(Bool())
  // Asynchronous reset for the RX clock divider.
  val divResetb = Input(AsyncReset())
  // Asynchronous resets for the lane serdes, so that a test can restart one
  // direction without disturbing the other. `txResetb` also holds the TX clock
  // divider, so the serializers and the divided clock they hand words over on
  // come back up together rather than at an arbitrary relative phase.
  val txResetb = Input(AsyncReset())
  val rxResetb = Input(AsyncReset())

  // UCIe digital clock (800 MHz).
  //
  // Should always be toggling when RX AFEs must be active.
  val ucieClk = Output(Clock())
  // UCIe digital reset (synchronous to `clk`).
  val ucieRst = Output(Bool())

  val txDivClk = Output(Clock())
  val txDivRst = Output(Bool())

  val rxDivClk = Output(Clock())
  val rxDivRst = Output(Bool())
}

// Combinational bit remap of a serdes word: `dout(i)` is driven by
// `din(permutation(i))`.
//
// One sits on each TX lane's serializer input and each RX lane's
// deserializer output so that a bit ordering mismatch between the digital
// word and the analog tile (or between the two dies) can be corrected from
// software. The identity permutation (`permutation(i) = i`) is the reset
// value and leaves the word untouched.
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
  val dll_reset = Bool()
  val driver = new DriverCtlIO
  val skew = new SkewCtlIO
  val shuffler = Vec(32, UInt(5.W))
  val sample_negedge = Bool()
  val delay = UInt(7.W)
}

class RxLaneDigitalCtlIO extends Bundle {
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

class PhyRegsIO(numLanes: Int = 16) extends Bundle {
  // TX CONTROL
  // Per-tile lane control, one entry per lane in the layout order described on
  // `Phy`. Each `shuffler` is a bit permutation within its own lane.
  val txctl = Input(Vec(numLanes + 4, new TxLaneDigitalCtlIO))
  // DLL codes read back per physical lane, same order as `txctl`.
  val dllCode = Output(Vec(numLanes + 4, UInt(5.W)))
  // Clocking tile control: phase code and frequency setting.
  val clkPhaseSel = Input(UInt(ClockingTile.phaseSelWidth.W))
  val clkFreqSel = Input(UInt(ClockingTile.freqSelWidth.W))

  // RX CONTROL
  // Per-tile lane control, indexed exactly like `txctl`. The two
  // forwarded-clock lanes recover a clock rather than a word, so only their AFE
  // settings do anything.
  val rxctl = Input(Vec(numLanes + 4, new RxLaneDigitalCtlIO))
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
  clkTile.io.PhaseSel := io.regs.clkPhaseSel
  clkTile.io.FreqSel := io.regs.clkFreqSel

  io.clkRst.ucieClk := clkTile.io.DigitalClk
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
  // The tester's own TX lanes sit outside the distribution network, so they
  // take the in-phase clock from the same place the network does.
  io.debug.txClk := clkTile.io.TxClk

  // TODO do we need to set pu/pd ctl to 0 when driver en is low?

  // Set up sideband
  val sbTxClk = Module(new TxDriver)
  sbTxClk.io.din := io.sb.txClk.asBool
  io.top.sbTxClk := sbTxClk.io.dout.asClock
  sbTxClk.io.ctl.pu_ctl := 63.U
  sbTxClk.io.ctl.pd_ctl := 63.U
  sbTxClk.io.ctl.en := true.B
  sbTxClk.io.ctl.en_b := false.B
  val sbTxData = Module(new TxDriver)
  sbTxData.io.din := io.sb.txData
  io.top.sbTxData := sbTxData.io.dout
  sbTxData.io.ctl.pu_ctl := 63.U
  sbTxData.io.ctl.pd_ctl := 63.U
  sbTxData.io.ctl.en := true.B
  sbTxData.io.ctl.en_b := false.B
  val esdSbRxClk = Module(new EsdRoutable)
  val esdSbRxData = Module(new EsdRoutable)
  esdSbRxClk.io.term := io.top.sbRxClk.asBool
  esdSbRxData.io.term := io.top.sbRxData.asBool
  io.sb.rxClk := io.top.sbRxClk
  io.sb.rxData := io.top.sbRxData
  io.debug.sbTxClk := io.sb.txClk

  // Global clock dividers
  // TX
  val txClkDiv = Module(new ClkDiv4)
  txClkDiv.io.clk := clkDist.io.txClkDivClk
  txClkDiv.io.resetb := io.clkRst.txResetb
  io.clkRst.txDivClk := (!txClkDiv.io.clkout_3.asBool).asClock
  val txRstSync = Module(new RstSync)
  txRstSync.io.rstbAsync := !io.clkRst.reset
  txRstSync.io.clk := io.clkRst.txDivClk
  io.clkRst.txDivRst := !txRstSync.io.rstbSync
  io.debug.txDivClk := io.clkRst.txDivClk
  // RX
  val rxClkDiv = Module(new ClkDiv4)
  rxClkDiv.io.clk := clkDist.io.rxClkDivClk
  rxClkDiv.io.resetb := io.clkRst.divResetb
  io.clkRst.rxDivClk := rxClkDiv.io.clkout_3
  val rxRstSync = Module(new RstSync)
  rxRstSync.io.rstbAsync := !io.clkRst.reset
  rxRstSync.io.clk := rxClkDiv.io.clkout_3
  io.clkRst.rxDivRst := !rxRstSync.io.rstbSync

  // The TX words in lane order, for the uniform lane pipeline below.
  val txLaneDin = Wire(Vec(Phy.numTxLanes(numLanes), Bits(Phy.SerdesRatio.W)))
  for (lane <- 0 until numLanes) {
    txLaneDin(lane) := io.tx.data(lane)
  }
  txLaneDin(Phy.validLane(numLanes)) := io.tx.valid
  txLaneDin(Phy.trackLane(numLanes)) := io.tx.track
  txLaneDin(Phy.clkPLane(numLanes)) := io.tx.clkp
  txLaneDin(Phy.clkNLane(numLanes)) := io.tx.clkn

  // TX lanes. Every lane is the same: a bit shuffle, then a serializer.
  for (lane <- 0 until Phy.numTxLanes(numLanes)) {
    val laneName = Phy.laneName("tx", lane, numLanes)

    // Bit remap applied immediately before the serializer, so the permutation
    // is in terms of the lane's own word.
    val txShuffler = Module(new Shuffler(Phy.SerdesRatio))
    txShuffler.suggestName(s"${laneName}_shuffler")
    txShuffler.io.din := txLaneDin(lane)
    txShuffler.io.permutation := io.regs.txctl(lane).shuffler

    val txLane = Module(new TxLane);
    txLane.suggestName(laneName);
    txLane.io.dll_reset := io.regs.txctl(lane).dll_reset
    txLane.io.dll_resetb := !io.regs.txctl(lane).dll_reset
    txLane.io.ser_resetb := io.clkRst.txResetb
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
    txLane.io.ctl.driver := io.regs.txctl(lane).driver
    txLane.io.ctl.skew := io.regs.txctl(lane).skew
    io.regs.dllCode(lane) := txLane.io.dll_code
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
      RxAfeCtl.connect(rxClkP.io.ctl, io.regs.rxctl(Phy.clkPLane(numLanes)))
    rxClkP.io.clkin := io.top.rxClkP
    // The forwarded clock arrives as a bump pair, but everything past the
    // clock lanes is single-ended, so the distribution network is driven from
    // the P lane alone. The N lane still terminates its bump and carries its
    // own AFE control.
    clkDist.io.rxClk := rxClkP.io.clkout
    io.debug.rxClk := rxClkP.io.clkout

    val rxClkN = Module(new RxClkLane)
    val rxClkNAfeCtl =
      RxAfeCtl.connect(rxClkN.io.ctl, io.regs.rxctl(Phy.clkNLane(numLanes)))
    rxClkN.io.clkin := io.top.rxClkN

    // Every lane that carries a word is the same: a deserializer, then a bit
    // shuffle.
    for (lane <- 0 until Phy.numRxDataLanes(numLanes)) {
      val laneName = Phy.laneName("rx", lane, numLanes)

      val rxLane = Module(new RxDataLane)
      val rxLaneAfeCtl = RxAfeCtl.connect(rxLane.io.ctl, io.regs.rxctl(lane))
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
      rxShuffler.io.din := rxLane.io.dout
      rxShuffler.io.permutation := io.regs.rxctl(lane).shuffler

      rxLaneDout(lane) := rxShuffler.io.dout
      rxLane.io.clk := clkDist.io.rxLaneClk(lane)
      rxLane.io.resetb := io.clkRst.rxResetb
    }
  }

  for (lane <- 0 until numLanes) {
    io.rx.data(lane) := rxLaneDout(lane)
  }
  io.rx.valid := rxLaneDout(Phy.validLane(numLanes))
  io.rx.track := rxLaneDout(Phy.trackLane(numLanes))
  io.debug.rxData := rxLaneDout

}
