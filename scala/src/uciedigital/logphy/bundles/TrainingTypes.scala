/*
  The contract between the link training state machines and PhyBridge.
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

object TrainingTestType extends ChiselEnum {
  val PointTest, EyeWidthSweep = Value
}

object PhyTrainActionKind extends ChiselEnum {
  val RunTest, FinishState = Value
}

// Table 4-5 clock phase. The numeric codes stay inside the bridge.
object SemanticClockPhase extends ChiselEnum {
  val Center, Left, Right = Value
}

object PhyEyeDecisionKind extends ChiselEnum {
  val Repeat, Finish = Value
}

// Both sessions can be open at once (sec 4.5.1.4 step 6), so name one.
object PhyEyeDecisionTarget extends ChiselEnum {
  val TxRequester, RxResponder = Value
}

class PhyEyeDecision extends Bundle {
  val kind = PhyEyeDecisionKind()
  val target = PhyEyeDecisionTarget()
}

// Qualifies mbInitCalDone, because only MBINIT.CAL reports on that wire.
object PhyCalKind extends ChiselEnum {
  val MbInitCal, TxSelfCal, RxClockCal = Value
}

// Only RXDESKEW asks for a clockPhase other than Center.
class PhyTrainAction extends Bundle {
  val kind = PhyTrainActionKind()
  val testKind = TrainingTestType()
  val clockPhase = SemanticClockPhase()
}

class PhyTrainResult(afeParams: AfeParams) extends Bundle {
  val pass = Vec(afeParams.mbLanes, Bool())
  val validPass = Bool()
}

class SemanticPhaseApplyIO extends Bundle {
  val request = Flipped(Decoupled(SemanticClockPhase()))
  val completion = Decoupled(new SemanticPhaseCompletion)
}

class SemanticPhaseCompletion extends Bundle {
  val phase = SemanticClockPhase()
  val success = Bool()
}

// remoteResult is the partner's report on our Transmitter (sec 4.5.1.4).
class MbTrainBridgeIO(afeParams: AfeParams) extends Bundle {
  val action = Flipped(Decoupled(new PhyTrainAction))
  val result = Decoupled(new PhyTrainResult(afeParams))

  val remoteResult = Decoupled(new PhyTrainResult(afeParams))
}

class MbTrainRequesterCtrlIO(afeParams: AfeParams) extends Bundle {
  val req = new Bundle {
    val readyForReq = Input(Bool())
    val start = Output(Bool())
    val testKind = Output(TrainingTestType())
    val clockPhase = Output(SemanticClockPhase())
    val complete = Output(Bool())
  }

  val resp = new Bundle {
    val inProgress = Input(Bool())
    val done = Input(Bool())
    val results = Flipped(Valid(Vec(afeParams.mbLanes, UInt(1.W))))
  }
}
