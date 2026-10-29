/*
  Maps PhyBridgeAnalogIO onto the Chisel Phy model. This is the whole per-PHY
  porting surface. The digital controller does not know what a txctl or a
  vref_sel is, so a different PHY needs only a file like this one.
 */

package edu.berkeley.cs.uciedigital.phybridge

import chisel3._
import edu.berkeley.cs.uciedigital.phy._
import edu.berkeley.cs.uciedigital.phy.macros.{RxDataLane, TxLane}
import edu.berkeley.cs.uciedigital.common._
import edu.berkeley.cs.uciedigital.logphy.{AfeParams, PhyBridgeAnalogIO}

class ChiselPhyAdapter(numLanes: Int, afeParams: AfeParams) extends Module {
  val p = afeParams.bridge
  val slots = afeParams.slots

  // A slot index and a PHY lane index are the same number when a code reaches
  // a tile, so the two numberings must agree. A mismatch gives the track lane
  // the clock enable while the link still trains.
  require(
    slots.valid == Phy.validLane(numLanes) &&
      slots.track == Phy.trackLane(numLanes) &&
      slots.clkP == Phy.clkPLane(numLanes) &&
      slots.clkN == Phy.clkNLane(numLanes),
    s"PhySlots and Phy disagree on lane order at x$numLanes: " +
      s"slots valid=${slots.valid} track=${slots.track} " +
      s"clkP=${slots.clkP} clkN=${slots.clkN}, " +
      s"phy valid=${Phy.validLane(numLanes)} track=${Phy.trackLane(numLanes)} " +
      s"clkP=${Phy.clkPLane(numLanes)} clkN=${Phy.clkNLane(numLanes)}"
  )
  require(
    slots.count == Phy.numTxLanes(numLanes),
    s"PhySlots has ${slots.count} slots, PHY has ${Phy.numTxLanes(numLanes)}"
  )
  require(
    numLanes == afeParams.mbLanes,
    s"adapter built for x$numLanes but the digital side is x${afeParams.mbLanes}"
  )

  // The shuffler drives cancel the serdes tree with `Phy.treeBitOrder` both
  // ways, so it must be its own inverse. SerdesBitOrder checks this.
  SerdesBitOrder.check()

  val io = IO(new Bundle {
    val bridge = Flipped(new PhyBridgeAnalogIO(afeParams))
    val txctl = Output(Vec(numLanes + 4, new TxLaneDigitalCtlIO))
    val rxctl = Output(Vec(numLanes + 4, new RxLaneDigitalCtlIO))
    val clkGateEn = Output(Bool())
    val txResetb = Output(Bool())
    val rxResetb = Output(Bool())
  })

  val codes = io.bridge.codes
  val lanes = io.bridge.lanes

  // ===========================================================================
  // Codes
  // ===========================================================================
  // `Dctrl` is a 32-tap thermometer, so its code runs 0..32. Clamp it, so a sum
  // that overruns the line stays at maximum delay.
  val skewMax = TxLane.DelayTaps.U

  // `TxLane.thermometer` is elaboration time only and cannot take a hardware
  // code.
  def thermo(count: UInt, segments: Int): UInt =
    VecInit((0 until segments).map(i => count > i.U)).asUInt
  def thermoB(count: UInt, segments: Int): UInt =
    ~thermo(count, segments)

  def clockPhase: UInt =
    if (p.has(PhyBridgeParams.ClockPhaseKnob))
      codes.shared(PhyBridgeParams.ClockPhaseKnob)
    else 0.U

  def perLaneOr0(name: String, slot: Int): UInt =
    if (p.has(name)) codes.perLane(name)(slot) else 0.U

  // The default table has no driver strength knob, so all driver segments are
  // on. A PHY that declares the knob gets it here.
  def txSwingFor(slot: Int): UInt =
    if (p.has(PhyBridgeParams.TxSwingKnob))
      codes.perLane(PhyBridgeParams.TxSwingKnob)(slot)
    else TxLane.DriverSegments.U

  // No knob declared, so sec 9.5.3.23 bit 4 picks one of the two Table 5-6
  // settings, 0.0 dB or -2.2 dB. Every segment is this model's -2.2 dB leg. A
  // declared knob wins.
  def txEqFor(slot: Int): UInt =
    if (p.has(PhyBridgeParams.TxEqKnob))
      codes.perLane(PhyBridgeParams.TxEqKnob)(slot)
    else Mux(io.bridge.txEqEnable, TxLane.EqSegments.U, 0.U)

  // This PHY has no module-level phase interpolator, so the shared clock phase
  // and the per-lane deskew are summed here. The bridge keeps the phase 8 bits
  // wide to match the sideband edge codes.
  def skewFor(slot: Int): UInt = {
    val sum = clockPhase +& perLaneOr0(PhyBridgeParams.TxDeskewKnob, slot)
    Mux(sum > skewMax, skewMax, sum)
  }

  // ===========================================================================
  // Lane control
  // ===========================================================================
  for (lane <- 0 until numLanes + 4) {
    // The PHY numbers its lanes data, valid, track, clkP, clkN. Index by slot
    // name, because an offset from the vector end can swap the track and clock
    // enables, and the link still trains with no Track.
    val txEnable =
      if (lane < numLanes) lanes.mbTxDataEn(lane)
      else if (lane == slots.valid) lanes.mbTxValidEn
      else if (lane == slots.track) lanes.mbTxTrackEn
      else lanes.mbTxClkEn
    val rxEnable =
      if (lane < numLanes) lanes.mbRxDataEn(lane)
      else if (lane == slots.valid) lanes.mbRxValidEn
      else if (lane == slots.track) lanes.mbRxTrackEn
      else lanes.mbRxClkEn
    val txActive = txEnable && !io.bridge.doElectricalIdleTx
    val rxActive = rxEnable && !io.bridge.doElectricalIdleRx

    // A disabled lane drives neither the driver nor the equalizer
    // (`TxLaneCtlIO.off`, sec 4.3.7 tri-state), so the enable also gates the
    // equalizer.
    val txDriverSegments =
      Mux(txActive, txSwingFor(lane), 0.U)
    val txEqSegments =
      Mux(txActive, txEqFor(lane), 0.U)

    // Every rail is active high except ENP_EQ, where 0 turns a segment on
    // (TxLaneCtlIO). A wrong sense drives the lane wrong and does not fail.
    io.txctl(lane).tile.ENP := thermo(txDriverSegments, TxLane.DriverSegments)
    io.txctl(lane).tile.ENN := thermo(txDriverSegments, TxLane.DriverSegments)
    io.txctl(lane).tile.ENP_EQ := thermoB(txEqSegments, TxLane.EqSegments)
    io.txctl(lane).tile.ENN_EQ := thermo(txEqSegments, TxLane.EqSegments)

    // Held on an idle lane, because the delay is a trained code and parking the
    // lane must not lose it.
    io.txctl(lane).tile.Dctrl := thermo(skewFor(lane), TxLane.DelayTaps)

    // The TX tile sends `din(treeBitOrder(t))` in UI t. The same permutation
    // here cancels it, so the word goes out in plain bit order and an offset
    // capture reads as a rotation. The RX shuffler must match.
    for (bit <- 0 until Phy.SerdesRatio)
      io.txctl(lane).shuffler(bit) := Phy.treeBitOrder(bit).U(5.W)
    io.txctl(lane).sample_negedge := false.B
    io.txctl(lane).delay := 0.U

    io.rxctl(lane).zen := rxActive
    io.rxctl(lane).zctl := 0.U
    io.rxctl(lane).vref_sel := perLaneOr0(PhyBridgeParams.RxVrefKnob, lane)
    io.rxctl(lane).afeBypassEn := !rxActive
    io.rxctl(lane).afeOpCycles := 16.U
    io.rxctl(lane).afeOverlapCycles := 2.U
    io.rxctl(lane).afeBypass.aEn := false.B
    io.rxctl(lane).afeBypass.aPc := true.B
    io.rxctl(lane).afeBypass.bEn := false.B
    io.rxctl(lane).afeBypass.bPc := true.B
    io.rxctl(lane).afeBypass.selA := true.B

    // Mirror of the TX side. `dout(j)` is the bit received in UI
    // treeBitOrder(j), so this restores time order for later alignment.
    for (bit <- 0 until Phy.SerdesRatio)
      io.rxctl(lane).shuffler(bit) := Phy.treeBitOrder(bit).U(5.W)
    io.rxctl(lane).sample_negedge := false.B

    // Receiver deskew is the tile delay line, like TX `Dctrl`. With no rxDeskew
    // knob it adds no delay. The PHY does not read rxctl.delay.
    io.rxctl(lane).Dctrl :=
      thermo(
        perLaneOr0(PhyBridgeParams.RxDeskewKnob, lane),
        RxDataLane.DelayTaps
      )
    io.rxctl(lane).delay := 0.U
  }

  // No rate select here. The clocking tile takes MainClkSel and TxClkDiv, which
  // UcieTL's clocking registers derive from the bridge's freqSel.
  io.clkGateEn := lanes.mbTxClkEn && !io.bridge.doElectricalIdleTx
  io.txResetb := io.bridge.txResetb
  io.rxResetb := io.bridge.rxResetb

  // Note: The sec 4.5.3 calibrations complete over MMIO, so no responder here.

  // Note: The final connection depends on the implementation. This Phy build
  // does not instantiate a PLL model.
  io.bridge.pllLock := true.B
  io.bridge.clocksUngatedAndStable := true.B
}
