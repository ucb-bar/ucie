package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.simulator.scalatest.{ChiselSim, Cli}
import chisel3.util._
import edu.berkeley.cs.uciedigital.simutils.VerilatorCoverage
import org.scalatest.funspec.AnyFunSpec

import scala.collection.mutable
import scala.util.Random

/*
  Run:
    ./mill --no-daemon test.testOnly edu.berkeley.cs.uciedigital.sideband.SidebandChannelRandomTest

  Useful knobs:
    -- -Dscale=N              Multiplies random packet counts.
                              Defaults: Protocol=80, D2D=81, LogPHY=66.
    -- -DemitCoverage=1       Prints Verilator line/toggle/branch/expr/user
                              coverage; writes coverage.info and annotated/.
    -- -DemitCoveragePoints=1 Also writes annotated-points/ for exact misses.
                              This also enables Verilator coverage.
    -- -DenableTiming=1       Enables Verilator timing for temporal covers/asserts.
    -- -DemitVcd=1            Enables VCD dumping through ChiselSim.

  Adding a manual case:
    Add a CoverageTracker goal, drive the packet/control sequence near that
    layer's directed block, and call cov.hit only after adding the checker:
    scoreboard expectation, expected status flag, or explicit no-output assert.
    RTL covers belong in the module; hit them here with checked stimulus.

  Coverage focus:
    Protocol: layer/FDI routes, invalid routes, parity drops, backpressure, opcodes.
    D2D: all layer/FDI/RDI directions, invalid routes, parity drops, backpressure, opcodes.
    LogPHY: layer/RDI/link routes, link wait/widths, freeze, timeout, parity, opcodes.
*/
class SidebandChannelRandomTest extends AnyFunSpec with ChiselSim with Cli.Scale with VerilatorCoverage {
  // ============================================================================
  // Config
  // ============================================================================

  override protected def scaleHelpText: String =
    "multiplies constrained-random packet counts; defaults: Protocol=80, D2D=81, LogPHY=66"

  val msgW = 128
  val ncW = 32
  val sbLinkW = 1
  val numCredits = 8
  val desTimeoutCycles = 256
  val depths = SidebandPriorityQueueDepths(4, 4, 4, 4)
  val protocolDefaultRandomPackets = 80
  val d2dDefaultRandomPackets = 81
  val logPhyDefaultRandomPackets = 66

  val debugPrints = false
  def printDebug(msg: => String): Unit = if(debugPrints) println(s"[SidebandChannelRandomTest] $msg")

  sealed trait Port { def name: String }
  case object LayerPort extends Port { val name = "layer" }
  case object FdiPort extends Port { val name = "fdi" }
  case object RdiPort extends Port { val name = "rdi" }
  case object LinkPort extends Port { val name = "link" }

  case class ExpectedPacket(bits: BigInt, width: Int = msgW)

  // ============================================================================
  // Message Helpers
  // ============================================================================

  val allOpcodes = SBMsgOpcode.all.map(_.litValue)
  val noDataOpcodes = SBMsgOpcode.OpsWithoutData.map(_.litValue).toSet
  val noDpOpcodes = SBMsgOpcode.OpsThatDontUseDPField.map(_.litValue).toSet

  def mask(width: Int): BigInt = (BigInt(1) << width) - 1
  def hex(value: BigInt, width: Int): String = {
    val digits = math.max(1, (width + 3) / 4)
    val text = (value & mask(width)).toString(16)
    "0x" + ("0" * math.max(0, digits - text.length)) + text
  }
  def bin(value: BigInt, width: Int): String = {
    val text = (value & mask(width)).toString(2)
    "0b" + ("0" * math.max(0, width - text.length)) + text
  }
  def opcodeOf(msg: BigInt): BigInt = msg & BigInt(31)
  def dstOf(msg: BigInt): Int = ((msg >> 56) & BigInt(3)).toInt
  def remoteOf(msg: BigInt): Boolean = (((msg >> 58) & BigInt(1)) == 1)
  def bitWidthForOpcode(opcode: BigInt): Int = if(noDataOpcodes.contains(opcode)) 64 else 128

  def parity(value: BigInt, width: Int): BigInt = {
    var p = BigInt(0)
    for(i <- 0 until width) {
      p ^= ((value >> i) & 1)
    }
    p
  }

  def withBit(value: BigInt, bit: Int, set: BigInt): BigInt = {
    val cleared = value & ~(BigInt(1) << bit)
    cleared | ((set & 1) << bit)
  }

  def setParity(msg: BigInt): BigInt = {
    val opcode = opcodeOf(msg)
    val headerNoParity = msg & ~(BigInt(3) << 62)
    val header = headerNoParity & mask(64)
    val payload = (msg >> 64) & mask(64)
    val cp = parity(header & mask(62), 62)
    val dp = if(noDpOpcodes.contains(opcode)) BigInt(0) else parity(payload, 64)

    withBit(withBit(headerNoParity, 62, cp), 63, dp) & mask(128)
  }

  def corruptParity(msg: BigInt): BigInt = msg ^ (BigInt(1) << 62)
  def corruptDataParity(msg: BigInt): BigInt = msg ^ (BigInt(1) << 63)

  def priorityOpcode(rank: Int): BigInt = rank match {
    case 0 => SBMsgOpcode.MessageWithoutData.litValue
    case 1 => SBMsgOpcode.CompletionWithoutData.litValue
    case 2 => SBMsgOpcode.MemoryRead_32b.litValue
    case _ => SBMsgOpcode.ManagementPortMsgWithoutData.litValue
  }
  def priorityDepth(rank: Int): Int = rank match {
    case 0 => depths.messageRequestOrResponse
    case 1 => depths.regAccessCompletion
    case 2 => depths.regAccessRequest
    case _ => depths.other
  }

  def randomOpcode(rand: Random): BigInt = allOpcodes(rand.nextInt(allOpcodes.length))
  def isRegAccessCompletion(opcode: BigInt): Boolean = {
    opcode == SBMsgOpcode.CompletionWithoutData.litValue ||
    opcode == SBMsgOpcode.CompletionWith32bData.litValue ||
    opcode == SBMsgOpcode.CompletionWith64bData.litValue
  }
  def consumesCredit(msg: BigInt): Boolean = !isRegAccessCompletion(opcodeOf(msg))

  // Builds a packet and sets CP/DP for the chosen opcode.
  def makeMsg(dst: Int, remote: Boolean, opcode: BigInt, tag: BigInt, payloadOverride: Option[BigInt] = None): BigInt = {
    val basePayload = payloadOverride.getOrElse(((tag << 32) | BigInt("5a5a5a5a", 16)) & mask(64))
    val payload =
      if(bitWidthForOpcode(opcode) == 64) BigInt(0)
      else basePayload & mask(64)
    val header =
      opcode |
      ((tag & BigInt("ff", 16)) << 14) |
      (((tag >> 8) & BigInt("ff", 16)) << 32) |
      (BigInt(dst & 3) << 56) |
      (BigInt(if(remote) 1 else 0) << 58)

    setParity((payload << 64) | header)
  }

  def randomPayload(rand: Random): BigInt = BigInt(rand.nextLong()) & mask(64)
  def randomMsg(rand: Random, dst: Int, remote: Boolean, opcode: BigInt, tag: BigInt): BigInt =
    makeMsg(dst, remote, opcode, tag, Some(randomPayload(rand)))

  def normalized(msg: BigInt, width: Int): BigInt = msg & mask(width)

  // ============================================================================
  // Coverage
  // ============================================================================

  // Tracks Scala-side coverage goals and prints a compact summary.
  class CoverageTracker(names: Seq[String]) {
    private val hits = mutable.LinkedHashMap(names.map(_ -> false): _*)

    def hit(name: String): Unit = {
      require(hits.contains(name), s"Unknown coverage goal: $name")
      hits(name) = true
      printDebug(s"hit $name")
    }

    def isHit(name: String): Boolean = hits.getOrElse(name, false)
    def hitCount: Int = hits.values.count(identity)
    def total: Int = hits.size
    def unhit: Seq[String] = hits.collect { case (name, false) => name }.toSeq
    def allHit: Boolean = unhit.isEmpty
    def summary: String = s"$hitCount/$total goals hit"

    def expectAllHit(): Unit = {
      assert(allHit, s"Missing coverage goals ($summary): ${unhit.mkString(", ")}")
    }

    def chooseBiased(rand: Random): Option[String] = {
      val missing = unhit
      if(missing.nonEmpty && rand.nextInt(100) < 70) Some(missing(rand.nextInt(missing.length))) else None
    }
  }

  // ============================================================================
  // Scoreboard
  // ============================================================================

  // Holds expected packets per egress and compares only meaningful packet bits.
  class PacketScoreboard(name: String) {
    private val queues = mutable.Map[Port, mutable.Queue[ExpectedPacket]](
      LayerPort -> mutable.Queue.empty,
      FdiPort -> mutable.Queue.empty,
      RdiPort -> mutable.Queue.empty,
      LinkPort -> mutable.Queue.empty
    )

    def expect(port: Port, bits: BigInt, width: Int = msgW): Unit = {
      queues(port).enqueue(ExpectedPacket(normalized(bits, width), width))
      printDebug(s"$name expect ${port.name} width=$width bits=${hex(bits, width)}")
    }

    def observe(port: Port, bits: BigInt, width: Int = msgW): Unit = {
      assert(queues(port).nonEmpty,
        s"$name unexpected packet on ${port.name}: got=${hex(bits, width)} width=$width")
      val exp = queues(port).dequeue()
      val got = normalized(bits, exp.width)
      assert(got == exp.bits,
        s"$name mismatch on ${port.name}: got=${hex(got, exp.width)}, expected=${hex(exp.bits, exp.width)}, width=${exp.width}")
    }

    def isEmpty: Boolean = queues.values.forall(_.isEmpty)
  }

  // ============================================================================
  // Serial Capture Helpers
  // ============================================================================

  // Reassembles four NC beats into one 128-bit packet.
  class NcPacketCapture(port: Port, scoreboard: PacketScoreboard) {
    private var beats = 0
    private var data = BigInt(0)

    def observe(valid: Boolean, bits: BigInt): Option[BigInt] = {
      var completed: Option[BigInt] = None
      if(valid) {
        data |= (bits & mask(ncW)) << (beats * ncW)
        beats += 1
        if(beats == (msgW / ncW)) {
          val packet = data
          scoreboard.observe(port, data)
          beats = 0
          data = 0
          completed = Some(packet)
        }
      }
      completed
    }

    def idle: Boolean = beats == 0
  }

  // Reassembles link.out bits using the forwarded clock and checks the 32-cycle wait.
  class LinkPacketCapture(scoreboard: PacketScoreboard, cov: CoverageTracker) {
    private var data = BigInt(0)
    private var count = 0
    private var target = 0
    private var idleAfterFirstBeat = 0
    private var waitingAfterPacket = false
    private var idleAfterPacket = 0
    private var sawFinalWaitAcceptGap = false

    def observe(fwClock: Boolean, bit: BigInt): Unit = {
      if(fwClock) {
        if(waitingAfterPacket) {
          if(idleAfterPacket >= 32) sawFinalWaitAcceptGap = true
          waitingAfterPacket = false
          idleAfterPacket = 0
        }
        if(count == 64 && target == 128) {
          assert(idleAfterFirstBeat >= 32, s"link packet inter-beat wait was $idleAfterFirstBeat cycles")
          cov.hit("link_egress_32_cycle_wait")
        }

        data |= (bit & 1) << count
        count += 1

        if(count == 5) {
          target = bitWidthForOpcode(opcodeOf(data))
        }

        if(target != 0 && count == target) {
          cov.hit(if(target == 64) "link_egress_64b" else "link_egress_128b")
          scoreboard.observe(LinkPort, data, target)
          data = 0
          count = 0
          target = 0
          idleAfterFirstBeat = 0
          waitingAfterPacket = true
          idleAfterPacket = 0
        }
      } else {
        if(count >= 64 && target == 128 && count < 128) idleAfterFirstBeat += 1
        if(waitingAfterPacket) idleAfterPacket += 1
      }
    }

    def idle: Boolean = count == 0
    def sawFinalWaitAccept: Boolean = sawFinalWaitAcceptGap
    def clear(): Unit = {
      data = 0
      count = 0
      target = 0
      idleAfterFirstBeat = 0
      waitingAfterPacket = false
      idleAfterPacket = 0
      sawFinalWaitAcceptGap = false
    }
  }

  // ============================================================================
  // Harnesses
  // ============================================================================

  class ProtocolHarness extends Module {
    val io = IO(new Bundle {
      val layer = new Bundle {
        val in = Flipped(Decoupled(UInt(msgW.W)))
        val out = Decoupled(UInt(msgW.W))
        val status = new Bundle {
          val sbParityErr = Output(Bool())
          val rxPriorityQueuesFull = Output(Bool())
          val invalidRouteUpper = Output(Bool())
          val invalidRouteCurr = Output(Bool())
          val invalidRouteLower = Output(Bool())
        }
      }
      val fdi = new Bundle {
        val in = Flipped(Valid(UInt(ncW.W)))
        val out = Valid(UInt(ncW.W))
        val txCreditReturn = Input(Bool())
        val rxCreditReturn = Output(Bool())
      }
    })

    val dut = Module(new ProtocolSidebandChannel(msgW, ncW, numCredits, depths))
    dut.io <> io

    block(Verification) {
      block(Verification.Cover) {
        cover(io.layer.in.fire, "ProtocolLayerIngressFire")
        cover(io.layer.out.fire, "ProtocolLayerEgressFire")
        cover(io.fdi.in.valid, "ProtocolFdiIngressValid")
        cover(io.fdi.out.valid, "ProtocolFdiEgressValid")
        cover(io.layer.out.valid && !io.layer.out.ready, "ProtocolLayerOutBackpressure")
        cover(io.layer.status.sbParityErr, "ProtocolParityError")
        cover(io.layer.status.invalidRouteCurr, "ProtocolInvalidRouteCurr")
        cover(io.layer.status.invalidRouteLower, "ProtocolInvalidRouteLower")
      }
    }
  }

  class D2DHarness extends Module {
    val io = IO(new Bundle {
      val fdi = new Bundle {
        val in = Flipped(Valid(UInt(ncW.W)))
        val out = Valid(UInt(ncW.W))
        val txCreditReturn = Input(Bool())
        val rxCreditReturn = Output(Bool())
      }
      val rdi = new Bundle {
        val in = Flipped(Valid(UInt(ncW.W)))
        val out = Valid(UInt(ncW.W))
        val txCreditReturn = Input(Bool())
        val rxCreditReturn = Output(Bool())
      }
      val layer = new Bundle {
        val in = Flipped(Decoupled(UInt(msgW.W)))
        val out = Decoupled(UInt(msgW.W))
        val status = new Bundle {
          val sbParityErr = Output(Bool())
          val rxPriorityQueuesFull = Output(Bool())
          val invalidRouteUpper = Output(Bool())
          val invalidRouteCurr = Output(Bool())
          val invalidRouteLower = Output(Bool())
        }
      }
    })

    val dut = Module(new D2DSidebandChannel(msgW, ncW, ncW, numCredits, depths))
    dut.io <> io

    block(Verification) {
      block(Verification.Cover) {
        cover(io.layer.in.fire, "D2DLayerIngressFire")
        cover(io.layer.out.fire, "D2DLayerEgressFire")
        cover(io.fdi.in.valid, "D2DFdiIngressValid")
        cover(io.fdi.out.valid, "D2DFdiEgressValid")
        cover(io.rdi.in.valid, "D2DRdiIngressValid")
        cover(io.rdi.out.valid, "D2DRdiEgressValid")
        cover(io.layer.status.sbParityErr, "D2DParityError")
        cover(io.layer.status.invalidRouteUpper, "D2DInvalidRouteUpper")
        cover(io.layer.status.invalidRouteCurr, "D2DInvalidRouteCurr")
        cover(io.layer.status.invalidRouteLower, "D2DInvalidRouteLower")
      }
    }
  }

  class LogPhyHarness extends Module {
    val io = IO(new Bundle {
      val rdi = new Bundle {
        val in = Flipped(Valid(UInt(ncW.W)))
        val out = Valid(UInt(ncW.W))
        val txCreditReturn = Input(Bool())
        val rxCreditReturn = Output(Bool())
        val activity = Output(Bool())
      }
      val layer = new Bundle {
        val in = Flipped(Decoupled(UInt(msgW.W)))
        val out = Decoupled(UInt(msgW.W))
        val status = new Bundle {
          val sbParityErr = Output(Bool())
          val rxPriorityQueuesFull = Output(Bool())
          val desTimedout = Output(Bool())
          val invalidRouteUpper = Output(Bool())
          val invalidRouteCurr = Output(Bool())
          val invalidRouteLower = Output(Bool())
        }
      }
      val link = new Bundle {
        val in = new Bundle {
          val bits = Input(UInt(sbLinkW.W))
          val fwClock = Input(UInt(1.W))
        }
        val out = new Bundle {
          val bits = Output(UInt(sbLinkW.W))
          val fwClock = Output(UInt(1.W))
        }
        val ctrl = new Bundle {
          val txMode = Input(SBRxTxMode())
          val rxMode = Input(SBRxTxMode())
          val freezeAcceptingPackets = Input(Bool())
          val allPacketsSent = Output(Bool())
        }
      }
    })

    val dut = Module(new LogPhySidebandChannel(msgW, sbLinkW, ncW, numCredits, desTimeoutCycles, depths))
    dut.io <> io

    block(Verification) {
      block(Verification.Cover) {
        cover(io.layer.in.fire, "LogPhyLayerIngressFire")
        cover(io.layer.out.fire, "LogPhyLayerEgressFire")
        cover(io.rdi.in.valid, "LogPhyRdiIngressValid")
        cover(io.rdi.out.valid, "LogPhyRdiEgressValid")
        cover(io.link.in.fwClock.asBool, "LogPhyLinkIngressClock")
        cover(io.link.out.fwClock.asBool, "LogPhyLinkEgressClock")
        cover(io.link.ctrl.freezeAcceptingPackets, "LogPhyFreezeAcceptingPackets")
        cover(io.link.ctrl.allPacketsSent, "LogPhyAllPacketsSent")
        cover(io.layer.status.desTimedout, "LogPhyDeserializerTimeout")
        cover(io.layer.status.sbParityErr, "LogPhyParityError")
        cover(io.layer.status.invalidRouteUpper, "LogPhyInvalidRouteUpper")
        cover(io.layer.status.invalidRouteCurr, "LogPhyInvalidRouteCurr")
        cover(io.layer.status.invalidRouteLower, "LogPhyInvalidRouteLower")
      }
    }
  }

  // ============================================================================
  // Drive / Observe Helpers
  // ============================================================================

  // These route models feed the scoreboard; the DUT still decides the real route.
  def routeD2D(from: Port, msg: BigInt): Option[Port] = from match {
    case FdiPort   => if(!remoteOf(msg) && dstOf(msg) == 1) Some(LayerPort) else if(remoteOf(msg) || dstOf(msg) == 2) Some(RdiPort) else None
    case LayerPort => if(!remoteOf(msg) && dstOf(msg) == 0) Some(FdiPort) else if(remoteOf(msg) || dstOf(msg) == 2) Some(RdiPort) else None
    case RdiPort   => if(dstOf(msg) == 1) Some(LayerPort) else if(dstOf(msg) == 0) Some(FdiPort) else None
    case LinkPort  => None
  }

  def routeProtocol(from: Port, msg: BigInt): Option[Port] = from match {
    case LayerPort => if(remoteOf(msg) || dstOf(msg) == 1 || dstOf(msg) == 2) Some(FdiPort) else None
    case FdiPort   => if(dstOf(msg) == 0) Some(LayerPort) else None
    case _         => None
  }

  def routeLogPhy(from: Port, msg: BigInt): Option[Port] = from match {
    case RdiPort   => if(!remoteOf(msg) && dstOf(msg) == 2) Some(LayerPort) else if(remoteOf(msg)) Some(LinkPort) else None
    case LayerPort => if(!remoteOf(msg) && (dstOf(msg) == 0 || dstOf(msg) == 1)) Some(RdiPort) else if(remoteOf(msg)) Some(LinkPort) else None
    case LinkPort  => if(dstOf(msg) == 2) Some(LayerPort) else if(dstOf(msg) == 0 || dstOf(msg) == 1) Some(RdiPort) else None
    case _         => None
  }

  def outboundExpected(from: Port, to: Port, msg: BigInt): ExpectedPacket = {
    val width = bitWidthForOpcode(opcodeOf(msg))
    if(to == LinkPort) ExpectedPacket(setParity(msg), width)
    else if(from == LayerPort) ExpectedPacket(setParity(msg), width)
    else ExpectedPacket(msg, width)
  }

  // Groups opcodes into broad coverage classes used by every layer test.
  def noteOpcodeCoverage(cov: CoverageTracker, msg: BigInt): Unit = opcodeOf(msg) match {
    case op if op == SBMsgOpcode.MessageWithoutData.litValue || op == SBMsgOpcode.MessageWith64bData.litValue =>
      cov.hit("opcode_req_resp")
    case op if op == SBMsgOpcode.CompletionWithoutData.litValue ||
               op == SBMsgOpcode.CompletionWith32bData.litValue ||
               op == SBMsgOpcode.CompletionWith64bData.litValue =>
      cov.hit("opcode_completion")
    case op if ((op & BigInt(16)) == 0) =>
      cov.hit("opcode_request")
    case _ =>
      cov.hit("opcode_other")
  }

  def scaledPacketCount(default: Int): Int = math.max(1, scaled(default))
  def scaleValue: Double = getOption[Double]("scale").getOrElse(1.0)

  // Prints the resolved runtime knobs at the top of each layer test.
  def printRunConfig(name: String, seed: Long, randomPackets: Int, directedPackets: Int, cov: CoverageTracker): Unit = {
    val coverageText = if(coverageEnabled) s"on points=$emitCoveragePoints dir=$coverageDirectory" else "off"
    val timingText = if(enableTiming) "on" else "off"
    println(
      s"$name config: randomPackets=$randomPackets directedPackets=$directedPackets " +
      s"totalPackets=${randomPackets + directedPackets} scale=$scaleValue seed=${hex(BigInt(seed) & mask(64), 64)} " +
      s"goals=${cov.total} msgW=$msgW ncW=$ncW credits=$numCredits " +
      s"verilatorCoverage=$coverageText verilatorTiming=$timingText"
    )
  }

  def printCoverageSummary(name: String, cov: CoverageTracker): Unit = {
    println(s"$name coverage: ${cov.summary}")
  }

  // ============================================================================
  // Tests
  // ============================================================================

  describe("Sideband channel constrained-random integration") {
    it("Protocol channel routes, drops, backpressures, and reports coverage") {
      simulate(new ProtocolHarness) { c =>
        // ----- Protocol setup -----
        val seed = 0x50524f54L
        val rand = new Random(seed)
        val randomPackets = scaledPacketCount(protocolDefaultRandomPackets)
        val cov = new CoverageTracker(Seq(
          "layer_to_fdi_d2d", "layer_to_fdi_logphy", "fdi_to_layer",
          "invalid_route_curr", "invalid_route_lower", "parity_error_drop",
          "layer_out_backpressure", "opcode_req_resp", "opcode_completion",
          "opcode_request", "opcode_other", "layer_to_fdi_back_to_back",
          "fdi_cp_parity_drop", "fdi_dp_parity_drop", "fdi_partial_packet_abort",
          "fdi_credit_block", "fdi_completion_bypass", "fdi_credit_recovery",
          "switch_curr_backpressure", "fdi_rx_queue_full", "fdi_priority_contention"
        ))
        val sb = new PacketScoreboard("protocol")
        val fdiOut = new NcPacketCapture(FdiPort, sb)
        var fdiCreditReturn = false
        var forceFdiCreditReturn = false
        var autoFdiCredits = true
        var fdiQueueFullHits = 0
        val directedPackets =
          2 + 2 + 1 + numCredits + 3 + (0 until 4).map(priorityDepth(_) + 2).sum + 3
        printRunConfig("Protocol", seed, randomPackets, directedPackets, cov)

        // ----- Protocol helpers -----
        def observe(): Unit = {
          c.io.layer.status.invalidRouteCurr.peek().litToBoolean.option(cov.hit("invalid_route_curr"))
          c.io.layer.status.invalidRouteLower.peek().litToBoolean.option(cov.hit("invalid_route_lower"))
          c.io.layer.status.sbParityErr.peek().litToBoolean.option(cov.hit("parity_error_drop"))
          if(c.io.layer.status.rxPriorityQueuesFull.peek().litToBoolean) {
            fdiQueueFullHits += 1
            cov.hit("fdi_rx_queue_full")
          }
          if(c.io.layer.out.valid.peek().litToBoolean && !c.io.layer.out.ready.peek().litToBoolean) cov.hit("layer_out_backpressure")
          if(c.io.layer.out.valid.peek().litToBoolean && c.io.layer.out.ready.peek().litToBoolean) {
            sb.observe(LayerPort, c.io.layer.out.bits.peek().litValue)
          }
          fdiOut.observe(c.io.fdi.out.valid.peek().litToBoolean, c.io.fdi.out.bits.peek().litValue).foreach { packet =>
            fdiCreditReturn = consumesCredit(packet)
          }
        }

        def tick(): Unit = {
          c.io.fdi.txCreditReturn.poke(((autoFdiCredits && fdiCreditReturn) || forceFdiCreditReturn).B)
          fdiCreditReturn = false
          forceFdiCreditReturn = false
          observe()
          c.clock.step()
        }
        def expectGoalHit(name: String, clue: String): Unit = {
          var guard = 0
          while(!cov.isHit(name) && guard < 16) { tick(); guard += 1 }
          assert(cov.isHit(name), clue)
        }
        def idleInputs(): Unit = {
          c.io.layer.in.valid.poke(false.B)
          c.io.fdi.in.valid.poke(false.B)
          c.io.fdi.txCreditReturn.poke(false.B)
        }
        def drain(): Unit = {
          idleInputs()
          c.io.layer.out.ready.poke(true.B)
          var guard = 0
          while((!sb.isEmpty || !fdiOut.idle) && guard < 300) { tick(); guard += 1 }
          assert(sb.isEmpty && fdiOut.idle, "protocol scoreboard did not drain")
        }
        def sendLayer(msg: BigInt): Unit = {
          c.io.layer.in.bits.poke(msg.U(msgW.W))
          c.io.layer.in.valid.poke(true.B)
          while(!c.io.layer.in.ready.peek().litToBoolean) tick()
          tick()
          c.io.layer.in.valid.poke(false.B)
        }
        def sendFdi(msg: BigInt): Unit = {
          for(i <- 0 until 4) {
            c.io.fdi.in.bits.poke(((msg >> (i * ncW)) & mask(ncW)).U(ncW.W))
            c.io.fdi.in.valid.poke(true.B)
            tick()
          }
          c.io.fdi.in.valid.poke(false.B)
        }
        def sendLayerBurst(msgs: Seq[BigInt]): Unit = {
          var idx = 0
          while(idx < msgs.length) {
            c.io.layer.in.bits.poke(msgs(idx).U(msgW.W))
            c.io.layer.in.valid.poke(true.B)
            if(c.io.layer.in.ready.peek().litToBoolean) idx += 1
            tick()
          }
          c.io.layer.in.valid.poke(false.B)
        }
        def sendFdiPartial(msg: BigInt, beats: Int): Unit = {
          for(i <- 0 until beats) {
            c.io.fdi.in.bits.poke(((msg >> (i * ncW)) & mask(ncW)).U(ncW.W))
            c.io.fdi.in.valid.poke(true.B)
            tick()
          }
          c.io.fdi.in.valid.poke(false.B)
          tick()
          tick()
        }
        def expectRoute(from: Port, msg: BigInt, badParity: Boolean = false): Unit = {
          noteOpcodeCoverage(cov, msg)
          routeProtocol(from, msg).foreach { to =>
            if(!badParity) {
              val exp = outboundExpected(from, to, msg)
              sb.expect(to, exp.bits, exp.width)
            }
          }
        }
        // Drives FDI credit empty, buffered backpressure, recovery, and completion bypass.
        def exerciseFdiCreditPath(tagBase: Int): Unit = {
          drain()
          autoFdiCredits = false

          for(i <- 0 until numCredits) {
            val msg = makeMsg(1, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + i)
            expectRoute(LayerPort, msg)
            sendLayer(msg)
            drain()
          }

          val blocked = makeMsg(1, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + 20)
          sendLayer(blocked)
          for(_ <- 0 until 6) tick()
          assert(fdiOut.idle && sb.isEmpty, "protocol credit-blocked packet leaked before credit return")
          cov.hit("fdi_credit_block")

          val buffered = makeMsg(2, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + 22)
          c.io.layer.in.bits.poke(buffered.U(msgW.W))
          c.io.layer.in.valid.poke(true.B)
          assert(c.io.layer.in.ready.peek().litToBoolean, "protocol layer buffer did not accept packet behind credit block")
          tick()
          assert(!c.io.layer.in.ready.peek().litToBoolean, "protocol layer input did not backpressure behind credit block")
          tick()
          c.io.layer.in.valid.poke(false.B)
          cov.hit("switch_curr_backpressure")

          expectRoute(LayerPort, blocked)
          expectRoute(LayerPort, buffered)
          forceFdiCreditReturn = true
          tick()
          forceFdiCreditReturn = true
          tick()
          drain()
          cov.hit("fdi_credit_recovery")

          val completion = makeMsg(1, remote = false, SBMsgOpcode.CompletionWithoutData.litValue, tagBase + 21)
          expectRoute(LayerPort, completion)
          sendLayer(completion)
          drain()
          cov.hit("fdi_completion_bypass")

          for(_ <- 0 until numCredits) {
            forceFdiCreditReturn = true
            tick()
          }
          autoFdiCredits = true
        }
        def sendFdiToBlockedLayer(msg: BigInt): Boolean = {
          val before = fdiQueueFullHits
          sendFdi(msg)
          tick()
          val dropped = fdiQueueFullHits > before
          if(!dropped) sb.expect(LayerPort, msg)
          dropped
        }
        // Fills a selected FDI RX priority queue while layer output is stalled.
        def exerciseFdiQueueBackpressure(rank: Int, tagBase: Int): Unit = {
          drain()
          c.io.layer.out.ready.poke(false.B)

          val dummy = makeMsg(0, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          sendFdi(dummy)
          tick()
          sb.expect(LayerPort, dummy)

          val before = fdiQueueFullHits
          for(i <- 0 to priorityDepth(rank)) {
            val msg = makeMsg(0, remote = false, priorityOpcode(rank), tagBase + i + 1)
            sendFdiToBlockedLayer(msg)
          }
          assert(fdiQueueFullHits > before, s"protocol FDI RX queue did not report full for rank=$rank")

          c.io.layer.out.ready.poke(true.B)
          drain()
        }
        // Queues two FDI priorities and checks that the higher-priority packet wins.
        def exerciseFdiPriorityContention(tagBase: Int): Unit = {
          drain()
          c.io.layer.out.ready.poke(false.B)

          val dummy = makeMsg(0, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          val low = makeMsg(0, remote = false, priorityOpcode(3), tagBase + 1)
          val high = makeMsg(0, remote = false, priorityOpcode(0), tagBase + 2)

          sendFdi(dummy)
          tick()
          sb.expect(LayerPort, dummy)
          sendFdi(low)
          tick()
          sendFdi(high)
          tick()
          sb.expect(LayerPort, high)
          sb.expect(LayerPort, low)

          c.io.layer.out.ready.poke(true.B)
          drain()
          cov.hit("fdi_priority_contention")
        }

        idleInputs()
        c.io.layer.out.ready.poke(true.B)
        c.clock.step(5)

        // ----- Protocol directed cases -----
        // Verifies back-to-back layer packets can enter the FDI path.
        val burstMsgs = Seq(
          makeMsg(1, remote = false, SBMsgOpcode.MessageWithoutData.litValue, 100),
          makeMsg(2, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 101)
        )
        burstMsgs.foreach(expectRoute(LayerPort, _))
        sendLayerBurst(burstMsgs)
        cov.hit("layer_to_fdi_back_to_back")
        drain()

        // Verifies an FDI packet with bad control parity is dropped.
        sendFdi(corruptParity(makeMsg(0, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 109)))
        for(_ <- 0 until 4) tick()
        c.io.layer.status.sbParityErr.expect(true.B)
        cov.hit("fdi_cp_parity_drop")
        drain()

        // Verifies an FDI packet with bad data parity is dropped.
        sendFdi(corruptDataParity(makeMsg(0, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 110)))
        for(_ <- 0 until 4) tick()
        c.io.layer.status.sbParityErr.expect(true.B)
        cov.hit("fdi_dp_parity_drop")
        drain()

        // Verifies an incomplete FDI packet does not escape the deserializer.
        sendFdiPartial(makeMsg(0, remote = false, SBMsgOpcode.MessageWithoutData.litValue, 120), beats = 2)
        assert(sb.isEmpty && fdiOut.idle, "protocol partial FDI packet produced output")
        cov.hit("fdi_partial_packet_abort")

        // Exercises FDI credit empty, recovery, and completion bypass behavior.
        exerciseFdiCreditPath(tagBase = 130)
        // Fills each FDI RX priority queue against a stalled layer output.
        for(rank <- 0 until 4) exerciseFdiQueueBackpressure(rank, tagBase = 200 + rank * 20)
        // Creates two queued FDI priorities and checks priority order.
        exerciseFdiPriorityContention(tagBase = 300)

        // ----- Protocol random traffic -----
        // Biased random traffic fills any still-missing Protocol coverage goals.
        for(i <- 0 until randomPackets) {
          val tag = i + 1
          cov.chooseBiased(rand) match {
            case Some("layer_to_fdi_d2d") =>
              val msg = randomMsg(rand, 1, remote = false, randomOpcode(rand), tag)
              cov.hit("layer_to_fdi_d2d"); expectRoute(LayerPort, msg); sendLayer(msg)
            case Some("layer_to_fdi_logphy") =>
              val msg = randomMsg(rand, 2, remote = false, randomOpcode(rand), tag)
              cov.hit("layer_to_fdi_logphy"); expectRoute(LayerPort, msg); sendLayer(msg)
            case Some("fdi_to_layer") =>
              val msg = randomMsg(rand, 0, remote = false, randomOpcode(rand), tag)
              cov.hit("fdi_to_layer"); expectRoute(FdiPort, msg); sendFdi(msg)
            case Some("invalid_route_curr") =>
              sendLayer(randomMsg(rand, 0, remote = false, randomOpcode(rand), tag))
              expectGoalHit("invalid_route_curr", "protocol invalid current route status was not observed")
            case Some("invalid_route_lower") =>
              sendFdi(randomMsg(rand, 1, remote = false, randomOpcode(rand), tag))
              expectGoalHit("invalid_route_lower", "protocol invalid lower route status was not observed")
            case Some("parity_error_drop") =>
              sendFdi(corruptParity(randomMsg(rand, 0, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tag)))
              expectGoalHit("parity_error_drop", "protocol parity error status was not observed")
            case Some("layer_out_backpressure") =>
              val msg = randomMsg(rand, 0, remote = false, randomOpcode(rand), tag)
              c.io.layer.out.ready.poke(false.B)
              expectRoute(FdiPort, msg); sendFdi(msg)
              for(_ <- 0 until 3) tick()
              c.io.layer.out.ready.poke(true.B)
            case _ =>
              val from = if(rand.nextBoolean()) LayerPort else FdiPort
              val dst = if(from == LayerPort) Seq(1, 2, 0, 3)(rand.nextInt(4)) else Seq(0, 1, 3)(rand.nextInt(3))
              val msg = randomMsg(rand, dst, rand.nextInt(8) == 0, priorityOpcode(rand.nextInt(4)), tag)
              expectRoute(from, msg)
              if(from == LayerPort) sendLayer(msg) else sendFdi(msg)
          }
          drain()
        }

        printCoverageSummary("Protocol", cov)
        cov.expectAllHit()
      }
      writeVerilatorCoverageReports("Protocol")
    }

    it("D2D channel covers all route directions, errors, and parity drops") {
      simulate(new D2DHarness) { c =>
        // ----- D2D setup -----
        val seed = 0xd2d001L
        val rand = new Random(seed)
        val randomPackets = scaledPacketCount(d2dDefaultRandomPackets)
        val cov = new CoverageTracker(Seq(
          "layer_to_fdi", "layer_to_rdi", "fdi_to_layer", "fdi_to_rdi",
          "rdi_to_layer", "rdi_to_fdi", "invalid_route_upper",
          "invalid_route_curr", "invalid_route_lower", "fdi_parity_drop",
          "rdi_parity_drop", "layer_out_backpressure", "opcode_req_resp",
          "opcode_completion", "opcode_request", "opcode_other",
          "layer_to_fdi_back_to_back", "fdi_cp_parity_drop",
          "fdi_dp_parity_drop", "fdi_partial_packet_abort",
          "fdi_credit_block", "fdi_completion_bypass",
          "fdi_credit_recovery", "switch_curr_backpressure",
          "rdi_lower_backpressure", "switch_curr_contention",
          "switch_upper_contention", "switch_lower_contention",
          "fdi_rx_queue_full", "fdi_priority_contention"
        ))
        val sb = new PacketScoreboard("d2d")
        val fdiOut = new NcPacketCapture(FdiPort, sb)
        val rdiOut = new NcPacketCapture(RdiPort, sb)
        var fdiCreditReturn = false
        var rdiCreditReturn = false
        var forceFdiCreditReturn = false
        var forceRdiCreditReturn = false
        var autoFdiCredits = true
        var autoRdiCredits = true
        var fdiQueueFullHits = 0
        var sawParityErr = false
        val directedPackets =
          6 + 2 + 2 + 1 + numCredits + 3 + (0 until 4).map(priorityDepth(_) + 2).sum + 3 + 10
        printRunConfig("D2D", seed, randomPackets, directedPackets, cov)

        // ----- D2D helpers -----
        def observe(): Unit = {
          c.io.layer.status.invalidRouteUpper.peek().litToBoolean.option(cov.hit("invalid_route_upper"))
          c.io.layer.status.invalidRouteCurr.peek().litToBoolean.option(cov.hit("invalid_route_curr"))
          c.io.layer.status.invalidRouteLower.peek().litToBoolean.option(cov.hit("invalid_route_lower"))
          if(c.io.layer.status.sbParityErr.peek().litToBoolean) sawParityErr = true
          if(c.io.layer.status.rxPriorityQueuesFull.peek().litToBoolean) {
            fdiQueueFullHits += 1
            cov.hit("fdi_rx_queue_full")
          }
          if(c.io.layer.out.valid.peek().litToBoolean && !c.io.layer.out.ready.peek().litToBoolean) cov.hit("layer_out_backpressure")
          if(c.io.layer.out.valid.peek().litToBoolean && c.io.layer.out.ready.peek().litToBoolean) {
            sb.observe(LayerPort, c.io.layer.out.bits.peek().litValue)
          }
          fdiOut.observe(c.io.fdi.out.valid.peek().litToBoolean, c.io.fdi.out.bits.peek().litValue).foreach { packet =>
            fdiCreditReturn = consumesCredit(packet)
          }
          rdiOut.observe(c.io.rdi.out.valid.peek().litToBoolean, c.io.rdi.out.bits.peek().litValue).foreach { packet =>
            rdiCreditReturn = consumesCredit(packet)
          }
        }
        def tick(): Unit = {
          c.io.fdi.txCreditReturn.poke(((autoFdiCredits && fdiCreditReturn) || forceFdiCreditReturn).B)
          c.io.rdi.txCreditReturn.poke(((autoRdiCredits && rdiCreditReturn) || forceRdiCreditReturn).B)
          fdiCreditReturn = false
          rdiCreditReturn = false
          forceFdiCreditReturn = false
          forceRdiCreditReturn = false
          observe()
          c.clock.step()
        }
        def expectGoalHit(name: String, clue: String): Unit = {
          var guard = 0
          while(!cov.isHit(name) && guard < 16) { tick(); guard += 1 }
          assert(cov.isHit(name), clue)
        }
        def expectParitySeen(clue: String): Unit = {
          var guard = 0
          while(!sawParityErr && guard < 16) { tick(); guard += 1 }
          assert(sawParityErr, clue)
        }
        def idleInputs(): Unit = {
          c.io.layer.in.valid.poke(false.B)
          c.io.fdi.in.valid.poke(false.B)
          c.io.rdi.in.valid.poke(false.B)
          c.io.fdi.txCreditReturn.poke(false.B)
          c.io.rdi.txCreditReturn.poke(false.B)
        }
        def drain(): Unit = {
          idleInputs(); c.io.layer.out.ready.poke(true.B)
          var guard = 0
          while((!sb.isEmpty || !fdiOut.idle || !rdiOut.idle) && guard < 400) { tick(); guard += 1 }
          assert(sb.isEmpty && fdiOut.idle && rdiOut.idle, "d2d scoreboard did not drain")
        }
        def sendLayer(msg: BigInt): Unit = {
          c.io.layer.in.bits.poke(msg.U(msgW.W)); c.io.layer.in.valid.poke(true.B)
          while(!c.io.layer.in.ready.peek().litToBoolean) tick()
          tick(); c.io.layer.in.valid.poke(false.B)
        }
        def sendNc(port: Port, msg: BigInt): Unit = {
          for(i <- 0 until 4) {
            val chunk = ((msg >> (i * ncW)) & mask(ncW)).U(ncW.W)
            if(port == FdiPort) { c.io.fdi.in.bits.poke(chunk); c.io.fdi.in.valid.poke(true.B) }
            else { c.io.rdi.in.bits.poke(chunk); c.io.rdi.in.valid.poke(true.B) }
            tick()
          }
          if(port == FdiPort) c.io.fdi.in.valid.poke(false.B) else c.io.rdi.in.valid.poke(false.B)
        }
        def sendLayerBurst(msgs: Seq[BigInt]): Unit = {
          var idx = 0
          while(idx < msgs.length) {
            c.io.layer.in.bits.poke(msgs(idx).U(msgW.W))
            c.io.layer.in.valid.poke(true.B)
            if(c.io.layer.in.ready.peek().litToBoolean) idx += 1
            tick()
          }
          c.io.layer.in.valid.poke(false.B)
        }
        def sendNcPartial(port: Port, msg: BigInt, beats: Int): Unit = {
          for(i <- 0 until beats) {
            val chunk = ((msg >> (i * ncW)) & mask(ncW)).U(ncW.W)
            if(port == FdiPort) { c.io.fdi.in.bits.poke(chunk); c.io.fdi.in.valid.poke(true.B) }
            else { c.io.rdi.in.bits.poke(chunk); c.io.rdi.in.valid.poke(true.B) }
            tick()
          }
          if(port == FdiPort) c.io.fdi.in.valid.poke(false.B) else c.io.rdi.in.valid.poke(false.B)
          tick()
          tick()
        }
        def expectRoute(from: Port, msg: BigInt, badParity: Boolean = false): Unit = {
          noteOpcodeCoverage(cov, msg)
          routeD2D(from, msg).foreach { to =>
            if(!badParity) {
              val exp = outboundExpected(from, to, msg)
              sb.expect(to, exp.bits, exp.width)
            }
          }
        }
        def send(from: Port, msg: BigInt, badParity: Boolean = false): Unit = {
          expectRoute(from, msg, badParity)
          from match {
            case LayerPort => sendLayer(msg)
            case FdiPort => sendNc(FdiPort, msg)
            case RdiPort => sendNc(RdiPort, msg)
            case _ =>
          }
        }
        // Drives FDI credit empty, buffered backpressure, recovery, and completion bypass.
        def exerciseFdiCreditPath(tagBase: Int): Unit = {
          drain()
          autoFdiCredits = false

          for(i <- 0 until numCredits) {
            val msg = makeMsg(0, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + i)
            send(LayerPort, msg)
            drain()
          }

          val blocked = makeMsg(0, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + 20)
          sendLayer(blocked)
          for(_ <- 0 until 6) tick()
          assert(fdiOut.idle && rdiOut.idle && sb.isEmpty, "d2d credit-blocked packet leaked before credit return")
          cov.hit("fdi_credit_block")

          val buffered = makeMsg(0, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + 22)
          c.io.layer.in.bits.poke(buffered.U(msgW.W))
          c.io.layer.in.valid.poke(true.B)
          assert(c.io.layer.in.ready.peek().litToBoolean, "d2d layer buffer did not accept packet behind credit block")
          tick()
          assert(!c.io.layer.in.ready.peek().litToBoolean, "d2d layer input did not backpressure behind credit block")
          tick()
          c.io.layer.in.valid.poke(false.B)
          cov.hit("switch_curr_backpressure")

          expectRoute(LayerPort, blocked)
          expectRoute(LayerPort, buffered)
          forceFdiCreditReturn = true
          tick()
          forceFdiCreditReturn = true
          tick()
          drain()
          cov.hit("fdi_credit_recovery")

          val completion = makeMsg(0, remote = false, SBMsgOpcode.CompletionWithoutData.litValue, tagBase + 21)
          send(LayerPort, completion)
          drain()
          cov.hit("fdi_completion_bypass")

          for(_ <- 0 until numCredits) {
            forceFdiCreditReturn = true
            tick()
          }
          autoFdiCredits = true
        }
        def sendFdiToBlockedLayer(msg: BigInt): Boolean = {
          val before = fdiQueueFullHits
          sendNc(FdiPort, msg)
          tick()
          val dropped = fdiQueueFullHits > before
          if(!dropped) sb.expect(LayerPort, msg)
          dropped
        }
        // Fills a selected FDI RX priority queue while layer output is stalled.
        def exerciseFdiQueueBackpressure(rank: Int, tagBase: Int): Unit = {
          drain()
          c.io.layer.out.ready.poke(false.B)

          val dummy = makeMsg(1, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          sendNc(FdiPort, dummy)
          tick()
          sb.expect(LayerPort, dummy)

          val before = fdiQueueFullHits
          for(i <- 0 to priorityDepth(rank)) {
            val msg = makeMsg(1, remote = false, priorityOpcode(rank), tagBase + i + 1)
            sendFdiToBlockedLayer(msg)
          }
          assert(fdiQueueFullHits > before, s"d2d FDI RX queue did not report full for rank=$rank")

          c.io.layer.out.ready.poke(true.B)
          drain()
        }
        // Queues two FDI priorities and checks that the higher-priority packet wins.
        def exerciseFdiPriorityContention(tagBase: Int): Unit = {
          drain()
          c.io.layer.out.ready.poke(false.B)

          val dummy = makeMsg(1, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          val low = makeMsg(1, remote = false, priorityOpcode(3), tagBase + 1)
          val high = makeMsg(1, remote = false, priorityOpcode(0), tagBase + 2)

          sendNc(FdiPort, dummy)
          tick()
          sb.expect(LayerPort, dummy)
          sendNc(FdiPort, low)
          tick()
          sendNc(FdiPort, high)
          tick()
          sb.expect(LayerPort, high)
          sb.expect(LayerPort, low)

          c.io.layer.out.ready.poke(true.B)
          drain()
          cov.hit("fdi_priority_contention")
        }
        def sendFdiRdiPair(fdiMsg: BigInt, rdiMsg: BigInt): Unit = {
          for(i <- 0 until 4) {
            c.io.fdi.in.bits.poke(((fdiMsg >> (i * ncW)) & mask(ncW)).U(ncW.W))
            c.io.rdi.in.bits.poke(((rdiMsg >> (i * ncW)) & mask(ncW)).U(ncW.W))
            c.io.fdi.in.valid.poke(true.B)
            c.io.rdi.in.valid.poke(true.B)
            tick()
          }
          c.io.fdi.in.valid.poke(false.B)
          c.io.rdi.in.valid.poke(false.B)
        }
        def returnFdiCredits(count: Int): Unit =
          for(_ <- 0 until count) { forceFdiCreditReturn = true; tick() }
        def returnRdiCredits(count: Int): Unit =
          for(_ <- 0 until count) { forceRdiCreditReturn = true; tick() }
        def zeroFdiCredits(tagBase: Int): Unit = {
          autoFdiCredits = false
          for(i <- 0 until numCredits) {
            send(LayerPort, makeMsg(0, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + i))
            drain()
          }
        }
        def zeroRdiCredits(tagBase: Int): Unit = {
          autoRdiCredits = false
          for(i <- 0 until numCredits) {
            send(LayerPort, makeMsg(2, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + i))
            drain()
          }
        }
        // Creates current, upper, and lower switch egress contention cases.
        def exerciseD2DSwitchContention(tagBase: Int): Unit = {
          drain()

          val toLayer = makeMsg(1, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          expectRoute(FdiPort, toLayer)
          expectRoute(RdiPort, toLayer)
          sendFdiRdiPair(toLayer, toLayer)
          cov.hit("switch_curr_contention")
          drain()

          zeroFdiCredits(tagBase + 10)
          val toFdi = makeMsg(0, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase + 1)
          sendLayer(toFdi)
          for(_ <- 0 until 3) tick()
          sendNc(RdiPort, toFdi)
          sendLayer(toFdi)
          expectRoute(LayerPort, toFdi)
          expectRoute(RdiPort, toFdi)
          expectRoute(LayerPort, toFdi)
          for(_ <- 0 until 3) tick()
          cov.hit("switch_upper_contention")
          returnFdiCredits(3)
          drain()
          returnFdiCredits(numCredits)
          autoFdiCredits = true

          zeroRdiCredits(tagBase + 30)
          val toRdi = makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase + 2)
          sendLayer(toRdi)
          for(_ <- 0 until 3) tick()
          sendNc(FdiPort, toRdi)
          sendLayer(toRdi)
          expectRoute(LayerPort, toRdi)
          expectRoute(FdiPort, toRdi)
          expectRoute(LayerPort, toRdi)
          for(_ <- 0 until 3) tick()
          cov.hit("switch_lower_contention")
          returnRdiCredits(3)
          drain()
          returnRdiCredits(numCredits)
          autoRdiCredits = true
        }
        // Stalls layer output to prove lower RDI ingress backpressures correctly.
        def exerciseRdiLowerBackpressure(tagBase: Int): Unit = {
          drain()
          c.io.layer.out.ready.poke(false.B)

          val first = makeMsg(1, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          val second = makeMsg(1, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + 1)
          sendNc(RdiPort, first)
          tick()
          sb.expect(LayerPort, first)
          sendNc(RdiPort, second)
          tick()
          sb.expect(LayerPort, second)

          c.io.layer.out.ready.poke(true.B)
          drain()
          cov.hit("rdi_lower_backpressure")
        }

        idleInputs(); c.io.layer.out.ready.poke(true.B); c.clock.step(5)

        // ----- D2D directed cases -----
        // Directed route cases make each legal D2D egress observable.
        val directed = Seq[(String, Port, BigInt)](
          ("layer_to_fdi", LayerPort, makeMsg(0, false, SBMsgOpcode.MessageWithoutData.litValue, 1)),
          ("layer_to_rdi", LayerPort, makeMsg(2, false, SBMsgOpcode.MemoryRead_32b.litValue, 2)),
          ("fdi_to_layer", FdiPort, makeMsg(1, false, SBMsgOpcode.CompletionWithoutData.litValue, 3)),
          ("fdi_to_rdi", FdiPort, makeMsg(2, false, SBMsgOpcode.ManagementPortMsgWithoutData.litValue, 4)),
          ("rdi_to_layer", RdiPort, makeMsg(1, false, SBMsgOpcode.MemoryWrite_32b.litValue, 5)),
          ("rdi_to_fdi", RdiPort, makeMsg(0, false, SBMsgOpcode.MessageWithoutData.litValue, 6))
        )
        directed.foreach { case (goal, from, msg) => cov.hit(goal); send(from, msg); drain() }

        // Verifies back-to-back layer packets can enter the FDI path.
        val burstMsgs = Seq(
          makeMsg(0, remote = false, SBMsgOpcode.MessageWithoutData.litValue, 100),
          makeMsg(0, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 101)
        )
        burstMsgs.foreach(expectRoute(LayerPort, _))
        sendLayerBurst(burstMsgs)
        cov.hit("layer_to_fdi_back_to_back")
        drain()

        // Verifies an FDI packet with bad control parity is dropped.
        sendNc(FdiPort, corruptParity(makeMsg(1, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 109)))
        for(_ <- 0 until 4) tick()
        c.io.layer.status.sbParityErr.expect(true.B)
        cov.hit("fdi_cp_parity_drop")
        drain()

        // Verifies an FDI packet with bad data parity is dropped.
        sendNc(FdiPort, corruptDataParity(makeMsg(1, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 110)))
        for(_ <- 0 until 4) tick()
        c.io.layer.status.sbParityErr.expect(true.B)
        cov.hit("fdi_dp_parity_drop")
        drain()

        // Verifies an incomplete FDI packet does not escape the deserializer.
        sendNcPartial(FdiPort, makeMsg(1, remote = false, SBMsgOpcode.MessageWithoutData.litValue, 120), beats = 2)
        assert(sb.isEmpty && fdiOut.idle && rdiOut.idle, "d2d partial FDI packet produced output")
        cov.hit("fdi_partial_packet_abort")

        // Exercises FDI credit empty, recovery, and completion bypass behavior.
        exerciseFdiCreditPath(tagBase = 130)
        // Fills each FDI RX priority queue against a stalled layer output.
        for(rank <- 0 until 4) exerciseFdiQueueBackpressure(rank, tagBase = 200 + rank * 20)
        // Creates two queued FDI priorities and checks priority order.
        exerciseFdiPriorityContention(tagBase = 300)
        // Forces switch egress contention across current, upper, and lower outputs.
        exerciseD2DSwitchContention(tagBase = 320)
        // Stalls the layer output to check RDI lower-ingress backpressure.
        exerciseRdiLowerBackpressure(tagBase = 330)

        // ----- D2D random traffic -----
        // Biased random traffic fills error, parity, backpressure, and opcode coverage.
        for(i <- 0 until randomPackets) {
          val tag = i + 10
          cov.chooseBiased(rand) match {
            case Some("invalid_route_upper") =>
              send(FdiPort, randomMsg(rand, 3, false, randomOpcode(rand), tag))
              expectGoalHit("invalid_route_upper", "d2d invalid upper route status was not observed")
            case Some("invalid_route_curr") =>
              send(LayerPort, randomMsg(rand, 1, false, randomOpcode(rand), tag))
              expectGoalHit("invalid_route_curr", "d2d invalid current route status was not observed")
            case Some("invalid_route_lower") =>
              send(RdiPort, randomMsg(rand, 2, false, randomOpcode(rand), tag))
              expectGoalHit("invalid_route_lower", "d2d invalid lower route status was not observed")
            case Some("fdi_parity_drop") =>
              sawParityErr = false
              send(FdiPort, corruptParity(randomMsg(rand, 1, false, SBMsgOpcode.MemoryRead_32b.litValue, tag)), badParity = true)
              expectParitySeen("d2d FDI parity error status was not observed")
              cov.hit("fdi_parity_drop")
            case Some("rdi_parity_drop") =>
              sawParityErr = false
              send(RdiPort, corruptParity(randomMsg(rand, 1, false, SBMsgOpcode.MemoryRead_32b.litValue, tag)), badParity = true)
              expectParitySeen("d2d RDI parity error status was not observed")
              cov.hit("rdi_parity_drop")
            case Some("layer_out_backpressure") =>
              val msg = randomMsg(rand, 1, false, randomOpcode(rand), tag)
              c.io.layer.out.ready.poke(false.B)
              send(FdiPort, msg)
              for(_ <- 0 until 3) tick()
              c.io.layer.out.ready.poke(true.B)
            case _ =>
              val from = Seq(LayerPort, FdiPort, RdiPort)(rand.nextInt(3))
              val dst = rand.nextInt(4)
              send(from, randomMsg(rand, dst, rand.nextInt(6) == 0, priorityOpcode(rand.nextInt(4)), tag))
          }
          drain()
        }

        printCoverageSummary("D2D", cov)
        cov.expectAllHit()
      }
      writeVerilatorCoverageReports("D2D")
    }

    it("LogPHY channel covers RDI/layer/link integration, freeze, and timeout") {
      simulate(new LogPhyHarness) { c =>
        // ----- LogPHY setup -----
        val seed = 0x106f1234L
        val rand = new Random(seed)
        val randomPackets = scaledPacketCount(logPhyDefaultRandomPackets)
        val cov = new CoverageTracker(Seq(
          "layer_to_rdi", "layer_to_link", "rdi_to_layer", "rdi_to_link",
          "link_to_layer", "link_to_rdi", "invalid_route_upper",
          "invalid_route_curr", "invalid_route_lower", "rdi_parity_drop",
          "link_parity_drop", "link_timeout", "freeze_all_packets_sent",
          "link_egress_64b", "link_egress_128b", "link_egress_32_cycle_wait",
          "link_final_wait_accept",
          "opcode_req_resp", "opcode_completion", "opcode_request", "opcode_other",
          "layer_to_rdi_back_to_back", "rdi_cp_parity_drop",
          "rdi_dp_parity_drop", "rdi_partial_packet_abort",
          "rdi_credit_block", "rdi_completion_bypass",
          "rdi_credit_recovery", "switch_curr_backpressure",
          "rdi_rx_queue_full", "rdi_priority_contention",
          "link_raw_64b", "link_freeze_blocks_tx", "link_dp_parity_drop",
          "link_lower_backpressure", "switch_curr_contention",
          "switch_upper_contention", "switch_lower_contention",
          "link_rx_raw_64b", "link_rx_queue_full",
          "link_rx_backpressure", "link_serializer_reset_mid_packet"
        ))
        val sb = new PacketScoreboard("logphy")
        val rdiOut = new NcPacketCapture(RdiPort, sb)
        val linkOut = new LinkPacketCapture(sb, cov)
        var rdiCreditReturn = false
        var forceRdiCreditReturn = false
        var autoRdiCredits = true
        var rdiQueueFullHits = 0
        var sawParityErr = false
        val directedPackets =
          8 + 2 + 2 + 1 + numCredits + 3 + (0 until 4).map(priorityDepth(_) + 2).sum + 3 + 33
        printRunConfig("LogPHY", seed, randomPackets, directedPackets, cov)

        // ----- LogPHY helpers -----
        def observe(): Unit = {
          c.io.layer.status.invalidRouteUpper.peek().litToBoolean.option(cov.hit("invalid_route_upper"))
          c.io.layer.status.invalidRouteCurr.peek().litToBoolean.option(cov.hit("invalid_route_curr"))
          c.io.layer.status.invalidRouteLower.peek().litToBoolean.option(cov.hit("invalid_route_lower"))
          c.io.layer.status.desTimedout.peek().litToBoolean.option(cov.hit("link_timeout"))
          if(c.io.layer.status.sbParityErr.peek().litToBoolean) sawParityErr = true
          if(c.io.layer.status.rxPriorityQueuesFull.peek().litToBoolean) {
            rdiQueueFullHits += 1
            cov.hit("rdi_rx_queue_full")
          }
          if(c.io.link.ctrl.freezeAcceptingPackets.peek().litToBoolean &&
             c.io.link.ctrl.allPacketsSent.peek().litToBoolean) {
            cov.hit("freeze_all_packets_sent")
          }
          if(c.io.layer.out.valid.peek().litToBoolean && c.io.layer.out.ready.peek().litToBoolean) {
            sb.observe(LayerPort, c.io.layer.out.bits.peek().litValue)
          }
          rdiOut.observe(c.io.rdi.out.valid.peek().litToBoolean, c.io.rdi.out.bits.peek().litValue).foreach { packet =>
            rdiCreditReturn = consumesCredit(packet)
          }
          linkOut.observe(c.io.link.out.fwClock.peek().litValue != 0, c.io.link.out.bits.peek().litValue)
        }
        def tick(): Unit = {
          c.io.rdi.txCreditReturn.poke(((autoRdiCredits && rdiCreditReturn) || forceRdiCreditReturn).B)
          rdiCreditReturn = false
          forceRdiCreditReturn = false
          observe()
          c.clock.step()
        }
        def expectGoalHit(name: String, clue: String): Unit = {
          var guard = 0
          while(!cov.isHit(name) && guard < 16) { tick(); guard += 1 }
          assert(cov.isHit(name), clue)
        }
        def expectParitySeen(clue: String): Unit = {
          var guard = 0
          while(!sawParityErr && guard < 16) { tick(); guard += 1 }
          assert(sawParityErr, clue)
        }
        def idleInputs(): Unit = {
          c.io.layer.in.valid.poke(false.B)
          c.io.rdi.in.valid.poke(false.B)
          c.io.rdi.txCreditReturn.poke(false.B)
          c.io.link.in.bits.poke(0.U)
          c.io.link.in.fwClock.poke(0.U)
        }
        def drain(): Unit = {
          idleInputs(); c.io.layer.out.ready.poke(true.B)
          var guard = 0
          while((!sb.isEmpty || !rdiOut.idle || !linkOut.idle) && guard < 5000) { tick(); guard += 1 }
          assert(sb.isEmpty && rdiOut.idle && linkOut.idle, "logphy scoreboard did not drain")
        }
        def sendLayer(msg: BigInt): Unit = {
          c.io.layer.in.bits.poke(msg.U(msgW.W)); c.io.layer.in.valid.poke(true.B)
          while(!c.io.layer.in.ready.peek().litToBoolean) tick()
          tick(); c.io.layer.in.valid.poke(false.B)
        }
        def sendRdi(msg: BigInt): Unit = {
          for(i <- 0 until 4) {
            c.io.rdi.in.bits.poke(((msg >> (i * ncW)) & mask(ncW)).U(ncW.W))
            c.io.rdi.in.valid.poke(true.B)
            tick()
          }
          c.io.rdi.in.valid.poke(false.B)
        }
        def sendLink(msg: BigInt, stopAfterBits: Int = -1): Unit = {
          val width = bitWidthForOpcode(opcodeOf(msg))
          val totalBits = if(stopAfterBits >= 0) stopAfterBits else width
          for(i <- 0 until totalBits) {
            c.io.link.in.bits.poke(((msg >> i) & 1).U)
            c.io.link.in.fwClock.poke(1.U)
            tick()
            c.io.link.in.fwClock.poke(0.U)
            tick()
            if(width == 128 && i == 63) {
              for(_ <- 0 until 32) tick()
            }
          }
          c.io.link.in.bits.poke(0.U)
          c.io.link.in.fwClock.poke(0.U)
        }
        def sendLayerBurst(msgs: Seq[BigInt]): Unit = {
          var idx = 0
          while(idx < msgs.length) {
            c.io.layer.in.bits.poke(msgs(idx).U(msgW.W))
            c.io.layer.in.valid.poke(true.B)
            if(c.io.layer.in.ready.peek().litToBoolean) idx += 1
            tick()
          }
          c.io.layer.in.valid.poke(false.B)
        }
        def sendRdiPartial(msg: BigInt, beats: Int): Unit = {
          for(i <- 0 until beats) {
            c.io.rdi.in.bits.poke(((msg >> (i * ncW)) & mask(ncW)).U(ncW.W))
            c.io.rdi.in.valid.poke(true.B)
            tick()
          }
          c.io.rdi.in.valid.poke(false.B)
          tick()
          tick()
        }
        def expectRoute(from: Port, msg: BigInt, badParity: Boolean = false): Unit = {
          noteOpcodeCoverage(cov, msg)
          routeLogPhy(from, msg).foreach { to =>
            if(!badParity) {
              val exp = outboundExpected(from, to, msg)
              sb.expect(to, exp.bits, exp.width)
            }
          }
        }
        def send(from: Port, msg: BigInt, badParity: Boolean = false): Unit = {
          expectRoute(from, msg, badParity)
          from match {
            case LayerPort => sendLayer(msg)
            case RdiPort => sendRdi(msg)
            case LinkPort => sendLink(msg)
            case _ =>
          }
        }
        // Drives RDI credit empty, buffered backpressure, recovery, and completion bypass.
        def exerciseRdiCreditPath(tagBase: Int): Unit = {
          drain()
          autoRdiCredits = false

          for(i <- 0 until numCredits) {
            val msg = makeMsg(0, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + i)
            send(LayerPort, msg)
            drain()
          }

          val blocked = makeMsg(0, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + 20)
          sendLayer(blocked)
          for(_ <- 0 until 6) tick()
          assert(rdiOut.idle && linkOut.idle && sb.isEmpty, "logphy credit-blocked packet leaked before credit return")
          cov.hit("rdi_credit_block")

          val buffered = makeMsg(1, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + 22)
          c.io.layer.in.bits.poke(buffered.U(msgW.W))
          c.io.layer.in.valid.poke(true.B)
          assert(c.io.layer.in.ready.peek().litToBoolean, "logphy layer buffer did not accept packet behind credit block")
          tick()
          assert(!c.io.layer.in.ready.peek().litToBoolean, "logphy layer input did not backpressure behind credit block")
          tick()
          c.io.layer.in.valid.poke(false.B)
          cov.hit("switch_curr_backpressure")

          expectRoute(LayerPort, blocked)
          expectRoute(LayerPort, buffered)
          forceRdiCreditReturn = true
          tick()
          forceRdiCreditReturn = true
          tick()
          drain()
          cov.hit("rdi_credit_recovery")

          val completion = makeMsg(0, remote = false, SBMsgOpcode.CompletionWithoutData.litValue, tagBase + 21)
          send(LayerPort, completion)
          drain()
          cov.hit("rdi_completion_bypass")

          for(_ <- 0 until numCredits) {
            forceRdiCreditReturn = true
            tick()
          }
          autoRdiCredits = true
        }
        def sendRdiToBlockedLayer(msg: BigInt): Boolean = {
          val before = rdiQueueFullHits
          sendRdi(msg)
          tick()
          val dropped = rdiQueueFullHits > before
          if(!dropped) sb.expect(LayerPort, msg)
          dropped
        }
        // Fills a selected RDI RX priority queue while layer output is stalled.
        def exerciseRdiQueueBackpressure(rank: Int, tagBase: Int): Unit = {
          drain()
          c.io.layer.out.ready.poke(false.B)

          val dummy = makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          sendRdi(dummy)
          tick()
          sb.expect(LayerPort, dummy)

          val before = rdiQueueFullHits
          for(i <- 0 to priorityDepth(rank)) {
            val msg = makeMsg(2, remote = false, priorityOpcode(rank), tagBase + i + 1)
            sendRdiToBlockedLayer(msg)
          }
          assert(rdiQueueFullHits > before, s"logphy RDI RX queue did not report full for rank=$rank")

          c.io.layer.out.ready.poke(true.B)
          drain()
        }
        // Queues two RDI priorities and checks that the higher-priority packet wins.
        def exerciseRdiPriorityContention(tagBase: Int): Unit = {
          drain()
          c.io.layer.out.ready.poke(false.B)

          val dummy = makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          val low = makeMsg(2, remote = false, priorityOpcode(3), tagBase + 1)
          val high = makeMsg(2, remote = false, priorityOpcode(0), tagBase + 2)

          sendRdi(dummy)
          tick()
          sb.expect(LayerPort, dummy)
          sendRdi(low)
          tick()
          sendRdi(high)
          tick()
          sb.expect(LayerPort, high)
          sb.expect(LayerPort, low)

          c.io.layer.out.ready.poke(true.B)
          drain()
          cov.hit("rdi_priority_contention")
        }
        def returnRdiCredits(count: Int): Unit =
          for(_ <- 0 until count) { forceRdiCreditReturn = true; tick() }
        def zeroRdiCredits(tagBase: Int): Unit = {
          autoRdiCredits = false
          for(i <- 0 until numCredits) {
            send(LayerPort, makeMsg(0, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + i))
            drain()
          }
        }
        // Creates link/layer/RDI switch contention and lower-link backpressure cases.
        def exerciseLogPhySwitchContention(tagBase: Int): Unit = {
          drain()
          c.io.layer.out.ready.poke(false.B)

          val lowerFirst = makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          val lowerSecond = makeMsg(2, remote = false, SBMsgOpcode.MemoryRead_32b.litValue, tagBase + 1)
          expectRoute(LinkPort, lowerFirst)
          sendLink(lowerFirst)
          expectRoute(LinkPort, lowerSecond)
          sendLink(lowerSecond)
          c.io.layer.out.ready.poke(true.B)
          drain()
          cov.hit("link_lower_backpressure")

          c.io.layer.out.ready.poke(false.B)
          val dummy = makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase + 10)
          val toLayer = makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase + 11)
          expectRoute(RdiPort, dummy)
          sendRdi(dummy)
          tick()
          expectRoute(LinkPort, toLayer)
          sendLink(toLayer)
          expectRoute(RdiPort, toLayer)
          sendRdi(toLayer)
          c.io.layer.out.ready.poke(true.B)
          drain()
          cov.hit("switch_curr_contention")

          zeroRdiCredits(tagBase + 20)
          val toRdi = makeMsg(0, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase + 30)
          sendLayer(toRdi)
          for(_ <- 0 until 3) tick()
          sendLink(toRdi)
          sendLayer(toRdi)
          expectRoute(LayerPort, toRdi)
          expectRoute(LinkPort, toRdi)
          expectRoute(LayerPort, toRdi)
          for(_ <- 0 until 3) tick()
          cov.hit("switch_upper_contention")
          returnRdiCredits(3)
          drain()
          returnRdiCredits(numCredits)
          autoRdiCredits = true

          val toLink = makeMsg(0, remote = true, SBMsgOpcode.MessageWithoutData.litValue, tagBase + 40)
          c.io.link.ctrl.freezeAcceptingPackets.poke(true.B)
          expectRoute(RdiPort, toLink)
          sendRdi(toLink)
          expectRoute(LayerPort, toLink)
          sendLayer(toLink)
          for(_ <- 0 until 4) tick()
          cov.hit("switch_lower_contention")
          c.io.link.ctrl.freezeAcceptingPackets.poke(false.B)
          drain()
        }
        def sendLinkToBlockedLayer(msg: BigInt): Unit = {
          sendLink(msg)
          tick()
          val exp = outboundExpected(LinkPort, LayerPort, msg)
          sb.expect(LayerPort, exp.bits, exp.width)
        }
        // Fills the link RX path while layer output is stalled and checks recovery.
        def exerciseLinkRxBackpressure(tagBase: Int): Unit = {
          drain()
          c.io.layer.out.ready.poke(false.B)

          val dummy = makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase)
          expectRoute(LinkPort, dummy)
          sendLink(dummy)

          val before = rdiQueueFullHits
          for(i <- 0 until (priorityDepth(0) + 2)) {
            val msg = makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, tagBase + i + 1)
            sendLinkToBlockedLayer(msg)
          }
          assert(rdiQueueFullHits > before, "logphy link RX queue did not report full")

          c.io.layer.out.ready.poke(true.B)
          drain()
          cov.hit("link_rx_queue_full")
          cov.hit("link_rx_backpressure")
        }
        // Resets while the link serializer is actively sending a packet.
        def exerciseLinkSerializerReset(tagBase: Int): Unit = {
          drain()
          val msg = makeMsg(0, remote = true, SBMsgOpcode.MemoryWrite_64b.litValue, tagBase)
          c.io.layer.in.bits.poke(msg.U(msgW.W))
          c.io.layer.in.valid.poke(true.B)
          while(!c.io.layer.in.ready.peek().litToBoolean) tick()
          tick()
          c.io.layer.in.valid.poke(false.B)
          for(_ <- 0 until 12) tick()
          c.reset.poke(true.B)
          c.clock.step(5)
          c.reset.poke(false.B)
          c.clock.step(20)
          linkOut.clear()
          for(_ <- 0 until 4) {
            c.io.link.out.fwClock.expect(0.U)
            tick()
          }
          assert(sb.isEmpty && rdiOut.idle && linkOut.idle, "logphy reset mid-packet leaked output")
          cov.hit("link_serializer_reset_mid_packet")
        }

        idleInputs()
        c.io.layer.out.ready.poke(true.B)
        c.io.link.ctrl.txMode.poke(SBRxTxMode.PACKET)
        c.io.link.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.link.ctrl.freezeAcceptingPackets.poke(false.B)
        c.reset.poke(true.B); c.clock.step(5); c.reset.poke(false.B); c.clock.step(20)

        // ----- LogPHY directed cases -----
        // Directed route cases cover every legal LogPHY ingress-to-egress path.
        val directed = Seq[(String, Port, BigInt)](
          ("layer_to_rdi", LayerPort, makeMsg(0, false, SBMsgOpcode.MessageWithoutData.litValue, 1)),
          ("layer_to_link", LayerPort, makeMsg(0, true, SBMsgOpcode.MemoryWrite_64b.litValue, 2)),
          ("rdi_to_layer", RdiPort, makeMsg(2, false, SBMsgOpcode.CompletionWithoutData.litValue, 3)),
          ("rdi_to_link", RdiPort, makeMsg(0, true, SBMsgOpcode.MessageWith64bData.litValue, 4)),
          ("link_to_layer", LinkPort, makeMsg(2, false, SBMsgOpcode.MemoryRead_32b.litValue, 5)),
          ("link_to_rdi", LinkPort, makeMsg(1, false, SBMsgOpcode.ManagementPortMsgWithoutData.litValue, 6))
        )
        directed.foreach { case (goal, from, msg) => cov.hit(goal); send(from, msg); drain() }

        // Verifies back-to-back layer packets can enter the RDI path.
        val burstMsgs = Seq(
          makeMsg(0, remote = false, SBMsgOpcode.MessageWithoutData.litValue, 100),
          makeMsg(1, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 101)
        )
        burstMsgs.foreach(expectRoute(LayerPort, _))
        sendLayerBurst(burstMsgs)
        cov.hit("layer_to_rdi_back_to_back")
        drain()

        // Verifies an RDI packet with bad control parity is dropped.
        sendRdi(corruptParity(makeMsg(2, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 109)))
        for(_ <- 0 until 4) tick()
        c.io.layer.status.sbParityErr.expect(true.B)
        cov.hit("rdi_cp_parity_drop")
        drain()

        // Verifies an RDI packet with bad data parity is dropped.
        sendRdi(corruptDataParity(makeMsg(2, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 110)))
        for(_ <- 0 until 4) tick()
        c.io.layer.status.sbParityErr.expect(true.B)
        cov.hit("rdi_dp_parity_drop")
        drain()

        // Verifies an incomplete RDI packet does not escape the deserializer.
        sendRdiPartial(makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, 120), beats = 2)
        assert(sb.isEmpty && rdiOut.idle && linkOut.idle, "logphy partial RDI packet produced output")
        cov.hit("rdi_partial_packet_abort")

        // Exercises RDI credit empty, recovery, and completion bypass behavior.
        exerciseRdiCreditPath(tagBase = 130)
        // Fills each RDI RX priority queue against a stalled layer output.
        for(rank <- 0 until 4) exerciseRdiQueueBackpressure(rank, tagBase = 200 + rank * 20)
        // Creates two queued RDI priorities and checks priority order.
        exerciseRdiPriorityContention(tagBase = 300)

        // Resets before link-mode cases so sticky status does not leak between cases.
        c.reset.poke(true.B); c.clock.step(5); c.reset.poke(false.B); c.clock.step(20)
        c.io.link.ctrl.txMode.poke(SBRxTxMode.PACKET)
        c.io.link.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.link.ctrl.freezeAcceptingPackets.poke(false.B)

        // Verifies the link serializer accepts the next packet in final wait.
        val finalWaitBurst = Seq(
          makeMsg(0, remote = true, SBMsgOpcode.MemoryWrite_64b.litValue, 313),
          makeMsg(0, remote = true, SBMsgOpcode.MessageWithoutData.litValue, 314)
        )
        linkOut.clear()
        finalWaitBurst.foreach(expectRoute(LayerPort, _))
        sendLayerBurst(finalWaitBurst)
        drain()
        assert(linkOut.sawFinalWaitAccept, "logphy link packets were not separated by at least 32 idle cycles")
        cov.hit("link_final_wait_accept")

        // Verifies RAW TX mode can serialize a 64-bit link packet.
        c.io.link.ctrl.txMode.poke(SBRxTxMode.RAW)
        val rawMsg = makeMsg(0, remote = true, SBMsgOpcode.MessageWithoutData.litValue, 310)
        expectRoute(LayerPort, rawMsg)
        sendLayer(rawMsg)
        cov.hit("link_raw_64b")
        drain()
        c.io.link.ctrl.txMode.poke(SBRxTxMode.PACKET)

        // Verifies freeze holds a link-bound packet until accepting resumes.
        val freezeBlockMsg = makeMsg(0, remote = true, SBMsgOpcode.MessageWithoutData.litValue, 311)
        c.io.link.ctrl.freezeAcceptingPackets.poke(true.B)
        c.io.layer.in.bits.poke(freezeBlockMsg.U(msgW.W))
        c.io.layer.in.valid.poke(true.B)
        var freezeGuard = 0
        while(!c.io.layer.in.ready.peek().litToBoolean && freezeGuard < 10) { tick(); freezeGuard += 1 }
        assert(freezeGuard < 10, "logphy freeze packet did not enter layer buffer")
        tick()
        c.io.layer.in.valid.poke(false.B)
        for(_ <- 0 until 4) tick()
        expectRoute(LayerPort, freezeBlockMsg)
        cov.hit("link_freeze_blocks_tx")
        c.io.link.ctrl.freezeAcceptingPackets.poke(false.B)
        drain()

        // Verifies a link packet with bad data parity is dropped.
        sendLink(corruptDataParity(makeMsg(2, remote = false, SBMsgOpcode.MemoryWrite_64b.litValue, 312)))
        for(_ <- 0 until 20) tick()
        c.io.layer.status.sbParityErr.expect(true.B)
        cov.hit("link_dp_parity_drop")
        drain()

        // Forces switch contention across LogPHY current, upper, and lower outputs.
        exerciseLogPhySwitchContention(tagBase = 330)

        // Verifies RAW RX mode accepts a 64-bit link packet.
        c.io.link.ctrl.rxMode.poke(SBRxTxMode.RAW)
        val rawRxMsg = makeMsg(2, remote = false, SBMsgOpcode.MessageWithoutData.litValue, 380)
        expectRoute(LinkPort, rawRxMsg)
        sendLink(rawRxMsg)
        cov.hit("link_rx_raw_64b")
        drain()
        c.io.link.ctrl.rxMode.poke(SBRxTxMode.PACKET)

        // Fills the link RX queue under layer backpressure and checks drain.
        exerciseLinkRxBackpressure(tagBase = 390)
        // Resets in the middle of link serialization and checks cleanup.
        exerciseLinkSerializerReset(tagBase = 410)
        c.io.link.ctrl.txMode.poke(SBRxTxMode.PACKET)
        c.io.link.ctrl.rxMode.poke(SBRxTxMode.PACKET)
        c.io.link.ctrl.freezeAcceptingPackets.poke(false.B)

        // Verifies allPacketsSent asserts while a frozen link-bound packet drains.
        val freezeMsg = makeMsg(0, true, SBMsgOpcode.MemoryWrite_64b.litValue, 20)
        cov.hit("layer_to_link")
        send(LayerPort, freezeMsg)
        c.io.link.ctrl.freezeAcceptingPackets.poke(true.B)
        drain()
        for(_ <- 0 until 50) tick()
        c.io.link.ctrl.allPacketsSent.expect(true.B)
        cov.hit("freeze_all_packets_sent")
        c.io.link.ctrl.freezeAcceptingPackets.poke(false.B)

        // Verifies an incomplete link RX packet times out.
        val timeoutMsg = makeMsg(2, false, SBMsgOpcode.MemoryRead_32b.litValue, 21)
        sendLink(timeoutMsg, stopAfterBits = 20)
        c.clock.step(desTimeoutCycles + 5)
        c.io.layer.status.desTimedout.expect(true.B)
        cov.hit("link_timeout")
        c.reset.poke(true.B); c.clock.step(5); c.reset.poke(false.B); c.clock.step(20)

        // ----- LogPHY random traffic -----
        // Biased random traffic fills invalid route, parity, and opcode coverage.
        for(i <- 0 until randomPackets) {
          val tag = i + 30
          cov.chooseBiased(rand) match {
            case Some("invalid_route_upper") =>
              send(RdiPort, randomMsg(rand, 3, false, randomOpcode(rand), tag))
              expectGoalHit("invalid_route_upper", "logphy invalid upper route status was not observed")
            case Some("invalid_route_curr") =>
              send(LayerPort, randomMsg(rand, 2, false, randomOpcode(rand), tag))
              expectGoalHit("invalid_route_curr", "logphy invalid current route status was not observed")
            case Some("invalid_route_lower") =>
              send(LinkPort, randomMsg(rand, 3, false, randomOpcode(rand), tag))
              expectGoalHit("invalid_route_lower", "logphy invalid lower route status was not observed")
            case Some("rdi_parity_drop") =>
              sawParityErr = false
              send(RdiPort, corruptParity(randomMsg(rand, 2, false, SBMsgOpcode.MemoryRead_32b.litValue, tag)), badParity = true)
              expectParitySeen("logphy RDI parity error status was not observed")
              cov.hit("rdi_parity_drop")
            case Some("link_parity_drop") =>
              sawParityErr = false
              send(LinkPort, corruptParity(randomMsg(rand, 2, false, SBMsgOpcode.MemoryRead_32b.litValue, tag)), badParity = true)
              expectParitySeen("logphy link parity error status was not observed")
              cov.hit("link_parity_drop")
            case _ =>
              val from = Seq(LayerPort, RdiPort, LinkPort)(rand.nextInt(3))
              val dst = from match {
                case LayerPort => Seq(0, 1, 2, 3)(rand.nextInt(4))
                case RdiPort => Seq(2, 0, 3)(rand.nextInt(3))
                case LinkPort => Seq(2, 0, 1, 3)(rand.nextInt(4))
                case _ => 0
              }
              send(from, randomMsg(rand, dst, rand.nextInt(5) == 0, priorityOpcode(rand.nextInt(4)), tag))
          }
          drain()
        }

        printCoverageSummary("LogPHY", cov)
        cov.expectAllHit()
      }
      writeVerilatorCoverageReports("LogPHY")
    }
  }

  implicit class BooleanOption(private val cond: Boolean) {
    def option(body: => Unit): Unit = if(cond) body
  }
}
