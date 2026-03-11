package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import org.scalatest.funspec.AnyFunSpec
import _root_.circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.Utils

class PhySpec extends AnyFunSpec {
  describe("Phy") {
    it("should generate valid SystemVerilog") {
      ChiselStage.emitSystemVerilogFile(
        new Phy,
        args = Array(
          "--target-dir",
          (Utils.buildRoot / "Phy_should_generate_valid_SystemVerilog").toString
        )
      )
    }
  }
}
