package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._
import chisel3.util.random._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem.{
  BaseSubsystem,
  PBUS,
  SBUS,
  TLBusWrapperLocation
}
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.regmapper.{RegField, RegWriteFn, RegFieldDesc}
import freechips.rocketchip.tilelink._
import edu.berkeley.cs.uciedigital.phy._
import edu.berkeley.cs.uciedigital.top.{UcieDigitalTop, UcieDigitalTopParams}
import edu.berkeley.cs.uciedigital.regs.{UcieRegBlock, UcieRegBlockIO, UcieRegParams, AdapterToRegs, PhyToRegs, LinkToRegs, MailboxSbResp, PhyToVendor}
import edu.berkeley.cs.chippy._
import freechips.rocketchip.diplomacy.{SimpleDevice, AddressSet}
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.lazymodule._
import edu.berkeley.cs.uciedigital.phy.macros.DriverCtlIO
import edu.berkeley.cs.uciedigital.phy.macros.clocking.ClockingTile
import freechips.rocketchip.util.AsyncQueueParams
import freechips.rocketchip.util.AsyncQueue
import freechips.rocketchip.diplomacy.RegionType
import freechips.rocketchip.diplomacy.TransferSizes
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.diplomacy.BundleBridgeSource
import testchipip.soc.{ChipletLinkParams, ChipletLinkWrapperInstantiationLike, ChipletLinkWrapper, OffchipSubsystemParams, ChipletIO}

case class UcieTLParams(
    address: BigInt = 0x200000,
    bufferDepthPerLane: Int = 11,
    numLanes: Int = 16,
    bitCounterWidth: Int = 64,
    creditCounterSize: Int = 128,
    creditRetThreshhold: Int = 31,
    creditRetTimerWidth: Int = 7,
    tlBufferDepth: Int = 63,
    managerWhere: TLBusWrapperLocation = PBUS,
    queueParams: AsyncQueueParams = AsyncQueueParams(depth = 32),
    maxInflight: Int = 1,
    clientIdBits: Int = 8,
    includeDefaultModels: Boolean = false,
    ucieRegsBaseAddress: BigInt = 0x40000
) extends ChipletLinkParams
 with ChipletLinkWrapperInstantiationLike 
 {
  def managerBusWhere = managerWhere
  def controlManagerBusWhere = Some(managerWhere)
  def instantiate(params: OffchipSubsystemParams, id: Int)(implicit p: Parameters): ChipletLinkWrapper = LazyModule(new UcieChipletLink(this, params, id))
  assert(isPow2(creditCounterSize), s"Credit counter size must be a power of 2")
  assert(tlBufferDepth < creditCounterSize / 2, s"TL buffer depth must be less than half of max credits")
 }

case object UcieTLKey extends Field[Option[Seq[UcieTLParams]]](None)

class UcieBumpsIO(numLanes: Int = 16) extends ChipletIO {
  val phy = new PhyBumpsIO(numLanes)
  val debug = new DebugBumpsIO

  def tieoff: Unit = {
    phy.rxData := DontCare
    phy.rxValid := false.B
    phy.rxTrack := false.B
    phy.rxClkP := false.B.asClock
    phy.rxClkN := false.B.asClock
    phy.sbRxClk := false.B.asClock
    phy.sbRxData := DontCare
    phy.bypassClk := false.B.asClock
    phy.digitalBypassClk := false.B.asClock
  }

  // Bypass and reference clocks should be connected at top level
  def connect(io: ChipletIO): Unit = io match {
    case io: UcieBumpsIO => {
      phy.rxData      := io.phy.txData
      phy.rxValid     := io.phy.txValid
      phy.rxTrack     := io.phy.txTrack
      phy.rxClkP      := io.phy.txClkP
      phy.rxClkN      := io.phy.txClkN
      phy.sbRxClk     := io.phy.sbTxClk
      phy.sbRxData    := io.phy.sbTxData

      io.phy.rxData   := phy.txData
      io.phy.rxValid  := phy.txValid
      io.phy.rxTrack  := phy.txTrack
      io.phy.rxClkP   := phy.txClkP
      io.phy.rxClkN   := phy.txClkN
      io.phy.sbRxClk  := phy.sbTxClk
      io.phy.sbRxData := phy.sbTxData
    }
    case _ => assert(false, s"IO does not match UcieBumpsIO: ${io.getClass}")
  }

  def loopback: Unit = {
    // Does this require a delayer?
    phy.rxData := phy.txData
    phy.rxValid := phy.txValid
    phy.rxTrack := phy.txTrack
    phy.rxClkP := phy.txClkP
    phy.rxClkN := phy.txClkN
    phy.sbRxClk := phy.sbTxClk
    phy.sbRxData := phy.sbTxData
  }
}

object MainbandSel extends ChiselEnum {
  // Allow PhyTest to control mainband
  val phytest = Value(0.U(2.W))
  // Send TL packets over mainband
  val tl = Value(1.U(2.W))
  // Route mainband through the UCIe digital controller (UcieDigitalTop)
  val ucie = Value(2.U(2.W))
}

class UcieTLRegsIO(
    bufferDepthPerLane: Int = 11,
    numLanes: Int = 16,
    bitCounterWidth: Int = 64,
    addrWidth: Int = 64 // Magic number, but this is hardcoded in A packet
) extends Bundle {
  val test = Flipped(
    new PhyTestRegsIO(bufferDepthPerLane, numLanes, bitCounterWidth)
  )
  val phy = Flipped(new PhyRegsIO(numLanes))
  val mainbandSel = Output(MainbandSel())
  val creditFlowEnable = Output(Bool())
  val lastSeenTLReq = Input(UInt(addrWidth.W))
}

class UcieTLRegs(params: UcieTLParams, beatBytes: Int, ucieRegParams: UcieRegParams)(implicit
    p: Parameters
) extends ClockSinkDomain(ClockSinkParameters()) {
  def toRegFieldRw[T <: Data](r: T, name: String): RegField = {
    RegField(
      r.getWidth,
      r.asUInt,
      RegWriteFn((valid, data) => {
        when(valid) {
          r := data.asTypeOf(r)
        }
        true.B
      }),
      Some(RegFieldDesc(name, ""))
    )
  }
  def toRegFieldR[T <: Data](r: T, name: String): RegField = {
    RegField.r(r.getWidth, r.asUInt, RegFieldDesc(name, ""))
  }
  val ucieTLRegionSize = 0x4000
  val device = new SimpleDevice("ucie_control", Seq("ucbbar,ucie"))
  val node = TLRegisterNode(
    Seq(AddressSet(params.address, ucieTLRegionSize + ucieRegParams.allocation.regionSize - 1)),
    device,
    "reg/control",
    beatBytes = beatBytes
  )

  override lazy val module = new UcieTLRegsImpl
  class UcieTLRegsImpl extends Impl {
    val io = IO(
      new UcieTLRegsIO(
        params.bufferDepthPerLane,
        params.numLanes,
        params.bitCounterWidth
      )
    )
    
    val ucieBlockIo = IO(new UcieRegBlockIO(ucieRegParams))

    val regmap = withClockAndReset(clock, reset) {
      // TODO: Remove and add necessary registers
      io.test := DontCare
      // MMIO registers.
      val testTarget = RegInit(TestTarget.mainband)
      val divResetb = RegInit(false.B.asAsyncReset)
      val txTestMode = RegInit(TxTestMode.manual)
      val txDataMode = RegInit(DataMode.finite)
      val txLfsrSeed = RegInit(
        VecInit(
          Seq.fill(params.numLanes + 1)(
            1.U(io.test.txLfsrSeed(0).getWidth.W)
          )
        )
      )
      val txFsmRst = Wire(DecoupledIO(UInt(1.W)))
      val txExecute = Wire(DecoupledIO(UInt(1.W)))
      val txWriteChunk = Wire(DecoupledIO(UInt(1.W)))
      val txManualRepeatPeriod =
        RegInit(0.U(io.test.txManualRepeatPeriod.getWidth.W))
      val txPacketsToSend =
        RegInit(0.U(io.test.txPacketsToSend.getWidth.W))
      val txClkP = RegInit(0.U(32.W))
      val txClkN = RegInit(0.U(32.W))
      val txValid = RegInit(0.U(32.W))
      val txTrack = RegInit(0.U(32.W))
      val txDataLaneGroup =
        RegInit(0.U(io.test.txDataLaneGroup.getWidth.W))
      val txDataOffset = RegInit(0.U(io.test.txDataOffset.getWidth.W))
      val txDataChunkIn0 = RegInit(0.U(64.W))
      val txDataChunkIn1 = RegInit(0.U(64.W))
      val rxDataMode = RegInit(DataMode.infinite)
      val rxLfsrSeed = RegInit(
        VecInit(
          Seq.fill(params.numLanes + 1)(
            1.U(io.test.rxLfsrSeed(0).getWidth.W)
          )
        )
      )
      val rxLfsrValid = RegInit(0.U(32.W))
      val rxFsmRst = Wire(DecoupledIO(UInt(1.W)))
      val rxPacketsToReceive =
        RegInit(0.U(io.test.rxPacketsToReceive.getWidth.W))
      val rxPauseCounters = RegInit(0.U(1.W))
      val rxDataLane = RegInit(0.U(io.test.rxDataLane.getWidth.W))
      val rxDataOffset = RegInit(0.U(io.test.rxDataOffset.getWidth.W))

      val clkPhaseSel = RegInit(0.U(ClockingTile.phaseSelWidth.W))
      val clkFreqSel = RegInit(0.U(ClockingTile.freqSelWidth.W))
      // TX clock is enabled out of reset so that existing bring-up sequences
      // do not have to turn it on explicitly.
      val clkGateEn = RegInit(true.B)
      // Physical lane carrying the valid signal in each direction. Reset to the
      // dedicated valid lane; see `PhyRegsIO` for the other select codes.
      val txValidLaneSel = RegInit(
        Phy
          .dedicatedValidLaneSel(params.numLanes)
          .U(Phy.validLaneSelWidth(params.numLanes).W)
      )
      val rxValidLaneSel = RegInit(
        Phy
          .dedicatedValidLaneSel(params.numLanes)
          .U(Phy.validLaneSelWidth(params.numLanes).W)
      )
      val txctl = RegInit(VecInit(Seq.fill(params.numLanes + 5)({
        val w = Wire(new TxLaneDigitalCtlIO)
        w.dll_reset := true.B
        w.driver.pu_ctl := 0.U
        w.driver.pd_ctl := 0.U
        w.driver.en := false.B
        w.driver.en_b := true.B
        w.skew.dll_en := false.B
        w.skew.ocl := false.B
        w.skew.delay := 0.U
        w.skew.mux_en := "b00000011".U
        w.skew.band_ctrl := "b01".U
        w.skew.mix_en := 0.U
        w.skew.nen_out := 20.U
        w.skew.pen_out := 22.U
        for (i <- 0 until 32) {
          w.shuffler(i) := i.U(5.W)
        }
        w.sample_negedge := false.B
        w.delay := 0.U
        w
      })))
      val rxctl = RegInit(VecInit(Seq.fill(params.numLanes + 5)({
        val w = Wire(new RxLaneDigitalCtlIO)
        w.zen := false.B
        w.zctl := 0.U
        w.vref_sel := 63.U
        w.afeBypassEn := false.B
        w.afeOpCycles := 16.U
        w.afeOverlapCycles := 2.U
        w.afeBypass.aEn := false.B
        w.afeBypass.aPc := true.B
        w.afeBypass.bEn := false.B
        w.afeBypass.bPc := true.B
        w.afeBypass.selA := false.B
        w.sample_negedge := false.B
        w.delay := 0.U
        w
      })))
      // UCIe common.
      // Test PLL P/N, UCIe PLL P/N, RX CLK P/N
      val commonDriverctl = RegInit(VecInit(Seq.fill(6)({
        val w = Wire(new DriverCtlIO)
        w.pu_ctl := 0.U
        w.pd_ctl := 0.U
        w.en := false.B
        w.en_b := true.B
        w
      })))
      val commonTxctl = RegInit({
        val w = Wire(new TxLaneDigitalCtlIO)
        w.dll_reset := true.B
        w.driver.pu_ctl := 0.U
        w.driver.pd_ctl := 0.U
        w.driver.en := false.B
        w.driver.en_b := true.B
        w.skew.dll_en := false.B
        w.skew.ocl := false.B
        w.skew.delay := 0.U
        w.skew.mux_en := "b00000011".U
        w.skew.band_ctrl := "b01".U
        w.skew.mix_en := 0.U
        w.skew.nen_out := 20.U
        w.skew.pen_out := 22.U
        for (i <- 0 until 32) {
          w.shuffler(i) := i.U(5.W)
        }
        w.sample_negedge := false.B
        w.delay := 0.U
        w
      })

      val commonTxTestMode = RegInit(TxTestMode.manual)
      val commonTxDataMode = RegInit(DataMode.finite)
      val commonTxLfsrSeed = RegInit(1.U(64.W))
      val commonTxFsmRst = Wire(DecoupledIO(UInt(1.W)))
      val commonTxExecute = Wire(DecoupledIO(UInt(1.W)))
      commonTxFsmRst.ready := true.B
      commonTxExecute.ready := true.B
      val commonTxManualRepeatPeriod = RegInit(0.U(6.W))
      val commonTxPacketsToSend = RegInit(0.U(params.bitCounterWidth.W))
      val commonData = RegInit(VecInit(Seq.fill(16)(0.U(64.W))))

      val mainbandSel = RegInit(MainbandSel.phytest)
      io.mainbandSel := mainbandSel

      val creditFlowEnable = RegInit(true.B)
      io.creditFlowEnable := creditFlowEnable

      val lastSeenTLReq = RegInit(0.U)
      lastSeenTLReq := io.lastSeenTLReq

      txFsmRst.ready := true.B
      txExecute.ready := true.B
      txWriteChunk.ready := true.B
      rxFsmRst.ready := true.B

      def applyShift[T <: Data](data: T, cycles: Int = 0): T = {
        if (cycles > 0) {
          ShiftRegister(data, cycles, true.B)
        } else {
          data
        }
      }

      io.test.txDataChunkIn.bits := applyShift(
        Cat(txDataChunkIn1, txDataChunkIn0)
      )
      io.test.txDataChunkIn.valid := applyShift(
        txWriteChunk.valid
      )
      io.test.txDataLaneGroup := applyShift(txDataLaneGroup)
      io.test.txDataOffset := applyShift(txDataOffset)

      io.test.testTarget := applyShift(testTarget)
      io.test.divResetb := applyShift(divResetb)
      io.test.txTestMode := applyShift(txTestMode)
      io.test.txDataMode := applyShift(txDataMode)
      io.test.txLfsrSeed := applyShift(txLfsrSeed)
      io.test.txFsmRst := applyShift(txFsmRst.valid)
      io.test.txExecute := applyShift(txExecute.valid)
      io.test.txManualRepeatPeriod := applyShift(txManualRepeatPeriod)
      io.test.txPacketsToSend := applyShift(txPacketsToSend)
      io.test.txClkP := applyShift(txClkP)
      io.test.txClkN := applyShift(txClkN)
      io.test.txValid := applyShift(txValid)
      io.test.txTrack := applyShift(txTrack)
      io.test.rxDataMode := applyShift(rxDataMode)
      io.test.rxLfsrSeed := applyShift(rxLfsrSeed)
      io.test.rxLfsrValid := applyShift(rxLfsrValid)
      io.test.rxFsmRst := applyShift(rxFsmRst.valid)
      io.test.rxPacketsToReceive := applyShift(rxPacketsToReceive)
      io.test.rxPauseCounters := applyShift(rxPauseCounters)
      io.test.rxDataLane := applyShift(rxDataLane)
      io.test.rxDataOffset := applyShift(rxDataOffset)
      io.phy.clkPhaseSel := applyShift(clkPhaseSel)
      io.phy.clkFreqSel := applyShift(clkFreqSel)
      io.phy.clkGateEn := applyShift(clkGateEn)
      io.phy.txValidLaneSel := applyShift(txValidLaneSel)
      io.phy.rxValidLaneSel := applyShift(rxValidLaneSel)
      io.phy.txctl := applyShift(VecInit(txctl.take(params.numLanes + 4)))
      io.phy.rxctl := applyShift(VecInit(rxctl.take(params.numLanes + 4)))

      // String name should always be camel case with an underscore to separate indices.
      // Adjacent indices should be contiguous in memory. Increasing index should correspond to increasing memory address.
      val mmioRegs = Seq(
        toRegFieldRw(testTarget, "testTarget"),
        toRegFieldRw(divResetb, "divResetb"),
        toRegFieldRw(txTestMode, "txTestMode"),
        toRegFieldRw(txDataMode, "txDataMode")
      ) ++ (0 until params.numLanes + 1).map((i: Int) => {
        toRegFieldRw(txLfsrSeed(i), s"txLfsrSeed_$i")
      }) ++ Seq(
        RegField.w(1, txFsmRst, RegFieldDesc("txFsmRst", "")),
        RegField.w(1, txExecute, RegFieldDesc("txExecute", "")),
        RegField.w(1, txWriteChunk, RegFieldDesc("txWriteChunk", "")),
        toRegFieldR(
          applyShift(io.test.txPacketsSent),
          "txPacketsSent"
        ),
        toRegFieldRw(txManualRepeatPeriod, "txManualRepeatPeriod"),
        toRegFieldRw(txPacketsToSend, "txPacketsToSend"),
        toRegFieldRw(txClkP, "txClkP"),
        toRegFieldRw(txClkN, "txClkN"),
        toRegFieldRw(txTrack, "txTrack"),
        toRegFieldRw(txDataLaneGroup, "txDataLaneGroup"),
        toRegFieldRw(txDataOffset, "txDataOffset"),
        toRegFieldRw(txDataChunkIn0, "txDataChunkIn0"),
        toRegFieldRw(txDataChunkIn1, "txDataChunkIn1"),
        toRegFieldR(
          applyShift(io.test.txDataChunkOut(63, 0)),
          "txDataChunkOut0"
        ),
        toRegFieldR(
          applyShift(io.test.txDataChunkOut(127, 64)),
          "txDataChunkOut1"
        )
      ) ++ Seq(
        toRegFieldR(
          applyShift(io.test.txTestState),
          "txTestState"
        ),
        toRegFieldRw(rxDataMode, s"rxDataMode")
      ) ++ (0 until params.numLanes + 1).map((i: Int) => {
        toRegFieldRw(rxLfsrSeed(i), s"rxLfsrSeed_$i")
      }) ++ (0 until params.numLanes + 2).map((i: Int) => {
        toRegFieldR(
          applyShift(io.test.rxBitErrors(i)),
          s"rxBitErrors_$i"
        )
      }) ++ Seq(
        RegField.w(1, rxFsmRst, RegFieldDesc("rxFsmRst", "")),
        toRegFieldRw(rxPacketsToReceive, "rxPacketsToReceive"),
        toRegFieldRw(rxPauseCounters, "rxPauseCounters"),
        toRegFieldR(
          applyShift(io.test.rxPacketsReceived),
          "rxPacketsReceived"
        ),
        toRegFieldR(
          applyShift(io.test.rxSignature),
          "rxSignature"
        ),
        toRegFieldRw(rxDataLane, "rxDataLane"),
        toRegFieldRw(rxDataOffset, "rxDataOffset"),
        toRegFieldR(
          applyShift(io.test.rxDataChunk),
          "rxDataChunk"
        ),
        toRegFieldRw(clkPhaseSel, "clkPhaseSel"),
        toRegFieldRw(clkFreqSel, "clkFreqSel"),
        toRegFieldRw(clkGateEn, "clkGateEn")
      ) ++ (0 until params.numLanes + 4).flatMap((i: Int) => {
        Seq(
          toRegFieldRw(txctl(i).dll_reset, s"txctl_${i}_dllReset"),
          toRegFieldRw(txctl(i).driver, s"txctl_${i}_driver"),
          toRegFieldRw(txctl(i).skew, s"txctl_${i}_skew")
        ) ++ (0 until 32).map((j: Int) =>
          toRegFieldRw(txctl(i).shuffler(j), s"txctl_${i}_shuffler_$j")
        ) ++ Seq(
          toRegFieldRw(txctl(i).sample_negedge, s"txctl_${i}_sampleNegedge"),
          toRegFieldRw(txctl(i).delay, s"txctl_${i}_delay"),
          toRegFieldR(
            applyShift(io.phy.dllCode(i)),
            s"txctl_${i}_dllCode"
          )
        )
      }) ++ (0 until params.numLanes + 4).flatMap((i: Int) => {
        Seq(
          toRegFieldRw(rxctl(i).zen, s"rxctl_${i}_zen"),
          toRegFieldRw(rxctl(i).zctl, s"rxctl_${i}_zctl"),
          toRegFieldRw(rxctl(i).vref_sel, s"rxctl_${i}_vrefSel"),
          toRegFieldRw(rxctl(i).afeBypassEn, s"rxctl_${i}_afeBypassEn"),
          toRegFieldRw(rxctl(i).afeBypass, s"rxctl_${i}_afeBypass"),
          toRegFieldRw(rxctl(i).afeOpCycles, s"rxctl_${i}_afeOpCycles"),
          toRegFieldRw(
            rxctl(i).afeOverlapCycles,
            s"rxctl_${i}_afeOverlapCycles"
          ),
          toRegFieldRw(rxctl(i).sample_negedge, s"rxctl_${i}_sampleNegedge"),
          toRegFieldRw(rxctl(i).delay, s"rxctl_${i}_rxDelay")
        )
      }) ++ Seq(
        toRegFieldRw(commonTxTestMode, "commonTxTestMode"),
        toRegFieldRw(commonTxDataMode, "commonTxDataMode"),
        toRegFieldRw(commonTxLfsrSeed, s"commonTxLfsrSeed"),
        RegField.w(1, commonTxFsmRst, RegFieldDesc("commonTxFsmRst", "")),
        RegField.w(1, commonTxExecute, RegFieldDesc("commonTxExecute", "")),
        toRegFieldRw(commonTxManualRepeatPeriod, "commonTxManualRepeatPeriod"),
        toRegFieldRw(commonTxPacketsToSend, "commonTxPacketsToSend")
      ) ++ (0 until 16).map((i: Int) => {
        toRegFieldRw(commonData(i), s"commonData_${i}")
      }) ++ (0 until commonDriverctl.length).map((i: Int) => {
        toRegFieldRw(commonDriverctl(i), s"commonDriverctl_${i}")
      }) ++ Seq(
        toRegFieldRw(commonTxctl.dll_reset, s"commonTxctlDllReset"),
        toRegFieldRw(commonTxctl.driver, s"commonTxctlDriver"),
        toRegFieldRw(commonTxctl.skew, s"commonTxctlSkew")
      ) ++ (0 until 32).map((j: Int) =>
        toRegFieldRw(commonTxctl.shuffler(j), s"commonTxctlShuffler_$j")
      ) ++ Seq(
        toRegFieldRw(txValid, "txValid"),
        toRegFieldRw(rxLfsrValid, "rxLfsrValid"),
        toRegFieldRw(mainbandSel, "mainbandSel"),
        toRegFieldRw(creditFlowEnable, "creditFlowEnable"),
        toRegFieldRw(txValidLaneSel, "txValidLaneSel"),
        toRegFieldRw(rxValidLaneSel, "rxValidLaneSel")
      ) ++ Seq(
        RegField.r(64, lastSeenTLReq, RegFieldDesc("lastSeenTLReq", ""))
      )

      mmioRegs.zipWithIndex.map({
        case (f, i) => {
          i * 8 -> Seq(f)
        }
      })
    }

    // Spec-defined UCIe digital registers. Added after UCIe TL Regs.
    val ucieRegmap = withClockAndReset(clock, reset) {
      val (entries, _, _) = UcieRegBlock.build(ucieBlockIo, reset, ucieRegParams, ucieTLRegionSize)
      entries
    }
    node.regmap((regmap ++ ucieRegmap): _*)
  }
}

object UcieTL {
  val dataBits = 256
}

class UcieTLBundleA extends Bundle {
  val opcode = UInt(3.W)
  val param = UInt(3.W)
  val size = UInt(4.W)
  val address = UInt(64.W) // to
  val mask = UInt((UcieTL.dataBits / 8).W)
  val data = UInt(UcieTL.dataBits.W)
  val source = UInt(8.W) // to
  val corrupt = Bool()
}

class UcieTLBundleD extends Bundle {
  // fixed fields during multibeat:
  val opcode = UInt(3.W)
  val param = UInt(2.W)
  val size = UInt(4.W)
  val data = UInt(UcieTL.dataBits.W)
  val source = UInt(8.W) // to
  val sink = UInt(1.W) // from
  val denied = Bool() // implies corrupt iff *Data
  val corrupt = Bool()
}

class UcieTXA(creditBits: Int = 5) extends Bundle {
  val tl_valid = Bool()
  val credit_valid = Bool()
  val credit_a = UInt(creditBits.W)
  val credit_d = UInt(creditBits.W)
  val tl = new UcieTLBundleA
}

class UcieTXD(creditBits: Int = 5) extends Bundle {
  val tl_valid = Bool()
  val credit_valid = Bool()
  val credit_a = UInt(creditBits.W)
  val credit_d = UInt(creditBits.W)
  val tl = new UcieTLBundleD
}

class UcieTL(params: UcieTLParams, managerRegion: Seq[AddressSet], beatBytes: Int, blockBytes: Int)(implicit
    p: Parameters
) extends LazyModule {
  override lazy val desiredName = "UcieTL"

  // Main digital clock node.
  val digitalClockNode = ClockSinkNode(Seq(ClockSinkParameters()))
  val ucieDigitalClockNode = ClockSourceNode(Seq(ClockSourceParameters()))

  val ucieRegParams = UcieDigitalTopParams.default().regs.copy(
    baseAddress = params.ucieRegsBaseAddress,
    numModules = 1,
    includeRegNode = false,
    includeInterruptNode = false
  )
  val ucieDigitalLazy: UcieDigitalTop =
    LazyModule(new UcieDigitalTop(UcieDigitalTopParams.default().copy(regs = ucieRegParams)))
  val regs = LazyModule(new UcieTLRegs(params, beatBytes, ucieRegParams))

  val device = new SimpleDevice("ucie", Seq("ucbbar,ucie"))
  // Manager node to send and acquire traffic to partner die
  val managerNode = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        managers = managerRegion.map { as => TLSlaveParameters.v1(
            address = AddressSet.misaligned(as.base, as.mask + 1),
            resources = device.reg,
            regionType =
              RegionType.UNCACHED, // Should be changed to CACHED eventually
            executable = true,
            supportsGet = TransferSizes(1, blockBytes),
            supportsPutFull = TransferSizes(1, blockBytes),
            supportsPutPartial = TransferSizes(1, blockBytes),
            fifoId = Some(0)
          )
        },
        beatBytes = beatBytes
      )
    )
  )
  // Client node to reply to send and acquire traffic from partner die
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
  val regNode = regs.node
  regs.clockNode := ucieDigitalClockNode

  override lazy val module = new UcieTLImpl
  class UcieTLImpl extends LazyRawModuleImp(this) {
    childClock := digitalClockNode.in(0)._1.clock
    childReset := digitalClockNode.in(0)._1.reset
    override def provideImplicitClockToLazyChildren = true

    val regmap = regs.module.regmap
    val io = IO(new UcieBumpsIO(params.numLanes))

    // PHY
    val phy = Module(new Phy(params.numLanes)(params.includeDefaultModels))
    io.phy <> phy.io.top
    phy.io.clkRst.reset := digitalClockNode.in(0)._1.reset
    ucieDigitalClockNode.out(0)._1.clock := phy.io.clkRst.ucieClk
    ucieDigitalClockNode.out(0)._1.reset := phy.io.clkRst.ucieRst
    phy.io.regs <> regs.module.io.phy

    // TEST HARNESS
    val test = withClockAndReset(
      phy.io.clkRst.ucieClk,
      phy.io.clkRst.ucieRst
    ) {
      Module(
        new PhyTest(
          params.bufferDepthPerLane,
          params.numLanes,
          params.bitCounterWidth
        )
      )
    }
    io.debug <> test.io.bumps
    test.io.debug <> phy.io.debug
    phy.io.clkRst.divResetb := test.io.divResetb
    test.io.regs <> regs.module.io.test

    val mainbandSel = regs.module.io.mainbandSel

    // Async crossings
    val txTestFifo =
      Module(new AsyncQueue(new TxIO(params.numLanes), params.queueParams))
    txTestFifo.io.enq_clock := phy.io.clkRst.ucieClk
    txTestFifo.io.enq_reset := phy.io.clkRst.ucieRst
    txTestFifo.io.deq_clock := phy.io.clkRst.txDivClk
    txTestFifo.io.deq_reset := phy.io.clkRst.txDivRst
    // TODO: should deq ready be synchronous to deq clock?
    // txTestFifo crosses both the phytest and ucie mainband tx signals to the PHY.
    txTestFifo.io.deq.ready := mainbandSel =/= MainbandSel.tl

    val rxTestFifo =
      Module(new AsyncQueue(new RxIO(params.numLanes), params.queueParams))
    rxTestFifo.io.enq.bits := phy.io.rx
    rxTestFifo.io.enq.valid := mainbandSel =/= MainbandSel.tl
    rxTestFifo.io.enq_clock := phy.io.clkRst.rxDivClk
    rxTestFifo.io.enq_reset := phy.io.clkRst.rxDivRst
    rxTestFifo.io.deq_clock := phy.io.clkRst.ucieClk
    rxTestFifo.io.deq_reset := phy.io.clkRst.ucieRst

  
    val ucieDigital = withClockAndReset(phy.io.clkRst.ucieClk, phy.io.clkRst.ucieRst) {
      ucieDigitalLazy.module
    }
    ucieDigital.io.regBlockIo.foreach { rb => regs.module.ucieBlockIo <> rb }
    val selUcie = mainbandSel === MainbandSel.ucie
    // phyFacing TX: mux PhyTest vs ucieDigital into txTestFifo.enq (both ucieClk).
    val digiToPhyTx = ucieDigital.io.phyFacingIo.mainbandLink.tx
    val digiTxAsTxIo = Wire(new TxIO(params.numLanes))
    digiTxAsTxIo.data := digiToPhyTx.bits.data
    digiTxAsTxIo.valid := digiToPhyTx.bits.valid
    digiTxAsTxIo.clkp := digiToPhyTx.bits.clkP
    digiTxAsTxIo.clkn := digiToPhyTx.bits.clkN
    digiTxAsTxIo.track := digiToPhyTx.bits.trk
    txTestFifo.io.enq.valid := Mux(selUcie, digiToPhyTx.valid, test.io.tx.valid)
    txTestFifo.io.enq.bits := Mux(selUcie, digiTxAsTxIo, test.io.tx.bits)
    test.io.tx.ready := txTestFifo.io.enq.ready && !selUcie
    digiToPhyTx.ready := txTestFifo.io.enq.ready && selUcie

    // phyFacing RX: rxTestFifo.deq routed to PhyTest or ucieDigital by sel.
    val digiToPhyRx = ucieDigital.io.phyFacingIo.mainbandLink.rx
    digiToPhyRx.bits.data := rxTestFifo.io.deq.bits.data
    digiToPhyRx.bits.valid := rxTestFifo.io.deq.bits.valid
    digiToPhyRx.bits.trk := rxTestFifo.io.deq.bits.track
    // TODO: RxIO has no clkp/clkn; use the forwarded-clock patterns until sampled clkP/clkN exist.
    // Need them for training and link bringup.
    digiToPhyRx.bits.clkP := "h55555555".U
    digiToPhyRx.bits.clkN := "haaaaaaaa".U
    digiToPhyRx.valid := rxTestFifo.io.deq.valid && selUcie
    test.io.rx.bits := rxTestFifo.io.deq.bits
    test.io.rx.valid := rxTestFifo.io.deq.valid && !selUcie
    rxTestFifo.io.deq.ready := Mux(selUcie, digiToPhyRx.ready, test.io.rx.ready)

    // Sideband: ucie mode uses ucieDigital; phytest/tl use PhyTest. Rx goes to both.
    val digiSb = ucieDigital.io.phyFacingIo.sidebandLink
    phy.io.sb.txClk  := Mux(selUcie, digiSb.out.fwClock.asBool.asClock, test.io.sb.txClk)
    phy.io.sb.txData := Mux(selUcie, digiSb.out.bits.asBool, test.io.sb.txData)
    test.io.sb.rxClk  := phy.io.sb.rxClk
    test.io.sb.rxData := phy.io.sb.rxData
    digiSb.in.bits    := phy.io.sb.rxData.asUInt
    digiSb.in.fwClock := phy.io.sb.rxClk.asUInt
     
    withClockAndReset(childClock, childReset) {
      val clientTl = clientNode.out(0)._1
      val managerTl = managerNode.in(0)._1

      val ucieClientTlD = Wire(new UcieTLBundleD)
      val ucieManagerTlA = Wire(new UcieTLBundleA)

      require(ucieClientTlD.opcode.getWidth >= clientTl.d.bits.opcode.getWidth)
      require(ucieClientTlD.param.getWidth >= clientTl.d.bits.param.getWidth)
      require(ucieClientTlD.size.getWidth >= clientTl.d.bits.size.getWidth)
      require(ucieClientTlD.data.getWidth >= clientTl.d.bits.data.getWidth)
      require(ucieClientTlD.source.getWidth >= clientTl.d.bits.source.getWidth)
      require(ucieClientTlD.denied.getWidth >= clientTl.d.bits.denied.getWidth)
      require(
        ucieClientTlD.corrupt.getWidth >= clientTl.d.bits.corrupt.getWidth
      )
      ucieClientTlD.opcode := clientTl.d.bits.opcode
      ucieClientTlD.param := clientTl.d.bits.param
      ucieClientTlD.size := clientTl.d.bits.size
      ucieClientTlD.data := clientTl.d.bits.data
      ucieClientTlD.source := clientTl.d.bits.source
      ucieClientTlD.sink := clientTl.d.bits.sink(ucieClientTlD.sink.getWidth - 1, 0) // Truncate since sink will always be 0
      ucieClientTlD.denied := clientTl.d.bits.denied
      ucieClientTlD.corrupt := clientTl.d.bits.corrupt

      require(
        ucieManagerTlA.opcode.getWidth >= managerTl.a.bits.opcode.getWidth
      )
      require(ucieManagerTlA.param.getWidth >= managerTl.a.bits.param.getWidth)
      require(ucieManagerTlA.size.getWidth >= managerTl.a.bits.size.getWidth)
      require(
        ucieManagerTlA.address.getWidth >= managerTl.a.bits.address.getWidth
      )
      require(ucieManagerTlA.mask.getWidth >= managerTl.a.bits.mask.getWidth)
      require(ucieManagerTlA.data.getWidth >= managerTl.a.bits.data.getWidth)
      require(
        ucieManagerTlA.source.getWidth >= managerTl.a.bits.source.getWidth
      )
      require(
        ucieManagerTlA.corrupt.getWidth >= managerTl.a.bits.corrupt.getWidth
      )
      ucieManagerTlA.opcode := managerTl.a.bits.opcode
      ucieManagerTlA.param := managerTl.a.bits.param
      ucieManagerTlA.size := managerTl.a.bits.size
      ucieManagerTlA.address := managerTl.a.bits.address
      ucieManagerTlA.mask := managerTl.a.bits.mask
      ucieManagerTlA.data := managerTl.a.bits.data
      ucieManagerTlA.source := managerTl.a.bits.source
      ucieManagerTlA.corrupt := managerTl.a.bits.corrupt

      val creditBits = log2Up(params.tlBufferDepth + 1)

      // Credits to return to the partner: first half = A channel, second half = D channel.
      val aCreditsToReturn = RegInit(0.U(creditBits.W))
      val dCreditsToReturn = RegInit(0.U(creditBits.W))
      val creditRetValid = Wire(Bool())
      val creditRetTimer = RegInit(0.U(params.creditRetTimerWidth.W)) // Arbitrary width for now
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
                        regs.module.io.mainbandSel =/= MainbandSel.phytest)

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

      val lastSeenAddr = RegInit(0.U(64.W))
      when(managerTl.a.valid) {
        lastSeenAddr := managerTl.a.bits.address
      }
      regs.module.io.lastSeenTLReq := lastSeenAddr

      val rxABuffer = Module(new Queue(new UcieTXA(creditBits), params.tlBufferDepth))
      val rxDBuffer = Module(new Queue(new UcieTXD(creditBits), params.tlBufferDepth))
      val txTlFifo =
        Module(new AsyncQueue(new TxIO(params.numLanes), params.queueParams))
      // Always true to send clock when tl path.
      txTlFifo.io.enq.valid := mainbandSel === MainbandSel.tl
      val txValid = clientTl.d.fire || managerTl.a.fire || creditRetValid
      txTlFifo.io.enq.bits.track := "h55555555".U
      txTlFifo.io.enq.bits.clkp := "h55555555".U
      txTlFifo.io.enq.bits.clkn := "haaaaaaaa".U
      txTlFifo.io.enq.bits.valid := Mux(txValid, "h0000ffff".U, 0.U)
      val txFramedData = Mux(
        clientTl.d.valid,
        Cat(ucieClientTxD.asUInt, 1.U),
        Cat(ucieManagerTxA.asUInt, 0.U)
      )
      txTlFifo.io.enq.bits.data := txFramedData.asTypeOf(txTlFifo.io.enq.bits.data)

      // chipFacing TX: route the same framed data into ucieDigital (ucie mode), crossing
      // childClock -> ucieClk. Only the protocol data crosses (no track/clkp/clkn/valid lanes); deq is ucieClk.
      val txAQ = Module(new AsyncQueue(chiselTypeOf(ucieDigital.io.chipFacingIo.mainbandTx.bits), params.queueParams))
      txAQ.io.enq.valid := mainbandSel === MainbandSel.ucie
      txAQ.io.enq.bits.data := txFramedData.asTypeOf(txAQ.io.enq.bits.data)
      txAQ.io.enq_clock := childClock
      txAQ.io.enq_reset := childReset
      txAQ.io.deq_clock := phy.io.clkRst.ucieClk
      txAQ.io.deq_reset := phy.io.clkRst.ucieRst
      ucieDigital.io.chipFacingIo.mainbandTx.valid := txAQ.io.deq.valid
      ucieDigital.io.chipFacingIo.mainbandTx.bits := txAQ.io.deq.bits
      txAQ.io.deq.ready := ucieDigital.io.chipFacingIo.mainbandTx.ready
      val txEnqReady = Mux(mainbandSel === MainbandSel.ucie, txAQ.io.enq.ready, txTlFifo.io.enq.ready)
    
      clientTl.d.ready := txEnqReady && dAvail
      managerTl.a.ready := txEnqReady && aAvail && !clientTl.d.valid

      when(rxABuffer.io.deq.fire && rxABuffer.io.deq.bits.tl_valid) {
        aCreditsToReturn := aCreditsToReturn + 1.U
      }
      when(creditRetValid) {
        aCreditsToReturn := Mux(rxABuffer.io.deq.fire, 1.U, 0.U)
      }
      when(rxDBuffer.io.deq.fire && rxDBuffer.io.deq.bits.tl_valid) {
        dCreditsToReturn := dCreditsToReturn + 1.U
      }
      when(creditRetValid) {
        dCreditsToReturn := Mux(rxDBuffer.io.deq.fire, 1.U, 0.U)
      }

      txTlFifo.io.enq_clock := childClock
      txTlFifo.io.enq_reset := childReset
      txTlFifo.io.deq_clock := phy.io.clkRst.txDivClk
      txTlFifo.io.deq_reset := phy.io.clkRst.txDivRst
      txTlFifo.io.deq.ready := regs.module.io.mainbandSel === MainbandSel.tl

      val rxTlFifo =
        Module(new AsyncQueue(new RxIO(params.numLanes), params.queueParams))
      val validFramer = Module(new ValidFramer(params.numLanes))
      rxTlFifo.io.enq.bits := phy.io.rx
      rxTlFifo.io.enq.valid := mainbandSel === MainbandSel.tl
      rxTlFifo.io.enq_clock := phy.io.clkRst.rxDivClk
      rxTlFifo.io.enq_reset := phy.io.clkRst.rxDivRst
      rxTlFifo.io.deq <> validFramer.io.phy
      rxTlFifo.io.deq_clock := childClock
      rxTlFifo.io.deq_reset := childReset
      // Replace decoupled IOs that need ready to be true with validIO
      validFramer.io.digital.ready := true.B
      rxABuffer.io.enq.valid := false.B
      rxDBuffer.io.enq.valid := false.B

      // chipFacing RX: ucieDigital's 512b (ucie mode) feeds the credit path, crossing
      // ucieClk -> childClock. Muxed with the tl-path ValidFramer output by mainbandSel.
      val rxAQ = Module(new AsyncQueue(chiselTypeOf(ucieDigital.io.chipFacingIo.mainbandRx.bits), params.queueParams))
      rxAQ.io.enq.valid := ucieDigital.io.chipFacingIo.mainbandRx.valid && (mainbandSel === MainbandSel.ucie)
      rxAQ.io.enq.bits := ucieDigital.io.chipFacingIo.mainbandRx.bits
      ucieDigital.io.chipFacingIo.mainbandRx.ready := rxAQ.io.enq.ready && (mainbandSel === MainbandSel.ucie)
      rxAQ.io.enq_clock := phy.io.clkRst.ucieClk
      rxAQ.io.enq_reset := phy.io.clkRst.ucieRst
      rxAQ.io.deq_clock := childClock
      rxAQ.io.deq_reset := childReset
      rxAQ.io.deq.ready := mainbandSel === MainbandSel.ucie
      val framedBits = Mux(mainbandSel === MainbandSel.ucie, rxAQ.io.deq.bits.data, validFramer.io.digital.bits.asUInt)
      val framedValid = Mux(mainbandSel === MainbandSel.ucie, rxAQ.io.deq.valid, validFramer.io.digital.valid)

      val tlBits = framedBits(framedBits.getWidth - 1, 1)
      rxABuffer.io.enq.bits := tlBits.asTypeOf(rxABuffer.io.enq.bits)
      rxDBuffer.io.enq.bits := tlBits.asTypeOf(rxDBuffer.io.enq.bits)
      when(framedValid) {
        when(framedBits.asUInt(0)) {
          rxDBuffer.io.enq.valid := true.B
        }.otherwise {
          rxABuffer.io.enq.valid := true.B
        }
      }

      clientTl.a <> rxABuffer.io.deq.map(bits => {
        val tlBundleA = Wire(chiselTypeOf(clientTl.a.bits))
        tlBundleA.opcode := bits.tl.opcode
        tlBundleA.param := bits.tl.param
        tlBundleA.size := bits.tl.size
        tlBundleA.address := bits.tl.address
        tlBundleA.mask := bits.tl.mask
        tlBundleA.data := bits.tl.data
        tlBundleA.source := bits.tl.source
        tlBundleA.corrupt := bits.tl.corrupt
        tlBundleA
      })
      clientTl.a.valid := rxABuffer.io.deq.valid && rxABuffer.io.deq.bits.tl_valid
      managerTl.d <> rxDBuffer.io.deq.map(bits => {
        val tlBundleD = Wire(chiselTypeOf(managerTl.d.bits))
        tlBundleD.opcode := bits.tl.opcode
        tlBundleD.param := bits.tl.param
        tlBundleD.size := bits.tl.size
        tlBundleD.data := bits.tl.data
        tlBundleD.source := bits.tl.source
        tlBundleD.sink := bits.tl.sink
        tlBundleD.denied := bits.tl.denied
        tlBundleD.corrupt := bits.tl.corrupt
        tlBundleD
      })
      managerTl.d.valid := rxDBuffer.io.deq.valid && rxDBuffer.io.deq.bits.tl_valid
      dontTouch(managerTl.d.bits.opcode)

      val txContClocks = Wire(new TxIO(params.numLanes))
      txContClocks.data := txTlFifo.io.deq.bits.data
      txContClocks.valid := txTlFifo.io.deq.bits.valid
      txContClocks.clkp := "h55555555".U
      txContClocks.clkn := "haaaaaaaa".U
      txContClocks.track := "h55555555".U

      // tl mode drives from txTlFifo; phytest and ucie both drive from txTestFifo.
      phy.io.tx := Mux(
        mainbandSel === MainbandSel.tl,
        Mux(
          txTlFifo.io.deq.valid,
          txContClocks,
          0.U.asTypeOf(phy.io.tx)
        ),
        Mux(
          txTestFifo.io.deq.valid,
          txTestFifo.io.deq.bits,
          0.U.asTypeOf(phy.io.tx)
        )
      )

      val creditAValid = rxABuffer.io.deq.valid && rxABuffer.io.deq.bits.credit_valid
      val creditDValid = rxDBuffer.io.deq.valid && rxDBuffer.io.deq.bits.credit_valid

      val aCreditCounter = Module(new CreditCounter(params.creditCounterSize, params.tlBufferDepth))
      aCreditCounter.io.used := managerTl.a.fire
      aCreditCounter.io.ret.valid := creditAValid || creditDValid
      aCreditCounter.io.ret.bits := Mux(creditAValid, rxABuffer.io.deq.bits.credit_a, rxDBuffer.io.deq.bits.credit_a)
      aCreditCounter.io.mode := regs.module.io.creditFlowEnable
      aAvail := aCreditCounter.io.avail

      val dCreditCounter = Module(new CreditCounter(params.creditCounterSize, params.tlBufferDepth))
      dCreditCounter.io.used := clientTl.d.fire
      dCreditCounter.io.ret.valid := creditAValid || creditDValid
      dCreditCounter.io.ret.bits := Mux(creditAValid, rxABuffer.io.deq.bits.credit_d, rxDBuffer.io.deq.bits.credit_d)
      dCreditCounter.io.mode := regs.module.io.creditFlowEnable
      dAvail := dCreditCounter.io.avail
    }
  }
}

trait CanHavePeripheryUcieTL { this: BaseSubsystem =>
  private val portName = "ucie"

  private val pbus = locateTLBusWrapper(PBUS)
  private val sbus = locateTLBusWrapper(SBUS)

  val uciephy = p(UcieTLKey) match {
    case Some(params) => {
      val uciephy =
        params.map(x => LazyModule(new UcieTL(x, Seq(AddressSet(0x0, 0xffffL)), pbus.beatBytes, pbus.blockBytes)(p)))

      lazy val uciephy_tlbus =
        params.map(x => locateTLBusWrapper(x.managerWhere))

      for (
        (((ucie, ucie_params), tlbus), n) <- uciephy
          .zip(params)
          .zip(uciephy_tlbus)
          .zipWithIndex
      ) {
        ucie.digitalClockNode := sbus.fixedClockNode
        pbus.coupleTo(s"uciephytest{$n}") {
          ucie.regNode := TLBuffer() := TLFragmenter(
            pbus.beatBytes,
            pbus.blockBytes
          ) := TLBuffer() := _
        }
      }
      Some(uciephy)
    }
    case None => None
  }
}

class UcieChipletLink(val params: UcieTLParams, val sys_params: OffchipSubsystemParams, val id: Int)(implicit p: Parameters) extends ChipletLinkWrapper {
  val ucie = LazyModule(new UcieTL(params, sys_params.managerRegion, sys_params.managerBeatBytes, sys_params.managerBlockBytes)(p))
  val client_node = ucie.clientNode
  val manager_node = ucie.managerNode
  val control_manager_node = Some(ucie.regNode)
  val clock_node = Some(ucie.digitalClockNode)
  val top_IO = BundleBridgeSource(() => new UcieBumpsIO(params.numLanes))
  override lazy val module = new UcieChipletLinkImpl(this)
}

class UcieChipletLinkImpl(outer: UcieChipletLink) extends LazyModuleImp(outer) {
  val io = outer.top_IO.out(0)._1
  outer.ucie.module.io <> io
}

class WithUcieTL(params: Seq[UcieTLParams])
    extends Config((site, here, up) => { case UcieTLKey =>
      Some(params)
    })

class WithUcieTLDefaultModels
    extends Config((site, here, up) => { case UcieTLKey =>
      up(UcieTLKey, site).map(u => u.map(_.copy(includeDefaultModels = true)))
    })

class RTLHarness(ucie: => UcieTL)(implicit p: Parameters) extends LazyModule {
  val clockNode = ClockSourceNode(Seq(ClockSourceParameters()))
  val node = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        clients = Seq(
          TLMasterParameters.v1(
            name = "dummy-node"
          )
        )
      )
    )
  )
  val ucieTL = LazyModule(ucie)
  // Counterparts for the mainband ports. Reusing the UcieTL node parameters keeps the negotiated
  // edges identical to a direct `managerNode := clientNode` loopback.
  val mbClientNode = TLClientNode(ucieTL.clientNode.portParams)
  val mbManagerNode = TLManagerNode(ucieTL.managerNode.portParams)

  ucieTL.digitalClockNode := clockNode
  ucieTL.regNode := node
  ucieTL.managerNode := mbClientNode
  mbManagerNode := ucieTL.clientNode

  // Bring every diplomatic port out to the top level so that the logic behind them is not
  // optimized out of UcieTL.
  val io_reg = InModuleBody { node.makeIOs() }
  val io_mb_in = InModuleBody { mbClientNode.makeIOs() }
  val io_mb_out = InModuleBody { mbManagerNode.makeIOs() }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    ucieTL.module.io := DontCare
    dontTouch(ucieTL.module.io)
    clockNode.out(0)._1.clock := clock
    clockNode.out(0)._1.reset := reset
    val regmap = ucieTL.module.regmap
  }
}
