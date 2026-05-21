package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._


object D2DSidebandConstant{
    val ADV_CAP_MESSAGE_DATA = "b0000000000000000000000000000000000000000000000000000000010010001".U// Raw mod [0], streaming [4], Stack0_Enable [7]
}

class D2DSidebandModuleIO() extends Bundle{
    // interface to link management controller
    val rcv = Output(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)) // sideband requested signals
    val snt = Input(UInt(D2DAdapterSignalSize.SIDEBAND_MESSAGE_OP_WIDTH)) // tell sideband module to send request of state change
    val rdy = Output(Bool())// sideband can consume the op in sideband_snt. 
}

class D2DSidebandModule(val fdiParams: FdiParams, val sbParams: SidebandParams) extends Module{
    val io = IO(new Bundle {
        val sb = new D2DSidebandModuleIO()
        val rdi = new Bundle{
            val plCfg = Input(UInt(fdiParams.ncWidth.W))
            val plCfgVld = Input(Bool())
            val plCfgCrd = Input(Bool())
            val lpCfg = Output(UInt(fdiParams.ncWidth.W))
            val lpCfgVld = Output(Bool())
            val lpCfgCrd = Output(Bool())
        }
        val fdi = new Bundle {
            val plCfg = Output(UInt(fdiParams.ncWidth.W))
            val plCfgVld = Output(Bool())
            val plCfgCrd = Input(Bool())
            val lpCfg = Input(UInt(fdiParams.ncWidth.W))
            val lpCfgVld = Input(Bool())
            val lpCfgCrd = Output(Bool())
        }
    })

    // This channel already contains the FDI-side node, RDI-side node, and the
    // D2D-layer switch. The local sideband bridge should connect through the
    // channel's layer port rather than instantiating extra nodes or switches.
    val sidebandChannel = Module(new D2DSidebandChannel(
        sbMsgWidth = sbParams.sbNodeMsgWidth,
        sbLinkWidth = sbParams.sbLinkWidth,
        fdiNcWidth = fdiParams.ncWidth,
        rdiNcWidth = fdiParams.ncWidth,
        numCredits = sbParams.maxCrd,
        queueDepths = SidebandPriorityQueueDepths()
    ))

    // FDI Sideband
    sidebandChannel.io.fdi.in.bits := io.fdi.lpCfg
    sidebandChannel.io.fdi.in.valid := io.fdi.lpCfgVld
    io.fdi.lpCfgCrd := sidebandChannel.io.fdi.rxCreditReturn

    io.fdi.plCfg := sidebandChannel.io.fdi.out.bits
    io.fdi.plCfgVld := sidebandChannel.io.fdi.out.valid
    sidebandChannel.io.fdi.txCreditReturn := io.fdi.plCfgCrd

    // RDI Sideband
    sidebandChannel.io.rdi.in.bits := io.rdi.plCfg
    sidebandChannel.io.rdi.in.valid := io.rdi.plCfgVld
    io.rdi.lpCfgCrd := sidebandChannel.io.rdi.rxCreditReturn

    io.rdi.lpCfg := sidebandChannel.io.rdi.out.bits
    io.rdi.lpCfgVld := sidebandChannel.io.rdi.out.valid
    sidebandChannel.io.rdi.txCreditReturn := io.rdi.plCfgCrd

    val sbTxMsg = WireDefault(
        SBMsgCreate(
            base = SBM.NOP_CRD,
            src = "D2D",
            dst = "D2D",
            remote = true
    )
    )
    val sbTxValid = WireDefault(false.B)

    // D2D/link-management -> sideband channel
    sidebandChannel.io.layer.in.bits := sbTxMsg
    sidebandChannel.io.layer.in.valid := sbTxValid
    io.sb.rdy := sbTxValid && sidebandChannel.io.layer.in.ready

    // sideband channel -> D2D/link-management
    sidebandChannel.io.layer.out.ready := true.B
    io.sb.rcv := SideBandMessage.NOP

    when(sidebandChannel.io.layer.out.valid) {
        when(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_REQ_ACTIVE)) {
            io.sb.rcv := SideBandMessage.REQ_ACTIVE
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_REQ_L1)) {
            io.sb.rcv := SideBandMessage.REQ_L1
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_REQ_L2)) {
            io.sb.rcv := SideBandMessage.REQ_L2
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_REQ_LINKRESET)) {
            io.sb.rcv := SideBandMessage.REQ_LINKRESET
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_REQ_DISABLE)) {
            io.sb.rcv := SideBandMessage.REQ_DISABLED
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_RSP_ACTIVE)) {
            io.sb.rcv := SideBandMessage.RSP_ACTIVE
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_RSP_PMNAK)) {
            io.sb.rcv := SideBandMessage.RSP_PMNAK
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_RSP_L1)) {
            io.sb.rcv := SideBandMessage.RSP_L1
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_RSP_L2)) {
            io.sb.rcv := SideBandMessage.RSP_L2
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_RSP_LINKRESET)) {
            io.sb.rcv := SideBandMessage.RSP_LINKRESET
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.LINKMGMT_ADAPTER0_RSP_DISABLE)) {
            io.sb.rcv := SideBandMessage.RSP_DISABLED
        }.elsewhen(SBMsgCompare(sidebandChannel.io.layer.out.bits, SBM.ADVCAP_ADAPTER)) {
            io.sb.rcv := SideBandMessage.ADV_CAP
        }
    }

    when(io.sb.snt =/= SideBandMessage.NOP) {
        when(io.sb.snt === SideBandMessage.REQ_ACTIVE) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_ACTIVE, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.REQ_L1) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_L1, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.REQ_L2) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_L2, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.REQ_LINKRESET) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_LINKRESET, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.REQ_DISABLED) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_REQ_DISABLE, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.RSP_ACTIVE) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_ACTIVE, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.RSP_PMNAK) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_PMNAK, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.RSP_L1) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_L1, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.RSP_L2) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_L2, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.RSP_LINKRESET) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_LINKRESET, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.RSP_DISABLED) {
            sbTxMsg := SBMsgCreate(base = SBM.LINKMGMT_ADAPTER0_RSP_DISABLE, src = "D2D", dst = "D2D", remote = true)
            sbTxValid := true.B
        }.elsewhen(io.sb.snt === SideBandMessage.ADV_CAP) {
            sbTxMsg := SBMsgCreate(
                base = SBM.ADVCAP_ADAPTER,
                src = "D2D",
                dst = "D2D",
                remote = true,
                data = D2DSidebandConstant.ADV_CAP_MESSAGE_DATA
            )
            sbTxValid := true.B
        }
    }
}
