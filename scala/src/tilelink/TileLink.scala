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
import edu.berkeley.cs.chippy._
import freechips.rocketchip.diplomacy.{SimpleDevice, AddressSet}
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.lazymodule._
import edu.berkeley.cs.uciedigital.phy.macros.{PllCtlIO, DriverCtlIO}
import freechips.rocketchip.util.AsyncQueueParams
import freechips.rocketchip.util.AsyncQueue
import edu.berkeley.cs.uciedigital.phy.macros.PllDebugOutIO
import freechips.rocketchip.diplomacy.RegionType
import freechips.rocketchip.diplomacy.TransferSizes
import freechips.rocketchip.diplomacy.IdRange

case class UcieTLParams(
    address: BigInt = 0x4000,
    bufferDepthPerLane: Int = 11,
    numLanes: Int = 16,
    bitCounterWidth: Int = 64,
    managerWhere: TLBusWrapperLocation = PBUS,
    queueParams: AsyncQueueParams = AsyncQueueParams(depth = 32),
    includeDefaultModels: Boolean = false
)

case object UcieTLKey extends Field[Option[Seq[UcieTLParams]]](None)

class UcieBumpsIO(numLanes: Int = 16) extends Bundle {
  val phy = new PhyBumpsIO(numLanes)
  val debug = new DebugBumpsIO
}

object MainbandSel extends ChiselEnum {
  // Allow PhyTest to control mainband
  val phytest = Value(0.U(1.W))
  // Send TL packets over mainband
  val tl = Value(1.U(1.W))
}

class UcieTLRegsIO(
    bufferDepthPerLane: Int = 11,
    numLanes: Int = 16,
    bitCounterWidth: Int = 64
) extends Bundle {
  val test = Flipped(
    new PhyTestRegsIO(bufferDepthPerLane, numLanes, bitCounterWidth)
  )
  val phy = Flipped(new PhyRegsIO(numLanes))
  val mainbandSel = Output(MainbandSel())
}

class UcieTLRegs(params: UcieTLParams, beatBytes: Int)(implicit
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
  val device = new SimpleDevice("ucie_control", Seq("ucbbar,ucie"))
  val node = TLRegisterNode(
    Seq(AddressSet(params.address, 16384 - 1)),
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

      val pllBypassEn = RegInit(false.B)
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
      val pllCtl = RegInit({
        val w = Wire(new PllCtlIO)
        w.dref_low := 30.U
        w.dref_high := 98.U
        w.dcoarse := 15.U
        w.d_kp := 50.U
        w.d_ki := 4.U
        w.d_clol := true.B
        w.d_ol_fcw := 0.U
        w.d_accumulator_reset := "h8000".U
        w.vco_reset := true.B
        w.digital_reset := true.B
        w
      })
      val testPllCtl = RegInit({
        val w = Wire(new PllCtlIO)
        w.dref_low := 30.U
        w.dref_high := 98.U
        w.dcoarse := 15.U
        w.d_kp := 50.U
        w.d_ki := 4.U
        w.d_clol := true.B
        w.d_ol_fcw := 0.U
        w.d_accumulator_reset := "h8000".U
        w.vco_reset := true.B
        w.digital_reset := true.B
        w
      })

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
      io.phy.pllBypassEn := applyShift(pllBypassEn)
      io.phy.txctl := applyShift(VecInit(txctl.take(params.numLanes + 4)))
      io.phy.pllCtl := applyShift(pllCtl)
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
        toRegFieldRw(pllCtl.dref_low, "pllDrefLow"),
        toRegFieldRw(pllCtl.dref_high, "pllDrefHigh"),
        toRegFieldRw(pllCtl.dcoarse, "pllDcoarse"),
        toRegFieldRw(pllCtl.d_kp, "pllDKp"),
        toRegFieldRw(pllCtl.d_ki, "pllDKi"),
        toRegFieldRw(pllCtl.d_clol, "pllDClol"),
        toRegFieldRw(pllCtl.d_ol_fcw, "pllDOlFcw"),
        toRegFieldRw(pllCtl.d_accumulator_reset, "pllDAccumulatorReset"),
        toRegFieldRw(pllCtl.vco_reset, "pllVcoReset"),
        toRegFieldRw(pllCtl.digital_reset, "pllDigitalReset"),
        toRegFieldRw(testPllCtl.dref_low, "testPllDrefLow"),
        toRegFieldRw(testPllCtl.dref_high, "testPllDrefHigh"),
        toRegFieldRw(testPllCtl.dcoarse, "testPllDcoarse"),
        toRegFieldRw(testPllCtl.d_kp, "testPllDKp"),
        toRegFieldRw(testPllCtl.d_ki, "testPllDKi"),
        toRegFieldRw(testPllCtl.d_clol, "testPllDClol"),
        toRegFieldRw(testPllCtl.d_ol_fcw, "testPllDOlFcw"),
        toRegFieldRw(
          testPllCtl.d_accumulator_reset,
          "testPllDAccumulatorReset"
        ),
        toRegFieldRw(testPllCtl.vco_reset, "testPllVcoReset"),
        toRegFieldRw(testPllCtl.digital_reset, "testPllDigitalReset"),
        toRegFieldR(applyShift(io.phy.pllOutput), "pllOutput"),
        toRegFieldRw(pllBypassEn, "pllBypassEn")
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
        toRegFieldRw(mainbandSel, "mainbandSel")
      )

      mmioRegs.zipWithIndex.map({
        case (f, i) => {
          i * 8 -> Seq(f)
        }
      })
    }
    node.regmap(regmap: _*)
  }
}

class UcieTL(params: UcieTLParams, beatBytes: Int)(implicit
    p: Parameters
) extends LazyModule {
  override lazy val desiredName = "UcieTL"

  // Main digital clock node.
  val digitalClockNode = ClockSinkNode(Seq(ClockSinkParameters()))
  val ucieDigitalClockNode = ClockSourceNode(Seq(ClockSourceParameters()))
  val regs = LazyModule(new UcieTLRegs(params, beatBytes))

  // TODO: Support more than 1 in-flight message
  val device = new SimpleDevice("ucie", Seq("ucbbar,ucie"))
  // Manager node to send and acquire traffic to partner die
  // TODO: Use correct AddressSet
  val managerNode = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        Seq(
          TLSlaveParameters.v1(
            address = Seq(AddressSet(0x0, 0xffffL)),
            resources = device.reg,
            regionType =
              RegionType.UNCACHED, // Should be changed to CACHED eventually
            executable = true,
            supportsGet = TransferSizes(1, beatBytes),
            supportsPutFull = TransferSizes(1, beatBytes),
            supportsPutPartial = TransferSizes(1, beatBytes),
            fifoId = Some(0)
          )
        ),
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
            sourceId = IdRange(0, 1),
            requestFifo = true,
            visibility = Seq(AddressSet(0x0, 0xffffL))
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
    test.io.sb <> phy.io.sb
    phy.io.clkRst.divResetb := test.io.divResetb
    test.io.regs <> regs.module.io.test

    // Async crossings
    val txTestFifo =
      Module(new AsyncQueue(new TxIO(params.numLanes), params.queueParams))
    txTestFifo.io.enq <> test.io.tx
    txTestFifo.io.enq_clock := phy.io.clkRst.ucieClk
    txTestFifo.io.enq_reset := phy.io.clkRst.ucieRst
    txTestFifo.io.deq_clock := phy.io.clkRst.txDivClk
    txTestFifo.io.deq_reset := phy.io.clkRst.txDivRst
    // TODO: should deq ready be synchronous to deq clock?
    txTestFifo.io.deq.ready := regs.module.io.mainbandSel === MainbandSel.phytest

    val rxTestFifo =
      Module(new AsyncQueue(new RxIO(params.numLanes), params.queueParams))
    rxTestFifo.io.enq.bits := phy.io.rx
    rxTestFifo.io.enq.valid := regs.module.io.mainbandSel === MainbandSel.phytest
    rxTestFifo.io.enq_clock := phy.io.clkRst.rxDivClk
    rxTestFifo.io.enq_reset := phy.io.clkRst.rxDivRst
    rxTestFifo.io.deq <> test.io.rx
    rxTestFifo.io.deq_clock := phy.io.clkRst.ucieClk
    rxTestFifo.io.deq_reset := phy.io.clkRst.ucieRst

    withClockAndReset(childClock, childReset) {
      val clientTl = clientNode.out(0)._1
      val managerTl = managerNode.in(0)._1
      val txAInFlight = RegInit(false.B)
      val rxABuffer = Module(new Queue(chiselTypeOf(clientTl.a.bits), 1))
      val rxDBuffer = Module(new Queue(chiselTypeOf(managerTl.d.bits), 1))
      val txTlFifo =
        Module(new AsyncQueue(new TxIO(params.numLanes), params.queueParams))
      // Always true to send clock.
      txTlFifo.io.enq.valid := true.B
      val tlValid = clientTl.d.valid || managerTl.a.valid
      txTlFifo.io.enq.bits.track := "h55555555".U
      txTlFifo.io.enq.bits.clkp := "h55555555".U
      txTlFifo.io.enq.bits.clkn := "haaaaaaaa".U
      txTlFifo.io.enq.bits.valid := Mux(tlValid, "h0000ffff".U, 0.U)
      txTlFifo.io.enq.bits.data := Mux(
        clientTl.d.valid,
        Cat(clientTl.d.bits.asUInt, 1.U),
        Cat(managerTl.a.bits.asUInt, 0.U)
      ).asTypeOf(txTlFifo.io.enq.bits.data)
      clientTl.d.ready := txTlFifo.io.enq.ready
      managerTl.a.ready := txTlFifo.io.enq.ready && !clientTl.d.valid && !txAInFlight
      when(managerTl.a.ready && managerTl.a.valid) {
        txAInFlight := true.B
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
      rxTlFifo.io.enq.valid := regs.module.io.mainbandSel === MainbandSel.tl
      rxTlFifo.io.enq_clock := phy.io.clkRst.rxDivClk
      rxTlFifo.io.enq_reset := phy.io.clkRst.rxDivRst
      rxTlFifo.io.deq <> validFramer.io.phy
      rxTlFifo.io.deq_clock := childClock
      rxTlFifo.io.deq_reset := childReset
      // Replace decoupled IOs that need ready to be true with validIO
      validFramer.io.digital.ready := true.B
      rxABuffer.io.enq.valid := false.B
      rxDBuffer.io.enq.valid := false.B
      val framedBits = validFramer.io.digital.bits.asUInt
      val tlBits = framedBits(framedBits.getWidth - 1, 1)
      rxABuffer.io.enq.bits := tlBits.asTypeOf(rxABuffer.io.enq.bits)
      rxDBuffer.io.enq.bits := tlBits.asTypeOf(rxDBuffer.io.enq.bits)
      when(validFramer.io.digital.valid) {
        when(framedBits.asUInt(0)) {
          rxDBuffer.io.enq.valid := true.B
        }.otherwise {
          rxABuffer.io.enq.valid := true.B
        }
      }

      clientTl.a <> rxABuffer.io.deq
      managerTl.d <> rxDBuffer.io.deq
      when(managerTl.d.valid && managerTl.d.ready) {
        txAInFlight := false.B
      }

      phy.io.tx := Mux(
        regs.module.io.mainbandSel === MainbandSel.phytest,
        Mux(
          txTestFifo.io.deq.valid,
          txTestFifo.io.deq.bits,
          0.U.asTypeOf(phy.io.tx)
        ),
        Mux(
          txTlFifo.io.deq.valid,
          txTlFifo.io.deq.bits,
          0.U.asTypeOf(phy.io.tx)
        )
      )
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
        params.map(x => LazyModule(new UcieTL(x, pbus.beatBytes)(p)))

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
  ucieTL.digitalClockNode := clockNode
  ucieTL.regNode := node
  // Hack to get RTL to be generated, should never be simulated.
  ucieTL.managerNode := ucieTL.clientNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    ucieTL.module.io := DontCare
    clockNode.out(0)._1 := DontCare
    val regmap = ucieTL.module.regmap
  }
}
