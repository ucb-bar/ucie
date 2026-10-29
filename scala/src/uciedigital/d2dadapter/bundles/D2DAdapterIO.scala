/*
  The IO of the D2D Adapter. It has FDI toward the Protocol Layer, RDI toward
  the Logical PHY, the mainband clocks and the register block port.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import edu.berkeley.cs.uciedigital.rdi._
import chisel3._
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._

class D2DAdapterIO(val fdiParams: FdiParams, val rdiParams: RdiParams)
    extends Bundle {
  val clkRst = new MainbandClkRstIO
  val fdi = Flipped(new Fdi(fdiParams))
  val rdi = Flipped(new Rdi(rdiParams))
  val regs = new AdapterRegIO()
}
