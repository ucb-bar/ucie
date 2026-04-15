package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._

class LinkManagementControllerIO (val fdiParams: FdiParams,
                                  val rdiParams: RdiParams) extends Bundle {
    //val fdi = Flipped(new Fdi(fdiParams))
    val fdi_lp_state_req = Input(PhyStateReq())
    val fdi_lp_linkerror = Input(Bool())
    val fdi_lp_rx_active_sts = Input(Bool())
    val fdi_pl_state_sts = Output(PhyState())
    val fdi_pl_rx_active_req = Output(Bool())
    val fdi_pl_inband_pres = Output(Bool())
    //val rdi = new Rdi(rdiParams)
    val rdi_lp_linkerror = Output(Bool())
    val rdi_lp_state_req = Output(PhyStateReq())
    val rdi_pl_state_sts = Input(PhyState())
    val rdi_pl_inband_pres = Input(Bool())
    // TODO: We need to define a common packet format for SB internal messages
    val sb_snd = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
    val sb_rcv = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
    val sb_rdy = Input(Bool())
    // stall handlers for the mainband
    val linkmgmt_stallreq = Output(Bool())
    val linkmgmt_stalldone = Input(Bool())
    val cycles_1us = Input(UInt(32.W))// number of cycles to 1 us
    // Dynamic testing with parity
    val parity_tx_sw_en = Input(Bool())// if parity tx is enabled by software
    val parity_rx_sw_en = Input(Bool())// if parity rx is enabled by software
    val parity_rx_enable = Output(Bool())// tell parity if the parity should be used for receiving
    val parity_tx_enable = Output(Bool())// tell parity if the parity should be used for sending
}

/**
  * LinkManagementController for top level FDI/RDI state machine implementation, 
  * decoding the sideband messages and arbitration of triggers for the D2D adapter
  * state machine
  * @param fdiParams FdiParams
  * @param rdiParams RdiParams
  * @param sbParams SidebandParams
  * TODO: parity module and parity negotiation, PM states
  */
class LinkManagementController (val fdiParams: FdiParams, val rdiParams: RdiParams, 
                                val sbParams: SidebandParams) extends Module {
    val io = IO(new LinkManagementControllerIO(fdiParams, rdiParams))

    // Submodule instantiations
    // Disabled submodule
    val disabled_submodule = Module(new LinkDisabledSubmodule())
    // LinkReset submodule
    val linkreset_submodule = Module(new LinkResetSubmodule())
    // LinkInit submodule
    val linkinit_submodule = Module(new LinkInitSubmodule())
    // Parity negotiation submodule
    val parity_negotiation_submodule = Module(new ParityNegotiationSubmodule())

    // Output registers
    // LinkError signal propagated to the PHY
    val rdi_lp_linkerror_reg = RegInit(false.B)
    val rdi_lp_state_req_reg = RegInit(PhyStateReq.nop)

    val fdi_pl_rxactive_req_reg = RegInit(false.B)
    val fdi_pl_inband_pres_reg = RegInit(false.B)

    val linkmgmt_stallreq_reg = RegInit(false.B)

    // Internal registers
    val fdi_lp_state_req_prev_reg = RegNext(io.fdi_lp_state_req)

    // FDI/RDI state register
    val link_state_reg = RegInit(PhyState.reset)

    // Top level IO signal assignments
    // RDI
    io.rdi_lp_linkerror := rdi_lp_linkerror_reg
    io.rdi_lp_state_req := rdi_lp_state_req_reg
    // FDI
    io.fdi_pl_state_sts := link_state_reg
    io.fdi_pl_rx_active_req := fdi_pl_rxactive_req_reg
    io.fdi_pl_inband_pres := fdi_pl_inband_pres_reg

    io.linkmgmt_stallreq := linkmgmt_stallreq_reg

    // LinkError propagation from Protocol layer to PHY
    rdi_lp_linkerror_reg := io.fdi_lp_linkerror

    // Submodule IO signal assignments
    // Disabled submodule
    disabled_submodule.io.fdi_lp_state_req := io.fdi_lp_state_req
    disabled_submodule.io.fdi_lp_state_req_prev := fdi_lp_state_req_prev_reg
    disabled_submodule.io.link_state := link_state_reg
    val disabled_entry = disabled_submodule.io.disabled_entry
    // Intermediate sideband messgaes which gets assigned to top IO when required
    val disabled_sb_snd = disabled_submodule.io.disabled_sb_snd
    val disabled_sb_rdy = Wire(Bool())
    disabled_submodule.io.disabled_sb_rcv := io.sb_rcv
    disabled_submodule.io.disabled_sb_rdy := disabled_sb_rdy

    // LinkReset submodule
    linkreset_submodule.io.fdi_lp_state_req := io.fdi_lp_state_req
    linkreset_submodule.io.fdi_lp_state_req_prev := fdi_lp_state_req_prev_reg
    linkreset_submodule.io.link_state := link_state_reg
    val linkreset_entry = linkreset_submodule.io.linkreset_entry
    // Intermediate sideband messgaes which gets assigned to top IO when required
    val linkreset_sb_snd = linkreset_submodule.io.linkreset_sb_snd
    val linkreset_sb_rdy = Wire(Bool())
    linkreset_submodule.io.linkreset_sb_rcv := io.sb_rcv
    linkreset_submodule.io.linkreset_sb_rdy := linkreset_sb_rdy

    // LinkInit submodule
    linkinit_submodule.io.fdi_lp_state_req := io.fdi_lp_state_req
    linkinit_submodule.io.fdi_lp_state_req_prev := fdi_lp_state_req_prev_reg
    linkinit_submodule.io.fdi_lp_rxactive_sts := io.fdi_lp_rx_active_sts
    linkinit_submodule.io.rdi_pl_state_sts := io.rdi_pl_state_sts
    linkinit_submodule.io.rdi_pl_inband_pres := io.rdi_pl_inband_pres
    linkinit_submodule.io.link_state := link_state_reg
    val linkinit_fdi_pl_rxactive_req = linkinit_submodule.io.linkinit_fdi_pl_rxactive_req
    val linkinit_fdi_pl_inband_pres = linkinit_submodule.io.linkinit_fdi_pl_inband_pres
    val linkinit_rdi_lp_state_req = linkinit_submodule.io.linkinit_rdi_lp_state_req
    val active_entry = linkinit_submodule.io.active_entry
    // Intermediate sideband messgaes which gets assigned to top IO when required
    val linkinit_sb_snd = linkinit_submodule.io.linkinit_sb_snd
    val linkinit_sb_rdy = Wire(Bool())
    linkinit_submodule.io.linkinit_sb_rcv := io.sb_rcv
    linkinit_submodule.io.linkinit_sb_rdy := linkinit_sb_rdy

    // Parity negotiation submodule
    io.parity_tx_enable := parity_negotiation_submodule.io.parity_tx_enable
    io.parity_rx_enable := parity_negotiation_submodule.io.parity_rx_enable

    parity_negotiation_submodule.io.start_negotiation := link_state_reg === PhyState.retrain
    val negotiation_complete = parity_negotiation_submodule.io.negotiation_complete 
    parity_negotiation_submodule.io.parity_sb_rcv := io.sb_rcv
    val parity_negotiation_sb_snd = parity_negotiation_submodule.io.parity_sb_snd
    val parity_negotiation_sb_rdy = Wire(Bool())
    parity_negotiation_submodule.io.parity_sb_rdy := parity_negotiation_sb_rdy 
    parity_negotiation_submodule.io.parity_rx_sw_en := io.parity_rx_sw_en
    parity_negotiation_submodule.io.parity_tx_sw_en := io.parity_tx_sw_en
    parity_negotiation_submodule.io.cycles_1us := io.cycles_1us

    // FDI/RDI common state change triggers
    // LinkError logic
    // PHY informs the adapter over RDI that it is in linkError state
    val linkerror_phy_sts = io.rdi_pl_state_sts === PhyState.linkError
    // Protocol initiates linkError through lp_linkerror assertion
    //val linkerror_fdi_req = io.fdi_lp_linkerror
    // Placeholder for any other internal request logic which can trigger linkError

    val stallhandler_handshake_done = linkmgmt_stallreq_reg & io.linkmgmt_stalldone

    // rx_deactive and rx_active signals for checking if rx on mainband is disabled
    val rx_deactive = ~(io.fdi_lp_rx_active_sts) & ~(io.fdi_pl_rx_active_req)
    val rx_active = io.fdi_lp_rx_active_sts & io.fdi_pl_rx_active_req

    // PHY informs the adapter over RDI that it should go into retrain
    val retrain_phy_sts = (io.rdi_pl_state_sts === PhyState.retrain)

    // Moved this condition to the disabled module
    // Reset to disabled requires atleast one clock cycle of lp_state_req = Reset(NOP)
    //val disabled_from_reset_fdi_req = ((fdi_lp_state_req_prev_reg === PhyStateReq.reset &&
    //                                    io.fdi_lp_state_req === PhyStateReq.reset) &&
    //                                    io.rdi_pl_state_sts === PhyState.reset)

    // stall arbitration
    when(link_state_reg === PhyState.active) {
        linkmgmt_stallreq_reg := linkreset_entry || disabled_entry || retrain_phy_sts
    }.otherwise {
        linkmgmt_stallreq_reg := false.B
    }

    // rxActive arbitration
    when(link_state_reg === PhyState.active){
        when(linkreset_entry || disabled_entry || retrain_phy_sts || linkerror_phy_sts){
            fdi_pl_rxactive_req_reg := false.B
        }.otherwise{
            fdi_pl_rxactive_req_reg := true.B
        }
    }.otherwise{
        when(linkreset_entry || disabled_entry || linkerror_phy_sts){
            fdi_pl_rxactive_req_reg := false.B
        }.otherwise{
            fdi_pl_rxactive_req_reg := linkinit_fdi_pl_rxactive_req
        }
    }

    // inband presence arbitration
    when(link_state_reg === PhyState.reset){
        when(linkerror_phy_sts){
            fdi_pl_inband_pres_reg := false.B
        }.otherwise{
            fdi_pl_inband_pres_reg := linkinit_fdi_pl_inband_pres
        }
    }.elsewhen(link_state_reg === PhyState.linkError || 
                link_state_reg === PhyState.disabled ||
                link_state_reg === PhyState.linkReset){
        fdi_pl_inband_pres_reg := false.B
    }.otherwise{
        when(linkerror_phy_sts){
            fdi_pl_inband_pres_reg := false.B
        }.otherwise{
            fdi_pl_inband_pres_reg := true.B
        }
    }

    // Sideband message generation logic
    linkreset_sb_rdy := false.B
    disabled_sb_rdy := false.B
    linkinit_sb_rdy := false.B
    parity_negotiation_sb_rdy := false.B

    when(link_state_reg === PhyState.reset){
        when(disabled_sb_snd =/= SideBandMessage.NOP){
            io.sb_snd := disabled_sb_snd
            disabled_sb_rdy := io.sb_rdy
        }.elsewhen(linkreset_sb_snd =/= SideBandMessage.NOP){
            io.sb_snd := linkreset_sb_snd
            linkreset_sb_rdy := io.sb_rdy
        }.elsewhen(linkinit_sb_snd =/= SideBandMessage.NOP){
            io.sb_snd := linkinit_sb_snd
            linkinit_sb_rdy := io.sb_rdy
        }.otherwise{
            io.sb_snd := SideBandMessage.NOP
        }
    }.elsewhen(link_state_reg === PhyState.active){
        when(disabled_sb_snd =/= SideBandMessage.NOP){
            io.sb_snd := disabled_sb_snd
            disabled_sb_rdy := io.sb_rdy
        }.elsewhen(linkreset_sb_snd =/= SideBandMessage.NOP){
            io.sb_snd := linkreset_sb_snd
            linkreset_sb_rdy := io.sb_rdy
        }.otherwise{
            io.sb_snd := SideBandMessage.NOP
        }
    }.elsewhen(link_state_reg === PhyState.retrain){
        when(disabled_sb_snd =/= SideBandMessage.NOP){
            io.sb_snd := disabled_sb_snd
            disabled_sb_rdy := io.sb_rdy
        }.elsewhen(linkreset_sb_snd =/= SideBandMessage.NOP){
            io.sb_snd := linkreset_sb_snd
            linkreset_sb_rdy := io.sb_rdy
        }.elsewhen(parity_negotiation_sb_snd =/= SideBandMessage.NOP){
            io.sb_snd := parity_negotiation_sb_snd
            parity_negotiation_sb_rdy := io.sb_rdy
        }.otherwise{
            io.sb_snd := SideBandMessage.NOP
        }
    }.elsewhen(link_state_reg === PhyState.linkReset){
        when(disabled_sb_snd =/= SideBandMessage.NOP){
            io.sb_snd := disabled_sb_snd
            disabled_sb_rdy := io.sb_rdy
        }.otherwise{
            io.sb_snd := SideBandMessage.NOP
        }
    }.elsewhen(link_state_reg === PhyState.disabled || link_state_reg === PhyState.linkError){
        io.sb_snd := SideBandMessage.NOP
    }.otherwise{
        io.sb_snd := SideBandMessage.NOP
    }

    // RDI lp state request generation logic
    when(link_state_reg === PhyState.reset) {
        rdi_lp_state_req_reg := linkinit_rdi_lp_state_req
    }.elsewhen(link_state_reg === PhyState.active) {
        when(retrain_phy_sts) {
            rdi_lp_state_req_reg := PhyStateReq.retrain
        }
    }.elsewhen(link_state_reg === PhyState.retrain) {
        rdi_lp_state_req_reg := PhyStateReq.nop
    }.elsewhen(link_state_reg === PhyState.linkError) {
        // Section 8.3.4.2 for link error exit
        when(io.fdi_lp_state_req === PhyStateReq.active &&
                (io.rdi_pl_state_sts === PhyState.linkError)) {
            rdi_lp_state_req_reg := PhyStateReq.active
        }.otherwise {
            rdi_lp_state_req_reg := PhyStateReq.nop
        }
    }.elsewhen(link_state_reg === PhyState.disabled) {
        when(io.fdi_lp_state_req === PhyStateReq.active) {
            rdi_lp_state_req_reg := PhyStateReq.active
        }.otherwise{
            rdi_lp_state_req_reg := PhyStateReq.disabled
        }
    }.elsewhen(link_state_reg === PhyState.linkReset) {
        when(io.fdi_lp_state_req === PhyStateReq.active) {
            rdi_lp_state_req_reg := PhyStateReq.active
        }.otherwise{
            rdi_lp_state_req_reg := PhyStateReq.linkReset
        }
    }

    // FDI/RDI state machine. We use the same SM for optimized code as the spec
    // seems to trigger the state machines in tandem with no intermediate signalling 
    switch(link_state_reg) {
        // RESET
        is(PhyState.reset){
            when(linkerror_phy_sts) {
                // TODO: any internal condition to trigger linkError? + SB msgs
                link_state_reg := PhyState.linkError
            }.elsewhen(disabled_entry && rx_deactive) {
                link_state_reg := PhyState.disabled
            }.elsewhen(linkreset_entry && rx_deactive) {
                link_state_reg := PhyState.linkReset
            }.elsewhen(active_entry) {
                link_state_reg := PhyState.active
            }.otherwise {
                link_state_reg := link_state_reg
            }
        }
        // ACTIVE
        is(PhyState.active) {
            when(linkerror_phy_sts) {
                link_state_reg := PhyState.linkError
            }.elsewhen(disabled_entry && rx_deactive && stallhandler_handshake_done) { // TODO: handle the stallreq/ack mechanism
                link_state_reg := PhyState.disabled
            }.elsewhen(linkreset_entry && rx_deactive && stallhandler_handshake_done) { // TODO: handle the stallreq/ack mechanism
                link_state_reg := PhyState.linkReset
            }.elsewhen(retrain_phy_sts && rx_deactive && stallhandler_handshake_done) { // TODO: handle the stallreq/ack mechanism
                link_state_reg := PhyState.retrain
            }.otherwise {
                link_state_reg := link_state_reg
            }
        }
        // RETRAIN
        // TODO: Retrain to active without L1 and L2 happens through lp_state_req
        // should not require the linkinit to happen again? 
        is(PhyState.retrain) {
            when(linkerror_phy_sts) {
                link_state_reg := PhyState.linkError
            }.elsewhen(disabled_entry) {
                link_state_reg := PhyState.disabled
            }.elsewhen(linkreset_entry) {
                link_state_reg := PhyState.linkReset
            }.otherwise {
                link_state_reg := link_state_reg
            }
        }
        // LINKERROR
        is(PhyState.linkError) {
            // TODO: Check this logic, also needs state change on internal reset request
            // 
            when((io.fdi_lp_state_req === PhyStateReq.active ||
                  io.rdi_pl_state_sts === PhyState.linkError) && rx_deactive) {
                    link_state_reg := PhyState.reset
                }.otherwise {
                    link_state_reg := link_state_reg
                }
        }
        // DISABLED
        is(PhyState.disabled) {
            when(linkerror_phy_sts) {
                link_state_reg := PhyState.linkError
            }.elsewhen(io.fdi_lp_state_req === PhyStateReq.active || 
                        io.rdi_pl_state_sts === PhyState.reset) {
                link_state_reg := PhyState.reset
            }.otherwise {
                link_state_reg := link_state_reg
            }
        }
        // LINKRESET
        is(PhyState.linkReset) {
            when(linkerror_phy_sts) {
                link_state_reg := PhyState.linkError
            }.elsewhen(disabled_entry && rx_deactive) {
                link_state_reg := PhyState.disabled
            }.elsewhen(io.fdi_lp_state_req === PhyStateReq.active || 
                        io.rdi_pl_state_sts === PhyState.reset) {
                link_state_reg := PhyState.reset
            }.otherwise {
                link_state_reg := link_state_reg
            }
        }
    }
}