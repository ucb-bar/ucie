/*
  The spec's timeouts in cycles of the 800 MHz sideband clock (sec 5.13).
 */

package edu.berkeley.cs.uciedigital.common

object UcieTiming {
  val sidebandFreqHz: Long = 800000000L

  def cyclesFor(seconds: Double): Int = {
    val cycles = sidebandFreqHz * seconds
    require(cycles >= 1.0, s"$seconds s is shorter than one sideband bit time")
    cycles.toInt
  }

  // sec 4.5 gives each state an 8 ms residency timeout, -0% and +50%.
  val trainingTimeoutCycles: Int = cyclesFor(0.008)

  // sec 4.5.3.1 holds RESET for at least 4 ms on every entry.
  val resetMinResidencyCycles: Int = cyclesFor(0.004)

  // sec 10.3.3.7 holds LinkError for at least 16 ms.
  val linkErrorMinResidencyCycles: Int = cyclesFor(0.016)

  // sec 10.1.7 requires a PMNAK no earlier than 1 us and no later than 2 us
  // after a PM request. It lets the requester retry 2 us after a PMNAK.
  val pmNakFloorCycles: Int = cyclesFor(0.000001)
  val pmResponseDeadlineCycles: Int = cyclesFor(0.000002)
  val pmRetryFloorCycles: Int = cyclesFor(0.000002)
}
