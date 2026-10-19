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
  val txClkPhaseWidth = 4
  val phyTestClkDivWidth = 3
  val sbClkDivWidth = 3
}

class ClockingTileIO extends Bundle {

  /** Global delay line on the TX quadrature clock, thermometer coded. */
  val PhaseSel = Input(UInt(ClockingTile.phaseSelWidth.W))

  /** UCIe main clock source: 0 PLL8, 1 PLL12, 2 the PLL8 X2 clock, 3 the analog
    * bypass pin. Each PLL is named for the clock it puts out, in GHz; the X2
    * multiplier makes 16 GHz from PLL8. The bypass pin can run at any rate up
    * to 16 GHz.
    */
  val MainClkSel = Input(UInt(ClockingTile.mainClkSelWidth.W))

  /** Per-PLL enables, so an unselected one can be powered down. PLL8 also feeds
    * the PHY test and TX sideband dividers.
    */
  val Pll8En = Input(Bool())
  val Pll12En = Input(Bool())

  /** Enables the X2 multiplier on PLL8's output. PLL8 has to be enabled too:
    * the X2 multiplier has nothing to double without it.
    */
  val X2En = Input(Bool())

  /** Per-PLL lock. Low while a PLL is off and from the moment it is enabled
    * until it has settled, so a configuration apply has something to hold the
    * clock gate across rather than counting out a guess. The X2 multiplier has
    * no lock of its own; it is ready once PLL8 is.
    */
  val Pll8Lock = Output(Bool())
  val Pll12Lock = Output(Bool())

  /** TX data clock division of the UCIe main clock: 0 /1, 1 /2, 2 /4, 3 /8. */
  val TxClkDiv = Input(UInt(ClockingTile.txClkDivWidth.W))

  /** The TX quadrature clock's shift from the TX data clock, in UCIe main clock
    * half cycles, 0 to 2*div-1. Half a main clock period a step, which is
    * inside the global delay line's range at every rate, so coarse and fine
    * together reach any phase.
    */
  val TxClkPhase = Input(UInt(ClockingTile.txClkPhaseWidth.W))

  /** PHY test clock division of the PLL8 clock: 0 /1, 1 /2, 2 /4, 3 /8, 4 /15;
    * /8 is 1 GHz. The divider stops on its own whenever the bypass pin is
    * selected. It divides PLL8 rather than the UCIe main clock, so nothing done
    * to the main clock reaches the PHY test clock.
    */
  val PhyTestClkDiv = Input(UInt(ClockingTile.phyTestClkDivWidth.W))

  /** Takes the PHY test clock from the bypass pin rather than the divider. */
  val PhyTestClkBypassEn = Input(Bool())

  /** Active-high enable for the glitch-free gate after the PHY test clock's
    * bypass mux. Shut, it covers both the divider and the bypass pin, so the
    * ratio or the bypass select can change behind it; the mux itself is not
    * glitch free.
    */
  val PhyTestClkEn = Input(Bool())

  /** TX sideband clock division of the PLL8 clock: 0 /1, 1 /5, 2 /10, 3 /15, 4
    * /20; /10 is 800 MHz. A divider of its own off the same PLL8 clock, since
    * the TX sideband clock runs at a rate the PHY test clock does not.
    */
  val SbClkDiv = Input(UInt(ClockingTile.sbClkDivWidth.W))

  /** Takes the TX sideband clock from its own bypass pin. */
  val SbClkBypassEn = Input(Bool())

  /** Active-high enable for the glitch-free gate after the TX sideband clock's
    * bypass mux, like `PhyTestClkEn`.
    */
  val SbClkEn = Input(Bool())

  /** Active-high enable for the glitch-free clock gate straight after the UCIe
    * main clock mux; the register that drives it is `clkEn`. Low stops the main
    * clock between pulses, so the TX divider stops and holds its count, and the
    * TX data and TX quadrature clocks both stop. The ratio and phase selects
    * take effect on main clock edges only, so changing them with the gate shut
    * moves no output. The PHY test and TX sideband clocks do not come from the
    * main clock and keep running, which is how the PHY test clock domain keeps
    * servicing the RX AFEs with the gate shut.
    */
  val ClkGateEn = Input(Bool())

  /** 100 MHz ref clock the PLLs lock to. */
  val RefClk = Input(Clock())
  // `DigBypassClk`, the PHY test bypass clock, keeps its old name: the clock
  // routing DEF routes it by that name.
  val DigBypassClk = Input(Clock())
  val SbBypassClk = Input(Clock())
  val BypassClk = Input(Clock())
  val PhyTestClk = Output(Clock())
  val SbClk = Output(Clock())
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
