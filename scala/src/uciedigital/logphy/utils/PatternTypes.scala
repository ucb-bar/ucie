/*
  Description:
    Shared pattern-generation enums for LogPHY training flows.
*/
package edu.berkeley.cs.uciedigital.logphy

import chisel3._

object PatternSelect extends ChiselEnum {
  val CLKREPAIR, VALTRAIN, PERLANEID, LFSR = Value
}
