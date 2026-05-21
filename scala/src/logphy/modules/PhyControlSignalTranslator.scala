/*
  Description: 
    A control signal translation module for signals between analog and digital.
*/

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.utils._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.util._

// ================================================================================================
// Classes and objects here for clarity, but are defined else where.
// ** Won't compile at the moment due to refined. Will be removed **
// ================================================================================================
// case class AfeParams(
//   sbSerializerRatio: Int = 1,
//   sbWidth: Int = 1,
//   mbSerializerRatio: Int = 32,
//   mbLanes: Int = 16,

//   clockPhaseSelBitWidth: Int = 5,
// )

// /** The speed of the physical layer of the link, in GT/s. */
// object SpeedMode extends ChiselEnum {
//   val speed4 = Value(0x0.U(4.W))
//   val speed8 = Value(0x1.U(4.W))
//   val speed12 = Value(0x2.U(4.W))
//   val speed16 = Value(0x3.U(4.W))
//   val speed24 = Value(0x4.U(4.W))
//   val speed32 = Value(0x5.U(4.W))
//   val speed48 = Value(0x6.U(4.W))
//   val speed64 = Value(0x7.U(4.W))
// }
// ================================================================================================
class PhyControlSignalTranslator(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val fromDigital = new Bundle {
      val mbCtrlIo = new Bundle {
        val txDataEn = Input(Vec(afeParams.mbLanes, Bool()))
        val txClkEn = Input(Bool())
        val txValidEn = Input(Bool())
        val txTrackEn = Input(Bool())            
        val rxDataEn = Input(Vec(afeParams.mbLanes, Bool()))
        val rxClkEn = Input(Bool())
        val rxValidEn = Input(Bool())
        val rxTrackEn = Input(Bool())
      }
      val sbCtrlIo = new Bundle {
        val txDataEn = Input(Bool())
        val txClkEn = Input(Bool())
        val rxDataEn = Input(Bool())
        val rxClkEn = Input(Bool())
      }
      val freqSel = Input(SpeedMode())
      val clockPhaseSelect = Input(UInt(afeParams.clockPhaseSelBitWidth.W))
      val doElectricalIdleTx = Input(Bool())
      val doElectricalIdleRx = Input(Bool())
    }

    val fromPhy = Input(new PhyStatusFromPhyIO())
    val toDigital = Output(new PhyStatusFromPhyIO())
    val toPhy = Output(new PhyControlToPhyIO(afeParams))
  })

  io.toDigital := io.fromPhy

  io.toPhy.mbTxDataEn := io.fromDigital.mbCtrlIo.txDataEn
  io.toPhy.mbTxClkEn := io.fromDigital.mbCtrlIo.txClkEn
  io.toPhy.mbTxValidEn := io.fromDigital.mbCtrlIo.txValidEn
  io.toPhy.mbTxTrackEn := io.fromDigital.mbCtrlIo.txTrackEn
  io.toPhy.mbRxDataEn := io.fromDigital.mbCtrlIo.rxDataEn
  io.toPhy.mbRxClkEn := io.fromDigital.mbCtrlIo.rxClkEn
  io.toPhy.mbRxValidEn := io.fromDigital.mbCtrlIo.rxValidEn
  io.toPhy.mbRxTrackEn := io.fromDigital.mbCtrlIo.rxTrackEn

  io.toPhy.sbTxDataEn := io.fromDigital.sbCtrlIo.txDataEn
  io.toPhy.sbTxClkEn := io.fromDigital.sbCtrlIo.txClkEn
  io.toPhy.sbRxDataEn := io.fromDigital.sbCtrlIo.rxDataEn
  io.toPhy.sbRxClkEn := io.fromDigital.sbCtrlIo.rxClkEn

  io.toPhy.freqSel := io.fromDigital.freqSel
  io.toPhy.clockPhaseSelect := io.fromDigital.clockPhaseSelect
  io.toPhy.doElectricalIdleTx := io.fromDigital.doElectricalIdleTx
  io.toPhy.doElectricalIdleRx := io.fromDigital.doElectricalIdleRx
}
