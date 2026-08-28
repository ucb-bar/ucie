package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import circt.stage.ChiselStage

object MainLogicalPhy extends App {
  ChiselStage.emitSystemVerilogFile(
    new LogicalPhy(),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

object MainMmpl extends App {
  ChiselStage.emitSystemVerilogFile(
    new Mmpl(MmplParams(numModules = 2), RdiParams(128, 32)),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

object MainMultiModulePhy extends App {
  ChiselStage.emitSystemVerilogFile(
    new MultiModulePhy(
      params = MmplParams(numModules = 2),
      rdiParams = RdiParams(128, 32)
    ),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}
