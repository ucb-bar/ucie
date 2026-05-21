/*
  Description:
    Bridge between RDI and the PHY. It properly handles byte mapping during lane degradation 
    with counters and accumulation.

    Requires that:
      1. Number of lanes is either 8 or 16
      2. Serializer ratio is a multiple of 8
      3. nBytes >= (nLanes * bytesPerLane); bytesPerLane = serializerRatio / 8
      4. nBytes % (activeLanes * bytesPerLane) == 0; So, data from PHY fits onto RDI data path

    These restriction make implementation easier.
    
    TX (RDI -> lanes):
      - On the first beat, latches lp_data and asserts pl_trdy only when idle
      - Distributes bytes sequentially across active lanes per beat
      - Generates fixed clkP/clkN (1010.../0101...) and valid-framing (11110000...)
      - pl_trdy is deasserted for the duration of a multi-beat transfer

    RX (lanes -> RDI):
      - Accumulates incoming beats into a register; presents pl_data and
        asserts pl_valid only when the final beat has been received
*/

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.util._

class MainbandLaneController(afeParams: AfeParams, rdiParams: RdiParams) extends Module {
  val io = IO(new Bundle {
    val rdi = new Bundle {
      val tx = new Bundle {
        val lpIrdy = Input(Bool())
        val lpValid = Input(Bool())
        val lpData = Input(UInt((rdiParams.nBytes * 8).W))
        val plTrdy = Output(Bool())
      }
      val rx = new Bundle {
        val plValid = Output(Bool())
        val plData = Output(UInt((rdiParams.nBytes * 8).W))
      }
    }
    val mbLanes = new MainbandLaneIO(afeParams)
    val ctrl = new Bundle {
      val validFramingError = Output(Bool())
      val localTxFunctionalLanes = Input(UInt(3.W))
      val localRxFunctionalLanes = Input(UInt(3.W))
    }
  })

  val nBytes = rdiParams.nBytes
  val idxW = log2Ceil(nBytes)
  val nLanes = afeParams.mbLanes
  val serRatio = afeParams.mbSerializerRatio
  val bytesPerLane = serRatio / 8 // bytes each lane carries per serialiser word
 
  require(nLanes == 8 || nLanes == 16, "only 8- and 16-lane configurations are supported")
  require(serRatio % 8 == 0, "serRatio must be a multiple of 8")
 
  // Lane-map decoder
  def decodeLaneMap(code: UInt): UInt = {
    val full = MuxLookup(code, "hFFFF".U(16.W))(Seq(
      "b001".U -> "h00FF".U(16.W),
      "b010".U -> "hFF00".U(16.W),
      "b011".U -> "hFFFF".U(16.W),
      "b100".U -> "h000F".U(16.W),
      "b101".U -> "h00F0".U(16.W)
    ))
    full(nLanes - 1, 0)
  }

  def activeLanesForCode(code: UInt): UInt = MuxLookup(code, 16.U)(Seq(
    "b001".U -> 8.U,
    "b010".U -> 8.U,
    "b011".U -> 16.U,
    "b100".U -> 4.U,
    "b101".U -> 4.U
  ))

  def calcBeatsForActive(activeLanes: Int): UInt = {
    require(nBytes % (activeLanes * bytesPerLane) == 0, 
      "RDI nBytes must be cleanly divisible by the active PHY bandwidth")
    (nBytes / (activeLanes * bytesPerLane)).U
  }

  // Number of RDI-word beats required to drain the nBytes across the active lanes.
  def beatsForCode(code: UInt): UInt = MuxLookup(code, calcBeatsForActive(nLanes))(Seq(
    "b011".U -> calcBeatsForActive(16), 
    "b001".U -> calcBeatsForActive(8),  // Lane 0-7
    "b010".U -> calcBeatsForActive(8),  // Lane 8-15
    "b100".U -> calcBeatsForActive(4),  // Lane 0-3
    "b101".U -> calcBeatsForActive(4)   // Lane 4-7
  ))
 
  // Lane maps and beat counts 
  val txLaneMap = decodeLaneMap(io.ctrl.localTxFunctionalLanes)
  val rxLaneMap = decodeLaneMap(io.ctrl.localRxFunctionalLanes)
  val numTxActive = activeLanesForCode(io.ctrl.localTxFunctionalLanes)
  val numRxActive = activeLanesForCode(io.ctrl.localRxFunctionalLanes)
  val numTxBeats = beatsForCode(io.ctrl.localTxFunctionalLanes)
  val numRxBeats = beatsForCode(io.ctrl.localRxFunctionalLanes)
 
  // Fixed patterns 
  // clkP: 10101010...; clkN: 01010101...
  val clkPBits = VecInit(Seq.tabulate(serRatio)(i => (i % 2 == 0).B)).asUInt
  val clkNBits = ~clkPBits
 
  // Valid framing: 11110000...
  val validFrame = VecInit(Seq.tabulate(serRatio)(i => ((i % 8) < 4).B)).asUInt
 
  // TX state
  val maxPossibleBeats = nBytes / (4 * bytesPerLane)
  val beatCtrW = log2Ceil(maxPossibleBeats + 1)
 
  val txBeatCtr = RegInit(0.U(beatCtrW.W))
  val txDataReg = RegInit(0.U((nBytes * 8).W))
  val txBusy = txBeatCtr =/= 0.U
  val txLastBeat = txBeatCtr === (numTxBeats - 1.U)
 
  // Accept a new RDI word only when idle and the PHY FIFO has space
  val txStart = !txBusy && io.rdi.tx.lpValid && io.rdi.tx.lpIrdy && io.mbLanes.tx.ready
 
  // Latch data on valid
  when(txStart) {
    txDataReg := io.rdi.tx.lpData
  }
 
  // Beat counter logic
  when(txStart && !txLastBeat) {
    txBeatCtr := 1.U
  }.elsewhen(txBusy && io.mbLanes.tx.ready) {
    txBeatCtr := Mux(txLastBeat, 0.U, txBeatCtr + 1.U)  // short circuit to 0 for no latency tx
  }
 
  // Use the incoming data on lpData, so that there's no latency by using the latched data
  val txEffectiveData = Mux(txBusy, txDataReg, io.rdi.tx.lpData)
  val txDataBytes = VecInit(Seq.tabulate(nBytes)(i =>
    txEffectiveData(i * 8 + 7, i * 8)
  ))
 
  // Byte offset into the full nBytes word for the current beat
  val bytesPerBeatTx = numTxActive * bytesPerLane.U
  val txByteOffset = txBeatCtr * bytesPerBeatTx
 
  // TX: build lane bundle 
  val txBundle = Wire(new MainbandLanes(nLanes, serRatio))
  // TODO: See Chapter 5 to implement valid and clock gating logic
  txBundle.clkP := clkPBits
  txBundle.clkN := clkNBits
  txBundle.trk := clkPBits
  txBundle.valid := Mux(io.rdi.tx.lpValid || txBusy, validFrame, 0.U)
 
  val aPosW = log2Ceil(nLanes + 1)
 
  for (lane <- 0 until nLanes) {
    val laneActive = txLaneMap(lane)
 
    // Physical to Logical Lane remapping
    val activePos = WireDefault(0.U(aPosW.W))
    switch(io.ctrl.localTxFunctionalLanes) {
      is("b011".U) { activePos := lane.U }                    // (0->0, 1->1...)
      is("b001".U) { activePos := lane.U }                    // (0->0, 1->1... 7->7)
      is("b010".U) { activePos := math.max(0, lane - 8).U }   // (8->0, 9->1...)
      is("b100".U) { activePos := lane.U }                    // (0->0... 3->3)
      is("b101".U) { activePos := math.max(0, lane - 4).U }   // (4->0... 7->3)
    }
 
    // Mapping byte to the lane
    // Each `slot` carries the RDI byte at: txByteOffset  +  activePos  +  slot * numTxActive
    val slotBytes = Wire(Vec(bytesPerLane, UInt(8.W)))
    for (slot <- 0 until bytesPerLane) {
      val byteIdx = txByteOffset +& activePos +& (slot.U * numTxActive)
      slotBytes(slot) := Mux(
        laneActive && (byteIdx < nBytes.U),
        txDataBytes(byteIdx(idxW - 1, 0)), 
        0.U
      )
    }
    txBundle.data(lane) := Mux(laneActive, slotBytes.asUInt, 0.U)
  }
 
  // For pl_trdy, deassert while mid-flight so the adapter back-pressures
  io.rdi.tx.plTrdy := !txBusy && io.mbLanes.tx.ready
  io.mbLanes.tx.valid := txStart || txBusy
  io.mbLanes.tx.bits := txBundle
 
  // RX state
  val rxBeatCtr = RegInit(0.U(beatCtrW.W))
  val rxDataAccum = Reg(Vec(nBytes, UInt(8.W)))
  val rxLastBeat = rxBeatCtr === (numRxBeats - 1.U)
  val rxAccepting = io.mbLanes.rx.valid && io.mbLanes.rx.ready
 
  when(rxAccepting) {
    rxBeatCtr := Mux(rxLastBeat, 0.U, rxBeatCtr + 1.U)
  }
 
  val rxBundle = io.mbLanes.rx.bits
  val bytesPerBeatRx = numRxActive * bytesPerLane.U
  val rxByteOffset = rxBeatCtr * bytesPerBeatRx
  val maxBytesPerBeat = nLanes * bytesPerLane
 
  // RX: unpack lanes into accumulator
  val currentRxData = WireDefault(rxDataAccum)
  val rxPosW = log2Ceil(nLanes + 1)

  for (lane <- 0 until nLanes) {
    val laneActive = rxLaneMap(lane)
    val activePos = WireDefault(0.U(rxPosW.W))

    switch(io.ctrl.localRxFunctionalLanes) {
      is("b011".U) { activePos := lane.U }                           
      is("b001".U) { activePos := lane.U }                           
      is("b010".U) { activePos := math.max(0, lane - 8).U }          
      is("b100".U) { activePos := lane.U }                           
      is("b101".U) { activePos := math.max(0, lane - 4).U }          
    }

    val laneWord = rxBundle.data(lane)

    // Map RX lane data to the correct byte position in the accumulator register
    // For each slot in this physical lane, calculate its destination byte index
    for (slot <- 0 until bytesPerLane) {
      val byteIdx = rxByteOffset +& activePos +& (slot.U * numRxActive)
      val byteVal = laneWord(slot * 8 + 7, slot * 8)

      when(laneActive && (byteIdx < nBytes.U)) {
        currentRxData(byteIdx(idxW - 1, 0)) := byteVal  
        when(rxAccepting) {
          rxDataAccum(byteIdx(idxW - 1, 0)) := byteVal
        }
      }
    }
  }
 
  // Send the complete word only once the final beat has landed
  io.mbLanes.rx.ready := true.B
  io.rdi.rx.plValid := rxAccepting && rxLastBeat
  io.rdi.rx.plData := currentRxData.asUInt
 
  // Valid-framing error detection
  val rxValidBits = rxBundle.valid
  val currentFramingError = io.mbLanes.rx.valid && (rxValidBits =/= validFrame)
  val stickyError = RegInit(false.B)
  when(currentFramingError) {
    stickyError := true.B
  }
  io.ctrl.validFramingError := currentFramingError || stickyError
}

