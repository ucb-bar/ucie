/*
  Description: 
    SidebandLinkNode encapsulates link serdes, and any flow-control and data integrity
    logic associated with transmitting or receiving sideband messages over the phyiscal link.
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.ltl._
import circt.stage.ChiselStage
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.SkidBuffer

class SidebandLinkNode(sbMsgWidth: Int, sbLinkWidth: Int, numCredits: Int, desTimeoutCycles: Int,
                          queueDepths: SidebandPriorityQueueDepths) extends Module {
  val io = IO(new Bundle {
    /* Switch Facing IOs */
    // Messages coming from the switch to be serialized
    val txIn = Flipped(Decoupled(UInt(sbMsgWidth.W))) 
    
    // Messages going to the switch
    val rxOut = Decoupled(UInt(sbMsgWidth.W))         

    /* Interface Facing IOs */
    // Serialized data going OUT to physical link
    val txOut = new Bundle {
      val bits = Output(UInt(sbLinkWidth.W))
      val fwClock = Output(UInt(1.W))
    }

    // Serialized data coming IN over physical link
    val rxIn = new Bundle {
      val bits = Input(UInt(sbLinkWidth.W))
      val fwClock = Input(UInt(1.W))
    }

    // Error signals
    val err = new Bundle {
      val sbParityErr = Output(Bool())
      val rxPriorityQueuesFull = Output(Bool())
      val desTimedout = Output(Bool())
    }

    // Ctrl signals
    val ctrl = new Bundle {
      val txMode = Input(SBRxTxMode())
      val rxMode = Input(SBRxTxMode())
      val freezeAcceptingPackets = Input(Bool())
      val allPacketsSent = Output(Bool())
    }
  })  

  // TX Path: txIn --> SkidBuffer --> Parity Set --> Serializer --> txOut
  val serializer = Module(new SidebandLinkSerializer(sbLinkWidth, sbMsgWidth))

  serializer.io.ctrl.txMode := io.ctrl.txMode

  // Minimizes potentially large combinational path for serializer ready signal
  val skidBuffer = Module(new SkidBuffer(sbMsgWidth)) 
  
  io.ctrl.allPacketsSent := io.ctrl.freezeAcceptingPackets && 
                            !skidBuffer.io.out.valid &&
                            serializer.io.in.ready

  skidBuffer.io.in.valid := io.txIn.valid && !io.ctrl.freezeAcceptingPackets
  skidBuffer.io.in.bits  := io.txIn.bits
  io.txIn.ready := skidBuffer.io.in.ready && !io.ctrl.freezeAcceptingPackets

  skidBuffer.io.out.ready := serializer.io.in.ready
  val txOpcode = skidBuffer.io.out.bits(4, 0)
  val txIsWoData = SBMsgOpcode.OpsWithoutData.map(_.asUInt === txOpcode).reduce(_ || _)
  val txAccept = serializer.io.in.fire
  
  // Parity Set Logic -- set parity before serializing
  // NOTE: Assumption is that if data bits need to be zeroed out they will be, so DP == 0
  val headerPSet = WireDefault(skidBuffer.io.out.bits(63, 0))
  val bitsToProtectPSet = WireDefault(headerPSet(61, 0)) // Skip DP(63), CP(62)
  val calculatedCPPset = WireDefault(bitsToProtectPSet.xorR)

  // Can safely skip DP bit when calculating CP bit for messages that don't use DP because 
  // it be 0 with this logic
  val doDpCalculationPSet = !(SBMsgOpcode.OpsThatDontUseDPField.map(_.asUInt === headerPSet(4,0))
                                                               .reduce(_ || _))
  val payloadPSet = WireDefault(skidBuffer.io.out.bits(127, 64))
  val payloadForDPPSet = WireDefault(Mux(doDpCalculationPSet, payloadPSet, 0.U))
  val calculatedDPPSet = WireDefault(payloadForDPPSet.xorR)

  val newHeader = WireDefault(Cat(calculatedDPPSet, calculatedCPPset, headerPSet(61, 0)))
  val newBits = WireDefault(Cat(payloadPSet, newHeader))

  serializer.io.in.valid := skidBuffer.io.out.valid  
  serializer.io.in.bits := newBits

  io.txOut.bits := serializer.io.out.bits
  io.txOut.fwClock := serializer.io.out.fwClock



  // RX Path: rxIn --> Deserializer --> Parity Check --> PriorityQueue --> rxOut
  val deserializer = Module(new SidebandLinkDeserializer(sbLinkWidth, sbMsgWidth, desTimeoutCycles))
  val priorityQueue = Module(new SidebandPriorityQueue(sbMsgWidth, queueDepths))
  
  deserializer.io.ctrl.rxMode := io.ctrl.rxMode  
  deserializer.io.in.bits := io.rxIn.bits
  deserializer.io.in.fwClock := io.rxIn.fwClock

  io.err.desTimedout := deserializer.io.ctrl.desTimedout

  // Parity check per spec. CP protects every header field except CP and DP
  // (reserved bits included), even parity, on every message. DP protects the
  // data fields, even parity, on messages that carry data.
  val parityErrReg = RegInit(false.B)
  val opcode = deserializer.io.out.bits(4, 0)

  val header = WireDefault(deserializer.io.out.bits(63, 0))
  val bitsToProtect = WireDefault(header(61, 0)) // Skip DP(63), CP(62)
  val expectedCP = header(62)
  val calculatedCP = WireDefault(bitsToProtect.xorR)
  val cpError = WireDefault(expectedCP ^ calculatedCP)

  val doDpCalculation = !(SBMsgOpcode.OpsThatDontUseDPField.map(_.asUInt === opcode).reduce(_ || _))
  val payload = WireDefault(deserializer.io.out.bits(127, 64))
  val expectedDP = header(63)
  val payloadForDP = WireDefault(Mux(doDpCalculation, payload, 0.U))
  val calculatedDP = WireDefault(payloadForDP.xorR)
  val dpError = WireDefault(doDpCalculation && (expectedDP ^ calculatedDP))

  val parityError = cpError || dpError
  val rxOpcode = io.rxOut.bits(4, 0)
  val rxIsWoData = SBMsgOpcode.OpsWithoutData.map(_.asUInt === rxOpcode).reduce(_ || _)

  // Don't enqueue if parity check fails and trigger an error
  val gatedDeserializerValid = Wire(Bool())
  gatedDeserializerValid := deserializer.io.out.valid && !parityError

  when(deserializer.io.out.valid && parityError) {
    parityErrReg := true.B
  }

  priorityQueue.io.enq.bits := deserializer.io.out.bits
  priorityQueue.io.enq.valid := gatedDeserializerValid
  deserializer.io.out.ready := priorityQueue.io.enq.ready
  
  priorityQueue.io.deq <> io.rxOut

  // The priority queue must not be full when there is a valid message incoming
  io.err.rxPriorityQueuesFull := gatedDeserializerValid && !priorityQueue.io.enq.ready
  io.err.sbParityErr := parityErrReg

  // =======================================================================
  // Assertions
  // =======================================================================
  block(Verification) {
    block(Verification.Assert) {
      AssertProperty(
        Sequence.BoolSequence(io.err.sbParityErr) |=> Sequence.BoolSequence(io.err.sbParityErr),
        label = Some("LinkNodeParityErrorIsSticky")
      )
    }
    block(Verification.Cover) {
      val sawFreezeBusy = RegInit(false.B)

      when(io.ctrl.freezeAcceptingPackets && !serializer.io.in.ready) {
        sawFreezeBusy := true.B
      }.elsewhen(io.ctrl.allPacketsSent) {
        sawFreezeBusy := false.B
      }

      cover(io.ctrl.freezeAcceptingPackets && io.txIn.valid && !io.txIn.ready, "LinkNodeFreezeBlocksTxAccept")
      cover(io.ctrl.allPacketsSent, "LinkNodeAllPacketsSent")
      cover(io.ctrl.allPacketsSent && sawFreezeBusy, "LinkNodeFreezeDrainsToAllPacketsSent")
      cover(txAccept && (io.ctrl.txMode === SBRxTxMode.RAW || txIsWoData), "LinkNodeTx64bPacketAccepted")
      cover(txAccept && io.ctrl.txMode === SBRxTxMode.PACKET && !txIsWoData, "LinkNodeTx128bPacketAccepted")
      cover(io.rxOut.fire && (io.ctrl.rxMode === SBRxTxMode.RAW || rxIsWoData), "LinkNodeRx64bPacketEmitted")
      cover(io.rxOut.fire && io.ctrl.rxMode === SBRxTxMode.PACKET && !rxIsWoData, "LinkNodeRx128bPacketEmitted")
      cover(deserializer.io.out.valid && cpError, "LinkNodeCpParityErrorDrop")
      cover(deserializer.io.out.valid && dpError, "LinkNodeDpParityErrorDrop")
      cover(io.err.desTimedout, "LinkNodeDeserializerTimeout")
      cover(io.err.rxPriorityQueuesFull, "LinkNodeRxPriorityQueueFull")
    }
  }
}

object MainSBLinkNode extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandLinkNode(128, 1, 32, 512, SidebandPriorityQueueDepths()),
    args = Array("-td", "./generatedVerilog/sideband"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}
