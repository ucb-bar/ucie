package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import chisel3.util.random._

object DataMode extends ChiselEnum {
  // Send/receive finite number of bits.
  val finite = Value(0.U(1.W))
  // Send/receive infinite amount of data.
  // In manual test mode, repeats the sent data bits.
  // In LFSR mode, continues sending LFSR data indefinitely.
  val infinite = Value(1.U(1.W))
}

object TestTarget extends ChiselEnum {
  // Receive from mainband once valid lane goes high.
  val mainband = Value(0.U(1.W))
  // Receive from loopback receiver as soon as first one is received.
  val loopback = Value(1.U(1.W))
}

// TX test modes.
object TxTestMode extends ChiselEnum {
  // Data to send is provided manually via `txDataOffset` and `txDataChunkIn`.
  val manual = Value(0.U(1.W))
  // Data to send is derived from an LFSR.
  val lfsr = Value(1.U(1.W))
}

/** State of the TX test FSM. */
object TxTestState extends ChiselEnum {

  /** Awaiting configuration and start of transmission. */
  val idle = Value(0.U(2.W))

  /** Test is currently being run. */
  val run = Value(1.U(2.W))

  /** Test is complete. */
  val done = Value(2.U(2.W))
}

/** Control registers for PHY tester.
  *
  * @constructor
  *   create a new [[TestRegsIO]]
  * @param bufferDepthPerLane
  *   log2(# of bits stored per lane)
  * @param numLanes
  *   number of lanes
  * @param bitCounterWidth
  *   width of counters for TX bits sent and RX bits received.
  */
class PhyTestRegsIO(
    bufferDepthPerLane: Int = 10,
    numLanes: Int = 2,
    bitCounterWidth: Int = 64
) extends Bundle {
  // GENERAL CONTROL
  // =====================
  /** The test setup being targeted. */
  val testTarget = Input(TestTarget())

  // TX CONTROL
  // =====================
  // The test mode of the TX.
  val txTestMode = Input(TxTestMode())
  // The data mode of the TX.
  val txDataMode = Input(DataMode())
  // Seed of the TX LFSR.
  val txLfsrSeed = Input(Vec(numLanes + 1, UInt((2 * Phy.SerdesRatio).W)))
  // Resets the TX FSM (i.e. resetting the number of bits sent to 0, reseeding the LFSR,
  // and stopping any in-progress transmissions).
  val txFsmRst = Input(Bool())
  // Starts a transmission starting from the beginning of the input buffer (`TxTestMode.manual`) or from
  // the current state of the LFSR (`TxTestMode.lsfr`). Does not do anything if a transmission is in progress.
  val txExecute = Input(Bool())
  // The number of packets of `Phy.SerdesRatio` bits sent since the last FSM reset.
  val txPacketsSent = Output(UInt(bitCounterWidth.W))
  // The number of 32-bit chunks per repeating period in TX transmission in manual mode.
  // Set to 0 to send the entire buffer. Numbers greater than the buffer length will send the entire buffer
  // in `TxTestMode.manual`.
  val txManualRepeatPeriod = Input(UInt((bufferDepthPerLane - 5 + 1).W))
  // The number of packets to send during transmission.
  val txPacketsToSend = Input(UInt(bitCounterWidth.W))
  // Clock P signal value.
  val txClkP = Input(UInt(32.W))
  // Clock P signal.
  val txClkN = Input(UInt(32.W))
  // Valid signal.
  val txValid = Input(UInt(32.W))
  // Track signal.
  val txTrack = Input(UInt(32.W))
  // Data chunk lane group in input buffer. Each lane group consists of 4 adjacent lanes (e.g. 0, 1, 2, 3).
  // Lane numLanes is valid, numLanes + 1 is track, numLanes + 2 is loopback.
  val txDataLaneGroup = Input(UInt(log2Ceil((numLanes + 2) / 4 + 1).W))
  // Data chunk offset in input buffer.
  val txDataOffset = Input(UInt((bufferDepthPerLane - 5).W))
  // 128-bit data chunk to write (32 bits per lane).
  val txDataChunkIn = Flipped(DecoupledIO(UInt(128.W)))
  // Data chunk at the given chunk offset for inspecting the data to be sent. Only available in idle/done mode.
  val txDataChunkOut = Output(UInt(128.W))
  // State of the TX test FSM.
  val txTestState = Output(TxTestState())

  // RX CONTROL
  // ====================
  // The data mode of the RX.
  val rxDataMode = Input(DataMode())
  // Seed of the RX LFSR used for detecting bit errors. Should be the same as the TX seed of the transmitting chiplet.
  val rxLfsrSeed = Input(Vec(numLanes + 1, UInt((2 * Phy.SerdesRatio).W)))
  // Expected valid signal in LFSR mode.
  val rxLfsrValid = Input(UInt(32.W))
  // Resets the RX FSM (i.e. resetting the number of bits received and the offset within the output
  // buffer to 0).
  val rxFsmRst = Input(Bool())
  // The number of packets received since the last FSM reset. Only the first 2^bufferDepthPerLane bits received
  // per lane are stored in the output buffer.
  val rxPacketsReceived = Output(UInt(bitCounterWidth.W))
  // The number of packets to receive.
  val rxPacketsToReceive = Input(UInt(bitCounterWidth.W))
  // The number of bit errors per lane since the last FSM reset. Only applicable in `TxTestMode.lsfr`.
  // Extra lanes for valid errors (requires 1111000011110000...) and loopback.
  val rxBitErrors = Output(Vec(numLanes + 2, UInt(bitCounterWidth.W)))
  // Pause the `rxPacketsReceived` and `rxBitErrors` counters to read them atomically.
  val rxPauseCounters = Input(Bool())
  // A MISR derived from the packets received since the last FSM reset.
  val rxSignature = Output(UInt(32.W))
  // Data chunk lane in output buffer.
  val rxDataLane = Input(UInt(log2Ceil(numLanes + 3).W))
  // Data chunk offset in output buffer.
  val rxDataOffset = Input(UInt((bufferDepthPerLane - 5).W))
  // Data chunk at the given chunk offset for inspect the received data.
  val rxDataChunk = Output(UInt(32.W))

  // DEBUG CIRCUITRY CONTROL
  // ===========================
  val driverctl = Input(Vec(6, new DriverControlIO))
  val txctl = Input(new TxLaneDigitalCtlIO)
  val txDebugTestMode = Input(TxTestMode())
  val txDebugDataMode = Input(DataMode())
  val txDebugLfsrSeed = Input(UInt(64.W))
  val txDebugFsmRst = Input(Bool())
  val txDebugExecute = Input(Bool())
  val txDebugManualRepeatPeriod = Input(UInt(6.W))
  val txDebugPacketsToSend = Input(UInt(bitCounterWidth.W))
  val txDebugdata = Input(Vec(16, UInt(64.W)))
  val txDebugState = Output(TxTestState())
  val txDebugPacketsEnqueued = Output(UInt(bitCounterWidth.W))
  val dllCode = Output(UInt(5.W))
}

class PhyDebugIO extends Bundle {
  val pllClkP = Output(Bool())
  val pllClkN = Output(Bool())
  val testPllClkP = Output(Bool())
  val testPllClkN = Output(Bool())
  val rxClk = Output(Bool())
  val rxClkDivided = Output(Bool())
}

class DebugBumpsIO extends Bundle {
  val phy = new PhyDebugIO
  val txData = Output(Bool())
}

class PhyTestIO(
    bufferDepthPerLane: Int = 10,
    numLanes: Int = 2,
    bitCounterWidth: Int = 64
) extends Bundle {
  val regs = new PhyTestRegsIO(bufferDepthPerLane, numLanes, bitCounterWidth)

  // PHY INTERFACE
  // ====================
  val phy = Flipped(new PhyToDigitalIO(numLanes))
  val debug = Flipped(new PhyDebugIO)

  // BUMP INTERFACE
  // ====================
  val bumps = new DebugBumpsIO
}

class PhyTest(
    bufferDepthPerLane: Int = 10,
    numLanes: Int = 2,
    bitCounterWidth: Int = 64,
    sim: Boolean = false
) extends Module {
  val io = IO(new PhyTestIO(bufferDepthPerLane, numLanes, bitCounterWidth))

  // General computations
  val maxBitCount = VecInit(Seq.fill(bitCounterWidth)(true.B)).asUInt
  val maxSramPackets = 1.U << (bufferDepthPerLane - 5).U;

  // TX registers
  val txReset = io.regs.txFsmRst || reset.asBool
  val txState = withReset(txReset) { RegInit(TxTestState.idle) }
  val txPacketsEnqueued = withReset(txReset) { RegInit(0.U(bitCounterWidth.W)) }
  val inputBufferAddrReg = withReset(txReset) {
    RegInit(0.U((bufferDepthPerLane - 5).W))
  }
  val txLfsrs = (0 until numLanes + 1).map((i: Int) => {
    val lfsr = Module(
      new FibonacciLFSR(
        2 * Phy.SerdesRatio,
        taps = LFSR.tapsMaxPeriod.get(2 * Phy.SerdesRatio).get.head,
        step = Phy.SerdesRatio
      )
    )
    lfsr.io.seed.bits := io.regs.txLfsrSeed(i).asTypeOf(lfsr.io.seed.bits)
    lfsr.io.seed.valid := txReset
    lfsr.io.increment := false.B
    lfsr
  })
  val loadedFirstChunk = withReset(txReset) { RegInit(false.B) }
  val txManualRepeatPeriod = Mux(
    io.regs.txManualRepeatPeriod === 0.U || io.regs.txManualRepeatPeriod > maxSramPackets,
    maxSramPackets,
    io.regs.txManualRepeatPeriod
  )

  // RX registers
  val rxReset = io.regs.rxFsmRst || reset.asBool
  val rxPacketsReceived = withReset(rxReset) {
    RegInit(0.U((64 - log2Ceil(Phy.SerdesRatio)).W))
  }
  val rxReceiveOffset = withReset(rxReset) {
    RegInit(0.U(log2Ceil(Phy.SerdesRatio).W))
  }
  /// numLanes data lanes, 1 valid lane, 1 loopback lane.
  val rxBitErrors = withReset(rxReset) {
    RegInit(VecInit(Seq.fill(numLanes + 2)(0.U(64.W))))
  }
  val rxPacketsReceivedOutput = withReset(rxReset) { RegInit(0.U(64.W)) }
  /// numLanes data lanes, 1 valid lane, 1 loopback lane.
  val rxErrorMask = withReset(rxReset) {
    RegInit(VecInit(Seq.fill(numLanes + 2)(0.U(Phy.SerdesRatio.W))))
  }
  val rxBitErrorsOutput = withReset(rxReset) {
    RegInit(VecInit(Seq.fill(numLanes + 2)(0.U(64.W))))
  }
  val rxLfsrs = (0 until numLanes + 1).map((i: Int) => {
    val lfsr = Module(
      new FibonacciLFSR(
        2 * Phy.SerdesRatio,
        taps = LFSR.tapsMaxPeriod.get(2 * Phy.SerdesRatio).get.head,
        step = Phy.SerdesRatio
      )
    )
    lfsr.io.seed.bits := io.regs.rxLfsrSeed(i).asTypeOf(lfsr.io.seed.bits)
    lfsr.io.seed.valid := rxReset
    lfsr.io.increment := false.B
    lfsr
  })

  val rxSignature = withReset(rxReset) { RegInit(0.U(32.W)) }

  val numSrams = (numLanes + 2) / 4 + 1
  val inputBuffer = (0 until numSrams).map(i =>
    SyncReadMem(1 << (bufferDepthPerLane - 5), UInt(128.W))
  )
  val inputBufferAddr = Wire(UInt((bufferDepthPerLane - 5).W))
  inputBufferAddr := io.regs.txDataOffset
  val inputRdPorts =
    (0 until numSrams).map(i => inputBuffer(i)(inputBufferAddr))
  val inputWrPorts =
    (0 until numSrams).map(i => inputBuffer(i)(io.regs.txDataOffset))
  val outputBuffer = (0 until numSrams).map(i =>
    SyncReadMem(1 << (bufferDepthPerLane - 5), UInt(128.W))
  )
  val outputBufferAddr = Wire(UInt(log2Ceil(1 << (bufferDepthPerLane - 5)).W))
  outputBufferAddr := rxPacketsReceived
  val toWrite = (0 until numSrams).map(i => {
    val wire = Wire(Vec(4, UInt(32.W)))
    for (i <- 0 until 4) {
      wire(i) := 0.U
    }
    wire
  })
  val shouldWrite = Wire(Bool())
  shouldWrite := false.B
  val outputBufferAddrDelayed = ShiftRegister(outputBufferAddr, 2, true.B)
  val toWriteDelayed = toWrite.map(w => ShiftRegister(w, 2, true.B))
  // Needs to default to false.
  val shouldWriteDelayed = ShiftRegister(shouldWrite, 2, false.B, true.B)
  val outputRdPorts =
    (0 until numSrams).map(i => outputBuffer(i)(io.regs.rxDataOffset))
  val outputWrPorts =
    (0 until numSrams).map(i => outputBuffer(i)(outputBufferAddrDelayed))
  when(shouldWriteDelayed) {
    for (i <- 0 until numSrams) {
      outputWrPorts(i) := toWriteDelayed(i).asTypeOf(outputWrPorts(i))
    }
  }

  io.regs.txPacketsSent := txPacketsEnqueued
  io.regs.txDataChunkIn.ready := txState === TxTestState.idle
  io.regs.txDataChunkOut := 0.U
  for (i <- 0 until numSrams) {
    when(i.U === io.regs.txDataLaneGroup) {
      io.regs.txDataChunkOut := inputRdPorts(i)
    }
  }
  io.regs.txTestState := txState
  io.regs.rxPacketsReceived := rxPacketsReceivedOutput
  io.regs.rxBitErrors := rxBitErrorsOutput
  io.regs.rxSignature := rxSignature
  io.regs.rxDataChunk := 0.U
  for (i <- 0 until numSrams) {
    when(i.U === io.regs.rxDataLane >> 2.U) {
      io.regs.rxDataChunk := outputRdPorts(i).asTypeOf(Vec(4, UInt(32.W)))(
        io.regs.rxDataLane(1, 0)
      )
    }
  }

  // TODO: Add async FIFOs here
  for (lane <- 0 until numLanes) {
    io.phy.tx.data(lane) := 0.U
  }
  io.phy.tx.valid := 0.U
  io.phy.tx.track := 0.U
  // Needs to always be true to send clock and track even when data isn't valid.
  io.phy.tx.valid := true.B

  io.phy.tx.clkp := io.regs.txClkP
  io.phy.tx.clkn := io.regs.txClkN

  // TODO: Add tx loopback RTL

  // Unlike `io.phy.tx.valid`, only true when data is valid.
  val tx_valid = Wire(Bool())
  tx_valid := false.B

  // TODO: uncomment
  // TX logic
  // switch(txState) {
  //   is(TxTestState.idle) {
  //     when(io.regs.txDataChunkIn.valid) {
  //       for (i <- 0 until numSrams) {
  //         when(i.U === io.regs.txDataLaneGroup) {
  //           inputWrPorts(i) := io.regs.txDataChunkIn.bits
  //         }
  //       }
  //     }

  //     when(io.regs.txExecute) {
  //       txState := TxTestState.run
  //     }
  //   }
  //   is(TxTestState.run) {
  //     switch(io.regs.txTestMode) {
  //       is(TxTestMode.manual) {
  //         // Need to load first chunk ahead of time so that we can constantly send data.
  //         when(loadedFirstChunk) {
  //           // Increment address when packet is enqueued.
  //           when(
  //             Mux(
  //               io.regs.testTarget === TestTarget.mainband,
  //               io.phy.tx.ready,
  //               io.phy.tx_loopback.ready
  //             )
  //           ) {
  //             inputBufferAddr := (inputBufferAddrReg + 1.U) % txManualRepeatPeriod
  //           }.otherwise {
  //             inputBufferAddr := inputBufferAddrReg % txManualRepeatPeriod
  //           }
  //           // Only send the next packet if we still need to send more bits.
  //           switch(io.regs.txDataMode) {
  //             is(DataMode.finite) {
  //               tx_valid := txPacketsEnqueued < io.regs.txPacketsToSend
  //             }
  //             is(DataMode.infinite) {
  //               tx_valid := true.B
  //             }
  //           }
  //         }.otherwise {
  //           inputBufferAddr := 0.U
  //           loadedFirstChunk := true.B
  //         }
  //       }
  //       is(TxTestMode.lfsr) {
  //         switch(io.regs.txDataMode) {
  //           is(DataMode.finite) {
  //             tx_valid := txPacketsEnqueued < io.regs.txPacketsToSend
  //           }
  //           is(DataMode.infinite) {
  //             tx_valid := true.B
  //           }
  //         }
  //       }
  //     }
  //     when(tx_valid) {
  //       switch(io.regs.txTestMode) {
  //         is(TxTestMode.manual) {
  //           switch(io.regs.testTarget) {
  //             is(TestTarget.mainband) {
  //               for (lane <- 0 until numLanes) {
  //                 io.phy.tx.bits.data(lane) := inputRdPorts(lane >> 2).asTypeOf(
  //                   Vec(4, UInt(32.W))
  //                 )(lane % 4)
  //               }
  //               io.phy.tx.bits.valid := inputRdPorts(numLanes >> 2).asTypeOf(
  //                 Vec(4, UInt(32.W))
  //               )(numLanes % 4)
  //               io.phy.tx.bits.track := inputRdPorts((numLanes + 1) >> 2)
  //                 .asTypeOf(Vec(4, UInt(32.W)))((numLanes + 1) % 4)
  //             }
  //             is(TestTarget.loopback) {
  //               io.phy.tx_loopback.bits := inputRdPorts((numLanes + 2) >> 2)
  //                 .asTypeOf(Vec(4, UInt(32.W)))((numLanes + 2) % 4)
  //             }
  //           }
  //         }
  //         is(TxTestMode.lfsr) {
  //           switch(io.regs.testTarget) {
  //             is(TestTarget.mainband) {
  //               for (lane <- 0 until numLanes) {
  //                 io.phy.tx.bits.data(lane) := Reverse(
  //                   txLfsrs(lane).io.out.asUInt
  //                 )(31, 0).asTypeOf(io.phy.tx.bits.data(lane))
  //               }
  //               io.phy.tx.bits.valid := io.regs.txValid
  //               io.phy.tx.bits.track := io.regs.txTrack
  //             }
  //             is(TestTarget.loopback) {
  //               io.phy.tx_loopback.bits := Reverse(
  //                 txLfsrs(numLanes).io.out.asUInt
  //               )(31, 0).asTypeOf(io.phy.tx_loopback.bits)
  //             }
  //           }
  //         }
  //       }
  //     }

  //     when(
  //       tx_valid && Mux(
  //         io.regs.testTarget === TestTarget.mainband,
  //         io.phy.tx.ready,
  //         io.phy.tx_loopback.ready
  //       )
  //     ) {
  //       txPacketsEnqueued := Mux(
  //         txPacketsEnqueued < VecInit(
  //           Seq.fill(txPacketsEnqueued.getWidth)(true.B)
  //         ).asUInt,
  //         txPacketsEnqueued + 1.U,
  //         txPacketsEnqueued
  //       )
  //       inputBufferAddrReg := (inputBufferAddrReg + 1.U) % txManualRepeatPeriod
  //       when(io.regs.txTestMode === TxTestMode.lfsr) {
  //         for (lane <- 0 until numLanes + 1) {
  //           txLfsrs(lane).io.increment := true.B
  //         }
  //       }
  //     }

  //     when(
  //       (io.regs.txTestMode === TxTestMode.lfsr || loadedFirstChunk) && !tx_valid
  //     ) {
  //       txState := TxTestState.done
  //     }
  //   }
  //   is(TxTestState.done) {}
  // }
  // io.phy.tx_loopback.valid := tx_valid

  // // RX logic

  // io.phy.rx.ready := true.B
  // io.phy.rx_loopback.ready := true.B

  // for (i <- 0 until numLanes + 2) {
  //   val newRxBitErrors = rxBitErrors(i) +& PopCount(rxErrorMask(i))
  //   rxBitErrors(i) := Mux(
  //     newRxBitErrors > maxBitCount,
  //     maxBitCount,
  //     newRxBitErrors
  //   )
  // }
  // when(io.regs.rxPauseCounters) {
  //   rxPacketsReceivedOutput := rxPacketsReceivedOutput
  //   rxBitErrorsOutput := rxBitErrorsOutput
  // }.otherwise {
  //   rxPacketsReceivedOutput := RegNext(rxPacketsReceived)
  //   rxBitErrorsOutput := rxBitErrors
  // }

  // // Dumb RX logic (starts recording as soon as valid goes high and never stops)
  // val recordingStarted = withReset(rxReset) { RegInit(false.B) }
  // val startRecording = Wire(Bool())
  // val startIdx = Wire(UInt(log2Ceil(Phy.SerdesRatio).W))
  // startRecording := false.B
  // startIdx := 0.U

  // // numLanes data lanes, 1 valid lane, 1 track lane, 1 loopback lane.
  // val runningData = withReset(rxReset) {
  //   RegInit(VecInit(Seq.fill(numLanes + 3)(0.U(32.W))))
  // }

  // for (i <- 0 until numLanes + 2) {
  //   rxErrorMask(i) := 0.U
  // }

  // // Check valid streak after each packet is dequeued.
  // when(
  //   Mux(
  //     io.regs.testTarget === TestTarget.mainband,
  //     io.phy.rx.ready & io.phy.rx.valid,
  //     io.phy.rx_loopback.ready & io.phy.rx_loopback.valid
  //   )
  // ) {

  //   // Find correct start index if recording hasn't started already.
  //   for (i <- Phy.SerdesRatio - 1 to 0 by -1) {
  //     val shouldStartRecording = Wire(Bool())
  //     shouldStartRecording := false.B
  //     switch(io.regs.testTarget) {
  //       is(TestTarget.mainband) {
  //         shouldStartRecording := io.phy.rx.bits.valid(i)
  //       }
  //       is(TestTarget.loopback) {
  //         shouldStartRecording := io.phy.rx_loopback.bits(i)
  //       }
  //     }
  //     when(!recordingStarted && shouldStartRecording) {
  //       startRecording := true.B
  //       startIdx := i.U
  //       rxReceiveOffset := Phy.SerdesRatio.U - i.U
  //     }
  //   }

  //   recordingStarted := recordingStarted || startRecording

  //   when(!recordingStarted && !startRecording) {
  //     // Store latest data at the beginning of the `runningData` register.
  //     for (lane <- 0 until numLanes + 3) {
  //       if (lane < numLanes) {
  //         runningData(lane) := io.phy.rx.bits.data(lane)
  //       } else if (lane == numLanes) {
  //         runningData(lane) := io.phy.rx.bits.valid
  //       } else if (lane == numLanes + 1) {
  //         runningData(lane) := io.phy.rx.bits.track
  //       } else {
  //         runningData(lane) := io.phy.rx_loopback.bits
  //       }
  //     }
  //   }.otherwise {
  //     val fullPacketReceived =
  //       rxReceiveOffset +& Phy.SerdesRatio.U - startIdx >= 32.U
  //     val shouldProcessPacket =
  //       fullPacketReceived && (io.regs.rxDataMode === DataMode.infinite || rxPacketsReceived < io.regs.rxPacketsToReceive)
  //     shouldWrite := rxPacketsReceived < maxSramPackets && fullPacketReceived
  //     val dataMask = Wire(UInt(64.W))
  //     dataMask := ((1.U << (Phy.SerdesRatio.U - startIdx)) - 1.U) << rxReceiveOffset
  //     val keepMask = Wire(UInt(64.W))
  //     keepMask := ~dataMask
  //     when(shouldProcessPacket) {
  //       rxPacketsReceived := Mux(
  //         rxPacketsReceived < VecInit(
  //           Seq.fill(rxPacketsReceived.getWidth)(true.B)
  //         ).asUInt,
  //         rxPacketsReceived + 1.U,
  //         rxPacketsReceived
  //       )
  //     }
  //     for (lane <- 0 until numLanes + 3) {
  //       val rawData = if (lane < numLanes) {
  //         io.phy.rx.bits.data(lane)
  //       } else if (lane == numLanes) {
  //         io.phy.rx.bits.valid
  //       } else if (lane == numLanes + 1) {
  //         io.phy.rx.bits.track
  //       } else {
  //         io.phy.rx_loopback.bits
  //       }
  //       val data = Wire(UInt(64.W))
  //       data := (rawData << rxReceiveOffset) >> startIdx
  //       val newData = Wire(UInt(64.W))
  //       newData := (data & dataMask) | (runningData(lane) & keepMask)
  //       runningData(lane) := newData(31, 0)
  //       when(fullPacketReceived) {
  //         runningData(lane) := newData >> 32.U
  //       }
  //       when(shouldWrite) {
  //         toWrite(lane >> 2)(lane % 4) := newData(31, 0)
  //       }

  //       when(shouldProcessPacket) {
  //         // Compare data against LFSR and increment LFSR.
  //         if (lane < numLanes) {
  //           rxLfsrs(lane).io.increment := true.B
  //           val lfsrData = newData(31, 0)
  //           rxErrorMask(lane) := newData(31, 0) ^ Reverse(
  //             rxLfsrs(lane).io.out.asUInt
  //           )(31, 0)
  //         }
  //         // Compare valid against intended waveform.
  //         if (lane == numLanes) {
  //           rxErrorMask(lane) := newData(31, 0) ^ io.regs.rxLfsrValid
  //         }
  //         if (lane == numLanes + 2) {
  //           rxLfsrs(numLanes).io.increment := true.B
  //           val lfsrData = newData(31, 0)
  //           rxErrorMask(numLanes + 1) := newData(31, 0) ^ Reverse(
  //             rxLfsrs(numLanes).io.out.asUInt
  //           )(31, 0)
  //         }
  //       }
  //     }
  //   }
  // }

  // TODO: Move to PHY
  // val refclkrx = Module(new ClkRx(sim))
  // refclkrx.io.vip := io.top.phy.refClkP
  // refclkrx.io.vin := io.top.phy.refClkN
  // val refclkbuf = Module(new DiffBuffer(sim))
  // refclkbuf.io.vinp := refclkrx.io.vop
  // refclkbuf.io.vinn := refclkrx.io.von
  // io.phy.refClkP := refclkbuf.io.voutp
  // io.phy.refClkN := refclkbuf.io.voutn

  // val bpclkrx = Module(new ClkRx(sim))
  // bpclkrx.io.vip := io.top.phy.bypassClkP
  // bpclkrx.io.vin := io.top.phy.bypassClkN
  // val bpclkbuf = Module(new DiffBuffer(sim))
  // bpclkbuf.io.vinp := bpclkrx.io.vop
  // bpclkbuf.io.vinn := bpclkrx.io.von
  // io.phy.bypassClkP := bpclkbuf.io.voutp
  // io.phy.bypassClkN := bpclkbuf.io.voutn

  val testPllClkPBuf0 = Module(new SingleEndedBuffer(sim))
  val testPllClkPBuf1 = Module(new SingleEndedBuffer(sim))
  val testPllClkPBuf2 = Module(new SingleEndedBuffer(sim))
  val testPllClkNBuf0 = Module(new SingleEndedBuffer(sim))
  val testPllClkNBuf1 = Module(new SingleEndedBuffer(sim))
  val testPllClkNBuf2 = Module(new SingleEndedBuffer(sim))
  testPllClkPBuf0.io.vin := io.debug.testPllClkP
  testPllClkPBuf1.io.vin := testPllClkPBuf0.io.vout
  testPllClkPBuf2.io.vin := testPllClkPBuf1.io.vout
  testPllClkNBuf0.io.vin := io.debug.testPllClkN
  testPllClkNBuf1.io.vin := testPllClkNBuf0.io.vout
  testPllClkNBuf2.io.vin := testPllClkNBuf1.io.vout

  val uciePllClkBuf0 = Module(new DiffBuffer(sim))
  val uciePllClkBuf1 = Module(new DiffBuffer(sim))
  uciePllClkBuf0.io.vinp := io.debug.pllClkP
  uciePllClkBuf0.io.vinn := io.debug.pllClkN
  uciePllClkBuf1.io.vinp := uciePllClkBuf0.io.voutp
  uciePllClkBuf1.io.vinn := uciePllClkBuf0.io.voutn
  // TODO: Hook up loopback lanes and TX data debug lane.
  // txLane.io.clkp := uciePllClkBuf1.io.voutp
  // txLane.io.clkn := uciePllClkBuf1.io.voutn

  val pllClkNDiv = Module(new ClkDiv4(sim))
  pllClkNDiv.io.clk := uciePllClkBuf0.io.voutn
  pllClkNDiv.io.resetb := !reset.asBool

  val rxClkPBuf0 = Module(new SingleEndedBuffer(sim))
  val rxClkPBuf1 = Module(new SingleEndedBuffer(sim))
  val rxClkPBuf2 = Module(new SingleEndedBuffer(sim))
  val rxClkPBuf3 = Module(new SingleEndedBuffer(sim))
  val rxClkNBuf0 = Module(new SingleEndedBuffer(sim))
  val rxClkNBuf1 = Module(new SingleEndedBuffer(sim))
  val rxClkNBuf2 = Module(new SingleEndedBuffer(sim))
  val rxClkNBuf3 = Module(new SingleEndedBuffer(sim))
  rxClkPBuf0.io.vin := io.debug.rxClk
  rxClkPBuf1.io.vin := rxClkPBuf0.io.vout
  rxClkPBuf2.io.vin := rxClkPBuf1.io.vout
  rxClkPBuf3.io.vin := rxClkPBuf2.io.vout
  rxClkNBuf0.io.vin := io.debug.rxClkDivided
  rxClkNBuf1.io.vin := rxClkNBuf0.io.vout
  rxClkNBuf2.io.vin := rxClkNBuf1.io.vout
  rxClkNBuf3.io.vin := rxClkNBuf2.io.vout

  val drivers = Seq(
    (
      testPllClkPBuf2.io.vout,
      io.bumps.phy.testPllClkP,
      "testPllClkPDriver"
    ),
    (
      testPllClkNBuf2.io.vout,
      io.bumps.phy.testPllClkN,
      "testPllClkNDriver"
    ),
    (uciePllClkBuf0.io.voutp, io.bumps.phy.pllClkP, "pllClkPDriver"),
    (pllClkNDiv.io.clkout_2, io.bumps.phy.pllClkN, "pllClkNDriver"),
    (rxClkPBuf3.io.vout, io.bumps.phy.rxClk, "rxClkDriver"),
    (rxClkNBuf3.io.vout, io.bumps.phy.rxClkDivided, "rxClkDivDriver")
  ).zipWithIndex
  for (((input, output, name), i) <- drivers) {
    val driver = Module(new TxDriver(sim)).suggestName(name)
    driver.io.din := input
    output := driver.io.dout
    // TODO: set up control signals
    // driver.io.ctl := io.driverctl(i)
  }
}
