package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy

/** Bursts, clock gating, and the track clock: the TX's packet-by-packet shape,
  * and the RX's handling of it.
  */
class PhyTestBurstSpec extends AnyFunSpec with ChiselSim {
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  import PhyTestRegLoopback._

  val numLanes = 4
  val scoredLanes = 0 to PhyTest.trackLane(numLanes)

  /** One packet as the TX put it on the wire. */
  case class Slot(
      data: Seq[BigInt],
      valid: BigInt,
      track: BigInt,
      clkp: BigInt,
      clkn: BigInt
  ) {
    def isData: Boolean = valid != 0
    def clockOn: Boolean = clkp != 0
  }

  def slot(c: PhyTestRegLoopback): Slot = Slot(
    data = (0 until numLanes).map(c.io.tx.data(_).peek().litValue),
    valid = c.io.tx.valid.peek().litValue,
    track = c.io.tx.track.peek().litValue,
    clkp = c.io.tx.clkp.peek().litValue,
    clkn = c.io.tx.clkn.peek().litValue
  )

  /** Starts the TX and returns the `n` packets it sends from the first one
    * after `txExecute`.
    */
  def record(c: PhyTestRegLoopback, n: Int): Seq[Slot] = {
    strobe(c, c.io.regs.txExecute)
    c.clock.step(txOutputDelay)
    (0 until n).map { _ =>
      val s = slot(c)
      c.clock.step()
      s
    }
  }

  def finiteRun(c: PhyTestRegLoopback, packets: Int): Unit = {
    c.io.regs.txDataMode.poke(DataMode.finite)
    c.io.regs.txPacketsToSend.poke(packets.U)
  }

  def bursts(c: PhyTestRegLoopback, burst: Int, idle: Int): Unit = {
    c.io.regs.txBurstPackets.poke(burst.U)
    c.io.regs.txIdlePackets.poke(idle.U)
  }

  describe("PhyTest TX bursts") {
    it(
      "should send bursts of the continuous stream separated by idle packets"
    ) {
      val packets = 18
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        finiteRun(c, packets)
        val continuous = record(c, packets + 4).filter(_.isData)

        resetFsms(c)
        bursts(c, burst = 4, idle = 3)
        val sent = record(c, packets + 4 * 3 + 4)

        // 4 on, 3 off, ..., with the last burst cut short by the count.
        val expectedShape =
          (0 until packets)
            .grouped(4)
            .toSeq
            .map(_.length)
            .flatMap(n => Seq.fill(n)(true) ++ Seq.fill(3)(false))
        assert(
          sent.map(_.isData).take(expectedShape.length) == expectedShape
        )
        assert(sent.drop(expectedShape.length).forall(!_.isData))
        for (s <- sent.filter(!_.isData)) {
          assert(s.data.forall(_ == 0), "idle packets carry no pattern")
          assert(s.track == 0)
        }
        // The pattern does not advance across a gap.
        assert(continuous.length == packets)
        assert(sent.filter(_.isData) == continuous)
      }
    }

    it("should score a bursting run clean with rxBurstMode, and not without") {
      val packets = 40
      def run(burstMode: Boolean): (Seq[BigInt], BigInt) = {
        var result: (Seq[BigInt], BigInt) = (Seq.empty, 0)
        simulate(new PhyTestRegLoopback(numLanes)) { c =>
          setupLfsr(c)
          finiteRun(c, packets)
          bursts(c, burst = 5, idle = 3)
          c.io.regs.rxBurstMode.poke(burstMode.B)
          strobe(c, c.io.regs.txExecute)
          c.clock.step(packets * 2 + outputDelay)
          result = (errors(c, PhyTest.NominalFraming), packetsReceived(c))
        }
        result
      }
      val (clean, received) = run(burstMode = true)
      assert(received == packets)
      for (lane <- scoredLanes) {
        withClue(s"lane $lane: ") { assert(clean(lane) == 0) }
      }
      // The original RX takes the idle packets for data.
      val (dirty, _) = run(burstMode = false)
      assert(scoredLanes.exists(dirty(_) > 0))
    }
  }

  describe("PhyTest forwarded clock gating") {
    it("should run the clock through each burst, its preamble and postamble") {
      // Two packets and 6 UI of postamble: three forwarded clock cycles into
      // the third packet, which only the 2 UI unit can say.
      val postUi = 2 * Phy.SerdesRatio + 6
      val (pre, postUnits, burst, idle, packets) =
        (2, postUi / PhyTest.ClkPostambleUi, 4, 8, 8)
      val unitsPerPacket = Phy.SerdesRatio / PhyTest.ClkPostambleUi
      val (postFull, postPartUi) = (
        postUnits / unitsPerPacket,
        postUnits % unitsPerPacket * PhyTest.ClkPostambleUi
      )
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        finiteRun(c, packets)
        bursts(c, burst, idle)
        c.io.regs.txClkGate.poke(true.B)
        c.io.regs.txClkPreamble.poke(pre.U)
        c.io.regs.txClkPostamble.poke(postUnits.U)
        c.clock.step(1 + txOutputDelay)
        assert(!slot(c).clockOn, "a gated clock is parked before the run")

        val sent = record(c, 40)
        // (data, UIs the clock runs for) per packet. Bit 0 goes out first,
        // so the part-clocked packet keeps its low bits.
        val (on, partial, off) = (
          (BigInt(1) << Phy.SerdesRatio) - 1,
          (BigInt(1) << postPartUi) - 1,
          BigInt(0)
        )
        val postamble = Seq.fill(postFull)((false, on)) :+ ((false, partial))
        val gap = postamble ++
          Seq.fill(idle - pre - postamble.length)((false, off)) ++
          Seq.fill(pre)((false, on))
        val expected = Seq.fill(pre)((false, on)) ++
          Seq.fill(burst)((true, on)) ++ gap ++
          Seq.fill(burst)((true, on)) ++ postamble
        // Parked low: P low and N high.
        def lanes(isData: Boolean, clocked: BigInt) =
          (isData, clkPWord & clocked, (clkNWord & clocked) | (on ^ clocked))
        val shape = sent.map(s => (s.isData, s.clkp, s.clkn))
        assert(shape.take(expected.length) == expected.map((lanes _).tupled))
        assert(shape.drop(expected.length).forall(_ == lanes(false, off)))
      }
    }

    /** Runs gated bursts into an RX that only sees the UIs the clock ran for,
      * and returns the nominal counts and the packets received.
      */
    def gatedRun(
        postUi: Int,
        packets: Int,
        pre: Int = 2,
        alternate: Boolean = false
    ): (Seq[BigInt], BigInt) = {
      val postUnits = postUi / PhyTest.ClkPostambleUi
      var result: (Seq[BigInt], BigInt) = (Seq.empty, 0)
      simulate(new PhyTestRegLoopback(numLanes, rxNeedsClock = true)) { c =>
        setupLfsr(c)
        finiteRun(c, packets)
        bursts(c, burst = 6, idle = 10)
        c.io.regs.txClkGate.poke(true.B)
        c.io.regs.txClkPreamble.poke(pre.U)
        c.io.regs.txClkPostamble.poke(postUnits.U)
        c.io.regs.txClkParkAlternate.poke(alternate.B)
        c.io.regs.rxBurstMode.poke(true.B)
        // Parked, so nothing more reaches the RX before the run.
        c.clock.step(2)
        val idleWords = c.io.regs.rxIdleWordsObserved.peek().litValue
        c.clock.step(8)
        assert(c.io.regs.rxIdleWordsObserved.peek().litValue == idleWords)
        strobe(c, c.io.regs.txExecute)
        c.clock.step(packets * 3 + outputDelay)
        result = (errors(c, PhyTest.NominalFraming), packetsReceived(c))
      }
      result
    }

    it("should score clean when the RX only sees the UIs the clock ran for") {
      val packets = 24
      // Two whole packets of postamble.
      val (counts, received) = gatedRun(postUi = 2 * Phy.SerdesRatio, packets)
      assert(received == packets)
      for (lane <- scoredLanes) {
        withClue(s"lane $lane: ") { assert(counts(lane) == 0) }
      }
    }

    it("should move the RX's word boundary by a part-packet postamble") {
      // 16 UI past the last whole packet, so after the first gap every RX
      // word straddles two TX packets.
      val (counts, _) =
        gatedRun(postUi = 2 * Phy.SerdesRatio + 16, packets = 24)
      assert((0 until numLanes).forall(counts(_) > 0))
    }

    it("should move the RX's word boundary by as little as one clock cycle") {
      // 2 UI past the last whole packet: the finest postamble step. A valid
      // word shifted 2, 4 or 6 UI is no closer to the waveform than to idle,
      // so `rxBurstMode` drops every burst after the first.
      val (_, received) =
        gatedRun(postUi = 2 * Phy.SerdesRatio + 2, packets = 24)
      assert(received == 6)
    }

    for (alternate <- Seq(false, true)) {
      val parks =
        if (alternate) "parking high and low in turn" else "parked low"
      it(
        s"should score clean with UCIe's gating and a packet of postamble, $parks"
      ) {
        // No preamble. A high park delays the postamble's last edge until
        // just before the next burst, so every gap still has a packet's worth
        // of edges and the RX word boundary does not move.
        val packets = 24
        val (counts, received) =
          gatedRun(postUi = Phy.SerdesRatio, packets, pre = 0, alternate)
        assert(received == packets)
        for (lane <- scoredLanes) {
          withClue(s"lane $lane: ") { assert(counts(lane) == 0) }
        }
      }

      it(s"should gate the clock as UCIe's strobe mode asks, $parks") {
        val postUi = Phy.SerdesRatio
        simulate(new PhyTestRegLoopback(numLanes)) { c =>
          setupLfsr(c)
          bursts(c, burst = 2, idle = 3)
          c.io.regs.txClkGate.poke(true.B)
          c.io.regs.txClkPostamble.poke((postUi / PhyTest.ClkPostambleUi).U)
          c.io.regs.txClkParkAlternate.poke(alternate.B)
          c.clock.step(4)
          // Every packet from here on, across two runs: three bursts, then
          // one more.
          val sent = scala.collection.mutable.ArrayBuffer[Slot]()
          def step(n: Int): Unit = for (_ <- 0 until n) {
            sent += slot(c)
            c.clock.step()
          }
          def run(packets: Int, cycles: Int): Unit = {
            finiteRun(c, packets)
            c.io.regs.txExecute.poke(true.B)
            step(1)
            c.io.regs.txExecute.poke(false.B)
            step(cycles)
          }
          step(3)
          run(packets = 6, cycles = 24)
          c.io.regs.txFsmRst.poke(true.B)
          c.io.regs.rxFsmRst.poke(true.B)
          step(1)
          c.io.regs.txFsmRst.poke(false.B)
          c.io.regs.rxFsmRst.poke(false.B)
          step(4)
          run(packets = 2, cycles = 12)

          // The lanes UI by UI, oldest first.
          def bits(w: BigInt) = (0 until Phy.SerdesRatio).map(w.testBit)
          val p = sent.flatMap(s => bits(s.clkp))
          val n = sent.flatMap(s => bits(s.clkn))
          val data = sent.flatMap(s => Seq.fill(Phy.SerdesRatio)(s.isData))
          assert(p.indices.forall(t => n(t) == !p(t)), "differential")
          // An edge at the start of a UI samples it.
          val edge = p.indices.map(t => t > 0 && p(t) != p(t - 1))
          def edges(from: Int, until: Int) = (from until until).count(edge)

          // Bursts, by their first and last UIs.
          val starts =
            data.indices.filter(t => data(t) && (t == 0 || !data(t - 1)))
          val ends = data.indices.filter(t =>
            data(t) && (t == data.length - 1 || !data(t + 1))
          )
          assert(starts.length == 4)
          assert(data.indices.filter(data).forall(edge), "runs through data")
          // Parked before the first burst, at whatever the gating left.
          var level = p(0)
          assert(edges(0, starts(0)) == (if (level) 1 else 0))
          val gaps = ends.init.zip(starts.tail)
          for (((last, first), i) <- gaps.zipWithIndex) withClue(s"gap $i: ") {
            val postEnd = last + postUi
            // UCIe's parked level alternates; low when it doesn't.
            val high = p(postEnd)
            assert(high == (alternate && !level))
            level = high
            // Clock through the postamble. A high park keeps its last
            // rising edge's level through the UI the postamble ends on.
            assert((last + 1 until postEnd).forall(edge))
            assert(edge(postEnd) == !high)
            // A packet's worth of edges either way.
            assert(edges(last + 1, first) == postUi)
            // Parked for a multiple of 8 UI, low for its last 1 to 8.
            assert((first - 1 - postEnd) % 8 == 0)
            val lowFor = (first - 1 to postEnd by -1).takeWhile(!p(_)).length
            if (high) {
              assert(lowFor >= 1 && lowFor <= 8)
              assert(edges(postEnd + 1, first) == 1)
            } else {
              assert(lowFor == first - postEnd)
              assert(edges(postEnd + 1, first) == 0)
            }
            assert(p(first), "the first data UI's edge is a rising one")
          }
          // And the run ends parked at the next level in turn.
          assert(p.last == (alternate && !level))
        }
      }
    }
  }

  describe("PhyTest track clock") {
    // Not the forwarded clock's pattern, so it cannot be coming from there.
    val trackWord = BigInt("00ff00ff", 16)

    it("should send txTrackWord on track whatever the FSM is doing") {
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.txTrackClk.poke(true.B)
        c.io.regs.txTrackWord.poke(trackWord.U)
        c.clock.step(1 + txOutputDelay)
        assert(slot(c).track == trackWord, "idle")
        val sent = record(c, 8)
        assert(sent.forall(_.isData))
        assert(sent.forall(_.track == trackWord), "running")
        assert(sent.forall(_.clkp == clkPWord))
      }
    }

    it("should still give way to valid moved onto track") {
      simulate(new PhyTestRegLoopback(numLanes)) { c =>
        setupLfsr(c)
        c.io.regs.txTrackClk.poke(true.B)
        c.io.regs.txTrackWord.poke(trackWord.U)
        c.io.regs.txValidLaneSel.poke(Phy.trackValidLaneSel(numLanes).U)
        val sent = record(c, 8)
        assert(sent.forall(_.track == validWord))
      }
    }
  }
}
