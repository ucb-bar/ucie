package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
//import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._

class LinkResetSubmoduleIO() extends Bundle {
  val fdi_lp_state_req = Input(PhyStateReq())
  val fdi_lp_state_req_prev = Input(PhyStateReq())
  val link_state = Input(PhyState())
  val linkreset_entry = Output(Bool())
  val linkreset_sb_snd = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
  val linkreset_sb_rcv = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH))
  val linkreset_sb_rdy = Input(Bool())
}

/** LinkResetSubmodule handles the transition of FDI/RDI state machine from
  * Reset, Active, and Retrain to LinkReset state. The transition is triggered
  * by fdi_lp_state_req or though sideband messages coming from partner link.
  * 
  */
// This is a modified copy of LinkResetSubmodule.scala file
class LinkResetSubmodule() extends Module {
  val io = IO(new LinkResetSubmoduleIO())

  val linkreset_fdi_req_reg = RegInit(false.B)
  val linkreset_sbmsg_req_rcv_flag = RegInit(false.B)
  val linkreset_sbmsg_rsp_rcv_flag = RegInit(false.B)
  val linkreset_sbmsg_ext_rsp_reg = RegInit(false.B) // receive and respond to sb linkreset request
  val linkreset_sbmsg_ext_req_reg = RegInit(false.B) // send and wait for sb linkreset response

  when(
      io.link_state === PhyState.reset ||
      io.link_state === PhyState.active ||
      io.link_state === PhyState.retrain
  ) {

    io.linkreset_entry := linkreset_sbmsg_ext_rsp_reg || linkreset_sbmsg_rsp_rcv_flag

    // State change request by fdi
    when(
      io.link_state === PhyState.reset &&
        io.fdi_lp_state_req === PhyStateReq.linkReset &&
        io.fdi_lp_state_req_prev === PhyStateReq.nop
    ) {
      linkreset_fdi_req_reg := true.B
    }.elsewhen(
      io.fdi_lp_state_req === PhyStateReq.linkReset &&
        io.link_state =/= PhyState.reset,
    ) {
      linkreset_fdi_req_reg := true.B
    }.otherwise {
      linkreset_fdi_req_reg := linkreset_fdi_req_reg
    }

    when(io.linkreset_sb_snd === SideBandMessage.REQ_LINKRESET && io.linkreset_sb_rdy){
      linkreset_sbmsg_ext_req_reg := true.B
    }.otherwise{
      linkreset_sbmsg_ext_req_reg := linkreset_sbmsg_ext_req_reg
    }

    when(io.linkreset_sb_snd === SideBandMessage.RSP_LINKRESET && io.linkreset_sb_rdy){
      linkreset_sbmsg_ext_rsp_reg := true.B
    }.otherwise{
      linkreset_sbmsg_ext_rsp_reg := linkreset_sbmsg_ext_rsp_reg
    }

    // Check whether there is inflight linkreset request sbmsg from partner link
    when(io.linkreset_sb_rcv === SideBandMessage.REQ_LINKRESET) {
      linkreset_sbmsg_req_rcv_flag := true.B
    }.otherwise {
      linkreset_sbmsg_req_rcv_flag := linkreset_sbmsg_req_rcv_flag
    }

    /* Check whether there is inflight linkreset response sbmsg from partner
     * link */
    when(io.linkreset_sb_rcv === SideBandMessage.RSP_LINKRESET) {
      linkreset_sbmsg_rsp_rcv_flag := true.B
    }.otherwise {
      linkreset_sbmsg_rsp_rcv_flag := linkreset_sbmsg_rsp_rcv_flag
    }

    // TODO: Check if this logic works on all corner cases
    // lp_state_req triggers sideband message
    when(linkreset_fdi_req_reg && !linkreset_sbmsg_req_rcv_flag &&
         !linkreset_sbmsg_ext_req_reg) {
      io.linkreset_sb_snd := SideBandMessage.REQ_LINKRESET
    }.elsewhen(linkreset_sbmsg_req_rcv_flag && !linkreset_sbmsg_ext_rsp_reg) {
      io.linkreset_sb_snd := SideBandMessage.RSP_LINKRESET
    }.otherwise {
      io.linkreset_sb_snd := SideBandMessage.NOP
    }
  }.otherwise {
    linkreset_fdi_req_reg := false.B
    linkreset_sbmsg_req_rcv_flag := false.B
    linkreset_sbmsg_rsp_rcv_flag := false.B
    linkreset_sbmsg_ext_req_reg := false.B
    linkreset_sbmsg_ext_rsp_reg := false.B
    io.linkreset_entry := false.B
    io.linkreset_sb_snd := SideBandMessage.NOP
  }
}