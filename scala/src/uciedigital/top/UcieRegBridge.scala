/*
  The bridge between the UCIe register block (sec 9.5) and the three layers.
  It also owns the Busy bit of the runtime link test.
 */
package edu.berkeley.cs.uciedigital.top

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.d2dadapter.AdapterRegIO
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.logphy._
import edu.berkeley.cs.uciedigital.rdi._
import edu.berkeley.cs.uciedigital.regs._

class UcieRegBridgeCtrlIO(retryW: Int) extends Bundle {
  val linkReset = Input(Bool())
  val pwrGood = Input(Bool())
  val retryTrainingAmt = Input(UInt(retryW.W))
}

class UcieRegBridgeChipIO extends Bundle {
  val rxOverflow = Input(Bool())
}

class UcieRegBridge(
    params: UcieRegParams,
    afeParams: AfeParams,
    retryW: Int
) extends Module {
  require(
    params.maxLinkWidth == afeParams.mbLanes &&
      params.phyBridge == afeParams.bridge,
    "Register and logical PHY training parameters must match"
  )

  val io = IO(new Bundle {
    val ctrl = new UcieRegBridgeCtrlIO(retryW)
    val chip = new UcieRegBridgeChipIO()
    val phyCtrl = Flipped(new LogicalPhyCtrlIO(retryW, afeParams))
    val phyStatus = Flipped(new LogicalPhyStatusIO())
    val protoCtrl = Flipped(new ProtocolLayerCtrlIO())
    val protoStatus = Flipped(new ProtocolLayerStatusIO())
    val adapter = Flipped(new AdapterRegIO())
    val regs = Flipped(new UcieRegBlockIO(params))
  })

  val n = params.numModules
  val regsToPhy = io.regs.regsToPhy
  val regsToLink = io.regs.regsToLink
  val regsToAdapter = io.regs.regsToAdapter
  val phyToRegs = io.regs.phyToRegs
  val linkToRegs = io.regs.linkToRegs
  val adapterToRegs = io.regs.adapterToRegs

  io.regs.linkReset := io.ctrl.linkReset

  def rose(x: Bool): Bool = {
    val prev = RegInit(false.B)
    prev := x
    x && !prev
  }

  def changed(x: UInt): Bool = {
    val prev = RegInit(0.U(x.getWidth.W))
    prev := x
    prev =/= x
  }

  // ===========================================================================
  // Link state and status
  // ===========================================================================
  val ltState = io.phyStatus.ltState
  val linkUp = io.protoStatus.linkState === FDIState.active
  val linkTraining = (ltState === LTState.sSBINIT) ||
    (ltState === LTState.sMBINIT) ||
    (ltState === LTState.sMBTRAIN) ||
    (ltState === LTState.sLINKINIT) ||
    (ltState === LTState.sPHYRETRAIN)

  // Table 9-10 bit 0 reports what was negotiated, so the Link Control request
  // bit is not a term here.
  val rawFormatEnabled = io.protoStatus.negotiatedProtocolValid &&
    (io.protoStatus.negotiatedFlitFormat === FDIFlitFormat.rawFormat)

  val widthEnabled = io.phyStatus.linkWidth.asUInt
  val speedEnabled = io.phyStatus.freqSel.asUInt

  val retrainInFlight = RegInit(false.B)
  when(ltState === LTState.sPHYRETRAIN) {
    retrainInFlight := true.B
  }.elsewhen(ltState === LTState.sACTIVE || ltState === LTState.sRESET) {
    retrainInFlight := false.B
  }
  val retrainDone = retrainInFlight && (ltState === LTState.sACTIVE)

  // The Link is up from the Protocol Layer side in Active, Active.PMNAK,
  // Retrain, L1 and L2 (Table 10-3). So Table 9-10 bit 15 reads up there.
  val fdiState = io.protoStatus.linkState
  val fdiUp = linkUp || fdiState === FDIState.activePmNak ||
    fdiState === FDIState.retrain || fdiState === FDIState.l1 ||
    fdiState === FDIState.l2

  // Bit 15 also needs RDI up. This Adapter never asks RDI for PM, so that
  // means Active and Retrain.
  val rdiUp = io.adapter.rdiState === RDIState.active ||
    io.adapter.rdiState === RDIState.retrain
  val linkStatusUp = fdiUp && rdiUp
  linkToRegs.linkStatusUp := linkStatusUp
  linkToRegs.linkTraining := linkTraining
  linkToRegs.rawFormatEnabled := rawFormatEnabled

  // Standard Package is x16, so x32 Advanced Package Module mode never runs.
  linkToRegs.x32AdvPkgEnabled := false.B
  linkToRegs.linkWidthEnabled := widthEnabled
  linkToRegs.linkSpeedEnabled := speedEnabled
  linkToRegs.flitFormat := io.protoStatus.negotiatedFlitFormat.asUInt

  linkToRegs.statusChanged := changed(linkStatusUp)

  // Table 9-10 bit 18 is a change that hardware chose. At each ACTIVE entry,
  // compare width and speed with the last entry. Skip it if bit 15 fell, or if
  // it lands on the start width or the target speed.
  val activeEntry = rose(ltState === LTState.sACTIVE)
  val activeWidth = RegEnable(widthEnabled, 0.U, activeEntry)
  val activeSpeed = RegEnable(speedEnabled, 0.U, activeEntry)
  val linkFell = RegInit(false.B)
  when(activeEntry) {
    linkFell := false.B
  }.elsewhen(linkToRegs.statusChanged && !linkStatusUp) {
    linkFell := true.B
  }
  val hwBwChanged =
    (widthEnabled =/= activeWidth &&
      widthEnabled =/= io.phyStatus.initLinkWidth.asUInt) ||
      (speedEnabled =/= activeSpeed && speedEnabled =/= regsToLink.targetSpeed)
  linkToRegs.bwChanged :=
    activeEntry && linkStatusUp && !linkFell && hwBwChanged

  // Table 9-9 clears both Link Control bits when training ends in success or
  // error. So the error path must clear them too.
  val trainingFailed = io.phyStatus.trainingTimedout ||
    io.phyStatus.fatalTrainingError ||
    (ltState === LTState.sTRAINERROR)

  // sec 4.5.3.5 clears Start once RDI is Active, which is when the LTSM
  // enters sACTIVE. FDI Active is later and may never come (sec 9.5.1.5).
  val phyTrainingComplete = ltState === LTState.sACTIVE

  // sec 9.5.1.5 bit 10. A Start completes only on a training that began in
  // RESET, never on the TRAINERROR it caused or on the Link it found up.
  val startArmed = RegInit(false.B)
  val startDone = startArmed && regsToLink.startTrainingPending &&
    (phyTrainingComplete || trainingFailed)
  when(ltState === LTState.sRESET) {
    startArmed := true.B
  }.elsewhen(startDone || !regsToLink.startTrainingPending) {
    startArmed := false.B
  }
  linkToRegs.trainingDone := startDone
  linkToRegs.retrainDone := retrainDone || trainingFailed

  // ===========================================================================
  // Error escalation
  // ===========================================================================
  val adapterStatePrev = RegInit(FDIState.reset)
  adapterStatePrev := io.adapter.linkState
  val adapterRetrainEntry = (io.adapter.linkState === FDIState.retrain) &&
    (adapterStatePrev =/= FDIState.retrain)

  // Table 9-27 bit 2 covers any LinkError on the RDI. An Adapter cause logs
  // its own bit earlier, so First Fatal still names it.
  val rdiStatePrev = RegInit(RDIState.reset)
  rdiStatePrev := io.adapter.rdiState
  val rdiLinkErrorEntry = (io.adapter.rdiState === RDIState.linkError) &&
    (rdiStatePrev =/= RDIState.linkError)
  val phyFatalErrorEntry = rose(io.adapter.phyFatalError)

  val uncorrSet = Wire(Vec(6, Bool()))
  val corrSet = Wire(Vec(5, Bool()))

  // sec 3.2.1.2 and sec 9.5.3.8 make every Adapter timeout an Adapter Timeout
  // in the Uncorrectable Error Status register, a register access among them.
  uncorrSet(0) := io.adapter.remoteRegAccessTimeout ||
    io.adapter.adapterTimeout.valid

  // Table 9-33 [6:4] encodes only the sideband queues here, so only their
  // drops set Table 9-27 bit 1.
  uncorrSet(1) := io.adapter.sideband.fdiRxQueuesFull ||
    io.adapter.sideband.rdiRxQueuesFull

  // The chip's mainband receive overflow has no Table 9-33 encoding, so it
  // is a Table 9-27 bit 2 data path error.
  uncorrSet(2) := io.adapter.sideband.invalidRoute ||
    io.chip.rxOverflow ||
    io.adapter.sideband.parityErrEvent ||
    rdiLinkErrorEntry ||
    phyFatalErrorEntry
  uncorrSet(3) := io.adapter.sideband.errMsgFatal

  // Table 10-1 treats RDI pl_nferror like a Sideband Non-Fatal {ErrMsg}.
  uncorrSet(4) := io.adapter.sideband.errMsgNonFatal ||
    io.adapter.phyNonFatalError
  uncorrSet(5) := io.adapter.invalidParamExchange

  // Streaming Raw has no CRC or retry and the parity feature is off, so bits 0
  // and 4 stay clear.
  corrSet(0) := false.B
  corrSet(1) := adapterRetrainEntry

  // In Raw Format Table 10-1 uses pl_cerror only for Table 9-30 bit 2. The
  // Protocol Layer logs its own pl_error elsewhere.
  corrSet(2) := io.adapter.phyCorrError
  corrSet(3) := io.adapter.sideband.errMsgCorrectable
  corrSet(4) := false.B

  adapterToRegs.uncorrErrSet := uncorrSet
  adapterToRegs.corrErrSet := corrSet

  adapterToRegs.headerLog1.valid := false.B
  adapterToRegs.headerLog1.bits := 0.U
  adapterToRegs.headerLog1Rearm := false.B

  // The register block latches these on the first Adapter Timeout, so the
  // priority here only decides which reason wins a genuine tie.
  adapterToRegs.headerLog2.timeoutEnc := Mux(
    io.adapter.adapterTimeout.valid,
    io.adapter.adapterTimeout.bits.enc,
    Mux(io.adapter.remoteRegAccessTimeout, 7.U, 0.U)
  )

  // Table 9-33 [6:4] is 011b for FDI and 100b for RDI. The register block
  // keeps the first drop, so a drop on both logs RDI.
  adapterToRegs.headerLog2.rxOverflowEnc := Mux(
    io.adapter.sideband.rdiRxQueuesFull,
    "b100".U,
    Mux(io.adapter.sideband.fdiRxQueuesFull, "b011".U, 0.U)
  )
  adapterToRegs.headerLog2.lsmResponse :=
    Mux(
      io.adapter.adapterTimeout.valid,
      io.adapter.adapterTimeout.bits.lsmResponse,
      0.U
    )

  // Table 9-33 bit 10 names the Adapter LSM that timed out. Only LSM 0 exists
  // in a single stack build.
  adapterToRegs.headerLog2.lsmId := false.B
  adapterToRegs.headerLog2.paramExchSuccess := io.adapter.paramExchSuccess
  adapterToRegs.headerLog2.flitFormat :=
    io.protoStatus.negotiatedFlitFormat.asUInt
  adapterToRegs.advCapAdapter := io.adapter.sideband.advCapAdapter

  // FinCap.Adapter is never sent or received, so nothing writes its log.
  adapterToRegs.finCapAdapter.valid := false.B
  adapterToRegs.finCapAdapter.bits := 0.U

  val severity = regsToAdapter.uncorrSeverity
  val uncorrActive = uncorrSet.asUInt & (~regsToAdapter.uncorrMask).asUInt
  val corrActive = corrSet.asUInt & (~regsToAdapter.corrMask).asUInt

  // sec 9.5.1.8 says the masks have no impact on Link Status, so bits 19 to
  // 21 follow every logged error. The masks gate the FDI pins.
  linkToRegs.corrErr := corrSet.asUInt.orR
  linkToRegs.uncorrFatal := (uncorrSet.asUInt & severity).orR
  linkToRegs.uncorrNonFatal := (uncorrSet.asUInt & (~severity).asUInt).orR
  io.adapter.rawFormatEnable := regsToLink.rawFormatEnable

  // Use an edge. Correctable Error Status is sticky, so a level would hold
  // pl_cerror high. Table 10-3 maps it as a pulse.
  val corrActiveReg = RegNext(corrActive.orR, false.B)
  val corrActivePrevReg = RegNext(corrActiveReg, false.B)
  io.adapter.internalCorrError := corrActiveReg && !corrActivePrevReg
  io.adapter.internalFatalError := (uncorrActive & severity).orR
  io.adapter.internalNonFatalError := (uncorrActive & (~severity).asUInt).orR

  // sec 3.4 and Table 9-27 take the Link down whatever the mask. The mask
  // only stops forwarding to the Protocol Layer (sec 9.5.3.3).
  io.adapter.linkDownError := uncorrSet(0) || uncorrSet(1) ||
    uncorrSet(2) || uncorrSet(5)

  io.adapter.remoteRegAccessThreshold := regsToAdapter.remoteRegAccessThreshold
  io.adapter.linkReset := io.ctrl.linkReset

  // ===========================================================================
  // PHY status and logs
  // ===========================================================================
  val negParam = io.phyStatus.negotiatedPhyParamSettings
  val negClockMode = RegInit(false.B)
  val negClockPhase = RegInit(false.B)
  val negTarr = RegInit(false.B)

  // sec 4.5.3.3.1 uses the partner's Tx swing for Vref. Chapter 9 has no
  // register for it, so it is latched here and must outlive the exchange.
  val negVoltageSwing = RegInit(0.U(5.W))
  val negParamSeen = RegInit(false.B)

  // sec 4.5.1.4 step 7 reports the edges for one cycle, so latch them. Only
  // sRESET clears them (sec 4.5.3.1).
  val partnerEye = RegInit(0.U.asTypeOf(new PartnerEyeEdges))
  val partnerEyeSeen = RegInit(false.B)
  when(io.phyStatus.partnerEyeEdges.valid) {
    partnerEye := io.phyStatus.partnerEyeEdges.bits
    partnerEyeSeen := true.B
  }
  when(ltState === LTState.sRESET) { partnerEyeSeen := false.B }
  when(negParam.valid) {
    negClockMode := negParam.bits.clockMode.asBool

    // The negotiated bit is gated by the data rate, so it cannot show the
    // value the partner advertised.
    negClockPhase := io.phyStatus.remoteClockPhase
    negTarr := negParam.bits.txAdjRuntime.asBool
    negVoltageSwing := negParam.bits.voltageSwing
    negParamSeen := true.B
  }

  val phyStatusOut = phyToRegs.phyStatus

  // sec 9.5.3.24 bit 3 is the local status, and the decision is
  // implementation-specific, so use the per-rate table at freqSel.
  val terminatedAtOperatingRate = MuxLookup(io.phyStatus.freqSel, false.B)(
    SpeedMode.all.zip(SpeedMode.gtsPerCode).map { case (mode, gts) =>
      mode -> params.phyCapability.terminatedAtRate.getOrElse(gts, false).B
    }
  )

  // sec 9.5.3.23 bit 3 can only turn termination off.
  phyStatusOut.rxTerminationStatus :=
    regsToPhy.phyControl.rxTerminationControl && terminatedAtOperatingRate

  // sec 9.5.3.24 bit 4 echoes sec 9.5.3.23 bit 4, which drives the analog.
  // There is no analog readback.
  phyStatusOut.txEqStatus := regsToPhy.phyControl.txEqEnable
  phyStatusOut.clockModeStatus := negClockMode
  phyStatusOut.clockPhaseStatus := negClockPhase
  phyStatusOut.laneReversal := io.phyStatus.doLaneReversal

  // I/Q correction and Tx EQ presets are above 32 GT/s and are not implemented.
  phyStatusOut.iqCorrectionParam := 0.U
  phyStatusOut.eqPresetSetting := 0.U
  phyStatusOut.tarrStatus := negTarr

  val sb = io.phyStatus.sideband

  // The deserializer timeout is a transport fault, so it groups with the local
  // sideband faults. sec 9.5.3.35 bit 9 is for handshakes.
  val sbInternalError = sb.sbInvalidRouteUpperSeen ||
    sb.sbInvalidRouteCurrSeen ||
    sb.sbInvalidRouteLowerSeen ||
    sb.sbRxPriorityQueuesFullSeen ||
    sb.sbUnhandledCurrentLayerMsgSeen ||
    sb.sbParityErrEvent ||
    sb.sbDeserializerTimedoutSeen

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

  // sec 9.5.3.35 bit 8. The LTSM retries a timeout, so only a timeout that
  // becomes a fatal error counts.
  val timeoutEscalated =
    rose(io.phyStatus.fatalTrainingError) && io.phyStatus.trainingTimedout

  for (m <- 0 until n) {
    // Only Module 0 exists. Modules 1 to 3 read zero, but the register shape
    // keeps their offsets (sec 9.5.3.30).
    val isM0 = (m == 0).B
    val elog = phyToRegs.errorLog(m)
    elog.valid := errEvent && isM0
    elog.bits.stateN := stateHist(0)
    elog.bits.stateNm1 := stateHist(1)
    elog.bits.stateNm2 := stateHist(2)
    elog.bits.stateNm3 := stateHist(3)
    elog.bits.laneReversal := io.phyStatus.doLaneReversal
    elog.bits.widthDegrade := io.phyStatus.widthDegraded

    phyToRegs.errLog1Set(m)(0) := timeoutEscalated && isM0

    // sec 9.5.3.35 bit 9 excludes Link Training messages. The only other
    // handshake this design times is sec 10.3.3.8.
    phyToRegs.errLog1Set(m)(1) := sb.sbRdiHandshakeTimedoutSeen && isM0

    // sec 9.5.3.35 bit 10 is the partner's LinkError request on the RDI
    // sideband.
    phyToRegs.errLog1Set(m)(2) := io.phyStatus.remoteLinkErrorRequest && isM0
    phyToRegs.errLog1Set(m)(3) :=
      (io.phyStatus.fatalTrainingError || sbInternalError) && isM0

    // sec 9.5.3.30 uses bits 15:0 for Standard Package. The map is the Rx map,
    // which can differ from the Tx map after a degrade.
    phyToRegs.currentLaneMap(m).valid := io.phyStatus.rxLaneMapCommit && isM0
    phyToRegs.currentLaneMap(m).bits := io.phyStatus.rxLaneMask
  }

  val linkTestBusy = RegInit(false.B)

  // The LTSM clears Busy at step 4b (sec 4.5.3.4.12). The other terms cover a
  // retrain that never reaches 4b, so software would poll Busy forever.
  when(regsToPhy.linkTestStart) {
    linkTestBusy := true.B
  }.elsewhen(
    io.phyStatus.clearRuntimeLinkCtrlBusyBit || retrainDone ||
      io.phyStatus.trainingTimedout || io.phyStatus.fatalTrainingError
  ) {
    linkTestBusy := false.B
  }
  phyToRegs.linkTestBusy := linkTestBusy

  // ===========================================================================
  // Control outputs to the datapath
  // ===========================================================================
  val tsOverride =
    io.regs.vendorToPhy.map(_.tsOverrideEnable).getOrElse(false.B)

  val ts1 = regsToPhy.ts1(0)
  val ts2 = regsToPhy.ts2(0)
  val ts3 = regsToPhy.ts3(0)
  val ts4 = regsToPhy.ts4(0)

  val ltp = io.phyCtrl.linkTrainingParameters
  ltp.dataPattern := ts1(2, 0)
  ltp.validPattern := ts1(5, 3)
  ltp.clockPhase := ts1(9, 6)
  ltp.patternMode := ts1(10)
  ltp.burstCount := ts1(26, 11)
  ltp.idleCount := ts2(15, 0)
  ltp.iterationCount := ts2(31, 16)

  // sec 9.5.3.29 splits [15:4] per-Lane from [31:16] aggregate, and the mode
  // is fixed at per-Lane.
  // Note: Move this source if the mode becomes selectable.
  ltp.maxErrorThreshold := ts4(15, 4)

  ltp.comparisonMode := 0.U

  io.phyCtrl.linkOpParamOverride := tsOverride
  io.phyCtrl.remoteRxTerminated :=
    io.regs.vendorToPhy.map(_.remoteRxTerminated).getOrElse(false.B)
  io.phyCtrl.maxErrorThresholdPerLane := ts4(15, 4)

  // sec 9.5.3.28 Training Setup 3, Module 0 only. The Repair Lane mask of
  // sec 9.5.3.29 [3:0] is not wired, because Standard Package has no Lane
  // repair (sec 5.9.2).
  io.phyCtrl.rxCompareLaneMask := ts3(
    io.phyCtrl.rxCompareLaneMask.getWidth - 1,
    0
  )

  io.phyCtrl.pwrGood := io.ctrl.pwrGood
  io.phyCtrl.retryTrainingAmt := io.ctrl.retryTrainingAmt
  io.phyCtrl.remoteLinkErrorLogged := regsToPhy.remoteLinkErrorLogged

  io.phyCtrl.txEqEnable := regsToPhy.phyControl.txEqEnable

  // sec 9.5.1.5 [5:2] Target Link Width, decoded to the Table 4-9 code. Only
  // x8 narrows, because [5:2] has no x4 encoding. On a x16 module it means
  // the lower eight Lanes.
  val targetWidthIsX8 = regsToLink.targetWidth === 1.U

  // The lower half follows sec 5.7.3.2 but is not a rule. This module still
  // has sixteen wires, so ucieSx8 is not involved.
  io.phyCtrl.targetWidthCode := Mux(
    targetWidthIsX8 && (afeParams.mbLanes > 8).B,
    "b001".U(3.W),
    "b011".U(3.W)
  )

  // sec 9.5.3.25, both directions of the pause handshake.
  io.phyCtrl.phyInitControl := regsToPhy.phyInitControl
  io.phyCtrl.resumeTrainingPending := regsToPhy.resumeTrainingPending
  phyToRegs.trainingPaused := io.phyCtrl.trainingPaused
  phyToRegs.resumeTrainingDone := io.phyCtrl.resumeTrainingDone

  // sec 9.5.3.36 bit 6 only marks the register valid.
  // Link Control bit 11 starts the retrain.
  io.phyCtrl.runtimeLinkCtrlBusyBit := linkTestBusy

  // sec 4.5.3.4.12 step 4a compares this with its value at PHYRETRAIN entry.
  // Busy is not used, since it would block step 4b. Start is excluded
  // (sec 4.5.3.7.1 step 5).
  val rltControlValue = Cat(
    regsToPhy.injectStuckAt,
    regsToPhy.applyLaneRepair.asUInt,
    regsToPhy.laneRepairId.asUInt
  )
  val rltControlAtRetrainEntry = RegInit(0.U(rltControlValue.getWidth.W))
  when(rose(ltState === LTState.sPHYRETRAIN)) {
    rltControlAtRetrainEntry := rltControlValue
  }
  io.phyCtrl.changeInRuntimeLinkCtrlRegsDetected :=
    rltControlAtRetrainEntry =/= rltControlValue
  io.phyCtrl.runtimeRequestForRepair :=
    regsToPhy.applyLaneRepair.reduce(_ || _)

  // Module 0 only, like ts1 to ts4 above.
  io.phyCtrl.runtimeLaneRepairId := regsToPhy.laneRepairId(0)
  io.phyCtrl.injectStuckAt := regsToPhy.injectStuckAt

  // With no vendor block, no MMIO can pin a code, so training sets every code.
  val defaultBridgeConfig = Wire(
    new PhyBridgeConfig(params.phyBridge, params.slots)
  )
  defaultBridgeConfig.holdCodes := false.B

  // TRAIN_TIMING's reset value. A build with no vendor block has no register
  // to read, and zero would measure each code before it settled.
  defaultBridgeConfig.settleCycles := 16.U
  params.phyBridge.knobs.zipWithIndex.foreach { case (knob, k) =>
    defaultBridgeConfig.pinnedEnable(k) := 0.U
    defaultBridgeConfig.pinned(k).foreach(_ := knob.reset.U)
  }
  io.phyCtrl.bridgeConfig := io.regs.vendorToPhy
    .map(_.bridgeConfig)
    .getOrElse(defaultBridgeConfig)

  // With no vendor block no MMIO can drive a job. The tie-off keeps the default
  // of sec 4.5.3.4 running, so the trainer does not wait for a driver.
  io.regs.vendorToPhy match {
    case Some(v) => io.phyCtrl.trainerCtrl := v.trainer
    case None    => VendorTrainerCtrl.tieOff(io.phyCtrl.trainerCtrl)
  }

  val lp = io.phyCtrl.localPhyParamSettings
  lp.valid := true.B

  // The register block makes sec 9.5.3.22 [9:5] firmware-writable, so use the
  // register.
  lp.bits.voltageSwing := regsToPhy.txVswingCode
  lp.bits.maxDataRate := regsToLink.targetSpeed
  lp.bits.clockMode := regsToPhy.phyControl.rxClockModeSelect
  lp.bits.clockPhase := regsToPhy.phyControl.rxClockPhaseSelect

  // sec 4.5.3.3.1 says SPMW is bit 20, but sec 9.5.1.4 says bit 22.
  // Chapter 9 wins.
  lp.bits.ucieSx8 := regsToLink.spmw || regsToPhy.phyControl.forceX8Width
  lp.bits.sbFeatExt := 0.U

  // The register block applies the TARR capability AND (sec 4.5.3.3.1), so
  // every consumer sees the final value.
  lp.bits.txAdjRuntime := regsToPhy.phyControl.tarrEnable
  lp.bits.moduleId := 0.U

  // The Protocol Layer requests LinkReset and Disabled (sec 10.3.3.5 and
  // sec 10.3.3.6). Start waits while a vendor bit is set.
  val vendorD2d = io.regs.vendorToD2d
  val protoLinkReset = vendorD2d.map(_.protoLinkReset).getOrElse(false.B)
  val protoDisable = vendorD2d.map(_.protoDisable).getOrElse(false.B)
  val protoLinkError = vendorD2d.map(_.protoLinkError).getOrElse(false.B)
  val protoDownHeld = protoLinkReset || protoDisable || protoLinkError
  io.protoCtrl.requestActive :=
    regsToLink.startTrainingPending && !protoDownHeld
  io.phyCtrl.swStartLinkTraining :=
    regsToLink.startTrainingPending && !protoDownHeld
  io.protoCtrl.requestRetrain := regsToLink.retrainPending
  io.adapter.swRetrain := regsToLink.retrainPending
  io.protoCtrl.requestLinkReset := protoLinkReset
  io.protoCtrl.requestDisable := protoDisable
  io.protoCtrl.requestLinkError := protoLinkError

  // Table 9-12 bits 0, 2 and 4 enable "Reporting of this error via protocol
  // layer mechanism". The Protocol Layer applies them. Table 10-3 maps the FDI
  // pins from the status registers by mask and severity alone.
  io.protoCtrl.corrProtoReport := regsToLink.corrProtoReport
  io.protoCtrl.nonFatalProtoReport := regsToLink.nonFatalProtoReport
  io.protoCtrl.fatalProtoReport := regsToLink.fatalProtoReport

  // ===========================================================================
  // Vendor block and sideband
  // ===========================================================================
  io.regs.phyToVendor.foreach { v =>
    v.bridgeStatus := io.phyCtrl.bridgeStatus
    v.trainer := io.phyCtrl.trainerStatus
    v.partnerVoltageSwing.valid := negParamSeen
    v.partnerVoltageSwing.bits := negVoltageSwing
    v.partnerEyeEdges.valid := partnerEyeSeen
    v.partnerEyeEdges.bits := partnerEye
  }

  // Table 10-1 leaves pl_error in Raw Format to the Protocol Layer "in an
  // implementation-specific manner", so its log is in the vendor block.
  io.regs.d2dToVendor.foreach(
    _.plErrorReceived := io.protoStatus.errUncorrectable
  )

  io.adapter.mailboxSideband.req.valid := io.regs.mailboxSideband.req.valid
  io.adapter.mailboxSideband.req.bits := io.regs.mailboxSideband.req.bits
  io.regs.mailboxSideband.req.ready := io.adapter.mailboxSideband.req.ready
  io.regs.mailboxSideband.resp.valid := io.adapter.mailboxSideband.resp.valid
  io.regs.mailboxSideband.resp.bits := io.adapter.mailboxSideband.resp.bits
  io.adapter.mailboxSideband.resp.ready := io.regs.mailboxSideband.resp.ready

  io.regs.sidebandRegTarget.req.valid := io.adapter.sidebandRegTarget.req.valid
  io.regs.sidebandRegTarget.req.bits := io.adapter.sidebandRegTarget.req.bits
  io.adapter.sidebandRegTarget.req.ready := io.regs.sidebandRegTarget.req.ready
  io.adapter.sidebandRegTarget.resp.valid := io.regs.sidebandRegTarget.resp.valid
  io.adapter.sidebandRegTarget.resp.bits := io.regs.sidebandRegTarget.resp.bits
  io.regs.sidebandRegTarget.resp.ready := io.adapter.sidebandRegTarget.resp.ready
}

object UcieRegBridge {
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

    block.sidebandRegTarget.req.valid := bridge.sidebandRegTarget.req.valid
    block.sidebandRegTarget.req.bits := bridge.sidebandRegTarget.req.bits
    bridge.sidebandRegTarget.req.ready := block.sidebandRegTarget.req.ready
    bridge.sidebandRegTarget.resp.valid := block.sidebandRegTarget.resp.valid
    bridge.sidebandRegTarget.resp.bits := block.sidebandRegTarget.resp.bits
    block.sidebandRegTarget.resp.ready := bridge.sidebandRegTarget.resp.ready

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
