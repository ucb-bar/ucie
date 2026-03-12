package edu.berkeley.cs.uciedigital

import os.Path
import java.nio.file.Paths
import svsim.verilator.Backend.CompilationSettings
import org.chipsalliance.cde.config.Parameters
import chisel3.RawModule
import circt.stage.ChiselStage
import edu.berkeley.cs.uciedigital.tilelink.SimTop

object Utils {
  val root = Path(
    Paths.get(sys.env("MILL_TEST_RESOURCE_DIR")).toAbsolutePath
  ) / os.up / os.up
  val buildRoot = root / "build"
  val xceliumDir = root / os.up / "xcelium"
  val probeFile = xceliumDir / "probe.tcl"

  val verilatorSettings =
    CompilationSettings.default
      .withDisableFatalExitOnWarnings(true)
      .withTiming(Some(CompilationSettings.Timing.TimingEnabled))
      .withTraceStyle(
        Some(
          svsim.verilator.Backend.CompilationSettings
            .TraceStyle(
              svsim.verilator.Backend.CompilationSettings.TraceKind.Vcd,
              traceUnderscore = true,
              maxArraySize = Some(2048),
              maxWidth = Some(2048),
              traceDepth = Some(2048)
            )
        )
      )

  def writeSourceFilesList(path: Path, sourceFiles: Seq[Path]) = {
    os.makeDir.all(path / os.up)
    os.write.over(path, sourceFiles.map(_.toString).mkString("\n"))
  }

  def writeVerilatorSimScript(
      path: Path,
      topModule: String,
      sourceFilesList: Path,
      incDirs: Seq[Path] = Seq.empty
  ) = {
    os.makeDir.all(path / os.up)
    os.write.over(
      path,
      s"""#!/bin/bash
set -ex -o pipefail
verilator \\
  --cc \\
  --exe \\
  --build \\
  --main \\
  -o ../simulation \\
  -j 0 \\
  --top-module ${topModule} \\
  --Mdir verilated-sources \\
  --assert \\
  --trace \\
  --timing \\
  --max-num-width 1048576 \\${incDirs
          .map(dir => s"\n  +incdir+$dir \\")
          .mkString("")}
  --vpi \\
  +define+layer$$Verification$$Assert$$Temporal \\
  +define+layer$$Verification$$Assume$$Temporal \\
  +define+layer$$Verification$$Cover$$Temporal \\
  -Wno-fatal \\
  -CFLAGS "$$CXXFLAGS -std=c++17" \\
  -LDFLAGS "$$LDFLAGS" \\
  -F ${sourceFilesList.toString} > >(tee -a verilator.out) 2> >(tee -a verilator.err >&2)
./simulation > >(tee -a simulation.out) 2> >(tee -a simulation.err >&2)
"""
    )
    path.toIO.setExecutable(true)
  }

  def writeXrunSimScript(
      path: Path,
      topModule: String,
      sourceFilesList: Path,
      incDirs: Seq[Path] = Seq.empty
  ) = {
    os.makeDir.all(path / os.up)
    os.write.over(
      path,
      s"""#!/bin/bash
set -ex -o pipefail
xrun \\
  -allowredefinition \\
  -dmsaoi \\
  -sv_ms \\
  -timescale 1ps/100fs \\
  -spectre_args "+preset=mx +mt=32 -ahdllint=warn" \\
  -access +rwc \\
  -top $topModule \\
  -input ${probeFile.toString} \\${incDirs
          .map(dir => s"\n  -incdir $dir \\")
          .mkString("")}
  -define layer$$Verification$$Assert$$Temporal \\
  -define layer$$Verification$$Assume$$Temporal \\
  -define layer$$Verification$$Cover$$Temporal \\
  -define RANDOMIZE_MEM_INIT -define RANDOMIZE_REG_INIT -define RANDOMIZE_GARBAGE_ASSIGN -define RANDOMIZE_INVALID_ASSIGN \\
  -f ${sourceFilesList.toString} \\
  > >(tee -a xrun.out) 2> >(tee -a xrun.err >&2)
"""
    )
    path.toIO.setExecutable(true)
  }

  /** Finds source files within a given source directory with the given file
    * extensions.
    */
  def getSourceFiles(
      sourceDir: Path,
      fileExtensions: Seq[String] = Seq(".v", ".sv", ".cc", ".vams")
  ): Seq[Path] = {
    os
      .walk(sourceDir)
      .filter(os.isFile)
      .filter(path => fileExtensions.exists(ext => path.last.endsWith(ext)))
  }

  def simulate[T <: RawModule](
      dut: => T,
      writeSimScript: (Path, String, Path, Seq[Path]) => Unit,
      workDir: Path
  )(implicit p: Parameters) = {
    val sourceDir = workDir / "src"
    os.remove.all(sourceDir)
    os.makeDir.all(sourceDir)
    val simDir = workDir / "sim"
    ChiselStage.emitSystemVerilogFile(
      dut,
      args = Array(
        "--target-dir",
        sourceDir.toString
      )
    )
    val sourceFiles = getSourceFiles(sourceDir)

    val sourceFilesList = simDir / "sourceFiles.F"
    val simScript = simDir / "simulate.sh"

    writeSourceFilesList(sourceFilesList, sourceFiles)

    writeSimScript(
      simScript,
      "SimTop",
      sourceFilesList,
      os.walk(sourceDir).filter(os.isDir) ++ Seq(sourceDir)
    )

    os.proc(
      "/bin/bash",
      simScript
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = simDir)
  }

}
