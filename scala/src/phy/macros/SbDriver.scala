package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import chisel3.util._

object SbDriver {

  /** Segments in the pad driver inside an [[SbDriver]]. It is the same driver
    * cell as a standalone [[PadDriver]], so the two cannot drift apart.
    */
  val DriverSegments = PadDriver.DriverSegments

  /** Drives one bump from its half rate pair at full strength, returning what a
    * receiver on that bump would see.
    *
    * For wiring a sideband bump straight into a receiver, as a loopback harness
    * does: the 2:1 lives in this cell, so the pair has to pass through one to
    * become the serial stream the far side expects.
    */
  def bump(
      clk: Clock,
      d0: Bool,
      d1: Bool,
      includeDefaultModels: Boolean = false
  ): Bool = {
    val driver = Module(new SbDriver()(includeDefaultModels))
    driver.io.in.clk := clk
    driver.io.in.d0 := d0
    driver.io.in.d1 := d1
    driver.io.ctl := PadDriverCtlIO.full
    driver.io.out
  }
}

/** The two half rate bits an [[SbDriver]] serializes, and the clock it
  * serializes them on.
  *
  * `d0` goes out while `clk` is high and `d1` while it is low. Holding the two
  * equal therefore sends one bit per clock period, and `d0 = enable, d1 = 0`
  * sends a gated copy of `clk` itself, which is how the sideband forwards its
  * clock.
  */
class SbSerialIO extends Bundle {
  val clk = Clock()
  val d0 = Bool()
  val d1 = Bool()
}

class SbDriverIO extends Bundle {
  val in = Input(new SbSerialIO)
  val ctl = Input(new PadDriverCtlIO)

  /** Sideband bump. */
  val out = Output(Bool())
}

/** One sideband bump driver: a 2:1 serializer feeding a pad driver, as a single
  * cell. Neither piece is separately visible to the RTL, so the digital side
  * hands over the two half rate bits and the cell does the rest.
  */
class SbDriver(implicit includeDefaultModels: Boolean = false)
    extends RawModule {
  val io = IO(new SbDriverIO)

  val verilogBlackBox = Module(new VerilogSbDriver)
  verilogBlackBox.io.clk := io.in.clk
  verilogBlackBox.io.d0 := io.in.d0
  verilogBlackBox.io.d1 := io.in.d1
  io.out := verilogBlackBox.io.out

  val puCtlTherm = Wire(UInt(64.W))
  puCtlTherm := (1.U << io.ctl.pu_ctl) - 1.U
  val pdCtlbTherm = Wire(UInt(64.W))
  pdCtlbTherm := ~((1.U << io.ctl.pd_ctl) - 1.U)
  verilogBlackBox.io.pu_ctl := puCtlTherm(SbDriver.DriverSegments - 1, 0)
  verilogBlackBox.io.pd_ctlb := pdCtlbTherm(SbDriver.DriverSegments - 1, 0)
  verilogBlackBox.io.en := io.ctl.en
  verilogBlackBox.io.en_b := io.ctl.en_b
}

class VerilogSbDriver(implicit includeDefaultModels: Boolean = false)
    extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val d0 = Input(Bool())
    val d1 = Input(Bool())
    val pu_ctl = Input(UInt(SbDriver.DriverSegments.W))
    val pd_ctlb = Input(UInt(SbDriver.DriverSegments.W))
    val en = Input(Bool())
    val en_b = Input(Bool())
    val out = Output(Bool())
  })

  override val desiredName = "sb_driver"

  if (includeDefaultModels) {
    addResource("/vsrc/sb_driver.v")
  }
}
