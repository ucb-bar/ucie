/*
  Description:
    Unit tests for D2DMainbandModule, the adapter's mainband datapath. It holds
    a one-beat TX buffer (protocol -> physical) and a one-beat RX buffer
    (physical -> protocol), plus the TX stall FSM that drains the buffer when
    logphy requests a stall.
 */
package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams
import org.scalatest.funspec.AnyFunSpec

class D2DMainbandModuleTest extends AnyFunSpec with ChiselSim {
  private val fdiParams = FdiParams(nBytes = 64, ncWidth = 32)
  private val rdiParams = RdiParams(nBytes = 64, ncWidth = 32)
  private val sbParams = SidebandParams()

  private def dut = new D2DMainbandModule(fdiParams, rdiParams, sbParams)

  /** Idle inputs with the link up, RX enabled and no stall pending. */
  private def initActive(c: D2DMainbandModule): Unit = {
    c.io.state.d2dState.poke(RDIState.active)
    c.io.state.rxActiveReq.poke(true.B)
    c.io.state.rxActiveSts.poke(true.B)
    c.io.state.mainbandStallReq.poke(false.B)
    c.io.fdi.lpIrdy.poke(false.B)
    c.io.fdi.lpValid.poke(false.B)
    c.io.fdi.lpData.poke(0.U)
    c.io.rdi.plTrdy.poke(false.B)
    c.io.rdi.plValid.poke(false.B)
    c.io.rdi.plData.poke(0.U)
  }

  private def driveFdiBeat(c: D2DMainbandModule, data: BigInt): Unit = {
    c.io.fdi.lpData.poke(data.U)
    c.io.fdi.lpValid.poke(true.B)
    c.io.fdi.lpIrdy.poke(true.B)
  }

  private def stopFdi(c: D2DMainbandModule): Unit = {
    c.io.fdi.lpValid.poke(false.B)
    c.io.fdi.lpIrdy.poke(false.B)
  }

  describe("D2DMainbandModule TX path") {
    it("Buffered a protocol beat and presented it on RDI") {
      simulate(dut) { c =>
        initActive(c)
        c.clock.step()
        c.io.fdi.plTrdy.expect(true.B) // buffer empty, ready for a beat
        c.io.rdi.lpValid.expect(false.B)

        driveFdiBeat(c, BigInt("a5a5a5a5", 16))
        c.clock.step()
        stopFdi(c)

        // The beat is now buffered and offered to the physical layer.
        c.io.rdi.lpValid.expect(true.B)
        c.io.rdi.lpIrdy.expect(true.B)
        c.io.rdi.lpData.expect(BigInt("a5a5a5a5", 16).U)
        // Buffer full and no drain yet: back-pressure the protocol layer.
        c.io.fdi.plTrdy.expect(false.B)

        // The physical layer takes it; the buffer empties.
        c.io.rdi.plTrdy.poke(true.B)
        c.clock.step()
        c.io.rdi.plValid.poke(false.B)
        c.io.rdi.lpValid.expect(false.B)
        c.io.fdi.plTrdy.expect(true.B)
      }
    }

    it("Accepted a new beat in the same cycle the previous one drained") {
      simulate(dut) { c =>
        initActive(c)
        c.clock.step()

        driveFdiBeat(c, 0x11.U.litValue)
        c.clock.step()
        c.io.rdi.lpData.expect(0x11.U)

        // With plTrdy high the buffer drains, so plTrdy stays high and a
        // second beat can be accepted back-to-back.
        c.io.rdi.plTrdy.poke(true.B)
        c.io.fdi.plTrdy.expect(true.B)
        driveFdiBeat(c, 0x22.U.litValue)
        c.clock.step()
        c.io.rdi.lpData.expect(0x22.U)
        stopFdi(c)
        c.clock.step()
        c.io.rdi.lpValid.expect(false.B)
      }
    }

    it("Drained the buffer and stalled the TX path on a stall request") {
      simulate(dut) { c =>
        initActive(c)
        c.clock.step()

        // Load a beat, then request a stall while it is still buffered.
        driveFdiBeat(c, 0x33.U.litValue)
        c.clock.step()
        stopFdi(c)
        c.io.rdi.lpValid.expect(true.B)

        c.io.state.mainbandStallReq.poke(true.B)
        // running -> draining: ingress blocked, but the pending beat may go out.
        c.io.fdi.plTrdy.expect(false.B)
        c.io.state.mainbandStallDone.expect(false.B)
        c.io.rdi.lpValid.expect(true.B)

        c.clock.step()
        // draining: still holding the beat because plTrdy is low.
        c.io.state.mainbandStallDone.expect(false.B)

        // Let the beat out; draining completes -> stalled.
        c.io.rdi.plTrdy.poke(true.B)
        c.clock.step()
        c.io.rdi.plTrdy.poke(false.B)
        c.io.state.mainbandStallDone.expect(true.B)
        // stalled: the TX path is gated even though the buffer is empty.
        c.io.rdi.lpValid.expect(false.B)
        c.io.rdi.lpIrdy.expect(false.B)
        c.io.fdi.plTrdy.expect(false.B)

        // Releasing the request returns to running.
        c.io.state.mainbandStallReq.poke(false.B)
        c.clock.step()
        c.io.state.mainbandStallDone.expect(false.B)
        c.io.fdi.plTrdy.expect(true.B)
      }
    }

    it("Stayed in draining while the buffer could not be drained") {
      simulate(dut) { c =>
        initActive(c)
        c.clock.step()

        // Load a beat and request a stall while the physical layer refuses to
        // take it: the FSM must sit in draining (neither completing the drain
        // nor returning to running) for as long as that lasts.
        driveFdiBeat(c, 0x66.U.litValue)
        c.clock.step()
        stopFdi(c)
        c.io.state.mainbandStallReq.poke(true.B)
        c.clock.step() // running -> draining

        for (_ <- 0 until 4) {
          c.io.state.mainbandStallDone.expect(false.B) // still draining
          c.io.rdi.lpValid.expect(true.B) // beat still pending
          c.io.fdi.plTrdy.expect(false.B) // ingress blocked
          c.clock.step()
        }

        // Letting the beat out finally completes the drain.
        c.io.rdi.plTrdy.poke(true.B)
        c.clock.step()
        c.io.rdi.plTrdy.poke(false.B)
        c.io.state.mainbandStallDone.expect(true.B)
      }
    }

    it("Completed the stall immediately when the buffer was already empty") {
      simulate(dut) { c =>
        initActive(c)
        c.clock.step()
        c.io.state.mainbandStallReq.poke(true.B)
        c.clock.step() // running -> draining, buffer empty so drain is done
        c.clock.step() // draining -> stalled
        c.io.state.mainbandStallDone.expect(true.B)
      }
    }

    it("Aborted draining when the stall request was withdrawn early") {
      simulate(dut) { c =>
        initActive(c)
        c.clock.step()

        // Fill the buffer so draining cannot complete while plTrdy is low.
        driveFdiBeat(c, 0x44.U.litValue)
        c.clock.step()
        stopFdi(c)

        c.io.state.mainbandStallReq.poke(true.B)
        c.clock.step() // running -> draining
        c.io.state.mainbandStallDone.expect(false.B)

        // Withdraw before the drain finishes: draining -> running.
        c.io.state.mainbandStallReq.poke(false.B)
        c.clock.step()
        c.io.state.mainbandStallDone.expect(false.B)
        c.io.rdi.lpValid.expect(true.B) // beat still there, TX ungated
      }
    }

    it("Ignored a stall request while the link was not active") {
      simulate(dut) { c =>
        initActive(c)
        c.io.state.d2dState.poke(RDIState.reset)
        c.io.state.mainbandStallReq.poke(true.B)
        c.clock.step(3)
        // txStallRequested is gated by d2dState === active, so the FSM stays
        // in running and never reports done.
        c.io.state.mainbandStallDone.expect(false.B)
        c.io.fdi.plTrdy.expect(true.B)
      }
    }
  }

  describe("D2DMainbandModule RX path") {
    it("Captured a physical beat and presented it to the protocol layer") {
      simulate(dut) { c =>
        initActive(c)
        c.clock.step()
        c.io.fdi.plValid.expect(false.B)

        c.io.rdi.plData.poke(BigInt("dead", 16).U)
        c.io.rdi.plValid.poke(true.B)
        c.clock.step()
        c.io.rdi.plValid.poke(false.B)

        c.io.fdi.plValid.expect(true.B)
        c.io.fdi.plData.expect(BigInt("dead", 16).U)

        // The captured beat is held until rxActiveReq drops.
        c.clock.step(2)
        c.io.fdi.plValid.expect(true.B)
        c.io.state.rxActiveReq.poke(false.B)
        c.clock.step()
        c.io.fdi.plValid.expect(false.B)
      }
    }

    it("Dropped RX beats while capture was disabled") {
      simulate(dut) { c =>
        initActive(c)
        c.clock.step()

        // Case 1: link not active.
        c.io.state.d2dState.poke(RDIState.retrain)
        c.io.rdi.plData.poke(0x55.U)
        c.io.rdi.plValid.poke(true.B)
        c.clock.step()
        c.io.fdi.plValid.expect(false.B)

        // Case 2: active but the protocol side has not acknowledged RX.
        c.io.state.d2dState.poke(RDIState.active)
        c.io.state.rxActiveSts.poke(false.B)
        c.clock.step()
        c.io.fdi.plValid.expect(false.B)

        // Case 3: RX request itself is low.
        c.io.state.rxActiveSts.poke(true.B)
        c.io.state.rxActiveReq.poke(false.B)
        c.clock.step()
        c.io.fdi.plValid.expect(false.B)

        // With all three conditions satisfied the beat lands.
        c.io.state.rxActiveReq.poke(true.B)
        c.clock.step()
        c.io.fdi.plValid.expect(true.B)
        c.io.fdi.plData.expect(0x55.U)
      }
    }
  }
}
