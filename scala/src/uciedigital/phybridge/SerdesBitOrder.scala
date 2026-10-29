/*
  Checks that Phy.treeBitOrder is its own inverse, which tree cancellation needs.
 */

package edu.berkeley.cs.uciedigital.phybridge

import edu.berkeley.cs.uciedigital.phy.Phy

object SerdesBitOrder {
  def check(): Unit = {
    // Framing misses a bad pairing, because 0x0f0f0f0f survives the reversal.
    val order = (0 until Phy.SerdesRatio).map(Phy.treeBitOrder)
    require(
      order.sorted == (0 until Phy.SerdesRatio).toList,
      s"Phy.treeBitOrder is not a permutation of 0 until ${Phy.SerdesRatio}: " +
        s"it maps that range onto ${order.distinct.size} distinct indices. It " +
        "is written for 5 bits, so a SerdesRatio other than 32 needs it widened"
    )
    require(
      (0 until Phy.SerdesRatio).forall(t => Phy.treeBitOrder(order(t)) == t),
      "Phy.treeBitOrder is no longer its own inverse, so applying it on both " +
        "the TX and RX shuffler no longer cancels the serdes tree -- the " +
        "payload would be bit-permuted within each word, and the valid-lane " +
        "framing check cannot see it"
    )
  }
}
