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

  override val desiredName = "debug_clkmux"

  if (includeDefaultModels) {
    addResource("/vsrc/debug_clkmux.v")
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
  val mainClkSelWidth = 2
  val txClkDivWidth = 2
  val repairClkDivWidth = 2
  val txClkPhaseWidth = 4
  val digClkDivWidth = 3
  val sbClkDivWidth = 3
}

class ClockingTileIO extends Bundle {

  /** Global delay line on TXCLKQ, thermometer coded. */
  val PhaseSel = Input(UInt(ClockingTile.phaseSelWidth.W))

  /** Main clock source: 0 PLL8, 1 PLL12, 2 PLL16, 3 the analog bypass pin. Each
    * PLL is named for the clock it puts out, in GHz.
    */
  val MainClkSel = Input(UInt(ClockingTile.mainClkSelWidth.W))

  /** Per-PLL enables, so an unselected one can be powered down. */
  val Pll8En = Input(Bool())
  val Pll12En = Input(Bool())
  val Pll16En = Input(Bool())

  /** Per-PLL lock. Low while a PLL is off and from the moment it is enabled
    * until it has settled, so a configuration apply has something to hold the
    * clock gate across rather than counting out a guess.
    */
  val Pll8Lock = Output(Bool())
  val Pll12Lock = Output(Bool())
  val Pll16Lock = Output(Bool())

  /** TX clock division of the main clock: 0 /1, 1 /2, 2 /4, 3 /8. */
  val TxClkDiv = Input(UInt(ClockingTile.txClkDivWidth.W))

  /** TXCLKQ's shift from TXCLK, in main clock half cycles, 0 to 2*div-1. Half a
    * main clock period a step, which is inside the global delay line's range at
    * every rate, so coarse and fine together reach any phase.
    */
  val TxClkPhase = Input(UInt(ClockingTile.txClkPhaseWidth.W))

  /** MBINIT.REPAIRCLK sampling clock division of the main clock, same codes as
    * [[TxClkDiv]].
    *
    * Its own divider rather than a tap off the TX one because the ratio between
    * the two IS the oversample ratio the repair taps measure at:
    *
    * {{{N = 2^(txClkDiv - repairClkDiv)}}}
    *
    * With the lanes at /4 and this at /1 a tap takes four samples per
    * transmitted UI. Holding N at four across an 8 GHz and a 16 GHz main clock
    * takes two knobs, which is the whole reason this is not just "the main
    * clock".
    */
  val RepairClkDiv = Input(UInt(ClockingTile.repairClkDivWidth.W))

  /** Active-high enable for [[RepairClk]].
    *
    * Unlike [[ClkGateEn]] this branch has exactly one consumer, so it is dark
    * outside a REPAIRCLK window -- and that is not only a power argument. The
    * repair counters and the capture ring live on this clock and are read from
    * the digital domain as static values; they are static precisely because the
    * clock stops.
    */
  val RepairClkEn = Input(Bool())

  /** Digital clock division of the main clock: 0 /1, 1 /2, 2 /4, 3 /8, 4 /15 --
    * the ratios that land a little over 1 GHz from an 8 or 16 GHz main clock.
    * The divider stops on its own whenever the bypass pin is selected.
    */
  val DigClkDiv = Input(UInt(ClockingTile.digClkDivWidth.W))

  /** Takes the digital clock from the bypass pin rather than the divider. */
  val DigClkBypassEn = Input(Bool())

  /** Sideband division of the main clock: 0 /1, 1 /5, 2 /10, 3 /15, 4 /20. The
    * sideband runs at a rate the digital domain does not, so it divides the
    * same main clock separately.
    */
  val SbClkDiv = Input(UInt(ClockingTile.sbClkDivWidth.W))

  /** Takes the sideband clock from its own bypass pin. */
  val SbClkBypassEn = Input(Bool())

  /** Active-high enable for the TX clock outputs. Low holds TxClk and TxClkQ at
    * zero, which stops the clock reaching the TX lanes. DigitalClk is not
    * gated: the digital domain has to keep running to service the RX AFEs.
    */
  val ClkGateEn = Input(Bool())

  /** 100 MHz reference the PLLs lock to. */
  val RefClk = Input(Clock())
  val DigBypassClk = Input(Clock())
  val SbBypassClk = Input(Clock())
  val BypassClk = Input(Clock())
  val DigitalClk = Output(Clock())
  val SbClk = Output(Clock())
  val TxClkQ = Output(Clock())
  val TxClk = Output(Clock())
  val RepairClk = Output(Clock())
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
