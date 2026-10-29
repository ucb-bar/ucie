/*
  The Chapter 9 register attributes (Table 9-4) as RegField factories.
 */

package edu.berkeley.cs.uciedigital.regs

import chisel3._
import freechips.rocketchip.regmapper.{
  RegField,
  RegFieldAccessType,
  RegFieldDesc,
  RegFieldWrType,
  RegReadFn,
  RegWriteFn
}

final case class UcieRegField(field: RegField, reg: UInt)

final case class UcieAutoClear(field: RegField, fire: Bool, pending: Bool)

final case class UcieOverrideField(field: RegField, reg: UInt, refused: Bool)

object UcieResets {
  def apply(implicitReset: Reset, linkReset: Bool): (Bool, Bool) = {
    val sticky = implicitReset.asBool
    val nonSticky = sticky || linkReset
    (sticky, nonSticky)
  }

  def apply(
      implicitReset: Reset,
      domainReset: Bool,
      linkReset: Bool
  ): (Bool, Bool) = {
    val sticky = implicitReset.asBool || domainReset
    val nonSticky = sticky || linkReset
    (sticky, nonSticky)
  }
}

class RegFieldTypes(val stickyReset: Bool, val nonStickyReset: Bool) {
  import RegFieldAccessType.{R, RW => RWacc}

  // RegFieldDesc has no sticky flag, so the sticky helpers add this text.
  val StickyNote = " [sticky: survives a link reset]"

  // ===========================================================================
  // Helpers
  // ===========================================================================
  def desc(
      name: String,
      description: String,
      access: RegFieldAccessType.RegFieldAccessType,
      reset: Option[BigInt] = None,
      wrType: Option[RegFieldWrType.RegFieldWrType] = None,
      volatile: Boolean = false
  ): RegFieldDesc =
    RegFieldDesc(
      name = name,
      desc = description,
      access = access,
      wrType = wrType,
      reset = reset,
      volatile = volatile
    )

  def stickyReg(width: Int, init: BigInt): UInt =
    withReset(stickyReset)(RegInit(init.U(width.W)))
  def nonStickyReg(width: Int, init: BigInt): UInt =
    withReset(nonStickyReset)(RegInit(init.U(width.W)))

  // ===========================================================================
  // Read-only
  // ===========================================================================
  def RO(width: Int, value: UInt, name: String, description: String): RegField =
    RegField.r(width, value, desc(name, description, R, volatile = true))

  def HWInit(
      width: Int,
      value: BigInt,
      name: String,
      description: String
  ): RegField =
    RegField.r(
      width,
      value.U(width.W),
      desc(name, description, R, reset = Some(value))
    )

  def RsvdP(width: Int, name: String = "rsvdp"): RegField =
    RegField.r(
      width,
      0.U(width.W),
      desc(name, "Reserved and Preserved (RsvdP)", R, Some(0))
    )
  def RsvdZ(width: Int, name: String = "rsvdz"): RegField =
    RegField.r(
      width,
      0.U(width.W),
      desc(name, "Reserved and Zero (RsvdZ)", R, Some(0))
    )

  // ===========================================================================
  // Read-write
  // ===========================================================================
  def RW(
      width: Int,
      init: BigInt,
      name: String,
      description: String
  ): UcieRegField = {
    val reg = nonStickyReg(width, init)
    UcieRegField(
      RegField(width, reg, desc(name, description, RWacc, Some(init))),
      reg
    )
  }

  def RWS(
      width: Int,
      init: BigInt,
      name: String,
      description: String
  ): UcieRegField = {
    val reg = stickyReg(width, init)
    UcieRegField(
      RegField(
        width,
        reg,
        desc(name, description + StickyNote, RWacc, Some(init))
      ),
      reg
    )
  }

  // The read returns applied. allow gates the write, and refused flags a drop.
  def RWOverride(
      width: Int,
      init: BigInt,
      applied: UInt,
      allow: Bool,
      name: String,
      description: String
  ): UcieOverrideField = {
    val reg = stickyReg(width, init)
    val refused = WireDefault(false.B)
    val write = RegWriteFn { (valid, data) =>
      when(valid) {
        when(allow) { reg := data }.otherwise { refused := true.B }
      }
      true.B
    }
    UcieOverrideField(
      RegField(
        width,
        RegReadFn(applied),
        write,

        // Volatile, because the read returns a live hardware value.
        desc(
          name,
          description + StickyNote,
          RWacc,
          Some(init),
          volatile = true
        )
      ),
      reg,
      refused
    )
  }

  // ===========================================================================
  // Status
  // ===========================================================================
  def ROS(
      width: Int,
      init: BigInt,
      name: String,
      description: String
  ): UcieRegField = {
    val reg = stickyReg(width, init)
    UcieRegField(
      RegField
        .r(width, reg, desc(name, description, R, Some(init), volatile = true)),
      reg
    )
  }

  def RW1C(
      width: Int,
      hwSet: UInt,
      name: String,
      description: String
  ): UcieRegField = {
    val reg = nonStickyReg(width, 0)
    val d = desc(
      name,
      description,
      RWacc,
      Some(0),
      Some(RegFieldWrType.ONE_TO_CLEAR),
      volatile = true
    )
    UcieRegField(RegField.w1ToClear(width, reg, hwSet, Some(d)), reg)
  }

  def RW1CS(
      width: Int,
      hwSet: UInt,
      name: String,
      description: String
  ): UcieRegField = {
    val reg = stickyReg(width, 0)
    val d = desc(
      name,
      description,
      RWacc,
      Some(0),
      Some(RegFieldWrType.ONE_TO_CLEAR),
      volatile = true
    )
    UcieRegField(RegField.w1ToClear(width, reg, hwSet, Some(d)), reg)
  }

  // ===========================================================================
  // Gated and handshake
  // ===========================================================================
  // Like RWOverride, but the read returns the stored register.
  def RWGated(
      width: Int,
      init: BigInt,
      allow: Bool,
      name: String,
      description: String
  ): UcieOverrideField = {
    val reg = stickyReg(width, init)
    val refused = WireDefault(false.B)
    val write = RegWriteFn { (valid, data) =>
      when(valid) {
        when(allow)(reg := data).otherwise(refused := true.B)
      }
      true.B
    }
    UcieOverrideField(
      RegField(
        width,
        RegReadFn(reg),
        write,
        desc(name, description + StickyNote, RWacc, Some(init))
      ),
      reg,
      refused
    )
  }

  // A W1S held until taken. The set wins, so a write at the take is a new one.
  def W1SPending(
      hwDone: Bool,
      name: String,
      description: String
  ): UcieAutoClear = {
    val pending = nonStickyReg(1, 0)
    val fire = WireDefault(false.B)
    val write = RegWriteFn { (valid, data) =>
      when(valid && data(0))(fire := true.B)
      true.B
    }

    // Use the edge, because the bus can hold a write's valid for many cycles.
    val fireEdge = fire && !RegNext(fire, false.B)
    when(fireEdge)(pending := 1.U).elsewhen(hwDone)(pending := 0.U)
    val d = desc(name, description, RWacc, Some(0), volatile = true)
    UcieAutoClear(RegField(1, pending, write, Some(d)), fire, pending(0))
  }

  def RWautoClear(
      hwDone: Bool,
      ignoreWhen: Bool,
      name: String,
      description: String
  ): UcieAutoClear = {
    val pending = nonStickyReg(1, 0)
    val fire = WireDefault(false.B)
    val write = RegWriteFn { (valid, data) =>
      when(valid && data(0) && !ignoreWhen && !pending(0)) {
        pending := 1.U
        fire := true.B
      }
      when(hwDone)(pending := 0.U)
      true.B
    }
    val d = desc(name, description, RWacc, Some(0), volatile = true)
    UcieAutoClear(RegField(1, pending, write, Some(d)), fire, pending(0))
  }

  // ===========================================================================
  // Lock
  // ===========================================================================
  def RWL(
      width: Int,
      init: BigInt,
      lock: Bool,
      name: String,
      description: String
  ): UcieRegField = {
    val reg = nonStickyReg(width, init)
    val write = RegWriteFn { (valid, data) =>
      when(valid && !lock)(reg := data)
      true.B
    }
    UcieRegField(
      RegField(
        width,
        reg,
        write,
        Some(desc(name, description, RWacc, Some(init)))
      ),
      reg
    )
  }

  def RWO(
      width: Int,
      init: BigInt,
      name: String,
      description: String
  ): UcieRegField = {
    val reg = nonStickyReg(width, init)
    val locked = nonStickyReg(width, 0)
    val write = RegWriteFn { (valid, data) =>
      when(valid) {
        val writable = ~locked
        reg := (reg & locked) | (data & writable)
        locked := locked | (data & writable)
      }
      true.B
    }
    UcieRegField(
      RegField(
        width,
        reg,
        write,
        Some(
          desc(
            name,
            description,
            RWacc,
            Some(init),
            Some(RegFieldWrType.MODIFY)
          )
        )
      ),
      reg
    )
  }

  // ===========================================================================
  // Rows
  // ===========================================================================
  def paddedRow(
      offset: Int,
      field: RegField,
      usedBits: Int,
      reservedTail: Int => RegField
  ): RegField.Map =
    if (usedBits >= 32) offset -> Seq(field)
    else offset -> Seq(field, reservedTail(32 - usedBits))

  def rows64(offset: Int, lo: RegField, hi: RegField): Seq[RegField.Map] =
    Seq(offset -> Seq(lo), (offset + 4) -> Seq(hi))
}
