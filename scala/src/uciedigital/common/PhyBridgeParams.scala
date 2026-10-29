/*
  Parameters and bundles for the analog/digital boundary. An analog designer
  adds a tunable code here and gets a PhyBridge port and MMIO registers for it.
  Software searches the codes, so the training state machines do not change.
 */

package edu.berkeley.cs.uciedigital.common

import chisel3._
import chisel3.util._
import scala.collection.immutable.SeqMap

case class PhyKnob(
    name: String,
    width: Int,
    reset: Int,
    perLane: Boolean = true
) {
  require(width > 0, s"knob $name needs a positive width")
  require(
    reset >= 0 && reset < (BigInt(1) << width),
    s"knob $name reset $reset does not fit in $width bits"
  )

  // Record silently renames any other name, and the port would then no longer
  // match the knob's register names.
  require(
    name.matches("[A-Za-z][A-Za-z0-9_]*"),
    s"knob name '$name' must be alphanumeric starting with a letter"
  )
}

// Note: Slot indices, and so the register offsets, change with the Lane count.
case class PhySlots(numDataLanes: Int) {
  require(numDataLanes > 0)

  // The order must match Phy.validLane, trackLane, clkPLane and clkNLane,
  // because the adapter uses a slot index as a lane index. ChiselPhyAdapter
  // checks it.
  val valid: Int = numDataLanes
  val track: Int = numDataLanes + 1
  val clkP: Int = numDataLanes + 2
  val clkN: Int = numDataLanes + 3
  val count: Int = numDataLanes + 4
  val width: Int = log2Ceil(count)
}

case class PhyBridgeParams(
    knobs: Seq[PhyKnob] = PhyBridgeParams.defaultKnobs
) {
  require(knobs.nonEmpty, "at least one knob is required")

  // SeqMap keeps one entry per key, so a duplicate name would remove a port
  // with no error.
  require(
    knobs.map(_.name).distinct.size == knobs.size,
    s"knob names must be unique, got ${knobs.map(_.name).mkString(", ")}"
  )

  def knob(name: String): Option[PhyKnob] = knobs.find(_.name == name)
  def index(name: String): Int = knobs.indexWhere(_.name == name)
  def has(name: String): Boolean = knobs.exists(_.name == name)

  val maxCodeWidth: Int = knobs.map(_.width).max
  val numKnobs: Int = knobs.size

  // One eye bank for each speed, because the sec 4.5.1.1 note recalls the best
  // phase for the current speed. The size comes from SpeedMode, so no two
  // speeds share a bank.
  val speedBanks: Int = 1 << SpeedMode().getWidth
}

object PhyBridgeParams {
  val ClockPhaseKnob = "txClkPhase"
  val TxDeskewKnob = "txDeskew"
  val RxVrefKnob = "rxVref"
  val RxDeskewKnob = "rxDeskew"

  // Not a default knob. Tx swing is advertised once in MBINIT.PARAM, and the
  // partner sets its Vref from it (sec 4.5.3.3.1). Training does not sweep it.
  val TxSwingKnob = "txSwing"

  // Not a default knob. sec 9.5.3.23 bit 4 selects one of the two Table 5-6
  // settings. Declare txEq only for a PHY with more settings. The knob then
  // overrides bit 4.
  val TxEqKnob = "txEq"

  // No rxDeskew by default. sec 4.5.3.4.10 makes MBTRAIN.RXDESKEW optional,
  // and with no knob it exits through its handshake.
  val defaultKnobs: Seq[PhyKnob] = Seq(
    // Module scope, because sec 4.5.1.1 trains all Lanes at one PI phase. It
    // has eight bits to match the {Rx Init D to C sweep done with results}
    // edges.
    PhyKnob(ClockPhaseKnob, 8, 0, perLane = false),
    PhyKnob(TxDeskewKnob, 5, 0),
    PhyKnob(RxVrefKnob, 7, 63)
  )
}

class PhyBridgeConfig(p: PhyBridgeParams, slots: PhySlots) extends Bundle {
  val pinned = Vec(p.numKnobs, Vec(slots.count, UInt(p.maxCodeWidth.W)))
  val pinnedEnable = Vec(p.numKnobs, UInt(slots.count.W))
  val holdCodes = Bool()
  val settleCycles = UInt(8.W)
}

class PhyBridgeStatus(p: PhyBridgeParams, slots: PhySlots) extends Bundle {
  val applied = Vec(p.numKnobs, Vec(slots.count, UInt(p.maxCodeWidth.W)))
  val eye = new PhyEyeEdges(p)
  val codesFrozen = Bool()
}

class PhyEyeEdges(p: PhyBridgeParams) extends Bundle {
  val left = UInt(p.maxCodeWidth.W)
  val center = UInt(p.maxCodeWidth.W)
  val right = UInt(p.maxCodeWidth.W)
  val valid = Bool()
}

// Table 7-11 fixes each edge at 8 bits (sec 4.5.1.4 step 7), whatever the local
// code width, so this is not PhyEyeEdges.
class PartnerEyeEdges extends Bundle {
  val left = UInt(8.W)
  val right = UInt(8.W)
}

// This is a Record, so each knob is a named port in the Verilog
// (io_codes_rxVref_0), not one flat vector.
class PhyCodes(p: PhyBridgeParams, slots: PhySlots) extends Record {
  val elements: SeqMap[String, Data] = SeqMap(
    // Make a new type for each knob. One val shared across knobs causes
    // AliasedAggregateFieldException.
    p.knobs.map { k =>
      k.name -> (if (k.perLane) Vec(slots.count, UInt(k.width.W))
                 else UInt(k.width.W)): (String, Data)
    }: _*
  )

  // Note: Use these, not codes.rxVref. Record extends Selectable, so that
  // compiles and gives an untyped Data.
  def perLane(name: String): Vec[UInt] =
    elements(name).asInstanceOf[Vec[UInt]]
  def shared(name: String): UInt = elements(name).asInstanceOf[UInt]

  def apply(k: PhyKnob): Data = elements(k.name)
}
