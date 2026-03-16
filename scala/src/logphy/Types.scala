package edu.berkeley.cs.uciedigital.logphy

import chisel3._

class LinkOperationParameters extends Bundle {
  /*
    Clock Phase control at Transmitter
    h0: Clock PI Center
    h1: Left Edge
    h2: Right Edge
  */
  val clockPhase = Input(UInt(4.W))

  /*    
    Data Pattern (for Data Lanes)
    h0: LFSR
    h1: Per Lane ID    
  */
  val dataPattern = Input(UInt(3.W))

  /*
    Valid Pattern (for Valid Lanes)
    h0: Functional pattern (aka VALTRAIN)
  */
  val validPattern = Input(UInt(3.W))

  /*
    Pattern Mode
    0: Continuous Mode
      Continuous Mode: Uses Burst count to indicate the number
      of UI of transmission. Idle Count = 0, Iteration Count = 1

    1: Burst Mode
      Burst Mode: Uses Burst Count/Idle Count/Iteration Count
  */
  val patternMode = Input(UInt(1.W))

  /*
    See spec ver 3.0 page 127 in implementation notes for details
    Note: This isn't currently used with current implementation of PatternWriter 
    and PatternReader as per spec link operations send a fixed pattern.
  */
  val iterationCount = Input(UInt(16.W))
  val idleCount = Input(UInt(16.W))
  val burstCount = Input(UInt(16.W))

  /*
    Maximum comparison error threshold  
  */
  val maxErrorThreshold = Input(UInt(16.W))

  /*
    Comparison Mode 
    0: Per Lane
    1: Aggregate
  */
  val comparisonMode = Input(UInt(1.W))    
}

object ComparisonMode extends ChiselEnum {
  val PERLANE, AGGREGATE = Value
}