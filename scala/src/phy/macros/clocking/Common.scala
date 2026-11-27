package edu.berkeley.cs.uciedigital.phy.macros.clocking

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._

// Clocking, reset, and pad macros used by the PHY.
//
// Blackboxes that wrap an analog IP top cell use the cell's module and pin
// names verbatim so that the emitted Verilog instantiates the IP directly.
// Supplies (VDD/VSS) are pins on the IP but are omitted here; they are
// connected by the physical flow.

object ClkMux {

  // Inputs the observation mux selects between, clocks or data.
  val numInputs = 16

  def selWidth: Int = log2Ceil(numInputs)

  // The cell's select code for input `index`, which is Gray coded.
  def gray(index: UInt): UInt = index ^ (index >> 1)
}

class ClkMuxIO extends Bundle {
  // Clocks or data, one bit per input. A bus rather than a `Vec` of clocks so
  // the port is `Q[15:0]`, as the cell names it.
  val Q = Input(UInt(ClkMux.numInputs.W))
  val sel = Input(UInt(ClkMux.selWidth.W))
  val clk_divided = Output(Clock())
}

// The 16:1 clock mux `mux16to1` from the analog clocking IP. `sel` =
// `ClkMux.gray(i)` picks `Q[i]`, not inverted, and every code picks some
// input; use `connect` rather than driving `sel` by hand.
class ClkMux(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkMuxIO)

  override val desiredName = "mux16to1"

  if (includeDefaultModels) {
    addResource("/vsrc/mux16to1.v")
  }

  // Returns `ins(index)`, Gray coding `index` into the cell's select. Inputs
  // past the end of `ins` are tied low, so selecting one gives a constant 0.
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

  /** Reset values of one PLL's loop settings registers (`UcieClkRegs`).
    * `Dcoarseb` is tied to the complement of `dcoarse`. `dbias` is the RDAC
    * code used while the PLL is enabled; the tile drives zero to turn the PLL
    * off, so zero here leaves it off.
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

  /** Placeholders until the PLL designer gives nominal settings. All zero
    * except the RDAC, which is mid-scale so an enabled PLL runs. The behavioral
    * models only check whether the RDAC is zero.
    */
  val pll8Settings = PllSettings(false, 0, 0, 0, 0, 0x100, 0)
  val pll12Settings = PllSettings(false, 0, 0, 0, 0, 0x100, 0)

  /** Width of PLL8's coarse frequency code. */
  val pllDcoarseWidth = 6

  /** `settings` as the registers' reset value. */
  def pllCfgReset(settings: PllSettings): PllCfgIO =
    (new PllCfgIO).Lit(
      _.autoEn -> settings.autoEn.B,
      _.kp -> settings.kp.U,
      _.ki -> settings.ki.U,
      _.codeInit -> settings.codeInit.U,
      _.codeByp -> settings.codeByp.U,
      _.dbias -> settings.dbias.U
    )
}

/** One PLL's digital loop settings and RDAC code, as registers drive them.
  * Widths are the cells' pins.
  */
class PllCfgIO extends Bundle {
  val autoEn = Bool()
  val kp = UInt(16.W)
  val ki = UInt(16.W)
  val codeInit = UInt(8.W)
  val codeByp = UInt(20.W)
  // Used only while the PLL is enabled. Zero leaves it off.
  val dbias = UInt(9.W)
}

/** The clocking tile's controls and clocks. The tile is six macros: two PLLs,
  * the TX clock generator, the global delay line and gate, and the PHY test and
  * TX sideband dividers. `ClockingTile` maps each control to its macro's pins.
  */
class ClockingTileIO extends Bundle {

  /** Global delay line on TxClkQ, thermometer coded. It is before the TX clock
    * gate, which an apply shuts during a change.
    */
  val PhaseSel = Input(UInt(ClockingTile.phaseSelWidth.W))

  /** UCIe main clock source: 0 PLL8, 1 PLL12, 2 PLL8 X2 (16 GHz), 3 the analog
    * bypass pin (up to 16 GHz). PLLs are named for their output in GHz.
    */
  val MainClkSel = Input(UInt(ClockingTile.mainClkSelWidth.W))

  /** Per-PLL enables. Either PLL can also feed the PHY test and TX sideband
    * dividers. Low sets the RDAC (`Dbias`) to zero, which turns the PLL off,
    * and holds its digital loop in reset.
    */
  val Pll8En = Input(Bool())
  val Pll12En = Input(Bool())

  /** Drives nothing; kept for the register map. The X2 multiplier in
    * `main_clk_sel_core` has no enable and runs whenever PLL8 does.
    */
  val X2En = Input(Bool())

  /** Per-PLL lock. Low while the PLL is off, and after enable until it settles.
    * An apply keeps the clock gate shut until lock. The X2 clock is ready when
    * PLL8 is locked.
    */
  val Pll8Lock = Output(Bool())
  val Pll12Lock = Output(Bool())

  /** Per-PLL loop settings and RDAC code, and PLL8's coarse frequency code
    * (`Dcoarseb` gets its complement).
    */
  val Pll8Cfg = Input(new PllCfgIO)
  val Pll12Cfg = Input(new PllCfgIO)
  val Pll8Dcoarse = Input(UInt(ClockingTile.pllDcoarseWidth.W))

  /** Per-PLL loop state, the cells' `code_full` and `code_int`, for
    * observation. Asynchronous to every clock outside the PLL.
    */
  val Pll8CodeFull = Output(UInt(20.W))
  val Pll8CodeInt = Output(UInt(8.W))
  val Pll12CodeFull = Output(UInt(20.W))
  val Pll12CodeInt = Output(UInt(8.W))

  /** TX data clock division of the UCIe main clock: 0 /1, 1 /2, 2 /4; 3 is
    * spare and selects /4 too.
    */
  val TxClkDiv = Input(UInt(ClockingTile.txClkDivWidth.W))

  /** TxClkQ's lead over TxClk, in UCIe main clock half cycles, 0 to 2*div-1. A
    * step of half a main clock period is within the global delay line's tap
    * range for main clocks of about 5 GHz and up (every PLL rate), so this and
    * the delay line together reach any phase. A slower bypass clock leaves
    * gaps. At /1 this has no effect: TxClkQ is the main clock.
    */
  val TxClkPhase = Input(UInt(ClockingTile.txClkPhaseWidth.W))

  /** PHY test clock divider for the source `PhyTestClkSrcSel` picks: 3 /6, 4
    * /8, 5 /12, 6 and 7 /15. 0, 1 and 2 select /8, the reset value. 1 GHz is
    * PLL8/8 or PLL12/12; 800 MHz is PLL12/15. The source is never the UCIe main
    * clock, so main clock changes don't affect it. /15 has no duty cycle
    * corrector: high for 9 source cycles, low for 6.
    */
  val PhyTestClkDiv = Input(UInt(ClockingTile.phyTestClkDivWidth.W))

  /** PHY test divider source: 0 PLL8, 1 PLL12, 2 and 3 the analog bypass pin
    * (to run without a PLL).
    */
  val PhyTestClkSrcSel = Input(UInt(ClockingTile.phyTestClkSrcSelWidth.W))

  /** Takes the PHY test clock from the bypass pin rather than the divider. */
  val PhyTestClkBypassEn = Input(Bool())

  /** Active-high enable for the glitch-free gate after the PHY test clock's
    * bypass mux. Change the source, ratio or bypass select only while it is
    * shut; the muxes are not glitch-free. The enable goes through three flops
    * on the gated clock's falling edge, so it takes effect 3-4 periods later,
    * and only while that clock runs. When shut, `PhyTestClk` stays high.
    */
  val PhyTestClkEn = Input(Bool())

  /** TX sideband clock divider for the source `SbClkSrcSel` picks: 1 /5, 2 /10,
    * 3 /15. 0 selects /10, the reset value. 800 MHz is PLL8/10 or PLL12/15. /5
    * and /15 have no duty cycle corrector: `SbClk` is high for 3 of 5 and 10 of
    * 15 source cycles.
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

  /** Active-high enable for the TX clock gate, driven by the `clkEn` register.
    * The gate is after the global delay line. Three flops synchronize the
    * enable to TxClk, and three more delay it to match TxClkQ's three flops.
    * Each clock goes through a clock gate cell driven by a flop on the same
    * clock, so no pulse is cut short and no path has to fit in half a period.
    * Low stops both clocks 6-7 TxClk periods later. The TX dividers before the
    * gate keep running, so the source, ratio, phase or delay code can change
    * with no edge reaching a lane. The PHY test and sideband clocks keep
    * running, so the PHY test domain can still service the RX AFEs.
    */
  val ClkGateEn = Input(Bool())

  /** TX clock gate tap select, driven by the `clkGateEdgeSel` register. It
    * picks the TxClk-chain tap that TxClkQ's flops sample. 0: flop 4, which
    * changes as TxClk rises. 1: flop 3 through a latch open while TxClk is low,
    * half a period earlier. Each tap has a race: a lag where a gate change can
    * take effect on either of two TxClkQ pulses, though none is cut. Tap 0's
    * race is at its flop and mux delay; tap 1's is half a period before.
    * Software sets this to 1 within a quarter period of tap 0's race. It has no
    * effect while the gate stays open or shut.
    */
  val ClkGateEdgeSel = Input(Bool())

  /** Shared reset of the TX data and quadrature dividers. While held, both
    * stop. On release, both restart on the same main clock edge, so TxClkQ's
    * lead is the one `TxClkPhase` sets. The dividers are before the TX clock
    * gate, so this does not reach the lanes.
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

/** The clocking tile behind `ClockingTileIO`. Each PLL and the analog bypass
  * pin feed the TX clock generator and both dividers. Controls are translated
  * here into each macro's pins and codes.
  */
class ClockingTile(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = FlatIO(new ClockingTileIO)
  // Drives no macro. Kept so the X2 enable is visible here, ready for a
  // doubler that gets an enable.
  dontTouch(io.X2En)

  // A PLL is off with its RDAC at zero, and `reset` holds only its digital
  // loop, so the enable drives both.
  def enable(pll: PllIO, en: Bool, cfg: PllCfgIO): Unit = {
    pll.reset := !en
    pll.Dbias := Mux(en, cfg.dbias, 0.U)
    pll.auto_en := cfg.autoEn
    pll.kp := cfg.kp
    pll.ki := cfg.ki
    pll.code_init := cfg.codeInit
    pll.code_byp := cfg.codeByp
  }

  val pll8 = Module(new Pll8g)
  pll8.io.clk_ref := io.RefClk
  enable(pll8.io, io.Pll8En, io.Pll8Cfg)
  pll8.io.Dcoarse := io.Pll8Dcoarse
  pll8.io.Dcoarseb := ~io.Pll8Dcoarse
  io.Pll8Lock := pll8.io.lock
  io.Pll8CodeFull := pll8.io.code_full
  io.Pll8CodeInt := pll8.io.code_int

  val pll12 = Module(new Pll12g)
  pll12.io.clk_ref := io.RefClk
  enable(pll12.io, io.Pll12En, io.Pll12Cfg)
  io.Pll12Lock := pll12.io.lock
  io.Pll12CodeFull := pll12.io.code_full
  io.Pll12CodeInt := pll12.io.code_int

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
  // The cell sets TxClkQ's lag behind TxClk: whole main clock cycles by
  // rotating its divider, half a cycle by clocking it on the other main clock
  // edge (`S2DSel` low). `TxClkPhase` is a lead in half cycles, so the lag is
  // its negative modulo 2*div: 3 bits wrap at 8 (/4), the low 2 at 4 (/2).
  // Both dividers get the setting; only the selected one is used.
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
  txClkGate.io.edgeSel := io.ClkGateEdgeSel
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

// The clocking tile's macros, with the cells' module and pin names. A pin
// marked "not on the cell yet" must be added to the cell under that name.

/** Pins shared by `8gpll_top` and `12gpll_top`: ref clock, digital loop reset
  * and settings, and the RDAC (zero turns the PLL off). `ClockingTile` drives
  * the reset from the enable, and the rest from registers.
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
  // Not on the delay line: the TX clock gate's enable and tap select, and the
  // TX data clock the gate also stops.
  val enable = Input(Bool())
  val edgeSel = Input(Bool())
  val VinData = Input(Clock())
  val VoutData = Output(Clock())
}

// Global delay line on TxClkQ, then the TX clock gate on both TX clocks. The
// cell name is a placeholder.
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
