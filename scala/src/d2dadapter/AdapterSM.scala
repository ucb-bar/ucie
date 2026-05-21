package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._

class AdapterSMIO(val fdiParams: FdiParams, val rdiParams: RdiParams) extends Bundle {
  val fdi_lp_state_req = Input(RDIStateReq())
  val fdi_lp_linkerror = Input(Bool())
  val fdi_lp_rx_active_sts = Input(Bool())
  val fdi_pl_state_sts = Output(RDIState())
  val fdi_pl_rx_active_req = Output(Bool())
  val fdi_pl_inband_pres = Output(Bool())

  val rdi_lp_linkerror = Output(Bool())
  val rdi_lp_state_req = Output(RDIStateReq())
  val rdi_pl_state_sts = Input(RDIState())
  val rdi_pl_inband_pres = Input(Bool())

  val sb_snd = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
  val sb_rcv = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
  val sb_rdy = Input(Bool())

  val linkmgmt_stallreq = Output(Bool())
  val linkmgmt_stalldone = Input(Bool())
}

/**
  * Unified adapter state machine that folds the legacy link-management,
  * link-init, link-reset, and disabled-state submodules into one module.
  */
class AdapterSM(
  val fdiParams: FdiParams,
  val rdiParams: RdiParams,
  val sbParams: SidebandParams,
) extends Module {
  val io = IO(new AdapterSMIO(fdiParams, rdiParams))

  // Top-level state / output registers
  val linkStateReg = RegInit(RDIState.reset)
  val fdiLpStateReqPrevReg = RegNext(io.fdi_lp_state_req)

  val rdiLpLinkErrorReg = RegInit(false.B)
  val rdiLpStateReqReg = RegInit(RDIStateReq.nop)
  val fdiPlRxActiveReqReg = RegInit(false.B)
  val fdiPlInbandPresReg = RegInit(false.B)
  val linkMgmtStallReqReg = RegInit(false.B)

  // Link-init sub-state
  val linkInitStateReg = RegInit(LinkInitState.INIT_START)
  val paramExchSbMsgRcvFlag = RegInit(false.B)
  val paramExchSbMsgSntFlag = RegInit(false.B)
  val activeSbMsgReqRcvFlag = RegInit(false.B)
  val activeSbMsgRspRcvFlag = RegInit(false.B)
  val activeSbMsgExtRspReg = RegInit(false.B)
  val activeSbMsgExtReqReg = RegInit(false.B)
  val transitionToActiveReg = RegInit(false.B)

  // Link-reset request/response tracking
  val linkResetFdiReqReg = RegInit(false.B)
  val linkResetSbMsgReqRcvFlag = RegInit(false.B)
  val linkResetSbMsgRspRcvFlag = RegInit(false.B)
  val linkResetSbMsgExtRspReg = RegInit(false.B)
  val linkResetSbMsgExtReqReg = RegInit(false.B)

  // Disabled request/response tracking
  val disabledFdiReqReg = RegInit(false.B)
  val disabledSbMsgReqRcvReg = RegInit(false.B)
  val disabledSbMsgRspRcvReg = RegInit(false.B)
  val disabledSbMsgExtRspReg = RegInit(false.B)
  val disabledSbMsgExtReqReg = RegInit(false.B)

  // Public outputs
  io.rdi_lp_linkerror := rdiLpLinkErrorReg
  io.rdi_lp_state_req := rdiLpStateReqReg
  io.fdi_pl_state_sts := linkStateReg
  io.fdi_pl_rx_active_req := fdiPlRxActiveReqReg
  io.fdi_pl_inband_pres := fdiPlInbandPresReg
  io.linkmgmt_stallreq := linkMgmtStallReqReg

  // Common state-change helpers
  val linkErrorPhySts = io.rdi_pl_state_sts === RDIState.linkError
  val stallHandshakeDone = linkMgmtStallReqReg && io.linkmgmt_stalldone
  val rxDeactive = !io.fdi_lp_rx_active_sts && !fdiPlRxActiveReqReg
  val retrainPhySts = io.rdi_pl_state_sts === RDIState.retrain

  // Link-init outputs
  val activeEntry = WireDefault(false.B)
  val linkInitFdiPlInbandPres = WireDefault(false.B)
  val linkInitFdiPlRxActiveReq = WireDefault(false.B)
  val linkInitRdiLpStateReq = WireDefault(RDIStateReq.nop)
  val linkInitSbSnd = WireDefault(SideBandMessage.NOP)

  when(linkStateReg === RDIState.reset) {
    switch(linkInitStateReg) {
      is(LinkInitState.INIT_START) {
        when(io.rdi_pl_inband_pres) {
          linkInitRdiLpStateReq := RDIStateReq.nop
        }
      }
      is(LinkInitState.RDI_BRINGUP) {
        linkInitRdiLpStateReq := RDIStateReq.active
      }
      is(LinkInitState.PARAM_EXCH) {
        linkInitRdiLpStateReq := RDIStateReq.active
        when(!paramExchSbMsgSntFlag) {
          linkInitSbSnd := SideBandMessage.ADV_CAP
        }
      }
      is(LinkInitState.FDI_BRINGUP) {
        linkInitFdiPlInbandPres := true.B
        linkInitRdiLpStateReq := RDIStateReq.active
        linkInitFdiPlRxActiveReq := activeSbMsgReqRcvFlag

        when(io.fdi_lp_rx_active_sts && linkInitFdiPlRxActiveReq && !activeSbMsgExtRspReg) {
          linkInitSbSnd := SideBandMessage.RSP_ACTIVE
        }.elsewhen(transitionToActiveReg && !activeSbMsgExtReqReg) {
          linkInitSbSnd := SideBandMessage.REQ_ACTIVE
        }
      }
      is(LinkInitState.INIT_DONE) {
        activeEntry := true.B
        linkInitFdiPlInbandPres := true.B
        linkInitFdiPlRxActiveReq := true.B
        linkInitRdiLpStateReq := RDIStateReq.active
      }
    }
  }.elsewhen(linkStateReg === RDIState.active) {
    activeEntry := true.B
    linkInitFdiPlInbandPres := true.B
    linkInitFdiPlRxActiveReq := true.B
    linkInitRdiLpStateReq := RDIStateReq.active
  }

  // Link-reset outputs
  val linkResetEntry = WireDefault(false.B)
  val linkResetSbSnd = WireDefault(SideBandMessage.NOP)
  when(
    linkStateReg === RDIState.reset ||
    linkStateReg === RDIState.active ||
    linkStateReg === RDIState.retrain
  ) {
    linkResetEntry := linkResetSbMsgExtRspReg || linkResetSbMsgRspRcvFlag

    when(linkResetFdiReqReg && !linkResetSbMsgReqRcvFlag && !linkResetSbMsgExtReqReg) {
      linkResetSbSnd := SideBandMessage.REQ_LINKRESET
    }.elsewhen(linkResetSbMsgReqRcvFlag && !linkResetSbMsgExtRspReg) {
      linkResetSbSnd := SideBandMessage.RSP_LINKRESET
    }
  }

  // Disabled outputs
  val disabledEntry = WireDefault(false.B)
  val disabledSbSnd = WireDefault(SideBandMessage.NOP)
  when(
    linkStateReg === RDIState.reset ||
    linkStateReg === RDIState.active ||
    linkStateReg === RDIState.retrain ||
    linkStateReg === RDIState.linkReset
  ) {
    disabledEntry := disabledSbMsgExtRspReg || disabledSbMsgRspRcvReg

    when(disabledFdiReqReg && !disabledSbMsgReqRcvReg && !disabledSbMsgExtReqReg) {
      disabledSbSnd := SideBandMessage.REQ_DISABLED
    }.elsewhen(disabledSbMsgReqRcvReg && !disabledSbMsgExtRspReg) {
      disabledSbSnd := SideBandMessage.RSP_DISABLED
    }
  }

  // Sideband arbitration
  val disabledSbSelected = WireDefault(false.B)
  val linkResetSbSelected = WireDefault(false.B)
  val linkInitSbSelected = WireDefault(false.B)

  io.sb_snd := SideBandMessage.NOP
  when(linkStateReg === RDIState.reset) {
    when(disabledSbSnd =/= SideBandMessage.NOP) {
      io.sb_snd := disabledSbSnd
      disabledSbSelected := true.B
    }.elsewhen(linkResetSbSnd =/= SideBandMessage.NOP) {
      io.sb_snd := linkResetSbSnd
      linkResetSbSelected := true.B
    }.elsewhen(linkInitSbSnd =/= SideBandMessage.NOP) {
      io.sb_snd := linkInitSbSnd
      linkInitSbSelected := true.B
    }
  }.elsewhen(linkStateReg === RDIState.active) {
    when(disabledSbSnd =/= SideBandMessage.NOP) {
      io.sb_snd := disabledSbSnd
      disabledSbSelected := true.B
    }.elsewhen(linkResetSbSnd =/= SideBandMessage.NOP) {
      io.sb_snd := linkResetSbSnd
      linkResetSbSelected := true.B
    }
  }.elsewhen(linkStateReg === RDIState.retrain) {
    when(disabledSbSnd =/= SideBandMessage.NOP) {
      io.sb_snd := disabledSbSnd
      disabledSbSelected := true.B
    }.elsewhen(linkResetSbSnd =/= SideBandMessage.NOP) {
      io.sb_snd := linkResetSbSnd
      linkResetSbSelected := true.B
    }
  }.elsewhen(linkStateReg === RDIState.linkReset) {
    when(disabledSbSnd =/= SideBandMessage.NOP) {
      io.sb_snd := disabledSbSnd
      disabledSbSelected := true.B
    }
  }

  val disabledSbAccepted = disabledSbSelected && io.sb_rdy
  val linkResetSbAccepted = linkResetSbSelected && io.sb_rdy
  val linkInitSbAccepted = linkInitSbSelected && io.sb_rdy

  // LinkError propagation from protocol to PHY.
  rdiLpLinkErrorReg := io.fdi_lp_linkerror

  // Link-init state update
  when(linkStateReg === RDIState.reset) {
    paramExchSbMsgRcvFlag := false.B
    paramExchSbMsgSntFlag := false.B
    activeSbMsgReqRcvFlag := false.B
    activeSbMsgRspRcvFlag := false.B
    activeSbMsgExtRspReg := false.B
    activeSbMsgExtReqReg := false.B
    transitionToActiveReg := false.B

    switch(linkInitStateReg) {
      is(LinkInitState.INIT_START) {
        when(io.rdi_pl_inband_pres) {
          linkInitStateReg := LinkInitState.RDI_BRINGUP
        }
      }
      is(LinkInitState.RDI_BRINGUP) {
        when(io.rdi_pl_state_sts === RDIState.active) {
          linkInitStateReg := LinkInitState.PARAM_EXCH
        }
      }
      is(LinkInitState.PARAM_EXCH) {
        when(io.sb_rcv === SideBandMessage.ADV_CAP) {
          paramExchSbMsgRcvFlag := true.B
        }.otherwise {
          paramExchSbMsgRcvFlag := paramExchSbMsgRcvFlag
        }

        when(linkInitSbAccepted && linkInitSbSnd === SideBandMessage.ADV_CAP) {
          paramExchSbMsgSntFlag := true.B
        }.otherwise {
          paramExchSbMsgSntFlag := paramExchSbMsgSntFlag
        }

        when(paramExchSbMsgSntFlag && paramExchSbMsgRcvFlag) {
          linkInitStateReg := LinkInitState.FDI_BRINGUP
        }
      }
      is(LinkInitState.FDI_BRINGUP) {
        when(io.sb_rcv === SideBandMessage.RSP_ACTIVE) {
          activeSbMsgRspRcvFlag := true.B
        }.otherwise {
          activeSbMsgRspRcvFlag := activeSbMsgRspRcvFlag
        }

        when(io.sb_rcv === SideBandMessage.REQ_ACTIVE) {
          activeSbMsgReqRcvFlag := true.B
        }.otherwise {
          activeSbMsgReqRcvFlag := activeSbMsgReqRcvFlag
        }

        when(linkInitSbAccepted && linkInitSbSnd === SideBandMessage.RSP_ACTIVE) {
          activeSbMsgExtRspReg := true.B
        }.otherwise {
          activeSbMsgExtRspReg := activeSbMsgExtRspReg
        }

        when(linkInitSbAccepted && linkInitSbSnd === SideBandMessage.REQ_ACTIVE) {
          activeSbMsgExtReqReg := true.B
        }.otherwise {
          activeSbMsgExtReqReg := activeSbMsgExtReqReg
        }

        when(io.fdi_lp_state_req === RDIStateReq.active &&
             fdiLpStateReqPrevReg === RDIStateReq.nop) {
          transitionToActiveReg := true.B
        }.otherwise {
          transitionToActiveReg := transitionToActiveReg
        }

        when(activeSbMsgExtRspReg && activeSbMsgRspRcvFlag) {
          linkInitStateReg := LinkInitState.INIT_DONE
        }
      }
      is(LinkInitState.INIT_DONE) {
        linkInitStateReg := LinkInitState.INIT_DONE
      }
    }
  }.elsewhen(linkStateReg === RDIState.active) {
    linkInitStateReg := LinkInitState.INIT_DONE
  }.otherwise {
    linkInitStateReg := LinkInitState.INIT_START
    paramExchSbMsgRcvFlag := false.B
    paramExchSbMsgSntFlag := false.B
    activeSbMsgReqRcvFlag := false.B
    activeSbMsgRspRcvFlag := false.B
    activeSbMsgExtRspReg := false.B
    activeSbMsgExtReqReg := false.B
    transitionToActiveReg := false.B
  }

  // Link-reset state update
  when(
    linkStateReg === RDIState.reset ||
    linkStateReg === RDIState.active ||
    linkStateReg === RDIState.retrain
  ) {
    when(
      linkStateReg === RDIState.reset &&
      io.fdi_lp_state_req === RDIStateReq.linkReset &&
      fdiLpStateReqPrevReg === RDIStateReq.nop
    ) {
      linkResetFdiReqReg := true.B
    }.elsewhen(
      io.fdi_lp_state_req === RDIStateReq.linkReset &&
      linkStateReg =/= RDIState.reset
    ) {
      linkResetFdiReqReg := true.B
    }.otherwise {
      linkResetFdiReqReg := linkResetFdiReqReg
    }

    when(linkResetSbAccepted && linkResetSbSnd === SideBandMessage.REQ_LINKRESET) {
      linkResetSbMsgExtReqReg := true.B
    }.otherwise {
      linkResetSbMsgExtReqReg := linkResetSbMsgExtReqReg
    }

    when(linkResetSbAccepted && linkResetSbSnd === SideBandMessage.RSP_LINKRESET) {
      linkResetSbMsgExtRspReg := true.B
    }.otherwise {
      linkResetSbMsgExtRspReg := linkResetSbMsgExtRspReg
    }

    when(io.sb_rcv === SideBandMessage.REQ_LINKRESET) {
      linkResetSbMsgReqRcvFlag := true.B
    }.otherwise {
      linkResetSbMsgReqRcvFlag := linkResetSbMsgReqRcvFlag
    }

    when(io.sb_rcv === SideBandMessage.RSP_LINKRESET) {
      linkResetSbMsgRspRcvFlag := true.B
    }.otherwise {
      linkResetSbMsgRspRcvFlag := linkResetSbMsgRspRcvFlag
    }
  }.otherwise {
    linkResetFdiReqReg := false.B
    linkResetSbMsgReqRcvFlag := false.B
    linkResetSbMsgRspRcvFlag := false.B
    linkResetSbMsgExtRspReg := false.B
    linkResetSbMsgExtReqReg := false.B
  }

  // Disabled state update
  when(
    linkStateReg === RDIState.reset ||
    linkStateReg === RDIState.active ||
    linkStateReg === RDIState.retrain ||
    linkStateReg === RDIState.linkReset
  ) {
    when(
      linkStateReg === RDIState.reset &&
      io.fdi_lp_state_req === RDIStateReq.disabled &&
      fdiLpStateReqPrevReg === RDIStateReq.nop
    ) {
      disabledFdiReqReg := true.B
    }.elsewhen(
      io.fdi_lp_state_req === RDIStateReq.disabled &&
      linkStateReg =/= RDIState.reset
    ) {
      disabledFdiReqReg := true.B
    }.otherwise {
      disabledFdiReqReg := disabledFdiReqReg
    }

    when(disabledSbAccepted && disabledSbSnd === SideBandMessage.REQ_DISABLED) {
      disabledSbMsgExtReqReg := true.B
    }.otherwise {
      disabledSbMsgExtReqReg := disabledSbMsgExtReqReg
    }

    when(disabledSbAccepted && disabledSbSnd === SideBandMessage.RSP_DISABLED) {
      disabledSbMsgExtRspReg := true.B
    }.otherwise {
      disabledSbMsgExtRspReg := disabledSbMsgExtRspReg
    }

    when(io.sb_rcv === SideBandMessage.REQ_DISABLED) {
      disabledSbMsgReqRcvReg := true.B
    }.otherwise {
      disabledSbMsgReqRcvReg := disabledSbMsgReqRcvReg
    }

    when(io.sb_rcv === SideBandMessage.RSP_DISABLED) {
      disabledSbMsgRspRcvReg := true.B
    }.otherwise {
      disabledSbMsgRspRcvReg := disabledSbMsgRspRcvReg
    }
  }.otherwise {
    disabledFdiReqReg := false.B
    disabledSbMsgReqRcvReg := false.B
    disabledSbMsgRspRcvReg := false.B
    disabledSbMsgExtReqReg := false.B
    disabledSbMsgExtRspReg := false.B
  }

  // Stall arbitration
  when(linkStateReg === RDIState.active) {
    linkMgmtStallReqReg := linkResetEntry || disabledEntry || retrainPhySts
  }.otherwise {
    linkMgmtStallReqReg := false.B
  }

  // RX-active arbitration
  when(linkStateReg === RDIState.active) {
    when(linkResetEntry || disabledEntry || retrainPhySts || linkErrorPhySts) {
      fdiPlRxActiveReqReg := false.B
    }.otherwise {
      fdiPlRxActiveReqReg := true.B
    }
  }.otherwise {
    when(linkResetEntry || disabledEntry || linkErrorPhySts) {
      fdiPlRxActiveReqReg := false.B
    }.otherwise {
      fdiPlRxActiveReqReg := linkInitFdiPlRxActiveReq
    }
  }

  // Inband-presence arbitration
  when(linkStateReg === RDIState.reset) {
    when(linkErrorPhySts) {
      fdiPlInbandPresReg := false.B
    }.otherwise {
      fdiPlInbandPresReg := linkInitFdiPlInbandPres
    }
  }.elsewhen(
    linkStateReg === RDIState.linkError ||
    linkStateReg === RDIState.disabled ||
    linkStateReg === RDIState.linkReset
  ) {
    fdiPlInbandPresReg := false.B
  }.otherwise {
    when(linkErrorPhySts) {
      fdiPlInbandPresReg := false.B
    }.otherwise {
      fdiPlInbandPresReg := true.B
    }
  }

  // RDI lp-state request generation
  rdiLpStateReqReg := rdiLpStateReqReg
  when(linkStateReg === RDIState.reset) {
    rdiLpStateReqReg := linkInitRdiLpStateReq
  }.elsewhen(linkStateReg === RDIState.active) {
    when(retrainPhySts) {
      rdiLpStateReqReg := RDIStateReq.retrain
    }
  }.elsewhen(linkStateReg === RDIState.retrain) {
    rdiLpStateReqReg := RDIStateReq.nop
  }.elsewhen(linkStateReg === RDIState.linkError) {
    when(io.fdi_lp_state_req === RDIStateReq.active &&
         io.rdi_pl_state_sts === RDIState.linkError) {
      rdiLpStateReqReg := RDIStateReq.active
    }.otherwise {
      rdiLpStateReqReg := RDIStateReq.nop
    }
  }.elsewhen(linkStateReg === RDIState.disabled) {
    when(io.fdi_lp_state_req === RDIStateReq.active) {
      rdiLpStateReqReg := RDIStateReq.active
    }.otherwise {
      rdiLpStateReqReg := RDIStateReq.disabled
    }
  }.elsewhen(linkStateReg === RDIState.linkReset) {
    when(io.fdi_lp_state_req === RDIStateReq.active) {
      rdiLpStateReqReg := RDIStateReq.active
    }.otherwise {
      rdiLpStateReqReg := RDIStateReq.linkReset
    }
  }

  // Unified link-state machine
  switch(linkStateReg) {
    is(RDIState.reset) {
      when(linkErrorPhySts) {
        linkStateReg := RDIState.linkError
      }.elsewhen(disabledEntry && rxDeactive) {
        linkStateReg := RDIState.disabled
      }.elsewhen(linkResetEntry && rxDeactive) {
        linkStateReg := RDIState.linkReset
      }.elsewhen(activeEntry) {
        linkStateReg := RDIState.active
      }
    }
    is(RDIState.active) {
      when(linkErrorPhySts) {
        linkStateReg := RDIState.linkError
      }.elsewhen(disabledEntry && rxDeactive && stallHandshakeDone) {
        linkStateReg := RDIState.disabled
      }.elsewhen(linkResetEntry && rxDeactive && stallHandshakeDone) {
        linkStateReg := RDIState.linkReset
      }.elsewhen(retrainPhySts && rxDeactive && stallHandshakeDone) {
        linkStateReg := RDIState.retrain
      }
    }
    is(RDIState.retrain) {
      when(linkErrorPhySts) {
        linkStateReg := RDIState.linkError
      }.elsewhen(disabledEntry) {
        linkStateReg := RDIState.disabled
      }.elsewhen(linkResetEntry) {
        linkStateReg := RDIState.linkReset
      }
    }
    is(RDIState.linkError) {
      when((io.fdi_lp_state_req === RDIStateReq.active ||
            io.rdi_pl_state_sts === RDIState.linkError) && rxDeactive) {
        linkStateReg := RDIState.reset
      }
    }
    is(RDIState.disabled) {
      when(linkErrorPhySts) {
        linkStateReg := RDIState.linkError
      }.elsewhen(io.fdi_lp_state_req === RDIStateReq.active ||
                 io.rdi_pl_state_sts === RDIState.reset) {
        linkStateReg := RDIState.reset
      }
    }
    is(RDIState.linkReset) {
      when(linkErrorPhySts) {
        linkStateReg := RDIState.linkError
      }.elsewhen(disabledEntry && rxDeactive) {
        linkStateReg := RDIState.disabled
      }.elsewhen(io.fdi_lp_state_req === RDIStateReq.active ||
                 io.rdi_pl_state_sts === RDIState.reset) {
        linkStateReg := RDIState.reset
      }
    }
  }
}
