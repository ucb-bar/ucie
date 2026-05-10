package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams
import org.scalatest.funspec.AnyFunSpec

class D2DAdapterRawModeTest extends AnyFunSpec with ChiselSim {
  import D2DAdapterTestUtils._

  private def bringupToActiveWithRemoteAdvCap(dut: D2DAdapter, remoteAdvCapData: BigInt): Unit = {
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
    assert(
      msgMatches(localRspActive, OpcodeMsgNoData, MsgCodeAdapter0RspActive, SubcodeActive),
      f"Expected local RSP_ACTIVE, got 0x$localRspActive%032x"
    )
    sendRdiSidebandMsg(dut, sbAdapter0RspActive())
  }

  describe("D2DAdapter raw mode bring-up (Steps 11/12)") {
    it("advertises raw-streaming AdvCap and reaches Active with matching remote raw AdvCap") {
      simulate(new D2DAdapter(FdiParams(32, 32), RdiParams(32, 32), new SidebandParams())) { dut =>
        bringupToActiveWithRemoteAdvCap(dut, AdvCapRawStreamingStack0)

        var cycles = 0
        while (dut.io.fdi.plStateSts.peek().litValue != FDIState.active.litValue && cycles < 200) {
          dut.clock.step()
          cycles += 1
        }
        assert(dut.io.fdi.plStateSts.peek().litValue == FDIState.active.litValue, "FDI did not reach Active")
      }
    }

    it("does not accept one-sided raw negotiation (remote raw bit cleared)") {
      simulate(new D2DAdapter(FdiParams(32, 32), RdiParams(32, 32), new SidebandParams())) { dut =>
        // Remote side clears raw bit (bit0) while keeping Streaming+Stack0 shape.
        val remoteNoRaw = AdvCapRawStreamingStack0 & ~BigInt(1)
        bringupToActiveWithRemoteAdvCap(dut, remoteNoRaw)

        var reachedActive = false
        var cycles = 0
        while (!reachedActive && cycles < 200) {
          reachedActive = dut.io.fdi.plStateSts.peek().litValue == FDIState.active.litValue
          dut.clock.step()
          cycles += 1
        }

        assert(!reachedActive, "FDI reached Active even though remote raw capability was not advertised")
      }
    }
  }
}

