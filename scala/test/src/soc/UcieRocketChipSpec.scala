package edu.berkeley.cs.uciedigital.soc

import chisel3._
import chisel3.util._

import org.scalatest.funspec.AnyFunSpec
import org.chipsalliance.cde.config.Parameters
import _root_.circt.stage.ChiselStage
import org.chipsalliance.diplomacy.lazymodule._

import examples.rocketconfig.{
  ClockSourceAtFreqMHz,
  RocketChipHarness,
  TestDriver
}
import edu.berkeley.cs.uciedigital.Utils
import os.Path
import chisel3.simulator.ChiselSim

class SimTop(binaryPath: Path, plusArgs: Seq[String] = Seq.empty, fast: Boolean = false)(implicit
    p: Parameters
) extends RawModule {
  val driver = Module(new TestDriver)
  val harness = Module(new TestHarness(binaryPath, plusArgs, fast))
  harness.io.reset := driver.reset
  driver.success := harness.io.success
}

class TestHarness(binaryPath: Path, plusArgs: Seq[String] = Seq.empty, fast: Boolean = false)(implicit
    p: Parameters
) extends RawModule {
  val io = IO(new Bundle {
    val success = Output(Bool())
    val reset = Input(Bool())
  })

  val digitalFreqMHz = 500
  val source = Module(new ClockSourceAtFreqMHz(digitalFreqMHz))
  source.io.power := true.B
  source.io.gate := false.B
  val digitalClock = source.io.clk

  withClockAndReset(digitalClock, io.reset) {
    val chiptop_lazy = LazyModule(new UcieRocketChipTop)
    val chiptop = Module(chiptop_lazy.module)
    chiptop.io.clock := digitalClock
    chiptop.io.reset := io.reset.asAsyncReset
    chiptop.serial_tl.clock_in := digitalClock
    chiptop.chip_id := 0.U

    // Loopback every UCIe port so the chiplet link initializes against
    // itself. The data lanes + sideband loop back via UcieBumpsIO.loopback;
    // refClk goes unused (DontCare) and bypassClk/digitalBypassClk are driven
    // by free-running clocks at the same rates as TileLinkSpec uses.
    val ucieBypassClock = RocketChipHarness.freeRunningClock(8000)
    val ucieDigitalBypassClock = RocketChipHarness.freeRunningClock(800)
    chiptop.c2c_ucie.foreach { ucie =>
      ucie.loopback
      ucie.phy.refClkP := DontCare
      ucie.phy.refClkN := DontCare
      ucie.phy.bypassClkP := ucieBypassClock
      ucie.phy.bypassClkN := (!ucieBypassClock.asBool).asClock
      ucie.phy.digitalBypassClk := ucieDigitalBypassClock
      ucie.phy.pllRdacVref := 0.U
    }

    RocketChipHarness.connectUart(chiptop.uart, digitalFreqMHz)
    val dtm_success = RocketChipHarness.connectJtag(chiptop.jtag, digitalClock, io.reset)
    val tsi_success = RocketChipHarness.connectSerialTLAndBoot(
      chiptop.serial_tl,
      chiptop_lazy.system.serdessers(0),
      digitalClock,
      io.reset,
      binaryPath,
      plusArgs,
      fast,
    )

    io.success := dtm_success || tsi_success
  }
}

class UcieRocketChipSpec extends AnyFunSpec with ChiselSim {
  describe("UcieRocketChip") {
    it("should generate valid System Verilog") {
      implicit val p = new UcieRocketChipConfig
      ChiselStage.emitSystemVerilogFile(
        LazyModule(new UcieRocketChipTop).module,
        args = Array(
          "--target-dir",
          (Utils.buildRoot / "UcieRocketChip_should_generate_valid_System_Verilog")
            .toString()
        )
      )
    }

    it("should run hello.riscv") {
      implicit val p = new UcieRocketChipConfig(sim = true)
      val workDir = Utils.buildRoot / "UcieRocketChip_should_run_hello_riscv"

      Utils.simulateTopWithBinary(
        new SimTop(Utils.softwareDir / "hello.riscv"),
        workDir,
        Utils.softwareDir / "hello.riscv",
      )
    }

    it("should run ucie-simple.riscv") {
      implicit val p = new UcieRocketChipConfig(sim = true)
      val workDir = Utils.buildRoot / "UcieRocketChip_should_run_ucie_simple"

      Utils.simulateTopWithBinary(
        new SimTop(Utils.softwareDir / "ucie-simple.riscv"),
        workDir,
        Utils.softwareDir / "ucie-simple.riscv",
      )
    }
  }
}
