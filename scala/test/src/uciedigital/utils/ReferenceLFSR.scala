package edu.berkeley.cs.uciedigital.utils

class ReferenceLFSR(initSeed: BigInt, poly: BigInt, width: Int) {
  /*
    The polynomial argument corresponds to the taps.
      !!! Forgo the leading term bit in polynomial !!!
      Ex: If polynomial is G(X) = X^3 + X^2 + 1
          Polynomial in binary: 1101 (X^3, X^2, X, 1)
          The value used for polynomial will be: 0x5, NOT 0xD
   */
  require(width > 0, "ReferenceLFSR requires a positive width")

  private val polynomial = poly
  private val mask = (BigInt(1) << width) - 1
  private var state = initSeed
  private var seed = initSeed

  def getState(): BigInt = state

  def getStateHex(): String = state.toString(16).toUpperCase

  def getStateBitStr(): String =
    state.toString(2).reverse.padTo(width, '0').reverse

  def reset(): Unit = {
    state = seed
  }

  def reseed(newSeed: BigInt): Unit = {
    state = newSeed
    seed = newSeed
  }

  def getMsb(): BigInt = {
    (state >> (width - 1)) & 1
  }

  def peekOutputWord(numBits: Int): BigInt = {
    var nextState = state
    var word = BigInt(0)

    for (bit <- 0 until numBits) {
      val msb = (nextState >> (width - 1)) & 1
      word |= msb << bit

      nextState = (nextState << 1) & mask
      if (msb == 1) {
        nextState = nextState ^ polynomial
      }
    }

    word
  }

  def advanceState(numBits: Int): Unit = {
    for (_ <- 0 until numBits) {
      increment()
    }
  }

  def increment(): BigInt = {
    // Shifts out the MSB.
    val msb = getMsb()
    state = (state << 1) & mask
    if (msb == 1) {
      state = state ^ polynomial
    }
    msb
  }
}
