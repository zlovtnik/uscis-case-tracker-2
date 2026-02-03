package uscis

import cats.effect.{IO, Resource}
import cats.syntax.all._
import scala.util.Try
import scala.io.Source

/**
 * Core effect types and utilities for the USCIS Case Tracker gRPC Service.
 * 
 * This package provides:
 * - Type aliases for common effect patterns (Result[A], IOResult[A])
 * - Conversions between Try/Either and IO
 * - Resource management utilities for file I/O
 * - Syntax extensions via implicit classes:
 *   - `TryOps[A]`: Adds `.toIO` method to `Try[A]` for converting to `IO[A]`
 *   - `EitherOps[A]`: Adds `.toIO` method to `Either[String, A]` for converting to `IO[A]`
 * 
 * Usage:
 * {{{import uscis.effects._
 * val result: Try[Int] = Success(42)
 * val io: IO[Int] = result.toIO  // via TryOps
 * 
 * val either: Either[String, Int] = Right(42)  
 * val io2: IO[Int] = either.toIO  // via EitherOps
 * }}}
 */
package object effects {

  // ============================================================
  // Type Aliases
  // ============================================================

  /** Result type for operations that can fail with an error message */
  type Result[A] = Either[String, A]

  /** IO-wrapped result for fallible operations */
  type IOResult[A] = IO[Either[String, A]]

  // ============================================================
  // IO Conversions (delegate to syntax extensions)
  // ============================================================

  /** Convert a Try to an IO, raising any failure as an exception.
   *  @see TryOps.toIO for the extension method version
   */
  def fromTry[A](t: => Try[A]): IO[A] = t.toIO

  /** Convert an Either to an IO, raising Left as an exception */
  def fromEither[A](e: => Either[Throwable, A]): IO[A] = IO.fromEither(e)

  /** Convert an Either[String, A] to IO, raising Left as RuntimeException.
   *  @see EitherOps.toIO for the extension method version
   */
  def fromResult[A](e: => Either[String, A]): IO[A] = e.toIO

  /** Delay an effect - alias for IO.delay */
  def delay[A](thunk: => A): IO[A] = IO.delay(thunk)

  /** Convert a Try-returning function to IO.
   *  @see TryOps.toIO for the extension method version
   */
  def liftTry[A](thunk: => Try[A]): IO[A] = IO.defer(thunk.toIO)

  // ============================================================
  // Resource Management
  // ============================================================

  /** Create a resource from a Source that auto-closes */
  def sourceResource(path: String): Resource[IO, Source] =
    Resource.make(
      IO.blocking(Source.fromFile(path, "UTF-8"))
    )(source => IO.blocking(source.close()))

  /** Create a resource from a Source from file path */
  def fileSourceResource(path: java.nio.file.Path): Resource[IO, Source] =
    Resource.make(
      IO.blocking(Source.fromFile(path.toFile, "UTF-8"))
    )(source => IO.blocking(source.close()))

  // ============================================================
  // Syntax Extensions
  // ============================================================

  implicit class TryOps[A](private val t: Try[A]) extends AnyVal {
    /** Convert this Try to an IO */
    def toIO: IO[A] = IO.fromTry(t)
  }

  implicit class EitherOps[A](private val e: Either[String, A]) extends AnyVal {
    /** Convert this Either[String, A] to an IO */
    def toIO: IO[A] = IO.fromEither(e.leftMap(new RuntimeException(_)))
  }
}
