package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.phy.Phy

class PhyTestLfsrLoopbackIO(numLanes: Int) extends Bundle {
  val lfsrSeed = Input(Vec(numLanes + 1, UInt(PhyTest.LfsrWidth.W)))
  // Sent on the valid lane every packet, and used as the RX's expected waveform.
  val validPattern = Input(UInt(Phy.SerdesRatio.W))
  // Must be pulsed after reset to load the seeds: the LFSRs' seed port only fires
  // while `txFsmRst`/`rxFsmRst` is high, and during module reset the `RegInit`
  // value wins, so a bare reset leaves them at their elaboration-time state.
  val fsmRst = Input(Bool())
  val execute = Input(Bool())
  val bitErrors =
    Output(Vec(PhyTest.NumFramings, Vec(numLanes + 2, UInt(64.W))))
  val packetsReceived = Output(UInt(64.W))
}

/** Loops the PHY tester's LFSR-mode TX lanes back onto its own RX lanes.
  *
  * With `dataDelay` set, the data lanes arrive one UI later than the valid
  * lane, so the RX latches onto the valid edge one UI before the pattern
  * actually starts. Skewing the framing the other way needs no harness support:
  * a valid pattern whose lowest set bit is 1 makes the RX align one UI late.
  */
class PhyTestLfsrLoopback(numLanes: Int = 4, dataDelay: Boolean = false)
    extends Module {
  val io = IO(new PhyTestLfsrLoopbackIO(numLanes))

  val dut = Module(new PhyTest(numLanes = numLanes))
  dut.io.regs := DontCare

  dut.io.regs.testTarget := TestTarget.mainband
  dut.io.regs.divResetb := false.B.asAsyncReset
  dut.io.regs.txTestMode := TxTestMode.lfsr
  dut.io.regs.txDataMode := DataMode.infinite
  dut.io.regs.rxDataMode := DataMode.infinite
  dut.io.regs.txLfsrSeed := io.lfsrSeed
  dut.io.regs.rxLfsrSeed := io.lfsrSeed
  dut.io.regs.txValid := io.validPattern
  dut.io.regs.rxLfsrValid := io.validPattern
  dut.io.regs.txTrack := 0.U
  dut.io.regs.txClkP := 0.U
  dut.io.regs.txClkN := 0.U
  dut.io.regs.txExecute := io.execute
  dut.io.regs.txFsmRst := io.fsmRst
  dut.io.regs.rxFsmRst := io.fsmRst
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
  dut.io.debug.pllClk := false.B
  dut.io.debug.fwdClk := false.B
  dut.io.sb.rxClk := dut.io.sb.txClk
  dut.io.sb.rxData := dut.io.sb.txData

  dut.io.tx.ready := true.B
  dut.io.rx.valid := dut.io.tx.valid
  dut.io.rx.bits.valid := dut.io.tx.bits.valid
  dut.io.rx.bits.track := dut.io.tx.bits.track
  for (lane <- 0 until numLanes) {
    val word = dut.io.tx.bits.data(lane)
    dut.io.rx.bits.data(lane) := (if (!dataDelay) word
                                  else {
                                    // Bit 0 is the oldest UI, so delaying by one
                                    // UI shifts each word up a bit and pulls in
                                    // the previous word's newest bit.
                                    val prev = RegNext(word, 0.U)
                                    Cat(
                                      word(Phy.SerdesRatio - 2, 0),
                                      prev(Phy.SerdesRatio - 1)
                                    )
                                  })
  }

  io.bitErrors(PhyTest.NominalFraming) := dut.io.regs.rxBitErrors
  io.bitErrors(PhyTest.EarlyFraming) := dut.io.regs.rxBitErrorsEarly
  io.bitErrors(PhyTest.LateFraming) := dut.io.regs.rxBitErrorsLate
  io.packetsReceived := dut.io.regs.rxPacketsReceived
}

class PhyTestFramingSpec extends AnyFunSpec with ChiselSim {
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
      validPattern: BigInt
  ): (Seq[Seq[BigInt]], BigInt) = {
    var result: Seq[Seq[BigInt]] = Seq.empty
    var received = BigInt(0)
    simulate(new PhyTestLfsrLoopback(numLanes, dataDelay)) { c =>
      for (lane <- 0 until numLanes + 1) {
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
        (0 until numLanes + 2).map(lane =>
          c.io.bitErrors(f)(lane).peek().litValue
        )
      )
      received = c.io.packetsReceived.peek().litValue
    }
    (result, received)
  }

  // Data lanes plus the valid lane. The loopback lane is not wired up yet, so its
  // counter compares zeros against the pattern and is meaningless.
  val scoredLanes = 0 to numLanes

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
        lane <- 0 until numLanes;
        f <- Seq(PhyTest.EarlyFraming, PhyTest.LateFraming)
      ) {
        withClue(s"lane $lane framing $f of $bits bits: ") {
          assert(errors(f)(lane) > bits / 5)
          assert(errors(f)(lane) < 4 * bits / 5)
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
      for (lane <- 0 until numLanes) {
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
