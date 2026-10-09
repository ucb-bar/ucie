package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._

import org.scalatest.funspec.AnyFunSpec

// The tile's control pins reach the driver unchanged, so nothing translates a
// count into a code at run time and the whole risk sits in the polarity of the
// codes software starts from. Every rail is active high; getting `ENP`
// backwards leaves a lane unable to drive a one, so pin the rails down here.
class TxLaneCtlSpec extends AnyFunSpec {
  val driverMask: BigInt = (BigInt(1) << TxLane.DriverSegments) - 1
  val eqMask: BigInt = (BigInt(1) << TxLane.EqSegments) - 1
  val delayMask: BigInt = (BigInt(1) << TxLane.DelayTaps) - 1

  describe("thermometer codes") {
    it("should enable the low segments") {
      assert(TxLane.thermometer(0, 9) == 0)
      assert(TxLane.thermometer(1, 9) == BigInt("000000001", 2))
      assert(TxLane.thermometer(5, 9) == BigInt("000011111", 2))
      assert(TxLane.thermometer(9, 9) == BigInt("111111111", 2))
    }

    it("should reject counts outside the segment count") {
      assertThrows[IllegalArgumentException](TxLane.thermometer(10, 9))
      assertThrows[IllegalArgumentException](TxLane.thermometer(-1, 9))
    }
  }

  describe("lane control codes") {
    it("should leave the driver high impedance in the reset state") {
      // No separate driver enable: high impedance is every segment of every
      // stack off, which on active high rails is all zeros.
      val off = TxLaneCtlIO.off
      assert(off.ENP.litValue == 0)
      assert(off.ENN.litValue == 0)
      assert(off.ENP_EQ.litValue == 0)
      assert(off.ENN_EQ.litValue == 0)
      assert(off.Dctrl.litValue == 0)
    }

    it("should turn on every segment of both main stacks at full strength") {
      val full = TxLaneCtlIO.full
      assert(full.ENP.litValue == driverMask)
      assert(full.ENN.litValue == driverMask)
      // The equalizer branch and the clock delay stay off.
      assert(full.ENP_EQ.litValue == 0)
      assert(full.ENN_EQ.litValue == 0)
      assert(full.Dctrl.litValue == 0)
    }

    it("should build every rail from segment counts") {
      val c = TxLaneCtlIO.codes(driver = 3, eq = 2, delay = TxLane.DelayTaps)
      assert(c.ENP.litValue == TxLane.thermometer(3, TxLane.DriverSegments))
      assert(c.ENN.litValue == TxLane.thermometer(3, TxLane.DriverSegments))
      assert(c.ENP_EQ.litValue == TxLane.thermometer(2, TxLane.EqSegments))
      assert(c.ENN_EQ.litValue == TxLane.thermometer(2, TxLane.EqSegments))
      assert(c.Dctrl.litValue == delayMask)
    }

    it("should carry each rail exactly as given, one bit at a time") {
      // Software has to be able to set any segment of any stack on its own,
      // so a single bit in one rail must land in that rail alone, uninverted.
      val zero = TxLaneCtlIO.off.litValue
      val rails = Seq[(String, Int, Int => TxLaneCtlIO, TxLaneCtlIO => BigInt)](
        (
          "ENP",
          TxLane.DriverSegments,
          b => TxLaneCtlIO.raw(BigInt(1) << b, 0, 0, 0, 0),
          _.ENP.litValue
        ),
        (
          "ENN",
          TxLane.DriverSegments,
          b => TxLaneCtlIO.raw(0, BigInt(1) << b, 0, 0, 0),
          _.ENN.litValue
        ),
        (
          "ENP_EQ",
          TxLane.EqSegments,
          b => TxLaneCtlIO.raw(0, 0, BigInt(1) << b, 0, 0),
          _.ENP_EQ.litValue
        ),
        (
          "ENN_EQ",
          TxLane.EqSegments,
          b => TxLaneCtlIO.raw(0, 0, 0, BigInt(1) << b, 0),
          _.ENN_EQ.litValue
        ),
        (
          "Dctrl",
          TxLane.DelayTaps,
          b => TxLaneCtlIO.raw(0, 0, 0, 0, BigInt(1) << b),
          _.Dctrl.litValue
        )
      )
      val seen = scala.collection.mutable.Set[BigInt]()
      for ((name, width, build, field) <- rails; b <- 0 until width) {
        withClue(s"$name bit $b: ") {
          val c = build(b)
          assert(field(c) == (BigInt(1) << b))
          val word = c.litValue ^ zero
          assert(word.bitCount == 1)
          assert(seen.add(word), "two rails share a bit of the packed word")
        }
      }
    }
  }
}
