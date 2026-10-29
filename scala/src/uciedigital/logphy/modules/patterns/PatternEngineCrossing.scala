/*
  The crossings between the training state machines and the pattern engines.
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.utils._
import chisel3._
import chisel3.util.log2Ceil

// The arming has no acknowledge, because a power-gated tile can park txDivClk.
// The completion has one, because a burst needs transmit edges to end.
class PatternWriterCrossIO extends Bundle {
  val armToggle = Output(Bool())
  val reqBits = Output(new PatternWriterReq)
  val completeToggle = Input(Bool())
  val completeAckToggle = Output(Bool())
}

// All levels, because a parked rxDivClk cannot acknowledge (sec 4.5.3.3.4).
class PatternReaderCrossIO(numMbLanes: Int) extends Bundle {
  val armToggle = Output(Bool())
  val reqBits = Output(new PatternReaderReq)
  val doneToggle = Output(Bool())
  val remoteFuncLanes = Output(UInt(3.W))
  val rxParked = Output(Bool())
  val verdictPerLane = Input(UInt(numMbLanes.W))
  val verdictAggregate = Input(Bool())
  val verdictFinal = Input(Bool())
  val served = Input(Bool())
  val alive = Input(Bool())
}

class PatternWriterCrossHost(bypass: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val engine = new PatternWriterIO
    val cross = new PatternWriterCrossIO
  })

  // The engine takes the arming on its next edge, so no acknowledge is needed.
  val armed = RegInit(false.B)
  val armToggle = RegInit(false.B)
  val reqBits = RegInit(0.U.asTypeOf(new PatternWriterReq))

  io.engine.req.ready := !armed
  when(io.engine.req.fire) {
    armed := true.B
    armToggle := !armToggle
    reqBits := io.engine.req.bits
  }

  io.cross.armToggle := armToggle
  io.cross.reqBits := reqBits

  // The substate that reads this pulse already waits, so it need not be held.
  val completeSink = Module(new TogglePulseSink(bypass))
  completeSink.io.reqToggle := io.cross.completeToggle
  io.cross.completeAckToggle := completeSink.io.ackToggle
  io.engine.resp.complete := completeSink.io.pulse
  when(completeSink.io.pulse) {
    armed := false.B
  }
}

class PatternReaderCrossHost(afeParams: AfeParams, bypass: Boolean = false)
    extends Module {
  val numMbLanes = afeParams.mbLanes
  val io = IO(new Bundle {
    val engine = new PatternReaderIO(numMbLanes)
    val cross = new PatternReaderCrossIO(numMbLanes)
    val ltsmInReset = Input(Bool())
  })

  // ===========================================================================
  // Window
  // ===========================================================================
  // The window opens before rxDivClk runs, so it is on the control clock.
  val armed = RegInit(false.B)
  val armToggle = RegInit(false.B)
  val reqBits = RegInit(0.U.asTypeOf(new PatternReaderReq))

  // A requester can leave with no verdict, and this host is not in its reset.
  val doneSeen = RegInit(false.B)

  // RESET holds every requester in reset, so an open window gets no done.
  val resetWhileOpen = io.ltsmInReset && armed && !doneSeen

  // One window at a time, so the arming toggle has one flip in flight.
  io.engine.req.ready := !armed
  when(armed && io.engine.done) {
    doneSeen := true.B
  }

  // Collecting and abandoning cannot occur together, so their order is free.
  when(io.engine.resp.fire) {
    armed := false.B
    doneSeen := false.B
  }
  when(doneSeen && !io.engine.done) {
    armed := false.B
    doneSeen := false.B
  }
  when(io.ltsmInReset) {
    armed := false.B
    doneSeen := false.B
  }
  when(io.engine.req.fire) {
    armed := true.B
    doneSeen := false.B
    armToggle := !armToggle
    reqBits := io.engine.req.bits
  }

  // done is a toggle, because its level can last only one or two cycles.
  val doneToggle = RegInit(false.B)
  val donePrev = RegNext(io.engine.done, false.B)
  when((io.engine.done && !donePrev) || resetWhileOpen) {
    doneToggle := !doneToggle
  }

  io.cross.armToggle := armToggle
  io.cross.reqBits := reqBits
  io.cross.doneToggle := doneToggle

  // A flop, because a parked rxDivClk reads this decode raw.
  // No word comes before the arming is answered, so the cycle is never seen.
  io.cross.remoteFuncLanes :=
    (if (bypass) io.engine.remoteFuncLanes
     else RegNext(io.engine.remoteFuncLanes, 0.U))

  // ===========================================================================
  // Parked clock
  // ===========================================================================
  // At 4 GT/s, the slowest rate, alive flips every 0.8 x serRatio cycles.
  // So 3 x serRatio quiet cycles means a parked clock.
  val rxParked =
    if (bypass) false.B
    else {
      // alive is a rxDivClk flop, so this arc takes the Sync3 false path.
      val aliveSync = Sync3(io.cross.alive)
      val change = aliveSync =/= RegNext(aliveSync, false.B)
      val quietMax = 3 * afeParams.mbSerializerRatio
      val quiet = RegInit(0.U(log2Ceil(quietMax + 1).W))
      val parked = RegInit(false.B)
      val oneChange = RegInit(false.B)
      when(change) {
        quiet := 0.U
      }.elsewhen(quiet =/= quietMax.U) {
        quiet := quiet + 1.U
      }

      // Unpark on the second change, once the rx Sync3 flops hold live levels.
      when(change) {
        oneChange := true.B
        when(oneChange) { parked := false.B }
      }.elsewhen(quiet === quietMax.U) {
        parked := true.B
        oneChange := false.B
      }
      parked
    }
  io.cross.rxParked := rxParked

  // ===========================================================================
  // Verdict
  // ===========================================================================
  // The verdict follows the requester's done, because rxDivClk can be parked.
  io.engine.resp.valid := armed && io.engine.done

  // A window with no burst reads as a fail (sec 4.5.3.3.4 step 6).
  val started =
    if (bypass) true.B else Sync3(io.cross.served) === armToggle

  // The reader holds the verdict in registers, so a word crossing is safe.
  io.engine.resp.bits.perLaneStatusBits := VecInit(
    Sync3.word(io.cross.verdictPerLane, bypass).asBools.map(_ && started)
  )
  io.engine.resp.bits.aggregateStatus :=
    Sync3(io.cross.verdictAggregate, false.B, bypass) && started

  // Use the rising edge, because an idle reader shows an old verdict as final.
  val finalSync = Sync3(io.cross.verdictFinal, false.B, bypass)
  val finalPrev = RegNext(finalSync, false.B)
  val verdictFinal = RegInit(false.B)

  // With rxDivClk parked no counter can move, so the reading is the verdict.
  when((finalSync && !finalPrev) || (doneSeen && rxParked)) {
    verdictFinal := true.B
  }
  when(io.engine.req.fire) {
    verdictFinal := false.B
  }
  io.engine.verdictFinal := verdictFinal
}

// The transmit domain half of the writer crossing.
class PatternWriterCrossDevice(bypass: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val cross = Flipped(new PatternWriterCrossIO)
    val engine = Flipped(new PatternWriterIO)
  })

  // A burst is armed only after the last completes, so a toggle is safe.
  val armSync = Sync3(io.cross.armToggle, false.B, bypass)
  val lastServed = RegInit(false.B)
  when(io.engine.req.fire) {
    lastServed := armSync
  }

  io.engine.req.valid := armSync =/= lastServed
  io.engine.req.bits := io.cross.reqBits

  val completeSource = Module(new TogglePulseSource(bypass))
  completeSource.io.pulse := io.engine.resp.complete
  completeSource.io.ackToggle := io.cross.completeAckToggle
  io.cross.completeToggle := completeSource.io.reqToggle
}

// The receive domain half of the reader crossing.
class PatternReaderCrossDevice(numMbLanes: Int, bypass: Boolean = false)
    extends Module {
  val io = IO(new Bundle {
    val cross = Flipped(new PatternReaderCrossIO(numMbLanes))
    val engine = Flipped(new PatternReaderIO(numMbLanes))
  })

  // Raw while parked (sec 4.1.3), so a burst's first edge reads the arming.
  // The first pattern word can come on the second edge (sec 5.11).
  def level(x: Bool): Bool = Sync3.parkable(x, io.cross.rxParked, bypass)

  // A toggle, because a parked clock can miss a level falling and rising.
  val armIn = level(io.cross.armToggle)
  val lastServed = RegInit(false.B)
  val newWindow = armIn =/= lastServed

  // The control side closed the window in progress before this arming.
  io.engine.req.valid := newWindow

  // Held from one arming to the next. Its arcs need a false path.
  io.engine.req.bits := io.cross.reqBits

  // Hold done until the reader takes it, because the reader can be mid-word.
  val doneIn = level(io.cross.doneToggle)
  val lastDoneServed = RegInit(false.B)
  val donePending = doneIn =/= lastDoneServed
  io.engine.done := donePending && !newWindow
  when(newWindow) {
    lastServed := armIn

    // A done that the parked clock held back is for the dropped window.
    lastDoneServed := doneIn
  }.elsewhen(donePending && io.engine.req.ready) {
    lastDoneServed := doneIn
  }
  io.engine.remoteFuncLanes :=
    VecInit(io.cross.remoteFuncLanes.asBools.map(level)).asUInt

  // Always ready, because the state machines read resp.valid before resp.ready.
  io.engine.resp.ready := true.B

  io.cross.verdictPerLane := io.engine.resp.bits.perLaneStatusBits.asUInt
  io.cross.verdictAggregate := io.engine.resp.bits.aggregateStatus
  io.cross.verdictFinal := io.engine.verdictFinal
  io.cross.served := lastServed
  io.cross.alive :=
    (if (bypass) false.B
     else {
       val ctr = RegInit(0.U(3.W))
       ctr := ctr + 1.U
       ctr(2)
     })
}
