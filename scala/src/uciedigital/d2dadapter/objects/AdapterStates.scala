/*
  The link init sub-states of Stage 3, and the kind of link management
  request that the Adapter LSM has outstanding.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._

object LinkInitState extends ChiselEnum {
  val INIT_START = Value(0x0.U(3.W))
  val RDI_BRINGUP = Value(0x1.U(3.W))
  val PARAM_EXCH = Value(0x2.U(3.W))
  val FDI_BRINGUP = Value(0x3.U(3.W))
  val INIT_DONE = Value(0x4.U(3.W))
}

// sec 7.1.3.3 permits only one outstanding request per Link Management
// MsgCode. MsgCode 03h covers all of these, so only one can be in flight.
object AdapterLSMRequest extends ChiselEnum {
  val none, active, linkReset, disable, l1, l2 = Value
}
