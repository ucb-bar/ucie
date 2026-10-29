/*
  Valid framing per 8 UI group, 0x0F with a byte and 0x00 without (sec 4.1.2).
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._

object ValidFraming {
  val GroupUI = 8
  val DataGroup = 0x0f

  def framePattern(serRatio: Int): UInt =
    VecInit(Seq.tabulate(serRatio)(i => ((i % GroupUI) < 4).B)).asUInt

  // Per group, because a burst can start or end part way through a word.
  def groups(valid: UInt, serRatio: Int): Seq[UInt] =
    Seq.tabulate(serRatio / GroupUI)(g =>
      valid(GroupUI * g + GroupUI - 1, GroupUI * g)
    )

  def hasData(valid: UInt, serRatio: Int): Bool =
    groups(valid, serRatio).map(_ === DataGroup.U(GroupUI.W)).reduce(_ || _)

  def isIdle(valid: UInt, serRatio: Int): Bool =
    groups(valid, serRatio).map(_ === 0.U(GroupUI.W)).reduce(_ && _)

  // A rotation by part of a group shows here. A whole group rotation does not.
  def isBad(valid: UInt, serRatio: Int): Bool =
    groups(valid, serRatio)
      .map(g => g =/= DataGroup.U(GroupUI.W) && g =/= 0.U(GroupUI.W))
      .reduce(_ || _)
}
