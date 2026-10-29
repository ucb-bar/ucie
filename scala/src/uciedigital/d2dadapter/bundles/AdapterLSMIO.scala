/*
  The IO of the Adapter LSM. It carries the FDI and RDI state requests and
  status, the sideband message handshake and the register block outputs.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import edu.berkeley.cs.uciedigital.rdi._
import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._

// The Adapter LSM fields of Header Log 2 (sec 9.5.3.8).
class AdapterTimeoutFields extends Bundle {
  val enc = UInt(4.W)
  val lsmResponse = UInt(3.W)
}

class AdapterLSMIO(
    val fdiParams: FdiParams,
    val rdiParams: RdiParams,
    val sbParams: SidebandParams
) extends Bundle {
  val fdi_lp_state_req = Input(FDIStateReq())
  val fdi_lp_linkerror = Input(Bool())
  val internal_linkerror = Input(Bool())
  val fdi_lp_rx_active_sts = Input(Bool())
  val fdi_pl_state_sts = Output(FDIState())
  val fdi_pl_rx_active_req = Output(Bool())
  val fdi_pl_inband_pres = Output(Bool())

  val rdi_lp_linkerror = Output(Bool())
  val rdi_lp_state_req = Output(RDIStateReq())
  val rdi_pl_state_sts = Input(RDIState())
  val rdi_pl_stallreq = Input(Bool())
  val sw_retrain = Input(Bool())
  val rdi_pl_inband_pres = Input(Bool())

  val sb_snd = Valid(UInt(sbParams.sbNodeMsgWidth.W))
  val sb_rcv = Flipped(Valid(UInt(sbParams.sbNodeMsgWidth.W)))
  val sb_rdy = Input(Bool())

  val linkmgmt_stallreq = Output(Bool())
  val linkmgmt_stalldone = Input(Bool())

  val link_state = Output(FDIState())
  val param_exch_success = Output(Bool())
  val adv_cap_payload = Input(UInt(64.W))
  val adv_cap_sent = Valid(UInt(64.W))
  val timeout = Valid(new AdapterTimeoutFields)
  val invalid_param_exchange = Output(Bool())
}
