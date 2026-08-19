package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.simutils.VerilatorCoverage
import org.scalatest.funspec.AnyFunSpec
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class SidebandLinkNodeTest
    extends AnyFunSpec
    with ChiselSim
    with VerilatorCoverage {
  val msgW = 128
  val linkW = 1

  val printDebugs = false
  def printDebug(msg: String): Unit =
    if (printDebugs) println(s"[SidebandLinkNodeTest] $msg")

  // 128-bit (with-data) opcode per class so a tag can ride in the payload.
  val reqResp = SBMsgOpcode.MessageWith64bData.litValue
  val accComplete = SBMsgOpcode.CompletionWith64bData.litValue
  val accRequest = SBMsgOpcode.MemoryRead_32b.litValue
  val other = SBMsgOpcode.ManagementPortMsgWithData.litValue
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

  // CP over header[61:0] (even, reserved included); DP over the payload when used.
  def withParity(msg: BigInt): BigInt = {
    val header = msg & mask(64)
    val payload = (msg >> 64) & mask(64)
    val cp = parity(header & mask(62))
    val dp = if (opsNoDp.contains(msg & 0x1f)) 0 else parity(payload)
    (payload << 64) | (header & mask(62)) | (BigInt(cp) << 62) | (BigInt(
      dp
    ) << 63)
  }

  def mkNode(
      depths: SidebandPriorityQueueDepths = SidebandPriorityQueueDepths(),
      timeout: Int = 512
  ) =
    new SidebandLinkNode(msgW, linkW, 32, timeout, depths)

  // Drive one bit onto the link, toggling the forwarded clock.
  def driveBit(c: SidebandLinkNode, bit: BigInt): Unit = {
    c.io.rxIn.bits.poke(bit.U)
    c.io.rxIn.fwClock.poke(true.B)
    c.clock.step()
    c.io.rxIn.fwClock.poke(false.B)
    c.clock.step()
  }

  // Serialize a message onto rxIn as 64-bit chunks separated by 32-bit idle gaps.
  def feedRxSerial(
      c: SidebandLinkNode,
      msg: BigInt,
      bitWidth: Int = 128
  ): Unit = {
    val total = bitWidth + (bitWidth / 64) * 32
    for (i <- 0 until total) {
      if ((i < 64) || (i >= 96 && i < 160)) {
        val bitIdx = if (i >= 96) i - 32 else i
        driveBit(c, (msg >> bitIdx) & 1)
      } else {
        c.io.rxIn.bits.poke(0.U)
        c.io.rxIn.fwClock.poke(false.B)
        c.clock.step()
      }
    }
    c.io.rxIn.bits.poke(0.U)
    c.io.rxIn.fwClock.poke(false.B)
  }

  // Capture the serialized stream from txOut and reassemble it.
  def captureTxSerial(c: SidebandLinkNode, bitWidth: Int = 128): BigInt = {
    var guard = 0
    while (c.io.txOut.bits.peek().litValue == 0 && guard < 100) {
      c.clock.step()
      guard += 1
    }
    var result = BigInt(0)
    val chunks = bitWidth / 64
    for (chunk <- 0 until chunks) {
      for (b <- 0 until 64) {
        result |= c.io.txOut.bits.peek().litValue << (chunk * 64 + b)
        c.clock.step()
      }
      if (chunk < chunks - 1) {
        for (_ <- 0 until 32) {
          c.clock.step()
        }
      }
    }
    result
  }

  def takeRxOut(c: SidebandLinkNode): BigInt = {
    c.io.rxOut.ready.poke(true.B)
    var guard = 0
    while (!c.io.rxOut.valid.peek().litToBoolean && guard < 50) {
      c.clock.step()
      guard += 1
    }
    c.io.rxOut.valid.expect(true.B)
    val t = c.io.rxOut.bits.peek().litValue >> 64
    c.clock.step()
    c.io.rxOut.ready.poke(false.B)
    t
  }

  describe("SidebandLinkNode TX path") {
    it("serializes a packet with parity set") {
      simulate(mkNode()) { c =>
        c.io.ctrl.txMode.poke(SBRxTxMode.PACKET)
        c.io.ctrl.freezeAcceptingPackets.poke(false.B)
        val msg = mkMsg(reqResp, BigInt("5a5a5a5a5a5a5a5a", 16))
        c.io.txIn.bits.poke(msg.U(msgW.W))
        c.io.txIn.valid.poke(true.B)
        c.io.txIn.ready.expect(true.B)
        c.clock.step()
        c.io.txIn.valid.poke(false.B)
        assert(captureTxSerial(c, 128) == withParity(msg))
      }
    }

    it("serializes a 64-bit message in RAW mode") {
      simulate(mkNode()) { c =>
        c.io.ctrl.txMode.poke(SBRxTxMode.RAW)
        c.io.ctrl.freezeAcceptingPackets.poke(false.B)
        val msg = mkMsg(reqResp, BigInt("0f0f0f0f0f0f0f0f", 16))
        c.io.txIn.bits.poke(msg.U(msgW.W))
        c.io.txIn.valid.poke(true.B)
        c.io.txIn.ready.expect(true.B)
        c.clock.step()
        c.io.txIn.valid.poke(false.B)
        assert(captureTxSerial(c, 64) == (withParity(msg) & mask(64)))
      }
    }

    it("freezes new accepts and signals allPacketsSent once drained") {
      simulate(mkNode()) { c =>
        c.io.ctrl.txMode.poke(SBRxTxMode.PACKET)
        c.io.ctrl.freezeAcceptingPackets.poke(false.B)
        c.io.txIn.bits.poke(mkMsg(reqResp, 1).U(msgW.W))
        c.io.txIn.valid.poke(true.B)
        c.io.txIn.ready.expect(true.B)
        c.clock.step()

        c.io.ctrl.freezeAcceptingPackets.poke(true.B)
        c.io.txIn.bits
          .poke(mkMsg(reqResp, 2).U(msgW.W)) // offered but must be refused
        c.io.txIn.ready.expect(false.B)

        var guard = 0
        while (!c.io.ctrl.allPacketsSent.peek().litToBoolean && guard < 400) {
          c.io.txIn.ready.expect(false.B)
          c.clock.step()
          guard += 1
        }
        c.io.ctrl.allPacketsSent.expect(true.B)
        c.io.txIn.valid.poke(false.B)
      }
    }
  }

  describe("SidebandLinkNode RX parity") {
    it("delivers a message with valid parity") {
      simulate(mkNode()) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        feedRxSerial(c, withParity(mkMsg(reqResp, 0xab)))
        assert(takeRxOut(c) == BigInt(0xab))
        c.io.err.sbParityErr.expect(false.B)
      }
    }

    it("drops a message with a corrupt CP bit") {
      simulate(mkNode()) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.rxOut.ready.poke(true.B)
        feedRxSerial(c, withParity(mkMsg(reqResp, 7)) ^ (BigInt(1) << 62))
        for (_ <- 0 until 8) {
          c.io.rxOut.valid.expect(false.B)
          c.clock.step()
        }
        c.io.err.sbParityErr.expect(true.B)
      }
    }

    it("drops a message with a corrupt DP bit") {
      simulate(mkNode()) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.rxOut.ready.poke(true.B)
        feedRxSerial(c, withParity(mkMsg(reqResp, 7)) ^ (BigInt(1) << 63))
        for (_ <- 0 until 8) {
          c.io.rxOut.valid.expect(false.B)
          c.clock.step()
        }
        c.io.err.sbParityErr.expect(true.B)
      }
    }

    it("drops a corrupt other-class message (CP is checked on every class)") {
      simulate(mkNode()) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.rxOut.ready.poke(true.B)
        feedRxSerial(c, withParity(mkMsg(other, 5)) ^ (BigInt(1) << 62))
        for (_ <- 0 until 8) {
          c.io.rxOut.valid.expect(false.B)
          c.clock.step()
        }
        c.io.err.sbParityErr.expect(true.B)
      }
    }

    it("delivers correctly-parity'd messages across all classes") {
      simulate(mkNode()) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        for (
          (op, tag) <- Seq[(BigInt, BigInt)](
            (reqResp, 1),
            (accComplete, 2),
            (accRequest, 3),
            (other, 4)
          )
        ) {
          feedRxSerial(c, withParity(mkMsg(op, tag)))
          assert(takeRxOut(c) == tag)
        }
        c.io.err.sbParityErr.expect(false.B)
      }
    }
  }

  describe("SidebandLinkNode RX ordering and backpressure") {
    it("dequeues received messages in priority then FIFO order") {
      simulate(mkNode()) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.rxOut.ready.poke(false.B)
        Seq[(BigInt, BigInt)](
          (reqResp, 1),
          (other, 4),
          (accComplete, 2),
          (accRequest, 3)
        ).foreach { case (op, tag) =>
          feedRxSerial(c, withParity(mkMsg(op, tag)))
        }
        val got = (0 until 4).map(_ => takeRxOut(c))
        assert(got == Seq[BigInt](1, 2, 3, 4))
      }
    }

    it("asserts rxPriorityQueuesFull only for the full target queue") {
      simulate(
        mkNode(SidebandPriorityQueueDepths(messageRequestOrResponse = 1))
      ) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.rxOut.ready.poke(false.B)
        feedRxSerial(
          c,
          withParity(mkMsg(reqResp, 1))
        ) // reqResp queue (depth 1) now full

        feedRxSerial(
          c,
          withParity(mkMsg(accComplete, 2))
        ) // different, non-full queue
        c.io.err.rxPriorityQueuesFull.expect(false.B)

        var sawFull = false
        feedRxSerial(c, withParity(mkMsg(reqResp, 3))) // hits the full queue
        for (_ <- 0 until 4) {
          if (c.io.err.rxPriorityQueuesFull.peek().litToBoolean) {
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
      simulate(mkNode()) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.rxOut.ready.poke(false.B)
        val rng = new Random(7)
        val classes = Seq(reqResp, accComplete, accRequest, other)
        val count = 6
        val sent = (0 until count).map { i =>
          val op = classes(rng.nextInt(classes.size))
          val tag = BigInt(0x10 + i)
          feedRxSerial(c, withParity(mkMsg(op, tag)))
          (op, tag)
        }
        val got = (0 until count).map(_ => takeRxOut(c))
        val expected = sent.zipWithIndex
          .sortBy { case ((op, _), i) => (rank(op), i) }
          .map(_._1._2)
        assert(got == expected)
        c.io.err.sbParityErr.expect(false.B)
      }
    }
  }

  describe("SidebandLinkNode RX timeout and mode") {
    it("asserts desTimedout when a packet stalls mid-stream") {
      simulate(mkNode(timeout = 200)) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        val msg = withParity(mkMsg(reqResp, 1))
        for (i <- 0 until 30) {
          driveBit(c, (msg >> i) & 1)
        }
        c.io.rxIn.bits.poke(0.U)
        c.io.rxIn.fwClock.poke(false.B)
        c.clock.step(230)
        c.io.err.desTimedout.expect(true.B)
      }
    }

    it("delivers a 64-bit message in RAW mode") {
      simulate(mkNode()) { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.RAW)
        val msg = withParity(
          mkMsg(SBMsgOpcode.MessageWithoutData.litValue, 0)
        ) & mask(64)
        feedRxSerial(c, msg, 64)
        c.io.rxOut.ready.poke(true.B)
        var guard = 0
        while (!c.io.rxOut.valid.peek().litToBoolean && guard < 50) {
          c.clock.step()
          guard += 1
        }
        c.io.rxOut.valid.expect(true.B)
        assert(c.io.rxOut.bits.peek().litValue == msg)
      }
    }
  }
}
