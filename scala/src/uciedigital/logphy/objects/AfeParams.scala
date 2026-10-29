/*
  The parameters of the analog front end.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.common.{PhyBridgeParams, PhySlots}

case class AfeParams(
    mbSerializerRatio: Int = 32,
    mbLanes: Int = 16,
    bridge: PhyBridgeParams = PhyBridgeParams()
) {
  require(mbLanes > 0 && mbLanes <= 16)

  // Whole 8 UI frames per word (sec 4.1.2), so an all zero Valid word is idle.
  require(
    mbSerializerRatio >= 8 && mbSerializerRatio % 8 == 0,
    s"mbSerializerRatio must be a multiple of 8, got $mbSerializerRatio"
  )

  def slots: PhySlots = PhySlots(mbLanes)
}
