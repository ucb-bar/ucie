/*
  The diplomatic top of the UCIe register region.
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.prci.{ClockSinkDomain, ClockSinkParameters}
import freechips.rocketchip.diplomacy.{AddressSet, SimpleDevice}
import freechips.rocketchip.tilelink.TLRegisterNode
import freechips.rocketchip.interrupts.{IntSourceNode, IntSourcePortSimple}

class UcieRegTopIO(params: UcieRegParams) extends UcieRegBlockIO(params) {
  val linkEventIrq =
    if (params.includeInterruptNode) None else Some(Output(Bool()))
  val linkErrorIrq =
    if (params.includeInterruptNode) None else Some(Output(Bool()))
  val trainingIrq =
    if (params.includeInterruptNode) None else Some(Output(Bool()))
  val plErrorIrq =
    if (params.includeInterruptNode) None else Some(Output(Bool()))
}

class UcieRegTop(val params: UcieRegParams, val beatBytes: Int = 4)(implicit
    p: Parameters
) extends ClockSinkDomain(ClockSinkParameters()) {
  val allocation = params.allocation

  val device = new SimpleDevice("ucie-regs", Seq("ucbbar,ucie-regs"))

  val node: Option[TLRegisterNode] =
    if (params.includeRegNode) {
      Some(
        TLRegisterNode(
          address =
            Seq(AddressSet(params.baseAddress, allocation.regionSize - 1)),
          device = device,
          deviceKey = "reg/control",
          beatBytes = beatBytes,
          undefZero = true
        )
      )
    } else None

  val intNode: Option[IntSourceNode] =
    if (params.includeInterruptNode) {
      Some(
        IntSourceNode(IntSourcePortSimple(num = 1, sources = UcieIrqs.count))
      )
    } else None

  override lazy val module = new UcieRegTopImpl
  class UcieRegTopImpl extends Impl {
    val io = IO(new UcieRegTopIO(params))

    val regMapEntries: Seq[freechips.rocketchip.regmapper.RegField.Map] =
      withClockAndReset(clock, reset) {
        // With no node, the caller builds the map with UcieRegBlock.build.
        if (node.isDefined || intNode.isDefined) {
          val (entries, irqs) = UcieRegBlock.build(io, reset, params)
          node.foreach { n =>
            UcieSharedRegMapper.attach(
              node = n,
              target = io.sidebandRegTarget,
              legacyMapping = Nil,
              ucieMapping = entries,
              ucieMapBase = 0,
              ucieRegionSize = allocation.regionSize
            )
          }
          if (node.isEmpty) {
            io.sidebandRegTarget.req.ready := false.B
            io.sidebandRegTarget.resp.valid := false.B
            io.sidebandRegTarget.resp.bits := 0.U.asTypeOf(
              new SidebandRegTargetResp
            )
          }
          intNode match {
            case Some(intn) =>
              val (ints, _) = intn.out(0)
              ints.zip(irqs.toSeq).foreach { case (i, s) => i := s }
            case None =>
              io.linkEventIrq.foreach(_ := irqs.linkEvent)
              io.linkErrorIrq.foreach(_ := irqs.linkError)
              io.trainingIrq.foreach(_ := irqs.training)
              io.plErrorIrq.foreach(_ := irqs.plError)
          }
          entries
        } else {
          io.regsToAdapter := DontCare
          io.regsToPhy := DontCare
          io.regsToLink := DontCare
          io.mailboxSideband.req := DontCare
          io.sidebandRegTarget.req.ready := false.B
          io.sidebandRegTarget.resp.valid := false.B
          io.sidebandRegTarget.resp.bits := 0.U.asTypeOf(
            new SidebandRegTargetResp
          )
          io.vendorToPhy.foreach(_ := DontCare)
          io.vendorToD2d.foreach(_ := DontCare)
          io.linkEventIrq.foreach(_ := false.B)
          io.linkErrorIrq.foreach(_ := false.B)
          io.trainingIrq.foreach(_ := false.B)
          io.plErrorIrq.foreach(_ := false.B)
          Seq.empty
        }
      }
  }
}
