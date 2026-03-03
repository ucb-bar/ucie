package edu.berkeley.cs.uciedigital.tilelink

import org.scalatest.funspec.AnyFunSpec

class CodegenSpec extends AnyFunSpec {
  describe("Codegen") {
    it("should format to SystemVerilog") {
      val codegen = new Codegen(new SystemVerilogFormatter)
      println(codegen.formatAll())
    }
  }
}
