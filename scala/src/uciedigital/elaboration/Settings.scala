/*
  Firtool option sets. They change only how readable the emitted Verilog is.
 */

package edu.berkeley.cs.uciedigital.elaboration

object Settings {
  // No randomization blocks and no source-location comments.
  val stripped: Array[String] = Array(
    "-O=debug",
    "--disable-all-randomization",
    "--strip-debug-info",
    "--lowering-options=disallowLocalVariables"
  )

  // Keeps randomization and source locations in `@[...]`. Use it to read a
  // module back against its Scala.
  val withSourceLocations: Array[String] = Array(
    "-O=debug",
    "--lowering-options=disallowLocalVariables",
    "--lowering-options=locationInfoStyle=wrapInAtSquareBracket"
  )

  // `stripped` without `-O=debug`, for the D2D Adapter and Protocol Layer.
  val production: Array[String] = Array(
    "--disable-all-randomization",
    "--strip-debug-info",
    "--lowering-options=disallowLocalVariables"
  )
}
