/*
  The sideband path of the D2D Adapter. It connects the FDI and RDI sideband
  interfaces to the Adapter LSM and to the register access mailbox.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import edu.berkeley.cs.uciedigital.rdi._
import chisel3._
import chisel3.util._
import chisel3.layer.block
import chisel3.layers.Verification
import edu.berkeley.cs.uciedigital.fdi._
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.regs._
import edu.berkeley.cs.uciedigital.sideband._

class D2DSidebandModule(
    val fdiParams: FdiParams,
    val sbParams: SidebandParams,
    val regLocators: Seq[(BigInt, BigInt)] = Seq(
      BigInt(0x1000) -> BigInt(0x2000)
    )
) extends Module {
  require(sbParams.sbNodeMsgWidth == 128)
  require(sbParams.remoteRegAccessTimeoutCycles > 0)
  require(regLocators.size <= 4)
  regLocators.foreach { case (base, size) =>
    // Size is at least 8, so the mailbox limits (size - 8) and (size - 4) are
    // not negative.
    require(base >= 0 && size >= 8 && base + size <= (BigInt(1) << 24))
  }

  val io = IO(new Bundle {
    val sb = new D2DSidebandModuleIO(sbParams)
    val regs = new AdapterSidebandStatusIO
    val mailbox = Flipped(new MailboxToSideband)
    val regTarget = Flipped(new SidebandRegTargetIO)
    val remoteRegAccessThreshold = Input(UInt(4.W))
    val linkReset = Input(Bool())
    val rdiState = Input(RDIState())
    val adapterLinkInvalid = Input(Bool())
    val remoteRegAccessTimeout = Output(Bool())
    val linkErrorRequest = Output(Bool())
    val rdi = new Bundle {
      val plCfg = Input(UInt(fdiParams.ncWidth.W))
      val plCfgVld = Input(Bool())
      val plCfgCrd = Input(Bool())
      val lpCfg = Output(UInt(fdiParams.ncWidth.W))
      val lpCfgVld = Output(Bool())
      val lpCfgCrd = Output(Bool())
      val plWakeAck = Input(Bool())
    }
    val fdi = new Bundle {
      val plCfg = Output(UInt(fdiParams.ncWidth.W))
      val plCfgVld = Output(Bool())
      val plCfgCrd = Output(Bool())
      val lpCfg = Input(UInt(fdiParams.ncWidth.W))
      val lpCfgVld = Input(Bool())
      val lpCfgCrd = Input(Bool())
      val lpClkAck = Input(Bool())
    }
  })

  // ===========================================================================
  // Modules
  // ===========================================================================
  val sidebandChannel = Module(
    new D2DSidebandChannel(
      sbMsgWidth = sbParams.sbNodeMsgWidth,
      fdiNcWidth = fdiParams.ncWidth,
      rdiNcWidth = fdiParams.ncWidth,
      numCredits = sbParams.maxCrd,
      queueDepths = SidebandPriorityQueueDepths()
    )
  )
  val mailbox = Module(
    new SidebandMailboxController(sbParams, regLocators)
  )

  // ===========================================================================
  // Sideband channel
  // ===========================================================================
  sidebandChannel.io.fdi.in.bits := io.fdi.lpCfg
  sidebandChannel.io.fdi.in.valid := io.fdi.lpCfgVld
  io.fdi.plCfgCrd := sidebandChannel.io.fdi.rxCreditReturn
  io.fdi.plCfg := sidebandChannel.io.fdi.out.bits
  io.fdi.plCfgVld := sidebandChannel.io.fdi.out.valid
  sidebandChannel.io.fdi.txCreditReturn := io.fdi.lpCfgCrd
  sidebandChannel.io.fdi.txHold := !io.fdi.lpClkAck

  sidebandChannel.io.rdi.in.bits := io.rdi.plCfg
  sidebandChannel.io.rdi.in.valid := io.rdi.plCfgVld
  io.rdi.lpCfgCrd := sidebandChannel.io.rdi.rxCreditReturn
  io.rdi.lpCfg := sidebandChannel.io.rdi.out.bits
  io.rdi.lpCfgVld := sidebandChannel.io.rdi.out.valid
  sidebandChannel.io.rdi.txCreditReturn := io.rdi.plCfgCrd
  sidebandChannel.io.rdi.txHold := !io.rdi.plWakeAck

  io.regs.parityErrEvent := sidebandChannel.io.layer.status.sbParityErrEvent
  io.regs.fdiRxQueuesFull := sidebandChannel.io.layer.status.fdiRxQueuesFull
  io.regs.rdiRxQueuesFull := sidebandChannel.io.layer.status.rdiRxQueuesFull
  io.regs.invalidRoute := sidebandChannel.io.layer.status.invalidRouteUpper ||
    sidebandChannel.io.layer.status.invalidRouteCurr ||
    sidebandChannel.io.layer.status.invalidRouteLower

  // ===========================================================================
  // RDI state and flush
  // ===========================================================================
  val rdiStatePrev = RegNext(io.rdiState, RDIState.reset)
  val newRdiEpoch =
    rdiStatePrev === RDIState.reset && io.rdiState === RDIState.active
  val linkInvalid = io.adapterLinkInvalid ||
    io.rdiState === RDIState.reset ||
    io.rdiState === RDIState.linkReset ||
    io.rdiState === RDIState.linkError
  val clearEngine = linkInvalid || newRdiEpoch
  val rdiActive = io.rdiState === RDIState.active ||
    io.rdiState === RDIState.activePmNak

  // sec 7.1.3.3 restarts the partner credits when its RDI goes from Reset to
  // Active. That can happen before ours does. So a request that arrives while
  // our RDI is in Reset belongs to the new epoch.
  val enteredReset =
    io.rdiState === RDIState.reset && rdiStatePrev =/= RDIState.reset
  val enteredLinkError =
    io.rdiState === RDIState.linkError && rdiStatePrev =/= RDIState.linkError

  // For this reason the completer flushes only on entry to Reset or
  // LinkError, not on the Reset to Active edge.
  val regCompleterFlush = enteredReset || enteredLinkError
  val regTrafficEligible = rdiActive && !io.linkReset
  sidebandChannel.io.flushRequesterRegAccess := clearEngine
  sidebandChannel.io.flushCompleterRegAccess := regCompleterFlush

  // ===========================================================================
  // Remote register access
  // ===========================================================================
  val rxPacket = sidebandChannel.io.layer.out.bits
  val rxFire = sidebandChannel.io.layer.out.fire
  mailbox.io.mailbox <> io.mailbox
  mailbox.io.regTarget <> io.regTarget
  mailbox.io.remoteRegAccessThreshold := io.remoteRegAccessThreshold
  mailbox.io.linkReset := io.linkReset
  mailbox.io.parityErrEvent := io.regs.parityErrEvent
  mailbox.io.rdiActive := rdiActive
  mailbox.io.clearEngine := clearEngine
  mailbox.io.regCompleterFlush := regCompleterFlush
  mailbox.io.newRdiEpoch := newRdiEpoch
  mailbox.io.regTrafficEligible := regTrafficEligible
  mailbox.io.rxValid := sidebandChannel.io.layer.out.valid
  mailbox.io.rxBits := rxPacket
  sidebandChannel.io.layer.out.ready := mailbox.io.rxReady
  mailbox.io.txCreditAvailable := sidebandChannel.io.rdi.txCreditAvailable
  mailbox.io.txPacketCommit := sidebandChannel.io.rdi.txPacketCommit
  io.remoteRegAccessTimeout := mailbox.io.remoteRegAccessTimeout
  io.linkErrorRequest := mailbox.io.linkErrorRequest

  // ===========================================================================
  // Transmit arbitration
  // ===========================================================================
  // sec 7.1.3.1 says a message must not wait behind Register Access requests.
  // So link management messages from the Adapter LSM have strict priority over
  // both mailbox lanes. The LSM builds its own packets, so none are translated.
  val linkMessageValid = io.sb.sntValid
  val linkMessage = io.sb.sntBits
  val localCreditAvailable = sidebandChannel.io.rdi.txCreditAvailable
  val selectLink = linkMessageValid && localCreditAvailable
  val selectCompletion = !selectLink && mailbox.io.completion.valid
  val selectRequest =
    !selectLink && !selectCompletion && mailbox.io.request.valid

  sidebandChannel.io.layer.in.valid :=
    selectLink || selectCompletion || selectRequest
  sidebandChannel.io.layer.in.bits := Mux(
    selectLink,
    linkMessage,
    Mux(selectCompletion, mailbox.io.completion.bits, mailbox.io.request.bits)
  )
  io.sb.rdy := selectLink && sidebandChannel.io.layer.in.ready
  mailbox.io.completionFire :=
    selectCompletion && sidebandChannel.io.layer.in.ready
  mailbox.io.requestFire :=
    selectRequest && sidebandChannel.io.layer.in.ready

  // ===========================================================================
  // Received messages
  // ===========================================================================
  io.sb.rcvValid := false.B
  io.sb.rcvBits := rxPacket
  io.regs.errMsgFatal := false.B
  io.regs.errMsgNonFatal := false.B
  io.regs.errMsgCorrectable := false.B
  io.regs.advCapAdapter.valid := false.B
  io.regs.advCapAdapter.bits := rxPacket(127, 64)

  when(rxFire) {
    io.regs.errMsgFatal := SBMsgCompare(rxPacket, SBM.ERRMSG_FATAL)
    io.regs.errMsgNonFatal := SBMsgCompare(rxPacket, SBM.ERRMSG_NONFATAL)

    // Table 9-30 bit 3 needs a Device origin encoding in the message info.
    // Table 7-8 defines MsgInfo of {ErrMsg} as Reserved for all three subcodes.
    // So there is no encoding to test.
    io.regs.errMsgCorrectable := SBMsgCompare(rxPacket, SBM.ERRMSG_CORRECTABLE)
    io.regs.advCapAdapter.valid := SBMsgCompare(rxPacket, SBM.ADVCAP_ADAPTER)

    // The LSM gets every received packet. Each LSM part takes only the
    // messages it owns, so a request and a response can arrive in either order.
    io.sb.rcvValid := true.B
  }

  // ===========================================================================
  // Verification
  // ===========================================================================
  block(Verification) {
    // The Adapter consumes and drops an unrecognised message, so this cover
    // makes it visible. PSTP is the likely source. This Adapter never
    // negotiates it.
    val recognised = SBMsgCompare(rxPacket, SBM.ERRMSG_FATAL) ||
      SBMsgCompare(rxPacket, SBM.ERRMSG_NONFATAL) ||
      SBMsgCompare(rxPacket, SBM.ERRMSG_CORRECTABLE) ||
      SBMsgCompare(rxPacket, SBM.ADVCAP_ADAPTER) ||
      SBMsgCompare(rxPacket, SBM.NOP_CRD) ||
      SBMsgCompare(rxPacket, SBM.PARITY_FEATURE_REQ) ||
      SBM.isRemoteRegAccessRequest(rxPacket(4, 0)) ||
      SBM.isRegAccessComplete(rxPacket(4, 0)) ||
      rxPacket(21, 14) === "h03".U || rxPacket(21, 14) === "h04".U
    cover(rxFire && !recognised, "SidebandMessageUnrecognised")
  }
}
