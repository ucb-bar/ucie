package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.utils._
import chisel3._
import circt.stage.ChiselStage

/*
  Description:
    Instatiates a Parallel Galois LFSR per lane.   

    Note:
    - When resetLfsr and increment is toggled high the update occurs on the following cycle
*/

class UcieLFSR(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val increment = Input(Vec(afeParams.mbLanes, Bool()))
    val resetLfsr = Input(Vec(afeParams.mbLanes, Bool()))
    val lfsrOutput = Output(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
  })

  // UCIe Spec logPHY LFSR
  // Galois Polynomial: G(X)=X^23 + X^21 + X^16 + X^8 + X^5 + X^2 + 1
  // Polynomial in binary: 1010_0001_0000_0001_0010_0101
  // Need to forgo msb when converting to hex for ParallelGaloisLFSR 
  // ==> 010_0001_0000_0001_0010_0101
  val polynomial = 0x210125

  val laneSeeds = List(
    0x1DBFBC, 0x0607BB, 0x1EC760, 0x18C0DB, 0x010F12, 0x19CFC9, 0x0277CE, 0x1BB807
  )

  val seeds = (for (i <- 0 until afeParams.mbLanes) yield laneSeeds(i % 8)).toList

  val lfsr = seeds.map(
    seed => Module(new ParallelGaloisLFSR(seed = seed,
                                          lfsrWidth = 23,
                                          dataWidth = afeParams.mbSerializerRatio,
                                          polynomial = polynomial)))
  for (i <- 0 until lfsr.length) {    
    lfsr(i).io.resetLfsr :=  io.resetLfsr(i)
    lfsr(i).io.increment := io.increment(i)
    io.lfsrOutput(i) := lfsr(i).io.lfsrOutput
  }
}

object MainUCIeLFSR extends App {
  ChiselStage.emitSystemVerilogFile(
    new UcieLFSR(new AfeParams),
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