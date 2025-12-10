package edu.berkeley.cs.uciedigital.phy

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}

object Phy {
  val SerdesRatio = 32
  val QueueParams = AsyncQueueParams(depth = 32)
}

class TxIO(numLanes: Int = 16) extends Bundle {
  val data = Vec(numLanes, Bits(Phy.SerdesRatio.W))
  val valid = Bits(Phy.SerdesRatio.W)
  val clkp = Bits(Phy.SerdesRatio.W)
  val clkn = Bits(Phy.SerdesRatio.W)
  val track = Bits(Phy.SerdesRatio.W)
}

class RxIO(numLanes: Int = 16) extends Bundle {
  val data = Vec(numLanes, Bits(Phy.SerdesRatio.W))
  val valid = Bits(Phy.SerdesRatio.W)
  val track = Bits(Phy.SerdesRatio.W)
}

class PhyBumpsIO(numLanes: Int = 16) extends Bundle {
  val txData = Output(Vec(numLanes, Bool()))
  val txValid = Output(Bool())
  val txTrack = Output(Bool())
  val txClkP = Output(Clock())
  val txClkN = Output(Clock())
  val rxData = Input(Vec(numLanes, Bool()))
  val rxValid = Input(Bool())
  val rxTrack = Input(Bool())
  val rxClkP = Input(Clock())
  val rxClkN = Input(Clock())
  val sbTxClk = Output(Clock())
  val sbTxData = Output(Bool())
  val sbRxClk = Input(Clock())
  val sbRxData = Input(Bool())
  val refClkP = Input(Bool())
  val refClkN = Input(Bool())
  val bypassClkP = Input(Bool())
  val bypassClkN = Input(Bool())
  val pllRdacVref = Input(Bool())
}

class PhyToDigitalIO(numLanes: Int = 16) extends Bundle {
  val tx = Input(new TxIO(numLanes))
  val rx = Output(new RxIO(numLanes))
  val sbTxClk = Input(Bool())
  val sbTxData = Input(Bool())
  val sbRxClk = Output(Bool())
  val sbRxData = Output(Bool())
}

class ClkRxIO extends Bundle {
  val vip = Input(Bool())
  val vin = Input(Bool())
  val vop = Output(Bool())
  val von = Output(Bool())
}

class ClkRx(sim: Boolean = false) extends BlackBox with HasBlackBoxInline {
  val io = IO(new ClkRxIO)

  override val desiredName = "clkrx_with_esd"

  if (sim) {
    setInline(
      "clkrx_with_esd.v",
      """module clkrx_with_esd(
      | input vip, vin,
      | output vop, von
      |);
      | assign vop = vip;
      | assign von = vin;
      |endmodule
      """.stripMargin
    )
  }
}

class ClkMuxIO extends Bundle {
  val in0 = Input(Bool())
  val in1 = Input(Bool())
  val mux0_en_0 = Input(Bool())
  val mux0_en_1 = Input(Bool())
  val mux1_en_0 = Input(Bool())
  val mux1_en_1 = Input(Bool())
  val out = Output(Bool())
  val outb = Output(Bool())
}

class ClkMux(sim: Boolean = false) extends BlackBox with HasBlackBoxInline {
  val io = IO(new ClkMuxIO)

  override val desiredName = "ucie_clkmux"

  if (sim) {
    setInline(
      "ucie_clkmux.v",
      """module ucie_clkmux(
      | input in0, in1,
      | input mux0_en_0, mux0_en_1,
      | input mux1_en_0, mux1_en_1,
      | output out, outb
      |);
      | assign out = mux0_en_0 && ~mux0_en_1 ? in0 : (~mux0_en_0 && mux0_en_1 ? in1 : 1'b0);
      |endmodule
      """.stripMargin
    )
  }
}

class UcieRstSyncIO extends Bundle {
  val clk = Input(Bool())
  val rstbAsync = Input(Bool())
  val rstbSync = Output(Bool())
}

class UcieRstSync(sim: Boolean = true) extends BlackBox with HasBlackBoxInline {
  val io = IO(new UcieRstSyncIO)

  if (sim) {
    setInline(
      "UcieRstSync.v",
      """module UcieRstSync(
      | input clk,
      | input rstbAsync,
      | output rstbSync
      |);
      | reg [2:0] ff;
      | always @(negedge rstbAsync) begin
      |   ff <= 3'd0;
      | end
      | always @(posedge clk) begin
      |   ff[0] <= rstbAsync;
      |   ff[1] <= ff[0];
      |   ff[2] <= ff[1];
      | end
      | assign rstbSync = ff[2];
      |endmodule
      """.stripMargin
    )
  } else {
    setInline(
      "UcieRstSync.v",
      """module UcieRstSync(
      | input clk,
      | input rstbAsync,
      | output rstbSync
      |);
      | wire rstb_int0, rstb_int1, rstb_int2, rstb_int3, constant_one;
      | b15tihi00ah1n03x5 tie_1_cell(.o(constant_one));
      | b15fqn003ah1n04x5 ff0(.clk(clk), .d(constant_one), .o(rstb_int0), .rb(rstbAsync));
      | b15fqn003ah1n08x5 ff1(.clk(clk), .d(rstb_int0), .o(rstb_int1), .rb(rstbAsync));
      | b15fqn003ah1n16x5 ff2(.clk(clk), .d(rstb_int1), .o(rstb_int2), .rb(rstbAsync));
      | b15bfn000ah1n32x5 outputbuf0(.a(rstb_int2), .o(rstb_int3));
      | b15bfn000ah1n80a5 outputbuf1(.a(rstb_int3), .o(rstbSync));
      |endmodule
      """.stripMargin
    )
  }
}

class Esd(sim: Boolean = false) extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val term = Input(Bool())
  })

  override val desiredName = "ucie_esd"

  if (sim) {
    setInline(
      "ucie_esd.v",
      """module ucie_esd(
      | input term
      |);
      |endmodule
      """.stripMargin
    )
  }
}

class EsdRoutable(sim: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new Bundle {
    val term = Input(Bool())
  })

  override val desiredName = "ucie_esd_routable"

  if (sim) {
    setInline(
      "ucie_esd_routable.v",
      """module ucie_esd_routable(
      | input term
      |);
      |endmodule
      """.stripMargin
    )
  }
}

class Shuffler32 extends RawModule {
  val io = IO(new Bundle {
    val din = Input(UInt(32.W))
    val dout = Output(UInt(32.W))
    val permutation = Input(Vec(32, UInt(5.W)))
  })

  io.dout := VecInit((0 until 32).map(i => io.din(io.permutation(i)))).asUInt
}

class TxLaneDigitalCtlIO extends Bundle {
  val dll_reset = Bool()
  val driver = new DriverControlIO
  val skew = new TxSkewControlIO
  val shuffler = Vec(32, UInt(5.W))
  val sample_negedge = Bool()
  val delay = UInt(7.W)
}

class RxLaneDigitalCtlIO extends Bundle {
  val zen = Bool()
  val zctl = UInt(5.W)
  val vref_sel = UInt(7.W)
  val afeBypass = new RxAfeIO
  val afeBypassEn = Bool()
  val afeOpCycles = UInt(16.W)
  val afeOverlapCycles = UInt(16.W)
  val sample_negedge = Bool()
  val delay = UInt(7.W)
}

class PhyIO(numLanes: Int = 16) extends Bundle {
  // TX CONTROL
  // Lane control (`numLanes` data lanes, 1 valid lane, 2 clock lanes, 1 track lane, 1 loopback lane).
  val txctl = Input(Vec(numLanes + 5, new TxLaneDigitalCtlIO))
  val dllCode = Output(Vec(numLanes + 5, UInt(5.W)))
  val pllCtl = Input(new UciePllCtlIO)
  val pllOutput = Output(new UciePllDebugOutIO)
  val testPllCtl = Input(new UciePllCtlIO)
  val testPllOutput = Output(new UciePllDebugOutIO)

  // RX CONTROL
  // Lane control (`numLanes` data lanes, 1 valid lane, 2 clock lanes, 1 track lane, 1 loopback lane).
  val rxctl = Input(Vec(numLanes + 5, new RxLaneDigitalCtlIO))

  // If 1, PHY uses bypass clk. If 0, PHY uses PLL clk.
  val pllBypassEn = Input(Bool())

  // DIGITAL INTERFACE
  // =====================
  // Interface with digital.
  val digital = new PhyToDigitalIO(numLanes)
  // Debug interface.
  val debug = new PhyDebugIO

  // TOP INTERFACE
  // =====================
  val top = new PhyBumpsIO(numLanes)
}

class Phy(numLanes: Int = 16, sim: Boolean = false) extends Module {
  val io = IO(new PhyIO(numLanes))

  // TODO do we need to set pu/pd ctl to 0 when driver en is low?

  // Set up sideband
  val sbTxClk = Module(new TxDriver(sim))
  sbTxClk.io.din := io.digital.sbTxClk
  io.top.sbTxClk := sbTxClk.io.dout.asClock
  sbTxClk.io.ctl.pu_ctl := 63.U
  sbTxClk.io.ctl.pd_ctl := 63.U
  sbTxClk.io.ctl.en := true.B
  sbTxClk.io.ctl.en_b := false.B
  val sbTxData = Module(new TxDriver(sim))
  sbTxData.io.din := io.digital.sbTxData
  io.top.sbTxData := sbTxData.io.dout
  sbTxData.io.ctl.pu_ctl := 63.U
  sbTxData.io.ctl.pd_ctl := 63.U
  sbTxData.io.ctl.en := true.B
  sbTxData.io.ctl.en_b := false.B
  val ESD_sbRxClk = Module(new EsdRoutable(sim))
  val ESD_sbRxData = Module(new EsdRoutable(sim))
  ESD_sbRxClk.io.term := io.top.sbRxClk.asBool
  ESD_sbRxData.io.term := io.top.sbRxData.asBool
  io.digital.sbRxClk := io.top.sbRxClk.asBool
  io.digital.sbRxData := io.top.sbRxData

  // Set up clocking
  val rxClkP = Module(new RxClkLane(sim))
  val rxClkPAfeCtl = Module(new RxAfeCtl())
  val rxClkPCtlWire = io.rxctl(numLanes + 1)
  rxClkP.io.clkin := io.top.rxClkP.asBool
  rxClkP.io.ctl.zen := rxClkPCtlWire.zen
  rxClkP.io.ctl.zctl := rxClkPCtlWire.zctl
  rxClkP.io.ctl.vref_sel := rxClkPCtlWire.vref_sel
  rxClkPAfeCtl.io.bypass := rxClkPCtlWire.afeBypassEn
  rxClkPAfeCtl.io.afeBypass := rxClkPCtlWire.afeBypass
  rxClkPAfeCtl.io.opCycles := rxClkPCtlWire.afeOpCycles
  rxClkPAfeCtl.io.overlapCycles := rxClkPCtlWire.afeOverlapCycles
  rxClkP.io.ctl.afe := rxClkPAfeCtl.io.afe
  val rxClkN = Module(new RxClkLane(sim))
  val rxClkNAfeCtl = Module(new RxAfeCtl())
  val rxClkNCtlWire = io.rxctl(numLanes + 2)
  rxClkN.io.clkin := io.top.rxClkN.asBool
  rxClkN.io.ctl.zen := rxClkNCtlWire.zen
  rxClkN.io.ctl.zctl := rxClkNCtlWire.zctl
  rxClkN.io.ctl.vref_sel := rxClkNCtlWire.vref_sel
  rxClkNAfeCtl.io.bypass := rxClkNCtlWire.afeBypassEn
  rxClkNAfeCtl.io.afeBypass := rxClkNCtlWire.afeBypass
  rxClkNAfeCtl.io.opCycles := rxClkNCtlWire.afeOpCycles
  rxClkNAfeCtl.io.overlapCycles := rxClkNCtlWire.afeOverlapCycles
  rxClkN.io.ctl.afe := rxClkNAfeCtl.io.afe

  val rxclkbuf0 = Module(new SingleEndedBuffer(sim))
  val rxclkbuf1 = Module(new SingleEndedBuffer(sim))
  val rxclkbuf2l = Module(new SingleEndedBuffer(sim))
  val rxclkbuf2r = Module(new SingleEndedBuffer(sim))
  val rxclkbuf3s =
    (0 until numLanes + 2).map(i => Module(new SingleEndedBuffer(sim)))
  rxclkbuf0.io.vin := rxClkP.io.clkout
  rxclkbuf1.io.vin := rxclkbuf0.io.vout
  rxclkbuf2l.io.vin := rxclkbuf1.io.vout
  rxclkbuf2r.io.vin := rxclkbuf1.io.vout
  for ((rxclkbuf3, i) <- rxclkbuf3s.zipWithIndex) {
    val rxclkbuf2 = if (i < 8) {
      rxclkbuf2r
    } else if (i < 16) {
      rxclkbuf2l
    } else {
      rxclkbuf2r
    }
    rxclkbuf3.io.vin := rxclkbuf2.io.vout
  }
  val rxClkPClkDiv = Module(new ClkDiv4(sim))
  rxClkPClkDiv.io.clk := rxClkP.io.clkout
  rxClkPClkDiv.io.resetb := !reset.asBool
  io.debug.rxClk := rxClkP.io.clkout
  io.debug.rxClkDivided := rxClkPClkDiv.io.clkout_2

  // TODO: Add refclkrx and buffers (need to add IO connections for all of these macros).

  val pll = Module(new UciePll(sim))
  pll.io.dref_low := io.pllCtl.dref_low
  pll.io.dref_high := io.pllCtl.dref_high
  pll.io.dcoarse := io.pllCtl.dcoarse
  pll.io.dvco_reset := io.pllCtl.vco_reset
  pll.io.dvco_resetn := !io.pllCtl.vco_reset
  pll.io.d_digital_reset := io.pllCtl.digital_reset
  pll.io.d_accumulator_reset := io.pllCtl.d_accumulator_reset
  pll.io.d_kp := io.pllCtl.d_kp
  pll.io.d_ki := io.pllCtl.d_ki
  pll.io.d_clol := io.pllCtl.d_clol
  pll.io.d_ol_fcw := io.pllCtl.d_ol_fcw
  io.pllOutput.d_fcw_debug := pll.io.d_fcw_debug
  io.pllOutput.d_sar_debug := pll.io.d_sar_debug

  val testPll = Module(new UciePll(sim))
  testPll.io.dref_low := io.testPllCtl.dref_low
  testPll.io.dref_high := io.testPllCtl.dref_high
  testPll.io.dcoarse := io.testPllCtl.dcoarse
  testPll.io.dvco_reset := io.testPllCtl.vco_reset
  testPll.io.dvco_resetn := !io.testPllCtl.vco_reset
  testPll.io.d_digital_reset := io.testPllCtl.digital_reset
  testPll.io.d_accumulator_reset := io.testPllCtl.d_accumulator_reset
  testPll.io.d_kp := io.testPllCtl.d_kp
  testPll.io.d_ki := io.testPllCtl.d_ki
  testPll.io.d_clol := io.testPllCtl.d_clol
  testPll.io.d_ol_fcw := io.testPllCtl.d_ol_fcw
  io.debug.testPllClkP := testPll.io.vp_out
  io.testPllOutput.d_fcw_debug := testPll.io.d_fcw_debug
  io.testPllOutput.d_sar_debug := testPll.io.d_sar_debug
  val testPllClkDiv = Module(new ClkDiv4(sim))
  testPllClkDiv.io.clk := testPll.io.vn_out
  testPllClkDiv.io.resetb := !reset.asBool
  io.debug.testPllClkN := testPllClkDiv.io.clkout_2

  val clkMuxP = Module(new ClkMux(sim))
  clkMuxP.io.in0 := pll.io.vp_out
  clkMuxP.io.mux0_en_0 := !io.pllBypassEn
  clkMuxP.io.mux0_en_1 := io.pllBypassEn
  clkMuxP.io.mux1_en_0 := false.B
  clkMuxP.io.mux1_en_1 := false.B
  val clkMuxN = Module(new ClkMux(sim))
  clkMuxN.io.in0 := pll.io.vn_out
  clkMuxN.io.mux0_en_0 := !io.pllBypassEn
  clkMuxN.io.mux0_en_1 := io.pllBypassEn
  clkMuxN.io.mux1_en_0 := false.B
  clkMuxN.io.mux1_en_1 := false.B

  val txclkbuf0 = Module(new DiffBuffer(sim))
  val txclkbuf1 = Module(new DiffBuffer(sim))
  val txclkbuf2 = Module(new DiffBuffer(sim))
  val txclkbuf3 = Module(new DiffBuffer(sim))
  val txclkbuf4 = Module(new DiffBufferN(10))
  val txclkbuf5ul = Module(new DiffBufferN(6))
  val txclkbuf5ur = Module(new DiffBufferN(4))
  val txclkbuf5ll = Module(new DiffBufferN(6))
  val txclkbuf5lr = Module(new DiffBufferN(4))
  txclkbuf0.io.vinp := clkMuxP.io.out
  txclkbuf0.io.vinn := clkMuxN.io.out
  txclkbuf1.io.vinp := txclkbuf0.io.voutp
  txclkbuf1.io.vinn := txclkbuf0.io.voutn
  txclkbuf2.io.vinp := txclkbuf1.io.voutp
  txclkbuf2.io.vinn := txclkbuf1.io.voutn
  txclkbuf3.io.vinp := txclkbuf2.io.voutp
  txclkbuf3.io.vinn := txclkbuf2.io.voutn
  txclkbuf4.io.vinp := txclkbuf3.io.voutp
  txclkbuf4.io.vinn := txclkbuf3.io.voutn
  txclkbuf5ul.io.vinp := txclkbuf4.io.voutp
  txclkbuf5ul.io.vinn := txclkbuf4.io.voutn
  txclkbuf5ur.io.vinp := txclkbuf4.io.voutp
  txclkbuf5ur.io.vinn := txclkbuf4.io.voutn
  txclkbuf5ll.io.vinp := txclkbuf4.io.voutp
  txclkbuf5ll.io.vinn := txclkbuf4.io.voutn
  txclkbuf5lr.io.vinp := txclkbuf4.io.voutp
  txclkbuf5lr.io.vinn := txclkbuf4.io.voutn
  io.debug.pllClkP := txclkbuf0.io.voutp
  io.debug.pllClkN := txclkbuf0.io.voutn

  val txClkDiv = Module(new ClkDiv4(sim))
  txClkDiv.io.clk := txclkbuf4.io.voutp
  txClkDiv.io.resetb := !reset.asBool
  val rstSyncTx = Module(new UcieRstSync(sim))
  rstSyncTx.io.rstbAsync := !reset.asBool
  rstSyncTx.io.clk := txClkDiv.io.clkout_3

  val rxClkDiv = Module(new ClkDiv4(sim))
  rxClkDiv.io.clk := rxclkbuf1.io.vout
  rxClkDiv.io.resetb := !reset.asBool
  val rstSyncRx = Module(new UcieRstSync(sim))
  rstSyncRx.io.rstbAsync := !reset.asBool
  rstSyncRx.io.clk := rxClkDiv.io.clkout_3

  // TODO: Hook up digital interface to shufflers
  // TODO: separate clock divider for async FIFO and its reset synchronizer
  for (lane <- 0 to numLanes + 3) {
    val shuffler = Module(new Shuffler32)
    shuffler.io.permutation := io.txctl(lane).shuffler

    val txLane = Module(new TxLane(sim));
    txLane.suggestName(if (lane < numLanes) {
      s"txdata$lane"
    } else if (lane == numLanes) {
      "txvalid"
    } else if (lane == numLanes + 1) {
      "txclkp"
    } else if (lane == numLanes + 2) {
      "txclkn"
    } else {
      "txtrack"
    });
    txLane.io.dll_reset := io.txctl(lane).dll_reset
    txLane.io.dll_resetb := !io.txctl(lane).dll_reset
    txLane.io.ser_resetb := !reset.asBool
    val clkbuf = if (lane < 4) {
      txclkbuf5ll
    } else if (lane < 8) {
      txclkbuf5ul
    } else if (lane < 12) {
      txclkbuf5ur
    } else if (lane < 16) {
      txclkbuf5lr
    } else if (lane == numLanes || lane == numLanes + 3) {
      txclkbuf5ll
    } else {
      txclkbuf5ul
    }
    txLane.io.clkp := clkbuf.io.voutp
    txLane.io.clkn := clkbuf.io.voutn
    val dinRegP =
      withClockAndReset(txClkDiv.io.clkout_3.asClock, !rstSyncTx.io.rstbSync) {
        ShiftRegister(
          shuffler.io.dout.asTypeOf(txLane.io.din),
          2,
          true.B
        )
      }
    val dinRegN = withClockAndReset(
      (!txClkDiv.io.clkout_3).asClock,
      !rstSyncTx.io.rstbSync
    ) {
      RegNext(dinRegP)
    }
    txLane.io.din := Mux(io.txctl(lane).sample_negedge, dinRegN, dinRegP)
    if (lane < numLanes) {
      io.top.txData(lane) := txLane.io.dout
    } else if (lane == numLanes) {
      io.top.txValid := txLane.io.dout
    } else if (lane == numLanes + 1) {
      io.top.txClkP := txLane.io.dout.asClock
    } else if (lane == numLanes + 2) {
      io.top.txClkN := txLane.io.dout.asClock
    } else {
      io.top.txTrack := txLane.io.dout
    }
    txLane.io.ctl.driver := io.txctl(lane).driver
    txLane.io.ctl.skew := io.txctl(lane).skew
    io.dllCode(lane) := txLane.io.dll_code
  }

  for (lane <- 0 until numLanes + 2) {
    val rxLane = Module(new RxDataLane(sim))
    val rxLaneAfeCtl = Module(new RxAfeCtl())
    val doutRegP =
      withClockAndReset(rxClkDiv.io.clkout_3.asClock, !rstSyncRx.io.rstbSync) {
        ShiftRegister(
          rxLane.io.dout,
          2,
          true.B
        )
      }
    val doutRegN = withClockAndReset(
      (!rxClkDiv.io.clkout_3).asClock,
      !rstSyncRx.io.rstbSync
    ) {
      RegNext(doutRegP)
    }
    val rxctlWire = io.rxctl(lane)
    // TODO: Hook up RX lane outputs and reduce latency.
    val doutEnq = Mux(rxctlWire.sample_negedge, doutRegN, doutRegP)
    if (lane < numLanes) {
      rxLane.suggestName(s"rxdata$lane")
      rxLane.io.din := io.top.rxData(lane)
    } else if (lane == numLanes) {
      rxLane.suggestName(s"rxvalid")
      rxLane.io.din := io.top.rxValid
    } else {
      rxLane.suggestName(s"rxtrack")
      rxLane.io.din := io.top.rxTrack
    }
    rxLane.io.ctl.zen := rxctlWire.zen
    rxLane.io.ctl.zctl := rxctlWire.zctl
    rxLane.io.ctl.vref_sel := rxctlWire.vref_sel
    rxLaneAfeCtl.io.bypass := rxctlWire.afeBypassEn
    rxLaneAfeCtl.io.afeBypass := rxctlWire.afeBypass
    rxLaneAfeCtl.io.opCycles := rxctlWire.afeOpCycles
    rxLaneAfeCtl.io.overlapCycles := rxctlWire.afeOverlapCycles
    rxLane.io.ctl.afe := rxLaneAfeCtl.io.afe
    rxLane.io.clk := rxclkbuf3s(lane).io.vout.asClock
    rxLane.io.resetb := !reset.asBool
  }

  // TODO: Move loopback to PhyTest
  // val txLoopbackFifo = Module(
  //   new AsyncQueue(UInt(Phy.SerdesRatio.W), Phy.QueueParams)
  // )
  // val loopbackShuffler = Module(new Shuffler32)
  // val txLoopbackLane = Module(new TxLane(sim))
  // val rstSyncTxLoopback = Module(new UcieRstSync(sim))
  // rstSyncTxLoopback.io.rstbAsync := !reset.asBool
  // rstSyncTxLoopback.io.clk := txLoopbackLane.io.divclk
  // txLoopbackFifo.io.enq <> io.test.tx_loopback
  // txLoopbackFifo.io.enq_clock := clock
  // txLoopbackFifo.io.enq_reset := reset
  // txLoopbackFifo.io.deq_clock := txLoopbackLane.io.divclk.asClock
  // txLoopbackFifo.io.deq_reset := !rstSyncTxLoopback.io.rstbSync.asBool
  // txLoopbackFifo.io.deq.ready := true.B

  // when(txLoopbackFifo.io.deq.valid) {
  //   loopbackShuffler.io.din := txLoopbackFifo.io.deq.bits
  // }.otherwise {
  //   loopbackShuffler.io.din := 0.U
  // }
  // loopbackShuffler.io.permutation := io.txctl(numLanes + 4).shuffler

  // txLoopbackLane.io.dll_reset := io.txctl(numLanes + 4).dll_reset
  // txLoopbackLane.io.dll_resetb := !io.txctl(numLanes + 4).dll_reset
  // txLoopbackLane.io.ser_resetb := !reset.asBool
  // txLoopbackLane.io.clkp := txclkbuf0.io.voutp
  // txLoopbackLane.io.clkn := txclkbuf0.io.voutn
  // txLoopbackLane.io.din := loopbackShuffler.io.dout.asTypeOf(
  //   txLoopbackLane.io.din
  // )
  // txLoopbackLane.io.ctl.driver := io.txctl(numLanes + 4).driver
  // txLoopbackLane.io.ctl.skew := io.txctl(numLanes + 4).skew
  // io.dllCode(numLanes + 4) := txLoopbackLane.io.dll_code

  // val rxLoopbackLane = Module(new RxDataLane(sim))
  // val rxLoopbackClkBuf = Module(new DiffBuffer(sim))
  // val rxLoopbackLaneAfeCtl = Module(new RxAfeCtl())
  // val rxLoopbackFifo = Module(
  //   new AsyncQueue(UInt(Phy.SerdesRatio.W), Phy.QueueParams)
  // )
  // val rstSyncRxLoopback = Module(new UcieRstSync(sim))
  // rstSyncRxLoopback.io.rstbAsync := !reset.asBool
  // rstSyncRxLoopback.io.clk := rxLoopbackLane.io.divclk
  // rxLoopbackFifo.io.enq.valid := true.B
  // rxLoopbackFifo.io.enq_reset := !rstSyncRx.io.rstbSync.asBool
  // rxLoopbackFifo.io.deq_clock := clock
  // rxLoopbackFifo.io.enq_clock := rxLoopbackLane.io.divclk.asClock
  // rxLoopbackFifo.io.deq_reset := reset
  // rxLoopbackFifo.io.deq <> io.test.rx_loopback
  // rxLoopbackLane.io.din := txLoopbackLane.io.dout
  // rxLoopbackFifo.io.enq.bits := rxLoopbackLane.io.dout
  // rxLoopbackLane.io.ctl.zen := io.rxctl(numLanes + 4).zen
  // rxLoopbackLane.io.ctl.zctl := io.rxctl(numLanes + 4).zctl
  // rxLoopbackLane.io.ctl.vref_sel := io.rxctl(numLanes + 4).vref_sel
  // rxLoopbackLaneAfeCtl.io.bypass := io.rxctl(numLanes + 4).afeBypassEn
  // rxLoopbackLaneAfeCtl.io.afeBypass := io.rxctl(numLanes + 4).afeBypass
  // rxLoopbackLaneAfeCtl.io.opCycles := io.rxctl(numLanes + 4).afeOpCycles
  // rxLoopbackLaneAfeCtl.io.overlapCycles := io
  //   .rxctl(numLanes + 4)
  //   .afeOverlapCycles
  // rxLoopbackLane.io.ctl.afe := rxLoopbackLaneAfeCtl.io.afe
  // rxLoopbackClkBuf.io.vinp := txclkbuf0.io.voutp
  // rxLoopbackClkBuf.io.vinn := txclkbuf0.io.voutn
  // rxLoopbackLane.io.clk := rxLoopbackClkBuf.io.voutp.asClock
  // rxLoopbackLane.io.resetb := !reset.asBool
}

class ClkDiv4IO extends Bundle {
  val clk = Input(Bool())
  val resetb = Input(Bool())
  val clkout_0 = Output(Bool())
  val clkout_1 = Output(Bool())
  val clkout_2 = Output(Bool())
  val clkout_3 = Output(Bool())
}

class ClkDiv4(sim: Boolean = false) extends BlackBox with HasBlackBoxInline {
  val io = IO(new ClkDiv4IO)

  override val desiredName = "clock_div_4_with_rst_sync"

  if (sim) {
    setInline(
      "clock_div_4_with_rst_sync.v",
      """module clock_div_4_with_rst_sync(
      |  input clk, resetb,
      |  output reg clkout_0, clkout_1, clkout_2, clkout_3
      |);
      |  always @(negedge resetb) begin
      |    clkout_0 <= 1'b0;
      |    clkout_1 <= 1'b0;
      |    clkout_2 <= 1'b0;
      |    clkout_3 <= 1'b0;
      |  end
      |  always @(posedge clk) begin
      |    if (resetb) begin
      |    	clkout_0 <= ~clkout_0;
      |    end
      |  end
      |  always @(posedge clkout_0) begin
      |    clkout_1 <= ~clkout_1;
      |  end
      |  always @(posedge clkout_1) begin
      |    clkout_2 <= ~clkout_2;
      |  end
      |  always @(posedge clkout_2) begin
      |    clkout_3 <= ~clkout_3;
      |  end
      |endmodule
      """.stripMargin
    )
  }
}

class DiffBufferIO extends Bundle {
  val vinp = Input(Bool())
  val vinn = Input(Bool())
  val voutp = Output(Bool())
  val voutn = Output(Bool())
}

class DiffBuffer(sim: Boolean = false) extends BlackBox with HasBlackBoxInline {
  val io = IO(new DiffBufferIO)

  override val desiredName = "ucie_diff_buffer"

  if (sim) {
    setInline(
      "ucie_diff_buffer.v",
      """module ucie_diff_buffer(
      |  input vinp, vinn,
      |  output voutp, voutn
      |);
      | assign voutp = vinp;
      | assign voutn = vinn;
      |endmodule
      """.stripMargin
    )
  }
}

// TODO: Fix that diff buffer must be instantiated somewhere else for this to work.
class DiffBufferN(n: Int) extends BlackBox with HasBlackBoxInline {
  val io = IO(new DiffBufferIO)

  override val desiredName = s"ucie_diff_buffer_$n"

  val body = new StringBuilder("")

  for (i <- 0 until n) {
    body ++= s" ucie_diff_buffer buf$i(.vinp(vinp), .vinn(vinn), .voutp(voutp), .voutn(voutn));\n"
  }
  setInline(
    s"ucie_diff_buffer_$n.v",
    s"""module ucie_diff_buffer_$n(
    |  input vinp, vinn,
    |  output voutp, voutn
    |);
    |${body}
    |endmodule
    """.stripMargin
  )
}

class BufferIO extends Bundle {
  val vin = Input(Bool())
  val vout = Output(Bool())
}

class SingleEndedBuffer(sim: Boolean = false)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new BufferIO)

  override val desiredName = "ucie_single_ended_buffer"

  if (sim) {
    setInline(
      "ucie_single_ended_buffer.v",
      """module ucie_single_ended_buffer(
      |  input vin,
      |  output vout
      |);
      | assign vout = vin;
      |endmodule
      """.stripMargin
    )
  }
}
