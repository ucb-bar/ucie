package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._

import org.scalatest.funspec.AnyFunSpec

// The control bits reach the tile unchanged, so the risk is in the polarity of
// the codes software starts from: all active high except `ENP_EQ`.
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

    it("should enable the low segments with zeros when active low") {
      assert(TxLane.thermometerLow(0, 4) == BigInt("1111", 2))
      assert(TxLane.thermometerLow(1, 4) == BigInt("1110", 2))
      assert(TxLane.thermometerLow(3, 4) == BigInt("1000", 2))
      assert(TxLane.thermometerLow(4, 4) == 0)
      assertThrows[IllegalArgumentException](TxLane.thermometerLow(5, 4))
    }
  }

  describe("lane control codes") {
    it("should leave the driver high impedance in the reset state") {
      // No driver enable: high impedance is all zeros, `ENP_EQ` all ones.
      val off = TxLaneCtlIO.off
      assert(off.ENP.litValue == 0)
      assert(off.ENN.litValue == 0)
      assert(off.ENP_EQ.litValue == eqMask)
      assert(off.ENN_EQ.litValue == 0)
      assert(off.Dctrl.litValue == 0)
    }

    it("should turn on every segment of both main stacks at full strength") {
      val full = TxLaneCtlIO.full
      assert(full.ENP.litValue == driverMask)
      assert(full.ENN.litValue == driverMask)
      // The equalizer branch and the clock delay stay off.
      assert(full.ENP_EQ.litValue == eqMask)
      assert(full.ENN_EQ.litValue == 0)
      assert(full.Dctrl.litValue == 0)
    }

    it("should build every control from segment counts") {
      val c = TxLaneCtlIO.codes(driver = 3, eq = 2, delay = TxLane.DelayTaps)
      assert(c.ENP.litValue == TxLane.thermometer(3, TxLane.DriverSegments))
      assert(c.ENN.litValue == TxLane.thermometer(3, TxLane.DriverSegments))
      assert(c.ENP_EQ.litValue == BigInt("1100", 2))
      assert(c.ENN_EQ.litValue == TxLane.thermometer(2, TxLane.EqSegments))
      assert(c.Dctrl.litValue == delayMask)
    }

    it("should carry each control bit exactly as given") {
      // Software sets each segment on its own, so each control bit must land
      // in a bit of the word of its own, uninverted.
      val zero = TxLaneCtlIO.raw(0, 0, 0, 0, 0).litValue
      val pins = Seq[(String, Int, Int => TxLaneCtlIO, TxLaneCtlIO => BigInt)](
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
        ),
        (
          "Edge_Sel",
          1,
          _ => TxLaneCtlIO.raw(0, 0, 0, 0, 0, edgeSel = true),
          _.Edge_Sel.litValue
        )
      )
      val seen = scala.collection.mutable.Set[BigInt]()
      for ((name, width, build, field) <- pins; b <- 0 until width) {
        withClue(s"$name bit $b: ") {
          val c = build(b)
          assert(field(c) == (BigInt(1) << b))
          val word = c.litValue ^ zero
          assert(word.bitCount == 1)
          assert(seen.add(word), "two control bits share a word bit")
        }
      }
    }

    it("should keep Edge_Sel off and in the word's top bit") {
      // Off by default, so lanes keep their reset edge unless software asks.
      assert(TxLaneCtlIO.off.Edge_Sel.litValue == 0)
      assert(TxLaneCtlIO.full.Edge_Sel.litValue == 0)
      // The MSB, so the other control bits keep their positions.
      val width = (new TxLaneCtlIO).getWidth
      val edge = TxLaneCtlIO.raw(0, 0, 0, 0, 0, edgeSel = true).litValue
      assert(edge == (BigInt(1) << (width - 1)))
    }
  }
}
