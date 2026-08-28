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

class Mmpl(
    params: MmplParams = MmplParams(),
    rdiParams: RdiParams = RdiParams(64, 32),
    sbParams: SidebandParams = new SidebandParams()
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

  // Set by the sideband cfg path below when the MMPL itself has a packet or a
  // credit in flight on the Adapter's bus; read by the hosted RDI state machine
  // above it, so it has to be declared before either.
  private val cfgSidebandBusy = WireDefault(false.B)

  /** Reads a per-Module signal from the numerically least operational Module.
    * Every Module of a multi-module Link runs at the same width and speed (spec
    * 4.7.1), so any operational Module speaks for the Link -- but a disabled
    * one does not, because its status stops tracking the Link.
    */
  private def fromLeastEnabled[T <: Data](select: Int => T): T =
    PriorityMux(
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
  private val txFeeding =
    txHold || (io.rdi.lpValid && io.rdi.lpIrdy && someModuleEnabled)
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
  io.rdi.plTrdy := allModulesTrdy && !txHold && someModuleEnabled

  // ==========================================================================
  // Receive
  // ==========================================================================
  // Modules can deliver their slice several cycles apart, so hold each one until
  // every enabled Module has a slice for this beat. Spec 4.7.1.2 warns that
  // Modules of a Link can be staggered, and pl_valid has no backpressure
  // (spec 10.1.4), so the depth here is the whole skew budget.
  private val clearBeatState = WireDefault(false.B)

  private val rxSlices = Seq.tabulate(n) { m =>
    val q = Module(
      new Queue(
        UInt(moduleBits.W),
        params.rxAlignDepth,
        pipe = true,
        flow = true,
        hasFlush = true
      )
    )
    q.suggestName(s"rxSliceQueue_$m")
    q.io.enq.valid := io.modules(m).rdi.plValid && moduleEnable(m)
    q.io.enq.bits := io.modules(m).rdi.plData
    /* Modules can deliver unequal numbers of beats -- one suppresses pl_valid
       after a framing error, they leave ACTIVE on different cycles, or one is
       disabled mid-stream -- and anything left behind would other-wise sit in
       the queue and re-emerge as a slice from k beats ago, silently offsetting
       that Module's contribution to every later word. Discard the partial word
       instead, on the same edge the Module set changes. */
    q.io.flush.foreach(_ := clearBeatState)
    q
  }

  private val rxAligned = allEnabled(rxSlices(_).io.deq.valid)
  private val rxFire = rxAligned && someModuleEnabled

  private val rxBeat = RegInit(0.U(rankW.W))
  private val rxAccum = RegInit(0.U(totalBits.W))
  private val rxLastBeat = rxBeat === (beatsPerWord - 1.U)

  for (m <- 0 until n) {
    rxSlices(m).io.deq.ready := rxFire && moduleEnable(m)
    swizzle.io.rx.moduleData(m) := rxSlices(m).io.deq.bits
  }
  swizzle.io.ctrl.rxBeat := rxBeat

  // Beats occupy disjoint aggregate bytes, so accumulating is an OR.
  private val rxGathered =
    swizzle.io.rx.plData | Mux(rxBeat === 0.U, 0.U, rxAccum)

  when(rxFire) {
    when(rxLastBeat) {
      rxBeat := 0.U
      rxAccum := 0.U
    }.otherwise {
      rxBeat := rxBeat + 1.U
      rxAccum := rxGathered
    }
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
    Option.when(params.isMultiModule)(Module(new RDIController(sbParams)))

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

      // The Link is only ready to bring RDI up once every operational Module is,
      // and any one Module failing or needing clocks speaks for the Link.
      ctrl.io.doRdiBringup :=
        someModuleEnabled && allEnabled(m => hosts(m).doRdiBringup)
      ctrl.io.trainingTimeout := anyEnabled(m => hosts(m).trainingTimeout)
      ctrl.io.validFramingError := anyEnabled(m => hosts(m).validFramingError)
      ctrl.io.cfgSidebandActive :=
        anyEnabled(m => hosts(m).cfgSidebandActive) || cfgSidebandBusy
      ctrl.io.plPhyInRecenter := anyEnabled(m => hosts(m).plPhyInRecenter)
      ctrl.io.clocksUngatedAndStable :=
        someModuleEnabled && allEnabled(m => hosts(m).clocksUngatedAndStable)

      // The state machine keys off LINKINIT and ACTIVE to raise inband presence
      // and force bring-up, so it must not see either until every operational
      // Module has got there.
      val ltsmForRdi = WireDefault(LTState.sRESET)
      when(
        someModuleEnabled && allEnabled(m =>
          hosts(m).ltsmState === LTState.sACTIVE
        )
      ) {
        ltsmForRdi := LTState.sACTIVE
      }.elsewhen(
        someModuleEnabled && allEnabled(m =>
          (hosts(m).ltsmState === LTState.sLINKINIT) ||
            (hosts(m).ltsmState === LTState.sACTIVE)
        )
      ) {
        ltsmForRdi := LTState.sLINKINIT
      }.elsewhen(anyEnabled(m => hosts(m).ltsmState === LTState.sTRAINERROR)) {
        ltsmForRdi := LTState.sTRAINERROR
      }.elsewhen(!allEnabled(m => hosts(m).ltsmState === LTState.sRESET)) {
        // Somewhere in the middle of training: not RESET, not up yet.
        ltsmForRdi := LTState.sMBTRAIN
      }
      ctrl.io.ltsmState := ltsmForRdi

      for (m <- 0 until n) {
        hosts(m).plStateSts := ctrl.io.rdi.plStateSts
        hosts(m).doingRdiBringup := ctrl.io.doingRdiBringup
      }

      // Spec 4.7.1.1: {LinkMgmt.RDI.*} is a Table 7-8 message, so it goes out on
      // the sideband of the numerically least Module ID whose LTSM is not in
      // RESET or SBINIT -- the same Module the cfg path uses.
      val rdiSbEligible = (0 until n).map { m =>
        moduleEnable(m) && (ltStates(m) =/= LTState.sRESET) &&
        (ltStates(m) =/= LTState.sSBINIT)
      }
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

  private val aggActive = aggState === RDIState.active

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

  // Each LogicalPhy already withholds its slice unless its own RDI is Active, so
  // a gather can only complete when every operational Module is up.
  io.rdi.plValid := rxFire && rxLastBeat
  io.rdi.plData := Mux(someModuleEnabled, rxGathered, 0.U)
  io.rdi.plStateSts := aggState
  io.rdi.plLnkCfg := aggWidth
  io.rdi.plInbandPres := aggInbandPres
  io.rdi.plStallReq := aggStallReq
  io.rdi.plClkReq := aggClkReq
  io.rdi.plWakeAck := aggWakeAck
  io.rdi.plError := anyEnabled(io.modules(_).rdi.plError)
  io.rdi.plCError := anyEnabled(io.modules(_).rdi.plCError)
  io.rdi.plNfError := anyEnabled(io.modules(_).rdi.plNfError)
  io.rdi.plTrainError := anyEnabled(io.modules(_).rdi.plTrainError)
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

    /* Transmit on the numerically least Module whose LTSM has moved past
       SBINIT, so the remote side has a trained sideband to receive it on.

       That choice moves as Modules train, and spec 7.1.4 requires the phases of
       one packet to go out on consecutive cycles -- on one Module. lp_cfg has
       no ready line, so the Adapter cannot be held off either. Stage whole
       packets here instead: chunks are always accepted, and a packet is only
       started once it is fully resident and a Module has been picked, after
       which the pick is registered for the length of the packet. That also
       covers the window where no Module is eligible at all, which used to drop
       the Adapter's chunks on the floor along with the credit it spent. */
    val cfgTxEligible = (0 until n).map { m =>
      moduleEnable(m) && (ltStates(m) =/= LTState.sRESET) &&
      (ltStates(m) =/= LTState.sSBINIT)
    }
    val cfgTxAny = cfgTxEligible.reduce(_ || _)

    val txCfg = Module(
      new Queue(UInt(rdiParams.ncWidth.W), params.cfgTxDepth * chunksPerPacket)
    )
    txCfg.suggestName("txCfgQueue")
    txCfg.io.enq.valid := io.rdi.lpCfgVld
    txCfg.io.enq.bits := io.rdi.lpCfg

    val txCfgEnqChunks = RegInit(0.U(chunkCtrW.W))
    val txCfgPackets = RegInit(0.U(log2Ceil(params.cfgTxDepth + 1).W))
    val txCfgEnqPacketDone =
      txCfg.io.enq.fire && (txCfgEnqChunks === (chunksPerPacket - 1).U)
    when(txCfg.io.enq.fire) {
      txCfgEnqChunks := Mux(txCfgEnqPacketDone, 0.U, txCfgEnqChunks + 1.U)
    }

    val cfgTxGrantValid = RegInit(false.B)
    val cfgTxGrant = RegInit(VecInit(Seq.fill(n)(false.B)))
    val cfgTxChunks = RegInit(0.U(chunkCtrW.W))

    val cfgTxSending = cfgTxGrantValid && txCfg.io.deq.valid
    val cfgTxPacketDone =
      cfgTxSending && (cfgTxChunks === (chunksPerPacket - 1).U)

    when(!cfgTxGrantValid) {
      when(txCfgPackets =/= 0.U && cfgTxAny) {
        cfgTxGrantValid := true.B
        cfgTxGrant := VecInit(PriorityEncoderOH(cfgTxEligible))
        cfgTxChunks := 0.U
      }
    }.elsewhen(cfgTxSending) {
      cfgTxChunks := Mux(cfgTxPacketDone, 0.U, cfgTxChunks + 1.U)
      when(cfgTxPacketDone) { cfgTxGrantValid := false.B }
    }

    txCfgPackets := txCfgPackets + txCfgEnqPacketDone.asUInt -
      cfgTxPacketDone.asUInt
    txCfg.io.deq.ready := cfgTxSending

    // Receive: a packet sent on one Module ID can arrive on a different one, so
    // hold every Module's chunks and forward one whole packet at a time.
    val rxCfg = Seq.tabulate(n) { m =>
      val q = Module(
        new Queue(UInt(rdiParams.ncWidth.W), 2 * chunksPerPacket)
      )
      q.suggestName(s"rxCfgQueue_$m")
      q.io.enq.valid := io.modules(m).rdi.plCfgVld
      q.io.enq.bits := io.modules(m).rdi.plCfg
      q
    }

    val rxCfgEnqChunks = Seq.tabulate(n)(m =>
      RegInit(0.U(chunkCtrW.W)).suggestName(s"rxCfgEnqChunks_$m")
    )
    val rxCfgPackets = Seq.tabulate(n)(m =>
      RegInit(0.U(chunkCtrW.W)).suggestName(s"rxCfgPackets_$m")
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
       can be outstanding and uncredited at once. */
    val cfgCrdOrder =
      Module(new Queue(UInt(log2Ceil(n).W), sbParams.maxCrd))
    cfgCrdOrder.suggestName("cfgCreditOrderQueue")
    cfgCrdOrder.io.enq.valid := cfgPacketDone
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
    val cfgCrdPendingW = log2Ceil(n * sbParams.maxCrd + 1)
    val cfgCrdPending = RegInit(0.U(cfgCrdPendingW.W))
    val cfgCrdArriving =
      PopCount((0 until n).map(io.modules(_).rdi.plCfgCrd))
    val cfgCrdEmit = (cfgCrdPending +& cfgCrdArriving) =/= 0.U
    cfgCrdPending := cfgCrdPending +& cfgCrdArriving - cfgCrdEmit.asUInt
    io.rdi.plCfgCrd := cfgCrdEmit

    /* Spec 10.1.3.2 rule 9: the Physical Layer must hold pl_clk_req across
       pl_cfg transitions, and Table 10-1 puts credit returns under the same
       rule. A Module's own bus activity no longer covers that, because the
       staging queues here decouple it from the aggregate bus, so the MMPL adds
       its own in-flight state to the request. */
    cfgSidebandBusy :=
      cfgGrantValid || cfgTxGrantValid || (cfgCrdPending =/= 0.U) ||
        (txCfgPackets =/= 0.U) ||
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
     one common width. The test is relative, so it belongs here rather than in a
     Module: measuring against full width instead would mean every Module of an
     already-degraded Link reports a width degrade for ever, and the Link would
     be sent back to MBTRAIN.REPAIR on every pass rather than proceeding to
     Step 6. */
  private val moduleActiveLanes = (0 until n).map { m =>
    MmplByteMap.activeLanes(
      io.modules(m).status.localTxFunctionalLanes,
      negotiatedBy8
    )
  }
  private val widestActiveLanes = (0 until n)
    .map(m => Mux(moduleEnable(m), moduleActiveLanes(m), 0.U))
    .reduce((a, b) => Mux(a > b, a, b))
  for (m <- 0 until n) {
    resolver.io.narrowerThanPeers(m) :=
      moduleEnable(m) && (moduleActiveLanes(m) < widestActiveLanes)
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

  switch(resolveState) {
    is(MmplResolveState.idle) {
      when(params.isMultiModule.B && resolver.io.resolved && reportsPending) {
        directed := resolver.io.moduleResolution
        directedEnable := resolver.io.nextEnable
        directedLink := resolver.io.linkResolution
        resolveState := MmplResolveState.directing
      }
    }
    is(MmplResolveState.directing) {
      // Hold the directive until every Module has acted on it and left
      // MBTRAIN.LINKSPEED, then shrink the operational set.
      when(!reportsPending) {
        moduleEnableReg := directedEnable
        clearBeatState := true.B
        resolveState := MmplResolveState.idle
      }
    }
  }

  when(linkInReset && resolveState === MmplResolveState.idle) {
    moduleEnableReg.foreach(_ := true.B)
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
     Module enters, and hold it until the last one leaves. */
  private val anyInPhyRetrain =
    anyEnabled(m => ltStates(m) === LTState.sPHYRETRAIN)
  private val commonRetrainHeld = RegInit(RetrainEncoding.TXSELFCAL)
  when(!anyInPhyRetrain) {
    commonRetrainHeld := commonRetrain
  }

  for (m <- 0 until n) {
    val ctrl = io.modules(m).ctrl
    ctrl.multiModule := params.isMultiModule.B
    ctrl.resolution.valid := resolveState === MmplResolveState.directing
    ctrl.resolution.bits := directed(m)
    ctrl.commonRetrainEncoding.valid := params.isMultiModule.B
    ctrl.commonRetrainEncoding.bits := commonRetrainHeld
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
        aggWidthValid || !aggActive,
        "FATAL: MMPL aggregated a Link width that is not a valid pl_lnk_cfg encoding"
      )
      // Spec 4.7.1: every Module of a multi-module Link runs at one speed.
      (0 until n).foreach { m =>
        assert(
          !aggActive || !moduleEnable(m) ||
            io.modules(m).rdi.plSpeedmode === io.rdi.plSpeedmode,
          "FATAL: MMPL Modules disagree on the Link speed while Active"
        )
        assert(
          !aggActive || !moduleEnable(m) ||
            io.modules(m).status.linkWidth === moduleWidth,
          "FATAL: MMPL Modules disagree on the Link width while Active"
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
