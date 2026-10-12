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

/** Which netlist the lane clock distribution network is.
  *
  * The PHY instantiates the netlist as a blackbox, so a physical design can
  * swap the behavioral model for the network it actually builds, and keep that
  * network's technology out of this repository. Such a layout lives in the
  * physical design's own project:
  *
  *   - Its module has the port list of `ucie_clk_dist_network.sv`, and delivers
  *     every lane and bypass clock with the behavioral model's polarity.
  *   - Its resources are classpath paths, so they can be in any project on the
  *     classpath.
  *   - It is a case class or case object: layouts are compared as part of
  *     `UcieTLParams`.
  */
trait ClkDistLayout {

  /** The netlist module's name. */
  def moduleName: String

  /** The Verilog the netlist needs: its own source, and its cells' models. */
  def resources(includeDefaultModels: Boolean): Seq[String]
}

object ClkDistLayout {

  /** The behavioral model: every lane clock is an `assign` of its source. What
    * simulation uses, and what a UCIe instance with no physical clock tree
    * gets.
    */
  case object Behavioral extends ClkDistLayout {
    val moduleName = "ucie_clk_dist_network"
    def resources(includeDefaultModels: Boolean) =
      if (includeDefaultModels) Seq("/vsrc/ucie_clk_dist_network.sv") else Seq()
  }
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

  // The clocking tile's two bypass clocks, on their way to it: the bypass
  // clock receiver's restored clock, and the digital bypass clock, straight off
  // its bump. Every module has its own bump for each. A physical network may
  // buffer either of them.
  val bypassClk = Input(Clock())
  val digitalBypassClk = Input(Clock())
  val tileBypassClk = Output(Clock())
  val tileDigitalBypassClk = Output(Clock())
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
  verilogBlackBox.io.bypassClk := io.bypassClk
  verilogBlackBox.io.digitalBypassClk := io.digitalBypassClk
  io.tileBypassClk := verilogBlackBox.io.tileBypassClk
  io.tileDigitalBypassClk := verilogBlackBox.io.tileDigitalBypassClk
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

    val bypassClk = Input(Clock())
    val digitalBypassClk = Input(Clock())
    val tileBypassClk = Output(Clock())
    val tileDigitalBypassClk = Output(Clock())
  })

  override val desiredName = layout.moduleName

  layout.resources(includeDefaultModels).foreach(addResource)
}
