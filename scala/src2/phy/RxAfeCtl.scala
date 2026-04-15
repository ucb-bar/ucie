package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import edu.berkeley.cs.uciedigital.phy.macros.{RxAfeIO, RxLaneCtlIO}

object RxAfeCtlState extends ChiselEnum {
  val sA, sAbInit, sAbSel, sB, sBaInit, sBaSel = Value
}

class RxAfeCtlIO extends Bundle {
  val bypassEn = Input(Bool())
  val bypass = Input(new RxAfeIO)
  val opCycles = Input(UInt(16.W))
  val overlapCycles = Input(UInt(16.W))
  val afe = Output(new RxAfeIO)
}

object RxAfeCtl {
  def connect(lane: RxLaneCtlIO, ctlIO: RxLaneDigitalCtlIO): RxAfeCtl = {
    val ctl = Module(new RxAfeCtl)
    lane.zen := ctlIO.zen
    lane.zctl := ctlIO.zctl
    lane.vref_sel := ctlIO.vref_sel
    ctl.io.bypassEn := ctlIO.afeBypassEn
    ctl.io.bypass := ctlIO.afeBypass
    ctl.io.opCycles := ctlIO.afeOpCycles
    ctl.io.overlapCycles := ctlIO.afeOverlapCycles
    lane.afe := ctl.io.afe
    ctl
  }
}

class RxAfeCtl extends Module with RequireSyncReset {
  val io = IO(new RxAfeCtlIO)

  val state = RegInit(RxAfeCtlState.sA)
  val ctr = RegInit(0.U(17.W))
  val ctrinc = Wire(UInt(17.W))
  ctrinc := ctr + 1.U

  io.afe := (new RxAfeIO).Lit(
    _.aEn -> false.B,
    _.aPc -> true.B,
    _.bEn -> false.B,
    _.bPc -> true.B,
    _.selA -> true.B
  )
  when(reset.asBool) {
    io.afe := (new RxAfeIO).Lit(
      _.aEn -> false.B,
      _.aPc -> true.B,
      _.bEn -> false.B,
      _.bPc -> true.B,
      _.selA -> true.B
    )
  }.otherwise {
    when(io.bypassEn) {
      io.afe := io.bypass
    }.otherwise {
      switch(state) {
        is(RxAfeCtlState.sA) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> false.B,
            _.bPc -> true.B,
            _.selA -> true.B
          )
        }
        is(RxAfeCtlState.sAbInit) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> true.B
          )
        }
        is(RxAfeCtlState.sAbSel) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> false.B
          )
        }
        is(RxAfeCtlState.sB) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> false.B,
            _.aPc -> true.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> false.B
          )
        }
        is(RxAfeCtlState.sBaInit) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> false.B
          )
        }
        is(RxAfeCtlState.sBaSel) {
          io.afe := (new RxAfeIO).Lit(
            _.aEn -> true.B,
            _.aPc -> false.B,
            _.bEn -> true.B,
            _.bPc -> false.B,
            _.selA -> true.B
          )
        }
      }
    }
  }
  ctr := ctrinc
  switch(state) {
    is(RxAfeCtlState.sA) {
      when(ctrinc === io.opCycles) {
        state := RxAfeCtlState.sAbInit
        ctr := 0.U
      }
    }
    is(RxAfeCtlState.sAbInit) {
      when(ctrinc === io.overlapCycles) {
        state := RxAfeCtlState.sAbSel
        ctr := 0.U
      }
    }
    is(RxAfeCtlState.sAbSel) {
      state := RxAfeCtlState.sB
      ctr := 0.U
    }
    is(RxAfeCtlState.sB) {
      when(ctrinc === io.opCycles) {
        state := RxAfeCtlState.sBaInit
        ctr := 0.U
      }
    }
    is(RxAfeCtlState.sBaInit) {
      when(ctrinc === io.overlapCycles) {
        state := RxAfeCtlState.sBaSel
        ctr := 0.U
      }
    }
    is(RxAfeCtlState.sBaSel) {
      state := RxAfeCtlState.sA
      ctr := 0.U
    }
  }
}
