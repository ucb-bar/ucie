package edu.berkeley.cs.uciedigital.interfaces

import chisel3._

/** An I/O Bundle containing `valid`, `ready`, and `irdy` signals that handshake
  * the transfer of data stored in the 'bits' subfield.
  *
  * The base protocol implied by the directionality is that the producer uses
  * the interface as-is (outputs bits) while the consumer uses the flipped
  * interface (inputs bits). The actual semantics of ready/valid are enforced
  * via the use of concrete subclasses.
  * @param gen
  *   the type of data to be wrapped in Ready/Valid
  * @groupdesc Signals
  *   The actual hardware fields of the Bundle
  */
abstract class ReadyValid3IO[+T <: Data](gen: T) extends Bundle {

  /** Indicates that the consumer is ready to accept the data this cycle
    * ("target ready")
    * @group Signals
    */
  val ready = Input(Bool())

  /** Indicates that the producer has put valid data in 'bits'
    * @group Signals
    */
  val valid = Output(Bool())

  /** Indicates that the producer wants the consumer to sample the data
    * ("initiator ready")
    * @group Signals
    */
  val irdy = Output(Bool())

  /** The data to be transferred when ready, irdy, and valid are asserted at the
    * same cycle
    * @group Signals
    */
  val bits = Output(gen)
}

object ReadyValid3IO {

  implicit class AddMethodsToReadyValid3[T <: Data](target: ReadyValid3IO[T]) {

    /** Indicates if IO is both ready, irdy, and valid
      */
    def fire: Bool = target.ready && target.irdy && target.valid

    @deprecated(
      "Calling this function with an empty argument list is invalid in Scala 3. Use the form without parentheses instead",
      "Chisel 3.5",
    )
    def fire(dummy: Int = 0): Bool = fire

    /** Push dat onto the output bits of this interface to let the consumer know
      * it has happened.
      * @param dat
      *   the values to assign to bits.
      * @return
      *   dat.
      */
    def enq(dat: T): T = {
      target.valid := true.B
      target.bits := dat
      dat
    }

    /** Indicate no enqueue occurs. Valid is set to false, and bits are
      * connected to an uninitialized wire.
      */
    def noenq(): Unit = {
      target.valid := false.B
      target.bits := DontCare
    }

    /** Assert ready on this port and return the associated data bits. This is
      * typically used when valid has been asserted by the producer side.
      * @return
      *   The data bits.
      */
    def deq(): T = {
      target.ready := true.B
      target.bits
    }

    /** Indicate no dequeue occurs. Ready is set to false.
      */
    def nodeq(): Unit = {
      target.ready := false.B
    }
  }
}

/** A concrete subclass of ReadyValid3IO signaling that the user expects a
  * "decoupled" interface: 'valid' indicates that the producer has put valid
  * data in 'bits', and 'ready' indicates that the consumer is ready to accept
  * the data this cycle. No requirements are placed on the signaling of ready or
  * valid.
  * @param gen
  *   the type of data to be wrapped in Decoupled3IO
  */
class Decoupled3IO[+T <: Data](gen: T) extends ReadyValid3IO[T](gen) {

  /** Applies the supplied functor to the bits of this interface, returning a
    * new typed Decoupled3IO interface.
    * @param f
    *   The function to apply to this Decoupled3IO's 'bits' with return type B
    * @return
    *   a new Decoupled3IO of type B
    */
  def map[B <: Data](f: T => B): Decoupled3IO[B] = {
    val _map_bits = f(bits)
    val _map = Wire(Decoupled3(chiselTypeOf(_map_bits)))
    _map.bits := _map_bits
    _map.valid := valid
    _map.irdy := irdy
    ready := _map.ready
    _map
  }
}

/** This factory adds a decoupled handshaking protocol to a data bundle. */
object Decoupled3 {

  /** Wraps some Data with a Decoupled3IO interface. */
  def apply[T <: Data](gen: T): Decoupled3IO[T] = new Decoupled3IO(gen)

  private final class EmptyBundle extends Bundle

  /** Returns a [[Decoupled3IO]] inteface with no payload */
  def apply(): Decoupled3IO[Data] = apply(new EmptyBundle)

  /** Returns a [[Decoupled3IO]] inteface with no payload */
  def empty: Decoupled3IO[Data] = Decoupled3()
}