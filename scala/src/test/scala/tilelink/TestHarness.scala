package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import chiseltest._
import org.scalatest.funspec.AnyFunSpec
import edu.berkeley.cs.chippy.ChippyStage
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.prci._
import edu.berkeley.cs.uciedigital.tilelink._
import edu.berkeley.cs.chippy.TLTesterParams
import edu.berkeley.cs.chippy.TLTester
import edu.berkeley.cs.chippy.TLTesterIO
import edu.berkeley.cs.chippy.TLTesterReq
import edu.berkeley.cs.chippy.TLTesterResp

class TestHarness(implicit p: Parameters) extends LazyModule {
  val tltParams = TLTesterParams()
  val ucieParams = UcieTLParams(sim = true)
  val beatBytes = 64;

  val tlt = LazyModule(new TLTester(tltParams, beatBytes))

  val ucieTL = LazyModule(new UcieTL(UcieTLParams(), beatBytes))
  val clockSourceNode_digital = ClockSourceNode(
    Seq(ClockSourceParameters())
  ) // drive uciephy and ucietl clock nodes
  // val clockSourceNode_phy     = ClockSourceNode(Seq(ClockSourceParameters()))

  ucieTL.clockNode := clockSourceNode_digital
  ucieTL.node := tlt.node

  // ucie.uciTL.managerNode := tltester.node
  // mem.node := ucie.uciTL.clientNode

  // ucie.clockNode := clockSourceNode_digital
  // ucie.uciTL.clockNode := clockSourceNode_phy

  // ucie.node := placeholder_node_ucie_regmap
  // ucie.uciTL.regNode.node := placeholder_node_tl_regmap

  // val uciephyTopIO = BundleBridgeSink[uciephytest.UciephyTopIO]()
  // uciephyTopIO := ucie.topIO

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new TLTesterIO(tltParams))

    io <> tlt.module.io

    // Loopback
    ucieTL.module.io.phy.rxData := ucieTL.module.io.phy.txData
    ucieTL.module.io.phy.rxValid := ucieTL.module.io.phy.txValid
    ucieTL.module.io.phy.rxTrack := ucieTL.module.io.phy.txTrack
    ucieTL.module.io.phy.rxClkP := ucieTL.module.io.phy.txClkP
    ucieTL.module.io.phy.rxClkN := ucieTL.module.io.phy.txClkN
    ucieTL.module.io.phy.sbRxClk := ucieTL.module.io.phy.sbTxClk
    ucieTL.module.io.phy.sbRxData := ucieTL.module.io.phy.sbTxData
    ucieTL.module.io.phy.refClkP := clock
    ucieTL.module.io.phy.refClkN := (!clock.asBool).asClock
    ucieTL.module.io.phy.bypassClkP := clock
    ucieTL.module.io.phy.bypassClkN := (!clock.asBool).asClock
    ucieTL.module.io.phy.pllRdacVref := 0.U

    clockSourceNode_digital.out(0)._1.clock := clock
    clockSourceNode_digital.out(0)._1.reset := reset
  }
}

class TestHarnessSpec extends AnyFunSpec with ChiselScalatestTester {
  describe("TestHarness") {
    it("should generate valid System Verilog") {
      implicit val p = Parameters.empty
      ChippyStage.emitSystemVerilogFile(
        LazyModule(new TestHarness()).module,
        args = Array(
          "--target-dir",
          "./test_run_dir/TestHarness_should_generate_valid_System_Verilog"
        )
      )
    }

    it("should be able to read/write MMIO registers") {
      implicit val p = Parameters.empty
      val dut = new TestHarness()
      test(LazyModule(dut).module).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) {
        c =>
          c.reset.poke(true.B)
          c.clock.step()
          c.reset.poke(false.B)
          c.clock.step()
          c.clock.setTimeout(512)
          c.io.req.enqueue(
            new TLTesterReq(dut.tltParams).Lit(
              _.addr -> 0x4000.U,
              _.data -> 0.U,
              _.is_write -> false.B
            )
          )
          c.io.resp.expectDequeue(
            new TLTesterResp(dut.tltParams).Lit(
              _.data -> 0.U
            )
          )
          println("[TEST] Success")
      }
    }
  }
}
