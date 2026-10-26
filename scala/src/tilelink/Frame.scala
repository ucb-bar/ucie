package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.{TLBundleA, TLBundleD}

/** The framed TileLink packets UcieTL exchanges with its partner die.
  *
  * A frame carries one A or D beat (or none) plus a credit return, with a
  * one-bit tag in bit 0 that says which channel's payload it holds: 1 for D,
  * 0 for A. Everything here is combinational; the flow control that decides
  * what goes in a frame lives in `UcieTLProtocol`.
  */
object UcieTLFrame {
  val dataBits = 256

  /** Bits in a framed TL packet: the wider of the two channel payloads plus the
    * one-bit tag that says which channel it is. The mainband pads this out to
    * `numLanes * Phy.SerdesRatio` bits; the sideband shifts exactly this many.
    */
  def frameBits(creditBits: Int): Int =
    math.max(
      new UcieTXA(creditBits).getWidth,
      new UcieTXD(creditBits).getWidth
    ) + 1

  /** Frames a D payload if `isD`, else an A payload. */
  def apply(isD: Bool, d: UcieTXD, a: UcieTXA): UInt =
    Mux(isD, Cat(d.asUInt, 1.U), Cat(a.asUInt, 0.U))

  /** Whether `frame` carries a D payload rather than an A one. */
  def isD(frame: UInt): Bool = frame(0)

  /** The payload of `frame`, to be cast with `asTypeOf` to the channel's type. */
  def payload(frame: UInt): UInt = frame(frame.getWidth - 1, 1)

  def fromTL(a: TLBundleA): UcieTLBundleA = {
    val ucieA = Wire(new UcieTLBundleA)
    require(ucieA.opcode.getWidth >= a.opcode.getWidth)
    require(ucieA.param.getWidth >= a.param.getWidth)
    require(ucieA.size.getWidth >= a.size.getWidth)
    require(ucieA.address.getWidth >= a.address.getWidth)
    require(ucieA.mask.getWidth >= a.mask.getWidth)
    require(ucieA.data.getWidth >= a.data.getWidth)
    require(ucieA.source.getWidth >= a.source.getWidth)
    require(ucieA.corrupt.getWidth >= a.corrupt.getWidth)
    ucieA.opcode := a.opcode
    ucieA.param := a.param
    ucieA.size := a.size
    ucieA.address := a.address
    ucieA.mask := a.mask
    ucieA.data := a.data
    ucieA.source := a.source
    ucieA.corrupt := a.corrupt
    ucieA
  }

  def fromTL(d: TLBundleD): UcieTLBundleD = {
    val ucieD = Wire(new UcieTLBundleD)
    require(ucieD.opcode.getWidth >= d.opcode.getWidth)
    require(ucieD.param.getWidth >= d.param.getWidth)
    require(ucieD.size.getWidth >= d.size.getWidth)
    require(ucieD.data.getWidth >= d.data.getWidth)
    require(ucieD.source.getWidth >= d.source.getWidth)
    require(ucieD.denied.getWidth >= d.denied.getWidth)
    require(ucieD.corrupt.getWidth >= d.corrupt.getWidth)
    ucieD.opcode := d.opcode
    ucieD.param := d.param
    ucieD.size := d.size
    ucieD.data := d.data
    ucieD.source := d.source
    // Truncate since sink will always be 0
    ucieD.sink := d.sink(ucieD.sink.getWidth - 1, 0)
    ucieD.denied := d.denied
    ucieD.corrupt := d.corrupt
    ucieD
  }

  /** Converts `a` back to a TL A beat of type `gen`. */
  def toTL(a: UcieTLBundleA, gen: TLBundleA): TLBundleA = {
    val tlA = Wire(chiselTypeOf(gen))
    tlA.opcode := a.opcode
    tlA.param := a.param
    tlA.size := a.size
    tlA.address := a.address
    tlA.mask := a.mask
    tlA.data := a.data
    tlA.source := a.source
    tlA.corrupt := a.corrupt
    tlA
  }

  /** Converts `d` back to a TL D beat of type `gen`. */
  def toTL(d: UcieTLBundleD, gen: TLBundleD): TLBundleD = {
    val tlD = Wire(chiselTypeOf(gen))
    tlD.opcode := d.opcode
    tlD.param := d.param
    tlD.size := d.size
    tlD.data := d.data
    tlD.source := d.source
    tlD.sink := d.sink
    tlD.denied := d.denied
    tlD.corrupt := d.corrupt
    tlD
  }
}

class UcieTLBundleA extends Bundle {
  val opcode = UInt(3.W)
  val param = UInt(3.W)
  val size = UInt(4.W)
  val address = UInt(64.W) // to
  val mask = UInt((UcieTLFrame.dataBits / 8).W)
  val data = UInt(UcieTLFrame.dataBits.W)
  val source = UInt(8.W) // to
  val corrupt = Bool()
}

class UcieTLBundleD extends Bundle {
  // fixed fields during multibeat:
  val opcode = UInt(3.W)
  val param = UInt(2.W)
  val size = UInt(4.W)
  val data = UInt(UcieTLFrame.dataBits.W)
  val source = UInt(8.W) // to
  val sink = UInt(1.W) // from
  val denied = Bool() // implies corrupt iff *Data
  val corrupt = Bool()
}

class UcieTXA(creditBits: Int = 5) extends Bundle {
  val tl_valid = Bool()
  val credit_valid = Bool()
  val credit_a = UInt(creditBits.W)
  val credit_d = UInt(creditBits.W)
  val tl = new UcieTLBundleA
}

class UcieTXD(creditBits: Int = 5) extends Bundle {
  val tl_valid = Bool()
  val credit_valid = Bool()
  val credit_a = UInt(creditBits.W)
  val credit_d = UInt(creditBits.W)
  val tl = new UcieTLBundleD
}
