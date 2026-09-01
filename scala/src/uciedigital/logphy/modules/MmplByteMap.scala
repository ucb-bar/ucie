/*
  Description:
    Byte mapping between the aggregate RDI of a multi-module Link and the
    per-Module RDI slices (spec 4.7.1).

  The rule is one sentence of the spec: "for any valid transfer, bytes are laid
  out from LSB to MSB in ascending order of Module ID and Lane ID across all the
  active Lanes". Writing `A` for the active Lanes per Module, `M` for the number
  of active Modules, and `rank` for a Module's position in ascending Module ID
  order among the active Modules, Module-local byte `j` of MMPL beat `b` carries
  aggregate RDI byte

      g = (j / A) * (M * A)  +  rank * A  +  (j % A)  +  b * M * bytesPerModule

  where `j / A` is the 8-UI chunk the byte travels in and `j % A` is its Lane.
  This reproduces every byte range in spec Figure 4-43 through Figure 4-46 and
  Figure 4-50.

  NOTE:
 * The receive direction ranks by the LOCAL Module ID; the transmit direction
   ranks by the REMOTE Module ID advertised in {MBINIT.PARAM configuration req},
   because the remote Receiver demaps by its own Module ID (Figure 4-44).
 * `rank` only ever shifts `g` by `rank * A`, so a Module at rank r sees exactly
   the rank-0 mapping applied to the aggregate word offset by `r * A` bytes.
   That is why the hardware below is a byte shift followed by static wiring
   rather than a full crossbar.
 * `b` is non-zero only when Modules have been disabled and the aggregate RDI is
   wider than the surviving Lanes can carry in one beat (Figure 4-46).
 */

package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.util._

object MmplByteMap {

  /*
    Functional-Lane code to active Lane count (spec Table 4-9). Mirrors
    MainbandLaneController.activeLanesForCode so the MMPL and the per-Module
    Lane controller agree on how many Lanes carry data.

    Spec 4.5.3.3.5: a x16 Standard Package Module that negotiated "UCIe-S x8"
    operates in x8 mode and reads Table 4-9 by 8, so the "all functional" code
    means Lanes 0 to 7 rather than Lanes 0 to 15. The narrower codes already
    name their Lanes explicitly and mean the same thing in both readings; only
    b011 changes, which is why `by8` only ever halves that one entry.
   */
  val laneCodes: Seq[(String, Int)] = Seq(
    "b011" -> 16, // Lanes 0 to 15 (Lanes 0 to 7 when reading by 8)
    "b001" -> 8, // Lanes 0 to 7
    "b010" -> 8, // Lanes 8 to 15
    "b100" -> 4, // Lanes 0 to 3
    "b101" -> 4 // Lanes 4 to 7
  )

  val defaultActiveLanes: Int = 16

  /** Active Lane count for a code, as read in x16 or in x8 mode. */
  def laneCount(code: String, by8: Boolean): Int = {
    val lanes = laneCodes.toMap.getOrElse(code, defaultActiveLanes)
    if (by8 && code == "b011") lanes / 2 else lanes
  }

  /** Distinct active Lane counts an implementation with `mbLanes` can reach. */
  def activeLaneOptions(mbLanes: Int): Seq[Int] =
    (defaultActiveLanes +: laneCodes.map(_._2)).distinct
      .filter(_ <= mbLanes)
      .sorted

  def activeLanes(code: UInt, by8: Bool): UInt =
    MuxLookup(code, Mux(by8, (defaultActiveLanes / 2).U, defaultActiveLanes.U))(
      laneCodes.map { case (c, _) =>
        c.U -> Mux(by8, laneCount(c, true).U, laneCount(c, false).U)
      }
    )

  def activeLanesShift(code: UInt, rank: UInt, by8: Bool): UInt =
    MuxLookup(
      code,
      Mux(
        by8,
        rank << log2Ceil(defaultActiveLanes / 2),
        rank << log2Ceil(defaultActiveLanes)
      )
    )(
      laneCodes.map { case (c, _) =>
        c.U -> Mux(
          by8,
          rank << log2Ceil(laneCount(c, true)),
          rank << log2Ceil(laneCount(c, false))
        )
      }
    )

  /** Module counts a Link may operate at: one, two or four (spec 1.2.2). */
  def permittedActiveCounts(numModules: Int): Seq[Int] =
    Seq(1, 2, 4).filter(_ <= numModules)

  /** MMPL beats needed to move one aggregate RDI word. */
  def beatsPerWord(numModules: Int, numActive: Int): Int =
    numModules / numActive

  /** Aggregate RDI byte index carried by a Module-local byte. */
  def globalByte(
      localByte: Int,
      activeLanes: Int,
      numActive: Int,
      bytesPerModule: Int,
      beat: Int,
      rank: Int
  ): Int = {
    val chunk = localByte / activeLanes
    val lane = localByte % activeLanes
    chunk * (numActive * activeLanes) + rank * activeLanes + lane +
      beat * numActive * bytesPerModule
  }
}

/*
  Description:
    Scatter of the aggregate RDI transmit word into per-Module slices, and the
    matching gather of the per-Module receive slices back into one word.
    Combinational; the MMPL owns the beat counters that drive txBeat and rxBeat.

  NOTE:
 * The two directions are configured separately. Transmit uses this Module's own
   functional-Lane code and ranks by the remote Module ID; receive uses the
   remote Transmitter's functional-Lane code and ranks by the local Module ID.
 */
class MmplByteSwizzle(params: MmplParams) extends Module {
  import MmplByteMap._

  private val n = params.numModules
  private val bytesPerModule = params.bytesPerModule
  private val totalBytes = n * bytesPerModule
  private val moduleBits = bytesPerModule * 8
  private val totalBits = totalBytes * 8

  // Rank, the active Module count and the beat index are all in 0 to n.
  private val rankW = log2Ceil(n + 1)
  // Largest byte offset a legal rank can introduce is (n - 1) * mbLanes, and
  // rank <= n - 1 by construction, so this is exact for every rank the MMPL can
  // present. Chisel truncates a too-wide assignment rather than saturating it.
  private val shiftBytesW = log2Ceil(n * params.afe.mbLanes + 1)

  val io = IO(new Bundle {
    val ctrl = new Bundle {
      val numActive = Input(UInt(rankW.W))
      val enable = Input(Vec(n, Bool()))

      // Spec 4.5.3.3.5: reads Table 4-9 by 8 when "UCIe-S x8" was negotiated.
      val by8 = Input(Bool())

      val txLaneCode = Input(UInt(3.W))
      val txBeat = Input(UInt(rankW.W))
      val txRank = Input(Vec(n, UInt(rankW.W)))

      val rxLaneCode = Input(UInt(3.W))
      val rxBeat = Input(UInt(rankW.W))
      val rxRank = Input(Vec(n, UInt(rankW.W)))
    }
    val tx = new Bundle {
      val lpData = Input(UInt(totalBits.W))
      val moduleData = Output(Vec(n, UInt(moduleBits.W)))
    }
    val rx = new Bundle {
      val moduleData = Input(Vec(n, UInt(moduleBits.W)))
      val plData = Output(UInt(totalBits.W))
    }
  })

  // ==========================================================================
  // Combination select
  // ==========================================================================
  // The rank-0 mapping depends only on the active Lane count, the active Module
  // count and the beat, so enumerate those and select one static wiring.
  private val combos: Seq[(Int, Int, Int)] = for {
    lanes <- activeLaneOptions(params.afe.mbLanes)
    numActive <- permittedActiveCounts(n)
    beat <- 0 until beatsPerWord(n, numActive)
  } yield (lanes, numActive, beat)

  private val comboSelW = log2Ceil(math.max(2, combos.length))

  private def comboSelect(laneCode: UInt, beat: UInt): UInt = {
    val lanes = activeLanes(laneCode, io.ctrl.by8)
    val sel = WireDefault(0.U(comboSelW.W))
    combos.zipWithIndex.foreach {
      case ((comboLanes, comboActive, comboBeat), idx) =>
        when(
          lanes === comboLanes.U &&
            io.ctrl.numActive === comboActive.U &&
            beat === comboBeat.U
        ) {
          sel := idx.U
        }
    }
    sel
  }

  private def shiftBits(laneCode: UInt, rank: UInt): UInt = {
    val shiftBytes = WireDefault(0.U(shiftBytesW.W))
    shiftBytes := activeLanesShift(laneCode, rank, io.ctrl.by8)
    Cat(shiftBytes, 0.U(3.W))
  }

  private val txComboSel = comboSelect(io.ctrl.txLaneCode, io.ctrl.txBeat)
  private val rxComboSel = comboSelect(io.ctrl.rxLaneCode, io.ctrl.rxBeat)

  // ==========================================================================
  // Transmit scatter
  // ==========================================================================
  for (m <- 0 until n) {
    // Offsetting the aggregate word by rank * activeLanes bytes turns every
    // rank into the rank-0 mapping.
    val shifted =
      (io.tx.lpData >> shiftBits(io.ctrl.txLaneCode, io.ctrl.txRank(m)))(
        totalBits - 1,
        0
      )

    val candidates = VecInit(combos.map { case (lanes, numActive, beat) =>
      Cat((0 until bytesPerModule).reverse.map { j =>
        val g = globalByte(j, lanes, numActive, bytesPerModule, beat, 0)
        shifted(g * 8 + 7, g * 8)
      })
    })

    io.tx.moduleData(m) := Mux(io.ctrl.enable(m), candidates(txComboSel), 0.U)
  }

  // ==========================================================================
  // Receive gather
  // ==========================================================================
  // Each Module places its bytes in a disjoint set of aggregate positions, so
  // the contributions can simply be OR'd together.
  private val rxContributions = (0 until n).map { m =>
    val candidates = VecInit(combos.map { case (lanes, numActive, beat) =>
      val placed = Wire(Vec(totalBytes, UInt(8.W)))
      placed.foreach(_ := 0.U)
      for (j <- 0 until bytesPerModule) {
        val g = globalByte(j, lanes, numActive, bytesPerModule, beat, 0)
        placed(g) := io.rx.moduleData(m)(j * 8 + 7, j * 8)
      }
      placed.asUInt
    })

    val placedWord = Mux(io.ctrl.enable(m), candidates(rxComboSel), 0.U)
    (placedWord << shiftBits(io.ctrl.rxLaneCode, io.ctrl.rxRank(m)))(
      totalBits - 1,
      0
    )
  }

  io.rx.plData := rxContributions.reduce(_ | _)
}
