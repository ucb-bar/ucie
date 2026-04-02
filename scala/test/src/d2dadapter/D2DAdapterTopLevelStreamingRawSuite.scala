package edu.berkeley.cs.uciedigital.d2dadapter

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._

final class TopLevelForwardIngressDriver(
  dut: D2DAdapter,
  beats: Seq[RawBeat],
  injectedSourceHoldoff: () => Boolean = () => false,
  gapCyclesBeforeBeat: Int => Int = _ => 0
) {
  private var beatIdx: Int = 0
  private var activeBeat: Option[RawBeat] = None
  private var preSendGapRemaining: Int = 0

  var acceptedCount: Long = 0L

  def isDone: Boolean = beatIdx >= beats.length && activeBeat.isEmpty
  def pendingBeat: Option[RawBeat] = activeBeat
  def pendingBeatIndex: Option[Int] = activeBeat.map(_ => beatIdx)

  private def loadNextIfNeeded(): Unit = {
    if (activeBeat.isEmpty && beatIdx < beats.length) {
      activeBeat = Some(beats(beatIdx))
      preSendGapRemaining = math.max(0, gapCyclesBeforeBeat(beatIdx))
    }
  }

  /**
    * Forward ingress boundary at top level:
    * protocol -> adapter via `fdi.lpData` + `fdi.lpStream`.
    *
    * The source holds payload and stream stable while the beat remains pending.
    */
  def driveOneCycle(): Unit = {
    loadNextIfNeeded()

    activeBeat.foreach { beat =>
      dut.io.fdi.lpData.bits.poke(beat.data.U)
      RawStreamSignalCodec.pokeStreamFromId(dut.io.fdi.lpStream, beat.streamId)
    }

    val gatedByScheduledGap = preSendGapRemaining > 0
    if (gatedByScheduledGap) preSendGapRemaining -= 1

    if (injectedSourceHoldoff() || gatedByScheduledGap) {
      dut.io.fdi.lpData.valid.poke(false.B)
      dut.io.fdi.lpData.irdy.poke(false.B)
      return
    }

    activeBeat match {
      case Some(_) =>
        dut.io.fdi.lpData.valid.poke(true.B)
        dut.io.fdi.lpData.irdy.poke(true.B)
      case None =>
        dut.io.fdi.lpData.valid.poke(false.B)
        dut.io.fdi.lpData.irdy.poke(false.B)
    }
  }

  def onAccepted(): Unit = {
    if (activeBeat.nonEmpty) {
      acceptedCount += 1
      beatIdx += 1
      activeBeat = None
    }
  }
}

final class TopLevelForwardIngressTracker(dut: D2DAdapter) {
  private var nextSeq: Long = 0L
  var acceptedCount: Long = 0L

  /**
    * Forward ingress acceptance semantics at top-level FDI boundary:
    * accepted = `fdi.lpData.valid && fdi.lpData.irdy && fdi.lpData.ready`.
    */
  def observeForNextEdge(edgeCycle: Long): Option[AcceptedBeat] = {
    val accepted = dut.io.fdi.lpData.valid.peek().litToBoolean &&
      dut.io.fdi.lpData.irdy.peek().litToBoolean &&
      dut.io.fdi.lpData.ready.peek().litToBoolean

    if (accepted) {
      Some(AcceptedBeat(
        seq = nextSeq,
        data = dut.io.fdi.lpData.bits.peek().litValue,
        streamId = RawStreamSignalCodec.peekStreamId(dut.io.fdi.lpStream),
        cycleAccepted = edgeCycle
      ))
    } else None
  }

  def commitAfterEdge(
    obs: Option[AcceptedBeat],
    expectedQ: mutable.Queue[AcceptedBeat],
    enqueueExpected: Boolean
  ): Boolean = {
    obs.foreach { beat =>
      if (enqueueExpected) expectedQ.enqueue(beat)
      nextSeq += 1
      acceptedCount += 1
    }
    obs.nonEmpty
  }
}

final class TopLevelForwardEgressMonitor(
  dut: D2DAdapter,
  onObserved: AcceptedBeat => Unit,
  egressStreamId: () => Int = () => RawStreamIds.UnknownStreamId
) {
  private var nextSeq: Long = 0L
  var observedCount: Long = 0L

  /**
    * Forward egress transfer semantics at top-level RDI boundary:
    * transferred = `rdi.lpData.valid && rdi.lpData.irdy && rdi.lpData.ready`.
    *
    * Note: this boundary has no stream metadata field in this RTL.
    */
  def observeForNextEdge(edgeCycle: Long): Option[AcceptedBeat] = {
    val transferred = dut.io.rdi.lpData.valid.peek().litToBoolean &&
      dut.io.rdi.lpData.irdy.peek().litToBoolean &&
      dut.io.rdi.lpData.ready.peek().litToBoolean

    if (transferred) {
      Some(AcceptedBeat(
        seq = nextSeq,
        data = dut.io.rdi.lpData.bits.peek().litValue,
        streamId = egressStreamId(),
        cycleAccepted = edgeCycle
      ))
    } else None
  }

  def commitAfterEdge(obs: Option[AcceptedBeat]): Unit = {
    obs.foreach { beat =>
      onObserved(beat)
      nextSeq += 1
      observedCount += 1
    }
  }
}

final class TopLevelForwardSourceStabilityChecker(
  dut: D2DAdapter,
  driver: TopLevelForwardIngressDriver
) {
  private var prevOutstanding: Option[(Int, BigInt, Int)] = None

  /**
    * While the same beat remains pending on `fdi.lpData`, payload and stream
    * must remain stable until acceptance.
    */
  def check(cycle: Long, boundaryCrossed: Boolean = false, boundaryName: String = "none"): Unit = {
    val linkState = dut.io.fdi.plStateStatus.peek().litValue
    (driver.pendingBeatIndex, driver.pendingBeat) match {
      case (Some(idx), Some(beat)) =>
        val currData = dut.io.fdi.lpData.bits.peek().litValue
        val currStream = RawStreamSignalCodec.peekStreamId(dut.io.fdi.lpStream)

        assert(
          currData == beat.data,
          s"Cycle $cycle state=0x${linkState.toString(16)} idx=$idx boundary=$boundaryName crossed=$boundaryCrossed: " +
            s"source data mismatch driven=0x${currData.toString(16)} pending=0x${beat.data.toString(16)}"
        ) // SPEC-DERIVED
        assert(
          currStream == beat.streamId,
          s"Cycle $cycle state=0x${linkState.toString(16)} idx=$idx boundary=$boundaryName crossed=$boundaryCrossed: " +
            s"source stream mismatch driven=0x${currStream.toHexString} pending=0x${beat.streamId.toHexString}"
        ) // SPEC-DERIVED

        prevOutstanding match {
          case Some((prevIdx, prevData, prevStream)) if prevIdx == idx =>
            assert(
              currData == prevData,
              s"Cycle $cycle state=0x${linkState.toString(16)} idx=$idx boundary=$boundaryName crossed=$boundaryCrossed: " +
                s"source data changed while unaccepted (prev=0x${prevData.toString(16)} now=0x${currData.toString(16)})"
            ) // SPEC-DERIVED
            assert(
              currStream == prevStream,
              s"Cycle $cycle state=0x${linkState.toString(16)} idx=$idx boundary=$boundaryName crossed=$boundaryCrossed: " +
                s"source stream changed while unaccepted (prev=0x${prevStream.toHexString} now=0x${currStream.toHexString})"
            ) // SPEC-DERIVED
          case _ =>
        }
        prevOutstanding = Some((idx, currData, currStream))

      case _ =>
        prevOutstanding = None
    }
  }
}

final class TopLevelReverseIngressDriver(
  dut: D2DAdapter,
  beats: Seq[RawBeat],
  injectedSourceHoldoff: () => Boolean = () => false,
  gapCyclesBeforeBeat: Int => Int = _ => 0
) {
  private var beatIdx: Int = 0
  private var activeBeat: Option[RawBeat] = None
  private var preSendGapRemaining: Int = 0

  var sampledCount: Long = 0L

  def isDone: Boolean = beatIdx >= beats.length && activeBeat.isEmpty
  def pendingBeat: Option[RawBeat] = activeBeat
  def pendingBeatIndex: Option[Int] = activeBeat.map(_ => beatIdx)

  private def loadNextIfNeeded(): Unit = {
    if (activeBeat.isEmpty && beatIdx < beats.length) {
      activeBeat = Some(beats(beatIdx))
      preSendGapRemaining = math.max(0, gapCyclesBeforeBeat(beatIdx))
    }
  }

  /**
    * Reverse ingress boundary at top level:
    * physical -> adapter via `rdi.plData` (valid-only).
    */
  def driveOneCycle(): Unit = {
    loadNextIfNeeded()

    activeBeat.foreach { beat =>
      dut.io.rdi.plData.bits.poke(beat.data.U)
    }

    val gatedByScheduledGap = preSendGapRemaining > 0
    if (gatedByScheduledGap) preSendGapRemaining -= 1

    if (injectedSourceHoldoff() || gatedByScheduledGap) {
      dut.io.rdi.plData.valid.poke(false.B)
      return
    }

    activeBeat match {
      case Some(_) => dut.io.rdi.plData.valid.poke(true.B)
      case None    => dut.io.rdi.plData.valid.poke(false.B)
    }
  }

  def onSampledIngressBeat(): Unit = {
    if (activeBeat.nonEmpty) {
      sampledCount += 1
      beatIdx += 1
      activeBeat = None
    }
  }
}

final class TopLevelReverseIngressTracker(
  dut: D2DAdapter,
  expectedQ: mutable.Queue[AcceptedBeat],
  expectedForwardedStreamId: Int
) {
  private var nextSeq: Long = 0L
  var sampledCount: Long = 0L

  /**
    * Reverse ingress sampling semantics at top-level RDI boundary:
    * sampled = `rdi.plData.valid` (valid-only channel).
    */
  def observeForNextEdge(edgeCycle: Long): Option[AcceptedBeat] = {
    val sampled = dut.io.rdi.plData.valid.peek().litToBoolean
    if (sampled) {
      Some(AcceptedBeat(
        seq = nextSeq,
        data = dut.io.rdi.plData.bits.peek().litValue,
        // Reverse ingress has no stream field; expected stream is derived from
        // top-level reverse egress `fdi.plStream` behavior.
        streamId = expectedForwardedStreamId,
        cycleAccepted = edgeCycle
      ))
    } else None
  }

  def commitAfterEdge(obs: Option[AcceptedBeat]): Boolean = {
    obs.foreach { beat =>
      expectedQ.enqueue(beat)
      nextSeq += 1
      sampledCount += 1
    }
    obs.nonEmpty
  }
}

final class TopLevelReverseEgressMonitor(
  dut: D2DAdapter,
  onObserved: AcceptedBeat => Unit
) {
  private var nextSeq: Long = 0L
  var observedCount: Long = 0L

  /**
    * Reverse egress transfer semantics at top-level FDI boundary:
    * transferred = `fdi.plData.valid` (valid-only channel).
    *
    * Stream metadata is observable here (`fdi.plStream`) and is checked.
    */
  def observeForNextEdge(edgeCycle: Long): Option[AcceptedBeat] = {
    val transferred = dut.io.fdi.plData.valid.peek().litToBoolean
    if (transferred) {
      Some(AcceptedBeat(
        seq = nextSeq,
        data = dut.io.fdi.plData.bits.peek().litValue,
        streamId = RawStreamSignalCodec.peekStreamId(dut.io.fdi.plStream),
        cycleAccepted = edgeCycle
      ))
    } else None
  }

  def commitAfterEdge(obs: Option[AcceptedBeat]): Unit = {
    obs.foreach { beat =>
      onObserved(beat)
      nextSeq += 1
      observedCount += 1
    }
  }
}

final class TopLevelReverseSourceStabilityChecker(
  dut: D2DAdapter,
  driver: TopLevelReverseIngressDriver
) {
  private var prevOutstanding: Option[(Int, BigInt)] = None

  def check(cycle: Long): Unit = {
    (driver.pendingBeatIndex, driver.pendingBeat) match {
      case (Some(idx), Some(beat)) =>
        val currData = dut.io.rdi.plData.bits.peek().litValue
        assert(
          currData == beat.data,
          s"Cycle $cycle idx=$idx reverse source data mismatch, driven=0x${currData.toString(16)} pending=0x${beat.data.toString(16)}"
        ) // SPEC-DERIVED

        prevOutstanding match {
          case Some((prevIdx, prevData)) if prevIdx == idx =>
            assert(
              currData == prevData,
              s"Cycle $cycle idx=$idx reverse source data changed while unaccepted " +
                s"(prev=0x${prevData.toString(16)} now=0x${currData.toString(16)})"
            ) // SPEC-DERIVED
          case _ =>
        }
        prevOutstanding = Some((idx, currData))

      case _ =>
        prevOutstanding = None
    }
  }
}

final case class SidebandHeader(opcode: Int, msgCode: Int, msgSubCode: Int)
final case class InboundSidebandObs(rdiCreditPulses: Int, fdiForwardPulses: Int)

final class RdiSidebandMessageCollector(wordWidth: Int, beatsPerMessage: Int) {
  private val wordMask = (BigInt(1) << wordWidth) - 1
  private var partialMsg: BigInt = 0
  private var partialBeats: Int = 0

  def observe(wordValid: Boolean, wordBits: BigInt): Option[BigInt] = {
    if (!wordValid) {
      return None
    }

    partialMsg |= (wordBits & wordMask) << (partialBeats * wordWidth)
    partialBeats += 1

    if (partialBeats == beatsPerMessage) {
      val full = partialMsg
      partialMsg = 0
      partialBeats = 0
      Some(full)
    } else None
  }
}

/**
  * Top-level streaming/raw suite that instantiates the real `D2DAdapter`.
  *
  * Scope intentionally matches current verification boundaries:
  * - Raw Format only
  * - Streaming protocol only
  * - no 68B/256B flit modeling
  * - no DLLP/retry/flit-mode behavior
  *
  * Top-level datapath boundaries used by this suite:
  * - forward: `fdi.lpData` ingress -> adapter -> `rdi.lpData` egress
  * - reverse: `rdi.plData` ingress -> adapter -> `fdi.plData` egress
  *
  * Stream-ID observability:
  * - forward egress (`rdi.lpData`) has no stream field in this RTL, so forward
  *   stream-preservation cannot be checked at top boundary.
  * - reverse egress (`fdi.plStream`) is visible and checked.
  */
class D2DAdapterTopLevelStreamingRawSuite extends AnyFlatSpec with ChiselScalatestTester {
  private val fdiParams = new FdiParams(width = 8, dllpWidth = 8, sbWidth = 32)
  private val rdiParams = new RdiParams(width = 8, sbWidth = 32)
  private val sbParams = new SidebandParams

  private val sidebandWordWidth = rdiParams.sbWidth
  private val sidebandBeatsPerMessage = sbParams.sbNodeMsgWidth / sidebandWordWidth
  private val sidebandWordMask = (BigInt(1) << sidebandWordWidth) - 1
  require(
    sbParams.sbNodeMsgWidth % sidebandWordWidth == 0,
    s"sbNodeMsgWidth=${sbParams.sbNodeMsgWidth} must be divisible by sideband word width=$sidebandWordWidth"
  )

  private final class ForwardTrafficEnv(
    val driver: TopLevelForwardIngressDriver,
    val ingressTracker: TopLevelForwardIngressTracker,
    val egressMonitor: TopLevelForwardEgressMonitor,
    val sourceStabilityChecker: TopLevelForwardSourceStabilityChecker,
    val expectedQ: mutable.Queue[AcceptedBeat],
    val scoreboard: Scoreboard,
    var maxExpectedQueueDepth: Int
  )

  private def initDut(dut: D2DAdapter): Unit = {
    dut.clock.setTimeout(0)

    // Protocol-side ingress defaults (forward path).
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

  private def snapshot(dut: D2DAdapter): String =
    s"state=${dut.io.fdi.plStateStatus.peek().litValue} " +
      s"rdi_lp_state_req=${dut.io.rdi.lpStateReq.peek().litValue} " +
      s"fdi_pl_rx_active_req=${dut.io.fdi.plRxActiveReq.peek().litToBoolean} " +
      s"fdi_pl_inband_pres=${dut.io.fdi.plInbandPres.peek().litToBoolean} " +
      s"fdi_pl_stallreq=${dut.io.fdi.plStallReq.peek().litToBoolean} " +
      s"rdi_pl_cfg_crd=${dut.io.rdi.plConfigCredit.peek().litToBoolean} " +
      s"fdi_pl_cfg_vld=${dut.io.fdi.plConfig.valid.peek().litToBoolean}"

  private def waitUntil(
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
    ) // UNKNOWN: needs spec/RTL audit
  }

  private def headerForLinkMessage(linkOp: UInt): SidebandHeader = {
    val op = linkOp.litValue.toInt
    val msgWithoutData = 0x12
    val msgWith64bData = 0x1b
    op match {
      case x if x == SideBandMessage.ADV_CAP.litValue.toInt =>
        SidebandHeader(msgWith64bData, msgCode = 0x01, msgSubCode = 0x00)
      case x if x == SideBandMessage.REQ_ACTIVE.litValue.toInt =>
        SidebandHeader(msgWithoutData, msgCode = 0x03, msgSubCode = 0x01)
      case x if x == SideBandMessage.RSP_ACTIVE.litValue.toInt =>
        SidebandHeader(msgWithoutData, msgCode = 0x04, msgSubCode = 0x01)
      case x if x == SideBandMessage.REQ_LINKRESET.litValue.toInt =>
        SidebandHeader(msgWithoutData, msgCode = 0x03, msgSubCode = 0x09)
      case x if x == SideBandMessage.RSP_LINKRESET.litValue.toInt =>
        SidebandHeader(msgWithoutData, msgCode = 0x04, msgSubCode = 0x09)
      case x if x == SideBandMessage.REQ_DISABLED.litValue.toInt =>
        SidebandHeader(msgWithoutData, msgCode = 0x03, msgSubCode = 0x0c)
      case x if x == SideBandMessage.RSP_DISABLED.litValue.toInt =>
        SidebandHeader(msgWithoutData, msgCode = 0x04, msgSubCode = 0x0c)
      case _ =>
        throw new IllegalArgumentException(f"Unsupported sideband link-op 0x$op%x")
    }
  }

  private def decodeLinkHeader(rawMsg: BigInt): SidebandHeader = {
    val opcode = (rawMsg & BigInt(0x1f)).toInt
    val msgCode = ((rawMsg >> 14) & BigInt(0xff)).toInt
    val msgSubCode = ((rawMsg >> 32) & BigInt(0xff)).toInt
    SidebandHeader(opcode = opcode, msgCode = msgCode, msgSubCode = msgSubCode)
  }

  private def linkHeaderFields(linkOp: UInt): (Int, Int, Int) = {
    val op = linkOp.litValue.toInt
    op match {
      case x if x == SideBandMessage.ADV_CAP.litValue.toInt =>
        (0x1b, 0x01, 0x00)
      case x if x == SideBandMessage.REQ_ACTIVE.litValue.toInt =>
        (0x12, 0x03, 0x01)
      case x if x == SideBandMessage.RSP_ACTIVE.litValue.toInt =>
        (0x12, 0x04, 0x01)
      case x if x == SideBandMessage.REQ_LINKRESET.litValue.toInt =>
        (0x12, 0x03, 0x09)
      case x if x == SideBandMessage.RSP_LINKRESET.litValue.toInt =>
        (0x12, 0x04, 0x09)
      case x if x == SideBandMessage.REQ_DISABLED.litValue.toInt =>
        (0x12, 0x03, 0x0c)
      case x if x == SideBandMessage.RSP_DISABLED.litValue.toInt =>
        (0x12, 0x04, 0x0c)
      case _ =>
        throw new IllegalArgumentException(f"Unsupported sideband link-op 0x$op%x")
    }
  }

  private def encodeInboundSidebandMessage(
    linkOp: UInt,
    data: BigInt = BigInt(0)
  ): BigInt = {
    val (opcode, msgCode, msgSubCode) = linkHeaderFields(linkOp)
    val payload64 =
      if (linkOp.litValue == SideBandMessage.ADV_CAP.litValue) BigInt(0x91) // raw[0], streaming[4], stack0_en[7]
      else data

    val srcId = BigInt(0x1) // D2D
    val dstId = BigInt(0x1) // local D2D node id (remote bit clear)
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

  private def sendInboundSidebandMessage(
    dut: D2DAdapter,
    linkOp: UInt,
    data: BigInt = BigInt(0),
    observeCycles: Int = 8
  ): InboundSidebandObs = {
    val msg = encodeInboundSidebandMessage(linkOp = linkOp, data = data)
    var rdiCreditPulses = 0
    var fdiForwardPulses = 0
    def sampleSidebandObs(): Unit = {
      if (dut.io.rdi.plConfigCredit.peek().litToBoolean) rdiCreditPulses += 1
      if (dut.io.fdi.plConfig.valid.peek().litToBoolean) fdiForwardPulses += 1
    }

    for (beat <- 0 until sidebandBeatsPerMessage) {
      sampleSidebandObs()
      val word = (msg >> (beat * sidebandWordWidth)) & sidebandWordMask
      dut.io.rdi.plConfig.bits.poke(word.U(sidebandWordWidth.W))
      dut.io.rdi.plConfig.valid.poke(true.B)
      dut.clock.step(1)
    }
    dut.io.rdi.plConfig.valid.poke(false.B)
    dut.io.rdi.plConfig.bits.poke(0.U)

    // Observe a few post-send cycles for dequeue credit and accidental forwarding.
    for (_ <- 0 until observeCycles) {
      sampleSidebandObs()
      dut.clock.step(1)
    }

    InboundSidebandObs(
      rdiCreditPulses = rdiCreditPulses,
      fdiForwardPulses = fdiForwardPulses
    )
  }

  private def waitForOutboundSidebandMessage(
    dut: D2DAdapter,
    collector: RdiSidebandMessageCollector,
    expectedLinkOp: UInt,
    maxCycles: Int,
    reason: String
  ): Unit = {
    val expected = headerForLinkMessage(expectedLinkOp)
    var waited = 0
    var matched = false
    var lastSeen: Option[SidebandHeader] = None

    while (waited < maxCycles && !matched) {
      val maybeMsg = collector.observe(
        wordValid = dut.io.rdi.lpConfig.valid.peek().litToBoolean,
        wordBits = dut.io.rdi.lpConfig.bits.peek().litValue
      )
      maybeMsg.foreach { raw =>
        val got = decodeLinkHeader(raw)
        lastSeen = Some(got)
        if (got == expected) matched = true
      }
      if (!matched) {
        dut.clock.step(1)
        waited += 1
      }
    }

    assert(
      matched,
      s"Timeout waiting for outbound sideband $reason after $maxCycles cycles. " +
        s"expected=$expected lastSeen=${lastSeen.getOrElse("none")} ${snapshot(dut)}"
    ) // UNKNOWN: needs spec/RTL audit
  }

  private def waitForRdiSidebandTxBurst(
    dut: D2DAdapter,
    maxCycles: Int,
    reason: String
  ): Unit = {
    waitUntil(dut, maxCycles = maxCycles, reason = s"$reason (tx valid high)") {
      dut.io.rdi.lpConfig.valid.peek().litToBoolean
    }
    waitUntil(dut, maxCycles = maxCycles, reason = s"$reason (tx burst drain)") {
      !dut.io.rdi.lpConfig.valid.peek().litToBoolean
    }
  }

  private def driveToActive(dut: D2DAdapter): Unit = {
    // Reset -> RDI bring-up.
    dut.io.rdi.plInbandPres.poke(true.B)
    waitUntil(dut, maxCycles = 40, reason = "RDI request ACTIVE during bring-up") {
      dut.io.rdi.lpStateReq.peek().litValue == PhyStateReq.active.litValue
    }
    dut.io.rdi.plStateStatus.poke(PhyState.active)

    // PARAM_EXCH: observe a top-level sideband TX burst, then provide remote ADV_CAP.
    waitForRdiSidebandTxBurst(dut, maxCycles = 80, reason = "PARAM_EXCH outbound sideband")
    val advCapObs = sendInboundSidebandMessage(
      dut,
      SideBandMessage.ADV_CAP,
      observeCycles = 80
    )
    assert(
      advCapObs.rdiCreditPulses > 0,
      s"Inbound ADV_CAP was not consumed by top-level sideband RX path (obs=$advCapObs). ${snapshot(dut)}"
    ) // SPEC-DERIVED

    // FDI bring-up starts once parameter exchange completes.
    waitUntil(
      dut,
      maxCycles = 60,
      reason = s"FDI inband presence high after ADV_CAP (obs=$advCapObs)"
    ) {
      dut.io.fdi.plInbandPres.peek().litToBoolean
    }

    // Trigger required nop -> active edge.
    dut.io.fdi.lpStateReq.poke(PhyStateReq.nop)
    dut.clock.step(1)
    dut.io.fdi.lpStateReq.poke(PhyStateReq.active)
    dut.clock.step(1)

    // Observe the local active-request sideband burst before supplying remote request.
    waitForRdiSidebandTxBurst(dut, maxCycles = 80, reason = "FDI bring-up REQ_ACTIVE-side sideband")
    sendInboundSidebandMessage(dut, SideBandMessage.REQ_ACTIVE)

    waitUntil(dut, maxCycles = 60, reason = "fdi.plRxActiveReq high") {
      dut.io.fdi.plRxActiveReq.peek().litToBoolean
    }

    sendInboundSidebandMessage(dut, SideBandMessage.RSP_ACTIVE)
    dut.io.fdi.lpRxActiveStatus.poke(true.B)

    // Observe local response sideband burst after rx-active readiness and remote RSP.
    waitForRdiSidebandTxBurst(dut, maxCycles = 80, reason = "FDI bring-up RSP_ACTIVE-side sideband")

    waitUntil(dut, maxCycles = 60, reason = "top-level ACTIVE state") {
      dut.io.fdi.plStateStatus.peek().litValue == PhyState.active.litValue
    }
  }

  private def recoverFromLinkErrorToActive(dut: D2DAdapter): Unit = {
    // Minimal LINKERROR exit conditions in this RTL:
    // keep partner in LINKERROR while rx_deactive is true, then re-run bring-up.
    dut.io.fdi.lpRxActiveStatus.poke(false.B)
    dut.io.rdi.plStateStatus.poke(PhyState.linkError)

    waitUntil(dut, maxCycles = 60, reason = "LINKERROR -> RESET recovery") {
      dut.io.fdi.plStateStatus.peek().litValue == PhyState.reset.litValue
    }

    // Prevent immediate re-entry to LINKERROR once RESET is reached.
    dut.io.rdi.plStateStatus.poke(PhyState.reset)
    dut.io.rdi.plInbandPres.poke(false.B)
    dut.clock.step(1)

    driveToActive(dut)
  }

  private def newForwardTrafficEnv(
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
      // Forward top-level egress (`rdi.lpData`) has no stream metadata field.
      egressStreamId = () => RawStreamIds.UnknownStreamId
    )
    val sourceStabilityChecker = new TopLevelForwardSourceStabilityChecker(dut, driver)
    new ForwardTrafficEnv(
      driver = driver,
      ingressTracker = ingressTracker,
      egressMonitor = egressMonitor,
      sourceStabilityChecker = sourceStabilityChecker,
      expectedQ = expectedQ,
      scoreboard = scoreboard,
      maxExpectedQueueDepth = 0
    )
  }

  private def stepForwardTraffic(
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

    val accepted = env.ingressTracker.commitAfterEdge(ingressObs, env.expectedQ, enqueueExpected = true)
    if (accepted) env.driver.onAccepted()
    env.maxExpectedQueueDepth = math.max(env.maxExpectedQueueDepth, env.expectedQ.size)
    env.egressMonitor.commitAfterEdge(egressObs)
    if (accepted) ingressObs else None
  }

  private def drainForwardTraffic(
    dut: D2DAdapter,
    env: ForwardTrafficEnv,
    startCycle: Long,
    maxDrainCycles: Int = 128
  ): Long = {
    var cycle = startCycle
    var drained = 0
    while (drained < maxDrainCycles && env.expectedQ.nonEmpty) {
      stepForwardTraffic(dut, env, cycle, egressReady = true)
      cycle += 1
      drained += 1
    }
    cycle
  }

  private def finishForwardTraffic(env: ForwardTrafficEnv): Unit = {
    env.scoreboard.finishAndAssert(
      acceptedInputCount = env.ingressTracker.acceptedCount,
      maxExpectedQueueDepth = Some(env.maxExpectedQueueDepth)
    )
  }

  private def runForwardTrafficToCompletion(
    dut: D2DAdapter,
    beats: Seq[RawBeat],
    maxCycles: Int = 6000,
    egressReadyFn: Long => Boolean = _ => true,
    injectedSourceHoldoffFn: Long => Boolean = _ => false,
    gapCyclesBeforeBeat: Int => Int = _ => 0
  ): Unit = {
    var cycleRef = 0L
    val env = newForwardTrafficEnv(
      dut = dut,
      beats = beats,
      injectedSourceHoldoff = () => injectedSourceHoldoffFn(cycleRef),
      gapCyclesBeforeBeat = gapCyclesBeforeBeat
    )

    while (cycleRef < maxCycles && (!env.driver.isDone || env.expectedQ.nonEmpty)) {
      stepForwardTraffic(
        dut = dut,
        env = env,
        cycle = cycleRef,
        egressReady = egressReadyFn(cycleRef)
      )
      cycleRef += 1
    }
    assert(cycleRef < maxCycles, s"Timeout at $maxCycles cycles") // UNKNOWN: needs spec/RTL audit

    cycleRef = drainForwardTraffic(dut, env, cycleRef)
    finishForwardTraffic(env)
  }

  private def runReverseTrafficToCompletion(
    dut: D2DAdapter,
    beats: Seq[RawBeat],
    maxCycles: Int = 6000,
    injectedSourceHoldoffFn: Long => Boolean = _ => false,
    gapCyclesBeforeBeat: Int => Int = _ => 0
  ): Unit = {
    var cycleRef = 0L
    val expectedQ = mutable.Queue.empty[AcceptedBeat]
    val scoreboard = new Scoreboard(expectedQ = expectedQ, checkStreamId = true)
    val driver = new TopLevelReverseIngressDriver(
      dut = dut,
      beats = beats,
      injectedSourceHoldoff = () => injectedSourceHoldoffFn(cycleRef),
      gapCyclesBeforeBeat = gapCyclesBeforeBeat
    )
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
    assert(cycleRef < maxCycles, s"Timeout at $maxCycles cycles") // UNKNOWN: needs spec/RTL audit

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

  behavior of "D2DAdapterTopLevelStreamingRawSuite"

  it should "top-level bringup + forward traffic" in {
    test(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)
      dut.io.fdi.plStateStatus.expect(PhyState.active) // SPEC-DERIVED

      val beats = (0 until 96).map { i =>
        RawBeat(
          data = BigInt("A100000000000000", 16) + BigInt(i),
          streamId = if ((i & 1) == 0) RawStreamIds.Stack0Streaming else RawStreamIds.Stack1Streaming
        )
      }
      val readyPattern: Long => Boolean = cycle => (cycle % 7) < 4
      runForwardTrafficToCompletion(
        dut = dut,
        beats = beats,
        maxCycles = 8000,
        egressReadyFn = readyPattern
      )
    }
  }

  it should "top-level bringup + reverse traffic" in {
    test(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)
      dut.io.fdi.plStateStatus.expect(PhyState.active) // SPEC-DERIVED

      val beats = (0 until 80).map { i =>
        RawBeat(
          data = BigInt("B200000000000000", 16) + BigInt(i),
          // Reverse ingress has no stream field; this is vector metadata only.
          streamId = RawStreamIds.Stack0Streaming
        )
      }
      val gapFn: Int => Int = beatIdx => if ((beatIdx % 9) == 0) 2 else 0
      runReverseTrafficToCompletion(
        dut = dut,
        beats = beats,
        maxCycles = 8000,
        gapCyclesBeforeBeat = gapFn
      )
    }
  }

  it should "top-level active traffic + retrain" in {
    test(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)

      val beats = (0 until 192).map { i =>
        RawBeat(
          data = BigInt("C300000000000000", 16) + BigInt(i),
          streamId = if ((i & 1) == 0) RawStreamIds.Stack0Streaming else RawStreamIds.Stack1Streaming
        )
      }

      var sourceHoldoff = false
      val env = newForwardTrafficEnv(dut, beats, injectedSourceHoldoff = () => sourceHoldoff)

      var cycle = 0L
      val triggerCycle = 24L
      val ackDelayCycles = 10L
      var retrainRequested = false
      var retrainSeen = false
      var stallReqSeen = false
      var firstStallReqCycle = -1L
      var ackPulseIssued = false
      var ackHighRemaining = 0
      var stopBoundaryCycle: Option[Long] = None
      var acceptedAfterBoundary = 0L
      var acceptedOnBoundaryEdge = 0L
      var firstAcceptedAfterBoundaryDetail: Option[String] = None

      while (cycle < 2400 && (!retrainSeen || env.expectedQ.nonEmpty || cycle < triggerCycle + ackDelayCycles + 120)) {
        if (cycle == triggerCycle) {
          dut.io.rdi.plStateStatus.poke(PhyState.retrain)
          dut.io.fdi.lpRxActiveStatus.poke(false.B)
          retrainRequested = true
        }

        val fdiPlStallReqNow = dut.io.fdi.plStallReq.peek().litToBoolean
        if (fdiPlStallReqNow && !stallReqSeen) {
          stallReqSeen = true
          firstStallReqCycle = cycle
          sourceHoldoff = true
          stopBoundaryCycle = Some(cycle)
        }

        // Modeled protocol behavior: one delayed stall-ack pulse.
        if (stallReqSeen && !ackPulseIssued && ackHighRemaining == 0 && cycle - firstStallReqCycle >= ackDelayCycles) {
          ackHighRemaining = 1
          ackPulseIssued = true
        }
        dut.io.fdi.lpStallAck.poke((ackHighRemaining > 0).B)

        val acceptedBeat = stepForwardTraffic(
          dut = dut,
          env = env,
          cycle = cycle,
          egressReady = true,
          boundaryCrossed = stopBoundaryCycle.exists(cycle >= _),
          boundaryName = "retrain_stop_boundary"
        )
        if (ackHighRemaining > 0) ackHighRemaining -= 1

        acceptedBeat.foreach { beat =>
          if (stopBoundaryCycle.contains(cycle)) {
            acceptedOnBoundaryEdge += 1
          } else if (stopBoundaryCycle.exists(cycle > _)) {
            acceptedAfterBoundary += 1
            if (firstAcceptedAfterBoundaryDetail.isEmpty) {
              firstAcceptedAfterBoundaryDetail = Some(
                s"cycle=$cycle seq=${beat.seq} data=0x${beat.data.toString(16)} ${snapshot(dut)}"
              )
            }
          }
        }

        if (dut.io.fdi.plStateStatus.peek().litValue == PhyState.retrain.litValue) retrainSeen = true
        cycle += 1
      }

      cycle = drainForwardTraffic(dut, env, cycle)
      assert(retrainRequested, "Retrain trigger was not issued") // RTL-DERIVED
      assert(stallReqSeen, "fdi.plStallReq was never asserted") // SPEC-DERIVED
      assert(retrainSeen, "Top-level state never reached RETRAIN") // SPEC-DERIVED
      assert(
        acceptedAfterBoundary == 0L,
        s"Observed $acceptedAfterBoundary accepted beats after retrain stop boundary; " +
          s"acceptedOnBoundaryEdge=$acceptedOnBoundaryEdge " +
          s"firstViolation=${firstAcceptedAfterBoundaryDetail.getOrElse("none")}"
      ) // UNKNOWN: needs spec/RTL audit
      finishForwardTraffic(env)
    }
  }

  it should "top-level active traffic + linkerror" in {
    test(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)

      val beats = (0 until 192).map { i =>
        RawBeat(
          data = BigInt("D400000000000000", 16) + BigInt(i),
          streamId = if ((i & 1) == 0) RawStreamIds.Stack0Streaming else RawStreamIds.Stack1Streaming
        )
      }

      var sourceHoldoff = false
      val env = newForwardTrafficEnv(dut, beats, injectedSourceHoldoff = () => sourceHoldoff)

      var cycle = 0L
      val triggerCycle = 36L
      var linkErrorRequested = false
      var linkErrorSeen = false
      var stopBoundaryCycle: Option[Long] = None
      var acceptedAfterBoundary = 0L
      var acceptedOnBoundaryEdge = 0L
      var firstAcceptedAfterBoundaryDetail: Option[String] = None

      while (cycle < 1800 && (!linkErrorSeen || env.expectedQ.nonEmpty || cycle < triggerCycle + 120)) {
        if (cycle == triggerCycle) {
          dut.io.rdi.plStateStatus.poke(PhyState.linkError)
          dut.io.fdi.lpRxActiveStatus.poke(false.B)
          linkErrorRequested = true
        }

        dut.io.fdi.lpStallAck.poke(false.B)

        val inLinkError = dut.io.fdi.plStateStatus.peek().litValue == PhyState.linkError.litValue
        if (inLinkError) linkErrorSeen = true
        if (inLinkError && stopBoundaryCycle.isEmpty) {
          sourceHoldoff = true
          stopBoundaryCycle = Some(cycle)
        }

        val acceptedBeat = stepForwardTraffic(
          dut = dut,
          env = env,
          cycle = cycle,
          egressReady = true,
          boundaryCrossed = stopBoundaryCycle.exists(cycle >= _),
          boundaryName = "linkerror_stop_boundary"
        )

        acceptedBeat.foreach { beat =>
          if (stopBoundaryCycle.contains(cycle)) {
            acceptedOnBoundaryEdge += 1
          } else if (stopBoundaryCycle.exists(cycle > _)) {
            acceptedAfterBoundary += 1
            if (firstAcceptedAfterBoundaryDetail.isEmpty) {
              firstAcceptedAfterBoundaryDetail = Some(
                s"cycle=$cycle seq=${beat.seq} data=0x${beat.data.toString(16)} ${snapshot(dut)}"
              )
            }
          }
        }

        cycle += 1
      }

      cycle = drainForwardTraffic(dut, env, cycle)
      assert(linkErrorRequested, "LinkError trigger was not issued") // RTL-DERIVED
      assert(linkErrorSeen, "Top-level state never entered LINKERROR") // SPEC-DERIVED
      assert(stopBoundaryCycle.nonEmpty, "Did not observe stop boundary after LINKERROR entry") // UNKNOWN: needs spec/RTL audit
      assert(
        acceptedAfterBoundary == 0L,
        s"Observed $acceptedAfterBoundary accepted beats after LINKERROR stop boundary; " +
          s"acceptedOnBoundaryEdge=$acceptedOnBoundaryEdge " +
          s"firstViolation=${firstAcceptedAfterBoundaryDetail.getOrElse("none")}"
      ) // UNKNOWN: needs spec/RTL audit
      finishForwardTraffic(env)
    }
  }

  it should "top-level recovery + resumed traffic" in {
    test(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)

      // Phase A: baseline forward traffic while ACTIVE.
      val preBeats = (0 until 32).map(i => RawBeat(BigInt("E500000000000000", 16) + BigInt(i), RawStreamIds.Stack0Streaming))
      runForwardTrafficToCompletion(dut, preBeats, maxCycles = 5000)

      // Enter LINKERROR and recover through RESET -> ACTIVE path.
      dut.io.rdi.plStateStatus.poke(PhyState.linkError)
      dut.io.fdi.lpRxActiveStatus.poke(false.B)
      waitUntil(dut, maxCycles = 60, reason = "enter LINKERROR") {
        dut.io.fdi.plStateStatus.peek().litValue == PhyState.linkError.litValue
      }
      recoverFromLinkErrorToActive(dut)
      dut.io.fdi.plStateStatus.expect(PhyState.active) // SPEC-DERIVED

      // Leak probe before resuming traffic.
      var staleTransfers = 0
      for (_ <- 0 until 8) {
        dut.io.fdi.lpData.valid.poke(false.B)
        dut.io.fdi.lpData.irdy.poke(false.B)
        dut.io.fdi.lpStallAck.poke(false.B)
        dut.io.rdi.lpData.ready.poke(true.B)
        val transferred = dut.io.rdi.lpData.valid.peek().litToBoolean &&
          dut.io.rdi.lpData.irdy.peek().litToBoolean &&
          dut.io.rdi.lpData.ready.peek().litToBoolean
        if (transferred) staleTransfers += 1
        dut.clock.step(1)
      }
      assert(staleTransfers == 0, s"Observed $staleTransfers stale forward transfers before resumed traffic") // SPEC-DERIVED

      // Phase B: resumed traffic should be clean.
      val postBeats = (0 until 48).map(i => RawBeat(BigInt("E510000000000000", 16) + BigInt(i), RawStreamIds.Stack1Streaming))
      runForwardTrafficToCompletion(dut, postBeats, maxCycles = 6000)
    }
  }

  it should "top-level linkreset/disabled flow (linkreset-focused)" in {
    test(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)
      driveToActive(dut)

      // Request LINKRESET from protocol side.
      dut.io.fdi.lpStateReq.poke(PhyStateReq.linkReset)
      dut.io.fdi.lpRxActiveStatus.poke(false.B)

      waitForRdiSidebandTxBurst(dut, maxCycles = 120, reason = "LINKRESET request sideband")

      // Remote responds with LINKRESET response.
      sendInboundSidebandMessage(dut, SideBandMessage.RSP_LINKRESET)

      // Complete protocol-side stall handshake (one pulse).
      waitUntil(dut, maxCycles = 80, reason = "fdi.plStallReq asserted for linkreset transition") {
        dut.io.fdi.plStallReq.peek().litToBoolean
      }
      dut.io.fdi.lpStallAck.poke(true.B)
      dut.clock.step(1)
      dut.io.fdi.lpStallAck.poke(false.B)

      waitUntil(dut, maxCycles = 120, reason = "link state LINKRESET") {
        dut.io.fdi.plStateStatus.peek().litValue == PhyState.linkReset.litValue
      }
      dut.io.fdi.plStateStatus.expect(PhyState.linkReset) // SPEC-DERIVED
    }
  }

  it should "top-level clock-gating wake/ack handshake pins are tied-off in this RTL" in {
    test(new D2DAdapter(fdiParams, rdiParams, sbParams)) { dut =>
      initDut(dut)

      for (cycle <- 0 until 16) {
        val phase = (cycle % 4)
        dut.io.fdi.lpClkAck.poke((phase == 0 || phase == 1).B)
        dut.io.fdi.lpWakeReq.poke((phase == 0 || phase == 2).B)
        dut.io.rdi.plClkReq.poke((phase == 1 || phase == 3).B)
        dut.io.rdi.plWakeAck.poke((phase == 2 || phase == 3).B)

        // Top-level constants in current RTL:
        // - adapter always requests protocol clocks (`fdi.plClkReq = 1`)
        // - adapter always reports wake-ack to protocol (`fdi.plWakeAck = 1`)
        // - adapter always acknowledges PHY clock req (`rdi.lpClkAck = 1`)
        // - adapter always requests PHY wake (`rdi.lpWakeReq = 1`)
        dut.io.fdi.plClkReq.expect(true.B) // RTL-DERIVED
        dut.io.fdi.plWakeAck.expect(true.B) // RTL-DERIVED
        dut.io.rdi.lpClkAck.expect(true.B) // RTL-DERIVED
        dut.io.rdi.lpWakeReq.expect(true.B) // RTL-DERIVED

        dut.clock.step(1)
      }
    }
  }
}
