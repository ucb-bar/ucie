package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._

class LinkInitSubmoduleIO() extends Bundle {
    val fdi_lp_state_req = Input(PhyStateReq())
    val fdi_lp_state_req_prev = Input(PhyStateReq())
    //val fdi_lp_inband_pres = Input(Bool())

    val fdi_lp_rxactive_sts = Input(Bool())
    
    val linkinit_fdi_pl_inband_pres = Output(Bool())
    val linkinit_fdi_pl_rxactive_req = Output(Bool())
    val linkinit_fdi_pl_state_sts = Output(PhyState())

    val rdi_pl_state_sts = Input(PhyState())
    val rdi_pl_inband_pres = Input(Bool())
    val linkinit_rdi_lp_state_req = Output(PhyStateReq())

    val link_state = Input(PhyState())
    val active_entry = Output(Bool())
    val linkinit_sb_snd = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
    val linkinit_sb_rcv = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
    val linkinit_sb_rdy = Input(Bool())
}

/**
  * LinkInitSubmodule handles the transition from Reset to Active for the
  * FDI/RDI state machines. Whenever the links moves from the reset state
  * they need to go through the link initialization i.e., the RDI bringup,
  * the parameter negotiations and the FDI bringup. This is handled inside
  * this linkInit submodule.
  * 
  */
class LinkInitSubmodule() extends Module {
    val io = IO(new LinkInitSubmoduleIO())

    // State register for link initialization 
    val linkinit_state_reg = RegInit(LinkInitState.INIT_START)

    // Parameter exchange on sideband message arbitration flags
    val param_exch_sbmsg_rcv_flag = RegInit(false.B)
    val param_exch_sbmsg_snt_flag = RegInit(false.B)

    // Active state sb message arbitration flags
    val active_sbmsg_req_rcv_flag = RegInit(false.B)
    val active_sbmsg_rsp_rcv_flag = RegInit(false.B)
    val active_sbmsg_ext_rsp_reg = RegInit(false.B) // receive and respond to sb active request
    val active_sbmsg_ext_req_reg = RegInit(false.B) // send and wait for sb active response

    val transition_to_active_reg = RegInit(false.B)

    // Defaults IO
    io.linkinit_rdi_lp_state_req := PhyStateReq.nop
    io.linkinit_fdi_pl_inband_pres := false.B
    io.linkinit_fdi_pl_state_sts := PhyState.reset
    io.linkinit_sb_snd := SideBandMessage.NOP
    io.active_entry := false.B
    io.linkinit_fdi_pl_rxactive_req := false.B

    when(io.link_state === PhyState.reset) {
      // Defaults
      io.active_entry := false.B
      io.linkinit_rdi_lp_state_req := PhyStateReq.nop
      io.linkinit_fdi_pl_rxactive_req := false.B
      io.linkinit_sb_snd := SideBandMessage.NOP
      param_exch_sbmsg_rcv_flag := false.B
      param_exch_sbmsg_snt_flag := false.B
      active_sbmsg_req_rcv_flag := false.B
      active_sbmsg_rsp_rcv_flag := false.B
      active_sbmsg_ext_rsp_reg := false.B
      active_sbmsg_ext_req_reg := false.B
      transition_to_active_reg := false.B

      switch(linkinit_state_reg) {
        // INIT START
        is(LinkInitState.INIT_START) {
            io.active_entry := false.B
            io.linkinit_rdi_lp_state_req := PhyStateReq.nop
            io.linkinit_fdi_pl_rxactive_req := false.B
            io.linkinit_sb_snd := SideBandMessage.NOP
            when(io.rdi_pl_inband_pres) {
              linkinit_state_reg := LinkInitState.RDI_BRINGUP
            }.otherwise {
              linkinit_state_reg := linkinit_state_reg
            }
        }
        // RDI BRINGUP
        is(LinkInitState.RDI_BRINGUP) {
            io.linkinit_rdi_lp_state_req := PhyStateReq.active
            when(io.rdi_pl_state_sts === PhyState.active) {
              linkinit_state_reg := LinkInitState.PARAM_EXCH
            }.otherwise {
              linkinit_state_reg := linkinit_state_reg
            }
        }
        // PARAMETER EXCHANGE
        is(LinkInitState.PARAM_EXCH) {
            io.linkinit_rdi_lp_state_req := PhyStateReq.active
            // Send the AdvCap sbmsg to the partner link adapter

            when(!param_exch_sbmsg_snt_flag) {
              io.linkinit_sb_snd := SideBandMessage.ADV_CAP
            }.otherwise {
              io.linkinit_sb_snd := SideBandMessage.NOP
            }

            // Check whether there is inflight AdvCap sbmsg from partner link adapter
            when(io.linkinit_sb_rcv === SideBandMessage.ADV_CAP){
                param_exch_sbmsg_rcv_flag := true.B
            }.otherwise {
                param_exch_sbmsg_rcv_flag := param_exch_sbmsg_rcv_flag
            }

            when(io.linkinit_sb_rdy && io.linkinit_sb_snd === SideBandMessage.ADV_CAP) {
                param_exch_sbmsg_snt_flag := true.B
            }.otherwise {
                param_exch_sbmsg_snt_flag := param_exch_sbmsg_snt_flag
            }

            // Check if AdvCap was sent and received and vice versa
            when(param_exch_sbmsg_snt_flag && param_exch_sbmsg_rcv_flag) {
                linkinit_state_reg := LinkInitState.FDI_BRINGUP   
            }.otherwise {
                linkinit_state_reg := linkinit_state_reg
            }
        }
        // FDI BRINGUP
        is(LinkInitState.FDI_BRINGUP) {
            io.linkinit_fdi_pl_inband_pres := true.B
            io.linkinit_rdi_lp_state_req := PhyStateReq.active

            when(active_sbmsg_req_rcv_flag) {
              io.linkinit_fdi_pl_rxactive_req := true.B
            }.otherwise {
              io.linkinit_fdi_pl_rxactive_req := false.B
            }

            when(io.fdi_lp_rxactive_sts && io.linkinit_fdi_pl_rxactive_req && !active_sbmsg_ext_rsp_reg) {
                io.linkinit_sb_snd := SideBandMessage.RSP_ACTIVE
            }.elsewhen(transition_to_active_reg && !active_sbmsg_ext_req_reg) {
                io.linkinit_sb_snd := SideBandMessage.REQ_ACTIVE
            }.otherwise {
              io.linkinit_sb_snd := SideBandMessage.NOP
            }

            when(io.linkinit_sb_rcv === SideBandMessage.RSP_ACTIVE) {
              active_sbmsg_rsp_rcv_flag := true.B
            }.otherwise {
              active_sbmsg_rsp_rcv_flag := active_sbmsg_rsp_rcv_flag
            }

            when(io.linkinit_sb_rcv === SideBandMessage.REQ_ACTIVE) {
              active_sbmsg_req_rcv_flag := true.B
            }.otherwise {
              active_sbmsg_req_rcv_flag := active_sbmsg_req_rcv_flag
            }

            when(io.linkinit_sb_snd === SideBandMessage.RSP_ACTIVE && io.linkinit_sb_rdy) {
              active_sbmsg_ext_rsp_reg := true.B
            }.otherwise {
              active_sbmsg_ext_rsp_reg := active_sbmsg_ext_rsp_reg
            }

            when(io.linkinit_sb_snd === SideBandMessage.REQ_ACTIVE && io.linkinit_sb_rdy) {
              active_sbmsg_ext_req_reg := true.B
            }.otherwise {
              active_sbmsg_ext_req_reg := active_sbmsg_ext_req_reg
            }

            when(io.fdi_lp_state_req === PhyStateReq.active &&
                 io.fdi_lp_state_req_prev === PhyStateReq.nop) {
              transition_to_active_reg := true.B
            }.otherwise{
              transition_to_active_reg := transition_to_active_reg
            }

            when(active_sbmsg_ext_rsp_reg && active_sbmsg_rsp_rcv_flag) {
              linkinit_state_reg := LinkInitState.INIT_DONE
            }.otherwise{
              linkinit_state_reg := linkinit_state_reg
            }
        }
        // INIT DONE
        is(LinkInitState.INIT_DONE) {
            io.active_entry := true.B
            io.linkinit_fdi_pl_state_sts := PhyState.active
            io.linkinit_fdi_pl_rxactive_req := true.B
            io.linkinit_fdi_pl_inband_pres := true.B
            io.linkinit_rdi_lp_state_req := PhyStateReq.active
            io.linkinit_sb_snd := SideBandMessage.NOP
            linkinit_state_reg := LinkInitState.INIT_DONE
        }
      }
    }.elsewhen(io.link_state === PhyState.active){
      io.active_entry := true.B
      io.linkinit_fdi_pl_state_sts := PhyState.active
      io.linkinit_fdi_pl_rxactive_req := true.B
      io.linkinit_fdi_pl_inband_pres := true.B
      io.linkinit_rdi_lp_state_req := PhyStateReq.active
      io.linkinit_sb_snd := SideBandMessage.NOP
      linkinit_state_reg := LinkInitState.INIT_DONE
    }.otherwise{
      linkinit_state_reg := LinkInitState.INIT_START
      io.active_entry := false.B
      io.linkinit_rdi_lp_state_req := PhyStateReq.nop
      io.linkinit_fdi_pl_rxactive_req := false.B
      io.linkinit_fdi_pl_inband_pres := false.B
      io.linkinit_sb_snd := SideBandMessage.NOP
      param_exch_sbmsg_rcv_flag := false.B
      param_exch_sbmsg_snt_flag := false.B
    }
}