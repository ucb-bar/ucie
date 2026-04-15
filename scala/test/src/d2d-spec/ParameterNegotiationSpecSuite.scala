package edu.berkeley.cs.uciedigital.d2dspec

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import edu.berkeley.cs.uciedigital.d2dadapter.{D2DAdapter, D2DSidebandConstant, RdiSidebandMessageCollector, SidebandHeader}
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

class ParameterNegotiationSpecSuite extends AnyFlatSpec with ChiselSim {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams

  private val sidebandWordWidth = rdiParams.sbWidth
  private val sidebandBeatsPerMessage = sbParams.sbNodeMsgWidth / sidebandWordWidth

  private val advCapHeader = SidebandHeader(opcode = 0x1b, msgCode = 0x01, msgSubCode = 0x00)
  private val finCapHeader = SidebandHeader(opcode = 0x1b, msgCode = 0x02, msgSubCode = 0x00)
  private val reqActiveHeader = SidebandHeader(opcode = 0x12, msgCode = 0x03, msgSubCode = 0x01)
  private val rspActiveHeader = SidebandHeader(opcode = 0x12, msgCode = 0x04, msgSubCode = 0x01)

  behavior of "D2D parameter negotiation"

  it should "advertise Raw Format and Streaming while masking unsupported protocols" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)

      dut.io.rdi.plInbandPres.poke(true.B)
      D2DSpecTopLevelSupport.waitUntil(dut, maxCycles = 40, reason = "RDI request ACTIVE") {
        dut.io.rdi.lpStateReq.peek().litValue == PhyStateReq.active.litValue
      }
      dut.io.rdi.plStateStatus.poke(PhyState.active)

      val collector = new RdiSidebandMessageCollector(
        wordWidth = sidebandWordWidth,
        beatsPerMessage = sidebandBeatsPerMessage
      )
      val advCapMsg = D2DSpecTopLevelSupport.waitForNextOutboundMessage(
        dut = dut,
        collector = collector,
        maxCycles = 120,
        reason = "ADV_CAP advertisement"
      )

      val expectedAdvCapMsg = SBMsgCreate(
        base = SBM.ADVCAP_ADAPTER,
        src = "D2D",
        remote = true,
        dst = "D2D",
        data = D2DSidebandConstant.ADV_CAP_MESSAGE_DATA
      ).litValue

      assert(
        advCapMsg == expectedAdvCapMsg,
        f"Expected ADVCAP_ADAPTER=0x$expectedAdvCapMsg%x, observed 0x$advCapMsg%x"
      )
      assert(
        D2DSpecTopLevelSupport.decodeLinkHeader(advCapMsg) == advCapHeader,
        s"Observed unexpected sideband header ${D2DSpecTopLevelSupport.decodeLinkHeader(advCapMsg)}"
      )
    }
  }

  it should "not emit FINCAP messages during streaming bring-up" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)
      val outboundMsgs = D2DSpecTopLevelSupport.bringLinkToActive(
        dut = dut,
        rdiParams = rdiParams,
        sbParams = sbParams
      )

      val headers = outboundMsgs.map(D2DSpecTopLevelSupport.decodeLinkHeader)
      assert(
        headers == Seq(advCapHeader, reqActiveHeader, rspActiveHeader),
        s"Expected only ADV_CAP/REQ_ACTIVE/RSP_ACTIVE during streaming bring-up, observed $headers"
      )
      assert(
        !headers.contains(finCapHeader),
        s"Streaming bring-up unexpectedly emitted FINCAP header $finCapHeader"
      )
    }
  }

  ignore should "assert LINKERROR after an 8 ms parameter exchange timeout" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)

      // Current RTL does not implement a parameter-exchange timeout path or a
      // spec-visible 8 ms timer. This ignored test reserves the spreadsheet row
      // and keeps the intended harness location in place for later enablement.
      dut.io.rdi.plInbandPres.poke(true.B)
      dut.io.rdi.plStateStatus.poke(PhyState.active)
      dut.clock.step(8)
    }
  }

  ignore should "reset the parameter-exchange timer on AdvCap stall" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)

      // Current RTL does not define an AdvCap.Stall sideband message or timer
      // reset behavior for parameter exchange. This ignored test preserves the
      // planned stimulus slot from the verification spreadsheet.
      dut.io.rdi.plInbandPres.poke(true.B)
      dut.io.rdi.plStateStatus.poke(PhyState.active)
      dut.clock.step(8)
    }
  }
}
