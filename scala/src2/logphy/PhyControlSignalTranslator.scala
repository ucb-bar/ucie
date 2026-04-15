package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.utils._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import circt.stage.ChiselStage
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
//   vRefSelBitWidth: Int = 5,

//   numLinkOps: Int = 16,

//   STANDALONE: Boolean = true
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
/*
  Description: 
    A control signal translation module for signals between analog and digital.
*/
class PhyControlSignalTranslator(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val fromDigital = new Bundle {
      val mbCtrlIo = new Bundle {
        val txDataTriState = Input(Vec(afeParams.mbLanes, Bool()))
        val txClkTriState = Input(Bool())
        val txValidTriState = Input(Bool())
        val txTrackTriState = Input(Bool())            
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
      val vrefCtrlIo = new Bundle {
        val dataVrefSel = Vec(afeParams.mbLanes, Input(UInt(afeParams.vRefSelBitWidth.W)))
        val validVrefSel = Input(UInt(afeParams.vRefSelBitWidth.W))
        val clkNVrefSel = Input(UInt(afeParams.vRefSelBitWidth.W))
        val clkPVrefSel = Input(UInt(afeParams.vRefSelBitWidth.W))
        val trkVrefSel = Input(UInt(afeParams.vRefSelBitWidth.W))
      }
      val freqSel = Input(SpeedMode())
      val clockPhaseSelect = Input(UInt(afeParams.clockPhaseSelBitWidth.W))
    }

    val toDigital = new Bundle {
      val clockPhaseSelIo = new Bundle {
        val rangeMin = Output(UInt(afeParams.clockPhaseSelBitWidth.W))
        val rangeMax = Output(UInt(afeParams.clockPhaseSelBitWidth.W))
        val stepSize = Output(UInt(afeParams.clockPhaseSelBitWidth.W))
      }
      val vrefSelIo = new Bundle {
        val rangeMin = Output(UInt(afeParams.vRefSelBitWidth.W))
        val rangeMax = Output(UInt(afeParams.vRefSelBitWidth.W))
        val stepSize = Output(UInt(afeParams.vRefSelBitWidth.W))
      }
      val pllLock = Output(Bool())
    }

    val toPhy = new Bundle {

    }
  })


}