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

  // Clocks the observation mux can choose between.
  val numInputs = 16

  def selWidth: Int = log2Ceil(numInputs)

  // The select code that picks input `index`. The cell's select is Gray coded:
  // `sel` = `index ^ (index >> 1)` picks `Q[index]`.
  def gray(index: UInt): UInt = index ^ (index >> 1)
}

class ClkMuxIO extends Bundle {
  // Clocks or data, one bit per input. A bus rather than a `Vec` of clocks so
  // the port is `Q[15:0]`, as the cell names it.
  val Q = Input(UInt(ClkMux.numInputs.W))
  val sel = Input(UInt(ClkMux.selWidth.W))
  val clk_divided = Output(Clock())
}

// The 16:1 clock mux `mux16to1` from the analog clocking IP.
//
// A binary tree of fifteen 2:1 clock mux cells, `sel[0]` at the leaves and
// `sel[3]` at the root, wired so that the select is Gray coded: `sel` =
// `gray(i)` picks `Q[i]`. Every code picks some input, so there is no all-off
// state, and the output is not inverted. Use `connect` rather than driving
// `sel` by hand.
class ClkMux(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkMuxIO)

  override val desiredName = "mux16to1"

  if (includeDefaultModels) {
    addResource("/vsrc/mux16to1.v")
  }

  // Passes `ins(index)`, returning the muxed clock. `index` is the input
  // number; the Gray code the cell wants is made here. Inputs past the end of
  // `ins` are tied low, so selecting one gives a constant 0.
  def connect(ins: Seq[Clock], index: UInt): Clock = {
    require(
      ins.length <= ClkMux.numInputs,
      s"${ins.length} clocks does not fit a ${ClkMux.numInputs} input mux"
    )
    io.Q := VecInit(
      Seq.tabulate(ClkMux.numInputs)(i =>
        ins.lift(i).map(_.asBool).getOrElse(false.B)
      )
    ).asUInt
    io.sel := ClkMux.gray(index)
    io.clk_divided
  }
}

object ClockingTile {
  val phaseSelWidth = 64
  val mainClkSelWidth = 2
  val txClkDivWidth = 2
  val txClkPhaseWidth = 3
  val phyTestClkDivWidth = 3
  val phyTestClkSrcSelWidth = 2
  val sbClkDivWidth = 2
  val sbClkSrcSelWidth = 2
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

  /** Per-PLL enables, so an unselected one can be powered down. Either PLL can
    * also feed the PHY test and TX sideband dividers.
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

  /** TX data clock division of the UCIe main clock: 0 /1, 1 /2, 2 /4; 3 is
    * spare and selects /4 too.
    */
  val TxClkDiv = Input(UInt(ClockingTile.txClkDivWidth.W))

  /** The TX quadrature clock's shift from the TX data clock, in UCIe main clock
    * half cycles, 0 to 2*div-1. Half a main clock period a step, which is
    * inside the global delay line's ~69 ps range for any main clock from about
    * 7.2 GHz up -- every PLL rate -- so coarse and fine together reach any
    * phase. A slower main clock off the bypass pin makes steps the line cannot
    * bridge.
    */
  val TxClkPhase = Input(UInt(ClockingTile.txClkPhaseWidth.W))

  /** PHY test clock division of whichever source `PhyTestClkSrcSel` picks: 0
    * /1, 1 /2, 2 /4, 3 /6, 4 /8, 5 /12, 6 /15; 7 is spare and selects /15 too.
    * 1 GHz is /8 off PLL8 or /12 off PLL12, 800 MHz /15 off PLL12. The divider
    * stops on its own whenever the bypass pin is selected. It divides a PLL or
    * the analog bypass pin rather than the UCIe main clock, so nothing done to
    * the main clock reaches the PHY test clock. /15 goes through a duty cycle
    * corrector, so it is 50% like the even ratios.
    */
  val PhyTestClkDiv = Input(UInt(ClockingTile.phyTestClkDivWidth.W))

  /** What the PHY test divider divides: 0 PLL8, 1 PLL12, 2 the analog bypass
    * pin, for running the divider with no PLL; 3 is spare and selects the
    * analog bypass pin too.
    */
  val PhyTestClkSrcSel = Input(UInt(ClockingTile.phyTestClkSrcSelWidth.W))

  /** Takes the PHY test clock from the bypass pin rather than the divider. */
  val PhyTestClkBypassEn = Input(Bool())

  /** Active-high enable for the glitch-free gate after the PHY test clock's
    * bypass mux. Shut, it covers both the divider and the bypass pin, so the
    * source, the ratio or the bypass select can change behind it; the muxes
    * themselves are not glitch free.
    */
  val PhyTestClkEn = Input(Bool())

  /** TX sideband clock division of whichever source `SbClkSrcSel` picks: 0 /1,
    * 1 /5, 2 /10, 3 /15. 800 MHz is /10 off PLL8, /15 off PLL12. A divider of
    * its own, since the TX sideband clock runs at a rate the PHY test clock
    * does not. /5 and /15 go through a duty cycle corrector, so they are 50%
    * like the even ratios.
    */
  val SbClkDiv = Input(UInt(ClockingTile.sbClkDivWidth.W))

  /** What the TX sideband divider divides, coded as `PhyTestClkSrcSel`. */
  val SbClkSrcSel = Input(UInt(ClockingTile.sbClkSrcSelWidth.W))

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

// Die-to-die bump ESD clamp, the delivered `D2D_ESD`. Only the protected net
// is connected here; the physical flow connects the supplies.
class EsdRoutable(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val D2D_signal = Input(Bool())
  })

  override val desiredName = "D2D_ESD"

  if (includeDefaultModels) {
    addResource("/vsrc/D2D_ESD.v")
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
