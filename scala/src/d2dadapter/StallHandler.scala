package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._

class FDIStallHandlerIO() extends Bundle{
    val linkStallReq = Input(Bool())
    val linkStallDone = Output(Bool())// complete stall
    val plStallReq = Output(Bool())
    val lpStallAck = Input(Bool())
}

class RDIStallHandlerIO() extends Bundle{
    val mainbandStallReq = Output(Bool()) 
    val mainbandStallDone = Input(Bool())// complete stall
    val plStallReq = Input(Bool())
    val lpStallAck = Output(Bool())
}

class FDIStallHandler() extends Module{
    val io = IO(new FDIStallHandlerIO())
    val stallHandshakeStateReg = RegInit(StallHandshakeState.IDLE)
    val nextState = WireDefault(stallHandshakeStateReg)
    val ackSeen = (stallHandshakeStateReg === StallHandshakeState.WAIT_ACK_ASSERT) &&
        io.lpStallAck

    // Keep pl_stallreq asserted until the controller drops its request after
    // the stall boundary is reached. This lets the state machine transition
    // while the protocol side remains held stalled.
    io.plStallReq := stallHandshakeStateReg === StallHandshakeState.WAIT_ACK_ASSERT ||
        (stallHandshakeStateReg === StallHandshakeState.STALLED && io.linkStallReq)
    io.linkStallDone := ackSeen ||
        (stallHandshakeStateReg === StallHandshakeState.STALLED && io.linkStallReq)

    switch(stallHandshakeStateReg){
        is(StallHandshakeState.IDLE){
            when(io.linkStallReq && !io.lpStallAck){
                nextState := StallHandshakeState.WAIT_ACK_ASSERT
            }
        }
        is(StallHandshakeState.WAIT_ACK_ASSERT){
            when(io.lpStallAck){
                nextState := StallHandshakeState.STALLED
            }
        }
        is(StallHandshakeState.STALLED){
            when(!io.linkStallReq){
                nextState := StallHandshakeState.WAIT_ACK_DEASSERT
            }
        }
        is(StallHandshakeState.WAIT_ACK_DEASSERT){
            when(!io.lpStallAck){
                nextState := StallHandshakeState.IDLE
            }
        }
    }

    stallHandshakeStateReg := nextState
}


class RDIStallHandler() extends Module{
    val io = IO(new RDIStallHandlerIO())
    val stallHandshakeStateReg = RegInit(StallHandshakeState.IDLE)
    val nextState = WireDefault(stallHandshakeStateReg)

    io.mainbandStallReq := stallHandshakeStateReg === StallHandshakeState.WAIT_ACK_ASSERT ||
        stallHandshakeStateReg === StallHandshakeState.STALLED
    io.lpStallAck := stallHandshakeStateReg === StallHandshakeState.STALLED

    switch(stallHandshakeStateReg){
        is(StallHandshakeState.IDLE){
            when(io.plStallReq){
                nextState := StallHandshakeState.WAIT_ACK_ASSERT
            }
        }
        is(StallHandshakeState.WAIT_ACK_ASSERT){
            when(io.mainbandStallDone){
                nextState := StallHandshakeState.STALLED
            }
        }
        is(StallHandshakeState.STALLED){
            when(!io.plStallReq){
                nextState := StallHandshakeState.WAIT_ACK_DEASSERT
            }
        }
        is(StallHandshakeState.WAIT_ACK_DEASSERT){
            nextState := StallHandshakeState.IDLE
        }
    }

    stallHandshakeStateReg := nextState
}
