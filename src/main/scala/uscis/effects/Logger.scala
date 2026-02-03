package uscis.effects

import cats.effect.IO
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Logging utilities for the USCIS Case Tracker gRPC Service.
 * 
 * Uses log4cats with SLF4J backend for structured logging.
 */
object Logging {

  /** Get a logger instance for a specific class */
  def logger[F[_]: Logger]: Logger[F] = Logger[F]

  /** Create an IO-based logger */
  def ioLogger: IO[Logger[IO]] = Slf4jLogger.create[IO]

  /** Create a named logger */
  def named(name: String): IO[Logger[IO]] = 
    Slf4jLogger.fromName[IO](name)

  /** Scoped logger for a specific component */
  def forComponent(component: String): IO[Logger[IO]] =
    Slf4jLogger.fromName[IO](s"uscis.$component")
}
