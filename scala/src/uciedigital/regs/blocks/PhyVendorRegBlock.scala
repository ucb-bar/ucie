/*
  The PHY vendor register block (Block ID 3h, sec 9.5.5) for software training.
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.logphy.{
  LogPhyTrainerParams,
  MBTrainOperation,
  SemanticClockPhase,
  TrainingTestType
}
import freechips.rocketchip.regmapper.RegField

object PhyVendorRegBlock {
  // The address arithmetic lives here once, so allocation and block agree.
  val PinEnBase = 0x80

  def codeStride(slots: PhySlots): Int = ((slots.count * 4) + 15) / 16 * 16

  def codeBase(nKnobs: Int): Int =
    ((PinEnBase + nKnobs * 4) + 0xff) / 0x100 * 0x100

  def builtInEnd(bridge: PhyBridgeParams, slots: PhySlots): Int = {
    val n = bridge.knobs.size
    codeBase(n) + n * codeStride(slots)
  }

  // At least 0x1000, and a big table takes the next power of two.
  def blockSize(bridge: PhyBridgeParams, slots: PhySlots): BigInt = {
    val need = BigInt(builtInEnd(bridge, slots))
    val pow2 = BigInt(1) << log2Ceil(need.max(1))
    pow2.max(UcieRegMap.VendorBlockSize)
  }
}

class PhyVendorRegBlock(
    f: RegFieldTypes,
    params: UcieRegParams,
    vIn: PhyToVendor,
    vOut: VendorToPhy
) {
  val BlockId = 0x3
  val blockLength: BigInt =
    PhyVendorRegBlock.blockSize(params.phyBridge, params.slots)

  def customRegs: Seq[(Int, Seq[RegField])] = Seq.empty

  val headerRows: Seq[(Int, Seq[RegField])] =
    VendorBlockHeader.rows(f, params.vendorId, BlockId, blockLength, "PHY")

  // ===========================================================================
  // Sizing and helpers
  // ===========================================================================
  val bridge = params.phyBridge
  val slots = params.slots
  val codeW = bridge.maxCodeWidth
  val nKnobs = bridge.knobs.size
  val PinEnBase = PhyVendorRegBlock.PinEnBase

  val CodeBase: Int = PhyVendorRegBlock.codeBase(nKnobs)

  // One word per slot, rounded to 16. Only the generated map gives addresses.
  val CodeStride = PhyVendorRegBlock.codeStride(slots)

  // Wider than needed, so a write to a missing knob or slot can be refused.
  val KnobSelW = math.max(4, log2Ceil(nKnobs max 2))

  val SlotSelW = 5

  // Four bits, because duplicates only have to outnumber an eight-deep queue.
  val SeqW = vIn.trainer.result.bits.seq.getWidth

  // Derived, so a wider selector cannot overlap the code field unnoticed.
  val CwCodeLsb = KnobSelW + SlotSelW + 1
  val knobSelW = log2Ceil(nKnobs max 2)
  val slotSelW = log2Ceil(slots.count max 2)

  // The custom region follows the knob banks, rounded to 0x100.
  val FirstCustom: Int =
    ((CodeBase + nKnobs * CodeStride) + 0xff) / 0x100 * 0x100

  require(
    BigInt(FirstCustom) <= blockLength,
    s"$nKnobs knobs lay out to 0x${FirstCustom.toHexString} but the block " +
      s"Length is 0x${blockLength.toString(16)}; PhyVendorRegBlock.blockSize " +
      s"and this layout have drifted apart"
  )
  require(
    slots.count <= (1 << SlotSelW),
    s"${slots.count} slots do not fit TRAIN_CODE_WRITE.slot_sel, which is $SlotSelW bits"
  )
  require(
    CwCodeLsb + codeW + 1 <= 32,
    s"a ${codeW}-bit code at bit $CwCodeLsb does not fit TRAIN_CODE_WRITE " +
      s"below the go bit"
  )

  require(
    log2Ceil(nKnobs max 2) <= KnobSelW,
    s"$nKnobs knobs need ${log2Ceil(nKnobs max 2)} bits of knob_sel and the " +
      s"register field is $KnobSelW; widen KnobSelW in both TRAIN_CODE_WRITE " +
      s"and TRAIN_RESULT_LO together, and check they still fit 32 bits"
  )

  // seq tops both result halves, so software compares them for a torn read.
  val resultLoUsed = slots.count + KnobSelW + SeqW
  val resultHiUsed = codeW + SlotSelW + 4 + SeqW
  require(
    resultLoUsed <= 32,
    s"TRAIN_RESULT_LO needs $resultLoUsed bits for ${slots.count} slots of " +
      s"pass_vec, a ${KnobSelW}-bit knob selector and a ${SeqW}-bit seq. That " +
      s"caps the knob table at ${1 << (32 - slots.count - SeqW)} at this link " +
      s"width -- the address map caps nothing any more, so this register is " +
      s"the bound"
  )
  require(
    resultHiUsed <= 32,
    s"TRAIN_RESULT_HI needs $resultHiUsed bits for a ${codeW}-bit code, a " +
      s"${SlotSelW}-bit slot, the four flags and a ${SeqW}-bit seq"
  )
  require(
    3 * codeW + 2 <= 32,
    s"three ${codeW}-bit eye edges plus valid and go overflow TRAIN_EYE"
  )

  // The bus can hold valid, so use the rising edge. It is one cycle late,
  // because the payload registers change a cycle after the write.
  def writePulse(fire: Bool): Bool = {
    val d1 = RegNext(fire, false.B)
    val d2 = RegNext(d1, false.B)
    d1 && !d2
  }

  // A zero-width RegField is illegal, and some widths can be zero.
  def rsvdPTail(used: Int, name: String): Seq[RegField] =
    if (used >= 32) Seq.empty else Seq(f.RsvdP(32 - used, name))
  def rsvdZTail(used: Int, name: String): Seq[RegField] =
    if (used >= 32) Seq.empty else Seq(f.RsvdZ(32 - used, name))

  def padded(field: RegField, width: Int, reservedName: String) =
    if (width >= 32) Seq(field)
    else Seq(field, f.RsvdP(32 - width, reservedName))

  // Set wins over the clear, because a refusal in the clear cycle is new.
  val clearFlags = WireDefault(false.B)
  def sticky(set: Bool): Bool = {
    val r = RegInit(false.B)
    when(set)(r := true.B).elsewhen(clearFlags)(r := false.B)
    r
  }

  val tCtrl = vOut.trainer
  val tSts = vIn.trainer

  // Override writes are refused while codes are frozen (sec 4.5.3.4.5).
  val overrideRefusals = collection.mutable.ArrayBuffer[Bool]()

  // Driven at the bottom, after every override field exists.
  val anyOverrideRefused = WireDefault(false.B)
  val codesMovable = !vIn.bridgeStatus.codesFrozen

  // ===========================================================================
  // LINK_INTEGRATION (0x38) and TRAIN_MODE (0x40)
  // ===========================================================================
  val linkRows: Seq[(Int, Seq[RegField])] = {
    val remoteTerm = f.RW(
      1,
      0,
      "remote_rx_terminated",
      "LINK_INTEGRATION Remote partner's Rx termination (0 unterminated, 1 terminated)"
    )

    // sec 9.5.3.24 bit 3 leaves this to the implementation, so a vendor row.
    vOut.remoteRxTerminated := remoteTerm.reg.asBool

    Seq(
      0x38 -> Seq(
        remoteTerm.field,
        f.RsvdP(31, "link_integration_rsvd_31_1")
      )
    )
  }

  // Sticky, like TRAIN_SUBSTATE_EN and TRAIN_TIMING, because they are policy.
  val swJobsEn = f.RWS(
    1,
    0,
    "train_sw_jobs_en",
    "TRAIN_MODE Software drives the jobs (0: hardware runs sec 4.5.3.4's default and never waits)"
  )
  val autoFinishEn = f.RWS(
    1,
    0,
    "train_auto_finish_en",
    "TRAIN_MODE Finish a sub-state in hardware once the sec 4.5 residency falls below the watermark"
  )
  val holdCodes = f.RWS(
    1,
    0,
    "train_hold_codes",
    "TRAIN_MODE Run no link operation anywhere, sec 4.5.3.4's default included; the handshakes still run"
  )
  val tsOverride = f.RWS(
    1,
    0,
    "train_ts_override_en",
    "TRAIN_MODE Take a link operation's pattern and counts from Training Setup 1/2 rather than sec 4.5.1's per-state constants"
  )
  val irqEn = f.RWS(
    1,
    0,
    "train_irq_en",
    "TRAIN_MODE Raise the training interrupt while a result is waiting to be read"
  )
  val finishWatermark = f.RWS(
    LogPhyTrainerParams.residencyWidth,
    0,
    "train_finish_watermark",
    "TRAIN_MODE Control-clock cycles of margin to keep in front of sec 4.5's deadline"
  )

  vOut.bridgeConfig.holdCodes := holdCodes.reg.asBool
  vOut.tsOverrideEnable := tsOverride.reg.asBool
  tCtrl.swJobsEn := swJobsEn.reg.asBool
  tCtrl.autoFinishEn := autoFinishEn.reg.asBool
  tCtrl.finishWatermark := finishWatermark.reg

  // includeInterruptNode sends this to a diplomatic node or to a raw port.
  val trainingIrq: Bool = irqEn.reg.asBool && (tSts.fifoLevel =/= 0.U)

  // ===========================================================================
  // TRAIN_SUBSTATE_EN (0x44)
  // ===========================================================================
  val nSubstates = MBTrainOperation.table.size

  // Reset enables the sec 4.5.3.4 required sub-states, so power-on conforms.
  val substateReset: BigInt =
    MBTrainOperation.table.zipWithIndex.foldLeft(BigInt(0)) {
      case (acc, (row, i)) =>
        if (row.runsByDefault && row.state != LTSMState.sMBTRAIN_LINKSPEED)
          acc | (BigInt(1) << i)
        else acc
    }
  val linkspeedBit =
    MBTrainOperation.table.indexWhere(_.state == LTSMState.sMBTRAIN_LINKSPEED)
  require(
    linkspeedBit == nSubstates - 1,
    s"MBTRAIN.LINKSPEED must be the last row of MBTrainOperation.table for " +
      s"TRAIN_SUBSTATE_EN's RsvdZ bit to sit at the top, found row $linkspeedBit"
  )

  val substateEn = f.RWS(
    nSubstates - 1,
    substateReset,
    "train_substate_en",
    "TRAIN_SUBSTATE_EN One bit per MBTRAIN sub-state with a sec 4.5.3.4 operation, in MBTrainOperation.table order"
  )

  // LINKSPEED reads 0, so no write can move sec 4.5.3.4.12 step 2's phase.
  tCtrl.substateEn := Cat(0.U(1.W), substateEn.reg)

  // ===========================================================================
  // TRAIN_CODE_WRITE (0x48) and TRAIN_JOB (0x4c)
  // ===========================================================================
  // Not sticky, so no staged request fires into a freshly trained link.
  val cwKnob =
    f.RW(KnobSelW, 0, "train_cw_knob_sel", "TRAIN_CODE_WRITE Knob index")
  val cwSlot =
    f.RW(SlotSelW, 0, "train_cw_slot_sel", "TRAIN_CODE_WRITE Slot index")
  val cwAll = f.RW(
    1,
    0,
    "train_cw_all_slots",
    "TRAIN_CODE_WRITE Write every slot of the knob rather than the one named"
  )
  val cwCode =
    f.RW(codeW, 0, "train_cw_code", "TRAIN_CODE_WRITE Code value")
  val cwGo = f.RWautoClear(
    true.B,
    false.B,
    "train_cw_go",
    "TRAIN_CODE_WRITE Apply the fields above (W1S, self-clearing)"
  )

  val cwFire = writePulse(cwGo.fire)
  val cwInRange =
    (cwKnob.reg < nKnobs.U) && (cwSlot.reg < slots.count.U)
  tCtrl.codeWrite.valid := cwFire && cwInRange

  // Flag a refused write, because the trainer never sees it.
  val cwOutOfRange = cwFire && !cwInRange
  tCtrl.codeWrite.bits.knobSel := cwKnob.reg(knobSelW - 1, 0)
  tCtrl.codeWrite.bits.slotSel := cwSlot.reg(slotSelW - 1, 0)
  tCtrl.codeWrite.bits.allSlots := cwAll.reg.asBool
  tCtrl.codeWrite.bits.code := cwCode.reg

  val jobTestKind = f.RW(
    1,
    0,
    "train_job_test_kind",
    "TRAIN_JOB 0: sec 4.5.1.1/4.5.1.3 point test, 1: sec 4.5.1.2/4.5.1.4 eye width sweep"
  )
  val jobPhase = f.RW(
    2,
    0,
    "train_job_clock_phase",
    "TRAIN_JOB Table 4-5 phase: 0 Center, 1 Left, 2 Right"
  )
  val jobSubstate = f.RW(
    LTSMState().getWidth,
    0,
    "train_job_substate",
    "TRAIN_JOB The sec 9.5.3.34 sub-state code this job was built for; a job naming a different one is refused"
  )

  // Held until taken, because the trainer takes jobs only in one window.
  val jobGo = f.W1SPending(
    tSts.jobTaken,
    "train_job_go",
    "TRAIN_JOB Submit the job above (W1S, clears when hardware takes it)"
  )

  tCtrl.job.valid := jobGo.pending
  tCtrl.job.bits.testKind := Mux(
    jobTestKind.reg.asBool,
    TrainingTestType.EyeWidthSweep,
    TrainingTestType.PointTest
  )
  tCtrl.job.bits.clockPhase := MuxLookup(
    jobPhase.reg,
    SemanticClockPhase.Center
  )(
    Seq(
      1.U -> SemanticClockPhase.Left,
      2.U -> SemanticClockPhase.Right
    )
  )

  // An undefined LTSMState encoding matches no state, so it is refused.
  tCtrl.job.bits.substate := LTSMState.safe(jobSubstate.reg)._1

  // ===========================================================================
  // TRAIN_CTRL (0x50), TRAIN_STS (0x54) and TRAIN_RESIDENCY (0x58)
  // ===========================================================================
  val ctrlFinish = f.RWautoClear(
    true.B,
    false.B,
    "train_ctrl_finish",
    "TRAIN_CTRL Leave this sub-state now, at the codes in force (W1S)"
  )
  val ctrlAbort = f.RWautoClear(
    true.B,
    false.B,
    "train_ctrl_abort",
    "TRAIN_CTRL Drop the job in flight as well, then leave (W1S)"
  )
  val ctrlPop = f.RWautoClear(
    true.B,
    false.B,
    "train_ctrl_fifo_pop",
    "TRAIN_CTRL Discard the result just read (W1S)"
  )
  val ctrlClear = f.RWautoClear(
    true.B,
    false.B,
    "train_ctrl_clear_flags",
    "TRAIN_CTRL Clear the sticky refused/overflow/override_refused flags (W1S)"
  )

  tCtrl.finish := writePulse(ctrlFinish.fire)
  tCtrl.abort := writePulse(ctrlAbort.fire)
  tCtrl.fifoPop := writePulse(ctrlPop.fire)
  clearFlags := writePulse(ctrlClear.fire)

  val refusedFlag = sticky(tSts.refused || cwOutOfRange)
  val overflowFlag = sticky(tSts.overflow)
  val overrideRefusedFlag = sticky(anyOverrideRefused)

  // ===========================================================================
  // TRAIN_EYE (0x68) and TRAIN_TIMING (0x6c)
  // ===========================================================================
  // RWOverride, because a read returns the eye held for this speed.
  val eyeLeft = f.RWOverride(
    codeW,
    0,
    vIn.bridgeStatus.eye.left,
    codesMovable,
    "train_eye_left",
    "TRAIN_EYE Left edge in code steps; reads the eye held for the current speed"
  )
  val eyeCenter = f.RWOverride(
    codeW,
    0,
    vIn.bridgeStatus.eye.center,
    codesMovable,
    "train_eye_center",
    "TRAIN_EYE Centre in code steps"
  )
  val eyeRight = f.RWOverride(
    codeW,
    0,
    vIn.bridgeStatus.eye.right,
    codesMovable,
    "train_eye_right",
    "TRAIN_EYE Right edge in code steps; right minus left is the margin"
  )
  val eyeValid = f.RWOverride(
    1,
    0,
    vIn.bridgeStatus.eye.valid,
    codesMovable,
    "train_eye_valid",
    "TRAIN_EYE Mark this speed's eye as learned"
  )
  val eyeGo = f.RWautoClear(
    true.B,
    false.B,
    "train_eye_go",
    "TRAIN_EYE Publish the fields above (W1S, self-clearing)"
  )
  Seq(eyeLeft, eyeCenter, eyeRight, eyeValid).foreach(
    overrideRefusals += _.refused
  )

  val eyeGoPulse = writePulse(eyeGo.fire)

  // Also gated here, so a refused publish sets the flag.
  val eyePubRefused = eyeGoPulse && !codesMovable
  overrideRefusals += eyePubRefused
  tCtrl.eyePub.valid := eyeGoPulse && codesMovable
  tCtrl.eyePub.bits.left := eyeLeft.reg
  tCtrl.eyePub.bits.center := eyeCenter.reg
  tCtrl.eyePub.bits.right := eyeRight.reg
  tCtrl.eyePub.bits.valid := eyeValid.reg.asBool

  // The only copy, so software can raise it if a DAC settles slowly.
  val settleCycles = f.RWS(
    8,
    16,
    "train_settle_cycles",
    "TRAIN_TIMING Cycles a newly written code is held before an operation may measure it"
  )
  vOut.bridgeConfig.settleCycles := settleCycles.reg

  // ===========================================================================
  // CAL_CTRL (0x70) and CAL_STATUS (0x74)
  // ===========================================================================
  // Software completes the sec 4.5.3.3.2, 4.5.3.4.4 and 4.5.3.4.5 calibrations.
  val swCalDone = f.RWautoClear(
    true.B,
    false.B,
    "cal_sw_done",
    "CAL_CTRL Complete the pending calibration (W1S)"
  )
  tCtrl.swCalDone := writePulse(swCalDone.fire)

  val calRows: Seq[(Int, Seq[RegField])] = Seq(
    0x70 -> Seq(
      swCalDone.field,
      f.RsvdP(31, "cal_ctrl_rsvd_31_1")
    ),
    0x74 -> Seq(
      f.RO(
        1,
        tSts.calPending,
        "cal_pending",
        "CAL_STATUS A calibration has been asked for and is waiting to be answered"
      ),

      // No cal_kind, because TRAIN_STS.substate names the calibration.
      // cal_pending exists because the sub-state starts before the request.
      f.RsvdZ(31, "cal_status_rsvd_31_1")
    ),

    // Chapter 9 has no partner Tx Vswing register, so this is a vendor row.
    0x78 -> Seq(
      f.RO(
        5,
        vIn.partnerVoltageSwing.bits,
        "param_partner_tx_vswing",
        "PARAM_STATUS The partner's advertised Tx voltage swing from {MBINIT.PARAM configuration req} Data[8:4]"
      ),
      f.RsvdZ(2, "param_status_rsvd_6_5"),
      f.RO(
        1,
        vIn.partnerVoltageSwing.valid,
        "param_valid",
        "PARAM_STATUS MBINIT.PARAM has completed, so the field above describes something"
      ),
      f.RsvdZ(24, "param_status_rsvd_31_8")
    ),

    // The partner's sec 4.5.1.4 step 7 report, so it is read-only.
    0x7c -> Seq(
      f.RO(
        8,
        vIn.partnerEyeEdges.bits.left,
        "partner_eye_left",
        "PARTNER_EYE sec 4.5.1.4 step 7 Data[7:0], the partner's reported left edge in its own PI phase code steps"
      ),
      f.RO(
        8,
        vIn.partnerEyeEdges.bits.right,
        "partner_eye_right",
        "PARTNER_EYE sec 4.5.1.4 step 7 Data[15:8]; right minus left is the margin the partner measured"
      ),
      f.RO(
        1,
        vIn.partnerEyeEdges.valid,
        "partner_eye_valid",
        "PARTNER_EYE A sweep has reported since the last teardown, so the edges above describe something"
      ),
      f.RsvdZ(15, "partner_eye_rsvd_31_17")
    )
  )

  // ===========================================================================
  // Per-knob override enables and code banks
  // ===========================================================================
  val knobRows: Seq[(Int, Seq[RegField])] =
    bridge.knobs.zipWithIndex.flatMap { case (knob, k) =>
      val cfg = vOut.bridgeConfig

      // A staged code is inert until pin_en. Both are gated (sec 4.5.3.4.5).
      val pinEn = f.RWGated(
        slots.count,
        0,
        codesMovable,
        s"${knob.name}_pin_en",
        s"Override enable, one bit per slot, for the ${knob.name} codes below"
      )
      cfg.pinnedEnable(k) := pinEn.reg
      overrideRefusals += pinEn.refused

      val codeRows = (0 until (if (knob.perLane) slots.count else 1)).map {
        slot =>
          val pinned = f.RWOverride(
            codeW,
            knob.reset,
            vIn.bridgeStatus.applied(k)(slot),
            codesMovable,
            s"${knob.name}_code_slot$slot",
            s"${knob.name} override value for slot $slot; reads the applied code"
          )
          cfg.pinned(k)(slot) := pinned.reg
          overrideRefusals += pinned.refused
          (CodeBase + k * CodeStride + slot * 4) -> padded(
            pinned.field,
            codeW,
            s"${knob.name}_code_slot${slot}_rsvd"
          )
      }

      // Slots a shared knob does not use still need their config wires driven.
      if (!knob.perLane) {
        for (slot <- 1 until slots.count) cfg.pinned(k)(slot) := knob.reset.U
      }

      Seq(
        (PinEnBase + k * 4) -> padded(
          pinEn.field,
          slots.count,
          s"${knob.name}_pin_en_rsvd"
        )
      ) ++ codeRows
    }

  anyOverrideRefused := overrideRefusals.reduce(_ || _)

  // ===========================================================================
  // The rows themselves
  // ===========================================================================
  val trainerRows: Seq[(Int, Seq[RegField])] = Seq(
    0x40 -> Seq(
      swJobsEn.field,
      autoFinishEn.field,
      irqEn.field,
      holdCodes.field,
      tsOverride.field,
      f.RsvdP(3, "train_mode_rsvd_7_5"),
      finishWatermark.field
    ),
    0x44 -> (Seq(
      substateEn.field,
      f.RsvdZ(1, "train_substate_en_linkspeed")
    ) ++ rsvdPTail(nSubstates, "train_substate_en_rsvd")),
    0x48 -> (Seq(
      cwKnob.field,
      cwSlot.field,
      cwAll.field,
      cwCode.field
    ) ++ rsvdPTail(CwCodeLsb + codeW + 1, "train_code_write_rsvd")
      :+ cwGo.field),
    0x4c -> (Seq(
      jobTestKind.field,
      jobPhase.field,
      jobSubstate.field
    ) ++ rsvdPTail(3 + LTSMState().getWidth + 1, "train_job_rsvd")
      :+ jobGo.field),
    0x50 -> Seq(
      ctrlFinish.field,
      ctrlAbort.field,
      ctrlPop.field,
      ctrlClear.field,
      f.RsvdP(28, "train_ctrl_rsvd_31_4")
    ),
    0x54 -> Seq(
      f.RO(1, tSts.jobBusy, "train_sts_job_busy", "An operation is in flight"),
      f.RO(
        1,
        tSts.waitingForJob,
        "train_sts_waiting_for_job",
        "Hardware is idle in an enabled sub-state and it is software's turn"
      ),
      f.RO(
        1,
        tSts.codeSettling,
        "train_sts_code_settling",
        "A code write has not settled, so an operation asked for now would wait"
      ),
      f.RO(
        1,
        refusedFlag,
        "train_sts_refused",
        "Sticky: the last job or code write was rejected"
      ),
      f.RO(
        1,
        overflowFlag,
        "train_sts_overflow",
        "Sticky: a partner result was dropped with the queue full"
      ),
      f.RO(
        1,
        overrideRefusedFlag,
        "train_sts_override_refused",
        "Sticky: an override or eye write was rejected while the codes were frozen"
      ),
      f.RO(
        tSts.fifoLevel.getWidth,
        tSts.fifoLevel,
        "train_sts_fifo_level",
        "Results waiting to be read"
      ),
      f.RO(
        LTSMState().getWidth,
        tSts.substate.asUInt,
        "train_sts_substate",
        "sec 9.5.3.34's code for the sub-state the LTSM is in"
      ),
      f.RsvdZ(
        32 - (6 + tSts.fifoLevel.getWidth + LTSMState().getWidth),
        "train_sts_rsvd"
      )
    ),
    0x58 -> Seq(
      f.RO(
        LogPhyTrainerParams.residencyWidth,
        tSts.residencyLeft,
        "train_residency_left",
        "sec 4.5's per-sub-state deadline counting down in control-clock cycles"
      ),
      f.RsvdZ(32 - LogPhyTrainerParams.residencyWidth, "train_residency_rsvd")
    ),

    // An aligned pair, with seq in both halves to detect a torn 64-bit read.
    0x60 -> (Seq(
      f.RO(
        slots.count,
        tSts.result.bits.passVec,
        "train_result_pass_vec",
        "One bit per slot; at x16 bit 16 is Valid, 17 Track, 18/19 the clock pair"
      ),
      f.RO(
        KnobSelW,
        tSts.result.bits.knobSel,
        "train_result_knob_sel",
        "Which knob the code in the high half belongs to"
      )
    ) ++ rsvdZTail(resultLoUsed, "train_result_lo_rsvd") :+ f.RO(
      SeqW,
      tSts.result.bits.seq,
      "train_result_seq_lo",
      "Sequence number, repeated in the high half as the seqlock"
    )),
    0x64 -> (Seq(
      f.RO(
        codeW,
        tSts.result.bits.code,
        "train_result_code",
        "The code in force when this was measured"
      ),
      f.RO(
        SlotSelW,
        tSts.result.bits.slotSel,
        "train_result_slot_sel",
        "The slot that code was written to"
      ),
      f.RO(
        1,
        tSts.result.bits.aggregatePass,
        "train_result_aggregate_pass",
        "Every slot passed"
      ),
      f.RO(
        1,
        tSts.result.bits.isRemote,
        "train_result_is_remote",
        "The partner measured this about our transmitter"
      ),
      f.RO(
        1,
        tSts.result.bits.codeValid,
        "train_result_code_valid",
        "A code was written in this sub-state; otherwise the code field describes nothing"
      ),
      f.RO(
        1,
        tSts.result.valid,
        "train_result_valid",
        "There is an entry to read at all"
      )
    ) ++ rsvdZTail(resultHiUsed, "train_result_hi_rsvd") :+ f.RO(
      SeqW,
      tSts.result.bits.seq,
      "train_result_seq_hi",
      "Sequence number, repeated from the low half as the seqlock"
    )),
    0x68 -> (Seq(
      eyeLeft.field,
      eyeCenter.field,
      eyeRight.field,
      eyeValid.field
    ) ++ rsvdPTail(3 * codeW + 2, "train_eye_rsvd") :+ eyeGo.field),
    0x6c -> Seq(
      settleCycles.field,
      f.RsvdP(24, "train_timing_rsvd_31_8")
    )
  )

  def entries(base: BigInt): Seq[RegField.Map] = {
    val builtIn = headerRows ++ linkRows ++ trainerRows ++ calRows ++ knobRows
    val occupied = builtIn.map(_._1).toSet

    // Fixed rows stay below PinEnBase. This names the region that overflows.
    val fixedRows = headerRows ++ linkRows ++ trainerRows ++ calRows
    require(
      fixedRows.forall(_._1 < PhyVendorRegBlock.PinEnBase),
      s"PHY vendor fixed registers must fit below PinEnBase " +
        f"(0x${PhyVendorRegBlock.PinEnBase}%x); " +
        s"raise PinEnBase and the code banks with it. Offending: " +
        fixedRows
          .filter(_._1 >= PhyVendorRegBlock.PinEnBase)
          .map(r => f"0x${r._1}%x")
          .mkString(", ")
    )
    require(
      builtIn.map(_._1).distinct.size == builtIn.size,
      s"PHY vendor built-in offsets must be distinct, got " +
        s"${builtIn.map(_._1.toHexString).mkString(", ")}"
    )
    VendorBlockHeader.requireCustomRegs(
      "PHY vendor",
      customRegs.map(_._1),
      FirstCustom,
      blockLength,
      occupied
    )

    (builtIn ++ customRegs).map { case (off, fields) =>
      (base + off).toInt -> fields
    }
  }
}
