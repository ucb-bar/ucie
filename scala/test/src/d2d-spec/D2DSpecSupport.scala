package edu.berkeley.cs.uciedigital.d2dspec

import chisel3._
import chisel3.simulator.PeekPokeAPI._

import scala.collection.mutable

import edu.berkeley.cs.uciedigital.d2dadapter._
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

object D2DSpecTopLevelSupport {
  def initDut(dut: D2DAdapter): Unit = {
    // Protocol-side ingress defaults.
    dut.io.fdi.lpData.valid.poke(false.B)
    dut.io.fdi.lpData.irdy.poke(false.B)
    dut.io.fdi.lpData.bits.poke(0.U)
    RawStreamSignalCodec.pokeStreamFromId(dut.io.fdi.lpStream, RawStreamIds.Stack0Streaming)

    // Protocol-side control defaults.
    dut.io.fdi.lpRetimerCrd.poke(false.B)
    dut.io.fdi.lpCorruptCrc.poke(false.B)
    dut.io.fdi.lpDllp.valid.poke(false.B)
    dut.io.fdi.lpDllp.bits.poke(0.U)
    dut.io.fdi.lpDllpOfc.poke(false.B)
    dut.io.fdi.lpStateReq.poke(PhyStateReq.nop)
    dut.io.fdi.lpLinkError.poke(false.B)
    dut.io.fdi.lpRxActiveStatus.poke(false.B)
    dut.io.fdi.lpStallAck.poke(false.B)
    dut.io.fdi.lpClkAck.poke(false.B)
    dut.io.fdi.lpWakeReq.poke(false.B)
    dut.io.fdi.lpConfig.valid.poke(false.B)
    dut.io.fdi.lpConfig.bits.poke(0.U)
    dut.io.fdi.plConfigCredit.poke(true.B)

    // PHY-side defaults.
    dut.io.rdi.lpData.ready.poke(true.B)
    dut.io.rdi.plData.valid.poke(false.B)
    dut.io.rdi.plData.bits.poke(0.U)
    dut.io.rdi.plRetimerCrd.poke(false.B)
    dut.io.rdi.plStateStatus.poke(PhyState.reset)
    dut.io.rdi.plInbandPres.poke(false.B)
    dut.io.rdi.plError.poke(false.B)
    dut.io.rdi.plCorrectableError.poke(false.B)
    dut.io.rdi.plNonFatalError.poke(false.B)
    dut.io.rdi.plTrainError.poke(false.B)
    dut.io.rdi.plPhyInRecenter.poke(false.B)
    dut.io.rdi.plStallReq.poke(false.B)
    dut.io.rdi.plSpeedMode.poke(SpeedMode.speed8)
    dut.io.rdi.plLinkWidth.poke(PhyWidth.width8)
    dut.io.rdi.plClkReq.poke(false.B)
    dut.io.rdi.plWakeAck.poke(false.B)
    dut.io.rdi.plConfig.valid.poke(false.B)
    dut.io.rdi.plConfig.bits.poke(0.U)
    dut.io.rdi.lpConfigCredit.poke(true.B)

    dut.clock.step(2)
  }

  def snapshot(dut: D2DAdapter): String =
    s"state=${dut.io.fdi.plStateStatus.peek().litValue} " +
      s"rdi_lp_state_req=${dut.io.rdi.lpStateReq.peek().litValue} " +
      s"fdi_pl_rx_active_req=${dut.io.fdi.plRxActiveReq.peek().litToBoolean} " +
      s"fdi_pl_inband_pres=${dut.io.fdi.plInbandPres.peek().litToBoolean} " +
      s"fdi_pl_stallreq=${dut.io.fdi.plStallReq.peek().litToBoolean}"

  def waitUntil(
    dut: D2DAdapter,
    maxCycles: Int,
    reason: String
  )(cond: => Boolean): Unit = {
    var waited = 0
    while (waited < maxCycles && !cond) {
      dut.clock.step(1)
      waited += 1
    }
    assert(
      cond,
      s"Timeout waiting for: $reason after $maxCycles cycles. ${snapshot(dut)}"
    )
  }

  def decodeLinkHeader(rawMsg: BigInt): SidebandHeader = {
    val opcode = (rawMsg & BigInt(0x1f)).toInt
    val msgCode = ((rawMsg >> 14) & BigInt(0xff)).toInt
    val msgSubCode = ((rawMsg >> 32) & BigInt(0xff)).toInt
    SidebandHeader(opcode = opcode, msgCode = msgCode, msgSubCode = msgSubCode)
  }

  def encodeInboundSidebandMessage(
    linkOp: UInt,
    data: BigInt = BigInt(0)
  ): BigInt = {
    val (opcode, msgCode, msgSubCode) =
      if (linkOp.litValue == SideBandMessage.ADV_CAP.litValue) {
        (0x1b, 0x01, 0x00)
      } else if (linkOp.litValue == SideBandMessage.REQ_ACTIVE.litValue) {
        (0x12, 0x03, 0x01)
      } else if (linkOp.litValue == SideBandMessage.RSP_ACTIVE.litValue) {
        (0x12, 0x04, 0x01)
      } else if (linkOp.litValue == SideBandMessage.REQ_LINKRESET.litValue) {
        (0x12, 0x03, 0x09)
      } else if (linkOp.litValue == SideBandMessage.RSP_LINKRESET.litValue) {
        (0x12, 0x04, 0x09)
      } else if (linkOp.litValue == SideBandMessage.REQ_DISABLED.litValue) {
        (0x12, 0x03, 0x0c)
      } else if (linkOp.litValue == SideBandMessage.RSP_DISABLED.litValue) {
        (0x12, 0x04, 0x0c)
      } else {
        throw new IllegalArgumentException(
          f"Unsupported inbound sideband link-op 0x${linkOp.litValue.toInt}%x"
        )
      }

    val payload64 =
      if (linkOp.litValue == SideBandMessage.ADV_CAP.litValue) BigInt(0x91)
      else data

    val srcId = BigInt(0x1) // D2D
    val dstId = BigInt(0x1) // Local D2D node id.
    val msgInfo = BigInt(0)
    val header =
      (dstId << 56) |
        (msgInfo << 40) |
        (BigInt(msgSubCode) << 32) |
        (srcId << 29) |
        (BigInt(msgCode) << 14) |
        BigInt(opcode)

    ((payload64 & ((BigInt(1) << 64) - 1)) << 64) | header
  }

  def sendInboundSidebandMessage(
    dut: D2DAdapter,
    sidebandWordWidth: Int,
    sidebandBeatsPerMessage: Int,
    linkOp: UInt,
    data: BigInt = BigInt(0),
    observeCycles: Int = 8
  ): Unit = {
    val sidebandWordMask = (BigInt(1) << sidebandWordWidth) - 1
    val msg = encodeInboundSidebandMessage(linkOp = linkOp, data = data)

    for (beat <- 0 until sidebandBeatsPerMessage) {
      val word = (msg >> (beat * sidebandWordWidth)) & sidebandWordMask
      dut.io.rdi.plConfig.bits.poke(word.U(sidebandWordWidth.W))
      dut.io.rdi.plConfig.valid.poke(true.B)
      dut.clock.step(1)
    }

    dut.io.rdi.plConfig.valid.poke(false.B)
    dut.io.rdi.plConfig.bits.poke(0.U)
    dut.clock.step(observeCycles)
  }

  def waitForNextOutboundMessage(
    dut: D2DAdapter,
    collector: RdiSidebandMessageCollector,
    maxCycles: Int,
    reason: String
  ): BigInt = {
    var waited = 0
    var found: Option[BigInt] = None

    while (waited < maxCycles && found.isEmpty) {
      val maybeMsg = collector.observe(
        wordValid = dut.io.rdi.lpConfig.valid.peek().litToBoolean,
        wordBits = dut.io.rdi.lpConfig.bits.peek().litValue
      )
      dut.clock.step(1)
      waited += 1
      if (maybeMsg.nonEmpty) found = maybeMsg
    }

    assert(
      found.nonEmpty,
      s"Timeout waiting for outbound sideband message: $reason after $maxCycles cycles. ${snapshot(dut)}"
    )
    found.get
  }

  def bringLinkToActive(
    dut: D2DAdapter,
    rdiParams: RdiParams,
    sbParams: SidebandParams
  ): Seq[BigInt] = {
    val sidebandWordWidth = rdiParams.sbWidth
    val sidebandBeatsPerMessage = sbParams.sbNodeMsgWidth / sidebandWordWidth
    val collector = new RdiSidebandMessageCollector(
      wordWidth = sidebandWordWidth,
      beatsPerMessage = sidebandBeatsPerMessage
    )
    val outbound = mutable.ArrayBuffer.empty[BigInt]

    dut.io.rdi.plInbandPres.poke(true.B)
    waitUntil(dut, maxCycles = 40, reason = "RDI request ACTIVE during bring-up") {
      dut.io.rdi.lpStateReq.peek().litValue == PhyStateReq.active.litValue
    }
    dut.io.rdi.plStateStatus.poke(PhyState.active)

    outbound += waitForNextOutboundMessage(
      dut = dut,
      collector = collector,
      maxCycles = 120,
      reason = "ADV_CAP advertisement"
    )
    sendInboundSidebandMessage(
      dut = dut,
      sidebandWordWidth = sidebandWordWidth,
      sidebandBeatsPerMessage = sidebandBeatsPerMessage,
      linkOp = SideBandMessage.ADV_CAP
    )

    waitUntil(dut, maxCycles = 60, reason = "FDI inband presence after parameter exchange") {
      dut.io.fdi.plInbandPres.peek().litToBoolean
    }

    dut.io.fdi.lpStateReq.poke(PhyStateReq.nop)
    dut.clock.step(1)
    dut.io.fdi.lpStateReq.poke(PhyStateReq.active)
    dut.clock.step(1)

    outbound += waitForNextOutboundMessage(
      dut = dut,
      collector = collector,
      maxCycles = 120,
      reason = "REQ_ACTIVE negotiation burst"
    )
    sendInboundSidebandMessage(
      dut = dut,
      sidebandWordWidth = sidebandWordWidth,
      sidebandBeatsPerMessage = sidebandBeatsPerMessage,
      linkOp = SideBandMessage.REQ_ACTIVE
    )

    waitUntil(dut, maxCycles = 60, reason = "FDI rx-active request") {
      dut.io.fdi.plRxActiveReq.peek().litToBoolean
    }

    sendInboundSidebandMessage(
      dut = dut,
      sidebandWordWidth = sidebandWordWidth,
      sidebandBeatsPerMessage = sidebandBeatsPerMessage,
      linkOp = SideBandMessage.RSP_ACTIVE
    )
    dut.io.fdi.lpRxActiveStatus.poke(true.B)

    outbound += waitForNextOutboundMessage(
      dut = dut,
      collector = collector,
      maxCycles = 120,
      reason = "RSP_ACTIVE negotiation burst"
    )

    waitUntil(dut, maxCycles = 60, reason = "top-level ACTIVE state") {
      dut.io.fdi.plStateStatus.peek().litValue == PhyState.active.litValue
    }

    outbound.toSeq
  }

  final case class ForwardTrafficEnv(
    driver: TopLevelForwardIngressDriver,
    ingressTracker: TopLevelForwardIngressTracker,
    egressMonitor: TopLevelForwardEgressMonitor,
    sourceStabilityChecker: TopLevelForwardSourceStabilityChecker,
    expectedQ: mutable.Queue[AcceptedBeat],
    scoreboard: Scoreboard,
    var maxExpectedQueueDepth: Int
  )

  def newForwardTrafficEnv(
    dut: D2DAdapter,
    beats: Seq[RawBeat],
    injectedSourceHoldoff: () => Boolean = () => false,
    gapCyclesBeforeBeat: Int => Int = _ => 0
  ): ForwardTrafficEnv = {
    val expectedQ = mutable.Queue.empty[AcceptedBeat]
    val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = false)
    val driver = new TopLevelForwardIngressDriver(
      dut = dut,
      beats = beats,
      injectedSourceHoldoff = injectedSourceHoldoff,
      gapCyclesBeforeBeat = gapCyclesBeforeBeat
    )
    val ingressTracker = new TopLevelForwardIngressTracker(dut)
    val egressMonitor = new TopLevelForwardEgressMonitor(
      dut = dut,
      onObserved = scoreboard.onObserved,
      egressStreamId = () => RawStreamIds.UnknownStreamId
    )
    val sourceStabilityChecker = new TopLevelForwardSourceStabilityChecker(dut, driver)
    ForwardTrafficEnv(
      driver = driver,
      ingressTracker = ingressTracker,
      egressMonitor = egressMonitor,
      sourceStabilityChecker = sourceStabilityChecker,
      expectedQ = expectedQ,
      scoreboard = scoreboard,
      maxExpectedQueueDepth = 0
    )
  }

  def stepForwardTraffic(
    dut: D2DAdapter,
    env: ForwardTrafficEnv,
    cycle: Long,
    egressReady: Boolean = true,
    boundaryCrossed: Boolean = false,
    boundaryName: String = "none"
  ): Option[AcceptedBeat] = {
    dut.io.rdi.lpData.ready.poke(egressReady.B)
    env.driver.driveOneCycle()
    env.sourceStabilityChecker.check(
      cycle = cycle,
      boundaryCrossed = boundaryCrossed,
      boundaryName = boundaryName
    )

    val ingressObs = env.ingressTracker.observeForNextEdge(cycle)
    val egressObs = env.egressMonitor.observeForNextEdge(cycle)
    dut.clock.step(1)

    val accepted = env.ingressTracker.commitAfterEdge(
      obs = ingressObs,
      expectedQ = env.expectedQ,
      enqueueExpected = true
    )
    if (accepted) env.driver.onAccepted()
    env.maxExpectedQueueDepth = math.max(env.maxExpectedQueueDepth, env.expectedQ.size)
    env.egressMonitor.commitAfterEdge(egressObs)
    if (accepted) ingressObs else None
  }

  def runForwardTrafficToCompletion(
    dut: D2DAdapter,
    beats: Seq[RawBeat],
    maxCycles: Int = 6000,
    egressReadyFn: Long => Boolean = _ => true
  ): Unit = {
    var cycleRef = 0L
    val env = newForwardTrafficEnv(dut = dut, beats = beats)

    while (cycleRef < maxCycles && (!env.driver.isDone || env.expectedQ.nonEmpty)) {
      stepForwardTraffic(
        dut = dut,
        env = env,
        cycle = cycleRef,
        egressReady = egressReadyFn(cycleRef)
      )
      cycleRef += 1
    }
    assert(cycleRef < maxCycles, s"Timeout at $maxCycles cycles. ${snapshot(dut)}")

    var drain = 0
    while (drain < 128 && env.expectedQ.nonEmpty) {
      stepForwardTraffic(
        dut = dut,
        env = env,
        cycle = cycleRef,
        egressReady = true
      )
      cycleRef += 1
      drain += 1
    }

    env.scoreboard.finishAndAssert(
      acceptedInputCount = env.ingressTracker.acceptedCount,
      maxExpectedQueueDepth = Some(env.maxExpectedQueueDepth)
    )
  }

  def runReverseTrafficToCompletion(
    dut: D2DAdapter,
    beats: Seq[RawBeat],
    maxCycles: Int = 6000
  ): Unit = {
    var cycleRef = 0L
    val expectedQ = mutable.Queue.empty[AcceptedBeat]
    val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = true)
    val driver = new TopLevelReverseIngressDriver(dut = dut, beats = beats)
    val ingressTracker = new TopLevelReverseIngressTracker(
      dut = dut,
      expectedQ = expectedQ,
      expectedForwardedStreamId = RawStreamIds.Stack0Streaming
    )
    val egressMonitor = new TopLevelReverseEgressMonitor(dut, scoreboard.onObserved)
    val sourceStabilityChecker = new TopLevelReverseSourceStabilityChecker(dut, driver)

    var maxExpectedQueueDepth = 0
    def updateMaxDepth(): Unit = {
      maxExpectedQueueDepth = math.max(maxExpectedQueueDepth, expectedQ.size)
    }

    while (cycleRef < maxCycles && (!driver.isDone || expectedQ.nonEmpty)) {
      driver.driveOneCycle()
      sourceStabilityChecker.check(cycleRef)

      val ingressObs = ingressTracker.observeForNextEdge(cycleRef)
      val egressObs = egressMonitor.observeForNextEdge(cycleRef)
      dut.clock.step(1)

      val sampled = ingressTracker.commitAfterEdge(ingressObs)
      updateMaxDepth()
      if (sampled) driver.onSampledIngressBeat()
      egressMonitor.commitAfterEdge(egressObs)
      cycleRef += 1
    }
    assert(cycleRef < maxCycles, s"Timeout at $maxCycles cycles. ${snapshot(dut)}")

    var drain = 0
    while (drain < 64 && expectedQ.nonEmpty) {
      driver.driveOneCycle()
      sourceStabilityChecker.check(cycleRef)

      val ingressObs = ingressTracker.observeForNextEdge(cycleRef)
      val egressObs = egressMonitor.observeForNextEdge(cycleRef)
      dut.clock.step(1)

      val sampled = ingressTracker.commitAfterEdge(ingressObs)
      updateMaxDepth()
      if (sampled) driver.onSampledIngressBeat()
      egressMonitor.commitAfterEdge(egressObs)

      cycleRef += 1
      drain += 1
    }

    scoreboard.finishAndAssert(
      acceptedInputCount = ingressTracker.sampledCount,
      maxExpectedQueueDepth = Some(maxExpectedQueueDepth)
    )
  }
}
