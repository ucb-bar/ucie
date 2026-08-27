/*
  Description:
    Tests for D2DSidebandModule, the adapter's bridge onto the sideband
    network. It owns a D2DSidebandChannel (FDI node + RDI node + D2D switch) and
    translates between the adapter's compact link-management opcodes and the
    128-bit UCIe sideband messages.

    The harness cross-connects two modules over their RDI (physical-side) config
    ports, which is where remote traffic leaves and enters the die. A message
    encoded by one module therefore travels through its channel, over the link,
    and back up through the partner's channel to be decoded again -- covering
    both the encode and the decode path of every opcode.
 */
package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams
import org.scalatest.funspec.AnyFunSpec

class D2DSidebandPairHarness(fdiParams: FdiParams, sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val aSnt = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
    val bSnt = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
    val aRcv = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
    val bRcv = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
    val aRdy = Output(Bool())
    val bRdy = Output(Bool())
    // FDI-side protocol config port of die A, so the FDI path can be exercised.
    val aFdiLpCfg = Input(UInt(fdiParams.ncWidth.W))
    val aFdiLpCfgVld = Input(Bool())
    val aFdiPlCfg = Output(UInt(fdiParams.ncWidth.W))
    val aFdiPlCfgVld = Output(Bool())
    val aFdiLpCfgCrd = Output(Bool())
  })

  private val dieA = Module(new D2DSidebandModule(fdiParams, sbParams))
  private val dieB = Module(new D2DSidebandModule(fdiParams, sbParams))

  dieA.io.sb.snt := io.aSnt
  dieB.io.sb.snt := io.bSnt
  io.aRcv := dieA.io.sb.rcv
  io.bRcv := dieB.io.sb.rcv
  io.aRdy := dieA.io.sb.rdy
  io.bRdy := dieB.io.sb.rdy

  // D2D link over the RDI config ports: each die's transmit side feeds the
  // other's receive side, and credits are returned the other way.
  dieB.io.rdi.plCfg := dieA.io.rdi.lpCfg
  dieB.io.rdi.plCfgVld := dieA.io.rdi.lpCfgVld
  dieA.io.rdi.plCfgCrd := dieB.io.rdi.lpCfgCrd
  dieA.io.rdi.plCfg := dieB.io.rdi.lpCfg
  dieA.io.rdi.plCfgVld := dieB.io.rdi.lpCfgVld
  dieB.io.rdi.plCfgCrd := dieA.io.rdi.lpCfgCrd

  // Die A's FDI (protocol) port is exposed; die B's is idle.
  dieA.io.fdi.lpCfg := io.aFdiLpCfg
  dieA.io.fdi.lpCfgVld := io.aFdiLpCfgVld
  dieA.io.fdi.plCfgCrd := false.B
  io.aFdiPlCfg := dieA.io.fdi.plCfg
  io.aFdiPlCfgVld := dieA.io.fdi.plCfgVld
  io.aFdiLpCfgCrd := dieA.io.fdi.lpCfgCrd

  dieB.io.fdi.lpCfg := 0.U
  dieB.io.fdi.lpCfgVld := false.B
  dieB.io.fdi.plCfgCrd := false.B
}

class D2DSidebandModuleTest extends AnyFunSpec with ChiselSim {
  private val fdiParams = FdiParams(nBytes = 64, ncWidth = 32)
  private val sbParams = SidebandParams()

  private def harness = new D2DSidebandPairHarness(fdiParams, sbParams)

  /** Every link-management opcode the module can encode and decode. */
  private val allOpcodes = Seq(
    "REQ_ACTIVE" -> SideBandMessage.REQ_ACTIVE,
    "REQ_L1" -> SideBandMessage.REQ_L1,
    "REQ_L2" -> SideBandMessage.REQ_L2,
    "REQ_LINKRESET" -> SideBandMessage.REQ_LINKRESET,
    "REQ_DISABLED" -> SideBandMessage.REQ_DISABLED,
    "RSP_ACTIVE" -> SideBandMessage.RSP_ACTIVE,
    "RSP_PMNAK" -> SideBandMessage.RSP_PMNAK,
    "RSP_L1" -> SideBandMessage.RSP_L1,
    "RSP_L2" -> SideBandMessage.RSP_L2,
    "RSP_LINKRESET" -> SideBandMessage.RSP_LINKRESET,
    "RSP_DISABLED" -> SideBandMessage.RSP_DISABLED,
    "ADV_CAP" -> SideBandMessage.ADV_CAP
  )

  private def initIdle(c: D2DSidebandPairHarness): Unit = {
    c.io.aSnt.poke(SideBandMessage.NOP)
    c.io.bSnt.poke(SideBandMessage.NOP)
    c.io.aFdiLpCfg.poke(0.U)
    c.io.aFdiLpCfgVld.poke(false.B)
  }

  /** Send one opcode from die A and wait for die B to decode it. */
  private def sendAtoB(
      c: D2DSidebandPairHarness,
      name: String,
      op: UInt
  ): Unit = {
    c.io.aSnt.poke(op)

    // Hold the request until the channel accepts it.
    var guard = 0
    while (!c.io.aRdy.peek().litToBoolean && guard < 60) {
      c.clock.step()
      guard += 1
    }
    assert(
      guard < 60,
      s"$name: the sideband channel never accepted the message"
    )
    c.clock.step()
    c.io.aSnt.poke(SideBandMessage.NOP)

    // The decoded opcode appears for a single cycle as the message is consumed.
    var seen = false
    guard = 0
    while (!seen && guard < 120) {
      seen = c.io.bRcv.peek().litValue == op.litValue
      c.clock.step()
      guard += 1
    }
    assert(seen, s"$name: die B never decoded the message")
  }

  describe("D2DSidebandModule") {
    it("Encoded and decoded every link-management opcode over the link") {
      simulate(harness) { c =>
        initIdle(c)
        c.clock.step(4)
        c.io.aRcv.expect(SideBandMessage.NOP)
        c.io.bRcv.expect(SideBandMessage.NOP)

        for ((name, op) <- allOpcodes) {
          sendAtoB(c, name, op)
        }
      }
    }

    it("Carried messages in the reverse direction as well") {
      simulate(harness) { c =>
        initIdle(c)
        c.clock.step(4)

        // Same path, driven from die B: proves the harness link and the
        // module's decode path are symmetric.
        c.io.bSnt.poke(SideBandMessage.REQ_ACTIVE)
        var guard = 0
        while (!c.io.bRdy.peek().litToBoolean && guard < 60) {
          c.clock.step()
          guard += 1
        }
        assert(
          guard < 60,
          "the sideband channel never accepted die B's message"
        )
        c.clock.step()
        c.io.bSnt.poke(SideBandMessage.NOP)

        var seen = false
        guard = 0
        while (!seen && guard < 120) {
          seen =
            c.io.aRcv.peek().litValue == SideBandMessage.REQ_ACTIVE.litValue
          c.clock.step()
          guard += 1
        }
        assert(seen, "die A never decoded the message")
      }
    }

    it("Kept the channel idle while no opcode was requested") {
      simulate(harness) { c =>
        initIdle(c)
        // With snt held at NOP nothing is offered to the channel and nothing
        // is decoded on either side.
        for (_ <- 0 until 20) {
          c.io.aRdy.expect(false.B)
          c.io.bRdy.expect(false.B)
          c.io.aRcv.expect(SideBandMessage.NOP)
          c.io.bRcv.expect(SideBandMessage.NOP)
          c.clock.step()
        }
      }
    }

    it("Ran a request/response exchange in both directions") {
      simulate(harness) { c =>
        initIdle(c)
        c.clock.step(4)

        // Die A requests Active, die B answers: the pattern every adapter
        // state transition uses.
        sendAtoB(c, "REQ_ACTIVE", SideBandMessage.REQ_ACTIVE)

        c.io.bSnt.poke(SideBandMessage.RSP_ACTIVE)
        var guard = 0
        while (!c.io.bRdy.peek().litToBoolean && guard < 60) {
          c.clock.step()
          guard += 1
        }
        assert(guard < 60, "die B could not send the response")
        c.clock.step()
        c.io.bSnt.poke(SideBandMessage.NOP)

        var seen = false
        guard = 0
        while (!seen && guard < 120) {
          seen =
            c.io.aRcv.peek().litValue == SideBandMessage.RSP_ACTIVE.litValue
          c.clock.step()
          guard += 1
        }
        assert(seen, "die A never saw the response")
      }
    }

    it("Ignored an undefined link-management opcode") {
      simulate(harness) { c =>
        initIdle(c)
        c.clock.step(4)

        // Only the twelve defined opcodes (and NOP) have an encoding. A value
        // outside that set must not be turned into a sideband message: the
        // module leaves the request unaccepted and the partner sees nothing.
        c.io.aSnt.poke("b111111".U)
        for (_ <- 0 until 20) {
          c.io.aRdy.expect(false.B)
          c.io.bRcv.expect(SideBandMessage.NOP)
          c.clock.step()
        }
        c.io.aSnt.poke(SideBandMessage.NOP)

        // A valid opcode still works afterwards, so nothing was left wedged.
        sendAtoB(c, "REQ_ACTIVE", SideBandMessage.REQ_ACTIVE)
      }
    }

    it("Kept D2D link traffic off the protocol-facing FDI port") {
      simulate(harness) { c =>
        initIdle(c)
        c.clock.step(4)

        // A link-management message is addressed to the partner D2D layer, so
        // the switch must route it out on RDI only: die A's protocol-facing
        // FDI output must stay quiet for the whole exchange.
        c.io.aSnt.poke(SideBandMessage.REQ_ACTIVE)
        var guard = 0
        while (!c.io.aRdy.peek().litToBoolean && guard < 60) {
          c.io.aFdiPlCfgVld.expect(false.B)
          c.clock.step()
          guard += 1
        }
        assert(guard < 60, "the sideband channel never accepted the message")
        c.clock.step()
        c.io.aSnt.poke(SideBandMessage.NOP)

        var seen = false
        guard = 0
        while (!seen && guard < 120) {
          c.io.aFdiPlCfgVld.expect(false.B)
          seen =
            c.io.bRcv.peek().litValue == SideBandMessage.REQ_ACTIVE.litValue
          c.clock.step()
          guard += 1
        }
        assert(seen, "die B never decoded the message")
      }
    }
  }
}
