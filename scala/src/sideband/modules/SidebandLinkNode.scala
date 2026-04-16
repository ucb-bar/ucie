/*
  Description: 
    SidebandLinkNode encapsulates link serdes, and any flow-control and data integrity
    logic associated with transmitting or receiving sideband messages over the phyiscal link.
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
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
    }
  })  

  // TX Path: txIn --> SkidBuffer --> Parity Set --> Serializer --> txOut
  val serializer = Module(new SidebandLinkSerializer(sbLinkWidth, sbMsgWidth))

  serializer.io.ctrl.txMode := io.ctrl.txMode

  // Minimizes potentially large combinational path for serializer ready signal
  val skidBuffer = Module(new SkidBuffer(sbMsgWidth)) 
  
  io.txIn <> skidBuffer.io.in
  skidBuffer.io.out.ready := serializer.io.in.ready
  
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
  val calculatedDPPSet = WireDefault(payloadPSet.xorR && doDpCalculationPSet)

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

  // Parity Check Logic
  // Passes other messages through without parity check, can add more rules if needed.
  val parityErrReg = RegInit(false.B)
  val opcode = deserializer.io.out.bits(4, 0)
  val isAccComplete = SBM.isRegAccessComplete(opcode)
  val isReqRespMessage = SBM.isReqRespMessage(opcode)
  val isAccRequest = SBM.isRegAccessRequest(opcode)
  val doParityCheck = isAccComplete || isReqRespMessage || isAccRequest

  val header = WireDefault(deserializer.io.out.bits(63, 0))
  val bitsToProtect = WireDefault(header(61, 0)) // Skip DP(63), CP(62)
  val expectedCP = header(62)
  val calculatedCP = WireDefault(bitsToProtect.xorR)
  val cpError = WireDefault(expectedCP ^ calculatedCP)

  val doDpCalculation = !(SBMsgOpcode.OpsThatDontUseDPField.map(_.asUInt === opcode).reduce(_ || _))
  val payload = WireDefault(deserializer.io.out.bits(127, 64))
  val expectedDP = header(63)
  val calculatedDP = WireDefault(payload.xorR)
  val dpError = WireDefault(doDpCalculation && (expectedDP ^ calculatedDP))

  // Don't enqueue if parity check fails and trigger an error
  val gatedDeserializerValid = Wire(Bool())
  gatedDeserializerValid := deserializer.io.out.valid && 
                            ((doParityCheck && !cpError && !dpError) || !doParityCheck) 

  when(deserializer.io.out.valid && (doParityCheck && (cpError || dpError))) {
    parityErrReg := true.B
  }

  priorityQueue.io.enq.bits := deserializer.io.out.bits
  priorityQueue.io.enq.valid := gatedDeserializerValid
  deserializer.io.out.ready := priorityQueue.io.enq.ready
  
  priorityQueue.io.deq <> io.rxOut

  // The priority queue must not be full when there is a valid message incoming
  io.err.rxPriorityQueuesFull := gatedDeserializerValid && !priorityQueue.io.enq.ready
  io.err.sbParityErr := parityErrReg
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