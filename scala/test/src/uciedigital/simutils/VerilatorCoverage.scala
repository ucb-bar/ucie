package edu.berkeley.cs.uciedigital.simutils

import chisel3.simulator.scalatest.ChiselSim
import chisel3.simulator.scalatest.HasCliOptions.CliOption

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import scala.sys.process.Process

trait VerilatorCoverage { this: ChiselSim =>
  private def verilatorCoverageSettings =
    new svsim.verilator.Backend.CompilationSettings.CoverageSettings(true, true, true)

  addOption(
    CliOption.flag(
      name = "emitCoverage",
      help = "enables Verilator line/toggle/user coverage and writes coverage reports after each test",
      updateBackendSettings = {
        case options: svsim.verilator.Backend.CompilationSettings =>
          options.withCoverageSettings(verilatorCoverageSettings)
        case options => options
      }
    )
  )

  addOption(
    CliOption.flag(
      name = "emitCoveragePoints",
      help = "enables Verilator coverage and also writes point-level annotated source output",
      updateBackendSettings = {
        case options: svsim.verilator.Backend.CompilationSettings =>
          options.withCoverageSettings(verilatorCoverageSettings)
        case options => options
      }
    )
  )

  addOption(
    CliOption.flag(
      name = "enableTiming",
      help = "enables Verilator timing support for temporal assertions or covers",
      updateBackendSettings = {
        case options: svsim.verilator.Backend.CompilationSettings =>
          options.withTiming(Some(svsim.verilator.Backend.CompilationSettings.Timing.TimingEnabled))
        case options => options
      }
    )
  )

  def emitCoverage: Boolean = getOption[Unit]("emitCoverage").nonEmpty
  def emitCoveragePoints: Boolean = getOption[Unit]("emitCoveragePoints").nonEmpty
  def coverageEnabled: Boolean = emitCoverage || emitCoveragePoints
  def enableTiming: Boolean = getOption[Unit]("enableTiming").nonEmpty
  def coverageDirectory: Path = implementation.getDirectory

  private def coverageDataFiles(dir: Path): Seq[Path] = {
    if(!Files.exists(dir)) Seq.empty
    else {
      val stream = Files.walk(dir)
      try {
        stream.iterator().asScala
          .filter(path => Files.isRegularFile(path) && path.getFileName.toString == "coverage.dat")
          .toSeq
      } finally {
        stream.close()
      }
    }
  }

  private def runCoverageCommand(name: String, cmd: Seq[String], dir: Path): Boolean = {
    val code = Process(cmd, dir.toFile).!
    if(code != 0) println(s"$name Verilator coverage command failed: ${cmd.mkString(" ")}")
    code == 0
  }

  def writeVerilatorCoverageReports(name: String): Unit = {
    if(coverageEnabled) {
      val dir = coverageDirectory
      val dataFiles = coverageDataFiles(dir)
      if(dataFiles.isEmpty) {
        println(s"$name Verilator coverage: no coverage.dat found under $dir")
      } else {
        val reportDir = dir.resolve("verilator-coverage")
        val annotatedDir = reportDir.resolve("annotated")
        Files.createDirectories(annotatedDir)

        val info = reportDir.resolve("coverage.info")
        val dataArgs = dataFiles.map(_.toString)
        val wroteInfo = runCoverageCommand(name, Seq("verilator_coverage", "--write-info", info.toString) ++ dataArgs, dir)
        val wroteAnnotated = runCoverageCommand(name, Seq("verilator_coverage", "--annotate", annotatedDir.toString) ++ dataArgs, dir)
        val annotatedPointsText =
          if(emitCoveragePoints) {
            val annotatedPointsDir = reportDir.resolve("annotated-points")
            Files.createDirectories(annotatedPointsDir)
            val wroteAnnotatedPoints = runCoverageCommand(
              name,
              Seq("verilator_coverage", "--annotate-points", "--annotate", annotatedPointsDir.toString) ++ dataArgs,
              dir
            )
            if(wroteAnnotatedPoints) annotatedPointsDir.toString else "not written"
          } else {
            "off"
          }

        val infoText = if(wroteInfo) info.toString else "not written"
        val annotatedText = if(wroteAnnotated) annotatedDir.toString else "not written"
        println(
          s"$name Verilator coverage: dataFiles=${dataFiles.size} lcov=$infoText annotated=$annotatedText annotatedPoints=$annotatedPointsText"
        )
      }
    }
  }

}
