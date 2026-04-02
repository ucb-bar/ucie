package edu.berkeley.cs.uciedigital.interfaces

import chisel3._
import chisel3.util._

case class RdiParams(width: Int = 64, sbWidth: Int = 32)

/** The raw D2D interface (RDI), from the perspective of the D2D Adapter. */
class Rdi(rdiParams: RdiParams) extends Bundle {

  /** Adapter to Physical Layer data.
    *
    * Encompasses lp_irdy, lp_valid, and pl_trdy from the UCIe specification.
    */
  val lpData = Decoupled3(Bits((8 * rdiParams.width).W))

  /** Physical Layer to Adapter data.
    *
    * Encompasses `pl_valid` and `pl_data` from the UCIe specification. Note
    * that backpressure is not possible. Data should be sampled whenever valid
    * is asserted at a clock edge.
    */
  val plData = Flipped(Valid(Bits((8 * rdiParams.width).W)))

  /** When asserted at a rising clock edge, it indicates a single credit return
    * from the Adapter to the Physical Layer for the Retimer Receiver buffers.
    * Each credit corresponds to 256B of mainband data. This signal must NOT
    * assert for dies that are not UCIe Retimers.
    */
  val lpRetimerCrd = Output(Bool())

  /** When asserted at a rising clock edge, it indicates a single credit return
    * from the Retimer to the Adapter. Each credit corresponds to 256B of
    * mainband data. This signal must NOT assert if the remote Link partner is
    * not a Retimer.
    */
  val plRetimerCrd = Input(Bool())

  /** Adapter request to Physical Layer to request state change. */
  val lpStateReq = Output(PhyStateReq())

  /** Adapter to Physical Layer indication that an error has occurred which
    * requires the Link to go down. Physical Layer must move to LinkError state
    * and stay there as long as lp_linkerror=1. The reason for having this be an
    * indication decoupled from regular state transitions is to allow immediate
    * action on part of the Adapter and Physical Layer in order to provide the
    * quickest path for error containment when applicable (for example, a viral
    * error escalation must map to the LinkError state). The Adapter must OR
    * internal error conditions with lp_linkerror received from Protocol Layer
    * on FDI.
    */
  val lpLinkError = Output(Bool())

  /** Physical Layer to Adapter Status indication of the Interface.
    *
    * The status signal is permitted to transition from Physical Layer
    * autonomously when applicable. For example the Physical Layer asserts the
    * Retrain status when it decides to enter retraining either autonomously or
    * when requested by remote agent.
    */
  val plStateStatus = Input(PhyState())

  val plInbandPres = Input(Bool())
  val plError = Input(Bool())
  val plCorrectableError = Input(Bool())
  val plNonFatalError = Input(Bool())
  val plTrainError = Input(Bool())
  val plPhyInRecenter = Input(Bool())
  val plStallReq = Input(Bool())
  val lpStallAck = Output(Bool())
  val plSpeedMode = Input(SpeedMode())
  val plLinkWidth = Input(PhyWidth())

  // Tie to 1 if clock gating not supported.
  val plClkReq = Input(Bool())
  val lpClkAck = Output(Bool())

  // Tie to 1 if clock gating not supported.
  val lpWakeReq = Output(Bool())
  val plWakeAck = Input(Bool())

  val plConfig = Flipped(Valid(UInt(rdiParams.sbWidth.W)))
  val plConfigCredit = Output(Bool())
  val lpConfig = Valid(UInt(rdiParams.sbWidth.W))
  val lpConfigCredit = Input(Bool())
}