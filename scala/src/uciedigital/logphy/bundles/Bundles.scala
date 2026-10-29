/*
  The IO bundles used inside the Logical PHY.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.common._
import chisel3._
import chisel3.util._

class SidebandCtrlIO extends Bundle {
  val txEn = Output(Bool())
  val rxEn = Output(Bool())
  val rxTxMode = Output(SBRxTxMode())
  val sbReset = Output(Bool())
  val freezeAcceptingPackets = Output(Bool())
  val allPacketsSent = Input(
    Bool()
  )
}

class MainbandLaneCtrlIO(afeParams: AfeParams) extends Bundle {
  val txDataEn = Output(Vec(afeParams.mbLanes, Bool()))
  val txClkEn = Output(Bool())
  val txClkHeldLow = Output(Bool())
  val txValidEn = Output(Bool())
  val txTrackEn = Output(Bool())
  val txTrackPattern = Output(Bool())
  val txTrackLevel = Output(Bool())
  val rxDataEn = Output(Vec(afeParams.mbLanes, Bool()))
  val rxClkEn = Output(Bool())
  val rxValidEn = Output(Bool())
  val rxTrackEn = Output(Bool())
}

class PhyCtrlIO extends Bundle {
  val pllLock = Input(Bool())
  val freqSel = Output(SpeedMode())
  val doElectricalIdleRx = Output(Bool())
  val doElectricalIdleTx = Output(Bool())
}

class MainbandLanes(mbNumLanes: Int, mbSerializerRatio: Int) extends Bundle {
  val data = Vec(mbNumLanes, Bits(mbSerializerRatio.W))
  val valid = Bits(mbSerializerRatio.W)
  val clkP = Bits(mbSerializerRatio.W)
  val clkN = Bits(mbSerializerRatio.W)
  val trk = Bits(mbSerializerRatio.W)
}

class MainbandLaneIO(afeParams: AfeParams) extends Bundle {
  val tx = Decoupled(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)
  )
  val rx = Flipped(
    Decoupled(new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
  )
}

class PhyLaneEnableIO(afeParams: AfeParams) extends Bundle {
  val mbTxDataEn = Vec(afeParams.mbLanes, Bool())
  val mbTxClkEn = Bool()
  val mbTxValidEn = Bool()
  val mbTxTrackEn = Bool()
  val mbRxDataEn = Vec(afeParams.mbLanes, Bool())
  val mbRxClkEn = Bool()
  val mbRxValidEn = Bool()
  val mbRxTrackEn = Bool()

  val sbTxDataEn = Bool()
  val sbTxClkEn = Bool()
  val sbRxDataEn = Bool()
  val sbRxClkEn = Bool()
}

// Note: add an analog code to this boundary as a PhyKnob.
class PhyBridgeAnalogIO(afeParams: AfeParams) extends Bundle {
  val p = afeParams.bridge
  val slots = afeParams.slots

  val lanes = Output(new PhyLaneEnableIO(afeParams))
  val freqSel = Output(SpeedMode())
  val txResetb = Output(Bool())
  val rxResetb = Output(Bool())
  val doElectricalIdleTx = Output(Bool())
  val doElectricalIdleRx = Output(Bool())
  val txEqEnable = Output(Bool())

  val codes = Output(new PhyCodes(p, slots))

  val pllLock = Input(Bool())
  val clocksUngatedAndStable = Input(Bool())
}

class SubFsmControlIO extends Bundle {
  val start = Input(Bool())
  val substateTransitioning = Output(Bool())
  val error = Output(Bool())
  val done = Output(Bool())
  val stallReceived = Output(Bool())
}

class PHYParamExchangeIO extends Bundle {
  val voltageSwing = Output(UInt(5.W))
  val maxDataRate = Output(UInt(4.W))
  val clockMode = Output(UInt(1.W))
  val clockPhase = Output(UInt(1.W))
  val ucieSx8 = Output(UInt(1.W))
  val sbFeatExt = Output(UInt(1.W))
  val txAdjRuntime = Output(UInt(1.W))
  val moduleId = Output(UInt(2.W))
}
