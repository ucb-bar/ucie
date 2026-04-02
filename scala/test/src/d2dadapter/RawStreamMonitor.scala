package edu.berkeley.cs.uciedigital.d2dadapter

import chiseltest._
import scala.collection.mutable

final class IngressAcceptanceTracker(
  dut: D2DMainbandModule,
  expectedQ: mutable.Queue[AcceptedBeat]
) {
  final case class IngressEdgeObservation(beat: Option[AcceptedBeat])

  private var nextSeq: Long = 0L
  var acceptedCount: Long = 0L

  /**
    * Acceptance event at ingress boundary:
    * accepted = lp_valid && lp_irdy && pl_trdy
    */
  /**
    * Observe transfer intent before stepping the clock.
    * `edgeCycle` is the cycle index of the next rising edge.
    */
  def observeForNextEdge(edgeCycle: Long): IngressEdgeObservation = {
    val accepted = dut.io.fdi_lp_valid.peek().litToBoolean &&
      dut.io.fdi_lp_irdy.peek().litToBoolean &&
      dut.io.fdi_pl_trdy.peek().litToBoolean

    val beatOpt =
      if (accepted) {
        Some(AcceptedBeat(
          seq = nextSeq,
          data = dut.io.fdi_lp_data.peek().litValue,
          streamId = RawStreamSignalCodec.peekStreamId(dut.io.fdi_lp_stream),
          cycleAccepted = edgeCycle
        ))
      } else None

    IngressEdgeObservation(beatOpt)
  }

  /** Commit observation after stepping the clock edge. */
  def commitAfterEdge(obs: IngressEdgeObservation): Boolean = {
    obs.beat.foreach { beat =>
      expectedQ.enqueue(beat)
      nextSeq += 1
      acceptedCount += 1
    }
    obs.beat.nonEmpty
  }
}

final class EgressMonitor(
  dut: D2DMainbandModule,
  onObserved: AcceptedBeat => Unit,
  egressStreamId: () => Int = () => RawStreamIds.UnknownStreamId
) {
  final case class EgressEdgeObservation(beat: Option[AcceptedBeat])

  private var nextSeq: Long = 0L
  var observedCount: Long = 0L

  /**
    * Egress transfer event for D2DMainbandModule: valid && irdy && trdy.
    * This observes the transfer that will be sampled on the next rising edge.
    */
  def observeForNextEdge(edgeCycle: Long): EgressEdgeObservation = {
    val transferred = dut.io.rdi_lp_valid.peek().litToBoolean &&
      dut.io.rdi_lp_irdy.peek().litToBoolean &&
      dut.io.rdi_pl_trdy.peek().litToBoolean

    val beatOpt =
      if (transferred) {
        Some(AcceptedBeat(
          seq = nextSeq,
          data = dut.io.rdi_lp_data.peek().litValue,
          streamId = egressStreamId(),
          cycleAccepted = edgeCycle
        ))
      } else None

    EgressEdgeObservation(beatOpt)
  }

  /** Commit observation after stepping the clock edge. */
  def commitAfterEdge(obs: EgressEdgeObservation): Unit = {
    obs.beat.foreach { beat =>
      onObserved(beat)
      nextSeq += 1
      observedCount += 1
    }
  }
}
