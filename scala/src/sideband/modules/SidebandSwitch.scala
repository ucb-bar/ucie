package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Description:
    Contains logic for a sideband switch. It manages routing of sideband packages based on packet
    srcid and dstid.

    layer: can be set with SBSourceDestination which is in SidebandMessageEncodings.scala

    Instantiation Examples:
    // Protocol Layer
    // Has no upper IDs. Lower IDs are Adapter (1), LogPHY (2)
    SidebandSwitch(layerId = 0, upperIds = Seq(), lowerIds = Seq(1, 2), sbMsgWidth = 128)

    // Adapter Layer
    // Upper is Protocol (0). Lower is LogPHY (2)
    SidebandSwitch(layerId = 1, upperIds = Seq(0), lowerIds = Seq(2), sbMsgWidth = 128)

    // LogPHY Layer
    // Upper is Protocol (0) and Adapter (1). No lower IDs
    SidebandSwitch(layerId = 2, upperIds = Seq(0, 1), lowerIds = Seq(), sbMsgWidth = 128)
*/

class SidebandSwitch(layerId: Int, upperIds: Seq[Int], lowerIds: Seq[Int], sbMsgWidth: Int) 
extends Module {
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
    }

    val err = new Bundle {
      val invalidRouteUpper = Output(Bool())
      val invalidRouteCurr = Output(Bool())
      val invalidRouteLower = Output(Bool())
      // TODO: Might want to add show the header of the packet causing the error
    }
  })

  // Helper functions
  def getDstLayer(msg: UInt): UInt = msg(25, 24)
  def isRemote(msg: UInt): Bool = msg(26) === 1.U
  def matchesAnyId(dstId: UInt, idList: Seq[Int]): Bool = {
    idList.map(id => dstId === id.U(2.W)).foldLeft(false.B)(_ || _)
  }

  val arbiterToCurrLayer  = Module(new RRArbiter(UInt(sbMsgWidth.W), 2))
  val arbiterToUpperLayer = Module(new RRArbiter(UInt(sbMsgWidth.W), 2))
  val arbiterToLowerLayer = Module(new RRArbiter(UInt(sbMsgWidth.W), 2))

  io.currLayer.to  <> arbiterToCurrLayer.io.out
  io.upperLayer.to <> arbiterToUpperLayer.io.out
  io.lowerLayer.to <> arbiterToLowerLayer.io.out

  // =======================================================================
  // Demux from UPPER Layer
  // =======================================================================
  val upperToCurr  =  !isRemote(io.upperLayer.from.bits) && 
                      (getDstLayer(io.upperLayer.from.bits) === layerId.U)
  val upperToLower =  isRemote(io.upperLayer.from.bits) || 
                      matchesAnyId(getDstLayer(io.upperLayer.from.bits), lowerIds)
  val upperMalformed = !(upperToCurr || upperToLower)  // Malformed destination from upper layer              

  arbiterToCurrLayer.io.in(0).valid := io.upperLayer.from.valid && upperToCurr
  arbiterToCurrLayer.io.in(0).bits := io.upperLayer.from.bits

  arbiterToLowerLayer.io.in(0).valid := io.upperLayer.from.valid && upperToLower
  arbiterToLowerLayer.io.in(0).bits := io.upperLayer.from.bits

  // If not upperToCurr nor upperToLower, drop packet
  io.upperLayer.from.ready := Mux(upperToCurr, arbiterToCurrLayer.io.in(0).ready,
                              Mux(upperToLower, arbiterToLowerLayer.io.in(0).ready, true.B))

  io.err.invalidRouteUpper := io.upperLayer.from.valid && upperMalformed  // Trigger error

  // =======================================================================
  // Demux from LOWER Layer
  // =======================================================================
  val lowerToCurr  = getDstLayer(io.lowerLayer.from.bits) === layerId.U
  val lowerToUpper = matchesAnyId(getDstLayer(io.lowerLayer.from.bits), upperIds)
  val lowerMalformed = !(lowerToCurr || lowerToUpper) // Malformed destination from lower layer 

  arbiterToCurrLayer.io.in(1).valid := io.lowerLayer.from.valid && lowerToCurr
  arbiterToCurrLayer.io.in(1).bits := io.lowerLayer.from.bits

  arbiterToUpperLayer.io.in(0).valid := io.lowerLayer.from.valid && lowerToUpper
  arbiterToUpperLayer.io.in(0).bits := io.lowerLayer.from.bits

  io.lowerLayer.from.ready := Mux(lowerToCurr, arbiterToCurrLayer.io.in(1).ready,
                              Mux(lowerToUpper, arbiterToUpperLayer.io.in(0).ready, true.B))

  io.err.invalidRouteLower := io.lowerLayer.from.valid && lowerMalformed  // Trigger error

  // =======================================================================
  // Demux from CURRENT Layer
  // =======================================================================
  val currToUpper  =  !isRemote(io.currLayer.from.bits) && 
                        matchesAnyId(getDstLayer(io.currLayer.from.bits), upperIds)
  val currToLower  =  isRemote(io.currLayer.from.bits) || 
                        matchesAnyId(getDstLayer(io.currLayer.from.bits), lowerIds)
  val currMalformed = !(currToUpper || currToLower) // Malformed destination from curr layer                         

  arbiterToUpperLayer.io.in(1).valid := io.currLayer.from.valid && currToUpper
  arbiterToUpperLayer.io.in(1).bits := io.currLayer.from.bits

  arbiterToLowerLayer.io.in(1).valid := io.currLayer.from.valid && currToLower
  arbiterToLowerLayer.io.in(1).bits := io.currLayer.from.bits

  io.currLayer.from.ready := Mux(currToUpper, arbiterToUpperLayer.io.in(1).ready,
                             Mux(currToLower, arbiterToLowerLayer.io.in(1).ready, true.B))

  io.err.invalidRouteCurr := io.currLayer.from.valid && currMalformed  // Trigger error                             
}

object MainSBSwitch extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandSwitch(layerId = 2, upperIds = Seq(0, 1), lowerIds = Seq(), sbMsgWidth = 128),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}