/*
  Stands in for an absent analog PHY. The PLL reads locked, the clocks ungated.
 */

package edu.berkeley.cs.uciedigital.phybridge

import chisel3._
import edu.berkeley.cs.uciedigital.logphy.PhyBridgeAnalogIO

object PhyBridgeTieOff {
  def apply(analog: PhyBridgeAnalogIO): Unit = {
    // Note: The final connection depends on the implementation.
    analog.pllLock := true.B
    analog.clocksUngatedAndStable := true.B
  }
}
