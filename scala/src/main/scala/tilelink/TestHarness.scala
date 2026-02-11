package edu.berkeley.cs.uciedigital.tilelink

import chisel3._

import firrtl.options.{StageError, StageUtils, OptionsException}
import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import edu.berkeley.cs.uciedigital.tilelink._

class TestHarness(implicit p: Parameters) extends LazyModule {
  val ucieParams = UcieTLParams()

  // val placeholder_node_ucie_regmap = TLClientNode(Seq(
  //   TLMasterPortParameters.v1(
  //     clients = Seq(TLMasterParameters.v1(
  //     name = "placeholder-node-ucie-regmap",
  //     sourceId = IdRange(0, testerParams.maxInflight)
  //   ))),
  // ))

  val placeholder_node_tl_regmap = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        clients = Seq(
          TLMasterParameters.v1(
            name = "placeholder-node-tl-regmap",
            sourceId = IdRange(0, 1)
          )
        )
      )
    )
  )

  // val tltester = LazyModule(new TileLinkTester)

  val ucieTL = LazyModule(new UcieTL(UcieTLParams(), 32))

  val clockSourceNode_digital = ClockSourceNode(
    Seq(ClockSourceParameters())
  ) // drive uciephy and ucietl clock nodes
  // val clockSourceNode_phy     = ClockSourceNode(Seq(ClockSourceParameters()))

  ucieTL.clockNode := clockSourceNode_digital
  ucieTL.node := placeholder_node_tl_regmap

  // ucie.uciTL.managerNode := tltester.node
  // mem.node := ucie.uciTL.clientNode

  // ucie.clockNode := clockSourceNode_digital
  // ucie.uciTL.clockNode := clockSourceNode_phy

  // ucie.node := placeholder_node_ucie_regmap
  // ucie.uciTL.regNode.node := placeholder_node_tl_regmap

  // val uciephyTopIO = BundleBridgeSink[uciephytest.UciephyTopIO]()
  // uciephyTopIO := ucie.topIO

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val system_clock = Input(new ClockBundle(ClockBundleParameters()))
      val done = Output(Bool())
    })

    // val (out_ucie, _) = placeholder_node_ucie_regmap.out(0)
    val (out_tl, _) = placeholder_node_tl_regmap.out(0)
    // out_ucie.tieoff()
    out_tl.tieoff()

    io.done := true.B

    // Loopback
    ucieTL.module.io.phy.rxData := ucieTL.module.io.phy.txData
    ucieTL.module.io.phy.rxValid := ucieTL.module.io.phy.txValid
    ucieTL.module.io.phy.rxTrack := ucieTL.module.io.phy.txTrack
    ucieTL.module.io.phy.rxClkP := ucieTL.module.io.phy.txClkP
    ucieTL.module.io.phy.rxClkN := ucieTL.module.io.phy.txClkN
    ucieTL.module.io.phy.sbRxClk := ucieTL.module.io.phy.sbTxClk
    ucieTL.module.io.phy.sbRxData := ucieTL.module.io.phy.sbTxData
    ucieTL.module.io.phy.refClkP := io.system_clock.clock
    ucieTL.module.io.phy.refClkN := (!io.system_clock.clock.asBool).asClock
    ucieTL.module.io.phy.bypassClkP := io.system_clock.clock
    ucieTL.module.io.phy.bypassClkN := (!io.system_clock.clock.asBool).asClock
    ucieTL.module.io.phy.pllRdacVref := 0.U

    clockSourceNode_digital.out(0)._1 <> io.system_clock
  }
}
