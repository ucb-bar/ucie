/*
  The SBINIT state of sec 4.5.3.2, for a Standard Package sideband.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.util._

class SBInitSM(sbParams: SidebandParams, timeoutMaxCycles: Int) extends Module {
  val io = IO(new Bundle {
    val fsmCtrl = new SubFsmControlIO()
    val parityError = Output(Bool())
    val requesterSbLaneIo = new SidebandLaneIO(sbParams)
    val responderSbLaneIo = new SidebandLaneIO(sbParams)
  })

  val requester = Module(new SBInitRequester(sbParams, timeoutMaxCycles))
  val responder = Module(new SBInitResponder(sbParams))

  requester.io.start := io.fsmCtrl.start
  responder.io.start := requester.io.outOfReset

  // sec 7.1.3.2 makes a CP or DP error on a received message a fatal UIE.
  io.parityError := requester.io.parityError || responder.io.parityError
  io.requesterSbLaneIo <> requester.io.sbLaneIo
  io.responderSbLaneIo <> responder.io.sbLaneIo

  // SBINIT has no sub-states, no Stall (Table 7-9), and only a timeout error.
  io.fsmCtrl.substateTransitioning := false.B
  io.fsmCtrl.error := false.B
  io.fsmCtrl.stallReceived := false.B
  io.fsmCtrl.done := requester.io.done && responder.io.done
}

object SBInitStateRequester extends ChiselEnum {
  val sPATTERN, sOUT_OF_RESET, sSBINIT_DONE_MSG = Value
}

class SBInitRequester(sbParams: SidebandParams, timeoutMaxCycles: Int)
    extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val parityError = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
    val outOfReset = Output(Bool())
  })

  val sbInitClkPattern =
    PatternDefs.sbInitClock.U(64.W)

  // sec 4.5.3.2 step 5 alternates 1 ms on and 1 ms low over the 8 ms timeout.
  val oneMsCycles = timeoutMaxCycles / 8

  // Count cycles, because 1 ms is a fixed time and a raw word takes 96 UI.
  val sendPatternCounter = RegInit(0.U(log2Ceil(oneMsCycles).W))
  val fourPatternCounter = RegInit(0.U(3.W))
  val isSendingPattern = RegInit(true.B)
  val detectPatternCounter = RegInit(0.U(2.W))
  val patternDetected = Wire(Bool())

  patternDetected := detectPatternCounter === 2.U
  isSendingPattern := ((sendPatternCounter === (oneMsCycles - 1).U) ^ isSendingPattern) ||
    patternDetected

  val outOfResetDetected = WireInit(false.B)

  // The partner leaves sec 4.5.3.2 step 6 only on our message, so send one.
  val outOfResetSeen = RegInit(false.B)
  val outOfResetSent = RegInit(false.B)

  val msgSent = RegInit(false.B)
  val msgReceived = RegInit(false.B)

  val currentState = RegInit(SBInitStateRequester.sPATTERN)
  val nextState = WireInit(currentState)

  io.sbLaneIo.rx.ready := false.B
  io.sbLaneIo.tx.valid := false.B
  io.sbLaneIo.tx.bits.data := 0.U

  // RAW framing checks no parity, so check it here (sec 7.1.3.2).
  val rxParityOk = !SidebandParity.check(io.sbLaneIo.rx.bits.data).any
  val rxOutOfReset = io.sbLaneIo.rx.valid &&
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.SBINIT_OUT_OF_RESET)
  val rxDoneResp = io.sbLaneIo.rx.valid &&
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.SBINIT_DONE_RESP)
  io.parityError := false.B

  io.outOfReset := currentState === SBInitStateRequester.sSBINIT_DONE_MSG

  io.done := msgSent && msgReceived

  currentState := nextState
  switch(currentState) {
    is(SBInitStateRequester.sPATTERN) {
      when(io.start) {
        io.sbLaneIo.rx.ready := true.B

        // sec 4.5.3.2 step 3 needs 128 UI, or two matching raw words in a row.
        when(io.sbLaneIo.rx.valid && !patternDetected) {
          when(io.sbLaneIo.rx.bits.data(63, 0) === sbInitClkPattern) {
            when(detectPatternCounter =/= 2.U) {
              detectPatternCounter := detectPatternCounter + 1.U
            }
          }.otherwise {
            detectPatternCounter := 0.U
          }
        }

        // The state leaves a cycle late, so the count term stops a fifth word.
        io.sbLaneIo.tx.valid := isSendingPattern && (fourPatternCounter =/= 4.U)
        io.sbLaneIo.tx.bits.data := Cat(0.U(64.W), sbInitClkPattern)

        when(!patternDetected) {
          // Wrap at each half period to flip isSendingPattern through the XOR.
          when(sendPatternCounter === (oneMsCycles - 1).U) {
            sendPatternCounter := 0.U
          }.otherwise {
            sendPatternCounter := sendPatternCounter + 1.U
          }
        }.otherwise {
          // One accepted raw word is one of step 4's four iterations.
          when(io.sbLaneIo.tx.ready && fourPatternCounter =/= 4.U) {
            fourPatternCounter := fourPatternCounter + 1.U
          }
        }

        when(fourPatternCounter === 4.U) {
          nextState := SBInitStateRequester.sOUT_OF_RESET
        }
      }
    }
    is(SBInitStateRequester.sOUT_OF_RESET) {
      io.sbLaneIo.rx.ready := true.B
      when(rxOutOfReset) {
        when(rxParityOk) { outOfResetDetected := true.B }
        io.parityError := !rxParityOk
      }

      when(outOfResetDetected) { outOfResetSeen := true.B }
      val stop = (outOfResetSeen || outOfResetDetected) && outOfResetSent

      io.sbLaneIo.tx.valid := !stop
      when(io.sbLaneIo.tx.ready) {
        io.sbLaneIo.tx.bits.data := PhyLinkMsg.create(SBM.SBINIT_OUT_OF_RESET)
      }
      when(io.sbLaneIo.tx.fire) { outOfResetSent := true.B }

      when(stop) {
        nextState := SBInitStateRequester.sSBINIT_DONE_MSG
      }
    }
    is(SBInitStateRequester.sSBINIT_DONE_MSG) {
      io.sbLaneIo.tx.valid := !msgSent
      when(!msgSent && io.sbLaneIo.tx.ready) {
        io.sbLaneIo.tx.bits.data := PhyLinkMsg.create(SBM.SBINIT_DONE_REQ)
        msgSent := true.B
      }

      when(!msgReceived && rxDoneResp) {
        io.sbLaneIo.rx.ready := rxParityOk
        msgReceived := rxParityOk
        io.parityError := !rxParityOk
      }
    }
  }
}

class SBInitResponder(sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val parityError = Output(Bool())
    val sbLaneIo = new SidebandLaneIO(sbParams)
  })

  val msgSent = RegInit(false.B)
  val msgReceived = RegInit(false.B)

  val rxParityOk = !SidebandParity.check(io.sbLaneIo.rx.bits.data).any
  val rxDoneReq = SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.SBINIT_DONE_REQ)
  val rxOutOfReset =
    SBMsgCompare(io.sbLaneIo.rx.bits.data, SBM.SBINIT_OUT_OF_RESET)
  io.parityError := false.B

  io.done := msgSent
  io.sbLaneIo.rx.ready := false.B
  io.sbLaneIo.tx.valid := false.B
  io.sbLaneIo.tx.bits.data := 0.U

  when(io.start) {
    // The partner repeats {SBINIT Out of Reset} (step 6), so drain it here too.
    when(!msgReceived && io.sbLaneIo.rx.valid) {
      io.sbLaneIo.rx.ready := (rxDoneReq || rxOutOfReset) && rxParityOk
      msgReceived := rxDoneReq && rxParityOk
      io.parityError := (rxDoneReq || rxOutOfReset) && !rxParityOk
    }

    io.sbLaneIo.tx.valid := msgReceived && !msgSent
    when(msgReceived && io.sbLaneIo.tx.ready) {
      io.sbLaneIo.tx.bits.data := PhyLinkMsg.create(SBM.SBINIT_DONE_RESP)
      msgSent := true.B
    }
  }
}
