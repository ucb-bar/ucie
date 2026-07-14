package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._

// LinkInitModule constants

object LinkInitState extends ChiselEnum {
    val INIT_START = Value(0x0.U(3.W))
    val RDI_BRINGUP = Value(0x1.U(3.W))
    val PARAM_EXCH = Value(0x2.U(3.W))
    val FDI_BRINGUP = Value(0x3.U(3.W))
    val INIT_DONE = Value(0x4.U(3.W))
}

// Sideband constants

object D2DAdapterSignalSize{
    val SIDEBAND_MESSAGE_OP_WIDTH = 6.W
}

object SideBandMessage{
    // start with 01: RES
    // start with 00: REQ
    // start with 1: others
    val NOP: UInt = "b000000".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val REQ_ACTIVE: UInt = "b000001".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val REQ_L1: UInt = "b000100".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val REQ_L2: UInt = "b001000".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val REQ_LINKRESET: UInt = "b001001".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val REQ_DISABLED: UInt = "b001100".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val RSP_ACTIVE: UInt = "b010001".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val RSP_PMNAK: UInt = "b010011".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val RSP_L1: UInt = "b010100".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val RSP_L2: UInt = "b011000".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val RSP_LINKRESET: UInt = "b011001".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val RSP_DISABLED: UInt = "b011100".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val ADV_CAP: UInt = "b100100".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
    val REGISTER_ACCESS: UInt = "b101000".U(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)
}

// Stall Handler constants

object StallHandlerWidth{
    val STATE_WIDTH = 2.W
}

object StallHandshakeState extends ChiselEnum{
    val IDLE = Value(0x0.U(StallHandlerWidth.STATE_WIDTH))
    val WAIT_ACK_ASSERT = Value(0x1.U(StallHandlerWidth.STATE_WIDTH))
    val STALLED = Value(0x2.U(StallHandlerWidth.STATE_WIDTH))
    val WAIT_ACK_DEASSERT = Value(0x3.U(StallHandlerWidth.STATE_WIDTH))
}