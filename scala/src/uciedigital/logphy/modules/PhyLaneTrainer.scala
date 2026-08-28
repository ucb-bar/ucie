// Gets signals to the training, and sets the lanes appropriately
// Signals once done and the LTSM continues

// Can do specific triggers like do per lane deskew, etc and get done signals back

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.utils._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.util._

// IOs are relative to the PhyLaneTrainer
class MbTrainTestIntf(afeParams: AfeParams) extends Bundle {
  val txSelfCalStart = Input(Bool())
  val rxClkCalStart = Input(Bool())
  val txSelfCalDone = Output(Bool())
  val rxClkCalDone = Output(Bool())

  // Signals which test the MBTrain can do
  val capableTest = Input(new Bundle {
    val isTxType = Bool()
    val isRxType = Bool()
    val testKind = TrainingTestType() // Enum: Point, Sweep, Either
  })

  // PhyLaneTrainer requests a test with `req` and recieves response in `resp`
  val req = new Bundle {
    val readyForReq = Input(
      Bool()
    ) // MBTrain indicates it is ready for a request
    val start = Output(Bool())
    val testKind = Output(TrainingTestType()) // Enum: Point, Sweep, Either
    val complete = Output(Bool()) // Indicates to move on, no more testing
  }

  val resp = new Bundle {
    val inProgress = Input(Bool())
    val done = Input(Bool())

    // Aggregate result is held in vec index (0)
    // Note: Must accept result when valid goes HIGH as no real need for back pressure.
    val results = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))
  }

  // Remote sends results for Remote-intiated Rx Init D2C Eye Width Sweep,
  // can be used for adjusting PI phase.
  val remoteRxSweepResults = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))
}

class MbInitTestIntf(afeParams: AfeParams) extends Bundle {
  val selfCalStart = Input(Bool())
  val selfCalDone = Output(Bool())
}

class PhyTrainIO(afeParams: AfeParams) extends Bundle {
  val ltsmState = Input(LTSMState()) // Used to see what to train
  val mbTrain = new MbTrainTestIntf(afeParams)
  val mbInit = new MbInitTestIntf(afeParams)

  // Status on which tests are running
  val localStatus = new Bundle {
    val doingTxEyeWidthSweep = Input(Bool())
    val doingTxPointTest = Input(Bool())
    val doingRxEyeWidthSweep = Input(Bool())
    val doingRxPointTest = Input(Bool())
  }

  val remoteStatus = new Bundle {
    val doingTxEyeWidthSweep = Input(Bool())
    val doingTxPointTest = Input(Bool())
    val doingRxEyeWidthSweep = Input(Bool())
    val doingRxPointTest = Input(Bool())
  }

  // eyeSweepCtrl only valid when during RX Eye Width Sweeps
  val eyeSweepCtrl = new Bundle {
    val waitingForCommand = Input(
      Bool()
    ) // Test asking whether to run again or end
    val step = Output(Bool())
    val doneStepping = Output(Bool())
  }
}

class PhyLaneTrainer(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val phyTrainIo = new PhyTrainIO(afeParams)
  })

  // Recieves a trigger on which test can be done
  // Sets the PHY code
  // and triggers to LTSM to do the handshake
  // Recieves BER results to check if whether to do another test
  // Registers that hold the correct encoding lives here
  // RXCLKCAL settle window. Must stay under the deserializer's 512-cycle idle timeout.
  val rxClkCalDwell = 64
  val rxClkCalCounter = RegInit(0.U(log2Ceil(rxClkCalDwell + 1).W))
  when(io.phyTrainIo.mbTrain.rxClkCalStart) {
    when(rxClkCalCounter =/= rxClkCalDwell.U) {
      rxClkCalCounter := rxClkCalCounter + 1.U
    }
  }.otherwise {
    rxClkCalCounter := 0.U
  }

  /* There is no calibration hardware, so TX self calibration is complete as
     soon as MBTRAIN.TXSELFCAL asks for it -- the same reasoning as
     `req.complete` below, and as the RXCLKCAL dwell above, which models a
     settle window rather than real work.

     Holding this low meant MBTRAIN.TXSELFCAL never sent its
     {MBTRAIN.TXSELFCAL done req}, so the state could only ever be left by the
     requester and responder ready bits leaking across the transition into it.
     That accident carried the first pass through MBTRAIN; it did not carry the
     second one, which is where a Link that had width degraded in
     MBTRAIN.REPAIR stalled for good. */
  io.phyTrainIo.mbTrain.txSelfCalDone := io.phyTrainIo.mbTrain.txSelfCalStart
  io.phyTrainIo.mbTrain.rxClkCalDone := rxClkCalCounter === rxClkCalDwell.U
  io.phyTrainIo.mbTrain.req.start := false.B
  io.phyTrainIo.mbTrain.req.testKind := TrainingTestType.Either
  // No calibration hardware, so a capable state completes without running a test.
  io.phyTrainIo.mbTrain.req.complete := true.B

  io.phyTrainIo.mbInit.selfCalDone := false.B

  io.phyTrainIo.eyeSweepCtrl.step := false.B
  io.phyTrainIo.eyeSweepCtrl.doneStepping := false.B
}
