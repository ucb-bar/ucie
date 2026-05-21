package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import circt.stage.ChiselStage

object MainSidebandMessageExchanger extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandMessageExchanger(new SidebandParams()),
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

object MainUCIeLFSR extends App {
  ChiselStage.emitSystemVerilogFile(
    new UcieLFSR(new AfeParams()),
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

object MainMainbandLaneController extends App {
  ChiselStage.emitSystemVerilogFile(
    new MainbandLaneController(new AfeParams(), RdiParams(64, 32)),
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

object MainPatternWriter extends App {
  ChiselStage.emitSystemVerilogFile(
    new PatternWriter(new AfeParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables",
    ),
  )
}

object MainPatternReader extends App {
  ChiselStage.emitSystemVerilogFile(
    new PatternReader(new AfeParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables",
    ),
  )
}
