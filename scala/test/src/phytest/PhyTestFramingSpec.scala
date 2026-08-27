package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy

class PhyTestLfsrLoopbackIO(numLanes: Int) extends Bundle {
  val lfsrSeed =
    Input(Vec(PhyTest.numTestLanes(numLanes), UInt(PhyTest.LfsrWidth.W)))
  // Sent on the valid lane every packet, and used as the RX's expected waveform.
  val validPattern = Input(UInt(Phy.SerdesRatio.W))
  // Must be pulsed after reset to load the seeds: the LFSRs' seed port only fires
  // while `txRst`/`rxRst` is high, and during module reset the `RegInit`
  // value wins, so a bare reset leaves them at their elaboration-time state.
  val fsmRst = Input(Bool())
  val execute = Input(Bool())
  val bitErrors =
    Output(
      Vec(
        PhyTest.NumFramings,
        Vec(PhyTest.numTestLanes(numLanes), UInt(64.W))
      )
    )
  val packetsReceived = Output(UInt(64.W))
}

/** Loops the PHY tester's LFSR-mode TX lanes back onto its own RX lanes.
  *
  * With `dataDelay` set, the data lanes arrive one UI later than the valid
  * lane, so the RX latches onto the valid edge one UI before the pattern
  * actually starts. Skewing the framing the other way needs no harness support:
  * a valid pattern whose lowest set bit is 1 makes the RX align one UI late.
  */
class PhyTestLfsrLoopback(
    numLanes: Int = 4,
    dataDelay: Boolean = false,
    validLaneSel: Int = -1
) extends Module {
  // Which lane carries the framing waveform; negative means leave it on the
  // dedicated valid lane.
  val validSel =
    if (validLaneSel >= 0) validLaneSel else Phy.defaultValidLaneSel(numLanes)
  val io = IO(new PhyTestLfsrLoopbackIO(numLanes))

  // The tester carries analog macros (the observation bump drivers, the TX data
  // debug lane, and the loopback pair), so this needs their behavioral models.
  val dut = Module(new PhyTest(numLanes = numLanes)(true))
  dut.io.regs := DontCare

  dut.io.regs.testTarget := TestTarget.mainband
  dut.io.regs.divResetb := false.B.asAsyncReset
  dut.io.regs.txTestMode := TxTestMode.lfsr
  dut.io.regs.txDataMode := DataMode.infinite
  dut.io.regs.rxDataMode := DataMode.infinite
  dut.io.regs.txLfsrSeed := io.lfsrSeed
  dut.io.regs.rxLfsrSeed := io.lfsrSeed
  dut.io.regs.txValid := io.validPattern
  dut.io.regs.txValidLaneSel := validSel.U
  dut.io.regs.rxValidLaneSel := validSel.U
  dut.io.regs.rxLfsrValid := io.validPattern
  dut.io.regs.txClkP := 0.U
  dut.io.regs.txClkN := 0.U
  dut.io.regs.txExecute := io.execute
  dut.io.regs.txRst := io.fsmRst
  dut.io.regs.rxRst := io.fsmRst
  dut.io.regs.rxPauseCounters := false.B
  dut.io.regs.txPacketsToSend := 0.U
  dut.io.regs.rxPacketsToReceive := 0.U
  dut.io.regs.txManualRepeatPeriod := 0.U
  dut.io.regs.txDataLaneGroup := 0.U
  dut.io.regs.txDataOffset := 0.U
  dut.io.regs.txDataChunkIn.valid := false.B
  dut.io.regs.txDataChunkIn.bits := 0.U
  dut.io.regs.rxDataLane := 0.U
  dut.io.regs.rxDataOffset := 0.U
  dut.io.regs.sb.txPacket := 0.U
  dut.io.regs.sb.txSend := false.B
  dut.io.regs.sb.rxPop := false.B
  dut.io.regs.sb.rxRst := false.B
  // Only the debug bumps and the tester's own lanes watch these, and this test
  // exercises neither.
  dut.io.debug.txClk := false.B.asClock
  dut.io.debug.rxClk := false.B.asClock
  dut.io.debug.txDivClk := false.B.asClock
  dut.io.debug.sbTxClk := false.B.asClock
  dut.io.debug.rxData := DontCare
  dut.io.sb.rxClk := dut.io.sb.txClk
  dut.io.sb.rxData := dut.io.sb.txData

  dut.io.tx.ready := true.B
  dut.io.rx.valid := dut.io.tx.valid
  // Bit 0 is the oldest UI, so delaying by one UI shifts each word up a bit
  // and pulls in the previous word's newest bit.
  def delayed(word: UInt): UInt =
    if (!dataDelay) word
    else {
      val prev = RegNext(word, 0.U)
      Cat(word(Phy.SerdesRatio - 2, 0), prev(Phy.SerdesRatio - 1))
    }

  // Valid frames the capture, so it is never delayed. Every other pattern lane
  // -- the data lanes and track -- is.
  dut.io.rx.bits.valid := dut.io.tx.bits.valid
  dut.io.rx.bits.track := delayed(dut.io.tx.bits.track)
  for (lane <- 0 until numLanes) {
    dut.io.rx.bits.data(lane) := delayed(dut.io.tx.bits.data(lane))
  }

  io.bitErrors(PhyTest.NominalFraming) := dut.io.regs.rxBitErrors
  io.bitErrors(PhyTest.EarlyFraming) := dut.io.regs.rxBitErrorsEarly
  io.bitErrors(PhyTest.LateFraming) := dut.io.regs.rxBitErrorsLate
  io.packetsReceived := dut.io.regs.rxPacketsReceived
}

class PhyTestFramingSpec extends AnyFunSpec with ChiselSim {
  // The tester instantiates analog macros, whose behavioral models Verilator
  // lints at.
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  val numLanes = 4
  val packets = 256
  // Distinct per lane so a lane comparing against the wrong LFSR cannot pass.
  def seed(lane: Int): BigInt =
    (BigInt("0123456789abcdef", 16) + lane * BigInt("1111", 16)) &
      ((BigInt(1) << PhyTest.LfsrWidth) - 1)
  // Lowest set bit 0, so the RX frames the pattern exactly. Rotating it gives a
  // different word, so the valid lane's framing counters are informative too.
  val alignedValid = BigInt("0f0f0f0f", 16)
  // The same waveform one UI later, so the RX aligns one UI late.
  val lateValid = alignedValid << 1

  // Runs the loopback for `packets` packets and returns the error counts, indexed
  // by framing then by lane.
  def counts(
      dataDelay: Boolean,
      validPattern: BigInt,
      validLaneSel: Int = -1
  ): (Seq[Seq[BigInt]], BigInt) = {
    var result: Seq[Seq[BigInt]] = Seq.empty
    var received = BigInt(0)
    simulate(new PhyTestLfsrLoopback(numLanes, dataDelay, validLaneSel)) { c =>
      for (lane <- 0 until PhyTest.numTestLanes(numLanes)) {
        c.io.lfsrSeed(lane).poke(seed(lane).U)
      }
      c.io.validPattern.poke(validPattern.U)
      c.io.execute.poke(false.B)
      c.io.fsmRst.poke(false.B)
      c.clock.step(4)
      // Load the seeds before starting the TX.
      c.io.fsmRst.poke(true.B)
      c.clock.step()
      c.io.fsmRst.poke(false.B)
      c.clock.step(2)
      c.io.execute.poke(true.B)
      c.clock.step()
      c.io.execute.poke(false.B)
      c.clock.step(packets + 4)
      result = (0 until PhyTest.NumFramings).map(f =>
        (0 until PhyTest.numTestLanes(numLanes)).map(lane =>
          c.io.bitErrors(f)(lane).peek().litValue
        )
      )
      received = c.io.packetsReceived.peek().litValue
    }
    (result, received)
  }

  // Every lane this harness loops back: the data lanes, valid, and track. The
  // loopback lane is not wired up here, so its counter compares zeros against
  // the pattern and is meaningless.
  val scoredLanes = 0 to PhyTest.trackLane(numLanes)
  // Of those, the ones carrying an LFSR pattern rather than the valid waveform.
  val patternLanes =
    scoredLanes.filter(_ != PhyTest.validLane(numLanes))

  describe("PhyTest LFSR framing counters") {
    it("should score the nominal framing clean when the RX aligns exactly") {
      val (errors, received) = counts(dataDelay = false, alignedValid)
      assert(received >= packets / 2, s"only $received packets received")
      val bits = received * Phy.SerdesRatio
      for (lane <- scoredLanes) {
        withClue(s"lane $lane: ") {
          assert(errors(PhyTest.NominalFraming)(lane) == 0)
          // A one UI shift of the reference has to make the count go bad, or the
          // extra counters are not actually looking at a different framing.
          assert(errors(PhyTest.EarlyFraming)(lane) > 0)
          assert(errors(PhyTest.LateFraming)(lane) > 0)
        }
      }
      // Sanity check on the magnitude: a one UI slip against an LFSR is as good as
      // random, so a misframed data lane sits near half the bits seen, nowhere
      // near zero. The valid lane is excluded because its count is set by the
      // shape of the valid waveform, not by chance.
      for (
        lane <- patternLanes;
        f <- Seq(PhyTest.EarlyFraming, PhyTest.LateFraming)
      ) {
        withClue(s"lane $lane framing $f of $bits bits: ") {
          assert(errors(f)(lane) > bits / 5)
          assert(errors(f)(lane) < 4 * bits / 5)
        }
      }
    }

    it("should score every lane clean with valid moved onto a data lane") {
      // `txValidLaneSel` sends the framing waveform on a data lane instead of
      // the dedicated one, and `rxValidLaneSel` tells the RX where to find it.
      // The lane it lands on carries the waveform rather than its own pattern,
      // so it has to be scored against the waveform too -- scoring it against
      // its LFSR would report about half the bits wrong.
      val moved = 1
      val (errors, received) =
        counts(dataDelay = false, alignedValid, validLaneSel = moved)
      assert(received >= packets / 2, s"only $received packets received")
      for (lane <- scoredLanes) {
        withClue(s"lane $lane with valid moved to lane $moved: ") {
          assert(errors(PhyTest.NominalFraming)(lane) == 0)
        }
      }
    }

    it("should score the early framing clean when the RX aligns one UI late") {
      // A dropped valid bit: the RX latches one UI after the pattern started, so
      // the pattern really began earlier than the edge indicated.
      val (errors, received) = counts(dataDelay = false, lateValid)
      assert(received >= packets / 2, s"only $received packets received")
      for (lane <- scoredLanes) {
        withClue(s"lane $lane: ") {
          assert(errors(PhyTest.EarlyFraming)(lane) == 0)
          assert(errors(PhyTest.NominalFraming)(lane) > 0)
        }
      }
    }

    it("should score the late framing clean when the RX aligns one UI early") {
      // A spuriously set valid bit: the RX latches one UI before the pattern
      // started, so the pattern really began later than the edge indicated.
      val (errors, received) = counts(dataDelay = true, alignedValid)
      assert(received >= packets / 2, s"only $received packets received")
      for (lane <- patternLanes) {
        withClue(s"lane $lane: ") {
          // The very first packet compares one pre-pattern bit, which may or may
          // not happen to match.
          assert(errors(PhyTest.LateFraming)(lane) <= 1)
          assert(errors(PhyTest.NominalFraming)(lane) > 1)
        }
      }
    }
  }
}
