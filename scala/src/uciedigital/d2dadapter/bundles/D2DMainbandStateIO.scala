/*
  The control port of the mainband path. The Adapter makes the transmit gate,
  the transmit sink and the receive teardown on the control clock. The mainband
  path moves each one into the clock domain of the pins that it controls.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._

class D2DMainbandStateIO extends Bundle {
  val txGate = Input(Bool())
  val txSink = Input(Bool())
  val rxTeardown = Input(Bool())
  val rxAllowed = Input(Bool())
  val txGateCmd = Output(Bool())
  val txGateSettled = Output(Bool())
  val txSinkCmd = Output(Bool())
  val txSinkSettled = Output(Bool())
}
