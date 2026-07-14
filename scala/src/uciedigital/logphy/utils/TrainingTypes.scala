/*
  Description:
    Shared training-control enums for LogPHY modules.
*/
package edu.berkeley.cs.uciedigital.logphy

import chisel3._

object TrainingTestType extends ChiselEnum {
  val PointTest, EyeWidthSweep, Either = Value
}
