package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chisel3.simulator.HasSimulator
import chisel3.simulator.scalatest.ChiselSim
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.tilelink._
import org.scalatest.funspec.AnyFunSpec

/** How the test joins the dies' `UciePacketIO`s. */
sealed trait UcieNoPhyLink
object UcieNoPhyLink {

  /** One die, its frames fed back with `loopback`: it serves its own requests. */
  case object Loopback extends UcieNoPhyLink

  /** Two dies joined with `connect`. */
  case object Connect extends UcieNoPhyLink

  /** Two dies whose frames reach each other `cycles` later. */
  case class Delayed(cycles: Int) extends UcieNoPhyLink
}

/** One or two `UcieTLNoPhy`s, each with a scripted driver (and scoreboard) on
  * its manager port and a `TLRAM` on its client port.
  */
class UcieTLNoPhyTest(link: UcieNoPhyLink, nReqs: Int, seed: Int)(implicit
    p: Parameters
) extends LazyModule {
  val params = UcieTLParams(phy = false)
  val beatBytes = UcieTLFrame.dataBits / 8
  val region = AddressSet(0x10000, 0xffff)
  val nDies = if (link == UcieNoPhyLink.Loopback) 1 else 2

  val dies = Seq.fill(nDies)(
    LazyModule(new UcieTLNoPhy(params, Seq(region), beatBytes, beatBytes))
  )
  val drivers = Seq.tabulate(nDies) { i =>
    LazyModule(
      new UcieTLTrafficDriver(
        s"driver$i",
        UcieTLRequest.script(nReqs, seed + i, region, beatBytes),
        nSources = 128,
        beatBytes
      )
    )
  }
  val rams = Seq.fill(nDies)(
    LazyModule(new TLRAM(region, cacheable = false, beatBytes = beatBytes))
  )
  for (i <- 0 until nDies) {
    dies(i).managerNode := drivers(i).node
    rams(i).node := dies(i).clientNode
  }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val done = Output(Bool())
      val error = Output(Bool())
      /** Credits each die holds per channel (A, D). */
      val credits = Output(Vec(nDies, Vec(2, UInt(8.W))))
    })

    drivers.foreach { d =>
      d.module.io.start := true.B
      d.module.io.dReady := true.B
    }

    val ios = dies.map(_.module.io)
    link match {
      case UcieNoPhyLink.Loopback => ios(0).loopback
      case UcieNoPhyLink.Connect  => ios(0).connect(ios(1))
      case UcieNoPhyLink.Delayed(cycles) =>
        for (i <- 0 until 2) {
          ios(1 - i).rx := ShiftRegister(
            ios(i).tx,
            cycles,
            0.U.asTypeOf(ios(i).tx),
            true.B
          )
        }
    }

    val counterBits = log2Up(params.creditCounterSize)
    def held(counter: CreditCounter): UInt = {
      val gnt = BoringUtils.tapAndRead(counter.cred_gnt)
      val used = BoringUtils.tapAndRead(counter.cred_used)
      (gnt - used)(counterBits - 1, 0)
    }
    for (i <- 0 until nDies) {
      val proto = dies(i).module.protocol
      io.credits(i)(0) := held(proto.aCreditCounter)
      io.credits(i)(1) := held(proto.dCreditCounter)
    }

    io.error := drivers.map(_.module.io.error).reduce(_ || _)
    // Done once every script has completed and owed credits have had time to
    // go back (the return timer fires every 2^creditRetTimerWidth cycles).
    val finished = drivers.map(_.module.io.finished).reduce(_ && _)
    val quietCycles = RegInit(0.U(32.W))
    when(finished) { quietCycles := quietCycles + 1.U }
    io.done := quietCycles >= (4 * (1 << params.creditRetTimerWidth) + 200).U
  }
}

/** `UcieTLNoPhy` over each way of joining `UciePacketIO`s: TL traffic must
  * complete with correct data, and every credit must come back.
  *
  * Waveforms land in
  * `build/chiselsim/UcieTLNoPhySpec/<describe>/<it>/workdir-vcs/trace.fsdb`.
  */
class UcieTLNoPhySpec extends AnyFunSpec with ChiselSim {
  implicit val simulator: HasSimulator = HasSimulator.simulators.vcs(
    svsim.CommonCompilationSettings(),
    svsim.vcs.Backend.CompilationSettings(
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

  def run(link: UcieNoPhyLink, nReqs: Int = 300, timeout: Int = 50000): Unit = {
    implicit val p: Parameters = Parameters.empty
    val dut = LazyModule(new UcieTLNoPhyTest(link, nReqs, seed = 1))
    simulate(dut.module) { c =>
      enableWaves()
      c.reset.poke(true.B)
      c.clock.step(5)
      c.reset.poke(false.B)
      var cycles = 0
      while (
        !c.io.done.peek().litToBoolean && !c.io.error.peek().litToBoolean &&
        cycles < timeout
      ) {
        c.clock.step(16)
        cycles += 16
      }
      assert(!c.io.error.peek().litToBoolean, "a driver got a wrong response")
      assert(
        c.io.done.peek().litToBoolean,
        s"traffic did not complete within $timeout cycles"
      )
      for (i <- 0 until dut.nDies; ch <- 0 until 2) {
        assert(
          c.io.credits(i)(ch).peek().litValue == depth,
          s"die $i channel ${"AD" (ch)} holds " +
            s"${c.io.credits(i)(ch).peek().litValue} credits at rest"
        )
      }
    }
  }

  describe("UcieTLNoPhy") {
    it("serves its own requests over a loopback") {
      run(UcieNoPhyLink.Loopback)
    }
    it("carries two-way traffic between connected dies") {
      run(UcieNoPhyLink.Connect)
    }
    it("carries two-way traffic over a 40-cycle link") {
      run(UcieNoPhyLink.Delayed(40))
    }
  }
}
