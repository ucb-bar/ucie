/*
  Description:
    Multi-module PHY Logic (spec 4.7). Aggregates one, two or four UCIe Modules
    into a single logical Link that presents one RDI to one Die-to-Die Adapter.

  It owns two jobs:

    1. The datapath. MmplByteSwizzle scatters the aggregate RDI transmit word
       into per-Module slices and gathers the receive slices back, so that
       "bytes are laid out from LSB to MSB in ascending order of Module ID and
       Lane ID across all the active Lanes" holds on the wire (spec 4.7.1).
    2. The control abstraction. One RDI status, width and speed are presented
       upward; the per-Module MBTRAIN.LINKSPEED reports are resolved by
       MmplLinkSpeedResolver and the result is directed back to every Module.

  NOTE:
 * numModules == 1 is a pass-through: the swizzle degenerates to the identity,
   the resolver never fires, and the cfg path is wired straight across.
 * The aggregate RDI is numModules times the bytes one Module carries per
   mainband beat, so at full width one aggregate transfer is one transfer per
   Module. Once Modules have been disabled the surviving Lanes cannot carry the
   whole word at once, and the transfer spreads over successive 8-UI intervals
   exactly as spec Figure 4-46 shows; that is what the beat counters below do.
 * Spec 3.5: "there is a single RDI state machine for this configuration. The
   Multi-module PHY Logic creates the abstraction and coordinates between the RDI
   state and individual modules." That machine is hosted here, and each Module is
   built without one; a Module hands up its view of training and takes the
   resulting RDI state back.
 * Non-LTSM sideband packets follow spec 4.7.1.1: register access, and the
   {LinkMgmt.RDI.*} messages of Table 7-8 that the hosted state machine
   exchanges, are transmitted on the numerically least Module ID whose LTSM is
   not in RESET or SBINIT. Either may be received on a different Module ID, so
   both receive directions merge across the Modules -- cfg a whole packet at a
   time, link management one message at a time.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband._
import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

object MmplResolveState extends ChiselEnum {
  val idle, directing = Value
}

/* One Module's receive slice as it waits for its siblings: the data, and
   whether the Module flagged a Valid framing error on it (Table 10-1 pl_error).
   The error has to travel with the slice so it reaches the Adapter with the
   aggregate word it belongs to. */
class MmplRxSlice(bits: Int) extends Bundle {
  val data = UInt(bits.W)
  val err = Bool()
}

class Mmpl(
    params: MmplParams = MmplParams(),
    rdiParams: RdiParams = RdiParams(64, 32),
    sbParams: SidebandParams = new SidebandParams(),
    // Spec 10.3.3.7's 16 ms minimum LinkError residency, for the hosted RDI
    // state machine; it follows the Modules' residency timeout override.
    linkErrorResidencyCycles: Int =
      LinkTrainingSM.linkErrorResidencyCycles(None)
) extends Module {
  private val n = params.numModules
  private val bytesPerModule = params.bytesPerModule
  private val moduleBits = bytesPerModule * 8
  private val totalBits = rdiParams.nBytes * 8
  private val rankW = log2Ceil(n + 1)

  require(
    rdiParams.nBytes == n * bytesPerModule,
    s"MMPL aggregate RDI must be $n x $bytesPerModule bytes, got ${rdiParams.nBytes}"
  )

  private val moduleRdiParams = params.moduleRdiParams(rdiParams.ncWidth)

  val io = IO(new Bundle {
    val rdi = new Rdi(rdiParams)
    val modules =
      Vec(n, new MmplModulePort(params, rdiParams.ncWidth, sbParams))
    /* Which Modules are physically wired to a remote Module Partner. Chapter 5
       permits multi-module Links whose two die have different Module counts
       (Table 5-28), where some local Modules are marked NC and never train --
       Figure 4-46 is exactly that case. A Module tied off here is left out of
       every aggregate, so it cannot hold up bring-up or drag the Link into
       LinkError on its own training timeout. Defaults to all connected. */
    val moduleConnected = Input(Vec(n, Bool()))
    val status = new Bundle {
      val moduleEnable = Output(Vec(n, Bool()))
      val linkResolution = Output(MmplResolution())
      val resolutionApplied = Output(Bool())
    }
  })

  // ==========================================================================
  // Operational Module set
  // ==========================================================================
  // A Module leaves the set when a resolution disables it (spec 4.7.1) and comes
  // back when the whole Link has fallen to RESET and will retrain from scratch.
  // An unconnected Module is never in the set at all.
  private val moduleEnableReg = RegInit(VecInit(Seq.fill(n)(true.B)))
  private val moduleEnable = VecInit((0 until n).map { m =>
    moduleEnableReg(m) && io.moduleConnected(m)
  })
  private val ltStates = (0 until n).map(io.modules(_).status.ltState)
  private val linkInReset = (0 until n)
    .map(m => !io.moduleConnected(m) || ltStates(m) === LTState.sRESET)
    .reduce(_ && _)

  private def overEnabled(pred: Int => Bool): Seq[Bool] =
    (0 until n).map(m => moduleEnable(m) && pred(m))
  private def anyEnabled(pred: Int => Bool): Bool =
    overEnabled(pred).reduce(_ || _)
  private def allEnabled(pred: Int => Bool): Bool =
    (0 until n).map(m => !moduleEnable(m) || pred(m)).reduce(_ && _)

  private val someModuleEnabled = moduleEnable.reduce(_ || _)
  private val numActive = PopCount(moduleEnable)

  /* A stall still waiting for lp_stallack when the hosted RDI went to
     LinkError: pl_trdy lets the Adapter drain, and what it hands over is
     dropped (RDIController.stallDrain). Driven by the hosted RDI state machine
     below, read by the transmit path above it. */
  private val stallDrain = WireDefault(false.B)

  // Set by the sideband cfg path below when the MMPL itself has a packet or a
  // credit in flight on the Adapter's bus; read by the hosted RDI state machine
  // above it, so it has to be declared before either.
  private val cfgSidebandBusy = WireDefault(false.B)

  // Modules the directive now being applied is removing from the Link. Written
  // by the resolution logic below and read by the hosted RDI state machine
  // above it, so declared here.
  private val leavingByDirective = WireDefault(VecInit(Seq.fill(n)(false.B)))

  /* Enabled Modules that fell out of training on their own -- to TRAINERROR in
     MBINIT, say -- before the resolver heard from them. Spec 4.7.1: "if any
     module failed to train, the MMPL must ensure that the multi-module
     configuration degrades to the next permitted configuration". Written by
     the resolution logic below: a failed Module is held in RESET and resolved
     as one that must be disabled, instead of leaving its siblings waiting in
     MBTRAIN.LINKSPEED for a report that never comes. */
  private val failedReg = RegInit(VecInit(Seq.fill(n)(false.B)))
  /* Modules falling out of training this cycle, ahead of failedReg catching
     up, so the hosted RDI state machine never sees them for even a cycle -- it
     latches pl_inband_pres low on TRAINERROR. Driven by the resolution logic
     below. */
  private val fellOut = WireDefault(VecInit(Seq.fill(n)(false.B)))
  // Enabled and still training or up.
  private val operational = VecInit((0 until n).map { m =>
    moduleEnable(m) && !failedReg(m) && !fellOut(m)
  })
  private val someOperational = operational.reduce(_ || _)
  /* What the hosted RDI state machine answers to: the operational Modules, or
     every enabled one once none is left, so that a Link whose Modules have all
     failed still reaches LinkError instead of going quiet. */
  private val rdiMember = VecInit((0 until n).map { m =>
    Mux(someOperational, operational(m), moduleEnable(m))
  })
  private val someRdiMember = rdiMember.reduce(_ || _)
  private def anyRdiMember(pred: Int => Bool): Bool =
    (0 until n).map(m => rdiMember(m) && pred(m)).reduce(_ || _)
  private def allRdiMember(pred: Int => Bool): Bool =
    (0 until n).map(m => !rdiMember(m) || pred(m)).reduce(_ && _)

  /* Where the Link's own sideband traffic -- {LinkMgmt.RDI.*} and register
     access -- may start. Spec 4.7.1.1 names "the numerically least Module ID
     whose LTSM is not in RESET or SBINIT". A Module in TRAINERROR, one the
     directive now being applied is disabling, and one that failed are passed
     over as well: each is on its way to RESET, whose sideband reset destroys
     whatever it is still holding -- the packets, and with them the lp_cfg
     credits the Adapter spent on them. The remote die takes these packets on
     any Module ID ("A packet sent on a given Module ID could be received on a
     different Module ID"), so the next Module up serves just as well. */
  private val sbTrafficEligible = VecInit((0 until n).map { m =>
    operational(m) && !leavingByDirective(m) &&
    (ltStates(m) =/= LTState.sRESET) && (ltStates(m) =/= LTState.sSBINIT) &&
    (ltStates(m) =/= LTState.sTRAINERROR)
  })

  /** Reads a per-Module signal from the numerically least operational Module.
    * Every Module of a multi-module Link runs at the same width and speed (spec
    * 4.7.1), so any operational Module speaks for the Link -- but a disabled or
    * failed one does not, because its status stops tracking the Link (a failed
    * Module sitting in RESET reads 4 GT/s and full width).
    */
  private def fromLeastEnabled[T <: Data](select: Int => T): T =
    PriorityMux(
      (0 until n).map(m => operational(m) -> select(m)) ++
        (0 until n).map(m => moduleEnable(m) -> select(m)) :+
        (true.B -> select(0))
    )

  // ==========================================================================
  // Ranks
  // ==========================================================================
  // Receive demaps by the local Module ID, which the wrapper assigns as the
  // Module index, so a Module's rank is how many enabled Modules precede it.
  private val rxRank = VecInit((0 until n).map { m =>
    val rank = WireDefault(0.U(rankW.W))
    if (m > 0) rank := PopCount((0 until m).map(moduleEnable(_)))
    rank
  })

  // Transmit maps by the remote Module ID (spec 4.7.1, Figure 4-44), because the
  // remote Receiver demaps by its own. Before MBINIT.PARAM has run there is no
  // remote ID yet, so fall back to the identity.
  private val remoteId = VecInit((0 until n).map { m =>
    val negotiated = io.modules(m).status.negotiatedPhyParamSettings
    Mux(negotiated.valid, negotiated.bits.moduleId, m.U(2.W))
  })
  private val txRank = VecInit((0 until n).map { m =>
    val rank = WireDefault(0.U(rankW.W))
    rank := PopCount((0 until n).map { k =>
      moduleEnable(k) && (remoteId(k) < remoteId(m))
    })
    rank
  })

  // ==========================================================================
  // Byte swizzle
  // ==========================================================================
  private val swizzle = Module(new MmplByteSwizzle(params))

  // A Module transmits on its own functional Lanes and receives on the ones the
  // remote Transmitter kept, which is how LogicalPhy wires its Lane controller.
  private val txLaneCode =
    fromLeastEnabled(io.modules(_).status.localTxFunctionalLanes)
  private val rxLaneCode =
    fromLeastEnabled(io.modules(_).status.remoteTxFunctionalLanes)

  // Spec 4.5.3.3.5: "UCIe-S x8" changes what the "all functional" Lane code
  // means, so the byte map has to read Table 4-9 the same way the per-Module
  // Lane controller does. Every Module of the Link negotiates the same
  // parameters (spec 4.7.1.2), so one Module speaks for all of them.
  private val negotiatedBy8 = fromLeastEnabled { m =>
    val negotiated = io.modules(m).status.negotiatedPhyParamSettings
    negotiated.valid && negotiated.bits.ucieSx8.asBool
  }

  swizzle.io.ctrl.numActive := numActive
  swizzle.io.ctrl.by8 := negotiatedBy8
  swizzle.io.ctrl.txLaneCode := txLaneCode
  swizzle.io.ctrl.rxLaneCode := rxLaneCode
  swizzle.io.ctrl.txRank := txRank
  swizzle.io.ctrl.rxRank := rxRank
  swizzle.io.ctrl.enable := moduleEnable

  // MMPL beats needed for one aggregate word: numModules / numActive.
  private val beatsPerWord = WireDefault(1.U(rankW.W))
  MmplByteMap.permittedActiveCounts(n).foreach { count =>
    when(numActive === count.U) {
      beatsPerWord := MmplByteMap.beatsPerWord(n, count).U
    }
  }
  private val singleBeat = beatsPerWord === 1.U

  // ==========================================================================
  // Transmit
  // ==========================================================================
  private val allModulesTrdy = allEnabled(io.modules(_).rdi.plTrdy)

  private val txBeat = RegInit(0.U(rankW.W))
  private val txHold = RegInit(false.B)
  private val txDataReg = RegInit(0.U(totalBits.W))

  // Feed the latched word while beats remain, otherwise whatever the Adapter is
  // presenting, so a single-beat transfer costs no extra cycle.
  private val txWord = Mux(txHold, txDataReg, io.rdi.lpData)
  private val txFeeding = !stallDrain &&
    (txHold || (io.rdi.lpValid && io.rdi.lpIrdy && someModuleEnabled))
  private val txFire = txFeeding && allModulesTrdy
  private val txLastBeat = txBeat === (beatsPerWord - 1.U)

  when(txFire) {
    when(txLastBeat) {
      txHold := false.B
      txBeat := 0.U
    }.otherwise {
      txHold := true.B
      txDataReg := txWord
      txBeat := txBeat + 1.U
    }
  }

  swizzle.io.ctrl.txBeat := txBeat
  swizzle.io.tx.lpData := txWord

  // pl_trdy accepts a new aggregate word only when no beats are outstanding.
  io.rdi.plTrdy := Mux(
    stallDrain,
    true.B,
    allModulesTrdy && !txHold && someModuleEnabled
  )

  // ==========================================================================
  // Receive
  // ==========================================================================
  // Modules can deliver their slice several cycles apart, so hold each one until
  // every enabled Module has a slice for this beat. Spec 4.7.1.2 warns that
  // Modules of a Link can be staggered, and pl_valid has no backpressure
  // (spec 10.1.4), so the depth here is the whole skew budget.
  private val clearBeatState = WireDefault(false.B)
  // Receive half of clearBeatState on its own, for the framing error path
  // below: nothing about a receive error touches the transmit beats.
  private val clearRxState = WireDefault(false.B)

  /* Set once a Valid framing error has gone up.
     Table 10-1: "Once pl_error is asserted, pl_valid should not be asserted
     (without pl_error assertion in the same cycle) until the state status has
     transitioned to Active after completing a successful Retrain entry and
     exit." Cleared where the RDI state is known, below. */
  private val rxErrLatched = RegInit(false.B)

  private val rxSlices = Seq.tabulate(n) { m =>
    val q = Module(
      new Queue(
        new MmplRxSlice(moduleBits),
        params.rxAlignDepth,
        pipe = true,
        flow = true,
        hasFlush = true
      )
    )
    q.suggestName(s"rxSliceQueue_$m")
    val mod = io.modules(m).rdi
    /* A Module raises pl_error with, or ahead of, the corrupted slice and then
       withholds pl_valid until retrain (LogicalPhy). Its siblings keep
       delivering, and with any skew between them the aggregate word that slice
       belongs to completes cycles later -- so the error rides through this
       queue with the slice instead of being ORed onto the RDI as it happens,
       which put it beside an older, good word and let the corrupted one through
       clean. When the error comes without a slice (the Module squashed the
       corrupted one), an empty one stands in so the beat it belonged to still
       carries the error. */
    val errSeen = RegNext(mod.plError, false.B)
    val errRise = mod.plError && !errSeen && !rxErrLatched
    q.io.enq.valid :=
      (mod.plValid || errRise) && moduleEnable(m) && !rxErrLatched
    q.io.enq.bits.data := mod.plData
    q.io.enq.bits.err := errRise
    /* Modules can deliver unequal numbers of beats -- one suppresses pl_valid
       after a framing error, they leave ACTIVE on different cycles, or one is
       disabled mid-stream -- and anything left behind would other-wise sit in
       the queue and re-emerge as a slice from k beats ago, silently offsetting
       that Module's contribution to every later word. Discard the partial word
       instead, on the same edge the Module set changes. */
    q.io.flush.foreach(_ := clearBeatState || clearRxState)
    q
  }

  private val rxAligned = allEnabled(rxSlices(_).io.deq.valid)
  private val rxFire = rxAligned && someModuleEnabled

  private val rxBeat = RegInit(0.U(rankW.W))
  private val rxAccum = RegInit(0.U(totalBits.W))
  private val rxLastBeat = rxBeat === (beatsPerWord - 1.U)

  for (m <- 0 until n) {
    rxSlices(m).io.deq.ready := rxFire && moduleEnable(m)
    swizzle.io.rx.moduleData(m) := rxSlices(m).io.deq.bits.data
  }
  swizzle.io.ctrl.rxBeat := rxBeat

  // Beats occupy disjoint aggregate bytes, so accumulating is an OR.
  private val rxGathered =
    swizzle.io.rx.plData | Mux(rxBeat === 0.U, 0.U, rxAccum)
  // A slice of the beat going up now was corrupted.
  private val rxBeatErr =
    rxFire && anyEnabled(rxSlices(_).io.deq.bits.err)
  private val rxWordDone = rxFire && rxLastBeat

  when(rxFire) {
    when(rxLastBeat) {
      rxBeat := 0.U
      rxAccum := 0.U
    }.otherwise {
      rxBeat := rxBeat + 1.U
      rxAccum := rxGathered
    }
  }

  /* The error goes up on the beat that carries it, not with the end of its
     word. The Module that flagged it withholds pl_valid until retrain, so once
     a word takes several beats (Figure 4-46) the rest of that word never comes:
     waiting for it lost pl_error altogether and overflowed the siblings'
     queues. Table 10-1 lets the Physical Layer "squash the pl_valid internally
     for the corrupted data" as long as the error "reaches the Adapter before or
     at the same time as the corrupted data", so a word the error lands in
     part-way is dropped and pl_error goes up on its own; on the last beat the
     word is whole and goes up with it. Either way nothing after it reaches the
     Adapter until retrain: whatever the siblings are still holding, or deliver
     in the meantime, is discarded rather than left to overflow their queues. */
  when(rxBeatErr) {
    rxErrLatched := true.B
    clearRxState := true.B
  }

  /* A resolution can change the Module count, and with it how many beats an
     aggregate word takes, so a word part-way through cannot be finished to a
     shape it was not built for. Driven where moduleEnable is written, and
     placed after every beat update so last-connect semantics really do let it
     win -- an earlier revision sat above `when(rxFire)` and silently lost the
     receive half of the race. */
  when(clearBeatState) {
    txBeat := 0.U
    txHold := false.B
  }
  when(clearBeatState || clearRxState) {
    rxBeat := 0.U
    rxAccum := 0.U
  }

  // ==========================================================================
  // The Link's RDI state machine
  // ==========================================================================
  // Spec 3.5: a multi-module Link has a single RDI state machine, and the MMPL
  // coordinates between it and the individual Modules. It is hosted here, and
  // each Module hands up the training view it needs and takes the resulting RDI
  // state back. A one-module Link keeps the machine inside its LogicalPhy, so
  // there the aggregate is that Module's own status.
  private val hostedRdi =
    Option.when(params.isMultiModule)(
      Module(new RDIController(sbParams, linkErrorResidencyCycles))
    )

  private val aggState = WireDefault(RDIState.reset)
  private val aggInbandPres = WireDefault(false.B)
  private val aggStallReq = WireDefault(false.B)
  private val aggClkReq = WireDefault(false.B)
  private val aggWakeAck = WireDefault(false.B)

  hostedRdi match {
    case Some(ctrl) =>
      val hosts = (0 until n).map(io.modules(_).rdiHost.get)

      ctrl.io.rdi.lpStateReq := io.rdi.lpStateReq
      ctrl.io.rdi.lpWakeReq := io.rdi.lpWakeReq
      ctrl.io.rdi.lpClkAck := io.rdi.lpClkAck
      ctrl.io.rdi.lpStallAck := io.rdi.lpStallAck
      ctrl.io.rdi.lpLinkError := io.rdi.lpLinkError
      // Spec 4.7.1.1 again: the Link's own messages need an eligible Module.
      ctrl.io.sbLinkDown := !sbTrafficEligible.asUInt.orR

      // The Link is only ready to bring RDI up once every operational Module is,
      // and any one Module failing or needing clocks speaks for the Link. A
      // Module that failed to train speaks for nothing while the rest can still
      // degrade around it: its timeout would take the Link to LinkError. Nor
      // does one the directive now being applied is disabling, which can time
      // out in its exchange or its TRAINERROR handshake on the way out.
      ctrl.io.doRdiBringup :=
        someRdiMember && allRdiMember(m => hosts(m).doRdiBringup)
      ctrl.io.internalLinkError := anyRdiMember(m =>
        hosts(m).internalLinkError && !leavingByDirective(m)
      )
      // Spec 3.5: one RDI for the Link, so a Module's software retrain is a
      // retrain of the whole Link, through that RDI.
      ctrl.io.internalRetrainReq := anyRdiMember(hosts(_).swRetrainRequest)
      // Nothing may be left in flight when the RDI leaves Active: neither the
      // rest of an aggregate word spreading over successive beats, nor a slice
      // part-way through a Module's Lanes.
      ctrl.io.txDrained := !txHold && allRdiMember(hosts(_).txIdle)
      ctrl.io.validFramingError := anyRdiMember(m => hosts(m).validFramingError)
      ctrl.io.cfgSidebandActive :=
        anyEnabled(m => hosts(m).cfgSidebandActive) || cfgSidebandBusy
      ctrl.io.plPhyInRecenter := anyRdiMember(m => hosts(m).plPhyInRecenter)
      ctrl.io.clocksUngatedAndStable :=
        someRdiMember && allRdiMember(m => hosts(m).clocksUngatedAndStable)

      // The state machine keys off LINKINIT and ACTIVE to raise inband presence
      // and force bring-up, so it must not see either until every operational
      // Module has got there.
      val ltsmForRdi = WireDefault(LTState.sRESET)
      when(
        someRdiMember && allRdiMember(m =>
          hosts(m).ltsmState === LTState.sACTIVE
        )
      ) {
        ltsmForRdi := LTState.sACTIVE
      }.elsewhen(
        someRdiMember && allRdiMember(m =>
          (hosts(m).ltsmState === LTState.sLINKINIT) ||
            (hosts(m).ltsmState === LTState.sACTIVE)
        )
      ) {
        ltsmForRdi := LTState.sLINKINIT
      }.elsewhen(
        anyRdiMember(m =>
          (hosts(m).ltsmState === LTState.sTRAINERROR) &&
            !leavingByDirective(m)
        )
      ) {
        /* A Module the current resolution is disabling passes through
           TRAINERROR by design (spec 4.5.3.4.12 Step 5d) while it is still
           counted operational. That is not the Link going down, and
           RDIController would otherwise drop pl_inband_pres, which Table 10-1
           requires to stay high until the Link is down. */
        ltsmForRdi := LTState.sTRAINERROR
      }.elsewhen(!allRdiMember(m => hosts(m).ltsmState === LTState.sRESET)) {
        // Somewhere in the middle of training: not RESET, not up yet.
        ltsmForRdi := LTState.sMBTRAIN
      }
      ctrl.io.ltsmState := ltsmForRdi

      for (m <- 0 until n) {
        hosts(m).plStateSts := ctrl.io.rdi.plStateSts
        hosts(m).doingRdiBringup := ctrl.io.doingRdiBringup
      }

      // Spec 4.7.1.1: {LinkMgmt.RDI.*} is a Table 7-8 message, so it goes out on
      // the sideband of the numerically least eligible Module -- the same one
      // the cfg path uses (see sbTrafficEligible).
      val rdiSbEligible = (0 until n).map(sbTrafficEligible(_))
      val rdiSbSelect = PriorityEncoderOH(rdiSbEligible)
      for (m <- 0 until n) {
        hosts(m).sbLaneIo.tx.valid := ctrl.io.sbLaneIo.tx.valid && rdiSbSelect(
          m
        )
        hosts(m).sbLaneIo.tx.bits := ctrl.io.sbLaneIo.tx.bits
      }
      ctrl.io.sbLaneIo.tx.ready :=
        Mux1H(rdiSbSelect, (0 until n).map(hosts(_).sbLaneIo.tx.ready))

      /* "A packet sent on a given Module ID could be received on a different
         Module ID on the sideband Receiver", so take the response from
         whichever Module it landed on.

         The hosted machine can only look at one Module per cycle, and
         `sbLaneIo.rx.ready` is a claim decode rather than flow control: a
         LogicalPhy retires anything nobody claimed in the cycle it was offered.
         So the Modules that lose arbitration must be told to hold their packet
         rather than left to read a low ready as a rejection -- otherwise a
         {LinkMgmt.RDI.Req.*} arriving on one Module while a response arrives on
         another is destroyed, and RDI bring-up waits forever for it. The
         granted Module still sees the machine's real decode, so a packet the
         machine does not recognise is still retired as unhandled. */
      val rdiRxOffered =
        (0 until n).map(m => moduleEnable(m) && hosts(m).sbLaneIo.rx.valid)
      val rdiRxGrant = PriorityEncoderOH(rdiRxOffered)
      ctrl.io.sbLaneIo.rx.valid := rdiRxOffered.reduce(_ || _)
      ctrl.io.sbLaneIo.rx.bits :=
        Mux1H(rdiRxGrant, (0 until n).map(hosts(_).sbLaneIo.rx.bits))
      for (m <- 0 until n) {
        hosts(m).sbLaneIo.rx.ready :=
          ctrl.io.sbLaneIo.rx.ready && rdiRxGrant(m)
        hosts(m).rxHold := rdiRxOffered(m) && !rdiRxGrant(m)
      }

      aggState := ctrl.io.rdi.plStateSts
      stallDrain := ctrl.io.stallDrain
      aggInbandPres := ctrl.io.rdi.plInbandPres
      aggStallReq := ctrl.io.rdi.plStallReq
      aggClkReq := ctrl.io.rdi.plClkReq
      aggWakeAck := ctrl.io.rdi.plWakeAck
      dontTouch(ctrl.io.ungateClocks)

    case None =>
      // One Module, which owns the state machine itself.
      aggState := io.modules(0).rdi.plStateSts
      aggInbandPres := io.modules(0).rdi.plInbandPres
      aggStallReq := io.modules(0).rdi.plStallReq
      aggClkReq := io.modules(0).rdi.plClkReq
      aggWakeAck := io.modules(0).rdi.plWakeAck
  }

  /* Up is Active or Active.PMNAK. Spec 10.3: "Because Active.PMNAK is a
     sub-state of Active, all rules that apply for Active are also applicable
     for Active.PMNAK" -- the Modules stay ACTIVE and keep delivering through
     it, and with L1/L2 unimplemented every PM request lands here. */
  private val aggUp =
    (aggState === RDIState.active) || (aggState === RDIState.activePmNak)
  private val wasUp = RegNext(aggUp, false.B)

  // Total width across all active Modules (spec 10.1 pl_lnk_cfg). The LinkWidth
  // encoding is a log2 ladder, so doubling the Module count adds one.
  private val numActiveLog2 = WireDefault(0.U(2.W))
  MmplByteMap.permittedActiveCounts(n).foreach { count =>
    when(numActive === count.U) { numActiveLog2 := log2Ceil(count).U }
  }
  private val moduleWidth = fromLeastEnabled(io.modules(_).status.linkWidth)
  private val aggWidthCode = moduleWidth.asUInt +& numActiveLog2
  // Widths beyond x256 have no encoding; a Standard Package x16 Module set
  // reaches x64 at four Modules, so this only guards against misconfiguration.
  private val aggWidthInRange = aggWidthCode <= LinkWidth.x256.asUInt
  private val (aggWidth, aggWidthEncoded) = LinkWidth.safe(aggWidthCode(2, 0))
  private val aggWidthValid = aggWidthInRange && aggWidthEncoded

  /* Each LogicalPhy delivers slices only while its LTSM is ACTIVE, and the
     flush below discards anything still queued when the Link leaves Active, so
     a gather can only ever complete from slices of the current Active period.

     A Valid framing error goes up as pl_error on the beat that carries it --
     with its word, if that beat completes one -- and after it nothing goes up
     until the Link is back in Active. pl_error is only permitted while the RDI
     is Active (Table 10-1), so a corrupted word that completes outside it is
     dropped instead. */
  when(!wasUp && aggUp) {
    rxErrLatched := false.B
  }
  io.rdi.plValid := rxWordDone && !rxErrLatched && (!rxBeatErr || aggUp)
  io.rdi.plData := Mux(someModuleEnabled, rxGathered, 0.U)
  io.rdi.plStateSts := aggState
  io.rdi.plLnkCfg := aggWidth
  io.rdi.plInbandPres := aggInbandPres
  io.rdi.plStallReq := aggStallReq
  io.rdi.plClkReq := aggClkReq
  io.rdi.plWakeAck := aggWakeAck
  io.rdi.plError := aggUp && (rxBeatErr || rxErrLatched)
  io.rdi.plCError := anyEnabled(io.modules(_).rdi.plCError)
  io.rdi.plNfError := anyEnabled(io.modules(_).rdi.plNfError)
  // A Module being degraded around has not failed the Link.
  io.rdi.plTrainError := anyRdiMember(io.modules(_).rdi.plTrainError)
  io.rdi.plPhyInRecenter := anyEnabled(io.modules(_).rdi.plPhyInRecenter)
  io.rdi.plSpeedmode := fromLeastEnabled(io.modules(_).rdi.plSpeedmode)
  io.rdi.plMaxSpeedmode := fromLeastEnabled(io.modules(_).rdi.plMaxSpeedmode)

  // ==========================================================================
  // Per-Module RDI drive
  // ==========================================================================
  for (m <- 0 until n) {
    val mod = io.modules(m).rdi
    mod.lclk := io.rdi.lclk
    mod.lpStateReq := io.rdi.lpStateReq
    mod.lpLinkError := io.rdi.lpLinkError
    mod.lpStallAck := io.rdi.lpStallAck
    mod.lpClkAck := io.rdi.lpClkAck
    mod.lpWakeReq := io.rdi.lpWakeReq
    /* Every Module must take its slice of the beat on the same cycle. A Module
       latches whenever its own pl_trdy meets lp_valid and lp_irdy
       (spec Table 10-1), so offering the beat to a Module that happens to be
       ready while another is not would have it transmit that slice now and
       again when the aggregate finally fires -- leaving the Modules' byte
       streams permanently one beat apart. Gate on the joint fire. */
    mod.lpIrdy := txFire && moduleEnable(m)
    mod.lpValid := txFire && moduleEnable(m)
    mod.lpData := swizzle.io.tx.moduleData(m)
  }

  // ==========================================================================
  // Sideband cfg path (spec 4.7.1.1)
  // ==========================================================================
  if (n == 1) {
    // Nothing to select or merge.
    io.modules(0).rdi.lpCfg := io.rdi.lpCfg
    io.modules(0).rdi.lpCfgVld := io.rdi.lpCfgVld
    io.modules(0).rdi.lpCfgCrd := io.rdi.lpCfgCrd
    io.rdi.plCfg := io.modules(0).rdi.plCfg
    io.rdi.plCfgVld := io.modules(0).rdi.plCfgVld
    io.rdi.plCfgCrd := io.modules(0).rdi.plCfgCrd
  } else {
    val chunksPerPacket =
      math.max(1, sbParams.sbNodeMsgWidth / rdiParams.ncWidth)
    val chunkCtrW = log2Ceil(chunksPerPacket + 1)

    require(
      sbParams.sbNodeMsgWidth % rdiParams.ncWidth == 0,
      s"MMPL stages whole sideband cfg packets, so the RDI cfg width " +
        s"(${rdiParams.ncWidth}) must divide the sideband message width " +
        s"(${sbParams.sbNodeMsgWidth})"
    )

    /* Neither cfg bus has a ready line -- spec 10.1.2: "back pressure is not
       possible from the Adapter to the Physical Layer" -- so a staging queue
       that fills has nowhere to put the chunk but the floor, and because the
       chunk counters advance on `fire` the survivors re-frame across packet
       boundaries: every later packet is then assembled from pieces of two.
       There is no recovery from that short of reset, so the depths have to be
       the flow-control bound rather than a guess.

       Transmit: the Adapter's credit for an lp_cfg packet only comes back once
       a Module has taken it out of this queue (io.rdi.plCfgCrd is the Modules'
       returns), so it can never have more than maxCrd packets in flight here.

       Receive: the remote transmitter is bounded by the credits the local
       Module advertised, which is the pool split numModules ways. */
    val txCfgPacketDepth = math.max(params.cfgTxDepth, sbParams.maxCrd)
    val rxCfgPacketDepth = math.max(2, sbParams.maxCrd / n)

    /* Transmit on the numerically least Module whose LTSM has moved past
       SBINIT, so the remote side has a trained sideband to receive it on.

       That choice moves as Modules train, and spec 7.1.4 requires the phases of
       one packet to go out on consecutive cycles -- on one Module. lp_cfg has
       no ready line, so the Adapter cannot be held off either. Stage whole
       packets here instead: chunks are always accepted, and a packet is only
       started once it is fully resident and a Module has been picked, after
       which the pick is registered for the length of the packet. That also
       covers the window where no Module is eligible at all, which used to drop
       the Adapter's chunks on the floor along with the credit it spent.

       A new packet starts only on a Module sbTrafficEligible allows. One
       already under way is finished on its Module as long as that Module's
       sideband channel is still up (not RESET or SBINIT): cutting it short
       there would only lose it sooner. */
    val cfgTxEligible = (0 until n).map(sbTrafficEligible(_))
    val cfgTxReachable = (0 until n).map { m =>
      moduleEnable(m) && (ltStates(m) =/= LTState.sRESET) &&
      (ltStates(m) =/= LTState.sSBINIT)
    }
    val cfgTxAny = cfgTxEligible.reduce(_ || _)

    val txCfg = Module(
      new Queue(UInt(rdiParams.ncWidth.W), txCfgPacketDepth * chunksPerPacket)
    )
    txCfg.suggestName("txCfgQueue")
    txCfg.io.enq.valid := io.rdi.lpCfgVld
    txCfg.io.enq.bits := io.rdi.lpCfg

    val txCfgEnqChunks = RegInit(0.U(chunkCtrW.W))
    val txCfgPackets = RegInit(0.U(log2Ceil(txCfgPacketDepth + 1).W))
    val txCfgEnqPacketDone =
      txCfg.io.enq.fire && (txCfgEnqChunks === (chunksPerPacket - 1).U)
    when(txCfg.io.enq.fire) {
      txCfgEnqChunks := Mux(txCfgEnqPacketDone, 0.U, txCfgEnqChunks + 1.U)
    }

    val cfgTxGrantValid = RegInit(false.B)
    val cfgTxGrant = RegInit(VecInit(Seq.fill(n)(false.B)))
    val cfgTxChunks = RegInit(0.U(chunkCtrW.W))

    /* The grant is registered for the length of the packet, so it can outlive
       the Module's eligibility: an LTSM dropping to RESET mid-packet takes its
       whole sideband channel down with it (LogicalPhy scopes the deserializer,
       priority queue and credit counters under sbReset), and the packet
       evaporates. Watch the granted Module rather than assume it stays. */
    val cfgTxGrantEligible =
      (0 until n).map(m => cfgTxGrant(m) && cfgTxReachable(m)).reduce(_ || _)
    val cfgTxAbort = cfgTxGrantValid && !cfgTxGrantEligible

    val cfgTxSending = cfgTxGrantValid && !cfgTxAbort && txCfg.io.deq.valid
    val cfgTxPacketDone =
      cfgTxSending && (cfgTxChunks === (chunksPerPacket - 1).U)

    /* Nothing can rescue the packet -- the chunks already on the wire went into
       a channel that is now in reset -- but the rest of it has to come out of
       the queue or every later packet is framed from the wrong chunks, and the
       Adapter's credit has to come back or it is gone for good. Drain and
       refund. */
    val cfgTxDraining = RegInit(false.B)
    val cfgTxDrainChunks = RegInit(0.U(chunkCtrW.W))
    val cfgTxDrainFire = cfgTxDraining && txCfg.io.deq.valid
    val cfgTxDrainDone =
      cfgTxDrainFire && (cfgTxDrainChunks === (chunksPerPacket - 1).U)
    val cfgTxRefund = WireDefault(false.B)

    when(!cfgTxGrantValid) {
      when(txCfgPackets =/= 0.U && cfgTxAny && !cfgTxDraining) {
        cfgTxGrantValid := true.B
        cfgTxGrant := VecInit(PriorityEncoderOH(cfgTxEligible))
        cfgTxChunks := 0.U
      }
    }.elsewhen(cfgTxAbort) {
      cfgTxGrantValid := false.B
      // Whatever is left of this packet still has to leave the queue.
      cfgTxDraining := true.B
      cfgTxDrainChunks := cfgTxChunks
    }.elsewhen(cfgTxSending) {
      cfgTxChunks := Mux(cfgTxPacketDone, 0.U, cfgTxChunks + 1.U)
      when(cfgTxPacketDone) { cfgTxGrantValid := false.B }
    }

    when(cfgTxDrainFire) {
      cfgTxDrainChunks := cfgTxDrainChunks + 1.U
      when(cfgTxDrainDone) {
        cfgTxDraining := false.B
        cfgTxDrainChunks := 0.U
        cfgTxRefund := true.B
      }
    }

    txCfgPackets := txCfgPackets + txCfgEnqPacketDone.asUInt -
      cfgTxPacketDone.asUInt - cfgTxDrainDone.asUInt
    txCfg.io.deq.ready := cfgTxSending || cfgTxDrainFire

    // Receive: a packet sent on one Module ID can arrive on a different one, so
    // hold every Module's chunks and forward one whole packet at a time.
    val rxCfg = Seq.tabulate(n) { m =>
      val q = Module(
        new Queue(UInt(rdiParams.ncWidth.W), rxCfgPacketDepth * chunksPerPacket)
      )
      q.suggestName(s"rxCfgQueue_$m")
      q.io.enq.valid := io.modules(m).rdi.plCfgVld
      q.io.enq.bits := io.modules(m).rdi.plCfg
      q
    }

    val rxCfgEnqChunks = Seq.tabulate(n)(m =>
      RegInit(0.U(chunkCtrW.W)).suggestName(s"rxCfgEnqChunks_$m")
    )
    /* Counts whole packets, so it is sized by the queue's packet depth -- not
       by chunkCtrW, which bounds a chunk index within one packet. The two
       happened to coincide while the queue held two packets; they do not once
       it is sized by the credit bound, and a counter that wraps silently loses
       the forwarding FSM's place. */
    val rxCfgPackets = Seq.tabulate(n)(m =>
      RegInit(0.U(log2Ceil(rxCfgPacketDepth + 1).W))
        .suggestName(s"rxCfgPackets_$m")
    )

    val cfgGrantValid = RegInit(false.B)
    val cfgGrant = RegInit(0.U(log2Ceil(n).W))
    val cfgDeqChunks = RegInit(0.U(chunkCtrW.W))

    val cfgForwarding =
      cfgGrantValid && VecInit(rxCfg.map(_.io.deq.valid))(cfgGrant)
    val cfgPacketDone =
      cfgForwarding && (cfgDeqChunks === (chunksPerPacket - 1).U)

    for (m <- 0 until n) {
      val enqPacketDone = rxCfg(m).io.enq.fire &&
        (rxCfgEnqChunks(m) === (chunksPerPacket - 1).U)
      when(rxCfg(m).io.enq.fire) {
        rxCfgEnqChunks(m) := Mux(enqPacketDone, 0.U, rxCfgEnqChunks(m) + 1.U)
      }
      val consumed = cfgPacketDone && (cfgGrant === m.U)
      rxCfgPackets(m) := rxCfgPackets(m) + enqPacketDone.asUInt -
        consumed.asUInt

      rxCfg(m).io.deq.ready := cfgForwarding && (cfgGrant === m.U)

      io.modules(m).rdi.lpCfg := Mux(cfgTxGrant(m), txCfg.io.deq.bits, 0.U)
      io.modules(m).rdi.lpCfgVld := cfgTxGrant(m) && cfgTxSending
    }

    val cfgHasPacket = (0 until n).map(m => rxCfgPackets(m) =/= 0.U)
    when(!cfgGrantValid) {
      when(cfgHasPacket.reduce(_ || _)) {
        cfgGrantValid := true.B
        cfgGrant := PriorityEncoder(cfgHasPacket)
        cfgDeqChunks := 0.U
      }
    }.elsewhen(cfgForwarding) {
      cfgDeqChunks := Mux(cfgPacketDone, 0.U, cfgDeqChunks + 1.U)
      when(cfgPacketDone) { cfgGrantValid := false.B }
    }

    io.rdi.plCfg := VecInit(rxCfg.map(_.io.deq.bits))(cfgGrant)
    io.rdi.plCfgVld := cfgForwarding

    /* Credits the PHY owes upward come from whichever Module received the
       packet, and only the transmitting Module is owed credits back. Track the
       order packets were forwarded in so the Adapter's returns land on the
       right Module. The depth is the credit bound, not the Module count: the
       Adapter advertises maxCrd credits for the one RDI, so that many packets
       can be outstanding and uncredited at once.

       Only *credited* packets belong in that order, though. Spec 7.1.3.3:
       "Register Access completions do not consume a credit and must always
       sink", and 7.1.3.1 says the Transmitter must not even check for credits
       before sending one -- so a conformant Adapter returns nothing for a
       completion. Enqueuing one anyway leaves an entry that is never popped,
       and from then on every credit return is handed to the wrong Module until
       one of them is starved out of the sideband entirely.

       The opcode is bits [4:0] of the message and the serializer emits the LSB
       chunk first (SidebandInterfaceSerdes), so it is readable off chunk 0.
       Sampled combinationally as well as latched, because a one-chunk packet
       finishes on the same cycle its header goes out. */
    val cfgFwdHeaderChunk = cfgForwarding && (cfgDeqChunks === 0.U)
    val cfgFwdCompletionNow = SBM.isRegAccessComplete(io.rdi.plCfg(4, 0))
    val cfgFwdCompletionReg = RegInit(false.B)
    when(cfgFwdHeaderChunk) { cfgFwdCompletionReg := cfgFwdCompletionNow }
    val cfgFwdIsCompletion =
      Mux(cfgFwdHeaderChunk, cfgFwdCompletionNow, cfgFwdCompletionReg)

    val cfgCrdOrder =
      Module(new Queue(UInt(log2Ceil(n).W), sbParams.maxCrd))
    cfgCrdOrder.suggestName("cfgCreditOrderQueue")
    cfgCrdOrder.io.enq.valid := cfgPacketDone && !cfgFwdIsCompletion
    cfgCrdOrder.io.enq.bits := cfgGrant
    cfgCrdOrder.io.deq.ready := io.rdi.lpCfgCrd

    for (m <- 0 until n) {
      io.modules(m).rdi.lpCfgCrd := io.rdi.lpCfgCrd &&
        cfgCrdOrder.io.deq.valid && (cfgCrdOrder.io.deq.bits === m.U)
    }
    /* Table 10-1: a 1 on pl_cfg_crd is exactly one credit return, so two
       Modules returning one on the same cycle cannot be OR'd into one wire
       without losing the second for good. Queue the surplus and emit one per
       cycle. Disabled Modules are counted too -- a Module dropped from the Link
       may still be holding a credit the Adapter is owed. */
    val cfgCrdPendingW = log2Ceil(n * sbParams.maxCrd + 2)
    val cfgCrdPending = RegInit(0.U(cfgCrdPendingW.W))
    /* lp_cfg packets each Module has been handed but not yet returned a credit
       for. A Module returns one only once the packet leaves its priority queue
       (SidebandInterfaceNode), and a Module dropping to RESET resets its whole
       sideband channel, so whatever it still holds dies there -- and those
       credits would never come back. Register Access completions never
       consume a credit (spec 7.1.3.3), so they are not counted. */
    val cfgTxHeaderNow = cfgTxSending && (cfgTxChunks === 0.U)
    val cfgTxCompletionNow =
      SBM.isRegAccessComplete(txCfg.io.deq.bits(4, 0))
    val cfgTxCompletionReg = RegInit(false.B)
    when(cfgTxHeaderNow) { cfgTxCompletionReg := cfgTxCompletionNow }
    val cfgTxIsCompletion =
      Mux(cfgTxHeaderNow, cfgTxCompletionNow, cfgTxCompletionReg)

    val cfgCrdOwedW = log2Ceil(sbParams.maxCrd + 1)
    val cfgCrdOwed = RegInit(VecInit(Seq.fill(n)(0.U(cfgCrdOwedW.W))))
    val cfgCrdOwedRefund = Wire(Vec(n, UInt(cfgCrdOwedW.W)))
    for (m <- 0 until n) {
      val handed = cfgTxPacketDone && cfgTxGrant(m) && !cfgTxIsCompletion
      val returned =
        io.modules(m).rdi.plCfgCrd && (cfgCrdOwed(m) =/= 0.U || handed)
      val owedNow = cfgCrdOwed(m) + handed.asUInt - returned.asUInt
      val channelReset = ltStates(m) === LTState.sRESET
      cfgCrdOwedRefund(m) := Mux(channelReset, owedNow, 0.U)
      cfgCrdOwed(m) := Mux(channelReset, 0.U, owedNow)
    }

    /* A Module's return for a packet it accepted, plus the MMPL's own refunds
       for packets that died with their Module before it could return one --
       part-sent (cfgTxRefund) or held (cfgCrdOwedRefund). All are credits the
       Adapter is owed for lp_cfg it has spent. */
    val cfgCrdArriving =
      PopCount((0 until n).map(io.modules(_).rdi.plCfgCrd)) +&
        cfgTxRefund.asUInt +& cfgCrdOwedRefund.reduce(_ +& _)
    val cfgCrdEmit = (cfgCrdPending +& cfgCrdArriving) =/= 0.U
    cfgCrdPending := cfgCrdPending +& cfgCrdArriving - cfgCrdEmit.asUInt
    io.rdi.plCfgCrd := cfgCrdEmit

    /* Spec 10.1.3.2 rule 9: the Physical Layer must hold pl_clk_req across
       pl_cfg transitions, and Table 10-1 puts credit returns under the same
       rule. A Module's own bus activity no longer covers that, because the
       staging queues here decouple it from the aggregate bus, so the MMPL adds
       its own in-flight state to the request. */
    cfgSidebandBusy :=
      cfgGrantValid || cfgTxGrantValid || cfgTxDraining ||
        (cfgCrdPending =/= 0.U) || (txCfgPackets =/= 0.U) ||
        (0 until n).map(m => rxCfgPackets(m) =/= 0.U).reduce(_ || _)

    block(Verification) {
      block(Verification.Assert) {
        assert(
          !cfgTxGrantValid || PopCount(cfgTxGrant) === 1.U,
          "FATAL: MMPL selected more than one Module for the cfg transmit path"
        )
        // Spec 7.1.4: the phases of one packet go out on consecutive cycles.
        assert(
          !cfgTxGrantValid || cfgTxSending || cfgTxChunks === 0.U,
          "FATAL: MMPL broke a sideband cfg packet across non-consecutive cycles"
        )
        (0 until n).foreach { m =>
          assert(
            rxCfg(m).io.enq.ready || !rxCfg(m).io.enq.valid,
            "FATAL: MMPL dropped a received sideband cfg chunk"
          )
        }
        assert(
          txCfg.io.enq.ready || !txCfg.io.enq.valid,
          "FATAL: MMPL dropped a sideband cfg chunk from the Adapter"
        )
        assert(
          cfgCrdOrder.io.enq.ready || !cfgCrdOrder.io.enq.valid,
          "FATAL: MMPL lost track of which Module owns a sideband cfg credit"
        )
      }
      block(Verification.Cover) {
        cover(cfgGrantValid && cfgGrant =/= 0.U)
        cover(cfgCrdArriving > 1.U)
        cover(txCfgPackets =/= 0.U && !cfgTxAny)
        // A Module going away mid-packet, and the credit coming back for it.
        cover(cfgTxAbort)
        cover(cfgTxRefund)
      }
    }
  }

  // ==========================================================================
  // MBTRAIN.LINKSPEED resolution (spec 4.7.1)
  // ==========================================================================
  private val resolver = Module(new MmplLinkSpeedResolver(params))
  for (m <- 0 until n) {
    resolver.io.reports(m) := io.modules(m).status.linkSpeedReport
    resolver.io.enable(m) := moduleEnable(m)
  }
  resolver.io.currentSpeed := fromLeastEnabled(io.modules(_).status.freqSel)

  /* Spec 4.7.1.2.1: a Module whose "width is already lower from the rest of the
     operational modules" counts as requesting a width degrade even though it
     exchanged {MBTRAIN.LINKSPEED done req}, because a multi-module Link needs
     one common width. The test is relative, so it belongs above a Module:
     measuring against full width instead would mean every Module of an
     already-degraded Link reports a width degrade for ever, and the Link would
     be sent back to MBTRAIN.REPAIR on every pass rather than proceeding to
     Step 6.
     The counts go down raw and the resolver makes the comparison, because it
     has to be remade against the survivors on each pass of the flow chart. */
  for (m <- 0 until n) {
    resolver.io.activeLanes(m) := MmplByteMap.activeLanes(
      io.modules(m).status.localTxFunctionalLanes,
      negotiatedBy8
    )
    // Both directions: what this die receives on is what the other die
    // transmits on, so the two die judge "narrower" on the same widths.
    resolver.io.activeRxLanes(m) := MmplByteMap.activeLanes(
      io.modules(m).status.remoteTxFunctionalLanes,
      negotiatedBy8
    )
    resolver.io.failed(m) := failedReg(m)
  }

  // Latch the resolution before applying it. Dropping a Module changes the
  // resolver's own inputs, so directing and applying have to be separate steps.
  private val resolveState = RegInit(MmplResolveState.idle)
  private val directed = RegInit(
    VecInit(Seq.fill(n)(MmplResolution.none))
  )
  private val directedEnable = RegInit(VecInit(Seq.fill(n)(true.B)))
  private val directedLink = RegInit(MmplResolution.none)

  private val reportsPending = anyEnabled(
    io.modules(_).status.linkSpeedReport.valid
  )

  /* A Module only samples the directive once it has reached the substate that
     waits for one, and Modules of a Link can be staggered by a long way (spec
     4.7.1.2). Holding the directive only while reports are pending drops it as
     soon as the *reporting* Modules have acted, which on the PHY retrain path
     can be a single Module -- the resolver deliberately answers that one
     without waiting for the rest. A sibling still running the point test would
     then arrive to find nothing directed and no way to resolve again, because
     the Module that left is still enabled but no longer reporting.

     So the directive is held until every operational Module has been through
     MBTRAIN.LINKSPEED and out again, or has left MBTRAIN altogether. "Through"
     matters: a Module still in DATATRAINCENTER2 has not acted, because it has
     yet to reach the substate that samples a directive, and spec 4.5.3.4.12
     Step 5 makes a PHY retrain request received on any Module a directive for
     every Module of the Link however far behind it is. Its remote Module
     Partner is in lockstep with it, so the pair finishes LINKSPEED Steps 1 and
     2 together and both find the held directive waiting.

     That has to be latched per Module rather than tested as a level: a Module
     that acts on the directive walks the rest of MBTRAIN and comes back round
     to LINKSPEED for the next pass, and on a staggered Link it can be back
     before its siblings have left. A level would read that as "still waiting",
     never retire the directive, and feed the returning Module the previous
     pass's answer for ever. */
  private val inLinkSpeed = (0 until n).map(m =>
    io.modules(m).status.currentState === LTSMState.sMBTRAIN_LINKSPEED
  )
  private val inMbTrain = (0 until n).map(m => ltStates(m) === LTState.sMBTRAIN)
  // Seen in LINKSPEED since the directive was issued, and acted on it. The
  // registers carry the memory across a Module's return to LINKSPEED; the live
  // terms keep a Module leaving this cycle from costing an extra one.
  private val directedSeen = RegInit(VecInit(Seq.fill(n)(false.B)))
  private val directedAck = RegInit(VecInit(Seq.fill(n)(false.B)))
  private def ackedNow(m: Int): Bool =
    !inMbTrain(m) || (directedSeen(m) && !inLinkSpeed(m))
  private val allDirectedAck = allEnabled(m => directedAck(m) || ackedNow(m))

  /* Every resolution but one is reached only after *all* operational Modules
     have reported, so all of them were parked waiting and "no report pending"
     is already the right retirement test -- and the safe one, because it does
     not depend on watching a Module out of a state it re-enters a few
     substates later. PHY retrain is the exception: the resolver answers it
     from a single Module's report by design, so its directive is the only one
     that can be retired before a sibling has even reached the substate that
     samples it. Extend the hold for that case alone. */
  private val holdForPhyRetrain =
    (directedLink === MmplResolution.phyRetrain) && !allDirectedAck

  /* Only a Module still owed this directive can hold it. One that has already
     acted may be back in LINKSPEED with its next pass's report -- the PHY
     retrain hold above keeps the directive long enough for that on a staggered
     Link -- and the directive is withdrawn from it, so waiting on that report
     left the directive unable to retire and every Module timing out. */
  private val reportsPendingUnacked = anyEnabled(m =>
    io.modules(m).status.linkSpeedReport.valid && !directedAck(m)
  )

  // A Module whose remote partner answered something other than the directed
  // resolution (spec 4.5.3.4.12 Step 5d).
  private val respMismatch =
    anyEnabled(io.modules(_).status.linkSpeedRespMismatch)

  switch(resolveState) {
    is(MmplResolveState.idle) {
      when(params.isMultiModule.B && resolver.io.resolved && reportsPending) {
        directed := resolver.io.moduleResolution
        directedEnable := resolver.io.nextEnable
        directedLink := resolver.io.linkResolution
        // A Module outside MBTRAIN has nothing to act on and acks at once; one
        // anywhere in MBTRAIN acks once it has been through LINKSPEED.
        directedSeen := VecInit(inLinkSpeed)
        directedAck := VecInit((0 until n).map(m => !inMbTrain(m)))
        resolveState := MmplResolveState.directing
      }
    }
    is(MmplResolveState.directing) {
      for (m <- 0 until n) {
        when(inLinkSpeed(m)) { directedSeen(m) := true.B }
        when(ackedNow(m)) { directedAck(m) := true.B }
      }
      /* Spec 4.5.3.4.12 Step 5d: "Any mismatch on received message vs. expected
         resolution must take all modules to TRAINERROR." A Module can only see
         its own exchange, so it reports the mismatch and the MMPL is what turns
         it into a directive for the rest -- otherwise the Module that caught it
         goes to TRAINERROR alone while its siblings complete a handshake the
         remote Link partner has already contradicted, and the two die end up in
         different configurations.

         Replacing the latched directive works because a Module sampled it in
         s11 and is now exchanging in s12, where it watches for exactly this. */
      when(respMismatch) {
        directed.foreach(_ := MmplResolution.trainError)
        directedLink := MmplResolution.trainError
        // Keep the operational set: see the trainError note below.
        directedEnable := moduleEnable
      }
      // Hold the directive until every Module has acted on it, then shrink the
      // operational set.
      when(!reportsPendingUnacked && !holdForPhyRetrain) {
        /* Except on trainError, where the resolver's nextEnable is all-false
           because no operational configuration remains. Committing that would
           mask the very failure it reports: every anyEnabled() reduction below
           goes quiet, taking pl_trainerror with it and, worse, the training
           timeout that is the hosted RDI state machine's only path to
           LinkError. The Modules head for TRAINERROR and RESET on their own, so
           keep the operational set and let `linkInReset` restore it once they
           have all fallen back. */
        when(directedLink =/= MmplResolution.trainError) {
          moduleEnableReg := directedEnable
        }
        clearBeatState := true.B
        resolveState := MmplResolveState.idle
      }
    }
  }

  /* Spec 4.7.1: "if any module failed to train, the MMPL must ensure that the
     multi-module configuration degrades to the next permitted configuration."
     A Module that times out or falls into TRAINERROR on its own before
     reporting -- in SBINIT or MBINIT, say -- never reaches LINKSPEED, and one
     still in RESET once every sibling is parked on its report never will; the
     resolver used to wait on either until every sibling timed out and the Link
     went to LinkError. Latch it as failed instead, hold it in RESET, and let
     the resolver disable it like any Module that cannot run.

     Only while the Link is not up and some sibling is still on its way to
     MBTRAIN.LINKSPEED, where the resolution that removes the failed Module
     will happen: a runtime failure, a failure once the rest have gone on to
     LINKINIT, or every Module falling together is the Link going down, and
     keeps its usual path to retry and LinkError. A Module the directive is
     disabling, or a Link the directive has sent to TRAINERROR, is falling by
     design.

     "Some sibling still on its way" means one that can carry the Link: not
     one falling out itself -- timed out, or in TRAINERROR, which a Module
     that has timed out takes a while to reach -- and not one the directive is
     disabling. Counting those, Modules that fall together (they leave RESET
     together and time out together, and a Retrain puts them all in PHYRETRAIN
     at once) counted each other as the one still training, were all latched
     failed, and so none of them retried or escalated. */
  /* A Module has failed once it times out, not only once it reaches
     TRAINERROR. The residency timeout is what the hosted RDI state machine
     takes to LinkError, and the TRAINERROR handshake that follows it can wait
     another 8 ms on a silent partner: left operational until then, one
     Module's timeout took the whole Link down, and a sibling parked on the
     resolution could time out first and be the one blamed. In RESET the flag is
     left over from the previous training until the Module leaves, so it only
     counts outside RESET -- a Module stuck there is `neverStarted`. */
  private val droppingOut = VecInit((0 until n).map { m =>
    val timedOut = io.modules(m).status.trainingTimedout &&
      (ltStates(m) =/= LTState.sRESET)
    (ltStates(m) === LTState.sTRAINERROR) || timedOut
  })
  private val stillTraining = VecInit((0 until n).map { m =>
    moduleEnable(m) && !failedReg(m) && !leavingByDirective(m) &&
    !droppingOut(m) &&
    ((ltStates(m) === LTState.sSBINIT) || (ltStates(m) === LTState.sMBINIT) ||
      (ltStates(m) === LTState.sMBTRAIN) ||
      (ltStates(m) === LTState.sPHYRETRAIN))
  })
  private val trainErrorDirected =
    (resolveState === MmplResolveState.directing) &&
      (directedLink === MmplResolution.trainError)
  for (m <- 0 until n) {
    val siblings = (0 until n).filter(_ != m)
    val siblingTraining =
      siblings.map(stillTraining(_)).foldLeft(false.B)(_ || _)
    val siblingsReported = siblings
      .map { k =>
        !(moduleEnable(k) && !failedReg(k)) ||
        io.modules(k).status.linkSpeedReport.valid
      }
      .foldLeft(true.B)(_ && _)
    val droppedOut = droppingOut(m)
    val neverStarted = (ltStates(m) === LTState.sRESET) &&
      (resolveState === MmplResolveState.idle) && siblingsReported
    fellOut(m) := moduleEnable(m) && !failedReg(m) && !aggUp &&
      !leavingByDirective(m) && !trainErrorDirected && siblingTraining &&
      (droppedOut || neverStarted)
    when(fellOut(m)) { failedReg(m) := true.B }
  }

  // Modules the restore below takes back: dropped by a resolution, or failed.
  private val restoring = VecInit((0 until n).map { m =>
    linkInReset && (resolveState === MmplResolveState.idle) &&
    (!moduleEnableReg(m) || failedReg(m))
  })
  /* The retry episode of a Module the restore is not taking back. If the Link
     failed as a whole and is retrying, that is the Link's episode, and the
     restored Modules take it up (see MmplModuleCtrlIO.restartEpisode); after a
     training the Link finished without them it is none. */
  private val linkEpisode = PriorityMux(
    (0 until n).map { m =>
      (io.moduleConnected(m) && !restoring(m) &&
        io.modules(m).status.trainingEpisode.active) ->
        io.modules(m).status.trainingEpisode
    } :+ (true.B -> 0.U.asTypeOf(new TrainingEpisode()))
  )
  when(linkInReset && resolveState === MmplResolveState.idle) {
    moduleEnableReg.foreach(_ := true.B)
    failedReg.foreach(_ := false.B)
    clearBeatState := true.B
  }

  /* Leaving ACTIVE strands whatever the alignment queues were holding: a Module
     that hit a framing error stops contributing while its siblings are still
     delivering, so their slices sit there with nothing to pair against. Nothing
     drains them, and on the next Active period they would re-emerge as slices
     from k beats ago and offset that Module's contribution to every later word.
     The Link does pass back through MBTRAIN.LINKSPEED on the way to ACTIVE, and
     that resolution flushes -- but only at the far end of the retrain, so this
     catches it at the moment the staleness is created, which is also what keeps
     pl_valid from completing a word out of a mix of the two periods.

     Only leaving Active for good counts. Active to Active.PMNAK is not leaving
     it: traffic keeps flowing, and flushing there truncated the transmit word
     in flight and dropped the receive slices waiting on a skewed sibling, which
     left every later word assembled from pieces of two. */
  when(wasUp && !aggUp) {
    clearBeatState := true.B
  }

  // Spec 4.5.3.7: one Retrain encoding for the whole Link. Table 4-12 resolves a
  // conflict in favour of the more drastic action, so the same priority applies
  // across Modules.
  private val retrainEncodings =
    (0 until n).map(io.modules(_).status.retrainEncoding)
  private val commonRetrain = WireDefault(RetrainEncoding.TXSELFCAL)
  when(anyEnabled(m => retrainEncodings(m) === RetrainEncoding.SPEEDIDLE)) {
    commonRetrain := RetrainEncoding.SPEEDIDLE
  }.elsewhen(anyEnabled(m => retrainEncodings(m) === RetrainEncoding.REPAIR)) {
    commonRetrain := RetrainEncoding.REPAIR
  }

  /* The aggregate above tracks live per-Module state -- busy bits and spare
     exhaustion -- but a Module puts its encoding on the wire in
     {PHYRETRAIN.retrain start req} and then resolves the remote's reply against
     it. Modules enter PHYRETRAIN staggered, so letting the value keep moving
     lets one Module transmit one encoding and resolve with another, and the two
     die exit PHYRETRAIN to different states. Sample it once, as the first
     Module enters, and hold it until every Module has had its turn.

     "Every Module", not "while one is in there": on the PHY retrain from
     LINKSPEED the Modules enter one by one, and the first one out resets its
     Lanes on the way to REPAIR or SPEEDIDLE, which changes the very spare
     count the aggregate is built from. A sibling entering even a cycle after
     it left would sample that and resolve differently -- one Module at a new
     speed, another at a new width. A Module that is not going to take part
     (dropped, failed, in RESET or TRAINERROR, or still in LINKINIT or ACTIVE,
     which an RDI Retrain leaves all together) does not hold it. */
  private val inPhyRetrain =
    (0 until n).map(m => ltStates(m) === LTState.sPHYRETRAIN)
  private val anyInPhyRetrain = anyEnabled(inPhyRetrain(_))
  private val commonRetrainHeld = RegInit(RetrainEncoding.TXSELFCAL)
  private val retrainEpoch = RegInit(false.B)
  private val retrainJoined = RegInit(VecInit(Seq.fill(n)(false.B)))
  private val retrainSettled = allEnabled { m =>
    val notTakingPart = (ltStates(m) === LTState.sRESET) ||
      (ltStates(m) === LTState.sTRAINERROR) ||
      (ltStates(m) === LTState.sLINKINIT) ||
      (ltStates(m) === LTState.sACTIVE)
    !operational(m) ||
    (retrainJoined(m) && !inPhyRetrain(m)) ||
    (!retrainJoined(m) && notTakingPart)
  }
  when(retrainEpoch) {
    for (m <- 0 until n) {
      when(inPhyRetrain(m)) { retrainJoined(m) := true.B }
    }
    when(retrainSettled) { retrainEpoch := false.B }
  }.elsewhen(anyInPhyRetrain) {
    retrainEpoch := true.B
    retrainJoined := VecInit(inPhyRetrain)
  }.otherwise {
    commonRetrainHeld := commonRetrain
  }

  /* Spec 4.5.3.4.12 separates the single-module handshake (Steps 3, 4 and 7)
     from the multi-module one (Step 5), and a die only ever runs the one its
     partner runs. A Link with a single connected Module faces a single-module
     die -- Table 5-28's x2 and x4 to x1 pairings, or a UCIe-S x8 port on Module
     0 (spec 5.7.3.3) -- which answers {exit to repair req} with
     {exit to repair resp} and never waits on a resolution, so that Module has
     to run the single-module flow too. */
  private val linkIsMultiModule =
    params.isMultiModule.B && (PopCount(io.moduleConnected) > 1.U)

  for (m <- 0 until n) {
    val ctrl = io.modules(m).ctrl
    ctrl.multiModule := linkIsMultiModule
    // Spec 3.5: the state machine hosted above is the Link's, at two Modules
    // or four. A one-module Link keeps its own.
    ctrl.rdiHosted := params.isMultiModule.B
    /* Withdrawn from a Module once it has acted, so that one which finishes its
       exchange and comes back round to LINKSPEED before a slow sibling has left
       does not find last pass's answer still on offer. */
    ctrl.resolution.valid :=
      (resolveState === MmplResolveState.directing) && !directedAck(m)
    ctrl.resolution.bits := directed(m)
    ctrl.commonRetrainEncoding.valid := params.isMultiModule.B
    ctrl.commonRetrainEncoding.bits := commonRetrainHeld
    // Holds a Module the Link has dropped, one that failed, or one with no
    // partner in RESET until the whole Link retrains from RESET and
    // `linkInReset` restores it. One the directive now being applied is
    // disabling counts already: its way out through TRAINERROR is not the
    // Link failing, so it must not escalate to LinkError.
    ctrl.moduleDisabled := !moduleEnable(m) || failedReg(m) ||
      leavingByDirective(m)
    ctrl.restart := restoring(m)
    ctrl.restartEpisode := linkEpisode
    // A sibling starting to train takes this Module out of RESET with it.
    ctrl.joinResetExit := linkIsMultiModule &&
      (0 until n)
        .filter(_ != m)
        .map(k => operational(k) && (ltStates(k) === LTState.sSBINIT))
        .foldLeft(false.B)(_ || _)

    leavingByDirective(m) := (resolveState === MmplResolveState.directing) &&
      (directedLink =/= MmplResolution.trainError) && !directedEnable(m)
  }

  io.status.moduleEnable := moduleEnable
  io.status.linkResolution := directedLink
  io.status.resolutionApplied := resolveState === MmplResolveState.directing

  // ==========================================================================
  // Assertions
  // ==========================================================================
  block(Verification) {
    block(Verification.Assert) {
      assert(
        aggWidthValid || !aggUp,
        "FATAL: MMPL aggregated a Link width that is not a valid pl_lnk_cfg encoding"
      )
      /* Spec 4.7 permits one-, two- and four-Module Links, and spec 5.7.3.4.1
         rule 1 says a degraded Link "shall not be three modules". MmplByteMap
         enumerates only those counts, so at any other count `beatsPerWord` and
         `comboSelect` both fall through to their defaults and the Modules
         scatter overlapping byte ranges. The resolver already checks its own
         output, but `moduleEnable` also follows io.moduleConnected, which can
         reach three without a resolution ever running -- so the check belongs
         on the aggregate, here. */
      assert(
        !someModuleEnabled ||
          MmplByteMap
            .permittedActiveCounts(n)
            .map(numActive === _.U)
            .reduce(_ || _),
        "FATAL: MMPL active Module count is not a permitted configuration (spec 4.7)"
      )
      // Spec 4.7.1: every Module of a multi-module Link runs at one speed.
      (0 until n).foreach { m =>
        assert(
          !aggUp || !moduleEnable(m) ||
            io.modules(m).rdi.plSpeedmode === io.rdi.plSpeedmode,
          "FATAL: MMPL Modules disagree on the Link speed while Active"
        )
        assert(
          !aggUp || !moduleEnable(m) ||
            io.modules(m).status.linkWidth === moduleWidth,
          "FATAL: MMPL Modules disagree on the Link width while Active"
        )
        // The receive gather reads one Lane code for every Module too.
        assert(
          !aggUp || !moduleEnable(m) ||
            MmplByteMap.activeLanes(
              io.modules(m).status.remoteTxFunctionalLanes,
              negotiatedBy8
            ) === MmplByteMap.activeLanes(rxLaneCode, negotiatedBy8),
          "FATAL: MMPL Modules disagree on the receive width while Active"
        )
      }
      // Spec 4.7: each Module of a multi-module Link has a dedicated Module ID,
      // and the transmit byte map ranks by the remote one. Two operational
      // Modules reporting the same remote ID would share a rank and leave part
      // of every aggregate word untransmitted, silently.
      for (i <- 0 until n; j <- (i + 1) until n) {
        assert(
          !aggUp || !moduleEnable(i) || !moduleEnable(j) ||
            remoteId(i) =/= remoteId(j),
          "FATAL: MMPL Modules report the same remote Module ID while Active"
        )
      }
      // A gathered beat must never be dropped for want of alignment room.
      (0 until n).foreach { m =>
        assert(
          rxSlices(m).io.enq.ready || !rxSlices(m).io.enq.valid,
          "FATAL: MMPL dropped a received mainband slice"
        )
      }
    }
    block(Verification.Cover) {
      cover(txFire && !txLastBeat)
      cover(rxFire && !rxLastBeat)
      cover(resolveState === MmplResolveState.directing)
      cover(!moduleEnable.reduce(_ && _))
    }
  }
}
