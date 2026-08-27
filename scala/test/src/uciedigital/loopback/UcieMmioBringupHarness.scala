package edu.berkeley.cs.uciedigital.loopback

import chisel3._
import chisel3.util.experimental.BoringUtils
import edu.berkeley.cs.chippy.{TLTester, TLTesterIO, TLTesterParams}
import edu.berkeley.cs.uciedigital.d2dadapter.LinkInitState
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.logphy.{LTSMState, LTState}
import edu.berkeley.cs.uciedigital.top.{UcieDigitalTop, UcieDigitalTopParams}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

/** Bit positions in the packed observation word.
  *
  * One wide UInt rather than one port per signal, because registering many
  * scopes makes the generated Verilator model fault at time zero.
  */
object MmioFlag {
  val rdiPlWakeAck = 0
  val rdiInbandPres = 1
  val rdiLpReqActive = 2
  val fdiInbandPres = 3
  val fdiProtocolVld = 4
  val fdiLpReqActive = 5
  val fdiRxActiveReq = 6
  val fdiRxActiveSts = 7
  val negotiatedProto = 8
  val chipTxReady = 9
  val phyTrainError = 10
  val phyTimedout = 11
  val phyRecenter = 12
  val rdiPlError = 13
  val rxOverflow = 14
  // What the register holds, as the protocol layer sees it.
  val protoReqActive = 15
  val chipRxValid = 16
  val fdiPlValid = 17
  val fdiLpValid = 18
  val fdiPlTrdy = 19
  val fdiStallReq = 20
  val protoStalled = 21
  val sbParityErr = 22
  val sbRxQueuesFull = 23
  val sbDeserTimedout = 24
  val sbBadRouteUpper = 25
  val sbBadRouteCurr = 26
  val sbBadRouteLower = 27
  val sbUnhandledMsg = 28

  val width = 64

  val sbFaults: Seq[(Int, String)] = Seq(
    sbParityErr -> "parity",
    sbRxQueuesFull -> "rxQueuesFull",
    sbDeserTimedout -> "deserTimeout",
    sbBadRouteUpper -> "badRouteUpper",
    sbBadRouteCurr -> "badRouteCurr",
    sbBadRouteLower -> "badRouteLower",
    sbUnhandledMsg -> "unhandledMsg"
  )
}

object UcieMmioBringupHarness {
  // UcieRegTop's node is 4 bytes wide, and a mismatched TLTester width would
  // read back shifted data rather than fail.
  val beatBytes = 4
  val tlParams = TLTesterParams(addrWidth = 32, dataWidth = 32)

  def topParams(): UcieDigitalTopParams = {
    val d = UcieDigitalTopParams.default()
    d.copy(regs =
      d.regs.copy(
        baseAddress = 0,
        includeRegNode = true,
        includeInterruptNode = false
      )
    )
  }
}

/** Two UcieDigitalTop instances cross-wired at the analog boundary, driven only
  * through their register blocks.
  *
  * The sibling harness, UcieDigitalLoopbackHarness, builds the three layers by
  * hand and drives their control ports from testbench pins. This one drives one
  * TileLink master per die and nothing else, so a stage that passes here is a
  * stage software can reach.
  *
  * Observation is by BoringUtils tap rather than by new ports, so the shipping
  * top gains nothing for the sake of the test.
  *
  * @param exposeDataPath
  *   exposes the chip-facing data ports. With them tied off the simulator folds
  *   away the beat packing, which every stage pays for across the reset wait.
  */
class UcieMmioBringupHarness(val exposeDataPath: Boolean = false)(implicit
    p: Parameters
) extends LazyModule {
  val tops =
    Seq.fill(2)(
      LazyModule(new UcieDigitalTop(UcieMmioBringupHarness.topParams()))
    )
  val testers = Seq.fill(2)(
    LazyModule(
      new TLTester(
        UcieMmioBringupHarness.tlParams,
        UcieMmioBringupHarness.beatBytes
      )
    )
  )
  (tops zip testers).foreach { case (top, tester) =>
    top.regNode.get := tester.node
  }

  override lazy val module = new UcieMmioBringupHarnessImp(this)
}

class UcieMmioBringupHarnessImp(outer: UcieMmioBringupHarness)
    extends LazyModuleImp(outer) {
  private val tp = UcieMmioBringupHarness.topParams()

  // The byte-exact stages assume one FDI word is one mainband beat. Nothing in
  // UcieDigitalTopParams relates the two, so state it here.
  require(
    tp.protocol.fdi.nBytes * 8 ==
      tp.logPhy.afe.mbLanes * tp.logPhy.afe.mbSerializerRatio,
    s"one FDI word must be one mainband beat: ${tp.protocol.fdi.nBytes * 8} bits vs " +
      s"${tp.logPhy.afe.mbLanes} lanes x ${tp.logPhy.afe.mbSerializerRatio}"
  )

  val beatBits: Int = tp.protocol.fdi.nBytes * 8
  private val exposeDataPath = outer.exposeDataPath

  val io = IO(new Bundle {
    // The only drive surface: one TileLink master per die.
    val reg = Vec(2, new TLTesterIO(UcieMmioBringupHarness.tlParams))

    val ltState = Output(Vec(2, LTState()))
    val ltsmState = Output(Vec(2, LTSMState()))
    val rdiState = Output(Vec(2, RDIState()))
    val fdiState = Output(Vec(2, FDIState()))
    val adapterLinkInit = Output(Vec(2, LinkInitState()))
    val flags = Output(Vec(2, UInt(MmioFlag.width.W)))

    val txValid = Option.when(exposeDataPath)(Input(Vec(2, Bool())))
    val txData = Option.when(exposeDataPath)(Input(Vec(2, UInt(beatBits.W))))
    val rxReady = Option.when(exposeDataPath)(Input(Vec(2, Bool())))
    val rxData = Option.when(exposeDataPath)(Output(Vec(2, UInt(beatBits.W))))
  })

  for (i <- 0 until 2) {
    val me = outer.tops(i).module
    val peer = outer.tops(1 - i).module

    io.reg(i) <> outer.testers(i).module.io

    me.io.phyFacingIo.sidebandLink.in.bits :=
      peer.io.phyFacingIo.sidebandLink.out.bits
    me.io.phyFacingIo.sidebandLink.in.fwClock :=
      peer.io.phyFacingIo.sidebandLink.out.fwClock
    me.io.phyFacingIo.mainbandLink.rx.bits :=
      peer.io.phyFacingIo.mainbandLink.tx.bits
    me.io.phyFacingIo.mainbandLink.rx.valid :=
      peer.io.phyFacingIo.mainbandLink.tx.valid
    me.io.phyFacingIo.mainbandLink.tx.ready :=
      peer.io.phyFacingIo.mainbandLink.rx.ready

    me.io.ctrl.linkReset := false.B
    me.io.ctrl.pwrGood := true.B
    me.io.ctrl.retryTrainingAmt := 0.U

    me.io.chipFacingIo.mainbandTx.valid :=
      io.txValid.map(_(i)).getOrElse(false.B)
    me.io.chipFacingIo.mainbandTx.bits.data :=
      io.txData.map(_(i)).getOrElse(0.U)
    me.io.chipFacingIo.mainbandRx.ready :=
      io.rxReady.map(_(i)).getOrElse(false.B)
    io.rxData.foreach(_(i) := me.io.chipFacingIo.mainbandRx.bits.data)

    io.ltState(i) := BoringUtils.tapAndRead(me.logicalPhy.io.status.ltState)
    io.ltsmState(i) :=
      BoringUtils.tapAndRead(me.logicalPhy.io.status.currentState)
    io.rdiState(i) := BoringUtils.tapAndRead(me.logicalPhy.io.rdi.plStateSts)
    io.fdiState(i) := BoringUtils.tapAndRead(me.d2dAdapter.io.fdi.plStateSts)
    io.adapterLinkInit(i) :=
      BoringUtils.tapAndRead(me.d2dAdapter.linkManager.linkInitStateReg)

    val f = Wire(Vec(MmioFlag.width, Bool()))
    f.foreach(_ := false.B)

    f(MmioFlag.rdiPlWakeAck) :=
      BoringUtils.tapAndRead(me.logicalPhy.io.rdi.plWakeAck)
    f(MmioFlag.rdiInbandPres) :=
      BoringUtils.tapAndRead(me.logicalPhy.io.rdi.plInbandPres)
    f(MmioFlag.rdiLpReqActive) :=
      BoringUtils.tapAndRead(me.d2dAdapter.io.rdi.lpStateReq) ===
        RDIStateReq.active
    f(MmioFlag.fdiInbandPres) :=
      BoringUtils.tapAndRead(me.d2dAdapter.io.fdi.plInbandPres)
    f(MmioFlag.fdiProtocolVld) :=
      BoringUtils.tapAndRead(me.d2dAdapter.io.fdi.plProtocolVld)
    f(MmioFlag.fdiLpReqActive) :=
      BoringUtils.tapAndRead(me.protocolLayer.io.fdi.lpStateReq) ===
        FDIStateReq.active
    f(MmioFlag.fdiRxActiveReq) :=
      BoringUtils.tapAndRead(me.d2dAdapter.io.fdi.plRxActiveReq)
    f(MmioFlag.fdiRxActiveSts) :=
      BoringUtils.tapAndRead(me.protocolLayer.io.fdi.lpRxActiveSts)
    f(MmioFlag.negotiatedProto) :=
      BoringUtils.tapAndRead(me.protocolLayer.io.status.negotiatedProtocolValid)
    f(MmioFlag.chipTxReady) := me.io.chipFacingIo.mainbandTx.ready
    f(MmioFlag.phyTrainError) :=
      BoringUtils.tapAndRead(me.logicalPhy.io.rdi.plTrainError)
    f(MmioFlag.phyTimedout) :=
      BoringUtils.tapAndRead(me.logicalPhy.io.status.trainingTimedout)
    f(MmioFlag.phyRecenter) :=
      BoringUtils.tapAndRead(me.logicalPhy.io.rdi.plPhyInRecenter)
    f(MmioFlag.rdiPlError) :=
      BoringUtils.tapAndRead(me.logicalPhy.io.rdi.plError)
    f(MmioFlag.rxOverflow) :=
      BoringUtils.tapAndRead(me.protocolLayer.io.status.rxOverflow)
    f(MmioFlag.protoReqActive) :=
      BoringUtils.tapAndRead(me.protocolLayer.io.ctrl.requestActive)
    f(MmioFlag.chipRxValid) := me.io.chipFacingIo.mainbandRx.valid
    f(MmioFlag.fdiPlValid) :=
      BoringUtils.tapAndRead(me.d2dAdapter.io.fdi.plValid)
    f(MmioFlag.fdiLpValid) :=
      BoringUtils.tapAndRead(me.protocolLayer.io.fdi.lpValid)
    f(MmioFlag.fdiPlTrdy) := BoringUtils.tapAndRead(me.d2dAdapter.io.fdi.plTrdy)
    f(MmioFlag.fdiStallReq) :=
      BoringUtils.tapAndRead(me.d2dAdapter.io.fdi.plStallReq)
    f(MmioFlag.protoStalled) :=
      BoringUtils.tapAndRead(me.protocolLayer.io.status.stalled)

    val sb = me.logicalPhy.io.status.sideband
    f(MmioFlag.sbParityErr) := BoringUtils.tapAndRead(sb.sbParityErrSeen)
    f(MmioFlag.sbRxQueuesFull) :=
      BoringUtils.tapAndRead(sb.sbRxPriorityQueuesFullSeen)
    f(MmioFlag.sbDeserTimedout) :=
      BoringUtils.tapAndRead(sb.sbDeserializerTimedoutSeen)
    f(MmioFlag.sbBadRouteUpper) :=
      BoringUtils.tapAndRead(sb.sbInvalidRouteUpperSeen)
    f(MmioFlag.sbBadRouteCurr) :=
      BoringUtils.tapAndRead(sb.sbInvalidRouteCurrSeen)
    f(MmioFlag.sbBadRouteLower) :=
      BoringUtils.tapAndRead(sb.sbInvalidRouteLowerSeen)
    f(MmioFlag.sbUnhandledMsg) :=
      BoringUtils.tapAndRead(sb.sbUnhandledCurrentLayerMsgSeen)

    io.flags(i) := f.asUInt
  }
}
