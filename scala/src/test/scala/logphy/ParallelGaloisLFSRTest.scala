package edu.berkeley.cs.uciedigital.logphy

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


class ParallelGaloisLFSRTest extends AnyFunSpec with ChiselScalatestTester{

  class referenceLFSR(initSeed: Int, poly: Int, width: Int) {
    /*
      The polynomial arguments corresponds to the taps.
        !!! Forgo the leading term bit in polynomial !!!
        Ex: If polynomial is G(X) = X^3 + X^2 + 1 
            Polynomial in binary: 1101 (X^3, X^2, X, 1)
            The value used for polynomial will be: 0x5, NOT 0xD
    */
    private val polynomial: Int = poly  
    private var state: Int = initSeed
    private var seed: Int = initSeed
    private val mask: Int = (1 << width) - 1

    def getState(): Int = state
    def getStateHex(): String = f"${state}%X"
    def getStateBitStr(): String = state.toBinaryString.reverse.padTo(width, '0').reverse

    def reset(): Unit = {
      state = seed
    }

    def reseed(newSeed: Int): Unit = {
      state = newSeed
      seed = newSeed    
    }

    def getMsb(): Int = {
      (state >> (width - 1)) & 1
    }

    def increment(): Int = {
      // shifts out of msb
      val msb = (state >> (width - 1)) & 1

      state = (state << 1) & mask
      if(msb == 1) {
        state = state ^ polynomial
      }
      msb
    }

    def doCycleOutput(numCycles: Int): (Int, BigInt) = {
      var bitStr = ""
      for(i <- 0 until numCycles) {
        val msb = increment()
        bitStr += msb.toBinaryString
      }
      (state, BigInt(bitStr.reverse, 2))
    }
  }

  val seed = 0x1DBFBC
  val lfsrWidth = 23
  val dataWidth = 32
  val polynomial = 0x210125
  
  describe("Parallel Galois LFSR Instantiation Test") {
    it("Instantiated ParallelGaloisLFSR") {
      test(new ParallelGaloisLFSR(seed, lfsrWidth, dataWidth, polynomial)) { c =>
        c.clock.step()
        println("[TEST] Success")
      }
    }
  }

  describe("Parallel Galois LFSR Sanity Check Test") {
    it("Parallel Galois LFSR works") {
      test(new ParallelGaloisLFSR(seed, lfsrWidth, dataWidth, polynomial)) { c =>

        val refLfsr = new referenceLFSR(seed, polynomial, lfsrWidth)
        println("[TEST] State at initialization")
        println("[TEST] Reference State: 0x" + refLfsr.getStateHex())   
        println("[TEST] DUT State:       0x" + c.io.state.peek().litValue.toString(16).toUpperCase) 
        c.io.state.expect(refLfsr.getState())

        val numIncrements = 5
        
        for(i <- 0 until numIncrements) {
          println(s"[TEST] ======== Step ${i + 1} ========")
          var refBitStr = ""
          for(i <- 0 until dataWidth) {
            val msb = refLfsr.increment()
            refBitStr = msb.toString + refBitStr
          }         
          val dutOutputBitStr = c.io.lfsrOutput.peek().litValue
                                                      .toString(2)
                                                      .reverse
                                                      .padTo(dataWidth, '0')
                                                      .reverse

          println(s"[TEST] Reference output bit string: ${refBitStr}")
          println(s"[TEST] DUT output bit string:       ${dutOutputBitStr}")
          assert(refBitStr == dutOutputBitStr, 
                 "DUT output doesn't match the reference output")

          c.io.increment.poke(true.B)    
          c.clock.step()
          c.io.increment.poke(false.B)
          
          val dutStateHexStr = c.io.state.peek().litValue.toString(16).toUpperCase
            println(s"[TEST] Reference state after incrementing: 0x${refLfsr.getStateHex()}")
            println(s"[TEST] DUT state after incrementing:       0x${dutStateHexStr}")
            
            assert(refLfsr.getStateHex() == dutStateHexStr, 
                  "DUT state doesn't match the reference state")
        }
        println("[TEST] Success")
      }
    }
  }

  describe("Parallel Galois LFSR Intermediate Reset Test") {
    it("Parallel Galois LFSR reset signal works") {
      test(new ParallelGaloisLFSR(seed, lfsrWidth, dataWidth, polynomial)) { c =>

        val refLfsr = new referenceLFSR(seed, polynomial, lfsrWidth)
        println("[TEST] State at initialization")
        println("[TEST] Reference State: 0x" + refLfsr.getStateHex())   
        println("[TEST] DUT State:       0x" + c.io.state.peek().litValue.toString(16).toUpperCase) 
        c.io.state.expect(refLfsr.getState())


        val numIncrements = 5
        val whenToReset = 4   // 1 based count
        
        for(i <- 0 until numIncrements) {
          
          println(s"[TEST] ======== Step ${i + 1} ========")

          if(i == (whenToReset - 1)){
            println(s"[TEST] RESETTING LFSR")
            c.io.resetLfsr.poke(true.B)    
            c.clock.step()
            c.io.resetLfsr.poke(false.B)

            refLfsr.reset()
            println(s"[TEST] States should be the same after reset")
            val dutStateHexStr = c.io.state.peek().litValue.toString(16).toUpperCase
            println(s"[TEST] Reference state after reset: 0x${refLfsr.getStateHex()}")
            println(s"[TEST] DUT state after reset:       0x${dutStateHexStr}")
            
            assert(refLfsr.getStateHex() == dutStateHexStr, 
                  "DUT state doesn't match the reference state")
          }
          else {
            var refBitStr = ""
            for(i <- 0 until dataWidth) {
              val msb = refLfsr.increment()
              refBitStr = msb.toString + refBitStr
            }
                                  
            val dutOutputBitStr = c.io.lfsrOutput.peek().litValue
                                                        .toString(2)
                                                        .reverse
                                                        .padTo(dataWidth, '0')
                                                        .reverse
            println(s"[TEST] Reference output bit string: ${refBitStr}")
            println(s"[TEST] DUT output bit string:       ${dutOutputBitStr}")

            assert(refBitStr == dutOutputBitStr, 
                  "DUT output doesn't match the reference output")

            c.io.increment.poke(true.B)    
            c.clock.step()
            c.io.increment.poke(false.B)

            val dutStateHexStr = c.io.state.peek().litValue.toString(16).toUpperCase
            println(s"[TEST] Reference state after incrementing: 0x${refLfsr.getStateHex()}")
            println(s"[TEST] DUT state after incrementing:       0x${dutStateHexStr}")
            
            assert(refLfsr.getStateHex() == dutStateHexStr, 
                  "DUT state doesn't match the reference state")
          }              
        }
        println("[TEST] Success")
      }
    }
  }
}