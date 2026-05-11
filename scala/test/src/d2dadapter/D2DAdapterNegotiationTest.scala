package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator.simulators.vcs
import svsim.CommonCompilationSettings
import svsim.vcs.Backend.CompilationSettings
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams
import org.scalatest.funspec.AnyFunSpec

class D2DAdapterNegotiationTest extends AnyFunSpec with ChiselSim {
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

  private def sendRdiSidebandMsg(dut: D2DAdapter, msg: BigInt, sidebandWidth: Int = 32): Unit = {
    val beats = 128 / sidebandWidth
    val mask = (BigInt(1) << sidebandWidth) - 1
    for (beat <- 0 until beats) {
      val lane = (msg >> (beat * sidebandWidth)) & mask
      dut.io.rdi.plCfgVld.poke(true.B)
      dut.io.rdi.plCfg.poke(lane.U)
      dut.clock.step()
    }
    dut.io.rdi.plCfgVld.poke(false.B)
    dut.io.rdi.plCfg.poke(0.U)
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

  private def runRawBringupAndCollectMsgs(
      dut: D2DAdapter,
      remoteAdvCapData: BigInt,
      maxMsgs: Int = 8
  ): Seq[BigInt] = {
    initAdapterInputs(dut)
    dut.clock.step(2)
    dut.io.rdi.plInbandPres.poke(true.B)
    dut.clock.step(5)
    dut.io.rdi.plStateSts.poke(RDIState.active)

    val msgs = scala.collection.mutable.ArrayBuffer.empty[BigInt]

    val localAdvCap = recvRdiSidebandMsg(dut)
    msgs += localAdvCap
    assert(
      msgMatches(localAdvCap, OpcodeMsgWith64B, MsgCodeAdvCapAdapter, SubcodeAdvCap),
      f"Expected local ADV_CAP, got 0x$localAdvCap%032x"
    )

    sendRdiSidebandMsg(dut, sbAdvcapAdapter(data = remoteAdvCapData))

    var cycles = 0
    while (!dut.io.fdi.plInbandPres.peekBoolean() && cycles < 200) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.fdi.plInbandPres.peekBoolean(), "Timed out waiting for FDI inband present")

    sendRdiSidebandMsg(dut, sbAdapter0ReqActive())

    cycles = 0
    while (!dut.io.fdi.plRxActiveReq.peekBoolean() && cycles < 200) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.fdi.plRxActiveReq.peekBoolean(), "Timed out waiting for FDI plRxActiveReq")
    dut.io.fdi.lpRxActiveSts.poke(true.B)

    val localRspActive = recvRdiSidebandMsg(dut)
    msgs += localRspActive
    assert(
      msgMatches(localRspActive, OpcodeMsgNoData, MsgCodeAdapter0RspActive, SubcodeActive),
      f"Expected local RSP_ACTIVE, got 0x$localRspActive%032x"
    )

    sendRdiSidebandMsg(dut, sbAdapter0RspActive())

    var seen = msgs.size
    while (seen < maxMsgs && dut.io.rdi.lpCfgVld.peekBoolean()) {
      msgs += recvRdiSidebandMsg(dut, maxCycles = 4)
      seen += 1
    }

    msgs.toSeq
  }

  describe("D2DAdapter negotiation behavior") {
    it("rejects illegal capability intersection when remote raw support is absent") {
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

        val remoteNoRaw = AdvCapRawStreamingStack0 & ~BigInt(1)
        sendRdiSidebandMsg(dut, sbAdvcapAdapter(data = remoteNoRaw))

        var reachedActive = false
        var sawReqActive = false
        var cycles = 0
        while (cycles < 250) {
          val fdiState = dut.io.fdi.plStateSts.peek().litValue
          if (fdiState == FDIState.active.litValue) {
            reachedActive = true
          }
          if (dut.io.fdi.plInbandPres.peekBoolean() || dut.io.fdi.plRxActiveReq.peekBoolean()) {
            sawReqActive = true
          }
          dut.clock.step()
          cycles += 1
        }

        assert(!reachedActive, "FDI reached Active for an illegal capability intersection")
        assert(!sawReqActive, "Adapter attempted Active bring-up for an illegal capability intersection")
      }
    }

    it("uses AdvCap-only handshake for raw streaming bring-up and does not emit FinCap") {
      simulate(new D2DAdapter(FdiParams(32, 32), RdiParams(32, 32), new SidebandParams())) { dut =>
        val msgs = runRawBringupAndCollectMsgs(dut, remoteAdvCapData = AdvCapRawStreamingStack0)

        val sawLocalAdvCap =
          msgs.exists(m => msgMatches(m, OpcodeMsgWith64B, MsgCodeAdvCapAdapter, SubcodeAdvCap))
        val sawLocalRspActive =
          msgs.exists(m => msgMatches(m, OpcodeMsgNoData, MsgCodeAdapter0RspActive, SubcodeActive))
        val sawFinCap = msgs.exists(m => msgCode(m) == MsgCodeFinCapAdapter)

        assert(sawLocalAdvCap, "Did not observe local ADV_CAP during negotiation")
        assert(sawLocalRspActive, "Did not observe local RSP_ACTIVE during negotiation")
        assert(!sawFinCap, "Observed unexpected FinCap message in raw streaming negotiation")

        var cycles = 0
        while (dut.io.fdi.plStateSts.peek().litValue != FDIState.active.litValue && cycles < 200) {
          dut.clock.step()
          cycles += 1
        }
        assert(dut.io.fdi.plStateSts.peek().litValue == FDIState.active.litValue, "FDI did not reach Active")
      }
    }
  }
}
