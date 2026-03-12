package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}
import edu.berkeley.cs.uciedigital.phy.macros._

object Phy {
  val SerdesRatio = 32
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
  val refClkP = Input(Clock())
  val refClkN = Input(Clock())
  val bypassClkP = Input(Clock())
  val bypassClkN = Input(Clock())
  val digitalBypassClk = Input(Clock())
  val pllRdacVref = Input(Bool())
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
  val sample_negedge = Bool()
  val delay = UInt(7.W)
}

class PhyRegsIO(numLanes: Int = 16) extends Bundle {
  // TX CONTROL
  // Lane control (`numLanes` data lanes, 1 valid lane, 2 clock lanes, 1 track lane).
  val txctl = Input(Vec(numLanes + 4, new TxLaneDigitalCtlIO))
  val dllCode = Output(Vec(numLanes + 4, UInt(5.W)))
  val pllCtl = Input(new PllCtlIO)
  val pllOutput = Output(new PllDebugOutIO)
  // If 1, PHY uses bypass clk. If 0, PHY uses PLL clk.
  val pllBypassEn = Input(Bool())

  // RX CONTROL
  // Lane control (`numLanes` data lanes, 1 valid lane, 2 clock lanes, 1 track lane).
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
  // Debug interface.
  val debug = new PhyDebugIO

  // TOP INTERFACE
  // =====================
  val top = new PhyBumpsIO(numLanes)
}

class Phy(numLanes: Int = 16)(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new PhyIO(numLanes))

  // TODO: add clock selection logic.
  io.clkRst.ucieClk := io.top.digitalBypassClk
  val digitalRstSync = Module(new RstSync)
  digitalRstSync.io.rstbAsync := !io.clkRst.reset
  digitalRstSync.io.clk := io.clkRst.ucieClk
  io.clkRst.ucieRst := !digitalRstSync.io.rstbSync

  val clkDist = Module(new ClkDistNetwork)
  clkDist.io.bypassClkP := io.top.bypassClkP
  clkDist.io.bypassClkN := io.top.bypassClkN

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

  val pll = Module(new Pll)
  pll.io.vclk_ref := io.top.refClkP.asBool
  pll.io.vclk_refb := io.top.refClkN.asBool
  pll.io.ctl := io.regs.pllCtl
  pll.io.vrdac_ref := io.top.pllRdacVref
  io.regs.pllOutput := pll.io.debug

  val clkMuxP = Module(new ClkMux)
  clkMuxP.connect(
    clkDist.io.clkMuxP,
    io.regs.pllBypassEn
  )
  val clkMuxN = Module(new ClkMux)
  clkMuxN.connect(
    clkDist.io.clkMuxN,
    io.regs.pllBypassEn
  )

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

  // TX lanes
  for (lane <- 0 until numLanes + 4) {
    val txLane = Module(new TxLane);
    txLane.suggestName(if (lane < numLanes) {
      s"txdata$lane"
    } else if (lane == numLanes) {
      "txvalid"
    } else if (lane == numLanes + 1) {
      "txclkp"
    } else if (lane == numLanes + 2) {
      "txclkn"
    } else {
      "txtrack"
    });
    txLane.io.dll_reset := io.regs.txctl(lane).dll_reset
    txLane.io.dll_resetb := !io.regs.txctl(lane).dll_reset
    txLane.io.ser_resetb := io.clkRst.divResetb
    txLane.io.clkp := clkDist.io.txLaneClkP(lane)
    txLane.io.clkn := clkDist.io.txLaneClkN(lane)
    if (lane < numLanes) {
      txLane.io.din := io.tx.data(lane)
      io.top.txData(lane) := txLane.io.dout
    } else if (lane == numLanes) {
      txLane.io.din := io.tx.valid
      io.top.txValid := txLane.io.dout
    } else if (lane == numLanes + 1) {
      txLane.io.din := io.tx.clkp
      io.top.txClkP := txLane.io.dout.asClock
    } else if (lane == numLanes + 2) {
      txLane.io.din := io.tx.clkn
      io.top.txClkN := txLane.io.dout.asClock
    } else {
      txLane.io.din := io.tx.track
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
  withClockAndReset(io.clkRst.ucieClk, io.clkRst.ucieRst) {
    // Set up clocking
    val rxClkP = Module(new RxClkLane)
    val rxClkPAfeCtl =
      RxAfeCtl.connect(rxClkP.io.ctl, io.regs.rxctl(numLanes + 1))
    rxClkP.io.clkin := io.top.rxClkP
    clkDist.io.rxClkP := rxClkP.io.clkout

    val rxClkN = Module(new RxClkLane)
    val rxClkNAfeCtl =
      RxAfeCtl.connect(rxClkN.io.ctl, io.regs.rxctl(numLanes + 2))
    rxClkN.io.clkin := io.top.rxClkN
    clkDist.io.rxClkN := rxClkN.io.clkout

    for (lane <- 0 until numLanes + 2) {
      val rxLane = Module(new RxDataLane)
      val rxLaneAfeCtl = RxAfeCtl.connect(rxLane.io.ctl, io.regs.rxctl(lane))
      if (lane < numLanes) {
        rxLane.suggestName(s"rxdata$lane")
        rxLane.io.din := io.top.rxData(lane)
        io.rx.data(lane) := rxLane.io.dout
      } else if (lane == numLanes) {
        rxLane.suggestName(s"rxvalid")
        rxLane.io.din := io.top.rxValid
        io.rx.valid := rxLane.io.dout
      } else {
        rxLane.suggestName(s"rxtrack")
        rxLane.io.din := io.top.rxTrack
        io.rx.track := rxLane.io.dout
      }
      rxLane.io.clk := clkDist.io.rxLaneClk(lane)
      rxLane.io.resetb := io.clkRst.divResetb
    }
  }

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
