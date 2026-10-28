package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.{TLBundle, TLBundleParameters}

class UcieTLProtocolIO(
    managerParams: TLBundleParameters,
    clientParams: TLBundleParameters,
    frameBits: Int
) extends Bundle {
  /** This die's requests to the partner: A in, D out. */
  val manager = Flipped(new TLBundle(managerParams))
  /** The partner's requests, issued on this die: A out, D in. */
  val client = new TLBundle(clientParams)

  /** Frames to the partner. Valid whenever there is a beat or a credit return
    * to send; `ready` is whether the link can take one this cycle.
    */
  val tx = Decoupled(UInt(frameBits.W))
  /** Frames from the partner. There is no backpressure: credit flow keeps room
    * in the A/D buffers for every frame the partner is allowed to send.
    */
  val rx = Flipped(Valid(UInt(frameBits.W)))

  /** If false, the credit counters are held at their defaults (credit flow
    * disabled).
    */
  val creditFlowEnable = Input(Bool())
  /** Whether credit returns may be sent at all (e.g. a TL path is selected).
    * Owed credits are only cleared when the frame carrying them is taken, so
    * this need not follow `tx.ready`.
    */
  val creditRetEnable = Input(Bool())
}

/** The link-independent half of UcieTL: converts TL beats to and from frames
  * and runs credit flow control over them.
  *
  * Each cycle at most one frame goes out: a D beat if the client D channel is
  * valid, else an A beat, carrying any credits owed to the partner. Credits on
  * received frames go straight to the credit counters, and only frames
  * carrying a beat land in the per-channel buffers. Nothing here depends on
  * how frames cross the link.
  */
class UcieTLProtocol(
    params: UcieTLParams,
    managerParams: TLBundleParameters,
    clientParams: TLBundleParameters
) extends Module {
  val frameBits = UcieTLFrame.frameBits(params.creditBits)
  val io = IO(new UcieTLProtocolIO(managerParams, clientParams, frameBits))

  val clientTl = io.client
  val managerTl = io.manager

  val ucieClientTlD = UcieTLFrame.fromTL(clientTl.d.bits)
  val ucieManagerTlA = UcieTLFrame.fromTL(managerTl.a.bits)

  val creditBits = params.creditBits

  // Credits to return to the partner: first half = A channel, second half = D channel.
  val aCreditsToReturn = RegInit(0.U(creditBits.W))
  val dCreditsToReturn = RegInit(0.U(creditBits.W))
  val creditRetValid = Wire(Bool())
  val creditRetTimer =
    RegInit(0.U(params.creditRetTimerWidth.W)) // Arbitrary width for now
  val creditsFull = Wire(Bool())
  val aAvail = Wire(Bool())
  val dAvail = Wire(Bool())

  creditRetTimer := creditRetTimer + 1.U
  creditsFull := aCreditsToReturn === 0.U && dCreditsToReturn === 0.U
  creditRetValid := ((clientTl.d.fire ||
    managerTl.a.fire ||
    creditRetTimer === (1 << params.creditRetTimerWidth - 1).U ||
    aCreditsToReturn > params.creditRetThreshhold.U ||
    dCreditsToReturn > params.creditRetThreshhold.U) &&
    !creditsFull &&
    io.creditRetEnable)

  val ucieClientTxD = Wire(new UcieTXD(creditBits))
  ucieClientTxD.tl_valid := clientTl.d.fire
  ucieClientTxD.credit_valid := creditRetValid
  ucieClientTxD.credit_a := aCreditsToReturn
  ucieClientTxD.credit_d := dCreditsToReturn
  dontTouch(ucieClientTxD.tl_valid)
  dontTouch(ucieClientTxD.credit_valid)
  dontTouch(ucieClientTxD.credit_a)
  dontTouch(ucieClientTxD.credit_d)
  ucieClientTxD.tl := ucieClientTlD

  val ucieManagerTxA = Wire(new UcieTXA(creditBits))
  ucieManagerTxA.tl_valid := managerTl.a.fire
  ucieManagerTxA.credit_valid := creditRetValid
  ucieManagerTxA.credit_a := aCreditsToReturn
  ucieManagerTxA.credit_d := dCreditsToReturn
  dontTouch(ucieManagerTxA.tl_valid)
  dontTouch(ucieManagerTxA.credit_valid)
  dontTouch(ucieManagerTxA.credit_a)
  dontTouch(ucieManagerTxA.credit_d)
  ucieManagerTxA.tl := ucieManagerTlA

  // Only TL beats are buffered: credits are taken off frames as they arrive
  // (below), so credit-only frames never occupy a slot. The partner spends a
  // credit per beat, so each buffer holds at most tlBufferDepth entries.
  val rxABuffer =
    Module(new Queue(new UcieTLBundleA, params.tlBufferDepth))
  val rxDBuffer =
    Module(new Queue(new UcieTLBundleD, params.tlBufferDepth))

  io.tx.valid := (clientTl.d.valid && dAvail) ||
    (managerTl.a.valid && aAvail && !clientTl.d.valid) ||
    creditRetValid
  io.tx.bits := UcieTLFrame(clientTl.d.valid, ucieClientTxD, ucieManagerTxA)

  clientTl.d.ready := io.tx.ready && dAvail
  managerTl.a.ready := io.tx.ready && aAvail && !clientTl.d.valid

  // Each beat dequeued frees a slot in the buffer, which the partner gets
  // back as a credit.
  when(rxABuffer.io.deq.fire) {
    aCreditsToReturn := aCreditsToReturn + 1.U
  }
  // Owed credits are cleared only once the frame carrying them is taken;
  // otherwise they stay owed and go out with a later frame.
  val creditRetSent = creditRetValid && io.tx.fire
  when(creditRetSent) {
    aCreditsToReturn := Mux(rxABuffer.io.deq.fire, 1.U, 0.U)
  }
  when(rxDBuffer.io.deq.fire) {
    dCreditsToReturn := dCreditsToReturn + 1.U
  }
  when(creditRetSent) {
    dCreditsToReturn := Mux(rxDBuffer.io.deq.fire, 1.U, 0.U)
  }

  val rxPayload = UcieTLFrame.payload(io.rx.bits)
  val rxIsD = UcieTLFrame.isD(io.rx.bits)
  val rxA = rxPayload.asTypeOf(new UcieTXA(creditBits))
  val rxD = rxPayload.asTypeOf(new UcieTXD(creditBits))
  val rxTlValid = Mux(rxIsD, rxD.tl_valid, rxA.tl_valid)
  rxABuffer.io.enq.bits := rxA.tl
  rxDBuffer.io.enq.bits := rxD.tl
  rxABuffer.io.enq.valid := io.rx.valid && !rxIsD && rxTlValid
  rxDBuffer.io.enq.valid := io.rx.valid && rxIsD && rxTlValid

  clientTl.a <> rxABuffer.io.deq.map(UcieTLFrame.toTL(_, clientTl.a.bits))
  managerTl.d <> rxDBuffer.io.deq.map(UcieTLFrame.toTL(_, managerTl.d.bits))
  dontTouch(managerTl.d.bits.opcode)

  // Credits are counted once, when their frame arrives, whether piggybacked on
  // a beat or in a credit-only frame. They don't wait on the TL sinks.
  val rxCreditValid =
    io.rx.valid && Mux(rxIsD, rxD.credit_valid, rxA.credit_valid)

  val aCreditCounter = Module(
    new CreditCounter(params.creditCounterSize, params.tlBufferDepth)
  )
  aCreditCounter.io.used := managerTl.a.fire
  aCreditCounter.io.ret.valid := rxCreditValid
  aCreditCounter.io.ret.bits := Mux(rxIsD, rxD.credit_a, rxA.credit_a)
  aCreditCounter.io.mode := io.creditFlowEnable
  aAvail := aCreditCounter.io.avail

  val dCreditCounter = Module(
    new CreditCounter(params.creditCounterSize, params.tlBufferDepth)
  )
  dCreditCounter.io.used := clientTl.d.fire
  dCreditCounter.io.ret.valid := rxCreditValid
  dCreditCounter.io.ret.bits := Mux(rxIsD, rxD.credit_d, rxA.credit_d)
  dCreditCounter.io.mode := io.creditFlowEnable
  dAvail := dCreditCounter.io.avail
}
