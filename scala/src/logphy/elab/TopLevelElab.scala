package edu.berkeley.cs.uciedigital.logphy

import circt.stage.ChiselStage

object MainLogicalPhy extends App {
  ChiselStage.emitSystemVerilogFile(
    new LogicalPhy(),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}
