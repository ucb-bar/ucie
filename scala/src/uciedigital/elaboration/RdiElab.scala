/*
  Elaboration entry points for the RDI modules.
 */

package edu.berkeley.cs.uciedigital.elaboration

import edu.berkeley.cs.uciedigital.rdi._
import edu.berkeley.cs.uciedigital.logphy._

import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.sideband._
import circt.stage.ChiselStage

// One handshake module, emitted once per role.
object MainReqAckResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new ReqAckResponder(),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainReqAckRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new ReqAckRequester(
      Some(new SidebandParams().clockHandshakeTimeoutCycles)
    ),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainRDIController extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIController(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainStallRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new StallRequester(new SidebandParams().stallTimeoutCycles),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainRDIStateMachine extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStateMachine(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainRDIStateMachineRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStateMachineRequester(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainRDIStateMachineResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RDIStateMachineResponder(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}
