package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._
import chisel3.simulator.PeekPokeAPI._

import edu.berkeley.cs.uciedigital.phy.{Phy, TxIO}

/** Loops PhyTest's mainband TX straight back onto its own RX, with every
  * control register brought out to the test.
  *
  * Unlike [[PhyTestLfsrLoopback]], which fixes the mode it runs, this leaves
  * every register to the test, so one harness covers every mode layered on top
  * of the original ones. Registers a test does not poke read zero, which for
  * every one of those layered controls is the value that leaves the original
  * modes as they were.
  *
  * @param dataDelay
  *   delay every pattern lane one UI behind valid, so the RX latches onto the
  *   valid edge one UI before the pattern starts.
  * @param rxNeedsClock
  *   only hand the RX a packet the forwarded clock was running for, the way a
  *   real RX stops when the clock is parked.
  */
class PhyTestRegLoopback(
    val numLanes: Int = 4,
    dataDelay: Boolean = false,
    rxNeedsClock: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val regs =
      new PhyTestRegsIO(PhyTestRegLoopback.bufferDepthPerLane, numLanes)
    // What the TX sends, every cycle: the RX takes it the same cycle.
    val tx = Output(new TxIO(numLanes))
  })

  val dut = Module(
    new PhyTest(PhyTestRegLoopback.bufferDepthPerLane, numLanes)(true)
  )
  dut.io.regs <> io.regs
  // In the full block the divider resets come from the clock register
  // block; the harness reset stands in for it here.
  dut.io.txDividerRstb := (!reset.asBool).asAsyncReset
  dut.io.rxDividerRstb := (!reset.asBool).asAsyncReset

  // Only the debug bumps and the tester's own lanes watch these, and nothing
  // here exercises either.
  dut.io.debug.testTxLaneClk.foreach(_ := false.B.asClock)
  dut.io.debug.testTxPadClk := false.B.asClock
  dut.io.debug.testRxPadClk := false.B.asClock
  dut.io.debug.txDivClk := false.B.asClock
  dut.io.debug.sbTxClk := false.B.asClock
  dut.io.debug.rxData := 0.U.asTypeOf(dut.io.debug.rxData)
  dut.io.sbTxClock := clock
  dut.io.sbTxReset := reset.asBool
  dut.io.sb.rxClk := false.B.asClock
  dut.io.sb.rxData := false.B

  dut.io.tx.ready := true.B
  io.tx := dut.io.tx.bits
  val rxFire = dut.io.tx.valid &&
    (if (rxNeedsClock) dut.io.tx.bits.clkp =/= 0.U else true.B)
  dut.io.rx.valid := rxFire
  // Bit 0 is the oldest UI, so delaying by one UI shifts each word up a bit
  // and pulls in the previous word's newest bit.
  def delayed(word: UInt): UInt =
    if (!dataDelay) word
    else {
      val prev = RegEnable(word, 0.U, rxFire)
      Cat(word(Phy.SerdesRatio - 2, 0), prev(Phy.SerdesRatio - 1))
    }
  dut.io.rx.bits.valid := dut.io.tx.bits.valid
  dut.io.rx.bits.track := delayed(dut.io.tx.bits.track)
  for (lane <- 0 until numLanes) {
    dut.io.rx.bits.data(lane) := delayed(dut.io.tx.bits.data(lane))
  }
}

object PhyTestRegLoopback {

  /** Capture SRAM depth `2^(bufferDepthPerLane - 5)` packets, small enough to
    * wrap in a short run.
    */
  val bufferDepthPerLane = 10
  val sramPackets = 1 << (bufferDepthPerLane - 5)

  /** The valid waveform every test sends. Bit 0 is set, so the RX frames on
    * the TX's packet boundaries.
    */
  val validWord = BigInt("0f0f0f0f", 16)
  val clkPWord = BigInt("55555555", 16)
  val clkNWord = BigInt("aaaaaaaa", 16)

  /** Distinct per lane, so a lane compared against the wrong LFSR fails. */
  def seed(lane: Int): BigInt =
    (BigInt("0123456789abcdef", 16) + lane * BigInt("1111", 16)) &
      ((BigInt(1) << PhyTest.LfsrWidth) - 1)

  /** Programs an LFSR run over the mainband, with the TX and RX seeded from
    * `txSeed` and `rxSeed`, and loads the seeds. Starts nothing.
    */
  def setupLfsr(
      c: PhyTestRegLoopback,
      txSeed: Int => BigInt = seed,
      rxSeed: Int => BigInt = seed
  ): Unit = {
    val r = c.io.regs
    r.testTarget.poke(TestTarget.mainband)
    r.mainbandMode.poke(BandMode.manual)
    r.txTestMode.poke(TxTestMode.lfsr)
    r.txDataMode.poke(DataMode.infinite)
    r.rxDataMode.poke(DataMode.infinite)
    for (lane <- 0 until PhyTest.numTestLanes(c.numLanes)) {
      r.txLfsrSeed(lane).poke(txSeed(lane).U)
      r.rxLfsrSeed(lane).poke(rxSeed(lane).U)
    }
    r.txValid.poke(validWord.U)
    r.rxLfsrValid.poke(validWord.U)
    r.txValidLaneSel.poke(Phy.defaultValidLaneSel(c.numLanes).U)
    r.rxValidLaneSel.poke(Phy.defaultValidLaneSel(c.numLanes).U)
    r.txClkP.poke(clkPWord.U)
    r.txClkN.poke(clkNWord.U)
    c.clock.step(4)
    resetFsms(c)
  }

  /** Resets both FSMs, which reseeds their LFSRs from the seed registers. */
  def resetFsms(c: PhyTestRegLoopback): Unit = {
    c.io.regs.txFsmRst.poke(true.B)
    c.io.regs.rxFsmRst.poke(true.B)
    c.clock.step()
    c.io.regs.txFsmRst.poke(false.B)
    c.io.regs.rxFsmRst.poke(false.B)
    c.clock.step(2)
  }

  def strobe(c: PhyTestRegLoopback, reg: Bool): Unit = {
    reg.poke(true.B)
    c.clock.step()
    reg.poke(false.B)
  }

  /** The counts and the packet count sit behind a couple of registers. */
  val outputDelay = 3

  def errors(c: PhyTestRegLoopback, framing: Int): Seq[BigInt] = {
    val counts = framing match {
      case PhyTest.NominalFraming => c.io.regs.rxBitErrors
      case PhyTest.EarlyFraming   => c.io.regs.rxBitErrorsEarly
      case PhyTest.LateFraming    => c.io.regs.rxBitErrorsLate
    }
    counts.map(_.peek().litValue)
  }

  def packetsReceived(c: PhyTestRegLoopback): BigInt =
    c.io.regs.rxPacketsReceived.peek().litValue

  /** One word of the RX capture SRAM. */
  def captured(c: PhyTestRegLoopback, lane: Int, offset: Int): BigInt = {
    c.io.regs.rxDataLane.poke(lane.U)
    c.io.regs.rxDataOffset.poke(offset.U)
    c.clock.step()
    c.io.regs.rxDataChunk.peek().litValue
  }

  /** Whether consecutive pattern words follow the LFSR recurrence: every bit
    * past the first `LfsrWidth` is the XOR of the bits `LfsrTaps` before it.
    */
  def followsLfsr(words: Seq[BigInt]): Boolean = {
    val bits = words.flatMap(w => (0 until Phy.SerdesRatio).map(w.testBit))
    (PhyTest.LfsrWidth until bits.length).forall(m =>
      PhyTest.LfsrTaps.toSeq.map(t => bits(m - t)).reduce(_ ^ _) == bits(m)
    )
  }
}
