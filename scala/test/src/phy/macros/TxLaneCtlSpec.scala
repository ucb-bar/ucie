package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._

import org.scalatest.funspec.AnyFunSpec

// The tile's control pins reach the driver unchanged, so nothing translates a
// count into a code at run time and the whole risk sits in the polarity of the
// codes software starts from. Getting `ENP` backwards leaves a lane silently
// not transmitting, so pin both rails down here.
class TxLaneCtlSpec extends AnyFunSpec {
  val driverMask: BigInt = (BigInt(1) << TxLane.DriverSegments) - 1
  val eqMask: BigInt = (BigInt(1) << TxLane.EqSegments) - 1
  val delayMask: BigInt = (BigInt(1) << TxLane.DelayTaps) - 1

  describe("thermometer codes") {
    it("should enable the low segments on an active high rail") {
      assert(TxLane.thermometer(0, 9) == 0)
      assert(TxLane.thermometer(1, 9) == BigInt("000000001", 2))
      assert(TxLane.thermometer(5, 9) == BigInt("000011111", 2))
      assert(TxLane.thermometer(9, 9) == BigInt("111111111", 2))
    }

    it("should enable the low segments on an active low rail") {
      assert(TxLane.thermometerB(0, 9) == BigInt("111111111", 2))
      assert(TxLane.thermometerB(1, 9) == BigInt("111111110", 2))
      assert(TxLane.thermometerB(5, 9) == BigInt("111100000", 2))
      assert(TxLane.thermometerB(9, 9) == 0)
    }

    it("should be complements of each other at every count") {
      for (segments <- Seq(TxLane.EqSegments, TxLane.DriverSegments)) {
        val mask = (BigInt(1) << segments) - 1
        for (count <- 0 to segments) {
          withClue(s"$count of $segments: ") {
            assert(
              (TxLane.thermometer(count, segments) ^
                TxLane.thermometerB(count, segments)) == mask
            )
          }
        }
      }
    }

    it("should reject counts outside the segment count") {
      assertThrows[IllegalArgumentException](TxLane.thermometer(10, 9))
      assertThrows[IllegalArgumentException](TxLane.thermometer(-1, 9))
      assertThrows[IllegalArgumentException](TxLane.thermometerB(5, 4))
    }
  }

  describe("lane control codes") {
    it("should leave every segment off in the reset state") {
      val off = TxLaneCtlIO.off
      // `ENP` and `ENP_EQ` are active low, so off reads all ones there and all
      // zeros on the active high rails.
      assert(off.ENP.litValue == driverMask)
      assert(off.ENN.litValue == 0)
      assert(off.ENP_EQ.litValue == eqMask)
      assert(off.ENN_EQ.litValue == 0)
      assert(off.Dctrl.litValue == 0)
    }

    it("should turn on every main segment at full strength") {
      val full = TxLaneCtlIO.full
      assert(full.ENP.litValue == 0)
      assert(full.ENN.litValue == driverMask)
      // The equalizer branch and the clock delay stay off.
      assert(full.ENP_EQ.litValue == eqMask)
      assert(full.ENN_EQ.litValue == 0)
      assert(full.Dctrl.litValue == 0)
    }

    it("should invert both driver rails together between off and full") {
      // Both rails reading the same code would be the driver shorting VDDQ to
      // VSS through itself, so the two states have to be complements.
      assert(
        (TxLaneCtlIO.off.ENP.litValue ^ TxLaneCtlIO.full.ENP.litValue) ==
          driverMask
      )
      assert(
        (TxLaneCtlIO.off.ENN.litValue ^ TxLaneCtlIO.full.ENN.litValue) ==
          driverMask
      )
    }

    it("should build every rail independently from segment counts") {
      val c = TxLaneCtlIO.codes(driver = 3, eq = 2, delay = TxLane.DelayTaps)
      assert(c.ENP.litValue == TxLane.thermometerB(3, TxLane.DriverSegments))
      assert(c.ENN.litValue == TxLane.thermometer(3, TxLane.DriverSegments))
      assert(c.ENP_EQ.litValue == TxLane.thermometerB(2, TxLane.EqSegments))
      assert(c.ENN_EQ.litValue == TxLane.thermometer(2, TxLane.EqSegments))
      assert(c.Dctrl.litValue == delayMask)
    }
  }
}
