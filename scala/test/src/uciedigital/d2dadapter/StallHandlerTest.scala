/*
  Description:
    Unit tests for the two D2D adapter stall handlers. Both implement the UCIe
    4-phase stall handshake but from opposite sides:
      - FDIStallHandler is the requester towards the protocol layer: the link
        manager asks for a stall, the handler drives pl_stallreq and waits for
        lp_stallack from the protocol layer.
      - RDIStallHandler is the responder towards the physical layer: logphy
        drives pl_stallreq, the handler asks the mainband to drain, and only
        acknowledges with lp_stallack once draining completed.
    Each test walks the FSM through IDLE -> WAIT_ACK_ASSERT -> STALLED ->
    WAIT_ACK_DEASSERT -> IDLE and the early-exit paths.
 */
package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class FDIStallHandlerTest extends AnyFunSpec with ChiselSim {
  describe("FDIStallHandler") {
    it("Ran the full 4-phase handshake and returned to idle") {
      simulate(new FDIStallHandler()) { c =>
        c.io.linkStallReq.poke(false.B)
        c.io.lpStallAck.poke(false.B)
        c.clock.step()
        c.io.plStallReq.expect(false.B)
        c.io.linkStallDone.expect(false.B)

        // IDLE -> WAIT_ACK_ASSERT: the link manager requests a stall.
        c.io.linkStallReq.poke(true.B)
        c.clock.step()
        c.io.plStallReq.expect(true.B)
        c.io.linkStallDone.expect(false.B) // no ack yet

        // Protocol layer acks: done pulses in the same cycle the ack is seen.
        c.io.lpStallAck.poke(true.B)
        c.io.linkStallDone.expect(true.B)
        c.clock.step()

        // WAIT_ACK_ASSERT -> STALLED: request still held, so the protocol
        // side stays stalled and done stays asserted.
        c.io.plStallReq.expect(true.B)
        c.io.linkStallDone.expect(true.B)

        // STALLED -> WAIT_ACK_DEASSERT: link manager drops the request.
        c.io.linkStallReq.poke(false.B)
        c.clock.step()
        c.io.plStallReq.expect(false.B)
        c.io.linkStallDone.expect(false.B)

        // WAIT_ACK_DEASSERT -> IDLE once the ack drops.
        c.io.lpStallAck.poke(false.B)
        c.clock.step()
        c.io.plStallReq.expect(false.B)

        // Back in IDLE: a new request starts the handshake again.
        c.io.linkStallReq.poke(true.B)
        c.clock.step()
        c.io.plStallReq.expect(true.B)
      }
    }

    it("Held in WAIT_ACK_DEASSERT while the protocol ack stayed asserted") {
      simulate(new FDIStallHandler()) { c =>
        c.io.linkStallReq.poke(true.B)
        c.io.lpStallAck.poke(false.B)
        c.clock.step()
        c.io.lpStallAck.poke(true.B)
        c.clock.step(2) // WAIT_ACK_ASSERT -> STALLED
        c.io.linkStallReq.poke(false.B)
        c.clock.step() // STALLED -> WAIT_ACK_DEASSERT

        // A stuck ack must keep the handler out of IDLE.
        for (_ <- 0 until 4) {
          c.io.plStallReq.expect(false.B)
          c.clock.step()
        }
        // Releasing the ack completes the handshake.
        c.io.lpStallAck.poke(false.B)
        c.clock.step()
        c.io.linkStallReq.poke(true.B)
        c.clock.step()
        c.io.plStallReq.expect(true.B) // proves IDLE was reached
      }
    }

    it("Stayed idle when the protocol ack was already asserted") {
      simulate(new FDIStallHandler()) { c =>
        // Rule: a rising edge on pl_stallreq is only legal while lp_stallack
        // is low, so a request raised on top of a stale ack must be ignored.
        c.io.lpStallAck.poke(true.B)
        c.io.linkStallReq.poke(true.B)
        c.clock.step(3)
        c.io.plStallReq.expect(false.B)
        c.io.linkStallDone.expect(false.B)
      }
    }
  }
}

class RDIStallHandlerTest extends AnyFunSpec with ChiselSim {
  describe("RDIStallHandler") {
    it("Drained the mainband before acknowledging the physical layer") {
      simulate(new RDIStallHandler()) { c =>
        c.io.plStallReq.poke(false.B)
        c.io.mainbandStallDone.poke(false.B)
        c.clock.step()
        c.io.mainbandStallReq.expect(false.B)
        c.io.lpStallAck.expect(false.B)

        // IDLE -> WAIT_ACK_ASSERT: logphy requests the stall, so the handler
        // asks the mainband to drain but must not ack yet.
        c.io.plStallReq.poke(true.B)
        c.clock.step()
        c.io.mainbandStallReq.expect(true.B)
        c.io.lpStallAck.expect(false.B)

        // Mainband still busy: no ack.
        c.clock.step(2)
        c.io.lpStallAck.expect(false.B)

        // WAIT_ACK_ASSERT -> STALLED once draining completed.
        c.io.mainbandStallDone.poke(true.B)
        c.clock.step()
        c.io.mainbandStallReq.expect(true.B)
        c.io.lpStallAck.expect(true.B)

        // STALLED -> WAIT_ACK_DEASSERT -> IDLE after the request drops.
        c.io.plStallReq.poke(false.B)
        c.clock.step()
        c.io.lpStallAck.expect(false.B)
        c.io.mainbandStallReq.expect(false.B)
        c.clock.step() // WAIT_ACK_DEASSERT -> IDLE (unconditional)

        // Proven back in IDLE: a new request restarts the handshake.
        c.io.plStallReq.poke(true.B)
        c.clock.step()
        c.io.mainbandStallReq.expect(true.B)
        c.io.lpStallAck.expect(false.B)
      }
    }

    it("Held the stall while the physical layer kept requesting") {
      simulate(new RDIStallHandler()) { c =>
        c.io.plStallReq.poke(true.B)
        c.io.mainbandStallDone.poke(true.B)
        c.clock.step(2) // IDLE -> WAIT_ACK_ASSERT -> STALLED

        // The ack must stay asserted as long as the request is held.
        for (_ <- 0 until 5) {
          c.io.lpStallAck.expect(true.B)
          c.io.mainbandStallReq.expect(true.B)
          c.clock.step()
        }
      }
    }
  }
}
