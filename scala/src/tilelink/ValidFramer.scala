package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.phy.RxIO
import edu.berkeley.cs.uciedigital.phy.Phy

class ValidFramerIO(numLanes: Int = 16) extends Bundle {
  // UCIE DIGITAL INTERFACE
  val digital = Decoupled(Vec(numLanes, Bits(Phy.SerdesRatio.W)))

  // PHY INTERFACE
  // ====================
  val phy = Flipped(DecoupledIO(new RxIO(numLanes)))
}

class ValidFramer(
    numLanes: Int = 16
) extends Module {
  val io = IO(new ValidFramerIO(numLanes))

  // RX logic
  io.phy.ready := true.B
  io.digital.valid := false.B
  io.digital.bits := 0.U.asTypeOf(io.digital.bits)

  val serializerLenBits = log2Ceil(Phy.SerdesRatio)

  val hasOne = Wire(Bool())
  val firstOne = Wire(UInt(serializerLenBits.W))
  hasOne := false.B
  firstOne := 0.U

  // afeParams.mbLanes data lanes, 1 valid lane.
  val runningData = RegInit(
    VecInit(Seq.fill(numLanes + 1)(0.U(Phy.SerdesRatio.W)))
  )

  // Check valid streak after each packet is dequeued.
  when(io.phy.ready & io.phy.valid) {
    // Store latest data at the beginning of the `runningData` register.
    val nextData = Wire(
      Vec(numLanes + 1, UInt((2 * Phy.SerdesRatio).W))
    )
    for (lane <- 0 until numLanes + 1) {
      if (lane < numLanes) {
        nextData(lane) := Cat(io.phy.bits.data(lane), runningData(lane))
      } else {
        nextData(lane) := Cat(io.phy.bits.valid, runningData(lane))
      }
      runningData(lane) := nextData(lane)(
        2 * Phy.SerdesRatio - 1,
        Phy.SerdesRatio
      )
    }

    // Find first one.
    hasOne := nextData(numLanes)(
      Phy.SerdesRatio - 1,
      0
    ).orR
    for (i <- Phy.SerdesRatio - 1 to 0 by -1) {
      when(nextData(numLanes)(i)) {
        firstOne := i.U
      }
    }

    when(hasOne) {
      io.digital.valid := true.B
      for (lane <- 0 until numLanes) {
        io.digital.bits(lane) := (nextData(lane) >> firstOne)(
          Phy.SerdesRatio - 1,
          0
        )
      }
      runningData(
        numLanes
      ) := (io.phy.bits.valid >> firstOne) << firstOne
    }
  }
}
