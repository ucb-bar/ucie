package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.regs.{
  D2DAdapterOffsets,
  DvsecOffsets,
  PhyOffsets,
  UcieRegMap
}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.scalatest.funspec.AnyFunSpec
import scala.collection.mutable.ArrayBuffer

/** Brings two UcieDigitalTop instances up through their register blocks only,
  * one stage per test.
  *
  * UcieDigitalStagedBringupTest drives the same stack from testbench pins. This
  * one drives one TileLink master per die and nothing else, so a stage that
  * passes here is a stage software can reach with register writes.
  *
  * Every test is a cold start, so the stages are independent and the first
  * failing test names the first thing software cannot do.
  */
class UcieMmioBringupTest extends AnyFunSpec with ChiselSim {
  private type H = UcieMmioBringupHarnessImp
  private implicit val p: Parameters = Parameters.empty

  // The LTSM asserts on substates the link passes through on its way up.
  private val firtoolOpts = Array[String]()

  private val resetWait = 3200000
  private val trainCycles = 3000000
  private val handshakeCycles = 400000
  private val flagCycles = 4096
  private val dataCycles = 200000
  private val busCycles = 64
  private val pollReads = 256
  private val dwellCycles = 20000
  private val burstLength = 4

  private object Off {
    val ExtCapHeader = DvsecOffsets.ExtCapHeader
    val LinkControl = DvsecOffsets.LinkControl
    val LinkStatus = DvsecOffsets.LinkStatus
    // The adapter and PHY halves share one page base and their own offsets are
    // relative to it, so derive rather than write a literal.
    val D2dUncorrStatus =
      (UcieRegMap.DvsecPageSize + D2DAdapterOffsets.UncorrStatus).toInt
    val PhyErrLog1 = (UcieRegMap.DvsecPageSize + PhyOffsets.ErrorLog1).toInt
  }

  // Link Control out of reset: raw format off, x16 width, 24 GT/s.
  private val linkCtrlReset = BigInt(0x108)
  private val startTrainBit = 10
  private val rawFormatEnableBit = 0

  // Link Status bit positions.
  private val stsLinkStatus = 15
  private val stsLinkTraining = 16

  private val extCapHeaderReset = BigInt(0x00010023)

  // ---------------------------------------------------------------------------
  // Register access
  // ---------------------------------------------------------------------------

  private def bit(word: BigInt, b: Int): Boolean = ((word >> b) & 1) == 1

  private def idleBus(h: H, die: Int): Unit = {
    h.io.reg(die).req.valid.poke(false.B)
    h.io.reg(die).resp.ready.poke(false.B)
  }

  private def tlAccess(
      h: H,
      die: Int,
      off: Int,
      isWrite: Boolean,
      data: BigInt
  ): BigInt = {
    val r = h.io.reg(die)
    r.resp.ready.poke(true.B)
    r.req.bits.addr.poke(off.U(32.W))
    r.req.bits.data.poke(data.U(32.W))
    r.req.bits.is_write.poke(isWrite.B)
    r.req.valid.poke(true.B)

    var left = busCycles
    def handshaking: Boolean =
      r.req.ready.peek().litToBoolean && r.resp.valid.peek().litToBoolean
    while (!handshaking && left > 0) {
      h.clock.step(1)
      left -= 1
    }
    assert(
      handshaking,
      s"die $die: the register access to 0x${off.toHexString} never handshook"
    )

    val v = r.resp.bits.data.peek().litValue
    h.clock.step(1)
    idleBus(h, die)
    v
  }

  private def regWrite(h: H, die: Int, off: Int, data: BigInt): Unit =
    tlAccess(h, die, off, isWrite = true, data)

  private def regRead(h: H, die: Int, off: Int): BigInt =
    tlAccess(h, die, off, isWrite = false, 0)

  /** Read a register until the condition holds, then give up. Returns the last
    * word so a failure can print it.
    */
  private def readUntil(h: H, die: Int, off: Int)(
      cond: BigInt => Boolean
  ): (Boolean, BigInt) = {
    var n = 0
    var w = regRead(h, die, off)
    while (!cond(w) && n < pollReads) {
      w = regRead(h, die, off)
      n += 1
    }
    (cond(w), w)
  }

  private def startTraining(h: H, die: Int): Unit =
    regWrite(
      h,
      die,
      Off.LinkControl,
      linkCtrlReset | (BigInt(1) << startTrainBit)
    )

  // ---------------------------------------------------------------------------
  // Observing and stepping
  // ---------------------------------------------------------------------------

  private def flag(h: H, die: Int, b: Int): Boolean =
    ((h.io.flags(die).peek().litValue >> b) & 1) == 1

  private def bothDies(check: Int => Boolean): Boolean = check(0) && check(1)

  private def states(h: H): String =
    (0 until 2)
      .map { die =>
        s"die$die=${h.io.ltState(die).peek()}/${h.io.fdiState(die).peek()}"
      }
      .mkString(", ")

  private def stepUntil(h: H, limit: Int, milestone: String)(
      done: => Boolean
  ): Unit = {
    var left = limit
    while (left > 0 && !done) {
      h.clock.step(1)
      left -= 1
    }
    assert(done, s"$milestone was not reached: ${states(h)}")
  }

  private def coldStart(h: H): Unit = {
    for (die <- 0 until 2) idleBus(h, die)
    h.clock.step(resetWait + 128)
  }

  /** Reset, then bring the whole link up from one register write on die 0. */
  private def bringUpToActive(h: H): Unit = {
    coldStart(h)
    startTraining(h, 0)
    stepUntil(h, trainCycles, "RDI active")(
      bothDies(die =>
        h.io.rdiState(die).peek().litValue == RDIState.active.litValue
      )
    )
    stepUntil(h, handshakeCycles, "FDI active")(
      bothDies(die =>
        h.io.fdiState(die).peek().litValue == FDIState.active.litValue
      )
    )
  }

  // ---------------------------------------------------------------------------
  // Chip-facing data
  // ---------------------------------------------------------------------------

  private def payload(bits: Int, die: Int, seq: Int): BigInt =
    (0 until bits / 16).foldLeft(BigInt(0)) { (acc, i) =>
      val half =
        ((die + 1) << 12) | ((seq + 1) << 8) | ((i * 7 + die * 3 + seq) & 0xff)
      acc | (BigInt(half & 0xffff) << (i * 16))
    }

  private def exchange(h: H, words: Seq[Seq[BigInt]]): Seq[Seq[BigInt]] = {
    val pending = words.map(_.to(ArrayBuffer))
    val got = Seq.fill(2)(ArrayBuffer.empty[BigInt])
    for (die <- 0 until 2) h.io.rxReady.get(die).poke(true.B)

    var quiet = 0
    var left = dataCycles
    while ((pending.exists(_.nonEmpty) || quiet < 64) && left > 0) {
      for (die <- 0 until 2) {
        h.io.txValid.get(die).poke(pending(die).nonEmpty.B)
        if (pending(die).nonEmpty) {
          h.io.txData.get(die).poke(pending(die).head.U(h.beatBits.W))
        }
      }
      val accepted = (0 until 2).map { die =>
        pending(die).nonEmpty && flag(h, die, MmioFlag.chipTxReady)
      }
      val delivered = (0 until 2).map { die =>
        Option.when(flag(h, die, MmioFlag.chipRxValid))(
          h.io.rxData.get(die).peek().litValue
        )
      }

      h.clock.step(1)
      left -= 1

      for (die <- 0 until 2) {
        if (accepted(die)) pending(die).remove(0)
        delivered(die).foreach(got(die) += _)
      }
      if (pending.forall(_.isEmpty)) quiet += 1
    }

    for (die <- 0 until 2) {
      h.io.txValid.get(die).poke(false.B)
      h.io.rxReady.get(die).poke(false.B)
    }
    assert(
      pending.forall(_.isEmpty),
      s"the chip-facing TX never accepted every beat: ${states(h)}"
    )
    got.map(_.toSeq)
  }

  private def checkDelivery(
      from: Int,
      sent: Seq[BigInt],
      received: Seq[BigInt]
  ): Unit =
    assert(
      received == sent,
      s"die $from to die ${1 - from}: expected ${sent.size} beats, got ${received.size}"
    )

  // ---------------------------------------------------------------------------
  // The ladder
  // ---------------------------------------------------------------------------

  describe("UCIe bring-up over MMIO") {

    it("Stage 1: acknowledges the RDI wake request") {
      simulate(
        LazyModule(new UcieMmioBringupHarness()).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        for (die <- 0 until 2) idleBus(h, die)
        stepUntil(h, flagCycles, "RDI wake ack")(
          bothDies(flag(h, _, MmioFlag.rdiPlWakeAck))
        )
        for (die <- 0 until 2) {
          h.io.ltsmState(die).expect(LTSMState.sRESET, "training has not begun")
          h.io.rdiState(die).expect(RDIState.reset)
          assert(!flag(h, die, MmioFlag.rdiInbandPres))
        }
      }
    }

    it("Stage 2: reads the register block") {
      simulate(
        LazyModule(new UcieMmioBringupHarness()).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        for (die <- 0 until 2) idleBus(h, die)
        h.clock.step(64)
        for (die <- 0 until 2) {
          assert(
            regRead(h, die, Off.ExtCapHeader) == extCapHeaderReset,
            s"die $die read a wrong capability header"
          )
          assert(
            regRead(h, die, Off.LinkControl) == linkCtrlReset,
            s"die $die read a wrong Link Control reset value"
          )
          // A write has to stick, or every later stage is driving nothing.
          regWrite(
            h,
            die,
            Off.LinkControl,
            linkCtrlReset | (BigInt(1) << rawFormatEnableBit)
          )
          assert(
            bit(regRead(h, die, Off.LinkControl), rawFormatEnableBit),
            s"die $die did not keep a Link Control write"
          )
          regWrite(h, die, Off.LinkControl, linkCtrlReset)
        }
      }
    }

    it("Stage 3: trains both dies from one register write") {
      simulate(
        LazyModule(new UcieMmioBringupHarness()).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        coldStart(h)
        startTraining(h, 0)
        stepUntil(h, trainCycles, "RDI active")(
          bothDies(die =>
            h.io.rdiState(die).peek().litValue == RDIState.active.litValue
          )
        )
        stepUntil(h, handshakeCycles, "LTSM ACTIVE")(
          bothDies(die =>
            h.io.ltState(die).peek().litValue == LTState.sACTIVE.litValue
          )
        )
        for (die <- 0 until 2) {
          h.io.ltsmState(die).expect(LTSMState.sACTIVE)
          assert(!flag(h, die, MmioFlag.phyTimedout))
          assert(!flag(h, die, MmioFlag.phyTrainError))
          assert(
            !flag(h, die, MmioFlag.phyRecenter),
            "recentering must be done"
          )
        }
      }
    }

    // The advertised capability payload is hardcoded, so nothing is really
    // negotiated. This checks the exchange completes and presence follows.
    it("Stage 4: exchanges ADV_CAP") {
      simulate(
        LazyModule(new UcieMmioBringupHarness()).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        coldStart(h)
        startTraining(h, 0)
        stepUntil(h, trainCycles, "FDI inband presence")(
          bothDies(flag(h, _, MmioFlag.fdiInbandPres))
        )
        for (die <- 0 until 2) {
          assert(flag(h, die, MmioFlag.fdiProtocolVld))
        }
      }
    }

    it("Stage 5: latches the negotiated protocol") {
      simulate(
        LazyModule(new UcieMmioBringupHarness()).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        coldStart(h)
        startTraining(h, 0)
        stepUntil(h, trainCycles, "negotiated protocol")(
          bothDies(flag(h, _, MmioFlag.negotiatedProto))
        )
        for (die <- 0 until 2) {
          assert(flag(h, die, MmioFlag.fdiInbandPres))
        }
      }
    }

    it("Stage 6: brings the FDI up on the die that was never written") {
      simulate(
        LazyModule(new UcieMmioBringupHarness()).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        // Only die 0 took a register write. Die 1 woke on the sideband pattern
        // and opened its own FDI, so one write brings the whole link up.
        for (die <- 0 until 2) {
          h.io.fdiState(die).expect(FDIState.active)
        }
      }
    }

    it("Stage 7: reports the receiver alive") {
      simulate(
        LazyModule(new UcieMmioBringupHarness()).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        for (die <- 0 until 2) {
          assert(flag(h, die, MmioFlag.fdiRxActiveReq))
          assert(flag(h, die, MmioFlag.fdiRxActiveSts))
          assert(!flag(h, die, MmioFlag.rxOverflow))
        }
      }
    }

    it("Stage 8: reads the link up from software") {
      simulate(
        LazyModule(new UcieMmioBringupHarness()).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        for (die <- 0 until 2) {
          h.io.fdiState(die).expect(FDIState.active)
          // The protocol layer drops its request once the FDI leaves reset,
          // which re-arms the edge the adapter needs for the next bring-up.
          assert(!flag(h, die, MmioFlag.fdiLpReqActive))
          val (up, w) = readUntil(h, die, Off.LinkStatus)(bit(_, stsLinkStatus))
          assert(
            up,
            s"die $die: software never saw link_status set, last read 0x${w.toString(16)}"
          )
          assert(
            !bit(w, stsLinkTraining),
            s"die $die still reports link_training after the link came up"
          )
        }
      }
    }

    it("Stage 9: opens the chip interface") {
      simulate(
        LazyModule(new UcieMmioBringupHarness(exposeDataPath = true)).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        stepUntil(h, flagCycles, "chip TX ready")(
          bothDies(flag(h, _, MmioFlag.chipTxReady))
        )
        // Touching the state for one cycle is not the same as holding the link.
        h.clock.step(dwellCycles)
        for (die <- 0 until 2) {
          h.io.fdiState(die).expect(FDIState.active, "the link did not hold")
          assert(!flag(h, die, MmioFlag.phyTrainError))
          assert(!flag(h, die, MmioFlag.fdiStallReq))
        }
      }
    }

    it("Stage 10: carries one protocol beat each way") {
      simulate(
        LazyModule(new UcieMmioBringupHarness(exposeDataPath = true)).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        val sent = Seq(
          Seq(payload(h.beatBits, 0, 0)),
          Seq(payload(h.beatBits, 1, 0))
        )
        val got = exchange(h, sent)
        checkDelivery(from = 0, sent = sent(0), received = got(1))
        checkDelivery(from = 1, sent = sent(1), received = got(0))
        for (die <- 0 until 2) {
          assert(
            !flag(h, die, MmioFlag.fdiPlValid),
            s"die $die still holds FDI pl_valid after the beat was delivered"
          )
        }
      }
    }

    it("Stage 11: carries simultaneous bursts") {
      simulate(
        LazyModule(new UcieMmioBringupHarness(exposeDataPath = true)).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        val sent = Seq(
          (0 until burstLength).map(payload(h.beatBits, 0, _)),
          (0 until burstLength).map(payload(h.beatBits, 1, _))
        )
        val got = exchange(h, sent)
        checkDelivery(from = 0, sent = sent(0), received = got(1))
        checkDelivery(from = 1, sent = sent(1), received = got(0))
        for (die <- 0 until 2) {
          h.io.fdiState(die).expect(FDIState.active, "the link did not hold")
          assert(!flag(h, die, MmioFlag.rxOverflow))
        }
      }
    }

    it("Stage 12: carries adapter sideband traffic") {
      simulate(
        LazyModule(new UcieMmioBringupHarness()).module,
        firtoolOpts = firtoolOpts
      ) { h =>
        bringUpToActive(h)
        for (die <- 0 until 2) {
          for ((b, name) <- MmioFlag.sbFaults) {
            assert(
              !flag(h, die, b),
              s"die $die latched a sideband $name fault during link init"
            )
          }
          assert(!flag(h, die, MmioFlag.phyTimedout))
          // Software must not be told about errors that did not happen.
          assert(
            regRead(h, die, Off.D2dUncorrStatus) == 0,
            s"die $die reports an uncorrectable adapter error"
          )
          assert(
            regRead(h, die, Off.PhyErrLog1) == 0,
            s"die $die reports a PHY error"
          )
        }
      }
    }
  }
}
