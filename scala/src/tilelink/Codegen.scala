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
  TestTarget,
  TxTestMode,
  DataMode,
  TxTestState
}
import edu.berkeley.cs.uciedigital.phy.macros.{DriverCtlIO, SkewCtlIO}

/** Backend-specific code formatter consumed by `Codegen`. Each method emits a
  * snippet in the target language; subclasses pick the syntax (SystemVerilog,
  * C, etc.). Implementations are responsible for whatever language-specific
  * setup is required (e.g. C's `CFormatter` prepends an implicit `base`
  * parameter to every emitted function so register-write helpers can compute
  * absolute addresses).
  */
trait Formatter {
  def formatFn(name: String, body: String, args: Seq[Arg] = Seq.empty): String
  def formatFnCall(name: String, args: Seq[String] = Seq.empty): String
  def formatForLoop(loopVar: String, length: Int, body: String): String
  def formatWhileLoop(condition: String, body: String): String
  def formatIfStmt(condition: String, body: String): String
  def formatPrintStmt(msg: String): String
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
  def formatPrintStmt(msg: String): String = {
    s"$$display(\"${Codegen.escapeString(msg)}\");\n"
  }
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

  val enableDriverCtl: BigInt = (new DriverCtlIO)
    .Lit(_.pu_ctl -> 63.U, _.pd_ctl -> 63.U, _.en -> true.B, _.en_b -> false.B)
    .litValue

  val defaultSkewCtl: BigInt = (new SkewCtlIO)
    .Lit(
      _.dll_en -> true.B,
      _.ocl -> false.B,
      _.delay -> 31.U,
      _.mux_en -> (3 << 6).U,
      _.band_ctrl -> 1.U,
      _.mix_en -> 16.U,
      _.nen_out -> 20.U,
      _.pen_out -> 22.U
    )
    .litValue

  val ucieParams: UcieTLParams = UcieTLParams()

  // Elaborate UcieTL once; share the regmap between formatRegs and regAddrMap.
  lazy val ucieRegmap: Seq[(Int, Seq[RegField])] = {
    implicit val p = Parameters.empty
    val ucie_dut = new RTLHarness(
      new UcieTL(ucieParams, Seq(AddressSet(0x0, 0xffffL)), 32, 32)
    )
    val ucie = (new chisel3.stage.phases.Elaborate)
      .transform(Seq(chisel3.stage.ChiselGeneratorAnnotation { () =>
        LazyModule(ucie_dut).module
      }))
      .collectFirst { case a: DesignAnnotation[ucie_dut.Impl] => a.design }
      .get
    ucie.regmap
  }

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

    for (lane <- 0 until ucieParams.numLanes + 4) {
      reqs += write(s"txctl_${lane}_dllReset", 0)
      reqs += write(s"txctl_${lane}_driver", enableDriverCtl)
      reqs += write(s"txctl_${lane}_skew", defaultSkewCtl)
      reqs += write(s"rxctl_${lane}_zen", 1)
      reqs += write(s"rxctl_${lane}_zctl", 0)
    }

    reqs += write("txClkP", defaultClkP)
    reqs += write("txClkN", defaultClkN)
    reqs += write("txTrack", defaultTrack)
    reqs += write("txValid", defaultValid)
    reqs += write("rxLfsrValid", defaultValid)
    reqs += write("commonTxctlDllReset", 0)
    reqs += write("divResetb", 1)

    for (i <- 0 until 6) {
      reqs += write(s"commonDriverctl_$i", enableDriverCtl)
    }

    reqs += write("commonTxctlDriver", enableDriverCtl)
    reqs += write("commonTxctlSkew", defaultSkewCtl)

    reqs += write("txFsmRst", 1)
    reqs += write("rxFsmRst", 1)
    reqs += write("commonTxFsmRst", 1)

    reqs += write("controllerSel", ControllerSel.phytest.litValue)
    reqs += write("mainbandMode", mainbandMode)
    reqs += write("sidebandMode", sidebandMode)

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
  *   - Every emitted function takes an implicit `uintptr_t base` first
  *     parameter; register-write helpers expand to `reg_write64(base + ofs,
  *     v)`, so the same `Codegen.format*Fn` bodies work unchanged.
  *   - Asserts collapse to plain `assert(...)`; the optional message is
  *     discarded (revisit if richer reporting is wanted).
  */
class CFormatter extends Formatter {
  def getConstantName(name: String): String = "UCIE_" + name
    .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
    .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
    .toUpperCase

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

  def formatPrintStmt(msg: String): String =
    s"""printf("${Codegen.escapeString(msg)}\\n");\n"""

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

class Codegen(f: Formatter) {
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
    for (case (addr, reg) <- Codegen.ucieRegmap) {
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
        ("testTargetLoopback", TestTarget.mainband.litValue),
        ("controllerSelPhytest", ControllerSel.phytest.litValue),
        ("controllerSelUcie", ControllerSel.ucie.litValue),
        ("bandModeManual", BandMode.manual.litValue),
        ("bandModeTl", BandMode.tl.litValue),
        ("defaultClkP", Codegen.defaultClkP),
        ("defaultClkN", Codegen.defaultClkN),
        ("defaultValid", Codegen.defaultValid),
        ("defaultTrack", Codegen.defaultTrack),
        ("enableDriverCtl", Codegen.enableDriverCtl),
        ("defaultSkewCtl", Codegen.defaultSkewCtl)
      )
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

  def formatResetFsmsFn(): String = {
    val body = new StringBuilder
    body.append(
      formatWriteNamedReg("txFsmRst", f.formatLong(1))
    )
    body.append(
      formatWriteNamedReg("rxFsmRst", f.formatLong(1))
    )
    body.append(
      formatWriteNamedReg("commonTxFsmRst", f.formatLong(1))
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

  def formatSetupUcieFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder

    {
      val loopBody = new StringBuilder
      for (
        case (ofs, value) <- Seq(
          ("DllReset", f.formatLong(0)),
          ("Driver", f.formatConstantRef("enableDriverCtl")),
          ("Skew", f.formatConstantRef("defaultSkewCtl"))
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
      body.append(f.formatForLoop("lane", 21, loopBody.toString))
    }

    body.append(
      formatWriteNamedReg("txClkP", f.formatConstantRef("defaultClkP"))
    )
    body.append(
      formatWriteNamedReg("txClkN", f.formatConstantRef("defaultClkN"))
    )
    body.append(
      formatWriteNamedReg("txTrack", f.formatConstantRef("defaultTrack"))
    )
    body.append(
      formatWriteNamedReg("txValid", f.formatConstantRef("defaultValid"))
    )
    body.append(
      formatWriteNamedReg("rxLfsrValid", f.formatConstantRef("defaultValid"))
    )
    body.append(
      formatWriteNamedReg("commonTxctlDllReset", f.formatLong(0))
    )
    // TODO: Gate clock before de-asserting reset.
    body.append(
      formatWriteNamedReg("divResetb", f.formatLong(1))
    )

    {
      val loopBody = new StringBuilder
      loopBody.append(
        f.formatWriteReg(
          "regDrv",
          s"${f.formatConstantRef("commonDriverctl")} + 8 * i",
          f.formatConstantRef("enableDriverCtl")
        )
      )
      body.append(f.formatForLoop("i", 6, loopBody.toString))
    }
    body.append(
      formatWriteNamedReg(
        "commonTxctlDriver",
        f.formatConstantRef("enableDriverCtl")
      )
    )

    body.append(
      formatWriteNamedReg(
        "commonTxctlSkew",
        f.formatConstantRef("defaultSkewCtl")
      )
    )
    body.append(f.formatFnCall("reset_fsms"))
    // Leave both bands under PhyTest; each test selects what it needs.
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
    sb.append(f.formatFn("setup_ucie", body.toString))
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
      f.formatForLoop("lane", 16, readChunkInnerLoop.toString)
    )
    readChunkOuterLoop.append(
      formatWriteNamedReg(
        "rxDataLane",
        f.formatLong(16)
      )
    )
    readChunkInnerLoop.append(
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
        f.formatLong(17)
      )
    )
    readChunkInnerLoop.append(
      f.formatUcieAssertEq(
        "regDrv",
        f.formatConstantRef("rxDataChunk"),
        f.formatConstantRef("defaultTrack"),
        msg = Some("RX track chunk does not match expected")
      )
    )
    body.append(f.formatForLoop("ofs", 32, readChunkOuterLoop.toString))
    sb.append(f.formatFn("manual_simple", body.toString))
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

  def formatDefines(): String = {
    val sb = new StringBuilder
    sb.append(formatRegs())
    sb.append(formatConstants())
    sb.toString
  }

  def formatFns(): String = {
    val sb = new StringBuilder
    sb.append(formatResetFsmsFn())
    sb.append(formatWriteTxctlFn())
    sb.append(formatWriteRxctlFn())
    sb.append(formatSetupUcieFn())
    sb.append(formatWriteTxDataChunkFn())
    sb.append(formatManualSimpleLoopbackFn())
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
  * sequence emitted by `Codegen` — `#define`s for register offsets and tuned
  * constants, plus `static inline` helpers for `write_txctl`, `write_rxctl`,
  * `reset_fsms`, and `setup_ucie`. RISC-V test programs can `#include` it to
  * program the UCIe MMIO registers from C.
  *
  * Run with one argument — the destination path: ./mill ucie.runMain
  * edu.berkeley.cs.uciedigital.tilelink.GenUcieHeader \ software/ucie.h
  */
object GenUcieHeader {
  def render(): String = {
    val cg = new Codegen(new CFormatter)
    val sb = new StringBuilder
    sb.append(
      "// Auto-generated by edu.berkeley.cs.uciedigital.tilelink.GenUcieHeader.\n"
    )
    sb.append("// Regenerate via:\n")
    sb.append(
      "//   ./mill ucie.runMain edu.berkeley.cs.uciedigital.tilelink.GenUcieHeader <path>\n"
    )
    sb.append("// DO NOT EDIT.\n\n")
    sb.append("#ifndef __UCIE_H__\n")
    sb.append("#define __UCIE_H__\n\n")
    sb.append("#include <stdint.h>\n")
    sb.append("#include <assert.h>\n")
    sb.append("#include \"mmio.h\"\n\n")
    sb.append(
      "// === Register offsets (relative to the UCIe MMIO base, e.g. 0x8000) ===\n"
    )
    sb.append(cg.formatRegs())
    sb.append("\n// === Constants ===\n")
    sb.append(cg.formatConstants())
    sb.append("\n// === Helper functions ===\n")
    sb.append(cg.formatResetFsmsFn())
    sb.append("\n")
    sb.append(cg.formatWriteTxctlFn())
    sb.append("\n")
    sb.append(cg.formatWriteRxctlFn())
    sb.append("\n")
    sb.append(cg.formatSetupUcieFn())
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
