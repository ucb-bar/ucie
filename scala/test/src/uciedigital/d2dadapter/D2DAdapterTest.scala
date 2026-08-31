/*
  Description:
    Top-level tests for D2DAdapter. Two adapters are wired into a full D2D link:
    their RDI sideband config ports carry link-management messages between the
    dies, and their RDI mainband ports carry flit data. Each adapter also gets a
    small physical-layer model (state status, inband presence, always-ready TX).

    With that in place the tests drive the protocol (FDI) side only, the way a
    protocol layer would, and observe the link come up to Active, carry data,
    and exit through link reset -- exercising the adapter state machine, both
    stall handlers, the sideband bridge and the mainband datapath together.
 */
package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams
import org.scalatest.funspec.AnyFunSpec

/** Protocol-side controls and observables for one adapter, plus the knobs of
  * its physical-layer model.
  */
class D2DAdapterDiePort(fdiParams: FdiParams) extends Bundle {
  // Protocol layer -> adapter
  val lpStateReq = Input(FDIStateReq())
  val lpRxActiveSts = Input(Bool())
  val lpLinkError = Input(Bool())
  val lpIrdy = Input(Bool())
  val lpValid = Input(Bool())
  val lpData = Input(UInt((fdiParams.nBytes * 8).W))
  val lpStallAck = Input(Bool())

  // Adapter -> protocol layer
  val plStateSts = Output(FDIState())
  val plInbandPres = Output(Bool())
  val plRxActiveReq = Output(Bool())
  val plStallReq = Output(Bool())
  val plTrdy = Output(Bool())
  val plValid = Output(Bool())
  val plData = Output(UInt((fdiParams.nBytes * 8).W))
  val plProtocolVld = Output(Bool())

  // Physical-layer model knobs and observables
  val phyState = Input(RDIState())
  val phyInbandPres = Input(Bool())
  val phyStallReq = Input(Bool())
  val rdiLpStateReq = Output(RDIStateReq())
  val rdiLpStallAck = Output(Bool())
}

class D2DAdapterPairHarness(
    fdiParams: FdiParams,
    rdiParams: RdiParams,
    sbParams: SidebandParams
) extends Module {
  val io = IO(new Bundle {
    val a = new D2DAdapterDiePort(fdiParams)
    val b = new D2DAdapterDiePort(fdiParams)
  })

  private val dieA = Module(new D2DAdapter(fdiParams, rdiParams, sbParams))
  private val dieB = Module(new D2DAdapter(fdiParams, rdiParams, sbParams))

  private def hookup(adapter: D2DAdapter, port: D2DAdapterDiePort): Unit = {
    // Protocol (FDI) side.
    adapter.io.fdi.lpStateReq := port.lpStateReq
    adapter.io.fdi.lpRxActiveSts := port.lpRxActiveSts
    adapter.io.fdi.lpLinkError := port.lpLinkError
    adapter.io.fdi.lpIrdy := port.lpIrdy
    adapter.io.fdi.lpValid := port.lpValid
    adapter.io.fdi.lpData := port.lpData
    adapter.io.fdi.lpStallAck := port.lpStallAck
    adapter.io.fdi.lpWakeReq := false.B
    adapter.io.fdi.lpCfg := 0.U
    adapter.io.fdi.lpCfgVld := false.B
    adapter.io.fdi.lpClkAck := false.B
    adapter.io.fdi.plCfgCrd := false.B

    port.plStateSts := adapter.io.fdi.plStateSts
    port.plInbandPres := adapter.io.fdi.plInbandPres
    port.plRxActiveReq := adapter.io.fdi.plRxActiveReq
    port.plStallReq := adapter.io.fdi.plStallReq
    port.plTrdy := adapter.io.fdi.plTrdy
    port.plValid := adapter.io.fdi.plValid
    port.plData := adapter.io.fdi.plData
    port.plProtocolVld := adapter.io.fdi.plProtocolVld

    // Physical (RDI) side model: status comes from the test, the PHY always
    // accepts TX beats, and no errors are injected.
    adapter.io.rdi.plStateSts := port.phyState
    adapter.io.rdi.plInbandPres := port.phyInbandPres
    adapter.io.rdi.plStallReq := port.phyStallReq
    adapter.io.rdi.plTrdy := true.B
    adapter.io.rdi.plError := false.B
    adapter.io.rdi.plCError := false.B
    adapter.io.rdi.plNfError := false.B
    adapter.io.rdi.plTrainError := false.B
    adapter.io.rdi.plPhyInRecenter := false.B
    adapter.io.rdi.plSpeedmode := SpeedMode.speed4
    adapter.io.rdi.plMaxSpeedmode := false.B
    adapter.io.rdi.plLnkCfg := LinkWidth.x16
    adapter.io.rdi.plClkReq := true.B
    adapter.io.rdi.plWakeAck := false.B

    // Register-block inputs: the notification enables that gate FDI error
    // reporting. These tests do not exercise error reporting, so leave them off.
    adapter.io.regs.corrProtoReport := false.B
    adapter.io.regs.nonFatalProtoReport := false.B
    adapter.io.regs.fatalProtoReport := false.B

    port.rdiLpStateReq := adapter.io.rdi.lpStateReq
    port.rdiLpStallAck := adapter.io.rdi.lpStallAck
  }

  hookup(dieA, io.a)
  hookup(dieB, io.b)

  // D2D link, sideband: each die's config transmit side feeds the partner's
  // receive side, with credits returned the other way.
  dieB.io.rdi.plCfg := dieA.io.rdi.lpCfg
  dieB.io.rdi.plCfgVld := dieA.io.rdi.lpCfgVld
  dieA.io.rdi.plCfgCrd := dieB.io.rdi.lpCfgCrd
  dieA.io.rdi.plCfg := dieB.io.rdi.lpCfg
  dieA.io.rdi.plCfgVld := dieB.io.rdi.lpCfgVld
  dieB.io.rdi.plCfgCrd := dieA.io.rdi.lpCfgCrd

  // D2D link, mainband: one die's TX beat becomes the partner's RX beat.
  dieB.io.rdi.plValid := dieA.io.rdi.lpValid
  dieB.io.rdi.plData := dieA.io.rdi.lpData
  dieA.io.rdi.plValid := dieB.io.rdi.lpValid
  dieA.io.rdi.plData := dieB.io.rdi.lpData
}

class D2DAdapterTest extends AnyFunSpec with ChiselSim {
  private val fdiParams = FdiParams(nBytes = 64, ncWidth = 32)
  private val rdiParams = RdiParams(nBytes = 64, ncWidth = 32)
  private val sbParams = SidebandParams()

  private def harness =
    new D2DAdapterPairHarness(fdiParams, rdiParams, sbParams)

  private def initDie(p: D2DAdapterDiePort): Unit = {
    p.lpStateReq.poke(FDIStateReq.nop)
    p.lpRxActiveSts.poke(false.B)
    p.lpLinkError.poke(false.B)
    p.lpIrdy.poke(false.B)
    p.lpValid.poke(false.B)
    p.lpData.poke(0.U)
    p.lpStallAck.poke(false.B)
    p.phyState.poke(RDIState.reset)
    p.phyInbandPres.poke(false.B)
    p.phyStallReq.poke(false.B)
  }

  private def waitUntil(c: D2DAdapterPairHarness, maxCycles: Int, what: String)(
      cond: => Boolean
  ): Unit = {
    var guard = 0
    while (!cond && guard < maxCycles) {
      c.clock.step()
      guard += 1
    }
    assert(guard < maxCycles, s"timed out waiting for $what")
  }

  /** Bring both dies from reset up to Active, driving only the protocol and
    * physical-layer interfaces.
    */
  private def bringUpLink(c: D2DAdapterPairHarness): Unit = {
    initDie(c.io.a)
    initDie(c.io.b)
    c.clock.step(4)

    // Physical layers report presence and reach Active on their own.
    for (p <- Seq(c.io.a, c.io.b)) {
      p.phyInbandPres.poke(true.B)
      p.phyState.poke(RDIState.active)
    }

    // The adapters exchange capabilities and signal inband presence upward.
    waitUntil(c, 400, "both dies to signal inband presence") {
      c.io.a.plInbandPres.peek().litToBoolean && c.io.b.plInbandPres
        .peek()
        .litToBoolean
    }

    // Now the protocol layers request Active and bring RX up.
    for (p <- Seq(c.io.a, c.io.b)) {
      p.lpStateReq.poke(FDIStateReq.active)
      p.lpRxActiveSts.poke(true.B)
    }

    waitUntil(c, 400, "both dies to reach Active") {
      c.io.a.plStateSts.peek().litValue == FDIState.active.litValue &&
      c.io.b.plStateSts.peek().litValue == FDIState.active.litValue
    }
  }

  describe("D2DAdapter pair") {
    it("Brought the link up to Active from both sides") {
      simulate(harness) { c =>
        bringUpLink(c)

        for (p <- Seq(c.io.a, c.io.b)) {
          p.plInbandPres.expect(true.B)
          p.plRxActiveReq.expect(true.B)
          p.plProtocolVld.expect(true.B)
          p.rdiLpStateReq.expect(RDIStateReq.active)
        }
      }
    }

    it("Carried a protocol flit from one die to the other") {
      simulate(harness) { c =>
        bringUpLink(c)

        // Die A's protocol layer pushes a beat; the adapter buffers it, sends
        // it over the mainband link, and die B's adapter presents it upward.
        val payload = BigInt("0123456789abcdef", 16)
        waitUntil(c, 40, "die A to accept a protocol beat") {
          c.io.a.plTrdy.peek().litToBoolean
        }
        c.io.a.lpData.poke(payload.U)
        c.io.a.lpValid.poke(true.B)
        c.io.a.lpIrdy.poke(true.B)
        c.clock.step()
        c.io.a.lpValid.poke(false.B)
        c.io.a.lpIrdy.poke(false.B)

        waitUntil(c, 60, "die B to present the flit to its protocol layer") {
          c.io.b.plValid.peek().litToBoolean
        }
        c.io.b.plData.expect(payload.U)
      }
    }

    it("Ran the physical-layer stall handshake through the mainband") {
      simulate(harness) { c =>
        bringUpLink(c)

        // The physical layer of die A requests a stall; the adapter must drain
        // the mainband before acknowledging on RDI.
        c.io.a.rdiLpStallAck.expect(false.B)
        c.io.a.phyStallReq.poke(true.B)
        waitUntil(c, 40, "die A to acknowledge the physical-layer stall") {
          c.io.a.rdiLpStallAck.peek().litToBoolean
        }
        // While stalled the protocol side is back-pressured.
        c.io.a.plTrdy.expect(false.B)

        // Releasing the request completes the 4-phase handshake.
        c.io.a.phyStallReq.poke(false.B)
        waitUntil(c, 40, "die A to release the stall acknowledgement") {
          !c.io.a.rdiLpStallAck.peek().litToBoolean
        }
        waitUntil(c, 40, "die A to accept protocol beats again") {
          c.io.a.plTrdy.peek().litToBoolean
        }
      }
    }

    it("Stalled the protocol layer and exited Active on a link reset") {
      simulate(harness) { c =>
        bringUpLink(c)

        // Die A's protocol layer requests a link reset. The adapter must first
        // stall the protocol layer over FDI.
        c.io.a.lpStateReq.poke(FDIStateReq.linkReset)
        waitUntil(c, 200, "die A to stall its protocol layer") {
          c.io.a.plStallReq.peek().litToBoolean
        }

        // The protocol layer acknowledges the stall and drops RX, which lets
        // both dies leave Active for linkReset.
        for (p <- Seq(c.io.a, c.io.b)) {
          p.lpStallAck.poke(true.B)
          p.lpRxActiveSts.poke(false.B)
        }

        waitUntil(c, 400, "both dies to reach linkReset") {
          c.io.a.plStateSts.peek().litValue == FDIState.linkReset.litValue &&
          c.io.b.plStateSts.peek().litValue == FDIState.linkReset.litValue
        }
        // Inband presence is registered off the state, so let it settle.
        c.clock.step(2)
        for (p <- Seq(c.io.a, c.io.b)) p.plInbandPres.expect(false.B)
      }
    }

    it("Reported a protocol link error to the physical layer") {
      simulate(harness) { c =>
        bringUpLink(c)

        // lp_linkerror is forwarded from FDI to RDI, and the physical layer
        // reporting linkError takes the adapter out of Active.
        c.io.a.lpLinkError.poke(true.B)
        c.io.a.phyState.poke(RDIState.linkError)
        waitUntil(c, 200, "die A to enter linkError") {
          c.io.a.plStateSts.peek().litValue == FDIState.linkError.litValue
        }
        c.io.a.plInbandPres.expect(false.B)
        c.io.a.plRxActiveReq.expect(false.B)
      }
    }
  }
}
