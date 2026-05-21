package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import circt.stage.ChiselStage

object MainRDIWakeHandshakeResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIWakeHandshakeResponder(),
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

object MainRDIClockHandshakeRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIClockHandshakeRequester(),
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

object MainRDIController extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIController(new SidebandParams()),
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

object MainRDIStallRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStallRequester(),
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

object MainRDIStateMachine extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStateMachine(new SidebandParams()),
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

object MainRDIStateMachineRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStateMachineRequester(new SidebandParams()),
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

object MainRDIStateMachineResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStateMachineResponder(new SidebandParams()),
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
