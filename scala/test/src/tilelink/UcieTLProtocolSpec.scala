package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.HasSimulator
import freechips.rocketchip.tilelink.TLBundleParameters
import org.scalatest.funspec.AnyFunSpec

/** Drives a `UcieTLProtocol` from scalar ports, so a test can play the partner
  * die without packing frames by hand.
  *
  * Frames for `rx` are built here with `UcieTLFrame`; TL payloads are left at
  * zero, since only the tags, `tl_valid` and the credit fields matter to flow
  * control. The credit fields of whatever frame goes out on `tx` are decoded
  * back out.
  */
class UcieTLProtocolHarness(params: UcieTLParams) extends Module {
  val tlParams = TLBundleParameters(
    addressBits = 32,
    dataBits = UcieTLFrame.dataBits,
    sourceBits = 4,
    sinkBits = 1,
    sizeBits = 4,
    echoFields = Nil,
    requestFields = Nil,
    responseFields = Nil,
    hasBCE = false
  )
  val creditBits = params.creditBits

  val io = IO(new Bundle {
    // This die's TL traffic.
    val managerAValid = Input(Bool())
    val managerAReady = Output(Bool())
    val managerDValid = Output(Bool())
    val managerDReady = Input(Bool())
    val clientAValid = Output(Bool())
    val clientAReady = Input(Bool())

    // The link.
    val txReady = Input(Bool())
    val txFire = Output(Bool())
    val txCreditValid = Output(Bool())
    val txCreditA = Output(UInt(creditBits.W))
    val txCreditD = Output(UInt(creditBits.W))

    // A frame from the partner.
    val rxValid = Input(Bool())
    val rxIsD = Input(Bool())
    val rxTlValid = Input(Bool())
    val rxCreditValid = Input(Bool())
    val rxCreditA = Input(UInt(creditBits.W))
    val rxCreditD = Input(UInt(creditBits.W))

    val creditFlowEnable = Input(Bool())
    val creditRetEnable = Input(Bool())
  })

  val dut = Module(new UcieTLProtocol(params, tlParams, tlParams))

  dut.io.manager.a.valid := io.managerAValid
  dut.io.manager.a.bits := 0.U.asTypeOf(dut.io.manager.a.bits)
  io.managerAReady := dut.io.manager.a.ready
  io.managerDValid := dut.io.manager.d.valid
  dut.io.manager.d.ready := io.managerDReady

  io.clientAValid := dut.io.client.a.valid
  dut.io.client.a.ready := io.clientAReady
  // This die never answers the partner's requests in these tests, so its
  // client D channel stays idle and A beats are never held off by D priority.
  dut.io.client.d.valid := false.B
  dut.io.client.d.bits := 0.U.asTypeOf(dut.io.client.d.bits)

  dut.io.tx.ready := io.txReady
  io.txFire := dut.io.tx.fire
  val txIsD = UcieTLFrame.isD(dut.io.tx.bits)
  val txPayload = UcieTLFrame.payload(dut.io.tx.bits)
  val txA = txPayload.asTypeOf(new UcieTXA(creditBits))
  val txD = txPayload.asTypeOf(new UcieTXD(creditBits))
  io.txCreditValid := Mux(txIsD, txD.credit_valid, txA.credit_valid)
  io.txCreditA := Mux(txIsD, txD.credit_a, txA.credit_a)
  io.txCreditD := Mux(txIsD, txD.credit_d, txA.credit_d)

  val rxA = Wire(new UcieTXA(creditBits))
  rxA := 0.U.asTypeOf(rxA)
  rxA.tl_valid := io.rxTlValid
  rxA.credit_valid := io.rxCreditValid
  rxA.credit_a := io.rxCreditA
  rxA.credit_d := io.rxCreditD
  val rxD = Wire(new UcieTXD(creditBits))
  rxD := 0.U.asTypeOf(rxD)
  rxD.tl_valid := io.rxTlValid
  rxD.credit_valid := io.rxCreditValid
  rxD.credit_a := io.rxCreditA
  rxD.credit_d := io.rxCreditD
  dut.io.rx.valid := io.rxValid
  dut.io.rx.bits := UcieTLFrame(io.rxIsD, rxD, rxA)

  dut.io.creditFlowEnable := io.creditFlowEnable
  dut.io.creditRetEnable := io.creditRetEnable
}

/** Credit flow in `UcieTLProtocol`, checked at its ports.
  *
  * Each test plays the partner die, puts the protocol in one specific state,
  * and checks how many A beats this die may then send, i.e. how many A credits
  * it holds. They assert what credit flow should do, so each fails for as long
  * as the bug it names is there. Waveforms land in
  * `build/chiselsim/UcieTLProtocolSpec/<describe>/<it>/workdir-vcs/trace.fsdb`.
  */
class UcieTLProtocolSpec extends AnyFunSpec with ChiselSim {
  val params = UcieTLParams()
  val depth = params.tlBufferDepth

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

  /** Idles every input, with credit flow on and the link able to take a frame. */
  def idle(c: UcieTLProtocolHarness): Unit = {
    c.io.managerAValid.poke(false.B)
    c.io.managerDReady.poke(true.B)
    c.io.clientAReady.poke(true.B)
    c.io.txReady.poke(true.B)
    c.io.rxValid.poke(false.B)
    c.io.rxIsD.poke(false.B)
    c.io.rxTlValid.poke(false.B)
    c.io.rxCreditValid.poke(false.B)
    c.io.rxCreditA.poke(0.U)
    c.io.rxCreditD.poke(0.U)
    c.io.creditFlowEnable.poke(true.B)
    // Off unless a test is about credit returns, so this die's own returns
    // stay out of the way.
    c.io.creditRetEnable.poke(false.B)
  }

  def reset(c: UcieTLProtocolHarness): Unit = {
    idle(c)
    c.reset.poke(true.B)
    c.clock.step(5)
    c.reset.poke(false.B)
    c.clock.step()
  }

  /** Offers A beats until this die runs out of A credits, and returns how many
    * were taken.
    */
  def spendACredits(c: UcieTLProtocolHarness, maxCycles: Int = 4 * depth): Int = {
    c.io.managerAValid.poke(true.B)
    var taken = 0
    var cycles = 0
    while (cycles < maxCycles && c.io.managerAReady.peek().litToBoolean) {
      taken += 1
      cycles += 1
      c.clock.step()
    }
    c.io.managerAValid.poke(false.B)
    // Any cycles left over did not take a beat; the loop above stops at the
    // first one that did not.
    taken
  }

  /** Puts one frame from the partner on `rx` for a cycle. */
  def receive(
      c: UcieTLProtocolHarness,
      isD: Boolean,
      tlValid: Boolean,
      creditA: Int = 0,
      creditD: Int = 0
  ): Unit = {
    c.io.rxValid.poke(true.B)
    c.io.rxIsD.poke(isD.B)
    c.io.rxTlValid.poke(tlValid.B)
    c.io.rxCreditValid.poke((creditA != 0 || creditD != 0).B)
    c.io.rxCreditA.poke(creditA.U)
    c.io.rxCreditD.poke(creditD.U)
    c.clock.step()
    c.io.rxValid.poke(false.B)
    c.io.rxCreditValid.poke(false.B)
  }

  describe("UcieTLProtocol credit flow") {
    it("starts with one A credit per partner buffer slot") {
      // Sanity check for the tests below, which all start by spending these.
      simulate(new UcieTLProtocolHarness(params)) { c =>
        enableWaves()
        reset(c)
        assert(spendACredits(c) == depth)
      }
    }

    it("counts a returned credit once while its frame waits in the RX buffer") {
      // A credit return rides in a frame that goes into the RX A buffer like
      // any other, and its credits are counted while it is at the head of the
      // buffer (`creditAValid` is `deq.valid`, not `deq.fire`). If the TL sink
      // holds the A channel off, the frame stays at the head and its credits
      // are counted again every cycle.
      simulate(new UcieTLProtocolHarness(params)) { c =>
        enableWaves()
        reset(c)
        assert(spendACredits(c) == depth)

        val heldCycles = 10
        c.io.clientAReady.poke(false.B)
        receive(c, isD = false, tlValid = false, creditA = 1)
        c.clock.step(heldCycles)
        c.io.clientAReady.poke(true.B)
        c.clock.step(5)

        val credits = spendACredits(c)
        assert(
          credits == 1,
          s"the partner returned 1 A credit, but this die then sent $credits A beats"
        )
      }
    }

    it("counts credits from both RX buffers when both return them in one cycle") {
      // `ret.bits` takes the A buffer's credits over the D buffer's when both
      // heads carry a return, and `ret.valid` is a single pulse, so the D
      // buffer's return is dropped.
      simulate(new UcieTLProtocolHarness(params)) { c =>
        enableWaves()
        reset(c)
        assert(spendACredits(c) == depth)

        // Line the two returns up at the heads of their buffers in the same
        // cycle. The D buffer gets a response first, held off for a cycle, so
        // its return comes out one cycle later than it went in.
        c.io.managerDReady.poke(false.B)
        receive(c, isD = true, tlValid = true) // response, held at the head
        receive(c, isD = true, tlValid = false, creditA = 1) // behind it
        c.io.managerDReady.poke(true.B) // response leaves this cycle
        receive(c, isD = false, tlValid = false, creditA = 1)
        // Both returns are at the heads now and both leave this cycle.
        c.clock.step(5)

        val credits = spendACredits(c)
        assert(
          credits == 2,
          s"the partner returned 2 A credits, but this die then sent $credits A beats"
        )
      }
    }

    it("keeps a credit return that arrives while the partner's A beats fill the RX buffer") {
      // Credit returns go into the RX A buffer, but credits only reserve room
      // for A beats. Once the partner has spent all its credits and the TL sink
      // is holding them, the buffer is full, and a return the partner sends
      // next (which costs it no credit) is dropped by the queue.
      simulate(new UcieTLProtocolHarness(params)) { c =>
        enableWaves()
        reset(c)

        // The partner sends one A beat per credit it holds, and the sink
        // takes none of them yet.
        c.io.clientAReady.poke(false.B)
        for (_ <- 0 until depth) {
          receive(c, isD = false, tlValid = true)
        }
        receive(c, isD = false, tlValid = false, creditA = 1)
        c.io.clientAReady.poke(true.B)
        c.clock.step(depth + 5)

        val credits = spendACredits(c)
        assert(
          credits == depth + 1,
          s"the partner returned 1 A credit on top of the initial $depth, " +
            s"but this die then sent $credits A beats"
        )
      }
    }

    it("returns credits only in frames the link takes") {
      // UcieTL holds `creditRetEnable` high on the mainband and in controller
      // mode whether or not their TX queues can take a frame. A return clears
      // the credits owed to the partner in the cycle it is offered, so one
      // offered while the queue is full is lost.
      simulate(new UcieTLProtocolHarness(params)) { c =>
        enableWaves()
        reset(c)

        // Take one A beat from the partner: this die now owes it one credit.
        receive(c, isD = false, tlValid = true)
        c.clock.step(2)

        // The link is stalled long enough for the return timer to fire
        // (every 2^creditRetTimerWidth cycles).
        c.io.creditRetEnable.poke(true.B)
        c.io.txReady.poke(false.B)
        c.clock.step(1 << params.creditRetTimerWidth)

        // Then it drains, and every frame it takes is checked for credits.
        c.io.txReady.poke(true.B)
        var returned = 0
        for (_ <- 0 until 2 * (1 << params.creditRetTimerWidth)) {
          if (
            c.io.txFire.peek().litToBoolean &&
            c.io.txCreditValid.peek().litToBoolean
          ) {
            returned += c.io.txCreditA.peek().litValue.toInt
          }
          c.clock.step()
        }
        assert(
          returned == 1,
          s"this die owed the partner 1 A credit, but frames on the link returned $returned"
        )
      }
    }
  }
}
