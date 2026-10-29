/*
  The parameters of the sideband stack, mostly timeouts in cycles.
 */

package edu.berkeley.cs.uciedigital.sideband

import edu.berkeley.cs.uciedigital.common.UcieTiming

case class SidebandParams(
    val sbNodeMsgWidth: Int = 128,
    val maxCrd: Int = 32,
    val sbLinkWidth: Int = 1,
    val remoteRegAccessTimeoutCycles: Int = UcieTiming.trainingTimeoutCycles,

    // 8 ms each (sec 3.2.1.2, sec 9.5.3.8).
    val paramExchTimeoutCycles: Int = UcieTiming.trainingTimeoutCycles,
    val lsmRequestTimeoutCycles: Int = UcieTiming.trainingTimeoutCycles,
    val linkErrorMinResidencyCycles: Int =
      UcieTiming.linkErrorMinResidencyCycles,

    // sec 10.3.2 gives no bound, so this uses the 8 ms of sec 10.3.3.8.
    val stallTimeoutCycles: Int = UcieTiming.trainingTimeoutCycles,
    val rdiHandshakeTimeoutCycles: Int = UcieTiming.trainingTimeoutCycles,

    // sec 10.1.3.2 gives no bound either, but rule 9 makes pl_cfg wait for it.
    val clockHandshakeTimeoutCycles: Int = UcieTiming.trainingTimeoutCycles,
    val pmNakFloorCycles: Int = UcieTiming.pmNakFloorCycles,
    val pmResponseDeadlineCycles: Int = UcieTiming.pmResponseDeadlineCycles,
    val pmRetryFloorCycles: Int = UcieTiming.pmRetryFloorCycles,
    val trainingTimeoutCycles: Int = UcieTiming.trainingTimeoutCycles,
    val resetMinResidencyCycles: Int = UcieTiming.resetMinResidencyCycles
) {
  require(maxCrd >= 1 && maxCrd <= 32)
  require(remoteRegAccessTimeoutCycles > 0)
  require(paramExchTimeoutCycles > 1)
  require(lsmRequestTimeoutCycles > 1)
  require(linkErrorMinResidencyCycles > 0)
  require(stallTimeoutCycles > 1)
  require(rdiHandshakeTimeoutCycles > 1)
  require(clockHandshakeTimeoutCycles > 1)
  require(pmNakFloorCycles > 1)
  require(pmResponseDeadlineCycles > pmNakFloorCycles)
  require(pmRetryFloorCycles > 1)

  // SBInitRequester divides this by eight for sec 4.5.3.2 step 5's 1 ms.
  require(trainingTimeoutCycles >= 8)
  require(resetMinResidencyCycles > 0)
}

case class SidebandPriorityQueueDepths(
    messageRequestOrResponse: Int = 32,
    regAccessCompletion: Int = 32,
    regAccessRequest: Int = 32,
    other: Int = 32
)
