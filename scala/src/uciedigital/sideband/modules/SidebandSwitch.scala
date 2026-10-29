/*
  Routes sideband packets between a layer and the layers above and below it,
  by dstid (Table 7-2).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import chisel3.util._

class SidebandSwitch(
    layerId: Int,
    upperIds: Seq[Int],
    lowerIds: Seq[Int],
    sbMsgWidth: Int,
    routeRemoteRegRequestsToUpper: Boolean = false,
    routeRegAccessToCurrent: Boolean = false,
    routeUpperRegRequestsToCurrent: Boolean = false,
    classAwareLowerArbitration: Boolean = false
) extends Module {
  require(
    !(routeRemoteRegRequestsToUpper && routeRegAccessToCurrent),
    "Only one lower-ingress register routing override may be enabled"
  )

  val io = IO(new Bundle {
    val upperLayer = new Bundle {
      val from = Flipped(Decoupled(UInt(sbMsgWidth.W)))
      val to = Decoupled(UInt(sbMsgWidth.W))
    }
    val currLayer = new Bundle {
      val from = Flipped(Decoupled(UInt(sbMsgWidth.W)))
      val to = Decoupled(UInt(sbMsgWidth.W))
    }
    val lowerLayer = new Bundle {
      val from = Flipped(Decoupled(UInt(sbMsgWidth.W)))
      val to = Decoupled(UInt(sbMsgWidth.W))
      val toClassReady = Input(Vec(4, Bool()))
    }

    val err = new Bundle {
      val invalidRouteUpper = Output(Bool())
      val invalidRouteCurr = Output(Bool())
      val invalidRouteLower = Output(Bool())
    }
  })

  // ===========================================================================
  // Helpers and arbiters
  // ===========================================================================
  def getDstLayer(msg: UInt): UInt = msg(57, 56)
  def isRemote(msg: UInt): Bool = msg(58) === 1.U
  def matchesAnyId(dstId: UInt, idList: Seq[Int]): Bool = {
    idList.map(id => dstId === id.U(2.W)).foldLeft(false.B)(_ || _)
  }

  def packetRank(packet: UInt): UInt = {
    val opcode = packet(4, 0)
    Mux(
      SBM.isReqRespMessage(opcode),
      0.U,
      Mux(
        SBM.isRegAccessComplete(opcode),
        1.U,
        Mux(SBM.isRegAccessRequest(opcode), 2.U, 3.U)
      )
    )
  }

  val arbiterToCurrLayer = Module(new RRArbiter(UInt(sbMsgWidth.W), 2))
  val arbiterToUpperLayer = Module(new RRArbiter(UInt(sbMsgWidth.W), 2))
  val arbiterToLowerLayer = Module(new RRArbiter(UInt(sbMsgWidth.W), 2))
  val currentLowerWins = WireDefault(false.B)
  val upperLowerWins = WireDefault(false.B)

  io.currLayer.to <> arbiterToCurrLayer.io.out
  io.upperLayer.to <> arbiterToUpperLayer.io.out
  io.lowerLayer.to <> arbiterToLowerLayer.io.out

  // ===========================================================================
  // Demux from the upper layer
  // ===========================================================================
  val upperDstLower =
    matchesAnyId(getDstLayer(io.upperLayer.from.bits), lowerIds)

  // The Adapter answers every Protocol Layer register access itself, so one
  // addressed to the Physical Layer (Table 7-2) stops here.
  val forceUpperToCurr = routeUpperRegRequestsToCurrent.B &&
    !isRemote(io.upperLayer.from.bits) && upperDstLower &&
    SBM.isRegAccessRequest(io.upperLayer.from.bits(4, 0))
  val upperToCurr = forceUpperToCurr || (!isRemote(io.upperLayer.from.bits) &&
    (getDstLayer(io.upperLayer.from.bits) === layerId.U))
  val upperToLower = !forceUpperToCurr &&
    (isRemote(io.upperLayer.from.bits) || upperDstLower)
  val upperMalformed = !(upperToCurr || upperToLower)

  arbiterToCurrLayer.io.in(0).valid := io.upperLayer.from.valid && upperToCurr
  arbiterToCurrLayer.io.in(0).bits := io.upperLayer.from.bits

  arbiterToLowerLayer.io.in(0).valid := io.upperLayer.from.valid &&
    upperToLower && !currentLowerWins
  arbiterToLowerLayer.io.in(0).bits := io.upperLayer.from.bits

  // A packet routed to neither is accepted and dropped.
  io.upperLayer.from.ready := Mux(
    upperToCurr,
    arbiterToCurrLayer.io.in(0).ready,
    Mux(
      upperToLower,
      !currentLowerWins && arbiterToLowerLayer.io.in(0).ready,
      true.B
    )
  )

  // TODO: Show the header of the packet that caused an invalid route error.
  io.err.invalidRouteUpper := io.upperLayer.from.valid && upperMalformed

  // ===========================================================================
  // Demux from the lower layer
  // ===========================================================================
  val lowerOpcode = io.lowerLayer.from.bits(4, 0)
  val lowerRemoteRegRequest = isRemote(io.lowerLayer.from.bits) &&
    SBM.isRemoteRegAccessRequest(lowerOpcode)
  val forceLowerToUpper = routeRemoteRegRequestsToUpper.B &&
    lowerRemoteRegRequest
  val forceLowerToCurr = routeRegAccessToCurrent.B &&
    (lowerRemoteRegRequest || SBM.isRegAccessComplete(lowerOpcode))
  val lowerToCurr = forceLowerToCurr ||
    (!forceLowerToUpper && getDstLayer(io.lowerLayer.from.bits) === layerId.U)
  val lowerToUpper = forceLowerToUpper ||
    (!forceLowerToCurr && matchesAnyId(
      getDstLayer(io.lowerLayer.from.bits),
      upperIds
    ))
  val lowerMalformed = !(lowerToCurr || lowerToUpper)

  arbiterToCurrLayer.io.in(1).valid := io.lowerLayer.from.valid && lowerToCurr
  arbiterToCurrLayer.io.in(1).bits := io.lowerLayer.from.bits

  arbiterToUpperLayer.io.in(0).valid := io.lowerLayer.from.valid && lowerToUpper
  arbiterToUpperLayer.io.in(0).bits := io.lowerLayer.from.bits

  io.lowerLayer.from.ready := Mux(
    lowerToCurr,
    arbiterToCurrLayer.io.in(1).ready,
    Mux(lowerToUpper, arbiterToUpperLayer.io.in(0).ready, true.B)
  )

  io.err.invalidRouteLower := io.lowerLayer.from.valid && lowerMalformed

  // ===========================================================================
  // Demux from the current layer
  // ===========================================================================
  val currToUpper = !isRemote(io.currLayer.from.bits) &&
    matchesAnyId(getDstLayer(io.currLayer.from.bits), upperIds)
  val currToLower = isRemote(io.currLayer.from.bits) ||
    matchesAnyId(getDstLayer(io.currLayer.from.bits), lowerIds)
  val currMalformed = !(currToUpper || currToLower)
  val upperRank = packetRank(io.upperLayer.from.bits)
  val currentRank = packetRank(io.currLayer.from.bits)
  val lowerClassContention = classAwareLowerArbitration.B &&
    io.upperLayer.from.valid && upperToLower &&
    io.currLayer.from.valid && currToLower
  val upperClassReady = io.lowerLayer.toClassReady(upperRank)
  val currentClassReady = io.lowerLayer.toClassReady(currentRank)
  currentLowerWins := lowerClassContention && currentClassReady &&
    (!upperClassReady || currentRank < upperRank)
  upperLowerWins := lowerClassContention && upperClassReady &&
    (!currentClassReady || upperRank < currentRank)

  arbiterToUpperLayer.io.in(1).valid := io.currLayer.from.valid && currToUpper
  arbiterToUpperLayer.io.in(1).bits := io.currLayer.from.bits

  arbiterToLowerLayer.io.in(1).valid := io.currLayer.from.valid &&
    currToLower && !upperLowerWins
  arbiterToLowerLayer.io.in(1).bits := io.currLayer.from.bits

  io.currLayer.from.ready := Mux(
    currToUpper,
    arbiterToUpperLayer.io.in(1).ready,
    Mux(
      currToLower,
      !upperLowerWins && arbiterToLowerLayer.io.in(1).ready,
      true.B
    )
  )

  io.err.invalidRouteCurr := io.currLayer.from.valid && currMalformed

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(io.currLayer.to.fire) |->
          Sequence.BoolSequence(
            getDstLayer(io.currLayer.to.bits) === layerId.U ||
              (routeRegAccessToCurrent.B &&
                ((isRemote(io.currLayer.to.bits) &&
                  SBM.isRemoteRegAccessRequest(io.currLayer.to.bits(4, 0))) ||
                  SBM.isRegAccessComplete(io.currLayer.to.bits(4, 0)))) ||
              (routeUpperRegRequestsToCurrent.B &&
                !isRemote(io.currLayer.to.bits) &&
                SBM.isRegAccessRequest(io.currLayer.to.bits(4, 0)) &&
                matchesAnyId(getDstLayer(io.currLayer.to.bits), lowerIds))
          ),
        label = Some("SwitchCurrEgressAddressedToCurrLayer")
      )
      AssertProperty(
        Sequence.BoolSequence(io.upperLayer.to.fire) |->
          Sequence.BoolSequence(
            matchesAnyId(getDstLayer(io.upperLayer.to.bits), upperIds) ||
              (routeRemoteRegRequestsToUpper.B &&
                isRemote(io.upperLayer.to.bits) &&
                SBM.isRemoteRegAccessRequest(io.upperLayer.to.bits(4, 0)))
          ),
        label = Some("SwitchUpperEgressAddressedToUpperIds")
      )
      AssertProperty(
        Sequence.BoolSequence(io.lowerLayer.to.fire) |->
          Sequence.BoolSequence(
            isRemote(io.lowerLayer.to.bits) ||
              matchesAnyId(getDstLayer(io.lowerLayer.to.bits), lowerIds)
          ),
        label = Some("SwitchLowerEgressAddressedToLowerIdsOrRemote")
      )
    }
    block(Verification.Cover) {
      cover(io.upperLayer.from.fire && upperToCurr, "SwitchUpperToCurrRoute")
      if (lowerIds.nonEmpty) {
        cover(
          io.upperLayer.from.fire && upperToLower,
          "SwitchUpperToLowerRoute"
        )
      }
      cover(io.lowerLayer.from.fire && lowerToCurr, "SwitchLowerToCurrRoute")
      if (routeRegAccessToCurrent) {
        cover(
          io.lowerLayer.from.fire && forceLowerToCurr,
          "SwitchLowerRegAccessToCurrRoute"
        )
      }
      if (upperIds.nonEmpty) {
        cover(
          io.lowerLayer.from.fire && lowerToUpper,
          "SwitchLowerToUpperRoute"
        )
        cover(io.currLayer.from.fire && currToUpper, "SwitchCurrToUpperRoute")
      }
      if (routeRemoteRegRequestsToUpper) {
        cover(
          io.lowerLayer.from.fire && forceLowerToUpper,
          "SwitchLowerRemoteRegRequestToUpperRoute"
        )
      }
      cover(io.currLayer.from.fire && currToLower, "SwitchCurrToLowerRoute")

      cover(io.err.invalidRouteUpper, "SwitchInvalidRouteUpper")
      cover(io.err.invalidRouteCurr, "SwitchInvalidRouteCurr")
      cover(io.err.invalidRouteLower, "SwitchInvalidRouteLower")
      cover(
        io.upperLayer.from.valid && !io.upperLayer.from.ready,
        "SwitchUpperIngressBackpressure"
      )
      cover(
        io.currLayer.from.valid && !io.currLayer.from.ready,
        "SwitchCurrIngressBackpressure"
      )
      cover(
        io.lowerLayer.from.valid && !io.lowerLayer.from.ready,
        "SwitchLowerIngressBackpressure"
      )

      cover(
        arbiterToCurrLayer.io.in(0).valid && arbiterToCurrLayer.io.in(1).valid,
        "SwitchCurrEgressContention"
      )
      cover(
        arbiterToCurrLayer.io.out.fire && arbiterToCurrLayer.io.chosen === 0.U,
        "SwitchCurrEgressUpperWins"
      )
      cover(
        arbiterToCurrLayer.io.out.fire && arbiterToCurrLayer.io.chosen === 1.U,
        "SwitchCurrEgressLowerWins"
      )
      if (upperIds.nonEmpty) {
        cover(
          arbiterToUpperLayer.io
            .in(0)
            .valid && arbiterToUpperLayer.io.in(1).valid,
          "SwitchUpperEgressContention"
        )
        cover(
          arbiterToUpperLayer.io.out.fire && arbiterToUpperLayer.io.chosen === 0.U,
          "SwitchUpperEgressLowerWins"
        )
        cover(
          arbiterToUpperLayer.io.out.fire && arbiterToUpperLayer.io.chosen === 1.U,
          "SwitchUpperEgressCurrWins"
        )
      }
      cover(
        arbiterToLowerLayer.io
          .in(0)
          .valid && arbiterToLowerLayer.io.in(1).valid,
        "SwitchLowerEgressContention"
      )
      cover(
        arbiterToLowerLayer.io.out.fire && arbiterToLowerLayer.io.chosen === 0.U,
        "SwitchLowerEgressUpperWins"
      )
      cover(
        arbiterToLowerLayer.io.out.fire && arbiterToLowerLayer.io.chosen === 1.U,
        "SwitchLowerEgressCurrWins"
      )
    }
  }
}
