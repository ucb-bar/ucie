package edu.berkeley.cs.uciedigital.d2dadapter

import scala.collection.mutable

final class Scoreboard(
  expectedQ: mutable.Queue[AcceptedBeat],
  checkStreamId: Boolean
) {
  var observedOutputCount: Long = 0L
  var mismatchCount: Long = 0L
  var extraOutputCount: Long = 0L

  private val firstIssues = mutable.ArrayBuffer.empty[String]
  private val maxIssuesToPrint = 8

  private def pushIssue(msg: String): Unit = {
    if (firstIssues.size < maxIssuesToPrint) firstIssues += msg
  }

  private def fmtHex(v: BigInt): String = s"0x${v.toString(16)}"
  private def fmtStream(v: Int): String = {
    if (v == RawStreamIds.UnknownStreamId) "UNMAPPED"
    else f"0x$v%x"
  }

  def onObserved(obs: AcceptedBeat): Unit = {
    observedOutputCount += 1

    if (expectedQ.isEmpty) {
      mismatchCount += 1
      extraOutputCount += 1
      pushIssue(
        s"EXTRA cycle=${obs.cycleAccepted} expSeq=NONE obsSeq=${obs.seq} " +
          s"expData=NONE obsData=${fmtHex(obs.data)} " +
          s"expStream=NONE obsStream=${fmtStream(obs.streamId)}"
      )
      return
    }

    val exp = expectedQ.dequeue()
    val dataOk = exp.data == obs.data
    val streamOk =
      if (!checkStreamId) true
      else obs.streamId != RawStreamIds.UnknownStreamId && exp.streamId == obs.streamId

    if (!dataOk || !streamOk) {
      mismatchCount += 1
      pushIssue(
        s"MISMATCH expCycle=${exp.cycleAccepted} obsCycle=${obs.cycleAccepted} " +
          s"expSeq=${exp.seq} obsSeq=${obs.seq} " +
          s"expData=${fmtHex(exp.data)} obsData=${fmtHex(obs.data)} " +
          s"expStream=${fmtStream(exp.streamId)} obsStream=${fmtStream(obs.streamId)} " +
          s"streamCheck=${if (checkStreamId) "on" else "off"}"
      )
    }
  }

  def finishAndAssert(acceptedInputCount: Long, maxExpectedQueueDepth: Option[Int] = None): Unit = {
    val missingOutputCount = expectedQ.size
    if (missingOutputCount > 0) {
      mismatchCount += missingOutputCount
      expectedQ.take(maxIssuesToPrint).foreach { exp =>
        pushIssue(
          s"MISSING expCycle=${exp.cycleAccepted} expSeq=${exp.seq} obsSeq=NONE " +
            s"expData=${fmtHex(exp.data)} obsData=NONE " +
            s"expStream=${fmtStream(exp.streamId)} obsStream=NONE"
        )
      }
    }

    val depthStr = maxExpectedQueueDepth.map(v => s" max_expected_queue_depth=$v").getOrElse("")
    firstIssues.foreach(msg => println(s"[TEST][DETAIL] $msg"))

    assert(extraOutputCount == 0L, s"Observed $extraOutputCount extra output beats")
    assert(missingOutputCount == 0, s"Missing $missingOutputCount output beats")
    assert(mismatchCount == 0L, s"Observed $mismatchCount mismatches")
  }
}
