package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
//import chisel3.util._
//import chisel3.experimental._

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._


object D2DSidebandConstant{
    val ADV_CAP_MESSAGE_DATA = "b0000000000000000000000000000000000000000000000000000000010010001".U// Raw mod [0], streaming [4], Stack0_Enable [7]
}

class D2DSidebandModuleIO(val fdiParams: FdiParams) extends Bundle{
    val fdi_pl_cfg = Output(UInt(fdiParams.sbWidth.W))
    val fdi_pl_cfg_vld = Output(Bool())
    val fdi_pl_cfg_crd = Input(Bool())
    val fdi_lp_cfg = Input(UInt(fdiParams.sbWidth.W))
    val fdi_lp_cfg_vld = Input(Bool())
    val fdi_lp_cfg_crd = Output(Bool())    

    val rdi_pl_cfg = Input(UInt(fdiParams.sbWidth.W))
    val rdi_pl_cfg_vld = Input(Bool())
    val rdi_pl_cfg_crd = Output(Bool())
    val rdi_lp_cfg = Output(UInt(fdiParams.sbWidth.W))
    val rdi_lp_cfg_vld = Output(Bool())
    val rdi_lp_cfg_crd = Input(Bool())

    // interface to link management controller
    val sideband_rcv = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)) // sideband requested signals
    val sideband_snt = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)) // tell sideband module to send request of state change
    val sideband_rdy = Output(Bool())// sideband can consume the op in sideband_snt. 
}

class D2DSidebandModule(val fdiParams: FdiParams, val sbParams: SidebandParams) extends Module{
    val io = IO(new D2DSidebandModuleIO(fdiParams))

    val fdi_sideband_node = Module(new SidebandNode(sbParams, fdiParams))
    val rdi_sideband_node = Module(new SidebandNode(sbParams, fdiParams))
    val sideband_switch = Module(new sidebandSwitcher(myID = 1, sbParams = sbParams))

    io.fdi_pl_cfg := fdi_sideband_node.io.outer.tx.bits
    io.fdi_pl_cfg_vld := fdi_sideband_node.io.outer.tx.valid
    fdi_sideband_node.io.outer.tx.credit := io.fdi_pl_cfg_crd

    fdi_sideband_node.io.outer.rx.bits := io.fdi_lp_cfg
    fdi_sideband_node.io.outer.rx.valid := io.fdi_lp_cfg_vld
    io.fdi_lp_cfg_crd := fdi_sideband_node.io.outer.rx.credit

    rdi_sideband_node.io.outer.rx.bits := io.rdi_pl_cfg
    rdi_sideband_node.io.outer.rx.valid := io.rdi_pl_cfg_vld
    io.rdi_pl_cfg_crd := rdi_sideband_node.io.outer.rx.credit

    io.rdi_lp_cfg := rdi_sideband_node.io.outer.tx.bits 
    io.rdi_lp_cfg_vld := rdi_sideband_node.io.outer.tx.valid
    rdi_sideband_node.io.outer.tx.credit := io.rdi_lp_cfg_crd

    sideband_switch.io.outer.layer_to_node_above <> fdi_sideband_node.io.inner.layer_to_node
    sideband_switch.io.outer.layer_to_node_below <> rdi_sideband_node.io.inner.layer_to_node
    sideband_switch.io.outer.node_to_layer_above <> fdi_sideband_node.io.inner.node_to_layer
    sideband_switch.io.outer.node_to_layer_below <> rdi_sideband_node.io.inner.node_to_layer

    sideband_switch.io.inner.layer_to_node_above.bits := 0.U(sbParams.sbNodeMsgWidth.W)
    sideband_switch.io.inner.layer_to_node_above.valid := false.B

    sideband_switch.io.inner.node_to_layer_below.ready := true.B
    sideband_switch.io.inner.node_to_layer_above.ready := true.B

    when(sideband_switch.io.inner.node_to_layer_below.valid && sideband_switch.io.inner.node_to_layer_below.ready){
        when(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_REQ_ACTIVE)){
            io.sideband_rcv := SideBandMessage.REQ_ACTIVE
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_REQ_L1)){
            io.sideband_rcv := SideBandMessage.REQ_L1
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_REQ_L2)){
            io.sideband_rcv := SideBandMessage.REQ_L2
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_REQ_LINKRESET)){
            io.sideband_rcv := SideBandMessage.REQ_LINKRESET
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_REQ_DISABLE)){
            io.sideband_rcv := SideBandMessage.REQ_DISABLED
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_RSP_ACTIVE)){
            io.sideband_rcv := SideBandMessage.RSP_ACTIVE
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_RSP_PMNAK)){
            io.sideband_rcv := SideBandMessage.RSP_PMNAK
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_RSP_L1)){
            io.sideband_rcv := SideBandMessage.RSP_L1
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_RSP_L2)){
            io.sideband_rcv := SideBandMessage.RSP_L2
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_RSP_LINKRESET)){
            io.sideband_rcv := SideBandMessage.RSP_LINKRESET
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.LINKMGMT_ADAPTER0_RSP_DISABLE)){
            io.sideband_rcv := SideBandMessage.RSP_DISABLED
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.PARTIYFEATURE_REQ)){
            io.sideband_rcv := SideBandMessage.PARITY_FEATURE_REQ
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.PARTIYFEATURE_ACK)){
            io.sideband_rcv := SideBandMessage.PARITY_FEATURE_ACK
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.PARTIYFEATURE_NAK)){
            io.sideband_rcv := SideBandMessage.PARITY_FEATURE_NAK
        }.elsewhen(SBMsgCompare(sideband_switch.io.inner.node_to_layer_below.bits, SBM.ADVCAP_ADAPTER)){
            io.sideband_rcv := SideBandMessage.ADV_CAP
        }.otherwise{
            io.sideband_rcv := SideBandMessage.NOP
        }
    }.otherwise{
        io.sideband_rcv := SideBandMessage.NOP
    }
    

    when(io.sideband_snt =/= SideBandMessage.NOP){
        when(io.sideband_snt === SideBandMessage.REQ_ACTIVE){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_ACTIVE, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.REQ_L1){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_L1, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.REQ_L2){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_L2, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.REQ_LINKRESET){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_LINKRESET, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.REQ_DISABLED){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_DISABLE, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.RSP_ACTIVE){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_ACTIVE, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.RSP_PMNAK){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_PMNAK, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.RSP_L1){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_L1, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.RSP_L2){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_L2, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.RSP_LINKRESET){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_LINKRESET, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.RSP_DISABLED){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_DISABLE, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.PARITY_FEATURE_REQ){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.PARTIYFEATURE_REQ, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.PARITY_FEATURE_ACK){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.PARTIYFEATURE_ACK, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.PARITY_FEATURE_NAK){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.PARTIYFEATURE_NAK, src = "D2D", remote = true, dst = "D2D")
        }.elsewhen(io.sideband_snt === SideBandMessage.ADV_CAP){
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.ADVCAP_ADAPTER, src = "D2D", remote = true, dst = "D2D", data = D2DSidebandConstant.ADV_CAP_MESSAGE_DATA)
        }.otherwise{
            sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.NOP_CRD, src = "D2D", remote = true, dst = "D2D")
        }
        sideband_switch.io.inner.layer_to_node_below.valid := true.B
        io.sideband_rdy := sideband_switch.io.inner.layer_to_node_below.valid & sideband_switch.io.inner.layer_to_node_below.ready
    }.otherwise{
        sideband_switch.io.inner.layer_to_node_below.bits := SBMsgCreate(base = SBM.NOP_CRD, src = "D2D", remote = true, dst = "D2D")
        sideband_switch.io.inner.layer_to_node_below.valid := false.B
        io.sideband_rdy := false.B
    }
}
