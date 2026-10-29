/*
  Elaboration entry point for the D2D Adapter.
 */

package edu.berkeley.cs.uciedigital.elaboration

import edu.berkeley.cs.uciedigital.rdi._
import circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.d2dadapter._
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.sideband._

object MainD2DAdapter extends App {
  ChiselStage.emitSystemVerilogFile(
    new D2DAdapter(
      new FdiParams(64, 32),
      RdiParams(64, 32),
      new SidebandParams()
    ),
    args = Array("-td", "./generatedVerilog/d2dadapter"),
    firtoolOpts = Settings.production
  )
}
