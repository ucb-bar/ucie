package edu.berkeley.cs.uciedigital.tilelink

import scala.util.Random

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import chisel3.util.experimental.BoringUtils
import chisel3.simulator.HasSimulator
import chisel3.simulator.scalatest.ChiselSim
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.diplomacy.{
  AddressSet,
  IdRange,
  RegionType,
  TransferSizes
}
import freechips.rocketchip.tilelink._
import org.scalatest.funspec.AnyFunSpec

/** One die's half of a PHY-less UCIe TL link: the same manager and client
  * nodes as `UcieTL`, with a `UcieTLProtocol` between them and its frame
  * interface brought out, so two of these can be wired back to back.
  */
class UcieTLProtocolDie(
    params: UcieTLParams,
    region: AddressSet,
    beatBytes: Int
)(implicit p: Parameters)
    extends LazyModule {
  // Requests from this die to the partner.
  val managerNode = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        managers = Seq(
          TLSlaveParameters.v1(
            address = Seq(region),
            regionType = RegionType.UNCACHED,
            supportsGet = TransferSizes(1, beatBytes),
            supportsPutFull = TransferSizes(1, beatBytes),
            supportsPutPartial = TransferSizes(1, beatBytes),
            fifoId = Some(0)
          )
        ),
        beatBytes = beatBytes
      )
    )
  )
  // The partner's requests, issued on this die.
  val clientNode = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "ucie-client",
            sourceId = IdRange(0, 1 << params.clientIdBits)
          )
        )
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (managerTl, _) = managerNode.in(0)
    val (clientTl, _) = clientNode.out(0)
    val protocol = Module(
      new UcieTLProtocol(params, managerTl.params, clientTl.params)
    )
    val io = IO(new Bundle {
      val tx = Decoupled(UInt(protocol.frameBits.W))
      val rx = Flipped(Valid(UInt(protocol.frameBits.W)))
      val creditRetEnable = Input(Bool())
    })
    protocol.io.manager <> managerTl
    clientTl <> protocol.io.client
    io.tx <> protocol.io.tx
    protocol.io.rx := io.rx
    protocol.io.creditFlowEnable := true.B
    protocol.io.creditRetEnable := io.creditRetEnable
  }
}

/** When to hold a channel off.
  *
  * @param percent chance, in percent, that any one cycle is stalled
  * @param windows `(start, length)` cycle ranges that are stalled outright
  */
case class Stalls(percent: Int = 0, windows: Seq[(Int, Int)] = Nil) {
  require(percent >= 0 && percent < 100)

  /** High on cycles the channel may move. */
  def allow(cycle: UInt, seed: Int): Bool = {
    val random =
      if (percent == 0) true.B
      else {
        val lfsr = LFSR(16, seed = Some((seed % 65535) + 1))
        lfsr(6, 0) >= (percent * 128 / 100).U
      }
    val inWindow = windows
      .map { case (start, len) =>
        cycle >= start.U && cycle < (start + len).U
      }
      .foldLeft(false.B)(_ || _)
    random && !inWindow
  }
}

/** Passes TL traffic through, holding A and D off on cycles they are not
  * allowed to move.
  */
class TLThrottle(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val allowA = Input(Bool())
      val allowD = Input(Bool())
    })
    (node.in zip node.out).foreach { case ((in, _), (out, _)) =>
      out <> in
      out.a.valid := in.a.valid && io.allowA
      in.a.ready := out.a.ready && io.allowA
      in.d.valid := out.d.valid && io.allowD
      out.d.ready := in.d.ready && io.allowD
    }
  }
}

/** One scripted request, with the data a Get must return. */
case class UcieTLRequest(
    address: BigInt,
    isWrite: Boolean,
    data: BigInt
)

/** Issues a script of single-beat Puts and Gets, up to `nSources` at a time,
  * and scoreboards every response against it.
  *
  * Like testchipip's `OffchipRouterTestDriver`, but with many requests in
  * flight, so the link's credits actually run out.
  */
class UcieTLTrafficDriver(
    name: String,
    reqs: Seq[UcieTLRequest],
    nSources: Int,
    beatBytes: Int
)(implicit p: Parameters)
    extends LazyModule {
  val node = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, nSources)
          )
        )
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val start = Input(Bool())
      val dReady = Input(Bool())
      val finished = Output(Bool())
      val error = Output(Bool())
      /** Index of the request whose response was wrong. */
      val errorReq = Output(UInt(32.W))
    })

    val (tl, edge) = node.out(0)
    val nReqs = reqs.size
    val idxBits = log2Ceil(nReqs + 1)
    val lgSize = log2Ceil(beatBytes)

    // Padded by one so the index of "past the end" is still in range.
    val padded = reqs :+ UcieTLRequest(0, isWrite = false, data = 0)
    val addrs = VecInit(padded.map(_.address.U(edge.bundle.addressBits.W)))
    val writes = VecInit(padded.map(_.isWrite.B))
    val datas = VecInit(padded.map(_.data.U(edge.bundle.dataBits.W)))

    val reqIdx = RegInit(0.U(idxBits.W))
    val respCount = RegInit(0.U(idxBits.W))
    val busy = RegInit(0.U(nSources.W))
    val inflight = Reg(Vec(nSources, UInt(idxBits.W)))
    val started = RegInit(false.B)
    when(io.start) { started := true.B }

    val freeSource = PriorityEncoder(~busy)
    val haveFree = !busy.andR

    tl.a.valid := started && reqIdx < nReqs.U && haveFree
    tl.a.bits := Mux(
      writes(reqIdx),
      edge.Put(freeSource, addrs(reqIdx), lgSize.U, datas(reqIdx))._2,
      edge.Get(freeSource, addrs(reqIdx), lgSize.U)._2
    )

    val aSet = WireDefault(0.U(nSources.W))
    when(tl.a.fire) {
      inflight(freeSource) := reqIdx
      aSet := UIntToOH(freeSource, nSources)
      reqIdx := reqIdx + 1.U
    }

    // Scoreboard: every response must answer a request in flight on its
    // source, with the right opcode, and a Get's data must be what the script
    // says was last written there.
    tl.d.ready := io.dReady
    val error = RegInit(false.B)
    val errorReq = RegInit(0.U(32.W))
    val dClear = WireDefault(0.U(nSources.W))
    when(tl.d.fire) {
      val src = tl.d.bits.source
      val idx = inflight(src)
      val known = busy(src)
      val wantData = !writes(idx)
      val opOk = Mux(
        wantData,
        tl.d.bits.opcode === TLMessages.AccessAckData,
        tl.d.bits.opcode === TLMessages.AccessAck
      )
      val dataOk = !wantData || tl.d.bits.data === datas(idx)
      val ok = known && opOk && dataOk && !tl.d.bits.denied
      when(!ok && !error) {
        error := true.B
        errorReq := idx
      }
      dClear := UIntToOH(src, nSources)
      respCount := respCount + 1.U
    }
    busy := (busy | aSet) & ~dClear

    io.finished := respCount === nReqs.U
    io.error := error
    io.errorReq := errorReq
  }
}

/** How one test run stresses the link. */
case class UcieTLScenario(
    nReqsAtoB: Int = 200,
    nReqsBtoA: Int = 0,
    nSources: Int = 128,
    linkLatency: Int = 4,
    // Backpressure on each die's RAM, i.e. on the partner's requests arriving.
    sinkStallsA: Stalls = Stalls(),
    sinkStallsB: Stalls = Stalls(),
    // Backpressure on each driver taking its responses.
    respStallsA: Stalls = Stalls(),
    respStallsB: Stalls = Stalls(),
    // Cycles each die's TX can't put a frame on the link.
    linkStalls: Stalls = Stalls(),
    // Whether `creditRetEnable` follows `tx.ready`, or is held high the way
    // `UcieTL` drives it. Either must work.
    creditRetFollowsTxReady: Boolean = true,
    seed: Int = 1
)

/** What a run found. */
class UcieTLCheckIO extends Bundle {
  val done = Output(Bool())
  val driverErrorA = Output(Bool())
  val driverErrorB = Output(Bool())
  val driverErrorReqA = Output(UInt(32.W))
  val driverErrorReqB = Output(UInt(32.W))
  // A frame arrived at a full RX buffer and was dropped. Per die, A then D.
  val overflow = Output(Vec(2, Vec(2, Bool())))
  // A die held more credits than the partner has buffer slots. Per die,
  // A then D.
  val overCredit = Output(Vec(2, Vec(2, Bool())))
  // Credits held per die and channel. Once the link has gone quiet, with every
  // buffer drained and every return delivered, this is `tlBufferDepth`.
  val credits = Output(Vec(2, Vec(2, UInt(8.W))))
  val firstErrorCycle = Output(UInt(32.W))
}

/** Two dies joined by their frame links, each with a driver issuing requests
  * to the partner and a RAM answering the partner's requests. The monitors tap
  * the credit counters and RX buffers inside each `UcieTLProtocol`.
  */
class UcieTLProtocolTest(val scenario: UcieTLScenario)(implicit p: Parameters)
    extends LazyModule {
  val params = UcieTLParams()
  val beatBytes = UcieTLFrame.dataBits / 8
  val region = AddressSet(0x10000, 0xffff)

  /** A random script against `region`, over a small set of lines so reads see
    * earlier writes. Every Get targets a line already written, since the RAM
    * starts out random.
    */
  def script(n: Int, seed: Int): Seq[UcieTLRequest] = {
    val rand = new Random(seed)
    val lines = Seq.tabulate(32)(i => region.base + i * beatBytes)
    val mem = scala.collection.mutable.Map[BigInt, BigInt]()
    Seq.fill(n) {
      val addr = lines(rand.nextInt(lines.size))
      if (mem.contains(addr) && rand.nextBoolean()) {
        UcieTLRequest(addr, isWrite = false, data = mem(addr))
      } else {
        val data = BigInt(UcieTLFrame.dataBits, rand)
        mem(addr) = data
        UcieTLRequest(addr, isWrite = true, data = data)
      }
    }
  }

  val dies =
    Seq.fill(2)(LazyModule(new UcieTLProtocolDie(params, region, beatBytes)))
  val drivers = Seq(
    (scenario.nReqsAtoB, "driverA"),
    (scenario.nReqsBtoA, "driverB")
  ).zipWithIndex.map { case ((n, name), i) =>
    LazyModule(
      new UcieTLTrafficDriver(
        name,
        script(n, scenario.seed + i),
        scenario.nSources,
        beatBytes
      )
    )
  }
  val throttles = Seq.fill(2)(LazyModule(new TLThrottle))
  val rams = Seq.fill(2)(
    LazyModule(new TLRAM(region, cacheable = false, beatBytes = beatBytes))
  )

  for (i <- 0 until 2) {
    dies(i).managerNode := drivers(i).node
    rams(i).node := throttles(i).node := dies(i).clientNode
  }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new UcieTLCheckIO)
    val s = scenario

    val cycle = RegInit(0.U(32.W))
    cycle := cycle + 1.U

    drivers.foreach(_.module.io.start := true.B)
    drivers(0).module.io.dReady := s.respStallsA.allow(cycle, s.seed * 10 + 1)
    drivers(1).module.io.dReady := s.respStallsB.allow(cycle, s.seed * 10 + 2)
    throttles(0).module.io.allowA := s.sinkStallsA.allow(cycle, s.seed * 10 + 3)
    throttles(1).module.io.allowA := s.sinkStallsB.allow(cycle, s.seed * 10 + 4)
    throttles.foreach(_.module.io.allowD := true.B)

    // The link: each die's frames reach the other `linkLatency` cycles later.
    for (i <- 0 until 2) {
      val from = dies(i).module
      val to = dies(1 - i).module
      val ready = s.linkStalls.allow(cycle, s.seed * 10 + 5 + i)
      from.io.tx.ready := ready
      from.io.creditRetEnable :=
        (if (s.creditRetFollowsTxReady) ready else true.B)
      val sent = Wire(Valid(UInt(from.protocol.frameBits.W)))
      sent.valid := from.io.tx.fire
      sent.bits := from.io.tx.bits
      to.io.rx := ShiftRegister(
        sent,
        s.linkLatency,
        0.U.asTypeOf(sent),
        true.B
      )
    }

    // Monitors.
    val depth = params.tlBufferDepth
    val counterBits = log2Up(params.creditCounterSize)
    def held(counter: CreditCounter): UInt = {
      val gnt = BoringUtils.tapAndRead(counter.cred_gnt)
      val used = BoringUtils.tapAndRead(counter.cred_used)
      (gnt - used)(counterBits - 1, 0)
    }
    def dropped(buffer: Queue[_ <: Data]): Bool =
      BoringUtils.tapAndRead(buffer.io.enq.valid) &&
        !BoringUtils.tapAndRead(buffer.io.enq.ready)

    val anyError = WireDefault(false.B)
    for (i <- 0 until 2) {
      val proto = dies(i).module.protocol
      val heldNow = Seq(held(proto.aCreditCounter), held(proto.dCreditCounter))
      val droppedNow = Seq(dropped(proto.rxABuffer), dropped(proto.rxDBuffer))
      for (ch <- 0 until 2) {
        val over = RegInit(false.B)
        when(heldNow(ch) > depth.U) { over := true.B }
        io.overCredit(i)(ch) := over
        val ovf = RegInit(false.B)
        when(droppedNow(ch)) { ovf := true.B }
        io.overflow(i)(ch) := ovf
        io.credits(i)(ch) := heldNow(ch)
        when(over || ovf) { anyError := true.B }
      }
    }

    io.driverErrorA := drivers(0).module.io.error
    io.driverErrorB := drivers(1).module.io.error
    io.driverErrorReqA := drivers(0).module.io.errorReq
    io.driverErrorReqB := drivers(1).module.io.errorReq
    when(io.driverErrorA || io.driverErrorB) { anyError := true.B }
    val sawError = RegInit(false.B)
    val firstErrorCycle = RegInit(0.U(32.W))
    when(anyError && !sawError) {
      sawError := true.B
      firstErrorCycle := cycle
    }
    io.firstErrorCycle := firstErrorCycle

    // Done once both scripts have completed and the link has been quiet long
    // enough for every owed credit to go back (the return timer fires every
    // 2^creditRetTimerWidth cycles).
    val quiet = 4 * (1 << params.creditRetTimerWidth) + 4 * s.linkLatency
    val finished = drivers.map(_.module.io.finished).reduce(_ && _)
    val quietCycles = RegInit(0.U(32.W))
    when(finished) { quietCycles := quietCycles + 1.U }
    io.done := quietCycles >= quiet.U
  }
}

/** Credit flow in `UcieTLProtocol`, end to end.
  *
  * Two dies' protocols are joined by a delayed frame link, and real TL traffic
  * runs over it: a scripted driver with a scoreboard on each die's manager
  * side, and a `TLRAM` behind a throttle on each die's client side, so every
  * read must return what was written. Monitors inside each protocol check that
  * no RX buffer drops a frame, that no die ever holds more credits than the
  * partner has buffer slots, and that once the link goes quiet each die holds
  * exactly `tlBufferDepth` credits per channel.
  *
  * Waveforms land in
  * `build/chiselsim/UcieTLProtocolSpec/<describe>/<it>/workdir-vcs/trace.fsdb`.
  */
class UcieTLProtocolSpec extends AnyFunSpec with ChiselSim {
  implicit val simulator: HasSimulator = HasSimulator.simulators.vcs(
    svsim.CommonCompilationSettings(),
    svsim.vcs.Backend.CompilationSettings(
      // FSDB, for Verdi. The Verdi release has to match VCS's (`current` is
      // V-2023.12-SP1-1); set VERDI_HOME to use another.
      traceSettings = svsim.vcs.Backend.CompilationSettings.TraceSettings(
        fsdbSettings = Some(
          svsim.vcs.Backend.CompilationSettings.TraceSettings.FsdbSettings(
            sys.env.getOrElse(
              "VERDI_HOME",
              "/ecad/tools/synopsys/verdi/V-2023.12-SP1-1"
            )
          )
        )
      ),
      waitForLicenseIfUnavailable = true
    )
  )

  val depth = UcieTLParams().tlBufferDepth

  def run(scenario: UcieTLScenario, timeout: Int = 50000): Unit = {
    implicit val p: Parameters = Parameters.empty
    // Applied outside `simulate`, so a failed simulation can't leave an
    // unapplied LazyModule in the global scope (see TileLinkSpec).
    val dut = LazyModule(new UcieTLProtocolTest(scenario))
    simulate(dut.module) { c =>
      enableWaves()
      c.reset.poke(true.B)
      c.clock.step(5)
      c.reset.poke(false.B)

      var cycles = 0
      while (!c.io.done.peek().litToBoolean && cycles < timeout) {
        c.clock.step(16)
        cycles += 16
      }

      val dies = Seq("A", "B")
      val chans = Seq("A", "D")
      val done = c.io.done.peek().litToBoolean
      val problems = scala.collection.mutable.ArrayBuffer[String]()
      if (!done) {
        problems += s"traffic did not complete within $timeout cycles"
      }
      if (c.io.driverErrorA.peek().litToBoolean) {
        problems += "driver A got a wrong response to request " +
          c.io.driverErrorReqA.peek().litValue
      }
      if (c.io.driverErrorB.peek().litToBoolean) {
        problems += "driver B got a wrong response to request " +
          c.io.driverErrorReqB.peek().litValue
      }
      for (i <- 0 until 2; ch <- 0 until 2) {
        val where = s"die ${dies(i)} ${chans(ch)} channel"
        if (c.io.overflow(i)(ch).peek().litToBoolean) {
          problems += s"$where: RX buffer dropped a frame"
        }
        if (c.io.overCredit(i)(ch).peek().litToBoolean) {
          problems += s"$where: held more than $depth credits"
        }
        if (done) {
          val credits = c.io.credits(i)(ch).peek().litValue
          if (credits != depth) {
            problems += s"$where: holds $credits credits at rest, expected $depth"
          }
        }
      }
      if (problems.nonEmpty && c.io.firstErrorCycle.peek().litValue != 0) {
        problems += s"first error at cycle ${c.io.firstErrorCycle.peek().litValue}"
      }
      assert(problems.isEmpty, problems.mkString("\n  ", "\n  ", ""))
    }
  }

  describe("UcieTLProtocol") {
    it("carries one-way traffic with no backpressure") {
      run(UcieTLScenario())
    }

    it("carries two-way traffic with no backpressure") {
      run(UcieTLScenario(nReqsBtoA = 200))
    }

    it("carries two-way traffic over a long link") {
      run(UcieTLScenario(nReqsBtoA = 200, linkLatency = 40))
    }

    it("survives random backpressure on the receiving RAMs") {
      // Frames carrying returned credits wait in the RX buffer behind requests
      // the RAM is holding off (CREDIT_STATUS.md bug 1).
      run(
        UcieTLScenario(
          nReqsBtoA = 200,
          sinkStallsA = Stalls(percent = 50),
          sinkStallsB = Stalls(percent = 50)
        )
      )
    }

    it("survives a receiving RAM stalling until the sender runs out of credits") {
      // A fills B's RX A buffer while B's RAM is stalled. A's driver also
      // stops taking responses for a while, so the ones B sent before the
      // stall wait in A's RX D buffer. Once they drain, A owes B D credits
      // but, out of A credits and with nothing to answer, can only return them
      // in a credit-only frame, which lands in the full buffer
      // (CREDIT_STATUS.md bug 3). While bug 1 is there it trips first: the
      // responses waiting in A's RX D buffer over-credit A, which then sends
      // B more A beats than B has room for.
      run(
        UcieTLScenario(
          nReqsAtoB = 400,
          linkLatency = 40,
          sinkStallsB = Stalls(windows = Seq((300, 2000))),
          respStallsA = Stalls(windows = Seq((300, 300)))
        )
      )
    }

    it("survives random backpressure on the responses") {
      run(
        UcieTLScenario(
          nReqsBtoA = 200,
          respStallsA = Stalls(percent = 50),
          respStallsB = Stalls(percent = 50)
        )
      )
    }

    it("survives link stalls when credit returns wait for the link") {
      run(UcieTLScenario(nReqsBtoA = 200, linkStalls = Stalls(percent = 30)))
    }

    it("survives link stalls with credit returns enabled as on UcieTL's mainband") {
      // Returns offered while the link can't take them must stay owed
      // (CREDIT_STATUS.md bug 4).
      run(
        UcieTLScenario(
          nReqsBtoA = 200,
          linkStalls = Stalls(percent = 30),
          creditRetFollowsTxReady = false
        )
      )
    }
  }
}
