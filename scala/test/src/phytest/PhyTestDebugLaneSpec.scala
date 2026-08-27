package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils
import edu.berkeley.cs.uciedigital.phy.Phy

/** Drives the tester's TX data debug lane with a clock derived from the main
  * one, so that the lane's own divided clock -- and the async queue crossing
  * onto it -- run for real rather than being tied off.
  *
  * The lane clock toggles once per main clock edge, and the serializer is DDR,
  * so exactly one bit leaves the lane per main clock cycle.
  */
class PhyTestDebugLaneHarness(
    numLanes: Int = 2,
    shuffle: Int => Int = Phy.treeBitOrder
) extends Module {
  val io = IO(new Bundle {
    val divResetb = Input(Bool())
    val data = Input(Vec(16, UInt(64.W)))
    val repeatPeriod = Input(UInt(6.W))
    val fsmRst = Input(Bool())
    val execute = Input(Bool())
    val state = Output(TxTestState())
    val packetsEnqueued = Output(UInt(64.W))
    val txData = Output(Bool())
  })

  // The analog macros need their behavioral models here.
  val dut = Module(new PhyTest(numLanes = numLanes)(true))
  dut.io.regs := DontCare

  // Half the main clock, so one serializer edge lands on each main clock edge.
  val laneClk = RegInit(false.B)
  laneClk := !laneClk
  dut.io.debug.txClk := laneClk.asClock
  dut.io.debug.rxClk := false.B.asClock
  dut.io.debug.txDivClk := false.B.asClock
  dut.io.debug.sbTxClk := false.B.asClock
  dut.io.debug.rxData := DontCare

  // Both bands stay under TileLink, so the mainband TX/RX FSMs and the sideband
  // tester are held in reset and only the debug lane runs.
  dut.io.regs.mainbandMode := BandMode.tl
  dut.io.regs.sidebandMode := BandMode.tl
  dut.io.regs.testTarget := TestTarget.mainband
  dut.io.regs.txValidLaneSel := Phy.defaultValidLaneSel(numLanes).U
  dut.io.regs.rxValidLaneSel := Phy.defaultValidLaneSel(numLanes).U
  dut.io.regs.divResetb := io.divResetb.asAsyncReset
  dut.io.regs.txRst := false.B
  dut.io.regs.txExecute := false.B
  dut.io.regs.txDataChunkIn.valid := false.B
  dut.io.regs.rxRst := false.B
  dut.io.regs.rxPauseCounters := false.B
  dut.io.regs.sb.txSend := false.B
  dut.io.regs.sb.rxPop := false.B
  dut.io.regs.sb.rxRst := false.B

  // The lane's serializer is only released once `dll_reset` drops. The
  // shuffler defaults to the permutation that cancels the tile's tree order;
  // passing the identity instead exposes that raw order.
  dut.io.regs.txctl.dll_reset := false.B
  for (i <- 0 until Phy.SerdesRatio) {
    dut.io.regs.txctl.shuffler(i) := shuffle(i).U
  }

  dut.io.regs.txDebugTestMode := TxTestMode.manual
  dut.io.regs.txDebugDataMode := DataMode.infinite
  dut.io.regs.txDebugData := io.data
  dut.io.regs.txDebugManualRepeatPeriod := io.repeatPeriod
  dut.io.regs.txDebugPacketsToSend := 0.U
  dut.io.regs.txDebugFsmRst := io.fsmRst
  dut.io.regs.txDebugExecute := io.execute

  dut.io.tx.ready := true.B
  dut.io.rx.valid := false.B
  dut.io.rx.bits := DontCare
  dut.io.sb.rxClk := dut.io.sb.txClk
  dut.io.sb.rxData := dut.io.sb.txData

  io.state := dut.io.regs.txDebugState
  io.packetsEnqueued := dut.io.regs.txDebugPacketsEnqueued
  io.txData := dut.io.bumps.txData
}

class PhyTestDebugLaneSpec extends AnyFunSpec with ChiselSim {
  // The tester instantiates analog macros, whose behavioral models Verilator
  // lints at.
  implicit val sim: HasSimulator =
    verilator(verilatorSettings = Utils.quietVerilatorSettings)

  // Two words that share no rotation, so finding them back to back in the bit
  // stream cannot be a coincidence.
  val words = Seq(BigInt("a5a5a5a5", 16), BigInt("3c3c3c3c", 16))

  // Main clock cycles per divided lane clock cycle: the lane clock is half the
  // main clock and the serializer divides it by sixteen.
  val divCycle = 2 * 16

  // The tile serializes with an adjacent-pairing binary tree, so the bit sent
  // in UI t is word[bitrev5(t)] -- D0 D16 D8 D24 D4 D20 ... -- rather than
  // word[t]. This is the one test that sees the wire order directly.
  // The bit the tile puts in UI `t`, given the shuffler permutation in front of
  // it. The serializer sends `shuffled(treeBitOrder(t))`, and the shuffler maps
  // `shuffled(i) = word(shuffle(i))`.
  def uis(word: BigInt, shuffle: Int => Int): Seq[Int] =
    (0 until Phy.SerdesRatio)
      .map(t => ((word >> shuffle(Phy.treeBitOrder(t))) & 1).toInt)

  describe("PhyTest TX data debug lane") {
    // Drives the debug lane with `words` under the given shuffler permutation
    // and checks the bump carries the resulting UI sequence.
    def runAndCheck(shuffle: Int => Int, what: String): Unit =
      simulate(new PhyTestDebugLaneHarness(shuffle = shuffle)) { c =>
        c.io.divResetb.poke(false.B)
        c.io.repeatPeriod.poke(words.length.U)
        c.io.fsmRst.poke(false.B)
        c.io.execute.poke(false.B)
        // Each register holds two packets, low half first.
        for (i <- 0 until 16) {
          val value = words.lift(2 * i).getOrElse(BigInt(0)) |
            (words.lift(2 * i + 1).getOrElse(BigInt(0)) << 32)
          c.io.data(i).poke(value.U)
        }
        c.clock.step(4)
        // Release the serializer's divider, then load the FSM's seeds.
        c.io.divResetb.poke(true.B)
        c.clock.step(4)
        c.io.fsmRst.poke(true.B)
        c.clock.step()
        c.io.fsmRst.poke(false.B)
        c.clock.step(4)

        assert(c.io.state.peek() == TxTestState.idle)
        assert(c.io.packetsEnqueued.peek().litValue == 0)

        c.io.execute.poke(true.B)
        c.clock.step()
        c.io.execute.poke(false.B)
        c.clock.step(4)
        assert(
          c.io.state.peek() == TxTestState.run,
          "execute should start the debug lane"
        )

        // The queue's enqueue side only opens once the lane's divided clock has
        // carried the sink out of reset, which takes tens of divided cycles --
        // and one divided cycle is 32 main clock cycles.
        c.clock.step(16 * divCycle)
        assert(
          c.io.packetsEnqueued.peek().litValue > 0,
          "the debug FSM should be enqueuing once the async queue opens"
        )
        val enqueuedBefore = c.io.packetsEnqueued.peek().litValue

        // One bit leaves the lane per main clock cycle, so this covers several
        // trips around the two word pattern.
        val stream = (0 until 8 * Phy.SerdesRatio).map { _ =>
          val bit = c.io.txData.peek().litValue.toInt
          c.clock.step()
          bit
        }

        val expected = words.flatMap(uis(_, shuffle))
        assert(
          stream.sliding(expected.length).contains(expected),
          s"expected the $what pattern ${expected.mkString} in the " +
            s"transmitted stream ${stream.mkString}"
        )

        // The queue drains one word per divided cycle, i.e. one per 32 UIs, so
        // the FSM has to have kept it fed across the whole run.
        assert(
          c.io.packetsEnqueued.peek().litValue > enqueuedBefore,
          "the debug FSM should keep enqueuing while the lane drains"
        )
      }

    it("should send din bit 0 first under the default shuffler") {
      // How the lane ships: the shuffler's reset value cancels the tile's tree
      // order, so UI `t` carries `din(t)`.
      assert(
        (0 until Phy.SerdesRatio)
          .map(t => Phy.treeBitOrder(Phy.treeBitOrder(t))) == (0 until
          Phy.SerdesRatio),
        "the default shuffler should cancel the tile's bit order"
      )
      runAndCheck(Phy.treeBitOrder, "bit ordered")
    }

    it("should send the tile's tree order under an identity shuffler") {
      // With nothing cancelling it, the adjacent-pairing tree shows through:
      // D0 D16 D8 D24 D4 D20 ...
      runAndCheck(identity, "tree ordered")
    }
  }
}
