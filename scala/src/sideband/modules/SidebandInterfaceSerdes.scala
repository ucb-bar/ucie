/*  
  Description: 
    The sideband interface serdes serializes and deserializes messages over FDI/RDI interface.
    Parameterizable to serialize/deserialize at NC_WIDTH: 8, 16, 32 as per spec.

    This file contains the RTL for:
      1. Serializer
      2. Deserializer    

    Note: 
    * The serializer and deserializer doesn't consider the opcode during serialization, because
    each credit associated with a message considers both a 64-bit header and potential 64-bit
    payload.
      - The added latency for serializing 0s is 8, 4, 2 cycles for 
        NC_WIDTH: 8, 16, 32, respectively.    
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import scala.math.max

class SidebandInterfaceSerializer(sbMsgWidth: Int, ncWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(sbMsgWidth.W)))
    val out = Valid(UInt(ncWidth.W))
  })

  // sbMsgWidth is the width of a sideband message, ncWidth is the interface sideband bus width
  assert(sbMsgWidth % ncWidth == 0 && ncWidth <= sbMsgWidth)
  val numBeats = (sbMsgWidth / ncWidth)
  val maxBeats = numBeats - 1
  val counterWidth = max(1, log2Ceil(numBeats))

  val beatCounter = RegInit(0.U(counterWidth.W))
  val dataReg = RegInit(0.U(sbMsgWidth.W))
  val inProgress = RegInit(false.B)
  val isLastBeat = WireDefault(beatCounter === maxBeats.U)

  io.out.bits := dataReg(ncWidth - 1, 0)
  io.out.valid := inProgress

  io.in.ready := !inProgress || isLastBeat

  when(inProgress) {
    when(!isLastBeat) {
      dataReg := dataReg >> ncWidth   // Using ncWidth.U may generate a barrel shifter instead of rewiring
      beatCounter := beatCounter + 1.U
    }.otherwise {
      when(io.in.valid) {   // Immediately loads back-to-back valid packet
        dataReg := io.in.bits
        beatCounter := 0.U
      }.otherwise {
        inProgress := false.B
        beatCounter := 0.U
      }
    }
  }

  when(!inProgress && io.in.valid) {
    dataReg := io.in.bits
    inProgress := true.B
    beatCounter := 0.U
  }
}

class SidebandInterfaceDeserializer(sbMsgWidth: Int, ncWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Valid(UInt(ncWidth.W)))
    val out = Valid(UInt(sbMsgWidth.W)) // Receiving end should sink when valid is high for a cycle
  })

  // sbMsgWidth is the width of a sideband message, ncWidth is the interface sideband bus width
  assert(sbMsgWidth % ncWidth == 0 && ncWidth <= sbMsgWidth)
  val numBeats = (sbMsgWidth / ncWidth)
  val maxBeats = numBeats - 1
  val counterWidth = max(1, log2Ceil(numBeats))
  
  val beatCounter = RegInit(0.U(counterWidth.W))
  val dataReg = RegInit(VecInit.fill(numBeats)(0.U(ncWidth.W)))
  val inProgress = RegInit(false.B)

  io.out.bits := dataReg.asUInt
  io.out.valid := inProgress && (beatCounter === 0.U)

  when(!io.in.valid) {
    inProgress := false.B
    beatCounter := 0.U
  }

  // As per spec can be assured that consecutive phases of a packet will come in consecutive 
  // clock cycles
  when(io.in.valid) {
    dataReg(beatCounter) := io.in.bits
    inProgress := true.B

    when(beatCounter =/= maxBeats.U) {       
      beatCounter := beatCounter + 1.U
    }.otherwise {
      beatCounter := 0.U
    }
  }
} 


object MainSBIntfSer extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandInterfaceSerializer(128, 32),
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

object MainSBIntfDes extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandInterfaceDeserializer(128, 32),
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