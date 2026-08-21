// Link datapath status/control bundles.
package edu.berkeley.cs.uciedigital.regs

import chisel3._

class LinkToRegs extends Bundle {
  val linkUp = Bool()
  val linkTraining = Bool()
  val rawFormatEnabled = Bool()
  val x32AdvPkgEnabled = Bool()
  val linkWidthEnabled = UInt(4.W)
  val linkSpeedEnabled = UInt(4.W)
  val flitFormat = UInt(4.W)
  val statusChanged = Bool()
  val bwChanged = Bool()
  val corrErr = Bool()
  val uncorrNonFatal = Bool()
  val uncorrFatal = Bool()
  val trainingDone = Bool()
  val retrainDone = Bool()
}

class RegsToLink extends Bundle {
  val rawFormatEnable = Bool()
  val targetWidth = UInt(4.W)
  val targetSpeed = UInt(4.W)
  val startTraining = Bool()
  val retrain = Bool()
  val startTrainingPending = Bool()
  val retrainPending = Bool()
  val corrProtoReport = Bool()
  val nonFatalProtoReport = Bool()
  val fatalProtoReport = Bool()
}
