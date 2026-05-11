package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator.simulators.vcs
import svsim.CommonCompilationSettings
import svsim.vcs.Backend.CompilationSettings
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams
import org.scalatest.funspec.AnyFunSpec

class D2DAdapterCapabilityTest extends AnyFunSpec with ChiselSim {
  import D2DAdapterTestUtils._
  implicit private val simBackend: chisel3.simulator.HasSimulator =
    vcs(CommonCompilationSettings.default, CompilationSettings())

  private def initAdapterInputs(dut: D2DAdapter): Unit = {
    dut.io.fdi.lpIrdy.poke(false.B)
    dut.io.fdi.lpValid.poke(false.B)
    dut.io.fdi.lpData.poke(0.U)
    dut.io.fdi.lpStateReq.poke(FDIStateReq.nop)
    dut.io.fdi.lpLinkError.poke(false.B)
    dut.io.fdi.lpRxActiveSts.poke(false.B)
    dut.io.fdi.lpStallAck.poke(false.B)
    dut.io.fdi.lpClkAck.poke(false.B)
    dut.io.fdi.lpWakeReq.poke(false.B)
    dut.io.fdi.lpCfg.poke(0.U)
    dut.io.fdi.lpCfgVld.poke(false.B)
    dut.io.fdi.plCfgCrd.poke(true.B)

    dut.io.rdi.plTrdy.poke(true.B)
    dut.io.rdi.plValid.poke(false.B)
    dut.io.rdi.plData.poke(0.U)
    dut.io.rdi.plStateSts.poke(RDIState.reset)
    dut.io.rdi.plInbandPres.poke(false.B)
    dut.io.rdi.plError.poke(false.B)
    dut.io.rdi.plCError.poke(false.B)
    dut.io.rdi.plNfError.poke(false.B)
    dut.io.rdi.plTrainError.poke(false.B)
    dut.io.rdi.plPhyInRecenter.poke(false.B)
    dut.io.rdi.plStallReq.poke(false.B)
    dut.io.rdi.plSpeedmode.poke(SpeedMode.speed16)
    dut.io.rdi.plMaxSpeedmode.poke(false.B)
    dut.io.rdi.plLnkCfg.poke(LinkWidth.x16)
    dut.io.rdi.plClkReq.poke(false.B)
    dut.io.rdi.plWakeAck.poke(false.B)
    dut.io.rdi.plCfg.poke(0.U)
    dut.io.rdi.plCfgVld.poke(false.B)
    dut.io.rdi.plCfgCrd.poke(true.B)
  }

  private def recvRdiSidebandMsg(dut: D2DAdapter, maxCycles: Int = 200, sidebandWidth: Int = 32): BigInt = {
    val beats = 128 / sidebandWidth
    var cycle = 0
    while (!dut.io.rdi.lpCfgVld.peekBoolean() && cycle < maxCycles) {
      dut.clock.step()
      cycle += 1
    }
    assert(dut.io.rdi.lpCfgVld.peekBoolean(), s"Timed out waiting for sideband output after $maxCycles cycles")

    var msg = BigInt(0)
    for (beat <- 0 until beats) {
      val lane = dut.io.rdi.lpCfg.peek().litValue
      msg |= lane << (beat * sidebandWidth)
      dut.clock.step()
    }
    msg
  }

  private def bit(data: BigInt, idx: Int): Int = ((data >> idx) & 1).toInt

  describe("D2DAdapter advertised capability") {
    it("emits raw single-stack capability map consistent with project profile") {
      simulate(new D2DAdapter(FdiParams(32, 32), RdiParams(32, 32), new SidebandParams())) { dut =>
        initAdapterInputs(dut)
        dut.clock.step(2)

        dut.io.rdi.plInbandPres.poke(true.B)
        dut.clock.step(5)
        dut.io.rdi.plStateSts.poke(RDIState.active)

        val localAdvCap = recvRdiSidebandMsg(dut)
        assert(
          msgMatches(localAdvCap, OpcodeMsgWith64B, MsgCodeAdvCapAdapter, SubcodeAdvCap),
          f"Expected local ADV_CAP, got 0x$localAdvCap%032x"
        )

        val cap = (localAdvCap >> 64) & ((BigInt(1) << 64) - 1)

        // Required for this raw single-stack scope.
        assert(bit(cap, 0) == 1, f"Raw bit must be 1, cap=0x$cap%016x")
        assert(bit(cap, 7) == 1, f"Stack0 enable bit must be 1, cap=0x$cap%016x")

        // Forbidden in this profile.
        assert(bit(cap, 1) == 0, f"Stack1 enable bit must be 0, cap=0x$cap%016x")
        assert(bit(cap, 5) == 0, f"Retry bit must be 0, cap=0x$cap%016x")
        assert(bit(cap, 8) == 0, f"68B flit-format bit must be 0, cap=0x$cap%016x")
        assert(bit(cap, 9) == 0, f"256B flit-format bit must be 0, cap=0x$cap%016x")
        assert(bit(cap, 2) == 0, f"Multi_Protocol_Enable bit must be 0, cap=0x$cap%016x")
        assert(bit(cap, 3) == 0, f"Enhanced_Multi_Protocol_Enable bit must be 0, cap=0x$cap%016x")
      }
    }

    it("advertises only functionality supported by the instantiated adapter build") {
      val dataBytes = 32
      val sidebandWidth = 32
      simulate(new D2DAdapter(FdiParams(dataBytes, sidebandWidth), RdiParams(dataBytes, sidebandWidth), new SidebandParams())) {
        dut =>
          // Interface shape checks for this elaborated build.
          assert(dut.io.fdi.lpData.getWidth == dataBytes * 8, s"FDI width mismatch: expected ${dataBytes * 8}")
          assert(dut.io.rdi.lpData.getWidth == dataBytes * 8, s"RDI width mismatch: expected ${dataBytes * 8}")
          assert(dut.io.fdi.lpCfg.getWidth == sidebandWidth, s"FDI sideband width mismatch: expected $sidebandWidth")
          assert(dut.io.rdi.plCfg.getWidth == sidebandWidth, s"RDI sideband width mismatch: expected $sidebandWidth")

          initAdapterInputs(dut)
          dut.clock.step(2)
          dut.io.rdi.plInbandPres.poke(true.B)
          dut.clock.step(5)
          dut.io.rdi.plStateSts.poke(RDIState.active)

          val localAdvCap = recvRdiSidebandMsg(dut)
          assert(
            msgMatches(localAdvCap, OpcodeMsgWith64B, MsgCodeAdvCapAdapter, SubcodeAdvCap),
            f"Expected local ADV_CAP, got 0x$localAdvCap%032x"
          )

          val cap = (localAdvCap >> 64) & ((BigInt(1) << 64) - 1)

          // In this project build, the supported/advertised capability set is raw+streaming on stack0 only.
          assert(cap == AdvCapRawStreamingStack0, f"Unexpected capability bitmap for this build: cap=0x$cap%016x")

          // Guard against unsupported protocol-stack advertisement.
          assert(bit(cap, 1) == 0, "Stack1 must remain unadvertised in this single-stack build")
          assert(bit(cap, 2) == 0 && bit(cap, 3) == 0, "Multi-protocol support must not be advertised")
        }
    }
  }
}
