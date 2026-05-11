package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator.simulators.vcs
import svsim.CommonCompilationSettings
import svsim.vcs.Backend.CompilationSettings
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams
import org.scalatest.funspec.AnyFunSpec
import scala.util.Random

class D2DAdapterDataPathTest extends AnyFunSpec with ChiselSim {
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

  private def bringupToActive(dut: D2DAdapter): Unit = {
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
    sendRdiSidebandMsg(dut, sbAdvcapAdapter(data = AdvCapRawStreamingStack0))

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
    assert(
      msgMatches(localRspActive, OpcodeMsgNoData, MsgCodeAdapter0RspActive, SubcodeActive),
      f"Expected local RSP_ACTIVE, got 0x$localRspActive%032x"
    )
    sendRdiSidebandMsg(dut, sbAdapter0RspActive())

    cycles = 0
    while (dut.io.fdi.plStateSts.peek().litValue != FDIState.active.litValue && cycles < 200) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.fdi.plStateSts.peek().litValue == FDIState.active.litValue, "FDI did not reach Active")
  }

  describe("D2DAdapter raw-mode data path") {
    it("passes TX payloads from FDI to RDI without modification under backpressure") {
      simulate(new D2DAdapter(FdiParams(32, 32), RdiParams(32, 32), new SidebandParams())) { dut =>
        bringupToActive(dut)

        val rng = new Random(1234)
        val inBeats = Seq.tabulate(24) { i =>
          if (i < 4) BigInt(i + 1) else BigInt(256, rng)
        }
        val outBeats = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var idx = 0
        var cycles = 0

        while ((idx < inBeats.length || outBeats.length < inBeats.length) && cycles < 3000) {
          val ready = (cycles % 3) != 0
          dut.io.rdi.plTrdy.poke(ready.B)

          if (idx < inBeats.length) {
            dut.io.fdi.lpValid.poke(true.B)
            dut.io.fdi.lpIrdy.poke(true.B)
            dut.io.fdi.lpData.poke(inBeats(idx).U)
          } else {
            dut.io.fdi.lpValid.poke(false.B)
            dut.io.fdi.lpIrdy.poke(false.B)
            dut.io.fdi.lpData.poke(0.U)
          }

          val acceptedFromFdi = idx < inBeats.length && dut.io.fdi.plTrdy.peekBoolean()
          val acceptedToRdi = dut.io.rdi.lpValid.peekBoolean() && dut.io.rdi.lpIrdy.peekBoolean() && ready
          val rdiData = dut.io.rdi.lpData.peek().litValue

          dut.clock.step()
          cycles += 1

          if (acceptedFromFdi) idx += 1
          if (acceptedToRdi) outBeats += rdiData
        }

        assert(outBeats.length == inBeats.length, s"TX beat count mismatch: in=${inBeats.length} out=${outBeats.length}")
        assert(outBeats == inBeats, "TX pass-through mismatch: observed modified/reordered payload")
      }
    }

    it("passes RX payloads from RDI to FDI without modification") {
      simulate(new D2DAdapter(FdiParams(32, 32), RdiParams(32, 32), new SidebandParams())) { dut =>
        bringupToActive(dut)

        val rng = new Random(5678)
        val beats = Seq.tabulate(20) { i =>
          if (i < 4) BigInt("a0", 16) + i else BigInt(256, rng)
        }

        beats.foreach { beat =>
          dut.io.rdi.plValid.poke(true.B)
          dut.io.rdi.plData.poke(beat.U)
          dut.clock.step()
          assert(dut.io.fdi.plValid.peekBoolean(), "FDI plValid was low while RX beat should be visible")
          val got = dut.io.fdi.plData.peek().litValue
          assert(got == beat, f"RX pass-through mismatch: expected 0x$beat%064x got 0x$got%064x")
        }

        dut.io.rdi.plValid.poke(false.B)
        dut.io.rdi.plData.poke(0.U)
      }
    }

    it("does not introduce retry or crc side behavior in raw mode data transfer") {
      simulate(new D2DAdapter(FdiParams(32, 32), RdiParams(32, 32), new SidebandParams())) { dut =>
        bringupToActive(dut)

        assert(dut.io.fdi.plProtocolVld.peekBoolean(), "Protocol valid should be asserted in active raw mode")
        assert(
          dut.io.fdi.plProtocol.peek().litValue == FDIProtocol.streamingNoManagementTransport.litValue,
          "Unexpected protocol advertised in raw mode"
        )
        assert(
          dut.io.fdi.plProtocolFlitFmt.peek().litValue == FDIFlitFormat.rawFormat.litValue,
          "Unexpected flit format advertised in raw mode"
        )

        val rng = new Random(9012)
        val txBeats = Seq.fill(16)(BigInt(256, rng))

        var sent = 0
        var received = 0
        var cycles = 0
        while ((sent < txBeats.length || received < txBeats.length) && cycles < 2000) {
          dut.io.rdi.plTrdy.poke(((cycles % 4) != 1).B)

          if (sent < txBeats.length) {
            dut.io.fdi.lpValid.poke(true.B)
            dut.io.fdi.lpIrdy.poke(true.B)
            dut.io.fdi.lpData.poke(txBeats(sent).U)
          } else {
            dut.io.fdi.lpValid.poke(false.B)
            dut.io.fdi.lpIrdy.poke(false.B)
            dut.io.fdi.lpData.poke(0.U)
          }

          val acceptedFromFdi = sent < txBeats.length && dut.io.fdi.plTrdy.peekBoolean()
          val acceptedToRdi = dut.io.rdi.lpValid.peekBoolean() && dut.io.rdi.lpIrdy.peekBoolean() && dut.io.rdi.plTrdy.peekBoolean()
          val rdiData = dut.io.rdi.lpData.peek().litValue

          // No sideband retry/replay signaling should appear during raw payload transfer.
          assert(!dut.io.rdi.lpCfgVld.peekBoolean(), "Unexpected sideband output during raw payload transfer")

          dut.clock.step()
          cycles += 1

          if (acceptedFromFdi) sent += 1
          if (acceptedToRdi) {
            assert(rdiData == txBeats(received), "Raw transfer payload changed (possible adapter-added transform)")
            received += 1
          }
        }

        assert(received == txBeats.length, s"Did not observe all raw payload beats: received=$received")
      }
    }
  }
}
