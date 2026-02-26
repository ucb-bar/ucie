package edu.berkeley.cs.uciedigital

import os.Path
import java.nio.file.Paths

object Utils {
  val root = Path(
    Paths.get(sys.env("MILL_TEST_RESOURCE_DIR")).toAbsolutePath
  ) / os.up / os.up
  val buildRoot = root / "build"
}
