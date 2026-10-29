/*
  The MBTRAIN sub-states that run a link operation (sec 4.5.3.4).
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.common._
import chisel3._
import chisel3.util._

object MBTrainOperation {

  // `runsByDefault` is false only where sec 4.5.3.4 calls the step optional.
  case class Row(
      state: LTSMState.Type,
      section: String,
      txInitiated: Boolean,
      runsByDefault: Boolean,
      phrase: String
  )

  import LTSMState._

  // Fixed by the spec, so a PHY without a knob still runs centering.
  val table: Seq[Row] = Seq(
    Row(
      sMBTRAIN_VALVREF,
      "4.5.3.4.1",
      txInitiated = false,
      runsByDefault = true,
      "UCIe Module optimizes Vref on its Valid Receiver ... " +
        "Receiver-initiated Data-to-Clock point tests"
    ),
    Row(
      sMBTRAIN_DATAVREF,
      "4.5.3.4.2",
      txInitiated = false,
      runsByDefault = true,
      "The UCIe Module optimizes data Vref ... " +
        "Receiver-initiated Data-to-Clock point tests"
    ),
    Row(
      sMBTRAIN_VALTRAINCENTER,
      "4.5.3.4.6",
      txInitiated = true,
      runsByDefault = true,
      "The UCIe Module must perform one or more Transmitter-initiated " +
        "Data-to-Clock eye width sweeps"
    ),
    Row(
      sMBTRAIN_VALTRAINVREF,
      "4.5.3.4.7",
      txInitiated = false,
      runsByDefault = false,
      "Step 2 is optional and implementation-specific"
    ),
    Row(
      sMBTRAIN_DATATRAINCENTER1,
      "4.5.3.4.8",
      txInitiated = true,
      runsByDefault = true,
      "The UCIe Module must perform one or more Transmitter-initiated " +
        "Data-to-Clock eye width sweeps"
    ),
    Row(
      sMBTRAIN_DATATRAINVREF,
      "4.5.3.4.9",
      txInitiated = false,
      runsByDefault = false,
      "Step 2 is optional and implementation-specific"
    ),
    Row(
      sMBTRAIN_RXDESKEW,
      "4.5.3.4.10",
      txInitiated = false,
      runsByDefault = false,
      "Step 3 is optional and implementation specific"
    ),
    Row(
      sMBTRAIN_DATATRAINCENTER2,
      "4.5.3.4.11",
      txInitiated = true,
      runsByDefault = true,
      "The UCIe Module must perform one or more Transmitter-initiated " +
        "Data-to-Clock eye width sweeps"
    ),

    // Note: DATACENTER2 below is the spec's own typo.
    Row(
      sMBTRAIN_LINKSPEED,
      "4.5.3.4.12",
      txInitiated = true,
      runsByDefault = true,
      "The UCIe Module must perform a Transmitter-initiated Data-to-Clock " +
        "point test ... with the final clock sampling phase calculated in " +
        "the previous MBTRAIN.DATACENTER2 state"
    )
  )

  val noOperation: Seq[LTSMState.Type] = Seq(
    sMBTRAIN_SPEEDIDLE,
    sMBTRAIN_TXSELFCAL,
    sMBTRAIN_RXCLKCAL,
    sMBTRAIN_REPAIR
  )

  require(
    table.map(_.state).distinct.size == table.size,
    "an MBTRAIN sub-state appears twice in the operation table"
  )

  // A new MBTRAIN sub-state fails elaboration until it is classified here.
  val mbTrainStates =
    LTSMState.specEncoding.filter(_._1.startsWith("MBTRAIN.")).map(_._3)
  require(
    (table.map(_.state) ++ noOperation).toSet == mbTrainStates.toSet,
    "every MBTRAIN sub-state must either run a sec 4.5.3.4 link operation or " +
      s"be listed as running none; classified " +
      s"${(table.map(_.state) ++ noOperation).size} of ${mbTrainStates.size}"
  )

  def hasOperation(state: LTSMState.Type): Bool =
    table.map(r => state === r.state).reduce(_ || _)

  // Only a default, because sec 4.5.3.4 lets software skip any operation.
  def runsByDefault(state: LTSMState.Type): Bool =
    MuxLookup(state.asUInt, false.B)(
      table.map(r => r.state.asUInt -> r.runsByDefault.B)
    )

  def txInitiated(state: LTSMState.Type): Bool =
    MuxLookup(state.asUInt, false.B)(
      table.map(r => r.state.asUInt -> r.txInitiated.B)
    )
}
