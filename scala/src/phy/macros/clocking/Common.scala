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
  val txClkPhaseWidth = 3
  val phyTestClkDivWidth = 3
  val phyTestClkSrcSelWidth = 2
  val sbClkDivWidth = 2
  val sbClkSrcSelWidth = 2

  /** What the tile ties one PLL's loop settings to. They have no registers, so
    * these are what the part runs with. `Dcoarseb` is tied to the complement
    * of `dcoarse`. `dbias` is the RDAC code the PLL runs on while enabled; the
    * tile drives zero there to turn the PLL off, so it has to be nonzero.
    */
  case class PllSettings(
      autoEn: Boolean,
      kp: Int,
      ki: Int,
      codeInit: Int,
      codeByp: Int,
      dbias: Int,
      dcoarse: Int
  )

  /** Placeholders until the PLL designer supplies the nominal settings: every
    * setting zero but the RDAC, at mid-scale so that an enabled PLL runs. The
    * behavioral PLL models only look at whether the RDAC is zero.
    */
  val pll8Settings = PllSettings(false, 0, 0, 0, 0, 0x100, 0)
  val pll12Settings = PllSettings(false, 0, 0, 0, 0, 0x100, 0)
}

/** The clocking tile's controls and clocks.
  *
  * These were once the pins of a single macro. The tile is now six -- the two
  * PLLs, the TX clock generator, the global delay line and its gate, and the
  * PHY test and TX sideband dividers -- and `ClockingTile` wires them up behind
  * this same interface, translating each control into the pins of the macro it
  * reaches.
  */
class ClockingTileIO extends Bundle {

  /** Global delay line on the TX quadrature clock, thermometer coded. The line
    * sits ahead of the TX clock gate, which an apply shuts across a change.
    */
  val PhaseSel = Input(UInt(ClockingTile.phaseSelWidth.W))

  /** UCIe main clock source: 0 PLL8, 1 PLL12, 2 the PLL8 X2 clock, 3 the analog
    * bypass pin. Each PLL is named for the clock it puts out, in GHz; the X2
    * multiplier makes 16 GHz from PLL8. The bypass pin can run at any rate up
    * to 16 GHz.
    */
  val MainClkSel = Input(UInt(ClockingTile.mainClkSelWidth.W))

  /** Per-PLL enables, so an unselected one can be powered down. Either PLL can
    * also feed the PHY test and TX sideband dividers. Low drives the PLL's RDAC
    * (`Dbias`) to zero, which is what turns it off, and holds its digital loop
    * in `reset`, which on its own only stops the loop.
    */
  val Pll8En = Input(Bool())
  val Pll12En = Input(Bool())

  /** Kept for the register map, and drives nothing: the X2 multiplier inside
    * `main_clk_sel_core` has no enable, and doubles PLL8 whenever PLL8 runs.
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

  /** The TX quadrature clock's lead over the TX data clock, in UCIe main clock
    * half cycles, 0 to 2*div-1. Half a main clock period a step, which is
    * inside the global delay line's ~69 ps range for any main clock from about
    * 7.2 GHz up -- every PLL rate -- so coarse and fine together reach any
    * phase. A slower main clock off the bypass pin makes steps the line cannot
    * bridge. At /1 there is no divider to rotate, and the TX quadrature clock
    * is the main clock whatever this says.
    */
  val TxClkPhase = Input(UInt(ClockingTile.txClkPhaseWidth.W))

  /** PHY test clock division of whichever source `PhyTestClkSrcSel` picks: 3
    * /6, 4 /8, 5 /12, 6 /15; 7 is spare and selects /15 too. The divider has
    * no /1, /2 or /4, so 0, 1 and 2 select /8, the reset ratio. 1 GHz is /8 off
    * PLL8 or /12 off PLL12, 800 MHz /15 off PLL12. It divides a PLL or the
    * analog bypass pin rather than the UCIe main clock, so nothing done to the
    * main clock reaches the PHY test clock. /15 has no duty cycle corrector:
    * `PhyTestClk` is high for 9 source cycles of the 15 and low for 6.
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
    *
    * The gate takes the enable through three flops on the falling edge of the
    * clock it gates, so a change lands three to four of that clock's periods
    * later, and only while that clock runs. Shut, `PhyTestClk` sits high.
    */
  val PhyTestClkEn = Input(Bool())

  /** TX sideband clock division of whichever source `SbClkSrcSel` picks: 1 /5,
    * 2 /10, 3 /15. The divider has no /1, so 0 selects /10, the reset ratio.
    * 800 MHz is /10 off PLL8, /15 off PLL12. A divider of its own, since the
    * TX sideband clock runs at a rate the PHY test clock does not. /5 and /15
    * have no duty cycle corrector: `SbClk` is high for 3 source cycles of the 5
    * and for 10 of the 15.
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

  /** Active-high enable for the TX clock gate; the register that drives it is
    * `clkEn`. The gate is two, after the global delay line: the TX data clock's
    * latches this while that clock is low, and the TX quadrature clock's
    * latches what the first latched while the delayed quadrature clock is low,
    * so the quadrature clock stops and starts after the data clock. Low stops
    * both between pulses. The TX dividers in front keep running, so a source,
    * a ratio, a phase, or a delay code can change behind it with no edge
    * reaching a lane. The PHY test and TX sideband clocks do not come from the
    * main clock and keep running, which is how the PHY test clock domain keeps
    * servicing the RX AFEs with the gate shut.
    */
  val ClkGateEn = Input(Bool())

  /** Shared reset of the TX data and quadrature dividers, the ones that make
    * the TX data and TX quadrature clocks from the main clock. Held, both stop;
    * released, both restart on one main clock edge, so the quadrature clock's
    * lead is the one `TxClkPhase` asks for rather than whatever the two
    * dividers happened to power up in or a glitch left them in. They sit ahead
    * of the TX clock gate, so this reaches no lane.
    */
  val TxClkGenRstb = Input(AsyncReset())

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

/** The clocking tile: the two PLLs, the TX clock generator, the global delay
  * line and TX clock gate, and the PHY test and TX sideband dividers, wired up
  * behind `ClockingTileIO`.
  *
  * Each PLL feeds the TX clock generator and both dividers; the analog bypass
  * pin does too. Every control is translated here into the pins and codes of
  * the macro it reaches, so nothing outside sees which macro has which pin or
  * how its codes run.
  */
class ClockingTile(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = FlatIO(new ClockingTileIO)
  // Reaches no macro, but kept, with the register behind it, so the X2
  // enable can still be watched at the tile, and a doubler that grows an
  // enable has one to take.
  dontTouch(io.X2En)

  // A PLL is off with its RDAC at zero, and `reset` holds only its digital
  // loop, so the enable drives both.
  def enable(pll: PllIO, en: Bool, s: ClockingTile.PllSettings): Unit = {
    require(s.dbias != 0, "an RDAC code of zero leaves the PLL off")
    pll.reset := !en
    pll.Dbias := Mux(en, s.dbias.U, 0.U)
    pll.auto_en := s.autoEn.B
    pll.kp := s.kp.U
    pll.ki := s.ki.U
    pll.code_init := s.codeInit.U
    pll.code_byp := s.codeByp.U
  }

  val pll8 = Module(new Pll8g)
  pll8.io.clk_ref := io.RefClk
  enable(pll8.io, io.Pll8En, ClockingTile.pll8Settings)
  pll8.io.Dcoarse := ClockingTile.pll8Settings.dcoarse.U
  pll8.io.Dcoarseb := ~ClockingTile.pll8Settings.dcoarse.U(6.W)
  io.Pll8Lock := pll8.io.lock

  val pll12 = Module(new Pll12g)
  pll12.io.clk_ref := io.RefClk
  enable(pll12.io, io.Pll12En, ClockingTile.pll12Settings)
  io.Pll12Lock := pll12.io.lock

  // TX clock generator. The cell's main clock codes run PLL12, PLL8, X2,
  // bypass, so PLL8 and PLL12 trade places.
  val txClkGen = Module(new MainClkSelCore)
  txClkGen.io.PLLclk_8G := pll8.io.clk_out
  txClkGen.io.PLLclk_12G := pll12.io.clk_out
  txClkGen.io.clk_bypass := io.BypassClk
  txClkGen.io.mainClkSel := Cat(
    io.MainClkSel(1),
    io.MainClkSel(0) ^ !io.MainClkSel(1)
  )
  // The cell's division codes run /2, /1, off, /4.
  val txClkSel = MuxLookup(io.TxClkDiv, 3.U)(Seq(0.U -> 1.U, 1.U -> 0.U))
  txClkGen.io.TxClkSel := txClkSel
  txClkGen.io.TxClkQSel := txClkSel
  // The cell sets the quadrature clock's lag behind the data clock: whole main
  // clock cycles by rotating its divider, and half of one by running that
  // divider off the main clock's other edge (`S2DSel` low). `TxClkPhase` is a
  // lead in half cycles, so the lag is its negative, modulo the 2*div half
  // cycles of a period: three bits wrap at /4's eight, and the low two at /2's
  // four. Both dividers get their share; only the selected one is seen.
  val txClkLag = 0.U(ClockingTile.txClkPhaseWidth.W) - io.TxClkPhase
  txClkGen.io.TxClkQDiv2PhaseSel := txClkLag(1)
  txClkGen.io.TxClkQDiv2PhaseS2DSel := !txClkLag(0)
  txClkGen.io.TxClkQDiv2PhaseS2DSel_B := txClkLag(0)
  txClkGen.io.TxClkQDiv4PhaseSel := txClkLag(2, 1)
  txClkGen.io.TxClkQDiv4PhaseS2DSel := !txClkLag(0)
  txClkGen.io.TxClkQDiv4PhaseS2DSel_B := txClkLag(0)
  txClkGen.io.reset := !io.TxClkGenRstb.asBool

  // Global delay line on the TX quadrature clock, and the TX clock gate on
  // both clocks after it.
  val txClkGate = Module(new GlobalDelaylineGate)
  txClkGate.io.Dctrl := io.PhaseSel
  txClkGate.io.Vin := txClkGen.io.TxClkQ
  txClkGate.io.VinData := txClkGen.io.TxClk
  txClkGate.io.enable := io.ClkGateEn
  io.TxClkQ := txClkGate.io.Vout
  io.TxClk := txClkGate.io.VoutData

  // PHY test divider. Its ratio codes run /6, /8, /15, /12; the bypass select
  // is the top bit, low for the bypass pin.
  val phyTestDiv = Module(new PhyTestDivCore)
  phyTestDiv.io.PLLclk_8G := pll8.io.clk_out
  phyTestDiv.io.PLLclk_12G := pll12.io.clk_out
  phyTestDiv.io.clk_bypass_analog := io.BypassClk
  phyTestDiv.io.src_sel := io.PhyTestClkSrcSel
  phyTestDiv.io.clk_bypass := io.DigBypassClk
  phyTestDiv.io.sel := Cat(
    !io.PhyTestClkBypassEn,
    MuxLookup(io.PhyTestClkDiv, 1.U(2.W))(
      Seq(3.U -> 0.U, 5.U -> 3.U, 6.U -> 2.U, 7.U -> 2.U)
    )
  )
  phyTestDiv.io.phyTestClkEn := io.PhyTestClkEn
  io.PhyTestClk := phyTestDiv.io.PhyTestClk

  // TX sideband divider. Its codes run /5, /10, bypass, /15.
  val sbDiv = Module(new SbDivCore)
  sbDiv.io.PLLclk_8G := pll8.io.clk_out
  sbDiv.io.PLLclk_12G := pll12.io.clk_out
  sbDiv.io.clk_bypass_analog := io.BypassClk
  sbDiv.io.sb_src_sel := io.SbClkSrcSel
  sbDiv.io.clk_bypass := io.SbBypassClk
  sbDiv.io.sb_sel := Mux(
    io.SbClkBypassEn,
    2.U,
    MuxLookup(io.SbClkDiv, 1.U(2.W))(Seq(1.U -> 0.U, 3.U -> 3.U))
  )
  sbDiv.io.sbClkEn := io.SbClkEn
  io.SbClk := sbDiv.io.SbClk
}

// The clocking tile's macros. Each takes its cell's module and pin names, so
// any pin marked as not on the cell yet is one the cell has to grow, under
// that name, before the emitted Verilog will connect to it.

/** The pins `8gpll_top` and `12gpll_top` have in common: the ref clock, the
  * digital loop's reset and settings, and the RDAC, whose code powers the PLL:
  * zero is off. `ClockingTile` drives the reset and the RDAC from the enable
  * and ties the rest off.
  */
class PllIO extends Bundle {
  val clk_ref = Input(Clock())
  val reset = Input(Bool())
  val auto_en = Input(Bool())
  val kp = Input(UInt(16.W))
  val ki = Input(UInt(16.W))
  val code_init = Input(UInt(8.W))
  val code_byp = Input(UInt(20.W))
  val Dbias = Input(UInt(9.W))
  val code_full = Output(UInt(20.W))
  val code_int = Output(UInt(8.W))
  // Not on the cell yet: the PLL's output, and its lock.
  val clk_out = Output(Clock())
  val lock = Output(Bool())
}

class Pll8gIO extends PllIO {
  val Dcoarse = Input(UInt(6.W))
  val Dcoarseb = Input(UInt(6.W))
}

// The 8 GHz PLL. The cell is `8gpll_top`, which is not a name Verilog can use
// unescaped, nor one firtool will emit, so the cell has to be renamed to this.
class Pll8g(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Pll8gIO)
  override val desiredName = "pll8g_top"

  if (includeDefaultModels) {
    addResource("/vsrc/pll8g_top.v")
  }
}

// The 12 GHz PLL, `12gpll_top`, renamed for the same reason.
class Pll12g(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new PllIO)
  override val desiredName = "pll12g_top"

  if (includeDefaultModels) {
    addResource("/vsrc/pll12g_top.v")
  }
}

class MainClkSelCoreIO extends Bundle {
  val PLLclk_8G = Input(Clock())
  val PLLclk_12G = Input(Clock())
  val clk_bypass = Input(Clock())
  val mainClkSel = Input(UInt(2.W))
  val TxClkSel = Input(UInt(2.W))
  val TxClkQSel = Input(UInt(2.W))
  val TxClkQDiv2PhaseSel = Input(Bool())
  val TxClkQDiv2PhaseS2DSel = Input(Bool())
  val TxClkQDiv2PhaseS2DSel_B = Input(Bool())
  val TxClkQDiv4PhaseSel = Input(UInt(2.W))
  val TxClkQDiv4PhaseS2DSel = Input(Bool())
  val TxClkQDiv4PhaseS2DSel_B = Input(Bool())
  // Not on the cell yet: the shared reset of the four TX dividers, active high.
  val reset = Input(Bool())
  val TxClk = Output(Clock())
  val TxClkQ = Output(Clock())
}

// TX clock generator: the X2 multiplier, the UCIe main clock mux, and the
// TX data and quadrature dividers.
class MainClkSelCore(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new MainClkSelCoreIO)
  override val desiredName = "main_clk_sel_core"

  if (includeDefaultModels) {
    addResource("/vsrc/main_clk_sel_core.v")
  }
}

class GlobalDelaylineGateIO extends Bundle {
  val Dctrl = Input(UInt(ClockingTile.phaseSelWidth.W))
  val Vin = Input(Clock())
  val Vout = Output(Clock())
  // Not on the delay line: the TX clock gate's enable, and the TX data clock
  // the gate's first half stops.
  val enable = Input(Bool())
  val VinData = Input(Clock())
  val VoutData = Output(Clock())
}

// The global delay line on the TX quadrature clock, then the TX clock gate on
// both TX clocks. The cell's name is a placeholder: the delay line's pins with
// the gate's added.
class GlobalDelaylineGate(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new GlobalDelaylineGateIO)
  override val desiredName = "global_delayline_gate"

  if (includeDefaultModels) {
    addResource("/vsrc/global_delayline_gate.v")
  }
}

class PhyTestDivCoreIO extends Bundle {
  // Not on the cell yet: the source mux in front of the dividers, in place of
  // the cell's `clk_in`. `src_sel` is coded as `PhyTestClkSrcSel`.
  val PLLclk_8G = Input(Clock())
  val PLLclk_12G = Input(Clock())
  val clk_bypass_analog = Input(Clock())
  val src_sel = Input(UInt(2.W))
  val clk_bypass = Input(Clock())
  val sel = Input(UInt(3.W))
  val phyTestClkEn = Input(Bool())
  val PhyTestClk = Output(Clock())
}

// PHY test divider: source mux, dividers, ratio and bypass muxes, and gate.
class PhyTestDivCore(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new PhyTestDivCoreIO)
  override val desiredName = "phytest_div_core"

  if (includeDefaultModels) {
    addResource("/vsrc/phytest_div_core.v")
  }
}

class SbDivCoreIO extends Bundle {
  // Not on the cell yet: the source mux, as `phytest_div_core`'s.
  val PLLclk_8G = Input(Clock())
  val PLLclk_12G = Input(Clock())
  val clk_bypass_analog = Input(Clock())
  val sb_src_sel = Input(UInt(2.W))
  val clk_bypass = Input(Clock())
  val sb_sel = Input(UInt(2.W))
  val sbClkEn = Input(Bool())
  val SbClk = Output(Clock())
}

// TX sideband divider: source mux, dividers, the ratio and bypass mux, and
// gate.
class SbDivCore(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new SbDivCoreIO)
  override val desiredName = "sb_div_core"

  if (includeDefaultModels) {
    addResource("/vsrc/sb_div_core.v")
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
