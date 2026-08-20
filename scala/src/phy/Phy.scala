package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}
import edu.berkeley.cs.uciedigital.phy.macros._
import edu.berkeley.cs.uciedigital.phy.macros.clocking._

object Phy {
  val SerdesRatio = 32

  // Lanes that can be selected to carry the valid signal: the `numLanes` data
  // lanes, the dedicated valid lane, and the track lane. The forwarded clock
  // lanes are excluded since the RX side has no counterpart for them.
  def validLaneSelCount(numLanes: Int): Int = numLanes + 2

  // Width of a valid lane select field.
  def validLaneSelWidth(numLanes: Int): Int =
    log2Ceil(validLaneSelCount(numLanes))

  // Select code for the dedicated valid lane, i.e. no remapping. This is the
  // reset value of both selects.
  def dedicatedValidLaneSel(numLanes: Int): Int = numLanes

  // Select code for the track lane. The mainband protocol does not use track,
  // so moving valid there works around a broken valid lane without giving up a
  // data lane.
  def trackValidLaneSel(numLanes: Int): Int = numLanes + 1

  // Physical TX lane index carrying the valid signal.
  //
  // The TX lane order is data, valid, clk P, clk N, track, so only the track
  // code needs remapping. Out of range codes fall back to the dedicated valid
  // lane.
  def txValidLane(sel: UInt, numLanes: Int): UInt =
    MuxLookup(sel, dedicatedValidLaneSel(numLanes).U)(
      (0 to numLanes).map(s => s.U -> s.U) :+
        (trackValidLaneSel(numLanes).U -> (numLanes + 3).U)
    )

  // Physical RX lane index carrying the valid signal.
  //
  // The RX lane order is data, valid, track, which already matches the select
  // codes. Out of range codes fall back to the dedicated valid lane.
  def rxValidLane(sel: UInt, numLanes: Int): UInt =
    Mux(
      sel <= trackValidLaneSel(numLanes).U,
      sel,
      dedicatedValidLaneSel(numLanes).U
    )

  // Applies the valid lane remap, returning what each of the `numLanes + 4` TX
  // lanes should drive.
  //
  // The selected lane and the dedicated valid lane swap roles, so the
  // dedicated lane drives whatever the selected lane would have driven. Track
  // is the only spare lane, so selecting it keeps all `numLanes` data lanes;
  // selecting a data lane instead costs that lane's data. The forwarded clock
  // lanes are never remapped.
  def txValidRemap(tx: TxIO, sel: UInt, numLanes: Int): Vec[UInt] = {
    val validLane = txValidLane(sel, numLanes)
    val src = Wire(Vec(numLanes + 4, Bits(SerdesRatio.W)))
    for (lane <- 0 until numLanes) {
      src(lane) := tx.data(lane)
    }
    src(numLanes) := tx.valid
    src(numLanes + 1) := tx.clkp
    src(numLanes + 2) := tx.clkn
    src(numLanes + 3) := tx.track
    // Payload displaced onto the dedicated valid lane by the remap.
    val displaced = src(validLane)
    val din = Wire(Vec(numLanes + 4, Bits(SerdesRatio.W)))
    for (lane <- 0 until numLanes + 4) {
      if (lane == numLanes) {
        din(lane) := Mux(validLane === numLanes.U, tx.valid, displaced)
      } else if (lane == numLanes + 1 || lane == numLanes + 2) {
        din(lane) := src(lane)
      } else {
        din(lane) := Mux(validLane === lane.U, tx.valid, src(lane))
      }
    }
    din
  }

  // Undoes the remap the partner die's TX applied, recovering an `RxIO` from
  // the `numLanes + 2` RX lane outputs: valid comes off the selected lane, and
  // the selected lane's payload comes off the dedicated valid lane.
  def rxValidRemap(laneDout: Vec[UInt], sel: UInt, numLanes: Int): RxIO = {
    val validLane = rxValidLane(sel, numLanes)
    val rx = Wire(new RxIO(numLanes))
    rx.valid := laneDout(validLane)
    for (lane <- 0 until numLanes) {
      rx.data(lane) := Mux(
        validLane === lane.U,
        laneDout(numLanes),
        laneDout(lane)
      )
    }
    rx.track := Mux(
      validLane === trackValidLaneSel(numLanes).U,
      laneDout(numLanes),
      laneDout(numLanes + 1)
    )
    rx
  }
}

class TxIO(numLanes: Int = 16) extends Bundle {
  val data = Vec(numLanes, Bits(Phy.SerdesRatio.W))
  val valid = Bits(Phy.SerdesRatio.W)
  val clkp = Bits(Phy.SerdesRatio.W)
  val clkn = Bits(Phy.SerdesRatio.W)
  val track = Bits(Phy.SerdesRatio.W)
}

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

/** Clocks the PHY brings out for observation. */
class PhyDebugIO extends Bundle {
  val pllClk = Output(Bool())
  val fwdClk = Output(Bool())
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
  // Asynchronous reset for resetting clock dividers.
  val divResetb = Input(AsyncReset())

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
  // Lane control (`numLanes` data lanes, 1 valid lane, 2 clock lanes, 1 track lane).
  val txctl = Input(Vec(numLanes + 4, new TxLaneDigitalCtlIO))
  val dllCode = Output(Vec(numLanes + 4, UInt(5.W)))
  // Clocking tile control: phase code and frequency setting.
  val clkPhaseSel = Input(UInt(ClockingTile.phaseSelWidth.W))
  val clkFreqSel = Input(UInt(ClockingTile.freqSelWidth.W))
  // Low stops the TX clock reaching the TX lanes.
  val clkGateEn = Input(Bool())

  // RX CONTROL
  // Lane control (`numLanes` data lanes, 1 valid lane, 2 clock lanes, 1 track lane).
  val rxctl = Input(Vec(numLanes + 4, new RxLaneDigitalCtlIO))

  // VALID LANE REMAP
  // Physical lane carrying the valid signal, so that a broken valid lane does
  // not take the whole link down. Codes 0 to `numLanes - 1` select a data lane,
  // `Phy.dedicatedValidLaneSel` (the reset value) selects the dedicated valid
  // lane, and `Phy.trackValidLaneSel` selects the track lane.
  //
  // The TX and RX directions travel over different wires and are selected
  // independently: this die's `txValidLaneSel` must match the partner die's
  // `rxValidLaneSel`, and vice versa.
  val txValidLaneSel = Input(UInt(Phy.validLaneSelWidth(numLanes).W))
  val rxValidLaneSel = Input(UInt(Phy.validLaneSelWidth(numLanes).W))
}

class PhyIO(numLanes: Int = 16) extends Bundle {
  // DIGITAL INTERFACE
  // =====================
  val clkRst = new PhyClkRstIO
  val regs = new PhyRegsIO(numLanes)
  val tx = Input(new TxIO(numLanes))
  val rx = Output(new RxIO(numLanes))
  val sb = new SbIO
  // Debug interface.
  val debug = new PhyDebugIO

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
  clkTile.io.ClkGateEn := io.regs.clkGateEn

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

  // TODO do we need to set pu/pd ctl to 0 when driver en is low?
  // TODO decide on and connect debug signals
  io.debug := DontCare

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

  // Global clock dividers
  // TX
  val txClkDiv = Module(new ClkDiv4)
  txClkDiv.io.clk := clkDist.io.txClkDivClk
  txClkDiv.io.resetb := io.clkRst.divResetb
  io.clkRst.txDivClk := (!txClkDiv.io.clkout_3.asBool).asClock
  val txRstSync = Module(new RstSync)
  txRstSync.io.rstbAsync := !io.clkRst.reset
  txRstSync.io.clk := io.clkRst.txDivClk
  io.clkRst.txDivRst := !txRstSync.io.rstbSync
  // RX
  val rxClkDiv = Module(new ClkDiv4)
  rxClkDiv.io.clk := clkDist.io.rxClkDivClk
  rxClkDiv.io.resetb := io.clkRst.divResetb
  io.clkRst.rxDivClk := rxClkDiv.io.clkout_3
  val rxRstSync = Module(new RstSync)
  rxRstSync.io.rstbAsync := !io.clkRst.reset
  rxRstSync.io.clk := rxClkDiv.io.clkout_3
  io.clkRst.rxDivRst := !rxRstSync.io.rstbSync

  // TX valid lane remap. The select is quasi-static configuration, like the
  // rest of `io.regs`, and is used combinationally in the TX divided clock
  // domain.
  val txLaneDin = Phy.txValidRemap(io.tx, io.regs.txValidLaneSel, numLanes)

  // TX lanes
  for (lane <- 0 until numLanes + 4) {
    val laneName = if (lane < numLanes) {
      s"txdata$lane"
    } else if (lane == numLanes) {
      "txvalid"
    } else if (lane == numLanes + 1) {
      "txclkp"
    } else if (lane == numLanes + 2) {
      "txclkn"
    } else {
      "txtrack"
    }

    // Bit remap applied immediately before the serializer, so the permutation
    // is in terms of the physical lane and is unaffected by the valid lane
    // remap above.
    val txShuffler = Module(new Shuffler(Phy.SerdesRatio))
    txShuffler.suggestName(s"${laneName}_shuffler")
    txShuffler.io.din := txLaneDin(lane)
    txShuffler.io.permutation := io.regs.txctl(lane).shuffler

    val txLane = Module(new TxLane);
    txLane.suggestName(laneName);
    txLane.io.dll_reset := io.regs.txctl(lane).dll_reset
    txLane.io.dll_resetb := !io.regs.txctl(lane).dll_reset
    txLane.io.ser_resetb := io.clkRst.divResetb
    txLane.io.clk := clkDist.io.txLaneClk(lane)
    txLane.io.din := txShuffler.io.dout
    if (lane < numLanes) {
      io.top.txData(lane) := txLane.io.dout
    } else if (lane == numLanes) {
      io.top.txValid := txLane.io.dout
    } else if (lane == numLanes + 1) {
      io.top.txClkP := txLane.io.dout.asClock
    } else if (lane == numLanes + 2) {
      io.top.txClkN := txLane.io.dout.asClock
    } else {
      io.top.txTrack := txLane.io.dout
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
  // Lane outputs are collected before the valid lane remap is undone below.
  val rxLaneDout = Wire(Vec(numLanes + 2, Bits(Phy.SerdesRatio.W)))
  withClockAndReset(io.clkRst.ucieClk, io.clkRst.ucieRst) {
    // Set up clocking
    val rxClkP = Module(new RxClkLane)
    val rxClkPAfeCtl =
      RxAfeCtl.connect(rxClkP.io.ctl, io.regs.rxctl(numLanes + 1))
    rxClkP.io.clkin := io.top.rxClkP
    // The forwarded clock arrives as a bump pair, but everything past the
    // clock lanes is single-ended, so the distribution network is driven from
    // the P lane alone. The N lane still terminates its bump and carries its
    // own AFE control.
    clkDist.io.rxClk := rxClkP.io.clkout

    val rxClkN = Module(new RxClkLane)
    val rxClkNAfeCtl =
      RxAfeCtl.connect(rxClkN.io.ctl, io.regs.rxctl(numLanes + 2))
    rxClkN.io.clkin := io.top.rxClkN

    for (lane <- 0 until numLanes + 2) {
      val laneName = if (lane < numLanes) {
        s"rxdata$lane"
      } else if (lane == numLanes) {
        "rxvalid"
      } else {
        "rxtrack"
      }

      val rxLane = Module(new RxDataLane)
      val rxLaneAfeCtl = RxAfeCtl.connect(rxLane.io.ctl, io.regs.rxctl(lane))
      rxLane.suggestName(laneName)
      if (lane < numLanes) {
        rxLane.io.din := io.top.rxData(lane)
      } else if (lane == numLanes) {
        rxLane.io.din := io.top.rxValid
      } else {
        rxLane.io.din := io.top.rxTrack
      }

      // Bit remap applied immediately after the deserializer, mirroring the
      // TX side: the permutation is in terms of the physical lane, before the
      // valid lane remap is undone below.
      val rxShuffler = Module(new Shuffler(Phy.SerdesRatio))
      rxShuffler.suggestName(s"${laneName}_shuffler")
      rxShuffler.io.din := rxLane.io.dout
      rxShuffler.io.permutation := io.regs.rxctl(lane).shuffler

      rxLaneDout(lane) := rxShuffler.io.dout
      rxLane.io.clk := clkDist.io.rxLaneClk(lane)
      rxLane.io.resetb := io.clkRst.divResetb
    }
  }

  // RX valid lane remap, undoing the swap the partner die's TX applied.
  io.rx := Phy.rxValidRemap(rxLaneDout, io.regs.rxValidLaneSel, numLanes)

  // TODO: Move loopback to PhyTest
  // val txLoopbackFifo = Module(
  //   new AsyncQueue(UInt(Phy.SerdesRatio.W), Phy.QueueParams)
  // )
  // val loopbackShuffler = Module(new Shuffler32)
  // val txLoopbackLane = Module(new TxLane)
  // val txDivRstSync = Module(new RstSync)
  // txDivRstSync.io.rstbAsync := !reset.asBool
  // txDivRstSync.io.clk := txLoopbackLane.io.divclk
  // txLoopbackFifo.io.enq <> io.test.tx_loopback
  // txLoopbackFifo.io.enq_clock := clock
  // txLoopbackFifo.io.enq_reset := reset
  // txLoopbackFifo.io.deq_clock := txLoopbackLane.io.divclk.asClock
  // txLoopbackFifo.io.deq_reset := !txDivRstSync.io.rstbSync.asBool
  // txLoopbackFifo.io.deq.ready := true.B

  // when(txLoopbackFifo.io.deq.valid) {
  //   loopbackShuffler.io.din := txLoopbackFifo.io.deq.bits
  // }.otherwise {
  //   loopbackShuffler.io.din := 0.U
  // }
  // loopbackShuffler.io.permutation := io.txctl(numLanes + 4).shuffler

  // txLoopbackLane.io.dll_reset := io.txctl(numLanes + 4).dll_reset
  // txLoopbackLane.io.dll_resetb := !io.txctl(numLanes + 4).dll_reset
  // txLoopbackLane.io.ser_resetb := !reset.asBool
  // txLoopbackLane.io.clkp := txclkbuf0.io.voutp
  // txLoopbackLane.io.clkn := txclkbuf0.io.voutn
  // txLoopbackLane.io.din := loopbackShuffler.io.dout.asTypeOf(
  //   txLoopbackLane.io.din
  // )
  // txLoopbackLane.io.ctl.driver := io.txctl(numLanes + 4).driver
  // txLoopbackLane.io.ctl.skew := io.txctl(numLanes + 4).skew
  // io.dllCode(numLanes + 4) := txLoopbackLane.io.dll_code

  // val rxLoopbackLane = Module(new RxDataLane)
  // val rxLoopbackClkBuf = Module(new DiffBuffer)
  // val rxLoopbackLaneAfeCtl = Module(new RxAfeCtl())
  // val rxLoopbackFifo = Module(
  //   new AsyncQueue(UInt(Phy.SerdesRatio.W), Phy.QueueParams)
  // )
  // val rxDivRstSync = Module(new RstSync)
  // rxDivRstSync.io.rstbAsync := !reset.asBool
  // rxDivRstSync.io.clk := rxLoopbackLane.io.divclk
  // rxLoopbackFifo.io.enq.valid := true.B
  // rxLoopbackFifo.io.enq_reset := !rxDivRstSync.io.rstbSync.asBool
  // rxLoopbackFifo.io.deq_clock := clock
  // rxLoopbackFifo.io.enq_clock := rxLoopbackLane.io.divclk.asClock
  // rxLoopbackFifo.io.deq_reset := reset
  // rxLoopbackFifo.io.deq <> io.test.rx_loopback
  // rxLoopbackLane.io.din := txLoopbackLane.io.dout
  // rxLoopbackFifo.io.enq.bits := rxLoopbackLane.io.dout
  // rxLoopbackLane.io.ctl.zen := io.rxctl(numLanes + 4).zen
  // rxLoopbackLane.io.ctl.zctl := io.rxctl(numLanes + 4).zctl
  // rxLoopbackLane.io.ctl.vref_sel := io.rxctl(numLanes + 4).vref_sel
  // rxLoopbackLaneAfeCtl.io.bypass := io.rxctl(numLanes + 4).afeBypassEn
  // rxLoopbackLaneAfeCtl.io.afeBypass := io.rxctl(numLanes + 4).afeBypass
  // rxLoopbackLaneAfeCtl.io.opCycles := io.rxctl(numLanes + 4).afeOpCycles
  // rxLoopbackLaneAfeCtl.io.overlapCycles := io
  //   .rxctl(numLanes + 4)
  //   .afeOverlapCycles
  // rxLoopbackLane.io.ctl.afe := rxLoopbackLaneAfeCtl.io.afe
  // rxLoopbackClkBuf.io.vinp := txclkbuf0.io.voutp
  // rxLoopbackClkBuf.io.vinn := txclkbuf0.io.voutn
  // rxLoopbackLane.io.clk := rxLoopbackClkBuf.io.voutp.asClock
  // rxLoopbackLane.io.resetb := !reset.asBool
}
