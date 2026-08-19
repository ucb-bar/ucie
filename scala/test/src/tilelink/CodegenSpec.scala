package edu.berkeley.cs.uciedigital.tilelink

import org.scalatest.funspec.AnyFunSpec

class CodegenSpec extends AnyFunSpec {
  describe("SystemVerilogFormatter") {
    val f = new SystemVerilogFormatter
    it("should format long values") {
      assert(f.formatLong(0xdeadbeefL) == "64'hDEADBEEF")
      assert(f.formatLong(0L) == "64'h0")
    }
    it("should format boolean values") {
      assert(f.formatBool(true) == "1'b1")
      assert(f.formatBool(false) == "1'b0")
    }
    it("should format constant references") {
      assert(f.formatConstantRef("txFsmRst") == "`TX_FSM_RST")
      assert(f.formatConstantRef("rxPacketsReceived") == "`RX_PACKETS_RECEIVED")
    }
    it("should format defines") {
      assert(f.formatDefine("myConst", "42") == "`define MY_CONST 42\n")
    }
    it("should format function calls") {
      assert(f.formatFnCall("foo") == "foo();\n")
      assert(f.formatFnCall("foo", Seq("a", "b")) == "foo(a, b);\n")
    }
    it("should format functions with args") {
      val result = f.formatFn("myTask", "body;\n", Seq(Arg("x", Datatype.Long)))
      assert(result.startsWith("task myTask(input [63:0] x)"))
      assert(result.contains("endtask"))
    }
    it("should format write and read macros") {
      assert(f.formatWrite("drv", "0x0", "0x1") == "`WRITE(drv, 0x0, 0x1);\n")
      assert(f.formatWriteReg("drv", "`ADDR", "64'h1") == "`WRITE_UCIE(drv, `ADDR, 64'h1);\n")
      assert(f.formatRead("drv", "result", "`ADDR") == "reg [63:0] result;\n`READ(drv, `ADDR, result);\n")
    }
    it("should format assert macros") {
      assert(f.formatAssertEq("drv", "`ADDR", "64'h1") == "`EXPECT(drv, `ADDR, 64'h1);\n")
      assert(f.formatAssertEq("drv", "`ADDR", "64'h1", Some("bad")) ==
        "`EXPECT_MSG(drv, `ADDR, 64'h1, \"bad\");\n")
      assert(f.formatUcieAssertEq("drv", "`ADDR", "64'h1") == "`EXPECT_UCIE(drv, `ADDR, 64'h1);\n")
    }
  }

  describe("CFormatter") {
    val f = new CFormatter
    it("should format long values") {
      assert(f.formatLong(0xdeadbeefL) == "0xdeadbeefULL")
      assert(f.formatLong(0L) == "0x0ULL")
    }
    it("should format boolean values") {
      assert(f.formatBool(true) == "1")
      assert(f.formatBool(false) == "0")
    }
    it("should format constant references") {
      assert(f.formatConstantRef("txFsmRst") == "UCIE_TX_FSM_RST")
      assert(f.formatConstantRef("rxPacketsReceived") == "UCIE_RX_PACKETS_RECEIVED")
    }
    it("should format defines") {
      assert(f.formatDefine("myConst", "42") == "#define UCIE_MY_CONST 42\n")
    }
    it("should format function calls") {
      assert(f.formatFnCall("foo") == "foo(base);\n")
      assert(f.formatFnCall("foo", Seq("a", "b")) == "foo(base, a, b);\n")
    }
    it("should format functions with an implicit base arg") {
      val result = f.formatFn("myFn", "body;\n", Seq(Arg("x", Datatype.Long)))
      assert(result.startsWith("static inline void myFn(uintptr_t base, uint64_t x)"))
      assert(result.contains("{"))
      assert(result.contains("}"))
    }
    it("should format write and read as base-relative MMIO accesses") {
      assert(f.formatWrite("drv", "0x0", "0x1") == "reg_write64(base + 0x0, 0x1);\n")
      assert(f.formatWriteReg("drv", "ADDR", "0x1ULL") == "reg_write64(base + ADDR, 0x1ULL);\n")
      assert(f.formatRead("drv", "result", "ADDR") == "uint64_t result;\nresult = reg_read64(base + ADDR);\n")
    }
    it("should format asserts as plain C asserts") {
      assert(f.formatAssertEq("drv", "ADDR", "0x1ULL") == "assert(reg_read64(base + ADDR) == (0x1ULL));\n")
      assert(f.formatAssertEq("drv", "ADDR", "0x1ULL", Some("bad")) ==
        "assert(reg_read64(base + ADDR) == (0x1ULL));\n")
      assert(f.formatUcieAssertEq("drv", "ADDR", "0x1ULL") == "assert(reg_read64(base + ADDR) == (0x1ULL));\n")
    }
    it("should format print statement") {
      assert(f.formatPrintStmt("hello") == "printf(\"hello\\n\");\n")
    }
  }

  describe("Codegen with SystemVerilogFormatter") {
    val codegen = new Codegen(new SystemVerilogFormatter)
    it("should format reset_fsms as a task") {
      val result = codegen.formatResetFsmsFn()
      assert(result.contains("task reset_fsms()"))
      assert(result.contains("`WRITE_UCIE(regDrv, `TX_FSM_RST, 64'h1)"))
      assert(result.contains("`WRITE_UCIE(regDrv, `RX_FSM_RST, 64'h1)"))
      assert(result.contains("`WRITE_UCIE(regDrv, `COMMON_TX_FSM_RST, 64'h1)"))
      assert(result.contains("`EXPECT_UCIE_MSG(regDrv, `TX_TEST_STATE, `TX_TEST_STATE_IDLE"))
      assert(result.contains("`EXPECT_UCIE_MSG(regDrv, `TX_PACKETS_SENT, 64'h0"))
      assert(result.contains("`EXPECT_UCIE_MSG(regDrv, `RX_PACKETS_RECEIVED, 64'h0"))
      assert(result.contains("endtask"))
    }
    it("should format full output") {
      println(codegen.formatAll())
    }
  }

  describe("Codegen with CFormatter") {
    val codegen = new Codegen(new CFormatter)
    it("should format reset_fsms as a base-relative MMIO function") {
      val result = codegen.formatResetFsmsFn()
      assert(result.contains("static inline void reset_fsms(uintptr_t base)"))
      assert(result.contains("reg_write64(base + UCIE_TX_FSM_RST, 0x1ULL);"))
      assert(result.contains("reg_write64(base + UCIE_RX_FSM_RST, 0x1ULL);"))
      assert(result.contains("reg_write64(base + UCIE_COMMON_TX_FSM_RST, 0x1ULL);"))
      assert(result.contains("assert(reg_read64(base + UCIE_TX_TEST_STATE) == (UCIE_TX_TEST_STATE_IDLE));"))
      assert(result.contains("assert(reg_read64(base + UCIE_TX_PACKETS_SENT) == (0x0ULL));"))
      assert(result.contains("assert(reg_read64(base + UCIE_RX_PACKETS_RECEIVED) == (0x0ULL));"))
      assert(!result.contains("endtask"))
      assert(!result.contains("`"))
    }
    it("should format full output") {
      println(codegen.formatAll())
    }
  }
}
