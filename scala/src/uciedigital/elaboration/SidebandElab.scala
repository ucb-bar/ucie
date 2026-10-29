/*
  Elaboration entry points for the sideband modules.
 */

package edu.berkeley.cs.uciedigital.elaboration

import circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.sideband._

object MainD2DSidebandChannel extends App {
  ChiselStage.emitSystemVerilogFile(
    new D2DSidebandChannel(
      sbMsgWidth = 128,
      rdiNcWidth = 32,
      fdiNcWidth = 32,
      numCredits = 32,
      queueDepths = SidebandPriorityQueueDepths()
    ),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.stripped
  )
}

object MainLogPhySidebandChannel extends App {
  ChiselStage.emitSystemVerilogFile(
    new LogPhySidebandChannel(
      sbMsgWidth = 128,
      sbLinkWidth = 1,
      rdiNcWidth = 32,
      numCredits = 32,
      desTimeoutCycles = 512,
      queueDepths = SidebandPriorityQueueDepths()
    ),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.stripped
  )
}

object MainProtocolSidebandChannel extends App {
  ChiselStage.emitSystemVerilogFile(
    new ProtocolSidebandChannel(
      sbMsgWidth = 128,
      fdiNcWidth = 32,
      numCredits = 32,
      queueDepths = SidebandPriorityQueueDepths()
    ),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.stripped
  )
}

object MainSBInterfaceNode extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandInterfaceNode(128, 32, 32, SidebandPriorityQueueDepths()),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.stripped
  )
}

object MainSBIntfSer extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandInterfaceSerializer(128, 32),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.withSourceLocations
  )
}

object MainSBIntfDes extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandInterfaceDeserializer(128, 32),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.withSourceLocations
  )
}

object MainSBLinkNode extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandLinkNode(128, 1, 512, SidebandPriorityQueueDepths()),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.stripped
  )
}

object MainSBSerdes extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandLinkSerializer(1, 128),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.withSourceLocations
  )

  ChiselStage.emitSystemVerilogFile(
    new SidebandLinkDeserializer(1, 128, 512),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.withSourceLocations
  )
}

object MainSBPriorityQueue extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandPriorityQueue(128, SidebandPriorityQueueDepths()),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.withSourceLocations
  )
}

object MainSBSwitch extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandSwitch(
      layerId = 2,
      upperIds = Seq(0, 1),
      lowerIds = Seq(),
      sbMsgWidth = 128
    ),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.withSourceLocations
  )
}

object MainSidebandMessageExchanger extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandMessageExchanger(new SidebandParams()),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Settings.withSourceLocations
  )
}
