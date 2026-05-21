package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import circt.stage.ChiselStage

object MainTrainErrorRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new TrainErrorRequester(new SidebandParams()),
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

object MainTrainErrorResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new TrainErrorResponder(new SidebandParams()),
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

object MainPhyRetrainSidebandHandshake extends App {
  ChiselStage.emitSystemVerilogFile(
    new PhyRetrainSidebandHandshake(new SidebandParams()),
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

object MainPhyRetrainRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new PhyRetrainRequester(new SidebandParams()),
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

object MainPhyRetrainResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new PhyRetrainResponder(new SidebandParams()),
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

object MainTxD2CPointTestRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CPointTestRequester(new AfeParams, new SidebandParams),
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

object MainTxD2CPointTestResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CPointTestResponder(new AfeParams, new SidebandParams),
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

object MainRxD2CPointTestRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CPointTestRequester(new AfeParams, new SidebandParams),
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

object MainRxD2CPointTestResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CPointTestResponder(new AfeParams, new SidebandParams),
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

object MainTxD2CEyeWidthSweepRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CEyeWidthSweepRequester(new AfeParams, new SidebandParams),
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

object MainTxD2CEyeWidthSweepResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new TxD2CEyeWidthSweepResponder(new AfeParams, new SidebandParams),
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

object MainRxD2CEyeWidthSweepRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CEyeWidthSweepRequester(new AfeParams, new SidebandParams),
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

object MainRxD2CEyeWidthSweepResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new RxD2CEyeWidthSweepResponder(new AfeParams, new SidebandParams),
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

object MainMBInitSM extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBInitSM(new AfeParams(), new SidebandParams()),
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

object MainMBInitRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBInitRequester(new AfeParams(), new SidebandParams()),
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

object MainMBInitResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBInitResponder(new AfeParams(), new SidebandParams()),
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

object MainLinkTrainingSM extends App {
  ChiselStage.emitSystemVerilogFile(
    new LinkTrainingSM(new SidebandParams(), new AfeParams(), retryW = 10),
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

object MainSBInit extends App {
  ChiselStage.emitSystemVerilogFile(
    new SBInitSM(new SidebandParams, 8000000),
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

object MainSBInitRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new SBInitRequester(new SidebandParams, 8000000),
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

object MainSBInitResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new SBInitResponder(new SidebandParams),
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

object MainMBTrainSM extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBTrainSM(new AfeParams(), new SidebandParams()),
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

object MainMBTrainRequester extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBTrainRequester(new AfeParams(), new SidebandParams()),
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

object MainMBTrainResponder extends App {
  ChiselStage.emitSystemVerilogFile(
    new MBTrainResponder(new AfeParams(), new SidebandParams()),
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
