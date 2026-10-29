/*
  The D2D Adapter's sideband register access mailbox (sec 7.1.2.1, sec 7.1.3).
  It answers the remote die's register accesses, and sends the accesses that
  software makes through the sec 9.5.1 mailbox registers.
 */

package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.regs._
import edu.berkeley.cs.uciedigital.sideband._

class SidebandMailboxController(
    val sbParams: SidebandParams,
    val regLocators: Seq[(BigInt, BigInt)]
) extends Module {
  require(sbParams.sbNodeMsgWidth == 128)
  require(sbParams.remoteRegAccessTimeoutCycles > 0)
  require(regLocators.size <= 4)
  regLocators.foreach { case (base, size) =>
    // A size of at least 8 keeps the limits size - 8 and size - 4 positive.
    require(base >= 0 && size >= 8 && base + size <= (BigInt(1) << 24))
  }

  val io = IO(new SidebandMailboxControllerIO(sbParams))

  // ===========================================================================
  // Receive decode
  // ===========================================================================
  val rxPacket = io.rxBits
  val rxOpcode = rxPacket(4, 0)
  val rxEp = rxPacket(5)
  val rxBe = rxPacket(21, 14)
  val rxTag = rxPacket(26, 22)
  val rxAddr = Cat(rxPacket(55, 40), rxPacket(39, 32))
  val rxStatus = rxPacket(34, 32)
  val rxCr = rxPacket(61)
  val rxRemoteRequest = rxPacket(58) &&
    SBM.isRemoteRegAccessRequest(rxOpcode)

  // Tables 7-2 to 7-4 let only the Protocol Layer send a local register access
  // to this Adapter. So a clear dstid[2] means the request came on FDI.
  val rxFdiRequest = !rxPacket(58) && SBM.isRegAccessRequest(rxOpcode)
  val rxCompletion = SBM.isRegAccessComplete(rxOpcode)

  val isMem64 = rxOpcode === SBMsgOpcode.MemoryRead_64b.asUInt ||
    rxOpcode === SBMsgOpcode.MemoryWrite_64b.asUInt
  val isCfg32 = rxOpcode === SBMsgOpcode.ConfigurationRead_32b.asUInt ||
    rxOpcode === SBMsgOpcode.ConfigurationWrite_32b.asUInt
  val isCfg64 = rxOpcode === SBMsgOpcode.ConfigurationRead_64b.asUInt ||
    rxOpcode === SBMsgOpcode.ConfigurationWrite_64b.asUInt
  val rxIs64 = isMem64 || isCfg64
  val rxWrite = rxOpcode === SBMsgOpcode.MemoryWrite_32b.asUInt ||
    rxOpcode === SBMsgOpcode.MemoryWrite_64b.asUInt ||
    rxOpcode === SBMsgOpcode.ConfigurationWrite_32b.asUInt ||
    rxOpcode === SBMsgOpcode.ConfigurationWrite_64b.asUInt
  val aligned = Mux(rxIs64, rxAddr(2, 0) === 0.U, rxAddr(1, 0) === 0.U)
  val beValid = rxIs64 || rxBe(7, 4) === 0.U
  val cfgAddressValid =
    rxAddr(23, 20) === 0.U && rxAddr(19, 12) === 0.U

  val locatorValid = WireDefault(false.B)
  val locatorBase = WireDefault(0.U(24.W))

  // offset <= size - width needs no adder, because the limit is a constant.
  val locatorLimit64 = WireDefault(0.U(24.W))
  val locatorLimit32 = WireDefault(0.U(24.W))
  for (((base, size), index) <- regLocators.zipWithIndex) {
    when(rxAddr(23, 20) === index.U) {
      locatorValid := true.B
      locatorBase := base.U
      locatorLimit64 := (size - 8).U
      locatorLimit32 := (size - 4).U
    }
  }
  val memoryAddressValid = locatorValid &&
    (rxAddr(19, 0) <= Mux(rxIs64, locatorLimit64, locatorLimit32))
  val mappedOffset = Mux(
    isCfg32 || isCfg64,
    Cat(0.U(12.W), rxAddr(11, 0)),
    locatorBase + rxAddr(19, 0)
  )
  val requestAddressValid = Mux(
    isCfg32 || isCfg64,
    cfgAddressValid,
    memoryAddressValid
  )
  val requestReservedZero = rxPacket(60, 59) === 0.U &&
    rxPacket(28, 27) === 0.U && rxPacket(13, 6) === 0.U

  // sec 7.1 lets direct access to a remote die come from its Adapter or its
  // Physical Layer. These are srcid 001b and 010b in Table 7-4.
  val requesterValid = rxPacket(31, 29) === 1.U || rxPacket(31, 29) === 2.U

  // Table 7-7 requires UR for every request with EP set.
  val requestValid = aligned && beValid && requestAddressValid &&
    requesterValid && requestReservedZero && !rxEp

  // ===========================================================================
  // Completer contexts
  // ===========================================================================
  // sec 7.1.3.3 limits the partner to four outstanding register accesses.
  val contextCount = 4
  val ctxValid = RegInit(VecInit(Seq.fill(contextCount)(false.B)))
  val ctxMapperPending = RegInit(VecInit(Seq.fill(contextCount)(false.B)))
  val ctxCompletionReady = RegInit(VecInit(Seq.fill(contextCount)(false.B)))
  val ctxWrite = Reg(Vec(contextCount, Bool()))
  val ctxIs64 = Reg(Vec(contextCount, Bool()))
  val ctxOffset = Reg(Vec(contextCount, UInt(24.W)))
  val ctxBe = Reg(Vec(contextCount, UInt(8.W)))
  val ctxWdata = Reg(Vec(contextCount, UInt(64.W)))
  val ctxTag = Reg(Vec(contextCount, UInt(5.W)))
  val ctxHeader = Reg(Vec(contextCount, UInt(64.W)))
  val ctxStatus = Reg(Vec(contextCount, UInt(3.W)))
  val ctxRdata = Reg(Vec(contextCount, UInt(64.W)))
  val fdiRequestValid = RegInit(false.B)
  val fdiRequestHeader = Reg(UInt(64.W))

  val freeMask = VecInit(ctxValid.map(v => !v)).asUInt
  val hasFreeContext = freeMask.orR
  val allocateContext = PriorityEncoder(freeMask)
  io.rxReady := (!rxRemoteRequest || hasFreeContext || !io.rdiActive) &&
    !(rxFdiRequest && fdiRequestValid)
  val rxFire = io.rxValid && io.rxReady

  when(rxFire && rxRemoteRequest && hasFreeContext) {
    ctxValid(allocateContext) := true.B
    ctxMapperPending(allocateContext) := requestValid
    ctxCompletionReady(allocateContext) := !requestValid
    ctxWrite(allocateContext) := rxWrite
    ctxIs64(allocateContext) := rxIs64
    ctxOffset(allocateContext) := mappedOffset
    ctxBe(allocateContext) := rxBe
    ctxWdata(allocateContext) := rxPacket(127, 64)
    ctxTag(allocateContext) := rxTag
    ctxHeader(allocateContext) := rxPacket(63, 0)
    ctxStatus(allocateContext) := SBM.UR
    ctxRdata(allocateContext) := 0.U
  }

  // sec 7.1.1 gives every register access a completion. Registers are reached
  // over MMIO, so only an FDI access gets a UR (Table 7-7). The UR does not use
  // the link, so no RDI state delays it.
  when(io.completionFire && fdiRequestValid) {
    fdiRequestValid := false.B
  }.elsewhen(rxFire && rxFdiRequest) {
    fdiRequestValid := true.B
    fdiRequestHeader := rxPacket(63, 0)
  }

  // ===========================================================================
  // Register mapper
  // ===========================================================================
  // No local access timeout (sec 9.5.3.8 encoding 0101b) is needed, because the
  // wait has a bound. UcieSharedRegMapper gates req.ready only on a queue this
  // module drains, and its RRArbiter does not starve it.
  val mapperPendingMask = VecInit(
    (0 until contextCount).map(i => ctxValid(i) && ctxMapperPending(i))
  ).asUInt
  val mapperNext = RegInit(0.U(log2Ceil(contextCount).W))
  val mapperSelect = WireDefault(mapperNext)
  for (offset <- (0 until contextCount).reverse) {
    val candidate = (mapperNext + offset.U)(log2Ceil(contextCount) - 1, 0)
    when(mapperPendingMask(candidate)) {
      mapperSelect := candidate
    }
  }
  val mapperBusy = RegInit(false.B)
  val discardMapperResponse = RegInit(false.B)
  val mapperContext = Reg(UInt(log2Ceil(contextCount).W))
  val mapperBypassCount = RegInit(
    VecInit(Seq.fill(contextCount)(0.U(log2Ceil(contextCount).W)))
  )

  io.regTarget.req.valid := mapperPendingMask.orR && !mapperBusy &&
    !discardMapperResponse && !io.regCompleterFlush && !io.linkReset
  io.regTarget.req.bits.write := ctxWrite(mapperSelect)
  io.regTarget.req.bits.is64 := ctxIs64(mapperSelect)
  io.regTarget.req.bits.logicalOffset := ctxOffset(mapperSelect)
  io.regTarget.req.bits.be := ctxBe(mapperSelect)
  io.regTarget.req.bits.wdata := ctxWdata(mapperSelect)
  io.regTarget.resp.ready := mapperBusy || discardMapperResponse

  when(io.regTarget.req.fire) {
    mapperBusy := true.B
    mapperContext := mapperSelect
    mapperNext := mapperSelect + 1.U
    ctxMapperPending(mapperSelect) := false.B
  }
  for (i <- 0 until contextCount) {
    when(
      io.regCompleterFlush || !mapperPendingMask(i) ||
        (io.regTarget.req.fire && mapperSelect === i.U)
    ) {
      mapperBypassCount(i) := 0.U
    }.elsewhen(io.regTarget.req.fire) {
      assert(mapperBypassCount(i) < (contextCount - 1).U)
      mapperBypassCount(i) := mapperBypassCount(i) + 1.U
    }
  }
  when(io.regTarget.resp.fire) {
    when(mapperBusy) {
      assert(ctxValid(mapperContext) && !ctxCompletionReady(mapperContext))
      mapperBusy := false.B
      ctxCompletionReady(mapperContext) := true.B
      ctxStatus(mapperContext) := Mux(
        io.regTarget.resp.bits.internalError,
        SBM.CA,
        Mux(io.regTarget.resp.bits.unsupported, SBM.UR, SBM.SC)
      )
      ctxRdata(mapperContext) := io.regTarget.resp.bits.rdata
    }.otherwise {
      assert(discardMapperResponse)
      discardMapperResponse := false.B
    }
  }
  assert(!io.regTarget.resp.valid || mapperBusy || discardMapperResponse)

  // ===========================================================================
  // Completion messages
  // ===========================================================================
  val completionMask = VecInit(
    (0 until contextCount).map(i => ctxValid(i) && ctxCompletionReady(i))
  ).asUInt
  val completionSelect = PriorityEncoder(completionMask)
  val completionStatus = ctxStatus(completionSelect)
  val completionOpcode = Mux(
    completionStatus =/= SBM.SC,
    SBMsgOpcode.CompletionWith64bData.asUInt,
    Mux(
      ctxWrite(completionSelect),
      SBMsgOpcode.CompletionWithoutData.asUInt,
      Mux(
        ctxIs64(completionSelect),
        SBMsgOpcode.CompletionWith64bData.asUInt,
        SBMsgOpcode.CompletionWith32bData.asUInt
      )
    )
  )
  val completionReadData = Mux(
    ctxIs64(completionSelect),
    ctxRdata(completionSelect),
    Cat(0.U(32.W), ctxRdata(completionSelect)(31, 0))
  )
  val completionData = Mux(
    completionStatus =/= SBM.SC,
    ctxHeader(completionSelect),
    completionReadData
  )
  val completionBase = MixedVecInit(
    completionOpcode,
    ctxBe(completionSelect),
    Cat(0.U(5.W), completionStatus)
  )
  val completionMessage = SBMsgCreate(
    completionBase,
    "b001".U,
    "b101".U,
    0.U(16.W),
    completionData,
    true.B,
    ctxTag(completionSelect),
    false.B
  )

  // Table 7-2 reserves srcid and dstid on a completion over FDI. A zero dstid
  // is what SidebandSwitch sends to the Protocol Layer. Cr stays clear, because
  // Table 7-7 keeps it off the FDI credit loop.
  val fdiCompletionMessage = SBMsgCreate(
    MixedVecInit(
      SBMsgOpcode.CompletionWith64bData.asUInt,
      fdiRequestHeader(21, 14),
      Cat(0.U(5.W), SBM.UR)
    ),
    0.U(3.W),
    0.U(3.W),
    0.U(16.W),
    fdiRequestHeader,
    false.B,
    fdiRequestHeader(26, 22),
    false.B
  )

  // ===========================================================================
  // Requester mailbox
  // ===========================================================================
  val requesterState = RegInit(MailboxRequesterState.idle)
  val requestCanceled = RegInit(false.B)
  val nextTag = RegInit(0.U(5.W))
  val requestOpcode = Reg(UInt(5.W))
  val requestBe = Reg(UInt(8.W))
  val requestAddr = Reg(UInt(24.W))
  val requestWdata = Reg(UInt(64.W))
  val requestTag = Reg(UInt(5.W))
  val requestIs64 = Reg(Bool())
  val requestIsRead = Reg(Bool())
  val responseStatus = Reg(UInt(2.W))
  val responseRdata = Reg(UInt(64.W))
  val responseHeader = Reg(UInt(64.W))
  val responseHeaderValid = RegInit(false.B)

  val mbOpcode = io.mailbox.req.bits.opcode
  val mbIs64 = mbOpcode === SBMsgOpcode.MemoryRead_64b.asUInt ||
    mbOpcode === SBMsgOpcode.MemoryWrite_64b.asUInt ||
    mbOpcode === SBMsgOpcode.ConfigurationRead_64b.asUInt ||
    mbOpcode === SBMsgOpcode.ConfigurationWrite_64b.asUInt
  val mbIsRead = mbOpcode === SBMsgOpcode.MemoryRead_32b.asUInt ||
    mbOpcode === SBMsgOpcode.MemoryRead_64b.asUInt ||
    mbOpcode === SBMsgOpcode.ConfigurationRead_32b.asUInt ||
    mbOpcode === SBMsgOpcode.ConfigurationRead_64b.asUInt
  val mbIsConfig = mbOpcode === SBMsgOpcode.ConfigurationRead_32b.asUInt ||
    mbOpcode === SBMsgOpcode.ConfigurationWrite_32b.asUInt ||
    mbOpcode === SBMsgOpcode.ConfigurationRead_64b.asUInt ||
    mbOpcode === SBMsgOpcode.ConfigurationWrite_64b.asUInt
  val mbAddressValid = Mux(
    mbIs64,
    io.mailbox.req.bits.addr(2, 0) === 0.U,
    io.mailbox.req.bits.addr(1, 0) === 0.U
  )
  val mbLocatorValid = Mux(
    mbIsConfig,
    io.mailbox.req.bits.addr(23, 20) === 0.U && io.mailbox.req.bits
      .addr(19, 12) === 0.U,
    io.mailbox.req.bits.addr(23, 20) <= 3.U
  )
  val mbRequestValid = SBM.isRemoteRegAccessRequest(
    mbOpcode
  ) && mbAddressValid && mbLocatorValid &&
    (mbIs64 || io.mailbox.req.bits.be(7, 4) === 0.U)

  io.mailbox.req.ready := requesterState === MailboxRequesterState.idle &&
    !io.linkReset && !io.parityErrEvent && !io.newRdiEpoch
  io.mailbox.resp.valid := requesterState === MailboxRequesterState.response &&
    !io.linkReset
  io.mailbox.resp.bits.status := responseStatus
  io.mailbox.resp.bits.rdata := responseRdata
  io.mailbox.resp.bits.header := responseHeader
  io.mailbox.resp.bits.headerValid := responseHeaderValid

  when(io.mailbox.req.fire) {
    requestCanceled := false.B
    requestOpcode := mbOpcode
    requestBe := io.mailbox.req.bits.be
    requestAddr := io.mailbox.req.bits.addr
    requestWdata := io.mailbox.req.bits.wdata
    requestTag := nextTag
    requestIs64 := mbIs64
    requestIsRead := mbIsRead
    when(mbRequestValid) {
      requesterState := MailboxRequesterState.offer
    }.otherwise {
      responseStatus := 1.U
      responseRdata := 0.U
      responseHeader := 0.U
      responseHeaderValid := false.B
      requesterState := MailboxRequesterState.response
    }
  }
  when(io.mailbox.resp.fire) {
    requesterState := MailboxRequesterState.idle
    requestCanceled := false.B
  }

  val requestBase = MixedVecInit(requestOpcode, requestBe, requestAddr(7, 0))
  val requestPayload = Mux(
    requestIs64,
    requestWdata,
    Cat(0.U(32.W), requestWdata(31, 0))
  )
  val requestMessage = SBMsgCreate(
    requestBase,
    "b001".U,
    "b100".U,
    requestAddr(23, 8),
    requestPayload,
    false.B,
    requestTag,
    false.B
  )
  val requestHeader = Cat(
    Mux(requestIsRead, false.B, requestPayload.xorR),
    requestMessage(61, 0).xorR,
    requestMessage(61, 0)
  )

  // Captured at commit, so the two wide xor trees end at a flop and not at the
  // completion compare or the requester FSM. Table 7-7 has UR and CA echo the
  // request header, so it must stay stable only while one is outstanding.
  val requestHeaderReg = Reg(UInt(64.W))
  val e2eCredits = RegInit(4.U(3.W))
  val requestCanSend = requesterState === MailboxRequesterState.offer &&
    e2eCredits =/= 0.U && io.txCreditAvailable && io.regTrafficEligible

  // ===========================================================================
  // Transmit arbitration and commit
  // ===========================================================================
  // Completions go before requests. sec 7.1.3.1 forbids a credit check before a
  // Completion, and a request must not pass a Completion that is owed.
  io.completion.valid := fdiRequestValid ||
    (io.rdiActive && completionMask.orR)
  io.completion.bits :=
    Mux(fdiRequestValid, fdiCompletionMessage, completionMessage)
  io.request.valid :=
    !io.clearEngine && !completionMask.orR && requestCanSend
  io.request.bits := requestMessage

  when(io.completionFire && !fdiRequestValid) {
    ctxValid(completionSelect) := false.B
    ctxCompletionReady(completionSelect) := false.B
  }
  when(io.requestFire) {
    requesterState := MailboxRequesterState.awaitingCommit
  }

  val committedPacket = io.txPacketCommit.bits

  // Tag and opcode are enough. Link messages and completions carry other
  // opcodes, so only our own register access request can match.
  val requestCommitted = io.txPacketCommit.valid &&
    committedPacket(26, 22) === requestTag &&
    committedPacket(4, 0) === requestOpcode &&
    (requesterState === MailboxRequesterState.awaitingCommit ||
      (requesterState === MailboxRequesterState.offer && io.requestFire))

  val timeoutWidth = log2Ceil(
    math.max(2, sbParams.remoteRegAccessTimeoutCycles)
  )
  val timeoutCounter = RegInit(0.U(timeoutWidth.W))
  val timeoutStreak = RegInit(0.U(4.W))
  val timeoutLast = (sbParams.remoteRegAccessTimeoutCycles - 1).U

  when(requestCommitted) {
    assert(e2eCredits =/= 0.U)
    requesterState := MailboxRequesterState.outstanding
    requestHeaderReg := requestHeader
    nextTag := nextTag + 1.U
    timeoutCounter := 0.U
  }

  // ===========================================================================
  // Completion matching
  // ===========================================================================
  val completionReservedZero = rxPacket(60, 59) === 0.U &&
    rxPacket(55, 35) === 0.U && rxPacket(28, 27) === 0.U &&
    rxPacket(13, 6) === 0.U
  val completionObserved = io.rxValid && rxCompletion
  val matchingCompletion = completionObserved &&
    requesterState === MailboxRequesterState.outstanding &&
    rxTag === requestTag && rxBe === requestBe &&
    rxPacket(31, 29) === 1.U && rxPacket(58, 56) === 5.U &&
    completionReservedZero
  val expectedScOpcode = Mux(
    !requestIsRead,
    SBMsgOpcode.CompletionWithoutData.asUInt,
    Mux(
      requestIs64,
      SBMsgOpcode.CompletionWith64bData.asUInt,
      SBMsgOpcode.CompletionWith32bData.asUInt
    )
  )
  val scCompletion = matchingCompletion && rxStatus === SBM.SC &&
    rxOpcode === expectedScOpcode
  val echoedRequestHeader = rxPacket(127, 64) === requestHeaderReg
  val urCompletion = matchingCompletion && rxStatus === SBM.UR &&
    rxOpcode === SBMsgOpcode.CompletionWith64bData.asUInt &&
    echoedRequestHeader

  // Table 7-7 answers a request that timed out at the completer with a UR that
  // has no data. So there is no header to echo or log.
  val urTimeoutCompletion = matchingCompletion && rxStatus === SBM.UR &&
    rxOpcode === SBMsgOpcode.CompletionWithoutData.asUInt
  val caCompletion = matchingCompletion && rxStatus === SBM.CA &&
    rxOpcode === SBMsgOpcode.CompletionWith64bData.asUInt &&
    echoedRequestHeader
  val stallCompletion =
    matchingCompletion && rxStatus === SBM.Stall && rxOpcode === SBMsgOpcode.CompletionWithoutData.asUInt
  val terminalCompletion =
    scCompletion || urCompletion || urTimeoutCompletion || caCompletion
  val timeoutExpired = timeoutCounter === timeoutLast
  val requestTimeout = !stallCompletion && timeoutExpired
  val nextTimeoutStreak = Mux(timeoutStreak === 15.U, 15.U, timeoutStreak + 1.U)
  val timeoutEscalates = requesterState === MailboxRequesterState.outstanding &&
    !terminalCompletion && !stallCompletion && timeoutExpired &&
    nextTimeoutStreak >= io.remoteRegAccessThreshold
  io.remoteRegAccessTimeout := timeoutEscalates
  io.linkErrorRequest := timeoutEscalates

  // ===========================================================================
  // Timeouts and responses
  // ===========================================================================
  when(io.mailbox.req.fire) { timeoutCounter := 0.U }

  // The skid buffer can take a packet that the node never commits, when link
  // credits run out. The request then waits in awaitingCommit until a link
  // reset. The commit cycle is excluded, because requestCommitted resets it.
  val commitStalled = requesterState === MailboxRequesterState.awaitingCommit &&
    !requestCommitted

  // A request stalled in this module must not hold the trigger forever. This
  // is not the outstanding timeout. It must not change timeoutStreak, because
  // Table 9-33 counts only accesses that reached the wire.
  val offerStalled = (requesterState === MailboxRequesterState.offer &&
    !(io.requestFire)) || commitStalled
  when(offerStalled) {
    when(timeoutExpired) {
      responseStatus := 1.U
      responseRdata := 0.U
      responseHeader := 0.U
      responseHeaderValid := false.B
      requesterState := MailboxRequesterState.response
      timeoutCounter := 0.U

      // A request abandoned in awaitingCommit can be on the wire already, so
      // retire its tag. Table 7-5 needs tags unique only among outstanding.
      when(commitStalled) {
        nextTag := nextTag + 1.U
      }
    }.otherwise {
      timeoutCounter := timeoutCounter + 1.U
    }
  }

  when(requesterState === MailboxRequesterState.outstanding) {
    when(terminalCompletion) {
      responseStatus := Mux(scCompletion, 3.U, Mux(caCompletion, 0.U, 1.U))
      responseRdata := Mux(
        scCompletion && requestIsRead,
        Mux(requestIs64, rxPacket(127, 64), Cat(0.U(32.W), rxPacket(95, 64))),
        0.U
      )
      responseHeader := Mux(
        urCompletion || caCompletion,
        rxPacket(127, 64),
        0.U
      )
      responseHeaderValid := urCompletion || caCompletion
      requesterState := Mux(
        requestCanceled,
        MailboxRequesterState.idle,
        MailboxRequesterState.response
      )
      requestCanceled := false.B
      timeoutCounter := 0.U
      timeoutStreak := 0.U
    }.elsewhen(stallCompletion) {
      timeoutCounter := 0.U
    }.elsewhen(timeoutExpired) {
      timeoutStreak := nextTimeoutStreak

      // Table 7-7 returns UR below the programmed threshold and logs an error
      // at or above it. CA is the only other error encoding that Table 9-20
      // defines.
      responseStatus := Mux(timeoutEscalates, 0.U, 1.U)
      responseRdata := 0.U
      responseHeader := 0.U
      responseHeaderValid := false.B
      requesterState := Mux(
        requestCanceled,
        MailboxRequesterState.idle,
        MailboxRequesterState.response
      )
      requestCanceled := false.B
    }.otherwise {
      timeoutCounter := timeoutCounter + 1.U
    }
  }

  // ===========================================================================
  // E2E credits
  // ===========================================================================
  val isNopCredit = rxFire && SBMsgCompare(rxPacket, SBM.NOP_CRD) &&
    rxPacket(61) === 0.U && rxPacket(60, 59) === 0.U &&
    rxPacket(58, 56) === "b101".U && rxPacket(31, 29) === "b001".U &&
    rxPacket(28, 22) === 0.U && rxPacket(13, 5) === 0.U
  val nopCount = rxPacket(55, 40)
  val validNopCount = isNopCredit && nopCount >= 1.U && nopCount <= 4.U
  val regCredit = rxFire &&
    (SBM.isRegAccessRequest(rxOpcode) || rxCompletion) && rxCr
  val creditsAdded =
    Mux(validNopCount, nopCount(2, 0), Mux(regCredit, 1.U, 0.U))
  val creditSum = e2eCredits +& creditsAdded
  val netCreditSum = Mux(requestCommitted, creditSum - 1.U, creditSum)
  val cappedCredits = Mux(netCreditSum > 4.U, 4.U, netCreditSum)
  when(io.newRdiEpoch) {
    e2eCredits := 4.U
  }.otherwise {
    e2eCredits := cappedCredits
  }

  // ===========================================================================
  // Clear and link reset
  // ===========================================================================
  when(io.clearEngine) {
    val cancelPendingRequest = requesterState === MailboxRequesterState.offer ||
      requesterState === MailboxRequesterState.awaitingCommit ||
      requesterState === MailboxRequesterState.outstanding
    val holdingResponse = requesterState === MailboxRequesterState.response &&
      !io.mailbox.resp.fire
    val preserveResponse = !io.linkReset &&
      (io.mailbox.req.fire ||
        (!requestCanceled && (cancelPendingRequest || holdingResponse)))
    when(preserveResponse) {
      requesterState := MailboxRequesterState.response
      when(cancelPendingRequest || io.mailbox.req.fire) {
        responseStatus := 1.U
        responseRdata := 0.U
        responseHeader := 0.U
        responseHeaderValid := false.B
      }
    }.otherwise {
      requesterState := MailboxRequesterState.idle
    }
    timeoutCounter := 0.U
    timeoutStreak := 0.U
    requestCanceled := false.B
  }

  when(io.regCompleterFlush) {
    mapperNext := 0.U
    when(mapperBusy && !io.regTarget.resp.fire) {
      discardMapperResponse := true.B
    }
    mapperBusy := false.B
    for (i <- 0 until contextCount) {
      ctxValid(i) := false.B
      ctxMapperPending(i) := false.B
      ctxCompletionReady(i) := false.B
    }
  }

  when(io.linkReset && !io.clearEngine) {
    when(mapperBusy && !io.regTarget.resp.fire) {
      discardMapperResponse := true.B
      ctxMapperPending(mapperContext) := true.B
    }
    mapperBusy := false.B
    switch(requesterState) {
      is(MailboxRequesterState.idle) {
        requestCanceled := false.B
      }
      is(MailboxRequesterState.offer) {
        requesterState := MailboxRequesterState.idle
        requestCanceled := false.B
      }
      is(MailboxRequesterState.awaitingCommit) {
        requestCanceled := true.B
      }
      is(MailboxRequesterState.outstanding) {
        when(terminalCompletion || requestTimeout) {
          requesterState := MailboxRequesterState.idle
          requestCanceled := false.B
        }.otherwise {
          requestCanceled := true.B
        }
      }
      is(MailboxRequesterState.response) {
        requesterState := MailboxRequesterState.idle
        requestCanceled := false.B
      }
    }
  }
}
