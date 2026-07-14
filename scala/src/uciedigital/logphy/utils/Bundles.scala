/*
  Description: 
    Bundles that are used within the LogPHY layer.
*/
package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.util._

class SidebandCtrlIO extends Bundle {
  val txEn = Output(Bool())
  val rxEn = Output(Bool())
  val rxTxMode = Output(SBRxTxMode())
  val sbReset = Output(Bool())
  val freezeAcceptingPackets = Output(Bool())
  val allPacketsSent = Input(Bool())  // Used in TrainError to make all packets are sent
}

class MainbandLaneCtrlIO (afeParams: AfeParams) extends Bundle {
  val txDataEn = Output(Vec(afeParams.mbLanes, Bool()))
  val txClkEn = Output(Bool())
  val txValidEn = Output(Bool())
  val txTrackEn = Output(Bool())            
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

class SidebandLanes(sbMsgWidth: Int) extends Bundle {
  /*
    As of UCIe 3.0, for internal logPHY IOs, the sideband clock is only used
    in the deserializer, so it isn't included in internal routing.
  */
  val data = Bits(sbMsgWidth.W)
}

class SidebandLaneIO(sbParams: SidebandParams) extends Bundle {  
  val tx = Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth))
  val rx = Flipped(Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth)))
}

class MainbandLanes(mbNumLanes: Int, mbSerializerRatio: Int) extends Bundle {
  val data    = Vec(mbNumLanes, Bits(mbSerializerRatio.W))
  val valid   = Bits(mbSerializerRatio.W)
  val clkP    = Bits(mbSerializerRatio.W)
  val clkN    = Bits(mbSerializerRatio.W)
  val trk     = Bits(mbSerializerRatio.W)
}

class MainbandLaneIO(afeParams: AfeParams) extends Bundle {
  val tx = Decoupled(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
  val rx = Flipped(Decoupled(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)))
}

class SidebandPhyLinkIO(sbLinkWidth: Int) extends Bundle {
  val in = new Bundle {
    val bits = Input(UInt(sbLinkWidth.W))
    val fwClock = Input(UInt(1.W))
  }
  val out = new Bundle {
    val bits = Output(UInt(sbLinkWidth.W))
    val fwClock = Output(UInt(1.W))
  }
}

class PhyStatusFromPhyIO extends Bundle {
  val pllLock = Bool()
  val clocksUngatedAndStable = Bool()
}

class PhyControlToPhyIO(afeParams: AfeParams) extends Bundle {
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

  val freqSel = SpeedMode()
  val clockPhaseSelect = UInt(afeParams.clockPhaseSelBitWidth.W)
  val doElectricalIdleTx = Bool()
  val doElectricalIdleRx = Bool()
}

class SubFsmControlIO extends Bundle {
  val start = Input(Bool())
  val substateTransitioning = Output(Bool())
  val error = Output(Bool())
  val done = Output(Bool())
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
