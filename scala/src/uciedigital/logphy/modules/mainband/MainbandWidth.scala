/*
  The Table 4-9 Lane width code of each mainband direction.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._

// One valid per direction, because step 3 of sec 4.5.3.3.6 may change one.
class MainbandWidthWrite extends Bundle {
  val txValid = Bool()
  val txCode = UInt(3.W)
  val rxValid = Bool()
  val rxCode = UInt(3.W)
}

class MainbandWidth extends Module {
  val io = IO(new Bundle {
    val resetWidth = Input(Bool())
    val initCode = Input(UInt(3.W))
    val hw = Input(new MainbandWidthWrite)
    val txCode = Output(UInt(3.W))
    val rxCode = Output(UInt(3.W))
  })

  // RESET loads initCode, because Target Link Width (sec 9.5.1.5) can change.
  val fullWidth = "b011".U(3.W)

  val txCode = RegInit(fullWidth)
  val rxCode = RegInit(fullWidth)

  // Note: a sec 9.5.3.36 software degrade arrives through REPAIR.
  when(io.resetWidth) {
    txCode := io.initCode
    rxCode := io.initCode
  }.otherwise {
    when(io.hw.txValid) { txCode := io.hw.txCode }
    when(io.hw.rxValid) { rxCode := io.hw.rxCode }
  }

  io.txCode := txCode
  io.rxCode := rxCode
}

// One valid, because the apply degrade resp follows both (sec 4.5.3.3.6).
class MainbandWidthApply extends Bundle {
  val valid = Bool()
  val txCode = UInt(3.W)
  val rxCode = UInt(3.W)
}
