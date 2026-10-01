package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy

/** What each reset register is for, shown by what goes wrong without it.
  *
  * The PHY has three resets a direction, and which one a piece of state hangs
  * off is a design decision rather than an accident: the dividers come off the
  * main reset so their phase against each other is fixed, the datapath comes
  * off a reset synchronized to the divided clock so it can be flushed while
  * that clock keeps running, and the test FSM comes off a third so a datapath
  * flush does not throw away the measurement being taken.
  *
  * Those distinctions are invisible in a test that only ever asserts all of
  * them together, which is what every other spec here does. Each case below
  * drives one reset and checks that state belonging to another is untouched --
  * so collapsing two of these registers into one, or hanging state off the
  * wrong one, fails here rather than in a fifty minute analog run.
  */
class ResetSchemeSpec extends AnyFunSpec with ChiselSim {
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  val numLanes = 4
  val wordMask = (BigInt(1) << Phy.SerdesRatio) - 1
  val alignedValid = wordMask
  val trackPattern = BigInt("55555555", 16)

  /** `rxSignature` and `rxPacketsReceived` sit two registers behind the RX
    * datapath, plus a cycle of slack.
    */
  val outputDelay = 3

  def packet(seed: Int): RxPacket = RxPacket(
    data = (0 until numLanes).map(lane =>
      (BigInt(seed) * BigInt("9e3779b9", 16) + BigInt(lane)) & wordMask
    ),
    valid = alignedValid,
    track = trackPattern
  )

  /** Mainband under PhyTest with both directions out of reset. */
  def setup(c: PhyTest): Unit = {
    c.io.regs.testTarget.poke(TestTarget.mainband)
    c.io.regs.mainbandMode.poke(BandMode.manual)
    c.io.regs.txValidLaneSel.poke(Phy.defaultValidLaneSel(numLanes).U)
    c.io.regs.rxValidLaneSel.poke(Phy.defaultValidLaneSel(numLanes).U)
    c.io.regs.rxDataMode.poke(DataMode.infinite)
    c.io.regs.rxPauseCounters.poke(false.B)
    c.io.regs.txExecute.poke(false.B)
    c.io.regs.txDataChunkIn.valid.poke(false.B)
    c.io.regs.rxDatapathRst.poke(false.B)
    c.io.regs.rxFsmRst.poke(false.B)
    c.io.regs.txDatapathRst.poke(false.B)
    c.io.regs.txFsmRst.poke(false.B)
    c.io.rx.valid.poke(false.B)
    c.clock.step()
  }

  def pulse(c: PhyTest, reg: Bool): Unit = {
    reg.poke(true.B)
    c.clock.step()
    reg.poke(false.B)
    c.clock.step(outputDelay)
  }

  /** Drives one packet per cycle. The RX accepts unconditionally. */
  def drive(c: PhyTest, packets: Seq[RxPacket]): Unit = {
    for (p <- packets) {
      for (lane <- 0 until numLanes) {
        c.io.rx.bits.data(lane).poke(p.data(lane).U)
      }
      c.io.rx.bits.valid.poke(p.valid.U)
      c.io.rx.bits.track.poke(p.track.U)
      c.io.rx.valid.poke(true.B)
      c.clock.step()
    }
    c.io.rx.valid.poke(false.B)
    c.clock.step(outputDelay)
  }

  def signature(c: PhyTest): BigInt = c.io.regs.rxSignature.peek().litValue
  def packetsReceived(c: PhyTest): BigInt =
    c.io.regs.rxPacketsReceived.peek().litValue

  describe("the RX reset split") {
    it(
      "should keep the measurement across a datapath reset and drop it on an FSM reset"
    ) {
      simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
        setup(c)
        pulse(c, c.io.regs.rxFsmRst)
        assert(signature(c) == 0, "the FSM reset should have cleared it")

        drive(c, (1 to 4).map(packet))
        val measured = signature(c)
        val counted = packetsReceived(c)
        assert(counted == 4, s"expected 4 packets, counted $counted")
        assert(measured != 0, "four packets should have folded into something")

        // `rxDatapathRst` flushes the deserializer handoff and the PHY side of
        // the async queue. It must not touch the counters: a run that had to
        // flush the datapath mid-burst would otherwise silently lose the
        // measurement it was taking, and read as a clean zero rather than as
        // an interrupted run.
        pulse(c, c.io.regs.rxDatapathRst)
        assert(
          signature(c) == measured,
          s"a datapath reset cleared the signature ($measured -> ${signature(c)}); " +
            "it belongs to the FSM, so this is the two registers collapsed into one"
        )
        assert(
          packetsReceived(c) == counted,
          s"a datapath reset cleared the packet count ($counted -> ${packetsReceived(c)})"
        )

        // `rxFsmRst` is what ends a measurement.
        pulse(c, c.io.regs.rxFsmRst)
        assert(
          signature(c) == 0,
          "the FSM reset did not clear the signature, so nothing can start a fresh run"
        )
        assert(packetsReceived(c) == 0, "the FSM reset did not clear the count")
      }
    }

    it("should hold the RX FSM while TileLink owns the mainband") {
      simulate(new PhyTest(numLanes = numLanes)(true)) { c =>
        setup(c)
        pulse(c, c.io.regs.rxFsmRst)
        drive(c, (1 to 3).map(packet))
        assert(packetsReceived(c) == 3)

        // `rxReset` carries `!mbManual`, so handing the band to TileLink holds
        // the FSM rather than leaving it counting frames it does not own. The
        // hold is what guarantees the band comes back at a defined starting
        // point with no software step in between.
        c.io.regs.mainbandMode.poke(BandMode.tl)
        c.clock.step(outputDelay)
        assert(
          packetsReceived(c) == 0,
          "the RX FSM kept its count with the band handed to TileLink, so it is " +
            "still counting traffic it does not own"
        )

        c.io.regs.mainbandMode.poke(BandMode.manual)
        c.clock.step(outputDelay)
        assert(
          packetsReceived(c) == 0,
          "the band came back mid-count rather than from a defined start"
        )
      }
    }
  }
}
