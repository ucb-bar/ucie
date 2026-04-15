package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
//import chisel3.util._
//import chisel3.experimental._

class ParityNegotiationSubmoduleIO() extends Bundle{
    val start_negotiation = Input(Bool())
    val negotiation_complete = Output(Bool())

    val parity_sb_rcv = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)) // sideband requested signals
    val parity_sb_snd = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)) // tell sideband module to send request of state change
    val parity_sb_rdy = Input(Bool())// sideband can consume the op in sideband_snt. 

    val parity_tx_sw_en = Input(Bool())// if parity tx is enabled by software
    val parity_rx_sw_en = Input(Bool())// if parity rx is enabled by software
    val parity_rx_enable = Output(Bool())// tell parity if the parity should be used for receiving
    val parity_tx_enable = Output(Bool())// tell parity if the parity should be used for sending

    val cycles_1us = Input(UInt(32.W))
}


class ParityNegotiationSubmodule() extends Module{
    val io = IO(new ParityNegotiationSubmoduleIO())

    val parity_rsp_snt_flag_reg = RegInit(false.B)
    val parity_req_snt_flag_reg = RegInit(false.B)
    val parity_req_rcv_flag_reg = RegInit(false.B)
    val parity_rsp_rcv_flag_reg = RegInit(false.B)
    val parity_rx_enable_reg = RegInit(false.B)
    val parity_tx_enable_reg = RegInit(false.B)

    val parity_req_timeout_counter_reg = RegInit(0.U(32.W))

    val timeout = io.cycles_1us << 2 // 8ms

    io.parity_rx_enable := parity_rx_enable_reg
    io.parity_tx_enable := parity_tx_enable_reg

//state_reg === InterfaceStatus.RETRAIN && !retrain_from_L1_reg && io.fdi_lp_state_req =/= StateReq.ACTIVE
    when(io.start_negotiation){

        val reqcomplete = !io.parity_tx_sw_en | parity_rsp_rcv_flag_reg
        val rspcomplete = parity_req_timeout_counter_reg === timeout | parity_rsp_snt_flag_reg
        io.negotiation_complete :=  reqcomplete & rspcomplete

        when(io.parity_tx_sw_en && !parity_req_snt_flag_reg){
            io.parity_sb_snd := SideBandMessage.PARITY_FEATURE_REQ
        }.elsewhen(io.parity_rx_sw_en && parity_req_rcv_flag_reg && !parity_rsp_snt_flag_reg){
            io.parity_sb_snd := SideBandMessage.PARITY_FEATURE_ACK
        }.elsewhen(!io.parity_rx_sw_en && parity_req_rcv_flag_reg && !parity_rsp_snt_flag_reg){
            io.parity_sb_snd := SideBandMessage.PARITY_FEATURE_NAK
        }.otherwise{
            io.parity_sb_snd  := SideBandMessage.NOP
        }

        when(!parity_req_rcv_flag_reg){
            parity_req_timeout_counter_reg := parity_req_timeout_counter_reg + 1.U
        }.elsewhen(parity_req_timeout_counter_reg === timeout){//8us
            parity_req_timeout_counter_reg := parity_req_timeout_counter_reg
        }.otherwise{
            parity_req_timeout_counter_reg := 0.U
        }

        when(io.parity_sb_snd === SideBandMessage.PARITY_FEATURE_REQ && io.parity_sb_rdy){
            parity_req_snt_flag_reg := true.B
        }.otherwise{
            parity_req_snt_flag_reg := parity_req_snt_flag_reg
        }

        when((io.parity_sb_snd === SideBandMessage.PARITY_FEATURE_ACK || io.parity_sb_snd === SideBandMessage.PARITY_FEATURE_NAK) && io.parity_sb_rdy){
            parity_rsp_snt_flag_reg := true.B
        }.otherwise{
            parity_rsp_snt_flag_reg := parity_rsp_snt_flag_reg
        }
        
        when(io.parity_sb_rcv === SideBandMessage.PARITY_FEATURE_REQ){
            parity_req_rcv_flag_reg := true.B
        }.otherwise{
            parity_req_rcv_flag_reg := parity_req_rcv_flag_reg
        }

        when(io.parity_sb_rcv === SideBandMessage.PARITY_FEATURE_ACK || io.parity_sb_rcv === SideBandMessage.PARITY_FEATURE_NAK){
            parity_rsp_rcv_flag_reg := true.B
        }.otherwise{
            parity_rsp_rcv_flag_reg := parity_rsp_rcv_flag_reg
        }


        when(io.parity_sb_snd === SideBandMessage.PARITY_FEATURE_ACK && io.parity_sb_rdy){
            parity_rx_enable_reg := true.B
        }.elsewhen(io.parity_sb_snd === SideBandMessage.PARITY_FEATURE_NAK && io.parity_sb_rdy){
            parity_rx_enable_reg := false.B
        }.otherwise{
            parity_rsp_snt_flag_reg := parity_rsp_snt_flag_reg
        }

        when(io.parity_sb_rcv === SideBandMessage.PARITY_FEATURE_ACK){
            parity_tx_enable_reg := true.B
        }.elsewhen(io.parity_sb_rcv === SideBandMessage.PARITY_FEATURE_NAK || !io.parity_tx_sw_en){
            parity_tx_enable_reg := false.B
        }.otherwise{
            parity_tx_enable_reg := parity_tx_enable_reg
        }

    }.otherwise{
        io.parity_sb_snd  := SideBandMessage.NOP
        io.negotiation_complete := false.B    

        parity_req_timeout_counter_reg := 0.U
        parity_rsp_snt_flag_reg := false.B
        parity_req_snt_flag_reg := false.B
        parity_req_rcv_flag_reg := false.B
        parity_rsp_rcv_flag_reg := false.B
        parity_rx_enable_reg := parity_rx_enable_reg
        parity_tx_enable_reg := parity_tx_enable_reg           
    }
}