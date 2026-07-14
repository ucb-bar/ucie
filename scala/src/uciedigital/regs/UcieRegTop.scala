// Diplomatic top of the UCIe register region with its TLRegisterNode and register map.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.prci.{ClockSinkDomain, ClockSinkParameters}
import freechips.rocketchip.diplomacy.{AddressSet, SimpleDevice}
import freechips.rocketchip.tilelink.TLRegisterNode
import freechips.rocketchip.interrupts.{IntSourceNode, IntSourcePortSimple}

// Add the raw IRQ output ports used when interrupts are not routed through a diplomatic node.
class UcieRegTopIO(params: UcieRegParams) extends UcieRegBlockIO(params) {
  val linkEventIrq = if (params.includeInterruptNode) None else Some(Output(Bool()))
  val linkErrorIrq = if (params.includeInterruptNode) None else Some(Output(Bool()))
}

class UcieRegTop(val params: UcieRegParams, val beatBytes: Int = 4)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters()) {

  val allocation = params.allocation

  val device = new SimpleDevice("ucie-regs", Seq("ucbbar,ucie-regs"))

  val node: Option[TLRegisterNode] =
    if (params.includeRegNode) {
      Some(TLRegisterNode(
        address = Seq(AddressSet(params.baseAddress, allocation.regionSize - 1)),
        device = device,
        deviceKey = "reg/control",
        beatBytes = beatBytes,
        undefZero = true
      ))
    } else None

  val intNode: Option[IntSourceNode] =
    if (params.includeInterruptNode) {
      Some(IntSourceNode(IntSourcePortSimple(num = 1, sources = 2)))
    } else None

  override lazy val module = new UcieRegTopImpl
  class UcieRegTopImpl extends Impl {
    val io = IO(new UcieRegTopIO(params))

    val regMapEntries: Seq[freechips.rocketchip.regmapper.RegField.Map] =
      withClockAndReset(clock, reset) {
        // If no node declared, build the map externally with UcieRegBlock.build
        if (node.isDefined || intNode.isDefined) {
          val (entries, linkEventIrq, linkErrorIrq) = UcieRegBlock.build(io, reset, params)
          node.foreach(_.regmap(entries: _*))
          intNode match {
            case Some(intn) =>
              val (ints, _) = intn.out(0)
              ints(0) := linkEventIrq
              ints(1) := linkErrorIrq
            case None =>
              io.linkEventIrq.foreach(_ := linkEventIrq)
              io.linkErrorIrq.foreach(_ := linkErrorIrq)
          }
          entries
        } else {
          io.regsToAdapter := DontCare
          io.regsToPhy := DontCare
          io.regsToLink := DontCare
          io.mailboxSideband.req := DontCare
          io.vendorToPhy.foreach(_ := DontCare)
          io.vendorToD2d.foreach(_ := DontCare)
          io.linkEventIrq.foreach(_ := false.B)
          io.linkErrorIrq.foreach(_ := false.B)
          Seq.empty
        }
      }
  }
}
