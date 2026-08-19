// Factory library for UCIe register-field attributes and their dual-reset sticky backing storage.
package edu.berkeley.cs.uciedigital.regs

import chisel3._
import freechips.rocketchip.regmapper.{
  RegField,
  RegFieldAccessType,
  RegFieldDesc,
  RegFieldWrType,
  RegWriteFn
}

final case class UcieRegField(field: RegField, reg: UInt)

final case class UcieAutoClear(field: RegField, fire: Bool, pending: Bool)

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

  private def desc(
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

  private def stickyReg(width: Int, init: BigInt): UInt =
    withReset(stickyReset)(RegInit(init.U(width.W)))
  private def nonStickyReg(width: Int, init: BigInt): UInt =
    withReset(nonStickyReset)(RegInit(init.U(width.W)))

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
      RegField(width, reg, desc(name, description, RWacc, Some(init))),
      reg
    )
  }

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
        Some(desc(name, description, RWacc, Some(init)))
      ),
      reg
    )
  }

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
