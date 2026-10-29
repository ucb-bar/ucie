/*
  Elaboration entry point for the Protocol Layer.
 */

package edu.berkeley.cs.uciedigital.elaboration

import circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.protocollayer.ProtocolLayer

object MainProtocolLayer extends App {
  ChiselStage.emitSystemVerilogFile(
    new ProtocolLayer(),
    args = Array("-td", "./generatedVerilog/protocol"),
    firtoolOpts = Settings.production
  )
}
