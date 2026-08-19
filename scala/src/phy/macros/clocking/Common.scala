package edu.berkeley.cs.uciedigital.phy.macros.clocking

import chisel3._
import chisel3.util._

// Clocking macros shared by the single-ended and differential schemes.
//
// Blackboxes that wrap an analog IP top cell use the cell's module and pin
// names verbatim so that the emitted Verilog instantiates the IP directly.
// Supplies (VDD/VSS) are pins on the IP but are omitted here; they are
// connected by the physical flow.

class ClkMuxClockIO extends Bundle {
  val in0 = Input(Clock())
  val in1 = Input(Clock())
  val out = Output(Clock())
}

class ClkMuxIO extends Bundle {
  val Vinp = Input(Clock())
  val Vinn = Input(Clock())
  val sel = Input(Bool())
  val selb = Input(Bool())
  val Vout = Output(Clock())
}

// Single-ended 2:1 clock mux cell. Differential clocking instantiates one per
// polarity; single-ended clocking instantiates one.
//
// `sel` and `selb` drive complementary pass gates and must be driven as true
// complements: `sel` high passes `Vinp`, `selb` high passes `Vinn`, and any
// other combination either floats or shorts the internal node. The shared
// output inverter means `Vout` is the inverse of the selected input.
class ClkMux(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkMuxIO)

  override val desiredName = "clkmux"

  if (includeDefaultModels) {
    addResource("/vsrc/clkmux.v")
  }

  def connect(clocks: ClkMuxClockIO, sel1: Bool) = {
    io.Vinn := clocks.in0
    io.Vinp := clocks.in1
    io.sel := sel1
    io.selb := !sel1
    clocks.out := io.Vout
  }
}

class S2DIO extends Bundle {
  val Vin = Input(Clock())
  val Voutp = Output(Clock())
  val Voutn = Output(Clock())
}

// Single-ended to differential clock converter. `Voutp` follows `Vin` and
// `Voutn` is its complement; a cross-coupled inverter pair balances the two.
class S2D(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new S2DIO)

  override val desiredName = "s2d"

  if (includeDefaultModels) {
    addResource("/vsrc/s2d.v")
  }
}

class ClkBufIO extends Bundle {
  val Vin = Input(Clock())
  val Vout = Output(Clock())
}

// Clock distribution buffers. Horizontal and vertical are electrically
// identical inverting buffers that differ only in layout orientation.
class ClkBufHorizontal(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkBufIO)

  override val desiredName = "clkbuf_horizontal"

  if (includeDefaultModels) {
    addResource("/vsrc/clkbuf_horizontal.v")
  }
}

class ClkBufVertical(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkBufIO)

  override val desiredName = "clkbuf_vertical"

  if (includeDefaultModels) {
    addResource("/vsrc/clkbuf_vertical.v")
  }
}

class RingOscIO extends Bundle {
  val Vout = Output(Clock())
}

// Free-running ring oscillators. There is no enable pin: the ring runs
// whenever the cell is powered. `clkPeriodPs` only affects the behavioral
// model, not the IP.
class Ro8G(clkPeriodPs: Int = 125)(implicit
    includeDefaultModels: Boolean = false
) extends BlackBox(Map("CLK_PERIOD_PS" -> clkPeriodPs))
    with HasBlackBoxResource {
  val io = IO(new RingOscIO)

  override val desiredName = "Ro8G"

  if (includeDefaultModels) {
    addResource("/vsrc/Ro8G.v")
  }
}

class Ro12G(clkPeriodPs: Int = 83)(implicit
    includeDefaultModels: Boolean = false
) extends BlackBox(Map("CLK_PERIOD_PS" -> clkPeriodPs))
    with HasBlackBoxResource {
  val io = IO(new RingOscIO)

  override val desiredName = "Ro12G"

  if (includeDefaultModels) {
    addResource("/vsrc/Ro12G.v")
  }
}

object GlobalDelayLine {
  val ctrlWidth = 64
}

class GlobalDelayLineIO extends Bundle {
  val Dctrl = Input(UInt(GlobalDelayLine.ctrlWidth.W))
  val Vin = Input(Clock())
  val Vout = Output(Clock())
}

// Digitally controlled delay line: six rows of four two-inverter unit delays,
// each unit loaded by a bank of switched capacitors. The cell is
// non-inverting, and larger `Dctrl` codes switch in more capacitance and so
// give more delay.
class GlobalDelayLine(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new GlobalDelayLineIO)

  override val desiredName = "global_delayline_v4"

  if (includeDefaultModels) {
    addResource("/vsrc/global_delayline_v4.v")
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

class ClkGateIO extends Bundle {
  val clk = Input(Clock())
  val en = Input(Bool())
  val gated_clk = Output(Clock())
}

class ClkGate(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkGateIO)

  override val desiredName = "ucie_clk_gate"

  if (includeDefaultModels) {
    addResource("/vsrc/ucie_clk_gate.sv")
  }
}
