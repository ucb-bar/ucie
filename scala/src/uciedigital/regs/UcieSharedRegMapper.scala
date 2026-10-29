/*
  Shares the register map between TileLink MMIO and sideband accesses.
  Aligned 64-bit sideband accesses run atomically.
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util._
import freechips.rocketchip.regmapper.{
  RegField,
  RegFieldAccessType,
  RegFieldWrType,
  RegMapper,
  RegMapperInput,
  RegMapperParams,
  RegWriteFn
}
import freechips.rocketchip.tilelink.{TLMessages, TLRegisterNode}
import freechips.rocketchip.util.{BundleField, ControlKey}

class UcieRegMapperExtraBundle(
    sourceBits: Int,
    sizeBits: Int,
    laneBits: Int,
    maskBits: Int
) extends Bundle {
  val sideband = Bool()
  val source = UInt((sourceBits max 1).W)
  val size = UInt((sizeBits max 1).W)
  val lane = UInt((laneBits max 1).W)
  val rawMask = UInt(maskBits.W)
}

case object UcieRegMapperExtra
    extends ControlKey[UcieRegMapperExtraBundle]("ucie_reg_mapper_extra")

case class UcieRegMapperExtraField(
    sourceBits: Int,
    sizeBits: Int,
    laneBits: Int,
    maskBits: Int
) extends BundleField[UcieRegMapperExtraBundle](
      UcieRegMapperExtra,
      Output(
        new UcieRegMapperExtraBundle(
          sourceBits,
          sizeBits,
          laneBits,
          maskBits
        )
      ),
      x => {
        x.sideband := false.B
        x.source := 0.U
        x.size := 0.U
        x.lane := 0.U
        x.rawMask := 0.U
      }
    )

object UcieSharedRegMapper {
  def attach(
      node: TLRegisterNode,
      target: SidebandRegTargetIO,
      legacyMapping: Seq[RegField.Map],
      ucieMapping: Seq[RegField.Map],
      ucieMapBase: BigInt,
      ucieRegionSize: BigInt
  ): Unit = {
    require(
      node.concurrency == 0,
      "shared UCIe register mapper requires concurrency=0"
    )
    require(ucieMapBase >= 0)
    require(ucieRegionSize > 0)

    val (bundleIn, edge) = node.in(0)
    val a = bundleIn.a
    val d = bundleIn.d
    val mapperBytes = node.beatBytes max 8
    val mapperBits = mapperBytes * 8
    val laneBits = log2Ceil(mapperBytes) max 1
    val indexBits = log2Ceil(node.size / mapperBytes) max 1
    val extraField = UcieRegMapperExtraField(
      edge.bundle.sourceBits,
      edge.bundle.sizeBits,
      laneBits,
      mapperBytes
    )
    val mapperParams = RegMapperParams(
      indexBits,
      mapperBytes,
      extraField +: a.bits.params.echoFields
    )

    val selectedSideband = WireDefault(false.B)
    val selectedRawMask = WireDefault(0.U(mapperBytes.W))
    val selectedBitMask = FillInterleaved(8, selectedRawMask)

    def maskedField(offset: Int, bit: Int, field: RegField): RegField = {
      if (field.width == 0) return field
      require(
        field.read.combinational && field.write.combinational,
        s"sideband-visible field at 0x${offset.toHexString} must be combinational"
      )
      require(
        field.desc.nonEmpty,
        s"sideband-visible field at 0x${offset.toHexString} needs write-semantics metadata"
      )
      val fieldDesc = field.desc.get
      val fieldName = fieldDesc.name
      val writeType = fieldDesc.wrType
      require(
        writeType.forall(_ == RegFieldWrType.ONE_TO_CLEAR),
        s"sideband-visible field '$fieldName' at 0x${offset.toHexString} " +
          s"has unsupported write semantics ${writeType.get}"
      )
      val wordBit = (offset * 8 + bit) % mapperBits
      val selected = selectedBitMask(wordBit + field.width - 1, wordBit)
      val selectedAny = selected.orR
      val (_, _, current) = field.read.fn(false.B, true.B)
      val isW1c = writeType.contains(RegFieldWrType.ONE_TO_CLEAR)
      val isWriteOnly = fieldDesc.access == RegFieldAccessType.W

      val write = RegWriteFn { (valid, data) =>
        val maskedData = if (isW1c || isWriteOnly) {
          data & selected
        } else {
          (data & selected) | (current & ~selected)
        }
        val doWrite = valid && (!selectedSideband || selectedAny)
        val writeData = Mux(selectedSideband, maskedData, data)
        val (_, done) = field.write.fn(false.B, doWrite, writeData)
        Mux(selectedSideband && !selectedAny, true.B, done)
      }
      field.copy(write = write)
    }

    val maskedUcieMapping = ucieMapping.map { case (offset, fields) =>
      var bit = 0
      val wrapped = fields.map { field =>
        val result = maskedField(offset, bit, field)
        bit += field.width
        result
      }
      offset -> wrapped
    }
    val allMapping = legacyMapping ++ maskedUcieMapping

    val hostResponses = Module(
      new Queue(chiselTypeOf(d.bits), 1, flow = true)
    )
    val targetResponses = Module(new Queue(new SidebandRegTargetResp, 1))

    val hostIn = Wire(Decoupled(new RegMapperInput(mapperParams)))
    val targetIn = Wire(Decoupled(new RegMapperInput(mapperParams)))
    hostIn.bits := 0.U.asTypeOf(hostIn.bits)
    targetIn.bits := 0.U.asTypeOf(targetIn.bits)

    val nodeOffsetBits = log2Ceil(node.size)
    val hostByteOffset = a.bits.address(nodeOffsetBits - 1, 0)
    val hostGroupLane = if (mapperBytes == node.beatBytes) {
      0.U(laneBits.W)
    } else {
      (hostByteOffset & (mapperBytes - node.beatBytes).U)(laneBits - 1, 0)
    }
    val hostShift = hostGroupLane << 3
    val hostData = a.bits.data.pad(mapperBits) << hostShift
    val hostMask = a.bits.mask.pad(mapperBytes) << hostGroupLane

    hostIn.valid := a.valid && hostResponses.io.enq.ready
    hostIn.bits.read := a.bits.opcode === TLMessages.Get
    hostIn.bits.index := hostByteOffset >> log2Ceil(mapperBytes)
    hostIn.bits.data := hostData
    hostIn.bits.mask := hostMask
    Connectable.waiveUnmatched(hostIn.bits.extra, a.bits.echo) match {
      case (lhs, rhs) => lhs :<= rhs
    }
    val hostExtra = hostIn.bits.extra(UcieRegMapperExtra)
    hostExtra.sideband := false.B
    hostExtra.source := a.bits.source
    hostExtra.size := a.bits.size
    hostExtra.lane := hostGroupLane
    hostExtra.rawMask := hostMask

    val targetSizeBytes = Mux(target.req.bits.is64, 8.U, 4.U)
    val targetEnd = target.req.bits.logicalOffset +& targetSizeBytes
    val targetInRegion = targetEnd <= ucieRegionSize.U
    val targetAligned = Mux(
      target.req.bits.is64,
      target.req.bits.logicalOffset(2, 0) === 0.U,
      target.req.bits.logicalOffset(1, 0) === 0.U
    )
    val targetEffectiveOffset = target.req.bits.logicalOffset +& ucieMapBase.U
    val targetLane = targetEffectiveOffset(laneBits - 1, 0)
    val targetFitsMapperWord =
      targetLane +& targetSizeBytes <= mapperBytes.U
    val relativeDword = target.req.bits.logicalOffset & ~3.U(24.W)
    val relativeQword = target.req.bits.logicalOffset & ~7.U(24.W)
    val mappedDwords = ucieMapping
      .collect {
        case (offset, fields) if fields.exists(_.width != 0) =>
          BigInt(offset) - ucieMapBase
      }
      .filter(_ >= 0)
      .distinct
    val mapped32 = mappedDwords
      .map(offset => relativeDword === offset.U)
      .reduceOption(_ || _)
      .getOrElse(false.B)
    val lowerDwordMapped = mappedDwords
      .map(offset => relativeQword === offset.U)
      .reduceOption(_ || _)
      .getOrElse(false.B)
    val upperDwordMapped = mappedDwords
      .map(offset => relativeQword + 4.U === offset.U)
      .reduceOption(_ || _)
      .getOrElse(false.B)
    val lowerDwordEnabled = target.req.bits.be(3, 0).orR
    val upperDwordEnabled = target.req.bits.be(7, 4).orR
    val mapped64 = (lowerDwordMapped || upperDwordMapped) &&
      (!lowerDwordEnabled || lowerDwordMapped) &&
      (!upperDwordEnabled || upperDwordMapped)
    val targetMapped =
      targetInRegion && targetAligned && targetFitsMapperWord && Mux(
        target.req.bits.is64,
        mapped64,
        mapped32
      )

    val targetShift = targetLane << 3
    val targetOpMask = Mux(target.req.bits.is64, "hff".U, "h0f".U)
    val targetRawMask =
      (target.req.bits.be & targetOpMask).pad(mapperBytes) << targetLane
    val targetMapperMask = Mux(
      target.req.bits.write,
      targetOpMask.pad(mapperBytes) << targetLane,
      targetRawMask
    )
    val targetData = target.req.bits.wdata.pad(mapperBits) << targetShift

    targetIn.valid := target.req.valid && targetMapped && targetResponses.io.enq.ready
    targetIn.bits.read := !target.req.bits.write
    targetIn.bits.index := targetEffectiveOffset >> log2Ceil(mapperBytes)
    targetIn.bits.data := targetData
    targetIn.bits.mask := targetMapperMask
    val targetExtra = targetIn.bits.extra(UcieRegMapperExtra)
    targetExtra.sideband := true.B
    targetExtra.source := 0.U
    targetExtra.size := Mux(target.req.bits.is64, 3.U, 2.U)
    targetExtra.lane := targetLane
    targetExtra.rawMask := targetRawMask

    val arbiter = Module(new RRArbiter(new RegMapperInput(mapperParams), 2))
    arbiter.io.in(0) <> hostIn
    arbiter.io.in(1) <> targetIn
    a.ready := hostIn.ready && hostResponses.io.enq.ready

    selectedSideband := arbiter.io.out.bits.extra(UcieRegMapperExtra).sideband
    selectedRawMask := arbiter.io.out.bits.extra(UcieRegMapperExtra).rawMask
    val mapperOut = RegMapper(
      mapperBytes,
      concurrency = 0,
      node.undefZero,
      arbiter.io.out,
      allMapping: _*
    )
    val resultExtra = mapperOut.bits.extra(UcieRegMapperExtra)
    val resultShift = resultExtra.lane << 3
    val maskedResult =
      mapperOut.bits.data & FillInterleaved(8, resultExtra.rawMask)

    val invalidTarget = target.req.valid && !targetMapped
    val mapperTargetResult = mapperOut.valid && resultExtra.sideband
    targetResponses.io.enq.valid := mapperTargetResult || invalidTarget
    targetResponses.io.enq.bits.rdata := Mux(
      mapperTargetResult,
      (maskedResult >> resultShift)(63, 0),
      0.U
    )
    targetResponses.io.enq.bits.unsupported := invalidTarget && !mapperTargetResult
    targetResponses.io.enq.bits.internalError := false.B
    target.req.ready := Mux(
      targetMapped,
      targetIn.ready && targetResponses.io.enq.ready,
      targetResponses.io.enq.ready && !mapperTargetResult
    )

    hostResponses.io.enq.valid := mapperOut.valid && !resultExtra.sideband
    hostResponses.io.enq.bits := edge.AccessAck(
      toSource = resultExtra.source,
      lgSize = resultExtra.size
    )
    hostResponses.io.enq.bits.data := (mapperOut.bits.data >> resultShift)(
      node.beatBytes * 8 - 1,
      0
    )
    Connectable.waiveUnmatched(
      hostResponses.io.enq.bits.echo,
      mapperOut.bits.extra
    ) match {
      case (lhs, rhs) => lhs :<= rhs
    }
    hostResponses.io.enq.bits.opcode := Mux(
      mapperOut.bits.read,
      TLMessages.AccessAckData,
      TLMessages.AccessAck
    )
    mapperOut.ready := Mux(
      resultExtra.sideband,
      targetResponses.io.enq.ready,
      hostResponses.io.enq.ready
    )

    d <> hostResponses.io.deq
    target.resp <> targetResponses.io.deq

    bundleIn.b.valid := false.B
    bundleIn.c.ready := true.B
    bundleIn.e.ready := true.B

    when(targetIn.fire) {
      assert(targetEffectiveOffset >= ucieMapBase.U)
      assert(
        targetEffectiveOffset +& targetSizeBytes <= (ucieMapBase + ucieRegionSize).U
      )
    }
    when(mapperOut.valid && resultExtra.sideband) {
      assert(targetResponses.io.enq.ready)
    }

    node.genRegDescsJson((legacyMapping ++ ucieMapping): _*)
  }
}
