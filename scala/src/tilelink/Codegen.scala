package edu.berkeley.cs.uciedigital.tilelink

import scala.collection.mutable

import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.diplomacy.AddressSet
import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.experimental.BundleLiterals._
import org.chipsalliance.diplomacy.lazymodule._
import chisel3.stage.DesignAnnotation
import edu.berkeley.cs.chippy.{
  TLTesterParams,
  TLTester,
  TLTesterIO,
  TLTesterReq,
  TLTesterResp,
  TLRequestDescriptor
}
import freechips.rocketchip.prci.{ClockSourceNode, ClockSourceParameters}

import edu.berkeley.cs.uciedigital.phytest.{
  BandMode,
  CaptureMode,
  PhyTest,
  RxStartMode,
  TestTarget,
  TxTestMode,
  DataMode,
  TxTestState
}
import edu.berkeley.cs.uciedigital.phy.macros.{
  PadDriverCtlIO,
  TxLane,
  TxLaneCtlIO
}
import edu.berkeley.cs.uciedigital.phy.Phy
import edu.berkeley.cs.uciedigital.phy.macros.clocking.ClockingTile

/** Backend code formatter used by `Codegen`. Each method emits a snippet in the
  * target language (SystemVerilog, C, etc.). Each implementation does its own
  * language setup. For example, `CFormatter` adds an implicit `base` parameter
  * to every function so register writes can compute absolute addresses.
  */
trait Formatter {
  def formatFn(name: String, body: String, args: Seq[Arg] = Seq.empty): String
  def formatFnCall(name: String, args: Seq[String] = Seq.empty): String
  def formatForLoop(loopVar: String, length: Int, body: String): String
  def formatWhileLoop(condition: String, body: String): String
  def formatIfStmt(condition: String, body: String): String

  /** `body` in a scope of its own, so it can declare a local partway through a
    * function: SystemVerilog only takes declarations at the top of a block.
    */
  def formatBlock(body: String): String
  def formatPrintStmt(msg: String): String

  /** Declares a 64-bit variable. SystemVerilog takes declarations only at the
    * start of a block, so open one with `formatBlock` and declare first.
    */
  def formatDeclare(name: String): String
  def formatAssign(name: String, value: String): String
  def breakStmt(): String
  def formatWaitCycles(n: Int): String
  def formatBool(bool: Boolean): String
  def formatConstantRef(name: String): String
  def formatWrite(drv: String, addr: String, value: String): String
  def formatWriteReg(drv: String, addr: String, value: String): String
  def formatRead(
      drv: String,
      outputName: String,
      addr: String,
      declareVar: Boolean = true
  ): String
  def formatReadReg(
      drv: String,
      outputName: String,
      addr: String,
      declareVar: Boolean = true
  ): String
  def formatAssertEq(
      drv: String,
      addr: String,
      value: String,
      msg: Option[String] = None
  ): String
  def formatUcieAssertEq(
      drv: String,
      addr: String,
      value: String,
      msg: Option[String] = None
  ): String
  def formatLong(value: Long): String
  def formatDefine(name: String, value: String): String
}

sealed trait Datatype
object Datatype {
  case object Long extends Datatype
}

case class Arg(name: String, datatype: Datatype)

class SystemVerilogFormatter extends Formatter {
  def getConstantName(name: String): String = {
    name
      // insert underscore between lowercase or number and uppercase
      .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
      // insert underscore between consecutive uppercase letters followed by lowercase (for acronyms)
      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
      .toUpperCase
  }
  def formatFn(
      name: String,
      body: String,
      args: Seq[Arg] = Seq.empty
  ): String = {
    s"""task $name(${args
        .map {
          case Arg(name, datatype) => {
            val datatypeString = datatype match {
              case Datatype.Long => "[63:0]"
            }
            s"input $datatypeString $name"
          }
        }
        .mkString(", ")});
  begin
${Codegen.indent(body)}
  end
endtask
"""
  }
  def formatFnCall(name: String, args: Seq[String] = Seq.empty): String = {
    s"$name(${args.mkString(", ")});\n"
  }
  def formatForLoop(loopVar: String, length: Int, body: String): String = {
    s"""for (int $loopVar = 0; $loopVar < $length; $loopVar++) begin
${Codegen.indent(body)}
end
"""
  }
  def formatWhileLoop(condition: String, body: String): String = {
    s"""while ($condition) begin
${Codegen.indent(body)}
end
"""
  }
  def formatIfStmt(condition: String, body: String): String = {
    s"""if ($condition) begin
${Codegen.indent(body)}
end
"""
  }
  def formatBlock(body: String): String = {
    s"""begin
${Codegen.indent(body)}
end
"""
  }
  def formatPrintStmt(msg: String): String = {
    s"$$display(\"${Codegen.escapeString(msg)}\");\n"
  }
  def formatDeclare(name: String): String = s"reg [63:0] $name;\n"
  def formatAssign(name: String, value: String): String = s"$name = $value;\n"
  def breakStmt(): String = {
    "break;\n"
  }
  def formatWaitCycles(n: Int): String = {
    s"repeat($n) @(posedge digitalClock);\n"
  }
  def formatBool(bool: Boolean): String = {
    if (bool) { "1'b1" }
    else { "1'b0" }
  }
  def formatConstantRef(name: String): String = {
    s"`${getConstantName(name)}"
  }
  def formatWrite(drv: String, addr: String, value: String) = {
    s"`WRITE($drv, $addr, $value);\n"
  }
  def formatWriteReg(drv: String, addr: String, value: String) = {
    s"`WRITE_UCIE($drv, $addr, $value);\n"
  }
  def formatRead(
      drv: String,
      outputName: String,
      addr: String,
      declareVar: Boolean = true
  ): String = {
    val sb = new StringBuilder
    if (declareVar) {
      sb.append(s"reg [63:0] $outputName;\n")

    }
    sb.append(s"`READ($drv, $addr, $outputName);\n")
    sb.toString
  }
  def formatReadReg(
      drv: String,
      outputName: String,
      addr: String,
      declareVar: Boolean = true
  ): String = {
    val sb = new StringBuilder
    if (declareVar) {
      sb.append(s"reg [63:0] $outputName;\n")

    }
    sb.append(s"`READ_UCIE($drv, $addr, $outputName);\n")
    sb.toString
  }
  def formatAssertEq(
      drv: String,
      addr: String,
      value: String,
      msg: Option[String] = None
  ): String = {
    msg match {
      case Some(msg) =>
        f"`EXPECT_MSG($drv, $addr, $value, \"${Codegen.escapeString(msg)}\");\n"
      case None => f"`EXPECT($drv, $addr, $value);\n"
    }
  }
  def formatUcieAssertEq(
      drv: String,
      addr: String,
      value: String,
      msg: Option[String] = None
  ): String = {
    msg match {
      case Some(msg) =>
        f"`EXPECT_UCIE_MSG($drv, $addr, $value, \"${Codegen.escapeString(msg)}\");\n"
      case None => f"`EXPECT_UCIE($drv, $addr, $value);\n"
    }
  }
  def formatLong(value: Long): String = {
    f"64'h$value%X"
  }
  def formatDefine(name: String, value: String): String = {
    f"`define ${getConstantName(name)} $value\n"
  }
}

object Codegen {

  /** Returns `defines` unchanged, or fails if it defines a name twice. Two
    * fields can map to one name (in C, `rst` and `ucieRst` both become
    * `UCIE_RST`), and a compiler only warns about a redefined macro.
    */
  def requireUniqueDefines(defines: String): String = {
    val define = "^\\s*[#`]define\\s+(\\S+)".r
    val names = defines.linesIterator.flatMap { line =>
      define.findFirstMatchIn(line).map(_.group(1))
    }.toSeq
    val repeated = names.diff(names.distinct).distinct.sorted
    require(
      repeated.isEmpty,
      s"generated names defined more than once: ${repeated.mkString(", ")}"
    )
    defines
  }

  def indent(content: String, n: Int = 1): String = {
    content.split("\n").map(line => s"${"  " * n}$line").mkString("\n")
  }
  def escapeString(s: String): String =
    s.flatMap {
      case '\n'             => "\\n"
      case '\t'             => "\\t"
      case '\r'             => "\\r"
      case '\"'             => "\\\""
      case '\\'             => "\\\\"
      case c if c.isControl => f"\\u${c.toInt}%04x"
      case c                => c.toString
    }

  val defaultClkP: BigInt = BigInt(0x55555555L)
  val defaultClkN: BigInt = BigInt(0xaaaaaaaaL)
  val defaultValid: BigInt = BigInt(0x0f0f0f0fL)
  val defaultTrack: BigInt = BigInt(0x55555555L)

  // Standalone drivers (sideband, debug clocks), which are a different cell
  // from the driver inside a TX tile.
  val enableDriverCtl: BigInt = PadDriverCtlIO.full.litValue

  // TX tile at full driver strength with the equalizer branch off and no added
  // clock delay, which is enough to get a lane transmitting during bring-up.
  val enableTxCtl: BigInt = TxLaneCtlIO.full.litValue

  /** Position of `name`'s lowest control bit in the packed `TxLaneCtlIO` word,
    * from `one`, which sets only that bit. Read off the Chisel type so a new
    * control bit cannot silently move it.
    */
  private def txCtlLsb(name: String, one: TxLaneCtlIO): BigInt = {
    val bit = one.litValue
    require(
      bit.bitCount == 1,
      s"one bit of `$name` should set exactly one bit of the word, got 0x${bit.toString(16)}"
    )
    BigInt(bit.lowestSetBit)
  }

  /** Bit position of `Dctrl` inside the packed `TxLaneCtlIO` word.
    *
    * A training sweep needs one control word per delay tap. With this position
    * it builds each word at run time from [[enableTxCtl]], with no constant per
    * tap.
    */
  val txCtlDelayLsb: BigInt =
    txCtlLsb("Dctrl", TxLaneCtlIO.raw(0, 0, 0, 0, 1))

  /** Every bit of `Dctrl` inside the packed `TxLaneCtlIO` word, so a delay can
    * be changed without touching the driver and equalizer fields around it.
    */
  val txCtlDelayMask: BigInt =
    ((BigInt(1) << TxLane.DelayTaps) - 1) << txCtlDelayLsb.toInt

  /** Positions of the driver's control bits in the packed `TxLaneCtlIO` word,
    * so software can set each segment on its own. All are active high except
    * `ENP_EQ`: a high impedance driver is all zeros with `ENP_EQ` all ones.
    */
  val txCtlEnpLsb: BigInt = txCtlLsb("ENP", TxLaneCtlIO.raw(1, 0, 0, 0, 0))
  val txCtlEnnLsb: BigInt = txCtlLsb("ENN", TxLaneCtlIO.raw(0, 1, 0, 0, 0))
  val txCtlEnpEqLsb: BigInt =
    txCtlLsb("ENP_EQ", TxLaneCtlIO.raw(0, 0, 1, 0, 0))
  val txCtlEnnEqLsb: BigInt =
    txCtlLsb("ENN_EQ", TxLaneCtlIO.raw(0, 0, 0, 1, 0))

  /** The Iris harness's centred global delay on the quadrature clock: a quarter
    * of the TX clock period, which centres the forwarded clock in the data eye.
    * Tests set it before gating the clock or handing the link to the UCIe
    * controller; on silicon, bring-up uses the code it trained.
    *
    * Sized for the reset clocking: main clock on the 8 GHz bypass pin, TX
    * division /1, so a 125 ps period. The behavioral delay line is a fixed
    * offset plus under 2 ps per tap. At 51 taps, TxClkQ trails TxClk by a
    * quarter period, modulo the period. A part brought up at a different rate
    * needs its own value.
    */
  val digitalGlobalDelayTaps: Int = 51

  /** Seed for both ends of a training run's LFSRs.
    *
    * Any nonzero value works. The RX scores what it receives against its own
    * LFSR run from the same seed, so the two ends only have to agree.
    */
  val trainLfsrSeed: BigInt = PhyTest.DefaultLfsrSeed

  /** Data lanes a training run sweeps: all of them.
    *
    * Each lane has its own delay and reference codes and its own bit error
    * counter, so one sweep trains every lane. Each point programs every lane to
    * the same code and reads one result per lane. Each lane then keeps the code
    * with its best score. The clock tree gives each lane a different arrival
    * time, so the lanes' best codes can differ.
    */
  def trainDataLanes(numLanes: Int): Int = numLanes

  /** The valid lane, swept with the data lanes.
    *
    * The receiver frames every lane's comparison on the edge it sees on valid.
    * A valid code off by a UI therefore shifts the framing of every lane, not
    * just one. `rxBitErrorsEarly` and `rxBitErrorsLate` score the same capture
    * framed one UI either side. Scoring all three lets a sweep tell "this
    * lane's data is wrong" from "the whole capture was framed a UI late", which
    * is recoverable.
    */
  def trainValidLane(numLanes: Int): Int = PhyTest.validLane(numLanes)

  /** Lanes a training run scores: the data lanes, then valid. Track and the
    * forwarded-clock lanes keep their `setup_ucie` settings. The mainband reads
    * nothing on track, and moving a clock lane's codes removes the sampling
    * clock from every lane at once.
    */
  def trainScoreLanes(numLanes: Int): Int = trainDataLanes(numLanes) + 1

  /** Where each field sits in a rate table entry.
    *
    * Chisel packs a bundle's first field into the most significant bits, so
    * these follow `ClkRateCfgIO`'s declaration order: UCIe main clock select,
    * TX division, then the PLL8, PLL12, and X2 multiplier enables in the three
    * low bits.
    */
  val rateCfgTxClkDivLsb: Int = 3
  val rateCfgMainClkSelLsb: Int =
    rateCfgTxClkDivLsb + ClockingTile.txClkDivWidth

  /** Fine steps within one coarse phase position, and taps between them.
    *
    * A coarse position is half a main clock period (62.5 ps at 8 GHz). The
    * global delay line covers it in 64 taps of 1 ps. Four steps of 16 taps give
    * 16 ps resolution: coarse against a UI, but enough to see an eye open and
    * close, and cheap enough to repeat at every rate.
    */
  val trainEyeFinePoints: Int = 4
  val trainEyeFineStep: Int = 16

  /** TX division codes an eye sweep visits: /1, /2 and /4, every ratio the TX
    * divider has.
    *
    * Off an 8 GHz main clock those are 16, 8 and 4 GT/s.
    */
  val trainEyeDivs: Int = 3

  /** Points in the coarse sweep, and taps between them, on the global delay
    * line.
    *
    * The global line delays the quadrature clock. The forwarded clock lanes
    * send that clock and the far side samples with it, so the line moves the
    * sampling point of every lane at once. Its 64 taps span more than a UI at
    * 16 GT/s, so a full sweep sees a whole eye. A 4-tap step gives 16 points a
    * few ps apart, fine against an eye tens of ps wide.
    */
  val trainGlobalCodes: Int = 16
  val trainGlobalStep: Int = 4

  /** Points in the per-lane trim sweep, and taps between them, on a lane's own
    * delay line.
    *
    * Each line is 32 taps of `DCDL_DELAY_STEP`, a fraction of a UI in total.
    * That covers the lane-to-lane spread from the clock tree, but by design
    * cannot move a lane across a UI; the global line does that. A 2-tap step
    * covers the range in 16 points.
    */
  val trainLocalCodes: Int = 16
  val trainLocalStep: Int = 2

  /** Reference codes to sweep: `trainVrefCodes` steps of `trainVrefStep` up the
    * ladder. `rxctl_<lane>_vrefSel` is 7 bits against an 8 bit ladder, so
    * software reaches only the bottom half of the supply. That is where the
    * receiver's swing is, because the driver and far termination divide it.
    */
  val trainVrefCodes: Int = 16
  val trainVrefStep: Int = 8

  /** Packets per point of a training sweep. Long enough that a sampling point
    * that has slipped a UI shows up as errors on about half the bits, short
    * enough that a sweep is not the whole simulation.
    */
  val trainPackets: Int = 16

  /** How far below the nominal count a re-framed error count must fall for the
    * capture to count as a slipped UI rather than broken data.
    *
    * A slipped lane usually samples near the edge it slipped across, so it also
    * has truly corrupted bits and the right framing does not reach zero. One
    * tap either side of the boundary, it measured about a tenth of the nominal
    * count, against about half for a wrong framing. Four separates the two with
    * margin.
    */
  val trainSlipRatio: Int = 4

  /** Most reads of the packet count by `run_lfsr`. It returns once every packet
    * is in, or once the count has stopped for `packetStallReads` reads after it
    * started to climb. So this bound only ends a run that receives nothing, as
    * at every code outside the eye. Same as the testbenches'
    * `wait_for_packets`.
    */
  val trainPollTries: Int = 1024

  /** Reads the packet count may stay unchanged, after packets start arriving,
    * before `run_lfsr` decides the receiver has stopped. Unlike a fixed total
    * of reads, this works at every rate: a burst at /4 takes four times as long
    * as at /1.
    */
  val packetStallReads: Int = 32

  /** Reads `setup_ucie_digital` waits for the link to come up. */
  val linkUpPollTries: Int = 1 << 24

  /** Reads of `clkCfgBusy` an apply is given to finish. Has to cover the ungate
    * wait, which is a PLL wake-up rather than a handful of cycles.
    */
  val applyPollTries: Int = 1 << 16

  /** Words the RX must have handled after a datapath reset before transmission
    * starts.
    *
    * A fixed delay does not fit. `rxDatapathRst` is released through a
    * synchronizer on the RX divided clock, so the release takes a fixed number
    * of *those* cycles, longer at each division. Counting the words the RX has
    * handled works at every rate and waits no longer than needed.
    */
  val rxSettleWords: Int = 4

  /** Packets `align_rx_words` runs to measure the RX word boundary. The RX
    * frames on the first, so a few is plenty.
    */
  val alignRxPackets: Int = 4

  /** Forwarded clock postamble that `setup_ucie_digital` sets for the
    * controller, which shares PhyTest's gating registers. Two packets deliver a
    * burst's last word at any RX word alignment, untrained. One packet does so
    * only at some alignments (`ucie/docs/phytest_bringup.md`, section 8).
    */
  val controllerClkPostambleUi: Int = 2 * Phy.SerdesRatio

  /** Polls allowed while waiting for `rxSettleWords`. Generous: a division the
    * part does not otherwise exercise should still clear it.
    */
  val rxSettleTries: Int = 4096

  // Bits of the DVSEC LinkControl word (UcieLinkDvsecRegs.scala) to set for
  // digital-controller bring-up: raw_format_enable (bit 0, the only flit
  // format this stack implements) and start_link_training (bit 10,
  // auto-clearing). LinkControl also holds target_link_width/
  // target_link_speed, which reset to this chip's max, so setup_ucie_digital
  // ORs this mask in with a read-modify-write.
  val linkControlBringupMask: BigInt = (BigInt(1) << 0) | (BigInt(1) << 10)

  // Bit index of link_status (link-up) within the packed DVSEC LinkStatus
  // word (UcieLinkDvsecRegs.scala), counting from the start of that word's
  // first field (raw_format_enabled, bit 0): raw_format_enabled(1) +
  // multi_protocol_enabled(1) + enhanced_multi_proto_enabled(1) +
  // x32_adv_pkg_enabled(1) + rsvd(3) + link_width_enabled(4) +
  // link_speed_enabled(4) = 15.
  val linkStatusLinkUpBit: Int = 15

  val ucieParams: UcieTLParams = UcieTLParams()

  /** Elaborates UcieTL with `params` and returns its register map.
    *
    * The layout depends on the parameters (for example, the per-lane register
    * arrays are sized from `numLanes`). To generate collateral for a specific
    * chip, pass that chip's parameters, not the defaults below.
    */
  def regmapFor(params: UcieTLParams): Seq[(Int, Seq[RegField])] = {
    implicit val p = Parameters.empty
    val ucie_dut = new RTLHarness(
      new UcieTL(params, Seq(AddressSet(0x0, 0xffffL)), 32, 32)
    )
    val ucie = (new chisel3.stage.phases.Elaborate)
      .transform(Seq(chisel3.stage.ChiselGeneratorAnnotation { () =>
        LazyModule(ucie_dut).module
      }))
      .collectFirst { case a: DesignAnnotation[ucie_dut.Impl] => a.design }
      .get
    ucie.regmap
  }

  // Elaborate UcieTL once; share the regmap between formatRegs and regAddrMap.
  lazy val ucieRegmap: Seq[(Int, Seq[RegField])] = regmapFor(ucieParams)

  lazy val regAddrMap: Map[String, BigInt] =
    ucieRegmap.flatMap { case (offset, fields) =>
      fields.flatMap(
        _.desc.map(d => (d.name, ucieParams.address + BigInt(offset)))
      )
    }.toMap

  /** MMIO setup for a TL loopback test: brings up the PHY, then puts the named
    * band into `tl` mode. The controller stays on PhyTest, which keeps the
    * other band under test control.
    */
  def tlRegReqs(
      mainbandMode: BigInt,
      sidebandMode: BigInt
  ): Seq[TLRequestDescriptor] = {
    def write(name: String, value: BigInt): TLRequestDescriptor =
      TLRequestDescriptor(regAddrMap(name), isWrite = true, data = value)

    val reqs = scala.collection.mutable.Buffer[TLRequestDescriptor]()

    // The block is held in the UCIe reset out of chip reset; the clocking
    // defaults are what a testbench drives, so it can come straight out.
    reqs += write("ucieRst", 0)

    for (lane <- 0 until ucieParams.numLanes + 4) {
      reqs += write(s"txctl_${lane}_tile", enableTxCtl)
      reqs += write(s"rxctl_${lane}_zen", 1)
      reqs += write(s"rxctl_${lane}_zctl", 0)
    }

    reqs += write("txClkP", defaultClkP)
    reqs += write("txClkN", defaultClkN)
    reqs += write("txValid", defaultValid)
    reqs += write("rxValid", defaultValid)

    for (i <- 0 until PhyTest.NumDebugDrivers) {
      reqs += write(s"debugDriverctl_$i", enableDriverCtl)
    }

    reqs += write("debugTxctlTile", enableTxCtl)

    // The UCIe main clock and RX forwarded clock are off out of reset. The
    // dividers left the UCIe reset (released above) with no clock, so they are
    // already in phase and only need the clocks turned on.
    reqs += write("clkEn", 1)
    reqs += write("rxClkEn", 1)

    // The selects, then the datapath reset a change of select needs.
    reqs += write("controllerSel", ControllerSel.phytest.litValue)
    reqs += write("mainbandMode", mainbandMode)
    reqs += write("sidebandMode", sidebandMode)

    reqs += write("txDatapathRst", 1)
    reqs += write("rxDatapathRst", 1)
    reqs += write("debugTxFsmRst", 1)

    reqs.toSeq
  }

  lazy val tlSimpleRegReqs: Seq[TLRequestDescriptor] =
    tlRegReqs(BandMode.tl.litValue, BandMode.manual.litValue)

  lazy val tlSidebandRegReqs: Seq[TLRequestDescriptor] =
    tlRegReqs(BandMode.manual.litValue, BandMode.tl.litValue)

  lazy val tlSimpleMbReqs: Seq[TLRequestDescriptor] = Seq(
    TLRequestDescriptor(0, isWrite = true, data = BigInt(0xdeadbeefL)),
    TLRequestDescriptor(0, isWrite = false, data = BigInt(0xdeadbeefL))
  )

  lazy val tlLongMbReqs: Seq[TLRequestDescriptor] = {
    val pattern: BigInt = BigInt(0x0100010001000100L)
    val writes = (0 until 32).map { i =>
      TLRequestDescriptor(
        BigInt(i) * 8,
        isWrite = true,
        data = BigInt(i) * pattern
      )
    }
    val reads = (0 until 32).map { i =>
      TLRequestDescriptor(
        BigInt(i) * 8,
        isWrite = false,
        data = BigInt(i) * pattern
      )
    }
    writes ++ reads
  }
}

/** Emits C source for the same UCIe MMIO programming sequences as
  * `SystemVerilogFormatter`. Conventions:
  *   - `getConstantName` prepends `UCIE_` so all `#define`s share a namespace.
  *   - Every function takes an implicit `uintptr_t base` first parameter.
  *     Register writes expand to `reg_write64(base + ofs, v)`, so the same
  *     `Codegen.format*Fn` bodies work unchanged.
  *   - Asserts become plain `assert(...)`; the optional message is dropped
  *     (revisit if richer reporting is wanted).
  */
class CFormatter extends Formatter {
  def getConstantName(name: String): String = {
    val snake = name
      .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
      .toUpperCase
    // A name that already starts with the prefix (`ucieRst`) keeps one copy.
    if (snake.startsWith("UCIE_")) snake else "UCIE_" + snake
  }

  private def renderArg(arg: Arg): String = {
    val ty = arg.datatype match {
      case Datatype.Long => "uint64_t"
    }
    s"$ty ${arg.name}"
  }

  def formatFn(
      name: String,
      body: String,
      args: Seq[Arg] = Seq.empty
  ): String = {
    val argStr = ("uintptr_t base" +: args.map(renderArg)).mkString(", ")
    s"""static inline void $name($argStr) {
${Codegen.indent(body)}
}
"""
  }

  def formatFnCall(name: String, args: Seq[String] = Seq.empty): String = {
    val argStr = ("base" +: args).mkString(", ")
    s"$name($argStr);\n"
  }

  def formatForLoop(loopVar: String, length: Int, body: String): String =
    s"""for (int $loopVar = 0; $loopVar < $length; $loopVar++) {
${Codegen.indent(body)}
}
"""

  def formatWhileLoop(condition: String, body: String): String =
    s"""while ($condition) {
${Codegen.indent(body)}
}
"""

  def formatIfStmt(condition: String, body: String): String =
    s"""if ($condition) {
${Codegen.indent(body)}
}
"""

  def formatBlock(body: String): String =
    s"""{
${Codegen.indent(body)}
}
"""

  def formatPrintStmt(msg: String): String =
    s"""printf("${Codegen.escapeString(msg)}\\n");\n"""

  def formatDeclare(name: String): String = s"uint64_t $name;\n"

  def formatAssign(name: String, value: String): String = s"$name = $value;\n"

  def breakStmt(): String = "break;\n"

  def formatWaitCycles(n: Int): String =
    s"// (wait $n cycles — no-op in C)\n"

  def formatBool(bool: Boolean): String = if (bool) "1" else "0"

  def formatConstantRef(name: String): String = getConstantName(name)

  def formatWrite(drv: String, addr: String, value: String): String =
    s"reg_write64(base + $addr, $value);\n"

  def formatWriteReg(drv: String, addr: String, value: String): String =
    s"reg_write64(base + $addr, $value);\n"

  def formatRead(
      drv: String,
      outputName: String,
      addr: String,
      declareVar: Boolean = true
  ): String = {
    val sb = new StringBuilder
    if (declareVar) {
      sb.append(s"uint64_t $outputName;\n")
    }
    sb.append(s"$outputName = reg_read64(base + $addr);\n")
    sb.toString
  }

  def formatReadReg(
      drv: String,
      outputName: String,
      addr: String,
      declareVar: Boolean = true
  ): String = formatRead(drv, outputName, addr, declareVar)

  def formatAssertEq(
      drv: String,
      addr: String,
      value: String,
      msg: Option[String] = None
  ): String =
    s"assert(reg_read64(base + $addr) == ($value));\n"

  def formatUcieAssertEq(
      drv: String,
      addr: String,
      value: String,
      msg: Option[String] = None
  ): String = formatAssertEq(drv, addr, value, msg)

  def formatLong(value: Long): String = f"0x$value%xULL"

  def formatDefine(name: String, value: String): String =
    s"#define ${getConstantName(name)} $value\n"
}

class Codegen(f: Formatter, params: UcieTLParams = Codegen.ucieParams) {
  // Elaborated once per Codegen, from this instance's parameters.
  private lazy val ucieRegmap: Seq[(Int, Seq[RegField])] =
    Codegen.regmapFor(params)

  def formatWriteNamedReg(
      addrConst: String,
      value: String
  ): String = {
    f.formatWriteReg("regDrv", f.formatConstantRef(addrConst), value)
  }
  def formatRegs(): String = {
    val sb = new StringBuilder

    // Maps the variable name to the first encountered index string.
    val varToIdx0 = mutable.Map[Seq[String], String]()
    // Maps the variable name to the address of its first entry.
    val varMapIdx0 = mutable.Map[Seq[String], Int]()
    // Maps the variable name to the address of its second entry.
    val varMapIdx1 = mutable.Map[Seq[String], Int]()

    def isNumber(s: String): Boolean = s.forall(_.isDigit)
    for (case (addr, reg) <- ucieRegmap) {
      val name = reg(0).desc.get.name
      val nameInd = name.split('_').map(_.capitalize)

      // Coalesces indices and names (end result is a Seq of alternating name, idx)
      val nameIndCoalesced = nameInd.foldLeft(Seq.empty[String]) {
        (acc, elem) =>
          acc match {
            case init :+ last =>
              if (isNumber(elem) && isNumber(last))
                init :+ (last + elem) // merge numbers
              else if (!isNumber(elem) && !isNumber(last))
                init :+ (last + elem) // merge non-numbers
              else acc :+ elem // start new group
            case _ => acc :+ elem
          }
      }

      // Should always start with a name
      require(!isNumber(nameIndCoalesced(0)))

      var i = 0;
      val varName = mutable.Buffer[String]()
      while (i < nameIndCoalesced.length) {
        varName += nameIndCoalesced(i)
        val idx =
          if (i + 1 < nameIndCoalesced.length) {
            nameIndCoalesced(i + 1)
          } else {
            "0"
          }
          val varNameSeq = varName.toSeq
        val idx0 = varToIdx0.getOrElseUpdate(varNameSeq, idx)
        if (idx == idx0) {
          if (!varMapIdx0.contains(varNameSeq)) {
            varMapIdx0(varNameSeq) = addr
            if (varNameSeq.length == 1) {
              sb.append(f.formatDefine(varNameSeq.mkString, f.formatLong(addr)))
            } else {
              sb.append(
                f.formatDefine(
                  s"${varNameSeq.mkString}Ofs",
                  f.formatLong(addr - varMapIdx0(varNameSeq.init))
                )
              )
            }
          }
        } else {
          if (!varMapIdx1.contains(varNameSeq)) {
            varMapIdx1(varNameSeq) = addr
            sb.append(
              f.formatDefine(
                s"${varNameSeq.mkString}Width",
                f.formatLong(addr - varMapIdx0(varNameSeq))
              )
            )
          }
        }
        i += 2
      }
    }
    sb.toString
  }

  def formatConstants(): String = {
    val sb = new StringBuilder
    for (
      case (name, value) <- Seq(
        ("txTestStateIdle", TxTestState.idle.litValue),
        ("txTestStateRun", TxTestState.run.litValue),
        ("txTestStateDone", TxTestState.done.litValue),
        ("txTestModeManual", TxTestMode.manual.litValue),
        ("txTestModeLfsr", TxTestMode.lfsr.litValue),
        ("dataModeFinite", DataMode.finite.litValue),
        ("dataModeInfinite", DataMode.infinite.litValue),
        ("testTargetMainband", TestTarget.mainband.litValue),
        ("testTargetLoopback", TestTarget.loopback.litValue),
        ("controllerSelPhytest", ControllerSel.phytest.litValue),
        ("controllerSelUcie", ControllerSel.ucie.litValue),
        ("bandModeManual", BandMode.manual.litValue),
        ("bandModeTl", BandMode.tl.litValue),
        ("rxStartModeValid", RxStartMode.valid.litValue),
        ("rxStartModeImmediate", RxStartMode.immediate.litValue),
        ("captureModeFirst", CaptureMode.first.litValue),
        ("captureModeError", CaptureMode.error.litValue),
        ("defaultClkP", Codegen.defaultClkP),
        ("defaultClkN", Codegen.defaultClkN),
        ("defaultValid", Codegen.defaultValid),
        ("defaultTrack", Codegen.defaultTrack),
        ("enableDriverCtl", Codegen.enableDriverCtl),
        ("enableTxCtl", Codegen.enableTxCtl),
        ("disableDriverCtl", PadDriverCtlIO.off.litValue),
        ("disableTxCtl", TxLaneCtlIO.off.litValue),
        ("txCtlDelayMask", Codegen.txCtlDelayMask),
        ("txCtlDelayLsb", Codegen.txCtlDelayLsb),
        ("txCtlEnpLsb", Codegen.txCtlEnpLsb),
        ("txCtlEnnLsb", Codegen.txCtlEnnLsb),
        ("txCtlEnpEqLsb", Codegen.txCtlEnpEqLsb),
        ("txCtlEnnEqLsb", Codegen.txCtlEnnEqLsb),
        ("trainLfsrSeed", Codegen.trainLfsrSeed),
        // Lane `i`'s seed out of reset, and from `seed_lfsrs`, is
        // `trainLfsrSeed + i * lfsrSeedLaneStep`.
        ("lfsrSeedLaneStep", PhyTest.LfsrSeedLaneStep),
        ("trainDataLanes", BigInt(Codegen.trainDataLanes(params.numLanes))),
        ("trainValidLane", BigInt(Codegen.trainValidLane(params.numLanes))),
        ("trainScoreLanes", BigInt(Codegen.trainScoreLanes(params.numLanes))),
        // The layout of every per-lane PhyTest vector (seeds, error counts,
        // `txInjectLane`, `rxDataLane`): the data lanes, then valid, track
        // and the loopback lane.
        ("numTestLanes", BigInt(PhyTest.numTestLanes(params.numLanes))),
        ("validLane", BigInt(PhyTest.validLane(params.numLanes))),
        ("trackLane", BigInt(PhyTest.trackLane(params.numLanes))),
        ("loopbackLane", BigInt(PhyTest.loopbackLane(params.numLanes))),
        // `txValidLaneSel`/`rxValidLaneSel` codes for valid where it belongs
        // and for valid moved onto track.
        (
          "defaultValidLaneSel",
          BigInt(Phy.defaultValidLaneSel(params.numLanes))
        ),
        ("trackValidLaneSel", BigInt(Phy.trackValidLaneSel(params.numLanes))),
        // Packets per lane in the TX pattern SRAM and the RX capture SRAM.
        ("sramPackets", BigInt(1) << (params.bufferDepthPerLane - 5)),
        ("clkPostambleUi", BigInt(PhyTest.ClkPostambleUi)),
        // Chip clock cycles each `clkMeter*` register counts over.
        ("clkMeterWindowCycles", BigInt(UcieClkRegs.meterWindowCycles)),
        ("rateCfgMainClkSelLsb", BigInt(Codegen.rateCfgMainClkSelLsb)),
        ("rateCfgTxClkDivLsb", BigInt(Codegen.rateCfgTxClkDivLsb)),
        ("trainEyeFinePoints", BigInt(Codegen.trainEyeFinePoints)),
        ("trainEyeFineStep", BigInt(Codegen.trainEyeFineStep)),
        ("trainEyeDivs", BigInt(Codegen.trainEyeDivs)),
        ("trainGlobalCodes", BigInt(Codegen.trainGlobalCodes)),
        ("trainGlobalStep", BigInt(Codegen.trainGlobalStep)),
        ("trainLocalCodes", BigInt(Codegen.trainLocalCodes)),
        ("trainLocalStep", BigInt(Codegen.trainLocalStep)),
        ("trainVrefCodes", BigInt(Codegen.trainVrefCodes)),
        ("trainVrefStep", BigInt(Codegen.trainVrefStep)),
        ("trainPackets", BigInt(Codegen.trainPackets)),
        ("trainSlipRatio", BigInt(Codegen.trainSlipRatio)),
        ("clkUngateSrcPllLock", ClkUngateSrc.pllLock.litValue),
        ("clkUngateSrcMmio", ClkUngateSrc.mmio.litValue),
        ("clkUngateSrcDelay", ClkUngateSrc.delay.litValue)
      ) ++ PhyTest.DebugMuxInputs.zipWithIndex.map { case (input, i) =>
        // `debugMuxSel` codes, one per observation mux input.
        (s"debugMux${input.capitalize}", BigInt(i))
      }
    ) {
      sb.append(
        f.formatDefine(
          name,
          f.formatLong(value.toLong)
        )
      )
    }
    sb.toString
  }

  /** Flushes the serdes handoff and the mainband's async queues, both ends.
    *
    * Required after every change to `controllerSel`, `mainbandMode` or
    * `sidebandMode`. The queues' PHY ends do not synchronize the selects: they
    * are always drained and fed, and the TL path's ends are gated by a select
    * from another clock. This reset makes the newly selected controller start
    * from empty queues with consistent pointers.
    */
  def formatResetDatapathFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("txDatapathRst", f.formatLong(1)))
    body.append(formatWriteNamedReg("rxDatapathRst", f.formatLong(1)))
    f.formatFn("reset_datapath", body.toString)
  }

  def formatResetFsmsFn(): String = {
    val body = new StringBuilder
    // The TX FSM first, so it stops queuing words before the datapath reset
    // empties the queue and clears the underflow flag. The RX FSM last, so it
    // starts on words that arrived after the datapath reset.
    body.append(
      formatWriteNamedReg("txFsmRst", f.formatLong(1))
    )
    body.append(f.formatFnCall("reset_datapath"))
    body.append(
      formatWriteNamedReg("rxFsmRst", f.formatLong(1))
    )
    body.append(
      formatWriteNamedReg("debugTxFsmRst", f.formatLong(1))
    )
    body.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("txTestState"),
        f.formatConstantRef("txTestStateIdle"),
        msg = Some("TX test state is not idle after reset")
      )
    )
    body.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("txPacketsSent"),
        f.formatLong(0),
        msg = Some("TX packets sent is not 0 after reset")
      )
    )
    body.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("rxPacketsReceived"),
        f.formatLong(0),
        msg = Some("RX packets received is not 0 after reset")
      )
    )
    f.formatFn("reset_fsms", body.toString)
  }

  def formatWriteTxctlFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(
      f.formatWriteReg(
        "regDrv",
        s"${f.formatConstantRef("txctl")} + lane * ${f.formatConstantRef("txctlWidth")} + ofs",
        "v"
      )
    )
    sb.append(
      f.formatFn(
        "write_txctl",
        body.toString,
        args = Seq(
          Arg("lane", Datatype.Long),
          Arg("ofs", Datatype.Long),
          Arg("v", Datatype.Long)
        )
      )
    )
    sb.toString
  }

  def formatWriteRxctlFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(
      f.formatWriteReg(
        "regDrv",
        s"${f.formatConstantRef("rxctl")} + lane * ${f.formatConstantRef("rxctlWidth")} + ofs",
        "v"
      )
    )
    sb.append(
      f.formatFn(
        "write_rxctl",
        body.toString,
        args = Seq(
          Arg("lane", Datatype.Long),
          Arg("ofs", Datatype.Long),
          Arg("v", Datatype.Long)
        )
      )
    )
    sb.toString
  }

  /** Sets `taps` of a TX lane's delay line, which sets the lane's sampling
    * point. Leaves its driver and equalizer settings as they were. The lane
    * must already be enabled (`setup_ucie` enables every lane).
    *
    * The delay line is on the tile's high speed clock, so it moves when the
    * lane drives each bit. With the forwarded clock fixed, that moves where in
    * the UI the far receiver samples. `Dctrl` is thermometer coded.
    *
    * Only with the clock gated: see `set_clk_en`.
    */
  def formatSetTxDelayFn(): String = {
    val body = new StringBuilder
    val tileAddr = s"${f.formatConstantRef("txctl")} + lane * " +
      s"${f.formatConstantRef("txctlWidth")} + ${f.formatConstantRef("txctlTileOfs")}"
    val tile = new StringBuilder
    tile.append(f.formatReadReg("regDrv", "tile", tileAddr, declareVar = true))
    val mask = f.formatConstantRef("txCtlDelayMask")
    // `taps` of 64 or more is every tap; a 64-bit shift by 64 is undefined
    // in C.
    val delayCode =
      s"((tile & ~$mask) | ((((taps >= 64) ? ~${f.formatLong(0)} : " +
        s"((${f.formatLong(1)} << taps) - ${f.formatLong(1)})) << " +
        s"${f.formatConstantRef("txCtlDelayLsb")}) & $mask))"
    tile.append(
      f.formatFnCall(
        "write_txctl",
        args = Seq("lane", f.formatConstantRef("txctlTileOfs"), delayCode)
      )
    )
    body.append(f.formatBlock(tile.toString))
    f.formatFn(
      "set_tx_delay",
      body.toString,
      args = Seq(Arg("lane", Datatype.Long), Arg("taps", Datatype.Long))
    )
  }

  /** Stops or starts the UCIe main clock, and with it the TX data and TX
    * quadrature clocks of the TX lanes. Off out of reset, so every sequence
    * that uses the lanes turns it on.
    *
    * Change a delay code only with this off. The tile's divider counts the
    * edges leaving the line, and shortening the line by several taps at once
    * loses an edge in flight. The divider is then a count behind, so its word
    * boundary is a UI off the valid lane it is framed against. Only a divider
    * reset recovers this.
    */
  def formatSetClkEnFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("clkEn", "en"))
    f.formatFn(
      "set_clk_en",
      body.toString,
      args = Seq(Arg("en", Datatype.Long))
    )
  }

  /** Restarts every clock divider, global and per tile, in both directions.
    *
    * `txDividerRst` and `rxDividerRst` are separate registers so each direction
    * can be retimed alone; this resets both.
    *
    * The reset is a level, not a strobe, because it is meant to be held while
    * both clocks are off: `clkEn` low for TX and `rxClkEn` low for RX. (The RX
    * dividers run on the RX forwarded clock, which this die's TX gate stops
    * only in loopback.) The tile dividers take the reset with no clock, then
    * all restart on the first edge after the clocks return. That puts them in a
    * known phase with each other.
    */
  def formatResetDividersFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("txDividerRst", f.formatLong(1)))
    body.append(formatWriteNamedReg("rxDividerRst", f.formatLong(1)))
    body.append(formatWriteNamedReg("txDividerRst", f.formatLong(0)))
    body.append(formatWriteNamedReg("rxDividerRst", f.formatLong(0)))
    f.formatFn("reset_dividers", body.toString)
  }

  /** Fills one entry of the rate translation table.
    *
    * The table maps the rate link training negotiates to the analog controls
    * for that rate. Software fills it because a part's clocking is a bring-up
    * choice (which PLL serves which rate, or a bypass pin instead), not fixed
    * in the RTL. An entry left at zero selects PLL8 with no PLL enabled: a rate
    * the part has not been told how to reach.
    *
    * The PHY test and TX sideband clocks are not in the table; they keep the
    * rate their own registers set, whatever training picks. The PLL enables
    * apply to the whole part, but an entry cannot turn off a PLL that either of
    * those clocks divides: the clock register block keeps it running.
    *
    * Fields are packed as the bundle declares them, most significant first:
    * UCIe main clock select, TX division, then the PLL8, PLL12, and X2
    * multiplier enables.
    */
  def formatSetRateCfgFn(): String = {
    val body = new StringBuilder
    val packed =
      s"((main_sel << ${f.formatConstantRef("rateCfgMainClkSelLsb")}) | " +
        s"(tx_div << ${f.formatConstantRef("rateCfgTxClkDivLsb")}) | " +
        s"pll_en)"
    body.append(
      f.formatWriteReg(
        "regDrv",
        s"${f.formatConstantRef("rateCfg")} + rate * ${f.formatConstantRef("rateCfgWidth")}",
        packed
      )
    )
    f.formatFn(
      "set_rate_cfg",
      body.toString,
      args = Seq(
        Arg("rate", Datatype.Long),
        Arg("main_sel", Datatype.Long),
        Arg("tx_div", Datatype.Long),
        Arg("pll_en", Datatype.Long)
      )
    )
  }

  /** Gives the analog clocking to the rate table, or takes it back.
    *
    * Low: the direct registers control the clocking, as when a testbench drives
    * it by hand. High: the table entry for the rate training settled on
    * controls it. The clock register block applies the entry by itself each
    * time that rate changes, and for the current rate as soon as this is set.
    * Fill the table first.
    */
  def formatSetFreqSelAutoFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("freqSelAutoEn", "en"))
    f.formatFn(
      "set_freq_sel_auto",
      body.toString,
      args = Seq(Arg("en", Datatype.Long))
    )
  }

  /** Puts the clocking in manual mode, or hands it back to the FSM.
    *
    * In manual mode each clock control is its own register and takes effect
    * when written. There is no apply, no rate table, no PLL kept on for a
    * divider, and no UCIe reset on the dividers. The UCIe controller's clock
    * handshake comes from `manualClksReady`, `manualClksReadyFreqSel` and
    * `manualClksUngated`. Software sequences each change: gate the clock,
    * switch, wait for PLL lock, ungate. Entering or leaving manual mode changes
    * no clock: the registers take what is already driven, and the FSM resumes
    * from what software left.
    */
  def formatSetClkManualFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("clkManualEn", "en"))
    f.formatFn(
      "set_clk_manual",
      body.toString,
      args = Seq(Arg("en", Datatype.Long))
    )
  }

  /** Sends the written configuration to the clocking tile.
    *
    * The registers are a shadow copy; nothing reaches the tile until this runs.
    * The sequencer closes the clock gate, switches, waits for the new source to
    * wake up, and opens the gate. The caller does not gate or wait by hand.
    *
    * Polls `clkCfgBusy` instead of counting, because the wait lasts as long as
    * the selected PLL takes to lock.
    */
  def formatApplyClkCfgFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("clkCfgApply", f.formatLong(1)))
    body.append(
      formatWaitClkCfg(
        "apply_clk_cfg: the apply never finished; a PLL it waits on may " +
          "not be locking (see clkMeter* and pllLockObserved)"
      )
    )
    f.formatFn("apply_clk_cfg", body.toString)
  }

  /** Polls `clkCfgBusy` until the apply under way finishes, printing `stuck` if
    * it never does.
    */
  private def formatWaitClkCfg(stuck: String): String = {
    val wait = new StringBuilder
    wait.append(f.formatDeclare("busy"))
    wait.append(f.formatAssign("busy", f.formatLong(1)))
    val pollBody = new StringBuilder
    pollBody.append(
      f.formatReadReg(
        "regDrv",
        "busy",
        f.formatConstantRef("clkCfgBusy"),
        declareVar = false
      )
    )
    pollBody.append(f.formatIfStmt("busy == 0", f.breakStmt()))
    wait.append(f.formatForLoop("w", Codegen.applyPollTries, pollBody.toString))
    wait.append(f.formatIfStmt("busy != 0", f.formatPrintStmt(stuck)))
    f.formatBlock(wait.toString)
  }

  /** Picks what opens the clock gate at the end of an apply.
    *
    *   - `clkUngateSrcPllLock`: when the PLL `mainClkSel` names reports lock.
    *     The X2 multiplier has no lock of its own and uses PLL8's. The analog
    *     bypass pin has no lock, so the gate opens at once.
    *   - `clkUngateSrcDelay`: after `delay` chip clock cycles, for a part whose
    *     lock is untrustworthy or not wired.
    *   - `clkUngateSrcMmio`: when `release_clk_gate` is called.
    *
    * Only `clkUngateSrcDelay` uses `delay`.
    */
  def formatSetUngateSrcFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("clkCfgUngateSrc", "src"))
    body.append(formatWriteNamedReg("clkCfgUngateDelay", "delay"))
    f.formatFn(
      "set_ungate_src",
      body.toString,
      args = Seq(Arg("src", Datatype.Long), Arg("delay", Datatype.Long))
    )
  }

  /** Resets everything in the UCIe block except the clocking registers.
    *
    * The clocking registers run on the chip clock and reset, so they survive
    * this and can be set up first. The UCIe reset is asserted out of chip
    * reset, so the rest of the block stays in reset until the clocking is set
    * and this releases it.
    *
    * It also resets everything in `UcieTLRegs`, so call it before configuring
    * that block, not after.
    */
  def formatResetUcieFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("ucieRst", f.formatLong(1)))
    body.append(formatWriteNamedReg("ucieRst", f.formatLong(0)))
    f.formatFn("reset_ucie", body.toString)
  }

  /** Opens the clock gate by hand, ending an apply that is still waiting.
    *
    * Meant for `clkUngateSrcMmio`, but works with any source. It recovers from
    * a PLL that will not lock or a delay set too long, so the sequencer needs
    * no hidden timeout.
    */
  def formatReleaseClkGateFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("clkCfgUngateReq", f.formatLong(1)))
    f.formatFn("release_clk_gate", body.toString)
  }

  /** Points the UCIe main clock at a source and sets the TX division.
    *
    * The lane rate follows the division, so a sweep uses this to step through
    * the part's rates. The apply gates the clock during the change, because the
    * divider and phase shifter both move and a lane divider may miscount an
    * edge in flight. The gate stays shut until the new source settles. `clkEn`
    * is left as it is: the clock runs after this only if it was on.
    *
    * Turns on the PLL the source needs (PLL8 for PLL8 and its X2 clock, PLL12
    * for PLL12) in the same apply, which then waits for its lock. No other PLL
    * is touched.
    */
  def formatSetMainClkFn(): String = {
    val body = new StringBuilder
    body.append(
      f.formatIfStmt(
        "src == 0 || src == 2",
        formatWriteNamedReg("pll8En", f.formatLong(1))
      )
    )
    body.append(
      f.formatIfStmt(
        "src == 1",
        formatWriteNamedReg("pll12En", f.formatLong(1))
      )
    )
    body.append(formatWriteNamedReg("mainClkSel", "src"))
    body.append(formatWriteNamedReg("txClkDiv", "div"))
    body.append(f.formatFnCall("apply_clk_cfg"))
    f.formatFn(
      "set_main_clk",
      body.toString,
      args = Seq(Arg("src", Datatype.Long), Arg("div", Datatype.Long))
    )
  }

  /** Sets the TX quadrature clock's coarse phase, in UCIe main clock half
    * cycles.
    *
    * With the global delay line this reaches any phase. The coarse step is half
    * a main clock period, within the line's range at every PLL rate, so the two
    * cover a whole UI with no gap. Below 16 GT/s the line alone cannot, which
    * is why the shifter exists. Below about 7.2 GHz main clock a step is longer
    * than the line, so get a low rate from a larger division, not a slower main
    * clock.
    *
    * Then applies, which closes the TX clock gate, moves the phase, restarts
    * the TX dividers, and opens the gate. In manual mode the phase takes effect
    * at once, and the caller must gate the clock around it.
    */
  def formatSetTxPhaseFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("txClkPhase", "half_cycles"))
    body.append(f.formatFnCall("apply_clk_cfg"))
    f.formatFn(
      "set_tx_phase",
      body.toString,
      args = Seq(Arg("half_cycles", Datatype.Long))
    )
  }

  /** Points the PHY test clock at PLL8 (`phytest_src` 0), PLL12 (1) or the
    * analog bypass pin (2), divided, and turns on the PLL it divides. No other
    * PLL is touched, so a clock on another PLL needs that PLL enabled by its
    * own write.
    *
    * One apply sets the PLL, source, ratio and switch. It keeps the PHY test
    * clock gated until that PLL locks, so the PHY test domain sees one clean
    * stop and restart.
    */
  def formatUseInternalClkFn(): String = {
    val body = new StringBuilder
    body.append(
      f.formatIfStmt(
        "phytest_src == 0",
        formatWriteNamedReg("pll8En", f.formatLong(1))
      )
    )
    body.append(
      f.formatIfStmt(
        "phytest_src == 1",
        formatWriteNamedReg("pll12En", f.formatLong(1))
      )
    )
    body.append(formatWriteNamedReg("phyTestClkSrcSel", "phytest_src"))
    body.append(formatWriteNamedReg("phyTestClkDiv", "phytest_div"))
    body.append(formatWriteNamedReg("phyTestClkBypassEn", f.formatLong(0)))
    body.append(f.formatFnCall("apply_clk_cfg"))
    f.formatFn(
      "use_internal_clk",
      body.toString,
      args = Seq(
        Arg("phytest_src", Datatype.Long),
        Arg("phytest_div", Datatype.Long)
      )
    )
  }

  /** Stops or starts the recovered RX forwarded clock at the RX clock lanes.
    * Off out of reset, like `clkEn`, so every sequence that receives turns it
    * on.
    *
    * The gate is in the clock lanes, before the RX clock tree, so turning it
    * off stops the clock to every RX data lane. Everything on the RX divided
    * clock also stops, including the reset synchronizer and the PHY side of the
    * async queue. The receiver should be idle, not mid-burst, while it is off.
    */
  def formatSetRxClkEnFn(): String = {
    val body = new StringBuilder
    body.append(formatWriteNamedReg("rxClkEn", "en"))
    f.formatFn(
      "set_rx_clk_en",
      body.toString,
      args = Seq(Arg("en", Datatype.Long))
    )
  }

  /** Sets the global delay line, thermometer coded over `clkPhaseSel`.
    *
    * This is the coarse control. It delays the TX quadrature clock, which the
    * forwarded clock lanes send, so it moves where in the UI the far side
    * samples every lane. Each lane's own line only trims around it.
    *
    * Like `set_tx_phase`, it then applies under the TX clock gate.
    */
  def formatSetGlobalDelayFn(): String = {
    val body = new StringBuilder
    // `taps` = 64 is every tap; shifting a 64-bit one by 64 is undefined in
    // C and comes out as no taps on RV64.
    body.append(
      formatWriteNamedReg(
        "clkPhaseSel",
        s"((taps >= 64) ? ~${f.formatLong(0)} : " +
          s"((${f.formatLong(1)} << taps) - ${f.formatLong(1)}))"
      )
    )
    body.append(f.formatFnCall("apply_clk_cfg"))
    f.formatFn(
      "set_global_delay",
      body.toString,
      args = Seq(Arg("taps", Datatype.Long))
    )
  }

  /** Sets one RX lane's sampling point: `taps` of the delay line on its
    * received data enabled.
    *
    * The RX counterpart of `set_tx_delay`. The delay is on this lane's data,
    * behind its clock tree replica, so it moves this lane's sampling point in
    * the UI and no other lane's. More taps delay the data relative to the
    * clock, so the lane samples earlier in the UI. `Dctrl` is thermometer
    * coded. The clock lanes have no delay line and ignore it.
    */
  def formatSetRxDelayFn(): String = {
    val body = new StringBuilder
    body.append(
      f.formatFnCall(
        "write_rxctl",
        args = Seq(
          "lane",
          f.formatConstantRef("rxctlDctrlOfs"),
          s"((taps >= 64) ? ~${f.formatLong(0)} : " +
            s"((${f.formatLong(1)} << taps) - ${f.formatLong(1)}))"
        )
      )
    )
    f.formatFn(
      "set_rx_delay",
      body.toString,
      args = Seq(Arg("lane", Datatype.Long), Arg("taps", Datatype.Long))
    )
  }

  /** Sets one lane's slicing reference, as a code up the tile's ladder. */
  def formatSetRxVrefFn(): String = {
    val body = new StringBuilder
    body.append(
      f.formatFnCall(
        "write_rxctl",
        args = Seq("lane", f.formatConstantRef("rxctlVrefSelOfs"), "code")
      )
    )
    f.formatFn(
      "set_rx_vref",
      body.toString,
      args = Seq(Arg("lane", Datatype.Long), Arg("code", Datatype.Long))
    )
  }

  /** Puts every lane's TX and RX seed back to its reset value, a different one
    * per lane (`PhyTest.defaultLfsrSeed`). Both dies of a link then agree on
    * every lane, and no two lanes send the same stream. The receiver scores
    * what it gets against the pattern it expects and counts the bits that
    * differ.
    *
    * Every lane is seeded, not just the one under test: the receiver frames on
    * the valid waveform, so the other lanes must stay readable for it to keep
    * framing. The seed registers keep their value across `reset_fsms`, which
    * reseeds from them, so a sweep seeds once and reruns as often as needed.
    */
  def formatSeedLfsrsFn(): String = {
    val seedLoop = new StringBuilder
    val seed = s"(${f.formatConstantRef("trainLfsrSeed")} + i * " +
      s"${f.formatConstantRef("lfsrSeedLaneStep")})"
    seedLoop.append(
      f.formatWriteReg(
        "regDrv",
        s"${f.formatConstantRef("txLfsrSeed")} + i * ${f.formatConstantRef("txLfsrSeedWidth")}",
        seed
      )
    )
    seedLoop.append(
      f.formatWriteReg(
        "regDrv",
        s"${f.formatConstantRef("rxLfsrSeed")} + i * ${f.formatConstantRef("rxLfsrSeedWidth")}",
        seed
      )
    )
    f.formatFn(
      "seed_lfsrs",
      f.formatForLoop(
        "i",
        PhyTest.numTestLanes(params.numLanes),
        seedLoop.toString
      )
    )
  }

  /** Runs `packets` packets of the LFSR pattern across the mainband and waits
    * for the receiver to score them. Call `seed_lfsrs` first.
    *
    * The wait is bounded, not a spin on `rxPacketsReceived`. A training sweep
    * visits codes where the lane receives nothing, and a spin would hang on the
    * first one instead of scoring it.
    */
  def formatRunLfsrFn(): String = {
    val body = new StringBuilder
    // The target and modes before `reset_fsms`, whose datapath reset a target
    // change needs.
    body.append(
      formatWriteNamedReg(
        "testTarget",
        f.formatConstantRef("testTargetMainband")
      )
    )
    body.append(
      formatWriteNamedReg("txTestMode", f.formatConstantRef("txTestModeLfsr"))
    )
    body.append(
      formatWriteNamedReg("txDataMode", f.formatConstantRef("dataModeFinite"))
    )
    // The receiver also stops at `packets`. Otherwise it would keep scoring
    // the idle zeros sent after the TX finishes against its own LFSR, and
    // every sweep point would show the same few errors whatever its code.
    body.append(
      formatWriteNamedReg("rxDataMode", f.formatConstantRef("dataModeFinite"))
    )
    body.append(formatWriteNamedReg("rxPacketsToReceive", "packets"))
    body.append(formatWriteNamedReg("txPacketsToSend", "packets"))
    body.append(f.formatFnCall("reset_fsms"))
    // Wait for the RX datapath to leave reset before the TX starts.
    //
    // `rxDatapathRst` is released through a synchronizer on the RX divided
    // clock, so the release takes twice as long at /2 and four times at /4.
    // The register writes before `tx_execute` take a fixed time, so at divided
    // rates the burst can start before the RX hands over any words. The RX
    // then misses the idle-to-valid transition, aligns on a mid-pattern word,
    // and every lane reads errors at every phase. Waiting on idle words works
    // at every rate.
    val settle = new StringBuilder
    locally {
      settle.append(f.formatDeclare("settle_w"))
      settle.append(f.formatAssign("settle_w", f.formatLong(0)))
      val poll = new StringBuilder
      poll.append(
        f.formatReadReg(
          "regDrv",
          "settle_w",
          f.formatConstantRef("rxIdleWordsObserved"),
          declareVar = false
        )
      )
      poll.append(
        f.formatIfStmt(s"settle_w >= ${Codegen.rxSettleWords}", f.breakStmt())
      )
      settle.append(f.formatForLoop("s", Codegen.rxSettleTries, poll.toString))
      settle.append(
        f.formatIfStmt(
          s"settle_w < ${Codegen.rxSettleWords}",
          f.formatPrintStmt(
            "run_lfsr: the RX is handing over no words; no forwarded clock " +
              "is arriving (see clkMeterRxDiv)"
          )
        )
      )
    }
    body.append(f.formatBlock(settle.toString))
    body.append(formatWriteNamedReg("txExecute", f.formatLong(1)))

    // Wait while packets are still arriving rather than for a fixed number of
    // reads: see `Codegen.packetStallReads`.
    val wait = new StringBuilder
    locally {
      wait.append(f.formatDeclare("r"))
      wait.append(f.formatDeclare("prev"))
      wait.append(f.formatDeclare("stalls"))
      wait.append(f.formatAssign("r", f.formatLong(0)))
      wait.append(f.formatAssign("prev", s"~${f.formatLong(0)}"))
      wait.append(f.formatAssign("stalls", f.formatLong(0)))
      val pollBody = new StringBuilder
      pollBody.append(
        f.formatReadReg(
          "regDrv",
          "r",
          f.formatConstantRef("rxPacketsReceived"),
          declareVar = false
        )
      )
      pollBody.append(f.formatIfStmt("r >= packets", f.breakStmt()))
      pollBody.append(
        f.formatIfStmt(
          "r != prev || r == 0",
          f.formatAssign("stalls", f.formatLong(0))
        )
      )
      pollBody.append(
        f.formatIfStmt(
          "r == prev && r != 0",
          f.formatAssign("stalls", s"stalls + ${f.formatLong(1)}")
        )
      )
      pollBody.append(f.formatAssign("prev", "r"))
      pollBody.append(
        f.formatIfStmt(s"stalls >= ${Codegen.packetStallReads}", f.breakStmt())
      )
      wait.append(
        f.formatForLoop("w", Codegen.trainPollTries, pollBody.toString)
      )
      wait.append(
        f.formatIfStmt(
          "r < packets",
          f.formatPrintStmt(
            "run_lfsr: the receiver stopped short of the packets asked for"
          )
        )
      )
    }
    body.append(f.formatBlock(wait.toString))

    f.formatFn(
      "run_lfsr",
      body.toString,
      args = Seq(Arg("packets", Datatype.Long))
    )
  }

  /** Turns every observation pad driver (`debugDriverctl_*`) and the TX data
    * debug lane's driver on (`en` = 1) or off (`en` = 0).
    */
  def formatEnableDebugDriversFn(): String = {
    val body = new StringBuilder
    val loopBody = new StringBuilder
    loopBody.append(
      f.formatWriteReg(
        "regDrv",
        s"${f.formatConstantRef("debugDriverctl")} + 8 * i",
        s"(en ? ${f.formatConstantRef("enableDriverCtl")} : " +
          s"${f.formatConstantRef("disableDriverCtl")})"
      )
    )
    body.append(
      f.formatForLoop("i", PhyTest.NumDebugDrivers, loopBody.toString)
    )
    body.append(
      formatWriteNamedReg(
        "debugTxctlTile",
        s"(en ? ${f.formatConstantRef("enableTxCtl")} : " +
          s"${f.formatConstantRef("disableTxCtl")})"
      )
    )
    f.formatFn(
      "enable_debug_drivers",
      body.toString,
      args = Seq(Arg("en", Datatype.Long))
    )
  }

  def formatSetupUcieFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder

    // The clocking stays as software set it. Restart the rest of the block on
    // it before configuring anything, so nothing below goes to a block that
    // came up on the wrong clocking. Both clocks are off first, so the UCIe
    // reset releases every divider, global and per tile, with no clock, and
    // they start in a known phase with each other.
    body.append(f.formatFnCall("set_clk_en", args = Seq(f.formatLong(0))))
    body.append(f.formatFnCall("set_rx_clk_en", args = Seq(f.formatLong(0))))
    body.append(f.formatFnCall("reset_ucie"))

    {
      val loopBody = new StringBuilder
      for (
        case (ofs, value) <- Seq(
          ("Tile", f.formatConstantRef("enableTxCtl"))
        )
      ) {
        loopBody.append(
          f.formatFnCall(
            "write_txctl",
            args = Seq("lane", f.formatConstantRef(s"txctl${ofs}Ofs"), value)
          )
        )
      }
      for (
        case (ofs, value) <- Seq(
          ("Zen", f.formatLong(1)),
          ("Zctl", f.formatLong(0))
        )
      ) {
        loopBody.append(
          f.formatFnCall(
            "write_rxctl",
            args = Seq("lane", f.formatConstantRef(s"rxctl${ofs}Ofs"), value)
          )
        )
      }
      // Every lane the PHY has, plus the tester's loopback transmitter.
      body.append(
        f.formatForLoop(
          "lane",
          params.numLanes + 5,
          loopBody.toString
        )
      )
    }

    body.append(
      formatWriteNamedReg("txClkP", f.formatConstantRef("defaultClkP"))
    )
    body.append(
      formatWriteNamedReg("txClkN", f.formatConstantRef("defaultClkN"))
    )
    body.append(
      formatWriteNamedReg("txValid", f.formatConstantRef("defaultValid"))
    )
    body.append(
      formatWriteNamedReg("rxValid", f.formatConstantRef("defaultValid"))
    )

    // Every observation driver off. Driving full-rate clocks off chip disturbs
    // the link (on Kodiak the TX clock tap put every 6 GHz lane at 50%). Link
    // tests run with them off; `enable_debug_drivers` turns them on for a
    // scope.
    body.append(f.formatFnCall("enable_debug_drivers", Seq(f.formatLong(0))))
    // The clocks back on. After this the clock gate is enough. It switches
    // only between pulses, so gating loses no edge and the dividers resume
    // where they stopped. A delay code change needs only a quiet clock, not a
    // divider restart.
    body.append(f.formatFnCall("set_clk_en", args = Seq(f.formatLong(1))))
    body.append(f.formatFnCall("set_rx_clk_en", args = Seq(f.formatLong(1))))
    // Leave both bands under PhyTest; each test selects what it needs. Before
    // `reset_fsms`, since its datapath reset is what a change of select needs.
    body.append(
      formatWriteNamedReg(
        "controllerSel",
        f.formatConstantRef("controllerSelPhytest")
      )
    )
    body.append(
      formatWriteNamedReg("mainbandMode", f.formatConstantRef("bandModeManual"))
    )
    body.append(
      formatWriteNamedReg("sidebandMode", f.formatConstantRef("bandModeManual"))
    )
    body.append(f.formatFnCall("reset_fsms"))
    sb.append(f.formatFn("setup_ucie", body.toString))
    sb.toString
  }

  /** Aligns every RX lane's word boundary with the far TX's words (see
    * `PhyRegsIO.rxUiDelay`), for a controller that cannot frame words itself as
    * PhyTest does on valid. PhyTest sends a short LFSR run; its RX frames on
    * valid and reports where in its word the run started (`rxFrameOffset`).
    * Each lane's `rxUiDelay` is set to the rest of that word, which puts the
    * run's start at UI 0 of the next. The far TX must send the same (in
    * loopback, this die's own TX). Leaves PhyTest owning the mainband, and
    * every delay at 0 if the RX framed nothing. Remove lane-to-lane skew after
    * this.
    *
    * The boundary holds until the next RX divider reset, or a gated clock gap
    * whose edge count is not a whole word, so call this after both.
    */
  def formatAlignRxWordsFn(): String = {
    val body = new StringBuilder
    body.append(
      formatWriteNamedReg(
        "controllerSel",
        f.formatConstantRef("controllerSelPhytest")
      )
    )
    body.append(
      formatWriteNamedReg("mainbandMode", f.formatConstantRef("bandModeManual"))
    )
    body.append(
      formatWriteNamedReg("txClkP", f.formatConstantRef("defaultClkP"))
    )
    body.append(
      formatWriteNamedReg("txClkN", f.formatConstantRef("defaultClkN"))
    )
    // Its UI 0 set, which is what makes the first set bit the RX sees mark
    // where the far TX's word starts.
    body.append(
      formatWriteNamedReg("txValid", f.formatConstantRef("defaultValid"))
    )
    body.append(
      formatWriteNamedReg("rxValid", f.formatConstantRef("defaultValid"))
    )
    def writeDelay(value: String) = f.formatForLoop(
      "lane",
      Phy.numRxDataLanes(params.numLanes),
      f.formatWriteReg(
        "regDrv",
        s"${f.formatConstantRef("rxUiDelay")} + lane * ${f.formatConstantRef("rxUiDelayWidth")}",
        value
      )
    )
    // Measured on the deserializers' own boundary.
    body.append(writeDelay(f.formatLong(0)))
    body.append(f.formatFnCall("seed_lfsrs"))
    body.append(
      f.formatFnCall(
        "run_lfsr",
        args = Seq(f.formatLong(Codegen.alignRxPackets.toLong))
      )
    )
    // Both declared up front: SystemVerilog takes no declaration after a
    // statement in a block.
    val apply = new StringBuilder
    apply.append(f.formatDeclare("received"))
    apply.append(f.formatDeclare("offset"))
    apply.append(
      f.formatReadReg(
        "regDrv",
        "received",
        f.formatConstantRef("rxPacketsReceived"),
        declareVar = false
      )
    )
    apply.append(
      f.formatReadReg(
        "regDrv",
        "offset",
        f.formatConstantRef("rxFrameOffset"),
        declareVar = false
      )
    )
    apply.append(
      f.formatIfStmt(
        "received == 0",
        f.formatPrintStmt(
          "align_rx_words: the RX framed nothing, so rxUiDelay stays at 0"
        )
      )
    )
    apply.append(
      f.formatIfStmt(
        "received != 0",
        writeDelay(s"(${Phy.SerdesRatio} - offset) % ${Phy.SerdesRatio}")
      )
    )
    body.append(f.formatBlock(apply.toString))
    f.formatFn("align_rx_words", body.toString)
  }

  /** MMIO setup to bring the link up through the digital controller
    * (`UcieDigitalTop`) instead of PhyTest. Enables the mainband pad drivers,
    * starts DVSEC link training, and gives the mainband/sideband muxes to
    * `ucieDigital` through `controllerSel`. Unlike `setup_ucie`, it skips the
    * PhyTest-only settings (manual clock/valid patterns, debug drivers,
    * `reset_fsms`), which the digital controller neither reads nor is reset by.
    *
    * Returns only after DVSEC `LinkStatus` reports link-up, so the caller can
    * use the link at once. `setup_ucie` gives PhyTest callers the same
    * guarantee by leaving the PHY ready.
    */
  def formatSetupUcieDigitalFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder

    // The block is held in the UCIe reset out of chip reset. Release it,
    // without asserting it first: a caller may already have configured lanes
    // it wants kept.
    body.append(formatWriteNamedReg("ucieRst", f.formatLong(0)))

    // The dividers reset with both clocks gated, so every lane, clock lanes
    // included, restarts on the same edge. The global delay stays where the
    // caller trained it.
    body.append(f.formatFnCall("set_clk_en", args = Seq(f.formatLong(0))))
    body.append(f.formatFnCall("set_rx_clk_en", args = Seq(f.formatLong(0))))
    body.append(f.formatFnCall("reset_dividers"))
    body.append(f.formatFnCall("set_clk_en", args = Seq(f.formatLong(1))))
    body.append(f.formatFnCall("set_rx_clk_en", args = Seq(f.formatLong(1))))

    {
      // Phy.scala's lane order: numLanes data lanes, valid, track, then the
      // two forwarded-clock lanes. TX drives all numLanes+4 (ucieDigital's
      // mainband tx.bits.clkp/clkn/trk go straight to the clkP/clkN/track
      // TxLane instances, like any data lane). Left at their reset "driver
      // off" state, the extra four silently drop what is sent on them,
      // including the clock. Each tile's `Dctrl` is kept, so a trim set in
      // bring-up survives.
      val txLoopBody = new StringBuilder
      val tileAddr = s"${f.formatConstantRef("txctl")} + lane * " +
        s"${f.formatConstantRef("txctlWidth")} + ${f.formatConstantRef("txctlTileOfs")}"
      val mask = f.formatConstantRef("txCtlDelayMask")
      txLoopBody.append(
        f.formatReadReg("regDrv", "tile", tileAddr, declareVar = true)
      )
      txLoopBody.append(
        f.formatFnCall(
          "write_txctl",
          args = Seq(
            "lane",
            f.formatConstantRef("txctlTileOfs"),
            s"((tile & $mask) | (${f.formatConstantRef("enableTxCtl")} & ~$mask))"
          )
        )
      )
      body.append(
        f.formatForLoop(
          "lane",
          Phy.numTxLanes(params.numLanes),
          f.formatBlock(txLoopBody.toString)
        )
      )
    }

    {
      // RX needs only data+valid+track (Phy.numRxDataLanes). Its clkP/clkN
      // lanes recover a clock instead of deserializing a word, so they need
      // no receiver enable.
      val rxLoopBody = new StringBuilder
      for (
        case (ofs, value) <- Seq(
          ("Zen", f.formatLong(1)),
          ("Zctl", f.formatLong(0))
        )
      ) {
        rxLoopBody.append(
          f.formatFnCall(
            "write_rxctl",
            args = Seq("lane", f.formatConstantRef(s"rxctl${ofs}Ofs"), value)
          )
        )
      }
      body.append(
        f.formatForLoop(
          "lane",
          Phy.numRxDataLanes(params.numLanes),
          rxLoopBody.toString
        )
      )
    }

    // The controller takes words on the deserializers' boundary as they come,
    // so line that up with its TX's first, while PhyTest still has the lanes.
    body.append(f.formatFnCall("align_rx_words"))

    // In strobe mode the controller gates the forwarded clock in ACTIVE, with
    // the postamble it shares with PhyTest.
    body.append(
      formatWriteNamedReg(
        "txClkPostamble",
        f.formatLong(
          (Codegen.controllerClkPostambleUi / PhyTest.ClkPostambleUi).toLong
        )
      )
    )

    // Give the mainband/sideband muxes to the digital controller BEFORE
    // starting training below. phy.io.sb.txClk/txData (TileLink.scala) mux on
    // this same controllerSel. Written after start_link_training, SBInit's
    // first clock pattern would start while the bump still goes to PhyTest's
    // driver, and be silently lost mid-word until this write takes effect.
    body.append(
      formatWriteNamedReg(
        "controllerSel",
        f.formatConstantRef("controllerSelUcie")
      )
    )
    // Then empty the mainband queues of whatever PhyTest left in them.
    body.append(f.formatFnCall("reset_datapath"))
    // Only now release the controller, so it starts from reset on its own
    // lanes, not on traffic from before it was selected.
    body.append(formatWriteNamedReg("ucieControllerRst", f.formatLong(0)))

    // Read-modify-write DVSEC LinkControl, which also holds
    // target_link_width/target_link_speed (see
    // Codegen.linkControlBringupMask). In its own block because the read
    // declares `linkControl` partway through the function.
    {
      val rmw = new StringBuilder
      rmw.append(
        f.formatReadReg(
          "regDrv",
          "linkControl",
          f.formatConstantRef("rawFormatEnable")
        )
      )
      rmw.append(
        f.formatWriteReg(
          "regDrv",
          f.formatConstantRef("rawFormatEnable"),
          s"linkControl | ${f.formatLong(Codegen.linkControlBringupMask.toLong)}"
        )
      )
      body.append(f.formatBlock(rmw.toString))
    }

    // Both error masks reset to "all masked"; unmask so real link errors
    // surface in LinkStatus/IRQs instead of only latching silently.
    body.append(formatWriteNamedReg("uncorrErrMask", f.formatLong(0)))
    body.append(formatWriteNamedReg("corrErrMask", f.formatLong(0)))

    // Block until training completes. LinkStatus is named after its first
    // packed field, raw_format_enabled (see Codegen.linkStatusLinkUpBit for
    // link_status's bit position within that same word).
    {
      val whileBody = new StringBuilder
      whileBody.append(
        f.formatReadReg(
          "regDrv",
          "linkStatus",
          f.formatConstantRef("rawFormatEnabled"),
          declareVar = false
        )
      )
      whileBody.append(
        f.formatIfStmt(
          s"(linkStatus >> ${f.formatLong(
              Codegen.linkStatusLinkUpBit.toLong
            )}) & ${f.formatLong(1)}",
          f.breakStmt()
        )
      )
      val wait = new StringBuilder
      wait.append(f.formatDeclare("linkStatus"))
      wait.append(f.formatAssign("linkStatus", f.formatLong(0)))
      wait.append(
        f.formatForLoop("w", Codegen.linkUpPollTries, whileBody.toString)
      )
      wait.append(
        f.formatIfStmt(
          s"((linkStatus >> ${f.formatLong(Codegen.linkStatusLinkUpBit.toLong)}) & ${f
              .formatLong(1)}) == 0",
          f.formatPrintStmt(
            "setup_ucie_digital: link training never brought the link up"
          )
        )
      )
      body.append(f.formatBlock(wait.toString))
    }

    sb.append(f.formatFn("setup_ucie_digital", body.toString))
    sb.toString
  }

  def formatWriteTxDataChunkFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(formatWriteNamedReg("txDataLaneGroup", "group"))
    body.append(formatWriteNamedReg("txDataOffset", "ofs"))
    body.append(
      formatWriteNamedReg(
        "txDataChunkIn0",
        s"(data1 << ${f.formatLong(32)}) | data0"
      )
    )
    body.append(
      formatWriteNamedReg(
        "txDataChunkIn1",
        s"(data3 << ${f.formatLong(32)}) | data2"
      )
    )
    body.append(
      formatWriteNamedReg(
        "txWriteChunk",
        f.formatLong(1)
      )
    )
    sb.append(
      f.formatFn(
        "write_tx_data_chunk",
        body.toString,
        args = Seq(
          Arg("group", Datatype.Long),
          Arg("ofs", Datatype.Long),
          Arg("data0", Datatype.Long),
          Arg("data1", Datatype.Long),
          Arg("data2", Datatype.Long),
          Arg("data3", Datatype.Long)
        )
      )
    )
    sb.toString
  }

  def formatManualSimpleLoopbackFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(f.formatFnCall("setup_ucie"))
    body.append(
      formatWriteNamedReg(
        "txPacketsToSend",
        f.formatLong(32)
      )
    )
    val writeChunkOuterLoop = new StringBuilder
    val writeChunkInnerLoop = new StringBuilder
    writeChunkInnerLoop.append(
      f.formatFnCall(
        "write_tx_data_chunk",
        args = Seq(
          "group",
          "ofs",
          f.formatLong(0xdeadbeefL),
          f.formatLong(0xdeadbeefL),
          f.formatLong(0xdeadbeefL),
          f.formatLong(0xdeadbeefL)
        )
      )
    )
    writeChunkOuterLoop.append(
      f.formatForLoop("group", 4, writeChunkInnerLoop.toString)
    )
    writeChunkOuterLoop.append(
      f.formatFnCall(
        "write_tx_data_chunk",
        args = Seq(
          f.formatLong(4),
          "ofs",
          f.formatConstantRef("defaultValid"),
          f.formatConstantRef("defaultTrack"),
          f.formatLong(0),
          f.formatLong(0)
        )
      )
    )
    body.append(f.formatForLoop("ofs", 32, writeChunkOuterLoop.toString))
    body.append(
      formatWriteNamedReg(
        "testTarget",
        f.formatConstantRef("testTargetMainband")
      )
    )
    body.append(
      formatWriteNamedReg(
        "txTestMode",
        f.formatConstantRef("txTestModeManual")
      )
    )
    body.append(
      formatWriteNamedReg(
        "txDataMode",
        f.formatConstantRef("dataModeFinite")
      )
    )
    body.append(
      formatWriteNamedReg(
        "rxDataMode",
        f.formatConstantRef("dataModeInfinite")
      )
    )
    body.append(
      formatWriteNamedReg(
        "txManualRepeatPeriod",
        f.formatLong(0)
      )
    )
    body.append(
      formatWriteNamedReg(
        "txExecute",
        f.formatLong(1)
      )
    )
    val whileBody = new StringBuilder
    whileBody.append(
      f.formatReadReg(
        "regDrv",
        "r",
        f.formatConstantRef("rxPacketsReceived"),
        declareVar = true
      )
    )
    whileBody.append(
      f.formatIfStmt(s"r >= ${f.formatLong(32)}", f.breakStmt())
    )
    body.append(f.formatWhileLoop(f.formatBool(true), whileBody.toString))
    body.append(f.formatPrintStmt("All packets received!"))
    body.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("txTestState"),
        f.formatConstantRef("txTestStateDone"),
        msg = Some("TX test state is not done after all packets have been sent")
      )
    )
    body.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("txPacketsSent"),
        f.formatLong(32),
        msg = Some("TX packets sent is not 32 after all data has been sent")
      )
    )
    val numLanes = params.numLanes
    val readChunkOuterLoop = new StringBuilder
    readChunkOuterLoop.append(
      formatWriteNamedReg(
        "rxDataOffset",
        "ofs"
      )
    )
    val readChunkInnerLoop = new StringBuilder
    readChunkInnerLoop.append(
      formatWriteNamedReg(
        "rxDataLane",
        "lane"
      )
    )
    readChunkInnerLoop.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("rxDataChunk"),
        f.formatLong(0xdeadbeefL),
        msg = Some("RX data chunk does not match expected")
      )
    )
    readChunkOuterLoop.append(
      f.formatForLoop("lane", numLanes, readChunkInnerLoop.toString)
    )
    // Valid and track are outside the data lane loop, so they go on the outer
    // builder. Appending them to the inner one after its `toString` was taken
    // would drop them from the output.
    readChunkOuterLoop.append(
      formatWriteNamedReg(
        "rxDataLane",
        f.formatLong(PhyTest.validLane(numLanes))
      )
    )
    readChunkOuterLoop.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("rxDataChunk"),
        f.formatConstantRef("defaultValid"),
        msg = Some("RX valid chunk does not match expected")
      )
    )
    readChunkOuterLoop.append(
      formatWriteNamedReg(
        "rxDataLane",
        f.formatLong(PhyTest.trackLane(numLanes))
      )
    )
    readChunkOuterLoop.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("rxDataChunk"),
        f.formatConstantRef("defaultTrack"),
        msg = Some("RX track chunk does not match expected")
      )
    )
    body.append(f.formatForLoop("ofs", 32, readChunkOuterLoop.toString))
    body.append(
      formatExpectClear(
        Seq(
          "txTestFifoUnderflow" -> "Mainband TX queue ran dry",
          "rxTestFifoOverflow" -> "Mainband RX queue overflowed",
          "txDebugFifoUnderflow" -> "TX debug lane queue ran dry"
        )
      )
    )
    sb.append(f.formatFn("manual_simple", body.toString))
    sb.toString
  }

  /** Asserts that each named sticky flag is still clear, with its message. */
  def formatExpectClear(flags: Seq[(String, String)]): String =
    flags.map { case (name, msg) =>
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef(name),
        f.formatLong(0),
        msg = Some(msg)
      )
    }.mkString

  /** A manual-mode run over the tester's on-chip loopback lane. The TX lane
    * feeds the RX lane directly, so this tests the serializer, driver, AFE and
    * deserializer with no partner die or bump.
    *
    * The lane sends zeros until the pattern starts, and the RX starts capturing
    * at the first one it sees: the lowest set bit of the first word.
    * `0xdeadbeef` has bit 0 set, so capture starts on a word boundary and every
    * offset reads the word back unchanged. If bit 0 were clear, the whole
    * capture would shift by the position of the lowest set bit.
    */
  def formatManualLoopbackFn(): String = {
    val numLanes = params.numLanes
    val loopbackLane = PhyTest.loopbackLane(numLanes)
    val packets = 32
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(f.formatFnCall("setup_ucie"))
    body.append(
      formatWriteNamedReg("txPacketsToSend", f.formatLong(packets))
    )

    // The loopback lane shares its SRAM group with valid and track; only its
    // own slot in that group carries the pattern.
    val chunkArgs = (0 until 4).map { slot =>
      if (slot == loopbackLane % 4) f.formatLong(0xdeadbeefL)
      else f.formatLong(0)
    }
    val writeChunkLoop = new StringBuilder
    writeChunkLoop.append(
      f.formatFnCall(
        "write_tx_data_chunk",
        args = Seq(f.formatLong(loopbackLane / 4), "ofs") ++ chunkArgs
      )
    )
    body.append(f.formatForLoop("ofs", packets, writeChunkLoop.toString))

    body.append(
      formatWriteNamedReg(
        "testTarget",
        f.formatConstantRef("testTargetLoopback")
      )
    )
    // Arms the loopback TX queue's underflow flag, which only counts from a
    // datapath reset taken with the loopback lane the target.
    body.append(f.formatFnCall("reset_datapath"))
    body.append(
      formatWriteNamedReg("txTestMode", f.formatConstantRef("txTestModeManual"))
    )
    body.append(
      formatWriteNamedReg("txDataMode", f.formatConstantRef("dataModeFinite"))
    )
    body.append(
      formatWriteNamedReg("rxDataMode", f.formatConstantRef("dataModeInfinite"))
    )
    body.append(formatWriteNamedReg("txManualRepeatPeriod", f.formatLong(0)))
    body.append(formatWriteNamedReg("txExecute", f.formatLong(1)))

    val whileBody = new StringBuilder
    whileBody.append(
      f.formatReadReg(
        "regDrv",
        "r",
        f.formatConstantRef("rxPacketsReceived"),
        declareVar = true
      )
    )
    whileBody.append(
      f.formatIfStmt(s"r >= ${f.formatLong(packets)}", f.breakStmt())
    )
    body.append(f.formatWhileLoop(f.formatBool(true), whileBody.toString))
    body.append(f.formatPrintStmt("All loopback packets received!"))
    body.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("txTestState"),
        f.formatConstantRef("txTestStateDone"),
        msg = Some("TX test state is not done after the loopback run")
      )
    )
    body.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("txPacketsSent"),
        f.formatLong(packets),
        msg = Some("TX packets sent is not the whole loopback run")
      )
    )

    val readChunkLoop = new StringBuilder
    readChunkLoop.append(formatWriteNamedReg("rxDataOffset", "ofs"))
    readChunkLoop.append(
      formatWriteNamedReg("rxDataLane", f.formatLong(loopbackLane))
    )
    readChunkLoop.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("rxDataChunk"),
        f.formatLong(0xdeadbeefL),
        msg = Some("RX loopback chunk does not match expected")
      )
    )
    body.append(f.formatForLoop("ofs", packets, readChunkLoop.toString))
    body.append(
      formatExpectClear(
        Seq(
          "loopbackTxFifoUnderflow" -> "Loopback TX queue ran dry",
          "loopbackRxFifoOverflow" -> "Loopback RX queue overflowed"
        )
      )
    )
    sb.append(f.formatFn("manual_loopback", body.toString))
    sb.toString
  }

  /** A single TL write/read round trip over `band`, which is either
    * `mainbandMode` or `sidebandMode`.
    */
  def formatTlLoopbackFn(name: String, band: String): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(f.formatFnCall("setup_ucie"))
    body.append(
      formatWriteNamedReg(band, f.formatConstantRef("bandModeTl"))
    )
    body.append(f.formatFnCall("reset_datapath"))
    body.append(f.formatWaitCycles(32))
    body.append(
      f.formatWrite(
        "mbDrv",
        f.formatLong(0),
        f.formatLong(0xdeadbeefL)
      )
    )
    body.append(
      f.formatAssertEq(
        "mbDrv",
        f.formatLong(0),
        f.formatLong(0xdeadbeefL)
      )
    )
    if (band == "mainbandMode") {
      body.append(
        formatExpectClear(
          Seq("tlRxFifoOverflow" -> "TL path RX queue overflowed")
        )
      )
    }
    sb.append(f.formatFn(name, body.toString))
    sb.toString
  }

  /** Sends one packet over the sideband with both bands left under PhyTest, and
    * checks it comes back bit exact through the MMIO staging registers.
    */
  def formatSbManualLoopbackFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    // LSB and MSB are both 0, so nothing about the packet can act as framing.
    val packet = 0x0123456789abcdeL
    def expect(reg: String, value: String, msg: String): String =
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef(reg),
        value,
        msg = Some(msg)
      )

    body.append(f.formatFnCall("setup_ucie"))
    body.append(
      formatWriteNamedReg("sidebandMode", f.formatConstantRef("bandModeManual"))
    )
    body.append(formatWriteNamedReg("sbRxRst", f.formatLong(1)))
    body.append(f.formatWaitCycles(16))
    body.append(formatWriteNamedReg("sbTxPacket", f.formatLong(packet)))
    body.append(formatWriteNamedReg("sbTxSend", f.formatLong(1)))
    body.append(f.formatWaitCycles(256))
    body.append(
      expect(
        "sbTxBusy",
        f.formatLong(0),
        "Sideband TX still busy after the packet should have gone out"
      )
    )
    body.append(
      expect(
        "sbRxValid",
        f.formatLong(1),
        "Sideband RX did not receive the looped back packet"
      )
    )
    body.append(
      expect(
        "sbRxPacket",
        f.formatLong(packet),
        "Sideband RX packet does not match what was sent"
      )
    )
    body.append(
      expect("sbRxOverflow", f.formatLong(0), "Sideband RX overflowed")
    )
    body.append(formatWriteNamedReg("sbRxPop", f.formatLong(1)))
    body.append(f.formatWaitCycles(16))
    body.append(
      expect(
        "sbRxValid",
        f.formatLong(0),
        "Sideband RX still valid after popping the only packet"
      )
    )
    sb.append(f.formatFn("sb_manual", body.toString))
    sb.toString
  }

  def formatTlSimpleLoopbackFn(): String =
    formatTlLoopbackFn("tl_simple", "mainbandMode")

  def formatTlSidebandLoopbackFn(): String =
    formatTlLoopbackFn("tl_sideband", "sidebandMode")

  def formatTlLongLoopbackFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(f.formatFnCall("setup_ucie"))
    body.append(
      formatWriteNamedReg("mainbandMode", f.formatConstantRef("bandModeTl"))
    )
    body.append(f.formatFnCall("reset_datapath"))
    body.append(f.formatWaitCycles(32))
    for (i <- 0 until 32) {
      body.append(
        f.formatWrite(
          "mbDrv",
          f.formatLong(i.toLong * 8L),
          f.formatLong(i.toLong * 0x0100010001000100L)
        )
      )
    }
    for (i <- 0 until 32) {
      body.append(
        f.formatAssertEq(
          "mbDrv",
          f.formatLong(i.toLong * 8L),
          f.formatLong(i.toLong * 0x0100010001000100L)
        )
      )
    }
    sb.append(f.formatFn("tl_long", body.toString))
    sb.toString
  }

  def formatDefines(): String =
    Codegen.requireUniqueDefines(formatRegs() + formatConstants())

  def formatFns(): String = {
    val sb = new StringBuilder
    sb.append(formatResetDatapathFn())
    sb.append(formatResetFsmsFn())
    sb.append(formatWriteTxctlFn())
    sb.append(formatWriteRxctlFn())
    sb.append(formatSetClkEnFn())
    sb.append(formatSetRateCfgFn())
    sb.append(formatSetFreqSelAutoFn())
    sb.append(formatSetClkManualFn())
    sb.append(formatApplyClkCfgFn())
    sb.append(formatSetUngateSrcFn())
    sb.append(formatReleaseClkGateFn())
    sb.append(formatResetUcieFn())
    sb.append(formatResetDividersFn())
    sb.append(formatSetMainClkFn())
    sb.append(formatSetTxPhaseFn())
    sb.append(formatUseInternalClkFn())
    sb.append(formatSetRxClkEnFn())
    sb.append(formatSetGlobalDelayFn())
    sb.append(formatSetTxDelayFn())
    sb.append(formatSetRxVrefFn())
    sb.append(formatSetRxDelayFn())
    sb.append(formatEnableDebugDriversFn())
    sb.append(formatSetupUcieFn())
    sb.append(formatSetupUcieDigitalFn())
    sb.append(formatSeedLfsrsFn())
    sb.append(formatRunLfsrFn())
    sb.append(formatAlignRxWordsFn())
    sb.append(formatWriteTxDataChunkFn())
    sb.append(formatManualSimpleLoopbackFn())
    sb.append(formatManualLoopbackFn())
    sb.append(formatSbManualLoopbackFn())
    sb.append(formatTlSimpleLoopbackFn())
    sb.append(formatTlSidebandLoopbackFn())
    sb.append(formatTlLongLoopbackFn())
    sb.toString
  }

  def formatAll(): String = {
    val sb = new StringBuilder
    sb.append(formatDefines())
    sb.append(formatFns())
    sb.toString
  }
}

/** Generates a C header (`ucie.h`) that mirrors the SystemVerilog setup
  * sequence from `Codegen`: `#define`s for register offsets and tuned
  * constants, plus `static inline` helpers for `write_txctl`, `write_rxctl`,
  * `set_tx_delay`, `set_rx_vref`, `reset_datapath`, `reset_fsms`, `setup_ucie`,
  * `setup_ucie_digital`, `seed_lfsrs`, and `run_lfsr`. RISC-V test programs
  * `#include` it to program the UCIe MMIO registers from C. `set_tx_delay`
  * through `run_lfsr` (except `setup_ucie_digital`) are enough to train a lane
  * as `TrainMainbandTestDriver` does in simulation.
  *
  * Run with one argument, the destination path: ./mill ucie.runMain
  * edu.berkeley.cs.uciedigital.tilelink.GenUcieHeader \ software/ucie.h
  */
object GenUcieHeader {

  /** How to regenerate the header this object emits by default. A downstream
    * chip generating the header from its own parameters passes its own command
    * so the file says how to reproduce it.
    */
  val defaultRegenCommand =
    "./mill ucie.runMain edu.berkeley.cs.uciedigital.tilelink.GenUcieHeader <path>"

  def render(
      params: UcieTLParams = Codegen.ucieParams,
      regenCommand: String = defaultRegenCommand
  ): String = {
    val cg = new Codegen(new CFormatter, params)
    val sb = new StringBuilder
    sb.append(
      "// Auto-generated by edu.berkeley.cs.uciedigital.tilelink.GenUcieHeader.\n"
    )
    sb.append("// Regenerate via:\n")
    sb.append(s"//   $regenCommand\n")
    sb.append("// DO NOT EDIT.\n\n")
    sb.append("#ifndef __UCIE_H__\n")
    sb.append("#define __UCIE_H__\n\n")
    sb.append("#include <stdint.h>\n")
    sb.append("#include <assert.h>\n")
    sb.append("#include \"mmio.h\"\n\n")
    sb.append(
      "// === Register offsets (relative to the UCIe MMIO base, e.g. 0x8000) ===\n"
    )
    val regs = cg.formatRegs()
    val constants = cg.formatConstants()
    Codegen.requireUniqueDefines(regs + constants)
    sb.append(regs)
    sb.append("\n// === Constants ===\n")
    sb.append(constants)
    sb.append("\n// === Helper functions ===\n")
    sb.append(cg.formatResetDatapathFn())
    sb.append(cg.formatResetFsmsFn())
    sb.append("\n")
    sb.append(cg.formatWriteTxctlFn())
    sb.append("\n")
    sb.append(cg.formatWriteRxctlFn())
    sb.append("\n")
    sb.append(cg.formatSetClkEnFn())
    sb.append(cg.formatSetRateCfgFn())
    sb.append(cg.formatSetFreqSelAutoFn())
    sb.append(cg.formatSetClkManualFn())
    sb.append(cg.formatApplyClkCfgFn())
    sb.append(cg.formatSetUngateSrcFn())
    sb.append(cg.formatReleaseClkGateFn())
    sb.append(cg.formatResetUcieFn())
    sb.append(cg.formatResetDividersFn())
    sb.append(cg.formatSetMainClkFn())
    sb.append(cg.formatSetTxPhaseFn())
    sb.append(cg.formatUseInternalClkFn())
    sb.append(cg.formatSetRxClkEnFn())
    sb.append(cg.formatSetGlobalDelayFn())
    sb.append(cg.formatSetTxDelayFn())
    sb.append("\n")
    sb.append(cg.formatSetRxVrefFn())
    sb.append(cg.formatSetRxDelayFn())
    sb.append("\n")
    sb.append(cg.formatEnableDebugDriversFn())
    sb.append(cg.formatSetupUcieFn())
    sb.append("\n")
    // In C a function has to come before its callers: `setup_ucie_digital`
    // calls `align_rx_words`, which runs PhyTest through the two before it.
    sb.append(cg.formatSeedLfsrsFn())
    sb.append("\n")
    sb.append(cg.formatRunLfsrFn())
    sb.append("\n")
    sb.append(cg.formatAlignRxWordsFn())
    sb.append("\n")
    sb.append(cg.formatSetupUcieDigitalFn())
    sb.append("\n#endif\n")
    sb.toString
  }

  def main(args: Array[String]): Unit = {
    require(
      args.length == 1,
      s"Usage: GenUcieHeader <output-path>; got ${args.mkString(" ")}"
    )
    val out = os.Path(args(0), os.pwd)
    os.makeDir.all(out / os.up)
    os.write.over(out, render())
    println(s"Wrote $out")
  }
}
