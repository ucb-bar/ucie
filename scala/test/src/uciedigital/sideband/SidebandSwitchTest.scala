package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.simutils.VerilatorCoverage
import org.scalatest.funspec.AnyFunSpec
import scala.util.Random

class SidebandSwitchTest extends AnyFunSpec with ChiselSim with VerilatorCoverage {
  val msgW = 128
  val UPPER = 0; val CURR = 1; val LOWER = 2

  val printDebugs = false
  def printDebug(msg: String): Unit = if (printDebugs) println(s"[SidebandSwitchTest] $msg")
  def portName(i: Int): String = Seq("UPPER", "CURR", "LOWER", "DROP")(if (i == -1) 3 else i)

  // Adapter layer (curr=1, upper={0}, lower={2}) is the only config that exercises all six routes.
  def adapter = new SidebandSwitch(layerId = 1, upperIds = Seq(0), lowerIds = Seq(2), sbMsgWidth = msgW)

  def mkMsg(dstLayer: Int, remote: Boolean, tag: BigInt): BigInt =
    (BigInt(if (remote) 1 else 0) << 58) | (BigInt(dstLayer & 0x3) << 56) | (tag << 64)

  def ingress(c: SidebandSwitch, i: Int) =
    Seq(c.io.upperLayer.from, c.io.currLayer.from, c.io.lowerLayer.from)(i)
  def egress(c: SidebandSwitch, i: Int) =
    Seq(c.io.upperLayer.to, c.io.currLayer.to, c.io.lowerLayer.to)(i)
  def errFor(c: SidebandSwitch, i: Int) =
    Seq(c.io.err.invalidRouteUpper, c.io.err.invalidRouteCurr, c.io.err.invalidRouteLower)(i)

  def initIdle(c: SidebandSwitch): Unit = {
    Seq(UPPER, CURR, LOWER).foreach(ingress(c, _).valid.poke(false.B))
    c.clock.step()
  }

  // Drive one message on `from`; expect it only on `exp` egress, or dropped + err when exp == -1.
  def routeOne(c: SidebandSwitch, from: Int, msg: BigInt, exp: Int): Unit = {
    Seq(UPPER, CURR, LOWER).foreach(egress(c, _).ready.poke(true.B))
    val in = ingress(c, from)
    in.bits.poke(msg.U(msgW.W))
    in.valid.poke(true.B)
    printDebug(f"${portName(from)}%-5s dst=${(msg >> 56) & 0x3} remote=${(msg >> 58) & 0x1} " +
      f"tag=0x${msg >> 64}%x -> ${portName(exp)}")
    Seq(UPPER, CURR, LOWER).foreach { e =>
      if (e == exp) {
        egress(c, e).valid.expect(true.B)
        egress(c, e).bits.expect(msg.U(msgW.W))
      } else egress(c, e).valid.expect(false.B)
    }
    in.ready.expect(true.B)
    errFor(c, from).expect((exp == -1).B)
    c.clock.step()
    in.valid.poke(false.B)
  }

  describe("SidebandSwitch routing") {
    it("routes from the upper layer") {
      simulate(adapter) { c =>
        initIdle(c)
        routeOne(c, UPPER, mkMsg(1, false, 0x1), CURR)   // dst == curr
        routeOne(c, UPPER, mkMsg(2, false, 0x2), LOWER)  // dst in lowerIds
        routeOne(c, UPPER, mkMsg(1, true, 0x3), LOWER)   // remote forces lower
      }
    }

    it("routes from the current layer") {
      simulate(adapter) { c =>
        initIdle(c)
        routeOne(c, CURR, mkMsg(0, false, 0x1), UPPER)   // dst in upperIds
        routeOne(c, CURR, mkMsg(2, false, 0x2), LOWER)   // dst in lowerIds
        routeOne(c, CURR, mkMsg(0, true, 0x3), LOWER)    // remote forces lower
      }
    }

    it("routes from the lower layer and ignores the remote bit") {
      simulate(adapter) { c =>
        initIdle(c)
        routeOne(c, LOWER, mkMsg(1, false, 0x1), CURR)   // dst == curr
        routeOne(c, LOWER, mkMsg(0, false, 0x2), UPPER)  // dst in upperIds
        routeOne(c, LOWER, mkMsg(0, true, 0x3), UPPER)   // remote bit ignored on lower ingress
      }
    }

    it("drops and flags packets with no legal destination") {
      simulate(adapter) { c =>
        initIdle(c)
        routeOne(c, UPPER, mkMsg(3, false, 0x1), -1)     // reserved dst
        routeOne(c, CURR, mkMsg(1, false, 0x2), -1)      // curr -> curr is illegal
        routeOne(c, LOWER, mkMsg(2, false, 0x3), -1)     // lower -> lower is illegal
      }
    }

    it("round-robins two sources contending for the same egress") {
      simulate(adapter) { c =>
        initIdle(c)
        Seq(UPPER, CURR, LOWER).foreach(egress(c, _).ready.poke(true.B))
        c.io.upperLayer.from.bits.poke(mkMsg(1, false, 0xA).U(msgW.W))
        c.io.upperLayer.from.valid.poke(true.B)
        c.io.lowerLayer.from.bits.poke(mkMsg(1, false, 0xB).U(msgW.W))
        c.io.lowerLayer.from.valid.poke(true.B)

        val seen = scala.collection.mutable.Set[BigInt]()
        c.io.currLayer.to.valid.expect(true.B)
        seen += (c.io.currLayer.to.bits.peek().litValue >> 64)
        if (c.io.upperLayer.from.ready.peek().litToBoolean) c.io.upperLayer.from.valid.poke(false.B)
        else c.io.lowerLayer.from.valid.poke(false.B)
        c.clock.step()

        c.io.currLayer.to.valid.expect(true.B)
        seen += (c.io.currLayer.to.bits.peek().litValue >> 64)
        c.clock.step()
        printDebug(s"contention delivered tags=${seen.map(t => f"0x$t%x")}")
        assert(seen == Set(BigInt(0xA), BigInt(0xB)))
      }
    }

    it("never delivers to a port with no configured ids (protocol layer)") {
      simulate(new SidebandSwitch(layerId = 0, upperIds = Seq(), lowerIds = Seq(1, 2), sbMsgWidth = msgW)) { c =>
        initIdle(c)
        c.io.upperLayer.to.ready.poke(true.B)
        routeOne(c, CURR, mkMsg(1, false, 0x1), LOWER)   // curr -> lower (first lowerId)
        routeOne(c, CURR, mkMsg(2, false, 0x2), LOWER)   // curr -> lower (second lowerId)
        routeOne(c, LOWER, mkMsg(0, false, 0x3), CURR)   // lower -> curr
        routeOne(c, CURR, mkMsg(3, false, 0x4), -1)      // nothing can reach the (empty) upper port
      }
    }

    it("never delivers to the lower port with no lower ids (logphy layer)") {
      simulate(new SidebandSwitch(layerId = 2, upperIds = Seq(0, 1), lowerIds = Seq(), sbMsgWidth = msgW)) { c =>
        initIdle(c)
        c.io.lowerLayer.to.ready.poke(true.B)
        routeOne(c, UPPER, mkMsg(2, false, 0x1), CURR)   // dst == curr
        routeOne(c, CURR, mkMsg(0, false, 0x2), UPPER)   // first upperId
        routeOne(c, CURR, mkMsg(1, false, 0x3), UPPER)   // second upperId
        routeOne(c, CURR, mkMsg(2, false, 0x4), -1)      // curr -> curr is illegal, no lower port
      }
    }

    it("stalls the ingress and holds the packet while the egress is not ready") {
      simulate(adapter) { c =>
        initIdle(c)
        c.io.upperLayer.to.ready.poke(true.B)
        c.io.lowerLayer.to.ready.poke(true.B)
        c.io.currLayer.to.ready.poke(false.B)
        val msg = mkMsg(1, false, 0x55) // upper -> curr
        c.io.upperLayer.from.bits.poke(msg.U(msgW.W))
        c.io.upperLayer.from.valid.poke(true.B)

        for (_ <- 0 until 3) {
          c.io.currLayer.to.valid.expect(true.B)
          c.io.currLayer.to.bits.expect(msg.U(msgW.W)) // bits held stable
          c.io.upperLayer.from.ready.expect(false.B)   // stalled, not dropped
          c.io.err.invalidRouteUpper.expect(false.B)   // not an error
          c.clock.step()
        }

        c.io.currLayer.to.ready.poke(true.B)
        c.io.upperLayer.from.ready.expect(true.B)       // accepted once egress is ready
        c.clock.step()
      }
    }

    it("routes three non-conflicting flows simultaneously") {
      simulate(adapter) { c =>
        initIdle(c)
        Seq(UPPER, CURR, LOWER).foreach(egress(c, _).ready.poke(true.B))
        val toCurr = mkMsg(1, false, 0x1) // upper -> curr
        val toLower = mkMsg(2, false, 0x2) // curr  -> lower
        val toUpper = mkMsg(0, false, 0x3) // lower -> upper
        c.io.upperLayer.from.bits.poke(toCurr.U(msgW.W))
        c.io.upperLayer.from.valid.poke(true.B)
        c.io.currLayer.from.bits.poke(toLower.U(msgW.W)) 
        c.io.currLayer.from.valid.poke(true.B)
        c.io.lowerLayer.from.bits.poke(toUpper.U(msgW.W))
        c.io.lowerLayer.from.valid.poke(true.B)

        c.io.currLayer.to.valid.expect(true.B) 
        c.io.currLayer.to.bits.expect(toCurr.U(msgW.W))
        c.io.lowerLayer.to.valid.expect(true.B)
        c.io.lowerLayer.to.bits.expect(toLower.U(msgW.W))
        c.io.upperLayer.to.valid.expect(true.B)
        c.io.upperLayer.to.bits.expect(toUpper.U(msgW.W))

        c.io.upperLayer.from.ready.expect(true.B)
        c.io.currLayer.from.ready.expect(true.B)
        c.io.lowerLayer.from.ready.expect(true.B)
        c.clock.step()
      }
    }

    it("does not starve either source under sustained contention") {
      simulate(adapter) { c =>
        initIdle(c)
        c.io.currLayer.to.ready.poke(true.B)
        c.io.upperLayer.from.bits.poke(mkMsg(1, false, 0xA).U(msgW.W)) 
        c.io.upperLayer.from.valid.poke(true.B)
        c.io.lowerLayer.from.bits.poke(mkMsg(1, false, 0xB).U(msgW.W))
        c.io.lowerLayer.from.valid.poke(true.B)
        var upperGrants = 0
        var lowerGrants = 0
        for (_ <- 0 until 20) {
          if (c.io.upperLayer.from.ready.peek().litToBoolean) upperGrants += 1
          if (c.io.lowerLayer.from.ready.peek().litToBoolean) lowerGrants += 1
          c.clock.step()
        }
        printDebug(s"sustained contention upperGrants=$upperGrants lowerGrants=$lowerGrants")
        assert(upperGrants > 0 && lowerGrants > 0, "a source was starved")
        assert(math.abs(upperGrants - lowerGrants) <= 1, "round-robin was unfair")
      }
    }

    it("never misroutes or drops well-formed traffic (randomized)") {
      simulate(adapter) { c =>
        initIdle(c)
        val rng = new Random(1)
        def model(from: Int, dst: Int, remote: Boolean): Int = from match {
          case UPPER => if (!remote && dst == 1) CURR else if (remote || dst == 2) LOWER else -1
          case CURR  => if (!remote && dst == 0) UPPER else if (remote || dst == 2) LOWER else -1
          case LOWER => if (dst == 1) CURR else if (dst == 0) UPPER else -1
        }
        for (tag <- 1 to 300) {
          val from = rng.nextInt(3)
          val dst = rng.nextInt(4)
          val remote = rng.nextBoolean()
          routeOne(c, from, mkMsg(dst, remote, tag), model(from, dst, remote))
        }
      }
    }
  }
}
