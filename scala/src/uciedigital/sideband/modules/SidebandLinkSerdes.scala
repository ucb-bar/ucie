/*
  Serializer and deserializer for the sideband link (sec 4.1.5).
 */

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.Sync3
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}

class SidebandLinkSerializer(val sbLinkW: Int, val msgW: Int) extends Module {
  val io = IO(new Bundle {
    val ctrl = new Bundle {
      val txMode = Input(SBRxTxMode())
    }
    val in = Flipped(Decoupled(UInt(msgW.W)))

    val out = new SidebandTxPins(sbLinkW)
  })

  object SerializerState extends ChiselEnum {
    val sIdle, sBitsSend, sBitsWait = Value
  }

  require(sbLinkW == 1, "Sideband link width must be 1 per spec")

  // ===========================================================================
  // Framing and outputs
  // ===========================================================================
  val currentState = RegInit(SerializerState.sIdle)
  val numBitsToSend = 64 // sec 4.1.5 sends 64-bit chunks
  val numBitsToWait = 32 // sec 4.1.5 waits 32 bits between chunks

  val packet = RegInit(0.U(msgW.W))
  val pktOpcode = RegInit(0.U(5.W))
  val pktMode = RegInit(SBRxTxMode.PACKET)
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

  val isWoData =
    SBMsgOpcode.OpsWithoutData.map(_.asUInt === pktOpcode).reduce(_ || _)
  val inputIsWoData =
    SBMsgOpcode.OpsWithoutData.map(_.asUInt === io.in.bits(4, 0)).reduce(_ || _)
  val acceptRaw = io.in.fire && io.ctrl.txMode === SBRxTxMode.RAW
  val acceptPacketNoData =
    io.in.fire && io.ctrl.txMode === SBRxTxMode.PACKET && inputIsWoData
  val acceptPacketWithData =
    io.in.fire && io.ctrl.txMode === SBRxTxMode.PACKET && !inputIsWoData

  when(isWoData || (pktMode === SBRxTxMode.RAW)) {
    numBeats := 1.U
  }.otherwise {
    numBeats := 2.U
  }

  // One bit per cycle on d0 and d1. The forwarded clock is this clock, gated.
  val dataBit = Mux(outClkEn, packet(sbLinkW - 1, 0), 0.U)

  io.out.clk := clock
  io.out.d0 := dataBit
  io.out.d1 := dataBit
  io.out.fwClockD0 := outClkEn
  io.out.fwClockD1 := false.B

  // ===========================================================================
  // State machine
  // ===========================================================================
  io.in.ready := false.B
  outClkEn := false.B
  outBitsCntEn := false.B
  waitBitsCntEn := false.B

  switch(currentState) {
    is(SerializerState.sIdle) {
      assert(outBitsCount === 0.U, "Output bit counter should start at 0")
      assert(waitBitsCount === 0.U, "Wait bit counter should start at 0")

      io.in.ready := true.B
      outClkEn := false.B
      outBitsCntEn := false.B
      when(io.in.valid) {
        beatCount := 0.U
        packet := io.in.bits
        pktOpcode := io.in.bits(4, 0)
        pktMode := io.ctrl.txMode
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

      // Accepting the next packet here saves a cycle between packets.
      when(doneSending && newPacketValid) {
        beatCount := 0.U
        io.in.ready := true.B
        packet := io.in.bits
        pktOpcode := io.in.bits(4, 0)
        pktMode := io.ctrl.txMode
      }
    }
  }

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
          currentState := SerializerState.sIdle
        }.otherwise {
          currentState := SerializerState.sBitsSend
        }
      }
    }
  }

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(currentState =/= SerializerState.sBitsSend) |->
          Sequence.BoolSequence(!outClkEn && dataBit === 0.U),
        label = Some("SidebandSerializerLowOutsideSend")
      )
    }
    block(Verification.Cover) {
      val firstWaitDone =
        currentState === SerializerState.sBitsWait && waitBitsDone && beatCount === 1.U
      val finalWaitDone =
        currentState === SerializerState.sBitsWait && waitBitsDone && doneSending
      val trackingPacket128 = RegInit(false.B)

      when(acceptPacketWithData) {
        trackingPacket128 := true.B
      }.elsewhen(finalWaitDone) {
        trackingPacket128 := false.B
      }

      cover(acceptRaw, "SidebandLinkSerializerAcceptRaw64")
      cover(acceptPacketNoData, "SidebandLinkSerializerAcceptPacket64")
      cover(acceptPacketWithData, "SidebandLinkSerializerAcceptPacket128")
      cover(
        currentState === SerializerState.sBitsSend && outClkEn,
        "SidebandLinkSerializerSendState"
      )
      cover(
        currentState === SerializerState.sBitsWait && waitBitsCntEn,
        "SidebandLinkSerializerWaitState"
      )
      cover(
        finalWaitDone && numBeats === 1.U,
        "SidebandLinkSerializer64bCompletes"
      )
      cover(
        trackingPacket128 && firstWaitDone,
        "SidebandLinkSerializer128bFirstChunkWaitDone"
      )
      cover(
        trackingPacket128 && finalWaitDone,
        "SidebandLinkSerializer128bTwoChunksWithWait"
      )
      cover(
        doneSending && newPacketValid && currentState === SerializerState.sBitsWait && io.in.ready,
        "SidebandLinkSerializerBackToBackFinalWaitAccept"
      )
      CoverProperty(
        Sequence.BoolSequence(
          reset.asBool && currentState =/= SerializerState.sIdle
        ),
        disable = None,
        label = Some("SidebandLinkSerializerResetDuringPacket")
      )
    }
  }
}

class SidebandLinkDeserializer(
    val sbLinkW: Int,
    val msgW: Int,
    val desTimeoutCycles: Int,

    // Idle cycles that re-frame the capture, far below sec 4.5.3.2's 1 ms.
    val sbGapCycles: Int = 96
) extends Module {
  val io = IO(new Bundle {
    val ctrl = new Bundle {
      val rxMode = Input(SBRxTxMode())
      val desTimedout = Output(Bool())
    }
    val in = new SidebandRxPins(sbLinkW)
    val flushRegAccess = Input(Bool())
    val clearParityContainment = Input(Bool())
    val out = Decoupled(UInt(msgW.W))
    val outInvalid = Output(Bool())
    val outPacketMode = Output(Bool())
    val headerCpErrEvent = Output(Bool())
  })

  require(sbLinkW == 1, "Sideband link width must be 1 per spec")
  require(
    desTimeoutCycles > (msgW + 64),
    "Need to atleast let largest message process"
  )
  require(
    sbGapCycles > 32,
    "sbGapCycles must exceed the 32 UI the serializer gates between chunks, " +
      "or a 128-bit packet re-frames at its own mid-packet gap"
  )

  // ===========================================================================
  // Clock crossing state
  // ===========================================================================
  val asyncQueueDepth = 2

  val negFwClock = (!io.in.fwClock.asBool).asClock
  val regAccessEpoch = RegInit(false.B)
  val flushRegAccessPrev = RegNext(io.flushRegAccess, false.B)
  val flushRegAccessPulse = io.flushRegAccess && !flushRegAccessPrev
  val flushPending = RegInit(false.B)
  val flushObserved = WireDefault(false.B)

  val flushObservedSync = Sync3(flushObserved)
  val headerCpErrToggle = WireDefault(false.B)
  val headerCpErrAck = WireDefault(false.B)
  val rxAssemblyReset =
    (reset.asBool || io.clearParityContainment).asAsyncReset

  // ===========================================================================
  // Idle detection and re-framing
  // ===========================================================================
  // A gap in the gated forwarded clock (sec 4.1.5) marks where a word starts.
  val rxActivity = Wire(Bool())

  // Sample a capture counter bit, because the clock itself aliases (sec 5.13).
  val activitySync = Sync3(rxActivity)
  val activityPrev = RegNext(activitySync, false.B)
  val gapCounter = RegInit(0.U(log2Ceil(sbGapCycles + 1).W))
  when(activitySync =/= activityPrev) {
    gapCounter := 0.U
  }.elsewhen(gapCounter =/= sbGapCycles.U) {
    gapCounter := gapCounter + 1.U
  }

  // A one-cycle pulse from a flop, so no glitch reaches the async reset.
  val sbGapPulse = RegNext(
    (gapCounter === (sbGapCycles - 1).U) &&

      // RAW only, because a mistimed arm in PACKET mode fails the CP check.
      (io.ctrl.rxMode === SBRxTxMode.RAW),
    false.B
  )

  // Kept apart from rxAssemblyReset, so a gap never clears rxContained.
  val rxFramingArm =
    (reset.asBool || io.clearParityContainment || sbGapPulse).asAsyncReset

  // ===========================================================================
  // Register access flush
  // ===========================================================================
  when(flushRegAccessPulse) {
    regAccessEpoch := !regAccessEpoch
  }
  when(io.flushRegAccess) {
    flushPending := true.B
  }.elsewhen(flushObservedSync) {
    flushPending := false.B
  }

  // ===========================================================================
  // Capture domain
  // ===========================================================================
  val rxQueue = Module(
    new AsyncQueue(
      UInt((msgW + 3).W),
      AsyncQueueParams(depth = asyncQueueDepth)
    )
  )
  rxQueue.io.enq_clock := negFwClock
  rxQueue.io.enq_reset := reset.asBool
  rxQueue.io.deq_clock := clock
  rxQueue.io.deq_reset := reset.asBool

  val (idleStatus, rxActivityOut) =
    withClockAndReset(negFwClock, reset.asAsyncReset) {
      val counter = withReset(rxAssemblyReset) {
        RegInit(0.U(log2Ceil(msgW).W))
      }
      val maxBits = withReset(rxAssemblyReset) {
        RegInit((msgW - 1).U(log2Ceil(msgW).W))
      }
      val dataReg = withReset(rxAssemblyReset) {
        RegInit(0.U(msgW.W))
      }
      val packetEpoch = withReset(rxAssemblyReset) { RegInit(false.B) }
      val packetInvalid = withReset(rxAssemblyReset) { RegInit(false.B) }
      val packetMode = withReset(rxAssemblyReset) { RegInit(false.B) }
      val rxContained = withReset(rxAssemblyReset) { RegInit(false.B) }
      val headerCpErrToggleReg = RegInit(false.B)
      val headerCpErrAckSync = Sync3(headerCpErrAck)
      val epochSync = Sync3(regAccessEpoch)

      // flushPending holds until this echo returns.
      val flushSync = Sync3(flushPending)
      flushObserved := flushSync

      val isWoData = SBMsgOpcode.OpsWithoutData
        .map(_.asUInt === dataReg(4, 0))
        .reduce(_ || _)

      // Set asynchronously, because this domain has no clock in the gap.
      val framingArmed = withReset(rxFramingArm) { RegInit(true.B) }
      framingArmed := false.B
      val effCounter = Mux(framingArmed, 0.U, counter)

      val recvDone = effCounter === maxBits
      val firstBit = effCounter === 0.U
      headerCpErrToggle := headerCpErrToggleReg

      when(firstBit) {
        packetEpoch := epochSync
        packetInvalid := flushSync
        packetMode := io.ctrl.rxMode === SBRxTxMode.PACKET
      }.elsewhen(flushSync) {
        packetInvalid := true.B
      }

      // The opcode sets the length once its 5 bits are in.
      when(effCounter === 5.U && !rxContained) {
        maxBits := Mux(
          isWoData || !packetMode,
          63.U,
          127.U
        )
      }

      val completeWord = dataReg.bitSet(effCounter, io.in.bits.asBool)
      val completeEpoch = Mux(firstBit, epochSync, packetEpoch)
      val completeMode = Mux(
        firstBit,
        io.ctrl.rxMode === SBRxTxMode.PACKET,
        packetMode
      )
      val completeInvalid = Mux(
        firstBit,
        flushSync,
        packetInvalid || flushSync
      )
      val completePacket = Mux(
        completeMode,
        completeWord,
        completeWord & ((BigInt(1) << 64) - 1).U(msgW.W)
      )
      val headerDone = effCounter === 63.U
      val headerCpBad = completeWord(62) ^ completeWord(61, 0).xorR
      val newHeaderError = headerDone && completeMode && headerCpBad

      // Fatal (sec 7.1.3.2), so stay contained until LinkError recovery.
      when(newHeaderError) {
        assert(
          headerCpErrToggleReg === headerCpErrAckSync,
          "Previous header CP event was not observed"
        )
        headerCpErrToggleReg := !headerCpErrToggleReg
      }

      when(rxContained) {
        counter := 0.U
        maxBits := (msgW - 1).U
        dataReg := 0.U
      }.elsewhen(newHeaderError) {
        rxContained := true.B
        counter := 0.U
        maxBits := (msgW - 1).U
        dataReg := 0.U
      }.otherwise {
        dataReg := completeWord
        counter := Mux(recvDone, 0.U, effCounter + 1.U)
      }

      rxQueue.io.enq.valid := recvDone && !newHeaderError && !rxContained
      rxQueue.io.enq.bits := Cat(
        completeInvalid,
        completeEpoch,
        completeMode,
        completePacket
      )

      // Bit 1 changes every second edge, for sampling margin.
      val activityCounter = RegInit(0.U(2.W))
      activityCounter := activityCounter + 1.U

      // Counts a word ending on this edge, because the clock stops after it.
      val wordBoundary = recvDone || newHeaderError || rxContained

      // A flop before the CDC, because the compare can glitch.
      val idleFlag = withReset(rxFramingArm) { RegInit(true.B) }
      idleFlag := wordBoundary

      (idleFlag, activityCounter(1))
    }
  rxActivity := rxActivityOut

  // ===========================================================================
  // Output
  // ===========================================================================
  io.out.valid := rxQueue.io.deq.valid
  io.out.bits := rxQueue.io.deq.bits(msgW - 1, 0)
  rxQueue.io.deq.ready := io.out.ready

  val headerCpErrSync = Sync3(headerCpErrToggle)
  val headerCpErrSyncPrev = RegNext(headerCpErrSync, false.B)
  headerCpErrAck := headerCpErrSync
  io.headerCpErrEvent := headerCpErrSync ^ headerCpErrSyncPrev

  val outputInvalidAtIngress = rxQueue.io.deq.bits(msgW + 2)
  val outputEpoch = rxQueue.io.deq.bits(msgW + 1)
  val outputWasPacketMode = rxQueue.io.deq.bits(msgW)
  io.outPacketMode := outputWasPacketMode
  val outputOpcode = io.out.bits(4, 0)
  val outputIsRegRequest = SBM.isRegAccessRequest(outputOpcode)
  val outputIsRegCompletion = SBM.isRegAccessComplete(outputOpcode)
  val outputIsRegAccess = outputIsRegRequest || outputIsRegCompletion
  val outputIsWoData =
    SBMsgOpcode.OpsWithoutData.map(_.asUInt === outputOpcode).reduce(_ || _)
  io.outInvalid := io.out.valid && outputWasPacketMode && outputIsRegAccess &&
    (outputInvalidAtIngress || outputEpoch =/= regAccessEpoch ||
      flushPending || io.flushRegAccess)

  // ===========================================================================
  // Timeout
  // ===========================================================================
  // Starts idle, so no timeout runs before the first forwarded clock edge.
  val idleStatusSync = Sync3(idleStatus, init = true.B)
  val timeoutCounter = RegInit(0.U(log2Ceil(desTimeoutCycles + 1).W))

  when(idleStatusSync) {
    timeoutCounter := 0.U
  }.elsewhen(timeoutCounter =/= desTimeoutCycles.U) {
    timeoutCounter := timeoutCounter + 1.U
  }

  io.ctrl.desTimedout := timeoutCounter === desTimeoutCycles.U

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    block(Verification.Assert) {
      val outStalled = RegNext(io.out.valid && !io.out.ready, false.B)
      val previousOutBits = RegNext(io.out.bits.asUInt)

      AssertProperty(
        Sequence.BoolSequence(outStalled) |->
          Sequence.BoolSequence(
            io.out.valid && io.out.bits.asUInt === previousOutBits
          ),
        label = Some("SidebandDeserializerHoldsOutputUnderBackpressure")
      )

      AssertProperty(
        Sequence.BoolSequence(
          rxQueue.io.enq.valid && io.ctrl.rxMode === SBRxTxMode.PACKET
        ) |->
          Sequence.BoolSequence(rxQueue.io.enq.ready),
        label = Some("SidebandDeserializerDoesNotDropWords")
      )

      AssertProperty(
        Sequence.BoolSequence(io.outInvalid) |->
          Sequence.BoolSequence(io.out.valid && outputIsRegAccess),
        label = Some("SidebandDeserializerOnlyInvalidatesRegisterPackets")
      )
    }
    block(Verification.Cover) {
      val sawOutputStall = RegInit(false.B)
      when(io.out.valid && !io.out.ready) {
        sawOutputStall := true.B
      }.elsewhen(io.out.fire) {
        sawOutputStall := false.B
      }

      cover(
        io.out.fire && !io.outPacketMode,
        "SidebandLinkDeserializerRaw64Output"
      )
      cover(
        io.out.fire && io.outPacketMode && outputIsWoData,
        "SidebandLinkDeserializerPacket64Output"
      )
      cover(
        io.out.fire && io.outPacketMode && !outputIsWoData,
        "SidebandLinkDeserializerPacket128Output"
      )
      cover(
        io.out.valid && !io.out.ready,
        "SidebandLinkDeserializerOutputBackpressure"
      )
      cover(
        io.out.fire && sawOutputStall,
        "SidebandLinkDeserializerStalledOutputConsumed"
      )
      cover(io.ctrl.desTimedout, "SidebandLinkDeserializerTimeout")
      cover(io.headerCpErrEvent, "SidebandLinkDeserializerHeaderCpError")
      CoverProperty(
        Sequence.BoolSequence(reset.asBool && !idleStatusSync),
        disable = None,
        label = Some("SidebandLinkDeserializerResetDuringPacket")
      )
    }
  }
}
