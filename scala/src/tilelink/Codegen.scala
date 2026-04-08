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
  TLTesterResp
}
import freechips.rocketchip.prci.{ClockSourceNode, ClockSourceParameters}

import edu.berkeley.cs.uciedigital.phy.{
  TestTarget,
  TxTestMode,
  DataMode,
  TxTestState
}
import edu.berkeley.cs.uciedigital.phy.macros.{DriverCtlIO, SkewCtlIO}

trait Formatter {}

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
}

class Codegen(f: SystemVerilogFormatter) {
  def formatWriteNamedReg(
      addrConst: String,
      value: String
  ): String = {
    f.formatWriteReg("regDrv", f.formatConstantRef(addrConst), value)
  }
  def formatRegs(): String = {
    implicit val p = Parameters.empty
    val ucie_dut = new RTLHarness(new UcieTL(UcieTLParams(), Seq(AddressSet(0x0, 0xffffL)), 32))
    val ucie = (new chisel3.stage.phases.Elaborate)
      .transform(Seq(chisel3.stage.ChiselGeneratorAnnotation { () =>
        val dut = LazyModule(ucie_dut).module
        dut
      }))
      .collectFirst { case a: DesignAnnotation[ucie_dut.Impl] => a.design }
      .get
    val sb = new StringBuilder

    // Maps the variable name to the first encountered index string.
    val varToIdx0 = mutable.Map[Seq[String], String]()
    // Maps the variable name to the address of its first entry.
    val varMapIdx0 = mutable.Map[Seq[String], Int]()
    // Maps the variable name to the address of its second entry.
    val varMapIdx1 = mutable.Map[Seq[String], Int]()

    def isNumber(s: String): Boolean = s.forall(_.isDigit)
    for (case (addr, reg) <- ucie.regmap) {
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
        ("defaultClkP", BigInt(0x55555555)),
        ("defaultClkN", BigInt(0xaaaaaaaa)),
        ("defaultValid", BigInt(0x0f0f0f0f)),
        ("defaultTrack", BigInt(0x55555555)),
        (
          "enableDriverCtl",
          (new DriverCtlIO)
            .Lit(
              _.pu_ctl -> 63.U,
              _.pd_ctl -> 63.U,
              _.en -> true.B,
              _.en_b -> false.B
            )
            .litValue
        ),
        (
          "defaultSkewCtl",
          (new SkewCtlIO)
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
        )
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
    body.append(
      formatWriteNamedReg("pllBypassEn", f.formatLong(1))
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

  def formatTlSimpleLoopbackFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(f.formatFnCall("setup_ucie"))
    body.append(
      formatWriteNamedReg(
        "mainbandSel",
        f.formatLong(1)
      )
    )
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
    sb.append(f.formatFn("tl_simple", body.toString))
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
    sb.append(formatTlSimpleLoopbackFn())
    sb.toString
  }

  def formatAll(): String = {
    val sb = new StringBuilder
    sb.append(formatRegs())
    sb.append(formatConstants())
    sb.append(formatResetFsmsFn())
    sb.append(formatWriteTxctlFn())
    sb.append(formatSetupUcieFn())
    sb.toString
  }
}
