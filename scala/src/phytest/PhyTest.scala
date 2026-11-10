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

/** What starts the RX recording. */
object RxStartMode extends ChiselEnum {

  /** The first one seen on the lane `rxValidLaneSel` names. */
  val valid = Value(0.U(1.W))

  /** The first word the RX receives, framed on its own word boundary. For
    * looking at what a lane receives when nothing on the link can be trusted to
    * frame it.
    */
  val immediate = Value(1.U(1.W))
}

/** What the RX capture SRAM keeps. */
object CaptureMode extends ChiselEnum {

  /** The first `2^bufferDepthPerLane` bits per lane from packet
    * `rxCaptureStart` on.
    */
  val first = Value(0.U(1.W))

  /** A window around the first bit error. The SRAM is written circularly until
    * a scored lane reports an error, then for `rxCapturePostTrigger` more
    * packets, and then left alone.
    */
  val error = Value(1.U(1.W))
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
  // Resets the TX datapath: the serdes handoff registers and the PHY side of
  // the async queue, all of which live on the TX divided clock. Synchronized
  // to that clock in the PHY, so it applies and releases cleanly. Does not
  // touch the FSM, and does not stop a clock divider.
  val txDatapathRst = Input(Bool())
  // Resets the TX FSM (i.e. resetting the number of bits sent to 0, reseeding the LFSR,
  // and stopping any in-progress transmissions).
  val txFsmRst = Input(Bool())
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
  // Data chunk at the given chunk offset for inspecting the data to be sent. Only available in idle/done mode,
  // and with `rxManualCheck` clear.
  val txDataChunkOut = Output(UInt(128.W))
  // State of the TX test FSM.
  val txTestState = Output(TxTestState())
  // Bursts: with both nonzero, the TX sends `txBurstPackets` packets, then
  // `txIdlePackets` idle packets, and repeats until `txPacketsToSend` have
  // gone out. An idle packet carries zeros on valid and on every pattern lane,
  // and the pattern does not advance across it, so the packets that do go out
  // are the same stream a continuous run sends. Either one zero sends
  // continuously.
  //
  // The RX needs `rxBurstMode` to skip the idle packets.
  val txBurstPackets = Input(UInt(32.W))
  val txIdlePackets = Input(UInt(32.W))
  // Gates the forwarded clock. The clock lanes then carry `txClkP`/`txClkN`
  // only from `txClkPreamble` packets before a burst -- or before the first
  // packet, without bursts -- until `txClkPostamble` times
  // `PhyTest.ClkPostambleUi` UI after it, and zeros the rest of the time, as
  // the UCIe controller parks them. With no clock edges arriving, the
  // partner's RX stops handing over words, so its datapath reset cannot
  // release either; reset it with the clock running.
  //
  // A postamble that ends partway through a packet clocks only that packet's
  // first UIs. The partner's RX counts its word boundaries on those edges, so
  // across the gap they move by the postamble modulo 32 UI. PhyTest's RX
  // frames once per run, so it only scores the bursts after the first clean
  // when the postamble is a whole number of packets.
  val txClkGate = Input(Bool())
  val txClkPreamble = Input(UInt(16.W))
  val txClkPostamble = Input(UInt(16.W))
  // Sends `txTrackWord` on the track lane in every packet, whatever the test
  // mode, instead of track's own pattern: a clock, the way the UCIe
  // controller sends track, or any other fixed waveform. Valid moved onto
  // track by `txValidLaneSel` still takes precedence.
  val txTrackClk = Input(Bool())
  val txTrackWord = Input(UInt(32.W))
  // Flips bit 0 of the next packet sent on lane `txInjectLane`, indexed like
  // every other per-lane vector here. Takes effect on the first packet sent
  // after the strobe, so a strobe before `txExecute` hits the first packet.
  // Cleared by an FSM reset.
  val txInjectError = Input(Bool())
  val txInjectLane =
    Input(UInt(log2Ceil(PhyTest.numTestLanes(numLanes)).W))

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
  // Resets the RX datapath: the deserializer handoff registers and the PHY
  // side of the async queue, both on the RX divided clock. Synchronized to
  // that clock in the PHY. Does not touch the FSM or any clock divider.
  val rxDatapathRst = Input(Bool())
  // Resets the RX FSM (i.e. resetting the number of bits received and the offset within the output
  // buffer to 0).
  val rxFsmRst = Input(Bool())
  // The number of packets received since the last FSM reset. Only the first 2^bufferDepthPerLane bits received
  // per lane are stored in the output buffer.
  val rxPacketsReceived = Output(UInt(bitCounterWidth.W))
  // The number of packets to receive.
  val rxPacketsToReceive = Input(UInt(bitCounterWidth.W))
  // The number of bit errors per lane since the last FSM reset, against the pattern
  // as framed by the valid edge the RX latched onto. Only applicable in
  // `TxTestMode.lsfr`, or in `TxTestMode.manual` under `rxManualCheck`.
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
  // Idle words the RX has handled since the FSM reset, free running.
  //
  // Read it twice: a value that moves means the receive path is clocked and
  // words are arriving. Read against `rxPacketsReceived` it separates three
  // cases that otherwise all look like "bit errors everywhere" -- neither
  // advancing is a dead receive path, this advancing with no packets never saw
  // valid, both advancing is a working link.
  //
  // `run_lfsr` waits on it too: the datapath reset releases through a
  // synchronizer on the RX divided clock, so no fixed delay covers every rate.
  val rxIdleWordsObserved = Output(UInt(32.W))
  // Data chunk lane in output buffer.
  val rxDataLane = Input(UInt(log2Ceil(PhyTest.numTestLanes(numLanes)).W))
  // Data chunk offset in output buffer.
  val rxDataOffset = Input(UInt((bufferDepthPerLane - 5).W))
  // Data chunk at the given chunk offset for inspect the received data.
  val rxDataChunk = Output(UInt(32.W))
  // What starts recording.
  val rxStartMode = Input(RxStartMode())
  // Only process packets whose valid word, on the lane `rxValidLaneSel`
  // names, reads closer to `rxLfsrValid` than to zero; the rest are idle
  // packets between bursts and are not counted, scored, captured, or folded
  // into the signature. Needs a valid waveform with bit 0 set, so that the RX
  // frames on the TX's packet boundaries and no packet straddles a burst edge.
  // Ignored with `TestTarget.loopback`, which has no valid lane.
  val rxBurstMode = Input(Bool())
  // Scores each pattern lane against its own received history instead of
  // against `rxLfsrSeed`: every bit is checked against the XOR of the bits
  // the LFSR taps say produced it. A lane then needs no seed, and its count
  // does not depend on the valid edge or on any other lane, so lane to lane
  // skew of any size reads clean. While set, the nominal count of every lane
  // that carries an LFSR pattern reports this score instead; valid and the
  // early and late counts are scored as before. A `TxTestMode.manual` pattern
  // is not an LFSR stream and reads as errors.
  //
  // The first two packets fill the history and are not scored, and a single
  // bit error is counted `1 + PhyTest.LfsrTaps.size` times.
  val rxSelfSync = Input(Bool())
  // Scores against a `TxTestMode.manual` pattern instead of the RX LFSRs:
  // load the partner's pattern into this die's TX pattern SRAM through
  // `txDataChunkIn`, and its repeat period into `txManualRepeatPeriod`. The
  // `n`th packet processed is checked against row `n` modulo that period, so
  // the RX has to start on the partner's first packet, i.e. on valid. Each
  // lane's nominal count is against its own slot of the row, valid included.
  // Unlike against the LFSRs, that holds for a lane the partner moved valid
  // onto too, so load valid's waveform into that lane's slot. The early and
  // late counts stay at zero, and `rxSelfSync` is ignored.
  //
  // The RX reads the SRAM through the port the TX sends from, so this only
  // works while this die's TX is not sending a manual pattern; an LFSR run is
  // fine. It also takes `txDataChunkOut` over. The counts land a cycle later
  // than against the LFSRs, which `rxPauseCounters` and `CaptureMode.error`
  // both allow for.
  val rxManualCheck = Input(Bool())
  // Keeps the RX FSM running while TileLink drives the mainband, so the
  // capture SRAM and the signature see the TileLink traffic. The TX FSM is
  // still held.
  val rxMonitor = Input(Bool())
  // What the capture SRAM keeps.
  val rxCaptureMode = Input(CaptureMode())
  // The `rxPacketsReceived` index of the first packet the capture SRAM
  // records, in either mode. Packet `n` lands at entry `n - rxCaptureStart`
  // modulo the SRAM depth, so in `CaptureMode.first` entry 0 holds packet
  // `rxCaptureStart`. In `CaptureMode.error` an error in an earlier packet
  // does not trigger either. To start at bit `b` of the stream, start at
  // packet `b / 32` and drop the first `b % 32` bits.
  val rxCaptureStart = Input(UInt(32.W))
  // Packets captured after the trigger in `CaptureMode.error`.
  val rxCapturePostTrigger = Input(UInt((bufferDepthPerLane - 5 + 1).W))
  // Lanes whose nominal count can trigger `CaptureMode.error`, indexed like
  // every other per-lane vector here. Only the lanes of the current target
  // are ever considered: data, valid, and track on the mainband, and the
  // loopback lane in loopback. Clear any lane that is not carrying its
  // pattern -- track under `txTrackClk`, or a lane whose driver is off -- or
  // it triggers on the first packet.
  val rxTriggerLaneMask =
    Input(UInt(PhyTest.numTestLanes(numLanes).W))
  // Whether `CaptureMode.error` has triggered since the last FSM reset, and
  // the `rxPacketsReceived` index of the packet that triggered it. That
  // packet sits at this index minus `rxCaptureStart`, modulo the SRAM depth,
  // and the SRAM holds the packets up to `rxCapturePostTrigger` after it.
  val rxCaptureTriggered = Output(Bool())
  val rxCaptureTriggerPacket = Output(UInt(64.W))
  // Ones and zeros received on the track lane, counted on every word the RX
  // hands over whatever owns the mainband, for runtime recalibration. Raw
  // words, not framed by valid. Both stop together once either would
  // overflow, so their ratio stays exact. Cleared only by `trackCntRst`;
  // `trackCntHold` stops them so the pair can be read at one instant.
  val trackOnes = Output(UInt(32.W))
  val trackZeros = Output(UInt(32.W))
  // The ones of `trackOnes` that landed on the even UIs of a word. The RX
  // samples alternate UIs on alternate clock edges, the even ones on the
  // rising edge in `rx_data_lane.v`, so with track's shuffler at its reset
  // value this and `trackOnes - trackOnesEven` are each edge's count, out of
  // `(trackOnes + trackZeros) / 2` samples apiece. With a clock on track the
  // two move opposite ways as the sampling point drifts, which leaves the
  // total where it was.
  val trackOnesEven = Output(UInt(32.W))
  val trackCntRst = Input(Bool())
  val trackCntHold = Input(Bool())
  // Sticky: the loopback receiver handed over a word its queue had no room
  // for, because the PHY test clock drains it slower than words arrive.
  // Cleared by the RX datapath reset.
  val loopbackRxOverflow = Output(Bool())
  // Sticky: the loopback transmitter's queue ran dry, so the lane sent zeros
  // in place of a word, because the PHY test clock fills it slower than the
  // lane sends. Only armed while the loopback lane is the target, and only
  // from a TX datapath reset taken with it the target. Cleared by the TX
  // datapath reset.
  val loopbackTxUnderflow = Output(Bool())
  // The same for the TX data debug lane, which is always armed.
  val txDebugUnderflow = Output(Bool())

  // DEBUG CIRCUITRY CONTROL
  // ===========================
  // Pad driver control for the observation bumps, in `DebugBumpsIO` order:
  // `txClk`, `rxClk`, `rxData`, `clkMux`. The TX data debug lane brings its own
  // driver and takes its control from `txctl` instead.
  val driverctl = Input(Vec(PhyTest.NumDebugDrivers, new PadDriverCtlIO))
  // Which clock the `clkMux` bump watches, as an index into the mux input list
  // in [[PhyTest]]. Zero selects the forwarded TX sideband clock and one the
  // TX divided clock; the rest are not wired up yet.
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

  // LOOPBACK LANE CONTROL
  // ===========================
  // The loopback TX lane drives the loopback RX lane on chip, so the pair takes
  // the same per-lane control as a mainband lane but never reaches a bump.
  val loopbackTxctl = Input(new TxLaneDigitalCtlIO)
  val loopbackRxctl = Input(new RxLaneDigitalCtlIO)

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
  // The 800 MHz TX sideband clock the sideband tester serializes on, and its
  // reset.
  val sbTxClock = Input(Clock())
  val sbTxReset = Input(Bool())
  val debug = Flipped(new PhyDebugIO(numLanes))
  // Divider resets for the tester's own lanes, the same ones the PHY's
  // dividers take. They come from the clock register block on the chip clock
  // rather than from anything here; see `UcieClkRegs`.
  val txDividerRstb = Input(AsyncReset())
  val rxDividerRstb = Input(AsyncReset())
  val txDatapathRst = Output(Bool())
  val rxDatapathRst = Output(Bool())

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

  /** UI per unit of `txClkPostamble`. */
  val ClkPostambleUi = 8
  require(
    Phy.SerdesRatio % ClkPostambleUi == 0 &&
      isPow2(Phy.SerdesRatio / ClkPostambleUi),
    s"a packet must be a power-of-two number of $ClkPostambleUi UI postamble units"
  )

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

  /** Bits of a received pattern word that disagree with the LFSR recurrence,
    * given the two words before it.
    *
    * The TX sends its Fibonacci LFSR's output in the order it was generated,
    * so every bit on the wire is the XOR of the bits `LfsrTaps` UIs before it.
    * `history` holds the previous word in its high half and the one before in
    * its low half, which is the `LfsrWidth` bits the largest tap reaches back.
    */
  def selfSyncErrors(word: UInt, history: UInt): UInt = {
    val window = Cat(word, history)
    VecInit((0 until Phy.SerdesRatio).map { j =>
      LfsrTaps.toSeq
        .map(t => window(LfsrWidth + j - t))
        .reduce(_ ^ _) ^ word(j)
    }).asUInt
  }

  /** Sticky flag for a queue whose producer cannot be held off, set when it
    * offers a word the queue has no room for. Called in the enqueue clock
    * domain and reset with it.
    *
    * Ready is also low for a few cycles after reset, until the two ends have
    * handshaken, which is not a full queue, so the flag only arms once ready
    * has been seen.
    */
  def overflowFlag(enqReady: Bool): Bool = {
    val primed = RegInit(false.B)
    val flag = RegInit(false.B)
    when(enqReady) { primed := true.B }
    when(primed && !enqReady) { flag := true.B }
    flag
  }

  /** Sticky flag for a queue whose consumer takes a word every cycle, set when
    * the queue runs dry. Called in the dequeue clock domain and reset with it.
    *
    * The queue is empty after reset until the producer's first word arrives,
    * and empty whenever its producer is not the one feeding it, so the flag
    * only arms once a word has been seen with `feeding` high throughout since
    * the reset.
    */
  def underflowFlag(deqValid: Bool, feeding: Bool): Bool = {
    val fedSinceReset = RegInit(true.B)
    val primed = RegInit(false.B)
    val flag = RegInit(false.B)
    when(!feeding) { fedSinceReset := false.B }
    when(deqValid) { primed := true.B }
    when(fedSinceReset && feeding && primed && !deqValid) { flag := true.B }
    flag
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

  // The clock dividers -- the global ones in the PHY and the one inside every
  // tile, these lanes' included -- come off the divider resets and nothing
  // else. They all release together, which is what fixes the phase between a
  // tile's load edge and the divided clock words are handed over on. The
  // datapath resets do not reach them, so a datapath restart leaves that phase
  // alone.
  //
  // Not this module's reset: that is released against the PHY test clock,
  // which the dividers do not run on, so a divider release timed by it could
  // land on any edge. The divider resets come from the chip clock instead, and
  // are released with the clocks they hold stopped.
  val txDividerRstb = io.txDividerRstb
  val rxDividerRstb = io.rxDividerRstb

  // `txDatapathRst` restarts the TX and `rxDatapathRst` the RX. Neither stops a
  // divider: they reset the logic on the TX or RX divided clock, the async
  // queue's PHY side included, while that clock keeps running, so a strobe
  // actually flushes the datapath instead of freezing it.
  io.txDatapathRst := io.regs.txDatapathRst
  io.rxDatapathRst := io.regs.rxDatapathRst

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
  sbTest.io.txClock := io.sbTxClock
  sbTest.io.txReset := io.sbTxReset
  io.sb <> sbTest.io.sb

  // General computations
  val maxBitCount = VecInit(Seq.fill(bitCounterWidth)(true.B)).asUInt
  val maxSramPackets = 1.U << (bufferDepthPerLane - 5).U;

  // OBSERVATION BUMPS
  //
  // The PHY hands over raw nets; picking what to watch and driving a pad with
  // it happens here, so the PHY carries only link RTL.
  //
  // `clkMux` watches whichever clock `clkMuxSel` indexes out of this list; the
  // cell takes `ClkMux.numInputs`, and selecting a tied-off input leaves every
  // pass gate open. `rxData` watches any bit of any RX lane's word. Those
  // words are in the RX divided clock domain but nothing here samples them, so
  // only the selects cross and they are quasi-static.
  val clkMuxIns = Seq(io.debug.sbTxClk, io.debug.txDivClk)
  val clkMux = Module(new ClkMux)
  val clkMuxOut = clkMux.connect(clkMuxIns, io.regs.clkMuxSel)
  val rxDebugData = io.debug.rxData(io.regs.rxDebugLane)(io.regs.rxDebugBit)

  for (
    (((name, din), bump), ctl) <- Seq(
      ("txclk_driver", io.debug.testTxPadClk.asBool),
      ("rxclk_driver", io.debug.testRxPadClk.asBool),
      ("rxdata_driver", rxDebugData),
      ("clkmux_driver", clkMuxOut.asBool)
    ).zip(
      Seq(io.bumps.txClk, io.bumps.rxClk, io.bumps.rxData, io.bumps.clkMux)
    ).zip(io.regs.driverctl)
  ) {
    val driver = Module(new PadDriver)
    driver.suggestName(name)
    driver.io.din := din
    driver.io.ctl := ctl
    bump := driver.io.dout
  }

  // A TX lane that sits outside the PHY's lane clock distribution network: the
  // async queue onto the TX divided clock, the bit shuffler every TX lane has,
  // and the lane itself. The tester owns two of these -- the TX data debug lane
  // and the loopback transmitter -- and both run off the same TX data clock the
  // PHY's own lanes do.
  //
  // The queue has to be fed a word every cycle while `feeding`, idle words
  // included, for its underflow flag to mean anything.
  //
  // Returns the enqueue side of the queue, in the PHY test clock domain, the
  // lane, and the queue's sticky underflow flag, synchronized into the PHY
  // test clock domain.
  def txTestLane(
      name: String,
      ctl: TxLaneDigitalCtlIO,
      clk: Clock,
      feeding: Bool
  ): (DecoupledIO[UInt], TxLane, Bool) = {
    val lane = Module(new TxLane)
    lane.suggestName(name)
    // The tile's divider reset is active high, `txDividerRstb` active low.
    lane.io.rst := (!txDividerRstb.asBool).asAsyncReset
    lane.io.clk := clk
    lane.io.ctl := ctl.tile

    // The tile no longer brings out its own divided clock, so words are handed
    // over on the TX divided clock, which is the one the tiles take their
    // words on.
    val divRstSync = Module(new RstSync)
    divRstSync.suggestName(s"${name}_div_rst_sync")
    divRstSync.io.rstbAsync := !(reset.asBool || io.regs.txDatapathRst)
    divRstSync.io.clk := io.debug.txDivClk

    val fifo = Module(new AsyncQueue(UInt(Phy.SerdesRatio.W), queueParams))
    fifo.suggestName(s"${name}_fifo")
    fifo.io.enq_clock := clock
    fifo.io.enq_reset := reset
    fifo.io.deq_clock := io.debug.txDivClk
    fifo.io.deq_reset := !divRstSync.io.rstbSync
    fifo.io.deq.ready := true.B
    val underflow = withClockAndReset(
      io.debug.txDivClk,
      !divRstSync.io.rstbSync
    ) {
      PhyTest.underflowFlag(fifo.io.deq.valid, feeding)
    }

    // An empty queue sends zeros rather than repeating the last word.
    val din = Mux(fifo.io.deq.valid, fifo.io.deq.bits, 0.U)
    // The backup handoff phase the PHY's own lanes have, for the same reason:
    // the word relaunched on the other edge of the TX divided clock.
    val dinNeg = withClockAndReset(
      (!io.debug.txDivClk.asBool).asClock,
      !divRstSync.io.rstbSync
    ) {
      RegNext(din, 0.U(Phy.SerdesRatio.W))
    }

    val shuffler = Module(new Shuffler(Phy.SerdesRatio))
    shuffler.suggestName(s"${name}_shuffler")
    shuffler.io.din := Mux(ctl.sample_negedge, dinNeg, din)
    shuffler.io.permutation := ctl.shuffler
    lane.io.din := shuffler.io.dout

    (fifo.io.enq, lane, RegNext(RegNext(underflow, false.B), false.B))
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

  val (txDebugEnq, txDebugLane, txDebugUnderflow) =
    txTestLane("txdebug", io.regs.txctl, io.debug.testTxLaneClk(0), true.B)
  io.bumps.txData := txDebugLane.io.dout
  io.regs.txDebugUnderflow := txDebugUnderflow
  io.regs.txDebugState := txDebugState
  io.regs.txDebugPacketsEnqueued := txDebugPacketsEnqueued

  val txDebugValid = Wire(Bool())
  txDebugValid := false.B
  // Always fed, with zeros outside a run -- what an empty queue sends anyway
  // -- so that it only runs dry when the PHY test clock cannot keep up.
  txDebugEnq.valid := true.B
  txDebugEnq.bits := Mux(
    !txDebugValid,
    0.U,
    Mux(
      io.regs.txDebugTestMode === TxTestMode.manual,
      txDebugWords(txDebugAddr),
      Reverse(txDebugLfsr.io.out.asUInt)(Phy.SerdesRatio - 1, 0)
    )
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
  // Fed whenever the TX FSM could be driving the loopback lane.
  val txLoopbackFeeding =
    io.regs.testTarget === TestTarget.loopback && mbManual
  val (txLoopbackEnq, txLoopbackLane, txLoopbackUnderflow) =
    txTestLane(
      "txloopback",
      io.regs.loopbackTxctl,
      io.debug.testTxLaneClk(1),
      txLoopbackFeeding
    )
  io.regs.loopbackTxUnderflow := txLoopbackUnderflow

  val rxLoopbackLane = Module(new RxDataLane)
  rxLoopbackLane.suggestName("rxloopback")
  RxAfeCtl.connect(rxLoopbackLane, io.regs.loopbackRxctl)
  rxLoopbackLane.io.din := txLoopbackLane.io.dout
  // Sampled with the clock that shifted the data out, the way a mainband RX
  // lane is sampled with the RX forwarded clock from the partner die's TX.
  rxLoopbackLane.io.clk := io.debug.testTxLaneClk(2)
  rxLoopbackLane.io.rstb := rxDividerRstb

  val rxLoopbackShuffler = Module(new Shuffler(Phy.SerdesRatio))
  rxLoopbackShuffler.suggestName("rxloopback_shuffler")
  rxLoopbackShuffler.io.din := rxLoopbackLane.io.dout.asUInt
  rxLoopbackShuffler.io.permutation := io.regs.loopbackRxctl.shuffler

  val rxLoopbackRstSync = Module(new RstSync)
  rxLoopbackRstSync.suggestName("rxloopback_div_rst_sync")
  rxLoopbackRstSync.io.rstbAsync := !(reset.asBool || io.regs.rxDatapathRst)
  rxLoopbackRstSync.io.clk := rxLoopbackLane.io.divclk.asClock

  val rxLoopbackFifo = Module(
    new AsyncQueue(UInt(Phy.SerdesRatio.W), queueParams)
  )
  rxLoopbackFifo.io.enq_clock := rxLoopbackLane.io.divclk.asClock
  rxLoopbackFifo.io.enq_reset := !rxLoopbackRstSync.io.rstbSync
  // The deserializer has no valid of its own: it hands over a word every
  // divided cycle whether or not the TX is sending.
  rxLoopbackFifo.io.enq.valid := true.B
  // The tile updates its word on the same divided clock edge the queue
  // enqueues on, so the two pass on the tile's clock to output delay alone.
  // `sample_negedge` takes the word through a register on the falling edge
  // instead, half a word period from the update. No latency either way.
  val rxLoopbackWordNeg = withClockAndReset(
    (!rxLoopbackLane.io.divclk).asClock,
    !rxLoopbackRstSync.io.rstbSync
  ) {
    RegNext(rxLoopbackShuffler.io.dout, 0.U(Phy.SerdesRatio.W))
  }
  rxLoopbackFifo.io.enq.bits := Mux(
    io.regs.loopbackRxctl.sample_negedge,
    rxLoopbackWordNeg,
    rxLoopbackShuffler.io.dout
  )
  rxLoopbackFifo.io.deq_clock := clock
  rxLoopbackFifo.io.deq_reset := reset
  // With no valid on the enqueue side, a full queue loses the word.
  val rxLoopbackOverflow = withClockAndReset(
    rxLoopbackLane.io.divclk.asClock,
    !rxLoopbackRstSync.io.rstbSync
  ) {
    PhyTest.overflowFlag(rxLoopbackFifo.io.enq.ready)
  }
  io.regs.loopbackRxOverflow :=
    RegNext(RegNext(rxLoopbackOverflow, false.B), false.B)

  // TX registers
  val txReset = io.regs.txFsmRst || !mbManual || reset.asBool
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
  val rxReset =
    io.regs.rxFsmRst || (!mbManual && !io.regs.rxMonitor) || reset.asBool
  val rxPacketsReceived = withReset(rxReset) {
    RegInit(0.U((64 - log2Ceil(Phy.SerdesRatio)).W))
  }
  // A bit offset within a word, so `log2Ceil(SerdesRatio)` bits is right: the
  // assigned `SerdesRatio - startIdx` is a full word when the valid lane's set
  // bit lands on bit 0, and a full word offset is the same as no offset. The
  // wrap to zero is the modulo, not a truncation bug.
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
  // The row of the TX pattern SRAM the next packet processed is checked
  // against under `rxManualCheck`.
  val rxManualRow = withReset(rxReset) {
    RegInit(0.U((bufferDepthPerLane - 5).W))
  }

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
  // A manual TX run below takes the port back.
  when(io.regs.rxManualCheck) {
    inputBufferAddr := rxManualRow
  }
  val inputRdPorts =
    (0 until numSrams).map(i => inputBuffer(i)(inputBufferAddr))
  val inputWrPorts =
    (0 until numSrams).map(i => inputBuffer(i)(io.regs.txDataOffset))
  val outputBuffer = (0 until numSrams).map(i =>
    SyncReadMem(1 << (bufferDepthPerLane - 5), UInt(128.W))
  )
  val outputBufferAddr = Wire(UInt(log2Ceil(1 << (bufferDepthPerLane - 5)).W))
  // Nothing before packet `rxCaptureStart` is recorded, and that packet lands
  // at entry 0. Both come off registers, so neither is in the packet's path.
  val rxCaptureStarted = rxPacketsReceived >= io.regs.rxCaptureStart
  val rxCaptureIndex = rxPacketsReceived - io.regs.rxCaptureStart
  outputBufferAddr := rxCaptureIndex
  val toWrite = (0 until numSrams).map(i => {
    val wire = Wire(Vec(4, UInt(32.W)))
    for (i <- 0 until 4) {
      wire(i) := 0.U
    }
    wire
  })
  val shouldWrite = Wire(Bool())
  shouldWrite := false.B
  // Drops a write already on its way down the pipeline. Driven by the
  // error-triggered capture below.
  val rxWriteCancel = Wire(Bool())
  rxWriteCancel := false.B
  val outputBufferAddrDelayed = ShiftRegister(outputBufferAddr, 2, true.B)
  // Every processed packet goes down the pipeline, and the manual pattern
  // check below takes its words from the first stage.
  val toWriteStaged = toWrite.map(w => RegNext(w))
  val toWriteDelayed = toWriteStaged.map(w => RegNext(w))
  // Needs to default to false.
  val shouldWriteStaged = RegNext(shouldWrite, false.B)
  val shouldWriteDelayed = RegNext(shouldWriteStaged && !rxWriteCancel, false.B)
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

  // The words the TX logic below builds. Error injection is applied on the
  // way out to `io.tx` and the loopback lane, at the very end.
  val txWord = Wire(new TxIO(numLanes))
  for (lane <- 0 until numLanes) {
    txWord.data(lane) := 0.U
  }
  txWord.valid := 0.U
  txWord.track := 0.U
  // Needs to be true whenever PhyTest owns the mainband, so that clock and
  // track keep going out even when data isn't valid.
  io.tx.valid := mbManual

  // The forwarded-clock lanes carry a fixed pattern rather than a test one,
  // driven below where `txClkGate` parks them.

  // Unlike `io.tx.valid`, only true when data is valid.
  val tx_valid = Wire(Bool())
  tx_valid := false.B
  // Whether the run still has data to send, bursts aside: `tx_valid` is this
  // outside the idle packets between bursts.
  val txWantData = Wire(Bool())
  txWantData := false.B

  // BURSTS AND CLOCK GATING
  //
  // A run is a sequence of bursts with gaps of idle packets between them.
  // Without bursts or clock gating there is one burst and no gap, which is
  // the continuous run. With clock gating a run also opens with a gap of
  // `txClkPreamble` packets, so that the clock is up before the first packet.
  // Every count here is of packets the target accepted, i.e. of 32 UI on the
  // wire.
  val txBursting =
    io.regs.txBurstPackets =/= 0.U && io.regs.txIdlePackets =/= 0.U
  val txInGap = withReset(txReset) { RegInit(false.B) }
  // The gap the clock preamble opens a run with, rather than one between
  // bursts.
  val txInPreamble = withReset(txReset) { RegInit(false.B) }
  val txGapCount = withReset(txReset) { RegInit(0.U(32.W)) }
  val txBurstCount = withReset(txReset) { RegInit(0.U(32.W)) }
  val txGapLen =
    Mux(txInPreamble, io.regs.txClkPreamble, io.regs.txIdlePackets)
  // Packets since the last data packet, saturating, for the postamble.
  // Starts saturated, so a gated clock stays parked until a run starts.
  val txSinceDataMax = (1 << 16) - 1
  val txSinceData = withReset(txReset) {
    RegInit(txSinceDataMax.U(16.W))
  }

  // The loopback lane only carries traffic when it is the selected target, so
  // that a mainband run leaves it quiet. While it is, it takes a word every
  // cycle, zeros outside the run's data packets -- what an empty queue sends
  // anyway -- so that the idle packets of a gap last on the wire and the queue
  // only runs dry when the PHY test clock cannot keep up.
  txLoopbackEnq.valid := txLoopbackFeeding
  val txLoopbackWord = Wire(UInt(Phy.SerdesRatio.W))
  txLoopbackWord := 0.U

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
        when(io.regs.txClkGate && io.regs.txClkPreamble =/= 0.U) {
          txInGap := true.B
          txInPreamble := true.B
        }
      }
    }
    is(TxTestState.run) {
      switch(io.regs.txTestMode) {
        is(TxTestMode.manual) {
          // Need to load first chunk ahead of time so that we can constantly send data.
          when(loadedFirstChunk) {
            // Increment address when packet is enqueued. A gap enqueues idle
            // packets instead, so the address holds through one.
            when(txTargetReady && !txInGap) {
              inputBufferAddr := (inputBufferAddrReg + 1.U) % txManualRepeatPeriod
            }.otherwise {
              inputBufferAddr := inputBufferAddrReg % txManualRepeatPeriod
            }
            // Only send the next packet if we still need to send more bits.
            switch(io.regs.txDataMode) {
              is(DataMode.finite) {
                txWantData := txPacketsEnqueued < io.regs.txPacketsToSend
              }
              is(DataMode.infinite) {
                txWantData := true.B
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
              txWantData := txPacketsEnqueued < io.regs.txPacketsToSend
            }
            is(DataMode.infinite) {
              txWantData := true.B
            }
          }
        }
      }
      tx_valid := txWantData && !txInGap

      // Step through the bursts and the gaps between them.
      when(txTargetReady) {
        when(txInGap) {
          txGapCount := txGapCount + 1.U
          when(txGapCount + 1.U >= txGapLen) {
            txInGap := false.B
            txInPreamble := false.B
          }
        }.elsewhen(tx_valid && txBursting) {
          when(txBurstCount + 1.U >= io.regs.txBurstPackets) {
            txBurstCount := 0.U
            txInGap := true.B
            txGapCount := 0.U
          }.otherwise {
            txBurstCount := txBurstCount + 1.U
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
                  txWord.data(lane) := sramWord(lane)
                }
                txWord.valid := sramWord(PhyTest.validLane(numLanes))
                txWord.track := sramWord(PhyTest.trackLane(numLanes))
              }
              is(TestTarget.loopback) {
                txLoopbackWord := inputRdPorts((numLanes + 2) >> 2)
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
                  txWord.data(lane) := lfsrWord(lane)
                }
                txWord.valid := io.regs.txValid
                txWord.track := lfsrWord(PhyTest.trackLane(numLanes))
              }
              is(TestTarget.loopback) {
                txLoopbackWord := Reverse(
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
        (io.regs.txTestMode === TxTestMode.lfsr || loadedFirstChunk) && !txWantData
      ) {
        txState := TxTestState.done
      }
    }
    is(TxTestState.done) {}
  }

  when(txTargetReady) {
    when(tx_valid) {
      txSinceData := 0.U
    }.elsewhen(txSinceData =/= txSinceDataMax.U) {
      txSinceData := txSinceData + 1.U
    }
  }
  // Which UIs of this packet the clock lanes carry the clock for. A gated
  // clock runs through every data packet, for `txClkPostamble` units after
  // one, and for the last `txClkPreamble` packets of a gap, which covers the
  // whole of the gap a gated run opens with. Bit 0 goes out first, so a
  // postamble ending partway through a packet keeps its low bits.
  val txPostambleUnitsPerPacket = Phy.SerdesRatio / PhyTest.ClkPostambleUi
  val txPostamblePackets =
    io.regs.txClkPostamble >> log2Ceil(txPostambleUnitsPerPacket)
  val txPostambleUnitsLeft =
    io.regs.txClkPostamble(log2Ceil(txPostambleUnitsPerPacket) - 1, 0)
  val txClkFull = !io.regs.txClkGate || tx_valid ||
    txSinceData < txPostamblePackets ||
    (txState === TxTestState.run && txInGap &&
      txGapLen - txGapCount <= io.regs.txClkPreamble)
  val txClkMask = Mux(
    txClkFull,
    Fill(Phy.SerdesRatio, 1.U(1.W)),
    Mux(
      txSinceData === txPostamblePackets,
      VecInit(
        (0 until txPostambleUnitsPerPacket).map(n =>
          ((BigInt(1) << (n * PhyTest.ClkPostambleUi)) - 1).U(Phy.SerdesRatio.W)
        )
      )(txPostambleUnitsLeft),
      0.U
    )
  )
  txWord.clkp := io.regs.txClkP & txClkMask
  txWord.clkn := io.regs.txClkN & txClkMask

  when(io.regs.txTrackClk) {
    txWord.track := io.regs.txTrackWord
  }

  // Valid goes out on whichever physical lane `txValidLaneSel` names, so a
  // broken dedicated valid lane does not stop a test. The chosen lane sends
  // valid in place of its own pattern; unlike a link that cannot afford to drop
  // a lane, the tester does not shuffle the displaced payload anywhere.
  for (lane <- 0 until numLanes) {
    when(io.regs.txValidLaneSel === lane.U) {
      txWord.data(lane) := txWord.valid
    }
  }
  when(io.regs.txValidLaneSel === Phy.trackValidLaneSel(numLanes).U) {
    txWord.track := txWord.valid
  }

  // ERROR INJECTION
  //
  // Flips bit 0 of one lane in the next packet sent after the strobe.
  val txInjectArmed = withReset(txReset) { RegInit(false.B) }
  val txInjectNow = txInjectArmed && tx_valid && txTargetReady
  when(txInjectNow) {
    txInjectArmed := false.B
  }
  when(io.regs.txInjectError) {
    txInjectArmed := true.B
  }
  def injected(word: UInt, lane: Int): UInt =
    word ^ (txInjectNow && io.regs.txInjectLane === lane.U).asUInt

  io.tx.bits := txWord
  for (lane <- 0 until numLanes) {
    io.tx.bits.data(lane) := injected(txWord.data(lane), lane)
  }
  io.tx.bits.valid := injected(txWord.valid, PhyTest.validLane(numLanes))
  io.tx.bits.track := injected(txWord.track, PhyTest.trackLane(numLanes))
  txLoopbackEnq.bits :=
    injected(txLoopbackWord, PhyTest.loopbackLane(numLanes))

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
  // The manual check's counts land a cycle behind the LFSR ones, so they stop
  // a cycle later to cover the same packets as `rxPacketsReceived`.
  val rxPauseErrors = Mux(
    io.regs.rxManualCheck,
    RegNext(io.regs.rxPauseCounters, false.B),
    io.regs.rxPauseCounters
  )
  when(io.regs.rxPauseCounters) {
    rxPacketsReceivedOutput := rxPacketsReceivedOutput
    rxSignatureOutput := rxSignatureOutput
  }.otherwise {
    rxPacketsReceivedOutput := RegNext(rxPacketsReceived)
    // Same pipelining as `rxPacketsReceived` so that a paused signature always
    // covers exactly the number of packets reported alongside it.
    rxSignatureOutput := RegNext(rxSignature)
  }
  when(!rxPauseErrors) {
    rxBitErrorsOutput := rxBitErrors
  }

  // Dumb RX logic (starts recording as soon as valid goes high and never stops)
  val recordingStarted = withReset(rxReset) { RegInit(false.B) }
  val rxIdleWordsReg = withReset(rxReset) { RegInit(0.U(32.W)) }
  io.regs.rxIdleWordsObserved := rxIdleWordsReg
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

  // Whether the packet completing this cycle is processed: counted, scored,
  // and folded into the signature. Driven by the RX logic below.
  val rxProcessPacket = Wire(Bool())
  rxProcessPacket := false.B

  // SELF-SYNCHRONIZING CHECK
  //
  // The last two packets each lane received, newest in the high half, for
  // `PhyTest.selfSyncErrors`. Valid never carries an LFSR pattern, so its
  // history is never read and optimizes away.
  val rxHistory = withReset(rxReset) {
    RegInit(
      VecInit(
        Seq.fill(PhyTest.numTestLanes(numLanes))(0.U(PhyTest.LfsrWidth.W))
      )
    )
  }
  // Packets processed since the FSM reset, up to the two the history holds.
  val rxHistoryFill = withReset(rxReset) { RegInit(0.U(2.W)) }
  val rxHistoryFull = rxHistoryFill === 2.U
  when(rxProcessPacket && !rxHistoryFull) {
    rxHistoryFill := rxHistoryFill + 1.U
  }

  // ERROR-TRIGGERED CAPTURE
  //
  // `rxErrorMask` is a register, so a packet's errors show up the cycle after
  // it was processed, by which point `rxPacketsReceived` has already counted
  // it. The packet itself was written into the SRAM before the trigger, since
  // the capture was still open then.
  //
  // Under `rxManualCheck` they show a cycle later still, so the packet after
  // may have been counted too, and be on its way into the SRAM. It counts
  // toward the packets kept after the trigger, and is dropped if there are to
  // be none.
  val rxProcessedLast = withReset(rxReset) {
    RegNext(rxProcessPacket, false.B)
  }
  val rxScoredBehind = io.regs.rxManualCheck && rxProcessedLast
  val rxWriteInFlight = io.regs.rxManualCheck && shouldWriteStaged
  val rxCaptureError = io.regs.rxCaptureMode === CaptureMode.error
  val rxTriggered = withReset(rxReset) { RegInit(false.B) }
  val rxTriggerPacket = withReset(rxReset) {
    RegInit(0.U(rxPacketsReceived.getWidth.W))
  }
  val rxCaptureRemaining = withReset(rxReset) {
    RegInit(0.U(io.regs.rxCapturePostTrigger.getWidth.W))
  }
  // Only lanes that carry the current target's traffic can trigger. The
  // others compare whatever they happen to receive against a pattern.
  val rxTargetLanes = Mux(
    io.regs.testTarget === TestTarget.mainband,
    ((1 << (PhyTest.trackLane(numLanes) + 1)) - 1).U,
    (1 << PhyTest.loopbackLane(numLanes)).U
  )
  val rxTriggerLanes = rxTargetLanes & io.regs.rxTriggerLaneMask
  val rxAnyError = (0 until PhyTest.numTestLanes(numLanes))
    .map(lane =>
      rxTriggerLanes(lane) &&
        rxErrorMask(PhyTest.NominalFraming)(lane).orR
    )
    .reduce(_ || _)
  // The errors showing now are the previous packet's, so that packet is at or
  // past the start once the count is past it.
  val rxTriggerNow = rxCaptureError && !rxTriggered && rxAnyError &&
    rxPacketsReceived > io.regs.rxCaptureStart +& rxScoredBehind
  val rxPostTriggerNone = io.regs.rxCapturePostTrigger === 0.U
  rxWriteCancel := rxTriggerNow && rxWriteInFlight && rxPostTriggerNone
  val rxCaptureLeft = Mux(
    rxTriggerNow,
    Mux(
      rxPostTriggerNone,
      0.U,
      io.regs.rxCapturePostTrigger - rxWriteInFlight
    ),
    rxCaptureRemaining
  )
  val rxCaptureOpen = !(rxTriggered || rxTriggerNow) || rxCaptureLeft =/= 0.U
  when(rxTriggerNow) {
    rxTriggered := true.B
    rxTriggerPacket := rxPacketsReceived - 1.U - rxScoredBehind
    rxCaptureRemaining := rxCaptureLeft
  }
  when((rxTriggered || rxTriggerNow) && shouldWrite) {
    rxCaptureRemaining := rxCaptureLeft - 1.U
  }
  io.regs.rxCaptureTriggered := rxTriggered
  io.regs.rxCaptureTriggerPacket := rxTriggerPacket

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
    // Or start on the first word, as it stands.
    when(!recordingStarted && io.regs.rxStartMode === RxStartMode.immediate) {
      startRecording := true.B
      startIdx := 0.U
      rxReceiveOffset := 0.U
    }

    // Free running, deliberately not saturating. Read it twice: a changed
    // value means idle words are still arriving, a value that sits still
    // means they are not. A saturated counter cannot say that, because pegged
    // and stopped look the same.
    //
    // 32 bits so the magnitude survives too -- words arrive roughly every 2 ns
    // at the top rate, which is about 8 s to wrap, so the absolute count is
    // good for far longer than any bringup read.
    when(!recordingStarted && !startRecording) {
      rxIdleWordsReg := rxIdleWordsReg + 1.U
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
      // In burst mode, a packet whose valid word reads closer to idle than to
      // the waveform falls between bursts. Closer rather than equal, so that a
      // bit error on valid does not turn one into the other.
      val alignedValidWord = rxLaneWords(io.regs.rxValidLaneSel)
      val packetIsValid = !io.regs.rxBurstMode ||
        io.regs.testTarget === TestTarget.loopback ||
        PopCount(alignedValidWord ^ io.regs.rxLfsrValid) <
        PopCount(alignedValidWord)
      val shouldProcessPacket =
        fullPacketReceived && packetIsValid && (io.regs.rxDataMode === DataMode.infinite || rxPacketsReceived < io.regs.rxPacketsToReceive)
      rxProcessPacket := shouldProcessPacket
      shouldWrite := rxCaptureStarted && Mux(
        rxCaptureError,
        shouldProcessPacket && rxCaptureOpen,
        rxCaptureIndex < maxSramPackets && fullPacketReceived && packetIsValid
      )
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
        toWrite(lane >> 2)(lane % 4) := newData(31, 0)
        rxLaneWords(lane) := newData(31, 0)

        when(shouldProcessPacket) {
          rxHistory(lane) := Cat(
            newData(31, 0),
            rxHistory(lane)(PhyTest.LfsrWidth - 1, Phy.SerdesRatio)
          )
        }

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
              rxErrorMask(PhyTest.NominalFraming)(lane) := Mux(
                io.regs.rxSelfSync,
                Mux(
                  rxHistoryFull,
                  PhyTest.selfSyncErrors(newData(31, 0), rxHistory(lane)),
                  0.U
                ),
                newData(31, 0) ^ refWord(state)
              )
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

  // MANUAL PATTERN CHECK
  //
  // The TX pattern SRAM is read on the row the next packet processed is due
  // to be checked against, so the row arrives the cycle after that packet,
  // alongside its words one stage down the capture pipeline. Reading off a
  // register keeps the late decision of whether a packet is processed off
  // the SRAM's address. Overrides every mask the LFSR scoring above set.
  when(rxProcessPacket) {
    rxManualRow := Mux(
      rxManualRow +& 1.U >= txManualRepeatPeriod,
      0.U,
      rxManualRow + 1.U
    )
  }
  when(io.regs.rxManualCheck) {
    for (
      f <- 0 until PhyTest.NumFramings;
      lane <- 0 until PhyTest.numTestLanes(numLanes)
    ) {
      rxErrorMask(f)(lane) := 0.U
    }
    when(rxProcessedLast) {
      def rowWord(lane: Int): UInt =
        inputRdPorts(lane >> 2).asTypeOf(Vec(4, UInt(32.W)))(lane % 4)
      for (lane <- 0 until PhyTest.numTestLanes(numLanes)) {
        rxErrorMask(PhyTest.NominalFraming)(lane) :=
          toWriteStaged(lane >> 2)(lane % 4) ^ rowWord(lane)
      }
    }
  }

  // TRACK COUNTERS
  //
  // Every word the RX hands over, whether or not the RX FSM is running.
  val trackCntReset = io.regs.trackCntRst || reset.asBool
  val trackOnes = withReset(trackCntReset) { RegInit(0.U(32.W)) }
  val trackZeros = withReset(trackCntReset) { RegInit(0.U(32.W)) }
  // Never more than `trackOnes`, so it cannot overflow first.
  val trackOnesEven = withReset(trackCntReset) { RegInit(0.U(32.W)) }
  val trackWordOnes = PopCount(io.rx.bits.track)
  val trackOnesNext = trackOnes +& trackWordOnes
  val trackZerosNext = trackZeros +& (Phy.SerdesRatio.U - trackWordOnes)
  when(
    io.rx.fire && !io.regs.trackCntHold &&
      !trackOnesNext(32) && !trackZerosNext(32)
  ) {
    trackOnes := trackOnesNext
    trackZeros := trackZerosNext
    trackOnesEven := trackOnesEven +
      PopCount((0 until Phy.SerdesRatio by 2).map(io.rx.bits.track(_)))
  }
  io.regs.trackOnes := trackOnes
  io.regs.trackZeros := trackZeros
  io.regs.trackOnesEven := trackOnesEven
}
