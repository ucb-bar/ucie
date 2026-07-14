/*
  Description:
    Contains the logic for the serializer, and deserializer for
    the sideband messaging over the physical UCIe link.
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import circt.stage.ChiselStage
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.Ser21
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}

// ============================================================================
// Sideband Link Serializer
// ============================================================================
class SidebandLinkSerializer(val sbLinkW: Int, val msgW: Int) extends Module {
  val io = IO(new Bundle {
    val ctrl = new Bundle {
      val txMode = Input(SBRxTxMode())
    }
    val in = Flipped(Decoupled(UInt(msgW.W)))

    val out = new Bundle {
      val bits = Output(UInt(sbLinkW.W))
      val fwClock = Output(Bool())
    }
  })

  object SerializerState extends ChiselEnum {
    val sIdle, sBitsSend, sBitsWait = Value
  }

  require(sbLinkW == 1, "Sideband link width must be 1 per spec")

  val currentState = RegInit(SerializerState.sIdle)
  val numBitsToSend = 64       // As per spec, tansmit in 64 bit chunks
  val numBitsToWait = 32       // As per spec, need to wait 32 bits

  val packet = RegInit(0.U(msgW.W))
  val pktOpcode = RegInit(0.U(5.W))
  val outClkEn = Wire(Bool())
  val outBitsCntEn = Wire(Bool())
  val waitBitsCntEn = Wire(Bool())

  val beatCount = RegInit(0.U(4.W))
  val numBeats = Wire(UInt(4.W))
  val doneSending = Wire(Bool())
  val newPacketValid = Wire(Bool())

  doneSending := beatCount === numBeats
  newPacketValid := io.in.valid && (currentState =/= SerializerState.sIdle)

  val (outBitsCount, outBitsDone) = Counter(outBitsCntEn, numBitsToSend)

  val (waitBitsCount, waitBitsDone) = Counter(waitBitsCntEn, numBitsToWait)

  // Packet opcodes without data
  val isWoData = SBMsgOpcode.OpsWithoutData.map(_.asUInt === pktOpcode).reduce(_ || _)
  val inputIsWoData = SBMsgOpcode.OpsWithoutData.map(_.asUInt === io.in.bits(4,0)).reduce(_ || _)
  val acceptRaw = io.in.fire && io.ctrl.txMode === SBRxTxMode.RAW
  val acceptPacketNoData = io.in.fire && io.ctrl.txMode === SBRxTxMode.PACKET && inputIsWoData
  val acceptPacketWithData = io.in.fire && io.ctrl.txMode === SBRxTxMode.PACKET && !inputIsWoData

  // txMode: RAW means send raw bits don't look at opcode (will be 64 bits)
  when(isWoData || (io.ctrl.txMode === SBRxTxMode.RAW)) {
    numBeats := 1.U       // messages w/o data (1 64 bit chunk)
  }.otherwise {
    numBeats := 2.U       // messages w/ data  (2 64-bit chunk)
  }

  val txsbd = Module(new Ser21)
  val txsbc = Module(new Ser21)

  val dataBit = Mux(outClkEn, packet(sbLinkW - 1, 0), 0.U)

  txsbd.io.clk := clock
  txsbd.io.d0 := dataBit
  txsbd.io.d1 := dataBit

  txsbc.io.clk := clock
  txsbc.io.d0 := outClkEn.asUInt
  txsbc.io.d1 := 0.U

  io.out.bits := txsbd.io.out
  io.out.fwClock := txsbc.io.out.asBool

  // defaults
  io.in.ready := false.B
  outClkEn := false.B
  outBitsCntEn := false.B
  waitBitsCntEn := false.B

    // state action
  switch(currentState) {
    is(SerializerState.sIdle) {
      assert(outBitsCount === 0.U,  "Output bit counter should start at 0")
      assert(waitBitsCount === 0.U, "Wait bit counter should start at 0")

      io.in.ready := true.B
      outClkEn := false.B
      outBitsCntEn := false.B
      when(io.in.valid) {
        beatCount := 0.U
        packet := io.in.bits
        pktOpcode := io.in.bits(4,0)
      }
    }
    is(SerializerState.sBitsSend) {
      io.in.ready := false.B
      outClkEn := true.B
      outBitsCntEn := true.B
      waitBitsCntEn := false.B
      packet := packet >> sbLinkW.U

      when(outBitsDone) {
        beatCount := beatCount + 1.U
      }
    }
    is(SerializerState.sBitsWait) {
      io.in.ready := false.B
      outClkEn := false.B
      outBitsCntEn := false.B
      waitBitsCntEn := true.B

      // finished sending current packet, but there is a valid packet
      // ready -- doing so removes a cycle delay between sending packets
      when(doneSending && newPacketValid) {
        beatCount := 0.U
        io.in.ready := true.B
        packet := io.in.bits
        pktOpcode := io.in.bits(4,0)
      }
    }
  }

  // state transition
  switch(currentState) {
    is(SerializerState.sIdle) {
      when(io.in.valid && io.in.ready) {
        currentState := SerializerState.sBitsSend
      }
    }
    is(SerializerState.sBitsSend) {
      when(outBitsDone) {
        currentState := SerializerState.sBitsWait
      }
    }
    is(SerializerState.sBitsWait) {
      when(waitBitsDone) {
        when(doneSending && !newPacketValid) {
          currentState := SerializerState.sIdle     // wait for new message
        }.otherwise {
          currentState := SerializerState.sBitsSend // more beats to send
        }
      }
    }
  }

  // ==========================================================================
  // Assertions
  // ==========================================================================
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(currentState =/= SerializerState.sBitsSend) |->
          Sequence.BoolSequence(!outClkEn && dataBit === 0.U),
        label = Some("SidebandSerializerLowOutsideSend")
      )
    }
    block(Verification.Cover) {
      val firstWaitDone = currentState === SerializerState.sBitsWait && waitBitsDone && beatCount === 1.U
      val finalWaitDone = currentState === SerializerState.sBitsWait && waitBitsDone && doneSending
      val trackingPacket128 = RegInit(false.B)

      when(acceptPacketWithData) {
        trackingPacket128 := true.B
      }.elsewhen(finalWaitDone) {
        trackingPacket128 := false.B
      }

      cover(acceptRaw, "SidebandLinkSerializerAcceptRaw64")
      cover(acceptPacketNoData, "SidebandLinkSerializerAcceptPacket64")
      cover(acceptPacketWithData, "SidebandLinkSerializerAcceptPacket128")
      cover(currentState === SerializerState.sBitsSend && outClkEn, "SidebandLinkSerializerSendState")
      cover(currentState === SerializerState.sBitsWait && waitBitsCntEn, "SidebandLinkSerializerWaitState")
      cover(finalWaitDone && numBeats === 1.U, "SidebandLinkSerializer64bCompletes")
      cover(trackingPacket128 && firstWaitDone, "SidebandLinkSerializer128bFirstChunkWaitDone")
      cover(trackingPacket128 && finalWaitDone, "SidebandLinkSerializer128bTwoChunksWithWait")
      cover(doneSending && newPacketValid && currentState === SerializerState.sBitsWait && io.in.ready,
        "SidebandLinkSerializerBackToBackFinalWaitAccept")
      CoverProperty(
        Sequence.BoolSequence(reset.asBool && currentState =/= SerializerState.sIdle),
        disable = None,
        label = Some("SidebandLinkSerializerResetDuringPacket")
      )
    }
  }
}


// ============================================================================
// Sideband Link Deserializer
// ============================================================================
class SidebandLinkDeserializer(val sbLinkW: Int, val msgW: Int, val desTimeoutCycles: Int)
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
    val out = Decoupled(UInt(msgW.W))
  })

  require(sbLinkW == 1, "Sideband link width must be 1 per spec")
  require(desTimeoutCycles > (msgW + 64), "Need to atleast let largest message process")

  val asyncQueueDepth = 2

  val negFwClock = (!io.in.fwClock).asClock

  // The assembled message word crosses to the local clock domain through an async FIFO.
  val rxQueue = Module(new AsyncQueue(UInt(msgW.W), AsyncQueueParams(depth = asyncQueueDepth)))
  rxQueue.io.enq_clock := negFwClock
  rxQueue.io.enq_reset := reset.asBool
  rxQueue.io.deq_clock := clock
  rxQueue.io.deq_reset := reset.asBool

  // Assemble the serial bits and enqueue the complete word on the final bit
  // in the forwarded clock domain.
  val idleStatus = withClockAndReset(negFwClock, reset.asAsyncReset) {
    val counter = RegInit(0.U(log2Ceil(msgW).W))
    val maxBits = RegInit((msgW - 1).U(log2Ceil(msgW).W))
    val dataReg = RegInit(0.U(msgW.W))

    val isWoData = SBMsgOpcode.OpsWithoutData.map(_.asUInt === dataReg(4, 0)).reduce(_ || _)
    val recvDone = counter === maxBits

    // Opcode (bits[4:0]) determines message length once the first 5 bits arrive.
    // RAW means read raw bits (will be 64 bits).
    when(counter === 5.U) {
      maxBits := Mux(isWoData || (io.ctrl.rxMode === SBRxTxMode.RAW), 63.U, 127.U)
    }

    val completeWord = dataReg.bitSet(counter, io.in.bits.asBool)
    dataReg := completeWord
    counter := Mux(recvDone, 0.U, counter + 1.U)

    rxQueue.io.enq.valid := recvDone
    rxQueue.io.enq.bits := completeWord
    
    counter === 0.U // returned and assigned to idleStatus
  }

  io.out.valid := rxQueue.io.deq.valid
  io.out.bits := rxQueue.io.deq.bits
  rxQueue.io.deq.ready := io.out.ready

  val idleStatusSync = RegNext(RegNext(idleStatus, true.B), true.B) // 2-FF sync
  val timeoutCounter = RegInit(0.U(log2Ceil(desTimeoutCycles + 1).W))

  when(idleStatusSync) {
    timeoutCounter := 0.U
  }.elsewhen(timeoutCounter =/= desTimeoutCycles.U) {
    timeoutCounter := timeoutCounter + 1.U
  }

  io.ctrl.desTimedout := timeoutCounter === desTimeoutCycles.U

  val outputOpcode = io.out.bits(4, 0)
  val outputIsWoData = SBMsgOpcode.OpsWithoutData.map(_.asUInt === outputOpcode).reduce(_ || _)

  // ==========================================================================
  // Assertions
  // ==========================================================================
  block(Verification) {
    block(Verification.Assert) {
      val outStalled = RegNext(io.out.valid && !io.out.ready, false.B)
      val previousOutBits = RegNext(io.out.bits.asUInt)

      AssertProperty(
        Sequence.BoolSequence(outStalled) |->
          Sequence.BoolSequence(io.out.valid && io.out.bits.asUInt === previousOutBits),
        label = Some("SidebandDeserializerHoldsOutputUnderBackpressure")
      )

      AssertProperty(
          Sequence.BoolSequence(rxQueue.io.enq.valid) |->
            Sequence.BoolSequence(rxQueue.io.enq.ready),
          label = Some("SidebandDeserializerDoesNotDropWords")
        )
    }
    block(Verification.Cover) {
      val sawOutputStall = RegInit(false.B)
      when(io.out.valid && !io.out.ready) {
        sawOutputStall := true.B
      }.elsewhen(io.out.fire) {
        sawOutputStall := false.B
      }

      cover(io.out.fire && io.ctrl.rxMode === SBRxTxMode.RAW, "SidebandLinkDeserializerRaw64Output")
      cover(io.out.fire && io.ctrl.rxMode === SBRxTxMode.PACKET && outputIsWoData, "SidebandLinkDeserializerPacket64Output")
      cover(io.out.fire && io.ctrl.rxMode === SBRxTxMode.PACKET && !outputIsWoData, "SidebandLinkDeserializerPacket128Output")
      cover(io.out.valid && !io.out.ready, "SidebandLinkDeserializerOutputBackpressure")
      cover(io.out.fire && sawOutputStall, "SidebandLinkDeserializerStalledOutputConsumed")
      cover(io.ctrl.desTimedout, "SidebandLinkDeserializerTimeout")
      CoverProperty(
        Sequence.BoolSequence(reset.asBool && !idleStatusSync),
        disable = None,
        label = Some("SidebandLinkDeserializerResetDuringPacket")
      )
    }
  }
}

object MainSBSerdes extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandLinkSerializer(1, 128),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Array(
      "-O=debug",
      "--lowering-options=disallowLocalVariables",
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket",  
    ),
  )

  ChiselStage.emitSystemVerilogFile(
    new SidebandLinkDeserializer(1, 128, 512),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Array(
      "-O=debug",
      "--lowering-options=disallowLocalVariables",
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket",  
    ),
  )
}
