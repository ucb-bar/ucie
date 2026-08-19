package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._

// ChiselSim for Chisel 7.0+
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.simutils.VerilatorCoverage

import org.scalatest.funspec.AnyFunSpec

class SidebandLinkSerdesTest
    extends AnyFunSpec
    with ChiselSim
    with VerilatorCoverage {

  val sbParams = new SidebandParams()
  val msgW = sbParams.sbNodeMsgWidth
  val sbLinkW = sbParams.sbLinkWidth

  val debugPrints = false

  def printDebug(msg: => String): Unit = {
    if (debugPrints) {
      Console.println(msg)
    }
  }

  describe("Sideband Link Serializer and Deserializer Instantiation Test") {
    it("Instantiated Serializer") {
      simulate(new SidebandLinkSerializer(sbLinkW, msgW)) { c =>
        c.clock.step()
        printDebug("[TEST] Success")
      }
    }

    it("Instantiated Deserializer") {
      val timeoutCycles = 512
      simulate(new SidebandLinkDeserializer(sbLinkW, msgW, timeoutCycles)) {
        c =>
          c.clock.step()
          printDebug("[TEST] Success")
      }
    }
  }

  def toBits(value: BigInt, width: Int): String = {
    val binary = value.toString(2)
    "0" * (width - binary.length) + binary
  }

  def bitWidthForOpcode(opcode: SBMsgOpcode.Type): Int = {
    if (SBMsgOpcode.OpsWithoutData.contains(opcode)) 64 else 128
  }

  def dataForOpcode(opcode: SBMsgOpcode.Type, bitWidth: Int): BigInt = {
    val widthMask = (BigInt(1) << bitWidth) - 1
    val payloadMask = widthMask ^ BigInt(31)
    val pattern = (BigInt(1) << (bitWidth - 1)) | (BigInt(
      "5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a",
      16
    ) & (widthMask >> 1))

    ((pattern ^ (opcode.litValue << 17)) & payloadMask) | opcode.litValue
  }

  def checkSerializedData(
      c: SidebandLinkSerializer,
      refData: UInt,
      len: Int
  ): Unit = {

    /** This function checks the data out of the serializer as well as making
      * sure data and clock are low during the waits. If message len is 128 bits
      * then we want 64 data, 32 low, 64 data, 32 low.
      */
    val loopCount = len + ((len / 64) * 32)
    var serializedData = false.B
    var bitstring = ""

    val serLatency = 1
    c.clock.step(serLatency)

    for (i <- 0 until loopCount) {

      if ((i >= 0 && i < 64)) {
        serializedData = refData(i)
      } else if (i >= 96 && i < 160) {
        serializedData = refData(i - 32)
      }

      if ((i >= 0 && i < 64) || (i >= 96 && i < 160)) { // data
        val bit = c.io.out.bits.peek().litValue
        bitstring += bit.toString
      } else if ((i >= 64 && i < 96) || (i >= 160 && i < 192)) { // wait
        c.io.out.bits.expect(0.U)
        c.io.out.fwClock.expect(false.B)
      }

      c.clock.step()
    }

    c.io.out.bits.expect(0.U)
    c.io.out.fwClock.expect(false.B)
    c.io.in.ready.expect(true.B)

    printDebug(s"[TEST] Captured Bitstring:    ${bitstring.reverse}")
    printDebug(s"[TEST] Number of bits serialized: ${bitstring.length}")
    printDebug("[TEST] Success")
  }

  describe("Serialize RAW bits") {
    val numPackets = 1
    it(s"Serialized ${numPackets} RAW packet(s) (64 bits per packet)") {
      simulate(new SidebandLinkSerializer(sbLinkW, msgW)) { c =>
        val waitCyclesCtrl = 5 // max of random wait cycles
        val seed = 0
        val rand = new scala.util.Random(seed)
        val bitWidth = 64

        // initialize
        c.io.ctrl.txMode.poke(SBRxTxMode.RAW)
        c.io.in.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.fwClock.expect(false.B)

        for (i <- 0 until numPackets) {
          printDebug(s"====== Sending packet ${i + 1} ======")
          val data = BigInt(bitWidth, rand)
          printDebug("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))

          // Send data
          c.io.in.ready.expect(true.B)
          c.io.in.bits.poke(data.U)
          c.io.in.valid.poke(true.B)
          c.clock.step()
          c.io.in.valid.poke(false.B)

          checkSerializedData(c, data.U, bitWidth)

          val waitAmt = rand.nextInt(waitCyclesCtrl)
          if (i != (numPackets - 1)) {
            printDebug(
              s"[TEST] Waiting ${waitAmt} cycles before sending next message"
            )
          }
          if (waitAmt != 0) {
            c.clock.step(waitAmt)
          }
          c.io.in.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.fwClock.expect(false.B)
        }
      }
    }
  }

  describe("Serialize 64-bit sideband packet(s)") {
    val numPackets = 5
    it(s"Serialized ${numPackets} 64-bit packet(s)") {
      simulate(new SidebandLinkSerializer(sbLinkW, msgW)) { c =>
        val waitCyclesCtrl = 5 // max of random wait cycles
        val seed = 234
        val rand = new scala.util.Random(seed)
        val bitWidth = 64

        printDebug(
          s"[TEST] Starting: Serializing ${numPackets} 64-bit packet(s)"
        )
        // initialize
        c.io.ctrl.txMode.poke(SBRxTxMode.PACKET)
        c.io.in.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.fwClock.expect(false.B)

        // Opcode will be valid but randomly selected. Remaining bits are random.
        for (i <- 0 until numPackets) {
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val randomOpcode = opcodes64Bits(rand.nextInt(opcodes64Bits.length))
          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:  " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits: " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue
          printDebug("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))

          // Send data
          c.io.in.ready.expect(true.B)
          c.io.in.bits.poke(data.U)
          c.io.in.valid.poke(true.B)
          c.clock.step()
          c.io.in.valid.poke(false.B)

          checkSerializedData(c, data.U, bitWidth)

          val waitAmt = rand.nextInt(waitCyclesCtrl)
          if (i != (numPackets - 1)) {
            printDebug(
              s"[TEST] Waiting ${waitAmt} cycles before sending next message"
            )
          }
          if (waitAmt != 0) {
            c.clock.step(waitAmt)
          }
          c.io.in.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.fwClock.expect(false.B)
        }
      }
    }
  }

  describe("Serialize 128-bit sideband packet(s)") {
    val numPackets = 10
    it(s"Serialized ${numPackets} 128-bit packet(s)") {
      simulate(new SidebandLinkSerializer(sbLinkW, msgW)) { c =>
        val waitCyclesCtrl = 5 // max of random wait cycles
        val seed = 2352
        val rand = new scala.util.Random(seed)
        val bitWidth = 128

        printDebug(
          s"[TEST] Starting: Serializing ${numPackets} 128-bit packet(s)"
        )
        // initialize
        c.io.ctrl.txMode.poke(SBRxTxMode.PACKET)
        c.io.in.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.fwClock.expect(false.B)

        // Opcode will be valid but randomly selected. Remaining bits are random.
        for (i <- 0 until numPackets) {
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)

          val randomOpcode = opcodes128Bits(rand.nextInt(opcodes128Bits.length))
          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:  " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits: " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue
          printDebug("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))

          // Send data
          c.io.in.ready.expect(true.B)
          c.io.in.bits.poke(data.U)
          c.io.in.valid.poke(true.B)
          c.clock.step()
          c.io.in.valid.poke(false.B)

          checkSerializedData(c, data.U, bitWidth)

          val waitAmt = rand.nextInt(waitCyclesCtrl)
          if (i != (numPackets - 1)) {
            printDebug(
              s"[TEST] Waiting ${waitAmt} cycles before sending next message"
            )
          }
          if (waitAmt != 0) {
            c.clock.step(waitAmt)
          }
          c.io.in.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.fwClock.expect(false.B)
        }
      }
    }
  }

  describe("Serialize any sideband packet(s)") {
    val numPackets = 10
    it(s"Serialized ${numPackets} sideband packet(s)") {
      simulate(new SidebandLinkSerializer(sbLinkW, msgW)) { c =>
        val waitCyclesCtrl = 5 // max of random wait cycles
        val opcodeRandCtrl = 8 // max of random opcode select
        val seed = 979
        val rand = new scala.util.Random(seed)

        printDebug(
          s"[TEST] Starting: Serializing ${numPackets} sideband packet(s)"
        )
        // initialize
        c.io.ctrl.txMode.poke(SBRxTxMode.PACKET)
        c.io.in.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.fwClock.expect(false.B)

        // Opcode will be valid but randomly selected. Remaining bits are random.
        for (i <- 0 until numPackets) {
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)

          val selectOpcode =
            rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
            if (selectOpcode < (opcodeRandCtrl / 2)) {
              (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
              (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }

          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:  " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits: " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue
          printDebug("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))

          // Send data
          c.io.in.ready.expect(true.B)
          c.io.in.bits.poke(data.U)
          c.io.in.valid.poke(true.B)
          c.clock.step()
          c.io.in.valid.poke(false.B)

          checkSerializedData(c, data.U, bitWidth)

          val waitAmt = rand.nextInt(waitCyclesCtrl)
          if (i != (numPackets - 1)) {
            printDebug(
              s"[TEST] Waiting ${waitAmt} cycles before sending next message"
            )
          }
          if (waitAmt != 0) {
            c.clock.step(waitAmt)
          }
          c.io.in.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.fwClock.expect(false.B)
        }
      }
    }
  }

  describe("Serialize a packet but reset is triggered in the middle") {
    val numPackets = 5
    it(s"Stopped serializing a packet when reset was triggered") {
      simulate(new SidebandLinkSerializer(sbLinkW, msgW)) { c =>
        val waitCyclesCtrl = 5 // max of random wait cycles before next run
        val opcodeRandCtrl = 8 // max of random opcode select
        val seed = 979
        val rand = new scala.util.Random(seed)

        printDebug(s"[TEST] Starting: Serializing sideband packet(s)")
        // initialize
        c.io.ctrl.txMode.poke(SBRxTxMode.PACKET)
        c.io.in.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.fwClock.expect(false.B)

        // Opcode will be valid but randomly selected. Remaining bits are random.
        for (i <- 0 until numPackets) {
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)

          val selectOpcode =
            rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
            if (selectOpcode < (opcodeRandCtrl / 2)) {
              (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
              (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }

          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:  " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits: " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue
          printDebug("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))

          // Send data
          c.io.in.ready.expect(true.B)
          c.io.in.bits.poke(data.U)
          c.io.in.valid.poke(true.B)
          c.clock.step()
          c.io.in.valid.poke(false.B)

          // wait a random amount between 0 and size of the packet before triggering reset
          // NOTE: When it serializes messages it can should interrupt in wait as well
          //       the captured bit string will have string of 0s if it interrupts in the wait
          val resetWaitAmt = rand.nextInt(bitWidth + 32)
          printDebug(
            s"[TEST] Waiting ${resetWaitAmt} cycles before triggering reset"
          )
          var bitstring = ""
          for (i <- 0 until resetWaitAmt) {
            val bit = c.io.out.bits.peek().litValue
            bitstring += bit.toString
            c.io.in.ready.expect(false.B)
            c.clock.step()
          }

          printDebug(s"[TEST] Captured Bitstring:    ${bitstring.reverse}")
          printDebug(s"[TEST] Number of bits serialized: ${bitstring.length}")

          c.reset.poke(true.B)
          c.clock.step(10)
          c.reset.poke(false.B)

          c.io.in.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.fwClock.expect(false.B)

          val waitAmt = rand.nextInt(waitCyclesCtrl)
          if (waitAmt != 0) {
            c.clock.step(waitAmt)
          }
          c.io.in.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.fwClock.expect(false.B)
        }
      }
    }
  }

  // ==========================================================================
  // Deserializer tests
  // ==========================================================================

  def serializeData(
      c: DeserializerTestHarness,
      refData: UInt,
      len: Int,
      resetCyclesTarget: Int = -1,
      timeoutCyclesTarget: Int = -1
  ): Unit = {

    val totalBits = len + ((len / 64) * 32)
    var resetWaitCount = 0
    var timeoutWaitCount = 0

    def driveDataBit(bit: UInt): Unit = {
      c.io.in.bits.poke(bit)
      c.io.in.fwClock.poke(true.B)
      c.io.out.msg.valid.expect(false.B)
      c.clock.step()
      c.io.in.fwClock.poke(false.B)
      c.clock.step()
    }

    def driveIdleCycle(): Unit = {
      c.io.in.bits.poke(0.U)
      c.io.in.fwClock.poke(false.B)
      c.clock.step()
    }

    for (i <- 0 until totalBits) {

      // reset test triggers reset at target cycles, and exits the function
      resetWaitCount += 1
      if (resetWaitCount == resetCyclesTarget) { // -1 is used as no test
        c.reset.poke(true.B)
        c.clock.step(2)
        return
      }

      // will stop serialzing randomly during the data or the wait
      timeoutWaitCount += 1
      if (timeoutWaitCount == timeoutCyclesTarget) { // -1 is used as no test
        c.io.in.bits.poke(0.U)
        c.io.in.fwClock.poke(false.B)
        return
      }

      // serialize data
      if ((i >= 0 && i < 64) || (i >= 96 && i < 160)) { // data
        val bitIdx = {
          if (i >= 96 && i < 160) { i - 32 }
          else { i }
        }
        driveDataBit(refData(bitIdx))
      } else if ((i >= 64 && i < 96) || (i >= 160 && i < 192)) { // wait
        driveIdleCycle()
      }
    }
  }

  def expectDeserializerPacket(
      c: DeserializerTestHarness,
      data: BigInt,
      bitWidth: Int
  ): Unit = {
    var guard = 0
    while (!c.io.out.msg.valid.peek().litToBoolean && guard < 50) {
      c.clock.step()
      guard += 1
    }
    assert(guard < 50, "Deserializer did not produce output")
    c.io.out.msg.valid.expect(true.B)

    val fullStr =
      c.io.out.msg.bits
        .peek()
        .litValue
        .toString(2)
        .reverse
        .padTo(bitWidth, '0')
        .reverse
    val capturedData = if (bitWidth == 64) fullStr.takeRight(64) else fullStr

    c.io.out.msg.ready.poke(true.B)
    c.clock.step(2)
    c.io.out.msg.ready.poke(false.B)
    c.io.out.msg.valid.expect(false.B)

    assert(
      capturedData == toBits(data, bitWidth),
      s"[TEST] Error in deserialization"
    )
  }

  def resetDeserializer(
      c: DeserializerTestHarness,
      rxMode: SBRxTxMode.Type
  ): Unit = {
    c.io.ctrl.rxMode.poke(rxMode)
    c.io.in.bits.poke(0.U)
    c.io.in.fwClock.poke(false.B)
    c.io.out.msg.ready.poke(false.B)
    c.reset.poke(true.B)
    c.clock.step(5)
    c.reset.poke(false.B)
    c.clock.step(10)
  }

  def driveDataEdges(
      c: DeserializerTestHarness,
      data: BigInt,
      start: Int,
      count: Int
  ): Unit = {
    for (bitIdx <- start until (start + count)) {
      c.io.in.bits.poke(((data >> bitIdx) & 1).U)
      c.io.in.fwClock.poke(true.B)
      c.clock.step()
      c.io.in.fwClock.poke(false.B)
      c.clock.step()
    }
  }

  def expectNoDeserializerOutput(
      c: DeserializerTestHarness,
      cycles: Int
  ): Unit = {
    for (_ <- 0 until cycles) {
      c.io.out.msg.valid.expect(false.B)
      c.clock.step()
    }
  }

  class DeserializerTestHarness(sbLinkW: Int, msgW: Int, timeoutCycles: Int)
      extends Module {
    val io = IO(new Bundle {
      val ctrl = new Bundle {
        val rxMode = Input(SBRxTxMode())
        val desTimedout = Output(Bool())
      }
      val in = new Bundle {
        val bits = Input(UInt(sbLinkW.W))
        val fwClock = Input(Bool())
      }
      val out = new Bundle {
        val msg = Decoupled(UInt(msgW.W))
      }
    })

    val dut = Module(new SidebandLinkDeserializer(sbLinkW, msgW, timeoutCycles))

    dut.io.ctrl <> io.ctrl
    dut.io.out <> io.out.msg
    dut.io.in.bits := io.in.bits
    dut.io.in.fwClock := io.in.fwClock
  }

  describe("Deserialize a RAW packet(s) (64 bits per packet)") {
    val timeoutCycles = 512
    val numPackets = 5
    it(s"Deserialized ${numPackets} RAW packet(s) (64 bits per packet)") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        val seed = 0
        val rand = new scala.util.Random(seed)
        val bitWidth = 64

        for (i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rxMode.poke(SBRxTxMode.RAW)
          c.io.in.fwClock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for (i <- 0 until 50) {
            c.clock.step()
          }

          printDebug(s"====== Sending packet ${i + 1} ======")
          val data = BigInt(bitWidth, rand)
          printDebug("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          serializeData(c, data.U, bitWidth)

          // check data
          c.io.out.msg.valid.expect(true.B)
          val capturedData =
            c.io.out.msg.bits
              .peek()
              .litValue
              .toString(2)
              .reverse
              .padTo(bitWidth, '0')
              .reverse

          c.io.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.out.msg.valid
            .expect(false.B) // valid should toggle low once data accepted

          printDebug("[TEST] Captured Bits (Binary): " + capturedData)
          printDebug(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(
            capturedData == toBits(data, bitWidth),
            s"[TEST] Error in deserialization"
          )
          printDebug("[TEST] Success")
        }
      }
    }
  }

  describe("Deserialize a sideband packet(s) (64 bits per packet)") {
    val timeoutCycles = 512
    val numPackets = 5
    it(s"Deserialized ${numPackets} sideband packet(s) (64 bits per packet)") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        val seed = 0
        val rand = new scala.util.Random(seed)
        val bitWidth = 64

        for (i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
          c.io.in.fwClock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for (i <- 0 until 50) {
            c.clock.step()
          }
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val randomOpcode = opcodes64Bits(rand.nextInt(opcodes64Bits.length))
          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:   " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits:  " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue

          printDebug("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          serializeData(c, data.U, bitWidth)

          // check data
          c.clock.step()
          c.io.out.msg.valid.expect(true.B)
          val capturedData =
            c.io.out.msg.bits
              .peek()
              .litValue
              .toString(2)
              .reverse
              .padTo(bitWidth, '0')
              .reverse

          c.io.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.out.msg.valid
            .expect(false.B) // valid should toggle low once data accepted

          printDebug("[TEST] Captured Bits (Binary): " + capturedData)
          printDebug(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(
            capturedData == toBits(data, bitWidth),
            s"[TEST] Error in deserialization"
          )
          printDebug("[TEST] Success")
        }
      }
    }
  }

  describe("Deserialize a sideband packet(s) (128 bits per packet)") {
    val timeoutCycles = 512
    val numPackets = 5
    it(s"Deserialized ${numPackets} sideband packet(s) (64 bits per packet)") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        val seed = 0
        val rand = new scala.util.Random(seed)
        val bitWidth = 128

        for (i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
          c.io.in.fwClock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for (i <- 0 until 50) {
            c.clock.step()
          }
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)

          val randomOpcode = opcodes128Bits(rand.nextInt(opcodes128Bits.length))

          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:   " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits:  " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue

          printDebug("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          serializeData(c, data.U, bitWidth)

          // check data
          c.clock.step()
          c.io.out.msg.valid.expect(true.B)
          val capturedData =
            c.io.out.msg.bits
              .peek()
              .litValue
              .toString(2)
              .reverse
              .padTo(bitWidth, '0')
              .reverse

          c.io.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.out.msg.valid
            .expect(false.B) // valid should toggle low once data accepted

          printDebug("[TEST] Captured Bits (Binary): " + capturedData)
          printDebug(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(
            capturedData == toBits(data, bitWidth),
            s"[TEST] Error in deserialization"
          )
          printDebug("[TEST] Success")
        }
      }
    }
  }

  describe("Deserialize any sideband packet(s)") {
    val timeoutCycles = 512
    val numPackets = 5
    val opcodeRandCtrl = 8 // max of random opcode select

    it(s"Deserialized ${numPackets} sideband packet(s)") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        val seed = 0
        val rand = new scala.util.Random(seed)

        for (i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
          c.io.in.fwClock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for (i <- 0 until 50) {
            c.clock.step()
          }
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)

          val selectOpcode =
            rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
            if (selectOpcode < (opcodeRandCtrl / 2)) {
              (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
              (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }

          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:   " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits:  " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue

          printDebug("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          serializeData(c, data.U, bitWidth)

          // check data
          c.clock.step()
          c.io.out.msg.valid.expect(true.B)
          val fullStr =
            c.io.out.msg.bits
              .peek()
              .litValue
              .toString(2)
              .reverse
              .padTo(bitWidth, '0')
              .reverse
          val capturedData = {
            if (bitWidth == 64) { fullStr.takeRight(64) }
            else { fullStr }
          }

          c.io.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.out.msg.valid
            .expect(false.B) // valid should toggle low once data accepted

          printDebug("[TEST] Captured Bits (Binary): " + capturedData)
          printDebug(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(
            capturedData == toBits(data, bitWidth),
            s"[TEST] Error in deserialization"
          )
          printDebug("[TEST] Success")
        }
      }
    }
  }

  describe("Deserialize every sideband opcode") {
    val timeoutCycles = 512

    it("Deserialized every opcode with the expected packet length") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.in.fwClock.poke(false.B)
        c.io.out.msg.ready.poke(false.B)
        c.clock.step(50)

        for (opcode <- SBMsgOpcode.all) {
          val bitWidth = bitWidthForOpcode(opcode)
          val data = dataForOpcode(opcode, bitWidth)

          serializeData(c, data.U, bitWidth)
          expectDeserializerPacket(c, data, bitWidth)
          c.clock.step(50)
        }
      }
    }
  }

  describe("Deserialize exact forwarded-clock edge boundaries") {
    val timeoutCycles = 512

    it("Emitted only after the final expected data edge") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        def check64(rxMode: SBRxTxMode.Type, opcode: SBMsgOpcode.Type): Unit = {
          val bitWidth = 64
          val data = dataForOpcode(opcode, bitWidth)

          resetDeserializer(c, rxMode)
          driveDataEdges(c, data, 0, 63)
          expectNoDeserializerOutput(c, 10)
          driveDataEdges(c, data, 63, 1)
          expectDeserializerPacket(c, data, bitWidth)
        }

        def check128(opcode: SBMsgOpcode.Type): Unit = {
          val bitWidth = 128
          val data = dataForOpcode(opcode, bitWidth)

          resetDeserializer(c, SBRxTxMode.PACKET)
          driveDataEdges(c, data, 0, 64)
          expectNoDeserializerOutput(c, 32)
          driveDataEdges(c, data, 64, 63)
          expectNoDeserializerOutput(c, 10)
          driveDataEdges(c, data, 127, 1)
          expectDeserializerPacket(c, data, bitWidth)
        }

        check64(SBRxTxMode.RAW, SBMsgOpcode.MemoryRead_64b)
        check64(SBRxTxMode.PACKET, SBMsgOpcode.CompletionWithoutData)
        check128(SBMsgOpcode.MemoryWrite_64b)
      }
    }
  }

  describe("Deserializer deterministic reset boundaries") {
    val timeoutCycles = 512

    it("Dropped partial packets across reset points") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        val data = dataForOpcode(SBMsgOpcode.MemoryWrite_64b, 128)
        val resetTargets = Seq(3, 6, 70, 110, 128)

        for (resetAt <- resetTargets) {
          resetDeserializer(c, SBRxTxMode.PACKET)
          serializeData(c, data.U, 128, resetCyclesTarget = resetAt)

          for (_ <- 0 until 20) {
            c.io.out.msg.valid.expect(false.B)
            c.clock.step()
          }

          c.reset.poke(false.B)
          c.clock.step(10)
          c.io.out.msg.valid.expect(false.B)
        }
      }
    }

  }

  describe("Deserializer output backpressure") {
    val timeoutCycles = 512

    it("Held valid data stable until consumed") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        def checkBackpressure(opcode: SBMsgOpcode.Type): Unit = {
          val bitWidth = bitWidthForOpcode(opcode)
          val data = dataForOpcode(opcode, bitWidth)

          resetDeserializer(c, SBRxTxMode.PACKET)
          serializeData(c, data.U, bitWidth)

          var guard = 0
          while (!c.io.out.msg.valid.peek().litToBoolean && guard < 50) {
            c.clock.step()
            guard += 1
          }
          assert(guard < 50, "Deserializer did not produce output")

          val heldBits = c.io.out.msg.bits.peek().litValue
          for (_ <- 0 until 16) {
            c.io.out.msg.valid.expect(true.B)
            c.io.out.msg.bits.expect(heldBits.U)
            c.clock.step()
          }

          c.io.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.out.msg.ready.poke(false.B)
          c.io.out.msg.valid.expect(false.B)

          assert(
            heldBits == data,
            s"[TEST] Error in backpressured output for opcode $opcode"
          )
        }

        checkBackpressure(SBMsgOpcode.CompletionWithoutData)
        checkBackpressure(SBMsgOpcode.MemoryWrite_64b)
      }
    }
  }

  describe("Deserialize a packet but reset is triggered in the middle") {
    val timeoutCycles = 512
    val numPackets = 5
    val opcodeRandCtrl = 8 // max of random opcode select

    it(s"Stopped serializing a packet when reset was triggered") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        val seed = 0
        val rand = new scala.util.Random(seed)

        for (i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
          c.io.in.fwClock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for (i <- 0 until 50) {
            c.clock.step()
          }
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)

          val selectOpcode =
            rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
            if (selectOpcode < (opcodeRandCtrl / 2)) {
              (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
              (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }

          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:   " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits:  " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue

          printDebug("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          val resetWaitAmt = rand.nextInt(bitWidth + 31) + 1

          printDebug(
            "[TEST] Number of cycles before triggering reset: " + resetWaitAmt
          )

          serializeData(c, data.U, bitWidth, resetCyclesTarget = resetWaitAmt)

          // check data
          /*
            Note if the forwarded clock keeps running then deserializer would sample
            after reset, but whole ucie would reset, and won't be respondeding to the partner, so
            the partner will timeout
           */
          // async FIFO mem is not reset, so out.bits may be stale
          for (i <- 0 until 50) {
            c.io.out.msg.valid
              .expect(false.B, "Should not output a partial packet")
            c.clock.step()
          }
          printDebug("[TEST] Success")
          c.reset.poke(false.B)
        }
      }
    }
  }

  describe(
    "Deserializer signals a timeout when the serializer stops sending a packet"
  ) {
    val timeoutCycles = 1000
    val numPackets = 5
    val opcodeRandCtrl = 8 // max of random opcode select

    it(s"Triggered a timeout when serializer stopped sending a packet") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        val seed = 0
        val rand = new scala.util.Random(seed)
        for (i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rxMode.poke(SBRxTxMode.PACKET)
          c.io.in.fwClock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for (i <- 0 until 50) {
            c.clock.step()
          }
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)

          val selectOpcode =
            rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
            if (selectOpcode < (opcodeRandCtrl / 2)) {
              (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
              (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }

          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:   " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits:  " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue

          printDebug("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          // Stop mid-data after the opcode, before the last bit
          val timeoutWaitAmt = rand.nextInt(40) + 6

          printDebug(
            "[TEST] Number of cycles before killing serializer: " + timeoutWaitAmt
          )

          serializeData(
            c,
            data.U,
            bitWidth,
            timeoutCyclesTarget = timeoutWaitAmt
          )

          // check timeout signal
          c.clock.step(timeoutCycles)
          c.io.ctrl.desTimedout.expect(true.B, "Should've triggered timeout")
          printDebug("[TEST] Successfully triggered a timeout")

          // Asssuming once a timeout is triggered you'd reset everything because BER for sideband
          // is 1e-27 or better, so spec doesn't have a retry mechanism on SB messages
          c.reset.poke(true.B)
          c.clock.step(5)
          c.reset.poke(false.B)

        }
      }
    }

    it("Recovered after reset from a timeout") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, 256)))
      { c =>
        val partialData = dataForOpcode(SBMsgOpcode.MemoryWrite_64b, 128)
        val cleanData = dataForOpcode(SBMsgOpcode.CompletionWithoutData, 64)

        resetDeserializer(c, SBRxTxMode.PACKET)
        serializeData(c, partialData.U, 128, timeoutCyclesTarget = 20)
        c.clock.step(256)
        c.io.ctrl.desTimedout.expect(true.B)

        c.reset.poke(true.B)
        c.clock.step(5)
        c.reset.poke(false.B)
        c.clock.step(10)
        c.io.ctrl.desTimedout.expect(false.B)

        serializeData(c, cleanData.U, 64)
        expectDeserializerPacket(c, cleanData, 64)
      }
    }
  }

  describe("Deserializer forwarded-clock domain reset behavior") {
    val timeoutCycles = 512

    it(
      "Dropped a packet when reset asserted while the forwarded clock keeps running"
    ) {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        val data = dataForOpcode(SBMsgOpcode.MemoryWrite_64b, 128)
        val cleanData = dataForOpcode(SBMsgOpcode.CompletionWithoutData, 64)

        resetDeserializer(c, SBRxTxMode.PACKET)

        // Get mid-packet so the forwarded-clock domain is actively assembling bits.
        driveDataEdges(c, data, 0, 20)

        // The remote die does not stop transmitting just because we reset: keep
        // the forwarded clock toggling while reset is high. Those edges clock the
        // fwClock-domain registers through their (async) reset arm.
        c.reset.poke(true.B)
        driveDataEdges(c, data, 20, 8)
        c.clock.step(3)
        c.reset.poke(false.B)
        c.io.in.bits.poke(0.U)
        c.io.in.fwClock.poke(false.B)
        c.clock.step(10)

        // The partial packet must not escape.
        expectNoDeserializerOutput(c, 20)

        // And reception must still work afterwards.
        serializeData(c, cleanData.U, 64)
        expectDeserializerPacket(c, cleanData, 64)
      }
    }

    it("Held the timed-out state until reset released it") {
      simulate((new DeserializerTestHarness(sbLinkW, msgW, timeoutCycles)))
      { c =>
        val partialData = dataForOpcode(SBMsgOpcode.MemoryWrite_64b, 128)
        val cleanData = dataForOpcode(SBMsgOpcode.CompletionWithoutData, 64)

        resetDeserializer(c, SBRxTxMode.PACKET)
        serializeData(c, partialData.U, 128, timeoutCyclesTarget = 12)

        // Let the watchdog reach its limit, then SIT in the timed-out state so
        // the counter's hold branch (counter == desTimeoutCycles) is exercised.
        c.clock.step(timeoutCycles + 4)
        for (_ <- 0 until 20) {
          c.io.ctrl.desTimedout.expect(true.B)
          c.clock.step()
        }

        c.reset.poke(true.B)
        c.clock.step(5)
        c.reset.poke(false.B)
        c.clock.step(10)
        c.io.ctrl.desTimedout.expect(false.B)

        serializeData(c, cleanData.U, 64)
        expectDeserializerPacket(c, cleanData, 64)
      }
    }
  }

  class LinkSerdesTestHarness extends Module {
    val io = IO(new Bundle {
      val ctrl = new Bundle {
        val rxtxMode = Input(SBRxTxMode())
        val desTimedout = Output(Bool())
      }
      val serializerIO = new Bundle {
        val in = new Bundle {
          val msg = Flipped(Decoupled(UInt(msgW.W)))
        }
        val out = new Bundle {
          val bits = Output(UInt(sbLinkW.W))
          val fwClock = Output(Bool())
        }
      }
      val deserializerIO = new Bundle {
        val out = new Bundle {
          val msg = Decoupled(UInt(msgW.W))
        }
      }
    })

    val ser = Module(new SidebandLinkSerializer(sbLinkW, msgW))
    val des = Module(new SidebandLinkDeserializer(sbLinkW, msgW, 1000))

    ser.io.ctrl.txMode := io.ctrl.rxtxMode
    des.io.ctrl.rxMode := io.ctrl.rxtxMode

    ser.io.in <> io.serializerIO.in.msg
    io.serializerIO.out.bits := ser.io.out.bits
    io.serializerIO.out.fwClock := ser.io.out.fwClock

    des.io.in.bits := ser.io.out.bits
    des.io.in.fwClock := ser.io.out.fwClock

    des.io.out <> io.deserializerIO.out.msg
    io.ctrl.desTimedout := des.io.ctrl.desTimedout
  }

  def resetLoopback(c: LinkSerdesTestHarness): Unit = {
    c.io.ctrl.rxtxMode.poke(SBRxTxMode.PACKET)
    c.io.serializerIO.in.msg.valid.poke(false.B)
    c.io.deserializerIO.out.msg.ready.poke(false.B)
    c.reset.poke(true.B)
    c.clock.step(5)
    c.reset.poke(false.B)
    c.clock.step(20)
  }

  def expectLoopbackPacket(
      c: LinkSerdesTestHarness,
      data: BigInt,
      bitWidth: Int
  ): Unit = {
    var guard = 0
    while (!c.io.deserializerIO.out.msg.valid.peek().litToBoolean) {
      c.clock.step()
      guard += 1
      assert(guard < 5000, "Loopback: deserializer never produced output")
    }

    val fullStr =
      c.io.deserializerIO.out.msg.bits
        .peek()
        .litValue
        .toString(2)
        .reverse
        .padTo(bitWidth, '0')
        .reverse
    val capturedData = if (bitWidth == 64) fullStr.takeRight(64) else fullStr

    c.io.deserializerIO.out.msg.ready.poke(true.B)
    c.clock.step(2)
    c.io.deserializerIO.out.msg.ready.poke(false.B)
    c.io.deserializerIO.out.msg.valid.expect(false.B)

    assert(capturedData == toBits(data, bitWidth), s"[TEST] Error in loopback")
  }

  describe("Sanity test using async queues and a loopback configuration") {
    val timeoutCycles = 1000
    val numPackets = 5
    val opcodeRandCtrl = 8 // max of random opcode select

    it(
      s"Serialized and deserialized single packets in loopback through async queues"
    ) {
      simulate((new LinkSerdesTestHarness))
      { c =>
        c.io.ctrl.rxtxMode.poke(SBRxTxMode.PACKET)
        c.io.serializerIO.in.msg.valid.poke(false.B)
        c.io.deserializerIO.out.msg.ready.poke(false.B)
        c.reset.poke(true.B)
        c.clock.step(5)
        c.reset.poke(false.B)
        c.clock.step()

        val seed = 0
        val rand = new scala.util.Random(seed)
        for (i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rxtxMode.poke(SBRxTxMode.PACKET)
          c.io.serializerIO.in.msg.valid.poke(false.B)
          c.io.deserializerIO.out.msg.ready.poke(false.B)

          for (i <- 0 until 50) {
            c.clock.step()
          }
          printDebug(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = SBMsgOpcode.OpsWithoutData

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)

          val selectOpcode =
            rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
            if (selectOpcode < (opcodeRandCtrl / 2)) {
              (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
              (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }

          printDebug("[TEST] Selected opcode: " + randomOpcode)
          printDebug(
            "[TEST] Selected opcode bits:   " + toBits(
              randomOpcode.litValue,
              bitWidth
            )
          )
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          printDebug(
            "[TEST] Generated random bits:  " + toBits(randData, bitWidth)
          )
          val data = randData | randomOpcode.litValue

          printDebug("[TEST] Serializing (Binary):   " + toBits(data, bitWidth))

          c.io.serializerIO.in.msg.ready.expect(true.B)
          c.io.serializerIO.in.msg.bits.poke(data.U)
          c.io.serializerIO.in.msg.valid.poke(true.B)
          c.clock.step()
          c.io.serializerIO.in.msg.valid.poke(false.B)

          var guard = 0
          while (
            c.io.deserializerIO.out.msg.valid.peek().litToBoolean == false
          ) {
            c.clock.step()
            guard += 1
            assert(guard < 5000, "Loopback: deserializer never produced output")
          }

          c.io.deserializerIO.out.msg.valid.expect(true.B)
          val fullStr =
            c.io.deserializerIO.out.msg.bits
              .peek()
              .litValue
              .toString(2)
              .reverse
              .padTo(bitWidth, '0')
              .reverse

          val capturedData = {
            if (bitWidth == 64) { fullStr.takeRight(64) }
            else { fullStr }
          }

          c.io.deserializerIO.out.msg.ready.poke(true.B)
          c.clock.step(2)

          // valid should toggle low once data accepted
          c.io.deserializerIO.out.msg.valid.expect(false.B)

          printDebug("[TEST] Captured Bits (Binary): " + capturedData)
          printDebug(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(
            capturedData == toBits(data, bitWidth),
            s"[TEST] Error in deserialization"
          )
          printDebug("[TEST] Success")

        }
      }
    }

    it("Preserved the 32-bit wait with a queued back-to-back packet") {
      simulate((new LinkSerdesTestHarness))
      { c =>
        def checkPair(
            firstOpcode: SBMsgOpcode.Type,
            secondOpcode: SBMsgOpcode.Type
        ): Unit = {
          val firstWidth = bitWidthForOpcode(firstOpcode)
          val secondWidth = bitWidthForOpcode(secondOpcode)
          val firstData = dataForOpcode(firstOpcode, firstWidth)
          val secondData = dataForOpcode(secondOpcode, secondWidth)
          val cyclesToFinalWait =
            firstWidth + (((firstWidth / 64) - 1) * 32) + 1
          var acceptedSecond = false

          resetLoopback(c)

          c.io.serializerIO.in.msg.ready.expect(true.B)
          c.io.serializerIO.in.msg.bits.poke(firstData.U)
          c.io.serializerIO.in.msg.valid.poke(true.B)
          c.clock.step()
          c.io.serializerIO.in.msg.valid.poke(false.B)

          c.clock.step(cyclesToFinalWait)

          c.io.serializerIO.in.msg.bits.poke(secondData.U)
          c.io.serializerIO.in.msg.valid.poke(true.B)
          for (_ <- 0 until 32) {
            c.io.serializerIO.out.bits.expect(0.U)
            c.io.serializerIO.out.fwClock.expect(false.B)
            acceptedSecond ||= c.io.serializerIO.in.msg.ready
              .peek()
              .litToBoolean
            c.clock.step()
            if (acceptedSecond) {
              c.io.serializerIO.in.msg.valid.poke(false.B)
            }
          }

          assert(
            acceptedSecond,
            "Serializer did not accept the queued back-to-back packet"
          )
          expectLoopbackPacket(c, firstData, firstWidth)
          expectLoopbackPacket(c, secondData, secondWidth)
        }

        checkPair(
          SBMsgOpcode.CompletionWithoutData,
          SBMsgOpcode.MemoryWrite_64b
        )
        checkPair(
          SBMsgOpcode.MemoryWrite_64b,
          SBMsgOpcode.CompletionWithoutData
        )
      }
    }

    it("Serialized and deserialized every opcode in loopback") {
      simulate((new LinkSerdesTestHarness))
      { c =>
        c.io.ctrl.rxtxMode.poke(SBRxTxMode.PACKET)
        c.io.serializerIO.in.msg.valid.poke(false.B)
        c.io.deserializerIO.out.msg.ready.poke(false.B)
        c.reset.poke(true.B)
        c.clock.step(5)
        c.reset.poke(false.B)
        c.clock.step(50)

        for (opcode <- SBMsgOpcode.all) {
          val bitWidth = bitWidthForOpcode(opcode)
          val data = dataForOpcode(opcode, bitWidth)

          c.io.serializerIO.in.msg.ready.expect(true.B)
          c.io.serializerIO.in.msg.bits.poke(data.U)
          c.io.serializerIO.in.msg.valid.poke(true.B)
          c.clock.step()
          c.io.serializerIO.in.msg.valid.poke(false.B)

          var guard = 0
          while (!c.io.deserializerIO.out.msg.valid.peek().litToBoolean) {
            c.clock.step()
            guard += 1
            assert(
              guard < 5000,
              s"Loopback: opcode $opcode never produced output"
            )
          }

          val fullStr =
            c.io.deserializerIO.out.msg.bits
              .peek()
              .litValue
              .toString(2)
              .reverse
              .padTo(bitWidth, '0')
              .reverse
          val capturedData =
            if (bitWidth == 64) fullStr.takeRight(64) else fullStr

          c.io.deserializerIO.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.deserializerIO.out.msg.ready.poke(false.B)
          c.io.deserializerIO.out.msg.valid.expect(false.B)

          assert(
            capturedData == toBits(data, bitWidth),
            s"[TEST] Error in loopback for opcode $opcode"
          )
          c.clock.step(50)
        }
      }
    }
  }
}
