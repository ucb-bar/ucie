package edu.berkeley.cs.uciedigital.d2dspec

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

import edu.berkeley.cs.uciedigital.d2dadapter.{D2DAdapter, RawBeat, RawStreamIds}
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

class RawDatapathSpecSuite extends AnyFlatSpec with ChiselSim {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams

  behavior of "D2D raw RX/TX datapath"

  it should "pass protocol-to-PHY raw data unmodified under backpressure" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)
      D2DSpecTopLevelSupport.bringLinkToActive(
        dut = dut,
        rdiParams = rdiParams,
        sbParams = sbParams
      )
      dut.io.fdi.plStateStatus.expect(PhyState.active)

      val rng = new Random(20260415L)
      val beats = (0 until 96).map { i =>
        RawBeat(
          data = BigInt(64, rng),
          streamId =
            if ((i & 1) == 0) RawStreamIds.Stack0Streaming
            else RawStreamIds.Stack1Streaming
        )
      }
      val readyPattern: Long => Boolean = cycle => (cycle % 7) < 4

      D2DSpecTopLevelSupport.runForwardTrafficToCompletion(
        dut = dut,
        beats = beats,
        maxCycles = 8000,
        egressReadyFn = readyPattern
      )
    }
  }

  it should "pass PHY-to-protocol raw data unmodified with streaming metadata fixed to stack0" in {
    simulate(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      D2DSpecTopLevelSupport.initDut(dut)
      D2DSpecTopLevelSupport.bringLinkToActive(
        dut = dut,
        rdiParams = rdiParams,
        sbParams = sbParams
      )
      dut.io.fdi.plStateStatus.expect(PhyState.active)

      val rng = new Random(424242L)
      val beats = (0 until 80).map { _ =>
        RawBeat(
          data = BigInt(64, rng),
          streamId = RawStreamIds.Stack0Streaming
        )
      }

      D2DSpecTopLevelSupport.runReverseTrafficToCompletion(
        dut = dut,
        beats = beats,
        maxCycles = 6000
      )
    }
  }
}
