/*
  Elaboration entry points for the Logical PHY and its modules.
 */

package edu.berkeley.cs.uciedigital.elaboration

import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.rdi.RdiParams
import edu.berkeley.cs.uciedigital.common.UcieTiming
import edu.berkeley.cs.uciedigital.sideband._
import circt.stage.ChiselStage

object MainLogicalPhy extends App {
  ChiselStage.emitSystemVerilogFile(
    new LogicalPhy(),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

// Keeps the synchronisers, which the default build ties into wires.
object MainLogicalPhySeparateDivClocks extends App {
  ChiselStage.emitSystemVerilogFile(
    new LogicalPhy(separateDivClocks = true),
    args = Array("-td", "./generatedVerilog/logphy/separateDivClocks"),
    firtoolOpts = Settings.stripped
  )
}

object MainUCIeLFSR extends App {
  ChiselStage.emitSystemVerilogFile(
    new UcieLFSR(new AfeParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.withSourceLocations
  )
}

// Each half runs on its own divided clock, so each is emitted alone for timing.
object MainMainbandTxDomain extends App {
  ChiselStage.emitSystemVerilogFile(
    new MainbandTxDomain(new AfeParams(), RdiParams(64, 32)),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.withSourceLocations
  )
}

object MainMainbandRxDomain extends App {
  ChiselStage.emitSystemVerilogFile(
    new MainbandRxDomain(new AfeParams(), RdiParams(64, 32)),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.withSourceLocations
  )
}

object MainPatternWriter extends App {
  ChiselStage.emitSystemVerilogFile(
    new PatternWriter(new AfeParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.withSourceLocations
  )
}

object MainPatternReader extends App {
  ChiselStage.emitSystemVerilogFile(
    new PatternReader(new AfeParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.withSourceLocations
  )
}

object MainTrainErrorRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new TrainErrorRequester(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainTrainErrorResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new TrainErrorResponder(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainPhyRetrainSidebandHandshake extends App {
  ChiselStage.emitSystemVerilogFile(
    new PhyRetrainSidebandHandshake(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainPhyRetrainRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new PhyRetrainRequester(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainPhyRetrainResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new PhyRetrainResponder(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainTxD2CPointTestRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CPointTestRequester(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainTxD2CPointTestResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CPointTestResponder(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainRxD2CPointTestRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CPointTestRequester(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainRxD2CPointTestResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CPointTestResponder(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainTxD2CEyeWidthSweepRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CEyeWidthSweepRequester(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainTxD2CEyeWidthSweepResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CEyeWidthSweepResponder(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainRxD2CEyeWidthSweepRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CEyeWidthSweepRequester(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainRxD2CEyeWidthSweepResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CEyeWidthSweepResponder(new AfeParams, new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainMBInitSM extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBInitSM(new AfeParams(), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainMBInitRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBInitRequester(new AfeParams(), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainMBInitResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBInitResponder(new AfeParams(), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainLinkTrainingSM extends App {
  ChiselStage.emitSystemVerilogFile(
    new LinkTrainingSM(new SidebandParams(), new AfeParams(), retryW = 10),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainSBInit extends App {
  ChiselStage.emitSystemVerilogFile(
    new SBInitSM(new SidebandParams, UcieTiming.trainingTimeoutCycles),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainSBInitRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new SBInitRequester(new SidebandParams, UcieTiming.trainingTimeoutCycles),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainSBInitResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new SBInitResponder(new SidebandParams),
    args = Array("-td", "./generatedVerilog/logphy/"),
    firtoolOpts = Settings.stripped
  )
}

object MainMBTrainSM extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBTrainSM(new AfeParams(), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainMBTrainRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBTrainRequester(new AfeParams(), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}

object MainMBTrainResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBTrainResponder(new AfeParams(), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Settings.stripped
  )
}
