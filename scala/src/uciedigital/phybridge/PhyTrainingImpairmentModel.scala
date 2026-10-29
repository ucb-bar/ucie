/*
  A test-only eye window, because the Chisel PHY model passes every code.
 */
package edu.berkeley.cs.uciedigital.phybridge

import chisel3._
import edu.berkeley.cs.uciedigital.phy.{Phy, RxIO, TxIO}
import edu.berkeley.cs.uciedigital.common._

// The window, in code steps, inside which the link is clean.
case class PhyTrainingImpairmentWindow(
    phaseMin: Int,
    phaseMax: Int,
    vrefMin: Int,
    vrefMax: Int
) {
  require(phaseMin >= 0 && phaseMin <= phaseMax)
  require(vrefMin >= 0 && vrefMin <= vrefMax)
}

class PhyTrainingPassWindow(p: PhyBridgeParams) extends Bundle {
  val phaseMin = UInt(p.maxCodeWidth.W)
  val phaseMax = UInt(p.maxCodeWidth.W)
  val vrefMin = UInt(p.maxCodeWidth.W)
  val vrefMax = UInt(p.maxCodeWidth.W)
}

// The clock phase is one module code, so a bad phase corrupts the whole word.
class PhyTrainingTxImpairmentModel(
    numLanes: Int,
    p: PhyBridgeParams
) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val clean = Input(new TxIO(numLanes))
    val txClkPhase = Input(UInt(p.maxCodeWidth.W))
    val window = Input(new PhyTrainingPassWindow(p))
    val impaired = Output(new TxIO(numLanes))
  })

  val errorMask = "h55555555".U(Phy.SerdesRatio.W)
  val pass = io.txClkPhase >= io.window.phaseMin &&
    io.txClkPhase <= io.window.phaseMax
  val corrupt = io.enable && !pass

  for (lane <- 0 until numLanes) {
    io.impaired.data(lane) := Mux(
      corrupt,
      io.clean.data(lane) ^ errorMask,
      io.clean.data(lane)
    )
  }
  io.impaired.valid := Mux(corrupt, io.clean.valid ^ errorMask, io.clean.valid)
  io.impaired.clkp := io.clean.clkp
  io.impaired.clkn := io.clean.clkn
  io.impaired.track := io.clean.track
}

// Vref is per lane, so each lane fails alone and a sweep centers per lane.
class PhyTrainingRxImpairmentModel(
    numLanes: Int,
    p: PhyBridgeParams
) extends Module {
  val slots = PhySlots(numLanes)

  val io = IO(new Bundle {
    val enable = Input(Bool())
    val clean = Input(new RxIO(numLanes))
    val rxVref = Input(Vec(slots.count, UInt(p.maxCodeWidth.W)))
    val window = Input(new PhyTrainingPassWindow(p))
    val impaired = Output(new RxIO(numLanes))
  })

  val errorMask = "haaaaaaaa".U(Phy.SerdesRatio.W)
  def pass(slot: Int): Bool =
    io.rxVref(slot) >= io.window.vrefMin && io.rxVref(slot) <= io.window.vrefMax

  for (lane <- 0 until numLanes) {
    io.impaired.data(lane) := Mux(
      io.enable && !pass(lane),
      io.clean.data(lane) ^ errorMask,
      io.clean.data(lane)
    )
  }

  // Repair is Advanced Package only, so Valid stays on its own lane.
  io.impaired.valid := Mux(
    io.enable && !pass(slots.valid),
    io.clean.valid ^ errorMask,
    io.clean.valid
  )
  io.impaired.track := io.clean.track
}
