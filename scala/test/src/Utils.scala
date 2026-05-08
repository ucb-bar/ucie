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
  val verilogSrcDir = root / os.up / "verilog"
  val defaultVsrcDir = root / "resources" / "vsrc"
  val constants = verilogSrcDir / "constants.vams"
  val xceliumDir = root / os.up / "xcelium"
  val controlFile = xceliumDir / "amscf.scs"
  val probeFile = xceliumDir / "probe.tcl"

  // root is iris/ucie/scala; the software dir lives at iris/ucie/software.
  val softwareDir = root / os.up / "software"

  def writeRocketVcsSimScript(
      path: Path,
      topModule: String,
      sourceFilesList: Path,
      incDirs: Seq[Path] = Seq.empty,
      loadmem: Option[Path] = None,
      debug: Boolean = false
  ): Unit = {
    val dramsim_ini = root / os.up / os.up / "testchipip" / "src" / "main" / "resources" / "dramsim2_ini"
    val dramsim2 = root / os.up / os.up / os.up / "DRAMSim2"
    val debugCompileFlags =
      if (debug) " +define+DEBUG -debug_access+all -kdb -lca" else ""
    val debugRuntimeFlag =
      if (debug) " +fsdbfile=waveform.fsdb" else ""
    os.makeDir.all(path / os.up)
    os.write.over(
      path,
      s"""#!/bin/bash
set -ex -o pipefail
vcs \\
  -full64 -j16 -fgp \\
  -CFLAGS "$$CXXFLAGS -O3 -std=c++17 -I$$RISCV/include -I${dramsim2.toString}" \\
  -LDFLAGS "$$LDFLAGS -L$$RISCV/lib -Wl,-rpath,$$RISCV/lib" \\
  -lriscv -lfesvr -ldramsim \\
  -notice -line +lint=all,noVCDE,noONGS,noUI -error=PCWM-L -error=noZMMCM \\
  -timescale=1ns/10ps -quiet -q +rad +vcs+lic+wait +vc+list \\
  -f ${sourceFilesList.toString} -sverilog +systemverilogext+.sv+.svi+.svh+.svt -assert svaext +libext+.sv +v2k +verilog2001ext+.v95+.vt+.vp +libext+.v \\
  -debug_pp \\
  -top $topModule \\${incDirs.map(dir => s"\n  +incdir+$dir \\").mkString("")}
  +define+layer$$Verification$$Assert$$Temporal \\
  +define+layer$$Verification$$Assume$$Temporal \\
  +define+layer$$Verification$$Cover$$Temporal \\
  +define+VCS +define+FSDB +define+RANDOMIZE_MEM_INIT +define+RANDOMIZE_REG_INIT +define+RANDOMIZE_GARBAGE_ASSIGN +define+RANDOMIZE_INVALID_ASSIGN$debugCompileFlags \\
  -o simulation -Mdir=vcs-sources
script -f -c "./simulation +permissive +dramsim +dramsim_ini_dir=${dramsim_ini.toString}${loadmem.map(p => s" +loadmem=${p.toString}").getOrElse("")}$debugRuntimeFlag +permissive-off placeholder-binary </dev/null 2> >(spike-dasm > simulation.out)" simulation.log
"""
    )
    path.toIO.setExecutable(true)
  }

  /** Elaborate a `dut` and run it under VCS, loading the given RISC-V binary
    * via TSI (or `+loadmem` when `fast` is true). Set `debug = true` to dump
    * an FSDB at `<workDir>/sim/waveform.fsdb`.
    */
  def simulateTopWithBinary[T <: RawModule](
      dut: => T,
      workDir: Path,
      binaryPath: Path,
      fast: Boolean = false,
      debug: Boolean = false
  )(implicit p: Parameters): Unit = {
    assert(
      os.exists(binaryPath),
      s"The provided binary $binaryPath does not exist. Run `make` in $softwareDir to build it first."
    )
    os.remove.all(workDir)
    os.makeDir.all(workDir)
    val sourceDir = workDir / "src"
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

    writeRocketVcsSimScript(
      simScript,
      "SimTop",
      sourceFilesList,
      incDirs = os.walk(sourceDir).filter(os.isDir) ++ Seq(sourceDir),
      loadmem = if (fast) Some(binaryPath) else None,
      debug = debug,
    )

    os.proc(
      "/bin/bash",
      simScript,
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = simDir)
  }

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
  -CFLAGS "$${CXXFLAGS:- } -std=c++17" \\
  -LDFLAGS "$${LDFLAGS:- }" \\
  -F ${sourceFilesList.toString} > >(tee -a verilator.out) 2> >(tee -a verilator.err >&2)
./simulation > >(tee -a simulation.out) 2> >(tee -a simulation.err >&2)
"""
    )
    path.toIO.setExecutable(true)
  }

  def writeVcsSimScript(
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
vcs \\
  -full64 -j16 -fgp \\
  -CFLAGS "$$CXXFLAGS -std=c++17" \\
  -LDFLAGS "$$LDFLAGS" \\
  -notice -line +lint=all,noVCDE,noONGS,noUI -error=PCWM-L -error=noZMMCM \\
  -timescale=1ps/100fs -quiet -q +rad +vcs+lic+wait +vc+list \\
  -f ${sourceFilesList.toString} -sverilog +systemverilogext+.sv+.svi+.svh+.svt -assert svaext +libext+.sv +v2k +verilog2001ext+.v95+.vt+.vp +libext+.v \\
  -debug_access+all -kdb -lca \\
  -top $topModule \\${incDirs.map(dir => s"\n  +incdir+$dir \\").mkString("")}
  +define+layer$$Verification$$Assert$$Temporal \\
  +define+layer$$Verification$$Assume$$Temporal \\
  +define+layer$$Verification$$Cover$$Temporal \\
  +define+VCS +define+FSDB +define+RANDOMIZE_MEM_INIT +define+RANDOMIZE_REG_INIT +define+RANDOMIZE_GARBAGE_ASSIGN +define+RANDOMIZE_INVALID_ASSIGN \\
  -o simulation -Mdir=vcs-sources > >(tee -a vcs.out) 2> >(tee -a vcs.err >&2)
./simulation +fsdbfile=waveform.fsdb > >(tee -a simulation.out) 2> >(tee -a simulation.err >&2)
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
      workDir: Path,
      includeVamsModels: Boolean = false
  )(implicit p: Parameters) = {
    val sourceDir = workDir / "src"
    os.remove.all(sourceDir)
    os.makeDir.all(sourceDir)
    val simDir = workDir / "sim"
    var topModule: String = "SimTop"
    ChiselStage.emitSystemVerilogFile(
      {
        val d = dut
        topModule = d.getClass.getSimpleName
        d
      },
      args = Array(
        "--target-dir",
        sourceDir.toString
      )
    )
    val sourceFiles = getSourceFiles(sourceDir) ++ {
      if (includeVamsModels) {
        val xceliumHome = Path(sys.env("XCELIUM_HOME"))
        val disciplines =
          xceliumHome / "tools.lnx86/spectre/etc/ahdl/disciplines.vams"
        val constants =
          xceliumHome / "tools.lnx86/spectre/etc/ahdl/constants.vams"
        val defaultModels = Seq("ucie_clk_dist_network.sv", "ucie_clk_div4.v", "ucie_clk_gate.sv", "ucie_clkmux.v", "ucie_clkrx.v", "ucie_esd.v", "ucie_esd_routable.v", "ucie_pll.v", "ucie_rst_sync.v")
        Seq(
          disciplines,
          constants,
          controlFile,
          Utils.constants,
        ) ++ getSourceFiles(verilogSrcDir) ++
          defaultModels.map(module => defaultVsrcDir / module)
      } else { Seq.empty }
    }

    val sourceFilesList = simDir / "sourceFiles.F"
    val simScript = simDir / "simulate.sh"

    writeSourceFilesList(sourceFilesList, sourceFiles)

    writeSimScript(
      simScript,
      topModule,
      sourceFilesList,
      os.walk(sourceDir).filter(os.isDir) ++ Seq(sourceDir) ++ {
        if (includeVamsModels) {
          Seq(
            verilogSrcDir
          )
        } else { Seq.empty }
      }
    )

    os.proc(
      "/bin/bash",
      simScript
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = simDir)
  }

}
