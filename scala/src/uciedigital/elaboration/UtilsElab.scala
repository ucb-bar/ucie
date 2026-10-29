/*
  Elaboration entry points for the shared utility modules.
 */

package edu.berkeley.cs.uciedigital.elaboration

import circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.utils._
import edu.berkeley.cs.uciedigital.logphy.{AfeParams, UcieLFSR}

object MainSkidBuffer extends App {
  ChiselStage.emitSystemVerilogFile(
    new SkidBuffer(128),
    args = Array("-td", "./generatedVerilog/utils/"),
    firtoolOpts = Settings.stripped
  )
}

object MainParallelGaloisLFSR extends App {
  ChiselStage.emitSystemVerilogFile(
    new ParallelGaloisLFSR(
      UcieLFSR.seedFor(0),
      UcieLFSR.lfsrWidth,
      new AfeParams().mbSerializerRatio,
      UcieLFSR.polynomial
    ),
    args = Array("-td", "./generatedVerilog/utils/"),
    firtoolOpts = Settings.withSourceLocations
  )
}
