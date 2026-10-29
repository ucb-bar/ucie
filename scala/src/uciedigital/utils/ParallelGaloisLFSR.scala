/*
  A Galois LFSR that gives `dataWidth` output bits per cycle with an XOR tree.
  The polynomial omits the leading term, so pass X^3 + X^2 + 1 as 0x5.
 */

package edu.berkeley.cs.uciedigital.utils

import chisel3._

class ParallelGaloisLFSR(
    seed: Int,
    lfsrWidth: Int,
    dataWidth: Int,
    polynomial: Int
) extends Module {
  val io = IO(new Bundle {
    val resetLfsr = Input(Bool())
    val increment = Input(Bool())
    val lfsrOutput = Output(UInt(dataWidth.W))
    val state = Output(UInt(lfsrWidth.W))
  })

  require(seed > 0, "Seed needs to be positive")
  require(dataWidth > 0, "Dath width needs to be positive")

  // ===========================================================================
  // Matrices from the serial model
  // ===========================================================================
  var stateUpdateMatrix: Array[Array[Int]] =
    Array.ofDim[Int](lfsrWidth, lfsrWidth)

  var dataOutMatrix: Array[Array[Int]] = Array.ofDim[Int](lfsrWidth, dataWidth)

  val serialGalois =
    new SerialGaloisLFSR(initSeed = seed, poly = polynomial, width = lfsrWidth)

  // Step the serial model from each one-hot state to fill both matrices.
  for (i <- 0 until lfsrWidth) {
    val oneHot = 1 << i
    serialGalois.reseed(oneHot)
    val (state, outputs) = serialGalois.doCycleOutput(dataWidth)

    for (j <- 0 until lfsrWidth) {
      stateUpdateMatrix(i)(j) = if (state(j) == '1') 1 else 0
    }

    for (j <- 0 until dataWidth) {
      dataOutMatrix(i)(j) = if (outputs(j) == '1') 1 else 0
    }
  }

  // ===========================================================================
  // State and outputs
  // ===========================================================================
  // The seed is the reset value, so an asynchronous reset loads it with no
  // clock edge. sec 4.5.3.5 needs that while rxDivClk is parked.
  val stateReg = RegInit(seed.U(lfsrWidth.W))

  val stateUpdateWire = WireInit(VecInit(Seq.fill(lfsrWidth)(0.U)))
  val dataOutputWire = WireInit(VecInit(Seq.fill(dataWidth)(0.U)))

  for (j <- 0 until lfsrWidth) {
    val terms = scala.collection.mutable.ArrayBuffer[Bool]()
    for (i <- 0 until lfsrWidth) {
      val doXor = stateUpdateMatrix(i)(j)
      if (doXor == 1) {
        terms += stateReg(i)
      }
    }
    stateUpdateWire(lfsrWidth - 1 - j) := terms.reduce(_ ^ _)
  }

  for (j <- 0 until dataWidth) {
    val terms = scala.collection.mutable.ArrayBuffer[Bool]()
    for (i <- 0 until lfsrWidth) {
      val doXor = dataOutMatrix(i)(j)
      if (doXor == 1) {
        terms += stateReg(i)
      }
    }
    dataOutputWire(dataWidth - 1 - j) := terms.reduce(_ ^ _)
  }

  when(io.resetLfsr) {
    stateReg := seed.U
  }.elsewhen(io.increment) {
    stateReg := stateUpdateWire.asUInt
  }

  io.state := stateReg
  io.lfsrOutput := dataOutputWire.asUInt
}

class SerialGaloisLFSR(initSeed: Int, poly: Int, width: Int) {
  val polynomial: Int = poly
  var state: Int = initSeed
  val mask: Int = (1 << width) - 1

  def getStateBitStr(): String =
    state.toBinaryString.reverse.padTo(width, '0').reverse

  def reseed(newSeed: Int): Unit = {
    state = newSeed
  }

  def increment(): Int = {
    val msb = (state >> (width - 1)) & 1

    state = (state << 1) & mask
    if (msb == 1) {
      state = state ^ polynomial
    }
    msb
  }

  def doCycleOutput(numCycles: Int): (String, String) = {
    var bitStr = ""
    for (i <- 0 until numCycles) {
      val msb = increment()
      bitStr += msb.toBinaryString
    }
    (getStateBitStr(), bitStr.reverse)
  }
}
