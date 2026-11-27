package edu.berkeley.cs.uciedigital.phytest

import chisel3._
import chisel3.util._
import chisel3.util.random._
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}

import edu.berkeley.cs.uciedigital.phy._
import edu.berkeley.cs.uciedigital.phy.macros._
import edu.berkeley.cs.uciedigital.phy.macros.clocking._

/** What a band carries while PhyTest is the selected controller. The mainband
  * and the sideband each have one, so either can carry TileLink while the other
  * stays under test control.
  */
object BandMode extends ChiselEnum {

  /** PhyTest drives the band. On the mainband, `TxTestMode` picks the manual
    * buffer or the LFSR. On the sideband, packets are staged one at a time
    * through `SidebandTestRegsIO`.
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

  /** The first word the RX receives, framed on the RX's own word boundary.
    * Shows what a lane receives when nothing on the link can be trusted for
    * framing.
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
    * packets, then stops.
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
  // Lane indices here are PHYSICAL lanes, as in `PhyRegsIO`'s `txctl`/`rxctl`,
  // so a lane's error count matches the AFE those registers tune.
  //
  // Every per-lane vector (pattern and capture SRAMs, bit error counters, LFSR
  // seeds) has `PhyTest.numTestLanes` entries: data `0` to `numLanes - 1`,
  // then valid, track, and loopback. Valid carries a repeating framing
  // waveform, not a pattern: it is checked against `rxValid` and its LFSR
  // seed slot is unused. Track is driven and scored like a data lane.
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
  // Resets the TX datapath (serdes handoff registers and the PHY side of the
  // async queue, all on the TX divided clock). The PHY synchronizes it to
  // that clock. Does not reset the FSM or stop a clock divider.
  val txDatapathRst = Input(Bool())
  // Resets the TX FSM: zeroes the bits-sent count, reseeds the LFSR, and stops
  // any transmission in progress.
  val txFsmRst = Input(Bool())
  // Starts sending from the start of the input buffer (`TxTestMode.manual`) or
  // from the current LFSR state (`TxTestMode.lsfr`). Ignored while a
  // transmission is in progress.
  val txExecute = Input(Bool())
  // The number of packets of `Phy.SerdesRatio` bits sent since the last FSM reset.
  val txPacketsSent = Output(UInt(bitCounterWidth.W))
  // In `TxTestMode.manual`, the number of 32-bit chunks in the repeat period.
  // 0, or a value above the buffer length, sends the whole buffer.
  val txManualRepeatPeriod = Input(UInt((bufferDepthPerLane - 5 + 1).W))
  // The number of packets to send during transmission.
  val txPacketsToSend = Input(UInt(bitCounterWidth.W))
  // Clock P signal value.
  val txClkP = Input(UInt(32.W))
  // Clock N signal.
  val txClkN = Input(UInt(32.W))
  // Valid word in LFSR mode. Manual mode takes valid from the pattern SRAM.
  val txValid = Input(UInt(32.W))
  // Physical lane that sends valid, so a broken dedicated valid lane does not
  // stop a test: `0` to `numLanes - 1` for a data lane,
  // `Phy.defaultValidLaneSel` (reset value) for the dedicated valid lane,
  // `Phy.trackValidLaneSel` for track. That lane's own data is then not sent,
  // so its error count means nothing. The dedicated valid lane always sends
  // valid.
  val txValidLaneSel = Input(UInt(Phy.validLaneSelWidth(numLanes).W))
  // Lane group of the data chunk in the input buffer: 4 adjacent lanes (e.g.
  // 0, 1, 2, 3). Lane numLanes is valid, numLanes + 1 is track, numLanes + 2
  // is loopback.
  val txDataLaneGroup = Input(UInt(log2Ceil((numLanes + 2) / 4 + 1).W))
  // Data chunk offset in input buffer.
  val txDataOffset = Input(UInt((bufferDepthPerLane - 5).W))
  // 128-bit data chunk to write (32 bits per lane).
  val txDataChunkIn = Flipped(DecoupledIO(UInt(128.W)))
  // Data chunk at the given chunk offset, to inspect the data to send. Only
  // available in idle/done, with `rxManualCheck` clear.
  val txDataChunkOut = Output(UInt(128.W))
  // State of the TX test FSM.
  val txTestState = Output(TxTestState())
  // Bursts: with both nonzero, the TX sends `txBurstPackets` packets, then
  // `txIdlePackets` idle packets, and repeats until `txPacketsToSend` are
  // sent. Either one zero sends continuously. Idle packets have zeros on
  // valid and every pattern lane, and the pattern does not advance over them,
  // so the data packets match a continuous run. On the mainband `TxClkGater`
  // at the PHY end makes the idle packets. The RX needs `rxBurstMode` to skip
  // them.
  val txBurstPackets = Input(UInt(32.W))
  val txIdlePackets = Input(UInt(32.W))
  // Gates the forwarded clock as in UCIe strobe mode, for PhyTest runs and
  // TileLink frames on the mainband. `TxClkGater` at the PHY end runs the
  // clock from `txClkPreamble` packets before data until `txClkPostamble`
  // times `PhyTest.ClkPostambleUi` UI after it, and parks it otherwise (park
  // levels: see `TxClkGater` and `txClkParkAlternate`). After a high park,
  // the clock goes low `PhyTest.ClkPreconditionUi` UI before it runs again:
  // the 1 to 8 UI of differential low UCIe asks for before the first edge.
  // Every gap has exactly the postamble's edges. The UCIe controller uses
  // these settings once it negotiates strobe mode.
  //
  // Rules:
  //   - After resetting the partner's RX datapath, run the clock a few
  //     words before gating: the RX drops the words it receives while the
  //     reset releases.
  //   - UCIe asks for a parked period that is a multiple of 8 UI. Gaps are
  //     whole packets, so use a postamble that is a multiple of 8 UI.
  //   - The partner's RX counts word boundaries on a gap's edges, so each gap
  //     moves them by the postamble modulo 32 UI. PhyTest's RX frames once
  //     per run, so only the first burst scores clean unless the postamble is
  //     a whole number of packets (a multiple of 32 UI).
  //   - To hand over a burst's last word, the RX needs 17 edges after the one
  //     that samples the last UI (1 for the tile to output the word on its
  //     divided clock's falling edge, 16 to the RX divided clock's next
  //     rising edge, where the queue takes it), plus up to 31 more to finish
  //     that RX word. With the clock centred on the data, that is 18 to 48
  //     UI. A high park delays its last edge, but the RX always needs an odd
  //     number of edges, so this does not change. The 32 UI default covers a
  //     burst that ends 17 UI or more into an RX word, which a divider
  //     realign and then one 40 UI gap set up (`ucie/docs/phytest_bringup.md`,
  //     section 8). Two packets (64 UI) cover every alignment. This also holds
  //     in `TxTestMode.manual`, whose pattern does not set the clock lanes.
  //   - UCIe sends no preamble. With `txClkPreamble` = 0, the clock's first
  //     edge must sample the first data UI. At the global delay's reset
  //     value, the clock trails the data by just over a UI and that UI is
  //     lost. Centre the clock first.
  val txClkGate = Input(Bool())
  val txClkPreamble = Input(UInt(16.W))
  val txClkPostamble = Input(UInt(16.W))
  // Alternates the level a gated clock parks at between differential low and
  // high, as UCIe asks. Off, it always parks low. See `txClkGate`.
  val txClkParkAlternate = Input(Bool())
  // Sends `txTrackWord` on track every packet, in any test mode, instead of
  // track's pattern: a clock, as the UCIe controller sends, or any fixed
  // waveform. Valid moved onto track by `txValidLaneSel` still wins.
  val txTrackClk = Input(Bool())
  val txTrackWord = Input(UInt(32.W))
  // Flips bit 0 of the next packet sent on lane `txInjectLane` (per-lane
  // vector index). A strobe before `txExecute` hits the first packet. Cleared
  // by an FSM reset.
  val txInjectError = Input(Bool())
  val txInjectLane =
    Input(UInt(log2Ceil(PhyTest.numTestLanes(numLanes)).W))
  // Per-lane sources for crosstalk aggressors and victims, in
  // `TxTestMode.lfsr` only. Bit `i` of `txLaneFixed` sends `txFixedWord` on
  // lane `i` instead of its LFSR stream (all zeros or all ones gives a static
  // level). Bit `i` of `txLaneInvert` inverts lane `i`'s stream. Fixed wins.
  // Valid's bits are ignored. Every LFSR keeps advancing, so a lane rejoins
  // its stream in step when its bit clears. `txTrackClk`, `txValidLaneSel`
  // and error injection still apply. Set the partner's `rxLaneFixed`,
  // `rxLaneInvert` and `rxFixedWord` to match.
  val txLaneFixed = Input(UInt(PhyTest.numTestLanes(numLanes).W))
  val txLaneInvert = Input(UInt(PhyTest.numTestLanes(numLanes).W))
  val txFixedWord = Input(UInt(Phy.SerdesRatio.W))

  // RX CONTROL
  // ====================
  // The data mode of the RX.
  val rxDataMode = Input(DataMode())
  // Seed of the RX LFSR used for detecting bit errors, indexed like
  // `txLfsrSeed`. Should be the same as the TX seed of the transmitting chiplet.
  val rxLfsrSeed =
    Input(Vec(PhyTest.numTestLanes(numLanes), UInt((2 * Phy.SerdesRatio).W)))
  // Expected valid word: the partner's `txValid`. Valid is a fixed waveform,
  // not an LFSR stream, in every mode but `rxManualCheck`.
  val rxValid = Input(UInt(32.W))
  // Physical lane the RX watches for the edge that starts recording. Coded
  // like `txValidLaneSel` but set independently. It must match the lane the
  // partner die sends valid on.
  val rxValidLaneSel = Input(UInt(Phy.validLaneSelWidth(numLanes).W))
  // Resets the RX datapath: the deserializer handoff registers and the PHY
  // side of the async queue, both on the RX divided clock. The PHY
  // synchronizes it to that clock. Does not reset the FSM or any divider.
  val rxDatapathRst = Input(Bool())
  // Resets the RX FSM: zeroes the bits-received count and the output buffer
  // offset.
  val rxFsmRst = Input(Bool())
  // Packets received since the last FSM reset. The output buffer stores only
  // the first 2^bufferDepthPerLane bits received per lane.
  val rxPacketsReceived = Output(UInt(bitCounterWidth.W))
  // The number of packets to receive.
  val rxPacketsToReceive = Input(UInt(bitCounterWidth.W))
  // Bit errors per lane since the last FSM reset, against the pattern framed
  // by the valid edge the RX locked onto. Only meaningful in
  // `TxTestMode.lsfr`, or in `TxTestMode.manual` with `rxManualCheck`.
  val rxBitErrors =
    Output(Vec(PhyTest.numTestLanes(numLanes), UInt(bitCounterWidth.W)))
  // The same counts with the pattern framed one UI earlier and one UI later.
  // A bit error on valid in the cycle the RX aligns shifts every comparison
  // by a UI, so the nominal count sits near half the bits received. The
  // framing that reads clean then gives the real count: `rxBitErrorsLate`
  // after a spurious valid bit, `rxBitErrorsEarly` after a dropped one.
  val rxBitErrorsEarly =
    Output(Vec(PhyTest.numTestLanes(numLanes), UInt(bitCounterWidth.W)))
  val rxBitErrorsLate =
    Output(Vec(PhyTest.numTestLanes(numLanes), UInt(bitCounterWidth.W)))
  // Pauses the `rxPacketsReceived`, `rxBitErrors`, and `rxSignature` outputs
  // so they can be read atomically. The error class counts below pause too.
  val rxPauseCounters = Input(Bool())
  // Where nominal bit errors fall. Bit `i` of a lane's 32-bit mask sets once
  // an error hits UI `i` of a packet, as the RX framed it. `rxErrPosMask`
  // shows lane `rxErrPosLane`'s mask (zero past the last lane).
  // `rxLaneErrored` has one bit per lane, set on its first error. Only the
  // current target's lanes collect bits. Cleared by the RX FSM reset. Not
  // frozen by `rxPauseCounters`; bits only accumulate, so a mid-run read is a
  // lower bound. Errors that keep hitting one UI point at one serializer or
  // deserializer phase, not the channel.
  val rxErrPosLane = Input(UInt(log2Ceil(PhyTest.numTestLanes(numLanes)).W))
  val rxErrPosMask = Output(UInt(Phy.SerdesRatio.W))
  val rxLaneErrored = Output(UInt(PhyTest.numTestLanes(numLanes).W))
  // Nominal bit errors on lane `rxErrClassLane`, split by sampling edge and
  // sent bit. `Rise`: UIs the deserializer samples on its clock's rising edge
  // (its own word's even UIs, whatever the framing). `Fall`: the odd UIs.
  // `One`: ones read as zeros. `Zero`: zeros read as ones. Errors mostly on
  // one edge point at the clock duty cycle or one comparator phase; mostly
  // on one value, at a threshold (`vrefSel`) off the eye centre. Paused with
  // the other counts and cleared by the RX FSM reset, but not by changing the
  // lane: reset the RX FSM after changing it.
  val rxErrClassLane =
    Input(UInt(log2Ceil(PhyTest.numTestLanes(numLanes)).W))
  // The PHY's `rxUiDelay`, which moves a lane's UI that far against its
  // deserializer's clock edges, so the split above can follow the edges.
  val rxUiDelay =
    Input(Vec(Phy.numRxDataLanes(numLanes), UInt(Phy.uiDelayWidth.W)))
  val rxErrRiseOne = Output(UInt(bitCounterWidth.W))
  val rxErrRiseZero = Output(UInt(bitCounterWidth.W))
  val rxErrFallOne = Output(UInt(bitCounterWidth.W))
  val rxErrFallZero = Output(UInt(bitCounterWidth.W))
  // MISR over the packets received since the last FSM reset. Works in any TX
  // test mode: compare with `PhyTest.signatureNext` folded over the expected
  // stream, to check patterns the RX has no LFSR for (e.g.
  // `TxTestMode.manual`) over runs far longer than the capture SRAM. Covers
  // exactly the packets in `rxPacketsReceived`, so read both under
  // `rxPauseCounters`.
  val rxSignature = Output(UInt(32.W))
  // Idle words the RX has handled since the FSM reset. Free running. Read it
  // twice: if it moves, the receive path is clocked. With `rxPacketsReceived`
  // it separates three cases that all look like "bit errors everywhere":
  // neither moves (dead receive path), only this moves (never saw valid),
  // both move (working link). `run_lfsr` also waits on it, since the
  // datapath reset releases through a synchronizer on the RX divided clock
  // and no fixed delay covers every rate.
  val rxIdleWordsObserved = Output(UInt(32.W))
  // UI of the RX's own word where the first packet started: the start of the
  // valid run that began recording. If the far TX's valid has UI 0 set, this
  // is where its words start. 0 before framing and with
  // `RxStartMode.immediate`. Cleared by an RX FSM reset. `align_rx_words`
  // sets every lane's `rxUiDelay` from it.
  val rxFrameOffset = Output(UInt(log2Ceil(Phy.SerdesRatio).W))
  // Data chunk lane in output buffer.
  val rxDataLane = Input(UInt(log2Ceil(PhyTest.numTestLanes(numLanes)).W))
  // Data chunk offset in output buffer.
  val rxDataOffset = Input(UInt((bufferDepthPerLane - 5).W))
  // Data chunk at the given chunk offset, to inspect the received data.
  val rxDataChunk = Output(UInt(32.W))
  // What starts recording.
  val rxStartMode = Input(RxStartMode())
  // With `RxStartMode.valid`: how many UI in a row valid must be set.
  // Recording starts on the first of them. The run may span two words. 0 and
  // 1 start on the first set bit; values above `Phy.SerdesRatio` act as
  // `Phy.SerdesRatio`. Keep it within the valid waveform's first run, or
  // recording never starts. Resets to `PhyTest.rxValidStartThresholdReset`.
  // The loopback lane always starts on its first set bit.
  val rxValidStartThreshold = Input(UInt(log2Ceil(Phy.SerdesRatio + 1).W))
  // Processes only packets whose valid word (lane `rxValidLaneSel`) is closer
  // to `rxValid` than to zero. Others are idle packets between bursts:
  // not counted, scored, captured, or folded into the signature. Needs a
  // valid waveform with bit 0 set, so the RX frames on the TX's packet
  // boundaries and no packet spans a burst edge. Ignored with
  // `TestTarget.loopback`, which has no valid lane.
  val rxBurstMode = Input(Bool())
  // Scores each pattern lane against its own received history instead of
  // `rxLfsrSeed`: each bit must equal the XOR of the earlier bits at the LFSR
  // taps. No seed is needed, and the count does not depend on the valid edge
  // or other lanes, so any lane-to-lane skew reads clean. Replaces the
  // nominal count of every lane carrying an LFSR pattern; valid and the early
  // and late counts are unchanged. A `TxTestMode.manual` pattern reads as
  // errors. The first two packets fill the history and are not scored. One
  // bit error counts `1 + PhyTest.LfsrTaps.size` times.
  val rxSelfSync = Input(Bool())
  // Seeds each pattern lane's RX LFSR from its own stream instead of
  // `rxLfsrSeed`: from the second and third packets after recording starts,
  // advanced to the fourth. From there the LFSR runs on its own and scores as
  // usual, so a bit error counts once, at its own UI. The first three packets
  // are not scored. The first is skipped because a lane that lags valid
  // starts it with bits from before the pattern. A bit error in the two
  // seed packets spoils the lane's run: it reads about half its bits wrong.
  // A seed of all zeros (a dead lane) is not loaded, so the LFSR runs on from
  // `rxLfsrSeed` and the lane reads about half wrong. Applies at all three
  // framings, and to inverted lanes; not to fixed lanes, valid, or a lane
  // carrying valid. `rxSelfSync` takes over the nominal count if both are set.
  val rxAutoSeed = Input(Bool())
  // Scores against a `TxTestMode.manual` pattern instead of the RX LFSRs.
  // Load the partner's pattern into this die's TX pattern SRAM
  // (`txDataChunkIn`) and its period into `txManualRepeatPeriod`. The `n`th
  // packet processed is checked against row `n` modulo the period, so the RX
  // must start on the partner's first packet, i.e. on valid. Each lane, valid
  // included, is checked against its own slot. This also holds for a lane
  // the partner moved valid onto, so load valid's waveform into its slot.
  // Early and late counts stay zero; `rxSelfSync` is ignored.
  //
  // The RX uses the SRAM port the TX sends from, so this works only while
  // this die's TX is not sending a manual pattern (an LFSR run is fine). It
  // also takes over `txDataChunkOut`. Counts arrive a cycle later than LFSR
  // counts; `rxPauseCounters` and `CaptureMode.error` allow for that.
  val rxManualCheck = Input(Bool())
  // Match the partner's `txLaneFixed`/`txLaneInvert`. Bit `i` of
  // `rxLaneFixed` scores lane `i` against `rxFixedWord`; bit `i` of
  // `rxLaneInvert`, against its reference inverted. Both apply at all three
  // framings (a slipped fixed word is a rotation) and under `rxSelfSync`.
  // Ignored for valid, a lane carrying valid, and under `rxManualCheck`.
  val rxLaneFixed = Input(UInt(PhyTest.numTestLanes(numLanes).W))
  val rxLaneInvert = Input(UInt(PhyTest.numTestLanes(numLanes).W))
  val rxFixedWord = Input(UInt(Phy.SerdesRatio.W))
  // Keeps the RX FSM running while TileLink drives the mainband, so the
  // capture SRAM and the signature see the TileLink traffic. The TX FSM is
  // still held.
  val rxMonitor = Input(Bool())
  // What the capture SRAM keeps.
  val rxCaptureMode = Input(CaptureMode())
  // `rxPacketsReceived` index of the first packet the capture SRAM records,
  // in either mode. Packet `n` goes to entry `n - rxCaptureStart` modulo the
  // SRAM depth. Earlier packets do not trigger `CaptureMode.error`. To start
  // at stream bit `b`, use packet `b / 32` and drop the first `b % 32` bits.
  val rxCaptureStart = Input(UInt(32.W))
  // Packets captured after the trigger in `CaptureMode.error`.
  val rxCapturePostTrigger = Input(UInt((bufferDepthPerLane - 5 + 1).W))
  // Lanes whose nominal count can trigger `CaptureMode.error`. Only the
  // current target's lanes count (data, valid, track on the mainband;
  // loopback in loopback). Clear lanes not carrying their pattern (track
  // under `txTrackClk`, a lane with its driver off), or they trigger on the
  // first packet.
  val rxTriggerLaneMask =
    Input(UInt(PhyTest.numTestLanes(numLanes).W))
  // Whether `CaptureMode.error` has triggered since the last FSM reset, and
  // the `rxPacketsReceived` index of the packet that triggered it. That
  // packet sits at this index minus `rxCaptureStart`, modulo the SRAM depth,
  // and the SRAM holds the packets up to `rxCapturePostTrigger` after it.
  val rxCaptureTriggered = Output(Bool())
  val rxCaptureTriggerPacket = Output(UInt(64.W))
  // Ones and zeros on the track lane, for runtime recalibration. Counted on
  // every raw word the RX hands over (not framed by valid), whatever owns the
  // mainband. Both stop when either would overflow, so the ratio stays exact.
  // Only `trackCntRst` clears them; `trackCntHold` freezes them for a
  // consistent read.
  val trackOnes = Output(UInt(32.W))
  val trackZeros = Output(UInt(32.W))
  // The ones of `trackOnes` on even UIs. The RX samples even UIs on the
  // rising edge in `rx_data_lane.v`, odd UIs on the falling edge. With
  // track's shuffler at reset, this and `trackOnes - trackOnesEven` are the
  // per-edge counts, out of `(trackOnes + trackZeros) / 2` samples each.
  // With a clock on track they move oppositely as the sampling point drifts,
  // and the total stays the same.
  val trackOnesEven = Output(UInt(32.W))
  val trackCntRst = Input(Bool())
  val trackCntHold = Input(Bool())
  // Sticky: the loopback receiver handed over a word while its queue was
  // full, because the PHY test clock drains it slower than words arrive.
  // Cleared by the RX datapath reset.
  val loopbackRxOverflow = Output(Bool())
  // Sticky: the loopback TX queue ran dry and the lane sent zeros, because
  // the PHY test clock fills it slower than the lane sends. Armed only while
  // loopback is the target, after a TX datapath reset taken while it was the
  // target. Cleared by the TX datapath reset.
  val loopbackTxUnderflow = Output(Bool())
  // The same for the TX data debug lane, which is always armed.
  val txDebugUnderflow = Output(Bool())

  // DEBUG CIRCUITRY CONTROL
  // ===========================
  // Pad driver control for the observation bumps, `mux0` then `mux1`. The TX
  // data debug lane brings its own driver and takes its control from `txctl`
  // instead.
  val driverctl = Input(Vec(PhyTest.NumDebugDrivers, new PadDriverCtlIO))
  // What each observation bump watches, `mux0` then `mux1`: independent
  // indices into `PhyTest.DebugMuxInputs`.
  val debugMuxSel =
    Input(Vec(PhyTest.NumDebugDrivers, UInt(ClkMux.selWidth.W)))
  // The TX data debug lane and its pattern FSM. A tester built without the
  // lane (`hasTxDebugLane = false`) ignores these and reports the FSM idle.
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

/** Bumps the tester drives for observation: `mux0` and `mux1` each carry
  * whichever of `PhyTest.DebugMuxInputs` their own select picks, and `txData`
  * is the TX data debug lane, present only with `hasTxDebugLane`. The PHY only
  * fans out the nets being watched; the muxes and pad drivers all live in
  * [[PhyTest]].
  */
class DebugBumpsIO(hasTxDebugLane: Boolean = true) extends Bundle {
  val mux0 = Output(Bool())
  val mux1 = Output(Bool())
  val txData = Option.when(hasTxDebugLane)(Output(Bool()))
}

class PhyTestIO(
    bufferDepthPerLane: Int = 10,
    numLanes: Int = 2,
    bitCounterWidth: Int = 64,
    hasTxDebugLane: Boolean = true
) extends Bundle {
  val regs = new PhyTestRegsIO(bufferDepthPerLane, numLanes, bitCounterWidth)

  // PHY INTERFACE
  // ====================
  // Only words that carry data; `TxClkGater` at the PHY end makes the rest.
  val tx = new DecoupledIO(new TxIO(numLanes))
  // Sent with each word on `tx`: whether `TxClkGater` leaves `txIdlePackets`
  // packets of gap after it (ending a burst), and whether the run has more
  // data after it.
  val txGapAfter = Output(Bool())
  val txContinues = Output(Bool())
  val rx = Flipped(new DecoupledIO(new RxIO(numLanes)))
  val sb = Flipped(new SbIO)
  // The 800 MHz TX sideband clock the sideband tester serializes on, and its
  // reset.
  val sbTxClock = Input(Clock())
  val sbTxReset = Input(Bool())
  val debug = Flipped(new PhyDebugIO(numLanes))
  // Divider resets for the tester's own lanes, the same ones the PHY's
  // dividers use. They come from the clock register block on the chip clock,
  // not from this module; see `UcieClkRegs`.
  val txDividerRstb = Input(AsyncReset())
  val rxDividerRstb = Input(AsyncReset())
  val txDatapathRst = Output(Bool())
  val rxDatapathRst = Output(Bool())

  // BUMP INTERFACE
  // ====================
  val bumps = new DebugBumpsIO(hasTxDebugLane)
}

/** Signature (MISR) parameters shared by [[PhyTest]] and its software model. */
object PhyTest {

  /** Width of the TX/RX pattern LFSRs. */
  val LfsrWidth = 2 * Phy.SerdesRatio

  /** Lanes the tester uses, in the order of every per-lane vector: `numLanes`
    * data lanes, then valid, track, and loopback. Data, valid, and track are
    * the RX physical lanes. Loopback is the tester's own on-chip pair.
    */
  def numTestLanes(numLanes: Int): Int = numLanes + 3
  def validLane(numLanes: Int): Int = numLanes
  def trackLane(numLanes: Int): Int = numLanes + 1
  def loopbackLane(numLanes: Int): Int = numLanes + 2

  /** How many observation bumps there are (`mux0`, `mux1`), each with a mux and
    * a pad driver of its own. The TX data debug lane brings its own driver.
    */
  val NumDebugDrivers = 2

  /** What both observation muxes can select, in select order, as `PhyDebugIO`
    * field names. Selects past the end of the list pick a tied-off input.
    */
  val DebugMuxInputs: Seq[String] = Seq(
    "sbTxClk",
    "txDivClk",
    "rxDivClk",
    "digitalBypassClk",
    "sidebandBypassClk",
    "refClk",
    "rxDataLsb",
    "txDataLsb",
    "testTxPadClk",
    "testRxPadClk"
  )

  /** Packets in the TX data debug lane's manual pattern. The lane has no SRAM:
    * the pattern comes from `txDebugData`, sixteen 64-bit registers, i.e. this
    * many `Phy.SerdesRatio` bit packets.
    */
  val DebugBufferPackets = 32

  /** Every lane's TX and RX LFSR seed out of reset, and what the generated
    * `seed_lfsrs` writes back. Each lane differs. With one seed everywhere, all
    * lanes switch together (the worst case for supply noise and crosstalk), and
    * lanes swapped between the TX and the RX read clean.
    *
    * A stream's first UI is its seed's bit 63. It is set, so every stream
    * starts with a 1. The loopback RX frames on its first 1, so a seeded run
    * there frames exactly.
    */
  val DefaultLfsrSeed = BigInt("8123456789abcdef", 16)
  val LfsrSeedLaneStep = BigInt("1111", 16)
  def defaultLfsrSeed(lane: Int): BigInt =
    (DefaultLfsrSeed + lane * LfsrSeedLaneStep) &
      ((BigInt(1) << LfsrWidth) - 1)

  /** UI per unit of `txClkPostamble`: one forwarded clock cycle, a rising and a
    * falling edge.
    */
  val ClkPostambleUi = 2
  require(
    Phy.SerdesRatio % ClkPostambleUi == 0 &&
      isPow2(Phy.SerdesRatio / ClkPostambleUi),
    s"a packet must be a power-of-two number of $ClkPostambleUi UI postamble units"
  )

  /** UI a gated clock spends low before it runs again after a high park, which
    * UCIe allows to be 1 to 8.
    */
  val ClkPreconditionUi = 2
  require(ClkPreconditionUi >= 1 && ClkPreconditionUi <= 8)

  /** `rxValidStartThreshold` out of reset: start on the first set bit, so any
    * valid waveform frames. Raise it (up to the waveform's first run, 4 for
    * `0x0f0f0f0f`) if a lone bit error on an idle valid lane frames runs.
    */
  val rxValidStartThresholdReset = 1

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

  /** `state` after `steps` single steps of the Fibonacci LFSR that
    * `chisel3.util.random.FibonacciLFSR` builds from [[LfsrTaps]]: each step
    * shifts towards the top bit and enters the XOR of the tapped bits at bit 0.
    * The taps are sparse, so this is a few XOR gates per bit.
    */
  def lfsrAdvance(state: UInt, steps: Int): UInt = {
    var s: Seq[Bool] = state.asBools
    for (_ <- 0 until steps) {
      s = LfsrTaps.toSeq.map(t => s(t - 1)).reduce(_ ^ _) +: s.dropRight(1)
    }
    VecInit(s).asUInt
  }

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
    * `laneWords` holds the `numLanes + 3` words of one received packet, in the
    * order the RX buffers them: data lanes `0 until numLanes`, then valid,
    * track, and loopback. Fold every packet the RX counts, in order, to get the
    * expected signature for a given `rxPacketsReceived`.
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
    * The TX sends its Fibonacci LFSR's output in the order it was generated, so
    * every bit on the wire is the XOR of the bits `LfsrTaps` UIs before it.
    * `history` holds the previous word in its high half and the one before in
    * its low half: the `LfsrWidth` bits the largest tap reaches back.
    */
  def selfSyncErrors(word: UInt, history: UInt): UInt = {
    val window = Cat(word, history)
    VecInit((0 until Phy.SerdesRatio).map { j =>
      LfsrTaps.toSeq
        .map(t => window(LfsrWidth + j - t))
        .reduce(_ ^ _) ^ word(j)
    }).asUInt
  }

  /** Sticky flag for a queue whose producer cannot be stalled. Sets when the
    * producer offers a word to a full queue. Call it in the enqueue clock
    * domain, under that domain's reset.
    *
    * Ready is also low for a few cycles after reset, until the two ends finish
    * their handshake. That is not a full queue, so the flag arms only once
    * ready has been seen.
    */
  def overflowFlag(enqReady: Bool): Bool = {
    val primed = RegInit(false.B)
    val flag = RegInit(false.B)
    when(enqReady) { primed := true.B }
    when(primed && !enqReady) { flag := true.B }
    flag
  }

  /** Sticky flag for a queue whose consumer takes a word every cycle. Sets when
    * the queue runs dry. Call it in the dequeue clock domain, under that
    * domain's reset.
    *
    * The queue is empty after reset until the producer's first word arrives,
    * and whenever its producer is not feeding it. So the flag arms only once a
    * word has been seen with `feeding` high ever since the reset.
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
    queueParams: AsyncQueueParams = AsyncQueueParams(depth = 32),
    // Whether this tester has the TX data debug lane and its `txData` bump. A
    // chip that wants only one such lane builds it into a single link.
    hasTxDebugLane: Boolean = true
)(implicit includeDefaultModels: Boolean = false)
    extends Module
    with RequireAsyncReset {
  val io = IO(
    new PhyTestIO(bufferDepthPerLane, numLanes, bitCounterWidth, hasTxDebugLane)
  )

  // Every register here resets asynchronously on its domain's reset, which
  // asserts with the UCIe reset and releases on the domain's clock. So the
  // lanes, termination, AFE and pad controls behind this block hold their
  // reset values from chip reset on, even before the PHY test clock starts.
  //
  // Software resets (FSM and counter strobes, and modes that hold an FSM) are
  // synchronous clears on top. Each sets its registers to their reset values
  // in the cycle it is high, over any other write. They are synchronous
  // because they combine registers and modes, which can glitch, and an
  // asynchronous reset pin would take a glitch as a reset. They are applied
  // at the end of the module, after every other write, so they win.
  private val softClears = scala.collection.mutable.ArrayBuffer[() => Unit]()
  private def softRegInit[T <: Data](clear: Bool)(init: T): T = {
    val r = RegInit(init)
    softClears += (() => when(clear) { r := init })
    r
  }
  private def softRegNext[T <: Data](clear: Bool)(next: T, init: T): T = {
    val r = softRegInit(clear)(init)
    r := next
    r
  }

  // The clock dividers (global ones in the PHY and the one in every tile,
  // these lanes' included) reset only from the divider resets. They all
  // release together, which fixes the phase between a tile's load edge and
  // the divided clock words are handed over on. Datapath resets do not touch
  // them, so a datapath restart keeps that phase.
  //
  // They do not use this module's reset: it releases on the PHY test clock,
  // which the dividers do not run on, so it could release them on any edge.
  // The divider resets come from the chip clock and release while the clocks
  // they hold are stopped.
  val txDividerRstb = io.txDividerRstb
  val rxDividerRstb = io.rxDividerRstb

  // `txDatapathRst` restarts the TX and `rxDatapathRst` the RX. Each resets
  // the logic on its divided clock, the async queue's PHY side included,
  // while that clock keeps running, so a strobe flushes the datapath instead
  // of freezing it. Neither stops a divider.
  io.txDatapathRst := io.regs.txDatapathRst
  io.rxDatapathRst := io.regs.rxDatapathRst
  // The same strobes, each from a flop that this module's reset sets, for the
  // debug and loopback lanes' datapath synchronizers. A flop, not an OR, so
  // the synchronizer input cannot glitch.
  val txDatapathRstReg = RegNext(io.regs.txDatapathRst, true.B)
  val rxDatapathRstReg = RegNext(io.regs.rxDatapathRst, true.B)

  // The two bands are independent: either can carry TileLink while the other
  // stays under test control.
  val mbManual = io.regs.mainbandMode === BandMode.manual
  val sbManual = io.regs.sidebandMode === BandMode.manual

  // Sideband tester: owns the sideband bumps end to end, independent of the
  // mainband TX/RX FSMs below. Disabled while TileLink owns the sideband, so
  // it does not drive the bumps or turn TL frames into bogus packets.
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
  // The PHY hands over raw nets; each bump, `mux0` and `mux1`, is a pad driver
  // behind a 16:1 `ClkMux` and select of its own, so the two can watch any
  // pair of `PhyTest.DebugMuxInputs` at once. Neither cell inverts, and
  // nothing here samples what they watch, so only the quasi-static selects
  // cross clock domains.
  val debugMuxIns: Seq[Clock] = PhyTest.DebugMuxInputs.map { name =>
    io.debug.elements(name) match {
      case c: Clock => c
      case b: Bool  => b.asClock
      case other =>
        throw new IllegalArgumentException(
          s"debug mux input $name is a ${other.getClass.getSimpleName}"
        )
    }
  }
  val debugBumps = Seq(io.bumps.mux0, io.bumps.mux1)
  for (i <- 0 until PhyTest.NumDebugDrivers) {
    val mux = Module(new ClkMux)
    mux.suggestName(s"mux$i")
    val muxOut = mux.connect(debugMuxIns, io.regs.debugMuxSel(i))
    val driver = Module(new PadDriver)
    driver.suggestName(s"mux${i}_driver")
    driver.io.din := muxOut.asBool
    driver.io.ctl := io.regs.driverctl(i)
    debugBumps(i) := driver.io.dout
  }

  // A TX lane outside the PHY's lane clock distribution network: async queue
  // onto the TX divided clock, bit shuffler, and lane. Used for the TX data
  // debug lane and the loopback transmitter, both on the PHY lanes' TX data
  // clock. Feed the queue every cycle while `feeding`, idle words included,
  // or its underflow flag means nothing. Returns the enqueue side (PHY test
  // clock domain), the lane, and the sticky underflow flag synchronized to
  // the PHY test clock.
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

    // The tile has no divided clock output, so words are handed over on the
    // TX divided clock, which the tiles take their words on.
    val divRstSync = Module(new RstSync)
    divRstSync.suggestName(s"${name}_div_rst_sync")
    divRstSync.io.rstbAsync := !txDatapathRstReg
    divRstSync.io.clk := io.debug.txDivClk

    val fifo = Module(new AsyncQueue(UInt(Phy.SerdesRatio.W), queueParams))
    fifo.suggestName(s"${name}_fifo")
    fifo.io.enq_clock := clock
    fifo.io.enq_reset := reset.asBool
    fifo.io.deq_clock := io.debug.txDivClk
    fifo.io.deq_reset := !divRstSync.io.rstbSync
    fifo.io.deq.ready := true.B
    val underflow = withClockAndReset(
      io.debug.txDivClk,
      (!divRstSync.io.rstbSync).asAsyncReset
    ) {
      PhyTest.underflowFlag(fifo.io.deq.valid, feeding)
    }

    // An empty queue sends zeros rather than repeating the last word.
    val din = Mux(fifo.io.deq.valid, fifo.io.deq.bits, 0.U)
    // The backup handoff phase the PHY's own lanes have, for the same reason:
    // the word relaunched on the other edge of the TX divided clock.
    val dinNeg = withClockAndReset(
      (!io.debug.txDivClk.asBool).asClock,
      (!divRstSync.io.rstbSync).asAsyncReset
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
  // One TX lane on its own bump, fed by a cut-down copy of the mainband TX FSM
  // below. The pattern is the `txDebugData` registers or an LFSR. There is no
  // capture SRAM behind it, so the manual buffer is the registers themselves.
  if (hasTxDebugLane) {
    val txDebugRst = io.regs.txDebugFsmRst || reset.asBool
    val txDebugState = softRegInit(txDebugRst)(TxTestState.idle)
    val txDebugPacketsEnqueued = softRegInit(txDebugRst)(0.U(bitCounterWidth.W))
    val txDebugAddr =
      softRegInit(txDebugRst)(0.U(log2Ceil(PhyTest.DebugBufferPackets).W))
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
    io.bumps.txData.get := txDebugLane.io.dout
    io.regs.txDebugUnderflow := txDebugUnderflow
    io.regs.txDebugState := txDebugState
    io.regs.txDebugPacketsEnqueued := txDebugPacketsEnqueued

    val txDebugValid = Wire(Bool())
    txDebugValid := false.B
    // Always fed, with zeros outside a run (what an empty queue sends anyway),
    // so it runs dry only when the PHY test clock cannot keep up.
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
  } else {
    // No lane, so nothing for the FSM registers to run: report it idle.
    io.regs.txDebugState := TxTestState.idle
    io.regs.txDebugPacketsEnqueued := 0.U
    io.regs.txDebugUnderflow := false.B
  }

  // LOOPBACK LANE
  //
  // A TX lane wired to an RX lane on chip, to test the serializer, driver,
  // AFE, and deserializer without a partner die or a bump.
  // `TestTarget.loopback` points the TX and RX FSMs below at this pair. It
  // has its own slot in the pattern and capture SRAMs.
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
  rxLoopbackRstSync.io.rstbAsync := !rxDatapathRstReg
  rxLoopbackRstSync.io.clk := rxLoopbackLane.io.divclk.asClock

  val rxLoopbackFifo = Module(
    new AsyncQueue(UInt(Phy.SerdesRatio.W), queueParams)
  )
  rxLoopbackFifo.io.enq_clock := rxLoopbackLane.io.divclk.asClock
  rxLoopbackFifo.io.enq_reset := !rxLoopbackRstSync.io.rstbSync
  // The deserializer has no valid of its own: it hands over a word every
  // divided cycle whether or not the TX is sending.
  rxLoopbackFifo.io.enq.valid := true.B
  // The tile outputs its word on its divided clock's falling edge, and the
  // queue enqueues on the rising edge half a word later, as the mainband's
  // queues do on `rxDivClk`. `sample_negedge` registers the word on the
  // falling edge instead: a backup in case the tile updates on the other
  // edge. No added latency either way.
  val rxLoopbackWordNeg = withClockAndReset(
    (!rxLoopbackLane.io.divclk).asClock,
    (!rxLoopbackRstSync.io.rstbSync).asAsyncReset
  ) {
    RegNext(rxLoopbackShuffler.io.dout, 0.U(Phy.SerdesRatio.W))
  }
  rxLoopbackFifo.io.enq.bits := Mux(
    io.regs.loopbackRxctl.sample_negedge,
    rxLoopbackWordNeg,
    rxLoopbackShuffler.io.dout
  )
  rxLoopbackFifo.io.deq_clock := clock
  rxLoopbackFifo.io.deq_reset := reset.asBool
  // With no valid on the enqueue side, a full queue loses the word.
  val rxLoopbackOverflow = withClockAndReset(
    rxLoopbackLane.io.divclk.asClock,
    (!rxLoopbackRstSync.io.rstbSync).asAsyncReset
  ) {
    PhyTest.overflowFlag(rxLoopbackFifo.io.enq.ready)
  }
  io.regs.loopbackRxOverflow :=
    RegNext(RegNext(rxLoopbackOverflow, false.B), false.B)

  // TX registers
  val txReset = io.regs.txFsmRst || !mbManual || reset.asBool
  val txState = softRegInit(txReset)(TxTestState.idle)
  val txPacketsEnqueued = softRegInit(txReset)(0.U(bitCounterWidth.W))
  val inputBufferAddrReg = softRegInit(txReset)(0.U((bufferDepthPerLane - 5).W))
  // One LFSR per lane, in per-lane vector order. Valid's is never read (valid
  // carries a framing waveform, not a pattern), so it optimizes away.
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
  val loadedFirstChunk = softRegInit(txReset)(false.B)
  val txManualRepeatPeriod = Mux(
    io.regs.txManualRepeatPeriod === 0.U || io.regs.txManualRepeatPeriod > maxSramPackets,
    maxSramPackets,
    io.regs.txManualRepeatPeriod
  )

  // RX registers
  val rxReset =
    io.regs.rxFsmRst || (!mbManual && !io.regs.rxMonitor) || reset.asBool
  val rxPacketsReceived =
    softRegInit(rxReset)(0.U((64 - log2Ceil(Phy.SerdesRatio)).W))
  // A bit offset within a word, so `log2Ceil(SerdesRatio)` bits is right. The
  // assigned `SerdesRatio - startIdx` is a full word when valid's set bit is
  // bit 0, and a full-word offset equals no offset. The wrap to zero is the
  // intended modulo, not a truncation bug.
  val rxReceiveOffset = softRegInit(rxReset)(0.U(log2Ceil(Phy.SerdesRatio).W))
  /// One count per framing per lane: the data lanes, then valid, track and
  /// loopback. See `rxBitErrorsEarly` in `PhyTestRegsIO` for the framings.
  def perFramingPerLane(width: Int) = VecInit(
    Seq.fill(PhyTest.NumFramings)(
      VecInit(Seq.fill(PhyTest.numTestLanes(numLanes))(0.U(width.W)))
    )
  )
  val rxBitErrors = softRegInit(rxReset)(perFramingPerLane(64))
  val rxPacketsReceivedOutput = softRegInit(rxReset)(0.U(64.W))
  val rxErrorMask = softRegInit(rxReset)(perFramingPerLane(Phy.SerdesRatio))
  val rxBitErrorsOutput = softRegInit(rxReset)(perFramingPerLane(64))
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
  val rxManualRow = softRegInit(rxReset)(0.U((bufferDepthPerLane - 5).W))

  // The `Phy.SerdesRatio` pattern bits a packet is compared against, oldest bit
  // first, in the same order the TX shifts them out of its own LFSR.
  def refWord(state: UInt): UInt =
    Reverse(state(PhyTest.LfsrWidth - 1, Phy.SerdesRatio))
  // The same window one UI further along the pattern, for a packet that was framed
  // one UI late.
  def refWordAdvanced(state: UInt): UInt =
    Reverse(state(PhyTest.LfsrWidth - 2, Phy.SerdesRatio - 1))
  // The same window one UI behind, for a packet framed one UI early. The bit
  // that has already left the state is recovered by running the Fibonacci
  // feedback backwards: it is the only unknown in the tap XOR that produced
  // the state's newest bit.
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

  val rxSignature = softRegInit(rxReset)(0.U(PhyTest.SignatureWidth.W))
  val rxSignatureOutput = softRegInit(rxReset)(0.U(PhyTest.SignatureWidth.W))
  /// Lane words of the packet that completed this cycle, and whether that packet
  /// should be folded into the signature. Driven by the RX logic further below.
  val rxLaneWords = Wire(Vec(numLanes + 3, UInt(Phy.SerdesRatio.W)))
  for (lane <- 0 until numLanes + 3) {
    rxLaneWords(lane) := 0.U
  }
  val rxSignatureUpdate = Wire(Bool())
  rxSignatureUpdate := false.B

  // Fold every counted packet into a MISR: XOR its lane words into the
  // signature, then take one LFSR step. It needs no model of the sent
  // pattern, so it checks any TX test mode over any run length, but gives
  // only pass/fail. Lane `l` is rotated left by `l` first, so identical
  // failures on lanes with identical data (common for a manual pattern) do
  // not cancel. Kept in step with `PhyTest.signatureNext`, the software model.
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

  // A pattern lane's LFSR word as `txLaneFixed` and `txLaneInvert` have it
  // sent.
  def txLaneWord(lane: Int, lfsr: UInt): UInt =
    Mux(
      io.regs.txLaneFixed(lane),
      io.regs.txFixedWord,
      Mux(io.regs.txLaneInvert(lane), ~lfsr, lfsr)
    )

  // The forwarded-clock lanes carry a fixed pattern rather than a test one,
  // driven below where `txClkGate` parks them.

  // True for a data packet, whichever target it goes to.
  val tx_valid = Wire(Bool())
  tx_valid := false.B
  // The mainband only takes data: between data words `TxClkGater` keeps the
  // lanes going itself, clock and track included.
  io.tx.valid := mbManual && tx_valid &&
    io.regs.testTarget === TestTarget.mainband
  // Whether the run still has data to send, bursts aside: `tx_valid` is this
  // outside the idle packets between bursts.
  val txWantData = Wire(Bool())
  txWantData := false.B

  // BURSTS
  //
  // A run is bursts separated by gaps of `txIdlePackets` packets; without
  // bursts, one burst. On the mainband `TxClkGater` makes the gaps (after the
  // packet with `txGapAfter`) and gates the clock. On the loopback lane,
  // which has no forwarded clock, this module sends the idle packets. Counts
  // here are of packets the target accepted.
  val txBursting =
    io.regs.txBurstPackets =/= 0.U && io.regs.txIdlePackets =/= 0.U
  val txInGap = softRegInit(txReset)(false.B)
  val txGapCount = softRegInit(txReset)(0.U(32.W))
  val txBurstCount = softRegInit(txReset)(0.U(32.W))
  val txGapLen = io.regs.txIdlePackets
  val txBurstEnds =
    txBursting && txBurstCount + 1.U >= io.regs.txBurstPackets

  // The loopback lane is fed only while it is the target, so a mainband run
  // leaves it quiet. Then it takes a word every cycle, zeros outside data
  // packets (what an empty queue sends anyway). So gaps keep their length on
  // the wire, and the queue runs dry only if the PHY test clock is too slow.
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
          }
        }.elsewhen(tx_valid && txBursting) {
          when(txBurstEnds) {
            txBurstCount := 0.U
            txInGap := io.regs.testTarget === TestTarget.loopback
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
                // Every pattern lane takes its own LFSR, or a fixed word or
                // its stream inverted; valid takes the framing waveform
                // instead.
                def lfsrWord(lane: Int): UInt =
                  txLaneWord(lane, Reverse(txLfsrs(lane).io.out.asUInt)(31, 0))
                for (lane <- 0 until numLanes) {
                  txWord.data(lane) := lfsrWord(lane)
                }
                txWord.valid := io.regs.txValid
                txWord.track := lfsrWord(PhyTest.trackLane(numLanes))
              }
              is(TestTarget.loopback) {
                val lane = PhyTest.loopbackLane(numLanes)
                txLoopbackWord := txLaneWord(
                  lane,
                  Reverse(txLfsrs(lane).io.out.asUInt)(31, 0)
                )
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

  // A data packet runs the clock all through; `TxClkGater` makes the
  // words around it, gated or not.
  txWord.clkp := io.regs.txClkP
  txWord.clkn := io.regs.txClkN
  io.txContinues := io.regs.txDataMode === DataMode.infinite ||
    txPacketsEnqueued + 1.U < io.regs.txPacketsToSend
  // No gap after the run's last burst: nothing follows it to gap before.
  io.txGapAfter := txBurstEnds && io.txContinues

  when(io.regs.txTrackClk) {
    txWord.track := io.regs.txTrackWord
  }

  // Valid also goes out on the lane `txValidLaneSel` names, in place of its
  // own pattern. The displaced data is not sent anywhere (see
  // `txValidLaneSel`).
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
  val txInjectArmed = softRegInit(txReset)(false.B)
  val txInjectNow = txInjectArmed && tx_valid && txTargetReady
  when(txInjectNow) {
    txInjectArmed := false.B
  }
  when(io.regs.txInjectError) {
    txInjectArmed := true.B
  }
  def injected(word: UInt, lane: Int): UInt =
    word ^ (txInjectNow && io.regs.txInjectLane === lane.U).asUInt

  val txWordInjected = Wire(new TxIO(numLanes))
  txWordInjected := txWord
  for (lane <- 0 until numLanes) {
    txWordInjected.data(lane) := injected(txWord.data(lane), lane)
  }
  txWordInjected.valid := injected(txWord.valid, PhyTest.validLane(numLanes))
  txWordInjected.track := injected(txWord.track, PhyTest.trackLane(numLanes))
  txLoopbackEnq.bits :=
    injected(txLoopbackWord, PhyTest.loopbackLane(numLanes))

  io.tx.bits := txWordInjected

  // RX logic

  io.rx.ready := true.B
  rxLoopbackFifo.io.deq.ready := true.B
  // The lane the RX frames on.
  val rxValidLaneWord =
    Phy.rxLaneWords(io.rx.bits, numLanes)(io.regs.rxValidLaneSel)
  // The loopback receiver hands over a word every divided cycle, even when
  // nothing is sent. So its lane reads zero unless it is the target, which
  // keeps whatever the idle lane picks up out of the capture SRAM and the
  // signature during a mainband run.
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
  val recordingStarted = softRegInit(rxReset)(false.B)
  val rxIdleWordsReg = softRegInit(rxReset)(0.U(32.W))
  io.regs.rxIdleWordsObserved := rxIdleWordsReg
  val startRecording = Wire(Bool())
  // Where the first packet starts, with the previous word's UIs as 0 to 31
  // and this word's as 32 to 63. It is in the previous word if the valid run
  // that starts recording began there.
  val startPos = Wire(UInt(log2Ceil(2 * Phy.SerdesRatio).W))
  startRecording := false.B
  startPos := Phy.SerdesRatio.U
  val startInPrev = startRecording && !startPos(log2Ceil(Phy.SerdesRatio))
  // Where in this word the data the start takes from it begins: all of it
  // when the packet started in the previous word.
  val startIdx = Mux(
    startRecording && !startInPrev,
    startPos(log2Ceil(Phy.SerdesRatio) - 1, 0),
    0.U
  )
  val rxFrameOffset = softRegInit(rxReset)(0.U(log2Ceil(Phy.SerdesRatio).W))
  when(startRecording) {
    rxFrameOffset := startPos(log2Ceil(Phy.SerdesRatio) - 1, 0)
    rxReceiveOffset :=
      Phy.SerdesRatio.U - startPos(log2Ceil(Phy.SerdesRatio) - 1, 0)
  }
  io.regs.rxFrameOffset := rxFrameOffset
  // Where this word's data lands in `runningData`: past the part of the
  // previous word the first packet keeps, on a start there.
  val rxOffsetNow = Mux(
    startInPrev,
    (Phy.SerdesRatio.U - startPos(log2Ceil(Phy.SerdesRatio) - 1, 0))(
      log2Ceil(Phy.SerdesRatio) - 1,
      0
    ),
    rxReceiveOffset
  )

  // numLanes data lanes, 1 valid lane, 1 track lane, 1 loopback lane. Until
  // recording starts, the last word received.
  val runningData =
    softRegInit(rxReset)(VecInit(Seq.fill(numLanes + 3)(0.U(32.W))))

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
  val rxHistory = softRegInit(rxReset)(
    VecInit(
      Seq.fill(PhyTest.numTestLanes(numLanes))(0.U(PhyTest.LfsrWidth.W))
    )
  )
  // Packets processed since the FSM reset, up to three: the two the history
  // holds, and the one `rxAutoSeed` skips before them.
  val rxHistoryFill = softRegInit(rxReset)(0.U(2.W))
  val rxHistoryFull = rxHistoryFill >= 2.U
  when(rxProcessPacket && rxHistoryFill =/= 3.U) {
    rxHistoryFill := rxHistoryFill + 1.U
  }

  // ERROR-TRIGGERED CAPTURE
  //
  // `rxErrorMask` is a register, so a packet's errors appear a cycle after
  // `rxPacketsReceived` counts it. The packet is already in the SRAM, since
  // the capture was still open. Under `rxManualCheck` errors appear a cycle
  // later still, so the next packet may also be counted and on its way into
  // the SRAM. It counts toward the post-trigger packets, and is dropped if
  // there are to be none.
  val rxProcessedLast = softRegNext(rxReset)(rxProcessPacket, false.B)
  val rxScoredBehind = io.regs.rxManualCheck && rxProcessedLast
  val rxWriteInFlight = io.regs.rxManualCheck && shouldWriteStaged
  val rxCaptureError = io.regs.rxCaptureMode === CaptureMode.error
  val rxTriggered = softRegInit(rxReset)(false.B)
  val rxTriggerPacket = softRegInit(rxReset)(0.U(rxPacketsReceived.getWidth.W))
  val rxCaptureRemaining =
    softRegInit(rxReset)(0.U(io.regs.rxCapturePostTrigger.getWidth.W))
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

  // ERROR LOCATIONS AND CLASSES
  //
  // Both read the nominal masks the counts above add up, in the cycle they do,
  // and only on the lanes that carry the current target's traffic.
  val rxNominalMasks = VecInit(
    (0 until PhyTest.numTestLanes(numLanes)).map(lane =>
      Mux(
        rxTargetLanes(lane),
        rxErrorMask(PhyTest.NominalFraming)(lane),
        0.U
      )
    )
  )
  val rxErrPos = softRegInit(rxReset)(
    VecInit(Seq.fill(PhyTest.numTestLanes(numLanes))(0.U(32.W)))
  )
  for (lane <- 0 until PhyTest.numTestLanes(numLanes)) {
    rxErrPos(lane) := rxErrPos(lane) | rxNominalMasks(lane)
  }
  io.regs.rxErrPosMask := Mux(
    io.regs.rxErrPosLane < PhyTest.numTestLanes(numLanes).U,
    rxErrPos(io.regs.rxErrPosLane),
    0.U
  )
  io.regs.rxLaneErrored := VecInit(rxErrPos.map(_.orR)).asUInt

  // The word each mask was scored from: one capture pipeline stage down, or
  // two under the manual check, whose masks land a cycle later.
  def rxScoredWord(lane: Int): UInt = Mux(
    io.regs.rxManualCheck,
    toWriteDelayed(lane >> 2)(lane % 4),
    toWriteStaged(lane >> 2)(lane % 4)
  )
  val rxClassValid = io.regs.rxErrClassLane < PhyTest.numTestLanes(numLanes).U
  val rxClassMask = Mux(
    rxClassValid,
    rxNominalMasks(io.regs.rxErrClassLane),
    0.U
  )
  val rxClassSent = VecInit(
    (0 until PhyTest.numTestLanes(numLanes)).map(rxScoredWord)
  )(io.regs.rxErrClassLane) ^ rxClassMask
  // Packet UI `i` is UI `i + startIdx` of the lane's word, UI
  // `i + startIdx - rxUiDelay` of the deserializer's, and the deserializer
  // samples its word's even UIs on its clock's rising edge. `rxOffsetNow` is
  // 32 - `startIdx`, so it has the same parity. The loopback lane has no
  // delay.
  val rxClassDelayOdd =
    io.regs.rxErrClassLane < Phy.numRxDataLanes(numLanes).U &&
      VecInit(io.regs.rxUiDelay.map(_(0)))(io.regs.rxErrClassLane)
  val rxRiseUis = Mux(
    rxOffsetNow(0) ^ rxClassDelayOdd,
    BigInt("aaaaaaaa", 16).U(32.W),
    BigInt("55555555", 16).U(32.W)
  )
  val rxErrClasses = Seq(
    rxClassMask & rxRiseUis & rxClassSent,
    rxClassMask & rxRiseUis & ~rxClassSent,
    rxClassMask & ~rxRiseUis & rxClassSent,
    rxClassMask & ~rxRiseUis & ~rxClassSent
  )
  val rxErrClassCounts = softRegInit(rxReset)(
    VecInit(Seq.fill(rxErrClasses.length)(0.U(bitCounterWidth.W)))
  )
  val rxErrClassOutput = softRegInit(rxReset)(
    VecInit(Seq.fill(rxErrClasses.length)(0.U(bitCounterWidth.W)))
  )
  for ((errs, i) <- rxErrClasses.zipWithIndex) {
    val next = rxErrClassCounts(i) +& PopCount(errs)
    rxErrClassCounts(i) := Mux(next > maxBitCount, maxBitCount, next)
  }
  when(!rxPauseErrors) {
    rxErrClassOutput := rxErrClassCounts
  }
  io.regs.rxErrRiseOne := rxErrClassOutput(0)
  io.regs.rxErrRiseZero := rxErrClassOutput(1)
  io.regs.rxErrFallOne := rxErrClassOutput(2)
  io.regs.rxErrFallZero := rxErrClassOutput(3)

  // Check valid streak after each packet is dequeued.
  when(
    Mux(
      io.regs.testTarget === TestTarget.mainband,
      io.rx.ready & io.rx.valid,
      rxLoopbackFifo.io.deq.ready & rxLoopbackFifo.io.deq.valid
    )
  ) {

    // Find where recording starts, if it has not already: the first run of
    // `rxValidStartThreshold` set bits on valid that ends in this word, or
    // the loopback lane's first set bit, since that lane carries data. A run
    // that ended in the previous word would have started it there.
    val loopbackTarget = io.regs.testTarget === TestTarget.loopback
    val startWindow = Cat(
      0.U(Phy.SerdesRatio.W),
      Mux(loopbackTarget, rxLoopbackFifo.io.deq.bits, rxValidLaneWord),
      Mux(
        loopbackTarget,
        runningData(PhyTest.loopbackLane(numLanes)),
        runningData(io.regs.rxValidLaneSel)
      )
    )
    val threshold = Mux(
      loopbackTarget || io.regs.rxValidStartThreshold === 0.U,
      1.U,
      Mux(
        io.regs.rxValidStartThreshold > Phy.SerdesRatio.U,
        Phy.SerdesRatio.U,
        io.regs.rxValidStartThreshold
      )
    )
    val thresholdMask =
      ((1.U((Phy.SerdesRatio + 1).W) << threshold) - 1.U)(
        Phy.SerdesRatio - 1,
        0
      )
    for (p <- 2 * Phy.SerdesRatio - 1 to 0 by -1) {
      val run = (startWindow(p + Phy.SerdesRatio - 1, p) & thresholdMask) ===
        thresholdMask
      val endsHere =
        if (p >= Phy.SerdesRatio) true.B
        else threshold > (Phy.SerdesRatio - p).U
      when(!recordingStarted && run && endsHere) {
        startRecording := true.B
        startPos := p.U
      }
    }
    // Or start on the first word, as it stands.
    when(!recordingStarted && io.regs.rxStartMode === RxStartMode.immediate) {
      startRecording := true.B
      startPos := Phy.SerdesRatio.U
    }

    // Free running and deliberately not saturating: read it twice, and a
    // change means idle words are still arriving. A saturated counter cannot
    // tell pegged from stopped. At 32 bits it wraps after about 8 s at the
    // top rate (a word about every 2 ns), far longer than any bringup read.
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
        rxOffsetNow +& Phy.SerdesRatio.U - startIdx >= 32.U
      // In burst mode, a packet whose valid word reads closer to idle than to
      // the waveform falls between bursts. Closer rather than equal, so that a
      // bit error on valid does not turn one into the other.
      val alignedValidWord = rxLaneWords(io.regs.rxValidLaneSel)
      val packetIsValid = !io.regs.rxBurstMode ||
        io.regs.testTarget === TestTarget.loopback ||
        PopCount(alignedValidWord ^ io.regs.rxValid) <
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
      dataMask := ((1.U << (Phy.SerdesRatio.U - startIdx)) - 1.U) << rxOffsetNow
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
        data := (rawData << rxOffsetNow) >> startIdx
        // On a start in the previous word, the part of it the first packet
        // keeps.
        val kept = Mux(
          startInPrev,
          runningData(lane) >> startPos(log2Ceil(Phy.SerdesRatio) - 1, 0),
          runningData(lane)
        )
        val newData = Wire(UInt(64.W))
        newData := (data & dataMask) | (kept & keepMask)
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
          // Score the packet at all three framings, so a valid bit error at
          // alignment time does not spoil the run.
          // Scores a lane against the framing waveform instead of a pattern.
          def scoreAgainstValid(errIdx: Int): Unit = {
            rxErrorMask(PhyTest.NominalFraming)(errIdx) :=
              newData(31, 0) ^ io.regs.rxValid
            rxErrorMask(PhyTest.EarlyFraming)(errIdx) :=
              newData(31, 0) ^ validRefAdvanced(io.regs.rxValid)
            rxErrorMask(PhyTest.LateFraming)(errIdx) :=
              newData(31, 0) ^ validRefDelayed(io.regs.rxValid)
          }
          if (lane == PhyTest.validLane(numLanes)) {
            // The dedicated valid lane always carries the framing waveform,
            // whether or not the partner also moved it onto another lane.
            scoreAgainstValid(lane)
          } else {
            // Data, track, and loopback are scored against the LFSR at their
            // own index, or against the framing waveform if the partner moved
            // it onto this lane. The LFSR advances either way, so references
            // stay in step across lanes and across a select change.
            rxLfsrs(lane).io.increment := true.B
            val state = rxLfsrs(lane).io.out.asUInt
            val carriesValid =
              if (lane <= PhyTest.trackLane(numLanes)) {
                io.regs.rxValidLaneSel === lane.U
              } else {
                false.B
              }
            // What the partner's `txLaneFixed`/`txLaneInvert` made of this
            // lane's stream, at each framing. A fixed word repeats every
            // packet, so a UI's slip is a rotation of it, as for valid.
            val fixed = io.regs.rxLaneFixed(lane)
            val inverted = io.regs.rxLaneInvert(lane)
            def expected(ref: UInt, fixedRef: UInt): UInt =
              Mux(fixed, fixedRef, Mux(inverted, ~ref, ref))
            // `rxAutoSeed`: the third packet loads the LFSR from the second
            // and third, oldest UI in its top bit, advanced two packets to
            // the next one. Instead of the step, so it can't move the state
            // on.
            val autoSeeding = io.regs.rxAutoSeed && !fixed && !carriesValid
            val seedWindow = Cat(
              newData(31, 0),
              rxHistory(lane)(PhyTest.LfsrWidth - 1, Phy.SerdesRatio)
            )
            val seedStream = Mux(inverted, ~seedWindow, seedWindow)
            when(
              autoSeeding && rxHistoryFill === 2.U && seedStream =/= 0.U &&
                !rxReset
            ) {
              rxLfsrs(lane).io.seed.valid := true.B
              rxLfsrs(lane).io.seed.bits := PhyTest
                .lfsrAdvance(Reverse(seedStream), 2 * Phy.SerdesRatio)
                .asTypeOf(rxLfsrs(lane).io.seed.bits)
              rxLfsrs(lane).io.increment := false.B
            }
            // Nothing to score against until then.
            val unseeded = autoSeeding && rxHistoryFill =/= 3.U
            when(carriesValid) {
              scoreAgainstValid(lane)
            }.otherwise {
              rxErrorMask(PhyTest.NominalFraming)(lane) := Mux(
                io.regs.rxSelfSync && !fixed && !inverted,
                Mux(
                  rxHistoryFull,
                  PhyTest.selfSyncErrors(newData(31, 0), rxHistory(lane)),
                  0.U
                ),
                Mux(
                  unseeded,
                  0.U,
                  newData(31, 0) ^
                    expected(refWord(state), io.regs.rxFixedWord)
                )
              )
              rxErrorMask(PhyTest.EarlyFraming)(lane) := Mux(
                unseeded,
                0.U,
                newData(31, 0) ^ expected(
                  refWordAdvanced(state),
                  validRefAdvanced(io.regs.rxFixedWord)
                )
              )
              rxErrorMask(PhyTest.LateFraming)(lane) := Mux(
                unseeded,
                0.U,
                newData(31, 0) ^ expected(
                  refWordDelayed(state),
                  validRefDelayed(io.regs.rxFixedWord)
                )
              )
            }
          }
        }
      }
    }
  }

  // MANUAL PATTERN CHECK
  //
  // The TX pattern SRAM is read at the next processed packet's row, which
  // arrives the cycle after that packet, beside its words one stage down the
  // capture pipeline. The address comes from a register, which keeps the
  // late "is it processed" decision off it. Overrides every mask the LFSR
  // scoring above set.
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
  val trackOnes = softRegInit(trackCntReset)(0.U(32.W))
  val trackZeros = softRegInit(trackCntReset)(0.U(32.W))
  // Never more than `trackOnes`, so it cannot overflow first.
  val trackOnesEven = softRegInit(trackCntReset)(0.U(32.W))
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

  // Last, so that each software reset wins over every other write.
  softClears.foreach(_())
}
