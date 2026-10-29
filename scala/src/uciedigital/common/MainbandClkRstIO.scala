/*
  The PHY's divided clocks and their resets. RDI and FDI carry transmit data
  on txDivClk and receive data on rxDivClk, so the mainband data path has no
  clock crossing. sec 10.1.2 and sec 10.2.2 require one lclk, so this is a
  KNOWN deviation. Each reset is already synchronised to its clock.
 */

package edu.berkeley.cs.uciedigital.common

import chisel3._

class MainbandClkRstIO extends Bundle {
  val txDivClk = Input(Clock())
  val txDivRst = Input(Bool())
  val rxDivClk = Input(Clock())
  val rxDivRst = Input(Bool())
}
