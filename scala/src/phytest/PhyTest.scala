package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._
import chisel3.util.random._
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}

import edu.berkeley.cs.uciedigital.phy._
import edu.berkeley.cs.uciedigital.phy.macros._
import edu.berkeley.cs.uciedigital.phy.macros.clocking._

/** What a band carries while PhyTest is the selected controller. The mainband
  * and the sideband have their own copy, so either can carry TileLink while the
  * other stays under test control.
  */
object BandMode extends ChiselEnum {

  /** PhyTest drives the band itself. On the mainband, `TxTestMode` picks
    * between the manual buffer and the LFSR; on the sideband, packets are
    * staged one at a time through `SidebandTestRegsIO`.
    */
  val manual = Value(0.U(1.W))

  /** TileLink frames drive the band, and PhyTest keeps off it entirely. */
  val tl = Value(1.U(1.W))
}

object DataMode extends ChiselEnum {
  // Send/receive finite number of bits.
  val finite = Value(0.U(1.W))
  // Send/receive infinite amount of data.
  // In manual test mode, repeats the sent data bits.
  // In LFSR mode, continues sending LFSR data indefinitely.
  val infinite = Value(1.U(1.W))
}

object TestTarget extends ChiselEnum {
  // Receive from mainband once valid lane goes high.
  val mainband = Value(0.U(1.W))
  // Receive from loopback receiver as soon as first one is received.
  val loopback = Value(1.U(1.W))
}

// TX test modes.
object TxTestMode extends ChiselEnum {
  // Data to send is provided manually via `txDataOffset` and `txDataChunkIn`.
  val manual = Value(0.U(1.W))
  // Data to send is derived from an LFSR.
  val lfsr = Value(1.U(1.W))
}

/** State of the TX test FSM. */
object TxTestState extends ChiselEnum {

  /** Awaiting configuration and start of transmission. */
  val idle = Value(0.U(2.W))

  /** Test is currently being run. */
  val run = Value(1.U(2.W))

  /** Test is complete. */
  val done = Value(2.U(2.W))
}

/** Control registers for PHY tester.
  *
  * @constructor
  *   create a new [[TestRegsIO]]
  * @param bufferDepthPerLane
  *   log2(# of bits stored per lane)
  * @param numLanes
  *   number of lanes
  * @param bitCounterWidth
  *   width of counters for TX bits sent and RX bits received.
  */
class PhyTestRegsIO(
    bufferDepthPerLane: Int = 10,
    numLanes: Int = 2,
    bitCounterWidth: Int = 64
) extends Bundle {
  // Every lane index here is a PHYSICAL lane, the same one `PhyRegsIO`'s
  // `txctl`/`rxctl` use, so the lane whose errors are counted here is the lane
  // whose AFE those registers tune.
  //
  // Every per-lane vector -- the pattern and capture SRAMs, the bit error
  // counters, and the LFSR seeds -- is `PhyTest.numTestLanes` long and ordered
  // the same way: data `0` to `numLanes - 1`, then valid, track, and loopback.
  //
  // Only valid is special. It carries a repeating framing waveform rather than
  // a pattern, so it is checked against `rxLfsrValid` instead of an LFSR and
  // its LFSR seed slot goes unused. Track is driven and scored exactly like a
  // data lane.
  // GENERAL CONTROL
  // =====================
  /** The test setup being targeted. */
  val testTarget = Input(TestTarget())
  val divResetb = Input(AsyncReset())

  /** What drives the mainband. In `tl` the mainband TX/RX FSMs below are held
    * in reset and PhyTest stops driving the lanes.
    */
  val mainbandMode = Input(BandMode())

  /** What drives the sideband. In `tl` the sideband tester is held in reset. */
  val sidebandMode = Input(BandMode())

  // TX CONTROL
  // =====================
  // The test mode of the TX.
  val txTestMode = Input(TxTestMode())
  // The data mode of the TX.
  val txDataMode = Input(DataMode())
  // Seed of the TX LFSR, one per lane. The valid lane's slot is unused.
  val txLfsrSeed =
    Input(Vec(PhyTest.numTestLanes(numLanes), UInt((2 * Phy.SerdesRatio).W)))
  // Resets the TX FSM (i.e. resetting the number of bits sent to 0, reseeding the LFSR,
  // and stopping any in-progress transmissions).
  val txRst = Input(Bool())
  // Starts a transmission starting from the beginning of the input buffer (`TxTestMode.manual`) or from
  // the current state of the LFSR (`TxTestMode.lsfr`). Does not do anything if a transmission is in progress.
  val txExecute = Input(Bool())
  // The number of packets of `Phy.SerdesRatio` bits sent since the last FSM reset.
  val txPacketsSent = Output(UInt(bitCounterWidth.W))
  // The number of 32-bit chunks per repeating period in TX transmission in manual mode.
  // Set to 0 to send the entire buffer. Numbers greater than the buffer length will send the entire buffer
  // in `TxTestMode.manual`.
  val txManualRepeatPeriod = Input(UInt((bufferDepthPerLane - 5 + 1).W))
  // The number of packets to send during transmission.
  val txPacketsToSend = Input(UInt(bitCounterWidth.W))
  // Clock P signal value.
  val txClkP = Input(UInt(32.W))
  // Clock P signal.
  val txClkN = Input(UInt(32.W))
  // Valid signal.
  val txValid = Input(UInt(32.W))
  // Physical lane the valid waveform goes out on, so that a broken dedicated
  // valid lane does not stop a test. Codes `0` to `numLanes - 1` pick a data
  // lane, `Phy.defaultValidLaneSel` (the reset value) the dedicated valid
  // lane, and `Phy.trackValidLaneSel` the track lane. The chosen lane sends
  // valid instead of its own pattern -- nothing is shuffled out of the way, so
  // that lane's data is not transmitted and its error count means nothing while
  // valid sits on it. The dedicated valid lane keeps sending valid regardless.
  val txValidLaneSel = Input(UInt(Phy.validLaneSelWidth(numLanes).W))
  // Data chunk lane group in input buffer. Each lane group consists of 4 adjacent lanes (e.g. 0, 1, 2, 3).
  // Lane numLanes is valid, numLanes + 1 is track, numLanes + 2 is loopback.
  val txDataLaneGroup = Input(UInt(log2Ceil((numLanes + 2) / 4 + 1).W))
  // Data chunk offset in input buffer.
  val txDataOffset = Input(UInt((bufferDepthPerLane - 5).W))
  // 128-bit data chunk to write (32 bits per lane).
  val txDataChunkIn = Flipped(DecoupledIO(UInt(128.W)))
  // Data chunk at the given chunk offset for inspecting the data to be sent. Only available in idle/done mode.
  val txDataChunkOut = Output(UInt(128.W))
  // State of the TX test FSM.
  val txTestState = Output(TxTestState())

  // RX CONTROL
  // ====================
  // The data mode of the RX.
  val rxDataMode = Input(DataMode())
  // Seed of the RX LFSR used for detecting bit errors, indexed like
  // `txLfsrSeed`. Should be the same as the TX seed of the transmitting chiplet.
  val rxLfsrSeed =
    Input(Vec(PhyTest.numTestLanes(numLanes), UInt((2 * Phy.SerdesRatio).W)))
  // Expected valid signal in LFSR mode.
  val rxLfsrValid = Input(UInt(32.W))
  // Physical lane the RX watches for the edge that starts recording. Coded like
  // `txValidLaneSel`, and selected independently of it: this die's
  // `rxValidLaneSel` has to match whatever the partner die transmits valid on.
  val rxValidLaneSel = Input(UInt(Phy.validLaneSelWidth(numLanes).W))
  // Resets the RX FSM (i.e. resetting the number of bits received and the offset within the output
  // buffer to 0).
  val rxRst = Input(Bool())
  // The number of packets received since the last FSM reset. Only the first 2^bufferDepthPerLane bits received
  // per lane are stored in the output buffer.
  val rxPacketsReceived = Output(UInt(bitCounterWidth.W))
  // The number of packets to receive.
  val rxPacketsToReceive = Input(UInt(bitCounterWidth.W))
  // The number of bit errors per lane since the last FSM reset, against the pattern
  // as framed by the valid edge the RX latched onto. Only applicable in
  // `TxTestMode.lsfr`.
  val rxBitErrors =
    Output(Vec(PhyTest.numTestLanes(numLanes), UInt(bitCounterWidth.W)))
  // The same counts against the pattern framed one UI earlier than the valid edge
  // indicated, and one UI later. A single bit error on the valid lane in the cycle
  // the RX aligns leaves every later comparison one UI out of step, which pins the
  // nominal count near half the bits received; the framing that reads clean is then
  // the real error count. A spuriously set valid bit aligns the RX one UI early, so
  // the pattern really started later (`rxBitErrorsLate`); a dropped valid bit aligns
  // it one UI late, so the pattern really started earlier (`rxBitErrorsEarly`).
  val rxBitErrorsEarly =
    Output(Vec(PhyTest.numTestLanes(numLanes), UInt(bitCounterWidth.W)))
  val rxBitErrorsLate =
    Output(Vec(PhyTest.numTestLanes(numLanes), UInt(bitCounterWidth.W)))
  // Pause the `rxPacketsReceived`, `rxBitErrors`, and `rxSignature` outputs to read
  // them atomically.
  val rxPauseCounters = Input(Bool())
  // A MISR derived from the packets received since the last FSM reset. Unlike
  // `rxBitErrors`, this works for any TX test mode: compare it against
  // `PhyTest.signatureNext` folded over the expected stream to check patterns the
  // RX has no LFSR for (e.g. `TxTestMode.manual`), over runs far longer than the
  // RX capture SRAM. Covers exactly the packets counted by `rxPacketsReceived`, so
  // read the two under `rxPauseCounters`.
  val rxSignature = Output(UInt(32.W))
  // Data chunk lane in output buffer.
  val rxDataLane = Input(UInt(log2Ceil(PhyTest.numTestLanes(numLanes)).W))
  // Data chunk offset in output buffer.
  val rxDataOffset = Input(UInt((bufferDepthPerLane - 5).W))
  // Data chunk at the given chunk offset for inspect the received data.
  val rxDataChunk = Output(UInt(32.W))

  // DEBUG CIRCUITRY CONTROL
  // ===========================
  // Pad driver control for the observation bumps, in `DebugBumpsIO` order:
  // `txClk`, `rxClk`, `rxData`, `clkMux`. The TX data debug lane brings its own
  // driver and takes its control from `txctl` instead.
  val driverctl = Input(Vec(PhyTest.NumDebugDrivers, new DriverCtlIO))
  // Which clock the `clkMux` bump watches, as an index into the mux input list
  // in [[PhyTest]]. Zero selects the sideband forwarded clock and one the TX
  // global divided clock; the rest are not wired up yet.
  val clkMuxSel = Input(UInt(ClkMux.selWidth.W))
  // Which RX lane, and which bit of that lane's deserialized word, the `rxData`
  // bump watches. Lanes are ordered as in `RxIO`: `numLanes` data lanes, then
  // valid, then track. The bit is post-shuffler, i.e. in digital word order
  // rather than the order the deserializer produced.
  val rxDebugLane = Input(UInt(log2Ceil(numLanes + 2).W))
  val rxDebugBit = Input(UInt(log2Ceil(Phy.SerdesRatio).W))
  val txctl = Input(new TxLaneDigitalCtlIO)
  val txDebugTestMode = Input(TxTestMode())
  val txDebugDataMode = Input(DataMode())
  val txDebugLfsrSeed = Input(UInt(64.W))
  val txDebugFsmRst = Input(Bool())
  val txDebugExecute = Input(Bool())
  val txDebugManualRepeatPeriod = Input(UInt(6.W))
  val txDebugPacketsToSend = Input(UInt(bitCounterWidth.W))
  val txDebugData = Input(Vec(16, UInt(64.W)))
  val txDebugState = Output(TxTestState())
  val txDebugPacketsEnqueued = Output(UInt(bitCounterWidth.W))
  val txDebugDllCode = Output(UInt(5.W))

  // LOOPBACK LANE CONTROL
  // ===========================
  // The loopback TX lane drives the loopback RX lane on chip, so the pair takes
  // the same per-lane control as a mainband lane but never reaches a bump.
  val loopbackTxctl = Input(new TxLaneDigitalCtlIO)
  val loopbackRxctl = Input(new RxLaneDigitalCtlIO)
  val loopbackDllCode = Output(UInt(5.W))

  // SIDEBAND CONTROL
  // ===========================
  val sb = new SidebandTestRegsIO
}

/** Bumps the tester drives for observation.
  *
  * The PHY only fans out the nets being watched; the muxing and the pad drivers
  * that put them here all live in [[PhyTest]].
  */
class DebugBumpsIO extends Bundle {
  val txClk = Output(Bool())
  val rxClk = Output(Bool())
  val rxData = Output(Bool())
  val clkMux = Output(Bool())
  val txData = Output(Bool())
}

class PhyTestIO(
    bufferDepthPerLane: Int = 10,
    numLanes: Int = 2,
    bitCounterWidth: Int = 64
) extends Bundle {
  val regs = new PhyTestRegsIO(bufferDepthPerLane, numLanes, bitCounterWidth)

  // PHY INTERFACE
  // ====================
  val tx = new DecoupledIO(new TxIO(numLanes))
  val rx = Flipped(new DecoupledIO(new RxIO(numLanes)))
  val sb = Flipped(new SbIO)
  val debug = Flipped(new PhyDebugIO(numLanes))
  val divResetb = Output(AsyncReset())
  val txResetb = Output(AsyncReset())
  val rxResetb = Output(AsyncReset())

  // BUMP INTERFACE
  // ====================
  val bumps = new DebugBumpsIO
}

/** Signature (MISR) parameters shared by [[PhyTest]] and its software model. */
object PhyTest {

  /** Width of the TX/RX pattern LFSRs. */
  val LfsrWidth = 2 * Phy.SerdesRatio

  /** Lanes the tester works in, and the one order every per-lane vector in it
    * uses: the `numLanes` data lanes, then valid, track, and the loopback lane.
    * Data, valid, and track are the RX physical lanes; loopback is the tester's
    * own on-chip pair.
    */
  def numTestLanes(numLanes: Int): Int = numLanes + 3
  def validLane(numLanes: Int): Int = numLanes
  def trackLane(numLanes: Int): Int = numLanes + 1
  def loopbackLane(numLanes: Int): Int = numLanes + 2

  /** Pad drivers the tester owns for the observation bumps, one per bump in
    * `DebugBumpsIO` order except the TX data debug lane, which brings its own.
    */
  val NumDebugDrivers = 4

  /** Packets the TX data debug lane's manual pattern holds. The lane has no
    * SRAM behind it: the pattern comes straight out of `txDebugData`, which is
    * sixteen 64-bit registers, i.e. this many `Phy.SerdesRatio` bit packets.
    */
  val DebugBufferPackets = 32

  /** Maximal period taps for [[LfsrWidth]], in LFSR convention (indexed from
    * one).
    */
  val LfsrTaps: Set[Int] = LFSR.tapsMaxPeriod.get(LfsrWidth).get.head
  // Running the feedback backwards to recover the bit that fell out of the state
  // only works if the state's oldest bit is a tap.
  require(
    LfsrTaps.max == LfsrWidth,
    s"LFSR taps $LfsrTaps must include $LfsrWidth"
  )

  /** Number of framings each lane is scored against: nominal, one UI early, one
    * UI late. See `rxBitErrorsEarly` in [[PhyTestRegsIO]].
    */
  val NumFramings = 3
  val NominalFraming = 0
  val EarlyFraming = 1
  val LateFraming = 2

  // A received packet is folded into the signature one lane word at a time, so
  // the signature is as wide as a packet.
  val SignatureWidth = Phy.SerdesRatio

  /** Maximal period tap points, in LFSR convention (indexed from one). */
  val SignatureTaps: Seq[Int] =
    LFSR.tapsMaxPeriod.get(SignatureWidth).get.head.toSeq.sorted

  private val SignatureMask = (BigInt(1) << SignatureWidth) - 1

  private def rotateLeft(word: BigInt, n: Int): BigInt = {
    val shift = ((n % SignatureWidth) + SignatureWidth) % SignatureWidth
    if (shift == 0) word & SignatureMask
    else
      (((word << shift) | ((word & SignatureMask) >> (SignatureWidth - shift))) &
        SignatureMask)
  }

  /** Software model of one signature update, matching the RTL bit for bit.
    *
    * `laneWords` holds the `numLanes + 3` words of a single received packet, in
    * the order the RX buffers them: data lanes `0 until numLanes`, then valid,
    * track, and loopback. Fold every packet the RX counts (in order) to get the
    * signature expected for a given `rxPacketsReceived`.
    */
  def signatureNext(signature: BigInt, laneWords: Seq[BigInt]): BigInt = {
    val folded = laneWords.zipWithIndex.foldLeft(signature & SignatureMask) {
      case (acc, (word, lane)) => acc ^ rotateLeft(word, lane)
    }
    val feedback = SignatureTaps.map(t => (folded >> (t - 1)) & 1).reduce(_ ^ _)
    ((folded << 1) & SignatureMask) | feedback
  }
}

class PhyTest(
    bufferDepthPerLane: Int = 10,
    numLanes: Int = 2,
    bitCounterWidth: Int = 64,
    sim: Boolean = false,
    queueParams: AsyncQueueParams = AsyncQueueParams(depth = 32)
)(implicit includeDefaultModels: Boolean = false)
    extends Module
    with RequireSyncReset {
  val io = IO(new PhyTestIO(bufferDepthPerLane, numLanes, bitCounterWidth))

  io.divResetb := io.regs.divResetb

  // The lane serdes resets follow the global divider reset, and each direction
  // can additionally be restarted on its own: `txRst` holds the serializers in
  // reset and `rxRst` the deserializers, for as long as the strobe lasts.
  val txSerdesResetb =
    (io.regs.divResetb.asBool && !io.regs.txRst).asAsyncReset
  val rxSerdesResetb =
    (io.regs.divResetb.asBool && !io.regs.rxRst).asAsyncReset
  io.txResetb := txSerdesResetb
  io.rxResetb := rxSerdesResetb

  // The two bands are independent: either can carry TileLink while the other
  // stays under test control.
  val mbManual = io.regs.mainbandMode === BandMode.manual
  val sbManual = io.regs.sidebandMode === BandMode.manual

  // Sideband tester: owns the sideband bumps end to end, independent of the
  // mainband TX/RX FSMs below. Disabled while TileLink owns the sideband, so it
  // neither drives the bumps nor assembles the TL frames it sees into nonsense
  // packets.
  val sbTest = Module(new SidebandTest)
  sbTest.io.regs <> io.regs.sb
  sbTest.io.en := sbManual
  io.sb <> sbTest.io.sb

  // General computations
  val maxBitCount = VecInit(Seq.fill(bitCounterWidth)(true.B)).asUInt
  val maxSramPackets = 1.U << (bufferDepthPerLane - 5).U;

  // OBSERVATION BUMPS
  //
  // The PHY hands over raw nets and nothing else; picking what to watch and
  // driving a pad with it happens here, so the PHY carries only link RTL.
  //
  // The `clkMux` bump watches whichever clock `clkMuxSel` indexes out of this
  // list. The cell takes `ClkMux.numInputs`, so there is room to bring more
  // clocks out here later; until then the rest of its inputs are tied off and
  // selecting one leaves every pass gate open. The `rxData` bump watches any
  // bit of any RX lane's deserialized word; those words sit in the RX divided
  // clock domain, but nothing here samples them, so the selects are the only
  // thing crossing and they are quasi-static configuration.
  val clkMuxIns = Seq(io.debug.sbTxClk, io.debug.txDivClk)
  val clkMux = Module(new ClkMux)
  val clkMuxOut = clkMux.connect(clkMuxIns, io.regs.clkMuxSel)
  val rxDebugData = io.debug.rxData(io.regs.rxDebugLane)(io.regs.rxDebugBit)

  for (
    (((name, din), bump), ctl) <- Seq(
      ("txclk_driver", io.debug.txClk.asBool),
      ("rxclk_driver", io.debug.rxClk.asBool),
      ("rxdata_driver", rxDebugData),
      ("clkmux_driver", clkMuxOut.asBool)
    ).zip(
      Seq(io.bumps.txClk, io.bumps.rxClk, io.bumps.rxData, io.bumps.clkMux)
    ).zip(io.regs.driverctl)
  ) {
    val driver = Module(new TxDriver)
    driver.suggestName(name)
    driver.io.din := din
    driver.io.ctl := ctl
    bump := driver.io.dout
  }

  // A TX lane that sits outside the PHY's lane clock distribution network: the
  // async queue onto the lane's own divided clock, the bit shuffler every TX
  // lane has, and the lane itself. The tester owns two of these -- the TX data
  // debug lane and the loopback transmitter -- and both run off the same
  // full-rate TX clock the PHY's own lanes do.
  //
  // Returns the enqueue side of the queue, in this module's clock domain, and
  // the lane.
  def txTestLane(
      name: String,
      ctl: TxLaneDigitalCtlIO
  ): (DecoupledIO[UInt], TxLane) = {
    val lane = Module(new TxLane)
    lane.suggestName(name)
    lane.io.dll_reset := ctl.dll_reset
    lane.io.dll_resetb := !ctl.dll_reset
    lane.io.ser_resetb := txSerdesResetb
    lane.io.clk := io.debug.txClk
    lane.io.ctl.driver := ctl.driver
    lane.io.ctl.skew := ctl.skew

    val divRstSync = Module(new RstSync)
    divRstSync.suggestName(s"${name}_div_rst_sync")
    divRstSync.io.rstbAsync := !reset.asBool
    divRstSync.io.clk := lane.io.divclk

    val fifo = Module(new AsyncQueue(UInt(Phy.SerdesRatio.W), queueParams))
    fifo.suggestName(s"${name}_fifo")
    fifo.io.enq_clock := clock
    fifo.io.enq_reset := reset
    fifo.io.deq_clock := lane.io.divclk
    fifo.io.deq_reset := !divRstSync.io.rstbSync
    fifo.io.deq.ready := true.B

    val shuffler = Module(new Shuffler(Phy.SerdesRatio))
    shuffler.suggestName(s"${name}_shuffler")
    // An empty queue sends zeros rather than repeating the last word.
    shuffler.io.din := Mux(fifo.io.deq.valid, fifo.io.deq.bits, 0.U)
    shuffler.io.permutation := ctl.shuffler
    lane.io.din := shuffler.io.dout

    (fifo.io.enq, lane)
  }

  // TX DATA DEBUG LANE
  //
  // One TX lane on a bump of its own, fed by a cut-down copy of the mainband TX
  // FSM below: the pattern is either the `txDebugData` registers or an LFSR,
  // and there is no capture SRAM behind it, so the manual buffer is the
  // registers themselves.
  val txDebugRst = io.regs.txDebugFsmRst || reset.asBool
  val txDebugState = withReset(txDebugRst) { RegInit(TxTestState.idle) }
  val txDebugPacketsEnqueued = withReset(txDebugRst) {
    RegInit(0.U(bitCounterWidth.W))
  }
  val txDebugAddr = withReset(txDebugRst) {
    RegInit(0.U(log2Ceil(PhyTest.DebugBufferPackets).W))
  }
  val txDebugLfsr = Module(
    new FibonacciLFSR(
      PhyTest.LfsrWidth,
      taps = PhyTest.LfsrTaps,
      step = Phy.SerdesRatio
    )
  )
  txDebugLfsr.io.seed.bits :=
    io.regs.txDebugLfsrSeed.asTypeOf(txDebugLfsr.io.seed.bits)
  txDebugLfsr.io.seed.valid := txDebugRst
  txDebugLfsr.io.increment := false.B

  // Each 64-bit register holds two packets, low half first.
  val txDebugWords = VecInit(
    io.regs.txDebugData.flatMap(word => Seq(word(31, 0), word(63, 32)))
  )
  val txDebugRepeatPeriod = Mux(
    io.regs.txDebugManualRepeatPeriod === 0.U ||
      io.regs.txDebugManualRepeatPeriod > PhyTest.DebugBufferPackets.U,
    PhyTest.DebugBufferPackets.U,
    io.regs.txDebugManualRepeatPeriod
  )

  val (txDebugEnq, txDebugLane) = txTestLane("txdebug", io.regs.txctl)
  io.bumps.txData := txDebugLane.io.dout
  io.regs.txDebugDllCode := txDebugLane.io.dll_code
  io.regs.txDebugState := txDebugState
  io.regs.txDebugPacketsEnqueued := txDebugPacketsEnqueued

  val txDebugValid = Wire(Bool())
  txDebugValid := false.B
  txDebugEnq.valid := txDebugValid
  txDebugEnq.bits := Mux(
    io.regs.txDebugTestMode === TxTestMode.manual,
    txDebugWords(txDebugAddr),
    Reverse(txDebugLfsr.io.out.asUInt)(Phy.SerdesRatio - 1, 0)
  )

  switch(txDebugState) {
    is(TxTestState.idle) {
      when(io.regs.txDebugExecute) {
        txDebugState := TxTestState.run
      }
    }
    is(TxTestState.run) {
      switch(io.regs.txDebugDataMode) {
        is(DataMode.finite) {
          txDebugValid := txDebugPacketsEnqueued < io.regs.txDebugPacketsToSend
        }
        is(DataMode.infinite) {
          txDebugValid := true.B
        }
      }
      when(txDebugValid && txDebugEnq.ready) {
        txDebugPacketsEnqueued := Mux(
          txDebugPacketsEnqueued < maxBitCount,
          txDebugPacketsEnqueued + 1.U,
          txDebugPacketsEnqueued
        )
        txDebugAddr := (txDebugAddr + 1.U) % txDebugRepeatPeriod
        when(io.regs.txDebugTestMode === TxTestMode.lfsr) {
          txDebugLfsr.io.increment := true.B
        }
      }
      when(!txDebugValid) {
        txDebugState := TxTestState.done
      }
    }
    is(TxTestState.done) {}
  }

  // LOOPBACK LANE
  //
  // A TX lane wired straight into an RX lane on chip, so the serializer, the
  // driver, the AFE, and the deserializer can all be exercised without a
  // partner die or even a bump. `TestTarget.loopback` points the TX and RX FSMs
  // below at this pair instead of the mainband, and the loopback lane has its
  // own slot in the pattern SRAMs and the capture SRAMs.
  val (txLoopbackEnq, txLoopbackLane) =
    txTestLane("txloopback", io.regs.loopbackTxctl)
  io.regs.loopbackDllCode := txLoopbackLane.io.dll_code

  val rxLoopbackLane = Module(new RxDataLane)
  rxLoopbackLane.suggestName("rxloopback")
  RxAfeCtl.connect(rxLoopbackLane.io.ctl, io.regs.loopbackRxctl)
  rxLoopbackLane.io.din := txLoopbackLane.io.dout
  // Sampled with the clock that shifted the data out, the way a mainband RX
  // lane is sampled with the clock the partner die's TX forwarded.
  rxLoopbackLane.io.clk := io.debug.txClk
  rxLoopbackLane.io.resetb := rxSerdesResetb

  val rxLoopbackShuffler = Module(new Shuffler(Phy.SerdesRatio))
  rxLoopbackShuffler.suggestName("rxloopback_shuffler")
  rxLoopbackShuffler.io.din := rxLoopbackLane.io.dout
  rxLoopbackShuffler.io.permutation := io.regs.loopbackRxctl.shuffler

  val rxLoopbackRstSync = Module(new RstSync)
  rxLoopbackRstSync.suggestName("rxloopback_div_rst_sync")
  rxLoopbackRstSync.io.rstbAsync := !reset.asBool
  rxLoopbackRstSync.io.clk := rxLoopbackLane.io.divclk.asClock

  val rxLoopbackFifo = Module(
    new AsyncQueue(UInt(Phy.SerdesRatio.W), queueParams)
  )
  rxLoopbackFifo.io.enq_clock := rxLoopbackLane.io.divclk.asClock
  rxLoopbackFifo.io.enq_reset := !rxLoopbackRstSync.io.rstbSync
  // The deserializer has no valid of its own: it hands over a word every
  // divided cycle whether or not the TX is sending.
  rxLoopbackFifo.io.enq.valid := true.B
  rxLoopbackFifo.io.enq.bits := rxLoopbackShuffler.io.dout
  rxLoopbackFifo.io.deq_clock := clock
  rxLoopbackFifo.io.deq_reset := reset

  // TX registers
  val txReset = io.regs.txRst || !mbManual || reset.asBool
  val txState = withReset(txReset) { RegInit(TxTestState.idle) }
  val txPacketsEnqueued = withReset(txReset) { RegInit(0.U(bitCounterWidth.W)) }
  val inputBufferAddrReg = withReset(txReset) {
    RegInit(0.U((bufferDepthPerLane - 5).W))
  }
  // One LFSR per lane, indexed like every other per-lane vector. The valid
  // lane's is never read -- valid carries a framing waveform, not a pattern --
  // so it optimizes away.
  val txLfsrs = (0 until PhyTest.numTestLanes(numLanes)).map((i: Int) => {
    val lfsr = Module(
      new FibonacciLFSR(
        PhyTest.LfsrWidth,
        taps = PhyTest.LfsrTaps,
        step = Phy.SerdesRatio
      )
    )
    lfsr.io.seed.bits := io.regs.txLfsrSeed(i).asTypeOf(lfsr.io.seed.bits)
    lfsr.io.seed.valid := txReset
    lfsr.io.increment := false.B
    lfsr
  })
  val loadedFirstChunk = withReset(txReset) { RegInit(false.B) }
  val txManualRepeatPeriod = Mux(
    io.regs.txManualRepeatPeriod === 0.U || io.regs.txManualRepeatPeriod > maxSramPackets,
    maxSramPackets,
    io.regs.txManualRepeatPeriod
  )

  // RX registers
  val rxReset = io.regs.rxRst || !mbManual || reset.asBool
  val rxPacketsReceived = withReset(rxReset) {
    RegInit(0.U((64 - log2Ceil(Phy.SerdesRatio)).W))
  }
  val rxReceiveOffset = withReset(rxReset) {
    RegInit(0.U(log2Ceil(Phy.SerdesRatio).W))
  }
  /// One count per framing per lane: numLanes data lanes, 1 valid lane, 1 loopback
  /// lane. See `rxBitErrorsEarly` in `PhyTestRegsIO` for what the framings mean.
  def perFramingPerLane(width: Int) = VecInit(
    Seq.fill(PhyTest.NumFramings)(
      VecInit(Seq.fill(PhyTest.numTestLanes(numLanes))(0.U(width.W)))
    )
  )
  val rxBitErrors = withReset(rxReset) { RegInit(perFramingPerLane(64)) }
  val rxPacketsReceivedOutput = withReset(rxReset) { RegInit(0.U(64.W)) }
  val rxErrorMask = withReset(rxReset) {
    RegInit(perFramingPerLane(Phy.SerdesRatio))
  }
  val rxBitErrorsOutput = withReset(rxReset) { RegInit(perFramingPerLane(64)) }
  val rxLfsrs = (0 until PhyTest.numTestLanes(numLanes)).map((i: Int) => {
    val lfsr = Module(
      new FibonacciLFSR(
        PhyTest.LfsrWidth,
        taps = PhyTest.LfsrTaps,
        step = Phy.SerdesRatio
      )
    )
    lfsr.io.seed.bits := io.regs.rxLfsrSeed(i).asTypeOf(lfsr.io.seed.bits)
    lfsr.io.seed.valid := rxReset
    lfsr.io.increment := false.B
    lfsr
  })

  // The `Phy.SerdesRatio` pattern bits a packet is compared against, oldest bit
  // first, in the same order the TX shifts them out of its own LFSR.
  def refWord(state: UInt): UInt =
    Reverse(state(PhyTest.LfsrWidth - 1, Phy.SerdesRatio))
  // The same window one UI further along the pattern, for a packet that was framed
  // one UI late.
  def refWordAdvanced(state: UInt): UInt =
    Reverse(state(PhyTest.LfsrWidth - 2, Phy.SerdesRatio - 1))
  // The same window one UI behind, for a packet that was framed one UI early. The
  // bit that has already fallen out of the state is recovered by running the
  // Fibonacci feedback backwards: it is the only unknown in the tap XOR that
  // produced the state's newest bit.
  def refWordDelayed(state: UInt): UInt = {
    val recovered = PhyTest.LfsrTaps.toSeq.sorted.init
      .map(t => state(t))
      .foldLeft(state(0))(_ ^ _)
    Cat(
      Reverse(state(PhyTest.LfsrWidth - 1, Phy.SerdesRatio + 1)),
      recovered
    )
  }
  // A valid word repeats every packet, so shifting its reference by a UI is just a
  // rotate.
  def validRefAdvanced(word: UInt): UInt =
    Cat(word(0), word(Phy.SerdesRatio - 1, 1))
  def validRefDelayed(word: UInt): UInt =
    Cat(word(Phy.SerdesRatio - 2, 0), word(Phy.SerdesRatio - 1))

  val rxSignature = withReset(rxReset) {
    RegInit(0.U(PhyTest.SignatureWidth.W))
  }
  val rxSignatureOutput = withReset(rxReset) {
    RegInit(0.U(PhyTest.SignatureWidth.W))
  }
  /// Lane words of the packet that completed this cycle, and whether that packet
  /// should be folded into the signature. Driven by the RX logic further below.
  val rxLaneWords = Wire(Vec(numLanes + 3, UInt(Phy.SerdesRatio.W)))
  for (lane <- 0 until numLanes + 3) {
    rxLaneWords(lane) := 0.U
  }
  val rxSignatureUpdate = Wire(Bool())
  rxSignatureUpdate := false.B

  // Fold every counted packet into a MISR: XOR all of its lane words into the
  // signature, then take one LFSR step. Unlike `rxBitErrors` this needs no model
  // of the transmitted pattern in hardware, so it checks any TX test mode over an
  // unbounded run length; the cost is that it is pass/fail rather than a bit error
  // count. Lane `l` is rotated left by `l` first so that identical failures on two
  // lanes carrying identical data (the common case for a manual pattern) cannot
  // cancel each other out in the XOR.
  // Kept in step with `PhyTest.signatureNext`, which models this in software.
  def rotateLeft(word: UInt, n: Int): UInt = {
    val shift = n % PhyTest.SignatureWidth
    if (shift == 0) word
    else
      Cat(
        word(PhyTest.SignatureWidth - 1 - shift, 0),
        word(PhyTest.SignatureWidth - 1, PhyTest.SignatureWidth - shift)
      )
  }
  val rxSignatureFolded = rxLaneWords.zipWithIndex
    .map { case (word, lane) => rotateLeft(word, lane) }
    .foldLeft(rxSignature)(_ ^ _)
  val rxSignatureFeedback =
    PhyTest.SignatureTaps.map(t => rxSignatureFolded(t - 1)).reduce(_ ^ _)
  when(rxSignatureUpdate) {
    rxSignature := Cat(
      rxSignatureFolded(PhyTest.SignatureWidth - 2, 0),
      rxSignatureFeedback
    )
  }

  val numSrams = (numLanes + 2) / 4 + 1
  val inputBuffer = (0 until numSrams).map(i =>
    SyncReadMem(1 << (bufferDepthPerLane - 5), UInt(128.W))
  )
  val inputBufferAddr = Wire(UInt((bufferDepthPerLane - 5).W))
  inputBufferAddr := io.regs.txDataOffset
  val inputRdPorts =
    (0 until numSrams).map(i => inputBuffer(i)(inputBufferAddr))
  val inputWrPorts =
    (0 until numSrams).map(i => inputBuffer(i)(io.regs.txDataOffset))
  val outputBuffer = (0 until numSrams).map(i =>
    SyncReadMem(1 << (bufferDepthPerLane - 5), UInt(128.W))
  )
  val outputBufferAddr = Wire(UInt(log2Ceil(1 << (bufferDepthPerLane - 5)).W))
  outputBufferAddr := rxPacketsReceived
  val toWrite = (0 until numSrams).map(i => {
    val wire = Wire(Vec(4, UInt(32.W)))
    for (i <- 0 until 4) {
      wire(i) := 0.U
    }
    wire
  })
  val shouldWrite = Wire(Bool())
  shouldWrite := false.B
  val outputBufferAddrDelayed = ShiftRegister(outputBufferAddr, 2, true.B)
  val toWriteDelayed = toWrite.map(w => ShiftRegister(w, 2, true.B))
  // Needs to default to false.
  val shouldWriteDelayed = ShiftRegister(shouldWrite, 2, false.B, true.B)
  val outputRdPorts =
    (0 until numSrams).map(i => outputBuffer(i)(io.regs.rxDataOffset))
  val outputWrPorts =
    (0 until numSrams).map(i => outputBuffer(i)(outputBufferAddrDelayed))
  when(shouldWriteDelayed) {
    for (i <- 0 until numSrams) {
      outputWrPorts(i) := toWriteDelayed(i).asTypeOf(outputWrPorts(i))
    }
  }

  io.regs.txPacketsSent := txPacketsEnqueued
  io.regs.txDataChunkIn.ready := txState === TxTestState.idle
  io.regs.txDataChunkOut := 0.U
  for (i <- 0 until numSrams) {
    when(i.U === io.regs.txDataLaneGroup) {
      io.regs.txDataChunkOut := inputRdPorts(i)
    }
  }
  io.regs.txTestState := txState
  io.regs.rxPacketsReceived := rxPacketsReceivedOutput
  io.regs.rxBitErrors := rxBitErrorsOutput(PhyTest.NominalFraming)
  io.regs.rxBitErrorsEarly := rxBitErrorsOutput(PhyTest.EarlyFraming)
  io.regs.rxBitErrorsLate := rxBitErrorsOutput(PhyTest.LateFraming)
  io.regs.rxSignature := rxSignatureOutput
  io.regs.rxDataChunk := 0.U
  for (i <- 0 until numSrams) {
    when(i.U === io.regs.rxDataLane >> 2.U) {
      io.regs.rxDataChunk := outputRdPorts(i).asTypeOf(Vec(4, UInt(32.W)))(
        io.regs.rxDataLane(1, 0)
      )
    }
  }

  for (lane <- 0 until numLanes) {
    io.tx.bits.data(lane) := 0.U
  }
  io.tx.bits.valid := 0.U
  io.tx.bits.track := 0.U
  // Needs to be true whenever PhyTest owns the mainband, so that clock and
  // track keep going out even when data isn't valid.
  io.tx.valid := mbManual

  // The forwarded-clock lanes carry a fixed pattern rather than a test one.
  io.tx.bits.clkp := io.regs.txClkP
  io.tx.bits.clkn := io.regs.txClkN

  // Unlike `io.tx.valid`, only true when data is valid.
  val tx_valid = Wire(Bool())
  tx_valid := false.B

  txLoopbackEnq.bits := 0.U
  // The loopback lane only carries traffic when it is the selected target, so
  // that a mainband run leaves it quiet.
  txLoopbackEnq.valid := tx_valid && io.regs.testTarget === TestTarget.loopback

  // Ready of whichever target the TX FSM is driving.
  val txTargetReady = Mux(
    io.regs.testTarget === TestTarget.mainband,
    io.tx.ready,
    txLoopbackEnq.ready
  )

  // TX logic
  switch(txState) {
    is(TxTestState.idle) {
      when(io.regs.txDataChunkIn.valid) {
        for (i <- 0 until numSrams) {
          when(i.U === io.regs.txDataLaneGroup) {
            inputWrPorts(i) := io.regs.txDataChunkIn.bits
          }
        }
      }

      when(io.regs.txExecute) {
        txState := TxTestState.run
      }
    }
    is(TxTestState.run) {
      switch(io.regs.txTestMode) {
        is(TxTestMode.manual) {
          // Need to load first chunk ahead of time so that we can constantly send data.
          when(loadedFirstChunk) {
            // Increment address when packet is enqueued.
            when(txTargetReady) {
              inputBufferAddr := (inputBufferAddrReg + 1.U) % txManualRepeatPeriod
            }.otherwise {
              inputBufferAddr := inputBufferAddrReg % txManualRepeatPeriod
            }
            // Only send the next packet if we still need to send more bits.
            switch(io.regs.txDataMode) {
              is(DataMode.finite) {
                tx_valid := txPacketsEnqueued < io.regs.txPacketsToSend
              }
              is(DataMode.infinite) {
                tx_valid := true.B
              }
            }
          }.otherwise {
            inputBufferAddr := 0.U
            loadedFirstChunk := true.B
          }
        }
        is(TxTestMode.lfsr) {
          switch(io.regs.txDataMode) {
            is(DataMode.finite) {
              tx_valid := txPacketsEnqueued < io.regs.txPacketsToSend
            }
            is(DataMode.infinite) {
              tx_valid := true.B
            }
          }
        }
      }
      when(tx_valid) {
        switch(io.regs.txTestMode) {
          is(TxTestMode.manual) {
            switch(io.regs.testTarget) {
              is(TestTarget.mainband) {
                // Data, valid, and track all come out of the pattern SRAM
                // the same way, each at its own lane index.
                def sramWord(lane: Int): UInt =
                  inputRdPorts(lane >> 2).asTypeOf(Vec(4, UInt(32.W)))(lane % 4)
                for (lane <- 0 until numLanes) {
                  io.tx.bits.data(lane) := sramWord(lane)
                }
                io.tx.bits.valid := sramWord(PhyTest.validLane(numLanes))
                io.tx.bits.track := sramWord(PhyTest.trackLane(numLanes))
              }
              is(TestTarget.loopback) {
                txLoopbackEnq.bits := inputRdPorts((numLanes + 2) >> 2)
                  .asTypeOf(Vec(4, UInt(32.W)))((numLanes + 2) % 4)
              }
            }
          }
          is(TxTestMode.lfsr) {
            switch(io.regs.testTarget) {
              is(TestTarget.mainband) {
                // Every pattern lane takes its own LFSR; valid takes the
                // framing waveform instead.
                def lfsrWord(lane: Int): UInt =
                  Reverse(txLfsrs(lane).io.out.asUInt)(31, 0)
                for (lane <- 0 until numLanes) {
                  io.tx.bits.data(lane) := lfsrWord(lane)
                }
                io.tx.bits.valid := io.regs.txValid
                io.tx.bits.track := lfsrWord(PhyTest.trackLane(numLanes))
              }
              is(TestTarget.loopback) {
                txLoopbackEnq.bits := Reverse(
                  txLfsrs(PhyTest.loopbackLane(numLanes)).io.out.asUInt
                )(31, 0)
              }
            }
          }
        }
      }

      when(tx_valid && txTargetReady) {
        txPacketsEnqueued := Mux(
          txPacketsEnqueued < VecInit(
            Seq.fill(txPacketsEnqueued.getWidth)(true.B)
          ).asUInt,
          txPacketsEnqueued + 1.U,
          txPacketsEnqueued
        )
        inputBufferAddrReg := (inputBufferAddrReg + 1.U) % txManualRepeatPeriod
        when(io.regs.txTestMode === TxTestMode.lfsr) {
          for (lane <- 0 until PhyTest.numTestLanes(numLanes)) {
            if (lane != PhyTest.validLane(numLanes)) {
              txLfsrs(lane).io.increment := true.B
            }
          }
        }
      }

      when(
        (io.regs.txTestMode === TxTestMode.lfsr || loadedFirstChunk) && !tx_valid
      ) {
        txState := TxTestState.done
      }
    }
    is(TxTestState.done) {}
  }

  // Valid goes out on whichever physical lane `txValidLaneSel` names, so a
  // broken dedicated valid lane does not stop a test. The chosen lane sends
  // valid in place of its own pattern; unlike a link that cannot afford to drop
  // a lane, the tester does not shuffle the displaced payload anywhere.
  for (lane <- 0 until numLanes) {
    when(io.regs.txValidLaneSel === lane.U) {
      io.tx.bits.data(lane) := io.tx.bits.valid
    }
  }
  when(io.regs.txValidLaneSel === Phy.trackValidLaneSel(numLanes).U) {
    io.tx.bits.track := io.tx.bits.valid
  }

  // RX logic

  io.rx.ready := true.B
  rxLoopbackFifo.io.deq.ready := true.B
  // The lane the RX frames on: recording starts at the first one seen on it.
  val rxValidLaneWord =
    Phy.rxLaneWords(io.rx.bits, numLanes)(io.regs.rxValidLaneSel)
  // The loopback receiver hands over a word every divided cycle whether or not
  // anything is being sent, so its lane reads zero unless it is the target.
  // That keeps it out of the capture SRAM and the signature during a mainband
  // run, where it would otherwise fold in whatever the idle lane picked up.
  val rxLoopbackData = Mux(
    io.regs.testTarget === TestTarget.loopback,
    rxLoopbackFifo.io.deq.bits,
    0.U
  )

  for (
    f <- 0 until PhyTest.NumFramings;
    i <- 0 until PhyTest.numTestLanes(numLanes)
  ) {
    val newRxBitErrors = rxBitErrors(f)(i) +& PopCount(rxErrorMask(f)(i))
    rxBitErrors(f)(i) := Mux(
      newRxBitErrors > maxBitCount,
      maxBitCount,
      newRxBitErrors
    )
  }
  when(io.regs.rxPauseCounters) {
    rxPacketsReceivedOutput := rxPacketsReceivedOutput
    rxBitErrorsOutput := rxBitErrorsOutput
    rxSignatureOutput := rxSignatureOutput
  }.otherwise {
    rxPacketsReceivedOutput := RegNext(rxPacketsReceived)
    rxBitErrorsOutput := rxBitErrors
    // Same pipelining as `rxPacketsReceived` so that a paused signature always
    // covers exactly the number of packets reported alongside it.
    rxSignatureOutput := RegNext(rxSignature)
  }

  // Dumb RX logic (starts recording as soon as valid goes high and never stops)
  val recordingStarted = withReset(rxReset) { RegInit(false.B) }
  val startRecording = Wire(Bool())
  val startIdx = Wire(UInt(log2Ceil(Phy.SerdesRatio).W))
  startRecording := false.B
  startIdx := 0.U

  // numLanes data lanes, 1 valid lane, 1 track lane, 1 loopback lane.
  val runningData = withReset(rxReset) {
    RegInit(VecInit(Seq.fill(numLanes + 3)(0.U(32.W))))
  }

  for (
    f <- 0 until PhyTest.NumFramings;
    i <- 0 until PhyTest.numTestLanes(numLanes)
  ) {
    rxErrorMask(f)(i) := 0.U
  }

  // Check valid streak after each packet is dequeued.
  when(
    Mux(
      io.regs.testTarget === TestTarget.mainband,
      io.rx.ready & io.rx.valid,
      rxLoopbackFifo.io.deq.ready & rxLoopbackFifo.io.deq.valid
    )
  ) {

    // Find correct start index if recording hasn't started already.
    for (i <- Phy.SerdesRatio - 1 to 0 by -1) {
      val shouldStartRecording = Wire(Bool())
      shouldStartRecording := false.B
      switch(io.regs.testTarget) {
        is(TestTarget.mainband) {
          shouldStartRecording := rxValidLaneWord(i)
        }
        is(TestTarget.loopback) {
          shouldStartRecording := rxLoopbackFifo.io.deq.bits(i)
        }
      }
      when(!recordingStarted && shouldStartRecording) {
        startRecording := true.B
        startIdx := i.U
        rxReceiveOffset := Phy.SerdesRatio.U - i.U
      }
    }

    recordingStarted := recordingStarted || startRecording

    when(!recordingStarted && !startRecording) {
      // Store latest data at the beginning of the `runningData` register.
      for (lane <- 0 until numLanes + 3) {
        if (lane < numLanes) {
          runningData(lane) := io.rx.bits.data(lane)
        } else if (lane == PhyTest.validLane(numLanes)) {
          runningData(lane) := io.rx.bits.valid
        } else if (lane == PhyTest.trackLane(numLanes)) {
          runningData(lane) := io.rx.bits.track
        } else {
          runningData(lane) := rxLoopbackData
        }
      }
    }.otherwise {
      val fullPacketReceived =
        rxReceiveOffset +& Phy.SerdesRatio.U - startIdx >= 32.U
      val shouldProcessPacket =
        fullPacketReceived && (io.regs.rxDataMode === DataMode.infinite || rxPacketsReceived < io.regs.rxPacketsToReceive)
      shouldWrite := rxPacketsReceived < maxSramPackets && fullPacketReceived
      // Every packet counted by `rxPacketsReceived` goes into the signature,
      // including the ones past the end of the capture SRAM.
      rxSignatureUpdate := shouldProcessPacket
      val dataMask = Wire(UInt(64.W))
      dataMask := ((1.U << (Phy.SerdesRatio.U - startIdx)) - 1.U) << rxReceiveOffset
      val keepMask = Wire(UInt(64.W))
      keepMask := ~dataMask
      when(shouldProcessPacket) {
        rxPacketsReceived := Mux(
          rxPacketsReceived < VecInit(
            Seq.fill(rxPacketsReceived.getWidth)(true.B)
          ).asUInt,
          rxPacketsReceived + 1.U,
          rxPacketsReceived
        )
      }
      for (lane <- 0 until numLanes + 3) {
        val rawData = if (lane < numLanes) {
          io.rx.bits.data(lane)
        } else if (lane == PhyTest.validLane(numLanes)) {
          io.rx.bits.valid
        } else if (lane == PhyTest.trackLane(numLanes)) {
          io.rx.bits.track
        } else {
          rxLoopbackData
        }
        val data = Wire(UInt(64.W))
        data := (rawData << rxReceiveOffset) >> startIdx
        val newData = Wire(UInt(64.W))
        newData := (data & dataMask) | (runningData(lane) & keepMask)
        runningData(lane) := newData(31, 0)
        when(fullPacketReceived) {
          runningData(lane) := newData >> 32.U
        }
        when(shouldWrite) {
          toWrite(lane >> 2)(lane % 4) := newData(31, 0)
        }
        rxLaneWords(lane) := newData(31, 0)

        when(shouldProcessPacket) {
          // Score the packet against the reference at all three framings so
          // that a valid bit error at alignment time does not invalidate the
          // run.
          // Compares a lane against the framing waveform rather than against
          // a pattern.
          def scoreAgainstValid(errIdx: Int): Unit = {
            rxErrorMask(PhyTest.NominalFraming)(errIdx) :=
              newData(31, 0) ^ io.regs.rxLfsrValid
            rxErrorMask(PhyTest.EarlyFraming)(errIdx) :=
              newData(31, 0) ^ validRefAdvanced(io.regs.rxLfsrValid)
            rxErrorMask(PhyTest.LateFraming)(errIdx) :=
              newData(31, 0) ^ validRefDelayed(io.regs.rxLfsrValid)
          }
          if (lane == PhyTest.validLane(numLanes)) {
            // The dedicated valid lane always carries the framing waveform,
            // whether or not the partner also moved it onto another lane.
            scoreAgainstValid(lane)
          } else {
            // Data, track, and loopback carry a pattern and are scored against
            // the LFSR at their own lane index -- unless the partner moved the
            // framing waveform onto this lane, in which case it carries that
            // instead and has to be scored against it. The LFSR still advances
            // either way, so the references stay in step across lanes and a
            // lane means the same thing before and after a select change.
            rxLfsrs(lane).io.increment := true.B
            val state = rxLfsrs(lane).io.out.asUInt
            val carriesValid =
              if (lane <= PhyTest.trackLane(numLanes)) {
                io.regs.rxValidLaneSel === lane.U
              } else {
                false.B
              }
            when(carriesValid) {
              scoreAgainstValid(lane)
            }.otherwise {
              rxErrorMask(PhyTest.NominalFraming)(lane) :=
                newData(31, 0) ^ refWord(state)
              rxErrorMask(PhyTest.EarlyFraming)(lane) :=
                newData(31, 0) ^ refWordAdvanced(state)
              rxErrorMask(PhyTest.LateFraming)(lane) :=
                newData(31, 0) ^ refWordDelayed(state)
            }
          }
        }
      }
    }
  }

}
