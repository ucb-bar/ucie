package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.logphy.ClkRepairStatusIO

/** Stand-in for the PHY's MBINIT.REPAIRCLK measurement, for harnesses that loop
  * two controllers back with no PHY between them.
  *
  * Nothing here samples a forwarded clock, so there is no measurement to make.
  * What a harness owes `ClkRepairDetector` is the handshake: a window that
  * completes some cycles after it is asked for, so REPAIRCLK finishes and
  * MBINIT moves on. A few cycles rather than a constant `done`, so a detector
  * that never raised `repairClkEn` would hang here rather than sail through.
  *
  * Whether the measurement itself is right belongs to `ClkRepairSpec` and to
  * `RepairClkTestDriver`, which put it in front of real analog models.
  */
object ClkRepairStub {

  /** Cycles a stubbed window takes. Short; the sideband exchange around it is
    * thousands of cycles either way.
    */
  val windowCycles = 8

  def apply(status: ClkRepairStatusIO, repairClkEn: Bool): Unit = {
    val count = RegInit(0.U(log2Ceil(windowCycles + 1).W))
    when(!repairClkEn) {
      count := 0.U
    }.elsewhen(count =/= windowCycles.U) {
      count := count + 1.U
    }
    status.done := count === windowCycles.U
    status.laneOk.foreach(_ := true.B)
  }
}
