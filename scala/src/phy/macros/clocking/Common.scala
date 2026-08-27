package edu.berkeley.cs.uciedigital.phy.macros.clocking

import chisel3._
import chisel3.util._

// Clocking, reset, and pad macros used by the PHY.
//
// Blackboxes that wrap an analog IP top cell use the cell's module and pin
// names verbatim so that the emitted Verilog instantiates the IP directly.
// Supplies (VDD/VSS) are pins on the IP but are omitted here; they are
// connected by the physical flow.

class ClkMuxIO extends Bundle {
  val Vinp = Input(Clock())
  val Vinn = Input(Clock())
  val sel = Input(Bool())
  val selb = Input(Bool())
  val Vout = Output(Clock())
}

// Single-ended 2:1 clock mux cell.
//
// `sel` and `selb` drive complementary pass gates and must be driven as true
// complements: `sel` high passes `Vinp`, `selb` high passes `Vinn`, and any
// other combination either floats or shorts the internal node. The shared
// output inverter means `Vout` is the inverse of the selected input, which
// does not matter for the observation-only uses this cell has. Use `connect`
// rather than driving `sel`/`selb` by hand.
class ClkMux(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkMuxIO)

  override val desiredName = "clkmux"

  if (includeDefaultModels) {
    addResource("/vsrc/clkmux.v")
  }

  // Passes `in0` when `sel1` is low and `in1` when it is high, returning the
  // muxed clock.
  def connect(in0: Clock, in1: Clock, sel1: Bool): Clock = {
    io.Vinn := in0
    io.Vinp := in1
    io.sel := sel1
    io.selb := !sel1
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
  // Active-high enable for the TX clock outputs. Low holds TxClk and TxClkQ
  // at zero, which stops the clock reaching the TX lanes. DigitalClk is not
  // gated: the digital domain has to keep running to service the RX AFEs.
  val ClkGateEn = Input(Bool())
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
