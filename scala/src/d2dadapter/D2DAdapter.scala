package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._


class D2DAdapterIO (val fdiParams: FdiParams, val rdiParams: RdiParams) extends Bundle {
    val fdi = Flipped(new Fdi(fdiParams))
    val rdi = Flipped(new Rdi(rdiParams))
}

class D2DAdapter(val fdiParams: FdiParams, val rdiParams: RdiParams, 
                 val sbParams: SidebandParams) extends Module {
    val io = IO(new D2DAdapterIO(fdiParams, rdiParams))

    assert(fdiParams.nBytes == rdiParams.nBytes)
    assert(fdiParams.ncWidth == rdiParams.ncWidth)

    val linkManager = Module(new AdapterSM(fdiParams, rdiParams, sbParams))
    val fdiStallHandler = Module(new FDIStallHandler())
    val rdiStallHandler = Module(new RDIStallHandler())

    val d2dSideband = Module(new D2DSidebandModule(fdiParams, sbParams))
    val d2dMainband = Module(new D2DMainbandModule(fdiParams, rdiParams, sbParams))
    val rdiClkAckReg = RegInit(false.B)
    val fdiWakeAckReg = RegInit(false.B)
    val fdiLpStateReqAsRdi = io.fdi.lpStateReq.asUInt.asTypeOf(RDIStateReq())
    val fdiPlStateSts = linkManager.io.fdi_pl_state_sts.asUInt.asTypeOf(FDIState())

    io.fdi.lclk := DontCare
    io.rdi.lclk := DontCare

    // Default protocol-facing status outputs derived from RDI.
    io.fdi.plSpeedmode := io.rdi.plSpeedmode
    io.fdi.plMaxSpeedmode := io.rdi.plMaxSpeedmode
    io.fdi.plLnkCfg := io.rdi.plLnkCfg
    io.fdi.plStateSts := fdiPlStateSts
    io.fdi.plInbandPres := linkManager.io.fdi_pl_inband_pres
    io.fdi.plNfError := io.rdi.plNfError
    io.fdi.plTrainError := io.rdi.plTrainError
    io.fdi.plError := io.rdi.plError
    io.fdi.plCError := io.rdi.plCError
    io.fdi.plPhyInRecenter := io.rdi.plPhyInRecenter
    io.fdi.plProtocol := FDIProtocol.streamingNoManagementTransport
    io.fdi.plProtocolFlitFmt := FDIFlitFormat.rawFormat
    io.fdi.plProtocolVld :=
      linkManager.io.fdi_pl_inband_pres ||
      (linkManager.io.fdi_pl_state_sts === RDIState.active) ||
      (linkManager.io.fdi_pl_state_sts === RDIState.retrain)
    io.fdi.plRxActiveReq := linkManager.io.fdi_pl_rx_active_req
    io.fdi.plClkReq := true.B

    fdiWakeAckReg := io.fdi.lpWakeReq
    io.fdi.plWakeAck := fdiWakeAckReg

    rdiClkAckReg := io.rdi.plClkReq
    io.rdi.lpClkAck := rdiClkAckReg
    io.rdi.lpWakeReq := true.B

    // Link management controller.
    linkManager.io.fdi_lp_state_req := fdiLpStateReqAsRdi
    linkManager.io.fdi_lp_linkerror := io.fdi.lpLinkError
    linkManager.io.fdi_lp_rx_active_sts := io.fdi.lpRxActiveSts

    io.rdi.lpLinkError := linkManager.io.rdi_lp_linkerror
    io.rdi.lpStateReq := linkManager.io.rdi_lp_state_req
    linkManager.io.rdi_pl_state_sts := io.rdi.plStateSts
    linkManager.io.rdi_pl_inband_pres := io.rdi.plInbandPres

    // Sideband.
    d2dSideband.io.sb.snt := linkManager.io.sb_snd
    linkManager.io.sb_rcv := d2dSideband.io.sb.rcv
    linkManager.io.sb_rdy := d2dSideband.io.sb.rdy

    io.fdi.plCfg := d2dSideband.io.fdi.plCfg
    io.fdi.plCfgVld := d2dSideband.io.fdi.plCfgVld
    d2dSideband.io.fdi.plCfgCrd := io.fdi.plCfgCrd
    d2dSideband.io.fdi.lpCfg := io.fdi.lpCfg
    d2dSideband.io.fdi.lpCfgVld := io.fdi.lpCfgVld
    io.fdi.lpCfgCrd := d2dSideband.io.fdi.lpCfgCrd

    d2dSideband.io.rdi.plCfg := io.rdi.plCfg
    d2dSideband.io.rdi.plCfgVld := io.rdi.plCfgVld
    d2dSideband.io.rdi.plCfgCrd := io.rdi.plCfgCrd
    io.rdi.lpCfg := d2dSideband.io.rdi.lpCfg
    io.rdi.lpCfgVld := d2dSideband.io.rdi.lpCfgVld
    io.rdi.lpCfgCrd := d2dSideband.io.rdi.lpCfgCrd

    // Stall Handlers
    // RDI stall stays adjacent-layer compliant: logphy requests a stall,
    // the mainband drains TX, and only then do we acknowledge back on RDI.
    rdiStallHandler.io.plStallReq := io.rdi.plStallReq
    d2dMainband.io.state.mainbandStallReq := rdiStallHandler.io.mainbandStallReq
    rdiStallHandler.io.mainbandStallDone := d2dMainband.io.state.mainbandStallDone
    io.rdi.lpStallAck := rdiStallHandler.io.lpStallAck

    // FDI stall is a separate adjacent-layer handshake. The controller
    // decides when protocol must stall to support a to-spec Active exit.
    fdiStallHandler.io.linkStallReq := linkManager.io.linkmgmt_stallreq
    linkManager.io.linkmgmt_stalldone := fdiStallHandler.io.linkStallDone
    io.fdi.plStallReq := fdiStallHandler.io.plStallReq
    fdiStallHandler.io.lpStallAck := io.fdi.lpStallAck

    // Mainband.
    d2dMainband.io.state.d2dState := linkManager.io.fdi_pl_state_sts
    d2dMainband.io.state.rxActiveReq := linkManager.io.fdi_pl_rx_active_req
    d2dMainband.io.state.rxActiveSts := io.fdi.lpRxActiveSts

    d2dMainband.io.fdi.lpIrdy := io.fdi.lpIrdy
    d2dMainband.io.fdi.lpValid := io.fdi.lpValid
    d2dMainband.io.fdi.lpData := io.fdi.lpData
    io.fdi.plTrdy := d2dMainband.io.fdi.plTrdy
    io.fdi.plValid := d2dMainband.io.fdi.plValid
    io.fdi.plData := d2dMainband.io.fdi.plData.asUInt

    io.rdi.lpIrdy := d2dMainband.io.rdi.lpIrdy
    io.rdi.lpValid := d2dMainband.io.rdi.lpValid
    io.rdi.lpData := d2dMainband.io.rdi.lpData.asUInt
    d2dMainband.io.rdi.plTrdy := io.rdi.plTrdy
    d2dMainband.io.rdi.plValid := io.rdi.plValid
    d2dMainband.io.rdi.plData := io.rdi.plData

}


object MainD2DAdapter extends App {
  ChiselStage.emitSystemVerilogFile(
    new D2DAdapter(new FdiParams(64, 32), RdiParams(64, 32), new SidebandParams()),
    args = Array("-td", "./generatedVerilog/logphy"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}
