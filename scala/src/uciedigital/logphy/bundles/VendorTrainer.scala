/*
  The software training surface at the register boundary (sec 4.5.3.4).
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.common._

// TRAIN_MODE's hold_codes and ts_override_en travel in other bundles.
class VendorTrainerCtrl(afeParams: AfeParams) extends Bundle {
  val p = afeParams.bridge
  val slots = afeParams.slots

  val swJobsEn = Bool()
  val autoFinishEn = Bool()
  val finishWatermark = UInt(LogPhyTrainerParams.residencyWidth.W)

  val substateEn = UInt(MBTrainOperation.table.size.W)

  val codeWrite = Valid(new PhyCodeWrite(p, slots))

  val job = Valid(new PhyTrainJob)

  val finish = Bool()
  val abort = Bool()
  val fifoPop = Bool()

  val swCalDone = Bool()

  val eyePub = Valid(new PhyEyeEdges(p))
}

class VendorTrainerStatus(afeParams: AfeParams) extends Bundle {
  val jobTaken = Bool()

  val jobBusy = Bool()
  val waitingForJob = Bool()
  val codeSettling = Bool()

  val refused = Bool()
  val overflow = Bool()

  val calPending = Bool()

  val fifoLevel = UInt(log2Ceil(LogPhyTrainerParams.resultDepth + 1).W)
  val substate = LTSMState()
  val residencyLeft = UInt(LogPhyTrainerParams.residencyWidth.W)

  val result = Valid(new PhyTrainResultEntry(afeParams))
}

object VendorTrainerCtrl {

  // One tie-off for every harness. With it the sec 4.5.3.4 default runs.
  def tieOff(c: VendorTrainerCtrl): Unit = {
    c.swJobsEn := false.B
    c.autoFinishEn := false.B
    c.finishWatermark := 0.U

    // It matters only once swJobsEn is set.
    c.substateEn := ((BigInt(1) << MBTrainOperation.table.size) - 1).U
    c.codeWrite.valid := false.B
    c.codeWrite.bits := 0.U.asTypeOf(chiselTypeOf(c.codeWrite.bits))
    c.job.valid := false.B
    c.job.bits := 0.U.asTypeOf(chiselTypeOf(c.job.bits))
    c.finish := false.B
    c.abort := false.B
    c.fifoPop := false.B

    // With no vendor block nothing answers a calibration, so MBINIT.CAL waits.
    c.swCalDone := false.B
    c.eyePub.valid := false.B
    c.eyePub.bits := 0.U.asTypeOf(chiselTypeOf(c.eyePub.bits))
  }
}
