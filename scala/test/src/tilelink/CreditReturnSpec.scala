package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim

import org.scalatest.funspec.AnyFunSpec

/** One direction of the framed TileLink credit link: the partner's transmitter,
  * this die's RX buffer, and the credit accounting that is supposed to keep the
  * two in step. Wired as a loopback, like every harness in `TileLinkSpec`, so
  * the credit counter that gates the sender is the one that tracks the buffer
  * the frames land in.
  *
  * The credit logic is a pinned copy of `UcieTL`'s. The real one lives inside
  * `withClockAndReset(childClock, childReset) { ... }` in `UcieTLImpl`
  * (`scala/src/tilelink/TileLink.scala:898-1234`), where nothing in the test
  * tree can reach it; each expression below cites the line it was copied from.
  *
  * @param fixed
  *   when true, applies the fixes suggested in `docs/code-review-2026-08-20.md`
  *   findings 3-5, so every test can be run against both the current wiring and
  *   the proposed one.
  */
class CreditLink(params: UcieTLParams, fixed: Boolean) extends Module {
  val creditBits = params.creditBits

  val io = IO(new Bundle {
    // The partner's transmitter. `send*` means "the partner has a frame ready";
    // whether it goes out is up to the credit counters, exactly as
    // `managerTl.a.ready`/`clientTl.d.ready` gate the real one.
    val sendA = Input(Bool())
    val aFrame = Input(new UcieTXA(creditBits))
    val sendD = Input(Bool())
    val dFrame = Input(new UcieTXD(creditBits))

    // This die's TL sinks: `clientTl.a.ready` and `managerTl.d.ready`.
    val aSinkReady = Input(Bool())
    val dSinkReady = Input(Bool())

    // Stands in for the `d.fire`/`a.fire`/timer terms of TileLink.scala:965.
    val creditRetGo = Input(Bool())
    val creditFlowEnable = Input(Bool())

    val aAvail = Output(Bool())
    val dAvail = Output(Bool())
    val aCreditsToReturn = Output(UInt(creditBits.W))
    val dCreditsToReturn = Output(UInt(creditBits.W))
    // High on a cycle the local side puts a credit return on the wire.
    val creditRetFrame = Output(Bool())
    // What the two credit counters are told to add.
    val aRet = Valid(UInt(log2Up(params.tlBufferDepth).W))
    val dRet = Valid(UInt(log2Up(params.tlBufferDepth).W))
    // Frames the partner got onto the wire, and frames that arrived with the
    // buffer full and went nowhere.
    val aSent = Output(UInt(16.W))
    val aDropped = Output(UInt(16.W))
    val dDropped = Output(UInt(16.W))
    // TL beats actually handed to the local sinks.
    val aBeats = Output(UInt(16.W))
  })

  // TileLink.scala:1007-1010
  val rxABuffer =
    Module(new Queue(new UcieTXA(creditBits), params.tlBufferDepth))
  val rxDBuffer =
    Module(new Queue(new UcieTXD(creditBits), params.tlBufferDepth))

  // TileLink.scala:954-961
  val aCreditsToReturn = RegInit(0.U(creditBits.W))
  val dCreditsToReturn = RegInit(0.U(creditBits.W))
  val creditRetValid = Wire(Bool())
  val creditsFull = Wire(Bool())
  val aAvail = Wire(Bool())
  val dAvail = Wire(Bool())

  // The partner's TX. TileLink.scala:1069 only lets a beat out while the
  // channel has credit, and TileLink.scala:1145-1151 enqueues whatever arrives
  // without looking at `enq.ready` -- so a frame that arrives with the buffer
  // full is silently dropped. Credit flow is what is supposed to make that
  // unreachable.
  val aSend = io.sendA && aAvail
  val dSend = io.sendD && dAvail
  rxABuffer.io.enq.valid := aSend
  rxABuffer.io.enq.bits := io.aFrame
  rxDBuffer.io.enq.valid := dSend
  rxDBuffer.io.enq.bits := io.dFrame

  // TileLink.scala:1153-1178: the sink sees a beat only when the frame at the
  // head carries one, but the buffer dequeues on the sink's ready either way.
  rxABuffer.io.deq.ready := io.aSinkReady
  rxDBuffer.io.deq.ready := io.dSinkReady
  val aBeatFire = rxABuffer.io.deq.fire && rxABuffer.io.deq.bits.tl_valid

  // TileLink.scala:1071-1082. The increment path checks `tl_valid`; the
  // reset-to-1 path does not (finding 5).
  when(rxABuffer.io.deq.fire && rxABuffer.io.deq.bits.tl_valid) {
    aCreditsToReturn := aCreditsToReturn + 1.U
  }
  when(creditRetValid) {
    aCreditsToReturn := Mux(
      if (fixed) rxABuffer.io.deq.fire && rxABuffer.io.deq.bits.tl_valid
      else rxABuffer.io.deq.fire,
      1.U,
      0.U
    )
  }
  when(rxDBuffer.io.deq.fire && rxDBuffer.io.deq.bits.tl_valid) {
    dCreditsToReturn := dCreditsToReturn + 1.U
  }
  when(creditRetValid) {
    dCreditsToReturn := Mux(
      if (fixed) rxDBuffer.io.deq.fire && rxDBuffer.io.deq.bits.tl_valid
      else rxDBuffer.io.deq.fire,
      1.U,
      0.U
    )
  }

  // TileLink.scala:964-976, with the mode selects and the sideband TX handshake
  // folded into `io.creditRetGo`.
  creditsFull := aCreditsToReturn === 0.U && dCreditsToReturn === 0.U
  creditRetValid := (io.creditRetGo ||
    aCreditsToReturn > params.creditRetThreshhold.U ||
    dCreditsToReturn > params.creditRetThreshhold.U) && !creditsFull

  // TileLink.scala:1204-1207: `deq.valid`, not `deq.fire` (finding 3).
  val creditAValid =
    (if (fixed) rxABuffer.io.deq.fire
     else rxABuffer.io.deq.valid) && rxABuffer.io.deq.bits.credit_valid
  val creditDValid =
    (if (fixed) rxDBuffer.io.deq.fire
     else rxDBuffer.io.deq.valid) && rxDBuffer.io.deq.bits.credit_valid

  // TileLink.scala:1209-1233. The `Mux` keeps only the A contribution when both
  // buffers have a credit frame at the head (finding 4).
  val aCreditCounter = Module(
    new CreditCounter(params.creditCounterSize, params.tlBufferDepth)
  )
  aCreditCounter.io.used := aSend && io.aFrame.tl_valid
  aCreditCounter.io.ret.valid := creditAValid || creditDValid
  aCreditCounter.io.ret.bits :=
    (if (fixed)
       Mux(creditAValid, rxABuffer.io.deq.bits.credit_a, 0.U) +
         Mux(creditDValid, rxDBuffer.io.deq.bits.credit_a, 0.U)
     else
       Mux(
         creditAValid,
         rxABuffer.io.deq.bits.credit_a,
         rxDBuffer.io.deq.bits.credit_a
       ))
  aCreditCounter.io.mode := io.creditFlowEnable
  aAvail := aCreditCounter.io.avail

  val dCreditCounter = Module(
    new CreditCounter(params.creditCounterSize, params.tlBufferDepth)
  )
  dCreditCounter.io.used := dSend && io.dFrame.tl_valid
  dCreditCounter.io.ret.valid := creditAValid || creditDValid
  dCreditCounter.io.ret.bits :=
    (if (fixed)
       Mux(creditAValid, rxABuffer.io.deq.bits.credit_d, 0.U) +
         Mux(creditDValid, rxDBuffer.io.deq.bits.credit_d, 0.U)
     else
       Mux(
         creditAValid,
         rxABuffer.io.deq.bits.credit_d,
         rxDBuffer.io.deq.bits.credit_d
       ))
  dCreditCounter.io.mode := io.creditFlowEnable
  dAvail := dCreditCounter.io.avail

  // Instrumentation.
  val aSent = RegInit(0.U(16.W))
  val aDropped = RegInit(0.U(16.W))
  val dDropped = RegInit(0.U(16.W))
  val aBeats = RegInit(0.U(16.W))
  when(rxABuffer.io.enq.fire) { aSent := aSent + 1.U }
  when(rxABuffer.io.enq.valid && !rxABuffer.io.enq.ready) {
    aDropped := aDropped + 1.U
  }
  when(rxDBuffer.io.enq.valid && !rxDBuffer.io.enq.ready) {
    dDropped := dDropped + 1.U
  }
  when(aBeatFire) { aBeats := aBeats + 1.U }

  io.aAvail := aAvail
  io.dAvail := dAvail
  io.aCreditsToReturn := aCreditsToReturn
  io.dCreditsToReturn := dCreditsToReturn
  io.creditRetFrame := creditRetValid
  io.aRet := aCreditCounter.io.ret
  io.dRet := dCreditCounter.io.ret
  io.aSent := aSent
  io.aDropped := aDropped
  io.dDropped := dDropped
  io.aBeats := aBeats
}

/** Credit return on the framed TileLink path.
  *
  * Findings 3-5 of `docs/code-review-2026-08-20.md`. Each is run twice: once
  * against the wiring in `TileLink.scala` today, once with the suggested fix,
  * so a failure is pinned to the wiring rather than to the test.
  */
class CreditReturnSpec extends AnyFunSpec with ChiselSim {
  // A small buffer so overflow is reachable in a few cycles. `creditBits` and
  // the credit counter's `ret.bits` are both 3 bits wide here, as they are both
  // 6 bits wide at the default depth of 63.
  val params = UcieTLParams(tlBufferDepth = 7, creditCounterSize = 16)
  val depth = params.tlBufferDepth

  def idle(c: CreditLink): Unit = {
    c.io.sendA.poke(false.B)
    c.io.sendD.poke(false.B)
    c.io.aSinkReady.poke(false.B)
    c.io.dSinkReady.poke(false.B)
    c.io.creditRetGo.poke(false.B)
    c.io.creditFlowEnable.poke(true.B)
    frameA(c, tlValid = false, creditValid = false)
    frameD(c, tlValid = false, creditValid = false)
  }

  def frameA(
      c: CreditLink,
      tlValid: Boolean,
      creditValid: Boolean,
      creditA: Int = 0,
      creditD: Int = 0
  ): Unit = {
    c.io.aFrame.tl_valid.poke(tlValid.B)
    c.io.aFrame.credit_valid.poke(creditValid.B)
    c.io.aFrame.credit_a.poke(creditA.U)
    c.io.aFrame.credit_d.poke(creditD.U)
  }

  def frameD(
      c: CreditLink,
      tlValid: Boolean,
      creditValid: Boolean,
      creditA: Int = 0,
      creditD: Int = 0
  ): Unit = {
    c.io.dFrame.tl_valid.poke(tlValid.B)
    c.io.dFrame.credit_valid.poke(creditValid.B)
    c.io.dFrame.credit_a.poke(creditA.U)
    c.io.dFrame.credit_d.poke(creditD.U)
  }

  /** Partner streams A beats while this die's A sink is stalled. Credit flow is
    * supposed to stop the partner after `tlBufferDepth` frames; anything it
    * sends past that lands on a full queue and is gone.
    */
  def overrun(fixed: Boolean): (BigInt, BigInt) = {
    var sent = BigInt(0)
    var dropped = BigInt(0)
    simulate(new CreditLink(params, fixed)) { c =>
      idle(c)
      c.clock.step(2)
      // An ordinary frame: one TL beat, and one A credit returned alongside it.
      frameA(c, tlValid = true, creditValid = true, creditA = 1)
      c.io.sendA.poke(true.B)
      // The A sink never takes a beat, so nothing ever leaves rxABuffer.
      c.io.aSinkReady.poke(false.B)
      c.clock.step(32)
      sent = c.io.aSent.peek().litValue
      dropped = c.io.aDropped.peek().litValue
    }
    (sent, dropped)
  }

  /** Credits the A counter is told to add for a single frame that carries some,
    * while the sink holds that frame at the head of the buffer.
    */
  def creditsForOneFrame(fixed: Boolean): BigInt = {
    var total = BigInt(0)
    simulate(new CreditLink(params, fixed)) { c =>
      idle(c)
      c.clock.step(2)
      frameA(c, tlValid = true, creditValid = true, creditA = 3)
      c.io.sendA.poke(true.B)
      c.clock.step()
      c.io.sendA.poke(false.B)
      frameA(c, tlValid = false, creditValid = false)

      // Sink stalled: the frame sits at the head for ten cycles, then leaves.
      for (_ <- 0 until 10) {
        if (c.io.aRet.valid.peek().litToBoolean) {
          total += c.io.aRet.bits.peek().litValue
        }
        c.clock.step()
      }
      c.io.aSinkReady.poke(true.B)
      for (_ <- 0 until 4) {
        if (c.io.aRet.valid.peek().litToBoolean) {
          total += c.io.aRet.bits.peek().litValue
        }
        c.clock.step()
      }
    }
    total
  }

  /** Credits added to each counter when an A frame and a D frame carrying
    * credits are at the head of their buffers on the same cycle.
    */
  def creditsForSimultaneousFrames(fixed: Boolean): (BigInt, BigInt) = {
    var aTotal = BigInt(0)
    var dTotal = BigInt(0)
    simulate(new CreditLink(params, fixed)) { c =>
      idle(c)
      c.clock.step(2)
      // The A frame returns 2 A-credits and 1 D-credit, the D frame 3 and 1.
      frameA(c, tlValid = true, creditValid = true, creditA = 2, creditD = 1)
      frameD(c, tlValid = true, creditValid = true, creditA = 3, creditD = 1)
      c.io.sendA.poke(true.B)
      c.io.sendD.poke(true.B)
      c.clock.step()
      c.io.sendA.poke(false.B)
      c.io.sendD.poke(false.B)
      frameA(c, tlValid = false, creditValid = false)
      frameD(c, tlValid = false, creditValid = false)

      // Both sinks take their beat, so both frames are at the head together and
      // leave together.
      c.io.aSinkReady.poke(true.B)
      c.io.dSinkReady.poke(true.B)
      for (_ <- 0 until 6) {
        if (c.io.aRet.valid.peek().litToBoolean) {
          aTotal += c.io.aRet.bits.peek().litValue
        }
        if (c.io.dRet.valid.peek().litToBoolean) {
          dTotal += c.io.dRet.bits.peek().litValue
        }
        c.clock.step()
      }
    }
    (aTotal, dTotal)
  }

  /** A-credits this die hands back to the partner while draining one real beat
    * followed by one credit-only frame, and the beats it actually consumed.
    */
  def creditsReturnedToPartner(fixed: Boolean): (BigInt, BigInt) = {
    var returned = BigInt(0)
    var beats = BigInt(0)
    simulate(new CreditLink(params, fixed)) { c =>
      idle(c)
      c.clock.step(2)
      // A frame with a beat and no credits, then a credit-only frame.
      frameA(c, tlValid = true, creditValid = false)
      c.io.sendA.poke(true.B)
      c.clock.step()
      frameA(c, tlValid = false, creditValid = true, creditA = 1)
      c.clock.step()
      c.io.sendA.poke(false.B)
      frameA(c, tlValid = false, creditValid = false)

      // Drain both with a return frame due the whole time.
      c.io.aSinkReady.poke(true.B)
      c.io.creditRetGo.poke(true.B)
      for (_ <- 0 until 8) {
        if (c.io.creditRetFrame.peek().litToBoolean) {
          returned += c.io.aCreditsToReturn.peek().litValue
        }
        c.clock.step()
      }
      beats = c.io.aBeats.peek().litValue
    }
    (returned, beats)
  }

  describe("credit return as wired in TileLink.scala") {
    it("should not drop frames when the A sink stalls") {
      val (sent, dropped) = overrun(fixed = false)
      assert(
        dropped == 0,
        s"$dropped frames arrived at a full $depth-deep rxABuffer and were " +
          s"silently dropped ($sent were accepted). A credit-carrying frame " +
          "held at the buffer head is counted once per cycle it waits " +
          "(TileLink.scala:1204), so the partner is granted far more room " +
          "than the buffer has."
      )
    }

    it("should count a frame's credits once") {
      val got = creditsForOneFrame(fixed = false)
      assert(got == 3, s"one frame carrying 3 A-credits was counted as $got")
    }

    it("should keep both credit frames when A and D are at the head together") {
      val (aTotal, dTotal) = creditsForSimultaneousFrames(fixed = false)
      assert(
        (aTotal, dTotal) == (BigInt(5), BigInt(2)),
        s"A and D frames returning 2+3 A-credits and 1+1 D-credits were " +
          s"counted as $aTotal and $dTotal: the D frame's credits were dropped"
      )
    }

    it("should not return a credit for a frame that carried no beat") {
      val (returned, beats) = creditsReturnedToPartner(fixed = false)
      assert(
        returned == beats,
        s"returned $returned A-credits to the partner after consuming only " +
          s"$beats beat(s): the credit-only frame dequeued on the same cycle " +
          "as a return, and TileLink.scala:1075 counts it as a beat"
      )
    }
  }

  describe("credit return with the code-review fixes") {
    it("should not drop frames when the A sink stalls") {
      val (sent, dropped) = overrun(fixed = true)
      assert(dropped == 0, s"$dropped frames were dropped")
      assert(
        sent == depth,
        s"credit flow let the partner send $sent frames into a $depth-deep " +
          "buffer"
      )
    }

    it("should count a frame's credits once") {
      assert(creditsForOneFrame(fixed = true) == 3)
    }

    it("should keep both credit frames when A and D are at the head together") {
      assert(
        creditsForSimultaneousFrames(fixed = true) == (BigInt(5), BigInt(2))
      )
    }

    it("should not return a credit for a frame that carried no beat") {
      val (returned, beats) = creditsReturnedToPartner(fixed = true)
      assert(returned == beats, s"returned $returned credits for $beats beats")
    }
  }

  describe("CreditCounter") {
    it("should reset its counters when credit flow is disabled") {
      simulate(new CreditCounter(params.creditCounterSize, depth)) { c =>
        c.io.mode.poke(true.B)
        c.io.ret.valid.poke(false.B)
        c.io.ret.bits.poke(0.U)

        // Spend every credit.
        c.io.used.poke(true.B)
        c.clock.step(depth)
        c.io.used.poke(false.B)
        c.clock.step()
        c.io.avail.expect(false.B)

        // `mode` low is documented as "reset counter values to default (credit
        // flow disabled)", so the channel should be free to send again.
        c.io.mode.poke(false.B)
        c.clock.step(4)
        c.io.avail.expect(
          true.B,
          "disabling credit flow left the counters where they were"
        )
      }
    }
  }
}
