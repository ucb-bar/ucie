/*
  Description:
    PatternWriter takes care of sending the various patterns done during training of the mainband.

  NOTE:
  * This implementation is currently specific to a mainband serializer ratio of 32
    - Should ideally parameterize to other serializer ratios with efficient HW
  * Doesn't support burst mode at the moment since burst mode isn't used for regular link training
  operations.
    - Burst mode used in compilance and debug modes
*/

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.utils._
import chisel3._
import chisel3.util._

class PatternWriterIO extends Bundle { 
  val req = Flipped(Decoupled(new Bundle {
    val patternType = PatternSelect()
  }))
  val functionalLanes = Input(UInt(3.W))
  val resp = new Bundle {
    val complete = Output(Bool())
  }
}

class PatternWriter(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val interfaceIo = new PatternWriterIO
    val mbTxLaneIo = Valid(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
    val txLfsrCtrl = new Bundle {
      val pattern = Input(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
      val increment = Output(Bool())
      val resetLfsr = Output(Bool())
      val valid = Output(Bool())
    }
  })

  // Status reg
  val inProgress = RegInit(false.B)  
  val doneSending = WireInit(false.B)
  val patternTypeReg = RegInit(PatternSelect.CLKREPAIR)
  
  // Pattern & Counter setup   
  // --- Clock Repair ---
  // 16 clock cycles followed by 8 cycles of low
  // PATTERN:  1010_1010_1010_1010_1010_1010_1010_1010_0000_0000_0000_0000 (48 bits) 
  // Note: Load the pattern reversed
  val clkPatternWidth = 48
  val clkRepairPattern = "h0000_5555_5555".U(48.W)
  val clkRepairPatternReg = Module(new CircularShiftRegister(width=clkPatternWidth, 
                                                             shiftAmt=afeParams.mbSerializerRatio))
  clkRepairPatternReg.io.reset := false.B
  clkRepairPatternReg.io.en := false.B
  clkRepairPatternReg.io.loadVal := clkRepairPattern

  val clkRepairNumIter = 128
  val clkRepairPatternNumBits = clkRepairNumIter * clkPatternWidth
  val clkRepairPatternNumCycles = clkRepairPatternNumBits / afeParams.mbSerializerRatio
  val clkRepairPatternVec = Wire(Vec(afeParams.mbSerializerRatio, UInt(1.W)))  
  for(i <- 0 until afeParams.mbSerializerRatio) {
    clkRepairPatternVec(i) := clkRepairPatternReg.io.output(i % clkPatternWidth)
  }

  // --- Valtrain --- 
  // four 1's followed by four 0's
  val valTrainPattern = "b0000_1111".U(8.W)
  val valTrainWidth = 8
  val valTrainNumIter = 128
  val valTrainNumBits = valTrainNumIter * valTrainWidth
  val valTrainNumCycles = valTrainNumBits / afeParams.mbSerializerRatio
  val valTrainPatternVec = Wire(Vec(afeParams.mbSerializerRatio, UInt(1.W)))  
  for(i <- 0 until afeParams.mbSerializerRatio) {
    valTrainPatternVec(i) := valTrainPattern(i % valTrainWidth)
  }
    
  // --- PerLane ID --- 
  val perLaneIdPattern = VecInit(Seq.fill(afeParams.mbLanes)(0.U(16.W)))
  for (i <- 0 until afeParams.mbLanes) {
    perLaneIdPattern(i) := Cat("b1010".U(4.W), i.U(8.W), "b1010".U(4.W))
  }
  val perLanePatternWidth = 16
  val perLaneNumIter = 128
  val perLaneNumBits = perLaneNumIter * perLanePatternWidth
  val perLaneNumCycles = perLaneNumBits / afeParams.mbSerializerRatio

  // --- LFSR ---
  /*
    Need to trigger one LFSR and cause it to reset or something 
  */
  val lfsrNumBits = 4096
  val lfsrNumCycles = lfsrNumBits / afeParams.mbSerializerRatio
  io.txLfsrCtrl.valid := false.B
  io.txLfsrCtrl.increment := false.B
  io.txLfsrCtrl.resetLfsr := io.interfaceIo.req.valid && !inProgress
  

  // --- Forwarded Clock ---
  val fwClkPPattern = "b0101_0101".U(8.W)
  val fwClkNPattern = "b1010_1010".U(8.W)
  val fwClkPatternWidth = 8
  val fwClkPPatternVec = Wire(Vec(afeParams.mbSerializerRatio, UInt(1.W)))  
  val fwClkNPatternVec = Wire(Vec(afeParams.mbSerializerRatio, UInt(1.W)))  
  for(i <- 0 until afeParams.mbSerializerRatio) {
    fwClkPPatternVec(i) := fwClkPPattern(i % fwClkPatternWidth)
    fwClkNPatternVec(i) := fwClkNPattern(i % fwClkPatternWidth)
  }

  val largestCycleCount = Seq(clkRepairPatternNumCycles, valTrainNumCycles, 
                              perLaneNumCycles, lfsrNumCycles).max
  val cycleCount = RegInit(0.U(log2Ceil(largestCycleCount).W))
  val maxCycleCount = RegInit((largestCycleCount - 1).U(log2Ceil(largestCycleCount).W))
  val laneMap = PatternLaneMap.decodeLaneMap(io.interfaceIo.functionalLanes, afeParams.mbLanes)

  when(io.interfaceIo.req.valid && !inProgress) {
    inProgress := true.B
    patternTypeReg := io.interfaceIo.req.bits.patternType
    clkRepairPatternReg.io.reset := true.B
  }

  when(doneSending) {
    inProgress := false.B
    cycleCount := 0.U    
  }
  
  io.mbTxLaneIo.bits.data.foreach(_ := 0.U)
  io.mbTxLaneIo.bits.valid := 0.U
  io.mbTxLaneIo.bits.clkP := 0.U
  io.mbTxLaneIo.bits.clkN := 0.U
  io.mbTxLaneIo.bits.trk := 0.U
  io.mbTxLaneIo.valid := inProgress

  io.interfaceIo.req.ready := !inProgress
  io.interfaceIo.resp.complete := doneSending
  
  when(inProgress) {
    
    when(cycleCount =/= maxCycleCount) {
      cycleCount := cycleCount + 1.U
    }
    doneSending := cycleCount === maxCycleCount

    switch(patternTypeReg) {
      is(PatternSelect.CLKREPAIR) {              
        maxCycleCount := (clkRepairPatternNumCycles - 1).U
        clkRepairPatternReg.io.en := !doneSending
        io.mbTxLaneIo.bits.clkP := clkRepairPatternVec.asUInt
        io.mbTxLaneIo.bits.clkN := clkRepairPatternVec.asUInt
        io.mbTxLaneIo.bits.trk := clkRepairPatternVec.asUInt
      }
      is(PatternSelect.VALTRAIN) {
        maxCycleCount := (valTrainNumCycles - 1).U
        io.mbTxLaneIo.bits.valid := valTrainPatternVec.asUInt
        io.mbTxLaneIo.bits.clkP := fwClkPPatternVec.asUInt
        io.mbTxLaneIo.bits.clkN := fwClkNPatternVec.asUInt
      }
      is(PatternSelect.PERLANEID) {
        maxCycleCount := (perLaneNumCycles - 1).U
        for (i <- 0 until afeParams.mbLanes) {
          val logicalLane = PatternLaneMap.activeLaneIndex(
            io.interfaceIo.functionalLanes,
            i,
            afeParams.mbLanes
          )
          val perLaneWord = Cat(
            perLaneIdPattern(logicalLane),
            perLaneIdPattern(logicalLane)
          )
          io.mbTxLaneIo.bits.data(i) := Mux(laneMap(i), perLaneWord, 0.U)
        }
        io.mbTxLaneIo.bits.valid := valTrainPatternVec.asUInt
        io.mbTxLaneIo.bits.clkP := fwClkPPatternVec.asUInt
        io.mbTxLaneIo.bits.clkN := fwClkNPatternVec.asUInt
      }
      is(PatternSelect.LFSR) {                
        // Map logical LFSR lanes onto the currently functional physical lanes so
        // degraded-width training follows the same lane policy as the mainband path.
        maxCycleCount := (lfsrNumCycles - 1).U
        for (i <- 0 until afeParams.mbLanes) {
          val logicalLane = PatternLaneMap.activeLaneIndex(
            io.interfaceIo.functionalLanes,
            i,
            afeParams.mbLanes
          )
          io.mbTxLaneIo.bits.data(i) := Mux(laneMap(i), io.txLfsrCtrl.pattern(logicalLane), 0.U)
        }
        io.txLfsrCtrl.increment := cycleCount =/= maxCycleCount          
        io.txLfsrCtrl.valid := true.B
        io.mbTxLaneIo.bits.valid := valTrainPatternVec.asUInt
        io.mbTxLaneIo.bits.clkP := fwClkPPatternVec.asUInt
        io.mbTxLaneIo.bits.clkN := fwClkNPatternVec.asUInt   
      }
    }
  }  
}

