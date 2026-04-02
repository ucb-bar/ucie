package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
//import chisel3.util._

import edu.berkeley.cs.uciedigital.interfaces._

class LinkDisabledSubmoduleIO() extends Bundle {
  val fdi_lp_state_req = Input(PhyStateReq())
  val fdi_lp_state_req_prev = Input(PhyStateReq())
  val link_state = Input(PhyState())
  val disabled_entry = Output(Bool())
  val disabled_sb_snd = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
  val disabled_sb_rcv = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
  val disabled_sb_rdy = Input(Bool())
}

/** LinkDisabledSubmodule handles the transition of FDI/RDI state machine from
  * Reset, Active, Retrain, and LinkReset to Disabled state. The transition is
  * triggered by fdi_lp_state_req or though sideband messages coming from
  * partner link.
  * 
  */
class LinkDisabledSubmodule() extends Module {
  val io = IO(new LinkDisabledSubmoduleIO())

  val disabled_fdi_req_reg = RegInit(false.B)
  val disabled_sbmsg_req_rcv_reg = RegInit(false.B)
  val disabled_sbmsg_rsp_rcv_reg = RegInit(false.B)
  val disabled_sbmsg_ext_rsp_reg = RegInit(false.B) // receive and respond to sb disabled request
  val disabled_sbmsg_ext_req_reg = RegInit(false.B) // send and wait for sb disabled response

  when(
      io.link_state === PhyState.reset ||
      io.link_state === PhyState.active ||
      io.link_state === PhyState.retrain ||
      io.link_state === PhyState.linkReset
  ) {
    io.disabled_entry := disabled_sbmsg_ext_rsp_reg || disabled_sbmsg_rsp_rcv_reg

    // State change request by fdi
    when(
      io.link_state === PhyState.reset &&
        io.fdi_lp_state_req === PhyStateReq.disabled &&
        io.fdi_lp_state_req_prev === PhyStateReq.nop
    ) {
      disabled_fdi_req_reg := true.B
    }.elsewhen(
      io.fdi_lp_state_req === PhyStateReq.disabled &&
        io.link_state =/= PhyState.reset
    ) {
      disabled_fdi_req_reg := true.B
    }.otherwise {
      disabled_fdi_req_reg := disabled_fdi_req_reg
    }

    when(io.disabled_sb_snd === SideBandMessage.REQ_DISABLED && io.disabled_sb_rdy){
      disabled_sbmsg_ext_req_reg := true.B
    }.otherwise{
      disabled_sbmsg_ext_req_reg := disabled_sbmsg_ext_req_reg
    }

    when(io.disabled_sb_snd === SideBandMessage.RSP_DISABLED && io.disabled_sb_rdy){
      disabled_sbmsg_ext_rsp_reg := true.B
    }.otherwise{
      disabled_sbmsg_ext_rsp_reg := disabled_sbmsg_ext_rsp_reg
    }
    // Check whether there is inflight disabled request sbmsg from partner link
    when(io.disabled_sb_rcv === SideBandMessage.REQ_DISABLED) {
      disabled_sbmsg_req_rcv_reg := true.B
    }.otherwise {
      disabled_sbmsg_req_rcv_reg := disabled_sbmsg_req_rcv_reg
    }

    /* Check whether there is inflight disaabled response sbmsg from partner
     * link */
    when(io.disabled_sb_rcv === SideBandMessage.RSP_DISABLED) {
      disabled_sbmsg_rsp_rcv_reg := true.B
    }.otherwise {
      disabled_sbmsg_rsp_rcv_reg := disabled_sbmsg_rsp_rcv_reg
    }

    // TODO: Check if this logic works on all corner cases
    // lp_state_req triggers sideband message
    // TODO: find a way to enable valid without probing ready
    when(disabled_fdi_req_reg && !disabled_sbmsg_req_rcv_reg 
         && !disabled_sbmsg_ext_req_reg) {
      io.disabled_sb_snd := SideBandMessage.REQ_DISABLED
    }.elsewhen(disabled_sbmsg_req_rcv_reg && !disabled_sbmsg_ext_rsp_reg) {
      io.disabled_sb_snd := SideBandMessage.RSP_DISABLED
    }.otherwise {
      io.disabled_sb_snd := SideBandMessage.NOP
    }
  }.otherwise {
    disabled_fdi_req_reg := false.B
    disabled_sbmsg_req_rcv_reg := false.B
    disabled_sbmsg_rsp_rcv_reg := false.B
    disabled_sbmsg_ext_req_reg := false.B
    disabled_sbmsg_ext_rsp_reg := false.B
    io.disabled_entry := false.B
    io.disabled_sb_snd := SideBandMessage.NOP
  }
}