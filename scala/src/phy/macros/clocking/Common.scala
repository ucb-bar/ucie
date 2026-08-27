package edu.berkeley.cs.uciedigital.phy.macros.clocking

import chisel3._
import chisel3.util._

// Clocking, reset, and pad macros used by the PHY.
//
// Blackboxes that wrap an analog IP top cell use the cell's module and pin
// names verbatim so that the emitted Verilog instantiates the IP directly.
// Supplies (VDD/VSS) are pins on the IP but are omitted here; they are
// connected by the physical flow.

object ClkMux {

  // Clocks the observation mux can choose between. Each input hangs its own
  // pass gate off the shared internal node, so widening this loads every
  // source clock a little more.
  val numInputs = 16

  def selWidth: Int = log2Ceil(numInputs)
}

class ClkMuxIO extends Bundle {
  val Vin = Input(Vec(ClkMux.numInputs, Clock()))
  val sel = Input(Vec(ClkMux.numInputs, Bool()))
  val selb = Input(Vec(ClkMux.numInputs, Bool()))
  val Vout = Output(Clock())
}

// Single-ended `ClkMux.numInputs` to 1 clock mux cell.
//
// Every input drives a complementary pass gate onto one shared node, so `sel`
// has to be one-hot and `selb` its exact complement: any other combination
// either floats the node or shorts two clocks together. The shared output
// inverter means `Vout` is the inverse of the selected input, which does not
// matter for the observation-only uses this cell has. Use `connect` rather
// than driving `sel`/`selb` by hand.
class ClkMux(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkMuxIO)

  override val desiredName = "clkmux"

  if (includeDefaultModels) {
    addResource("/vsrc/clkmux.v")
  }

  // Passes `ins(sel)`, returning the muxed clock. Inputs past the end of `ins`
  // are tied off, and the one-hot the cell needs is decoded here so that an out
  // of range select turns every pass gate off rather than shorting two clocks.
  def connect(ins: Seq[Clock], sel: UInt): Clock = {
    require(
      ins.length <= ClkMux.numInputs,
      s"${ins.length} clocks does not fit a ${ClkMux.numInputs} input mux"
    )
    val oneHot = UIntToOH(sel, ClkMux.numInputs)
    for (i <- 0 until ClkMux.numInputs) {
      io.Vin(i) := ins.lift(i).getOrElse(false.B.asClock)
      io.sel(i) := oneHot(i) && (i < ins.length).B
      io.selb(i) := !io.sel(i)
    }
    io.Vout
  }
}

object ClockingTile {
  val phaseSelWidth = 64
  val freqSelWidth = 3
}

class ClockingTileIO extends Bundle {
  val PhaseSel = Input(UInt(ClockingTile.phaseSelWidth.W))
  val FreqSel = Input(UInt(ClockingTile.freqSelWidth.W))
  val DigBypassClk = Input(Clock())
  val BypassClk = Input(Clock())
  val DigitalClk = Output(Clock())
  val TxClkQ = Output(Clock())
  val TxClk = Output(Clock())
}

class ClockingTile(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClockingTileIO)
  override val desiredName = "clocking_tile"

  if (includeDefaultModels) {
    addResource("/vsrc/clocking_tile.v")
  }
}

class RstSyncIO extends Bundle {
  val clk = Input(Clock())
  val rstbAsync = Input(Bool())
  val rstbSync = Output(Bool())
}

class RstSync(implicit includeDefaultModels: Boolean = true)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new RstSyncIO)

  override val desiredName = "ucie_rst_sync"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_rst_sync.v")
  }
}

// Pad ESD clamp: back-to-back diodes from the protected net to each supply.
// `IO_signal` is a bidirectional pin on the IP; the digital side only needs to
// attach the net, so it is declared as an input here.
class Esd(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val IO_signal = Input(Bool())
  })

  override val desiredName = "IO_ESD"

  if (includeDefaultModels) {
    addResource("/vsrc/IO_ESD.v")
  }
}

class EsdRoutable(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val term = Input(Bool())
  })

  override val desiredName = "ucie_esd_routable"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_esd_routable.v")
  }
}

class ClkDiv4IO extends Bundle {
  val clk = Input(Clock())
  val resetb = Input(AsyncReset())
  val clkout_0 = Output(Clock())
  val clkout_1 = Output(Clock())
  val clkout_2 = Output(Clock())
  val clkout_3 = Output(Clock())
}

class ClkDiv4(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkDiv4IO)

  override val desiredName = "ucie_clk_div4"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_clk_div4.v")
  }
}
