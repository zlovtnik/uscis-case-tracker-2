package uscis

import cats.effect.{IO, IOApp, ExitCode, Resource}
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.slf4j.bridge.SLF4JBridgeHandler
import uscis.server.GrpcServer

/**
 * USCIS Case Tracker gRPC Server.
 * 
 * A functional gRPC service for tracking USCIS case statuses.
 * Uses Cats Effect for pure functional effect management.
 * 
 * The server runs until interrupted (SIGINT/SIGTERM).
 */
object Main extends IOApp {

  // Initialize JUL-to-SLF4J bridge before anything else
  // This routes gRPC/Netty's java.util.logging through Logback
  private def initLoggingBridge: IO[Unit] = IO.delay {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val program = for {
      _      <- initLoggingBridge
      logger <- Slf4jLogger.create[IO]
      config <- GrpcServer.loadConfig
      _      <- logger.info("=" * 60)
      _      <- logger.info("USCIS Case Tracker gRPC Server v0.1.0")
      _      <- logger.info("=" * 60)
      _      <- logger.info(s"Starting server on ${config.host}:${config.port}")
      result <- GrpcServer.resourceWithPersistence
                  .use { server =>
                    for {
                      _ <- logger.info("Server is ready to accept connections")
                      _ <- logger.info("Press Ctrl+C to shutdown")
                      _ <- IO.never[Unit] // Keep running until interrupted
                    } yield ExitCode.Success
                  }
    } yield result

    program.handleErrorWith { error =>
      Slf4jLogger.create[IO]
        .flatMap(_.error(error)(s"Server failed: ${error.getMessage}"))
        .handleErrorWith(_ => IO(println(s"Server failed: ${error.getMessage}"))) *>
      IO.pure(ExitCode.Error)
    }
  }
}
