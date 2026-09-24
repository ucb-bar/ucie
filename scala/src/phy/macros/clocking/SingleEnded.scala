package edu.berkeley.cs.uciedigital.phy.macros.clocking

import chisel3._
import chisel3.util._

class ClkRxIO extends Bundle {
  val Vin = Input(Clock())
  val Vout = Output(Clock())
}

// Self-biased inverter input stage followed by a restoring inverter, so the
// receiver is non-inverting. Port and module names match the analog IP top
// cell; the VDD/VSS pins are connected by the physical flow.
class ClkRx(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new ClkRxIO)

  override val desiredName = "clock_receiver"

  if (includeDefaultModels) {
    addResource("/vsrc/clock_receiver.v")
  }
}

/** Which netlist the lane clock distribution network is. */
sealed trait ClkDistLayout {
  /** The netlist module's name. */
  def moduleName: String

  /** The Verilog the netlist needs: its own source, and its cells' models. */
  def resources(includeDefaultModels: Boolean): Seq[String]
}

object ClkDistLayout {

  /** The behavioral model: every lane clock is an `assign` of its source. What
    * simulation uses, and what a UCIe instance with no physical clock tree gets.
    */
  case object Behavioral extends ClkDistLayout {
    val moduleName = "ucie_clk_dist_network"
    def resources(includeDefaultModels: Boolean) =
      if (includeDefaultModels) Seq("/vsrc/ucie_clk_dist_network.sv") else Seq()
  }

  /** The buffered, shielded tree the analog IP's clock DEF places and routes for
    * a module whose bump field sits in orientation `orient` (`"r0"` or
    * `"r90"`). The netlist is generated alongside the DEF
    * (`analogip_tstechn7/rs/src/ucie/clock_tree.rs`), so every buffer and wire
    * has the same name in both. Every path is an even count of inverters, so
    * the lane clocks carry the same polarity as the behavioral model's.
    */
  case class Buffered(orient: String) extends ClkDistLayout {
    require(
      Seq("r0", "r90").contains(orient),
      s"no clock tree netlist for orientation $orient"
    )
    val moduleName = s"ucie_clk_dist_network_$orient"
    def resources(includeDefaultModels: Boolean) =
      Seq(s"/vsrc/$moduleName.sv") ++ (if (includeDefaultModels)
                                        ClkBuf.cells.map(c => s"/vsrc/$c.v")
                                      else Seq())
  }
}

/** The clock tree's buffer cells: one inverter each, in three layouts --
  * horizontal runs, vertical runs driving down, and vertical runs driving up.
  */
object ClkBuf {
  val cells = Seq("clkbuf_horizontal", "clkbuf_vertical", "clkbuf_vertical_fs")
}

class ClkDistNetworkIO(numLanes: Int = 16) extends Bundle {
  // In-phase TX clock, distributed to the data, valid, and track lanes.
  val txClk = Input(Clock())
  // Quadrature TX clock, distributed to the two forwarded-clock lanes so that
  // the transmitted clock lands in the center of the data eye.
  val txClkQ = Input(Clock())

  val txClkDivClk = Output(Clock())
  val rxClkDivClk = Output(Clock())

  val rxClk = Input(Clock())
  // Every lane clock is single-ended; each TX tile makes its own complement.
  val txLaneClk = Output(Vec(numLanes + 4, Clock()))
  val rxLaneClk = Output(Vec(numLanes + 2, Clock()))

  // The tester's own lanes, off the in-phase TX clock: the TX data debug lane,
  // the loopback transmitter, and the loopback receiver, in that order. The
  // tree delivers them as close to the PHY's lanes as the debug bumps' distance
  // allows.
  val testTxLaneClk = Output(Vec(ClkDistNetwork.numTestLanes, Clock()))
  // The tester's two clock observation pad drivers, TX and RX. Buffered taps
  // rather than the raw clocks, so the wire the router runs out to the pads
  // does not load the network's roots.
  val testTxPadClk = Output(Clock())
  val testRxPadClk = Output(Clock())
}

object ClkDistNetwork {
  val numTestLanes = 3
}

class ClkDistNetwork(layout: ClkDistLayout = ClkDistLayout.Behavioral)(implicit
    includeDefaultModels: Boolean = false
) extends RawModule {
  val io = IO(new ClkDistNetworkIO)

  val verilogBlackBox = Module(new VerilogClkDistNetwork(layout))
  verilogBlackBox.io.txClk := io.txClk
  verilogBlackBox.io.txClkQ := io.txClkQ
  io.txClkDivClk := verilogBlackBox.io.txClkDivClk
  io.rxClkDivClk := verilogBlackBox.io.rxClkDivClk
  verilogBlackBox.io.rxClk := io.rxClk
  io.txLaneClk := verilogBlackBox.io.txLaneClk.asTypeOf(io.txLaneClk)
  io.rxLaneClk := verilogBlackBox.io.rxLaneClk.asTypeOf(io.rxLaneClk)
  io.testTxLaneClk := verilogBlackBox.io.testTxLaneClk
    .asTypeOf(io.testTxLaneClk)
  io.testTxPadClk := verilogBlackBox.io.testTxPadClk
  io.testRxPadClk := verilogBlackBox.io.testRxPadClk
}

class VerilogClkDistNetwork(layout: ClkDistLayout)(implicit
    includeDefaultModels: Boolean = false
) extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val txClk = Input(Clock())
    val txClkQ = Input(Clock())

    val txClkDivClk = Output(Clock())
    val rxClkDivClk = Output(Clock())

    val rxClk = Input(Clock())
    val txLaneClk = Output(UInt(20.W))
    val rxLaneClk = Output(UInt(18.W))

    val testTxLaneClk = Output(UInt(ClkDistNetwork.numTestLanes.W))
    val testTxPadClk = Output(Clock())
    val testRxPadClk = Output(Clock())
  })

  override val desiredName = layout.moduleName

  layout.resources(includeDefaultModels).foreach(addResource)
}
