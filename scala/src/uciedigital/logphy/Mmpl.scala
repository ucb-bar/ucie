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
  private val moduleEnable = RegInit(VecInit(Seq.fill(n)(true.B)))
  private val ltStates = (0 until n).map(io.modules(_).status.ltState)
  private val linkInReset =
    ltStates.map(_ === LTState.sRESET).reduce(_ && _)

  private def overEnabled(pred: Int => Bool): Seq[Bool] =
    (0 until n).map(m => moduleEnable(m) && pred(m))
  private def anyEnabled(pred: Int => Bool): Bool =
    overEnabled(pred).reduce(_ || _)
  private def allEnabled(pred: Int => Bool): Bool =
    (0 until n).map(m => !moduleEnable(m) || pred(m)).reduce(_ && _)

  private val someModuleEnabled = moduleEnable.reduce(_ || _)
  private val numActive = PopCount(moduleEnable)

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

  swizzle.io.ctrl.numActive := numActive
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
  // Modules can deliver their slice a cycle or two apart, so hold each one until
  // every enabled Module has a slice for this beat.
  private val rxSlices = Seq.tabulate(n) { m =>
    val q = Module(new Queue(UInt(moduleBits.W), 2, pipe = true, flow = true))
    q.suggestName(s"rxSliceQueue_$m")
    q.io.enq.valid := io.modules(m).rdi.plValid && moduleEnable(m)
    q.io.enq.bits := io.modules(m).rdi.plData
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

  // A resolution can change the Module count, and with it how many beats an
  // aggregate word takes. Driven where moduleEnable is written, so a partial
  // word is dropped on the same edge the count changes and cannot be finished
  // to a shape it was not built for. Placed after the beat updates above so it
  // wins over them.
  private val clearBeatState = WireDefault(false.B)
  when(clearBeatState) {
    txBeat := 0.U
    txHold := false.B
    rxBeat := 0.U
    rxAccum := 0.U
  }

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
      ctrl.io.cfgSidebandActive := anyEnabled(m => hosts(m).cfgSidebandActive)
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

      // "A packet sent on a given Module ID could be received on a different
      // Module ID on the sideband Receiver", so take the response from whichever
      // Module it landed on. Only one Module carries these at a time, so a
      // priority pick is enough and the grant returns ready to that Module only.
      val rdiRxGrant = PriorityEncoderOH(
        (0 until n).map(m => moduleEnable(m) && hosts(m).sbLaneIo.rx.valid)
      )
      ctrl.io.sbLaneIo.rx.valid :=
        anyEnabled(m => hosts(m).sbLaneIo.rx.valid)
      ctrl.io.sbLaneIo.rx.bits :=
        Mux1H(rdiRxGrant, (0 until n).map(hosts(_).sbLaneIo.rx.bits))
      for (m <- 0 until n) {
        hosts(m).sbLaneIo.rx.ready :=
          ctrl.io.sbLaneIo.rx.ready && rdiRxGrant(m)
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
    mod.lpIrdy := txFeeding && moduleEnable(m)
    mod.lpValid := txFeeding && moduleEnable(m)
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

    // Transmit on the numerically least Module whose LTSM has moved past
    // SBINIT, so the remote side has a trained sideband to receive it on.
    val cfgTxEligible = (0 until n).map { m =>
      moduleEnable(m) && (ltStates(m) =/= LTState.sRESET) &&
      (ltStates(m) =/= LTState.sSBINIT)
    }
    val cfgTxSelect = PriorityEncoderOH(cfgTxEligible)
    val cfgTxAny = cfgTxEligible.reduce(_ || _)

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

      io.modules(m).rdi.lpCfg := Mux(cfgTxSelect(m), io.rdi.lpCfg, 0.U)
      io.modules(m).rdi.lpCfgVld := cfgTxSelect(m) && io.rdi.lpCfgVld
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

    // Credits the PHY owes upward come from whichever Module received the
    // packet, and only the transmitting Module is owed credits back. Track the
    // order packets were forwarded in so the Adapter's returns land on the right
    // Module.
    val cfgCrdOrder = Module(new Queue(UInt(log2Ceil(n).W), 2 * n))
    cfgCrdOrder.suggestName("cfgCreditOrderQueue")
    cfgCrdOrder.io.enq.valid := cfgPacketDone
    cfgCrdOrder.io.enq.bits := cfgGrant
    cfgCrdOrder.io.deq.ready := io.rdi.lpCfgCrd

    for (m <- 0 until n) {
      io.modules(m).rdi.lpCfgCrd := io.rdi.lpCfgCrd &&
        cfgCrdOrder.io.deq.valid && (cfgCrdOrder.io.deq.bits === m.U)
    }
    io.rdi.plCfgCrd := anyEnabled(io.modules(_).rdi.plCfgCrd)

    block(Verification) {
      block(Verification.Assert) {
        assert(
          !cfgTxAny || PopCount(cfgTxSelect) === 1.U,
          "FATAL: MMPL selected more than one Module for the cfg transmit path"
        )
        (0 until n).foreach { m =>
          assert(
            rxCfg(m).io.enq.ready || !rxCfg(m).io.enq.valid,
            "FATAL: MMPL dropped a received sideband cfg chunk"
          )
        }
        assert(
          cfgCrdOrder.io.enq.ready || !cfgCrdOrder.io.enq.valid,
          "FATAL: MMPL lost track of which Module owns a sideband cfg credit"
        )
      }
      block(Verification.Cover) {
        cover(cfgGrantValid && cfgGrant =/= 0.U)
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
        moduleEnable := directedEnable
        clearBeatState := true.B
        resolveState := MmplResolveState.idle
      }
    }
  }

  when(linkInReset && resolveState === MmplResolveState.idle) {
    moduleEnable.foreach(_ := true.B)
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

  for (m <- 0 until n) {
    val ctrl = io.modules(m).ctrl
    ctrl.multiModule := params.isMultiModule.B
    ctrl.resolution.valid := resolveState === MmplResolveState.directing
    ctrl.resolution.bits := directed(m)
    ctrl.commonRetrainEncoding.valid := params.isMultiModule.B
    ctrl.commonRetrainEncoding.bits := commonRetrain
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
