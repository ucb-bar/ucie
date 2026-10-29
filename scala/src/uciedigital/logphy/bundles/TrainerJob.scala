/*
  The job, result and code write that software exchanges with the trainer.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.common._

// No direction field, because sec 4.5.3.4 fixes it per sub-state.
class PhyTrainJob extends Bundle {
  val testKind = TrainingTestType()
  val clockPhase = SemanticClockPhase()
  val substate = LTSMState()
}

// The code travels with the verdict, so software can drain the queue late.
class PhyTrainResultEntry(afeParams: AfeParams) extends Bundle {
  val p = afeParams.bridge
  val slots = afeParams.slots

  val passVec = UInt(slots.count.W)
  val aggregatePass = Bool()

  val code = UInt(p.maxCodeWidth.W)
  val knobSel = UInt(log2Ceil(p.numKnobs max 2).W)
  val slotSel = UInt(log2Ceil(slots.count max 2).W)

  val codeValid = Bool()

  val seq = UInt(4.W)

  val isRemote = Bool()
}

class PhyCodeWrite(p: PhyBridgeParams, slots: PhySlots) extends Bundle {
  val knobSel = UInt(log2Ceil(p.numKnobs max 2).W)
  val slotSel = UInt(log2Ceil(slots.count max 2).W)

  val allSlots = Bool()
  val code = UInt(p.maxCodeWidth.W)
}
