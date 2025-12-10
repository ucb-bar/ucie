package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._

import chisel3._
import chisel3.util._

// Currently using ChiselTest because of ChiselSim issues in Chisel 6
// Ideally want to move to ChiselSim when switched to Chisel 7
import chiseltest._
import firrtl2.options.TargetDirAnnotation

// ChiselSim for Chisel 6
// import chisel3.simulator.EphemeralSimulator._    

// ChiselSim, for Chisel 7.0+
// import chisel3.simulator.scalatest.ChiselSim     
// import chisel3.simulator.stimulus.{RunUntilFinished, RunUntilSuccess, ResetProcedure}

import org.scalatest.funspec.AnyFunSpec


// class LinkTrainingSMTest extends AnyFunSpec with ChiselScalatestTester{

//   describe("LinkTrainingSM Instantiation Test") {
//     it("LinkTrainingSM Instantiated") {
//       test(new LinkTrainingSM(new SidebandParams(), new AfeParams(), retryW = 3))
//       // .withAnnotations(Seq(WriteVcdAnnotation))
//       // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/"))) 
//       { c =>
//         c.clock.step()
//         println("[TEST] Success")
//       }
//     }
//   }    
// }