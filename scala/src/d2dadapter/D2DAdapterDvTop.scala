package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

class D2DAdapterDvTop(dataBytes: Int, sidebandWidth: Int) extends Module {
  val fdiParams = FdiParams(width = dataBytes, sbWidth = sidebandWidth)
  val rdiParams = RdiParams(nBytes = dataBytes, ncWidth = sidebandWidth)
  val sbParams = SidebandParams()

  val io = IO(new D2DAdapterIO(fdiParams, rdiParams))

  val dut = Module(new D2DAdapter(fdiParams, rdiParams, sbParams))
  dut.io <> io
}

object D2DAdapterDvElaborate extends App {
  private def argValue(name: String, default: String): String = {
    args
      .sliding(2)
      .collectFirst { case Array(flag, value) if flag == name => value }
      .getOrElse(default)
  }

  val targetDir = argValue("--target-dir", "build/d2d-dv/generated")
  val dataBytes = argValue("--data-bytes", "32").toInt
  val sidebandWidth = argValue("--sideband-width", "32").toInt

  ChiselStage.emitSystemVerilogFile(
    new D2DAdapterDvTop(dataBytes, sidebandWidth),
    args = Array(
      "--target-dir",
      targetDir
    ),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}
