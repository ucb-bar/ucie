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
  CacheBlockBytes,
  TLBusWrapperLocation
}
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.{
  HasRegMap,
  RegField,
  RegWriteFn,
  RegReadFn,
  RegFieldDesc
}
import freechips.rocketchip.tilelink._
import edu.berkeley.cs.uciedigital.phy._
import freechips.rocketchip.util.{AsyncQueueParams}
import testchipip.soc.{OBUS}

case class UcieTLParams(
    address: BigInt = 0x4000,
    bufferDepthPerLane: Int = 11,
    numLanes: Int = 16,
    bitCounterWidth: Int = 64,
    managerWhere: TLBusWrapperLocation = PBUS,
    sim: Boolean = false
)

case object UcieTLKey extends Field[Option[Seq[UcieTLParams]]](None)

class UcieBumpsIO(numLanes: Int = 16) extends Bundle {
  val phy = new PhyBumpsIO(numLanes)
  val debug = new DebugBumpsIO
}

class UcieTL(params: UcieTLParams, beatBytes: Int)(implicit
    p: Parameters
) extends ClockSinkDomain(ClockSinkParameters())(p) {
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
  override lazy val desiredName = "UcieTL"
  val device = new SimpleDevice("ucie", Seq("ucbbar,ucie"))
  val node = TLRegisterNode(
    Seq(AddressSet(params.address, 16384 - 1)),
    device,
    "reg/control",
    beatBytes = beatBytes
  )

  val topIO = BundleBridgeSource(() => new UcieBumpsIO(params.numLanes))

  override lazy val module = new UcieTLImpl
  class UcieTLImpl extends Impl {
    val io = IO(new Bundle {})
    withClockAndReset(clock, reset) {
      val io = topIO.out(0)._1

      // PHY
      val phy = Module(new Phy(params.numLanes, params.sim))

      // TEST HARNESS
      val phyTestReset = ShiftRegister(reset, 2, true.B)
      val test = withReset(phyTestReset) {
        Module(
          new PhyTest(
            params.bufferDepthPerLane,
            params.numLanes,
            params.bitCounterWidth
          )
        )
      }

      // MMIO registers.
      val testTarget = RegInit(TestTarget.mainband)
      val txTestMode = RegInit(TxTestMode.manual)
      val txDataMode = RegInit(DataMode.finite)
      val txLfsrSeed = RegInit(
        VecInit(
          Seq.fill(params.numLanes + 1)(
            1.U(test.io.regs.txLfsrSeed(0).getWidth.W)
          )
        )
      )
      val txFsmRst = Wire(DecoupledIO(UInt(1.W)))
      val txExecute = Wire(DecoupledIO(UInt(1.W)))
      val txWriteChunk = Wire(DecoupledIO(UInt(1.W)))
      val txManualRepeatPeriod =
        RegInit(0.U(test.io.regs.txManualRepeatPeriod.getWidth.W))
      val txPacketsToSend =
        RegInit(0.U(test.io.regs.txPacketsToSend.getWidth.W))
      val txClkP = RegInit(0.U(32.W))
      val txClkN = RegInit(0.U(32.W))
      val txValid = RegInit(0.U(32.W))
      val txTrack = RegInit(0.U(32.W))
      val txDataLaneGroup =
        RegInit(0.U(test.io.regs.txDataLaneGroup.getWidth.W))
      val txDataOffset = RegInit(0.U(test.io.regs.txDataOffset.getWidth.W))
      val txDataChunkIn0 = RegInit(0.U(64.W))
      val txDataChunkIn1 = RegInit(0.U(64.W))
      val rxDataMode = RegInit(DataMode.infinite)
      val rxLfsrSeed = RegInit(
        VecInit(
          Seq.fill(params.numLanes + 1)(
            1.U(test.io.regs.rxLfsrSeed(0).getWidth.W)
          )
        )
      )
      val rxLfsrValid = RegInit(0.U(32.W))
      val rxFsmRst = Wire(DecoupledIO(UInt(1.W)))
      val rxPacketsToReceive =
        RegInit(0.U(test.io.regs.rxPacketsToReceive.getWidth.W))
      val rxPauseCounters = RegInit(0.U(1.W))
      val rxDataLane = RegInit(0.U(test.io.regs.rxDataLane.getWidth.W))
      val rxDataOffset = RegInit(0.U(test.io.regs.rxDataOffset.getWidth.W))

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
        val w = Wire(new UciePllCtlIO)
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
        val w = Wire(new UciePllCtlIO)
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
        val w = Wire(new DriverControlIO)
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

      val ucieStack = RegInit(false.B)

      txFsmRst.ready := true.B
      txExecute.ready := true.B
      txWriteChunk.ready := true.B
      rxFsmRst.ready := true.B

      test.io.regs.txDataChunkIn.bits := ShiftRegister(
        Cat(txDataChunkIn1, txDataChunkIn0),
        2,
        true.B
      )
      test.io.regs.txDataChunkIn.valid := ShiftRegister(
        txWriteChunk.valid,
        2,
        true.B
      )
      test.io.regs.txDataLaneGroup := ShiftRegister(txDataLaneGroup, 2, true.B)
      test.io.regs.txDataOffset := ShiftRegister(txDataOffset, 2, true.B)

      test.io.regs.testTarget := ShiftRegister(testTarget, 2, true.B)
      test.io.regs.txTestMode := ShiftRegister(txTestMode, 2, true.B)
      test.io.regs.txDataMode := ShiftRegister(txDataMode, 2, true.B)
      test.io.regs.txLfsrSeed := ShiftRegister(txLfsrSeed, 2, true.B)
      test.io.regs.txFsmRst := ShiftRegister(txFsmRst.valid, 2, true.B)
      test.io.regs.txExecute := ShiftRegister(txExecute.valid, 2, true.B)
      test.io.regs.txManualRepeatPeriod := ShiftRegister(
        txManualRepeatPeriod,
        2,
        true.B
      )
      test.io.regs.txPacketsToSend := ShiftRegister(txPacketsToSend, 2, true.B)
      test.io.regs.txClkP := ShiftRegister(txClkP, 2, true.B)
      test.io.regs.txClkN := ShiftRegister(txClkN, 2, true.B)
      test.io.regs.txValid := ShiftRegister(txValid, 2, true.B)
      test.io.regs.txTrack := ShiftRegister(txTrack, 2, true.B)
      test.io.regs.rxDataMode := ShiftRegister(rxDataMode, 2, true.B)
      test.io.regs.rxLfsrSeed := ShiftRegister(rxLfsrSeed, 2, true.B)
      test.io.regs.rxLfsrValid := ShiftRegister(rxLfsrValid, 2, true.B)
      test.io.regs.rxFsmRst := ShiftRegister(rxFsmRst.valid, 2, true.B)
      test.io.regs.rxPacketsToReceive := ShiftRegister(
        rxPacketsToReceive,
        2,
        true.B
      )
      test.io.regs.rxPauseCounters := ShiftRegister(rxPauseCounters, 2, true.B)
      test.io.regs.rxDataLane := ShiftRegister(rxDataLane, 2, true.B)
      test.io.regs.rxDataOffset := ShiftRegister(rxDataOffset, 2, true.B)

      phy.io.pllBypassEn := ShiftRegister(pllBypassEn, 2, true.B)
      phy.io.txctl := ShiftRegister(txctl, 2, true.B)
      phy.io.pllCtl := ShiftRegister(pllCtl, 2, true.B)
      phy.io.testPllCtl := ShiftRegister(testPllCtl, 2, true.B)
      phy.io.rxctl := ShiftRegister(rxctl, 2, true.B)

      var mmioRegs = Seq(
        toRegFieldRw(testTarget, "testTarget"),
        toRegFieldRw(txTestMode, "txTestMode"),
        toRegFieldRw(txDataMode, "txDataMode")
      ) ++ (0 until params.numLanes + 1).map((i: Int) => {
        toRegFieldRw(txLfsrSeed(i), s"txLfsrSeed_$i")
      }) ++ Seq(
        RegField.w(1, txFsmRst, RegFieldDesc("txFsmRst", "")),
        RegField.w(1, txExecute, RegFieldDesc("txExecute", "")),
        RegField.w(1, txWriteChunk, RegFieldDesc("txWriteChunk", "")),
        toRegFieldR(
          ShiftRegister(test.io.regs.txPacketsSent, 2, true.B),
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
          ShiftRegister(test.io.regs.txDataChunkOut(63, 0), 2, true.B),
          "txDataChunkOut0"
        ),
        toRegFieldR(
          ShiftRegister(test.io.regs.txDataChunkOut(127, 64), 2, true.B),
          "txDataChunkOut1"
        )
      ) ++ Seq(
        toRegFieldR(
          ShiftRegister(test.io.regs.txTestState, 2, true.B),
          "txTestState"
        ),
        toRegFieldRw(rxDataMode, s"rxDataMode")
      ) ++ (0 until params.numLanes + 1).map((i: Int) => {
        toRegFieldRw(rxLfsrSeed(i), s"rxLfsrSeed_$i")
      }) ++ (0 until params.numLanes + 2).map((i: Int) => {
        toRegFieldR(
          ShiftRegister(test.io.regs.rxBitErrors(i), 2, true.B),
          s"rxBitErrors_$i"
        )
      }) ++ Seq(
        RegField.w(1, rxFsmRst, RegFieldDesc("rxFsmRst", "")),
        toRegFieldRw(rxPacketsToReceive, "rxPacketsToReceive"),
        toRegFieldRw(rxPauseCounters, "rxPauseCounters"),
        toRegFieldR(
          ShiftRegister(test.io.regs.rxPacketsReceived, 2, true.B),
          "rxPacketsReceived"
        ),
        toRegFieldR(
          ShiftRegister(test.io.regs.rxSignature, 2, true.B),
          "rxSignature"
        ),
        toRegFieldRw(rxDataLane, "rxDataLane"),
        toRegFieldRw(rxDataOffset, "rxDataOffset"),
        toRegFieldR(
          ShiftRegister(test.io.regs.rxDataChunk, 2, true.B),
          "rxDataChunk"
        ),
        toRegFieldRw(pllCtl.dref_low, "pll_dref_low"),
        toRegFieldRw(pllCtl.dref_high, "pll_dref_high"),
        toRegFieldRw(pllCtl.dcoarse, "pll_dcoarse"),
        toRegFieldRw(pllCtl.d_kp, "pll_d_kp"),
        toRegFieldRw(pllCtl.d_ki, "pll_d_ki"),
        toRegFieldRw(pllCtl.d_clol, "pll_d_clol"),
        toRegFieldRw(pllCtl.d_ol_fcw, "pll_d_ol_fcw"),
        toRegFieldRw(pllCtl.d_accumulator_reset, "pll_d_accumulator_reset"),
        toRegFieldRw(pllCtl.vco_reset, "pll_vco_reset"),
        toRegFieldRw(pllCtl.digital_reset, "pll_digital_reset"),
        toRegFieldRw(testPllCtl.dref_low, "test_pll_dref_low"),
        toRegFieldRw(testPllCtl.dref_high, "test_pll_dref_high"),
        toRegFieldRw(testPllCtl.dcoarse, "test_pll_dcoarse"),
        toRegFieldRw(testPllCtl.d_kp, "test_pll_d_kp"),
        toRegFieldRw(testPllCtl.d_ki, "test_pll_d_ki"),
        toRegFieldRw(testPllCtl.d_clol, "test_pll_d_clol"),
        toRegFieldRw(testPllCtl.d_ol_fcw, "test_pll_d_ol_fcw"),
        toRegFieldRw(
          testPllCtl.d_accumulator_reset,
          "test_pll_d_accumulator_reset"
        ),
        toRegFieldRw(testPllCtl.vco_reset, "test_pll_vco_reset"),
        toRegFieldRw(testPllCtl.digital_reset, "test_pll_digital_reset"),
        toRegFieldR(ShiftRegister(phy.io.pllOutput, 2, true.B), "pllOutput"),
        toRegFieldR(
          ShiftRegister(phy.io.testPllOutput, 2, true.B),
          "testPllOutput"
        ),
        toRegFieldRw(pllBypassEn, "pllBypassEn")
      ) ++ (0 until params.numLanes + 5).flatMap((i: Int) => {
        Seq(
          toRegFieldRw(txctl(i).dll_reset, s"dll_reset_$i"),
          toRegFieldRw(txctl(i).driver, s"txctl_${i}_driver"),
          toRegFieldRw(txctl(i).skew, s"txctl_${i}_skew")
        ) ++ (0 until 32).map((j: Int) =>
          toRegFieldRw(txctl(i).shuffler(j), s"txctl_${i}_shuffler_$j")
        ) ++ Seq(
          toRegFieldRw(txctl(i).sample_negedge, s"txctl_${i}_sample_negedge"),
          toRegFieldRw(txctl(i).delay, s"txctl_${i}_delay"),
          toRegFieldR(
            ShiftRegister(phy.io.dllCode(i), 2, true.B),
            s"dllCode_$i"
          )
        )
      }) ++ (0 until params.numLanes + 5).flatMap((i: Int) => {
        Seq(
          toRegFieldRw(rxctl(i).zen, s"zen_$i"),
          toRegFieldRw(rxctl(i).zctl, s"zctl_$i"),
          toRegFieldRw(rxctl(i).vref_sel, s"vref_sel_$i"),
          toRegFieldRw(rxctl(i).afeBypassEn, s"afeBypassEn_$i"),
          toRegFieldRw(rxctl(i).afeBypass, s"afeBypass_$i"),
          toRegFieldRw(rxctl(i).afeOpCycles, s"afeOpCycles_$i"),
          toRegFieldRw(rxctl(i).afeOverlapCycles, s"afeOverlapCycles_$i"),
          toRegFieldRw(rxctl(i).sample_negedge, s"sample_negedge_$i"),
          toRegFieldRw(rxctl(i).delay, s"rx_delay_$i")
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
        toRegFieldRw(commonTxctl.dll_reset, s"dll_reset"),
        toRegFieldRw(commonTxctl.driver, s"commonTxctl_driver"),
        toRegFieldRw(commonTxctl.skew, s"commonTxctl_skew")
      ) ++ (0 until 32).map((j: Int) =>
        toRegFieldRw(commonTxctl.shuffler(j), s"commonTxctl_shuffler_$j")
      ) ++ Seq(
        toRegFieldRw(txValid, "txValid"),
        toRegFieldRw(rxLfsrValid, "rxLfsrValid")
      )

      node.regmap(mmioRegs.zipWithIndex.map({
        case (f, i) => {
          i * 8 -> Seq(f)
        }
      }): _*)
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
        ucie.clockNode := sbus.fixedClockNode
        pbus.coupleTo(s"uciephytest{$n}") {
          ucie.node := TLBuffer() := TLFragmenter(
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

class WithUcieTLSim
    extends Config((site, here, up) => { case UcieTLKey =>
      up(UcieTLKey, site).map(u => u.map(_.copy(sim = true)))
    })
