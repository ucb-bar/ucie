package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator.simulators.vcs
import svsim.CommonCompilationSettings
import svsim.vcs.Backend.CompilationSettings
import edu.berkeley.cs.uciedigital.interfaces._
import org.scalatest.funspec.AnyFunSpec

class ResolvedWidthTest extends AnyFunSpec with ChiselSim {
  implicit private val simBackend: chisel3.simulator.HasSimulator =
    vcs(CommonCompilationSettings.default, CompilationSettings())

  private def initLogicalPhy(dut: LogicalPhy): Unit = {
    // RDI adapter->phy inputs
    dut.io.rdi.lpIrdy.poke(false.B)
    dut.io.rdi.lpValid.poke(false.B)
    dut.io.rdi.lpData.poke(0.U)
    dut.io.rdi.lpStateReq.poke(RDIStateReq.nop)
    dut.io.rdi.lpLinkError.poke(false.B)
    dut.io.rdi.lpStallAck.poke(false.B)
    dut.io.rdi.lpClkAck.poke(false.B)
    dut.io.rdi.lpWakeReq.poke(false.B)
    dut.io.rdi.lpCfg.poke(0.U)
    dut.io.rdi.lpCfgVld.poke(false.B)
    dut.io.rdi.lpCfgCrd.poke(true.B)

    // Top-level control
    dut.io.ctrl.pwrGood.poke(true.B)
    dut.io.ctrl.retryTrainingAmt.poke(0.U)
    dut.io.ctrl.localPhyParamSettings.valid.poke(false.B)
    dut.io.ctrl.localPhyParamSettings.bits.voltageSwing.poke(0.U)
    dut.io.ctrl.localPhyParamSettings.bits.maxDataRate.poke(0.U)
    dut.io.ctrl.localPhyParamSettings.bits.clockMode.poke(0.U)
    dut.io.ctrl.localPhyParamSettings.bits.clockPhase.poke(0.U)
    dut.io.ctrl.localPhyParamSettings.bits.ucieSx8.poke(0.U)
    dut.io.ctrl.localPhyParamSettings.bits.sbFeatExt.poke(0.U)
    dut.io.ctrl.localPhyParamSettings.bits.txAdjRuntime.poke(0.U)
    dut.io.ctrl.localPhyParamSettings.bits.moduleId.poke(0.U)
    dut.io.ctrl.swStartLinkTraining.poke(false.B)
    dut.io.ctrl.maxErrorThresholdPerLane.poke(0.U)
    dut.io.ctrl.changeInRuntimeLinkCtrlRegsDetected.poke(false.B)
    dut.io.ctrl.runtimeLinkCtrlBusyBit.poke(false.B)
    dut.io.ctrl.runtimeRequestForRepair.poke(false.B)

    // Analog model side defaults
    dut.io.analog.status.pllLock.poke(true.B)
    dut.io.analog.status.clocksUngatedAndStable.poke(true.B)
    dut.io.analog.sidebandLink.in.bits.poke(0.U)
    dut.io.analog.sidebandLink.in.fwClock.poke(0.U)

    dut.io.analog.mainband.tx.ready.poke(true.B)
    dut.io.analog.mainband.rx.valid.poke(false.B)
    dut.io.analog.mainband.rx.bits.data.foreach(_.poke(0.U))
    dut.io.analog.mainband.rx.bits.valid.poke(0.U)
    dut.io.analog.mainband.rx.bits.clkP.poke(0.U)
    dut.io.analog.mainband.rx.bits.clkN.poke(0.U)
    dut.io.analog.mainband.rx.bits.trk.poke(0.U)
  }

  describe("Resolved width presentation to adapter-facing RDI") {
    it("presents a legal width and stays aligned with default x16 configuration in current build") {
      simulate(new LogicalPhy()) { dut =>
        initLogicalPhy(dut)

        val legal = Set(
          LinkWidth.x16.litValue,
          LinkWidth.x8.litValue,
          LinkWidth.x4.litValue,
          LinkWidth.x2.litValue,
          LinkWidth.x1.litValue
        )

        // For default LogicalPhy params in this environment, resolved width should
        // present as x16 unless training parameterization explicitly changes it.
        val expectedDefault = LinkWidth.x16.litValue

        var cycles = 0
        while (cycles < 40) {
          val presented = dut.io.rdi.plLnkCfg.peek().litValue
          assert(legal.contains(presented), f"Illegal LinkWidth encoding observed: 0x$presented%x")
          assert(
            presented == expectedDefault,
            f"Unexpected default link width for current build: got=0x$presented%x expected=0x$expectedDefault%x"
          )
          dut.clock.step()
          cycles += 1
        }

        // Exercise control activity and ensure width remains legal and stable.
        dut.io.ctrl.swStartLinkTraining.poke(true.B)
        dut.clock.step(8)
        dut.io.ctrl.swStartLinkTraining.poke(false.B)

        cycles = 0
        while (cycles < 80) {
          val presented = dut.io.rdi.plLnkCfg.peek().litValue
          assert(legal.contains(presented), f"Illegal LinkWidth encoding observed during trigger: 0x$presented%x")
          assert(
            presented == expectedDefault,
            f"Unexpected width change in current default build: got=0x$presented%x expected=0x$expectedDefault%x"
          )
          dut.clock.step()
          cycles += 1
        }
      }
    }
  }
}
