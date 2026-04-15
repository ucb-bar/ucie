package edu.berkeley.cs.uciedigital.utils

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import chisel3.util.random._
import chisel3.ltl._
import chisel3.layer.{Convention, Layer, block}


// // All layers are declared here.  The Assert and Debug layers are nested under
// // the Verification layer.
// object Verification extends Layer(Convention.Bind) {
//   object Assert extends Layer(Convention.Bind)
//   object Debug extends Layer(Convention.Bind)
//   object Cover extends Layer(Convention.Bind)
// }


/*
  Description: 
    Produces `dataWidth` bits worth of LFSR output in one cycle using an XOR tree.
    It will combinationally calculate the output bits based on the current state. When it is
    time to increment, it will step `dataWidth` times in a cycle. The next state is also
    calculated combinationally based on current state.

    Note:
    * This LFSR is based on Galois architecture.
    * The polynomial arguments corresponds to the taps.
        !!! Forgo the leading term bit in polynomial !!!
        Ex: If polynomial is G(X) = X^3 + X^2 + 1 
            Polynomial in binary: 1101 (X^3, X^2, X, 1)
            The value used for polynomial will be: 0x5, NOT 0xD
*/

class ParallelGaloisLFSR(seed: Int, lfsrWidth: Int, dataWidth: Int, polynomial: Int) 
  extends Module {  
  val io = IO(new Bundle {
    val resetLfsr = Input(Bool())
    val increment = Input(Bool())
    val lfsrOutput = Output(UInt(dataWidth.W)) 
    val state = Output(UInt(lfsrWidth.W))    
  })

  assert(seed > 0, "Seed needs to be positive")
  assert(dataWidth > 0, "Dath width needs to be positive")

  // if(!chiselSim) {
  //   block(Verification) {
  //     block(Verification.Assert) {
  //       val impl = Sequence.BoolSequence(io.resetLfsr)
  //       AssertProperty(impl |-> Property.eventually(Sequence.BoolSequence(io.increment === true.B)))
  //     }    
  //     block(Verification.Cover) {
  //       val impl = Sequence.BoolSequence(io.resetLfsr)
  //       CoverProperty(impl |-> Property.eventually(Sequence.BoolSequence(io.increment === true.B)))
  //     }
  //   }
  // }

  // rows: lfsrWidth, cols: lfsrWidth
  var stateUpdateMatrix: Array[Array[Int]] = Array.ofDim[Int](lfsrWidth, lfsrWidth) 
  
  // rows: lfsrWidth, cols: dataWidth
  var dataOutMatrix: Array[Array[Int]] = Array.ofDim[Int](lfsrWidth, dataWidth) 
  
  val serialGalois = new SerialGaloisLFSR(initSeed = seed, poly = polynomial, width = lfsrWidth)

  // construct the matrix
  for(i <- 0 until lfsrWidth) {
    val oneHot = 1 << i
    serialGalois.reseed(oneHot)
    val (state, outputs)= serialGalois.doCycleOutput(dataWidth)

    // populate state update matrix columns
    for(j <- 0 until lfsrWidth) {
      stateUpdateMatrix(i)(j) = if(state(j) == '1') 1 else 0      
    }
  
    // populate data out matrix columns
    for(j <- 0 until dataWidth) {
      dataOutMatrix(i)(j) = if(outputs(j) == '1') 1 else 0      
    }
  }

  val stateReg = Reg(UInt(lfsrWidth.W))

  val stateUpdateWire = WireInit(VecInit(Seq.fill(lfsrWidth)(0.U)))
  val dataOutputWire = WireInit(VecInit(Seq.fill(dataWidth)(0.U)))

  // Compute stateUpdate combinationally based on current lfsr state
  for(j <- 0 until lfsrWidth) {
    val terms = scala.collection.mutable.ArrayBuffer[Bool]()
    for(i <- 0 until lfsrWidth) {
      val doXor = stateUpdateMatrix(i)(j)      
      if(doXor == 1) {
        terms += stateReg(i)
      }    
    }
    stateUpdateWire(lfsrWidth - 1 - j) := terms.reduce(_ ^ _)
  }

  // Compute dataOutput combinationally based on current lfsr state
  for(j <- 0 until dataWidth) {
    val terms = scala.collection.mutable.ArrayBuffer[Bool]()
    for(i <- 0 until lfsrWidth) {
      val doXor = dataOutMatrix(i)(j)      
      if(doXor == 1) {
        terms += stateReg(i)
      }    
    }
    dataOutputWire(dataWidth - 1 - j) := terms.reduce(_ ^ _)
  }

  when(reset.asBool || io.resetLfsr) {
    stateReg := seed.U
  }.otherwise {
    when(io.increment) {
      stateReg := stateUpdateWire.asUInt    
    }
  }

  io.state := stateReg
  io.lfsrOutput := dataOutputWire.asUInt 
}

class SerialGaloisLFSR(initSeed: Int, poly: Int, width: Int) {

  private val polynomial: Int = poly  
  private var state: Int = initSeed
  private var seed: Int = initSeed
  private val mask: Int = (1 << width) - 1

  def getState(): Int = state
  def getStateHex(): String = f"0x${state}%X"
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
    // returns the MSB
    val msb = (state >> (width - 1)) & 1

    state = (state << 1) & mask
    if(msb == 1) {
      state = state ^ polynomial
    }
    msb
  }

  def doCycleOutput(numCycles: Int): (String, String) = {
    var bitStr = ""
    for(i <- 0 until numCycles) {
      val msb = increment()
      bitStr += msb.toBinaryString
    }
    (getStateBitStr(), bitStr.reverse)
  }
}

object MainParallelGaloisLFSR extends App {
  ChiselStage.emitSystemVerilogFile(
    new ParallelGaloisLFSR(0x1DBFBC, 23, 32, 0x210125),
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
