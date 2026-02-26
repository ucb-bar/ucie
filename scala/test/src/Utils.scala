package edu.berkeley.cs.uciedigital

import os.Path
import java.nio.file.Paths
import svsim.verilator.Backend.CompilationSettings

object Utils {
  val root = Path(
    Paths.get(sys.env("MILL_TEST_RESOURCE_DIR")).toAbsolutePath
  ) / os.up / os.up
  val buildRoot = root / "build"
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
              maxArraySize = Some(1024),
              maxWidth = Some(1024),
              traceDepth = Some(1024)
            )
        )
      )

}
