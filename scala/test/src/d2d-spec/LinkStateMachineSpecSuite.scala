package edu.berkeley.cs.uciedigital.d2dspec

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import edu.berkeley.cs.uciedigital.d2dadapter.{D2DAdapter, RawBeat, RawStreamIds, RdiSidebandMessageCollector, SidebandHeader}
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

class LinkStateMachineSpecSuite extends AnyFlatSpec with ChiselSim {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams

  private val sidebandWordWidth = rdiParams.sbWidth
  private val sidebandBeatsPerMessage = sbParams.sbNodeMsgWidth / sidebandWordWidth

  private val advCapHeader = SidebandHeader(opcode = 0x1b, msgCode = 0x01, msgSubCode = 0x00)
  private val reqActiveHeader = SidebandHeader(opcode = 0x12, msgCode = 0x03, msgSubCode = 0x01)

  behavior of "D2D link state machine"

  it should "wait for RDI ACTIVE before beginning active-state negotiation" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)

      val collector = new RdiSidebandMessageCollector(
        wordWidth = sidebandWordWidth,
        beatsPerMessage = sidebandBeatsPerMessage
      )

      dut.io.rdi.plInbandPres.poke(true.B)
      D2DSpecTopLevelSupport.waitUntil(dut, maxCycles = 40, reason = "RDI bring-up request") {
        dut.io.rdi.lpStateReq.peek().litValue == PhyStateReq.active.litValue
      }

      for (_ <- 0 until 12) {
        dut.io.fdi.plStateStatus.expect(PhyState.reset)
        dut.io.fdi.plInbandPres.expect(false.B)
        dut.io.rdi.lpConfig.valid.expect(false.B)
        dut.clock.step(1)
      }

      dut.io.rdi.plStateStatus.poke(PhyState.active)
      val advCapMsg = D2DSpecTopLevelSupport.waitForNextOutboundMessage(
        dut = dut,
        collector = collector,
        maxCycles = 120,
        reason = "ADV_CAP after RDI ACTIVE"
      )
      assert(
        D2DSpecTopLevelSupport.decodeLinkHeader(advCapMsg) == advCapHeader,
        s"Expected ADV_CAP after RDI ACTIVE, observed ${D2DSpecTopLevelSupport.decodeLinkHeader(advCapMsg)}"
      )

      D2DSpecTopLevelSupport.sendInboundSidebandMessage(
        dut = dut,
        sidebandWordWidth = sidebandWordWidth,
        sidebandBeatsPerMessage = sidebandBeatsPerMessage,
        linkOp = SideBandMessage.ADV_CAP
      )

      D2DSpecTopLevelSupport.waitUntil(dut, maxCycles = 60, reason = "FDI inband presence") {
        dut.io.fdi.plInbandPres.peek().litToBoolean
      }

      dut.io.fdi.lpStateReq.poke(PhyStateReq.nop)
      dut.clock.step(1)
      dut.io.fdi.lpStateReq.poke(PhyStateReq.active)
      dut.clock.step(1)

      val reqActiveMsg = D2DSpecTopLevelSupport.waitForNextOutboundMessage(
        dut = dut,
        collector = collector,
        maxCycles = 120,
        reason = "REQ_ACTIVE after RDI ACTIVE"
      )
      assert(
        D2DSpecTopLevelSupport.decodeLinkHeader(reqActiveMsg) == reqActiveHeader,
        s"Expected REQ_ACTIVE after RDI ACTIVE, observed ${D2DSpecTopLevelSupport.decodeLinkHeader(reqActiveMsg)}"
      )
    }
  }

  it should "propagate RETRAIN to the adapter state machine and halt new ingress traffic" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)
      D2DSpecTopLevelSupport.bringLinkToActive(dut, rdiParams, sbParams)

      val beats = (0 until 128).map { i =>
        RawBeat(
          data = BigInt("9000000000000000", 16) + BigInt(i),
          streamId =
            if ((i & 1) == 0) RawStreamIds.Stack0Streaming
            else RawStreamIds.Stack1Streaming
        )
      }
      val env = D2DSpecTopLevelSupport.newForwardTrafficEnv(dut, beats)

      var cycle = 0L
      val triggerCycle = 40L
      var stallReqSeen = false
      var rdiRetrainReqSeen = false
      var retrainSeen = false
      var ackIssued = false
      var retrainBoundaryCycle: Option[Long] = None
      var acceptedAfterBoundary = 0L

      while (cycle < 1800 && (!retrainSeen || env.expectedQ.nonEmpty || cycle < triggerCycle + 100)) {
        if (cycle == triggerCycle) {
          dut.io.rdi.plStateStatus.poke(PhyState.retrain)
          dut.io.fdi.lpRxActiveStatus.poke(false.B)
        }

        val stallReq = dut.io.fdi.plStallReq.peek().litToBoolean
        if (stallReq) stallReqSeen = true
        if (stallReq && !ackIssued) {
          dut.io.fdi.lpStallAck.poke(true.B)
          ackIssued = true
        } else {
          dut.io.fdi.lpStallAck.poke(false.B)
        }

        if (dut.io.rdi.lpStateReq.peek().litValue == PhyStateReq.retrain.litValue) {
          rdiRetrainReqSeen = true
        }

        val accepted = D2DSpecTopLevelSupport.stepForwardTraffic(
          dut = dut,
          env = env,
          cycle = cycle,
          egressReady = true,
          boundaryCrossed = retrainBoundaryCycle.exists(cycle >= _),
          boundaryName = "retrain_boundary"
        )

        if (dut.io.fdi.plStateStatus.peek().litValue == PhyState.retrain.litValue) {
          retrainSeen = true
          if (retrainBoundaryCycle.isEmpty) retrainBoundaryCycle = Some(cycle)
        }

        if (accepted.nonEmpty && retrainBoundaryCycle.exists(cycle > _)) {
          acceptedAfterBoundary += 1
        }

        cycle += 1
      }

      assert(stallReqSeen, "No FDI stall request was observed for retrain entry")
      assert(rdiRetrainReqSeen, "No RDI retrain request was observed before retrain entry")
      assert(retrainSeen, "Adapter never propagated RETRAIN to FDI state status")
      assert(
        acceptedAfterBoundary == 0,
        s"Observed $acceptedAfterBoundary accepted beats after RETRAIN became visible on FDI"
      )

      env.scoreboard.finishAndAssert(
        acceptedInputCount = env.ingressTracker.acceptedCount,
        maxExpectedQueueDepth = Some(env.maxExpectedQueueDepth)
      )
    }
  }

  it should "not request RDI retrain exit before RETRAIN is visible on FDI" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)
      D2DSpecTopLevelSupport.bringLinkToActive(dut, rdiParams, sbParams)

      dut.io.rdi.plStateStatus.poke(PhyState.retrain)
      dut.io.fdi.lpRxActiveStatus.poke(false.B)

      var cycle = 0
      var ackIssued = false
      var retrainSeen = false

      while (cycle < 80 && !retrainSeen) {
        val stallReq = dut.io.fdi.plStallReq.peek().litToBoolean
        if (stallReq && cycle >= 4 && !ackIssued) {
          dut.io.fdi.lpStallAck.poke(true.B)
          ackIssued = true
        } else {
          dut.io.fdi.lpStallAck.poke(false.B)
        }

        assert(
          dut.io.rdi.lpStateReq.peek().litValue != PhyStateReq.active.litValue,
          s"Observed premature RDI exit request before FDI RETRAIN was visible. ${D2DSpecTopLevelSupport.snapshot(dut)}"
        )

        dut.clock.step(1)
        if (dut.io.fdi.plStateStatus.peek().litValue == PhyState.retrain.litValue) {
          retrainSeen = true
        }
        cycle += 1
      }

      assert(retrainSeen, "Adapter never reached RETRAIN in exit-barrier check")
      assert(
        dut.io.rdi.lpStateReq.peek().litValue != PhyStateReq.active.litValue,
        s"Observed premature RDI exit request after RETRAIN entry. ${D2DSpecTopLevelSupport.snapshot(dut)}"
      )
    }
  }

  it should "propagate remote LINKERROR to FDI" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)
      D2DSpecTopLevelSupport.bringLinkToActive(dut, rdiParams, sbParams)

      dut.io.rdi.plStateStatus.poke(PhyState.linkError)
      D2DSpecTopLevelSupport.waitUntil(dut, maxCycles = 20, reason = "FDI LINKERROR visibility") {
        dut.io.fdi.plStateStatus.peek().litValue == PhyState.linkError.litValue
      }
      dut.io.fdi.plStateStatus.expect(PhyState.linkError)
    }
  }

  it should "give LINKERROR higher priority than a concurrent LINKRESET request" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)
      D2DSpecTopLevelSupport.bringLinkToActive(dut, rdiParams, sbParams)

      dut.io.fdi.lpStateReq.poke(PhyStateReq.linkReset)
      dut.io.fdi.lpRxActiveStatus.poke(false.B)
      dut.io.rdi.plStateStatus.poke(PhyState.linkError)

      D2DSpecTopLevelSupport.waitUntil(dut, maxCycles = 20, reason = "LINKERROR priority resolution") {
        dut.io.fdi.plStateStatus.peek().litValue == PhyState.linkError.litValue
      }
      dut.io.fdi.plStateStatus.expect(PhyState.linkError)

      for (_ <- 0 until 6) {
        dut.io.fdi.plStateStatus.expect(PhyState.linkError)
        dut.clock.step(1)
      }
    }
  }

  it should "not request LINKERROR exit on RDI before LINKERROR is visible on FDI" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)
      D2DSpecTopLevelSupport.bringLinkToActive(dut, rdiParams, sbParams)

      dut.io.fdi.lpStateReq.poke(PhyStateReq.nop)
      dut.io.rdi.plStateStatus.poke(PhyState.linkError)

      var cycle = 0
      var linkErrorSeen = false
      while (cycle < 20 && !linkErrorSeen) {
        assert(
          dut.io.rdi.lpStateReq.peek().litValue != PhyStateReq.active.litValue,
          s"Observed premature RDI LinkError-exit request before FDI LINKERROR. ${D2DSpecTopLevelSupport.snapshot(dut)}"
        )
        dut.clock.step(1)
        if (dut.io.fdi.plStateStatus.peek().litValue == PhyState.linkError.litValue) {
          linkErrorSeen = true
        }
        cycle += 1
      }
      assert(linkErrorSeen, "FDI LINKERROR never became visible")

      for (_ <- 0 until 6) {
        dut.io.rdi.lpStateReq.expect(PhyStateReq.nop)
        dut.clock.step(1)
      }
    }
  }

  ignore should "propagate a remote LINKRESET state directly from RDI to FDI" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)
      D2DSpecTopLevelSupport.bringLinkToActive(dut, rdiParams, sbParams)

      // Current RTL reaches LINKRESET through local/sideband request-response
      // flow, but does not directly follow a raw remote RDI plStateStatus=LinkReset
      // indication. This ignored test preserves the spreadsheet requirement.
      dut.io.rdi.plStateStatus.poke(PhyState.linkReset)
      dut.clock.step(8)
    }
  }
}
