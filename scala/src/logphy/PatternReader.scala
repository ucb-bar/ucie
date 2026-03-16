package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.utils._
import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

/*
  Description:
    PatternReader takes care of pattern comparison done during training of the mainband.
    The RX mainband lanes (data, valid, clkN, clkP, track) will enter and depending on the 
    control signals from the LTSM, it will do the appropriate pattern comparison

  NOTE:
  * This implementation is currently specific to a mainband serializer ratio of 32
    - Should ideally parameterize to other serializer ratios with efficient HW generation
    - Depending on serializer ratio some patterns that don't easily divde by serializer ratio
      require a shift register to keep pattern aligned. Patterns that do easily divide can be
      compared against wires, no registers needed.      
*/

class PatternReaderIO(numMbLanes: Int) extends Bundle {
  val req = Flipped(Decoupled(new Bundle {    
    val patternType = Input(PatternSelect())      // Which pattern to detect
    val comparisonMode = Input(ComparisonMode())  // Which type of error detection to do
    val errorThreshold = Input(UInt(16.W))        // Max amount before failure
    val doConsecutiveCount = Input(Bool())        // Whether to detect consecutive patterns or not
    val done = Input(Bool())
    val clear = Input(Bool()) 
  }))
  val resp = Valid(new Bundle {
    val perLaneStatusBits = Output(Vec(numMbLanes, Bool()))
    val aggregateStatus = Output(Bool()) 
  })
}
class PatternReader(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val interfaceIo = new PatternReaderIO(afeParams.mbLanes)    
    val rxLfsrCtrl = new Bundle {
      val increment = Output(Bool())
      val resetLfsr = Output(Bool())
      val pattern = Input(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
    }
    val mbRxLaneIo = Input(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))    
  })

  // State registers/wires
  val inProgress = RegInit(false.B)
  val patternTypeReg = RegInit(PatternSelect.CLKREPAIR)
  val comparisonModeReg = RegInit(ComparisonMode.PERLANE)
  val doConsecutiveCountReg = RegInit(false.B)
  val errorThresholdReg = RegInit(0.U(16.W))  

  // Note: When doConsecutiveCountReg is TRUE, errorCounterReg will count the number of cycles
  // a pattern has been seen consecutively.
  val errorCounterWire = Wire(Vec(afeParams.mbLanes, UInt(16.W)))
  val errorCounterReg = RegInit(VecInit(Seq.fill(afeParams.mbLanes)(0.U(16.W))))  
   
  // Pattern comparison wires
  val patternCompStatus = Wire(Vec(afeParams.mbLanes, Bool()))
  val doDetection = Wire(Bool())
  val isValidData = Wire(Bool())
  val counterEn = Wire(Bool())

  // patternDetected is used for consecutive pattern detection. When patternDetected is HIGH,
  // there's no error from the perlane comparison so update the error counter reg.
  // Otherwise, pattern not detected consecutively so drive 0 instead
  val patternDetected = WireInit(VecInit(Seq.fill(afeParams.mbLanes)(false.B)))

  // Delay the done signal because it takes a cycle for the error 
  // counter to register so comparator can use updated count.
  // TODO: SVA - When inprogress and done goes high, it should stay high
  val delayedDoneSignal = Reg(Bool())
  delayedDoneSignal := io.interfaceIo.req.bits.done

  // Pattern generation logic
  // For consecutive pattern detection, each pattern takes a different number of cycles
  // to detect 16 consecutive iterations
  // Values with `NumCycles` suffix is the number of consecutive cycles of detection required to
  // detect 16 consecutive iterations
  val numConsecutive = 16   // Spec requires at least 16 consecutive detections

  // Clock Repair
  val clkPatternWidth = 48
  val clkRepairPattern = "h0000_5555_5555".U(48.W)
  
  // One shift register for ClkP, ClkN, Trk each as they are tracked individually during
  // a read of Clock Repair pattern
  val clkRepairPatternReg = Seq.fill(3)(Module(new CircularShiftRegister(width=clkPatternWidth, 
                                                            shiftAmt=afeParams.mbSerializerRatio)))
  for(i <- 0 until 3) {
    clkRepairPatternReg(i).io.reset := false.B
    clkRepairPatternReg(i).io.en := doDetection && (patternTypeReg === PatternSelect.CLKREPAIR) && 
                                    patternDetected(i)
    clkRepairPatternReg(i).io.loadVal := clkRepairPattern
  }

  val clkRepairPatternNumBits = numConsecutive * clkPatternWidth
  val clkRepairPatternNumCycles = clkRepairPatternNumBits / afeParams.mbSerializerRatio
  val clkRepairRefPatternVec = Seq.fill(3)(Wire(Vec(afeParams.mbSerializerRatio, UInt(1.W))))
  for(i <- 0 until 3) {
    for(j <- 0 until afeParams.mbSerializerRatio) {
      clkRepairRefPatternVec(i)(j) := clkRepairPatternReg(i).io.output(j % clkPatternWidth)
    }
  }

  // Valtrain
  val valTrainWidth = 8
  val valTrainPattern = "b0000_1111".U(8.W)
  val valTrainPerWord = afeParams.mbSerializerRatio / 8 // 32/8 = 4
  val valTrainNumCycles = numConsecutive / valTrainPerWord
  val valTrainRefPatternVec = Wire(Vec(afeParams.mbSerializerRatio, UInt(1.W)))  
  for(i <- 0 until afeParams.mbSerializerRatio) {
    valTrainRefPatternVec(i) := valTrainPattern(i % valTrainWidth)
  }

  // PerLane ID
  // Only works for mb serializer ratio of 32
  val perLanePatternWidth = 16
  val perLanePerWord = afeParams.mbSerializerRatio / perLanePatternWidth
  val perLaneNumCycles = numConsecutive / perLanePerWord  
  val perLaneIdPattern = Wire(Vec(afeParams.mbLanes, (UInt(16.W))))
  val perLaneIdRefPatternVec = Wire(Vec(afeParams.mbLanes, (UInt(32.W))))
  for(i <- 0 until afeParams.mbLanes) {
    perLaneIdPattern(i) := Cat("b1010".U(4.W), i.U(8.W), "b1010".U(4.W))
    perLaneIdRefPatternVec(i) := Cat(perLaneIdPattern(i), perLaneIdPattern(i))
  }
  
  // LFSR
  io.rxLfsrCtrl.increment := doDetection && (patternTypeReg === PatternSelect.LFSR) && isValidData
  io.rxLfsrCtrl.resetLfsr := io.interfaceIo.req.valid && !inProgress  // Reset when new request

  // Note: When inProgress, and looking for the CLKREPAIR pattern, hold constant HIGH 
  // since detection is done on the clock and track lanes.
  isValidData := io.mbRxLaneIo.valid === valTrainRefPatternVec.asUInt
  doDetection := inProgress && !delayedDoneSignal
  counterEn := doDetection && (isValidData || patternTypeReg === PatternSelect.CLKREPAIR) 

  // IO connections
  io.interfaceIo.resp.bits.perLaneStatusBits.zipWithIndex.foreach{case(res, i) => 
    res := patternCompStatus(i)
  }
  io.interfaceIo.resp.bits.aggregateStatus := patternCompStatus(0)

  // TODO: SVA -- valid should go after after one cycle once io.interfaceIo.req.done goes high
  io.interfaceIo.resp.valid := delayedDoneSignal && inProgress 
  io.interfaceIo.req.ready := !inProgress

  // Reset state
  when(inProgress && io.interfaceIo.req.bits.clear) {
    inProgress := false.B
    errorCounterReg.foreach(x => x := 0.U)
    clkRepairPatternReg.foreach(x => x.io.reset := true.B)
  }

  // Ready to accept and new request has come in   
  // TODO: SVA -- If pattern detection is clock repair or perlane id then error threshold must be 0
  // because spec only detects consecutive patterns for those, and useConsecutive count should be
  // high
  // TODO: SVA -- If doConsecutive, then must be in PERLANE comparison mode because it reuses the
  //              perlane XOR -> POPCOUNT (no need to === separately which is roughly the same)
  when(io.interfaceIo.req.valid && !inProgress) { 
    inProgress := true.B 
    patternTypeReg := io.interfaceIo.req.bits.patternType
    comparisonModeReg := io.interfaceIo.req.bits.comparisonMode  
    doConsecutiveCountReg := io.interfaceIo.req.bits.doConsecutiveCount
  
    // Reuse errorThresholdReg to hold the number of cycles for consecutive pattern detection
    when(io.interfaceIo.req.bits.doConsecutiveCount) {
      switch(io.interfaceIo.req.bits.patternType) {
        is(PatternSelect.CLKREPAIR) {
          errorThresholdReg := clkRepairPatternNumCycles.U
        }
        is(PatternSelect.VALTRAIN) {
          errorThresholdReg := valTrainNumCycles.U
        }
        is(PatternSelect.PERLANEID) {
          errorThresholdReg := perLaneNumCycles.U
        }
      }      
    }.otherwise {
      errorThresholdReg := io.interfaceIo.req.bits.errorThreshold
    }    
  }

  // Logic for comparison and error checking
  val xorResult = Wire(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
  val remotePattern = Wire(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
  val localPattern = Wire(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W))) 
  xorResult.zipWithIndex.foreach{case(res, i) => res := remotePattern(i) ^ localPattern(i)}
  remotePattern.foreach(x => x := 0.U)
  localPattern.foreach(x => x := 0.U)

  switch(patternTypeReg) {
    is(PatternSelect.CLKREPAIR) {
      remotePattern(0) := io.mbRxLaneIo.clkP
      localPattern(0) := clkRepairRefPatternVec(0).asUInt
      remotePattern(1) := io.mbRxLaneIo.clkN
      localPattern(1) := clkRepairRefPatternVec(1).asUInt
      remotePattern(2) := io.mbRxLaneIo.trk
      localPattern(2) := clkRepairRefPatternVec(2).asUInt
    }
    is(PatternSelect.VALTRAIN) {
      remotePattern(0) := io.mbRxLaneIo.valid
      localPattern(0) := valTrainRefPatternVec.asUInt
    }
    is(PatternSelect.PERLANEID) {
      remotePattern.zipWithIndex.foreach{case (res, i) => res := io.mbRxLaneIo.data(i)}
      localPattern.zipWithIndex.foreach{case (res, i) => res := perLaneIdRefPatternVec(i)}
    }
    is(PatternSelect.LFSR) {
      remotePattern.zipWithIndex.foreach{case (res, i) => res := io.mbRxLaneIo.data(i)}
      localPattern.zipWithIndex.foreach{case (res, i) => res := io.rxLfsrCtrl.pattern(i)}
    }
  }

  val aggregateOrResult = Wire(UInt(afeParams.mbSerializerRatio.W))
  aggregateOrResult := xorResult.reduceTree(_ | _)
  
  val popCountResult = Wire(Vec(afeParams.mbLanes, 
                                UInt(log2Ceil(afeParams.mbSerializerRatio + 1).W)))

  when(comparisonModeReg === ComparisonMode.AGGREGATE) {
    popCountResult(0) := PopCount(aggregateOrResult.asUInt)
    for(i <- 1 until afeParams.mbLanes) {
      popCountResult(i) := 0.U
     }
  }.otherwise { // ComparisonMode.PERLANE
    popCountResult.zipWithIndex.foreach{case(res, i) => res := PopCount(xorResult(i))}
  }
  
  val adderResult = Wire(Vec(afeParams.mbLanes, UInt(16.W)))
  val adderOperand1 = Wire(Vec(afeParams.mbLanes, UInt(16.W)))
  val adderOperand2 = Wire(Vec(afeParams.mbLanes, UInt(16.W)))

  adderResult.zipWithIndex.foreach{case(res, i) => res := adderOperand1(i) + adderOperand2(i)}

  adderOperand1.zipWithIndex.foreach{case(res, i) => res := errorCounterReg(i)}
  when(doConsecutiveCountReg) {
    adderOperand2.zipWithIndex.foreach{case(res, i) => res := 1.U}
  }.otherwise {
    adderOperand2.zipWithIndex.foreach{case(res, i) =>
      res := Cat(0.U((16 - log2Ceil(afeParams.mbSerializerRatio + 1)).W), popCountResult(i))
    }
  }

  when(doConsecutiveCountReg) {
    patternDetected.zipWithIndex.foreach{case(res, i) => res := (popCountResult(i) === 0.U)}
  }

  errorCounterWire.zipWithIndex.foreach{case(res, i) => res := adderResult(i)} // Default
  when(doConsecutiveCountReg) {
    for(i <- 0 until afeParams.mbLanes) {
      when(!patternDetected(i)) {
        errorCounterWire(i) := 0.U
      }
    }
  }
  
  when(counterEn) {
    // For consecutive detection, don't update the reg when number of consecutive iterations met
    when(doConsecutiveCountReg) {
      for(i <- 0 until afeParams.mbLanes) {
        when(!patternCompStatus(i)) {
          errorCounterReg(i) := errorCounterWire(i)
        }
      }
    }.otherwise {
      errorCounterReg.zipWithIndex.foreach{case(reg, i) => reg := errorCounterWire(i)}
    }
  }

  when(doConsecutiveCountReg) {
    patternCompStatus.zipWithIndex.foreach{case(res, i) => 
      res := errorCounterReg(i) === errorThresholdReg
    }
  }.otherwise {
    patternCompStatus.zipWithIndex.foreach{case(res, i) =>
      res := errorCounterReg(i) <= errorThresholdReg
    }
  } 
}


object MainPatternReader extends App {
  ChiselStage.emitSystemVerilogFile(
    new PatternReader(new AfeParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables",
    ),
  )
}