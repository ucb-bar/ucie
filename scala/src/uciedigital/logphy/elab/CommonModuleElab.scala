package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.utils._
import circt.stage.ChiselStage

object MainSidebandMessageExchanger extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandMessageExchanger(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "--lowering-options=disallowLocalVariables",
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}

object MainUCIeLFSR extends App {
  ChiselStage.emitSystemVerilogFile(
    new UcieLFSR(new AfeParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "--lowering-options=disallowLocalVariables",
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}

object MainParallelGaloisLFSR extends App {
  ChiselStage.emitSystemVerilogFile(
    new ParallelGaloisLFSR(0x1dbfbc, 23, 32, 0x210125),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Array(
      "-O=debug",
      "--lowering-options=disallowLocalVariables",
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}

object MainMainbandLaneController extends App {
  ChiselStage.emitSystemVerilogFile(
    new MainbandLaneController(new AfeParams(), RdiParams(64, 32)),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "--lowering-options=disallowLocalVariables",
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}

object MainPatternWriter extends App {
  ChiselStage.emitSystemVerilogFile(
    new PatternWriter(new AfeParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Array(
      "-O=debug",
      "--lowering-options=disallowLocalVariables",
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}

object MainPatternReader extends App {
  ChiselStage.emitSystemVerilogFile(
    new PatternReader(new AfeParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Array(
      "-O=debug",
      "--lowering-options=disallowLocalVariables",
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
