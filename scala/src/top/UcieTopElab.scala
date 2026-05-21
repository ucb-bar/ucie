package edu.berkeley.cs.uciedigital.top

import circt.stage.ChiselStage

object UcieTopElab {
  private val commonFirtoolOpts = Array(
    "--disable-all-randomization"
  )

  private val commonLoweringOpts = Seq(
    "disallowLocalVariables"
  )

  private val debugLoweringOpts = Seq(
    "printDebugInfo",
    "locationInfoStyle=wrapInAtSquareBracket",
    "emittedLineLength=120",
    "disallowMuxInlining"
  )

  private val releaseLoweringOpts = Seq(
    "emittedLineLength=120"
  )

  private def loweringOptions(opts: Seq[String]): String =
    s"--lowering-options=${opts.mkString(",")}"

  def emit(targetDir: String, debug: Boolean): Unit = {
    val firtoolOpts =
      if (debug) {
        Array("-O=debug", 
              "-g",
              loweringOptions(commonLoweringOpts ++ debugLoweringOpts)) ++ commonFirtoolOpts
      } else {
        Array("-O=release",
              loweringOptions(commonLoweringOpts ++ releaseLoweringOpts)) ++ commonFirtoolOpts
      }

    ChiselStage.emitSystemVerilogFile(
      new UcieTop(),
      args = Array("-td", targetDir),
      firtoolOpts = firtoolOpts,
    )
  }
}

object MainUcieTopDebug extends App {
  UcieTopElab.emit("./generatedVerilog/top-debug", debug = true)
}

object MainUcieTop extends App {
  UcieTopElab.emit("./generatedVerilog/top", debug = false)
}
