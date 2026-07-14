package edu.berkeley.cs.uciedigital.utils

object TestHelpers {
  def debug(printDebug: Boolean, message: String): Unit = {
    if (printDebug) {
      println(message)
    }
  }
}
