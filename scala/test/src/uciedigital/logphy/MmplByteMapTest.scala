package edu.berkeley.cs.uciedigital.logphy

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

import scala.util.Random

/** Byte mapping between the aggregate RDI and the per-Module slices, spec
  * 4.7.1.
  *
  * The spec-figure cases below use each figure's own RDI width, so they check
  * `MmplByteMap.globalByte` against the published byte ranges directly rather
  * than against a re-derivation of it.
  */
class MmplByteMapTest extends AnyFunSpec with ChiselSim {
  import MmplByteMap._

  private val randomSeed = 0x6d6d706cL // "mmpl"

  /** Aggregate byte range a Module carries in one 8-UI chunk of one beat. */
  private def chunkRange(
      activeLanes: Int,
      numActive: Int,
      bytesPerModule: Int,
      beat: Int,
      rank: Int,
      chunk: Int
  ): (Int, Int) = {
    val lo = globalByte(
      chunk * activeLanes,
      activeLanes,
      numActive,
      bytesPerModule,
      beat,
      rank
    )
    val hi = globalByte(
      chunk * activeLanes + activeLanes - 1,
      activeLanes,
      numActive,
      bytesPerModule,
      beat,
      rank
    )
    (lo, hi)
  }

  private def ranges(
      activeLanes: Int,
      numActive: Int,
      bytesPerModule: Int,
      beat: Int,
      chunk: Int
  ): Seq[(Int, Int)] =
    (0 until numActive).map(
      chunkRange(activeLanes, numActive, bytesPerModule, beat, _, chunk)
    )

  // ==========================================================================
  // Spec figures
  // ==========================================================================
  describe("MmplByteMap against the spec figures") {
    it("Figure 4-43: four x16 Modules, 64B RDI, matching Module IDs") {
      // One 8-UI chunk carries the whole RDI word, so each Module owns 16
      // contiguous bytes in ascending Module ID order.
      assert(
        ranges(16, 4, 16, 0, 0) ==
          Seq((0, 15), (16, 31), (32, 47), (48, 63))
      )
    }

    it("Figure 4-45: four width-degraded x8 Modules, 64B RDI") {
      // UI 7-0 carries RDI B0 to B31, UI 15-8 carries B32 to B63.
      assert(
        ranges(8, 4, 16, 0, 0) ==
          Seq((0, 7), (8, 15), (16, 23), (24, 31))
      )
      assert(
        ranges(8, 4, 16, 0, 1) ==
          Seq((32, 39), (40, 47), (48, 55), (56, 63))
      )
    }

    it("Figure 4-46: two of four Modules disabled, 64B RDI") {
      // M0 and M2 are disabled, so M1 ranks first and takes the least
      // significant bytes; the rest of the word follows in later 8-UI chunks.
      assert(ranges(16, 2, 32, 0, 0) == Seq((0, 15), (16, 31)))
      assert(ranges(16, 2, 32, 0, 1) == Seq((32, 47), (48, 63)))
    }

    it("Reads Table 4-9 by 8 when UCIe-S x8 was negotiated") {
      /* Spec 4.5.3.3.5 (p.152): a x16 Standard Package Module that negotiated
         "UCIe-S x8" operates in x8 mode and applies the training steps to the
         lower-8 data-lane set, so the "all functional" code covers Lanes 0 to 7
         rather than 0 to 15. LogicalPhy already reports x8 for that code; the
         byte map has to agree with it or the MMPL lays out twice the bytes its
         own pl_lnk_cfg claims. The narrower codes name their Lanes explicitly
         and mean the same in either reading. */
      assert(laneCount("b011", by8 = false) == 16)
      assert(laneCount("b011", by8 = true) == 8)
      for (code <- Seq("b001", "b010", "b100", "b101")) {
        assert(
          laneCount(code, by8 = true) == laneCount(code, by8 = false),
          s"$code should read the same in x8 mode"
        )
      }

      // Two x8-mode Modules, 32B RDI: 8 bytes per Module per 8-UI chunk.
      assert(ranges(8, 2, 16, 0, 0) == Seq((0, 7), (8, 15)))
      assert(ranges(8, 2, 16, 0, 1) == Seq((16, 23), (24, 31)))
    }

    it("Figure 4-50: four x32 Modules, 256B RDI") {
      assert(
        ranges(32, 4, 64, 0, 0) ==
          Seq((0, 31), (32, 63), (64, 95), (96, 127))
      )
      assert(
        ranges(32, 4, 64, 0, 1) ==
          Seq((128, 159), (160, 191), (192, 223), (224, 255))
      )
    }

    it("Ranks a Module only by shifting the aggregate word by rank * Lanes") {
      for {
        lanes <- Seq(4, 8, 16, 32)
        numActive <- Seq(1, 2, 4)
        beat <- 0 until 2
        rank <- 0 until numActive
        j <- 0 until (4 * lanes)
      } {
        val base = globalByte(j, lanes, numActive, 4 * lanes, beat, 0)
        val ranked = globalByte(j, lanes, numActive, 4 * lanes, beat, rank)
        assert(
          ranked == base + rank * lanes,
          s"lanes=$lanes numActive=$numActive beat=$beat rank=$rank j=$j"
        )
      }
    }

    it("Covers every aggregate byte exactly once across Modules and beats") {
      for {
        numModules <- Seq(1, 2, 4)
        lanes <- Seq(4, 8, 16)
        numActive <- permittedActiveCounts(numModules)
      } {
        val bytesPerModule = 64
        val total = numModules * bytesPerModule
        val seen = Array.fill(total)(0)
        for {
          beat <- 0 until beatsPerWord(numModules, numActive)
          rank <- 0 until numActive
          j <- 0 until bytesPerModule
        } {
          val g =
            globalByte(j, lanes, numActive, bytesPerModule, beat, rank)
          assert(
            g < total,
            s"index $g out of range for numModules=$numModules lanes=$lanes"
          )
          seen(g) += 1
        }
        assert(
          seen.forall(_ == 1),
          s"numModules=$numModules lanes=$lanes numActive=$numActive is not a permutation"
        )
      }
    }
  }

  // ==========================================================================
  // Hardware
  // ==========================================================================

  /** Module-local byte index for an aggregate byte, or None if another Module
    * or another beat carries it.
    */
  private def localByteOf(
      global: Int,
      activeLanes: Int,
      numActive: Int,
      bytesPerModule: Int,
      beat: Int,
      rank: Int
  ): Option[Int] =
    (0 until bytesPerModule).find { j =>
      globalByte(
        j,
        activeLanes,
        numActive,
        bytesPerModule,
        beat,
        rank
      ) == global
    }

  private def moduleWord(
      lpData: BigInt,
      activeLanes: Int,
      numActive: Int,
      bytesPerModule: Int,
      beat: Int,
      rank: Int
  ): BigInt =
    (0 until bytesPerModule).foldLeft(BigInt(0)) { case (acc, j) =>
      val g = globalByte(j, activeLanes, numActive, bytesPerModule, beat, rank)
      acc | (((lpData >> (g * 8)) & 0xff) << (j * 8))
    }

  private val permutations: Map[Int, Seq[Seq[Int]]] = Map(
    1 -> Seq(Seq(0)),
    // Table 5-27, x2 unstacked: M0 <-> M1.
    2 -> Seq(Seq(0, 1), Seq(1, 0)),
    // Table 5-27, x4 unstacked Standard Die Rotate: M0 <-> M2, M1 <-> M3.
    // The reversal is not a Table 5-27 pairing; it is extra coverage of the
    // rank arithmetic, which does not care which permutation it is handed.
    4 -> Seq(Seq(0, 1, 2, 3), Seq(2, 3, 0, 1), Seq(3, 2, 1, 0))
  )

  for (numModules <- Seq(1, 2, 4)) {
    describe(s"MmplByteSwizzle with $numModules module(s)") {
      val params = MmplParams(numModules = numModules)
      val bytesPerModule = params.bytesPerModule
      val totalBytes = numModules * bytesPerModule

      it(s"scatters and gathers every configuration (numModules=$numModules)") {
        simulate(new MmplByteSwizzle(params)) { c =>
          val random = new Random(randomSeed)

          for {
            (code, activeLanes) <- laneCodes
            numActive <- permittedActiveCounts(numModules)
            ranks <- permutations(numModules)
          } {
            // Only the first `numActive` ranks are occupied; the Modules
            // holding them are whichever ones the permutation places there.
            val enabled = ranks.zipWithIndex.collect {
              case (r, m) if r < numActive => m
            }

            val lpData = BigInt(totalBytes * 8, random)
            c.io.tx.lpData.poke(lpData.U((totalBytes * 8).W))
            // Loopback, so the two directions run the same configuration.
            c.io.ctrl.txLaneCode.poke(code.U(3.W))
            c.io.ctrl.rxLaneCode.poke(code.U(3.W))
            c.io.ctrl.by8.poke(false.B)
            c.io.ctrl.numActive.poke(numActive.U)
            for (m <- 0 until numModules) {
              c.io.ctrl.txRank(m).poke(ranks(m).U)
              c.io.ctrl.rxRank(m).poke(ranks(m).U)
              c.io.ctrl.enable(m).poke(enabled.contains(m).B)
            }

            var gathered = BigInt(0)
            for (beat <- 0 until beatsPerWord(numModules, numActive)) {
              c.io.ctrl.txBeat.poke(beat.U)
              c.io.ctrl.rxBeat.poke(beat.U)

              val context =
                s"code=$code lanes=$activeLanes numActive=$numActive " +
                  s"ranks=${ranks.mkString(",")} beat=$beat"

              for (m <- 0 until numModules) {
                val expected =
                  if (enabled.contains(m))
                    moduleWord(
                      lpData,
                      activeLanes,
                      numActive,
                      bytesPerModule,
                      beat,
                      ranks(m)
                    )
                  else BigInt(0)
                c.io.tx
                  .moduleData(m)
                  .expect(
                    expected.U((bytesPerModule * 8).W),
                    s"$context module=$m scatter"
                  )
                // Loop the slice straight back so the gather sees exactly what
                // the scatter produced.
                c.io.rx.moduleData(m).poke(expected.U((bytesPerModule * 8).W))
              }

              gathered |= c.io.rx.plData.peek().litValue
            }

            // Every beat together must reconstruct the whole aggregate word.
            assert(
              gathered == lpData,
              s"round trip lost data for code=$code numActive=$numActive " +
                s"ranks=${ranks.mkString(",")}"
            )

          }
        }
      }
    }
  }

  // ==========================================================================
  // Non-contiguous surviving Module sets
  // ==========================================================================
  describe("MmplByteSwizzle with Modules disabled out of the middle") {
    /* Spec Figure 4-46 is a stacked-to-unstacked Link with M0 and M2 disabled,
       and spec Table 5-29 makes {M1, M3} a legal survivor set after two failed
       Module pairs. The permutation sweep above only ever enables a contiguous
       half, so this is the case it cannot reach. */
    val numModules = 4
    val params = MmplParams(numModules = numModules)
    val bytesPerModule = params.bytesPerModule
    val totalBytes = numModules * bytesPerModule

    for (enabled <- Seq(Seq(1, 3), Seq(0, 3), Seq(1, 2), Seq(0, 2))) {
      it(s"round trips with only M${enabled.mkString(" and M")} enabled") {
        simulate(new MmplByteSwizzle(params)) { c =>
          val random = new Random(randomSeed)
          val lpData = BigInt(totalBytes * 8, random)
          // Rank is position among the enabled Modules in ascending order.
          val rank = enabled.zipWithIndex.toMap

          c.io.tx.lpData.poke(lpData.U((totalBytes * 8).W))
          c.io.ctrl.txLaneCode.poke("b011".U(3.W))
          c.io.ctrl.rxLaneCode.poke("b011".U(3.W))
          c.io.ctrl.by8.poke(false.B)
          c.io.ctrl.numActive.poke(enabled.length.U)
          for (m <- 0 until numModules) {
            val r = rank.getOrElse(m, 0)
            c.io.ctrl.txRank(m).poke(r.U)
            c.io.ctrl.rxRank(m).poke(r.U)
            c.io.ctrl.enable(m).poke(enabled.contains(m).B)
          }

          var gathered = BigInt(0)
          for (beat <- 0 until beatsPerWord(numModules, enabled.length)) {
            c.io.ctrl.txBeat.poke(beat.U)
            c.io.ctrl.rxBeat.poke(beat.U)
            for (m <- 0 until numModules) {
              val expected =
                if (enabled.contains(m))
                  moduleWord(
                    lpData,
                    16,
                    enabled.length,
                    bytesPerModule,
                    beat,
                    rank(m)
                  )
                else BigInt(0)
              c.io.tx
                .moduleData(m)
                .expect(
                  expected.U((bytesPerModule * 8).W),
                  s"beat=$beat module=$m scatter"
                )
              c.io.rx.moduleData(m).poke(expected.U((bytesPerModule * 8).W))
            }
            gathered |= c.io.rx.plData.peek().litValue
          }

          assert(
            gathered == lpData,
            s"round trip lost data with enabled=${enabled.mkString(",")}"
          )
        }
      }
    }

    it("gives the least enabled Module the least significant bytes") {
      // Spec 4.7.1 / Figure 4-46: "the remaining bytes of RDI are sent over
      // subsequent 8-UI intervals such that M1 on the remote Link partner
      // receives the least significant bytes."
      val enabled = Seq(1, 3)
      simulate(new MmplByteSwizzle(params)) { c =>
        // A word whose every byte is its own index makes the mapping readable.
        val lpData = (0 until totalBytes).foldLeft(BigInt(0)) { (acc, i) =>
          acc | (BigInt(i & 0xff) << (i * 8))
        }
        c.io.tx.lpData.poke(lpData.U((totalBytes * 8).W))
        c.io.ctrl.txLaneCode.poke("b011".U(3.W))
        c.io.ctrl.rxLaneCode.poke("b011".U(3.W))
        c.io.ctrl.by8.poke(false.B)
        c.io.ctrl.numActive.poke(2.U)
        for (m <- 0 until numModules) {
          val r = if (m == 3) 1 else 0
          c.io.ctrl.txRank(m).poke(r.U)
          c.io.ctrl.rxRank(m).poke(r.U)
          c.io.ctrl.enable(m).poke(enabled.contains(m).B)
        }
        c.io.ctrl.txBeat.poke(0.U)

        // Beat 0, first 8-UI chunk: M1 (rank 0) takes the Lane-width block at
        // the bottom of the word, M3 (rank 1) the block above it.
        val activeLanes = 16
        val m1 = c.io.tx.moduleData(1).peek().litValue
        val m3 = c.io.tx.moduleData(3).peek().litValue
        assert(
          (m1 & 0xff) == 0,
          s"M1 should start at RDI byte 0, got ${m1 & 0xff}"
        )
        assert(
          (m3 & 0xff) == activeLanes,
          s"M3 should start at RDI byte $activeLanes, got ${m3 & 0xff}"
        )
        // ...and the disabled Modules carry nothing at all.
        for (m <- Seq(0, 2)) {
          c.io.tx
            .moduleData(m)
            .expect(0.U, s"disabled module $m must not be given bytes")
        }
      }
    }
  }
}
