package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._

import org.scalatest.funspec.AnyFunSpec
import edu.berkeley.cs.chippy.ChippyStage
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters

class TestHarnessSpec extends AnyFunSpec {
  describe("TestHarness") {
    it("should generate valid System Verilog") {
      implicit val p = Parameters.empty
      ChippyStage.emitSystemVerilogFile(
        LazyModule(new TestHarness()).module,
        args = Array("--target-dir", "build"),
        firtoolOpts = Array("-o", "build")
      )
    }
  }
}
