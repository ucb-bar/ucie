package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.simutils.VerilatorCoverage
import org.scalatest.funspec.AnyFunSpec
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

// Two nodes wired as a full-duplex link. Each node's TX feeds the other's RX,
// and each node's RX credit return drives the other's TX credit input.
class TwoNodeLink(
    sbMsgWidth: Int,
    ncWidth: Int,
    numCredits: Int,
    depths: SidebandPriorityQueueDepths
) extends Module {
  val io = IO(new Bundle {
    val aTxIn = Flipped(Decoupled(UInt(sbMsgWidth.W)))
    val aRxOut = Decoupled(UInt(sbMsgWidth.W))
    val bTxIn = Flipped(Decoupled(UInt(sbMsgWidth.W)))
    val bRxOut = Decoupled(UInt(sbMsgWidth.W))
  })
  val a = Module(
    new SidebandInterfaceNode(sbMsgWidth, ncWidth, numCredits, depths)
  )
  val b = Module(
    new SidebandInterfaceNode(sbMsgWidth, ncWidth, numCredits, depths)
  )

  b.io.rxIn <> a.io.txOut
  a.io.txCreditReturn := b.io.rxCreditReturn
  a.io.rxIn <> b.io.txOut
  b.io.txCreditReturn := a.io.rxCreditReturn

  io.aTxIn <> a.io.txIn
  io.aRxOut <> a.io.rxOut
  io.bTxIn <> b.io.txIn
  io.bRxOut <> b.io.rxOut
}

class SidebandInterfaceNodeTest
    extends AnyFunSpec
    with ChiselSim
    with VerilatorCoverage {
  val msgW = 128
  val ncW = 32
  val n = msgW / ncW

  val printDebugs = false
  def printDebug(msg: String): Unit =
    if (printDebugs) println(s"[SidebandInterfaceNodeTest] $msg")

  val reqResp = SBMsgOpcode.MessageWith64bData.litValue
  val accComplete = SBMsgOpcode.CompletionWithoutData.litValue
  val accRequest = SBMsgOpcode.MemoryRead_32b.litValue
  val other = SBMsgOpcode.ManagementPortMsgWithoutData.litValue
  val opsNoDp = Set[BigInt](0x10, 0x12, 0x17, 0x18, 0x1e, 0x1f)

  def mask(w: Int): BigInt = (BigInt(1) << w) - 1
  def mkMsg(opcode: BigInt, tag: BigInt): BigInt = (tag << 64) | opcode

  def rank(op: BigInt): Int = op match {
    case `reqResp`     => 0
    case `accComplete` => 1
    case `accRequest`  => 2
    case _             => 3
  }

  def parity(x: BigInt): Int = {
    var v = x
    var p = 0
    while (v != 0) {
      p ^= (v & 1).toInt
      v >>= 1
    }
    p
  }

  // Mirrors the node's TX parity-set. CP covers header[61:0], DP covers the payload.
  def withParity(msg: BigInt): BigInt = {
    val header = msg & mask(64)
    val payload = (msg >> 64) & mask(64)
    val cp = parity(header & mask(62))
    val dp = if (opsNoDp.contains(msg & 0x1f)) 0 else parity(payload)
    (payload << 64) | (header & mask(62)) | (BigInt(cp) << 62) | (BigInt(
      dp
    ) << 63)
  }

  def feedRx(c: SidebandInterfaceNode, msg: BigInt): Unit = {
    for (i <- 0 until n) {
      c.io.rxIn.valid.poke(true.B)
      c.io.rxIn.bits.poke(((msg >> (i * ncW)) & mask(ncW)).U(ncW.W))
      c.clock.step()
    }
    c.io.rxIn.valid.poke(false.B)
  }

  // Push one message into txIn and let it serialize out.
  def pushTx(c: SidebandInterfaceNode, msg: BigInt): Unit = {
    c.io.txIn.bits.poke(msg.U(msgW.W))
    c.io.txIn.valid.poke(true.B)
    var guard = 0
    while (!c.io.txIn.ready.peek().litToBoolean && guard < 20) {
      c.clock.step()
      guard += 1
    }
    c.clock.step()
    c.io.txIn.valid.poke(false.B)
    c.clock.step(n + 2)
  }

  def txGoesValid(c: SidebandInterfaceNode): Boolean = {
    var saw = false
    for (_ <- 0 until 10) {
      if (c.io.txOut.valid.peek().litToBoolean) {
        saw = true
      }
      c.clock.step()
    }
    saw
  }

  describe("SidebandInterfaceNode link (two nodes)") {
    // count exceeds numCredits so forward progress depends on returned credits.
    it(
      "delivers messages intact in both directions, sustained beyond the credit limit"
    ) {
      simulate(new TwoNodeLink(msgW, ncW, 4, SidebandPriorityQueueDepths())) {
        c =>
          val count = 12
          val rng = new Random(2)
          val aMsgs = Seq.fill(count)(mkMsg(reqResp, BigInt(60, rng)))
          val bMsgs = Seq.fill(count)(mkMsg(reqResp, BigInt(60, rng)))
          c.io.aRxOut.ready.poke(true.B)
          c.io.bRxOut.ready.poke(true.B)

          val aGot = ArrayBuffer[BigInt]()
          val bGot = ArrayBuffer[BigInt]()
          var ai = 0
          var bi = 0
          var cycles = 0
          while ((aGot.size < count || bGot.size < count) && cycles < 1000) {
            c.io.aTxIn.valid.poke((ai < count).B)
            if (ai < count) {
              c.io.aTxIn.bits.poke(aMsgs(ai).U(msgW.W))
            }
            c.io.bTxIn.valid.poke((bi < count).B)
            if (bi < count) {
              c.io.bTxIn.bits.poke(bMsgs(bi).U(msgW.W))
            }
            if (c.io.bRxOut.valid.peek().litToBoolean) {
              bGot += c.io.bRxOut.bits.peek().litValue
            }
            if (c.io.aRxOut.valid.peek().litToBoolean) {
              aGot += c.io.aRxOut.bits.peek().litValue
            }
            if (ai < count && c.io.aTxIn.ready.peek().litToBoolean) {
              ai += 1
            }
            if (bi < count && c.io.bTxIn.ready.peek().litToBoolean) {
              bi += 1
            }
            c.clock.step()
            cycles += 1
          }
          assert(
            bGot.toSeq == aMsgs.map(withParity),
            "B did not receive what A sent"
          )
          assert(
            aGot.toSeq == bMsgs.map(withParity),
            "A did not receive what B sent"
          )
      }
    }
  }

  describe("SidebandInterfaceNode TX credit flow") {
    it("transmits a completion even with no credits") {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 1, SidebandPriorityQueueDepths())
      ) { c =>
        c.io.txCreditReturn.poke(false.B)
        pushTx(c, mkMsg(reqResp, 1)) // consumes the only credit

        c.io.txIn.bits.poke(mkMsg(accComplete, 2).U(msgW.W))
        c.io.txIn.valid.poke(true.B)
        assert(txGoesValid(c))
      }
    }

    it("stalls a credit-consuming message with no credits") {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 1, SidebandPriorityQueueDepths())
      ) { c =>
        c.io.txCreditReturn.poke(false.B)
        pushTx(c, mkMsg(reqResp, 1)) // consumes the only credit

        c.io.txIn.bits.poke(mkMsg(reqResp, 2).U(msgW.W))
        c.io.txIn.valid.poke(true.B)
        for (_ <- 0 until 8) {
          c.io.txOut.valid.expect(false.B)
          c.clock.step()
        }
      }
    }

    it("replenishes credits via txCreditReturn") {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 1, SidebandPriorityQueueDepths())
      ) { c =>
        c.io.txCreditReturn.poke(false.B)
        pushTx(c, mkMsg(reqResp, 1))

        c.io.txCreditReturn.poke(true.B)
        c.clock.step()
        c.io.txCreditReturn.poke(false.B)

        c.io.txIn.bits.poke(mkMsg(reqResp, 2).U(msgW.W))
        c.io.txIn.valid.poke(true.B)
        assert(txGoesValid(c))
      }
    }
  }

  describe("SidebandInterfaceNode RX parity") {
    it("flags a parity error and drops a message with a corrupt CP bit") {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 32, SidebandPriorityQueueDepths())
      ) { c =>
        c.io.rxOut.ready.poke(true.B)
        feedRx(
          c,
          withParity(mkMsg(reqResp, 7)) ^ (BigInt(1) << 62)
        ) // corrupt CP
        var sawRx = false
        for (_ <- 0 until 8) {
          if (c.io.rxOut.valid.peek().litToBoolean) {
            sawRx = true
          }
          c.clock.step()
        }
        assert(!sawRx)
        c.io.sbParityErr.expect(true.B)
      }
    }

    it("flags a parity error and drops a message with a corrupt DP bit") {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 32, SidebandPriorityQueueDepths())
      ) { c =>
        c.io.rxOut.ready.poke(true.B)
        feedRx(
          c,
          withParity(mkMsg(reqResp, 7)) ^ (BigInt(1) << 63)
        ) // corrupt DP
        var sawRx = false
        for (_ <- 0 until 8) {
          if (c.io.rxOut.valid.peek().litToBoolean) {
            sawRx = true
          }
          c.clock.step()
        }
        assert(!sawRx)
        c.io.sbParityErr.expect(true.B)
      }
    }

    it("drops a corrupt other-class message (CP is checked on every class)") {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 32, SidebandPriorityQueueDepths())
      ) { c =>
        c.io.rxOut.ready.poke(true.B)
        feedRx(c, withParity(mkMsg(other, 5)) ^ (BigInt(1) << 62)) // corrupt CP
        var sawRx = false
        for (_ <- 0 until 8) {
          if (c.io.rxOut.valid.peek().litToBoolean) {
            sawRx = true
          }
          c.clock.step()
        }
        assert(!sawRx)
        c.io.sbParityErr.expect(true.B)
      }
    }

    it("delivers correctly-parity'd messages across all classes") {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 32, SidebandPriorityQueueDepths())
      ) { c =>
        c.io.rxOut.ready.poke(true.B)
        for (
          (op, tag) <- Seq[(BigInt, BigInt)](
            (reqResp, 1),
            (accComplete, 2),
            (accRequest, 3),
            (other, 4)
          )
        ) {
          feedRx(c, withParity(mkMsg(op, tag)))
          var guard = 0
          while (!c.io.rxOut.valid.peek().litToBoolean && guard < 12) {
            c.clock.step()
            guard += 1
          }
          c.io.rxOut.valid.expect(true.B)
          assert((c.io.rxOut.bits.peek().litValue >> 64) == tag)
          c.clock.step()
        }
        c.io.sbParityErr.expect(false.B)
      }
    }
  }

  describe("SidebandInterfaceNode RX ordering and backpressure") {
    it("dequeues received messages in priority then FIFO order") {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 32, SidebandPriorityQueueDepths())
      ) { c =>
        c.io.rxOut.ready.poke(false.B)
        Seq[(BigInt, BigInt)](
          (reqResp, 1),
          (other, 4),
          (accComplete, 2),
          (reqResp, 5),
          (accRequest, 3),
          (other, 6)
        ).foreach { case (op, tag) =>
          feedRx(c, withParity(mkMsg(op, tag)))
        }
        c.clock.step(4)

        val got = (0 until 6).map { _ =>
          c.io.rxOut.ready.poke(true.B)
          while (!c.io.rxOut.valid.peek().litToBoolean) {
            c.clock.step()
          }
          val t = c.io.rxOut.bits.peek().litValue >> 64
          c.clock.step()
          t
        }
        assert(got == Seq[BigInt](1, 5, 2, 3, 4, 6))
      }
    }

    it("asserts rxPriorityQueuesFull only for the full target queue") {
      val depths = SidebandPriorityQueueDepths(messageRequestOrResponse = 2)
      simulate(new SidebandInterfaceNode(msgW, ncW, 32, depths)) { c =>
        c.io.rxOut.ready.poke(false.B)
        feedRx(c, withParity(mkMsg(reqResp, 1)))
        feedRx(
          c,
          withParity(mkMsg(reqResp, 2))
        ) // reqResp queue (depth 2) now full

        feedRx(
          c,
          withParity(mkMsg(accComplete, 3))
        ) // different, non-full queue
        var sawFullOnOther = false
        for (_ <- 0 until 4) {
          if (c.io.rxPriorityQueuesFull.peek().litToBoolean) {
            sawFullOnOther = true
          }
          c.clock.step()
        }
        assert(!sawFullOnOther)

        feedRx(c, withParity(mkMsg(reqResp, 4))) // hits the full queue
        var sawFull = false
        for (_ <- 0 until 4) {
          if (c.io.rxPriorityQueuesFull.peek().litToBoolean) {
            sawFull = true
          }
          c.clock.step()
        }
        assert(sawFull)
      }
    }

    it(
      "delivers a constrained-random mix of classes in priority then FIFO order"
    ) {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 32, SidebandPriorityQueueDepths())
      ) { c =>
        val rng = new Random(7)
        val classes = Seq(reqResp, accComplete, accRequest, other)
        val count = 20
        c.io.rxOut.ready.poke(false.B)
        val sent = (0 until count).map { i =>
          val op = classes(rng.nextInt(classes.size))
          val tag = BigInt(0x1000 + i)
          feedRx(c, withParity(mkMsg(op, tag)))
          (op, tag)
        }
        c.clock.step(4)

        val got = ArrayBuffer[BigInt]()
        var guard = 0
        c.io.rxOut.ready.poke(true.B)
        while (got.size < count && guard < 2000) {
          if (c.io.rxOut.valid.peek().litToBoolean) {
            got += c.io.rxOut.bits.peek().litValue >> 64
          }
          c.clock.step()
          guard += 1
        }
        val expected = sent.zipWithIndex
          .sortBy { case ((op, _), i) => (rank(op), i) }
          .map(_._1._2)
        assert(got.toSeq == expected)
        c.io.sbParityErr.expect(false.B)
      }
    }
  }

  describe("SidebandInterfaceNode reset") {
    it("clears queued messages and parity error on reset") {
      simulate(
        new SidebandInterfaceNode(msgW, ncW, 32, SidebandPriorityQueueDepths())
      ) { c =>
        c.io.rxOut.ready.poke(false.B)
        feedRx(c, withParity(mkMsg(reqResp, 1)))
        feedRx(
          c,
          withParity(mkMsg(reqResp, 7)) ^ (BigInt(1) << 62)
        ) // parity error
        c.clock.step(2)
        c.io.sbParityErr.expect(true.B)

        c.reset.poke(true.B)
        c.clock.step()
        c.reset.poke(false.B)

        c.io.rxOut.ready.poke(true.B)
        c.io.rxOut.valid.expect(false.B)
        c.io.sbParityErr.expect(false.B)

        feedRx(c, withParity(mkMsg(reqResp, 9)))
        var guard = 0
        while (!c.io.rxOut.valid.peek().litToBoolean && guard < 12) {
          c.clock.step()
          guard += 1
        }
        c.io.rxOut.valid.expect(true.B)
        assert((c.io.rxOut.bits.peek().litValue >> 64) == BigInt(9))
      }
    }
  }
}
