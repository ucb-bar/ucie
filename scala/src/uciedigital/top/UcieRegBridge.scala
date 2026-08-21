/*
  Description:
    Translation layer between the UCIe register block bundles and the protocol layer,
    D2D adapter, and logical PHY. Owns the encoding conversions, edge detectors, error
    escalation, training state history, and the runtime-link-test busy bit.
 */
package edu.berkeley.cs.uciedigital.top

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.d2dadapter.AdapterRegIO
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.protocol.{
  ProtocolLayerCtrlIO,
  ProtocolLayerStatusIO
}
import edu.berkeley.cs.uciedigital.regs._

class UcieRegBridgeCtrlIO(retryW: Int) extends Bundle {
  val linkReset = Input(Bool())
  val pwrGood = Input(Bool())
  val retryTrainingAmt = Input(UInt(retryW.W))
}

class UcieRegBridge(
    params: UcieRegParams,
    afeParams: AfeParams,
    retryW: Int
) extends Module {
  val io = IO(new Bundle {
    val ctrl = new UcieRegBridgeCtrlIO(retryW)
    val phyCtrl = Flipped(new LogicalPhyCtrlIO(retryW, afeParams))
    val phyStatus = Flipped(new LogicalPhyStatusIO())
    val protoCtrl = Flipped(new ProtocolLayerCtrlIO())
    val protoStatus = Flipped(new ProtocolLayerStatusIO())
    val adapter = Flipped(new AdapterRegIO())
    val regs = Flipped(new UcieRegBlockIO(params))
  })

  private val n = params.numModules
  private val regsToPhy = io.regs.regsToPhy
  private val regsToLink = io.regs.regsToLink
  private val regsToAdapter = io.regs.regsToAdapter
  private val phyToRegs = io.regs.phyToRegs
  private val linkToRegs = io.regs.linkToRegs
  private val adapterToRegs = io.regs.adapterToRegs

  io.regs.linkReset := io.ctrl.linkReset

  private def rose(x: Bool): Bool = {
    val prev = RegInit(false.B)
    prev := x
    x && !prev
  }

  private def changed(x: UInt): Bool = {
    val prev = RegInit(0.U(x.getWidth.W))
    prev := x
    prev =/= x
  }

  // ==============================================================================================
  // Link state and status
  // ==============================================================================================
  val ltState = io.phyStatus.ltState
  val linkUp = io.protoStatus.linkState === FDIState.active
  val linkTraining = (ltState === LTState.sSBINIT) ||
    (ltState === LTState.sMBINIT) ||
    (ltState === LTState.sMBTRAIN) ||
    (ltState === LTState.sLINKINIT) ||
    (ltState === LTState.sPHYRETRAIN)
  val rawFormatEnabled = io.protoStatus.negotiatedProtocolValid &&
    (io.protoStatus.negotiatedFlitFormat === FDIFlitFormat.rawFormat) &&
    regsToLink.rawFormatEnable

  val widthEnabled = io.phyStatus.linkWidth.asUInt
  val speedEnabled = io.phyStatus.freqSel.asUInt

  // A retrain episode is in flight from PHYRETRAIN entry until the LTSM is back in ACTIVE.
  val retrainInFlight = RegInit(false.B)
  when(ltState === LTState.sPHYRETRAIN) {
    retrainInFlight := true.B
  }.elsewhen(ltState === LTState.sACTIVE || ltState === LTState.sRESET) {
    retrainInFlight := false.B
  }
  val retrainDone = retrainInFlight && (ltState === LTState.sACTIVE)

  linkToRegs.linkUp := linkUp
  linkToRegs.linkTraining := linkTraining
  linkToRegs.rawFormatEnabled := rawFormatEnabled
  // Standard package x16, so the x32 Advanced Package Module mode never engages.
  linkToRegs.x32AdvPkgEnabled := false.B
  linkToRegs.linkWidthEnabled := widthEnabled
  linkToRegs.linkSpeedEnabled := speedEnabled
  linkToRegs.flitFormat := io.protoStatus.negotiatedFlitFormat.asUInt
  linkToRegs.statusChanged := changed(
    Cat(linkUp, linkTraining, rawFormatEnabled)
  )
  linkToRegs.bwChanged := linkUp && changed(Cat(widthEnabled, speedEnabled))
  linkToRegs.trainingDone := linkUp
  linkToRegs.retrainDone := retrainDone

  // ==============================================================================================
  // Adapter status and error escalation
  // ==============================================================================================
  val adapterStatePrev = RegInit(RDIState.reset)
  adapterStatePrev := io.adapter.linkState
  val adapterRetrainEntry = (io.adapter.linkState === RDIState.retrain) &&
    (adapterStatePrev =/= RDIState.retrain)

  val uncorrSet = Wire(Vec(6, Bool()))
  val corrSet = Wire(Vec(5, Bool()))

  // No timeout counters exist in AdapterSM, so the Adapter Timeout bit has no producer.
  uncorrSet(0) := false.B
  uncorrSet(1) := io.protoStatus.rxOverflow
  uncorrSet(2) := io.adapter.sideband.invalidRoute ||
    io.adapter.sideband.rxQueuesFull
  uncorrSet(3) := io.adapter.sideband.errMsgFatal
  uncorrSet(4) := io.adapter.sideband.errMsgNonFatal
  // Parameter exchange in AdapterSM has no failure path to report.
  uncorrSet(5) := false.B

  // Streaming Raw has no CRC/retry and the parity feature is off, so bits 0 and 4 stay clear.
  corrSet(0) := false.B
  corrSet(1) := adapterRetrainEntry
  corrSet(2) := io.adapter.sideband.parityErr
  corrSet(3) := io.adapter.sideband.errMsgCorrectable
  corrSet(4) := false.B

  adapterToRegs.uncorrErrSet := uncorrSet
  adapterToRegs.corrErrSet := corrSet

  // Header Log 1 is written from the mailbox completion inside the register block.
  adapterToRegs.headerLog1.valid := false.B
  adapterToRegs.headerLog1.bits := 0.U
  // Timeout and Rx-overflow syndrome encodings have no producer in this configuration.
  adapterToRegs.headerLog2.timeoutEnc := 0.U
  adapterToRegs.headerLog2.rxOverflowEnc := 0.U
  adapterToRegs.headerLog2.lsmResponse := 0.U
  adapterToRegs.headerLog2.lsmId := false.B
  adapterToRegs.headerLog2.paramExchSuccess := io.adapter.paramExchSuccess
  adapterToRegs.headerLog2.flitFormat :=
    io.protoStatus.negotiatedFlitFormat.asUInt
  adapterToRegs.advCapAdapter := io.adapter.sideband.advCapAdapter
  // FinCap.Adapter is never sent or received, so the finalized log has no producer.
  adapterToRegs.finCapAdapter.valid := false.B
  adapterToRegs.finCapAdapter.bits := 0.U

  val uncorrActive = uncorrSet.asUInt & (~regsToAdapter.uncorrMask).asUInt
  val corrActive = corrSet.asUInt & (~regsToAdapter.corrMask).asUInt
  linkToRegs.corrErr := corrActive.orR
  linkToRegs.uncorrFatal :=
    (uncorrActive & regsToAdapter.uncorrSeverity).orR
  linkToRegs.uncorrNonFatal :=
    (uncorrActive & (~regsToAdapter.uncorrSeverity).asUInt).orR

  io.adapter.corrProtoReport := regsToLink.corrProtoReport
  io.adapter.nonFatalProtoReport := regsToLink.nonFatalProtoReport
  io.adapter.fatalProtoReport := regsToLink.fatalProtoReport

  // ==============================================================================================
  // PHY status
  // ==============================================================================================
  val negParam = io.phyStatus.negotiatedPhyParamSettings
  val negClockMode = RegInit(false.B)
  val negClockPhase = RegInit(false.B)
  val negTarr = RegInit(false.B)
  when(negParam.valid) {
    negClockMode := negParam.bits.clockMode.asBool
    negClockPhase := negParam.bits.clockPhase.asBool
    negTarr := negParam.bits.txAdjRuntime.asBool
  }

  val phyStatusOut = phyToRegs.phyStatus
  // No analog readback path reaches the digital stack, so these echo their controls.
  phyStatusOut.rxTerminationStatus := regsToPhy.phyControl.rxTerminationControl
  phyStatusOut.txEqStatus := regsToPhy.phyControl.txEqEnable
  phyStatusOut.clockModeStatus := negClockMode
  phyStatusOut.clockPhaseStatus := negClockPhase
  phyStatusOut.laneReversal := io.phyStatus.doLaneReversal
  // I/Q correction and Tx EQ presets are above 32 GT/s features and are not implemented.
  phyStatusOut.iqCorrectionParam := 0.U
  phyStatusOut.eqPresetSetting := 0.U
  phyStatusOut.tarrStatus := negTarr

  val sb = io.phyStatus.sideband
  val sbInternalError = sb.sbInvalidRouteUpperSeen ||
    sb.sbInvalidRouteCurrSeen ||
    sb.sbInvalidRouteLowerSeen ||
    sb.sbRxPriorityQueuesFullSeen ||
    sb.sbUnhandledCurrentLayerMsgSeen ||
    sb.sbParityErrSeen

  // ==============================================================================================
  // Training state history for Error Log 0/1
  // ==============================================================================================
  val curState = WireDefault(0.U(8.W))
  curState := io.phyStatus.currentState.asUInt

  val stateHist = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))
  val statePrev = RegInit(0.U(8.W))
  val inTrainError = ltState === LTState.sTRAINERROR
  when(curState =/= statePrev && !inTrainError) {
    stateHist(3) := stateHist(2)
    stateHist(2) := stateHist(1)
    stateHist(1) := stateHist(0)
    stateHist(0) := curState
    statePrev := curState
  }

  val errEvent = rose(inTrainError) || rose(io.phyStatus.trainingTimedout)

  val laneMapChanged = changed(io.phyStatus.txLaneMask)

  for (m <- 0 until n) {
    val isM0 = (m == 0).B
    val elog = phyToRegs.errorLog(m)
    elog.valid := errEvent && isM0
    elog.bits.stateN := stateHist(0)
    elog.bits.stateNm1 := stateHist(1)
    elog.bits.stateNm2 := stateHist(2)
    elog.bits.stateNm3 := stateHist(3)
    elog.bits.laneReversal := io.phyStatus.doLaneReversal
    elog.bits.widthDegrade := io.phyStatus.widthDegraded

    phyToRegs.errLog1Set(m)(0) := io.phyStatus.trainingTimedout && isM0
    phyToRegs.errLog1Set(m)(1) := sb.sbDeserializerTimedoutSeen && isM0
    phyToRegs.errLog1Set(m)(
      2
    ) := io.phyStatus.remoteRequestingTrainError && isM0
    phyToRegs.errLog1Set(m)(3) :=
      (io.phyStatus.fatalTrainingError || sbInternalError) && isM0

    // Standard package reports the module lane map in bits 15:0.
    phyToRegs.currentLaneMap(m).valid := laneMapChanged && isM0
    phyToRegs.currentLaneMap(m).bits := io.phyStatus.txLaneMask
  }

  // ==============================================================================================
  // Runtime link test
  // ==============================================================================================
  val linkTestBusy = RegInit(false.B)
  when(regsToPhy.linkTestStart) {
    linkTestBusy := true.B
  }.elsewhen(
    retrainDone || io.phyStatus.trainingTimedout ||
      io.phyStatus.fatalTrainingError
  ) {
    linkTestBusy := false.B
  }
  phyToRegs.linkTestBusy := linkTestBusy

  // ==============================================================================================
  // Control outputs to the datapath
  // ==============================================================================================
  val tsOverride =
    io.regs.vendorToPhy.map(_.tsOverrideEnable).getOrElse(false.B)

  val ts1 = regsToPhy.ts1(0)
  val ts2 = regsToPhy.ts2(0)
  val ts4 = regsToPhy.ts4(0)

  val ltp = io.phyCtrl.linkTrainingParameters
  ltp.dataPattern := ts1(2, 0)
  ltp.validPattern := ts1(5, 3)
  ltp.clockPhase := ts1(9, 6)
  ltp.patternMode := ts1(10)
  ltp.burstCount := ts1(26, 11)
  ltp.idleCount := ts2(15, 0)
  ltp.iterationCount := ts2(31, 16)
  ltp.maxErrorThreshold := ts4(15, 4)
  // Training Setup 4 carries no comparison-mode field, so per-lane comparison is kept.
  ltp.comparisonMode := 0.U

  io.phyCtrl.linkOpParamOverride := tsOverride
  io.phyCtrl.clockPhaseSelect := Mux(tsOverride, ts1(9, 6), 0.U)
  io.phyCtrl.maxErrorThresholdPerLane := ts4(15, 4)

  io.phyCtrl.pwrGood := io.ctrl.pwrGood
  io.phyCtrl.retryTrainingAmt := io.ctrl.retryTrainingAmt
  io.phyCtrl.swStartLinkTraining := regsToLink.startTrainingPending
  // The DVSEC Retrain bit goes through the protocol layer so the adapter runs its stall/drain
  // handshake first. Only the runtime link test, which is a PHY-level operation, retrains the
  // logical PHY directly.
  io.phyCtrl.swRetrainRequest := linkTestBusy
  io.phyCtrl.runtimeLinkCtrlBusyBit := linkTestBusy
  io.phyCtrl.changeInRuntimeLinkCtrlRegsDetected := linkTestBusy
  io.phyCtrl.runtimeRequestForRepair :=
    regsToPhy.applyLaneRepair.reduce(_ || _)

  val lp = io.phyCtrl.localPhyParamSettings
  lp.valid := true.B
  lp.bits.voltageSwing := params.phyCapability.txVswingCode.U
  lp.bits.maxDataRate := regsToLink.targetSpeed
  lp.bits.clockMode := regsToPhy.phyControl.rxClockModeSelect
  lp.bits.clockPhase := regsToPhy.phyControl.rxClockPhaseSelect
  lp.bits.ucieSx8 := (regsToLink.targetWidth === LinkWidth.x8.asUInt) ||
    regsToPhy.phyControl.forceX8Width
  lp.bits.sbFeatExt := 0.U
  lp.bits.txAdjRuntime := regsToPhy.phyControl.tarrEnable
  lp.bits.moduleId := 0.U

  io.protoCtrl.requestRetrain := regsToLink.retrainPending
  io.protoCtrl.requestLinkReset := false.B
  io.protoCtrl.requestDisable := false.B

  // ==============================================================================================
  // Vendor block
  // ==============================================================================================
  // Force Link Active has no state machine support yet. Acknowledging it immediately keeps the
  // auto-clear bit from latching as a permanently pending override.
  io.regs.phyToVendor.foreach { v =>
    v.forceActiveDone := io.regs.vendorToPhy.map(_.forceLinkActive).get
  }
  io.regs.d2dToVendor.foreach(_ := DontCare)

  // ==============================================================================================
  // Sideband mailbox
  // ==============================================================================================
  // Until the mailbox is bridged onto the sideband channel, every request completes as an
  // Unsupported Request so the trigger bit clears instead of hanging.
  val mbReq = io.regs.mailboxSideband.req
  val mbResp = io.regs.mailboxSideband.resp
  val mbPending = RegInit(false.B)
  mbReq.ready := !mbPending
  when(mbReq.valid && mbReq.ready) {
    mbPending := true.B
  }.elsewhen(mbResp.valid && mbResp.ready) {
    mbPending := false.B
  }
  mbResp.valid := mbPending
  mbResp.bits.status := UcieRegBridge.MailboxStatusUR.U
  mbResp.bits.rdata := 0.U
  mbResp.bits.header := 0.U
}

object UcieRegBridge {
  val MailboxStatusUR = 1

  def attach(block: UcieRegBlockIO, bridge: UcieRegBlockIO): Unit = {
    block.linkReset := bridge.linkReset
    block.adapterToRegs := bridge.adapterToRegs
    block.phyToRegs := bridge.phyToRegs
    block.linkToRegs := bridge.linkToRegs
    bridge.regsToAdapter := block.regsToAdapter
    bridge.regsToPhy := block.regsToPhy
    bridge.regsToLink := block.regsToLink

    bridge.mailboxSideband.req.valid := block.mailboxSideband.req.valid
    bridge.mailboxSideband.req.bits := block.mailboxSideband.req.bits
    block.mailboxSideband.req.ready := bridge.mailboxSideband.req.ready
    block.mailboxSideband.resp.valid := bridge.mailboxSideband.resp.valid
    block.mailboxSideband.resp.bits := bridge.mailboxSideband.resp.bits
    bridge.mailboxSideband.resp.ready := block.mailboxSideband.resp.ready

    block.vendorToPhy.zip(bridge.vendorToPhy).foreach { case (b, br) =>
      br := b
    }
    block.phyToVendor.zip(bridge.phyToVendor).foreach { case (b, br) =>
      b := br
    }
    block.vendorToD2d.zip(bridge.vendorToD2d).foreach { case (b, br) =>
      br := b
    }
    block.d2dToVendor.zip(bridge.d2dToVendor).foreach { case (b, br) =>
      b := br
    }
  }
}
