/*
  Description: 
    SidebandInterfaceNode encapsulates interface serdes, and any flow-control and data integrity
    logic associated with transmitting or receiving sideband messages over the RDI/FDI interface.
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import edu.berkeley.cs.uciedigital.utils.SkidBuffer

class SidebandInterfaceNode(sbMsgWidth: Int, ncWidth: Int, numCredits: Int, 
                            queueDepths: SidebandPriorityQueueDepths) extends Module {
  val io = IO(new Bundle {
    /* Switch Facing IOs */
    // Messages coming from the switch to be serialized
    val txIn = Flipped(Decoupled(UInt(sbMsgWidth.W))) 
    
    // Messages going to the switch
    val rxOut = Decoupled(UInt(sbMsgWidth.W))         

    /* Interface Facing IOs */
    // Serialized chunks going out to RDI/FDI
    val txOut = Valid(UInt(ncWidth.W))
    
    // Credits returned from the receiver
    val txCreditReturn = Input(Bool()) 

    // Serialized chunks coming in over RDI/FDI
    val rxIn = Flipped(Valid(UInt(ncWidth.W)))
    
    // Credits returned to transmitter
    val rxCreditReturn = Output(Bool()) 

    // Error signals
    val sbParityErr = Output(Bool())
    val rxPriorityQueuesFull = Output(Bool())
  })

  // TX Path: txIn --> SkidBuffer --> Parity Set --> Serializer --> txOut
  val serializer = Module(new SidebandInterfaceSerializer(sbMsgWidth, ncWidth))
  val txCreditCounter = RegInit(numCredits.U((log2Ceil(numCredits) + 1).W))

  // Minimizes potentially large combinational path for serializer ready signal
  val skidBuffer = Module(new SkidBuffer(sbMsgWidth)) 
  
  // Serialize only if enough credits. RegisterAccessCompletition messages always get serialized
  val isEnoughCredits = txCreditCounter =/= 0.U 
  val isRegAccessComplete = SBM.isRegAccessComplete(skidBuffer.io.out.bits(4, 0))
  val hasPermission = isEnoughCredits || isRegAccessComplete

  io.txIn <> skidBuffer.io.in
  skidBuffer.io.out.ready := serializer.io.in.ready && hasPermission
  
  // Parity Set Logic -- set parity before serializing
  // NOTE: Assumption is that if data bits need to be zeroed out they will be, so DP == 0
  val headerPSet = WireDefault(skidBuffer.io.out.bits(63, 0))
  val bitsToProtectPSet = WireDefault(headerPSet(61, 0))      // Skip DP(63), CP(62)
  val calculatedCPPset = WireDefault(bitsToProtectPSet.xorR)

  // Can safely skip DP bit when calculating CP bit for messages that don't use DP because 
  // it be 0 with this logic
  val doDpCalculationPSet = !(SBMsgOpcode.OpsThatDontUseDPField.map(_.asUInt === headerPSet(4,0))
                                                               .reduce(_ || _))
  val payloadPSet = WireDefault(skidBuffer.io.out.bits(127, 64))
  val calculatedDPPSet = WireDefault(payloadPSet.xorR && doDpCalculationPSet)

  val newHeader = WireDefault(Cat(calculatedDPPSet, calculatedCPPset, headerPSet(61, 0)))
  val newBits = WireDefault(Cat(payloadPSet, newHeader))

  serializer.io.in.valid := skidBuffer.io.out.valid && hasPermission  
  serializer.io.in.bits := newBits
  io.txOut <> serializer.io.out

  // Credit return and consumption logic
  val consumeCredit = Wire(Bool())
  consumeCredit := skidBuffer.io.out.valid && skidBuffer.io.out.ready && !isRegAccessComplete

  when(consumeCredit && !io.txCreditReturn) {      
    txCreditCounter := txCreditCounter - 1.U
  }.elsewhen(!consumeCredit && io.txCreditReturn) {
    txCreditCounter := txCreditCounter + 1.U
  }
  
  // RX Path: rxIn --> Deserializer --> Parity Check --> PriorityQueue --> rxOut
  val deserializer = Module(new SidebandInterfaceDeserializer(sbMsgWidth, ncWidth))
  val priorityQueue = Module(new SidebandPriorityQueue(sbMsgWidth, queueDepths))
  
  io.rxIn <> deserializer.io.in

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
  
  priorityQueue.io.deq <> io.rxOut
  io.rxCreditReturn := io.rxOut.ready && io.rxOut.valid // Return credit when message is sent out

  // The priority queue must not be full when there is a valid message incoming
  io.rxPriorityQueuesFull := gatedDeserializerValid && !priorityQueue.io.enq.ready
  io.sbParityErr := parityErrReg
}

object MainSBInterfaceNode extends App {
  ChiselStage.emitSystemVerilogFile(
    new SidebandInterfaceNode(128, 32, 32, SidebandPriorityQueueDepths()),
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