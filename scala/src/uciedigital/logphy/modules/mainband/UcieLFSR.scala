/*
  The mainband scrambler, one LFSR per Lane (sec 4.4.1, Table 4-4).
 */

package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.utils._
import chisel3._

object UcieLFSR {
  // X23 + X21 + X16 + X8 + X5 + X2 + 1, without the X23 term.
  val polynomial = 0x210125

  val lfsrWidth = 23

  // Table 4-4 without RD0 to RD3, because Standard Package has no Lane repair.
  val laneSeeds = List(
    0x1dbfbc, 0x0607bb, 0x1ec760, 0x18c0db, 0x010f12, 0x19cfc9, 0x0277ce,
    0x1bb807
  )

  // sec 4.4.1 takes the seed by the Lane number modulo 8.
  def seedFor(lane: Int): Int = laneSeeds(lane % laneSeeds.length)
}

class UcieLFSR(afeParams: AfeParams) extends Module {
  val io = IO(new Bundle {
    val increment = Input(Vec(afeParams.mbLanes, Bool()))
    val resetLfsr = Input(Vec(afeParams.mbLanes, Bool()))
    val lfsrOutput =
      Output(Vec(afeParams.mbLanes, UInt(afeParams.mbSerializerRatio.W)))
  })

  val seeds =
    (for (i <- 0 until afeParams.mbLanes) yield UcieLFSR.seedFor(i)).toList

  val lfsr = seeds.map(seed =>
    Module(
      new ParallelGaloisLFSR(
        seed = seed,
        lfsrWidth = UcieLFSR.lfsrWidth,
        dataWidth = afeParams.mbSerializerRatio,
        polynomial = UcieLFSR.polynomial
      )
    )
  )
  for (i <- 0 until lfsr.length) {
    lfsr(i).io.resetLfsr := io.resetLfsr(i)
    lfsr(i).io.increment := io.increment(i)
    io.lfsrOutput(i) := lfsr(i).io.lfsrOutput
  }
}
