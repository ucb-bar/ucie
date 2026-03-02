package edu.berkeley.cs.uciedigital.tilelink

import freechips.rocketchip.regmapper.RegField
import org.chipsalliance.cde.config.Parameters
import chisel3._
import org.chipsalliance.diplomacy.lazymodule._
import chisel3.stage.DesignAnnotation
import edu.berkeley.cs.chippy.{
  TLTesterParams,
  TLTester,
  TLTesterIO,
  TLTesterReq,
  TLTesterResp
}
import freechips.rocketchip.prci.ClockSourceNode
import freechips.rocketchip.prci.ClockSourceParameters
import scala.collection.mutable
import edu.berkeley.cs.uciedigital.phy.TxTestState

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
  def formatFn(name: String, body: String, args: Seq[Arg] = Seq.empty) = {
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
  def formatFnCall(name: String, args: Seq[String] = Seq.empty) = {
    s"$name(${args.mkString(", ")});\n"
  }
  def formatForLoop(loopVar: String, length: Int, body: String) = {
    s"""for (int $loopVar = 0; $loopVar < $length; $loopVar++) begin
${Codegen.indent(body)}
end
"""
  }
  def formatConstantRef(name: String): String = {
    s"`${getConstantName(name)}"
  }
  def formatWriteReg(addr: String, value: String) = {
    s"write_ucie($addr, $value);\n"
  }
  def formatAssertEq(addr: String, value: String) = {
    f"expect_ucie($addr, $value);\n"
  }
  def formatLong(value: Long): String = {
    f"64'h$value%X"
  }
  def formatDefine(name: String, value: String): String = {
    f"`define ${getConstantName(name)} $value\n"
  }
}

object UcieCodegenRef {
  val tltParams = TLTesterParams(addrWidth = 64, dataWidth = 64)
  val ucieParams = UcieTLParams(sim = true)
  val beatBytes = 8
}

class UcieCodegenRef(implicit p: Parameters) extends LazyModule {
  val tlt = LazyModule(
    new TLTester(UcieCodegenRef.tltParams, UcieCodegenRef.beatBytes)
  )
  val ucieTL = LazyModule(
    new UcieTL(UcieCodegenRef.ucieParams, UcieCodegenRef.beatBytes)
  )
  val clockSourceNode_digital = ClockSourceNode(
    Seq(ClockSourceParameters())
  )

  ucieTL.clockNode := clockSourceNode_digital
  ucieTL.node := tlt.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    tlt.module.io := DontCare
    ucieTL.module.io := DontCare

    val regmap = ucieTL.module.regmap

    clockSourceNode_digital.out(0)._1.clock := clock
    clockSourceNode_digital.out(0)._1.reset := reset
  }
}

object Codegen {
  def indent(content: String, n: Int = 1): String = {
    content.split("\n").map(line => s"${"  " * n}$line").mkString("\n")
  }
}

class Codegen(f: SystemVerilogFormatter) {
  def formatRegs(): String = {
    implicit val p = Parameters.empty
    val ucie_dut = new UcieCodegenRef
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
      case (name, value) <- Seq(("txTestStateIdle", TxTestState.idle.litValue))
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
      f.formatWriteReg(f.formatConstantRef("txFsmRst"), f.formatLong(1))
    )
    body.append(
      f.formatWriteReg(f.formatConstantRef("txFsmRst"), f.formatLong(1))
    )
    body.append(
      f.formatAssertEq(
        f.formatConstantRef("txTestState"),
        f.formatConstantRef("txTestStateIdle")
      )
    )
    body.append(
      f.formatAssertEq(f.formatConstantRef("txPacketsSent"), f.formatLong(0))
    )
    f.formatFn("reset_fsms", body.toString)
  }

  def formatWriteTxctlFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(
      f.formatWriteReg(
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

  def formatResetUcieFn(): String = {
    val sb = new StringBuilder
    val body = new StringBuilder
    body.append(f.formatFnCall("reset_fsms"))

    val loopBody = new StringBuilder
    loopBody.append(
      f.formatFnCall(
        "write_txctl",
        args =
          Seq("lane", f.formatConstantRef("txctlDllResetOfs"), f.formatLong(0))
      )
    )
    body.append(f.formatForLoop("lane", 21, loopBody.toString))
    sb.append(f.formatFn("reset_ucie", body.toString))
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
    sb.append(formatResetUcieFn())
    sb.toString
  }

  def formatAll(): String = {
    val sb = new StringBuilder
    sb.append(formatRegs())
    sb.append(formatConstants())
    sb.append(formatResetFsmsFn())
    sb.append(formatWriteTxctlFn())
    sb.append(formatResetUcieFn())
    sb.toString
  }
}
